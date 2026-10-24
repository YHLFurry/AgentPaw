package com.paw.agent.core.llm

import com.paw.agent.core.agent.SensitiveDataMasker
import com.paw.agent.core.llm.dto.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class DiagnosticKind { TEXT, STREAM, TOOLS, VISION }
enum class DiagnosticStatus { RUNNING, PASSED, FAILED }
data class DiagnosticResult(val kind: DiagnosticKind, val status: DiagnosticStatus, val detail: String = "")

/** Makes isolated, bounded test requests. Test tool calls are inspected and are never executed. */
class LlmDiagnostics(private val client: LlmClient) {
    fun run(config: LlmConfig): Flow<DiagnosticResult> = flow {
        if (!config.isUsable) throw LlmException.NotConfigured(config.validationErrors().joinToString("；"))
        val probeConfig = config.copy(maxTokens = minOf(config.maxTokens, if (config.isReasoningModel) 1024 else 128))
        for (kind in DiagnosticKind.entries) {
            emit(DiagnosticResult(kind, DiagnosticStatus.RUNNING))
            try {
                val request = when (kind) {
                    DiagnosticKind.TEXT, DiagnosticKind.STREAM -> ChatCompletionRequest(config.model,
                        listOf(ChatMessage("user", "Reply with exactly OK.")))
                    DiagnosticKind.TOOLS -> ChatCompletionRequest(config.model,
                        listOf(ChatMessage("user", "Call diagnostic_echo with value OK. Do not answer with text.")),
                        tools = listOf(ToolSpec(function = FunctionSpec("diagnostic_echo", "Returns the supplied value; diagnostic only.",
                            Json.parseToJsonElement("""{"type":"object","properties":{"value":{"type":"string"}},"required":["value"],"additionalProperties":false}""")))),
                    )
                    DiagnosticKind.VISION -> ChatCompletionRequest(config.model, listOf(ChatMessage("user",
                        ChatMessageContent.Parts(listOf(
                            ContentPart.TextPart("What is the main color of this image? Reply with one English color word."),
                            ContentPart.ImagePart(ImageUrl("data:image/png;base64,$RED_IMAGE", detail = "low")),
                        )))))
                }
                val chunks = client.complete(probeConfig.copy(stream = kind == DiagnosticKind.STREAM), request).toList()
                val done = chunks.filterIsInstance<LlmChunk.Done>().lastOrNull()
                check(done != null) { "响应缺少结束信息" }
                check(done.finishReason != "length") { "测试输出预算耗尽，尚未确认此能力" }
                val text = chunks.filterIsInstance<LlmChunk.Delta>().joinToString("") { it.text }.trim()
                when (kind) {
                    DiagnosticKind.TEXT, DiagnosticKind.STREAM -> check(text.isNotBlank()) { "未收到文字回复" }
                    DiagnosticKind.VISION -> check(Regex("(?i)\\bred\\b").containsMatchIn(text)) { "返回内容未正确识别测试图片，尚未确认图像能力" }
                    DiagnosticKind.TOOLS -> {
                        val call = chunks.filterIsInstance<LlmChunk.ToolCalls>().flatMap { it.calls }
                            .firstOrNull { it.name == "diagnostic_echo" && it.id.isNotBlank() }
                        check(call != null && runCatching {
                            Json.parseToJsonElement(call.arguments).jsonObject["value"]?.jsonPrimitive?.content == "OK"
                        }.getOrDefault(false)) { "未返回有效的测试工具调用，尚未确认工具能力" }
                    }
                }
                emit(DiagnosticResult(kind, DiagnosticStatus.PASSED))
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                emit(DiagnosticResult(kind, DiagnosticStatus.FAILED, SensitiveDataMasker.mask(error.message.orEmpty()).take(300)))
            }
        }
    }

    companion object {
        private const val RED_IMAGE = "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAIAAAD8GO2jAAAAKElEQVR4nO3NsQ0AAAzCMP5/un0CNkuZ41wybXsHAAAAAAAAAAAAxR4yw/wuPL6QkAAAAABJRU5ErkJggg=="
    }
}
