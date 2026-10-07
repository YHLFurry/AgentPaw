package com.paw.agent.data.conversation

import android.content.Context
import com.paw.agent.core.model.Conversation
import com.paw.agent.core.model.Message
import com.paw.agent.core.model.MessageRole
import com.paw.agent.core.model.MessageStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) : ConversationRepository {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val storageDir = File(context.filesDir, "conversations").apply { mkdirs() }
    private val mutex = Mutex()

    private val _conversation = MutableStateFlow(
        Conversation(
            id = UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        ),
    )
    override val conversation: StateFlow<Conversation> = _conversation.asStateFlow()

    private val _historyList = MutableStateFlow<List<Conversation>>(emptyList())
    val historyList: StateFlow<List<Conversation>> = _historyList.asStateFlow()

    init {
        scope.launch {
            loadAllFromDisk()
        }
    }

    private suspend fun loadAllFromDisk() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val files = storageDir.listFiles { _, name -> name.endsWith(".json") } ?: emptyArray()
            val loaded = files.mapNotNull { file ->
                runCatching {
                    json.decodeFromString<Conversation>(file.readText())
                }.getOrNull()
            }.sortedByDescending { it.updatedAt }

            if (loaded.isNotEmpty()) {
                _historyList.value = loaded
                // 默认将最新的一条作为当前会话，若该会话有消息
                val latest = loaded.first()
                _conversation.value = latest
            } else {
                // 初次创建并落盘空会话
                saveToDiskLocked(_conversation.value)
                _historyList.value = listOf(_conversation.value)
            }
        }
    }

    override fun newConversation(): String {
        val now = System.currentTimeMillis()
        val newConv = Conversation(
            id = UUID.randomUUID().toString(),
            createdAt = now,
            updatedAt = now,
        )
        _conversation.value = newConv
        scope.launch {
            mutex.withLock {
                saveToDiskLocked(newConv)
                updateHistoryListLocked(newConv)
            }
        }
        return newConv.id
    }

    /**
     * 回溯并载入历史任务记录，使其成为当前主活跃会话
     */
    fun loadConversation(id: String) {
        val target = _historyList.value.firstOrNull { it.id == id } ?: return
        _conversation.value = target
    }

    /**
     * 删除指定历史记录
     */
    fun deleteConversation(id: String) {
        scope.launch {
            mutex.withLock {
                val file = File(storageDir, "$id.json")
                if (file.exists()) file.delete()
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
        _conversation.update { current ->
            val updated = current.copy(
                messages = current.messages + message,
                title = current.title.ifBlank { message.content.take(TITLE_MAX_CHARS) },
                updatedAt = System.currentTimeMillis(),
            )
            persistAsync(updated)
            updated
        }
    }

    override fun updateMessage(id: String, transform: (Message) -> Message) {
        _conversation.update { current ->
            val updated = current.copy(
                messages = current.messages.map { if (it.id == id) transform(it) else it },
                updatedAt = System.currentTimeMillis(),
            )
            persistAsync(updated)
            updated
        }
    }

    override fun appendToMessage(id: String, text: String) {
        if (text.isEmpty()) return
        updateMessage(id) { it.copy(content = it.content + text) }
    }

    override fun removeMessage(id: String) {
        _conversation.update { current ->
            val updated = current.copy(
                messages = current.messages.filterNot { it.id == id },
                updatedAt = System.currentTimeMillis(),
            )
            persistAsync(updated)
            updated
        }
    }

    override fun clear() {
        val now = System.currentTimeMillis()
        _conversation.update { current ->
            val updated = current.copy(messages = emptyList(), title = "", updatedAt = now)
            persistAsync(updated)
            updated
        }
    }

    private fun persistAsync(conv: Conversation) {
        scope.launch {
            mutex.withLock {
                saveToDiskLocked(conv)
                updateHistoryListLocked(conv)
            }
        }
    }

    private fun saveToDiskLocked(conv: Conversation) {
        runCatching {
            val file = File(storageDir, "${conv.id}.json")
            file.writeText(json.encodeToString(conv))
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
            val stepCount = toolMessages.size
            val toolCounts = mutableMapOf<String, Int>()

            toolMessages.forEach { msg ->
                // 工具名称提取：从内容如 "✔ tapAtPixel: ..." 或 "⚙ 正在执行: tap ..."
                val parts = msg.content.removePrefix("✔ ").removePrefix("❌ ").removePrefix("⚙ 正在执行: ").split(" ")
                val toolName = parts.firstOrNull()?.removeSuffix(":") ?: "tool"
                toolCounts[toolName] = (toolCounts[toolName] ?: 0) + 1
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
