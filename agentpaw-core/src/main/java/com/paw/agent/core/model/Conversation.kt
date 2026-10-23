package com.paw.agent.core.model

import kotlinx.serialization.Serializable

/**
 * An ordered list of messages plus the bookkeeping the UI needs.
 *
 * History entries may contain only a summary; the full body is loaded on demand.
 */
@Serializable
data class Conversation(
    val id: String,
    val title: String = "",
    val messages: List<Message> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val summary: ConversationSummary? = null,
) {
    val isEmpty: Boolean get() = messages.isEmpty()

    /** True while an assistant turn is streaming. */
    val isStreaming: Boolean
        get() = messages.lastOrNull()?.status == MessageStatus.STREAMING
}

@Serializable
data class ConversationSummary(
    val initialGoal: String = "",
    val stepCount: Int = 0,
    val durationMs: Long = 0,
    val status: MessageStatus = MessageStatus.COMPLETE,
    val toolCounts: Map<String, Int> = emptyMap(),
)
