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
 * Agent 执行无障碍操作期间调用 [show] 即可在屏幕上显示一枚可拖拽的红色"停止"胶囊；
 * 点击后：
 * 1. 触发 [AgentAccessibilityService.requestUserStop]，中止进行中及后续的无障碍手势/输入；
 * 2. 回调 [show] 注册的 onStopped（接入方可在此取消 Agent 循环等）；
 * 3. 自动隐藏按钮（也可随时 [hide]）。
 *
 * 需要 SYSTEM_ALERT_WINDOW 悬浮窗权限（由宿主申请），无权限时 [show] 返回 false。
 * 线程安全：全部操作切到主线程执行。
 */
object AgentStopFloatingButton {

    private const val STATUS_STOP_ID = 20001

    private var floatingView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null

    /**
     * 显示停止悬浮按钮。
     *
     * @param onStopped 点击停止按钮后的回调（主线程），可为 null
     * @return true 表示按钮已显示（或已在主线程外提交显示请求）；
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

    /** 隐藏停止悬浮按钮（幂等） */
    fun hide() {
        runOnMain {
            hideInternal()
        }
    }

    /** 按钮当前是否正在显示 */
    val isVisible: Boolean
        get() = synchronized(this) { floatingView != null }

    @SuppressLint("ClickableViewAccessibility")
    private fun showInternal(context: Context, onStopped: (() -> Unit)?): Boolean {
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
                // 3. 收起按钮
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
