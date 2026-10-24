package com.paw.agent.core.agent

import com.paw.agent.core.model.Message
import com.paw.agent.core.model.MessageRole
import com.paw.agent.core.model.ToolCall
import org.junit.Assert.*
import org.junit.Test

class ContextHistoryTest {
    @Test fun `parallel tool results precede image observations and retain full tool data`() {
        val history = listOf(
            Message("assistant", MessageRole.ASSISTANT, "", toolCalls = listOf(
                ToolCall("a", "screen", "{}"), ToolCall("b", "read", "{}"))),
            Message("a_result", MessageRole.TOOL, "brief", toolCallId = "a", fullLog = "full data"),
            Message("a_obs", MessageRole.USER, "[Current Screen Observation]", images = listOf("image")),
            Message("b_result", MessageRole.TOOL, "second", toolCallId = "b"),
        )
        val normalized = ContextHistory.normalize(history)
        assertEquals(listOf(MessageRole.ASSISTANT, MessageRole.TOOL, MessageRole.TOOL, MessageRole.USER), normalized.map { it.role })
        assertEquals("full data", normalized[1].content)
    }

    @Test fun `interrupted exchange receives missing result and orphan tool is omitted`() {
        val normalized = ContextHistory.normalize(listOf(
            Message("orphan", MessageRole.TOOL, "ignored", toolCallId = "orphan"),
            Message("assistant", MessageRole.ASSISTANT, "", toolCalls = listOf(ToolCall("a", "tap", "{}"))),
            Message("user", MessageRole.USER, "continue"),
        ))
        assertEquals(3, normalized.size)
        assertEquals("a", normalized[1].toolCallId)
        assertTrue(normalized[1].content.contains("interrupted"))
    }

    @Test fun `all current user attachments survive recent screenshot retention`() {
        val history = listOf(Message("user", MessageRole.USER, "compare", images = listOf("a", "b", "c"))) +
            (1..3).map { Message("screen\u0024{it}_obs", MessageRole.USER, "[Current Screen Observation]", images = listOf("screen")) }
        val retained = ContextHistory.retainImages(history)
        assertEquals(3, retained.first().images.size)
        assertTrue(retained[1].images.isEmpty())
        assertEquals(2, retained.drop(1).count { it.images.isNotEmpty() })
    }
}
