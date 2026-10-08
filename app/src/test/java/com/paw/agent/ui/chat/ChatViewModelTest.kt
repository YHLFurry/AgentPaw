package com.paw.agent.ui.chat

import com.paw.agent.core.agent.Agent
import com.paw.agent.core.agent.AgentContext
import com.paw.agent.core.agent.AgentTool
import com.paw.agent.core.agent.ToolRegistry
import com.paw.agent.core.llm.LlmChunk
import com.paw.agent.core.llm.LlmClient
import com.paw.agent.core.llm.LlmConfig
import com.paw.agent.core.llm.dto.ChatCompletionRequest
import com.paw.agent.core.model.MessageRole
import com.paw.agent.core.model.MessageStatus
import com.paw.agent.core.model.ToolCall
import com.paw.agent.core.model.ToolDefinition
import com.paw.agent.data.conversation.InMemoryConversationRepository
import com.paw.agent.data.settings.AppSettings
import com.paw.agent.data.settings.SettingsRepository
import com.paw.agent.data.settings.UiThemeMode
import com.paw.agent.ui.floating.AgentExecutionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val conversationRepository = InMemoryConversationRepository()
    private val settingsRepository = FakeSettingsRepository()

    private val config = LlmConfig(
        baseUrl = "https://example.test/v1",
        model = "test-model",
        apiKey = "key",
        stream = true,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        conversationRepository.clear()
    }

    @After
    fun tearDown() {
        AgentExecutionController.unregisterStopCallback()
        AgentExecutionController.unregisterResumeCallback()
        Dispatchers.resetMain()
    }

    private fun sendMessage(viewModel: ChatViewModel, text: String) {
        viewModel.onDraftChange(text)
        viewModel.onSendOrStop(config)
    }

    @Test
    fun `AssistantDelta replaces content rather than accumulating duplicates (regression bug 10)`() = runTest(testDispatcher) {
        // Mock streaming with cumulative deltas: "Hello", then "Hello world", then "Hello world!"
        val client = ScriptedLlmClient(
            listOf(
                listOf(
                    LlmChunk.Delta("Hello"),
                    LlmChunk.Delta(" world"),
                    LlmChunk.Delta("!"),
                ),
            ),
        )
        val agent = Agent(client, ToolRegistry())
        val viewModel = ChatViewModel(agent, conversationRepository, settingsRepository)

        sendMessage(viewModel, "Hi")
        advanceUntilIdle()

        val messages = conversationRepository.conversation.value.messages
        assertEquals(2, messages.size)
        assertEquals(MessageRole.USER, messages[0].role)
        assertEquals("Hi", messages[0].content)

        val assistant = messages[1]
        assertEquals(MessageRole.ASSISTANT, assistant.role)
        // With updateMessage, content must be exactly "Hello world!", not "HelloHello worldHello world!"
        assertEquals("Hello world!", assistant.content)
        assertEquals(MessageStatus.COMPLETE, assistant.status)
    }

    @Test
    fun `ToolStarted preserves assistant message with toolCalls for valid protocol`() = runTest(testDispatcher) {
        // Model directly emits tool call without prior text
        val toolCall = ToolCall(id = "call_1", name = "echo", arguments = "{\"text\":\"ping\"}")
        val client = ScriptedLlmClient(
            listOf(
                listOf(LlmChunk.ToolCalls(listOf(toolCall))),
                listOf(LlmChunk.Delta("All done")),
            ),
        )
        val agent = Agent(client, ToolRegistry(listOf(EchoTool())))
        val viewModel = ChatViewModel(agent, conversationRepository, settingsRepository)

        sendMessage(viewModel, "Execute tool")
        advanceUntilIdle()

        val messages = conversationRepository.conversation.value.messages
        // Must preserve assistant message with toolCalls before the tool message
        assertEquals(4, messages.size)
        assertEquals(MessageRole.USER, messages[0].role)
        assertEquals(MessageRole.ASSISTANT, messages[1].role)
        assertEquals(listOf(toolCall), messages[1].toolCalls)
        assertEquals(MessageRole.TOOL, messages[2].role)
        assertEquals("call_1", messages[2].toolCallId)
        assertEquals(MessageRole.ASSISTANT, messages[3].role)
        assertEquals("All done", messages[3].content)
        assertEquals(MessageStatus.COMPLETE, messages[3].status)
    }

    @Test
    fun `multi-round turn maintains correct assistant and tool lifecycle without overwriting`() = runTest(testDispatcher) {
        // Round 1: Model outputs thought, then invokes tool
        // Round 2: Model outputs final answer
        val toolCall = ToolCall(id = "call_2", name = "echo", arguments = "{\"text\":\"foo\"}")
        val client = ScriptedLlmClient(
            listOf(
                listOf(
                    LlmChunk.Delta("Let me inspect "),
                    LlmChunk.Delta("the screen first."),
                    LlmChunk.ToolCalls(listOf(toolCall)),
                ),
                listOf(
                    LlmChunk.Delta("Inspected. "),
                    LlmChunk.Delta("Final answer ready."),
                ),
            ),
        )
        val agent = Agent(client, ToolRegistry(listOf(EchoTool())))
        val viewModel = ChatViewModel(agent, conversationRepository, settingsRepository)

        sendMessage(viewModel, "Do multi-round")
        advanceUntilIdle()

        val messages = conversationRepository.conversation.value.messages
        // Expected:
        // 0: USER ("Do multi-round")
        // 1: ASSISTANT ("Let me inspect the screen first.", COMPLETE)
        // 2: TOOL ("call_2", COMPLETE)
        // 3: ASSISTANT ("Inspected. Final answer ready.", COMPLETE)
        assertEquals(4, messages.size)

        assertEquals(MessageRole.USER, messages[0].role)

        val firstAssistant = messages[1]
        assertEquals(MessageRole.ASSISTANT, firstAssistant.role)
        assertEquals("Let me inspect the screen first.", firstAssistant.content)
        assertEquals(MessageStatus.COMPLETE, firstAssistant.status)

        val toolMsg = messages[2]
        assertEquals(MessageRole.TOOL, toolMsg.role)
        assertEquals("call_2", toolMsg.toolCallId)
        assertEquals(MessageStatus.COMPLETE, toolMsg.status)

        val secondAssistant = messages[3]
        assertEquals(MessageRole.ASSISTANT, secondAssistant.role)
        assertEquals("Inspected. Final answer ready.", secondAssistant.content)
        assertEquals(MessageStatus.COMPLETE, secondAssistant.status)

        // Ensure distinct IDs for the two assistant messages
        assertNotEquals(firstAssistant.id, secondAssistant.id)
    }

    @Test
    fun `stop cleanly cancels active assistant message and resets generating state`() = runTest(testDispatcher) {
        // Model with infinite stream or long delay
        val client = object : LlmClient {
            override fun complete(config: LlmConfig, request: ChatCompletionRequest): Flow<LlmChunk> = flow {
                emit(LlmChunk.Delta("Generating part 1... "))
                // Suspend indefinitely until cancelled
                kotlinx.coroutines.awaitCancellation()
            }
            override suspend fun testConnection(config: LlmConfig): Result<Unit> = Result.success(Unit)
        }
        val agent = Agent(client, ToolRegistry())
        val viewModel = ChatViewModel(agent, conversationRepository, settingsRepository)

        sendMessage(viewModel, "Long task")
        // Run until the first delta is collected
        testDispatcher.scheduler.runCurrent()

        assertTrue(viewModel.uiState.value.isGenerating)
        assertTrue(AgentExecutionController.state.value.isRunning)

        viewModel.stop()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isGenerating)
        assertFalse(AgentExecutionController.state.value.isRunning)

        val messages = conversationRepository.conversation.value.messages
        val assistant = messages.last()
        assertEquals(MessageRole.ASSISTANT, assistant.role)
        assertEquals(MessageStatus.CANCELLED, assistant.status)
    }

    @Test
    fun `CancellationException in flow updates message status to CANCELLED`() = runTest(testDispatcher) {
        val client = object : LlmClient {
            override fun complete(config: LlmConfig, request: ChatCompletionRequest): Flow<LlmChunk> = flow {
                emit(LlmChunk.Delta("Starting..."))
                throw kotlinx.coroutines.CancellationException("Job cancelled by external scope")
            }
            override suspend fun testConnection(config: LlmConfig): Result<Unit> = Result.success(Unit)
        }
        val agent = Agent(client, ToolRegistry())
        val viewModel = ChatViewModel(agent, conversationRepository, settingsRepository)

        sendMessage(viewModel, "Will cancel")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isGenerating)
        val messages = conversationRepository.conversation.value.messages
        val assistant = messages.last()
        assertEquals(MessageRole.ASSISTANT, assistant.role)
        assertEquals(MessageStatus.CANCELLED, assistant.status)
    }

    @Test
    fun `stop preserves breakpoint and sending resume continues task from breakpoint`() = runTest(testDispatcher) {
        val client = object : LlmClient {
            override fun complete(config: LlmConfig, request: ChatCompletionRequest): Flow<LlmChunk> = flow {
                emit(LlmChunk.Delta("Step 1 executed successfully"))
                kotlinx.coroutines.awaitCancellation()
            }
            override suspend fun testConnection(config: LlmConfig): Result<Unit> = Result.success(Unit)
        }
        val agent = Agent(client, ToolRegistry())
        val viewModel = ChatViewModel(agent, conversationRepository, settingsRepository)

        sendMessage(viewModel, "自动化下单流程")
        testDispatcher.scheduler.runCurrent()

        viewModel.stop()
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.activeBreakpoint)
        assertEquals("自动化下单流程", viewModel.uiState.value.activeBreakpoint?.originalGoal)

        // Sending "继续" automatically triggers breakpoint continuation
        sendMessage(viewModel, "继续执行剩余操作")
        testDispatcher.scheduler.runCurrent()

        // Active breakpoint is consumed upon resuming
        assertNull(viewModel.uiState.value.activeBreakpoint)
        viewModel.stop()
    }

    // --- Helpers ---

    private class ScriptedLlmClient(
        private val script: List<List<LlmChunk>>,
    ) : LlmClient {
        private var callCount = 0

        override fun complete(config: LlmConfig, request: ChatCompletionRequest): Flow<LlmChunk> = flow {
            val turn = script.getOrElse(callCount) { emptyList() }
            callCount++
            turn.forEach { emit(it) }
        }

        override suspend fun testConnection(config: LlmConfig): Result<Unit> = Result.success(Unit)
    }

    private class EchoTool : AgentTool {
        override val definition = ToolDefinition(
            name = "echo",
            description = "Echo tool for tests",
            parametersSchema = """{"type":"object"}""",
        )

        override suspend fun execute(arguments: String, context: AgentContext): String =
            "echo_result:$arguments"
    }

    private class FakeSettingsRepository : SettingsRepository {
        private val _settings = MutableStateFlow(AppSettings(llm = LlmConfig(stream = true)))
        override val settings: Flow<AppSettings> = _settings

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
