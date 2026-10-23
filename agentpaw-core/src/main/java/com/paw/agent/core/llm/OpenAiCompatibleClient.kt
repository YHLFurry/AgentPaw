package com.paw.agent.core.llm

import com.paw.agent.core.llm.dto.ChatCompletionRequest
import com.paw.agent.core.llm.dto.ChatCompletionResponse
import com.paw.agent.core.llm.dto.ChatMessage
import com.paw.agent.core.llm.dto.Choice
import com.paw.agent.core.llm.dto.ToolCallDelta
import com.paw.agent.core.model.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.EOFException
import com.paw.agent.core.io.BoundedIO
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

    /**
     * 传输策略。由宿主（设置页）实时下发：开关一变，下一次请求立即按新策略判定。
     * 默认严格模式——未显式开启前不允许任何明文 HTTP。
     */
    @Volatile
    var networkPolicy: NetworkPolicy = NetworkPolicy()

    override fun complete(
        config: LlmConfig,
        request: ChatCompletionRequest,
    ): Flow<LlmChunk> = flow {
        if (!config.isUsable) {
            throw LlmException.NotConfigured(
                "Base URL, model and (where required) an API key must be set",
            )
        }

        val policyDecision = networkPolicy.evaluate(config.baseUrl)
        if (!policyDecision.allowed) {
            throw LlmException.NotConfigured(policyDecision.reason)
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
        var receivedChars = 0L

        val clientForCall = clientForConfig(config)
        val call = clientForCall.newCall(httpRequest)
        val cancellationHandle = currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause is kotlinx.coroutines.CancellationException) {
                call.cancel()
            }
        }

        try {
            val response = try {
                suspendCancellableCoroutine<Response> { cont ->
                    cont.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (cont.isActive) cont.resumeWithException(e)
                        }

                        override fun onResponse(call: Call, response: Response) {
                            cont.resume(response) { _, abandoned, _ -> abandoned.close() }
                        }
                    })
                }
            } catch (e: java.io.IOException) {
                if (e.message?.contains("Cleartext HTTP traffic", ignoreCase = true) == true) {
                    throw LlmException.NotConfigured(
                        "Cleartext HTTP is disabled to protect API keys. Use HTTPS or localhost/127.0.0.1 for local models.",
                    )
                }
                throw e
            }

            response.use {
                if (!response.isSuccessful) {
                    // Safe to consume the body here: this response is not a stream.
                    throw response.toLlmException(readBody(response.body))
                }

                val body = response.body ?: throw LlmException.EmptyResponse()

                if (!config.stream) {
                    val text = readBody(body)
                    val parsed = runCatching {
                        json.decodeFromString(ChatCompletionResponse.serializer(), text)
                    }.getOrElse { throw LlmException.Malformed(it) }

                    parsed.error?.let { throw LlmException.Http(200, it.describe()) }

                    val message = parsed.choices.firstOrNull()?.message
                        ?: throw LlmException.EmptyResponse()

                    message.content?.asString()?.takeIf { it.isNotEmpty() }?.let { emit(LlmChunk.Delta(it)) }

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
                var malformedCount = 0
                while (!source.exhausted()) {
                    val line = try {
                        source.readUtf8LineStrict(1024L * 1024)
                    } catch (e: EOFException) {
                        if (source.buffer.size > 1024L * 1024) throw LlmException.Malformed(IOException("SSE line exceeds 1 MB"))
                        source.readUtf8Line()
                    } ?: break
                    if (!line.startsWith(SSE_DATA_PREFIX)) continue

                    val data = line.removePrefix(SSE_DATA_PREFIX).trim()
                    if (data.isEmpty()) continue
                    if (data == SSE_DONE) break

                    val chunk = runCatching {
                        json.decodeFromString(ChatCompletionResponse.serializer(), data)
                    }.getOrElse {
                        malformedCount++
                        null
                    } ?: continue

                    chunk.error?.let { throw LlmException.Http(200, it.describe()) }

                    val choice: Choice = chunk.choices.firstOrNull() ?: continue
                    receivedChars += (choice.delta?.content?.length ?: 0) +
                        (choice.delta?.toolCalls?.sumOf { it.function?.arguments?.length ?: 0 } ?: 0)
                    if (receivedChars > 4L * 1024 * 1024 || toolArgs.size > 256) {
                        throw LlmException.Malformed(IOException("Model response exceeds resource budget"))
                    }

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
                    if (malformedCount > 0) {
                        throw LlmException.Malformed(IllegalArgumentException("All $malformedCount SSE chunks failed to parse"))
                    } else {
                        throw LlmException.EmptyResponse()
                    }
                }
                emit(LlmChunk.Done(null, null))
            }
        } finally {
            cancellationHandle.dispose()
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun testConnection(config: LlmConfig): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!config.isUsable) {
                    throw LlmException.NotConfigured("Fill in the required fields first")
                }

                val decision = networkPolicy.evaluate(config.baseUrl)
                if (!decision.allowed) {
                    throw LlmException.NotConfigured(decision.reason)
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

                val call = clientForConfig(config).newCall(request)
                val response = try {
                    suspendCancellableCoroutine<Response> { cont ->
                        cont.invokeOnCancellation { call.cancel() }
                        call.enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                if (cont.isActive) cont.resumeWithException(e)
                            }

                            override fun onResponse(call: Call, response: Response) {
                                cont.resume(response) { _, abandoned, _ -> abandoned.close() }
                            }
                        })
                    }
                } catch (e: java.io.IOException) {
                    if (e.message?.contains("Cleartext HTTP traffic", ignoreCase = true) == true) {
                        throw LlmException.NotConfigured(
                            "Cleartext HTTP is disabled to protect API keys. Use HTTPS or localhost/127.0.0.1 for local models.",
                        )
                    }
                    throw e
                }
                response.use {
                    if (!response.isSuccessful) {
                        throw response.toLlmException(readBody(response.body))
                    }
                }
                Unit
            }
        }

    override suspend fun listModels(config: LlmConfig): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (config.baseUrl.isBlank()) {
                    throw LlmException.NotConfigured("Base URL 不能为空")
                }
                val policyDecision = networkPolicy.evaluate(config.baseUrl)
                if (!policyDecision.allowed) {
                    throw LlmException.NotConfigured(
                        policyDecision.reason ?: "网络策略拦截了此地址",
                    )
                }

                val url = modelsUrl(config.baseUrl)

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .apply { applyAuth(config) }
                    .build()

                val clientForCall = clientForConfig(config)
                val call = clientForCall.newCall(request)
                val response = suspendCancellableCoroutine<Response> { cont ->
                    cont.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (cont.isActive) cont.resumeWithException(e)
                        }

                        override fun onResponse(call: Call, response: Response) {
                            cont.resume(response) { _, abandoned, _ -> abandoned.close() }
                        }
                    })
                }

                val body = response.use { resp ->
                    if (!resp.isSuccessful) {
                        throw resp.toLlmException(readBody(resp.body))
                    }
                    readBody(resp.body)
                }

                parseModelsFromJson(body).ifEmpty {
                    throw LlmException.Http(200, "端点未返回任何可用模型")
                }
            }
        }

    private fun readBody(body: okhttp3.ResponseBody?): String = body?.charStream()?.use {
        BoundedIO.readText(it, 4 * 1024 * 1024)
    }.orEmpty()

    private fun clientForConfig(config: LlmConfig): OkHttpClient = httpClient.newBuilder()
        // 请求中包含凭据和对话；禁止重定向绕过已校验的地址与传输策略。
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(config.timeoutSeconds.coerceIn(5, 30).toLong(), TimeUnit.SECONDS)
        .readTimeout(config.timeoutSeconds.coerceIn(5, 600).toLong(), TimeUnit.SECONDS)
        .writeTimeout(config.timeoutSeconds.coerceIn(5, 600).toLong(), TimeUnit.SECONDS)
        .callTimeout(config.timeoutSeconds.coerceIn(5, 600).toLong(), TimeUnit.SECONDS)
        .build()

    private fun parseModelsFromJson(jsonStr: String): List<String> {
        return runCatching {
            val root = json.parseToJsonElement(jsonStr).jsonObject
            val list = mutableListOf<String>()

            // 1. 标准 OpenAI: {"data": [{"id": "gpt-4o"}]}
            root["data"]?.let { dataElement ->
                runCatching {
                    dataElement.jsonArray.forEach { item ->
                        val obj = item.jsonObject
                        val id = obj["id"]?.jsonPrimitive?.contentOrNull
                            ?: obj["name"]?.jsonPrimitive?.contentOrNull
                        if (!id.isNullOrBlank()) list.add(id)
                    }
                }
            }

            // 2. Ollama / Gemini 兼容: {"models": [{"name": "llama3"}]}
            if (list.isEmpty()) {
                root["models"]?.let { modelsElement ->
                    runCatching {
                        modelsElement.jsonArray.forEach { item ->
                            val obj = item.jsonObject
                            val id = obj["name"]?.jsonPrimitive?.contentOrNull
                                ?: obj["id"]?.jsonPrimitive?.contentOrNull
                            if (!id.isNullOrBlank()) list.add(id)
                        }
                    }
                }
            }

            list.filter { it.isNotBlank() }.distinct().sorted()
        }.getOrDefault(emptyList())
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

    companion object {
        const val SSE_DATA_PREFIX = "data:"
        const val SSE_DONE = "[DONE]"

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Joins a user-entered base URL with the completions path. */
        fun completionsUrl(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            return if (trimmed.endsWith("/chat/completions")) {
                trimmed
            } else {
                "$trimmed/chat/completions"
            }
        }

        /** Resolves the model-list endpoint from either a base URL or a completions URL. */
        fun modelsUrl(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            val base = when {
                trimmed.endsWith("/chat/completions") -> trimmed.removeSuffix("/chat/completions")
                trimmed.endsWith("/models") -> return trimmed
                else -> trimmed
            }
            return "$base/models"
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

        private val IPV4_REGEX = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

        fun isLocalOrPrivateAddress(url: String): Boolean {
            val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
            val rawHost = uri.host?.lowercase() ?: return false
            val host = rawHost.removePrefix("[").removeSuffix("]")

            if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "10.0.2.2") {
                return true
            }
            if (host.endsWith(".local") && !host.contains("..")) {
                return true
            }
            // IPv6 Unique Local Address (fc00::/7) 或 Link-Local (fe80::/10)
            if (host.contains(":")) {
                if (host == "::1") return true
                val normalizedIpv6 = host.split("%").first()
                if (normalizedIpv6.startsWith("fc") || normalizedIpv6.startsWith("fd") || normalizedIpv6.startsWith("fe80")) {
                    return true
                }
            }


            val match = IPV4_REGEX.matchEntire(host)
            if (match != null) {
                val (o1Str, o2Str, o3Str, o4Str) = match.destructured
                val o1 = o1Str.toIntOrNull() ?: return false
                val o2 = o2Str.toIntOrNull() ?: return false
                val o3 = o3Str.toIntOrNull() ?: return false
                val o4 = o4Str.toIntOrNull() ?: return false
                if (o1 !in 0..255 || o2 !in 0..255 || o3 !in 0..255 || o4 !in 0..255) return false

                // 127.0.0.0/8 (loopback)
                if (o1 == 127) return true
                // 10.0.0.0/8
                if (o1 == 10) return true
                // 172.16.0.0/12
                if (o1 == 172 && o2 in 16..31) return true
                // 192.168.0.0/16
                if (o1 == 192 && o2 == 168) return true
                // Link-local 169.254.0.0/16
                if (o1 == 169 && o2 == 254) return true
            }

            return false
        }
    }
}
