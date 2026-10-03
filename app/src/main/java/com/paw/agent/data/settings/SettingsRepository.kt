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
    suspend fun reset()
}
