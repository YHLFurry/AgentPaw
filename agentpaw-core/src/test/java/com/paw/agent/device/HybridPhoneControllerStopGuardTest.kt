package com.paw.agent.device

import com.paw.agent.device.accessibility.AgentAccessibilityService
import com.paw.agent.device.root.RootController
import com.paw.agent.device.shizuku.ShizukuController
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-201：用户停止指令必须对**所有**会改变设备状态的操作生效。
 *
 * 背景：tap/doubleTap/longPress/tapAtPixel/swipe/inputText/pressBack/pressHome/
 * pressRecents/pressEnter 都以 `userStopRequested()` 门禁开头，但 launchApp 与
 * openDeepLink 遗漏了该检查——用户按下停止胶囊后，Agent 仍会拉起目标应用或
 * 打开 DeepLink，违反「停止即全部中止」的产品契约。
 *
 * 本测试在修复前对 launchApp/openDeepLink 断言失败（红），修复后通过（绿）。
 */
class HybridPhoneControllerStopGuardTest {

    private class RecordingRootController(
        var available: Boolean = true,
    ) : RootController() {
        override val isAvailable: Boolean get() = available
        val calls = mutableListOf<String>()

        override suspend fun tap(x: Int, y: Int): Boolean {
            calls.add("tap($x,$y)")
            return true
        }

        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
            calls.add("swipe($x1,$y1,$x2,$y2)")
            return true
        }

        override suspend fun keyEvent(keyCode: Int): Boolean {
            calls.add("keyEvent($keyCode)")
            return true
        }

        override suspend fun inputText(text: String): Boolean {
            calls.add("inputText($text)")
            return true
        }

        override suspend fun executeCommand(cmd: String, timeoutMs: Long): String {
            calls.add("exec($cmd)")
            return "OK"
        }

        override suspend fun getForegroundInfo(): Pair<String, String> {
            calls.add("getForegroundInfo")
            return Pair("com.test.root", "RootActivity")
        }
    }

    private class RecordingShizukuController(
        var available: Boolean = true,
    ) : ShizukuController() {
        override val isAvailable: Boolean get() = available
        val calls = mutableListOf<String>()

        override suspend fun tap(x: Int, y: Int): Boolean {
            calls.add("tap($x,$y)")
            return true
        }

        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
            calls.add("swipe($x1,$y1,$x2,$y2)")
            return true
        }

        override suspend fun keyEvent(keyCode: Int): Boolean {
            calls.add("keyEvent($keyCode)")
            return true
        }

        override suspend fun inputText(text: String): Boolean {
            calls.add("inputText($text)")
            return true
        }

        override suspend fun executeCommand(cmd: String, timeoutMs: Long): String {
            calls.add("exec($cmd)")
            return "OK"
        }

        override suspend fun getForegroundInfo(): Pair<String, String> {
            calls.add("getForegroundInfo")
            return Pair("com.test.shizuku", "ShizukuActivity")
        }
    }

    @After
    fun tearDown() {
        // 全局停止标志是进程级静态状态，务必复位，避免污染同进程其他测试
        AgentAccessibilityService.clearGlobalStop()
    }

    @Test
    fun `without stop request launchApp and openDeepLink execute normally`() = runTest {
        val root = RecordingRootController(available = true)
        val controller = HybridPhoneController(
            context = null,
            rootController = root,
            shizukuController = RecordingShizukuController(available = true),
        )

        assertTrue(controller.launchApp("com.android.settings"))
        assertTrue(root.calls.any { it.contains("monkey -p com.android.settings") })

        root.calls.clear()
        assertTrue(controller.openDeepLink("https://example.com/page"))
        assertTrue(root.calls.any { it.contains("am start") && it.contains("https://example.com/page") })
    }

    @Test
    fun `after user stop launchApp does not launch anything`() = runTest {
        val root = RecordingRootController(available = true)
        val shizuku = RecordingShizukuController(available = true)
        val controller = HybridPhoneController(
            context = null,
            rootController = root,
            shizukuController = shizuku,
        )

        AgentAccessibilityService.requestGlobalStop()

        val ok = controller.launchApp("com.tencent.mm")
        assertFalse("用户已停止，launchApp 必须失败", ok)
        assertTrue("停止后不得触达 Root 通道", root.calls.isEmpty())
        assertTrue("停止后不得触达 Shizuku 通道", shizuku.calls.isEmpty())
    }

    @Test
    fun `after user stop openDeepLink does not open anything`() = runTest {
        val root = RecordingRootController(available = true)
        val shizuku = RecordingShizukuController(available = true)
        val controller = HybridPhoneController(
            context = null,
            rootController = root,
            shizukuController = shizuku,
        )

        AgentAccessibilityService.requestGlobalStop()

        val ok = controller.openDeepLink("alipays://platformapi/startapp")
        assertFalse("用户已停止，openDeepLink 必须失败", ok)
        assertTrue("停止后不得触达 Root 通道", root.calls.isEmpty())
        assertTrue("停止后不得触达 Shizuku 通道", shizuku.calls.isEmpty())
    }

    @Test
    fun `stop guard covers every state-changing operation consistently`() = runTest {
        val root = RecordingRootController(available = true)
        val shizuku = RecordingShizukuController(available = true)
        val controller = HybridPhoneController(
            context = null,
            rootController = root,
            shizukuController = shizuku,
        )

        AgentAccessibilityService.requestGlobalStop()

        // 全部状态变更类操作都应被同一条门禁拦住
        assertFalse(controller.tap(500, 500))
        assertFalse(controller.doubleTap(500, 500))
        assertFalse(controller.longPress(500, 500, 1000L))
        assertFalse(controller.tapAtPixel(540f, 1200f))
        assertFalse(controller.swipe(100, 800, 100, 200))
        assertFalse(controller.inputText("hello", clearBeforeInput = false))
        assertFalse(controller.pressBack())
        assertFalse(controller.pressHome())
        assertFalse(controller.pressRecents())
        assertFalse(controller.pressEnter())
        assertFalse(controller.launchApp("com.tencent.mm"))
        assertFalse(controller.openDeepLink("https://example.com"))

        assertTrue("停止后任何通道都不应被调用", root.calls.isEmpty() && shizuku.calls.isEmpty())
    }

    @Test
    fun `stop guard clears when a new task starts`() = runTest {
        val root = RecordingRootController(available = true)
        val controller = HybridPhoneController(
            context = null,
            rootController = root,
            shizukuController = RecordingShizukuController(available = true),
        )

        AgentAccessibilityService.requestGlobalStop()
        assertFalse(controller.launchApp("com.android.settings"))

        // 新一轮任务开始前会清除全局停止标志，此后应恢复放行
        AgentAccessibilityService.clearGlobalStop()
        assertTrue(controller.launchApp("com.android.settings"))
        assertTrue(root.calls.any { it.contains("monkey -p com.android.settings") })
        assertEquals(ExecutionEngine.ROOT, controller.activeExecutionEngine)
    }
}
