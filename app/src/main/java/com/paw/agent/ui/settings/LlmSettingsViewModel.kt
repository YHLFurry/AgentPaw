package com.paw.agent.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.paw.agent.core.llm.LlmClient
import com.paw.agent.core.llm.LlmConfig
import com.paw.agent.core.llm.LlmProvider
import com.paw.agent.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URI

/** Outcome of the "test connection" action. */
sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Success(val message: String) : TestState
    data class Failure(val message: String) : TestState
}

/** Editable copy of [LlmConfig] plus per-field validation errors. */
data class LlmSettingsUiState(
    val provider: LlmProvider = LlmProvider.CUSTOM,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Float = 0.7f,
    val topP: Float = 1.0f,
    val maxTokens: Int = 2048,
    val maxToolRounds: Int = 15,
    val visionResolutionMode: String = "AUTO",
    val stream: Boolean = true,
    val systemPrompt: String = LlmConfig.DEFAULT_SYSTEM_PROMPT,
    val showApiKey: Boolean = false,
    val baseUrlError: String? = null,
    val modelError: String? = null,
    val apiKeyError: String? = null,
    val savedAt: Long? = null,
    val testState: TestState = TestState.Idle,
) {
    /** No inline errors: safe to persist. */
    val isValid: Boolean
        get() = baseUrlError == null && modelError == null && apiKeyError == null

    fun toConfig(): LlmConfig = LlmConfig(
        provider = provider,
        baseUrl = baseUrl.trim(),
        apiKey = apiKey.trim(),
        model = model.trim(),
        temperature = temperature,
        topP = topP,
        maxTokens = maxTokens,
        maxToolRounds = maxToolRounds,
        visionResolutionMode = visionResolutionMode,
        stream = stream,
        systemPrompt = systemPrompt,
    )
}

class LlmSettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val llmClient: LlmClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LlmSettingsUiState())
    val uiState: StateFlow<LlmSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                // Only seed the form once; later edits must not be clobbered.
                if (!_uiState.value.savedAt.let { it != null }) {
                    val llm = settings.llm
                    _uiState.value = _uiState.value.copy(
                        provider = llm.provider,
                        baseUrl = llm.baseUrl,
                        apiKey = llm.apiKey,
                        model = llm.model,
                        temperature = llm.temperature,
                        topP = llm.topP,
                        maxTokens = llm.maxTokens,
                        maxToolRounds = llm.maxToolRounds,
                        visionResolutionMode = llm.visionResolutionMode,
                        stream = llm.stream,
                        systemPrompt = llm.systemPrompt,
                    )
                }
            }
        }
    }

    fun onProviderChange(provider: LlmProvider) {
        // Presets carry sensible defaults; keep whatever the user already typed
        // for the fields the preset does not define.
        _uiState.value = _uiState.value.copy(
            provider = provider,
            baseUrl = provider.defaultBaseUrl,
            model = provider.defaultModel,
            baseUrlError = null,
            modelError = null,
            apiKeyError = null,
            testState = TestState.Idle,
        )
    }

    fun onBaseUrlChange(value: String) {
        _uiState.value = _uiState.value.copy(baseUrl = value, baseUrlError = null, testState = TestState.Idle)
    }

    fun onApiKeyChange(value: String) {
        _uiState.value = _uiState.value.copy(apiKey = value, apiKeyError = null, testState = TestState.Idle)
    }

    fun onModelChange(value: String) {
        _uiState.value = _uiState.value.copy(model = value, modelError = null, testState = TestState.Idle)
    }

    fun onTemperatureChange(value: Float) {
        _uiState.value = _uiState.value.copy(temperature = value)
    }

    fun onTopPChange(value: Float) {
        _uiState.value = _uiState.value.copy(topP = value)
    }

    fun onMaxTokensChange(value: Int) {
        _uiState.value = _uiState.value.copy(maxTokens = value.coerceIn(1, 32768))
    }

    fun onMaxToolRoundsChange(value: Int) {
        _uiState.value = _uiState.value.copy(maxToolRounds = value.coerceAtLeast(0))
    }

    fun onVisionResolutionModeChange(value: String) {
        _uiState.value = _uiState.value.copy(visionResolutionMode = value)
    }

    fun onStreamChange(value: Boolean) {
        _uiState.value = _uiState.value.copy(stream = value)
    }

    fun onSystemPromptChange(value: String) {
        _uiState.value = _uiState.value.copy(systemPrompt = value)
    }

    fun toggleApiKeyVisibility() {
        _uiState.value = _uiState.value.copy(showApiKey = !_uiState.value.showApiKey)
    }

    /** Validates, then writes through to DataStore. */
    fun save() {
        val state = _uiState.value
        val validated = validate(state)
        _uiState.value = validated
        if (!validated.isValid) return

        viewModelScope.launch {
            settingsRepository.updateLlm { validated.toConfig() }
            _uiState.value = _uiState.value.copy(savedAt = System.currentTimeMillis())
        }
    }

    fun testConnection() {
        val state = _uiState.value
        val validated = validate(state)
        _uiState.value = validated
        if (!validated.isValid) return

        _uiState.value = _uiState.value.copy(testState = TestState.Running)
        viewModelScope.launch {
            llmClient.testConnection(validated.toConfig())
                .onSuccess {
                    _uiState.value = _uiState.value.copy(testState = TestState.Success("OK"))
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        testState = TestState.Failure(error.message ?: "Unknown error"),
                    )
                }
        }
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            settingsRepository.resetLlm()
            val fresh = LlmConfig()
            _uiState.value = LlmSettingsUiState(
                provider = fresh.provider,
                baseUrl = fresh.baseUrl,
                model = fresh.model,
                temperature = fresh.temperature,
                topP = fresh.topP,
                maxTokens = fresh.maxTokens,
                maxToolRounds = fresh.maxToolRounds,
                visionResolutionMode = fresh.visionResolutionMode,
                stream = fresh.stream,
                systemPrompt = fresh.systemPrompt,
            )
        }
    }

    private fun validate(state: LlmSettingsUiState): LlmSettingsUiState {
        val url = state.baseUrl.trim()
        val baseUrlError = when {
            url.isEmpty() -> "error_base_url_required"
            !url.startsWith("http://") && !url.startsWith("https://") -> "error_base_url_invalid"
            runCatching { URI(url) }.isFailure -> "error_base_url_invalid"
            else -> null
        }

        val modelError = if (state.model.isBlank()) "error_model_required" else null

        val apiKeyError = if (state.provider.requiresApiKey && state.apiKey.isBlank()) {
            "error_api_key_required"
        } else {
            null
        }

        return state.copy(
            baseUrlError = baseUrlError,
            modelError = modelError,
            apiKeyError = apiKeyError,
        )
    }

    class Factory(
        private val settingsRepository: SettingsRepository,
        private val llmClient: LlmClient,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LlmSettingsViewModel(settingsRepository, llmClient) as T
    }
}
