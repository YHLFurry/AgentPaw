package com.paw.agent.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.paw.agent.core.agent.Agent
import com.paw.agent.core.agent.breakpoint.TaskBreakpoint
import com.paw.agent.core.llm.LlmConfig
import com.paw.agent.core.model.Conversation
import com.paw.agent.data.conversation.ConversationRepository
import com.paw.agent.data.settings.SettingsRepository
import com.paw.agent.runner.AgentTaskRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Transient UI state that is not part of the conversation itself. */
data class ChatUiState(
    val draft: String = "",
    val isGenerating: Boolean = false,
    val configReady: Boolean = false,
    val activeBreakpoint: TaskBreakpoint? = null,
    val isInitialized: Boolean = false,
)

/**
 * 聊天界面 ViewModel。
 * 执行调度已完全下沉至独立的 [AgentTaskRunner] 与前台服务，
 * 本 ViewModel 仅负责 UI 层的草稿编辑、配置就绪状态聚合以及用户操作委托，
 * 不再绑定执行协程生命周期，避免 Activity 销毁导致后台手机操作中断。
 */
class ChatViewModel(
    private val agent: Agent,
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
    val taskRunner: AgentTaskRunner = AgentTaskRunner(
        context = null,
        agent = agent,
        conversationRepository = conversationRepository,
        settingsRepository = settingsRepository,
    ),
) : ViewModel() {

    val conversation: StateFlow<Conversation> = conversationRepository.conversation
    val isInitialized: StateFlow<Boolean> = conversationRepository.isInitialized

    private val _draft = MutableStateFlow("")
    private val _configReady = MutableStateFlow(false)

    val uiState: StateFlow<ChatUiState> = combine(
        _draft,
        taskRunner.isGenerating,
        _configReady,
        taskRunner.activeBreakpoint,
        conversationRepository.isInitialized,
    ) { draft, isGenerating, configReady, activeBreakpoint, isInitialized ->
        ChatUiState(
            draft = draft,
            isGenerating = isGenerating,
            configReady = configReady,
            activeBreakpoint = activeBreakpoint,
            isInitialized = isInitialized,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ChatUiState(),
    )

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _configReady.value = settings.llm.isUsable
            }
        }
    }

    fun onDraftChange(value: String) {
        _draft.value = value
    }

    /** Sends the current draft, or stops generation if a turn is already running. */
    fun onSendOrStop(config: LlmConfig) {
        if (!conversationRepository.isInitialized.value) return

        if (taskRunner.isGenerating.value) {
            taskRunner.stop()
            return
        }

        val text = _draft.value.trim()
        if (text.isEmpty()) return

        _draft.value = ""
        taskRunner.startTask(config, text)
    }

    fun stop() {
        taskRunner.stop()
    }

    fun resumeBreakpoint(customInstruction: String? = null, config: LlmConfig? = null) {
        taskRunner.resumeBreakpoint(customInstruction, config)
    }

    fun confirmRiskAction(config: LlmConfig? = null) {
        taskRunner.confirmAndExecuteRiskAction(config)
    }

    fun dismissBreakpoint() {
        taskRunner.dismissBreakpoint()
    }

    fun newConversation() {
        taskRunner.stop()
        taskRunner.dismissBreakpoint()
        conversationRepository.newConversation()
    }

    fun clear() {
        taskRunner.stop()
        taskRunner.dismissBreakpoint()
        conversationRepository.clear()
    }

    class Factory(
        private val agent: Agent,
        private val conversationRepository: ConversationRepository,
        private val settingsRepository: SettingsRepository,
        private val taskRunner: AgentTaskRunner? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ChatViewModel(
                agent = agent,
                conversationRepository = conversationRepository,
                settingsRepository = settingsRepository,
                taskRunner = taskRunner ?: AgentTaskRunner(
                    context = null,
                    agent = agent,
                    conversationRepository = conversationRepository,
                    settingsRepository = settingsRepository,
                ),
            ) as T
    }
}
