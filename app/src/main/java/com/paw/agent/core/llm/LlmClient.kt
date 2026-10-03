package com.paw.agent.core.llm

import com.paw.agent.core.llm.dto.ChatCompletionRequest
import kotlinx.coroutines.flow.Flow

/**
 * A single response from the model, normalised across providers.
 *
 * @param delta the incremental text for this chunk (streaming only).
 * @param toolCalls fully-formed tool calls, emitted once the stream completes.
 * @param usage token accounting, when the provider reports it.
 */
sealed interface LlmChunk {
    data class Delta(val text: String) : LlmChunk
    data class ToolCalls(val calls: List<com.paw.agent.core.model.ToolCall>) : LlmChunk
    data class Done(val finishReason: String?, val usage: com.paw.agent.core.llm.dto.Usage?) : LlmChunk
}

/**
 * The single seam between the agent framework and a concrete model backend.
 *
 * Implement this to plug in a provider that is not OpenAI-compatible; everything
 * above this interface (the agent loop, the UI) stays untouched.
 */
interface LlmClient {

    /**
     * Issues a chat completion.
     *
     * @param emitFullResponse when true the flow yields a single non-streaming
     *   result; when false it yields [LlmChunk.Delta]s as tokens arrive.
     */
    fun complete(
        config: LlmConfig,
        request: ChatCompletionRequest,
    ): Flow<LlmChunk>

    /**
     * Cheap reachability probe used by the settings screen. Sends a one-token
     * request and reports success or the failure reason.
     */
    suspend fun testConnection(config: LlmConfig): Result<Unit>
}
