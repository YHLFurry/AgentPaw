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

import com.paw.agent.core.agent.breakpoint.ResumeIntentDetector
import com.paw.agent.core.agent.breakpoint.StepSnapshot
import com.paw.agent.core.agent.breakpoint.TaskBreakpoint
import kotlinx.coroutines.flow.update

/** Transient UI state that is not part of the conversation itself. */
data class ChatUiState(
    val draft: String = "",
    val isGenerating: Boolean = false,
    val configReady: Boolean = false,
    val activeBreakpoint: TaskBreakpoint? = null,
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

    private var isAdaptivePacingEnabled = true
    private var lastLlmConfig: LlmConfig = LlmConfig()

    init {
        AgentExecutionController.registerStopCallback {
            stop()
        }
        AgentExecutionController.registerResumeCallback {
            resumeBreakpoint()
        }
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                lastLlmConfig = settings.llm
                _uiState.update { it.copy(configReady = settings.llm.isUsable) }
                isAdaptivePacingEnabled = settings.adaptivePacingEnabled
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        AgentExecutionController.unregisterStopCallback()
        AgentExecutionController.unregisterResumeCallback()
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

        val currentBp = _uiState.value.activeBreakpoint
        val resumeIntent = ResumeIntentDetector.detect(text, hasActiveBreakpoint = currentBp != null)

        if (resumeIntent.isResume && currentBp != null) {
            _uiState.update { it.copy(draft = "") }
            resumeBreakpoint(customInstruction = resumeIntent.additionalInstruction, config = config)
            return
        }

        // 非续操指令或无活跃断点，放弃原断点并启动新交互轮次
        _uiState.update { it.copy(draft = "", activeBreakpoint = null) }

        conversationRepository.addMessage(
            Message(
                id = UUID.randomUUID().toString(),
                role = MessageRole.USER,
                content = text,
                createdAt = System.currentTimeMillis(),
            ),
        )

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
                    enableAdaptivePacing = isAdaptivePacingEnabled,
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
                _uiState.update { it.copy(isGenerating = false) }
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

            is AgentEvent.AdaptivePaced -> {
                AgentExecutionController.updateProgress(
                    stepCount,
                    maxSteps,
                    "⏱ 智能识别等待: ${event.reason}",
                )
            }
        }

        _uiState.value = _uiState.value.copy(isGenerating = true)
    }

    fun stop() {
        if (isStopping) return
        isStopping = true
        try {
            cancelled = true
            runJob?.cancel()
            runJob = null
            // 同步停止无障碍操作：中止进行中/后续手势，确保"停止"立即生效
            com.paw.agent.device.accessibility.AgentAccessibilityService.instance?.requestUserStop()

            // 1. 中断与回滚处理：规范化流式中断步骤，避免残存无效悬空消息
            val messages = conversationRepository.conversation.value.messages
            val originalUserMsg = messages.lastOrNull { it.role == MessageRole.USER }
            val originalGoal = originalUserMsg?.content.orEmpty().ifBlank { "手机自动化任务" }

            var interruptedSnapshot: StepSnapshot? = null
            val completedSteps = mutableListOf<StepSnapshot>()
            var stepIndexCounter = 1

            messages.forEach { msg ->
                if (msg.role == MessageRole.TOOL) {
                    val rawContent = msg.content
                    val toolName = rawContent
                        .removePrefix("⚙ 正在执行:")
                        .removePrefix("✔")
                        .removePrefix("❌")
                        .trim()
                        .substringBefore(":")
                        .substringBefore(" ")
                        .trim()

                    if (msg.status == MessageStatus.STREAMING) {
                        // 在途被中断的步骤，记录断点现场并回滚/修正消息状态为 CANCELLED
                        interruptedSnapshot = StepSnapshot(
                            stepIndex = stepCount.coerceAtLeast(1),
                            toolName = toolName.ifBlank { "action" },
                            arguments = rawContent.take(100),
                            resultSummary = "中途被用户主动暂停",
                            isInterrupted = true,
                        )
                        conversationRepository.updateMessage(msg.id) {
                            it.copy(
                                content = "⏸ [已暂停于此步骤] ${it.content.removePrefix("⚙ 正在执行:")} (用户中途暂停)",
                                status = MessageStatus.CANCELLED,
                            )
                        }
                    } else if (msg.status == MessageStatus.COMPLETE) {
                        completedSteps.add(
                            StepSnapshot(
                                stepIndex = stepIndexCounter++,
                                toolName = toolName.ifBlank { "action" },
                                arguments = "",
                                resultSummary = rawContent.take(120),
                                isError = false,
                            )
                        )
                    }
                }
            }

            activeAssistantId?.let { id ->
                conversationRepository.updateMessage(id) { it.copy(status = MessageStatus.CANCELLED) }
            }
            activeAssistantId = null

            // 2. 保存断点状态快照，供后续无缝从断点继续执行
            if (originalUserMsg != null) {
                val bp = TaskBreakpoint(
                    originalGoal = originalGoal,
                    stoppedAtStep = stepCount.coerceAtLeast(1),
                    maxSteps = maxSteps,
                    completedSteps = completedSteps,
                    interruptedStep = interruptedSnapshot,
                    interruptedReason = "用户中途停止操作",
                )
                _uiState.update { it.copy(activeBreakpoint = bp) }
                AgentExecutionController.markPaused(stepCount.coerceAtLeast(1), "已在第${stepCount.coerceAtLeast(1)}步暂停")
            } else {
                AgentExecutionController.requestStop()
            }

            _uiState.update { it.copy(isGenerating = false) }
        } finally {
            isStopping = false
        }
    }

    /**
     * 从当前断点恢复并继续执行剩余交互操作
     */
    fun resumeBreakpoint(customInstruction: String? = null, config: LlmConfig? = null) {
        val bp = _uiState.value.activeBreakpoint ?: return
        val effectiveConfig = config ?: lastLlmConfig
        _uiState.update { it.copy(activeBreakpoint = null) }

        val resumePrompt = bp.buildResumePrompt(customInstruction)
        val userDisplayText = if (customInstruction.isNullOrBlank()) "▶ 继续执行剩余操作" else "▶ 继续：$customInstruction"

        conversationRepository.addMessage(
            Message(
                id = UUID.randomUUID().toString(),
                role = MessageRole.USER,
                content = userDisplayText,
                createdAt = System.currentTimeMillis(),
            ),
        )

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
        com.paw.agent.device.accessibility.AgentAccessibilityService.instance?.clearUserStop()
        stepCount = bp.stoppedAtStep
        maxSteps = bp.maxSteps
        AgentExecutionController.markStarted(maxSteps)
        AgentExecutionController.updateProgress(stepCount, maxSteps, "从断点继续执行中...")

        val resumeConfig = effectiveConfig.copy(
            systemPrompt = if (effectiveConfig.systemPrompt.isBlank()) resumePrompt
            else "${effectiveConfig.systemPrompt}\n\n$resumePrompt",
        )

        runJob = viewModelScope.launch {
            try {
                agent.run(
                    config = resumeConfig,
                    history = conversationRepository.conversation.value.messages.dropLast(1),
                    isCancelled = { cancelled },
                    enableAdaptivePacing = isAdaptivePacingEnabled,
                ).collect { event -> handleEvent(event) }
            } catch (e: CancellationException) {
                activeAssistantId?.let { id ->
                    conversationRepository.updateMessage(id) { it.copy(status = MessageStatus.CANCELLED) }
                }
                activeAssistantId = null
                AgentExecutionController.requestStop()
                throw e
            } finally {
                _uiState.update { it.copy(isGenerating = false) }
            }
        }
    }

    fun dismissBreakpoint() {
        _uiState.update { it.copy(activeBreakpoint = null) }
        AgentExecutionController.requestStop()
    }

    fun newConversation() {
        stop()
        _uiState.update { it.copy(activeBreakpoint = null) }
        conversationRepository.newConversation()
    }

    fun clear() {
        stop()
        _uiState.update { it.copy(activeBreakpoint = null) }
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
