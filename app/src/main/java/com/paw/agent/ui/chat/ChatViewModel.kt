package com.paw.agent.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.paw.agent.core.agent.Agent
import com.paw.agent.core.agent.AgentEvent
import com.paw.agent.core.llm.LlmConfig
import com.paw.agent.core.model.Conversation
import com.paw.agent.core.model.Message
import com.paw.agent.core.model.MessageRole
import com.paw.agent.core.model.MessageStatus
import com.paw.agent.data.conversation.ConversationRepository
import com.paw.agent.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

import com.paw.agent.ui.floating.AgentExecutionController

/** Transient UI state that is not part of the conversation itself. */
data class ChatUiState(
    val draft: String = "",
    val isGenerating: Boolean = false,
    val configReady: Boolean = false,
)

class ChatViewModel(
    private val agent: Agent,
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val conversation: StateFlow<Conversation> = conversationRepository.conversation

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var runJob: Job? = null
    private var cancelled = false
    private var stepCount = 0
    private var maxSteps = 15

    /**
     * 防重入标识：stop() 与 AgentExecutionController.stopCallback 互相回调时，
     * 保证同一时刻只有最外层的 stop() 执行真正的停止逻辑，避免无限递归。
     */
    private var isStopping = false

    init {
        AgentExecutionController.registerStopCallback {
            stop()
        }
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.value = _uiState.value.copy(configReady = settings.llm.isUsable)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        AgentExecutionController.unregisterStopCallback()
    }

    fun onDraftChange(value: String) {
        _uiState.value = _uiState.value.copy(draft = value)
    }

    /** Sends the current draft, or stops generation if a turn is already running. */
    fun onSendOrStop(config: LlmConfig) {
        if (_uiState.value.isGenerating) {
            stop()
            return
        }

        val text = _uiState.value.draft.trim()
        if (text.isEmpty()) return

        conversationRepository.addMessage(
            Message(
                id = UUID.randomUUID().toString(),
                role = MessageRole.USER,
                content = text,
                createdAt = System.currentTimeMillis(),
            ),
        )
        _uiState.value = _uiState.value.copy(draft = "")

        // Placeholder row so the UI has something to stream into.
        val assistantId = UUID.randomUUID().toString()
        activeAssistantId = assistantId
        conversationRepository.addMessage(
            Message(
                id = assistantId,
                role = MessageRole.ASSISTANT,
                content = "",
                status = MessageStatus.STREAMING,
                createdAt = System.currentTimeMillis(),
            ),
        )

        cancelled = false
        // 重置上一轮的用户停止标志，否则停止一次后无障碍操作永远快速失败
        com.paw.agent.device.accessibility.AgentAccessibilityService.instance?.clearUserStop()
        stepCount = 0
        maxSteps = config.maxToolRounds
        AgentExecutionController.markStarted(maxSteps)

        runJob = viewModelScope.launch {
            try {
                agent.run(
                    config = config,
                    history = conversationRepository.conversation.value.messages
                        .dropLast(1), // exclude the placeholder
                    isCancelled = { cancelled },
                ).collect { event -> handleEvent(event) }
            } catch (e: CancellationException) {
                activeAssistantId?.let { id ->
                    conversationRepository.updateMessage(id) {
                        it.copy(status = MessageStatus.CANCELLED)
                    }
                }
                activeAssistantId = null
                AgentExecutionController.requestStop()
                throw e
            } finally {
                _uiState.value = _uiState.value.copy(isGenerating = false)
            }
        }
    }

    private var activeAssistantId: String? = null

    private fun handleEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.AssistantDelta -> {
                val currentId = activeAssistantId
                if (currentId == null) {
                    val newId = UUID.randomUUID().toString()
                    activeAssistantId = newId
                    conversationRepository.addMessage(
                        Message(
                            id = newId,
                            role = MessageRole.ASSISTANT,
                            content = event.message.content,
                            status = MessageStatus.STREAMING,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                } else {
                    conversationRepository.updateMessage(currentId) {
                        it.copy(
                            content = event.message.content,
                            status = MessageStatus.STREAMING,
                        )
                    }
                }
            }

            is AgentEvent.Completed -> {
                val currentId = activeAssistantId
                if (currentId != null) {
                    conversationRepository.updateMessage(currentId) {
                        it.copy(content = event.message.content, status = MessageStatus.COMPLETE)
                    }
                    activeAssistantId = null
                } else if (event.message.content.isNotBlank()) {
                    conversationRepository.addMessage(
                        Message(
                            id = UUID.randomUUID().toString(),
                            role = MessageRole.ASSISTANT,
                            content = event.message.content,
                            status = MessageStatus.COMPLETE,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
                AgentExecutionController.markCompleted()
            }

            is AgentEvent.Failed -> {
                val currentId = activeAssistantId
                if (currentId != null) {
                    conversationRepository.updateMessage(currentId) {
                        it.copy(
                            content = event.message.content.ifBlank { it.content },
                            status = MessageStatus.FAILED,
                            error = event.message.error,
                        )
                    }
                    activeAssistantId = null
                }
                AgentExecutionController.markFailed(event.message.error ?: "执行失败")
            }

            is AgentEvent.Cancelled -> {
                val currentId = activeAssistantId
                if (currentId != null) {
                    conversationRepository.updateMessage(currentId) {
                        it.copy(status = MessageStatus.CANCELLED)
                    }
                    activeAssistantId = null
                }
                AgentExecutionController.requestStop()
            }

            is AgentEvent.ToolStarted -> {
                stepCount++
                val currentId = activeAssistantId
                if (currentId != null) {
                    val currentMsg = conversationRepository.conversation.value.messages.find { it.id == currentId }
                    if (currentMsg != null && currentMsg.content.isBlank()) {
                        conversationRepository.removeMessage(currentId)
                    } else {
                        conversationRepository.updateMessage(currentId) {
                            it.copy(status = MessageStatus.COMPLETE)
                        }
                    }
                    activeAssistantId = null
                }

                val actionDesc = "${event.call.name} ${event.call.arguments.take(40)}"
                AgentExecutionController.updateProgress(stepCount, maxSteps, actionDesc)

                conversationRepository.addMessage(
                    Message(
                        id = event.call.id,
                        role = MessageRole.TOOL,
                        content = "⚙ 正在执行: ${event.call.name} ${event.call.arguments.take(100)}",
                        toolCallId = event.call.id,
                        status = MessageStatus.STREAMING,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }

            is AgentEvent.ToolFinished -> {
                val briefContent = if (event.result.content.length > 200) {
                    event.result.content.take(200) + "…"
                } else {
                    event.result.content
                }
                conversationRepository.updateMessage(event.result.toolCallId) {
                    it.copy(
                        content = if (event.result.isError) {
                            "❌ ${event.result.name} 失败: $briefContent"
                        } else {
                            "✔ ${event.result.name}: $briefContent"
                        },
                        status = if (event.result.isError) MessageStatus.FAILED else MessageStatus.COMPLETE,
                    )
                }
            }
        }

        _uiState.value = _uiState.value.copy(isGenerating = true)
    }

    fun stop() {
        // stop() 内部会调用 AgentExecutionController.requestStop()，后者又会触发
        // init 中注册的 stopCallback 反向回调 stop()。若无保护，两个方法会无限
        // 互调直至 StackOverflowError（点击"新会话/清空会话/停止"即崩溃）。
        // 这里用 isStopping 保证重入的 stop() 直接返回，递归链最多走一层。
        if (isStopping) return
        isStopping = true
        try {
            cancelled = true
            runJob?.cancel()
            runJob = null
            // 同步停止无障碍操作：中止进行中/后续手势，确保"停止"立即生效
            com.paw.agent.device.accessibility.AgentAccessibilityService.instance?.requestUserStop()
            activeAssistantId?.let { id ->
                conversationRepository.updateMessage(id) { it.copy(status = MessageStatus.CANCELLED) }
            }
            activeAssistantId = null
            // 回调链：requestStop() -> stopCallback -> stop()，重入调用会被
            // 上面的 isStopping 拦下，不再继续反向递归
            AgentExecutionController.requestStop()
            _uiState.value = _uiState.value.copy(isGenerating = false)
        } finally {
            isStopping = false
        }
    }

    fun newConversation() {
        stop()
        conversationRepository.newConversation()
    }

    fun clear() {
        stop()
        conversationRepository.clear()
    }

    class Factory(
        private val agent: Agent,
        private val conversationRepository: ConversationRepository,
        private val settingsRepository: SettingsRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ChatViewModel(agent, conversationRepository, settingsRepository) as T
    }
}
