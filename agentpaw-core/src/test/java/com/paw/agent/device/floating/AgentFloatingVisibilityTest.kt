package com.paw.agent.device.floating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 停止悬浮窗前后台判定与显示策略的纯逻辑测试（无需设备）。
 */
class AgentFloatingVisibilityTest {

    // ---------- 判定条件 ----------

    @Test
    fun `lifecycle signal wins when host has activities`() {
        // 宿主有 Activity 时，只看 resume 计数，忽略无障碍包名
        assertTrue(
            AgentForegroundDecider.isHostAppInForeground(
                hasObservedActivity = true,
                resumedActivityCount = 1,
                accessibilityForegroundPackage = "com.tencent.mm",
                hostPackage = "com.paw.agent",
            )
        )
        assertFalse(
            AgentForegroundDecider.isHostAppInForeground(
                hasObservedActivity = true,
                resumedActivityCount = 0,
                accessibilityForegroundPackage = "com.paw.agent",
                hostPackage = "com.paw.agent",
            )
        )
    }

    @Test
    fun `split screen keeps button visible when host loses focus but stays started`() {
        // 分屏：宿主 Activity 仍 started 但已 pause -> 用户实际在别的应用 -> 应显示悬浮窗
        assertFalse(
            AgentForegroundDecider.isHostAppInForeground(
                hasObservedActivity = true,
                resumedActivityCount = 0,
                accessibilityForegroundPackage = "com.tencent.mm",
                hostPackage = "com.paw.agent",
            )
        )
    }

    @Test
    fun `accessibility package is the fallback when host has no activity`() {
        // 纯 Service 型接入方：只能靠无障碍上报的前台包名
        assertTrue(
            AgentForegroundDecider.isHostAppInForeground(
                hasObservedActivity = false,
                resumedActivityCount = 0,
                accessibilityForegroundPackage = "com.paw.agent",
                hostPackage = "com.paw.agent",
            )
        )
        assertFalse(
            AgentForegroundDecider.isHostAppInForeground(
                hasObservedActivity = false,
                resumedActivityCount = 0,
                accessibilityForegroundPackage = "com.tencent.mm",
                hostPackage = "com.paw.agent",
            )
        )
    }

    @Test
    fun `unknown state falls back to background so stop entry is never lost`() {
        // 两个信号都不可用时视为后台：宁可多显示，也不能让用户失去停止入口
        assertFalse(
            AgentForegroundDecider.isHostAppInForeground(
                hasObservedActivity = false,
                resumedActivityCount = 0,
                accessibilityForegroundPackage = null,
                hostPackage = "com.paw.agent",
            )
        )
        assertFalse(
            AgentForegroundDecider.isHostAppInForeground(
                hasObservedActivity = false,
                resumedActivityCount = 0,
                accessibilityForegroundPackage = "",
                hostPackage = null,
            )
        )
    }

    // ---------- 生命周期计数器 ----------

    @Test
    fun `tracker counts resume and pause`() {
        val tracker = HostAppVisibilityTracker()
        assertFalse(tracker.isHostInForegroundByLifecycle())

        tracker.onActivityStarted()
        tracker.onActivityResumed()
        assertEquals(1, tracker.resumedActivityCount())
        assertTrue(tracker.isHostInForegroundByLifecycle())

        tracker.onActivityPaused()
        assertFalse(tracker.isHostInForegroundByLifecycle())

        tracker.onActivityStopped()
        assertEquals(0, tracker.startedActivityCount())
    }

    @Test
    fun `tracker handles multiple activities and never goes negative`() {
        val tracker = HostAppVisibilityTracker()
        tracker.onActivityStarted()
        tracker.onActivityStarted()
        tracker.onActivityResumed()
        tracker.onActivityResumed()
        assertEquals(2, tracker.resumedActivityCount())

        tracker.onActivityPaused()
        assertTrue("一个 Activity 仍在前台即算前台", tracker.isHostInForegroundByLifecycle())
        tracker.onActivityPaused()
        assertFalse(tracker.isHostInForegroundByLifecycle())

        // 多余的回调不应把计数压成负数
        tracker.onActivityPaused()
        tracker.onActivityStopped()
        tracker.onActivityStopped()
        assertEquals(0, tracker.resumedActivityCount())
        assertEquals(0, tracker.startedActivityCount())

        // 再多的多余回调也不应把计数压成负数
        tracker.onActivityPaused()
        tracker.onActivityStopped()
        assertEquals(0, tracker.resumedActivityCount())
        assertEquals(0, tracker.startedActivityCount())
    }

    @Test
    fun `tracker marks activity observed on create only`() {
        val tracker = HostAppVisibilityTracker()
        assertFalse(tracker.hasObservedActivity)

        tracker.onActivityCreated()
        assertTrue(tracker.hasObservedActivity)
        // 仅 created 不应影响前台判定
        assertFalse(tracker.isHostInForegroundByLifecycle())
        assertEquals(0, tracker.startedActivityCount())
    }

    // ---------- 显示状态机 ----------

    @Test
    fun `default policy hides button while host app is in foreground`() {
        val sm = AgentFloatingVisibilityStateMachine()
        // 状态机初始按"后台"处理（信号未知时的安全兜底），这里显式切到前台
        sm.onHostAppForegroundChanged(true)

        // 用户在宿主 APP 内点击开始 -> 只登记请求，不显示
        assertFalse(sm.requestShow())
        assertTrue(sm.isShowRequested)
        assertFalse(sm.isVisible)

        // 用户离开宿主 APP -> 立即显示
        assertTrue(sm.onHostAppForegroundChanged(false))
        assertTrue(sm.isVisible)

        // 用户回到宿主 APP -> 立即隐藏
        assertFalse(sm.onHostAppForegroundChanged(true))
        assertFalse(sm.isVisible)
    }

    @Test
    fun `show while already in background attaches immediately`() {
        val sm = AgentFloatingVisibilityStateMachine()
        sm.onHostAppForegroundChanged(false)
        assertTrue(sm.requestShow())
        assertTrue(sm.isVisible)
    }

    @Test
    fun `hide cancels the pending request`() {
        val sm = AgentFloatingVisibilityStateMachine()
        sm.onHostAppForegroundChanged(false)
        sm.requestShow()

        assertFalse(sm.requestHide())
        assertFalse(sm.isShowRequested)
        // 已撤销请求，退到后台也不该再冒出来
        sm.onHostAppForegroundChanged(true)
        assertFalse(sm.onHostAppForegroundChanged(false))
        assertFalse(sm.isVisible)
    }

    @Test
    fun `always mode ignores foreground state`() {
        val sm = AgentFloatingVisibilityStateMachine(AgentFloatingVisibilityMode.ALWAYS)
        sm.onHostAppForegroundChanged(true)
        assertTrue(sm.requestShow())
        assertTrue(sm.isVisible)
        sm.onHostAppForegroundChanged(false)
        assertTrue(sm.isVisible)
    }

    @Test
    fun `switching mode re-evaluates current state`() {
        val sm = AgentFloatingVisibilityStateMachine()
        sm.onHostAppForegroundChanged(true)
        sm.requestShow()
        assertFalse(sm.isVisible)

        assertTrue(sm.updateMode(AgentFloatingVisibilityMode.ALWAYS))
        assertTrue(sm.isVisible)

        assertFalse(sm.updateMode(AgentFloatingVisibilityMode.SHOW_ONLY_IN_BACKGROUND))
        assertFalse(sm.isVisible)
    }

    @Test
    fun `full user journey mirrors the requirement`() {
        val sm = AgentFloatingVisibilityStateMachine()

        // 1. Agent 在本体 APP 内启动任务
        sm.onHostAppForegroundChanged(true)
        sm.requestShow()
        assertFalse("本体 APP 前台不显示停止悬浮窗", sm.isVisible)

        // 2. Agent 驱动手机跳到第三方 APP，用户离开本体
        sm.onHostAppForegroundChanged(false)
        assertTrue("离开本体 APP 后显示停止悬浮窗", sm.isVisible)

        // 3. 用户切回本体 APP 查看进度
        sm.onHostAppForegroundChanged(true)
        assertFalse("回到本体 APP 立即隐藏", sm.isVisible)

        // 4. 再次离开，仍应能显示
        sm.onHostAppForegroundChanged(false)
        assertTrue(sm.isVisible)
    }
}