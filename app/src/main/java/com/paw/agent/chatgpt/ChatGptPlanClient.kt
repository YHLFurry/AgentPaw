package com.paw.agent.chatgpt

import android.content.Context
import com.paw.agent.core.llm.*
import com.paw.agent.core.llm.dto.*
import com.paw.agent.core.model.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Direct Responses transport; phone tools remain owned and executed by AgentPaw. */
class ChatGptPlanClient(context: Context, private val fallback: LlmClient) : LlmClient {
    private val auth = ChatGptAuth.get(context)
    private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).callTimeout(240, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    override fun complete(config: LlmConfig, request: ChatCompletionRequest): Flow<LlmChunk> {
        if (config.provider != LlmProvider.CHATGPT_PLAN) return fallback.complete(config, request)
        return flow {
            val payload = ResponsesCodec.request(config.model, request)
            val call = http.newCall(Request.Builder().url("${ChatGptAuth.RESOURCE}/responses")
                .header("Authorization", "Bearer ${auth.accessToken()}").header("Accept", "text/event-stream")
                .post(payload.toString().toRequestBody("application/json".toMediaType())).build())
            val handle = currentCoroutineContext().job.invokeOnCompletion { cause -> if (cause is kotlinx.coroutines.CancellationException) call.cancel() }
            val cancellation = kotlinx.coroutines.CoroutineScope(currentCoroutineContext()).launch(Dispatchers.IO) {
                try { kotlinx.coroutines.awaitCancellation() } finally { call.cancel() }
            }
            try { call.execute().use { response ->
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
                    currentCoroutineContext().ensureActiveCompat()
                    val line = source.readUtf8Line() ?: break
                    if (line.isEmpty()) { processEvent(); if (completed) break }
                    else if (line.startsWith("data:")) { if (data.isNotEmpty()) data.append('\n'); data.append(line.substring(5).trimStart()) }
                }
                if (!completed) processEvent()
                check(completed) { "连接提前结束，未收到完整模型响应，请重试。" }
            } } finally { cancellation.cancel(); handle.dispose(); call.cancel() }
        }.flowOn(Dispatchers.IO)
    }
    override suspend fun testConnection(config: LlmConfig): Result<Unit> {
        if (config.provider != LlmProvider.CHATGPT_PLAN) return fallback.testConnection(config)
        return runCatching {
            complete(config, ChatCompletionRequest(config.model, listOf(ChatMessage("user", "Reply exactly: OK")))).collect { }
        }
    }
    override suspend fun listModels(config: LlmConfig): Result<List<String>> {
        if (config.provider != LlmProvider.CHATGPT_PLAN) return fallback.listModels(config)
        return withContext(Dispatchers.IO) { runCatching {
            http.newCall(Request.Builder().url("${ChatGptAuth.RESOURCE}/models").header("Authorization", "Bearer ${auth.accessToken()}").build()).execute().use {
                check(it.isSuccessful) { "获取订阅模型失败 HTTP ${it.code}" }
                val body = json.parseToJsonElement(it.body!!.string()).jsonObject
                val models = (body["models"] ?: body["data"])!!.jsonArray
                models.map { m -> m.jsonObject }.filter { m -> m["visibility"]?.jsonPrimitive?.content.let { v -> v == null || v == "list" } }
                    .map { m -> (m["slug"] ?: m["id"])!!.jsonPrimitive.content }
            }
        } }
    }
    override suspend fun detectContextWindow(config: LlmConfig): Result<Int?> =
        if (config.provider == LlmProvider.CHATGPT_PLAN) Result.success(null) else fallback.detectContextWindow(config)

    private fun safeError(body: String): String = runCatching {
        val e = json.parseToJsonElement(body).jsonObject
        val err = (e["error"] as? JsonObject) ?: (e["response"] as? JsonObject)?.get("error")?.let { it as? JsonObject }
        err?.get("message")?.jsonPrimitive?.content ?: err?.get("code")?.jsonPrimitive?.content ?: "请检查网络、账号权限或订阅额度。"
    }.getOrDefault("请检查网络或重新登录 ChatGPT。")
}

private fun kotlin.coroutines.CoroutineContext.ensureActiveCompat() { if (!job.isActive) throw kotlinx.coroutines.CancellationException() }
private fun JsonObject.int(name: String) = this[name]?.jsonPrimitive?.intOrNull ?: 0

object ResponsesCodec {
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
                    if (content.isNotEmpty()) add(buildJsonObject { put("role", m.role); put("content", content) })
                    m.toolCalls.orEmpty().forEach { t -> add(buildJsonObject { put("type", "function_call"); put("call_id", t.id); put("name", t.function.name); put("arguments", t.function.arguments) }) }
                }
            }
        }
        if (!request.tools.isNullOrEmpty()) putJsonArray("tools") {
            request.tools.orEmpty().forEach { t -> add(buildJsonObject {
                put("type", "function"); put("name", t.function.name); put("description", t.function.description); put("parameters", t.function.parameters); put("strict", false)
            }) }
        }
    }
    private fun textPart(role: String, text: String) = buildJsonObject { put("type", if (role == "assistant") "output_text" else "input_text"); put("text", text) }
    fun calls(result: JsonObject): List<ToolCall> = result["output"]?.jsonArray.orEmpty().mapNotNull { element ->
        val o = element.jsonObject
        if (o["type"]?.jsonPrimitive?.content != "function_call") null
        else ToolCall(o["call_id"]!!.jsonPrimitive.content, o["name"]!!.jsonPrimitive.content, o["arguments"]?.jsonPrimitive?.content ?: "{}")
    }
}
