package com.paw.agent.chatgpt

import android.content.Context
import com.paw.agent.core.llm.*
import com.paw.agent.core.llm.dto.*
import com.paw.agent.core.model.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Direct Responses transport; phone tools remain owned and executed by AgentPaw. */
class ChatGptPlanClient internal constructor(
    private val auth: ChatGptAuth,
    private val fallback: LlmClient,
    private val http: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = ChatGptAuth.RESOURCE,
) : LlmClient {
    constructor(context: Context, fallback: LlmClient) : this(ChatGptAuth.get(context), fallback)

    internal fun clientFor(config: LlmConfig): OkHttpClient = http.newBuilder()
        .connectTimeout(config.timeoutSeconds.coerceIn(10, 30).toLong(), TimeUnit.SECONDS)
        .readTimeout(config.timeoutSeconds.coerceIn(10, 300).toLong(), TimeUnit.SECONDS)
        .writeTimeout(config.timeoutSeconds.coerceIn(10, 300).toLong(), TimeUnit.SECONDS)
        .callTimeout(config.timeoutSeconds.coerceIn(10, 300).toLong(), TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    override fun complete(config: LlmConfig, request: ChatCompletionRequest): Flow<LlmChunk> {
        if (config.provider != LlmProvider.CHATGPT_PLAN) return fallback.complete(config, request)
        return flow {
            val payload = ResponsesCodec.request(config.model, request)
            auth.authorizedResponse(clientFor(config), { token ->
                Request.Builder().url("$baseUrl/responses")
                    .header("Authorization", "Bearer $token").header("Accept", "text/event-stream")
                    .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
            }) { response ->
                check(response.isSuccessful) { "ChatGPT 请求失败 HTTP ${response.code}：${safeError(response.body?.string().orEmpty())}" }
                val source = response.body?.source() ?: error("ChatGPT 返回空响应。")
                var completed = false
                val data = StringBuilder()
                suspend fun processEvent() {
                    if (data.isEmpty()) return
                    val event = json.parseToJsonElement(data.toString()).jsonObject; data.setLength(0)
                    when (event["type"]?.jsonPrimitive?.content) {
                        "response.output_text.delta" -> emit(LlmChunk.Delta(event["delta"]!!.jsonPrimitive.content))
                        "response.completed" -> {
                            val result = event["response"]!!.jsonObject
                            val calls = ResponsesCodec.calls(result)
                            if (calls.isNotEmpty()) emit(LlmChunk.ToolCalls(calls))
                            val u = result["usage"] as? JsonObject
                            emit(LlmChunk.Done(if (calls.isEmpty()) "stop" else "tool_calls", u?.let { Usage(it.int("input_tokens"), it.int("output_tokens"), it.int("total_tokens")) }))
                            completed = true
                        }
                        "response.failed", "response.incomplete", "error" -> error("ChatGPT 响应未完成：${safeError(event.toString())}")
                    }
                }
                while (!source.exhausted()) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (line.isEmpty()) { processEvent(); if (completed) break }
                    else if (line.startsWith("data:")) { if (data.isNotEmpty()) data.append('\n'); data.append(line.substring(5).trimStart()) }
                }
                if (!completed) processEvent()
                check(completed) { "连接提前结束，未收到完整模型响应，请重试。" }
            }
        }.flowOn(Dispatchers.IO)
    }
    override suspend fun testConnection(config: LlmConfig): Result<Unit> {
        if (config.provider != LlmProvider.CHATGPT_PLAN) return fallback.testConnection(config)
        return cancellableResult {
            complete(config, ChatCompletionRequest(config.model, listOf(ChatMessage("user", "Reply exactly: OK")))).collect { }
        }
    }
    override suspend fun listModels(config: LlmConfig): Result<List<String>> {
        if (config.provider != LlmProvider.CHATGPT_PLAN) return fallback.listModels(config)
        return withContext(Dispatchers.IO) { cancellableResult {
            auth.authorizedResponse(clientFor(config), { token ->
                Request.Builder().url("$baseUrl/models").header("Authorization", "Bearer $token").build()
            }) { response ->
                check(response.isSuccessful) { "获取订阅模型失败 HTTP ${response.code}" }
                val body = json.parseToJsonElement(response.body!!.string()).jsonObject
                val models = (body["models"] ?: body["data"])!!.jsonArray
                models.map { it.jsonObject }.filter { model ->
                    model["visibility"]?.jsonPrimitive?.content.let { it == null || it == "list" }
                }.map { (it["slug"] ?: it["id"])!!.jsonPrimitive.content }.distinct()
            }
        } }
    }

    override suspend fun detectContextWindow(config: LlmConfig): Result<Int?> =
        if (config.provider == LlmProvider.CHATGPT_PLAN) Result.success(null) else fallback.detectContextWindow(config)

    private fun safeError(body: String): String = runCatching {
        val e = json.parseToJsonElement(body).jsonObject
        val err = (e["error"] as? JsonObject) ?: (e["response"] as? JsonObject)?.get("error")?.let { it as? JsonObject }
        err?.get("code")?.jsonPrimitive?.content?.takeIf { it.matches(Regex("[a-z0-9_]{1,80}")) }
            ?: "请检查网络、账号权限或订阅额度。"
    }.getOrDefault("请检查网络或重新登录 ChatGPT。")
}

private suspend fun <T> cancellableResult(block: suspend () -> T): Result<T> =
    try { Result.success(block()) } catch (e: CancellationException) { throw e }
    catch (e: Exception) { Result.failure(e) }
private fun JsonObject.int(name: String) = this[name]?.jsonPrimitive?.intOrNull ?: 0

object ResponsesCodec {
    const val TOOL_NAMESPACE = "agentpaw"
    fun request(model: String, request: ChatCompletionRequest): JsonObject = buildJsonObject {
        put("model", model); put("store", false); put("stream", true)
        putJsonArray("input") {
            request.messages.forEach { m ->
                if (m.role == "tool") {
                    add(buildJsonObject { put("type", "function_call_output"); put("call_id", requireNotNull(m.toolCallId)); put("output", m.textContent.orEmpty()) })
                } else {
                    val content = buildJsonArray {
                        when (val c = m.content) {
                            is ChatMessageContent.Text -> if (c.value.isNotEmpty()) add(textPart(m.role, c.value))
                            is ChatMessageContent.Parts -> c.parts.forEach { p -> when (p) {
                                is ContentPart.TextPart -> add(textPart(m.role, p.text))
                                is ContentPart.ImagePart -> add(buildJsonObject { put("type", "input_image"); put("image_url", p.imageUrl.url); p.imageUrl.detail?.let { put("detail", it) } })
                            } }
                            null -> Unit
                        }
                    }
                    if (content.isNotEmpty()) add(buildJsonObject { put("role", if (m.role == "system") "developer" else m.role); put("content", content) })
                    m.toolCalls.orEmpty().forEach { t -> add(buildJsonObject { put("type", "function_call"); put("namespace", TOOL_NAMESPACE); put("call_id", t.id); put("name", t.function.name); put("arguments", t.function.arguments) }) }
                }
            }
        }
        if (!request.tools.isNullOrEmpty()) putJsonArray("tools") {
            add(buildJsonObject {
                put("type", "namespace")
                put("name", TOOL_NAMESPACE)
                put("description", "AgentPaw phone tools and skills executed locally with user permission.")
                putJsonArray("tools") {
                    request.tools.orEmpty().forEach { tool -> add(buildJsonObject {
                        put("type", "function")
                        put("name", tool.function.name)
                        put("description", tool.function.description)
                        put("parameters", tool.function.parameters)
                        put("strict", false)
                    }) }
                }
            })
        }
    }
    private fun textPart(role: String, text: String) = buildJsonObject { put("type", if (role == "assistant") "output_text" else "input_text"); put("text", text) }
    fun calls(result: JsonObject): List<ToolCall> = result["output"]?.jsonArray.orEmpty().mapNotNull { element ->
        val o = element.jsonObject
        if (o["type"]?.jsonPrimitive?.content != "function_call") null
        else {
            val namespace = o["namespace"]?.jsonPrimitive?.contentOrNull
            require(namespace == TOOL_NAMESPACE) { "Unexpected tool namespace" }
            ToolCall(o["call_id"]!!.jsonPrimitive.content, o["name"]!!.jsonPrimitive.content,
                o["arguments"]?.jsonPrimitive?.content ?: "{}")
        }
    }
}
