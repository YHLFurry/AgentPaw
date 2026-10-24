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
    @Test fun `model context detection falls back to catalog metadata`() = runTest {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val catalog = chain.request().url.encodedPath.endsWith("/models")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (catalog) 200 else 404).message("test")
                .body((if (catalog) """{"data":[{"id":"local-model","max_model_len":32768}]}""" else "{}")
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        assertEquals(32768, OpenAiCompatibleClient(httpClient = http).detectContextWindow(
            LlmConfig(baseUrl = "https://example.test/v1", model = "local-model")).getOrThrow())
    }

    @Test fun `incomplete tool arguments cannot become executable calls`() = runTest {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"a","function":{"name":"tap","arguments":"{"}}]},"finish_reason":"tool_calls"}]}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val failure = runCatching { OpenAiCompatibleClient(httpClient = http).complete(
            LlmConfig(baseUrl = "https://example.test/v1", model = "test", stream = false),
            ChatCompletionRequest("test", listOf(ChatMessage("user", "hi"))),
        ).toList() }.exceptionOrNull()
        assertTrue(failure is LlmException.Malformed)
    }
    @Test fun `reasoning model uses completion budget without incompatible sampling parameters`() = runTest {
        var body = ""
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            body = okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        OpenAiCompatibleClient(httpClient = http).complete(
            LlmConfig(baseUrl = "https://example.test/v1", model = "o3-mini", stream = false, reasoningEffort = "HIGH"),
            ChatCompletionRequest("o3-mini", listOf(ChatMessage("user", "hi"))),
        ).toList()
        assertTrue(body.contains("\"max_completion_tokens\":2048"))
        assertTrue(!body.contains("\"max_tokens\""))
        assertTrue(!body.contains("temperature"))
        assertTrue(!body.contains("top_p"))
    }

    @Test fun `stream reads usage after finish event`() = runTest {
        val data = """
            data: {"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}
            data: {"choices":[],"usage":{"prompt_tokens":20,"completion_tokens":5,"total_tokens":25}}
            data: [DONE]
        """.trimIndent()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(data.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val chunks = OpenAiCompatibleClient(httpClient = http).complete(
            LlmConfig(baseUrl = "https://example.test/v1", model = "test"),
            ChatCompletionRequest("test", listOf(ChatMessage("user", "hi"))),
        ).toList()
        assertEquals(25, chunks.filterIsInstance<LlmChunk.Done>().single().usage?.totalTokens)
    }

    @Test fun `stream without terminal marker fails rather than reporting partial success`() = runTest {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""data: {"choices":[{"delta":{"content":"partial"}}]}"""
                    .toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val failure = runCatching { OpenAiCompatibleClient(httpClient = http).complete(
            LlmConfig(baseUrl = "https://example.test/v1", model = "test"),
            ChatCompletionRequest("test", listOf(ChatMessage("user", "hi"))),
        ).toList() }.exceptionOrNull()
        assertTrue(failure is LlmException.IncompleteResponse)
    }
    @Test fun `connection test exercises configured streaming mode`() = runTest {
        var body = ""
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            body = okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""data: {"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}\n\ndata: [DONE]\n"""
                    .replace("\\n", "\n").toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val result = OpenAiCompatibleClient(httpClient = http).testConnection(
            LlmConfig(baseUrl = "https://example.test/v1", model = "test", stream = true),
        )
        assertTrue(result.isSuccess)
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"max_tokens\":32"))
    }
    @Test fun `detects provider reported context window`() = runTest {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            assertTrue(chain.request().url.encodedPath.endsWith("/models/my%2Fmodel"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"id":"my/model","context_length":65536}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val detected = OpenAiCompatibleClient(httpClient = http).detectContextWindow(
            LlmConfig(baseUrl = "https://example.test/v1", model = "my/model"),
        )
        assertEquals(65_536, detected.getOrThrow())
    }
    @Test fun `connection test rejects successful HTTP with no model choice`() = runTest {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"choices":[]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = OpenAiCompatibleClient(httpClient = http).testConnection(
            LlmConfig(baseUrl = "https://example.test/v1", model = "test", stream = false),
        )
        assertTrue(result.exceptionOrNull() is LlmException.EmptyResponse)
    }

    @Test fun `explicit reasoning effort is serialized only when selected`() = runTest {
        var body = ""
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            body = chain.request().body!!.let { requestBody ->
                okio.Buffer().also { requestBody.writeTo(it) }.readUtf8()
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = OpenAiCompatibleClient(httpClient = http)
        val request = ChatCompletionRequest(model = "test", messages = listOf(ChatMessage("user", "hi")))
        client.complete(LlmConfig(baseUrl = "https://example.test/v1", model = "test", stream = false,
            reasoningEffort = "HIGH"), request).toList()
        assertTrue(body.contains("\"reasoning_effort\":\"high\""))
        client.complete(LlmConfig(baseUrl = "https://example.test/v1", model = "test", stream = false), request).toList()
        assertTrue(!body.contains("reasoning_effort"))
    }
    @Test fun `oversized SSE lines fail with a resource budget error`() = runTest {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(("data: " + "x".repeat(1024 * 1024) + "\n").toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val failure = runCatching {
            OpenAiCompatibleClient(httpClient = http).complete(
                LlmConfig(baseUrl = "https://example.test/v1", model = "test", stream = true),
                ChatCompletionRequest(model = "test", messages = listOf(ChatMessage(role = "user", content = "hi"))),
            ).toList()
        }.exceptionOrNull()
        assertTrue(failure is LlmException.Malformed)
        assertTrue(failure?.cause?.message.orEmpty().contains("1 MB"))
    }
    @Test
    fun `model discovery rejects redirects before sending credentials to a new target`() = runTest {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val worker = kotlin.concurrent.thread(isDaemon = true) {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* drain request headers */ }
                    socket.getOutputStream().write(
                        "HTTP/1.1 302 Found\r\nLocation: http://example.com/models\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
                    )
                }
            }
            val client = OpenAiCompatibleClient().apply { networkPolicy = NetworkPolicy(allowHttp = true) }
            val result = client.listModels(LlmConfig(baseUrl = "http://127.0.0.1:${server.localPort}/v1", apiKey = "test-secret"))
            worker.join(5000)
            assertTrue(result.exceptionOrNull() is LlmException.Http)
            assertEquals(302, (result.exceptionOrNull() as LlmException.Http).code)
        }
    }

    @Test
    fun `models url supports base and completions endpoints`() {
        assertEquals("https://example.com/v1/models", OpenAiCompatibleClient.modelsUrl("https://example.com/v1"))
        assertEquals(
            "https://example.com/v1/models",
            OpenAiCompatibleClient.modelsUrl("https://example.com/v1/chat/completions"),
        )
        assertEquals("https://example.com/v1/models", OpenAiCompatibleClient.modelsUrl("https://example.com/v1/models/"))
    }

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
            baseUrl = "https://127.0.0.1:11434/v1",
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
            baseUrl = "https://localhost:11434/v1",
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
