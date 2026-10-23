package com.paw.agent.data.conversation

import android.content.Context
import com.paw.agent.core.model.Conversation
import com.paw.agent.core.model.Message
import com.paw.agent.core.model.MessageRole
import com.paw.agent.core.model.MessageStatus
import com.paw.agent.core.model.ConversationSummary
import com.paw.agent.core.io.BoundedIO
import kotlinx.coroutines.delay
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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

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
    internal val historyBodyReads = java.util.concurrent.atomic.AtomicInteger()
    internal val conversationWrites = java.util.concurrent.atomic.AtomicInteger()
    // 只合并同一会话的流式快照；全局 CONFLATED 会丢弃其他会话待保存的数据。
    private val persistChannel = Channel<Unit>(Channel.CONFLATED)
    private val pendingLock = Any()
    private val pendingConversations = LinkedHashMap<String, Conversation>()
    private val failedConversations = LinkedHashMap<String, Conversation>()
    private val savingIds = mutableSetOf<String>()
    private val savingConversations = LinkedHashMap<String, Conversation>()
    private val deletedIds = mutableSetOf<String>()
    private val _storageError = MutableStateFlow<String?>(null)
    val storageError: StateFlow<String?> = _storageError.asStateFlow()

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
    private val index = LinkedHashMap<String, HistoryEntry>()
    private val indexFile = File(storageDir, "history.index")
    private var visibleCount = MAX_HISTORY_ITEMS
    @Volatile private var userSelectedConversation = false
    private var mediaGeneration = 0L
    private val _allHistory = MutableStateFlow<List<Conversation>>(emptyList())
    val allHistorySummaries: StateFlow<List<Conversation>> = _allHistory.asStateFlow()
    private val _hasMoreHistory = MutableStateFlow(false)
    val hasMoreHistory: StateFlow<Boolean> = _hasMoreHistory.asStateFlow()

    @Serializable
    private data class HistoryEntry(val conversation: Conversation, val modifiedAt: Long, val size: Long)

    init {
        scope.launch(ioDispatcher) {
            for (signal in persistChannel) {
                // Bound disk writes during streaming; terminal UI updates are emitted separately.
                if (_conversation.value.isStreaming) delay(250)
                val (mediaVersion, pending) = mutex.withLock {
                    mediaGeneration to synchronized(pendingLock) {
                        pendingConversations.values.toList().also {
                            savingIds.addAll(pendingConversations.keys)
                            savingConversations.putAll(pendingConversations)
                            pendingConversations.clear()
                        }
                    }
                }
                for (conv in pending) {
                    mutex.withLock {
                        if (!synchronized(pendingLock) { conv.id in deletedIds }) {
                            val newer = synchronized(pendingLock) { pendingConversations.remove(conv.id) }
                            val snapshot = newer ?: if (mediaVersion != mediaGeneration) conv.copy(messages = conv.messages.map { it.copy(images = emptyList()) }) else conv
                            val saved = saveToDiskLocked(snapshot)
                            synchronized(pendingLock) {
                                if (saved) failedConversations.remove(conv.id) else failedConversations[conv.id] = snapshot
                                _storageError.value = if (failedConversations.isEmpty()) null else SAVE_ERROR_MESSAGE
                            }
                            updateHistoryListLocked(snapshot, saved)
                        }
                        synchronized(pendingLock) { savingIds.remove(conv.id); savingConversations.remove(conv.id) }
                    }
                }
                mutex.withLock { persistIndex() }
            }
        }
        scope.launch(ioDispatcher) {
            try {
                loadAllFromDisk()
            } catch (_: Exception) {
                _storageError.value = "无法读取部分历史记录，请检查存储空间后重试。"
            } finally {
                _isInitialized.value = true
            }
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
                    require(raw.length <= (BoundedIO.MAX_IMAGE_BYTES * 4 / 3 + 4)) { "Image exceeds size budget" }
                    val bytes = Base64.getDecoder().decode(raw)
                    val safeMessageId = UUID.nameUUIDFromBytes(message.id.toByteArray()).toString()
                    val imgFile = File(imagesDir, "${conversationId}_${safeMessageId}_$index.jpg")
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
            val cachedIndex = runCatching {
                json.decodeFromString<List<HistoryEntry>>(BoundedIO.readFile(indexFile)).associateBy { it.conversation.id }
            }.getOrDefault(emptyMap())
            val files = storageDir.listFiles { _, name -> name.endsWith(".json") } ?: emptyArray()
            files.forEach { file ->
                val cached = cachedIndex[file.nameWithoutExtension]
                if (cached != null && cached.conversation.id == file.nameWithoutExtension && validId(cached.conversation.id) && cached.conversation.messages.isEmpty() && cached.modifiedAt == file.lastModified() && cached.size == file.length()) {
                    index[file.nameWithoutExtension] = cached
                    return@forEach
                }
                runCatching {
                    val conv = readConversation(file)
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
                    updateIndex(cleanConv)
                }.getOrElse { e ->
                    runCatching {
                        val corruptFile = File(storageDir, "${file.nameWithoutExtension}_${System.currentTimeMillis()}.corrupt")
                        file.copyTo(corruptFile, overwrite = true)
                        file.delete()
                        cleanOldCorruptFiles(storageDir, maxCount = 5)
                    }
                }
            }

            if (index.isNotEmpty()) {
                refreshHistory()
                // 默认将最新的一条作为当前会话；但若用户在加载期间已发送了新消息，则予以保留绝不覆盖
                val latest = readConversation(File(storageDir, "${_historyList.value.first().id}.json"))
                _conversation.update { current ->
                    if (current.messages.isNotEmpty() || userSelectedConversation) current else latest
                }
            } else {
                // 初次创建并落盘空会话
                saveToDiskLocked(_conversation.value)
                updateIndex(_conversation.value)
                refreshHistory()
            }
            persistIndex()
        }
    }

    override fun newConversation(): String {
        userSelectedConversation = true
        val newId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val newConv = Conversation(id = newId, createdAt = now, updatedAt = now)
        _conversation.value = newConv
        persistAsync(newConv)
        return newId
    }

    /**
     * 回溯并载入历史任务记录，使其成为当前主活跃会话。
     * 同步立即可见更新内存状态，避免界面跳转时出现闪烁或时序竞态。
     */
    override suspend fun loadConversation(id: String): Boolean {
        val target = getConversation(id) ?: return false
        userSelectedConversation = true
        _conversation.value = target
        return true
    }

    suspend fun getConversation(id: String): Conversation? = withContext(ioDispatcher) {
        mutex.withLock {
            if (!validId(id) || synchronized(pendingLock) { id in deletedIds }) return@withLock null
            if (_conversation.value.id == id) return@withLock _conversation.value
            synchronized(pendingLock) { pendingConversations[id] ?: savingConversations[id] ?: failedConversations[id] }?.let { return@withLock it }
            runCatching { readConversation(File(storageDir, "$id.json")) }.getOrNull()
        }
    }

    fun loadMoreHistory() {
        scope.launch(ioDispatcher) { mutex.withLock { visibleCount += MAX_HISTORY_ITEMS; refreshHistory() } }
    }

    /**
     * 删除指定历史记录，并同步清理该任务关联的离线截图文件
     */
    fun deleteConversation(id: String) {
        if (_historyList.value.none { it.id == id } && _conversation.value.id != id) return
        synchronized(pendingLock) {
            deletedIds.add(id)
            pendingConversations.remove(id)
            failedConversations.remove(id)
        }
        scope.launch(ioDispatcher) {
            mutex.withLock {
                val file = File(storageDir, "$id.json")
                if (file.exists()) file.delete()

                val imageFiles = imagesDir.listFiles { _, name -> name.startsWith("${id}_") } ?: emptyArray()
                imageFiles.forEach { it.delete() }

                _historyList.value = _historyList.value.filterNot { it.id == id }
                index.remove(id)
                if (_conversation.value.id == id) {
                    val fallback = index.keys.firstOrNull()?.let { runCatching { readConversation(File(storageDir, "$it.json")) }.getOrNull() } ?: Conversation(
                        id = UUID.randomUUID().toString(),
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                    )
                    _conversation.value = fallback
                    saveToDiskLocked(fallback)
                    updateHistoryListLocked(fallback)
                }
                refreshHistory()
                persistIndex()
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
            var sourceConversation: Conversation? = null
            _conversation.update { current ->
                current.copy(
                    messages = current.messages + message,
                    title = current.title.ifBlank { message.content.take(TITLE_MAX_CHARS) },
                    updatedAt = System.currentTimeMillis(),
                ).also { sourceConversation = it }
            }
            val source = sourceConversation ?: return
            // 先保存原始图片数据，确保快速切换会话或进程被回收时不会丢失消息。
            persistAsync(source)
            // 在 IO 调度器中异步处理图片解码与落盘
            scope.launch(ioDispatcher) {
                if (_conversation.value.id != source.id) return@launch
                val normalizedMessage = extractImagesToDisk(message, source.id)
                var nextConv: Conversation? = null
                _conversation.update { current ->
                    if (current.id != source.id) return@update current
                    val updatedMessages = current.messages.map { existing ->
                        if (existing.id == message.id && existing.images == message.images) existing.copy(images = normalizedMessage.images) else existing
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
        var sourceConversationId: String? = null
        _conversation.update { current ->
            sourceConversationId = current.id
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
            // 保留当前会话的原始版本，避免异步图片处理前切换会话造成更新丢失。
            persistAsync(_conversation.value)
            scope.launch(ioDispatcher) {
                val currentConvId = sourceConversationId ?: return@launch
                if (_conversation.value.id != currentConvId) return@launch
                val targetMsg = transformedMessage ?: return@launch
                val normalized = extractImagesToDisk(targetMsg, currentConvId)
                var nextConv: Conversation? = null
                _conversation.update { current ->
                    if (current.id != currentConvId) return@update current
                    val updated = current.copy(
                        messages = current.messages.map { if (it.id == id && it.images == targetMsg.images) it.copy(images = normalized.images) else it },
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
    suspend fun clearAllMedia(): Boolean = withContext(ioDispatcher) {
            mutex.withLock {
                mediaGeneration++
                val allImages = imagesDir.listFiles().orEmpty()
                val ids = (index.keys + _conversation.value.id + synchronized(pendingLock) { pendingConversations.keys + savingConversations.keys }).toSet()
                var savedAll = true
                for (id in ids) {
                    val conv = if (_conversation.value.id == id) _conversation.value else
                        synchronized(pendingLock) { pendingConversations[id] ?: savingConversations[id] ?: failedConversations[id] }
                            ?: runCatching { readConversation(File(storageDir, "$id.json")) }.getOrNull()
                    if (conv == null) { savedAll = false; continue }
                    val cleared = conv.copy(messages = conv.messages.map { it.copy(images = emptyList()) })
                    if (!saveToDiskLocked(cleared)) { savedAll = false; continue }
                    _conversation.update { current ->
                        if (current.id == id) current.copy(messages = current.messages.map { it.copy(images = emptyList()) }) else current
                    }
                    synchronized(pendingLock) {
                        pendingConversations[id]?.let { pendingConversations[id] = it.copy(messages = it.messages.map { msg -> msg.copy(images = emptyList()) }) }
                        failedConversations.remove(id)
                    }
                    updateIndex(cleared)
                }
                if (savedAll) allImages.forEach { it.delete() }
                refreshHistory()
                persistIndex()
                _storageError.value = if (savedAll) null else "部分历史图片未能清理，请检查存储空间后重试。"
                savedAll
            }
    }

    private fun persistAsync(conv: Conversation) {
        synchronized(pendingLock) {
            if (conv.id in deletedIds) return
            pendingConversations[conv.id] = conv
        }
        persistChannel.trySend(Unit)
    }

    /** 由用户在腾出存储空间后重试；失败快照在当前进程内保留。 */
    fun retryFailedSaves() {
        synchronized(pendingLock) {
            failedConversations.forEach { (id, conv) ->
                // 新快照正在写入时，不得把旧失败快照重新排到它后面覆盖新内容。
                if (id !in deletedIds && id !in savingIds) pendingConversations.putIfAbsent(id, conv)
            }
        }
        persistChannel.trySend(Unit)
    }

    private fun saveToDiskLocked(conv: Conversation): Boolean {
        var tempFile: File? = null
        val result = runCatching {
            require(validId(conv.id)) { "Invalid conversation identifier" }
            val file = File(storageDir, "${conv.id}.json")
            val temporary = File.createTempFile("conv_", ".tmp", storageDir)
            tempFile = temporary
            val normalized = conv.copy(summary = null, messages = conv.messages.map { extractImagesToDisk(it, conv.id) })
            val encoded = json.encodeToString(normalized)
            require(encoded.toByteArray().size <= BoundedIO.MAX_CONVERSATION_BYTES) { "Conversation exceeds storage budget" }
            temporary.writeText(encoded)
            conversationWrites.incrementAndGet()
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure {
            _storageError.value = SAVE_ERROR_MESSAGE
        }
        tempFile?.takeIf { it.exists() }?.delete()
        return result.isSuccess
    }

    private fun updateHistoryListLocked(conv: Conversation, persisted: Boolean = true) {
        updateIndex(conv, persisted)
        refreshHistory()
    }

    private fun updateIndex(conv: Conversation, persisted: Boolean = true) {
        val stats = computeStats(conv)
        val summary = conv.copy(messages = emptyList(), summary = ConversationSummary(
            initialGoal = stats.initialGoal.take(2000), stepCount = stats.stepCount, durationMs = stats.durationMs,
            status = stats.status, toolCounts = stats.toolCounts,
        ))
        val file = File(storageDir, "${conv.id}.json")
        index[conv.id] = HistoryEntry(summary, if (persisted) file.lastModified() else -1L, file.length())
    }

    private fun refreshHistory() {
        val sorted = index.values.map { it.conversation }.sortedByDescending { it.updatedAt }
        _allHistory.value = sorted
        _historyList.value = sorted.take(visibleCount)
        _hasMoreHistory.value = sorted.size > visibleCount
    }

    private fun readConversation(file: File): Conversation {
        historyBodyReads.incrementAndGet()
        require(validId(file.nameWithoutExtension))
        val conv = json.decodeFromString<Conversation>(BoundedIO.readFile(file))
        require(conv.id == file.nameWithoutExtension && validId(conv.id))
        return conv.copy(summary = null)
    }

    private fun persistIndex() {
        var temp: File? = null
        runCatching {
            val temporary = File.createTempFile("index_", ".tmp", storageDir)
            temp = temporary
            temporary.writeText(json.encodeToString(index.values.toList()))
            Files.move(temporary.toPath(), indexFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        temp?.delete()
    }

    private fun cleanOldCorruptFiles(dir: File, maxCount: Int) {
        val corruptFiles = dir.listFiles { f -> f.name.endsWith(".corrupt") } ?: return
        if (corruptFiles.size > maxCount) {
            corruptFiles.sortedByDescending { it.lastModified() }
                .drop(maxCount)
                .forEach { it.delete() }
        }
    }

    companion object {
        private fun validId(id: String): Boolean = Regex("[A-Za-z0-9_-]{1,128}").matches(id)
        private const val SAVE_ERROR_MESSAGE = "会话保存失败，请检查可用存储空间；当前内容尚未可靠保存。"
        const val TITLE_MAX_CHARS = 40
        const val MAX_HISTORY_ITEMS = 100

        /**
         * 提取任务执行元数据与统计指标，用于回溯和对比
         */
        fun computeStats(conv: Conversation): TaskExecutionStats {
            if (conv.messages.isEmpty()) conv.summary?.let {
                return TaskExecutionStats(conv.id, conv.title, it.initialGoal, it.stepCount, it.durationMs, it.status, it.toolCounts, conv.createdAt, conv.updatedAt)
            }
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

            val durationMs = conv.messages.sumOf { it.durationMs.coerceAtLeast(0L) }

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
