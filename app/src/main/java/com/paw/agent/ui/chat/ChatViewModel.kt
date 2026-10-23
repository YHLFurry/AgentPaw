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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update

/** Transient UI state that is not part of the conversation itself. */
data class ChatUiState(
    val draft: String = "",
    val isGenerating: Boolean = false,
    val configReady: Boolean = false,
    val activeBreakpoint: TaskBreakpoint? = null,
    val isInitialized: Boolean = false,
    val images: List<String> = emptyList(),
    val importingImages: Boolean = false,
    val attachmentError: Boolean = false,
)

private data class ChatDraft(val text: String = "", val images: List<String> = emptyList(), val importing: Boolean = false, val error: Boolean = false)

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

    private val _draft = MutableStateFlow(ChatDraft())
    private val _configReady = MutableStateFlow(false)
    private var importVersion = 0
    private var discardAttachment: (String) -> Unit = {}

    val uiState: StateFlow<ChatUiState> = combine(
        _draft,
        taskRunner.isGenerating,
        _configReady,
        taskRunner.activeBreakpoint,
        conversationRepository.isInitialized,
    ) { draft, isGenerating, configReady, activeBreakpoint, isInitialized ->
        ChatUiState(
            draft = draft.text,
            images = draft.images,
            importingImages = draft.importing,
            attachmentError = draft.error,
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
        _draft.update { it.copy(text = value) }
    }

    fun importImages(uris: List<String>, importer: suspend (String, String) -> String, discard: (String) -> Unit = {}) {
        if (_draft.value.importing || taskRunner.isGenerating.value || uris.isEmpty()) return
        val conversationId = conversation.value.id
        val version = ++importVersion
        discardAttachment = discard
        val available = MAX_ATTACHMENTS - _draft.value.images.size
        _draft.update { it.copy(importing = true, error = uris.size > available) }
        viewModelScope.launch {
            try {
                for (uri in uris.take(available)) {
                    val image = importer(uri, conversationId)
                    if (conversation.value.id != conversationId || version != importVersion) { discard(image); break }
                    _draft.update { it.copy(images = it.images + image) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (version == importVersion) _draft.update { it.copy(error = true) }
            } finally {
                if (version == importVersion) _draft.update { it.copy(importing = false) }
            }
        }
    }

    fun removeImage(index: Int) {
        _draft.value.images.getOrNull(index)?.let(discardAttachment)
        _draft.update { it.copy(images = it.images.filterIndexed { i, _ -> i != index }, error = false) }
    }

    private fun clearDraft() {
        importVersion++
        _draft.value.images.forEach(discardAttachment)
        _draft.value = ChatDraft()
    }

    /** Sends the current draft, or stops generation if a turn is already running. */
    fun onSendOrStop(config: LlmConfig) {
        if (!conversationRepository.isInitialized.value) return

        if (taskRunner.isGenerating.value) {
            taskRunner.stop()
            return
        }

        val draft = _draft.value
        if (draft.importing || !config.isUsable) return
        if (draft.images.any { it.startsWith("file://") && !java.io.File(it.removePrefix("file://")).isFile }) {
            _draft.update { it.copy(error = true) }
            return
        }
        val text = draft.text.trim()
        if (text.isEmpty() && draft.images.isEmpty()) return

        _draft.value = ChatDraft()
        taskRunner.startTask(config, text.ifBlank { "请分析所附图片。" }, draft.images)
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
        clearDraft()
        taskRunner.stop()
        taskRunner.dismissBreakpoint()
        conversationRepository.newConversation()
    }

    fun clear() {
        clearDraft()
        taskRunner.stop()
        taskRunner.dismissBreakpoint()
        conversationRepository.clear()
    }

    suspend fun restoreConversation(id: String): Boolean {
        clearDraft()
        taskRunner.stop()
        taskRunner.dismissBreakpoint()
        return conversationRepository.loadConversation(id)
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

    companion object { const val MAX_ATTACHMENTS = 4 }
}
