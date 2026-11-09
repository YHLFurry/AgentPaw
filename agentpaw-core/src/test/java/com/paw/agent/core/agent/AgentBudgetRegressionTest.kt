package com.paw.agent.core.agent

import com.paw.agent.core.llm.*
import com.paw.agent.core.llm.dto.ChatCompletionRequest
import com.paw.agent.core.model.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AgentBudgetRegressionTest {
    @Test fun `explicit eight overrides configuration and automatic rounds have an absolute limit`() = runTest {
        for ((override, configured, limit) in listOf(Triple(8, 30, 8), Triple(null, 0, 200), Triple(null, 999, 200))) {
            var calls = 0
            var mutations = 0
            val client = object : LlmClient {
                override fun complete(config: LlmConfig, request: ChatCompletionRequest) = flow {
                    emit(LlmChunk.ToolCalls(listOf(ToolCall("call_${calls++}", "probe", "{}"))))
                    emit(LlmChunk.Done("tool_calls", null))
                }
                override suspend fun testConnection(config: LlmConfig) = Result.success(Unit)
            }
            val tool = object : AgentTool {
                override val definition = ToolDefinition("probe", "test", """{"type":"object"}""")
                override suspend fun execute(arguments: String, context: AgentContext): String { mutations++; return "ok" }
            }
            val registry = ToolRegistry(listOf(tool))
            val agent = if (override == null) Agent(client, registry) else Agent(client, registry, maxToolRounds = override)
            val events = agent.run(
                LlmConfig(maxToolRounds = configured, maxTotalTokens = 2_000_000), emptyList(), enableAdaptivePacing = false).toList()
            assertEquals(limit, mutations)
            assertTrue(events.last() is AgentEvent.Failed)
        }
    }
}
