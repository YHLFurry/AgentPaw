package com.paw.agent.core.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolControlSignalTest {

    @Test
    fun `parse returns SafetyPause when is_safety_pause is true`() {
        val json = """{"status":"paused","is_safety_pause":true,"message":"检测到敏感页面（支付密码输入界面）"}"""
        val signal = ToolControlSignal.parse(json)
        assertTrue(signal is ToolControlSignal.SafetyPause)
        assertEquals("检测到敏感页面（支付密码输入界面）", (signal as ToolControlSignal.SafetyPause).reason)
    }

    @Test
    fun `parse returns RequiresConfirmation when requires_confirmation is true`() {
        val json = """{
            "status":"requires_confirmation",
            "requires_confirmation":true,
            "tool_name":"tap",
            "arguments":"{\"x\":500,\"y\":500}",
            "action":"点击付款按钮",
            "target":"立即支付",
            "risk_level":"HIGH",
            "reason":"即将进行支付确认"
        }"""
        val signal = ToolControlSignal.parse(json)
        assertTrue(signal is ToolControlSignal.RequiresConfirmation)
        val confirmation = signal as ToolControlSignal.RequiresConfirmation
        assertEquals("tap", confirmation.toolName)
        assertEquals("{\"x\":500,\"y\":500}", confirmation.arguments)
        assertEquals("点击付款按钮", confirmation.action)
        assertEquals("立即支付", confirmation.target)
        assertEquals("HIGH", confirmation.riskLevel)
        assertEquals("即将进行支付确认", confirmation.reason)
    }

    @Test
    fun `parse returns None for normal execution result`() {
        val json = """{"status":"success","message":"点击完成"}"""
        val signal = ToolControlSignal.parse(json)
        assertTrue(signal is ToolControlSignal.None)
    }

    @Test
    fun `parse returns None for non-json input`() {
        val plainText = "Operation finished successfully"
        val signal = ToolControlSignal.parse(plainText)
        assertTrue(signal is ToolControlSignal.None)
    }
}
