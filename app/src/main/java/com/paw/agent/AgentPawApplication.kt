package com.paw.agent

import android.app.Application
import com.paw.agent.core.agent.Agent
import com.paw.agent.core.agent.ToolRegistry
import com.paw.agent.core.llm.LlmClient
import com.paw.agent.core.llm.OpenAiCompatibleClient
import com.paw.agent.data.conversation.ConversationRepository
import com.paw.agent.data.conversation.InMemoryConversationRepository
import com.paw.agent.data.settings.DataStoreSettingsRepository
import com.paw.agent.data.settings.SettingsRepository

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

    val toolRegistry: ToolRegistry by lazy { ToolRegistry() }

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
