package com.paw.agent.core.agent

import com.paw.agent.core.llm.dto.ChatMessage as WireMessage
import com.paw.agent.core.llm.dto.FunctionSpec
import com.paw.agent.core.llm.dto.ToolSpec
import com.paw.agent.core.llm.LlmChunk
import com.paw.agent.core.llm.LlmClient
import com.paw.agent.core.llm.LlmConfig
import com.paw.agent.core.llm.LlmException
import com.paw.agent.core.llm.dto.ChatCompletionRequest
import com.paw.agent.core.model.Message
import com.paw.agent.core.model.MessageRole
import com.paw.agent.core.model.MessageStatus
import com.paw.agent.core.model.ToolCall
import com.paw.agent.core.model.ToolResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Base64

/**
 * Events emitted by [Agent.run] while it works through a turn.
 *
 * The UI renders these directly, which keeps the agent loop free of any
 * Android or Compose types.
 */
sealed interface AgentEvent {
    /** An assistant message was created; [message] is updated as tokens arrive. */
    data class AssistantDelta(val message: Message) : AgentEvent

    /**
     * An assistant turn was completed with pending tool calls.
     * [message] carries the populated toolCalls and COMPLETE status.
     */
    data class AssistantTurn(val message: Message, val round: Int = 1) : AgentEvent

    /** A tool the model asked for is about to run. */
    data class ToolStarted(val call: ToolCall, val round: Int = 1) : AgentEvent

    /** A tool finished, successfully or not. */
    data class ToolFinished(val result: ToolResult) : AgentEvent

    /** AI 智能识别的步间等待延时与节奏调整 */
    data class AdaptivePaced(val call: ToolCall, val delayMillis: Long, val reason: String) : AgentEvent

    /** The turn ended normally. */
    data class Completed(val message: Message) : AgentEvent

    /** The turn failed; [message] carries the error text. */
    data class Failed(val message: Message) : AgentEvent

    /** The user stopped generation. */
    data class Cancelled(val message: Message) : AgentEvent
}

/**
 * Drives one conversational turn: ask the model, run any tools it requests, feed
 * the results back, repeat until the model answers without asking for tools.
 *
 * This is the framework's core abstraction. It has no Android dependencies, so it
 * can be unit-tested on the JVM and reused from a background service later.
 *
 * @param maxToolRounds guards against a model that keeps requesting tools
 *   forever; the turn is abandoned once the budget is exhausted.
 */
class Agent(
    private val llmClient: LlmClient,
    private val toolRegistry: ToolRegistry = ToolRegistry(),
    private val maxToolRounds: Int = DEFAULT_MAX_TOOL_ROUNDS,
) {

    fun run(
        config: LlmConfig,
        history: List<Message>,
        isCancelled: () -> Boolean = { false },
        depth: Int = 0,
        enableAdaptivePacing: Boolean = true,
    ): Flow<AgentEvent> = flow {
        val conversationId = "conversation"
        val context = AgentContext(conversationId, depth) { isCancelled() }

        var workingHistory = history
        var assistantId: String? = null
        var buffer = StringBuilder()

        val effectiveMaxRounds: Int? = if (this@Agent.maxToolRounds != DEFAULT_MAX_TOOL_ROUNDS) {
            if (this@Agent.maxToolRounds <= 0) null else this@Agent.maxToolRounds.coerceAtMost(100)
        } else {
            if (config.maxToolRounds <= 0) null else config.maxToolRounds.coerceAtMost(100)
        }
        var round = 0
        while (true) {
            currentCoroutineContext().ensureActive()

            if (isCancelled()) {
                emit(AgentEvent.Cancelled(currentAssistant(buffer, assistantId, MessageStatus.CANCELLED)))
                return@flow
            }

            // ---- one model call ----
            buffer = StringBuilder()
            val pendingToolCalls = mutableListOf<ToolCall>()
            var failure: LlmException? = null

            try {
                llmClient.complete(config, buildRequest(config, workingHistory))
                    .collect { chunk ->
                        when (chunk) {
                            is LlmChunk.Delta -> {
                                buffer.append(chunk.text)
                                emit(
                                    AgentEvent.AssistantDelta(
                                        currentAssistant(buffer, assistantId, MessageStatus.STREAMING),
                                    ),
                                )
                            }

                            is LlmChunk.ToolCalls -> pendingToolCalls += chunk.calls

                            is LlmChunk.Done -> Unit
                        }
                    }
            } catch (e: LlmException) {
                failure = e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = LlmException.Malformed(e)
            }

            if (failure != null) {
                emit(
                    AgentEvent.Failed(
                        currentAssistant(
                            buffer,
                            assistantId,
                            MessageStatus.FAILED,
                            failure.message ?: "Unknown error",
                        ),
                    ),
                )
                return@flow
            }

            // ---- did the model ask for tools? ----
            if (pendingToolCalls.isEmpty()) {
                val done = currentAssistant(buffer, assistantId, MessageStatus.COMPLETE)
                emit(AgentEvent.Completed(done))
                return@flow
            }

            if (effectiveMaxRounds != null && round >= effectiveMaxRounds) {
                val msg = currentAssistant(
                    buffer,
                    assistantId,
                    MessageStatus.FAILED,
                    "Stopped after $effectiveMaxRounds tool rounds",
                )
                emit(AgentEvent.Failed(msg))
                return@flow
            }

            val assistantMessage = currentAssistant(
                buffer,
                assistantId,
                MessageStatus.COMPLETE,
            ).copy(toolCalls = pendingToolCalls)

            workingHistory = workingHistory + assistantMessage
            emit(AgentEvent.AssistantTurn(assistantMessage, round + 1))

            for (call in pendingToolCalls) {
                currentCoroutineContext().ensureActive()
                if (isCancelled()) {
                    emit(AgentEvent.Cancelled(assistantMessage))
                    return@flow
                }

                emit(AgentEvent.ToolStarted(call, round + 1))
                val result = executeTool(call, context)
                emit(AgentEvent.ToolFinished(result))
                workingHistory = workingHistory + result.toMessages()

                if (enableAdaptivePacing && !isCancelled() && !call.name.lowercase().contains("wait")) {
                    val decision = AdaptivePacingEngine.evaluateDelay(call, assistantMessage.content)
                    if (decision.delayMillis > 0) {
                        emit(AgentEvent.AdaptivePaced(call, decision.delayMillis, decision.reason))
                        kotlinx.coroutines.delay(decision.delayMillis)
                    }
                }
            }

            round++
        }
    }

    /**
     * 直接执行已获得用户显式授权确认的特定工具，用于断点恢复与高风险操作闭环
     */
    suspend fun executeDirectTool(
        toolName: String,
        arguments: String,
        conversationId: String = "conversation",
    ): ToolResult {
        val context = AgentContext(
            conversationId = conversationId,
            depth = 0,
            grantedTokens = setOf("risk_confirmed:$toolName"),
            cancelledCheck = { false },
        )
        val call = ToolCall(
            id = "confirmed_" + java.util.UUID.randomUUID().toString(),
            name = toolName,
            arguments = arguments,
        )
        return executeTool(call, context)
    }

    private suspend fun executeTool(call: ToolCall, context: AgentContext): ToolResult {
        val tool = toolRegistry.find(call.name)
            ?: return ToolResult(
                toolCallId = call.id,
                name = call.name,
                content = "Error: no tool named '${call.name}' is registered.",
                isError = true,
            )

        return runCatching { tool.execute(call.arguments, context) }
            .fold(
                onSuccess = { ToolResult(call.id, call.name, it) },
                onFailure = { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    ToolResult(
                        toolCallId = call.id,
                        name = call.name,
                        content = "Error: ${e.message ?: e::class.simpleName}",
                        isError = true,
                    )
                },
            )
    }

    private fun buildRequest(config: LlmConfig, history: List<Message>): ChatCompletionRequest =
        ChatCompletionRequest(
            model = config.model,
            messages = buildList {
                if (config.systemPrompt.isNotBlank()) {
                    add(WireMessage(role = "system", content = config.systemPrompt))
                }
                // Retain only the latest 2 observations with full images to prevent token bloat
                val allImageIndices = history.mapIndexedNotNull { index, msg ->
                    if (msg.images.isNotEmpty()) index else null
                }
                val keepImageIndices = allImageIndices.takeLast(2).toSet()

                history.forEachIndexed { index, message ->
                    when (message.role) {
                        MessageRole.SYSTEM -> add(
                            WireMessage(role = "system", content = message.content),
                        )

                        MessageRole.USER -> {
                            val shouldSendImages = message.images.isNotEmpty() && index in keepImageIndices
                            if (!shouldSendImages) {
                                val textContent = if (message.images.isNotEmpty() && index !in keepImageIndices) {
                                    "${message.content} (Previous screenshot omitted to preserve context)"
                                } else {
                                    message.content
                                }
                                add(WireMessage(role = "user", content = textContent))
                            } else {
                                val parts = buildList {
                                    if (message.content.isNotBlank()) {
                                        add(com.paw.agent.core.llm.dto.ContentPart.TextPart(message.content))
                                    }
                                    message.images.forEach { img ->
                                        val url = when {
                                            img.startsWith("data:") || img.startsWith("http://") || img.startsWith("https://") -> img
                                            img.startsWith("file://") || File(img).exists() -> {
                                                val f = File(img.removePrefix("file://")).canonicalFile
                                                val validExts = setOf("jpg", "jpeg", "png", "webp", "bmp")
                                                val ext = f.extension.lowercase()
                                                // 严格校验必须为常规图片文件且大小合理，杜绝目录穿越越权读取非图片私有敏感文件
                                                if (f.exists() && f.isFile && ext in validExts && f.length() <= 10 * 1024 * 1024) {
                                                    val b64 = Base64.getEncoder().encodeToString(f.readBytes())
                                                    "data:image/jpeg;base64,$b64"
                                                } else null
                                            }
                                            else -> "data:image/jpeg;base64,$img"
                                        }

                                        if (url != null) {
                                            add(
                                                com.paw.agent.core.llm.dto.ContentPart.ImagePart(
                                                    imageUrl = com.paw.agent.core.llm.dto.ImageUrl(url = url),
                                                ),
                                            )
                                        }
                                    }
                                }
                                add(
                                    WireMessage(
                                        role = "user",
                                        content = com.paw.agent.core.llm.dto.ChatMessageContent.Parts(parts),
                                    ),
                                )
                            }
                        }

                        MessageRole.ASSISTANT -> {
                            val calls = message.toolCalls
                            if (calls.isEmpty()) {
                                add(WireMessage(role = "assistant", content = message.content))
                            } else {
                                add(
                                    WireMessage(
                                        role = "assistant",
                                        content = message.content.ifBlank { null },
                                        toolCalls = calls.map {
                                            com.paw.agent.core.llm.dto.ToolCallDto(
                                                id = it.id,
                                                function = com.paw.agent.core.llm.dto.FunctionCallDto(
                                                    name = it.name,
                                                    arguments = it.arguments.ifBlank { "{}" },
                                                ),
                                            )
                                        },
                                    ),
                                )
                            }
                        }

                        MessageRole.TOOL -> {
                            val sanitized = SensitiveDataMasker.mask(message.content)
                            add(
                                WireMessage(
                                    role = "tool",
                                    content = sanitized,
                                    toolCallId = message.toolCallId,
                                ),
                            )
                        }
                    }
                }
            },
            tools = toolRegistry.definitions.takeIf { it.isNotEmpty() }?.map { definition ->
                ToolSpec(
                    function = FunctionSpec(
                        name = definition.name,
                        description = definition.description,
                        parameters = runCatching {
                            json.parseToJsonElement(definition.parametersSchema)
                        }.getOrElse { emptyObjectSchema },
                    ),
                )
            },
        )

    private fun currentAssistant(
        buffer: StringBuilder,
        id: String?,
        status: MessageStatus,
        error: String? = null,
    ): Message = Message(
        id = id ?: NEW_MESSAGE_ID,
        role = MessageRole.ASSISTANT,
        content = buffer.toString(),
        status = status,
        error = error,
    )

    private fun ToolResult.toMessages(): List<Message> {
        if (content.contains("image_base64")) {
            val element = runCatching { json.parseToJsonElement(content).jsonObject }.getOrNull()
            val imageBase64 = element?.get("image_base64")?.jsonPrimitive?.contentOrNull
            if (!imageBase64.isNullOrBlank()) {
                val cleanedSummary = buildString {
                    append("Screenshot taken successfully")
                    element["width"]?.let { append(" (${it}x${element["height"]}") }
                    element["mode"]?.let { append(", mode: $it)") } ?: append(")")
                }
                val toolMsg = Message(
                    id = "${toolCallId}_result",
                    role = MessageRole.TOOL,
                    content = cleanedSummary,
                    toolCallId = toolCallId,
                    status = if (isError) MessageStatus.FAILED else MessageStatus.COMPLETE,
                )
                val observationMsg = Message(
                    id = "${toolCallId}_obs",
                    role = MessageRole.USER,
                    content = "[Current Screen Observation]",
                    images = listOf(imageBase64),
                    status = MessageStatus.COMPLETE,
                )
                return listOf(toolMsg, observationMsg)
            }
        }
        return listOf(
            Message(
                id = "${toolCallId}_result",
                role = MessageRole.TOOL,
                content = SensitiveDataMasker.mask(content),
                toolCallId = toolCallId,
                status = if (isError) MessageStatus.FAILED else MessageStatus.COMPLETE,
            ),
        )
    }

    companion object {
        const val DEFAULT_MAX_TOOL_ROUNDS = 8

        private const val NEW_MESSAGE_ID = "streaming-assistant"

        private val json = Json { ignoreUnknownKeys = true }
        private val emptyObjectSchema = json.parseToJsonElement("""{"type":"object"}""")
    }
}
