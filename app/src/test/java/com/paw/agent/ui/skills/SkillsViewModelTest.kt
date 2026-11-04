package com.paw.agent.ui.skills

import com.paw.agent.core.llm.LlmChunk
import com.paw.agent.core.llm.LlmClient
import com.paw.agent.core.llm.LlmConfig
import com.paw.agent.core.llm.dto.ChatCompletionRequest
import com.paw.agent.core.skill.SkillEngine
import com.paw.agent.core.skill.SkillPresets
import com.paw.agent.core.skill.SkillRegistry
import com.paw.agent.data.skill.SkillStore
import com.paw.agent.device.PhoneController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class SkillsViewModelTest {
    @get:Rule val temp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private fun viewModel(store: SkillStore): SkillsViewModel {
        val controller = Proxy.newProxyInstance(PhoneController::class.java.classLoader,
            arrayOf(PhoneController::class.java)) { _, _, _ -> error("Editor must not operate device") } as PhoneController
        val client = object : LlmClient {
            override fun complete(config: LlmConfig, request: ChatCompletionRequest): Flow<LlmChunk> = emptyFlow()
            override suspend fun testConnection(config: LlmConfig) = Result.success(Unit)
        }
        return SkillsViewModel(store, SkillRegistry(), SkillEngine(controller), client, {})
    }

    @Test fun `invalid JSON survives mode switch and structured control attempts`() = runTest {
        val store = SkillStore(scope = backgroundScope, storageDirectory = temp.newFolder())
        val vm = viewModel(store)
        vm.startCreate()
        vm.setEditorMode(SkillEditorMode.ADVANCED)
        vm.onJsonChange("{ unfinished")
        vm.setEditorMode(SkillEditorMode.SIMPLE)
        vm.updateGuard { it.copy(confirmBeforeRun = true) }
        assertEquals(SkillEditorMode.ADVANCED, vm.uiState.value.editorMode)
        assertEquals("{ unfinished", vm.uiState.value.jsonText)
        assertFalse(vm.uiState.value.canSave)
        assertTrue(vm.uiState.value.hasUnsavedChanges)
    }

    @Test fun `invalid DSL cannot be discarded by switching modes`() = runTest {
        val store = SkillStore(scope = backgroundScope, storageDirectory = temp.newFolder())
        val vm = viewModel(store)
        vm.startCreate()
        val invalid = "---\nunknown action"
        vm.onDslChange(invalid)
        vm.setEditorMode(SkillEditorMode.ADVANCED)
        assertEquals(SkillEditorMode.SIMPLE, vm.uiState.value.editorMode)
        assertEquals(invalid, vm.uiState.value.dslText)
        assertTrue(vm.uiState.value.message.orEmpty().isNotBlank())
    }

    @Test fun `saving twice writes once and closes the saved draft`() = runTest {
        val store = SkillStore(scope = backgroundScope, storageDirectory = temp.newFolder())
        store.awaitLoaded()
        val vm = viewModel(store)
        vm.startCreate()
        var callbacks = 0
        val saved = CompletableDeferred<Unit>()
        vm.save { callbacks++; saved.complete(Unit) }
        assertTrue(vm.uiState.value.saving)
        assertFalse(vm.uiState.value.canSave)
        vm.save { callbacks++ }
        saved.await()
        assertEquals(1, callbacks)
        assertEquals(1, store.skills.value.size)
        assertNull(vm.uiState.value.editing)
        assertFalse(vm.uiState.value.saving)
    }

    @Test fun `new skills receive an unused key and untouched drafts are clean`() = runTest {
        val store = SkillStore(scope = backgroundScope, storageDirectory = temp.newFolder())
        store.awaitLoaded()
        store.save(SkillPresets.blankTemplate())
        val vm = viewModel(store)
        vm.startCreate()
        assertEquals("skill_custom_2", vm.uiState.value.editing?.key)
        assertTrue(vm.uiState.value.isNewSkill)
        assertFalse(vm.uiState.value.hasUnsavedChanges)
    }
}
