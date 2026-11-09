package com.paw.agent.ui.history

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.paw.agent.R
import com.paw.agent.core.llm.*
import com.paw.agent.core.llm.dto.ChatCompletionRequest
import com.paw.agent.core.model.MessageStatus
import com.paw.agent.core.skill.SkillEngine
import com.paw.agent.core.skill.SkillPresets
import com.paw.agent.core.skill.SkillRegistry
import com.paw.agent.data.conversation.TaskExecutionStats
import com.paw.agent.data.settings.UiThemeMode
import com.paw.agent.data.skill.SkillStore
import com.paw.agent.device.PhoneController
import com.paw.agent.ui.components.adaptive.AppSurface
import com.paw.agent.ui.components.adaptive.AppAlertDialog
import com.paw.agent.ui.components.adaptive.AppButton
import com.paw.agent.ui.components.adaptive.AppText
import com.paw.agent.ui.skills.SkillsHubScreen
import com.paw.agent.ui.skills.SkillsViewModel
import com.paw.agent.ui.theme.AgentPawAppTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.lang.reflect.Proxy

/** 实际组件的窄屏、大字号和交互回归；截图用于人工复查。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-w320dp-h900dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaintenanceUiReviewTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var root: View
    @After fun cleanup() { scope.cancel() }

    @Test fun historyMaterialLight() = history(UiThemeMode.MATERIAL, false)
    @Test fun historyMaterialDark() = history(UiThemeMode.MATERIAL, true)
    @Test fun historyMiuixLight() = history(UiThemeMode.MIUIX, false)
    @Test fun historyMiuixDark() = history(UiThemeMode.MIUIX, true)
    @Test fun skillsMaterialLight() = skills(UiThemeMode.MATERIAL, false)
    @Test fun skillsMaterialDark() = skills(UiThemeMode.MATERIAL, true)
    @Test fun skillsMiuixLight() = skills(UiThemeMode.MIUIX, false)
    @Test fun skillsMiuixDark() = skills(UiThemeMode.MIUIX, true)
    @Test fun longDialogMaterial() = longDialog(UiThemeMode.MATERIAL)
    @Test fun longDialogMiuix() = longDialog(UiThemeMode.MIUIX)

    private fun longDialog(theme: UiThemeMode) {
        var confirmed = false
        compose.setContent {
            AgentPawAppTheme(theme, darkTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    AppAlertDialog({}, { AppText("操作结果") }, { AppText("需要检查和修正的导入内容。".repeat(100)) },
                        { AppButton(onClick = { confirmed = true }) { AppText("确认操作") } },
                        { AppButton(onClick = {}) { AppText("取消操作") } })
                }
            }
        }
        compose.onNodeWithText("确认操作").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(confirmed) }
    }

    private fun history(theme: UiThemeMode, dark: Boolean) {
        val stats = TaskExecutionStats("record", "很长的任务名称，用于检查大字号下的状态标签与标题布局", "打开设置并查看应用通知权限，再回到主屏幕。", 123456, 9_876_000,
            MessageStatus.FAILED, emptyMap(), 1_700_000_000_000, 1_700_000_000_000)
        var selected = 0
        compose.setContent {
            root = LocalView.current
            AgentPawAppTheme(theme, darkTheme = dark, dynamicColor = false) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    AppSurface(modifier = Modifier.fillMaxSize()) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            HistoryTaskCard(stats, false, false, {}, {}, {})
                            HistoryTaskCard(stats.copy(title = "已选任务"), true, true, { selected++ }, {}, {})
                        }
                    }
                }
            }
        }
        val resources = RuntimeEnvironment.getApplication().resources
        compose.onAllNodesWithText(resources.getString(R.string.history_status_failed)).assertCountEquals(2)
        // 选择模式隐藏恢复/删除操作，卡片向辅助功能暴露选中状态。
        compose.onAllNodesWithContentDescription(resources.getString(R.string.history_delete_record)).assertCountEquals(1)
        compose.onNodeWithContentDescription(resources.getString(R.string.history_delete_record)).assertIsDisplayed()
        compose.onNode(isSelectable()).performScrollTo().assertIsSelected().performClick()
        compose.runOnIdle { assertEquals(1, selected) }
        compose.onAllNodesWithText(resources.getString(R.string.history_step_count, stats.stepCount))[0].performScrollTo().assertIsDisplayed()
        capture("history-${theme.name.lowercase()}-${if (dark) "dark" else "light"}")
    }

    private fun viewModel(): SkillsViewModel {
        val store = SkillStore(scope = scope, storageDirectory = temp.newFolder())
        runBlocking { store.awaitLoaded() }
        val controller = Proxy.newProxyInstance(PhoneController::class.java.classLoader, arrayOf(PhoneController::class.java)) { _, _, _ ->
            error("UI review must not operate the device")
        } as PhoneController
        val client = object : LlmClient {
            override fun complete(config: LlmConfig, request: ChatCompletionRequest) = emptyFlow<LlmChunk>()
            override suspend fun testConnection(config: LlmConfig) = Result.success(Unit)
        }
        return SkillsViewModel(store, SkillRegistry(), SkillEngine(controller), client, {})
    }

    private fun skills(theme: UiThemeMode, dark: Boolean) {
        val vm = viewModel()
        compose.setContent {
            root = LocalView.current
            AgentPawAppTheme(theme, darkTheme = dark, dynamicColor = false) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                    SkillsHubScreen(vm, {}, {}, {})
                }
            }
        }
        input("skills_search").performScrollTo().performTextInput("skill_return_home")
        compose.onNodeWithText(SkillPresets.builtIns.first().displayName).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(SkillPresets.builtIns[1].displayName).assertDoesNotExist()
        capture("skills-${theme.name.lowercase()}-${if (dark) "dark" else "light"}")
        val resources = RuntimeEnvironment.getApplication().resources
        compose.onAllNodesWithContentDescription(resources.getString(R.string.skills_import))[0].performClick()
        input("skill_import_text").performTextInput("{ unfinished")
        compose.onNodeWithTag("skill_import_confirm").performClick()
        compose.waitUntil(5_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            !vm.uiState.value.importing
        }
        input("skill_import_text").assertTextContains("{ unfinished")
        compose.onNodeWithTag("skill_import_confirm").assertIsEnabled().assertIsDisplayed()
    }

    private fun input(tag: String) = compose.onNode(hasSetTextAction() and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))))

    private fun capture(name: String) {
        val output = File("build/reports/maintenance-ui", "$name.png")
        output.parentFile?.mkdirs()
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }

    @Test fun riskMaterial() = risk(UiThemeMode.MATERIAL)
    @Test fun riskMiuix() = risk(UiThemeMode.MIUIX)

    private fun risk(theme: UiThemeMode) {
        var rejected = 0
        var discarded = 0
        compose.setContent {
            root = LocalView.current
            AgentPawAppTheme(theme, darkTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                    com.paw.agent.ui.chat.ChatScreen(messages = emptyList(), draft = "", isGenerating = false,
                        configReady = true, modelLabel = "Review",
                        activeBreakpoint = com.paw.agent.core.agent.breakpoint.TaskBreakpoint(originalGoal = "测试任务", stoppedAtStep = 3,
                            riskConfirmation = com.paw.agent.core.agent.breakpoint.RiskConfirmationDetails("发送消息", "测试联系人", "将发送消息，需要确认", toolName = "input_text")),
                        onDraftChange = {}, onSendOrStop = {}, onOpenSettings = {}, onOpenHistory = {}, onOpenSkills = {}, onNewConversation = {},
                        onRejectRiskAction = { rejected++ }, onDismissBreakpoint = { discarded++ })
                }
            }
        }
        val label = RuntimeEnvironment.getApplication().getString(R.string.chat_reject_action)
        compose.onNode(hasText(label) and hasClickAction()).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, rejected); assertEquals(0, discarded) }
        capture("risk-${theme.name.lowercase()}")
    }

}
