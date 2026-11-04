package com.paw.agent.ui.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.unit.Density
import com.paw.agent.core.llm.LlmConfig
import com.paw.agent.core.llm.LlmProvider
import com.paw.agent.R
import com.paw.agent.data.settings.UiThemeMode
import com.paw.agent.ui.components.adaptive.rememberAppSnackbarHostState
import com.paw.agent.ui.theme.AgentPawAppTheme
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 原生绘制并导出实际页面，供布局和明暗主题视觉复查。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiAppearanceReviewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun materialLight() = capture(UiThemeMode.MATERIAL, false, "material-light")
    @Test fun materialDark() = capture(UiThemeMode.MATERIAL, true, "material-dark")
    @Test fun miuixLight() = capture(UiThemeMode.MIUIX, false, "miuix-light")
    @Test fun miuixDark() = capture(UiThemeMode.MIUIX, true, "miuix-dark")
    @Test fun materialGeneration() = capture(UiThemeMode.MATERIAL, false, "material-generation", generation = true)
    @Test fun miuixGeneration() = capture(UiThemeMode.MIUIX, true, "miuix-generation", generation = true)
    @Test @Config(qualifiers = "zh-rCN-w320dp-h740dp-xxhdpi")
    fun materialNarrowLargeFont() = capture(UiThemeMode.MATERIAL, false, "material-large-font", fontScale = 1.5f)
    @Test @Config(qualifiers = "zh-rCN-w320dp-h740dp-xxhdpi")
    fun miuixNarrowLargeFont() = capture(UiThemeMode.MIUIX, true, "miuix-large-font", fontScale = 1.5f)

    // These tests assert interactions. Robolectric's native Skia cannot compile the blur
    // dependency's child.eval shader; real-device tests retain hardware rendering coverage.
    @Test @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun modelSearchMaterialLight() = verifyModelSearch(UiThemeMode.MATERIAL, false)
    @Test @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun modelSearchMaterialDark() = verifyModelSearch(UiThemeMode.MATERIAL, true)
    @Test @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun modelSearchMiuixLight() = verifyModelSearch(UiThemeMode.MIUIX, false)
    @Test @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun modelSearchMiuixDark() = verifyModelSearch(UiThemeMode.MIUIX, true)

    private fun verifyModelSearch(theme: UiThemeMode, dark: Boolean) {
        var selected = ""
        val state = LlmSettingsUiState(isLoading = false,
            fetchedModels = (0..299).map { "model-$it" })
        compose.setContent {
            AgentPawAppTheme(theme, darkTheme = dark, dynamicColor = false) {
                LlmServicePage(state, actions(onModel = { selected = it }), {}, rememberAppSnackbarHostState())
            }
        }
        val label = RuntimeEnvironment.getApplication().getString(R.string.settings_models_search)
        compose.onNodeWithText(label).performScrollTo().performTextInput("model-123")
        compose.onNodeWithText(label).performImeAction()
        // Scroll the result list into the outer page viewport before scrolling its items.
        compose.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("model-123") and isSelectable())).performScrollTo()
        // EditableText also contains the query: match the selectable option, not the search field.
        compose.onNode(hasText("model-123") and isSelectable()).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("model-123", selected) }
    }

    private fun actions(onModel: (String) -> Unit = {}) = LlmSettingsActions(
        onProviderChange = {}, onBaseUrlChange = {}, onApiKeyChange = {}, onModelChange = onModel,
        onTemperatureChange = {}, onTopPChange = {}, onMaxTokensChange = {}, onMaxToolRoundsChange = {},
        onVisionResolutionModeChange = {}, onStreamChange = {}, onSystemPromptChange = {},
        onToggleApiKeyVisibility = {}, onSave = {}, onTestConnection = {}, onReset = {})

    private fun capture(theme: UiThemeMode, dark: Boolean, name: String, generation: Boolean = false, fontScale: Float = 1f) {
        val config = LlmConfig(baseUrl = "https://api.example.com/v1", apiKey = "example-key", model = "agent-vision")
        val state = LlmSettingsUiState(isLoading = false, savedConfig = config,
            provider = LlmProvider.CUSTOM, baseUrl = config.baseUrl, apiKey = config.apiKey, model = config.model)
        val actions = actions()
        lateinit var root: View
        compose.setContent {
            root = LocalView.current
            AgentPawAppTheme(theme, darkTheme = dark, dynamicColor = false) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    val snackbar = rememberAppSnackbarHostState()
                    if (generation) GenerationPage(state, actions, {}, snackbar, Modifier.testTag("preview"))
                    else LlmServicePage(state, actions, {}, snackbar, Modifier.testTag("preview"))
                }
            }
        }
        val output = File("build/reports/ui-review", "$name.png")
        output.parentFile?.mkdirs()
        compose.onNodeWithTag("preview").fetchSemanticsNode()
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }
}
