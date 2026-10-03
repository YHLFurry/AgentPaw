package com.paw.agent.core.model

import kotlinx.serialization.Serializable

/**
 * An ordered list of messages plus the bookkeeping the UI needs.
 *
 * The framework keeps conversation state in memory for now; persisting it is a
 * follow-up (see the roadmap in the README).
 */
@Serializable
data class Conversation(
    val id: String,
    val title: String = "",
    val messages: List<Message> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    val isEmpty: Boolean get() = messages.isEmpty()

    /** True while an assistant turn is streaming. */
    val isStreaming: Boolean
        get() = messages.lastOrNull()?.status == MessageStatus.STREAMING
}
