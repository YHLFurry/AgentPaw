package com.paw.agent.device

import com.paw.agent.device.accessibility.AgentAccessibilityService
import com.paw.agent.device.root.RootController
import com.paw.agent.device.shizuku.ShizukuController
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class HybridPhoneControllerInputTest {
    @Before fun clearStop() = AgentAccessibilityService.clearGlobalStop()

    private class Root : RootController() {
        override val isAvailable = true
        var calls = 0
        var succeeds = true
        override suspend fun inputText(text: String): Boolean { calls++; return succeeds }
        override suspend fun executeCommand(cmd: String, timeoutMs: Long): String { calls++; return "OK" }
    }
    private class Shizuku : ShizukuController() {
        override val isAvailable = true
        var calls = 0
        override suspend fun inputText(text: String): Boolean { calls++; return true }
    }

    @Test fun `direct unicode input bypasses clipboard and shell even with root available`() = runTest {
        val root = Root()
        val shizuku = Shizuku()
        val requests = mutableListOf<Pair<String, Boolean>>()
        val controller = object : HybridPhoneController(rootController = root, shizukuController = shizuku) {
            override suspend fun accessibilityInputText(text: String, clearBeforeInput: Boolean): Boolean {
                requests += text to clearBeforeInput
                return true
            }
        }
        assertTrue(controller.inputText("中文🐾", true))
        assertEquals(listOf("中文🐾" to true), requests)
        assertEquals(0, root.calls)
        assertEquals(0, shizuku.calls)
    }

    @Test fun `failed shell input never repeats via second engine`() = runTest {
        val root = Root().apply { succeeds = false }
        val shizuku = Shizuku()
        val controller = object : HybridPhoneController(rootController = root, shizukuController = shizuku) {
            override suspend fun accessibilityInputText(text: String, clearBeforeInput: Boolean) = false
        }
        assertFalse(controller.inputText("may be partially typed", false))
        assertEquals(1, root.calls)
        assertEquals(0, shizuku.calls)
    }

    @Test fun `unavailable clipboard fails without sending a paste key for old content`() = runTest {
        val root = Root()
        val controller = object : HybridPhoneController(rootController = root) {
            override suspend fun accessibilityInputText(text: String, clearBeforeInput: Boolean) = false
        }
        assertFalse(controller.inputText("中文", true))
        assertEquals(0, root.calls)
        assertFalse(controller.inputText("first\nsecond", true))
        assertEquals(0, root.calls)
        assertFalse(controller.inputText("literal %s and 100%", true))
        assertEquals(0, root.calls)
    }
}
