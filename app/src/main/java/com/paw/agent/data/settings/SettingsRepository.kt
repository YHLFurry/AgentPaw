package com.paw.agent.data.settings

import androidx.compose.runtime.Immutable
import com.paw.agent.core.llm.LlmConfig
import kotlinx.coroutines.flow.Flow

/** User-facing app preferences, persisted with DataStore. */
@Immutable
data class AppSettings(
    val llm: LlmConfig = LlmConfig(),
    val dynamicColor: Boolean = true,
    val darkTheme: Boolean = false,
    /** Which design system renders the UI; see [UiThemeMode]. */
    val uiTheme: UiThemeMode = UiThemeMode.Default,
) {
    companion object {
        val Default = AppSettings()
    }
}

/** Reads and writes [AppSettings]. */
interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun updateLlm(transform: (LlmConfig) -> LlmConfig)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setDarkTheme(enabled: Boolean)
    suspend fun setUiTheme(mode: UiThemeMode)
    /** Clears the LLM section only, so appearance choices survive an LLM reset. */
    suspend fun resetLlm()
}
