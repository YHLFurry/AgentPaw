package com.paw.agent.ui.settings

import com.paw.agent.core.llm.LlmChunk
import com.paw.agent.core.llm.LlmClient
import com.paw.agent.core.llm.LlmConfig
import com.paw.agent.core.llm.dto.ChatCompletionRequest
import com.paw.agent.data.settings.AppSettings
import com.paw.agent.data.settings.SettingsRepository
import com.paw.agent.data.settings.UiThemeMode
import kotlinx.coroutines.Dispatchers
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
    fun `clamps maxToolRounds within 1 to 50`() = runTest {
        val viewModel = LlmSettingsViewModel(fakeSettingsRepo, fakeLlmClient)
        advanceUntilIdle()

        viewModel.onMaxToolRoundsChange(0)
        assertEquals(1, viewModel.uiState.value.maxToolRounds)

        viewModel.onMaxToolRoundsChange(100)
        assertEquals(50, viewModel.uiState.value.maxToolRounds)
    }

    private class FakeLlmClient : LlmClient {
        override fun complete(config: LlmConfig, request: ChatCompletionRequest): Flow<LlmChunk> = emptyFlow()
        override suspend fun testConnection(config: LlmConfig): Result<Unit> = Result.success(Unit)
    }

    private class FakeSettingsRepository : SettingsRepository {
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
            _settings.update { it.copy(llm = LlmConfig()) }
        }

        override suspend fun setExpertMode(enabled: Boolean) {
            _settings.update { it.copy(expertMode = enabled) }
        }

        override suspend fun setSplitVisionLanguageMode(enabled: Boolean) {
            _settings.update { it.copy(splitVisionLanguageMode = enabled) }
        }

        override suspend fun setRootModeEnabled(enabled: Boolean) {
            _settings.update { it.copy(rootModeEnabled = enabled) }
        }

        override suspend fun setAdaptivePacingEnabled(enabled: Boolean) {
            _settings.update { it.copy(adaptivePacingEnabled = enabled) }
        }
    }
}
