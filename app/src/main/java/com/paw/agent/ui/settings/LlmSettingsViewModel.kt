package com.paw.agent.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.paw.agent.core.llm.LlmClient
import com.paw.agent.core.llm.LlmConfig
import com.paw.agent.core.llm.LlmProvider
import com.paw.agent.core.llm.DiagnosticKind
import com.paw.agent.core.llm.DiagnosticResult
import com.paw.agent.core.llm.LlmDiagnostics
import com.paw.agent.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
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
    val maxTokensInput: String? = null,
    val maxTotalTokens: Int = 0,
    val maxTotalTokensInput: String? = null,
    val detectedTotalTokens: Int = 0,
    val detectedFor: String = "",
    val reasoningEffort: String = "AUTO",
    val autoCompress: Boolean = true,
    val maxToolRounds: Int = 15,
    val visionResolutionMode: String = "AUTO",
    val pacingProfile: String = "BALANCED",
    val timeoutSeconds: Int = 60,
    val stream: Boolean = true,
    val systemPrompt: String = LlmConfig.DEFAULT_SYSTEM_PROMPT,
    val showApiKey: Boolean = false,
    val baseUrlError: String? = null,
    val modelError: String? = null,
    val apiKeyError: String? = null,
    val tokenError: String? = null,
    val settingsError: String? = null,
    val saveError: String? = null,
    val savedAt: Long? = null,
    val testState: TestState = TestState.Idle,
    val diagnosticsRunning: Boolean = false,
    val diagnostics: Map<DiagnosticKind, DiagnosticResult> = emptyMap(),
    val expertMode: Boolean = false,
    val splitVisionLanguageMode: Boolean = false,
    val rootModeEnabled: Boolean = false,
    val adaptivePacingEnabled: Boolean = true,
    // ---- 资深模式：高危开关 ----
    val allowHttpTransport: Boolean = false,
    val allowPublicHttp: Boolean = false,
    val allowShellSkills: Boolean = false,
    val allowSensitiveScreen: Boolean = false,
    val riskBypassUntil: Long = 0L,
    /** 高危开关被拒绝时的原因，展示一次后清空。 */
    val riskNotice: String? = null,
    // ---- 联网拉取模型列表状态 ----
    val isFetchingModels: Boolean = false,
    val fetchedModels: List<String> = emptyList(),
    val fetchModelsError: String? = null,
    val fetchModelsSuccessMessage: String? = null,
) {
    /** No inline errors: safe to persist. */
    val isValid: Boolean
        get() = baseUrlError == null && modelError == null && apiKeyError == null && tokenError == null && settingsError == null

    fun toConfig(): LlmConfig = LlmConfig(
        provider = provider,
        baseUrl = baseUrl.trim(),
        apiKey = apiKey.trim(),
        model = model.trim(),
        temperature = temperature,
        topP = topP,
        maxTokens = maxTokens,
        maxTotalTokens = maxTotalTokens,
        detectedTotalTokens = detectedTotalTokens,
        detectedFor = detectedFor,
        reasoningEffort = reasoningEffort,
        autoCompress = autoCompress,
        maxToolRounds = maxToolRounds,
        visionResolutionMode = visionResolutionMode,
        pacingProfile = pacingProfile,
        timeoutSeconds = timeoutSeconds,
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

    private var seededFromRepository = false
    private var testJob: Job? = null
    private var fetchJob: Job? = null
    private var diagnosticsJob: Job? = null

    private fun invalidateConnection(clearModels: Boolean = true) {
        testJob?.cancel()
        fetchJob?.cancel()
        diagnosticsJob?.cancel()
        _uiState.value = _uiState.value.copy(testState = TestState.Idle, isFetchingModels = false,
            fetchedModels = if (clearModels) emptyList() else _uiState.value.fetchedModels,
            fetchModelsError = null, fetchModelsSuccessMessage = null,
            diagnosticsRunning = false, diagnostics = emptyMap(), settingsError = null, savedAt = null)
    }

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                val llm = settings.llm
                _uiState.value = _uiState.value.copy(
                    expertMode = settings.expertMode,
                    splitVisionLanguageMode = settings.splitVisionLanguageMode,
                    rootModeEnabled = settings.rootModeEnabled,
                    adaptivePacingEnabled = settings.adaptivePacingEnabled,
                    allowHttpTransport = settings.allowHttpTransport,
                    allowPublicHttp = settings.allowPublicHttp,
                    allowShellSkills = settings.allowShellSkills,
                    allowSensitiveScreen = settings.allowSensitiveScreen,
                    riskBypassUntil = settings.riskBypassUntil,
                )
                // Only seed the LLM form once; later edits must not be clobbered by unrelated setting changes.
                if (!seededFromRepository) {
                    seededFromRepository = true
                    _uiState.value = _uiState.value.copy(
                        provider = llm.provider,
                        baseUrl = llm.baseUrl,
                        apiKey = llm.apiKey,
                        model = llm.model,
                        temperature = llm.temperature,
                        topP = llm.topP,
                        maxTokens = llm.maxTokens,
                        maxTotalTokens = llm.maxTotalTokens,
                        detectedTotalTokens = llm.detectedTotalTokens,
                        detectedFor = llm.detectedFor,
                        reasoningEffort = llm.reasoningEffort,
                        autoCompress = llm.autoCompress,
                        maxToolRounds = llm.maxToolRounds.coerceAtLeast(0),
                        visionResolutionMode = llm.visionResolutionMode,
                        pacingProfile = llm.pacingProfile,
                        timeoutSeconds = llm.timeoutSeconds,
                        stream = llm.stream,
                        systemPrompt = llm.systemPrompt,
                        apiKeyError = if (settings.apiKeyDecryptionFailed) "系统密钥解密失败（如修改了锁屏或指纹），请重新输入 API Key" else null,
                    )
                }
            }
        }
    }

    fun onToggleExpertMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(expertMode = enabled)
        viewModelScope.launch { settingsRepository.setExpertMode(enabled) }
    }

    fun onToggleSplitVisionLanguage(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(splitVisionLanguageMode = enabled)
        viewModelScope.launch { settingsRepository.setSplitVisionLanguageMode(enabled) }
    }

    fun onToggleRootMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(rootModeEnabled = enabled)
        viewModelScope.launch { settingsRepository.setRootModeEnabled(enabled) }
    }

    fun onToggleAdaptivePacing(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(adaptivePacingEnabled = enabled)
        viewModelScope.launch { settingsRepository.setAdaptivePacingEnabled(enabled) }
    }

    // ------------------------------------------------------------ 高危开关

    /**
     * 变更高危开关。拒绝原因由仓储层给出（依赖未满足等），这里只负责展示。
     * 不做乐观更新：开关状态必须反映真实落盘结果，否则会出现「界面显示已开、
     * 实际策略仍是关」的最坏情况。
     */
    fun onToggleHighRisk(toggle: com.paw.agent.data.settings.HighRiskToggle, enabled: Boolean) {
        viewModelScope.launch {
            val result = settingsRepository.setHighRiskToggle(toggle, enabled)
            if (!result.applied) {
                _uiState.value = _uiState.value.copy(riskNotice = result.message)
            }
        }
    }

    fun onStartRiskBypass(minutes: Int = 15) {
        viewModelScope.launch { settingsRepository.setRiskBypassMinutes(minutes) }
    }

    fun onStopRiskBypass() {
        viewModelScope.launch { settingsRepository.setRiskBypassMinutes(0) }
    }

    fun consumeRiskNotice() {
        _uiState.value = _uiState.value.copy(riskNotice = null)
    }

    fun onProviderChange(provider: LlmProvider) {
        invalidateConnection()
        // Presets carry sensible defaults; keep whatever the user already typed
        // for the fields the preset does not define.
        _uiState.value = _uiState.value.copy(
            provider = provider,
            baseUrl = provider.defaultBaseUrl,
            model = provider.defaultModel,
            baseUrlError = null,
            modelError = null,
            apiKeyError = null,
            detectedTotalTokens = 0,
            detectedFor = "",
            testState = TestState.Idle,
        )
    }

    fun onBaseUrlChange(value: String) {
        invalidateConnection()
        _uiState.value = _uiState.value.copy(baseUrl = value, baseUrlError = null,
            detectedTotalTokens = 0, detectedFor = "", testState = TestState.Idle)
    }

    fun onApiKeyChange(value: String) {
        invalidateConnection()
        _uiState.value = _uiState.value.copy(apiKey = value, apiKeyError = null,
            detectedTotalTokens = 0, detectedFor = "", testState = TestState.Idle)
    }

    fun onModelChange(value: String) {
        invalidateConnection(clearModels = false)
        _uiState.value = _uiState.value.copy(model = value, modelError = null,
            detectedTotalTokens = 0, detectedFor = "", testState = TestState.Idle)
    }

    fun onTemperatureChange(value: Float) {
        invalidateConnection(clearModels = false)
        _uiState.value = _uiState.value.copy(temperature = value.coerceIn(0.0f, 2.0f))
    }

    fun onTopPChange(value: Float) {
        invalidateConnection(clearModels = false)
        _uiState.value = _uiState.value.copy(topP = value.coerceIn(0.0f, 1.0f))
    }

    fun onMaxTokensChange(value: Int) {
        _uiState.value = _uiState.value.copy(maxTokens = value, maxTokensInput = null, tokenError = null)
    }

    fun onMaxTotalTokensChange(value: Int) {
        _uiState.value = _uiState.value.copy(maxTotalTokens = value, maxTotalTokensInput = null, tokenError = null)
    }

    fun onMaxTokensInputChange(value: String) {
        invalidateConnection(clearModels = false)
        _uiState.value = _uiState.value.copy(maxTokensInput = value,
            maxTokens = value.toIntOrNull() ?: if (value.isEmpty()) 0 else -1, tokenError = null)
    }

    fun onMaxTotalTokensInputChange(value: String) {
        invalidateConnection(clearModels = false)
        _uiState.value = _uiState.value.copy(maxTotalTokensInput = value,
            maxTotalTokens = value.toIntOrNull() ?: if (value.isEmpty()) 0 else -1, tokenError = null)
    }

    fun onReasoningEffortChange(value: String) {
        invalidateConnection(clearModels = false)
        if (value in LlmConfig.REASONING_EFFORTS) {
            _uiState.value = _uiState.value.copy(reasoningEffort = value)
        }
    }

    fun onAutoCompressChange(value: Boolean) {
        _uiState.value = _uiState.value.copy(autoCompress = value)
    }

    fun onMaxToolRoundsChange(value: Int) {
        val clamped = value.coerceAtLeast(0)
        _uiState.value = _uiState.value.copy(maxToolRounds = clamped)
        viewModelScope.launch {
            settingsRepository.updateLlm { it.copy(maxToolRounds = clamped) }
        }
    }

    fun onVisionResolutionModeChange(value: String) {
        _uiState.value = _uiState.value.copy(visionResolutionMode = value)
        viewModelScope.launch {
            settingsRepository.updateLlm { it.copy(visionResolutionMode = value) }
        }
    }

    fun onTimeoutSecondsChange(value: Int) {
        val clamped = value.coerceIn(10, 300)
        _uiState.value = _uiState.value.copy(timeoutSeconds = clamped)
        viewModelScope.launch {
            settingsRepository.updateLlm { it.copy(timeoutSeconds = clamped) }
        }
    }

    fun onPacingProfileChange(value: String) {
        _uiState.value = _uiState.value.copy(pacingProfile = value)
        viewModelScope.launch {
            settingsRepository.updateLlm { it.copy(pacingProfile = value) }
        }
    }

    fun onStreamChange(value: Boolean) {
        invalidateConnection(clearModels = false)
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
            try {
                settingsRepository.updateLlm { validated.toConfig() }
                _uiState.value = _uiState.value.copy(savedAt = System.currentTimeMillis(), saveError = null)
            } catch (error: kotlinx.coroutines.CancellationException) { throw error
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(savedAt = null, saveError = error.message ?: "设置保存失败")
            }
        }
    }

    fun testConnection() {
        testJob?.cancel()
        diagnosticsJob?.cancel()
        val state = _uiState.value
        val validated = validate(state)
        _uiState.value = validated
        if (!validated.isValid) {
            _uiState.value = validated.copy(testState = TestState.Failure(
                validated.toConfig().validationErrors().joinToString("；")))
            return
        }

        _uiState.value = _uiState.value.copy(testState = TestState.Running)
        testJob = viewModelScope.launch {
            llmClient.testConnection(validated.toConfig())
                .onSuccess {
                    currentCoroutineContext().ensureActive()
                    _uiState.value = _uiState.value.copy(testState = TestState.Success("OK"))
                    withTimeoutOrNull(5_000) {
                        llmClient.detectContextWindow(validated.toConfig()).getOrNull()
                    }?.let { detected ->
                        if (detected in 1..2_000_000 &&
                            _uiState.value.baseUrl.trim() == validated.baseUrl.trim() &&
                            _uiState.value.model.trim() == validated.model.trim() &&
                            _uiState.value.apiKey.trim() == validated.apiKey.trim()) {
                            val marker = "${validated.baseUrl.trim()}|${validated.model.trim()}"
                            _uiState.value = _uiState.value.copy(
                                detectedTotalTokens = detected,
                                detectedFor = marker,
                            )
                            settingsRepository.updateLlm {
                                it.copy(detectedTotalTokens = detected, detectedFor = marker)
                            }
                        }
                    }
                }
                .onFailure { error ->
                    currentCoroutineContext().ensureActive()
                    _uiState.value = _uiState.value.copy(
                        testState = TestState.Failure(error.message ?: "Unknown error"),
                    )
                }
        }
    }

    fun runDiagnostics() {
        diagnosticsJob?.cancel()
        testJob?.cancel()
        val validated = validate(_uiState.value)
        _uiState.value = validated
        if (!validated.isValid) {
            _uiState.value = validated.copy(testState = TestState.Failure(validated.toConfig().validationErrors().joinToString("；")))
            return
        }
        _uiState.value = validated.copy(diagnosticsRunning = true, diagnostics = emptyMap(), testState = TestState.Idle)
        diagnosticsJob = viewModelScope.launch {
            try {
                LlmDiagnostics(llmClient).run(validated.toConfig()).collect { result ->
                    currentCoroutineContext().ensureActive()
                    _uiState.value = _uiState.value.copy(diagnostics = _uiState.value.diagnostics + (result.kind to result))
                }
            } finally {
                if (currentCoroutineContext().isActive) _uiState.value = _uiState.value.copy(diagnosticsRunning = false)
            }
        }
    }

    /** 联网实时拉取当前端点的可用模型列表 (GET /v1/models) */
    fun fetchModels() {
        val state = _uiState.value
        if (state.baseUrl.isBlank()) {
            _uiState.value = state.copy(fetchModelsError = "请先填写 Base URL 接口地址")
            return
        }
        _uiState.value = state.copy(
            isFetchingModels = true,
            fetchModelsError = null,
            fetchModelsSuccessMessage = null,
        )
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            val config = state.toConfig()
            llmClient.listModels(config)
                .onSuccess { models ->
                    currentCoroutineContext().ensureActive()
                    _uiState.value = _uiState.value.copy(
                        isFetchingModels = false,
                        fetchedModels = models,
                        fetchModelsSuccessMessage = "成功拉取到 ${models.size} 个可用模型",
                        fetchModelsError = null,
                    )
                }
                .onFailure { error ->
                    currentCoroutineContext().ensureActive()
                    _uiState.value = _uiState.value.copy(
                        isFetchingModels = false,
                        fetchModelsError = error.message ?: "拉取模型列表失败，请检查网络或鉴权密钥",
                        fetchModelsSuccessMessage = null,
                    )
                }
        }
    }

    fun clearFetchModelsMessage() {
        _uiState.value = _uiState.value.copy(fetchModelsError = null, fetchModelsSuccessMessage = null)
    }

    fun resetToDefaults() {
        invalidateConnection()
        viewModelScope.launch {
            try {
            settingsRepository.resetLlm()
            val fresh = LlmConfig()
            _uiState.value = _uiState.value.copy(
                provider = fresh.provider,
                baseUrl = fresh.baseUrl,
                apiKey = fresh.apiKey,
                model = fresh.model,
                temperature = fresh.temperature,
                topP = fresh.topP,
                maxTokens = fresh.maxTokens,
                maxTokensInput = null,
                maxTotalTokens = fresh.maxTotalTokens,
                maxTotalTokensInput = null,
                detectedTotalTokens = fresh.detectedTotalTokens,
                detectedFor = fresh.detectedFor,
                reasoningEffort = fresh.reasoningEffort,
                autoCompress = fresh.autoCompress,
                maxToolRounds = fresh.maxToolRounds,
                visionResolutionMode = fresh.visionResolutionMode,
                timeoutSeconds = fresh.timeoutSeconds,
                pacingProfile = fresh.pacingProfile,
                stream = fresh.stream,
                systemPrompt = fresh.systemPrompt,
                baseUrlError = null, modelError = null, apiKeyError = null, tokenError = null,
                settingsError = null, saveError = null, showApiKey = false,
            )
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(saveError = error.message ?: "恢复默认失败")
            }
        }
    }

    private fun validate(state: LlmSettingsUiState): LlmSettingsUiState {
        val url = state.baseUrl.trim()
        val parsedUrl = runCatching { URI(url) }.getOrNull()
        val baseUrlError = when {
            url.isEmpty() -> "error_base_url_required"
            parsedUrl == null || parsedUrl.scheme?.lowercase() !in setOf("http", "https") ||
                parsedUrl.host.isNullOrBlank() || parsedUrl.rawUserInfo != null ||
                parsedUrl.rawFragment != null || parsedUrl.rawQuery != null ||
                (parsedUrl.port != -1 && parsedUrl.port !in 1..65535) -> "error_base_url_invalid"
            else -> null
        }

        val modelError = if (state.model.isBlank() || state.model.any { it.isISOControl() }) "error_model_required" else null

        val apiKeyError = if ((state.provider.requiresApiKey && state.apiKey.isBlank()) || state.apiKey.any { it.isISOControl() }) {
            "error_api_key_required"
        } else {
            null
        }

        return state.copy(
            baseUrlError = baseUrlError,
            modelError = modelError,
            apiKeyError = apiKeyError,
            settingsError = if (!state.temperature.isFinite() || !state.topP.isFinite() ||
                state.reasoningEffort !in LlmConfig.REASONING_EFFORTS) "采样参数或思考强度无效" else null,
            tokenError = if (state.maxTokens !in 1..LlmConfig.MAX_TOKEN_LIMIT ||
                state.maxTotalTokens !in 0..LlmConfig.MAX_TOKEN_LIMIT ||
                state.toConfig().effectiveMaxTotalTokens <= state.maxTokens
            ) "error_total_tokens_range" else null,
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
