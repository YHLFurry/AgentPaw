package com.paw.agent.ui.floating

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 跨组件同步 Agent 实时执行状态，供悬浮窗、前台服务或外部打断调用
 */
object AgentExecutionController {

    const val UNLIMITED_STEPS = 0

    data class ExecutionState(
        val isRunning: Boolean = false,
        val currentStep: Int = 0,
        val maxSteps: Int = 15,
        val currentAction: String = "",
        val isPaused: Boolean = false,
    ) {
        val isUnlimited: Boolean get() = maxSteps <= UNLIMITED_STEPS
    }

    private val _state = MutableStateFlow(ExecutionState())
    val state: StateFlow<ExecutionState> = _state.asStateFlow()

    private var stopCallback: (() -> Unit)? = null
    private var resumeCallback: (() -> Unit)? = null

    fun registerStopCallback(callback: () -> Unit) {
        stopCallback = callback
    }

    fun unregisterStopCallback() {
        stopCallback = null
    }

    fun registerResumeCallback(callback: () -> Unit) {
        resumeCallback = callback
    }

    fun unregisterResumeCallback() {
        resumeCallback = null
    }

    fun requestStop() {
        stopCallback?.invoke()
        _state.value = _state.value.copy(isRunning = false, currentAction = "已手动停止", isPaused = false)
    }

    fun requestResume() {
        resumeCallback?.invoke()
    }

    fun markPaused(step: Int, reason: String = "已在断点处暂停") {
        _state.value = _state.value.copy(
            isRunning = false,
            isPaused = true,
            currentStep = step,
            currentAction = reason,
        )
    }

    fun updateProgress(step: Int, maxSteps: Int, action: String) {
        _state.value = ExecutionState(
            isRunning = true,
            currentStep = step,
            maxSteps = maxSteps,
            currentAction = action,
            isPaused = false,
        )
    }

    fun markStarted(maxSteps: Int) {
        _state.value = ExecutionState(
            isRunning = true,
            currentStep = 1,
            maxSteps = maxSteps,
            currentAction = "正在分析屏幕与意图...",
            isPaused = false,
        )
    }

    fun markCompleted() {
        _state.value = _state.value.copy(
            isRunning = false,
            currentAction = "任务已完成",
        )
    }

    fun markFailed(reason: String) {
        _state.value = _state.value.copy(
            isRunning = false,
            currentAction = "任务失败: $reason",
        )
    }
}
