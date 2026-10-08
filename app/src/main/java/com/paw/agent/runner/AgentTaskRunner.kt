package com.paw.agent.runner

import android.content.Context
import com.paw.agent.core.agent.Agent
import com.paw.agent.core.agent.AgentEvent
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.paw.agent.core.agent.breakpoint.RiskConfirmationDetails
import com.paw.agent.core.model.ToolCall
import com.paw.agent.core.tool.ToolControlSignal
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
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
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val breakpointFile by lazy {
        File(context?.filesDir ?: File(System.getProperty("java.io.tmpdir") ?: "."), "active_breakpoint.json")
    }
    private val runningTaskFile by lazy {
        File(context?.filesDir ?: File(System.getProperty("java.io.tmpdir") ?: "."), "running_task.json")
    }

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
    private val activeToolCalls = ConcurrentHashMap<String, ToolCall>()
    private val activeToolMessageIds = ConcurrentHashMap<String, String>()

    private fun resolveOriginalGoal(): String {
        return sessionOriginalGoal?.ifBlank { null }
            ?: conversationRepository.conversation.value.messages
                .firstOrNull { it.role == MessageRole.USER && !it.content.startsWith("▶ 继续") }
                ?.content?.ifBlank { null }
            ?: "手机自动化任务"
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
        scope.launch(Dispatchers.IO) {
            recoverInterruptedTaskOrBreakpoint()
        }
    }

    private fun persistBreakpoint(bp: TaskBreakpoint?) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (bp != null) {
                    val temp = File.createTempFile("bp_", ".tmp", breakpointFile.parentFile ?: File("."))
                    temp.writeText(json.encodeToString(bp))
                    if (!temp.renameTo(breakpointFile)) {
                        temp.copyTo(breakpointFile, overwrite = true)
                        temp.delete()
                    }
                } else {
                    if (breakpointFile.exists()) breakpointFile.delete()
                }
            }
        }
    }

    private fun markTaskRunning(goal: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                runningTaskFile.writeText(goal)
            }
        }
    }

    private fun clearTaskRunning() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (runningTaskFile.exists()) runningTaskFile.delete()
            }
        }
    }

    private fun recoverInterruptedTaskOrBreakpoint() {
        runCatching {
            if (breakpointFile.exists()) {
                val bp = json.decodeFromString<TaskBreakpoint>(breakpointFile.readText())
                sessionOriginalGoal = bp.originalGoal
                _activeBreakpoint.value = bp
                AgentExecutionController.markPaused(bp.stoppedAtStep, "检测到未完成的中断任务，可继续执行")
            } else if (runningTaskFile.exists()) {
                val goal = runningTaskFile.readText().trim().ifBlank { "自动化任务" }
                sessionOriginalGoal = goal
                val recoveredBp = TaskBreakpoint(
                    originalGoal = goal,
                    stoppedAtStep = 1,
                    maxSteps = 15,
                    interruptedReason = "任务在执行期间应用被系统中断/进程重启，现场已保存",
                )
                _activeBreakpoint.value = recoveredBp
                persistBreakpoint(recoveredBp)
                clearTaskRunning()
                AgentExecutionController.markPaused(1, "任务曾被系统中断，可继续执行")
            }
        }
    }

    fun startTask(config: LlmConfig, promptText: String) {
        if (_isGenerating.value) return

        val text = promptText.trim()
        if (text.isEmpty()) return

        val currentBp = _activeBreakpoint.value
        val resumeIntent = ResumeIntentDetector.detect(text, hasActiveBreakpoint = currentBp != null)
        if (currentBp != null && resumeIntent.isResume) {
            resumeBreakpoint(
                customInstruction = resumeIntent.additionalInstruction,
                config = config,
            )
            return
        }

        sessionOriginalGoal = text
        _activeBreakpoint.value = null
        persistBreakpoint(null)
        markTaskRunning(text)
        _isGenerating.value = true

        // 启动真正的前台服务，保证后台运行优先级与通知栏可见
        context?.let { AgentFloatingService.start(it) }

        conversationRepository.addMessage(
            Message(
                id = UUID.randomUUID().toString(),
                role = MessageRole.USER,
                content = text,
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
        stepCount = 0
        maxSteps = config.maxToolRounds
        AgentExecutionController.markStarted(maxSteps)

        runJob = scope.launch {
            try {
                agent.run(
                    config = config,
                    history = conversationRepository.conversation.value.messages.dropLast(1),
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
                if (!isStopping && _activeBreakpoint.value == null) {
                    AgentExecutionController.markStopped()
                }
                throw e
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun resumeBreakpoint(customInstruction: String? = null, config: LlmConfig? = null) {
        if (_isGenerating.value) return
        val bp = _activeBreakpoint.value ?: return
        val effectiveConfig = config ?: lastLlmConfig
        sessionOriginalGoal = bp.originalGoal
        _activeBreakpoint.value = null
        persistBreakpoint(null)
        markTaskRunning(bp.originalGoal)
        _isGenerating.value = true

        context?.let { AgentFloatingService.start(it) }

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

        val basePrompt = effectiveConfig.systemPrompt
            .substringBefore("【断点续操恢复指令（严格执行）】")
            .trim()

        val resumeConfig = effectiveConfig.copy(
            systemPrompt = if (basePrompt.isBlank()) resumePrompt
            else "$basePrompt\n\n$resumePrompt",
        )

        runJob = scope.launch {
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
                if (!isStopping && _activeBreakpoint.value == null) {
                    AgentExecutionController.markStopped()
                }
                throw e
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun stop() {
        if (isStopping) return
        isStopping = true
        try {
            cancelled = true
            runJob?.cancel()
            runJob = null
            com.paw.agent.device.accessibility.AgentAccessibilityService.instance?.requestUserStop()

            val originalGoal = resolveOriginalGoal()
            val currentConv = conversationRepository.conversation.value
            val messages = currentConv.messages
            val originalUserMsg = messages.firstOrNull { it.role == MessageRole.USER }

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
                            ),
                        )
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
                    event.result.name == "take_screenshot" -> "屏幕截图成功 (多模态感知已同步)"
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
        _isGenerating.value = true
        context?.let { AgentFloatingService.start(it) }

        scope.launch {
            val toolResult = agent.executeDirectTool(risk.toolName, confirmedArgs)

            val toolResultMsgId = UUID.randomUUID().toString()
            val briefContent = "✔ 已显式授权执行: ${risk.action} (${risk.target})"
            conversationRepository.addMessage(
                Message(
                    id = toolResultMsgId,
                    role = MessageRole.TOOL,
                    content = briefContent,
                    toolCallId = toolResultMsgId,
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

            resumeBreakpoint(
                customInstruction = "【系统确认】：用户已显式确认并授权执行完成 [${risk.action}: ${risk.target}]，请直接根据最新屏幕状态推进后续步骤。",
                config = effectiveConfig,
            )
        }
    }
}
