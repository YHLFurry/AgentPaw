package com.paw.agent.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Who produced a [Message]. Modelled on the OpenAI chat-completions vocabulary so
 * the same type can be sent to any OpenAI-compatible endpoint unchanged.
 */
@Serializable
enum class MessageRole {
    @SerialName("system")
    SYSTEM,

    @SerialName("user")
    USER,

    @SerialName("assistant")
    ASSISTANT,

    @SerialName("tool")
    TOOL,
}

/**
 * The lifecycle of an assistant turn, so the UI can tell a finished answer apart
 * from one that is still streaming or that failed.
 */
@Serializable
enum class MessageStatus {
    /** Fully received (or not yet sent, for user messages). */
    COMPLETE,

    /** Tokens are still arriving. */
    STREAMING,

    /** The turn ended in an error; [Message.error] carries the detail. */
    FAILED,

    /** The user cancelled generation. */
    CANCELLED,
}

/**
 * A single turn in a conversation.
 *
 * @param id stable identifier, used as the Compose list key.
 * @param toolCallId set when this message is the result of a tool call.
 */
@Serializable
data class Message(
    val id: String,
    val role: MessageRole,
    val content: String,
    val status: MessageStatus = MessageStatus.COMPLETE,
    val error: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val images: List<String> = emptyList(),
    val createdAt: Long = 0L,
) {
    val isUser: Boolean get() = role == MessageRole.USER
    val isAssistant: Boolean get() = role == MessageRole.ASSISTANT
}

/**
 * A tool invocation requested by the model. The agent loop resolves these through
 * the registered [com.paw.agent.core.agent.ToolRegistry].
 */
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

/** A tool the agent is allowed to call. */
@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    /** JSON Schema for the arguments; forwarded verbatim to the provider. */
    val parametersSchema: String,
)

/** The result of running a [ToolDefinition]. */
@Serializable
data class ToolResult(
    val toolCallId: String,
    val name: String,
    val content: String,
    val isError: Boolean = false,
)
