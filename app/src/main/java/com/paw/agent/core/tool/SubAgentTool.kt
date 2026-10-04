package com.paw.agent.core.tool

import com.paw.agent.core.agent.Agent
import com.paw.agent.core.agent.AgentContext
import com.paw.agent.core.agent.AgentEvent
import com.paw.agent.core.agent.AgentTool
import com.paw.agent.core.agent.ToolRegistry
import com.paw.agent.core.llm.LlmClient
import com.paw.agent.core.llm.LlmConfig
import com.paw.agent.core.model.Message
import com.paw.agent.core.model.MessageRole
import com.paw.agent.core.model.ToolDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Delegates a self-contained sub-task to a nested agent.
 *
 * The sub-agent gets its own system prompt and **no tools of its own**, so a
 * delegated task is a leaf: it cannot delegate again, cannot loop, and cannot
 * touch the sandbox or the network. Its final answer comes back as an ordinary
 * tool result for the parent to use.
 *
 * That keeps a long multi-step chain out of the main conversation and lets one
 * prompt own a whole sub-task end to end.
 *
 * @param maxDepth how many delegation levels are allowed from the top-level turn.
 * @param maxToolRounds rounds the sub-agent may use for its own tool calls.
 */
class SubAgentTool(
    private val llmClient: LlmClient,
    private val configProvider: () -> LlmConfig,
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
    private val maxToolRounds: Int = DEFAULT_TOOL_ROUNDS,
) : AgentTool {

    override val definition = ToolDefinition(
        name = "delegate_task",
        description = """
            Run a self-contained sub-task with a fresh agent and return its final
            answer. Use this when a task needs several steps of its own, so the
            main conversation stays focused on the user's actual question. The
            'task' must make sense on its own, without conversation context. The
            sub-agent cannot delegate further and has no tools.
        """.trimIndent(),
        parametersSchema = """
            {
              "type": "object",
              "properties": {
                "task": {
                  "type": "string",
                  "description": "The full sub-task, self-contained."
                },
                "role": {
                  "type": "string",
                  "description": "Optional expertise for the sub-agent, e.g. 'meticulous data analyst'."
                }
              },
              "required": ["task"]
            }
        """.trimIndent(),
    )

    override suspend fun execute(arguments: String, context: AgentContext): String {
        val request = runCatching {
            Json.parseToJsonElement(arguments).jsonObject
        }.getOrElse {
            return "Error: could not parse the arguments (${it.message})."
        }

        val task = request.string("task")
        if (task.isBlank()) return "Error: 'task' is required."

        if (!context.canNest(maxDepth)) {
            return "Error: delegation depth limit ($maxDepth) reached. " +
                "Complete the remaining work directly instead of delegating."
        }
        if (context.isCancelled()) return "Error: cancelled before the sub-agent started."

        // The sub-agent is rebuilt per call so no state leaks between delegations.
        val subAgent = Agent(
            llmClient = llmClient,
            toolRegistry = ToolRegistry(),
            maxToolRounds = maxToolRounds,
        )

        val role = request.string("role")
        val config = configProvider().copy(systemPrompt = systemPromptFor(role))

        return try {
            var answer = ""
            var failure: String? = null

            subAgent.run(
                config = config,
                history = listOf(
                    Message(id = "sub_task", role = MessageRole.USER, content = task),
                ),
                isCancelled = { context.isCancelled() },
                depth = context.depth + 1,
            ).collect { event ->
                currentCoroutineContext().ensureActive()
                when (event) {
                    is AgentEvent.Completed -> answer = event.message.content
                    is AgentEvent.Failed -> failure = event.message.error
                    is AgentEvent.Cancelled -> failure = "cancelled"
                    else -> Unit
                }
            }

            when {
                failure != null -> "Error: the sub-agent failed (${failure ?: "unknown"})."
                answer.isBlank() -> "Error: the sub-agent returned no answer."
                else -> answer
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Error: the sub-agent could not run (${e.message})."
        }
    }

    private fun systemPromptFor(role: String): String {
        val base = LlmConfig.DEFAULT_SYSTEM_PROMPT
        if (role.isBlank()) return base
        return "$base\n\nYou are acting as a ${role.trim()}. " +
            "Complete the delegated task on your own and reply with the result only — " +
            "no preamble and no questions about the parent task."
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.content.orEmpty().trim()

    companion object {
        const val DEFAULT_MAX_DEPTH = 2
        const val DEFAULT_TOOL_ROUNDS = 4
    }
}
