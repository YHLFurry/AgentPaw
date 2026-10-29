package com.paw.agent.ui.settings

import com.paw.agent.core.llm.LlmChunk
import com.paw.agent.core.llm.LlmClient
import com.paw.agent.core.llm.LlmConfig
import com.paw.agent.core.llm.dto.ChatCompletionRequest
import com.paw.agent.data.settings.HighRiskResult
import com.paw.agent.data.settings.HighRiskRules
import com.paw.agent.data.settings.HighRiskState
import com.paw.agent.data.settings.HighRiskToggle
import com.paw.agent.data.settings.AppSettings
import com.paw.agent.data.settings.SettingsRepository
import com.paw.agent.data.settings.UiThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LlmSettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val fakeSettingsRepo = FakeSettingsRepository()
    private val fakeLlmClient = FakeLlmClient()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initializes with values from settings repository`() = runTest {
        val viewModel = LlmSettingsViewModel(fakeSettingsRepo, fakeLlmClient)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("https://api.openai.com/v1", state.baseUrl)
        assertEquals("gpt-4o", state.model)
    }

    @Test
    fun `user draft is not clobbered when unrelated settings update`() = runTest {
        val viewModel = LlmSettingsViewModel(fakeSettingsRepo, fakeLlmClient)
        advanceUntilIdle()

        // User edits model in draft
        viewModel.onModelChange("my-custom-model")
        assertEquals("my-custom-model", viewModel.uiState.value.model)

        // An unrelated setting updates in the repository
        fakeSettingsRepo.setExpertMode(true)
        advanceUntilIdle()

        // Draft model must NOT be overwritten
        assertEquals("my-custom-model", viewModel.uiState.value.model)
        assertTrue(viewModel.uiState.value.expertMode)
    }

    @Test
    fun `validates empty model and invalid url on save`() = runTest {
        val viewModel = LlmSettingsViewModel(fakeSettingsRepo, fakeLlmClient)
        advanceUntilIdle()

        viewModel.onModelChange("")
        viewModel.onBaseUrlChange("not-a-valid-url")
        viewModel.save()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isValid)
        assertTrue(viewModel.uiState.value.modelError != null)
        assertTrue(viewModel.uiState.value.baseUrlError != null)
    }

    @Test
    fun `allows positive integer for maxToolRounds and supports 0 as unlimited`() = runTest {
        val viewModel = LlmSettingsViewModel(fakeSettingsRepo, fakeLlmClient)
        advanceUntilIdle()

        viewModel.onMaxToolRoundsChange(0)
        assertEquals(0, viewModel.uiState.value.maxToolRounds)

        viewModel.onMaxToolRoundsChange(150)
        assertEquals(150, viewModel.uiState.value.maxToolRounds)

        viewModel.onMaxToolRoundsChange(-5)
        assertEquals(0, viewModel.uiState.value.maxToolRounds)
    }

    @Test fun `rejects total token budget smaller than output`() = runTest {
        val viewModel = LlmSettingsViewModel(fakeSettingsRepo, fakeLlmClient)
        advanceUntilIdle()
        viewModel.onMaxTokensChange(2048)
        viewModel.onMaxTotalTokensChange(1024)
        viewModel.save()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isValid)
        assertEquals("error_total_tokens_range", viewModel.uiState.value.tokenError)
    }

    @Test fun `connection metadata remains a draft until save`() = runTest {
        val viewModel = LlmSettingsViewModel(fakeSettingsRepo, fakeLlmClient)
        advanceUntilIdle()
        viewModel.testConnection()
        advanceUntilIdle()
        assertEquals(65_536, viewModel.uiState.value.toConfig().effectiveMaxTotalTokens)
        assertEquals(0, fakeSettingsRepo.settings.value.llm.detectedTotalTokens)
        viewModel.save()
        advanceUntilIdle()
        assertEquals(65_536, fakeSettingsRepo.settings.value.llm.detectedTotalTokens)
    }

    @Test fun `provider switches preserve separate endpoint and key drafts`() = runTest {
        val vm = LlmSettingsViewModel(fakeSettingsRepo, fakeLlmClient)
        advanceUntilIdle()
        vm.onBaseUrlChange("https://private.example/v1")
        vm.onModelChange("private-model")
        vm.onProviderChange(com.paw.agent.core.llm.LlmProvider.OPENAI)
        assertEquals("", vm.uiState.value.apiKey)
        vm.onApiKeyChange("openai-key")
        vm.onProviderChange(com.paw.agent.core.llm.LlmProvider.CUSTOM)
        assertEquals("https://private.example/v1", vm.uiState.value.baseUrl)
        assertEquals("private-model", vm.uiState.value.model)
        assertEquals("sk-test", vm.uiState.value.apiKey)
        vm.onProviderChange(com.paw.agent.core.llm.LlmProvider.OPENAI)
        assertEquals("openai-key", vm.uiState.value.apiKey)
    }

    @Test fun `selecting current provider does not erase edits or probe results`() = runTest {
        val vm = LlmSettingsViewModel(fakeSettingsRepo, fakeLlmClient)
        advanceUntilIdle()
        vm.onModelChange("my-model")
        vm.onProviderChange(vm.uiState.value.provider)
        assertEquals("my-model", vm.uiState.value.model)
    }

    @Test fun `timeout can be cleared and typed digit by digit without clamping`() = runTest {
        val vm = LlmSettingsViewModel(fakeSettingsRepo, fakeLlmClient)
        advanceUntilIdle()
        vm.onTimeoutSecondsInputChange("")
        assertEquals("", vm.uiState.value.timeoutSecondsInput)
        vm.onTimeoutSecondsInputChange("1")
        assertEquals("1", vm.uiState.value.timeoutSecondsInput)
        vm.onTimeoutSecondsInputChange("120")
        assertEquals(60, fakeSettingsRepo.settings.value.llm.timeoutSeconds)
        vm.save()
        advanceUntilIdle()
        assertEquals(120, fakeSettingsRepo.settings.value.llm.timeoutSeconds)
        assertFalse(vm.uiState.value.hasUnsavedChanges)
    }

    @Test fun `invalid timeout blocks save without changing stored configuration`() = runTest {
        val vm = LlmSettingsViewModel(fakeSettingsRepo, fakeLlmClient)
        advanceUntilIdle()
        vm.onTimeoutSecondsInputChange("999999999999999")
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.timeoutError != null)
        assertEquals(60, fakeSettingsRepo.settings.value.llm.timeoutSeconds)
    }

    @Test fun `round count overflow stays visible and cannot silently save old value`() = runTest {
        val vm = LlmSettingsViewModel(fakeSettingsRepo, fakeLlmClient)
        advanceUntilIdle()
        vm.onMaxToolRoundsInputChange("999999999999999")
        vm.save()
        advanceUntilIdle()
        assertEquals("999999999999999", vm.uiState.value.maxToolRoundsInput)
        assertTrue(vm.uiState.value.maxToolRoundsError != null)
        assertEquals(15, fakeSettingsRepo.settings.value.llm.maxToolRounds)
        assertEquals(0, fakeSettingsRepo.writes)
    }

    @Test fun `save before initial settings load never overwrites stored data`() = runTest {
        val vm = LlmSettingsViewModel(fakeSettingsRepo, fakeLlmClient)
        assertTrue(vm.uiState.value.isLoading)
        vm.save()
        advanceUntilIdle()
        assertEquals(0, fakeSettingsRepo.writes)
        assertEquals("gpt-4o", vm.uiState.value.model)
    }

    @Test fun `reset failure preserves draft and clears busy state`() = runTest {
        val vm = LlmSettingsViewModel(fakeSettingsRepo, fakeLlmClient)
        advanceUntilIdle()
        vm.onModelChange("keep-draft")
        fakeSettingsRepo.failWrites = true
        vm.resetToDefaults()
        advanceUntilIdle()
        assertEquals("keep-draft", vm.uiState.value.model)
        assertFalse(vm.uiState.value.isSaving)
        assertFalse(vm.uiState.value.isResetting)
        assertTrue(vm.uiState.value.saveError != null)
    }

    @Test fun `agent generation and service parameters all wait for save`() = runTest {
        val vm = LlmSettingsViewModel(fakeSettingsRepo, fakeLlmClient)
        advanceUntilIdle()
        vm.onMaxToolRoundsChange(40)
        vm.onVisionResolutionModeChange("HIGH")
        vm.onPacingProfileChange("CAREFUL")
        advanceUntilIdle()
        assertEquals(15, fakeSettingsRepo.settings.value.llm.maxToolRounds)
        assertTrue(vm.uiState.value.hasUnsavedChanges)
        vm.save()
        advanceUntilIdle()
        assertEquals(40, fakeSettingsRepo.settings.value.llm.maxToolRounds)
        assertEquals("HIGH", fakeSettingsRepo.settings.value.llm.visionResolutionMode)
        assertEquals("CAREFUL", fakeSettingsRepo.settings.value.llm.pacingProfile)
    }

    @Test fun `save in flight does not mark newer edits saved and ignores duplicate save`() = runTest {
        val vm = LlmSettingsViewModel(fakeSettingsRepo, fakeLlmClient)
        advanceUntilIdle()
        fakeSettingsRepo.writeDelay = 1000
        vm.save()
        runCurrent()
        assertTrue(vm.uiState.value.isSaving)
        vm.onModelChange("newer-draft")
        vm.save()
        advanceUntilIdle()
        assertEquals(1, fakeSettingsRepo.writes)
        assertEquals("gpt-4o", fakeSettingsRepo.settings.value.llm.model)
        assertTrue(vm.uiState.value.hasUnsavedChanges)
        assertEquals(null, vm.uiState.value.savedAt)
        assertFalse(vm.uiState.value.isSaving)
    }

    @Test fun `model fetch validates endpoint without requiring a model name`() = runTest {
        val vm = LlmSettingsViewModel(fakeSettingsRepo, fakeLlmClient)
        advanceUntilIdle()
        vm.onModelChange("")
        vm.fetchModels()
        advanceUntilIdle()
        assertEquals(listOf("a-model", "z-model"), vm.uiState.value.fetchedModels)
        vm.onBaseUrlChange("https://user:secret@example.com/v1")
        vm.fetchModels()
        advanceUntilIdle()
        assertEquals(1, fakeLlmClient.modelRequests)
        assertTrue(vm.uiState.value.baseUrlError != null)
    }

    @Test fun `thrown network exceptions finish loading states and show feedback`() = runTest {
        val vm = LlmSettingsViewModel(fakeSettingsRepo, fakeLlmClient)
        advanceUntilIdle()
        fakeLlmClient.throwRequests = true
        vm.fetchModels()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isFetchingModels)
        assertTrue(vm.uiState.value.fetchModelsError.orEmpty().contains("offline"))
        vm.testConnection()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.testState is TestState.Failure)
    }

    @Test fun `edits cancel model fetch and do not accept outdated results`() = runTest {
        val vm = LlmSettingsViewModel(fakeSettingsRepo, fakeLlmClient)
        advanceUntilIdle()
        fakeLlmClient.probeDelay = 1000
        vm.fetchModels()
        runCurrent()
        vm.onBaseUrlChange("https://other.example/v1")
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isFetchingModels)
        assertTrue(vm.uiState.value.fetchedModels.isEmpty())
    }

    @Test fun `starting connection test clears cancelled diagnostics running state`() = runTest {
        val vm = LlmSettingsViewModel(fakeSettingsRepo, fakeLlmClient)
        advanceUntilIdle()
        fakeLlmClient.probeDelay = 1000
        vm.runDiagnostics()
        assertTrue(vm.uiState.value.diagnosticsRunning)
        vm.testConnection()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.diagnosticsRunning)
        assertTrue(vm.uiState.value.testState is TestState.Success)
    }

    @Test fun `overflowing token input remains visible and cannot become automatic mode`() = runTest {
        val viewModel = LlmSettingsViewModel(fakeSettingsRepo, fakeLlmClient)
        advanceUntilIdle()
        viewModel.onMaxTotalTokensInputChange("999999999999999999")
        viewModel.save()
        advanceUntilIdle()
        assertEquals("999999999999999999", viewModel.uiState.value.maxTotalTokensInput)
        assertFalse(viewModel.uiState.value.isValid)
        assertTrue(viewModel.uiState.value.maxTotalTokens != 0)
    }

    @Test fun `changing model cancels old connection result`() = runTest {
        val viewModel = LlmSettingsViewModel(fakeSettingsRepo, fakeLlmClient)
        advanceUntilIdle()
        fakeLlmClient.probeDelay = 1000
        viewModel.testConnection()
        runCurrent()
        viewModel.onModelChange("another-model")
        advanceUntilIdle()
        assertEquals(TestState.Idle, viewModel.uiState.value.testState)
        assertEquals(0, viewModel.uiState.value.detectedTotalTokens)
    }

    @Test fun `save failure is shown without reporting success`() = runTest {
        val viewModel = LlmSettingsViewModel(fakeSettingsRepo, fakeLlmClient)
        advanceUntilIdle()
        fakeSettingsRepo.failWrites = true
        viewModel.save()
        advanceUntilIdle()
        assertEquals(null, viewModel.uiState.value.savedAt)
        assertTrue(viewModel.uiState.value.saveError.orEmpty().contains("disk full"))
    }

    @Test fun `reset restores LLM defaults while preserving expert mode`() = runTest {
        val viewModel = LlmSettingsViewModel(fakeSettingsRepo, fakeLlmClient)
        advanceUntilIdle()
        viewModel.onToggleExpertMode(true)
        viewModel.onTimeoutSecondsChange(120)
        viewModel.onPacingProfileChange("FAST")
        advanceUntilIdle()
        viewModel.resetToDefaults()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.expertMode)
        assertEquals(60, viewModel.uiState.value.timeoutSeconds)
        assertEquals("BALANCED", viewModel.uiState.value.pacingProfile)
        assertEquals(0, viewModel.uiState.value.maxTotalTokens)
    }

    private class FakeLlmClient : LlmClient {
        var probeDelay = 0L
        var throwRequests = false
        var modelRequests = 0
        override suspend fun listModels(config: LlmConfig): Result<List<String>> {
            modelRequests++
            delay(probeDelay)
            if (throwRequests) throw java.io.IOException("offline")
            return Result.success(listOf("z-model", "a-model", "a-model"))
        }
        override fun complete(config: LlmConfig, request: ChatCompletionRequest): Flow<LlmChunk> = emptyFlow()
        override suspend fun testConnection(config: LlmConfig): Result<Unit> {
            delay(probeDelay)
            if (throwRequests) throw java.io.IOException("offline")
            return Result.success(Unit)
        }
        override suspend fun detectContextWindow(config: LlmConfig): Result<Int?> = Result.success(65_536)
    }

    private class FakeSettingsRepository : SettingsRepository {
        var failWrites = false
        var writeDelay = 0L
        var writes = 0
        private val _settings = MutableStateFlow(
            AppSettings(
                llm = LlmConfig(
                    baseUrl = "https://api.openai.com/v1",
                    model = "gpt-4o",
                    apiKey = "sk-test",
                )
            )
        )
        override val settings = _settings.asStateFlow()

        override suspend fun updateLlm(transform: (LlmConfig) -> LlmConfig) {
            writes++
            delay(writeDelay)
            if (failWrites) throw java.io.IOException("disk full")
            _settings.update { it.copy(llm = transform(it.llm)) }
        }

        override suspend fun setDynamicColor(enabled: Boolean) {
            _settings.update { it.copy(dynamicColor = enabled) }
        }

        override suspend fun setDarkTheme(enabled: Boolean) {
            _settings.update { it.copy(darkTheme = enabled) }
        }

        override suspend fun setUiTheme(mode: UiThemeMode) {
            _settings.update { it.copy(uiTheme = mode) }
        }

        override suspend fun resetLlm() {
            if (failWrites) throw java.io.IOException("disk full")
            _settings.update { it.copy(llm = LlmConfig()) }
        }

        override suspend fun setExpertMode(enabled: Boolean) {
            _settings.update { it.copy(expertMode = enabled) }
        }

        override suspend fun setSplitVisionLanguageMode(enabled: Boolean) {
            _settings.update { it.copy(splitVisionLanguageMode = enabled) }
        }

        override suspend fun setRootModeEnabled(enabled: Boolean) {
            _settings.update { it.copy(rootModeEnabled = enabled, allowShellSkills = false) }
        }

        override suspend fun setHighRiskToggle(
            toggle: HighRiskToggle,
            enabled: Boolean,
        ): HighRiskResult {
            val current = _settings.value
            val outcome = HighRiskRules.toggle(
                HighRiskState(
                    allowHttp = current.allowHttpTransport,
                    allowPublicHttp = current.allowPublicHttp,
                    allowShellSkills = current.allowShellSkills,
                    allowSensitiveScreen = current.allowSensitiveScreen,
                    rootModeEnabled = current.rootModeEnabled,
                ),
                toggle,
                enabled,
            )
            if (outcome.applied) {
                _settings.update {
                    it.copy(
                        allowHttpTransport = outcome.state.allowHttp,
                        allowPublicHttp = outcome.state.allowPublicHttp,
                        allowShellSkills = outcome.state.allowShellSkills,
                        allowSensitiveScreen = outcome.state.allowSensitiveScreen,
                    )
                }
            }
            return HighRiskResult(outcome.applied, outcome.message)
        }

        override suspend fun setRiskBypassMinutes(minutes: Int) {
            _settings.update { it.copy(riskBypassUntil = HighRiskRules.bypassUntil(minutes)) }
        }

        override suspend fun setAdaptivePacingEnabled(enabled: Boolean) {
            _settings.update { it.copy(adaptivePacingEnabled = enabled) }
        }
    }
}
