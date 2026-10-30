package com.paw.agent.runner

import android.content.Context
import com.paw.agent.core.agent.Agent
import com.paw.agent.core.agent.AgentEvent
import com.paw.agent.core.agent.SensitiveDataMasker
import com.paw.agent.core.agent.breakpoint.ResumeIntentDetector
import com.paw.agent.core.agent.breakpoint.StepSnapshot
import com.paw.agent.core.agent.breakpoint.TaskBreakpoint
import com.paw.agent.core.llm.LlmConfig
import com.paw.agent.core.model.Message
import com.paw.agent.core.model.MessageRole
import com.paw.agent.core.model.MessageStatus
import com.paw.agent.data.conversation.ConversationRepository
import com.paw.agent.data.settings.SettingsRepository
import com.paw.agent.ui.floating.AgentExecutionController
import com.paw.agent.ui.floating.AgentFloatingService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.paw.agent.core.agent.breakpoint.RiskConfirmationDetails
import com.paw.agent.core.model.ToolCall
import com.paw.agent.core.tool.ToolControlSignal
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 独立的 Agent 任务调度与执行器。
 * 运行在 Application / 前台服务生命周期范围内，与 Activity / ViewModel 解耦，
 * 保证用户切到后台、操作其他应用或 Activity 被系统回收时，任务与前台服务持续稳定运行。
 */
class AgentTaskRunner(
    private val context: Context? = null,
    private val agent: Agent,
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    checkpointDirectory: File? = context?.filesDir,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val checkpointStore = checkpointDirectory?.let { TaskCheckpointStore(it, scope, ioDispatcher) }
    private var checkpointRevision = 0L
    private var runGeneration = 0L

    private val _activeBreakpoint = MutableStateFlow<TaskBreakpoint?>(null)
    val activeBreakpoint: StateFlow<TaskBreakpoint?> = _activeBreakpoint.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private var runJob: Job? = null
    private var cancelled = false
    private var stepCount = 0
    private var maxSteps = 15
    private var isStopping = false
    private var isAdaptivePacingEnabled = true
    private var lastLlmConfig: LlmConfig = LlmConfig()
    private var activeAssistantId: String? = null
    private var sessionOriginalGoal: String? = null
    private var sessionStartMessageIndex = 0
    private var previousCompletedSteps: List<StepSnapshot> = emptyList()
    private val activeToolCalls = ConcurrentHashMap<String, ToolCall>()
    private val activeToolMessageIds = ConcurrentHashMap<String, String>()

    private fun resolveOriginalGoal(): String {
        return sessionOriginalGoal?.ifBlank { null }
            ?: conversationRepository.conversation.value.messages
                .firstOrNull { it.role == MessageRole.USER && !it.content.startsWith("▶ 继续") }
                ?.content?.ifBlank { null }
            ?: "手机自动化任务"
    }

    private fun completedStepSnapshots(): List<StepSnapshot> {
        val messages = conversationRepository.conversation.value.messages.drop(sessionStartMessageIndex)
        val calls = messages.flatMap { it.toolCalls }.associateBy { it.id }
        val completed = messages.filter { it.role == MessageRole.TOOL && it.status == MessageStatus.COMPLETE }
        return previousCompletedSteps + completed.mapIndexed { index, message ->
            val call = calls[message.toolCallId]
            StepSnapshot(
                stepIndex = previousCompletedSteps.size + index + 1,
                toolName = call?.name ?: message.content.removePrefix("✔ ").substringBefore(":").trim(),
                arguments = call?.arguments.orEmpty(),
                resultSummary = message.content.take(120),
            )
        }
    }

    init {
        AgentExecutionController.registerStopCallback {
            stop()
        }
        AgentExecutionController.registerResumeCallback {
            resumeBreakpoint()
        }
        scope.launch {
            settingsRepository.settings.collect { settings ->
                lastLlmConfig = settings.llm
                isAdaptivePacingEnabled = settings.adaptivePacingEnabled
            }
        }
        // 恢复之前持久化的未完成断点，或恢复进程被杀/崩溃中断的任务
        scope.launch {
            val bp = checkpointStore?.recover() ?: return@launch
            // Recovery can finish after the user has already started or discarded a task.
            if (checkpointRevision != 0L) return@launch
            sessionOriginalGoal = bp.originalGoal
            _activeBreakpoint.value = bp
            persistBreakpoint(bp)
            clearTaskRunning()
            AgentExecutionController.markPaused(bp.stoppedAtStep, "检测到未完成的中断任务，可继续执行")
        }
    }

    private fun persistBreakpoint(bp: TaskBreakpoint?) {
        checkpointRevision++
        checkpointStore?.saveBreakpoint(bp)
    }

    private fun markTaskRunning(goal: String) {
        checkpointRevision++
        checkpointStore?.markRunning(goal)
    }

    private fun clearTaskRunning() {
        checkpointRevision++
        checkpointStore?.clearRunning()
    }

    fun startTask(config: LlmConfig, promptText: String, images: List<String> = emptyList()) {
        if (_isGenerating.value) return

        val text = promptText.trim()
        if (text.isEmpty()) return

        val currentBp = _activeBreakpoint.value
        val resumeIntent = ResumeIntentDetector.detect(text, hasActiveBreakpoint = currentBp != null)
        if (currentBp != null && resumeIntent.isResume && images.isEmpty()) {
            resumeBreakpoint(
                customInstruction = resumeIntent.additionalInstruction,
                config = config,
            )
            return
        }

        val generation = ++runGeneration
        activeToolCalls.clear()
        activeToolMessageIds.clear()
        sessionOriginalGoal = text
        sessionStartMessageIndex = conversationRepository.conversation.value.messages.size
        previousCompletedSteps = emptyList()
        _activeBreakpoint.value = null
        persistBreakpoint(null)
        markTaskRunning(text)
        _isGenerating.value = true

        // 启动真正的前台服务，保证后台运行优先级与通知栏可见
        context?.let { runCatching { AgentFloatingService.start(it) } }

        conversationRepository.addMessage(
            Message(
                id = UUID.randomUUID().toString(),
                role = MessageRole.USER,
                content = text,
                images = images,
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
        com.paw.agent.device.accessibility.AgentAccessibilityService.clearGlobalStop()
        stepCount = 0
        maxSteps = if (config.maxToolRounds <= 0) 0 else config.maxToolRounds
        AgentExecutionController.markStarted(maxSteps)

        runJob = scope.launch {
            collectAgent(config, generation)
        }
    }

    fun resumeBreakpoint(customInstruction: String? = null, config: LlmConfig? = null) {
        if (_isGenerating.value) return
        val bp = _activeBreakpoint.value ?: return
        val effectiveConfig = config ?: lastLlmConfig
        val generation = ++runGeneration
        activeToolCalls.clear()
        activeToolMessageIds.clear()
        sessionOriginalGoal = bp.originalGoal
        sessionStartMessageIndex = conversationRepository.conversation.value.messages.size
        previousCompletedSteps = bp.completedSteps
        _activeBreakpoint.value = null
        persistBreakpoint(null)
        markTaskRunning(bp.originalGoal)
        _isGenerating.value = true

        context?.let { runCatching { AgentFloatingService.start(it) } }

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
        com.paw.agent.device.accessibility.AgentAccessibilityService.clearGlobalStop()
        stepCount = bp.stoppedAtStep
        maxSteps = if (bp.maxSteps <= 0) 0 else bp.maxSteps
        AgentExecutionController.markStarted(maxSteps)
        AgentExecutionController.updateProgress(stepCount, maxSteps, "从断点继续执行中...")

        val basePrompt = effectiveConfig.systemPrompt
            .substringBefore("【断点续操恢复指令（严格执行）】")
            .trim()

        val resumeConfig = effectiveConfig.copy(
            systemPrompt = if (basePrompt.isBlank()) resumePrompt
            else "$basePrompt\n\n$resumePrompt",
        )

        runJob = scope.launch {
            collectAgent(resumeConfig, generation)
        }
    }

    private suspend fun collectAgent(config: LlmConfig, generation: Long) {
        try {
            agent.run(
                config = config,
                history = conversationRepository.conversation.value.messages.dropLast(1),
                isCancelled = { cancelled || generation != runGeneration },
                enableAdaptivePacing = isAdaptivePacingEnabled,
            ).collect { event ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (generation == runGeneration) handleEvent(event)
            }
        } catch (e: CancellationException) {
            if (generation == runGeneration) {
                activeAssistantId?.let { id ->
                    conversationRepository.updateMessage(id) { it.copy(status = MessageStatus.CANCELLED) }
                }
                activeAssistantId = null
                if (_activeBreakpoint.value == null) {
                    clearTaskRunning()
                    AgentExecutionController.markStopped()
                }
            }
            throw e
        } catch (e: Exception) {
            if (generation == runGeneration) {
                handleEvent(AgentEvent.Failed(Message(
                    id = UUID.randomUUID().toString(), role = MessageRole.ASSISTANT,
                    content = "任务执行异常，请检查当前页面后重试。", status = MessageStatus.FAILED,
                    error = SensitiveDataMasker.mask(e.message ?: "执行失败"),
                )))
            }
        } finally {
            // A cancelled predecessor may finish after its replacement has already started.
            if (generation == runGeneration) {
                _isGenerating.value = false
                runJob = null
                activeToolCalls.clear()
                activeToolMessageIds.clear()
            }
        }
    }

    fun stop() {
        if (isStopping || !_isGenerating.value) return
        isStopping = true
        try {
            runGeneration++
            cancelled = true
            runJob?.cancel()
            runJob = null
            com.paw.agent.device.accessibility.AgentAccessibilityService.requestGlobalStop()

            val originalGoal = resolveOriginalGoal()
            val currentConv = conversationRepository.conversation.value
            val messages = currentConv.messages.drop(sessionStartMessageIndex)
            val originalUserMsg = messages.firstOrNull { it.role == MessageRole.USER }

            var interruptedSnapshot: StepSnapshot? = null
            val completedSteps = completedStepSnapshots()

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
                    }
                }
            }

            activeAssistantId?.let { id ->
                conversationRepository.updateMessage(id) { it.copy(status = MessageStatus.CANCELLED) }
            }
            activeAssistantId = null

            if (originalUserMsg != null) {
                val bp = TaskBreakpoint(
                    originalGoal = originalGoal,
                    stoppedAtStep = stepCount.coerceAtLeast(1),
                    maxSteps = maxSteps,
                    completedSteps = completedSteps,
                    interruptedStep = interruptedSnapshot,
                    interruptedReason = "用户中途停止操作",
                )
                _activeBreakpoint.value = bp
                persistBreakpoint(bp)
                clearTaskRunning()
                AgentExecutionController.markPaused(stepCount.coerceAtLeast(1), "已在第${stepCount.coerceAtLeast(1)}步暂停")
            } else {
                persistBreakpoint(null)
                clearTaskRunning()
                AgentExecutionController.markStopped()
            }

            _isGenerating.value = false
            activeToolCalls.clear()
            activeToolMessageIds.clear()
        } finally {
            isStopping = false
        }
    }

    fun dismissBreakpoint() {
        _activeBreakpoint.value = null
        persistBreakpoint(null)
        clearTaskRunning()
        AgentExecutionController.markStopped("断点已取消")
    }

    private fun handleEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.ContextCompressed -> AgentExecutionController.updateProgress(
                stepCount, maxSteps,
                "上下文已${if (event.passive) "在超限后" else "自动"}压缩：约 ${event.beforeTokens} → ${event.afterTokens} Token",
            )
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

            is AgentEvent.AssistantTurn -> {
                val currentId = activeAssistantId
                if (currentId != null) {
                    conversationRepository.updateMessage(currentId) {
                        it.copy(
                            content = event.message.content,
                            toolCalls = event.message.toolCalls,
                            durationMs = event.message.durationMs,
                            status = MessageStatus.COMPLETE,
                        )
                    }
                    activeAssistantId = null
                } else {
                    conversationRepository.addMessage(
                        event.message.copy(
                            id = UUID.randomUUID().toString(),
                            status = MessageStatus.COMPLETE,
                        ),
                    )
                }
            }

            is AgentEvent.Completed -> {
                clearTaskRunning()
                persistBreakpoint(null)
                _isGenerating.value = false
                val currentId = activeAssistantId
                if (currentId != null) {
                    conversationRepository.updateMessage(currentId) {
                        it.copy(content = event.message.content, status = MessageStatus.COMPLETE, durationMs = event.message.durationMs)
                    }
                    activeAssistantId = null
                } else if (event.message.content.isNotBlank()) {
                    conversationRepository.addMessage(
                        Message(
                            id = UUID.randomUUID().toString(),
                            role = MessageRole.ASSISTANT,
                            content = event.message.content,
                            status = MessageStatus.COMPLETE,
                            durationMs = event.message.durationMs,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
                AgentExecutionController.markCompleted()
            }

            is AgentEvent.Failed -> {
                clearTaskRunning()
                _isGenerating.value = false
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
                } else {
                    conversationRepository.addMessage(event.message.copy(
                        id = UUID.randomUUID().toString(), createdAt = System.currentTimeMillis(),
                    ))
                }
                AgentExecutionController.markFailed(event.message.error ?: "执行失败")
            }

            is AgentEvent.Cancelled -> {
                clearTaskRunning()
                _isGenerating.value = false
                val currentId = activeAssistantId
                if (currentId != null) {
                    conversationRepository.updateMessage(currentId) {
                        it.copy(status = MessageStatus.CANCELLED)
                    }
                    activeAssistantId = null
                }
                if (!isStopping && _activeBreakpoint.value == null) {
                    AgentExecutionController.markStopped()
                }
            }

            is AgentEvent.ToolStarted -> {
                stepCount = event.round
                val currentId = activeAssistantId
                if (currentId != null) {
                    conversationRepository.updateMessage(currentId) {
                        it.copy(
                            status = MessageStatus.COMPLETE,
                            toolCalls = it.toolCalls.ifEmpty { listOf(event.call) },
                        )
                    }
                    activeAssistantId = null
                }

                val toolMsgId = "tool_${event.call.id}_${UUID.randomUUID().toString().take(8)}"
                activeToolCalls[event.call.id] = event.call
                activeToolMessageIds[event.call.id] = toolMsgId
                val actionDesc = "${event.call.name} ${event.call.arguments.take(40)}"
                AgentExecutionController.updateProgress(stepCount, maxSteps, actionDesc)

                conversationRepository.addMessage(
                    Message(
                        id = toolMsgId,
                        role = MessageRole.TOOL,
                        content = "⚙ 正在执行: ${event.call.name} ${event.call.arguments.take(100)}",
                        toolCallId = event.call.id,
                        status = MessageStatus.STREAMING,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }

            is AgentEvent.ToolFinished -> {
                val toolMsgId = activeToolMessageIds.remove(event.result.toolCallId) ?: event.result.toolCallId
                val call = activeToolCalls.remove(event.result.toolCallId)
                val desensitized = com.paw.agent.core.agent.SensitiveDataMasker.mask(event.result.content)
                val controlSignal = ToolControlSignal.parse(
                    toolResultContent = event.result.content,
                    toolName = event.result.name,
                    arguments = call?.arguments.orEmpty(),
                )

                val isSafetyPaused = controlSignal is ToolControlSignal.SafetyPause
                val isConfirmationRequired = controlSignal is ToolControlSignal.RequiresConfirmation

                val briefContent = when {
                    isSafetyPaused -> "检测到敏感密码/支付页面，自动化操作已安全暂停"
                    isConfirmationRequired -> "高风险操作拦截，等待用户授权: ${(controlSignal as ToolControlSignal.RequiresConfirmation).action}"
                    event.result.name == "take_screenshot" && !event.result.isError -> "屏幕截图成功 (多模态感知已同步)"
                    desensitized.length > 120 -> desensitized.take(120) + "…"
                    else -> desensitized
                }

                conversationRepository.updateMessage(toolMsgId) {
                    it.copy(
                        content = when {
                            isSafetyPaused -> "🛡️ 安全暂停: $briefContent"
                            isConfirmationRequired -> "⚠️ ${event.result.name}: $briefContent"
                            event.result.isError -> "❌ ${event.result.name} 失败: $briefContent"
                            else -> "✔ ${event.result.name}: $briefContent"
                        },
                        fullLog = desensitized,
                        durationMs = event.result.durationMs,
                        status = when {
                            isSafetyPaused || isConfirmationRequired -> MessageStatus.CANCELLED
                            event.result.isError -> MessageStatus.FAILED
                            else -> MessageStatus.COMPLETE
                        },
                    )
                }

                if (isSafetyPaused) {
                    triggerSafetyPauseBreakpoint(event.result, call, controlSignal as ToolControlSignal.SafetyPause)
                } else if (isConfirmationRequired) {
                    triggerRiskConfirmationBreakpoint(event.result, call, controlSignal as ToolControlSignal.RequiresConfirmation)
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
    }

    private fun triggerSafetyPauseBreakpoint(
        result: com.paw.agent.core.model.ToolResult,
        call: ToolCall?,
        signal: ToolControlSignal.SafetyPause,
    ) {
        val originalGoal = resolveOriginalGoal()
        val bp = TaskBreakpoint(
            originalGoal = originalGoal,
            stoppedAtStep = stepCount.coerceAtLeast(1),
            maxSteps = maxSteps,
            completedSteps = completedStepSnapshots(),
            interruptedStep = StepSnapshot(
                stepIndex = stepCount.coerceAtLeast(1),
                toolName = result.name,
                arguments = call?.arguments.orEmpty(),
                resultSummary = signal.message,
                isInterrupted = true,
            ),
            interruptedReason = signal.reason,
            isSafetyPaused = true,
        )
        _activeBreakpoint.value = bp
        persistBreakpoint(bp)
        clearTaskRunning()
        AgentExecutionController.markPaused(stepCount.coerceAtLeast(1), "安全暂停: 敏感密码/支付页面")
        cancelled = true
        runJob?.cancel()
        runJob = null
        _isGenerating.value = false
    }

    private fun triggerRiskConfirmationBreakpoint(
        result: com.paw.agent.core.model.ToolResult,
        call: ToolCall?,
        signal: ToolControlSignal.RequiresConfirmation,
    ) {
        val originalGoal = resolveOriginalGoal()
        val toolName = if (signal.toolName.isNotBlank()) signal.toolName else result.name
        val arguments = if (signal.arguments.isNotBlank()) signal.arguments else call?.arguments.orEmpty()
        val riskDetails = RiskConfirmationDetails(
            action = signal.action,
            target = signal.target,
            reason = signal.reason,
            riskLevel = signal.riskLevel,
            toolName = toolName,
            arguments = arguments,
            isConfirmed = false,
        )
        val bp = TaskBreakpoint(
            originalGoal = originalGoal,
            stoppedAtStep = stepCount.coerceAtLeast(1),
            maxSteps = maxSteps,
            completedSteps = completedStepSnapshots(),
            interruptedStep = StepSnapshot(
                stepIndex = stepCount.coerceAtLeast(1),
                toolName = toolName,
                arguments = arguments,
                resultSummary = "触发高风险防护确认: ${signal.action} -> ${signal.target}",
                isInterrupted = true,
            ),
            interruptedReason = "检测到高风险操作 [${signal.action}: ${signal.target}]，需您显式确认后继续",
            riskConfirmation = riskDetails,
        )
        _activeBreakpoint.value = bp
        persistBreakpoint(bp)
        clearTaskRunning()
        AgentExecutionController.markPaused(stepCount.coerceAtLeast(1), "高风险动作待确认: ${signal.action}")
        cancelled = true
        runJob?.cancel()
        runJob = null
        _isGenerating.value = false
    }

    /**
     * 用户在风险确认卡片中点击“确认执行”：
     * 直接由系统执行已授权的特定工具原子动作（携带 confirmed: true），
     * 记录执行结果，并从下一操作步骤继续推进，杜绝重复拦截或依赖模型自行补充参数。
     */
    fun confirmAndExecuteRiskAction(config: LlmConfig? = null) {
        if (_isGenerating.value) return
        val bp = _activeBreakpoint.value ?: return
        val risk = bp.riskConfirmation ?: run {
            resumeBreakpoint(config = config)
            return
        }

        val confirmedArgs = try {
            val element = if (risk.arguments.isNotBlank()) {
                json.parseToJsonElement(risk.arguments).jsonObject.toMutableMap()
            } else {
                mutableMapOf()
            }
            element["confirmed"] = JsonPrimitive(true)
            json.encodeToString(JsonObject(element))
        } catch (e: Exception) {
            risk.arguments
        }

        val effectiveConfig = config ?: lastLlmConfig
        val generation = ++runGeneration
        _isGenerating.value = true
        cancelled = false
        com.paw.agent.device.accessibility.AgentAccessibilityService.clearGlobalStop()
        AgentExecutionController.markStarted(bp.maxSteps)
        context?.let { runCatching { AgentFloatingService.start(it) } }

        runJob = scope.launch {
            try {
                val toolResult = agent.executeDirectTool(risk.toolName, confirmedArgs,
                    isCancelled = { cancelled || generation != runGeneration })
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (generation != runGeneration) return@launch
                val call = ToolCall(toolResult.toolCallId, risk.toolName, confirmedArgs)
                when (val signal = ToolControlSignal.parse(toolResult.content, risk.toolName, confirmedArgs)) {
                    is ToolControlSignal.SafetyPause -> {
                        triggerSafetyPauseBreakpoint(toolResult, call, signal)
                        return@launch
                    }
                    is ToolControlSignal.RequiresConfirmation -> {
                        triggerRiskConfirmationBreakpoint(toolResult, call, signal)
                        return@launch
                    }
                    ToolControlSignal.None -> Unit
                }
                if (toolResult.isError) {
                    _isGenerating.value = false
                    AgentExecutionController.markFailed("授权操作执行失败，请检查权限与当前页面后重试")
                    conversationRepository.addMessage(Message(
                        id = UUID.randomUUID().toString(), role = MessageRole.ASSISTANT,
                        content = "授权操作执行失败，任务已停止。", status = MessageStatus.FAILED,
                        error = SensitiveDataMasker.mask(toolResult.content).take(1000),
                    ))
                    return@launch
                }

                val toolResultMsgId = UUID.randomUUID().toString()
                val briefContent = "✔ 已显式授权执行: ${risk.action} (${risk.target})"
                // Keep the confirmed call/result pair in history so normalization does not
                // discard the receipt or replace it with an interrupted-action placeholder.
                conversationRepository.addMessage(Message(
                    id = UUID.randomUUID().toString(), role = MessageRole.ASSISTANT,
                    content = "", toolCalls = listOf(call),
                ))
                conversationRepository.addMessage(
                    Message(
                        id = toolResultMsgId,
                        role = MessageRole.TOOL,
                        content = briefContent,
                        toolCallId = call.id,
                        fullLog = SensitiveDataMasker.mask(toolResult.content),
                        durationMs = toolResult.durationMs,
                        status = if (toolResult.isError) MessageStatus.FAILED else MessageStatus.COMPLETE,
                        createdAt = System.currentTimeMillis(),
                    ),
                )

                val completedSnapshot = StepSnapshot(
                    stepIndex = bp.stoppedAtStep,
                    toolName = risk.toolName,
                    arguments = confirmedArgs,
                    resultSummary = "用户已显式确认并授权执行完成",
                    isError = toolResult.isError,
                )
                val updatedBp = bp.copy(
                    stoppedAtStep = bp.stoppedAtStep + 1,
                    completedSteps = bp.completedSteps + completedSnapshot,
                    interruptedStep = null,
                    riskConfirmation = risk.copy(isConfirmed = true),
                )

                _activeBreakpoint.value = updatedBp
                persistBreakpoint(updatedBp)

                // 显式复位 _isGenerating，使得 resumeBreakpoint 可以正常通过门禁启动后续 LLM 循环
                _isGenerating.value = false

                resumeBreakpoint(
                    customInstruction = "【系统确认】：用户已显式确认并授权执行完成 [${risk.action}: ${risk.target}]，请直接根据最新屏幕状态推进后续步骤。",
                    config = effectiveConfig,
                )
            } catch (t: CancellationException) {
                throw t
            } catch (t: Exception) {
                if (generation == runGeneration) {
                    _isGenerating.value = false
                    AgentExecutionController.markFailed(t.message ?: "授权执行失败")
                }
            } finally {
                if (generation == runGeneration) {
                    _isGenerating.value = false
                    runJob = null
                }
            }
        }
    }
}

