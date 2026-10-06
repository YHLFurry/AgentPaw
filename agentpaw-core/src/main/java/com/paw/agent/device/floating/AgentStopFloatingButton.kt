package com.paw.agent.device.floating

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import com.paw.agent.device.accessibility.AgentAccessibilityService

/**
 * Agent 无障碍操作停止悬浮窗按钮（Maven 包内置，供接入方直接使用）。
 *
 * Agent 执行无障碍操作期间调用 [show] 登记一次「需要停止入口」的请求；
 * 按钮的实际显示/隐藏由宿主 APP 的前后台状态驱动（默认策略 [AgentFloatingVisibilityMode.SHOW_ONLY_IN_BACKGROUND]）：
 *
 * | 宿主 APP 状态 | 按钮行为 |
 * |---|---|
 * | 前台可见（用户正在用宿主 APP） | **不显示**，避免遮挡自家界面 |
 * | 已退到后台（用户已离开，去操作别的 APP） | **显示**悬浮停止胶囊 |
 *
 * 显示时机：宿主 APP 由前台转后台，或 [show] 调用时已处于后台；
 * 隐藏时机：宿主 APP 由后台转前台，或 [hide] 被调用，或用户点击停止按钮。
 *
 * 点击按钮后：
 * 1. 触发 [AgentAccessibilityService.requestUserStop]，中止进行中及后续的无障碍手势/输入；
 * 2. 回调 [show] 注册的 onStopped（接入方可在此取消 Agent 循环等）；
 * 3. 自动隐藏按钮（也可随时 [hide]）。
 *
 * 前后台判定条件与状态机见 [AgentFloatingDecider]、[AgentFloatingVisibilityStateMachine]，
 * 采集逻辑见 [AgentAppForegroundMonitor]。
 *
 * 需要 SYSTEM_ALERT_WINDOW 悬浮窗权限（由宿主申请），无权限时 [show] 返回 false。
 * 线程安全：全部窗口操作切到主线程执行。
 */
object AgentStopFloatingButton {

    private const val STATUS_STOP_ID = 20001

    private var floatingView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null

    /** 最近的显示请求上下文与回调，供状态变化后重建按钮 */
    private var pendingContext: Context? = null
    private var pendingStoppedCallback: (() -> Unit)? = null

    private val stateMachine = AgentFloatingVisibilityStateMachine()

    private val foregroundListener: (Boolean) -> Unit = { foreground ->
        // 宿主 APP 前后台切换时同步窗口：回前台立即收起，退后台立即补上
        runCatching { syncWindowVisibility() }
    }

    /**
     * 显示停止悬浮按钮。
     *
     * 注意：默认策略下，本方法只是「登记请求」。若宿主 APP 当前处于前台，
     * 按钮不会立即出现，而是在宿主 APP 退到后台时才显示；[hide] 可随时撤销该请求。
     *
     * @param onStopped 点击停止按钮后的回调（主线程），可为 null
     * @return true 表示请求已登记（若宿主 APP 在后台则按钮已显示）；
     *         false 表示缺少悬浮窗权限或显示失败
     */
    fun show(context: Context, onStopped: (() -> Unit)? = null): Boolean {
        val app = context.applicationContext ?: return false
        return if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            showInternal(app, onStopped)
        } else {
            // 非主线程：切到主线程显示（结果无法同步返回，按提交成功处理）
            runOnMain { showInternal(app, onStopped) }
            true
        }
    }

    /** 隐藏停止悬浮按钮并撤销显示请求（幂等） */
    fun hide() {
        runOnMain {
            pendingContext = null
            pendingStoppedCallback = null
            stateMachine.requestHide()
            hideInternal()
        }
    }

    /** 按钮当前是否真的挂在窗口上 */
    val isVisible: Boolean
        get() = synchronized(this) { floatingView != null }

    /** 是否存在待显示的请求（可能因宿主 APP 在前台而暂未显示） */
    val isShowRequested: Boolean
        get() = synchronized(this) { stateMachine.isShowRequested }

    /** 宿主 APP 当前是否处于前台可见 */
    val isHostAppInForeground: Boolean
        get() = AgentAppForegroundMonitor.isHostAppInForeground

    /** 覆盖显示策略，默认仅在宿主 APP 退到后台时显示 */
    fun setVisibilityMode(mode: AgentFloatingVisibilityMode) {
        runOnMain {
            stateMachine.updateMode(mode)
            syncWindowVisibility()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showInternal(context: Context, onStopped: (() -> Unit)?): Boolean {
        // 惰性兜底注册：集成方若未在 Application.onCreate 显式 install，这里补一次
        AgentAppForegroundMonitor.install(context)
        AgentAppForegroundMonitor.addListener(foregroundListener)

        pendingContext = context
        pendingStoppedCallback = onStopped
        stateMachine.onHostAppForegroundChanged(AgentAppForegroundMonitor.isHostAppInForeground)
        stateMachine.requestShow()

        // 宿主 APP 在前台时按策略不显示，仅登记请求等待退到后台
        if (!stateMachine.isVisible) {
            hideInternal()
            return true
        }
        return attachWindow(context, onStopped)
    }

    /** 按状态机当前结论挂载或摘除窗口 */
    private fun syncWindowVisibility() {
        val shouldShow = stateMachine.isVisible
        val context = pendingContext
        if (shouldShow && floatingView == null && context != null) {
            attachWindow(context, pendingStoppedCallback)
        } else if (!shouldShow) {
            hideInternal()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachWindow(context: Context, onStopped: (() -> Unit)?): Boolean {
        hideInternal()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            return false
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 120
        }

        val stopButton = Button(context).apply {
            id = STATUS_STOP_ID
            text = "⏹ 停止"
            textSize = 12f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 20).toFloat()
                setColor(Color.parseColor("#CCDC2626"))
                setStroke(dp(context, 1), Color.parseColor("#66FFFFFF"))
            }
            setPadding(dp(context, 14), dp(context, 6), dp(context, 14), dp(context, 6))
            elevation = dp(context, 6).toFloat()

            setOnClickListener {
                // 1. 停止所有无障碍操作
                AgentAccessibilityService.instance?.requestUserStop()
                // 2. 通知接入方（例如取消 Agent 循环）
                runCatching { onStopped?.invoke() }
                // 3. 撤销显示请求并收起按钮（避免下次退到后台时又冒出来）
                pendingContext = null
                pendingStoppedCallback = null
                stateMachine.requestHide()
                hideInternal()
            }
        }

        // 拖拽移动，避免遮挡用户正在操作的界面
        stopButton.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var moved = false

            override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
                val currentParams = params ?: return false
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        initialX = currentParams.x
                        initialY = currentParams.y
                        touchX = event.rawX
                        touchY = event.rawY
                        moved = false
                        return true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true
                        currentParams.x = initialX - dx
                        currentParams.y = initialY + dy
                        runCatching { wm.updateViewLayout(floatingView, currentParams) }
                        return true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        return !moved // 未拖动时交给 onClick 处理
                    }
                }
                return false
            }
        })

        return runCatching {
            wm.addView(stopButton, lp)
            floatingView = stopButton
            params = lp
            windowManager = wm
            true
        }.getOrDefault(false)
    }

    private fun hideInternal() {
        val view = floatingView ?: return
        synchronized(this) {
            floatingView = null
            params = null
        }
        runCatching {
            windowManager?.removeView(view)
        }
        windowManager = null
    }

    private fun runOnMain(block: () -> Unit) {
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
