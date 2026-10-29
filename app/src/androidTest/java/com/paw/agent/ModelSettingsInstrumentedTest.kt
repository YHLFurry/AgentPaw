package com.paw.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.paw.agent.core.llm.LlmProvider
import com.paw.agent.data.settings.UiThemeMode
import com.paw.agent.ui.components.adaptive.rememberAppSnackbarHostState
import com.paw.agent.ui.settings.*
import com.paw.agent.ui.theme.AgentPawAppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 覆盖两套主题的模型选择、输入和破坏性操作确认。需要设备执行。 */
@RunWith(Parameterized::class)
class ModelSettingsInstrumentedTest(private val theme: UiThemeMode, private val dark: Boolean) {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun text(id: Int) = context.getString(id)
    private fun actions(onReset: () -> Unit = {}, onModel: (String) -> Unit = {},
        onTimeout: (String) -> Unit = {}) = LlmSettingsActions(
        onProviderChange = {}, onBaseUrlChange = {}, onApiKeyChange = {}, onModelChange = onModel,
        onTemperatureChange = {}, onTopPChange = {}, onMaxTokensChange = {},
        onMaxToolRoundsChange = {}, onVisionResolutionModeChange = {}, onStreamChange = {},
        onSystemPromptChange = {}, onToggleApiKeyVisibility = {}, onSave = {},
        onTestConnection = {}, onReset = onReset, onTimeoutSecondsInputChange = onTimeout,
    )
    private fun show(state: LlmSettingsUiState, actions: LlmSettingsActions) {
        compose.setContent {
            AgentPawAppTheme(theme, darkTheme = dark, dynamicColor = false) {
                LlmServicePage(state, actions, {}, rememberAppSnackbarHostState())
            }
        }
    }

    @Test fun resetRequiresExplicitConfirmation() {
        var resets = 0
        show(LlmSettingsUiState(isLoading = false), actions(onReset = { resets++ }))
        compose.onNodeWithText(text(R.string.settings_reset)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.settings_reset_confirmation)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, resets) }
        compose.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals(0, resets) }
        compose.onNodeWithText(text(R.string.settings_reset)).performClick()
        compose.onAllNodesWithText(text(R.string.settings_reset)).onLast().performClick()
        compose.runOnIdle { assertEquals(1, resets) }
    }

    @Test fun fetchedModelsCanBeSearchedAndSelected() {
        var selected = ""
        show(LlmSettingsUiState(isLoading = false, provider = LlmProvider.CUSTOM,
            fetchedModels = (0..299).map { "model-$it" }), actions(onModel = { selected = it }))
        compose.onNodeWithText(text(R.string.settings_models_search)).performScrollTo().performTextInput("model-123")
        compose.onNodeWithText("model-123").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("model-123", selected) }
    }

    @Test fun timeoutCanBeClearedAndRetyped() {
        var state by mutableStateOf(LlmSettingsUiState(isLoading = false))
        compose.setContent {
            AgentPawAppTheme(theme, darkTheme = dark, dynamicColor = false) {
                LlmServicePage(state, actions(onTimeout = { state = state.copy(timeoutSecondsInput = it) }),
                    {}, rememberAppSnackbarHostState())
            }
        }
        compose.onNodeWithText(text(R.string.settings_timeout_seconds)).performScrollTo().performTextClearance()
        compose.onNodeWithText(text(R.string.settings_timeout_seconds)).performTextInput("120")
        compose.runOnIdle { assertEquals("120", state.timeoutSecondsInput) }
    }

    @Test fun savingIsUnavailableUntilRepositoryLoads() {
        show(LlmSettingsUiState(), actions())
        compose.onNodeWithText(text(R.string.settings_save)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.settings_loading)).assertIsDisplayed()
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0} dark={1}")
        fun themes() = UiThemeMode.entries.flatMap { theme -> listOf(arrayOf<Any>(theme, false), arrayOf<Any>(theme, true)) }
    }
}
