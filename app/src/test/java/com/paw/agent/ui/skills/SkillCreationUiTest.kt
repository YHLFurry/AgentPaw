package com.paw.agent.ui.skills

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.paw.agent.R
import com.paw.agent.AppContainer
import com.paw.agent.ui.AgentPawApp
import com.paw.agent.core.llm.LlmChunk
import com.paw.agent.core.llm.LlmClient
import com.paw.agent.core.llm.LlmConfig
import com.paw.agent.core.llm.dto.ChatCompletionRequest
import com.paw.agent.core.skill.SkillEngine
import com.paw.agent.core.skill.SkillRegistry
import com.paw.agent.data.settings.UiThemeMode
import com.paw.agent.data.skill.SkillStore
import com.paw.agent.device.PhoneController
import com.paw.agent.ui.theme.AgentPawAppTheme
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w412dp-h900dp-xxhdpi")
class SkillCreationUiTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun cleanup() { scope.cancel() }

    @Test fun createFromHubInMaterial() = createFromHub(UiThemeMode.MATERIAL)
    @Test fun createFromHubInMiuix() = createFromHub(UiThemeMode.MIUIX)
    @Test @Config(qualifiers = "zh-rCN-w412dp-h900dp-xxhdpi")
    fun createFromHubInChineseMiuix() = createFromHub(UiThemeMode.MIUIX)
    @Test @Config(sdk = [31], qualifiers = "zh-rCN-w412dp-h900dp-xxhdpi")
    fun createOnAndroid12Miuix() = createFromHub(UiThemeMode.MIUIX)
    @Test @Config(qualifiers = "zh-rCN-w412dp-h900dp-xxhdpi")
    fun createWithLargeFonts() = createFromHub(UiThemeMode.MIUIX, fontScale = 2f)
    @Test @Config(sdk = [36], qualifiers = "zh-rCN-w412dp-h900dp-xxhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun createOnAndroid16Material() = createFromHub(UiThemeMode.MATERIAL)
    @Test @Config(sdk = [36], qualifiers = "zh-rCN-w412dp-h900dp-xxhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun createOnAndroid16Miuix() = createFromHub(UiThemeMode.MIUIX)
    @Test @Config(sdk = [36], qualifiers = "zh-rCN-w412dp-h900dp-xxhdpi")
    fun createThroughApplicationInMaterial() = createThroughApplication(UiThemeMode.MATERIAL)
    @Test @Config(sdk = [36], qualifiers = "zh-rCN-w412dp-h900dp-xxhdpi")
    fun createThroughApplicationInMiuix() = createThroughApplication(UiThemeMode.MIUIX)

    @Test fun discardConfirmationProtectsMaterialDraft() = checkDiscard(UiThemeMode.MATERIAL)
    @Test fun discardConfirmationProtectsMiuixDraft() = checkDiscard(UiThemeMode.MIUIX)

    private fun checkDiscard(theme: UiThemeMode) {
        createFromHub(theme, onReady = { vm ->
            val resources = RuntimeEnvironment.getApplication().resources
            compose.runOnIdle { vm.appendDslLine("等待 1") }
            compose.onNodeWithContentDescription(resources.getString(R.string.cd_back)).performClick()
            compose.onNodeWithText(resources.getString(R.string.skills_discard_title)).assertIsDisplayed()
            compose.onNodeWithText(resources.getString(R.string.action_cancel)).performClick()
            compose.runOnIdle { assertTrue(vm.uiState.value.hasUnsavedChanges) }
            compose.onNodeWithContentDescription(resources.getString(R.string.cd_back)).performClick()
            compose.onNodeWithText(resources.getString(R.string.skills_discard_action)).performClick()
            compose.waitForIdle()
            compose.runOnIdle { assertNull(vm.uiState.value.editing) }
        })
    }

    private fun createThroughApplication(theme: UiThemeMode) {
        val application = RuntimeEnvironment.getApplication()
        val container = AppContainer(application)
        try {
            compose.setContent {
                AgentPawAppTheme(theme, darkTheme = false, dynamicColor = false) {
                    AgentPawApp(container)
                }
            }
            compose.onNodeWithContentDescription(application.getString(R.string.skills_title)).performClick()
            compose.onNodeWithText(application.getString(R.string.skills_create)).performClick()
            compose.onAllNodesWithText(application.getString(R.string.skills_mode_simple)).onFirst().assertIsDisplayed()
        } finally {
            container.appScope.cancel()
        }
    }

    private fun createFromHub(theme: UiThemeMode, fontScale: Float = 1f, onReady: (SkillsViewModel) -> Unit = {}) {
        val store = SkillStore(scope = scope, storageDirectory = temp.newFolder())
        runBlocking { store.awaitLoaded() }
        val controller = Proxy.newProxyInstance(PhoneController::class.java.classLoader,
            arrayOf(PhoneController::class.java)) { _, method, _ ->
            error("Creating a skill must not operate the device: ${method.name}")
        } as PhoneController
        val client = object : LlmClient {
            override fun complete(config: LlmConfig, request: ChatCompletionRequest): Flow<LlmChunk> = emptyFlow()
            override suspend fun testConnection(config: LlmConfig) = Result.success(Unit)
        }
        val vm = SkillsViewModel(store, SkillRegistry(), SkillEngine(controller), client, {})
        compose.setContent {
            AgentPawAppTheme(theme, darkTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = "skills") {
                        composable("skills") { SkillsHubScreen(vm, onBack = {},
                            onOpenEditor = { nav.navigate("skills/editor") }, onOpenWish = {}) }
                        composable("skills/editor") { SkillEditorScreen(vm, onBack = { nav.popBackStack() }) }
                    }
                }
            }
        }
        val resources = RuntimeEnvironment.getApplication().resources
        compose.onNodeWithText(resources.getString(R.string.skills_create)).performClick()
        compose.onAllNodesWithText(resources.getString(R.string.skills_mode_simple)).onFirst().assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.action_save)).assertIsDisplayed()
        onReady(vm)
    }
}
