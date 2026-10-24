package com.paw.agent.core.agent

import com.paw.agent.core.llm.LlmChunk
import com.paw.agent.core.llm.LlmClient
import com.paw.agent.core.llm.LlmConfig
import com.paw.agent.core.llm.LlmException
import com.paw.agent.core.llm.dto.ChatCompletionRequest
import com.paw.agent.core.model.Message
import com.paw.agent.core.model.MessageRole
import com.paw.agent.core.model.ToolDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the agent loop with a scripted [LlmClient], so the tool-calling
 * behaviour can be verified without any network access.
 */
class AgentTest {
    @Test fun `context error retries once with compressed history`() = runTest {
        val requests = mutableListOf<ChatCompletionRequest>()
        val client = object : LlmClient {
            override fun complete(config: LlmConfig, request: ChatCompletionRequest): Flow<LlmChunk> = flow {
                requests += request
                if (requests.size == 1) throw LlmException.Http(400, "context length exceeded")
                emit(LlmChunk.Delta("ok"))
            }
            override suspend fun testConnection(config: LlmConfig): Result<Unit> = Result.success(Unit)
        }
        val history = listOf(user("original goal").copy(id = "old"),
            Message("tool_request", MessageRole.ASSISTANT, "", toolCalls = listOf(com.paw.agent.core.model.ToolCall("t", "read", "{}"))),
            Message("tool_result", MessageRole.TOOL, "observation ".repeat(3000), toolCallId = "t"),
            user("latest task").copy(id = "latest"))
        val events = Agent(client).run(config.copy(maxTotalTokens = 4096, maxTokens = 1024), history).toList()
        assertEquals("ok", events.filterIsInstance<AgentEvent.Completed>().single().message.content)
        assertEquals(2, requests.size)
        assertTrue(requests[1].messages.size <= requests[0].messages.size)
    }

    @Test fun `unsupported token parameter does not trigger compression retries`() = runTest {
        var requests = 0
        val client = object : LlmClient {
            override fun complete(config: LlmConfig, request: ChatCompletionRequest): Flow<LlmChunk> = flow {
                requests++
                throw LlmException.Http(400, "unsupported parameter max_tokens")
            }
            override suspend fun testConnection(config: LlmConfig): Result<Unit> = Result.success(Unit)
        }
        val events = Agent(client).run(config, listOf(user("hi"))).toList()
        assertEquals(1, requests)
        assertTrue(events.last() is AgentEvent.Failed)
    }

    @Test fun `output token cutoff is a visible failure and never executes partial tools`() = runTest {
        var executed = false
        val tool = object : AgentTool {
            override val definition = ToolDefinition("tap", "tap", "{}")
            override suspend fun execute(arguments: String, context: AgentContext): String { executed = true; return "ok" }
        }
        val client = ScriptedClient(listOf(listOf(LlmChunk.Delta("partial"),
            LlmChunk.ToolCalls(listOf(com.paw.agent.core.model.ToolCall("t", "tap", "{}"))),
            LlmChunk.Done("length", null))))
        val events = Agent(client, ToolRegistry(listOf(tool))).run(config, listOf(user("hi"))).toList()
        assertTrue(events.last() is AgentEvent.Failed)
        assertTrue(events.filterIsInstance<AgentEvent.Failed>().single().message.error.orEmpty().contains("Token"))
        assertTrue(!executed)
    }
    @Test
    fun `structured tool errors are failures and assistant ids differ across rounds`() = runTest {
        val tool = object : AgentTool {
            override val definition = ToolDefinition("fails", "Failure", "{}")
            override suspend fun execute(arguments: String, context: AgentContext) = """{"status":"error","message":"permission denied"}"""
        }
        val client = ScriptedClient(listOf(
            listOf(LlmChunk.Delta("trying"), LlmChunk.ToolCalls(listOf(com.paw.agent.core.model.ToolCall("c1", "fails", "{}")))),
            listOf(LlmChunk.Delta("failed")),
        ))
        val events = Agent(client, ToolRegistry(listOf(tool))).run(config, listOf(user("test")), enableAdaptivePacing = false).toList()
        assertTrue(events.filterIsInstance<AgentEvent.ToolFinished>().single().result.isError)
        val deltaIds = events.filterIsInstance<AgentEvent.AssistantDelta>().map { it.message.id }
        assertEquals(2, deltaIds.distinct().size)
    }

    private val config = LlmConfig(
        baseUrl = "https://example.test/v1",
        model = "test-model",
        apiKey = "key",
        stream = false,
    )

    private fun assistant(content: String) = Message(
        id = "a1",
        role = MessageRole.ASSISTANT,
        content = content,
    )

    private fun user(content: String) = Message(
        id = "u1",
        role = MessageRole.USER,
        content = content,
    )

    private class ScriptedClient(
        private val script: List<List<LlmChunk>>,
    ) : LlmClient {
        var callCount = 0
            private set

        override fun complete(
            config: LlmConfig,
            request: ChatCompletionRequest,
        ): Flow<LlmChunk> = flow {
            val turn = script.getOrElse(callCount) { listOf(LlmChunk.Delta("done")) }
            callCount++
            turn.forEach { emit(it) }
        }

        override suspend fun testConnection(config: LlmConfig): Result<Unit> =
            Result.success(Unit)
    }

    private class EchoTool : AgentTool {
        override val definition = ToolDefinition(
            name = "echo",
            description = "Echoes the input back.",
            parametersSchema = """{"type":"object","properties":{"text":{"type":"string"}}}""",
        )

        override suspend fun execute(arguments: String, context: AgentContext): String =
            "echoed:$arguments"
    }

    @Test
    fun `plain answer completes without tool calls`() = runTest {
        val client = ScriptedClient(listOf(listOf(LlmChunk.Delta("Hello "), LlmChunk.Delta("there"))))
        val agent = Agent(client)

        val events = agent.run(config, listOf(user("hi"))).toList()

        val completed = events.filterIsInstance<AgentEvent.Completed>().single()
        assertEquals("Hello there", completed.message.content)
        assertEquals(1, client.callCount)
    }

    @Test
    fun `deltas accumulate into the assistant message`() = runTest {
        val client = ScriptedClient(listOf(listOf(LlmChunk.Delta("a"), LlmChunk.Delta("b"), LlmChunk.Delta("c"))))
        val agent = Agent(client, streamUpdateIntervalMs = 0)

        val events = agent.run(config, listOf(user("hi"))).toList()
        val deltas = events.filterIsInstance<AgentEvent.AssistantDelta>()

        assertEquals(listOf("a", "ab", "abc"), deltas.map { it.message.content })
    }

    @Test fun `stream batching retains the final text while bounding UI emissions`() = runTest {
        val client = ScriptedClient(listOf((1..1000).map { LlmChunk.Delta("x") }))
        val events = Agent(client, streamUpdateIntervalMs = 60_000).run(config, listOf(user("hi"))).toList()
        assertTrue(events.filterIsInstance<AgentEvent.AssistantDelta>().size <= 2)
        assertEquals("x".repeat(1000), events.filterIsInstance<AgentEvent.Completed>().single().message.content)
    }

    @Test
    fun `tool call runs the tool and feeds the result back`() = runTest {
        val toolCall = com.paw.agent.core.model.ToolCall(
            id = "call_1",
            name = "echo",
            arguments = """{"text":"hi"}""",
        )
        val client = ScriptedClient(
            listOf(
                listOf(LlmChunk.ToolCalls(listOf(toolCall))),
                listOf(LlmChunk.Delta("done")),
            ),
        )
        val agent = Agent(client, ToolRegistry(listOf(EchoTool())))

        val events = agent.run(config, listOf(user("hi"))).toList()

        val started = events.filterIsInstance<AgentEvent.ToolStarted>().single()
        val finished = events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertEquals("echo", started.call.name)
        assertEquals("echoed:{\"text\":\"hi\"}", finished.result.content)
        assertTrue(events.any { it is AgentEvent.Completed })
        // two model calls: one to request the tool, one after the result
        assertEquals(2, client.callCount)
    }

    @Test
    fun `unknown tool yields an error result instead of aborting the turn`() = runTest {
        val toolCall = com.paw.agent.core.model.ToolCall(
            id = "call_1",
            name = "does_not_exist",
            arguments = "{}",
        )
        val client = ScriptedClient(
            listOf(
                listOf(LlmChunk.ToolCalls(listOf(toolCall))),
                listOf(LlmChunk.Delta("recovered")),
            ),
        )
        val agent = Agent(client, ToolRegistry(listOf(EchoTool())))

        val events = agent.run(config, listOf(user("hi"))).toList()

        val result = events.filterIsInstance<AgentEvent.ToolFinished>()
            .single().result
        assertTrue(result.isError)
        assertTrue(result.content.contains("does_not_exist"))
        assertTrue(events.any { it is AgentEvent.Completed })
    }

    @Test
    fun `cancellation before the first call emits Cancelled`() = runTest {
        val client = ScriptedClient(listOf(listOf(LlmChunk.Delta("never"))))
        val agent = Agent(client)

        val events = agent.run(config, listOf(user("hi")), isCancelled = { true }).toList()

        assertTrue(events.isNotEmpty())
        assertTrue(events.first() is AgentEvent.Cancelled)
        assertEquals(0, client.callCount)
    }

    @Test
    fun `failing client surfaces a Failed event`() = runTest {
        val client = object : LlmClient {
            override fun complete(
                config: LlmConfig,
                request: ChatCompletionRequest,
            ): Flow<LlmChunk> = flow {
                throw com.paw.agent.core.llm.LlmException.Http(500, "boom")
            }

            override suspend fun testConnection(config: LlmConfig): Result<Unit> =
                Result.success(Unit)
        }
        val agent = Agent(client)

        val events = agent.run(config, listOf(user("hi"))).toList()

        val failed = events.filterIsInstance<AgentEvent.Failed>().single()
        assertTrue(failed.message.error?.contains("500") == true)
    }

    @Test
    fun `tool round budget stops a looping model`() = runTest {
        // A client that always asks for another tool call.
        val client = ScriptedClient(
            List(20) {
                listOf(
                    LlmChunk.ToolCalls(
                        listOf(
                            com.paw.agent.core.model.ToolCall("id", "echo", "{}"),
                        ),
                    ),
                )
            },
        )
        val agent = Agent(client, ToolRegistry(listOf(EchoTool())), maxToolRounds = 2)

        val events = agent.run(config, listOf(user("loop"))).toList()

        assertTrue(events.any { it is AgentEvent.Failed })
        // 1 initial call + 2 tool rounds
        assertEquals(3, client.callCount)
    }

    @Test
    fun `setting maxToolRounds to 0 allows unlimited rounds until completed`() = runTest {
        val toolCall = com.paw.agent.core.model.ToolCall("id", "echo", "{}")
        // 10 turns of tool calls followed by final answer
        val script = List(10) { listOf(LlmChunk.ToolCalls(listOf(toolCall))) } +
            listOf(listOf(LlmChunk.Delta("final answer")))
        val client = ScriptedClient(script)
        // Passing 0 should be treated as unlimited steps
        val agent = Agent(client, ToolRegistry(listOf(EchoTool())), maxToolRounds = 0)

        val events = agent.run(config, listOf(user("go"))).toList()

        val completed = events.filterIsInstance<AgentEvent.Completed>().singleOrNull()
        org.junit.Assert.assertNotNull("Should complete successfully without step cap when set to 0", completed)
        assertEquals("final answer", completed!!.message.content)
        // 1 initial call + 10 tool rounds = 11 calls
        assertEquals(11, client.callCount)
    }

    @Test
    fun `each tool call produces exactly one result event`() = runTest {
        val toolCall = com.paw.agent.core.model.ToolCall("c1", "echo", "{}")
        val client = ScriptedClient(
            listOf(
                listOf(LlmChunk.ToolCalls(listOf(toolCall))),
                listOf(LlmChunk.Delta("ok")),
            ),
        )
        val agent = Agent(client, ToolRegistry(listOf(EchoTool())))

        val events = agent.run(config, listOf(user("hi"))).toList()

        val results = events.filterIsInstance<AgentEvent.ToolFinished>()
        assertEquals(1, results.size)
        assertEquals("c1", results.single().result.toolCallId)
        assertEquals(1, events.filterIsInstance<AgentEvent.ToolStarted>().size)
    }
}
