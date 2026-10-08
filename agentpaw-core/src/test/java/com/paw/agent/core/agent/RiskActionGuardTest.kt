package com.paw.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskActionGuardTest {

    @Test
    fun `test shell command is evaluated as critical risk`() {
        val decision = RiskActionGuard.evaluate(
            toolName = "shell_command",
            arguments = """{"command":"ls -la"}""",
        )
        assertTrue(decision.isRisk)
        assertEquals(RiskLevel.CRITICAL, decision.level)
        assertEquals("ROOT_SHELL", decision.category)
        assertTrue(decision.requiresConfirmation)
    }

    @Test
    fun `test payment keyword triggers critical risk`() {
        val decision = RiskActionGuard.evaluate(
            toolName = "tap",
            arguments = """{"x":500,"y":500}""",
            screenContextText = "确认支付 ¥99.00",
        )
        assertTrue(decision.isRisk)
        assertEquals(RiskLevel.CRITICAL, decision.level)
        assertEquals("PAYMENT_CLICK", decision.category)
        assertTrue(decision.requiresConfirmation)
    }

    @Test
    fun `test payment scheme triggers critical risk`() {
        val decision = RiskActionGuard.evaluate(
            toolName = "open_deeplink",
            arguments = """{"uri":"alipays://platformapi/startapp"}""",
        )
        assertTrue(decision.isRisk)
        assertEquals(RiskLevel.CRITICAL, decision.level)
        assertEquals("PAYMENT", decision.category)
        assertTrue(decision.requiresConfirmation)
    }

    @Test
    fun `test account deletion in args triggers high risk`() {
        val decision = RiskActionGuard.evaluate(
            toolName = "click_element",
            arguments = """{"element_id":"btn_delete", "text":"删除账号"}""",
            screenContextText = "确定注销账号并删除所有个人数据",
        )
        assertTrue(decision.isRisk)
        assertEquals(RiskLevel.HIGH, decision.level)
        assertEquals("DELETE", decision.category)
        assertTrue(decision.requiresConfirmation)
    }

    @Test
    fun `test permission grant in args triggers high risk`() {
        val decision = RiskActionGuard.evaluate(
            toolName = "tap",
            arguments = """{"x":300,"y":700,"action":"授予权限"}""",
            screenContextText = "授予应用获取root权限",
        )
        assertTrue(decision.isRisk)
        assertEquals(RiskLevel.HIGH, decision.level)
        assertEquals("AUTHORIZATION", decision.category)
        assertTrue(decision.requiresConfirmation)
    }

    @Test
    fun `test send message triggers moderate risk`() {
        val decision = RiskActionGuard.evaluate(
            toolName = "tap",
            arguments = """{"x":900,"y":950}""",
            screenContextText = "确认发送",
        )
        assertTrue(decision.isRisk)
        assertEquals(RiskLevel.MODERATE, decision.level)
        assertEquals("SEND_OR_SUBMIT", decision.category)
        assertTrue(decision.requiresConfirmation)
    }

    @Test
    fun `test benign content does not trigger risk`() {
        val decision = RiskActionGuard.evaluate(
            toolName = "tap",
            arguments = """{"x":100,"y":200}""",
            screenContextText = "首页 搜索 设置 个人中心",
        )
        assertFalse(decision.isRisk)
        assertEquals(RiskLevel.LOW, decision.level)
        assertFalse(decision.requiresConfirmation)
    }

    @Test
    fun `test word bounded english pay does not trigger on non-payment words`() {
        // e.g. "player" or "payment_layout" when checking screen text or args
        val decision = RiskActionGuard.evaluate(
            toolName = "tap",
            arguments = """{"x":100,"y":200}""",
            screenContextText = "player list display",
        )
        assertFalse(decision.isRisk)
        assertEquals(RiskLevel.LOW, decision.level)
    }
}
