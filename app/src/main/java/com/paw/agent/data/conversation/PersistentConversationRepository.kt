package com.paw.agent.data.conversation

import android.content.Context
import com.paw.agent.core.model.Conversation
import com.paw.agent.core.model.Message
import com.paw.agent.core.model.MessageRole
import com.paw.agent.core.model.MessageStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64
import java.util.UUID

/**
 * 任务执行历史统计数据，用于历史查看、追溯及任务对比分析
 */
@Serializable
data class TaskExecutionStats(
    val conversationId: String,
    val title: String,
    val initialGoal: String,
    val stepCount: Int,
    val durationMs: Long,
    val status: MessageStatus,
    val toolCounts: Map<String, Int>,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 具备磁盘持久化、多会话归档、任务回溯与对比分析能力的会话仓库
 */
class PersistentConversationRepository(
    context: Context? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    storageDirectory: File? = null,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) : ConversationRepository {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val storageDir = (storageDirectory ?: File(context?.filesDir ?: File(System.getProperty("java.io.tmpdir"), "agentpaw-conversations"), "conversations")).apply { mkdirs() }
    private val imagesDir = File(storageDir, "images").apply { mkdirs() }
    private val mutex = Mutex()
    private val persistChannel = Channel<Conversation>(Channel.UNLIMITED)

    private val _conversation = MutableStateFlow(
        Conversation(
            id = UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        ),
    )
    override val conversation: StateFlow<Conversation> = _conversation.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    override val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _historyList = MutableStateFlow<List<Conversation>>(emptyList())
    val historyList: StateFlow<List<Conversation>> = _historyList.asStateFlow()

    init {
        scope.launch(ioDispatcher) {
            for (conv in persistChannel) {
                mutex.withLock {
                    saveToDiskLocked(conv)
                    updateHistoryListLocked(conv)
                }
            }
        }
        scope.launch(ioDispatcher) {
            loadAllFromDisk()
            _isInitialized.value = true
        }
    }

    /**
     * 将包含 Base64 原始图片的数据剥离并存入独立图片文件目录，
     * 仅在会话中记录本地 file:// 协议路径，避免 JSON 膨胀与内存爆满。
     * 使用原子写入机制防止文件写出一半损坏。
     */
    private fun extractImagesToDisk(message: Message, conversationId: String): Message {
        if (message.images.isEmpty()) return message
        val updatedImages = message.images.mapIndexed { index, img ->
            if (img.startsWith("file://") || img.startsWith("http://") || img.startsWith("https://")) {
                img
            } else {
                runCatching {
                    val raw = if (img.contains(",")) img.substringAfter(",") else img
                    val bytes = Base64.getDecoder().decode(raw)
                    val imgFile = File(imagesDir, "${conversationId}_${message.id}_$index.jpg")
                    val tempFile = File.createTempFile("tmp_img_", ".tmp", imagesDir)
                    tempFile.writeBytes(bytes)
                    if (!tempFile.renameTo(imgFile)) {
                        tempFile.copyTo(imgFile, overwrite = true)
                        tempFile.delete()
                    }
                    "file://${imgFile.absolutePath}"
                }.getOrDefault(img)
            }
        }
        return message.copy(images = updatedImages)
    }

    private suspend fun loadAllFromDisk() = mutex.withLock {
        withContext(ioDispatcher) {
            val files = storageDir.listFiles { _, name -> name.endsWith(".json") } ?: emptyArray()
            val loaded = files.mapNotNull { file ->
                runCatching {
                    val conv = json.decodeFromString<Conversation>(file.readText())
                    var modified = false
                    val cleanedMessages = conv.messages.map { msg ->
                        if (msg.images.any { !it.startsWith("file://") && !it.startsWith("http") }) {
                            modified = true
                            extractImagesToDisk(msg, conv.id)
                        } else msg
                    }
                    val cleanConv = if (modified) conv.copy(messages = cleanedMessages) else conv
                    if (modified) {
                        saveToDiskLocked(cleanConv)
                    }
                    cleanConv
                }.getOrNull()
            }.sortedByDescending { it.updatedAt }

            if (loaded.isNotEmpty()) {
                _historyList.value = loaded
                // 默认将最新的一条作为当前会话；但若用户在加载期间已发送了新消息，则予以保留绝不覆盖
                val latest = loaded.first()
                _conversation.update { current ->
                    if (current.messages.isNotEmpty()) current else latest
                }
            } else {
                // 初次创建并落盘空会话
                saveToDiskLocked(_conversation.value)
                _historyList.value = listOf(_conversation.value)
            }
        }
    }

    override fun newConversation(): String {
        val newId = UUID.randomUUID().toString()
        scope.launch {
            mutex.withLock {
                val now = System.currentTimeMillis()
                val newConv = Conversation(
                    id = newId,
                    createdAt = now,
                    updatedAt = now,
                )
                _conversation.value = newConv
                saveToDiskLocked(newConv)
                updateHistoryListLocked(newConv)
            }
        }
        return newId
    }

    /**
     * 回溯并载入历史任务记录，使其成为当前主活跃会话
     */
    fun loadConversation(id: String) {
        scope.launch {
            mutex.withLock {
                val target = _historyList.value.firstOrNull { it.id == id } ?: return@withLock
                _conversation.value = target
            }
        }
    }

    /**
     * 删除指定历史记录，并同步清理该任务关联的离线截图文件
     */
    fun deleteConversation(id: String) {
        scope.launch {
            mutex.withLock {
                val file = File(storageDir, "$id.json")
                if (file.exists()) file.delete()

                val imageFiles = imagesDir.listFiles { _, name -> name.startsWith("${id}_") } ?: emptyArray()
                imageFiles.forEach { it.delete() }

                _historyList.value = _historyList.value.filterNot { it.id == id }
                if (_conversation.value.id == id) {
                    val fallback = _historyList.value.firstOrNull() ?: Conversation(
                        id = UUID.randomUUID().toString(),
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                    )
                    _conversation.value = fallback
                    saveToDiskLocked(fallback)
                    updateHistoryListLocked(fallback)
                }
            }
        }
    }

    override fun addMessage(message: Message) {
        val hasRawImages = message.images.any { !it.startsWith("file://") && !it.startsWith("http") }
        if (!hasRawImages) {
            var nextConv: Conversation? = null
            _conversation.update { current ->
                val updated = current.copy(
                    messages = current.messages + message,
                    title = current.title.ifBlank { message.content.take(TITLE_MAX_CHARS) },
                    updatedAt = System.currentTimeMillis(),
                )
                nextConv = updated
                updated
            }
            nextConv?.let { persistAsync(it) }
        } else {
            // 先立即展示消息，避免阻塞调用方主线程
            _conversation.update { current ->
                current.copy(
                    messages = current.messages + message,
                    title = current.title.ifBlank { message.content.take(TITLE_MAX_CHARS) },
                    updatedAt = System.currentTimeMillis(),
                )
            }
            // 在 IO 调度器中异步处理图片解码与落盘
            scope.launch(ioDispatcher) {
                val currentConvId = _conversation.value.id
                val normalizedMessage = extractImagesToDisk(message, currentConvId)
                var nextConv: Conversation? = null
                _conversation.update { current ->
                    val updatedMessages = current.messages.map {
                        if (it.id == message.id) normalizedMessage else it
                    }
                    val updated = current.copy(messages = updatedMessages, updatedAt = System.currentTimeMillis())
                    nextConv = updated
                    updated
                }
                nextConv?.let { persistAsync(it) }
            }
        }
    }

    override fun updateMessage(id: String, transform: (Message) -> Message) {
        var transformedMessage: Message? = null
        _conversation.update { current ->
            val updated = current.copy(
                messages = current.messages.map {
                    if (it.id == id) {
                        val transformed = transform(it)
                        transformedMessage = transformed
                        transformed
                    } else it
                },
                updatedAt = System.currentTimeMillis(),
            )
            updated
        }

        val hasRawImages = transformedMessage?.images?.any { !it.startsWith("file://") && !it.startsWith("http") } == true
        if (!hasRawImages) {
            persistAsync(_conversation.value)
        } else {
            scope.launch(ioDispatcher) {
                val currentConvId = _conversation.value.id
                val targetMsg = transformedMessage ?: return@launch
                val normalized = extractImagesToDisk(targetMsg, currentConvId)
                var nextConv: Conversation? = null
                _conversation.update { current ->
                    val updated = current.copy(
                        messages = current.messages.map { if (it.id == id) normalized else it },
                        updatedAt = System.currentTimeMillis(),
                    )
                    nextConv = updated
                    updated
                }
                nextConv?.let { persistAsync(it) }
            }
        }
    }

    override fun appendToMessage(id: String, text: String) {
        if (text.isEmpty()) return
        updateMessage(id) { it.copy(content = it.content + text) }
    }

    override fun removeMessage(id: String) {
        var nextConv: Conversation? = null
        _conversation.update { current ->
            val updated = current.copy(
                messages = current.messages.filterNot { it.id == id },
                updatedAt = System.currentTimeMillis(),
            )
            nextConv = updated
            updated
        }
        nextConv?.let { persistAsync(it) }
    }

    override fun clear() {
        val now = System.currentTimeMillis()
        val currentConv = _conversation.value
        scope.launch(ioDispatcher) {
            val convImages = imagesDir.listFiles { _, name -> name.startsWith("${currentConv.id}_") } ?: emptyArray()
            convImages.forEach { it.delete() }
        }
        var nextConv: Conversation? = null
        _conversation.update { current ->
            val updated = current.copy(messages = emptyList(), title = "", updatedAt = now)
            nextConv = updated
            updated
        }
        nextConv?.let { persistAsync(it) }
    }

    /**
     * 清理所有历史缓存的屏幕截图媒体文件，释放存储空间
     */
    fun clearAllMedia() {
        scope.launch(ioDispatcher) {
            val allImages = imagesDir.listFiles() ?: emptyArray()
            allImages.forEach { it.delete() }
        }
    }

    private fun persistAsync(conv: Conversation) {
        persistChannel.trySend(conv)
    }

    private fun saveToDiskLocked(conv: Conversation) {
        runCatching {
            val file = File(storageDir, "${conv.id}.json")
            val tempFile = File.createTempFile("conv_", ".tmp", storageDir)
            tempFile.writeText(json.encodeToString(conv))
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
        }
    }

    private fun updateHistoryListLocked(conv: Conversation) {
        val current = _historyList.value.toMutableList()
        val index = current.indexOfFirst { it.id == conv.id }
        if (index >= 0) {
            current[index] = conv
        } else {
            current.add(0, conv)
        }
        _historyList.value = current.sortedByDescending { it.updatedAt }
    }

    companion object {
        const val TITLE_MAX_CHARS = 40

        /**
         * 提取任务执行元数据与统计指标，用于回溯和对比
         */
        fun computeStats(conv: Conversation): TaskExecutionStats {
            val initialGoal = conv.messages.firstOrNull { it.isUser }?.content ?: conv.title.ifBlank { "空任务" }
            val toolMessages = conv.messages.filter { it.role == MessageRole.TOOL }
            val structuredCalls = conv.messages.flatMap { it.toolCalls }
            val stepCount = if (structuredCalls.isNotEmpty()) structuredCalls.size else toolMessages.size
            val toolCounts = mutableMapOf<String, Int>()

            if (structuredCalls.isNotEmpty()) {
                structuredCalls.forEach { call ->
                    toolCounts[call.name] = (toolCounts[call.name] ?: 0) + 1
                }
            } else {
                toolMessages.forEach { msg ->
                    // 工具名称提取：从内容如 "✔ tapAtPixel: ..." 或 "⚙ 正在执行: tap ..."
                    val parts = msg.content.removePrefix("✔ ").removePrefix("❌ ").removePrefix("⚙ 正在执行: ").split(" ")
                    val toolName = parts.firstOrNull()?.removeSuffix(":") ?: "tool"
                    toolCounts[toolName] = (toolCounts[toolName] ?: 0) + 1
                }
            }

            val lastMsg = conv.messages.lastOrNull { it.isAssistant }
            val status = lastMsg?.status ?: if (conv.messages.isEmpty()) MessageStatus.COMPLETE else MessageStatus.COMPLETE

            val durationMs = if (conv.updatedAt >= conv.createdAt && conv.createdAt > 0L) {
                (conv.updatedAt - conv.createdAt).coerceAtLeast(1000L)
            } else {
                0L
            }

            return TaskExecutionStats(
                conversationId = conv.id,
                title = conv.title.ifBlank { initialGoal.take(20) },
                initialGoal = initialGoal,
                stepCount = stepCount,
                durationMs = durationMs,
                status = status,
                toolCounts = toolCounts,
                createdAt = conv.createdAt,
                updatedAt = conv.updatedAt,
            )
        }
    }
}
