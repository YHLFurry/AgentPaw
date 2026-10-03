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

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.value = _uiState.value.copy(configReady = settings.llm.isUsable)
            }
        }
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
        runJob = viewModelScope.launch {
            try {
                agent.run(
                    config = config,
                    history = conversationRepository.conversation.value.messages
                        .dropLast(1), // exclude the placeholder
                    isCancelled = { cancelled },
                ).collect { event -> handleEvent(assistantId, event) }
            } catch (e: CancellationException) {
                conversationRepository.updateMessage(assistantId) {
                    it.copy(status = MessageStatus.CANCELLED)
                }
                throw e
            } finally {
                _uiState.value = _uiState.value.copy(isGenerating = false)
            }
        }
    }

    private fun handleEvent(assistantId: String, event: AgentEvent) {
        when (event) {
            is AgentEvent.AssistantDelta -> conversationRepository.appendToMessage(
                id = assistantId,
                text = event.message.content,
            )

            is AgentEvent.Completed -> conversationRepository.updateMessage(assistantId) {
                it.copy(content = event.message.content, status = MessageStatus.COMPLETE)
            }

            is AgentEvent.Failed -> conversationRepository.updateMessage(assistantId) {
                it.copy(
                    content = event.message.content.ifBlank { it.content },
                    status = MessageStatus.FAILED,
                    error = event.message.error,
                )
            }

            is AgentEvent.Cancelled -> conversationRepository.updateMessage(assistantId) {
                it.copy(status = MessageStatus.CANCELLED)
            }

            // Tool activity is recorded on the assistant turn; the framework
            // itself stays free of UI concerns.
            is AgentEvent.ToolStarted, is AgentEvent.ToolFinished -> Unit
        }

        _uiState.value = _uiState.value.copy(isGenerating = true)
    }

    fun stop() {
        cancelled = true
        runJob?.cancel()
        runJob = null
        _uiState.value = _uiState.value.copy(isGenerating = false)
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
