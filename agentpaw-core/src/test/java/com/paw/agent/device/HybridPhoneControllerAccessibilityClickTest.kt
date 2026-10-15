package com.paw.agent.device

import com.paw.agent.device.root.RootController
import com.paw.agent.device.shizuku.ShizukuController
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无障碍点击接缝测试：验证 Root > Shizuku > 无障碍 优先级链上，
 * 无障碍分支使用「节点锚定点击优先」的接线（accessibilityTapAt /
 * accessibilityLongPressAt 接缝替身不触碰真实服务实例，JVM 单测不可用）。
 *
 * 背景（ISSUE-207）：纯无障碍模式下原先只做手势注入盲打，遇到遮挡、
 * 小目标、滚动位移会失手；现在优先走节点 ACTION_CLICK、手势兜底。
 */
class HybridPhoneControllerAccessibilityClickTest {

    private class FakeRootController(
        var available: Boolean = false,
        var tapResult: Boolean = true,
    ) : RootController() {
        override val isAvailable: Boolean get() = available
        val calls = mutableListOf<String>()

        override suspend fun tap(x: Int, y: Int): Boolean {
            calls.add("tap($x,$y)")
            return tapResult
        }

        override suspend fun executeCommand(cmd: String, timeoutMs: Long): String {
            calls.add(cmd)
            return "OK"
        }

        override suspend fun getForegroundInfo(): Pair<String, String> = Pair("", "")
    }

    private class FakeShizukuController(
        var available: Boolean = false,
        var tapResult: Boolean = true,
    ) : ShizukuController() {
        override val isAvailable: Boolean get() = available
        val calls = mutableListOf<String>()

        override suspend fun tap(x: Int, y: Int): Boolean {
            calls.add("tap($x,$y)")
            return tapResult
        }

        override suspend fun executeCommand(cmd: String, timeoutMs: Long): String {
            calls.add(cmd)
            return "OK"
        }

        override suspend fun getForegroundInfo(): Pair<String, String> = Pair("", "")
    }

    /** 记录无障碍点击调用的控制器替身 */
    private class RecordingController(
        root: RootController,
        shizuku: ShizukuController,
    ) : HybridPhoneController(
        context = null,
        rootController = root,
        shizukuController = shizuku,
    ) {
        val tapCalls = mutableListOf<Pair<Float, Float>>()
        val longPressCalls = mutableListOf<Triple<Float, Float, Long>>()
        var tapResult: Boolean = true
        var longPressResult: Boolean = true

        override suspend fun accessibilityTapAt(x: Float, y: Float): Boolean {
            tapCalls.add(x to y)
            return tapResult
        }

        override suspend fun accessibilityLongPressAt(x: Float, y: Float, durationMs: Long): Boolean {
            longPressCalls.add(Triple(x, y, durationMs))
            return longPressResult
        }
    }

    @Test
    fun `when root and shizuku are unavailable, tap routes to accessibility with physical coordinates`() = runTest {
        val root = FakeRootController(available = false)
        val shizuku = FakeShizukuController(available = false)
        val controller = RecordingController(root, shizuku)

        val ok = controller.tap(500, 500)

        assertTrue(ok)
        // context 为 null 时回退 1080x2400 默认尺寸：500/1000 → 540/1200
        assertEquals(listOf(540f to 1200f), controller.tapCalls)
        assertTrue("Root 不可用不应被调用", root.calls.isEmpty())
        assertTrue("Shizuku 不可用不应被调用", shizuku.calls.isEmpty())
    }

    @Test
    fun `when root succeeds, accessibility is never called`() = runTest {
        val root = FakeRootController(available = true, tapResult = true)
        val shizuku = FakeShizukuController(available = true)
        val controller = RecordingController(root, shizuku)

        assertTrue(controller.tap(500, 500))

        assertTrue(controller.tapCalls.isEmpty())
        assertTrue(root.calls.any { it.startsWith("tap") })
        assertTrue(shizuku.calls.isEmpty())
    }

    @Test
    fun `when root and shizuku both fail, accessibility is the last resort`() = runTest {
        val root = FakeRootController(available = true, tapResult = false)
        val shizuku = FakeShizukuController(available = true, tapResult = false)
        val controller = RecordingController(root, shizuku)

        assertTrue(controller.tap(500, 500))

        assertTrue("Root 应先尝试", root.calls.any { it.startsWith("tap") })
        assertTrue("Root 失败后应回退 Shizuku", shizuku.calls.any { it.startsWith("tap") })
        assertEquals(listOf(540f to 1200f), controller.tapCalls)
    }

    @Test
    fun `explicit ACCESSIBILITY mode ignores available root and shizuku`() = runTest {
        val root = FakeRootController(available = true)
        val shizuku = FakeShizukuController(available = true)
        val controller = RecordingController(root, shizuku)
        controller.controlMode = PhoneControlMode.ACCESSIBILITY

        assertTrue(controller.tap(300, 300))

        assertTrue("ACCESSIBILITY 纯模式不应走 Root", root.calls.isEmpty())
        assertTrue("ACCESSIBILITY 纯模式不应走 Shizuku", shizuku.calls.isEmpty())
        // 300/1000 * 1080 = 324, 300/1000 * 2400 = 720
        assertEquals(listOf(324f to 720f), controller.tapCalls)
    }

    @Test
    fun `tapAtPixel routes exact pixel coordinates to accessibility`() = runTest {
        val controller = RecordingController(FakeRootController(), FakeShizukuController())

        assertTrue(controller.tapAtPixel(123.4f, 567.8f))

        assertEquals(listOf(123.4f to 567.8f), controller.tapCalls)
    }

    @Test
    fun `tap returns false when accessibility click fails`() = runTest {
        val controller = RecordingController(FakeRootController(), FakeShizukuController())
        controller.tapResult = false

        assertFalse(controller.tap(500, 500))
    }

    @Test
    fun `longPress routes to accessibility with duration and physical coordinates`() = runTest {
        val controller = RecordingController(FakeRootController(), FakeShizukuController())

        assertTrue(controller.longPress(500, 500, durationMs = 1500L))

        assertEquals(listOf(Triple(540f, 1200f, 1500L)), controller.longPressCalls)
    }

    @Test
    fun `longPress returns false when accessibility long press fails`() = runTest {
        val controller = RecordingController(FakeRootController(), FakeShizukuController())
        controller.longPressResult = false

        assertFalse(controller.longPress(500, 500, durationMs = 1000L))
    }

    @Test
    fun `doubleTap stays gesture-based through the service seam`() = runTest {
        // 双击刻意不做节点锚定：OnDoubleTapListener 由触摸手势驱动，
        // 连点两次 ACTION_CLICK 不会触发。此处仅验证链路仍然可达无障碍分支。
        val controller = RecordingController(FakeRootController(), FakeShizukuController())

        // 无 Root/Shizuku 时 doubleTap 走 service.doubleClickAt（真实实例为 null → false），
        // 与修复前行为一致：不回归、不改语义。
        assertFalse(controller.doubleTap(500, 500))
        assertTrue("双击不应经过节点点击接缝", controller.tapCalls.isEmpty())
        assertTrue("双击不应经过长按接缝", controller.longPressCalls.isEmpty())
    }
}
