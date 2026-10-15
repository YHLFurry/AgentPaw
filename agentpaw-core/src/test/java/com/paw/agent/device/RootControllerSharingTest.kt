package com.paw.agent.device

import com.paw.agent.device.root.RootController
import com.paw.agent.device.shizuku.ShizukuController
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-202：Root 授权状态刷新与实例共享。
 *
 * 背景缺陷：
 * 1. RootController 的探测缓存不会自动过期，首次探测（可能在用户授权前）写入
 *    false 后，整个进程生命周期内执行通道都读不到最新授权；
 * 2. HybridPhoneController 与 DevicePermissionManager 各持一个 RootController
 *    实例，设置页「测试 ROOT」刷新的是另一个对象，与真实执行通道状态背离。
 *
 * 本测试在修复前失败（红），修复后通过（绿）。
 */
class RootControllerSharingTest {

    /**
     * 探测结果可控的替身：复用基类真实的缓存逻辑（isAvailable 的缓存 +
     * refreshAvailability 的刷新路径），仅替换底层 su 探测。
     */
    private class FakeRootController : RootController() {
        @Volatile
        var probeResult: Boolean = false

        var probeCount: Int = 0
            private set

        val executedCommands = mutableListOf<String>()

        override fun checkSuDirectly(timeoutMs: Long): Boolean {
            probeCount++
            return probeResult
        }

        override suspend fun executeCommand(cmd: String, timeoutMs: Long): String {
            executedCommands.add(cmd)
            return "OK"
        }
    }

    private class NoopShizukuController : ShizukuController() {
        override val isAvailable: Boolean get() = false
    }

    @After
    fun tearDown() {
        // rootController 是进程级单例状态，必须复位，避免污染同进程其他测试
        DevicePermissionManager.resetRootControllerForTesting()
    }

    @Test
    fun `default hybrid controller shares the permission manager root instance`() {
        val fake = FakeRootController()
        DevicePermissionManager.installRootControllerForTesting(fake)

        // AppContainer 的用法：不显式传 rootController
        val controller = HybridPhoneController(
            context = null,
            shizukuController = NoopShizukuController(),
        )

        assertSame(
            "执行通道必须与权限管理器共享同一 Root 实例，否则刷新无法传播",
            fake,
            controller.rootController,
        )
    }

    @Test
    fun `shared instance reflects authorization changes after refresh`() = runTest {
        val fake = FakeRootController().apply { probeResult = true }
        DevicePermissionManager.installRootControllerForTesting(fake)

        val controller = HybridPhoneController(
            context = null,
            shizukuController = NoopShizukuController(),
        )

        assertTrue(controller.isRootAvailable)
        assertEquals(ExecutionEngine.ROOT, controller.activeExecutionEngine)

        // 缓存语义（设计如此）：撤销后不刷新仍读到上一次的探测结果
        fake.probeResult = false
        assertTrue("缓存未刷新前保持上一次结果", controller.isRootAvailable)

        // 刷新后立即可见撤销
        DevicePermissionManager.refreshRootAvailability()
        assertFalse("刷新后控制器必须看到撤销", controller.isRootAvailable)

        // 重新授权并刷新后恢复
        fake.probeResult = true
        DevicePermissionManager.refreshRootAvailability()
        assertTrue(controller.isRootAvailable)
        assertEquals(ExecutionEngine.ROOT, controller.activeExecutionEngine)
    }

    @Test
    fun `refreshRootAvailability invalidates the stale cache`() = runTest {
        val fake = FakeRootController().apply { probeResult = false }
        DevicePermissionManager.installRootControllerForTesting(fake)

        val controller = HybridPhoneController(
            context = null,
            shizukuController = NoopShizukuController(),
        )

        // 首次读取把陈旧的 false 写入缓存
        assertFalse(controller.isRootAvailable)
        val probesBefore = fake.probeCount

        // 用户在 Magisk 完成授权后，刷新探测
        fake.probeResult = true
        val refreshed = DevicePermissionManager.refreshRootAvailability()

        assertTrue("刷新应返回最新探测结果", refreshed)
        assertTrue("刷新后控制器必须看到最新授权状态（修复前仍是缓存 false）", controller.isRootAvailable)
        assertEquals(ExecutionEngine.ROOT, controller.activeExecutionEngine)
        assertTrue("刷新必须触发一次新的底层探测", fake.probeCount > probesBefore)
    }

    @Test
    fun `refresh propagates through the shared instance for execution fallback`() = runTest {
        val fake = FakeRootController().apply { probeResult = false }
        DevicePermissionManager.installRootControllerForTesting(fake)

        val controller = HybridPhoneController(
            context = null,
            shizukuController = NoopShizukuController(),
        )

        // 未授权 Root 时执行引擎不应是 ROOT
        assertFalse(controller.isRootAvailable)

        fake.probeResult = true
        DevicePermissionManager.refreshRootAvailability()

        assertEquals(ExecutionEngine.ROOT, controller.activeExecutionEngine)
        // 授权后 launchApp 应走 Root 通道
        assertTrue(controller.launchApp("com.android.settings"))
        assertTrue(
            "授权恢复后应通过 Root 通道启动应用",
            fake.executedCommands.any { it.contains("monkey -p com.android.settings") },
        )
    }
}
