package com.paw.agent.core.llm

import com.paw.agent.core.llm.dto.ChatCompletionRequest
import com.paw.agent.core.llm.dto.ChatMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleClientTest {

    @Test
    fun `stream sse with deltas emits deltas and done`() = runTest {
        val sseData = """
            data: {"id":"chat-1","choices":[{"index":0,"delta":{"content":"Hello"}}]}
            
            data: {"id":"chat-1","choices":[{"index":0,"delta":{"content":" World"}}]}
            
            data: {"id":"chat-1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"total_tokens":10}}
            
            data: [DONE]
        """.trimIndent()

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(sseData.toResponseBody("text/event-stream".toMediaType()))
                    .build()
            }
            .build()

        val client = OpenAiCompatibleClient(httpClient = mockClient)
        val config = LlmConfig(
            baseUrl = "http://127.0.0.1:11434/v1",
            apiKey = "local-key",
            model = "test-model",
            stream = true,
        )
        val request = ChatCompletionRequest(
            model = "test-model",
            messages = listOf(ChatMessage(role = "user", content = "Hi")),
        )

        val chunks = client.complete(config, request).toList()
        assertEquals(3, chunks.size)
        assertTrue(chunks[0] is LlmChunk.Delta && (chunks[0] as LlmChunk.Delta).text == "Hello")
        assertTrue(chunks[1] is LlmChunk.Delta && (chunks[1] as LlmChunk.Delta).text == " World")
        assertTrue(chunks[2] is LlmChunk.Done && (chunks[2] as LlmChunk.Done).finishReason == "stop")
    }

    @Test
    fun `stream sse with tool calls accumulates tool arguments`() = runTest {
        val sseData = """
            data: {"id":"chat-2","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call-123","type":"function","function":{"name":"tap","arguments":"{\"x\":10"}}]}}]}
            
            data: {"id":"chat-2","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":",\"y\":20}"}}]}}]}
            
            data: {"id":"chat-2","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}
            
            data: [DONE]
        """.trimIndent()

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(sseData.toResponseBody("text/event-stream".toMediaType()))
                    .build()
            }
            .build()

        val client = OpenAiCompatibleClient(httpClient = mockClient)
        val config = LlmConfig(
            baseUrl = "http://localhost:11434/v1",
            apiKey = "local-key",
            model = "test-model",
            stream = true,
        )
        val request = ChatCompletionRequest(
            model = "test-model",
            messages = listOf(ChatMessage(role = "user", content = "Tap somewhere")),
        )

        val chunks = client.complete(config, request).toList()
        val toolCallsChunk = chunks.filterIsInstance<LlmChunk.ToolCalls>().firstOrNull()
        val doneChunk = chunks.filterIsInstance<LlmChunk.Done>().firstOrNull()

        org.junit.Assert.assertNotNull(toolCallsChunk)
        assertEquals(1, toolCallsChunk!!.calls.size)
        assertEquals("call-123", toolCallsChunk.calls[0].id)
        assertEquals("tap", toolCallsChunk.calls[0].name)
        assertEquals("""{"x":10,"y":20}""", toolCallsChunk.calls[0].arguments)

        org.junit.Assert.assertNotNull(doneChunk)
        assertEquals("tool_calls", doneChunk!!.finishReason)
    }
}
