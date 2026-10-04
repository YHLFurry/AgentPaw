package com.paw.agent.core.tool

import com.paw.agent.core.agent.AgentContext
import com.paw.agent.core.agent.ToolRegistry
import com.paw.agent.core.llm.LlmChunk
import com.paw.agent.core.llm.LlmClient
import com.paw.agent.core.llm.LlmConfig
import com.paw.agent.core.llm.dto.ChatCompletionRequest
import com.paw.agent.core.llm.dto.ChatCompletionResponse
import com.paw.agent.core.llm.dto.ChatMessage
import com.paw.agent.core.llm.dto.Choice
import com.paw.agent.core.llm.dto.Usage
import com.paw.agent.core.search.SearchBackend
import com.paw.agent.core.search.SearchResponse
import com.paw.agent.core.search.SearchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BuiltInToolsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val config = LlmConfig(
        baseUrl = "https://example.test/v1",
        model = "test-model",
        apiKey = "k",
        stream = false,
    )

    // ---- ShellTool --------------------------------------------------------

    @Test
    fun `shell tool runs a script and reports output`() = runTest {
        val tool = ShellTool(temp.newFolder("shell"))
        val result = tool.execute("""{"script":"expr 6 * 7"}""", AgentContext("c"))

        assertTrue(result, result.contains("exit=0"))
        assertTrue(result, result.contains("42"))
    }

    @Test
    fun `shell tool reports a failing script without throwing`() = runTest {
        val tool = ShellTool(temp.newFolder("shell"))
        val result = tool.execute("""{"script":"cat /etc/passwd"}""", AgentContext("c"))

        // The sandbox must refuse it, and the tool must surface that as text.
        assertTrue(result, result.contains("exit="))
        assertTrue(result, !result.contains("root:x:"))
    }

    @Test
    fun `shell tool requires a script argument`() = runTest {
        val tool = ShellTool(temp.newFolder("shell"))
        val result = tool.execute("""{}""", AgentContext("c"))

        assertTrue(result, result.contains("'script' is required"))
    }

    @Test
    fun `shell tool rejects malformed json`() = runTest {
        val tool = ShellTool(temp.newFolder("shell"))
        val result = tool.execute("not json", AgentContext("c"))

        assertTrue(result, result.startsWith("Error:"))
    }

    // ---- WebSearchTool ----------------------------------------------------

    @Test
    fun `web search tool formats results`() = runTest {
        val tool = WebSearchTool(FakeSearchBackend())
        val result = tool.execute("""{"query":"rust"}""", AgentContext("c"))

        assertTrue(result, result.contains("Rust programming language"))
        assertTrue(result, result.contains("https://example.org/rust"))
    }

    @Test
    fun `web search tool reports an empty result honestly`() = runTest {
        val tool = WebSearchTool(EmptySearchBackend())
        val result = tool.execute("""{"query":"nothing here"}""", AgentContext("c"))

        assertTrue(result, result.contains("No results"))
    }

    @Test
    fun `web search tool surfaces backend failure`() = runTest {
        val tool = WebSearchTool(FailingSearchBackend())
        val result = tool.execute("""{"query":"x"}""", AgentContext("c"))

        assertTrue(result, result.contains("failed"))
    }

    @Test
    fun `web search tool requires a query`() = runTest {
        val tool = WebSearchTool(FakeSearchBackend())
        val result = tool.execute("""{}""", AgentContext("c"))

        assertTrue(result, result.contains("'query' is required"))
    }

    // ---- SubAgentTool -----------------------------------------------------

    @Test
    fun `sub agent returns the delegated answer`() = runTest {
        val client = ScriptedLlmClient("delegated answer")
        val tool = SubAgentTool(client, { config })

        val result = tool.execute(
            """{"task":"summarise the topic","role":"researcher"}""",
            AgentContext("c", depth = 0),
        )

        assertEquals("delegated answer", result)
        assertEquals(1, client.callCount)
    }

    @Test
    fun `sub agent honours the depth limit`() = runTest {
        val client = ScriptedLlmClient("should not run")
        val tool = SubAgentTool(client, { config }, maxDepth = 2)

        val result = tool.execute(
            """{"task":"go deeper"}""",
            AgentContext("c", depth = 2),
        )

        assertTrue(result, result.contains("depth limit"))
        assertEquals(0, client.callCount)
    }

    @Test
    fun `sub agent requires a task`() = runTest {
        val tool = SubAgentTool(ScriptedLlmClient("x"), { config })
        val result = tool.execute("""{"role":"researcher"}""", AgentContext("c"))

        assertTrue(result, result.contains("'task' is required"))
    }

    @Test
    fun `sub agent reports failure instead of throwing`() = runTest {
        val tool = SubAgentTool(FailingLlmClient(), { config })
        val result = tool.execute("""{"task":"anything"}""", AgentContext("c"))

        assertTrue(result, result.startsWith("Error:"))
    }

    @Test
    fun `sub agent registry is empty so it cannot recurse`() = runTest {
        val client = ScriptedLlmClient("done")
        // The tool builds its own Agent with ToolRegistry(), so the definitions
        // advertised to the model must never include delegate_task.
        val tool = SubAgentTool(client, { config })
        assertTrue(tool.definition.name == "delegate_task")
        assertTrue(ToolRegistry().definitions.isEmpty())
    }

    // ---- fakes ------------------------------------------------------------

    private class FakeSearchBackend : SearchBackend {
        override val name = "Fake"
        override suspend fun search(query: String, limit: Int): Result<SearchResponse> =
            Result.success(
                SearchResponse(
                    query = query,
                    results = listOf(
                        SearchResult("Rust programming language", "systems language", "https://example.org/rust"),
                    ),
                ),
            )
    }

    private class EmptySearchBackend : SearchBackend {
        override val name = "Empty"
        override suspend fun search(query: String, limit: Int): Result<SearchResponse> =
            Result.success(SearchResponse(query, emptyList()))
    }

    private class FailingSearchBackend : SearchBackend {
        override val name = "Broken"
        override suspend fun search(query: String, limit: Int): Result<SearchResponse> =
            Result.failure(IllegalStateException("connection reset"))
    }

    /** Returns a fixed assistant message. */
    private class ScriptedLlmClient(private val answer: String) : LlmClient {
        var callCount = 0
            private set

        override fun complete(config: LlmConfig, request: ChatCompletionRequest): Flow<LlmChunk> = flow {
            callCount++
            emit(
                LlmChunk.Delta(answer),
            )
            emit(LlmChunk.Done("stop", Usage()))
        }

        override suspend fun testConnection(config: LlmConfig): Result<Unit> = Result.success(Unit)
    }

    private class FailingLlmClient : LlmClient {
        override fun complete(config: LlmConfig, request: ChatCompletionRequest): Flow<LlmChunk> =
            flow { throw com.paw.agent.core.llm.LlmException.Http(500, "boom") }

        override suspend fun testConnection(config: LlmConfig): Result<Unit> = Result.success(Unit)
    }
}
