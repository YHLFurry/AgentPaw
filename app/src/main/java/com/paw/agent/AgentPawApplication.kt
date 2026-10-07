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

    val conversationRepository: com.paw.agent.data.conversation.PersistentConversationRepository by lazy {
        com.paw.agent.data.conversation.PersistentConversationRepository(application)
    }

    val customSkillRepository: com.paw.agent.data.skill.CustomSkillRepository by lazy {
        com.paw.agent.data.skill.CustomSkillRepository(application)
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

    val phoneController: com.paw.agent.device.HybridPhoneController by lazy {
        com.paw.agent.device.HybridPhoneController(application)
    }

    val skillRegistry: com.paw.agent.core.skill.SkillRegistry by lazy {
        com.paw.agent.core.skill.SkillRegistry(
            listOf(
                com.paw.agent.core.skill.ReturnHomeAndResetSkill(),
                com.paw.agent.core.skill.OpenAndSearchSkill(),
                com.paw.agent.core.skill.ScrollAndFindSkill(),
            ),
        )
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
                // Built-in utility tools
                ShellTool(sandboxRoot, SandboxLimits()),
                WebSearchTool(searchBackend),
                SubAgentTool(llmClient, { activeLlmConfig }),
                // Android Phone Tools
                com.paw.agent.core.tool.android.TakeScreenshotTool(phoneController),
                com.paw.agent.core.tool.android.TapTool(phoneController),
                com.paw.agent.core.tool.android.DoubleTapTool(phoneController),
                com.paw.agent.core.tool.android.LongPressTool(phoneController),
                com.paw.agent.core.tool.android.SwipeTool(phoneController),
                com.paw.agent.core.tool.android.InputTextTool(phoneController),
                com.paw.agent.core.tool.android.KeyActionTool(phoneController),
                com.paw.agent.core.tool.android.LaunchAppTool(phoneController),
                com.paw.agent.core.tool.android.DeepLinkTool(phoneController),
                com.paw.agent.core.tool.android.GetScreenStateTool(phoneController),
                com.paw.agent.core.tool.android.ClickElementTool(phoneController),
                com.paw.agent.core.tool.android.WaitTool(),
            ) + skillRegistry.toTools(phoneController),
        ).also {
            customSkillRepository.syncToSkillRegistry(skillRegistry, it, phoneController)
        }
    }

    val agent: Agent by lazy { Agent(llmClient = llmClient, toolRegistry = toolRegistry) }
}

class AgentPawApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 初始化 Shizuku 监听（binder 到达 / 授权结果回调），保证设置页与 Agent 运行期状态实时可用
        com.paw.agent.device.shizuku.ShizukuInitializer.initialize()
        // 尽早注册宿主 APP 前后台监听（必须早于首个 Activity 的 onResume），
        // 供 Maven 包的停止悬浮窗判断"用户是否已离开本体 APP"
        com.paw.agent.device.floating.AgentAppForegroundMonitor.install(this)
    }
}
