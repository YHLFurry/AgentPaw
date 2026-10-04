package com.paw.agent

import android.app.Application
import com.paw.agent.core.agent.Agent
import com.paw.agent.core.agent.ToolRegistry
import com.paw.agent.core.llm.LlmClient
import com.paw.agent.core.llm.LlmConfig
import com.paw.agent.core.llm.OpenAiCompatibleClient
import com.paw.agent.core.search.DuckDuckGoSearchBackend
import com.paw.agent.core.search.SearchBackend
import com.paw.agent.core.shell.SandboxLimits
import com.paw.agent.core.tool.ShellTool
import com.paw.agent.core.tool.SubAgentTool
import com.paw.agent.core.tool.WebSearchTool
import com.paw.agent.data.conversation.ConversationRepository
import com.paw.agent.data.conversation.InMemoryConversationRepository
import com.paw.agent.data.settings.DataStoreSettingsRepository
import com.paw.agent.data.settings.SettingsRepository
import java.io.File

/**
 * Manual dependency container.
 *
 * A DI framework is overkill at this size; if the graph grows, this class is the
 * single place to swap in Hilt/Koin without touching the UI layer.
 */
class AppContainer(application: Application) {

    val settingsRepository: SettingsRepository by lazy {
        DataStoreSettingsRepository(application)
    }

    val conversationRepository: ConversationRepository by lazy {
        InMemoryConversationRepository()
    }

    val llmClient: LlmClient by lazy { OpenAiCompatibleClient() }

    val searchBackend: SearchBackend by lazy { DuckDuckGoSearchBackend() }

    /**
     * The config the current turn is using.
     *
     * Tools are built once, but the LLM settings can change at any time, so the
     * UI pushes the latest value here. [SubAgentTool] reads it to configure the
     * sub-agent it spawns, which avoids blocking on the DataStore flow.
     */
    @Volatile
    var activeLlmConfig: LlmConfig = LlmConfig()
        private set

    fun onLlmConfigChanged(config: LlmConfig) {
        activeLlmConfig = config
    }

    /**
     * Root of the script sandbox. Everything a script reads or writes lives
     * under here, so the interpreter can refuse to step outside it.
     */
    private val sandboxRoot: File by lazy {
        File(application.filesDir, "sandbox").apply { mkdirs() }
    }

    /**
     * The tools the agent may call.
     *
     * `delegate_task` is listed too, but the sub-agent it spawns gets an empty
     * registry, so delegation is always a leaf and cannot recurse.
     */
    val toolRegistry: ToolRegistry by lazy {
        ToolRegistry(
            listOf(
                ShellTool(sandboxRoot, SandboxLimits()),
                WebSearchTool(searchBackend),
                SubAgentTool(llmClient, { activeLlmConfig }),
            ),
        )
    }

    val agent: Agent by lazy { Agent(llmClient = llmClient, toolRegistry = toolRegistry) }
}

class AgentPawApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
