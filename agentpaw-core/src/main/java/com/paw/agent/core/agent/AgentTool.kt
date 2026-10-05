package com.paw.agent.core.agent

import com.paw.agent.core.model.ToolDefinition

/**
 * A capability the agent can invoke while producing an answer.
 *
 * Implementations must be safe to call from a background dispatcher and should
 * honour [AgentContext.isCancelled] for long-running work.
 */
interface AgentTool {

    val definition: ToolDefinition

    /**
     * Runs the tool.
     *
     * @param arguments raw JSON string supplied by the model.
     * @return text handed back to the model as the tool result.
     * @throws Exception on failure; the agent converts it into an error result
     *   so the model can recover rather than aborting the whole turn.
     */
    suspend fun execute(arguments: String, context: AgentContext): String
}

/**
 * Per-run state handed to tools: which conversation is active, how deep the
 * agent is nested, and whether the user has asked to stop.
 *
 * [depth] starts at 0 for a top-level turn and increases with each delegated
 * sub-agent, which is what stops mutually-delegating agents from looping.
 */
class AgentContext(
    val conversationId: String,
    val depth: Int = 0,
    private val cancelledCheck: () -> Boolean = { false },
) {
    fun isCancelled(): Boolean = cancelledCheck()

    /** True when another delegation level is still permitted. */
    fun canNest(limit: Int): Boolean = depth < limit
}

/**
 * Registry of the tools available to the agent.
 *
 * Registration order is preserved, and the resulting [definitions] list is what
 * gets sent to the provider, so tool ordering is stable across requests.
 */
class ToolRegistry(tools: List<AgentTool> = emptyList()) {

    private val toolsByName: Map<String, AgentTool> = tools.associateBy { it.definition.name }

    val definitions: List<ToolDefinition> = tools.map { it.definition }

    val isEmpty: Boolean get() = toolsByName.isEmpty()

    fun find(name: String): AgentTool? = toolsByName[name]

    fun with(tool: AgentTool): ToolRegistry = ToolRegistry(toolsByName.values + tool)
}
