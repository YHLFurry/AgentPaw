package com.paw.agent.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.paw.agent.core.llm.LlmConfig
import com.paw.agent.core.llm.LlmProvider
import com.paw.agent.data.settings.UiThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "agentpaw_settings")

/**
 * DataStore-backed [SettingsRepository].
 *
 * The API key is stored in the app's private preferences on-device only; it is
 * never logged and never leaves the device except as an auth header.
 */
class DataStoreSettingsRepository(
    private val context: Context,
) : SettingsRepository {

    private object Keys {
        val PROVIDER = stringPreferencesKey("llm_provider")
        val BASE_URL = stringPreferencesKey("llm_base_url")
        val API_KEY = stringPreferencesKey("llm_api_key")
        val MODEL = stringPreferencesKey("llm_model")
        val TEMPERATURE = floatPreferencesKey("llm_temperature")
        val TOP_P = floatPreferencesKey("llm_top_p")
        val MAX_TOKENS = intPreferencesKey("llm_max_tokens")
        val MAX_TOOL_ROUNDS = intPreferencesKey("llm_max_tool_rounds")
        val VISION_RESOLUTION_MODE = stringPreferencesKey("llm_vision_resolution_mode")
        val STREAM = booleanPreferencesKey("llm_stream")
        val SYSTEM_PROMPT = stringPreferencesKey("llm_system_prompt")
        val DYNAMIC_COLOR = booleanPreferencesKey("ui_dynamic_color")
        val DARK_THEME = booleanPreferencesKey("ui_dark_theme")
        val UI_THEME = stringPreferencesKey("ui_theme_mode")
        val EXPERT_MODE = booleanPreferencesKey("ui_expert_mode")
        val SPLIT_VISION_LANGUAGE = booleanPreferencesKey("ui_split_vision_language")
        val ROOT_MODE = booleanPreferencesKey("agent_root_mode")
        val ADAPTIVE_PACING = booleanPreferencesKey("agent_adaptive_pacing")
    }

    override val settings: Flow<AppSettings> = context.dataStore.data
        // A corrupt preferences file should not take the app down.
        .catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { prefs -> prefs.toSettings() }

    override suspend fun updateLlm(transform: (LlmConfig) -> LlmConfig) {
        context.dataStore.edit { prefs ->
            val updated = transform(prefs.toLlmConfig())
            prefs[Keys.PROVIDER] = updated.provider.name
            prefs[Keys.BASE_URL] = updated.baseUrl
            prefs[Keys.API_KEY] = com.paw.agent.data.security.KeystoreSecretStorage.encrypt(updated.apiKey)
            prefs[Keys.MODEL] = updated.model
            prefs[Keys.TEMPERATURE] = updated.temperature
            prefs[Keys.TOP_P] = updated.topP
            prefs[Keys.MAX_TOKENS] = updated.maxTokens
            prefs[Keys.MAX_TOOL_ROUNDS] = updated.maxToolRounds
            prefs[Keys.VISION_RESOLUTION_MODE] = updated.visionResolutionMode
            prefs[Keys.STREAM] = updated.stream
            prefs[Keys.SYSTEM_PROMPT] = updated.systemPrompt
        }
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    override suspend fun setDarkTheme(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DARK_THEME] = enabled }
    }

    override suspend fun setUiTheme(mode: UiThemeMode) {
        context.dataStore.edit { it[Keys.UI_THEME] = mode.storageKey }
    }

    override suspend fun setExpertMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.EXPERT_MODE] = enabled }
    }

    override suspend fun setSplitVisionLanguageMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SPLIT_VISION_LANGUAGE] = enabled }
    }

    override suspend fun setRootModeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ROOT_MODE] = enabled }
    }

    override suspend fun setAdaptivePacingEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ADAPTIVE_PACING] = enabled }
    }

    /** Clears only the LLM keys; appearance choices (theme, dark, dynamic) stay. */
    override suspend fun resetLlm() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.PROVIDER)
            prefs.remove(Keys.BASE_URL)
            prefs.remove(Keys.API_KEY)
            prefs.remove(Keys.MODEL)
            prefs.remove(Keys.TEMPERATURE)
            prefs.remove(Keys.TOP_P)
            prefs.remove(Keys.MAX_TOKENS)
            prefs.remove(Keys.MAX_TOOL_ROUNDS)
            prefs.remove(Keys.VISION_RESOLUTION_MODE)
            prefs.remove(Keys.STREAM)
            prefs.remove(Keys.SYSTEM_PROMPT)
        }
    }

    private fun Preferences.toLlmConfig(): LlmConfig {
        val provider = LlmProvider.fromName(this[Keys.PROVIDER])
        return LlmConfig(
            provider = provider,
            baseUrl = this[Keys.BASE_URL] ?: provider.defaultBaseUrl,
            apiKey = com.paw.agent.data.security.KeystoreSecretStorage.decrypt(this[Keys.API_KEY].orEmpty()),
            model = this[Keys.MODEL] ?: provider.defaultModel,
            temperature = this[Keys.TEMPERATURE] ?: 0.7f,
            topP = this[Keys.TOP_P] ?: 1.0f,
            maxTokens = this[Keys.MAX_TOKENS] ?: 2048,
            maxToolRounds = this[Keys.MAX_TOOL_ROUNDS] ?: 15,
            visionResolutionMode = this[Keys.VISION_RESOLUTION_MODE] ?: "AUTO",
            stream = this[Keys.STREAM] ?: true,
            systemPrompt = this[Keys.SYSTEM_PROMPT] ?: LlmConfig.DEFAULT_SYSTEM_PROMPT,
        )
    }

    private fun Preferences.toSettings(): AppSettings = AppSettings(
        llm = toLlmConfig(),
        dynamicColor = this[Keys.DYNAMIC_COLOR] ?: true,
        darkTheme = this[Keys.DARK_THEME] ?: false,
        uiTheme = UiThemeMode.fromName(this[Keys.UI_THEME]),
        expertMode = this[Keys.EXPERT_MODE] ?: false,
        splitVisionLanguageMode = this[Keys.SPLIT_VISION_LANGUAGE] ?: false,
        rootModeEnabled = this[Keys.ROOT_MODE] ?: false,
        adaptivePacingEnabled = this[Keys.ADAPTIVE_PACING] ?: true,
    )
}
