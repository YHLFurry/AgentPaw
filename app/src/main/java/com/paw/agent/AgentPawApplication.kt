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
import com.paw.agent.core.skill.SkillEngine
import com.paw.agent.core.skill.SkillRegistry
import com.paw.agent.core.skill.SkillRunOptions
import com.paw.agent.core.tool.ShellTool
import com.paw.agent.core.tool.SubAgentTool
import com.paw.agent.core.tool.WebSearchTool
import com.paw.agent.data.conversation.ConversationRepository
import com.paw.agent.data.conversation.InMemoryConversationRepository
import com.paw.agent.data.settings.AppSettings
import com.paw.agent.data.settings.DataStoreSettingsRepository
import com.paw.agent.data.settings.SettingsRepository
import com.paw.agent.data.skill.SkillStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Manual dependency container.
 *
 * A DI framework is overkill at this size; if the graph grows, this class is the
 * single place to swap in Hilt/Koin without touching the UI layer.
 */
class AppContainer(application: Application) {

    /** 应用级协程域：技能同步、后台加载等不依赖界面生命周期的工作。 */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settingsRepository: SettingsRepository by lazy {
        DataStoreSettingsRepository(application)
    }

    val conversationRepository: com.paw.agent.data.conversation.PersistentConversationRepository by lazy {
        com.paw.agent.data.conversation.PersistentConversationRepository(application)
    }

    /** 用户技能持久化（v2 格式，支持 v1 自动迁移）。 */
    val skillStore: SkillStore by lazy { SkillStore(application, appScope) }

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
     * 当前生效的执行策略（技能 shell、风险确认豁免、敏感页放行）。
     * 由 [updateRuntimeSettings] 在设置变化时刷新，技能工具每次执行都会读取最新值。
     */
    @Volatile
    private var skillRunOptions: SkillRunOptions = SkillRunOptions()

    val phoneController: com.paw.agent.device.HybridPhoneController by lazy {
        com.paw.agent.device.HybridPhoneController(application)
    }

    val skillRegistry: SkillRegistry by lazy { SkillRegistry() }

    val skillEngine: SkillEngine by lazy {
        SkillEngine(
            controller = phoneController,
            options = { skillRunOptions },
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
     * 技能工具不在这里一次性拼进列表：它们由 [SkillStore.syncInto] 按当前启用的
     * 定义动态注册/注销，避免“删掉的技能仍然留在模型工具表里”。
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
            ),
        ).also { registry ->
            appScope.launch { skillStore.syncInto(skillRegistry, registry, skillEngine) }
        }
    }

    val agent: Agent by lazy { Agent(llmClient = llmClient, toolRegistry = toolRegistry) }

    val agentTaskRunner: com.paw.agent.runner.AgentTaskRunner by lazy {
        com.paw.agent.runner.AgentTaskRunner(
            context = application,
            agent = agent,
            conversationRepository = conversationRepository,
            settingsRepository = settingsRepository,
        )
    }

    /**
     * 把设置里的安全策略下发到真正的执行组件。
     *
     * 这是「开关真的生效」的唯一接线点：网络策略给 LLM 客户端，技能执行策略给引擎。
     */
    fun updateRuntimeSettings(settings: AppSettings) {
        skillRunOptions = settings.skillRuntimePolicy()
        (llmClient as? OpenAiCompatibleClient)?.networkPolicy = settings.networkPolicy()
    }

    /** 技能增删改后调用，把变更同步到模型可调用的工具表。 */
    suspend fun syncSkills() {
        skillStore.syncInto(skillRegistry, toolRegistry, skillEngine)
    }

    /**
     * 安全回溯历史会话并切回主交互流：
     * 自动停止当前任何进行中任务，清除残留的断点状态机与浮窗，
     * 避免旧任务的断点卡片或执行状态污染新载入的任务。
     */
    fun restoreConversation(id: String): Boolean {
        agentTaskRunner.stop()
        agentTaskRunner.dismissBreakpoint()
        return conversationRepository.loadConversation(id)
    }
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
        // 提前把持久化的技能注册进工具表，避免首条指令到达时技能尚未就绪
        container.appScope.launch {
            container.toolRegistry
            container.syncSkills()
        }
    }
}
