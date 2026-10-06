package com.paw.agent.device.floating

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 宿主 APP（含集成本 Maven 包的 APP）前台/后台状态监听器。
 *
 * 判定条件见 [AgentForegroundDecider]，本类只负责采集两类信号并对外广播：
 * 1. 宿主进程的 Activity 生命周期（onResume/onPause），主信号；
 * 2. 无障碍服务上报的前台包名，宿主为纯 Service 型接入方时的兜底信号。
 *
 * 采集来源：
 * - Activity 生命周期通过 [Application.registerActivityLifecycleCallbacks] 注册，
 *   必须在进程启动早期注册（推荐在 `Application.onCreate` 调用 [install]，注册过晚会漏掉首个 Activity 的 resume）。
 * - 无障碍包名由 [com.paw.agent.device.accessibility.AgentAccessibilityService] 在收到事件时调用 [notifyAccessibilityForegroundPackage] 更新。
 *
 * 线程安全：所有公开方法可在任意线程调用，回调统一在主线程分发。
 */
object AgentAppForegroundMonitor {

    private val tracker = HostAppVisibilityTracker()
    private val listeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

    private var installed = false
    private var installedApplication: Application? = null

    /** 无障碍服务最近上报的前台包名（未启用服务时为 null） */
    @Volatile
    private var accessibilityForegroundPackage: String? = null

    /** 宿主包名，用于与无障碍前台包名比对 */
    @Volatile
    private var hostPackage: String? = null

    @Volatile
    private var lastKnownForeground: Boolean = false

    /** 宿主 APP 当前是否处于前台可见 */
    val isHostAppInForeground: Boolean
        get() = lastKnownForeground

    /**
     * 注册 Activity 生命周期监听。可重复调用，仅首次生效。
     *
     * 建议在宿主 `Application.onCreate` 中调用；若集成方遗漏该调用，
     * [AgentStopFloatingButton.show] 会在首次使用时惰性兜底注册，但可能漏掉首个 Activity 的 resume，
     * 导致首次进入 APP 时误判为「后台」而短暂显示按钮。
     */
    @JvmStatic
    fun install(context: Context) {
        val app = context.applicationContext as? Application ?: return
        synchronized(this) {
            if (installed) return
            installed = true
            installedApplication = app
            hostPackage = app.packageName
            app.registerActivityLifecycleCallbacks(Callbacks)
        }
        recompute()
    }

    /**
     * 由无障碍服务在收到窗口变化事件时上报前台包名，用于无 Activity 场景下的兜底判定。
     * 非主线程安全无问题，内部同步。
     */
    @JvmStatic
    fun notifyAccessibilityForegroundPackage(packageName: String?) {
        accessibilityForegroundPackage = packageName
        recompute()
    }

    /**
     * 添加前后台状态监听。宿主转入前台回调 true，退到后台回调 false。
     * 注册时会立即以当前状态回调一次（主线程），便于调用方对齐初始状态。
     */
    @JvmStatic
    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
        dispatch(listener, lastKnownForeground)
    }

    @JvmStatic
    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    /** 解除注册（一般无需调用，仅供测试与极端场景） */
    @JvmStatic
    fun uninstall() {
        val app = synchronized(this) {
            if (!installed) return
            installed = false
            val current = installedApplication
            installedApplication = null
            current
        }
        app?.unregisterActivityLifecycleCallbacks(Callbacks)
        tracker.reset()
        synchronized(this) { lastKnownForeground = false }
        listeners.forEach { dispatch(it, false) }
    }

    private fun recompute() {
        val foreground = AgentForegroundDecider.isHostAppInForeground(
            hasObservedActivity = tracker.hasObservedActivity,
            resumedActivityCount = tracker.resumedActivityCount(),
            accessibilityForegroundPackage = accessibilityForegroundPackage,
            hostPackage = hostPackage,
        )
        val changed = synchronized(this) {
            if (lastKnownForeground == foreground) {
                false
            } else {
                lastKnownForeground = foreground
                true
            }
        }
        if (changed) {
            listeners.forEach { dispatch(it, foreground) }
        }
    }

    private fun dispatch(listener: (Boolean) -> Unit, foreground: Boolean) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            runCatching { listener(foreground) }
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { listener(foreground) }
            }
        }
    }

    private object Callbacks : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            tracker.onActivityCreated()
            recompute()
        }

        override fun onActivityStarted(activity: Activity) {
            tracker.onActivityStarted()
            recompute()
        }

        override fun onActivityResumed(activity: Activity) {
            tracker.onActivityResumed()
            recompute()
        }

        override fun onActivityPaused(activity: Activity) {
            tracker.onActivityPaused()
            recompute()
        }

        override fun onActivityStopped(activity: Activity) {
            tracker.onActivityStopped()
            recompute()
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) {
            tracker.onActivityDestroyed()
            recompute()
        }
    }
}