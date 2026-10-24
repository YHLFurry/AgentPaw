package com.paw.agent.core.agent

import com.paw.agent.core.llm.LlmConfig
import com.paw.agent.core.model.Message
import com.paw.agent.core.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBudgetTest {
    private fun user(id: String, text: String) = Message(id, MessageRole.USER, text)

    @Test fun `auto budget recognizes explicit suffix and uses conservative fallback`() {
        assertEquals(32_000, ContextBudget.limit(LlmConfig(model = "local-32k")))
        assertEquals(16_000, ContextBudget.limit(LlmConfig(model = "unknown")))
        assertEquals(65_536, ContextBudget.limit(LlmConfig(maxTotalTokens = 65_536)))
    }

    @Test fun `configuration rejects invalid URL and impossible token window`() {
        assertFalse(LlmConfig(baseUrl = "https://", model = "test").isUsable)
        assertFalse(LlmConfig(baseUrl = "https://example.test/v1", model = "test",
            maxTokens = 2048, maxTotalTokens = 1024).isUsable)
        assertTrue(LlmConfig(baseUrl = "https://example.test/v1", model = "test",
            maxTokens = 2048, maxTotalTokens = 4096).isUsable)
    }

    @Test fun `active compression keeps original goal and latest task within budget`() {
        val history = listOf(
            user("old", "original goal"),
            Message("old-answer", MessageRole.ASSISTANT, "old progress ".repeat(1_000)),
            user("latest", "please continue"),
        )
        val result = ContextBudget.compact(history, LlmConfig(maxTotalTokens = 4_096, maxTokens = 1_024))
        assertTrue(result.any { it.id == "latest" })
        assertTrue(result.any { it.id == "old" && it.content.startsWith("original goal") })
        assertTrue(result.sumOf(ContextBudget::estimate) < history.sumOf(ContextBudget::estimate))
        assertFalse(result.any { it.id == "context-summary" && it.role == MessageRole.SYSTEM })
    }

    @Test fun `disabled automatic compression leaves history untouched`() {
        val history = listOf(user("old", "x".repeat(10_000)), user("new", "hi"))
        assertEquals(history, ContextBudget.compact(history, LlmConfig(autoCompress = false)))
        assertEquals(history, ContextBudget.compact(history, LlmConfig(autoCompress = false), aggressive = true))
    }

    @Test fun `CJK text does not use ASCII token estimate`() {
        assertTrue(ContextBudget.estimateText("中".repeat(300)) >= 300)
        assertTrue(ContextBudget.estimateText("a".repeat(300)) < 300)
    }

    @Test fun `oversized current task fails instead of silently discarding instructions`() {
        val failure = runCatching { ContextBudget.compact(listOf(user("latest", "中".repeat(10_000))),
            LlmConfig(maxTotalTokens = 4096, maxTokens = 1024)) }.exceptionOrNull()
        assertTrue(failure is com.paw.agent.core.llm.LlmException.ContextLimit)
    }
}
