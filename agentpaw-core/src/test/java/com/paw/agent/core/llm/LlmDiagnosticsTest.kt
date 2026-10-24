package com.paw.agent.core.llm

import com.paw.agent.core.llm.dto.ChatCompletionRequest
import com.paw.agent.core.llm.dto.ChatMessageContent
import com.paw.agent.core.model.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LlmDiagnosticsTest {
    private val config = LlmConfig(baseUrl = "https://example.test/v1", model = "test")

    @Test fun `reports four independent capabilities without executing tools`() = runTest {
        val requests = mutableListOf<ChatCompletionRequest>()
        val client = object : LlmClient {
            override fun complete(config: LlmConfig, request: ChatCompletionRequest): Flow<LlmChunk> = flow {
                requests += request
                when {
                    !request.tools.isNullOrEmpty() -> emit(LlmChunk.ToolCalls(listOf(ToolCall("test", "diagnostic_echo", """{"value":"OK"}"""))))
                    request.messages.first().content is ChatMessageContent.Parts -> emit(LlmChunk.Delta("red"))
                    else -> emit(LlmChunk.Delta("OK"))
                }
                emit(LlmChunk.Done("stop", null))
            }
            override suspend fun testConnection(config: LlmConfig): Result<Unit> = Result.success(Unit)
        }
        val results = LlmDiagnostics(client).run(config).toList()
        assertEquals(4, requests.size)
        assertEquals(4, results.count { it.status == DiagnosticStatus.PASSED })
        assertTrue(requests.single { !it.tools.isNullOrEmpty() }.tools!!.all { it.function.name == "diagnostic_echo" })
    }

    @Test fun `one failed capability does not hide the remaining results`() = runTest {
        val client = object : LlmClient {
            override fun complete(config: LlmConfig, request: ChatCompletionRequest): Flow<LlmChunk> = flow {
                if (!request.tools.isNullOrEmpty()) throw LlmException.Http(400, "tools unsupported")
                emit(LlmChunk.Delta(if (request.messages.first().content is ChatMessageContent.Parts) "red" else "OK"))
                emit(LlmChunk.Done("stop", null))
            }
            override suspend fun testConnection(config: LlmConfig): Result<Unit> = Result.success(Unit)
        }
        val results = LlmDiagnostics(client).run(config).toList()
        assertEquals(1, results.count { it.status == DiagnosticStatus.FAILED })
        assertEquals(DiagnosticKind.TOOLS, results.single { it.status == DiagnosticStatus.FAILED }.kind)
        assertEquals(3, results.count { it.status == DiagnosticStatus.PASSED })
    }

    @Test fun `cancelling diagnostics propagates cancellation`() = runTest {
        val client = object : LlmClient {
            override fun complete(config: LlmConfig, request: ChatCompletionRequest): Flow<LlmChunk> = flow { throw CancellationException("cancel") }
            override suspend fun testConnection(config: LlmConfig): Result<Unit> = Result.success(Unit)
        }
        assertTrue(runCatching { LlmDiagnostics(client).run(config).toList() }.exceptionOrNull() is CancellationException)
    }
}
