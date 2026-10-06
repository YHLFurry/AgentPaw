package com.paw.agent.device.floating

/**
 * 停止悬浮窗的显示策略。
 *
 * - [SHOW_ONLY_IN_BACKGROUND]：仅当宿主 APP 退到后台（用户已离开宿主 APP，正在操作其它应用）时显示；
 *   用户回到宿主 APP 内部时自动隐藏。这是默认值，也是最符合直觉的行为——悬浮窗不应该盖在自家界面上。
 * - [ALWAYS]：只要请求显示就一直显示（旧的直白行为，保留给需要常驻的接入方）。
 */
enum class AgentFloatingVisibilityMode {
    SHOW_ONLY_IN_BACKGROUND,
    ALWAYS,
}

/**
 * 前后台判定规则（纯逻辑，无 Android 依赖，可 JVM 单测）。
 *
 * 判定条件按优先级分两档：
 *
 * 1. **宿主进程内存在 Activity 生命周期回调**（绝大多数 APP 成立）
 *    → 以 Activity 的 resume/pause 为唯一事实来源：`resumedCount > 0` 即宿主 APP 在前台可见。
 *    选 resume 而非 start，是因为分屏/画中画下宿主 Activity 可能仍处于 started 但已失去焦点，
 *    此时用户实际在别的应用里，必须显示悬浮窗。
 *
 * 2. **进程内从未出现过任何 Activity**（纯 Service 型接入方）
 *    → 生命周期不可用，退化为无障碍服务上报的前台包名比对：`无障碍前台包名 == 宿主包名` 即宿主在前台。
 *    该信号仅在无障碍服务已启用时存在，而这正是驱动手机所必需的前提，因此对本场景始终可用。
 *
 * 两种信号都不成立时视为「宿主在后台」，即允许显示悬浮窗（安全兜底：宁可多显示，也不要在用户失去对
 * 屏幕的控制时缺少停止入口）。
 */
object AgentForegroundDecider {

    fun isHostAppInForeground(
        hasObservedActivity: Boolean,
        resumedActivityCount: Int,
        accessibilityForegroundPackage: String?,
        hostPackage: String?,
    ): Boolean {
        if (hasObservedActivity) return resumedActivityCount > 0
        if (accessibilityForegroundPackage.isNullOrEmpty() || hostPackage.isNullOrEmpty()) return false
        return accessibilityForegroundPackage == hostPackage
    }
}

/**
 * 宿主 APP Activity 生命周期计数器（纯逻辑，可 JVM 单测）。
 *
 * 由 [AgentAppVisibilityMonitor] 通过 `ActivityLifecycleCallbacks` 驱动。
 * 所有方法线程安全。
 */
class HostAppVisibilityTracker {

    private var startedCount = 0
    private var resumedCount = 0

    /** 本进程是否出现过任何 Activity 回调（用于判定生命周期信号是否可用） */
    @Volatile
    var hasObservedActivity: Boolean = false
        private set

    /**
     * Activity 创建。此处不计入 started 计数（onStart 才是），
     * 仅标记「生命周期信号可用」，让前台判定立刻从无障碍兜底切换到生命周期主信号。
     */
    @Synchronized
    fun onActivityCreated() {
        hasObservedActivity = true
    }

    @Synchronized
    fun onActivityStarted() {
        hasObservedActivity = true
        startedCount++
    }

    @Synchronized
    fun onActivityStopped() {
        if (startedCount > 0) startedCount--
    }

    @Synchronized
    fun onActivityResumed() {
        hasObservedActivity = true
        resumedCount++
    }

    @Synchronized
    fun onActivityPaused() {
        if (resumedCount > 0) resumedCount--
    }

    /** Activity 销毁前一定已经走过 onStop，这里无需重复扣减 */
    @Synchronized
    fun onActivityDestroyed() {
        hasObservedActivity = true
    }

    @Synchronized
    fun startedActivityCount(): Int = startedCount

    @Synchronized
    fun resumedActivityCount(): Int = resumedCount

    /** 宿主 APP 是否处于前台可见（仅生命周期信号，不含无障碍包名兜底） */
    @Synchronized
    fun isHostInForegroundByLifecycle(): Boolean = resumedCount > 0

    @Synchronized
    fun reset() {
        startedCount = 0
        resumedCount = 0
        hasObservedActivity = false
    }
}

/**
 * 悬浮窗显示状态机（纯逻辑，可 JVM 单测）。
 *
 * 把「是否请求显示」「宿主是否在前台」「显示策略」三者解耦，
 * 使 [AgentStopFloatingButton] 只需在任一输入变化时调用 [evaluate] 同步真实窗口即可。
 */
class AgentFloatingVisibilityStateMachine(
    mode: AgentFloatingVisibilityMode = AgentFloatingVisibilityMode.SHOW_ONLY_IN_BACKGROUND,
) {

    var mode: AgentFloatingVisibilityMode = mode
        private set

    /** 接入方是否调用过 show() 且尚未 hide() */
    var isShowRequested: Boolean = false
        private set

    /** 宿主 APP 是否在前台可见 */
    var isHostAppInForeground: Boolean = false
        private set

    /** 按当前三个输入计算出的「按钮是否应当存在」 */
    val isVisible: Boolean
        get() = decide(mode, isShowRequested, isHostAppInForeground)

    fun requestShow(): Boolean {
        isShowRequested = true
        return isVisible
    }

    fun requestHide(): Boolean {
        isShowRequested = false
        return isVisible
    }

    fun updateMode(newMode: AgentFloatingVisibilityMode): Boolean {
        mode = newMode
        return isVisible
    }

    fun onHostAppForegroundChanged(foreground: Boolean): Boolean {
        isHostAppInForeground = foreground
        return isVisible
    }

    companion object {
        fun decide(
            mode: AgentFloatingVisibilityMode,
            isShowRequested: Boolean,
            isHostAppInForeground: Boolean,
        ): Boolean = when (mode) {
            AgentFloatingVisibilityMode.ALWAYS -> isShowRequested
            AgentFloatingVisibilityMode.SHOW_ONLY_IN_BACKGROUND -> isShowRequested && !isHostAppInForeground
        }
    }
}