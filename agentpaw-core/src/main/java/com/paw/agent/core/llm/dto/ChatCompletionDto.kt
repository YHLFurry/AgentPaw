package com.paw.agent.core.llm.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive

/*
 * Wire format for the OpenAI chat-completions API. Every provider preset in
 * LlmProvider is reachable through this shape, so these DTOs are the only place
 * that needs to change if a provider deviates.
 */

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean = false,
    val tools: List<ToolSpec>? = null,
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: ChatMessageContent? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
) {
    constructor(
        role: String,
        content: String?,
        toolCalls: List<ToolCallDto>? = null,
        toolCallId: String? = null,
    ) : this(
        role = role,
        content = content?.let { ChatMessageContent.Text(it) },
        toolCalls = toolCalls,
        toolCallId = toolCallId,
    )

    val textContent: String? get() = content?.asString()
}

@Serializable(with = ChatMessageContentSerializer::class)
sealed interface ChatMessageContent {
    fun asString(): String

    data class Text(val value: String) : ChatMessageContent {
        override fun asString(): String = value
    }

    data class Parts(val parts: List<ContentPart>) : ChatMessageContent {
        override fun asString(): String =
            parts.filterIsInstance<ContentPart.TextPart>().joinToString("\n") { it.text }
    }
}

@Serializable
sealed interface ContentPart {
    @Serializable
    @SerialName("text")
    data class TextPart(val text: String) : ContentPart

    @Serializable
    @SerialName("image_url")
    data class ImagePart(
        @SerialName("image_url") val imageUrl: ImageUrl,
    ) : ContentPart
}

@Serializable
data class ImageUrl(
    val url: String,
    val detail: String? = "auto",
)

object ChatMessageContentSerializer : KSerializer<ChatMessageContent> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("ChatMessageContent")

    override fun serialize(encoder: Encoder, value: ChatMessageContent) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw IllegalStateException("This serializer can only be used with Json")
        when (value) {
            is ChatMessageContent.Text -> jsonEncoder.encodeJsonElement(JsonPrimitive(value.value))
            is ChatMessageContent.Parts -> {
                val element = jsonEncoder.json.encodeToJsonElement(
                    ListSerializer(ContentPart.serializer()),
                    value.parts,
                )
                jsonEncoder.encodeJsonElement(element)
            }
        }
    }

    override fun deserialize(decoder: Decoder): ChatMessageContent {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw IllegalStateException("This serializer can only be used with Json")
        val element = jsonDecoder.decodeJsonElement()
        return when (element) {
            is JsonPrimitive -> ChatMessageContent.Text(element.content)
            is JsonArray -> {
                val parts = jsonDecoder.json.decodeFromJsonElement(
                    ListSerializer(ContentPart.serializer()),
                    element,
                )
                ChatMessageContent.Parts(parts)
            }
            else -> ChatMessageContent.Text(element.toString())
        }
    }
}

@Serializable
data class ToolCallDto(
    val id: String,
    @SerialName("type") val type: String = "function",
    val function: FunctionCallDto,
)

@Serializable
data class FunctionCallDto(
    val name: String,
    /** Raw JSON string; parsed by the agent loop before dispatch. */
    val arguments: String,
)

@Serializable
data class ToolSpec(
    @SerialName("type") val type: String = "function",
    val function: FunctionSpec,
)

@Serializable
data class FunctionSpec(
    val name: String,
    val description: String,
    val parameters: JsonElement,
)

@Serializable
data class ChatCompletionResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null,
    val error: ApiError? = null,
)

@Serializable
data class Choice(
    val index: Int = 0,
    val message: ChatMessage? = null,
    val delta: Delta? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class Delta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDelta>? = null,
)

/** Streaming tool calls arrive in fragments that must be accumulated by index. */
@Serializable
data class ToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val function: FunctionCallDelta? = null,
)

@Serializable
data class FunctionCallDelta(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

@Serializable
data class ApiError(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null,
)
