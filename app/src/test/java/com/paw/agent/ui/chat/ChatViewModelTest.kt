package com.paw.agent.ui.chat

import androidx.lifecycle.SavedStateHandle
import com.paw.agent.data.conversation.ConversationRepository

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
import com.paw.agent.data.settings.HighRiskResult
import com.paw.agent.data.settings.HighRiskRules
import com.paw.agent.data.settings.HighRiskState
import com.paw.agent.data.settings.HighRiskToggle
import com.paw.agent.data.settings.AppSettings
import com.paw.agent.data.settings.SettingsRepository
import com.paw.agent.data.settings.UiThemeMode
import com.paw.agent.ui.floating.AgentExecutionController
import com.paw.agent.core.agent.breakpoint.TaskBreakpoint
import com.paw.agent.runner.AgentTaskRunner
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
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `text draft waits for conversation restoration before being recovered`() = runTest(testDispatcher) {
        val ready = MutableStateFlow(false)
        val repository = object : ConversationRepository by conversationRepository {
            override val isInitialized = ready
        }
        val handle = SavedStateHandle(mapOf(
            ChatViewModel.DRAFT_CONVERSATION_KEY to conversationRepository.conversation.value.id,
            ChatViewModel.DRAFT_TEXT_KEY to "unfinished task",
        ))
        val viewModel = ChatViewModel(Agent(ScriptedLlmClient(emptyList())), repository, settingsRepository, savedStateHandle = handle)
        advanceUntilIdle()
        assertEquals("", viewModel.uiState.value.draft)
        assertEquals("unfinished task", handle.get<String>(ChatViewModel.DRAFT_TEXT_KEY))
        ready.value = true
        advanceUntilIdle()
        assertEquals("unfinished task", viewModel.uiState.value.draft)
    }

    @Test fun `draft from another conversation is never restored`() = runTest(testDispatcher) {
        val handle = SavedStateHandle(mapOf(
            ChatViewModel.DRAFT_CONVERSATION_KEY to "another-conversation",
            ChatViewModel.DRAFT_TEXT_KEY to "old task",
        ))
        val viewModel = ChatViewModel(Agent(ScriptedLlmClient(emptyList())), conversationRepository, settingsRepository, savedStateHandle = handle)
        advanceUntilIdle()
        assertEquals("", viewModel.uiState.value.draft)
        assertEquals("", handle.get<String>(ChatViewModel.DRAFT_TEXT_KEY))
    }

    @Test fun `user edit before initialization wins over restored draft`() = runTest(testDispatcher) {
        val ready = MutableStateFlow(false)
        val repository = object : ConversationRepository by conversationRepository {
            override val isInitialized = ready
        }
        val handle = SavedStateHandle(mapOf(
            ChatViewModel.DRAFT_CONVERSATION_KEY to conversationRepository.conversation.value.id,
            ChatViewModel.DRAFT_TEXT_KEY to "old text",
        ))
        val viewModel = ChatViewModel(Agent(ScriptedLlmClient(emptyList())), repository, settingsRepository, savedStateHandle = handle)
        viewModel.onDraftChange("new text")
        ready.value = true
        advanceUntilIdle()
        assertEquals("new text", viewModel.uiState.value.draft)
        assertEquals("new text", handle.get<String>(ChatViewModel.DRAFT_TEXT_KEY))
    }

    @Test fun `sending clears saved draft and a new conversation keeps its new edits`() = runTest(testDispatcher) {
        val handle = SavedStateHandle()
        val viewModel = ChatViewModel(Agent(ScriptedLlmClient(listOf(listOf(LlmChunk.Delta("done"))))), conversationRepository, settingsRepository, savedStateHandle = handle)
        advanceUntilIdle()
        viewModel.onDraftChange("task")
        assertEquals("task", handle.get<String>(ChatViewModel.DRAFT_TEXT_KEY))
        viewModel.onSendOrStop(config)
        advanceUntilIdle()
        assertEquals("", handle.get<String>(ChatViewModel.DRAFT_TEXT_KEY))
        viewModel.onDraftChange("discard this")
        viewModel.newConversation()
        viewModel.onDraftChange("keep new draft")
        advanceUntilIdle()
        assertEquals("keep new draft", viewModel.uiState.value.draft)
        assertEquals(conversationRepository.conversation.value.id, handle.get<String>(ChatViewModel.DRAFT_CONVERSATION_KEY))
        assertEquals("keep new draft", handle.get<String>(ChatViewModel.DRAFT_TEXT_KEY))
    }

    @Test fun `external conversation switch clears old draft and attachments`() = runTest(testDispatcher) {
        val handle = SavedStateHandle()
        val viewModel = ChatViewModel(Agent(ScriptedLlmClient(emptyList())), conversationRepository, settingsRepository, savedStateHandle = handle)
        val discarded = mutableListOf<String>()
        advanceUntilIdle()
        viewModel.onDraftChange("old task")
        viewModel.importImages(listOf("image"), importer = { _, _ -> "private-image" }, discard = { discarded += it })
        advanceUntilIdle()
        conversationRepository.newConversation()
        advanceUntilIdle()
        assertEquals("", viewModel.uiState.value.draft)
        assertTrue(viewModel.uiState.value.images.isEmpty())
        assertEquals(listOf("private-image"), discarded)
        assertEquals("", handle.get<String>(ChatViewModel.DRAFT_TEXT_KEY))
    }
    @Test fun `removed local files cannot be sent as invisible attachments`() = runTest(testDispatcher) {
        val viewModel = ChatViewModel(Agent(ScriptedLlmClient(emptyList())), conversationRepository, settingsRepository)
        viewModel.importImages(listOf("missing"), importer = { _, _ -> "file:///definitely-missing-agentpaw.jpg" })
        advanceUntilIdle()
        sendMessage(viewModel, "keep this text")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.attachmentError)
        assertEquals("keep this text", viewModel.uiState.value.draft)
        assertTrue(conversationRepository.conversation.value.messages.isEmpty())
    }
    @Test fun `images travel with the next user message and can be removed before sending`() = runTest(testDispatcher) {
        val viewModel = ChatViewModel(Agent(ScriptedLlmClient(listOf(listOf(LlmChunk.Delta("done"))))), conversationRepository, settingsRepository)
        viewModel.importImages(listOf("first", "second"), importer = { uri, _ -> "data:image/jpeg;base64,$uri" })
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.images.size)
        viewModel.removeImage(0)
        sendMessage(viewModel, "Analyze this image")
        advanceUntilIdle()
        assertEquals(listOf("data:image/jpeg;base64,second"), conversationRepository.conversation.value.messages.first { it.isUser }.images)
        assertTrue(viewModel.uiState.value.images.isEmpty())
    }

    @Test fun `attachment count is bounded and import failures preserve the text draft`() = runTest(testDispatcher) {
        val viewModel = ChatViewModel(Agent(ScriptedLlmClient(emptyList())), conversationRepository, settingsRepository)
        viewModel.onDraftChange("keep my draft")
        viewModel.importImages((1..6).map { "$it" }, importer = { uri, _ -> uri })
        advanceUntilIdle()
        assertEquals(4, viewModel.uiState.value.images.size)
        assertTrue(viewModel.uiState.value.attachmentError)
        assertEquals("keep my draft", viewModel.uiState.value.draft)
    }

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
    @Test
    fun `breakpoints isolate user tasks and retain completed steps across resume`() = runTest(testDispatcher) {
        var requests = 0
        val client = object : LlmClient {
            override fun complete(config: LlmConfig, request: ChatCompletionRequest): Flow<LlmChunk> = flow {
                when (val round = requests++) {
                    0, 2, 4 -> emit(LlmChunk.ToolCalls(listOf(ToolCall("echo_$round", "echo", "{\"step\":$round}"))))
                    1 -> emit(LlmChunk.Delta("first task done"))
                    else -> {
                        emit(LlmChunk.Delta("waiting"))
                        kotlinx.coroutines.awaitCancellation()
                    }
                }
            }
            override suspend fun testConnection(config: LlmConfig): Result<Unit> = Result.success(Unit)
        }
        val viewModel = ChatViewModel(Agent(client, ToolRegistry(listOf(EchoTool()))), conversationRepository, settingsRepository)
        sendMessage(viewModel, "first task")
        advanceUntilIdle()
        sendMessage(viewModel, "second task")
        advanceUntilIdle()
        viewModel.stop()
        advanceUntilIdle()

        val breakpoint = viewModel.taskRunner.activeBreakpoint.value!!
        assertEquals("second task", breakpoint.originalGoal)
        assertEquals(listOf("{\"step\":2}"), breakpoint.completedSteps.map { it.arguments })

        viewModel.resumeBreakpoint(config = config)
        advanceUntilIdle()
        viewModel.stop()
        advanceUntilIdle()
        assertEquals(listOf("{\"step\":2}", "{\"step\":4}"),
            viewModel.taskRunner.activeBreakpoint.value!!.completedSteps.map { it.arguments })
    }

    @Test
    fun `late disk recovery cannot replace a newly started task`() = runTest(testDispatcher) {
        val directory = temporaryFolder.newFolder()
        File(directory, "active_breakpoint.json").writeText(Json.encodeToString(
            TaskBreakpoint(originalGoal = "old goal", stoppedAtStep = 4),
        ))
        val client = object : LlmClient {
            override fun complete(config: LlmConfig, request: ChatCompletionRequest): Flow<LlmChunk> = flow {
                emit(LlmChunk.Delta("new task running"))
                kotlinx.coroutines.awaitCancellation()
            }
            override suspend fun testConnection(config: LlmConfig): Result<Unit> = Result.success(Unit)
        }
        val runner = AgentTaskRunner(agent = Agent(client), conversationRepository = conversationRepository,
            settingsRepository = settingsRepository, scope = backgroundScope,
            checkpointDirectory = directory, ioDispatcher = testDispatcher)
        runner.startTask(config, "new goal")
        testDispatcher.scheduler.runCurrent()

        assertNull(runner.activeBreakpoint.value)
        assertTrue(runner.isGenerating.value)
        assertTrue(AgentExecutionController.state.value.isRunning)
        assertEquals("new goal", File(directory, "running_task.json").readText())
        runner.stop()
        testDispatcher.scheduler.runCurrent()
    }

    @Test
    fun `dismiss during startup prevents a recovered breakpoint from reappearing`() = runTest(testDispatcher) {
        val directory = temporaryFolder.newFolder()
        File(directory, "active_breakpoint.json").writeText(Json.encodeToString(
            TaskBreakpoint(originalGoal = "discarded goal", stoppedAtStep = 4),
        ))
        val runner = AgentTaskRunner(agent = Agent(ScriptedLlmClient(emptyList())), conversationRepository = conversationRepository,
            settingsRepository = settingsRepository, scope = backgroundScope,
            checkpointDirectory = directory, ioDispatcher = testDispatcher)
        runner.dismissBreakpoint()
        testDispatcher.scheduler.runCurrent()

        assertNull(runner.activeBreakpoint.value)
        assertFalse(AgentExecutionController.state.value.isPaused)
        assertFalse(File(directory, "active_breakpoint.json").exists())
    }

    @Test
    fun `stopped coroutine cannot cancel the next task started immediately`() = runTest(testDispatcher) {
        val client = object : LlmClient {
            override fun complete(config: LlmConfig, request: ChatCompletionRequest): Flow<LlmChunk> = flow {
                emit(LlmChunk.Delta("running"))
                kotlinx.coroutines.awaitCancellation()
            }
            override suspend fun testConnection(config: LlmConfig): Result<Unit> = Result.success(Unit)
        }
        val viewModel = ChatViewModel(Agent(client), conversationRepository, settingsRepository)
        sendMessage(viewModel, "first task")
        testDispatcher.scheduler.runCurrent()
        viewModel.stop()
        sendMessage(viewModel, "second task")
        testDispatcher.scheduler.runCurrent()

        assertTrue(viewModel.taskRunner.isGenerating.value)
        assertTrue(AgentExecutionController.state.value.isRunning)
        assertEquals(MessageStatus.STREAMING, conversationRepository.conversation.value.messages.last().status)
        viewModel.stop()
        advanceUntilIdle()
    }

    @Test
    fun `stop after completion does not create a resumable task`() = runTest(testDispatcher) {
        val viewModel = ChatViewModel(Agent(ScriptedLlmClient(listOf(listOf(LlmChunk.Delta("done"))))), conversationRepository, settingsRepository)
        sendMessage(viewModel, "finish task")
        advanceUntilIdle()
        viewModel.stop()

        assertNull(viewModel.taskRunner.activeBreakpoint.value)
        assertEquals(MessageStatus.COMPLETE, conversationRepository.conversation.value.messages.last().status)
        assertFalse(AgentExecutionController.state.value.isPaused)
    }

    @Test
    fun `repeated stop preserves the original breakpoint`() = runTest(testDispatcher) {
        val client = object : LlmClient {
            override fun complete(config: LlmConfig, request: ChatCompletionRequest): Flow<LlmChunk> = flow {
                emit(LlmChunk.Delta("running"))
                kotlinx.coroutines.awaitCancellation()
            }
            override suspend fun testConnection(config: LlmConfig): Result<Unit> = Result.success(Unit)
        }
        val viewModel = ChatViewModel(Agent(client), conversationRepository, settingsRepository)
        sendMessage(viewModel, "pause task")
        testDispatcher.scheduler.runCurrent()
        viewModel.stop()
        val breakpoint = viewModel.taskRunner.activeBreakpoint.value
        viewModel.stop()
        assertEquals(breakpoint, viewModel.taskRunner.activeBreakpoint.value)
        advanceUntilIdle()
    }

    @Test
    fun `confirmation stops when tool reports a new safety pause`() = runTest(testDispatcher) {
        val tool = object : AgentTool {
            override val definition = ToolDefinition("risky", "Risk test tool", """{"type":"object"}""")
            override suspend fun execute(arguments: String, context: AgentContext): String =
                if (context.isGranted("risk_confirmed:risky")) {
                    """{"status":"paused","is_safety_pause":true,"message":"请输入密码"}"""
                } else {
                    """{"status":"requires_confirmation","action":"执行操作","tool_name":"risky","arguments":"{}"}"""
                }
        }
        val client = ScriptedLlmClient(listOf(
            listOf(LlmChunk.ToolCalls(listOf(ToolCall("risk_1", "risky", "{}")))),
            listOf(LlmChunk.Delta("must not resume")),
        ))
        val viewModel = ChatViewModel(Agent(client, ToolRegistry(listOf(tool))), conversationRepository, settingsRepository)
        sendMessage(viewModel, "执行任务")
        advanceUntilIdle()
        assertNotNull(viewModel.taskRunner.activeBreakpoint.value?.riskConfirmation)
        viewModel.confirmRiskAction(config)
        advanceUntilIdle()
        assertTrue(viewModel.taskRunner.activeBreakpoint.value?.isSafetyPaused == true)
        assertFalse(viewModel.taskRunner.isGenerating.value)
        assertFalse(conversationRepository.conversation.value.messages.any { it.content.contains("must not resume") })
    }

    @Test
    fun `confirmed action result reaches the resumed model with a matching call`() = runTest(testDispatcher) {
        val tool = object : AgentTool {
            override val definition = ToolDefinition("risky", "Risk test tool", """{"type":"object"}""")
            override suspend fun execute(arguments: String, context: AgentContext): String =
                if (context.isGranted("risk_confirmed:risky")) {
                    """{"status":"success","receipt":"receipt_42"}"""
                } else {
                    """{"status":"requires_confirmation","action":"执行操作","tool_name":"risky","arguments":"{}"}"""
                }
        }
        var calls = 0
        var resumedRequest: ChatCompletionRequest? = null
        val client = object : LlmClient {
            override fun complete(config: LlmConfig, request: ChatCompletionRequest): Flow<LlmChunk> = flow {
                if (calls++ == 0) emit(LlmChunk.ToolCalls(listOf(ToolCall("risk_1", "risky", "{}"))))
                else {
                    resumedRequest = request
                    emit(LlmChunk.Delta("done"))
                }
            }
            override suspend fun testConnection(config: LlmConfig): Result<Unit> = Result.success(Unit)
        }
        val viewModel = ChatViewModel(Agent(client, ToolRegistry(listOf(tool))), conversationRepository, settingsRepository)
        sendMessage(viewModel, "执行任务")
        advanceUntilIdle()
        viewModel.confirmRiskAction(config)
        advanceUntilIdle()

        val confirmedAssistant = conversationRepository.conversation.value.messages.first {
            it.toolCalls.any { call -> call.id.startsWith("confirmed_") }
        }
        val confirmedCall = confirmedAssistant.toolCalls.single()
        assertTrue(resumedRequest!!.messages.any {
            it.role == "tool" && it.toolCallId == confirmedCall.id && it.content?.asString()?.contains("receipt_42") == true
        })
        assertFalse(viewModel.taskRunner.isGenerating.value)
    }

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
