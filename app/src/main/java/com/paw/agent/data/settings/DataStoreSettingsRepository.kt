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
        val STREAM = booleanPreferencesKey("llm_stream")
        val SYSTEM_PROMPT = stringPreferencesKey("llm_system_prompt")
        val DYNAMIC_COLOR = booleanPreferencesKey("ui_dynamic_color")
        val DARK_THEME = booleanPreferencesKey("ui_dark_theme")
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
            prefs[Keys.API_KEY] = updated.apiKey
            prefs[Keys.MODEL] = updated.model
            prefs[Keys.TEMPERATURE] = updated.temperature
            prefs[Keys.TOP_P] = updated.topP
            prefs[Keys.MAX_TOKENS] = updated.maxTokens
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

    override suspend fun reset() {
        context.dataStore.edit { it.clear() }
    }

    private fun Preferences.toLlmConfig(): LlmConfig {
        val provider = LlmProvider.fromName(this[Keys.PROVIDER])
        return LlmConfig(
            provider = provider,
            baseUrl = this[Keys.BASE_URL] ?: provider.defaultBaseUrl,
            apiKey = this[Keys.API_KEY].orEmpty(),
            model = this[Keys.MODEL] ?: provider.defaultModel,
            temperature = this[Keys.TEMPERATURE] ?: 0.7f,
            topP = this[Keys.TOP_P] ?: 1.0f,
            maxTokens = this[Keys.MAX_TOKENS] ?: 2048,
            stream = this[Keys.STREAM] ?: true,
            systemPrompt = this[Keys.SYSTEM_PROMPT] ?: LlmConfig.DEFAULT_SYSTEM_PROMPT,
        )
    }

    private fun Preferences.toSettings(): AppSettings = AppSettings(
        llm = toLlmConfig(),
        dynamicColor = this[Keys.DYNAMIC_COLOR] ?: true,
        darkTheme = this[Keys.DARK_THEME] ?: false,
    )
}
