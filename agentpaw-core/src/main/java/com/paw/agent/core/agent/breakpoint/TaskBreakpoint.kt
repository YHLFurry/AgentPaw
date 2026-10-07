package com.paw.agent.core.agent.breakpoint

import java.util.UUID

/**
 * 单个操作步骤的断点快照
 *
 * @param stepIndex 步骤序号 (1-indexed)
 * @param toolName 执行的工具名称 (例如 open_app, tap, input_text)
 * @param arguments 步骤入参 JSON 或描述
 * @param resultSummary 执行返回结果摘要
 * @param isError 步骤是否执行失败
 * @param isInterrupted 是否为中途中断的在途步骤
 * @param timestamp 记录时间戳
 */
data class StepSnapshot(
    val stepIndex: Int,
    val toolName: String,
    val arguments: String,
    val resultSummary: String,
    val isError: Boolean = false,
    val isInterrupted: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * 任务断点现场快照
 *
 * 记录用户中途停止时的完整现场，包括：
 * - 原始任务目标
 * - 暂停时所处步数与最大限制
 * - 之前已成功完成的步骤集合（用于防重复执行）
 * - 中断时的动作步骤（若处于执行中）
 * - 用户补充指示与恢复上下文
 */
data class TaskBreakpoint(
    val id: String = UUID.randomUUID().toString(),
    val originalGoal: String,
    val stoppedAtStep: Int,
    val maxSteps: Int = 15,
    val completedSteps: List<StepSnapshot> = emptyList(),
    val interruptedStep: StepSnapshot? = null,
    val interruptedReason: String = "用户中途停止操作",
    val timestamp: Long = System.currentTimeMillis(),
) {
    /**
     * 生成用于注入 LLM 上下文的结构化断点提示词
     */
    fun buildResumePrompt(userFollowUpInstruction: String?): String {
        val stepsText = if (completedSteps.isEmpty()) {
            "（此前尚未完成任何有效原子步骤）"
        } else {
            completedSteps.joinToString("\n") { step ->
                "  ✓ 第 ${step.stepIndex} 步: ${step.toolName}(${step.arguments.take(60)}) -> ${step.resultSummary.take(120)}"
            }
        }

        val interruptedText = if (interruptedStep != null) {
            "在第 ${stoppedAtStep} 步 [${interruptedStep.toolName}(${interruptedStep.arguments.take(60)})] 处被用户中断"
        } else {
            "在第 ${stoppedAtStep} 步执行完毕后暂停"
        }

        val followUpText = if (userFollowUpInstruction.isNullOrBlank()) {
            "用户要求直接从断点处继续执行剩余交互操作"
        } else {
            userFollowUpInstruction.trim()
        }

        return """
        【断点续操恢复指令（严格执行）】
        任务原目标：$originalGoal
        断点暂停位置：$interruptedText

        【已成功执行的步骤清单（严禁重复执行以下已完成的操作）】：
        $stepsText

        【用户后续续操指示与补充提示】：
        $followUpText

        【核心续操执行规范】：
        1. 严禁重复执行上述已完成清单中的任何前序步骤！
        2. 当前设备屏幕可能已处于断点操作之后的状态，请先观察当前屏幕并评估进度。
        3. 结合用户最新指示，直接从断点之后继续推进尚未完成的剩余交互操作，直到任务全部完成。
        """.trimIndent()
    }
}
