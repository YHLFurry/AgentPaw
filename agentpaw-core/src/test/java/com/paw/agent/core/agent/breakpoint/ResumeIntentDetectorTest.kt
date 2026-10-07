package com.paw.agent.core.agent.breakpoint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeIntentDetectorTest {

    @Test
    fun `when no active breakpoint, never treats input as resume`() {
        val result = ResumeIntentDetector.detect("继续", hasActiveBreakpoint = false)
        assertFalse(result.isResume)
        assertEquals("继续", result.additionalInstruction)
    }

    @Test
    fun `direct continue keywords correctly identified`() {
        assertTrue(ResumeIntentDetector.detect("继续", hasActiveBreakpoint = true).isResume)
        assertTrue(ResumeIntentDetector.detect("接着做", hasActiveBreakpoint = true).isResume)
        assertTrue(ResumeIntentDetector.detect("continue", hasActiveBreakpoint = true).isResume)
        assertTrue(ResumeIntentDetector.detect("resume", hasActiveBreakpoint = true).isResume)
        assertTrue(ResumeIntentDetector.detect("", hasActiveBreakpoint = true).isResume)
    }

    @Test
    fun `continue with follow up instruction extracts details`() {
        val res1 = ResumeIntentDetector.detect("继续，刚才那步点第二个按钮", hasActiveBreakpoint = true)
        assertTrue(res1.isResume)
        assertEquals("刚才那步点第二个按钮", res1.additionalInstruction)

        val res2 = ResumeIntentDetector.detect("接着做：输入测试账号", hasActiveBreakpoint = true)
        assertTrue(res2.isResume)
        assertEquals("输入测试账号", res2.additionalInstruction)

        val res3 = ResumeIntentDetector.detect("我已经登录好了，继续执行后面的操作", hasActiveBreakpoint = true)
        assertTrue(res3.isResume)
    }

    @Test
    fun `user confirmation words like ok or completed recognized during breakpoint`() {
        val res = ResumeIntentDetector.detect("好了", hasActiveBreakpoint = true)
        assertTrue(res.isResume)
        assertTrue(res.additionalInstruction?.contains("用户已确认") == true)
    }

    @Test
    fun `completely unrelated new goal is not treated as resume`() {
        val res = ResumeIntentDetector.detect("帮我查一下明天的天气预报", hasActiveBreakpoint = true)
        assertFalse(res.isResume)
        assertEquals("帮我查一下明天的天气预报", res.additionalInstruction)
    }

    @Test
    fun `breakpoint buildResumePrompt formats completed and pending steps correctly`() {
        val bp = TaskBreakpoint(
            originalGoal = "在商城中购买商品",
            stoppedAtStep = 2,
            completedSteps = listOf(
                StepSnapshot(1, "open_app", "mall", "商城已成功打开"),
            ),
            interruptedStep = StepSnapshot(2, "tap", "500 800", "用户暂停", isInterrupted = true),
        )

        val prompt = bp.buildResumePrompt("刚才那一步跳过，直接点搜索")
        assertTrue(prompt.contains("商城已成功打开"))
        assertTrue(prompt.contains("严禁重复执行"))
        assertTrue(prompt.contains("刚才那一步跳过，直接点搜索"))
    }
}
