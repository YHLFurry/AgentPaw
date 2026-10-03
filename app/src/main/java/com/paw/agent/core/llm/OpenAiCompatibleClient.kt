package com.paw.agent.core.llm

import com.paw.agent.core.llm.dto.ChatCompletionRequest
import com.paw.agent.core.llm.dto.ChatCompletionResponse
import com.paw.agent.core.llm.dto.ChatMessage
import com.paw.agent.core.llm.dto.Choice
import com.paw.agent.core.llm.dto.ToolCallDelta
import com.paw.agent.core.model.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The default [LlmClient], speaking the OpenAI chat-completions protocol over
 * HTTP with server-sent events.
 *
 * Works with every preset in [LlmProvider] plus any other OpenAI-compatible
 * endpoint (vLLM, LM Studio, OneAPI, SiliconFlow, …).
 */
class OpenAiCompatibleClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val json: Json = defaultJson(),
) : LlmClient {

    override fun complete(
        config: LlmConfig,
        request: ChatCompletionRequest,
    ): Flow<LlmChunk> = flow {
        if (!config.isUsable) {
            throw LlmException.NotConfigured(
                "Base URL, model and (where required) an API key must be set",
            )
        }

        val wire = request.copy(
            model = config.model.ifBlank { request.model },
            stream = config.stream,
            temperature = config.temperature,
            topP = config.topP,
            maxTokens = config.maxTokens,
        )

        val httpRequest = Request.Builder()
            .url(completionsUrl(config.baseUrl))
            .post(json.encodeToString(ChatCompletionRequest.serializer(), wire)
                .toRequestBody(JSON_MEDIA_TYPE))
            .apply { applyAuth(config) }
            .header("Accept", if (config.stream) "text/event-stream" else "application/json")
            .build()

        // Streaming tool calls arrive as fragments keyed by index.
        val toolArgs = LinkedHashMap<Int, StringBuilder>()
        val toolNames = LinkedHashMap<Int, String>()
        val toolIds = LinkedHashMap<Int, String>()
        var sawContent = false

        httpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                // Safe to consume the body here: this response is not a stream.
                throw response.toLlmException(response.body?.string().orEmpty())
            }

            val body = response.body ?: throw LlmException.EmptyResponse()

            if (!config.stream) {
                val text = body.string()
                val parsed = runCatching {
                    json.decodeFromString(ChatCompletionResponse.serializer(), text)
                }.getOrElse { throw LlmException.Malformed(it) }

                parsed.error?.let { throw LlmException.Http(200, it.describe()) }

                val message = parsed.choices.firstOrNull()?.message
                    ?: throw LlmException.EmptyResponse()

                message.content?.takeIf { it.isNotEmpty() }?.let { emit(LlmChunk.Delta(it)) }

                message.toolCalls?.takeIf { it.isNotEmpty() }?.let { calls ->
                    emit(
                        LlmChunk.ToolCalls(
                            calls.map { call ->
                                ToolCall(
                                    id = call.id,
                                    name = call.function.name,
                                    arguments = call.function.arguments,
                                )
                            },
                        ),
                    )
                }
                emit(LlmChunk.Done(parsed.choices.firstOrNull()?.finishReason, parsed.usage))
                return@use
            }

            // ---- streaming (SSE) ----
            val source = body.source()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith(SSE_DATA_PREFIX)) continue

                val data = line.removePrefix(SSE_DATA_PREFIX).trim()
                if (data.isEmpty()) continue
                if (data == SSE_DONE) break

                val chunk = runCatching {
                    json.decodeFromString(ChatCompletionResponse.serializer(), data)
                }.getOrElse { throw LlmException.Malformed(it) }

                chunk.error?.let { throw LlmException.Http(200, it.describe()) }

                val choice: Choice = chunk.choices.firstOrNull() ?: continue

                choice.delta?.content?.takeIf { it.isNotEmpty() }?.let {
                    sawContent = true
                    emit(LlmChunk.Delta(it))
                }

                choice.delta?.toolCalls?.forEach {
                    accumulate(it, toolArgs, toolNames, toolIds)
                }

                if (choice.finishReason != null) {
                    if (toolArgs.isNotEmpty()) emit(LlmChunk.ToolCalls(toolCalls(toolArgs, toolNames, toolIds)))
                    emit(LlmChunk.Done(choice.finishReason, chunk.usage))
                    return@use
                }
            }

            if (toolArgs.isNotEmpty()) {
                emit(LlmChunk.ToolCalls(toolCalls(toolArgs, toolNames, toolIds)))
            } else if (!sawContent) {
                throw LlmException.EmptyResponse()
            }
            emit(LlmChunk.Done(null, null))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun testConnection(config: LlmConfig): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!config.isUsable) {
                    throw LlmException.NotConfigured("Fill in the required fields first")
                }

                val probe = ChatCompletionRequest(
                    model = config.model,
                    messages = listOf(ChatMessage(role = "user", content = "ping")),
                    maxTokens = 1,
                    stream = false,
                )

                val request = Request.Builder()
                    .url(completionsUrl(config.baseUrl))
                    .post(json.encodeToString(ChatCompletionRequest.serializer(), probe)
                        .toRequestBody(JSON_MEDIA_TYPE))
                    .apply { applyAuth(config) }
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw response.toLlmException(response.body?.string().orEmpty())
                    }
                }
                Unit
            }
        }

    private fun Request.Builder.applyAuth(config: LlmConfig): Request.Builder = apply {
        if (config.apiKey.isNotBlank()) {
            header(config.provider.apiKeyHeader, config.provider.apiKeyPrefix + config.apiKey)
        }
    }

    private fun okhttp3.Response.toLlmException(body: String): LlmException {
        val apiError = runCatching {
            json.decodeFromString(ChatCompletionResponse.serializer(), body).error
        }.getOrNull()

        val detail = apiError?.describe() ?: body
        return LlmException.Http(
            code = code,
            body = detail,
            unauthorized = code == 401 || code == 403,
        )
    }

    private fun com.paw.agent.core.llm.dto.ApiError.describe(): String =
        listOfNotNull(message, type, code).firstOrNull() ?: "unknown error"

    /** Merges one streaming tool-call fragment into the per-index buffers. */
    private fun accumulate(
        delta: ToolCallDelta,
        args: LinkedHashMap<Int, StringBuilder>,
        names: LinkedHashMap<Int, String>,
        ids: LinkedHashMap<Int, String>,
    ) {
        delta.id?.takeIf { it.isNotBlank() }?.let { ids[delta.index] = it }
        delta.function?.name?.takeIf { it.isNotBlank() }?.let { names[delta.index] = it }
        delta.function?.arguments?.let { args.getOrPut(delta.index) { StringBuilder() }.append(it) }
    }

    private fun toolCalls(
        args: LinkedHashMap<Int, StringBuilder>,
        names: LinkedHashMap<Int, String>,
        ids: LinkedHashMap<Int, String>,
    ): List<ToolCall> = args.map { (index, arguments) ->
        ToolCall(
            id = ids[index].orEmpty().ifBlank { "call_$index" },
            name = names[index].orEmpty(),
            arguments = arguments.toString(),
        )
    }

    private companion object {
        const val SSE_DATA_PREFIX = "data:"
        const val SSE_DONE = "[DONE]"

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Joins a user-entered base URL with the completions path. */
        fun completionsUrl(baseUrl: String): String {
            val trimmed = baseUrl.trimEnd('/')
            return if (trimmed.endsWith("/chat/completions")) {
                trimmed
            } else {
                "$trimmed/chat/completions"
            }
        }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
            coerceInputValues = true
        }
    }
}
