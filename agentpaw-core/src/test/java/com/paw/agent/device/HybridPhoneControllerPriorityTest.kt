package com.paw.agent.device
 
import com.paw.agent.device.root.RootController
import com.paw.agent.device.shizuku.ShizukuController
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
 
class HybridPhoneControllerPriorityTest {
 
    private class TestRootController(
        var available: Boolean = true,
        var tapResult: Boolean = true,
        var swipeResult: Boolean = true,
        var keyEventResult: Boolean = true,
        var inputTextResult: Boolean = true,
        var commandOutput: String = "OK",
    ) : RootController() {
        override val isAvailable: Boolean get() = available
        val calls = mutableListOf<String>()
 
        override suspend fun tap(x: Int, y: Int): Boolean {
            calls.add("tap($x,$y)")
            return tapResult
        }
 
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
            calls.add("swipe($x1,$y1,$x2,$y2)")
            return swipeResult
        }
 
        override suspend fun keyEvent(keyCode: Int): Boolean {
            calls.add("keyEvent($keyCode)")
            return keyEventResult
        }
 
        override suspend fun inputText(text: String): Boolean {
            calls.add("inputText($text)")
            return inputTextResult
        }
 
        override suspend fun executeCommand(cmd: String, timeoutMs: Long): String {
            calls.add("exec($cmd)")
            return commandOutput
        }
 
        override suspend fun getForegroundInfo(): Pair<String, String> {
            calls.add("getForegroundInfo")
            return Pair("com.test.root", "RootActivity")
        }
    }
 
    private class TestShizukuController(
        var available: Boolean = true,
        var tapResult: Boolean = true,
        var swipeResult: Boolean = true,
        var keyEventResult: Boolean = true,
        var inputTextResult: Boolean = true,
        var commandOutput: String = "OK",
    ) : ShizukuController() {
        override val isAvailable: Boolean get() = available
        val calls = mutableListOf<String>()
 
        override suspend fun tap(x: Int, y: Int): Boolean {
            calls.add("tap($x,$y)")
            return tapResult
        }
 
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
            calls.add("swipe($x1,$y1,$x2,$y2)")
            return swipeResult
        }
 
        override suspend fun keyEvent(keyCode: Int): Boolean {
            calls.add("keyEvent($keyCode)")
            return keyEventResult
        }
 
        override suspend fun inputText(text: String): Boolean {
            calls.add("inputText($text)")
            return inputTextResult
        }
 
        override suspend fun executeCommand(cmd: String, timeoutMs: Long): String {
            calls.add("exec($cmd)")
            return commandOutput
        }
 
        override suspend fun getForegroundInfo(): Pair<String, String> {
            calls.add("getForegroundInfo")
            return Pair("com.test.shizuku", "ShizukuActivity")
        }
    }
 
    @Test
    fun `when root is available, controller uses pure root mode first and does not call shizuku`() = runTest {
        val root = TestRootController(available = true)
        val shizuku = TestShizukuController(available = true)
        val controller = HybridPhoneController(
            context = null,
            rootController = root,
            shizukuController = shizuku,
        )
 
        assertEquals(ExecutionEngine.ROOT, controller.activeExecutionEngine)
 
        // 1. tap
        val tapOk = controller.tap(500, 500)
        assertTrue(tapOk)
        assertTrue(root.calls.any { it.startsWith("tap") })
        assertTrue("Shizuku should NOT be called when Root succeeds", shizuku.calls.none { it.startsWith("tap") })
 
        // 2. doubleTap
        root.calls.clear()
        val dtOk = controller.doubleTap(500, 500)
        assertTrue(dtOk)
        assertTrue(root.calls.count { it.startsWith("tap") } == 2)
        assertTrue(shizuku.calls.isEmpty())
 
        // 3. swipe
        root.calls.clear()
        val swipeOk = controller.swipe(100, 100, 500, 500)
        assertTrue(swipeOk)
        assertTrue(root.calls.any { it.startsWith("swipe") })
        assertTrue(shizuku.calls.isEmpty())
 
        // 4. keys (back, home, recents, enter)
        root.calls.clear()
        assertTrue(controller.pressBack())
        assertTrue(controller.pressHome())
        assertTrue(controller.pressRecents())
        assertTrue(controller.pressEnter())
        assertTrue(root.calls.contains("keyEvent(4)"))
        assertTrue(root.calls.contains("keyEvent(3)"))
        assertTrue(root.calls.contains("keyEvent(187)"))
        assertTrue(root.calls.contains("keyEvent(66)"))
        assertTrue(shizuku.calls.isEmpty())
 
        // 5. inputText (ASCII)
        root.calls.clear()
        assertTrue(controller.inputText("hello", clearBeforeInput = false))
        assertTrue(root.calls.contains("inputText(hello)"))
        assertTrue(shizuku.calls.isEmpty())
 
        // 6. launchApp via Root monkey
        root.calls.clear()
        val launchOk = controller.launchApp("com.android.settings")
        assertTrue(launchOk)
        assertTrue(root.calls.any { it.contains("monkey -p com.android.settings") })
        assertTrue(shizuku.calls.isEmpty())
 
        // 7. getScreenState foreground info from Root
        root.calls.clear()
        val state = controller.getScreenState()
        assertEquals("com.test.root", state.foregroundPackage)
        assertEquals("RootActivity", state.foregroundActivity)
        assertTrue(root.calls.contains("getForegroundInfo"))
        assertTrue(shizuku.calls.isEmpty())
    }
 
    @Test
    fun `when root is unavailable and shizuku is available, controller uses pure shizuku mode`() = runTest {
        val root = TestRootController(available = false)
        val shizuku = TestShizukuController(available = true)
        val controller = HybridPhoneController(
            context = null,
            rootController = root,
            shizukuController = shizuku,
        )
 
        assertEquals(ExecutionEngine.SHIZUKU, controller.activeExecutionEngine)
 
        // 1. tap
        val tapOk = controller.tap(500, 500)
        assertTrue(tapOk)
        assertTrue(root.calls.isEmpty())
        assertTrue(shizuku.calls.any { it.startsWith("tap") })
 
        // 2. swipe
        shizuku.calls.clear()
        val swipeOk = controller.swipe(100, 100, 500, 500)
        assertTrue(swipeOk)
        assertTrue(shizuku.calls.any { it.startsWith("swipe") })
 
        // 3. keys
        shizuku.calls.clear()
        assertTrue(controller.pressBack())
        assertTrue(controller.pressHome())
        assertTrue(controller.pressRecents())
        assertTrue(controller.pressEnter())
        assertTrue(shizuku.calls.contains("keyEvent(4)"))
        assertTrue(shizuku.calls.contains("keyEvent(3)"))
        assertTrue(shizuku.calls.contains("keyEvent(187)"))
        assertTrue(shizuku.calls.contains("keyEvent(66)"))
 
        // 4. inputText
        shizuku.calls.clear()
        assertTrue(controller.inputText("test input", clearBeforeInput = false))
        assertTrue(shizuku.calls.contains("inputText(test input)"))
 
        // 5. launchApp
        shizuku.calls.clear()
        val launchOk = controller.launchApp("com.tencent.mm")
        assertTrue(launchOk)
        assertTrue(shizuku.calls.any { it.contains("monkey -p com.tencent.mm") })
 
        // 6. getScreenState
        shizuku.calls.clear()
        val state = controller.getScreenState()
        assertEquals("com.test.shizuku", state.foregroundPackage)
        assertEquals("ShizukuActivity", state.foregroundActivity)
        assertTrue(shizuku.calls.contains("getForegroundInfo"))
    }
 
    @Test
    fun `when root fails for an action, it smoothly falls back to shizuku`() = runTest {
        val root = TestRootController(available = true, tapResult = false)
        val shizuku = TestShizukuController(available = true, tapResult = true)
        val controller = HybridPhoneController(
            context = null,
            rootController = root,
            shizukuController = shizuku,
        )
 
        // Tap should attempt root first, fail, then fall back to shizuku and succeed
        val tapOk = controller.tap(500, 500)
        assertTrue(tapOk)
        assertTrue("Root tap should have been attempted", root.calls.any { it.startsWith("tap") })
        assertTrue("Shizuku tap should have been invoked as fallback", shizuku.calls.any { it.startsWith("tap") })
    }
 
    @Test
    fun `control mode SHIZUKU explicitly bypasses root even when root is available`() = runTest {
        val root = TestRootController(available = true)
        val shizuku = TestShizukuController(available = true)
        val controller = HybridPhoneController(
            context = null,
            rootController = root,
            shizukuController = shizuku,
        )
        controller.controlMode = PhoneControlMode.SHIZUKU
 
        assertEquals(ExecutionEngine.SHIZUKU, controller.activeExecutionEngine)
 
        val tapOk = controller.tap(300, 300)
        assertTrue(tapOk)
        assertTrue("Root should not be called in SHIZUKU mode", root.calls.isEmpty())
        assertTrue("Shizuku should be called in SHIZUKU mode", shizuku.calls.any { it.startsWith("tap") })
    }
}
