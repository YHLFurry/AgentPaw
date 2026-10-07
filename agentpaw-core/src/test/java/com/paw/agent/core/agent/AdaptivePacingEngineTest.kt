package com.paw.agent.core.agent

import com.paw.agent.core.model.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptivePacingEngineTest {

    @Test
    fun `evaluateDelay assigns longer delay for launch_app`() {
        val tool = ToolCall("call-1", "launch_app", """{"package_name":"com.tencent.mm"}""")
        val decision = AdaptivePacingEngine.evaluateDelay(tool)

        assertTrue(decision.delayMillis >= 1800L)
        assertTrue(decision.reason.contains("应用启动"))
    }

    @Test
    fun `evaluateDelay assigns navigational delay only for navigational values not keys`() {
        // Parameter key is "search", but value is not navigational
        val nonNavTool = ToolCall("call-2", "tap", """{"search_mode":"coordinate","x":100,"y":200}""")
        val nonNavDecision = AdaptivePacingEngine.evaluateDelay(nonNavTool)
        assertEquals(400L, nonNavDecision.delayMillis)

        // Parameter value is "搜索" (navigational)
        val navTool = ToolCall("call-3", "tap", """{"target":"搜索","x":500,"y":500}""")
        val navDecision = AdaptivePacingEngine.evaluateDelay(navTool)
        assertEquals(900L, navDecision.delayMillis)
        assertTrue(navDecision.reason.contains("跳转/提交"))
    }

    @Test
    fun `evaluateDelay respects explicit wait duration`() {
        val tool = ToolCall("call-4", "wait", """{"seconds":2.5}""")
        val decision = AdaptivePacingEngine.evaluateDelay(tool)

        assertEquals(2500L, decision.delayMillis)
        assertTrue(decision.reason.contains("AI 显式指示等待"))
    }

    @Test
    fun `evaluateDelay scales with pacing profiles`() {
        val tool = ToolCall("call-5", "swipe", """{"startX":500,"startY":800,"endX":500,"endY":200}""")

        val balanced = AdaptivePacingEngine.evaluateDelay(tool, profile = AdaptivePacingEngine.PacingProfile.BALANCED)
        val fast = AdaptivePacingEngine.evaluateDelay(tool, profile = AdaptivePacingEngine.PacingProfile.FAST)
        val careful = AdaptivePacingEngine.evaluateDelay(tool, profile = AdaptivePacingEngine.PacingProfile.CAREFUL)

        assertTrue(fast.delayMillis < balanced.delayMillis)
        assertTrue(careful.delayMillis > balanced.delayMillis)
    }

    @Test
    fun `evaluateDelay increases when assistant context mentions loading or waiting`() {
        val tool = ToolCall("call-6", "tap", """{"x":100,"y":100}""")
        val normal = AdaptivePacingEngine.evaluateDelay(tool, assistantText = "点击头像查看详情")
        val withContext = AdaptivePacingEngine.evaluateDelay(tool, assistantText = "点击后需要等待加载数据")

        assertTrue(withContext.delayMillis > normal.delayMillis)
        assertTrue(withContext.reason.contains("上下文感知加载中"))
    }
}
