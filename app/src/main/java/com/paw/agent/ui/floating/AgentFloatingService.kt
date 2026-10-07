package com.paw.agent.ui.floating

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.paw.agent.MainActivity
import com.paw.agent.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Agent 任务执行悬浮胶囊服务。
 * 在第三方 App 操作时显示当前动作，支持用户随时拖拽和一键停止。
 *
 * 显示时机遵循与 Maven 包一致的前后台策略：宿主 AgentPaw 在前台时不显示悬浮胶囊，
 * 退到后台（用户已离开去操作别的 APP）时才显示。前台服务通知不受此限制，
 * 以满足 Android 对前台服务必须有可见通知的要求。
 */
class AgentFloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var stateCollectJob: Job? = null

    /** 宿主 APP 是否处于前台；前台时不挂载悬浮胶囊 */
    private var hostInForeground = true

    private val foregroundListener: (Boolean) -> Unit = { foreground ->
        hostInForeground = foreground
        syncFloatingVisibility()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        com.paw.agent.device.floating.AgentAppForegroundMonitor.install(this)
        com.paw.agent.device.floating.AgentAppForegroundMonitor.addListener(foregroundListener)
        hostInForeground = com.paw.agent.device.floating.AgentAppForegroundMonitor.isHostAppInForeground
        startForegroundNotification()
        setupFloatingView()
        observeAgentState()
    }

    private val channelId = "agent_floating_service"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_TASK) {
            stopAccessibilityOperations()
            AgentExecutionController.requestStop()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /**
     * 联动 core 层停止无障碍操作：中止进行中/后续手势与输入，
     * 确保用户点"停止"后 Agent 不会继续操作手机。
     */
    private fun stopAccessibilityOperations() {
        com.paw.agent.device.accessibility.AgentAccessibilityService.instance?.requestUserStop()
        com.paw.agent.device.floating.AgentStopFloatingButton.hide()
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "AgentPaw 运行状态",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示 AgentPaw 当前在手机上的执行状态"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }

        val notification = buildNotification("正在准备操作手机...")
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(contentText: String): Notification {
        val appPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AgentFloatingService::class.java).apply {
                action = ACTION_STOP_TASK
            },
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("AgentPaw 手机智能体运行中")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(appPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "⏹ 停止任务", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingView() {
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 200
        }
        params = lp

        // 创建胶囊 UI (深色毛玻璃药丸风格)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12), dpToPx(6), dpToPx(8), dpToPx(6))
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(24).toFloat()
                setColor(Color.parseColor("#E6202124")) // 半透明深色质感
                setStroke(dpToPx(1), Color.parseColor("#4DFFFFFF"))
            }
            elevation = dpToPx(6).toFloat()
        }

        // 展开/收起按钮
        val toggleButton = TextView(this).apply {
            text = "收起 ◀"
            textSize = 10f
            setTextColor(Color.parseColor("#E0E0E0"))
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(12).toFloat()
                setColor(Color.parseColor("#33FFFFFF"))
            }
            setPadding(dpToPx(6), dpToPx(3), dpToPx(6), dpToPx(3))
        }
        container.addView(toggleButton)

        // 状态文字
        val statusText = TextView(this).apply {
            id = STATUS_TEXT_ID
            text = "AgentPaw: 准备中..."
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dpToPx(8), 0, dpToPx(10), 0)
            maxLines = 1
        }
        container.addView(statusText)

        // 停止/恢复按钮
        val actionButton = Button(this).apply {
            id = View.generateViewId()
            text = "⏹ 停止"
            textSize = 11f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(14).toFloat()
                setColor(Color.parseColor("#DC2626")) // 显眼红色
            }
            setPadding(dpToPx(8), dpToPx(2), dpToPx(8), dpToPx(2))
            minHeight = dpToPx(28)
            minimumHeight = dpToPx(28)
            setOnClickListener {
                if (AgentExecutionController.state.value.isPaused) {
                    AgentExecutionController.requestResume()
                } else {
                    stopAccessibilityOperations()
                    AgentExecutionController.requestStop()
                }
            }
        }
        container.addView(actionButton)

        fun applyExpandedState() {
            if (isExpanded) {
                statusText.visibility = View.VISIBLE
                actionButton.visibility = View.VISIBLE
                toggleButton.text = "收起 ◀"
                container.setPadding(dpToPx(12), dpToPx(6), dpToPx(8), dpToPx(6))
            } else {
                statusText.visibility = View.GONE
                actionButton.visibility = View.GONE
                toggleButton.text = "🐾 展开 ▶"
                container.setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
            }
            params?.let { runCatching { windowManager.updateViewLayout(container, it) } }
        }

        toggleButton.setOnClickListener {
            isExpanded = !isExpanded
            applyExpandedState()
        }

        // 手势拖动与点击唤醒主界面
        container.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isMoved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val currentParams = params ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = currentParams.x
                        initialY = currentParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isMoved = true
                        }
                        currentParams.x = initialX + dx
                        currentParams.y = initialY + dy
                        val target = floatingView
                        if (target != null && target.isAttachedToWindow) {
                            runCatching { windowManager.updateViewLayout(target, currentParams) }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isMoved) {
                            if (!isExpanded) {
                                isExpanded = true
                                applyExpandedState()
                            } else {
                                // 点击非按钮区域，唤起 MainActivity
                                val appIntent = Intent(this@AgentFloatingService, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                }
                                startActivity(appIntent)
                            }
                        }
                        return true
                    }
                }
                return false
            }
        })

        floatingView = container
        // 挂载与否由宿主前后台状态决定，见 syncFloatingVisibility()
        syncFloatingVisibility()
    }

    /**
     * 按宿主 APP 前后台状态挂载/摘除悬浮胶囊。
     * 前台可见时直接不挂载，用户看不到也点不到，避免遮挡本体 APP 界面。
     */
    private fun syncFloatingVisibility() {
        val view = floatingView ?: return
        if (hostInForeground) {
            if (view.isAttachedToWindow) {
                runCatching { windowManager.removeView(view) }
            }
            return
        }
        if (!view.isAttachedToWindow &&
            com.paw.agent.device.DevicePermissionManager.canDrawOverlays(this)
        ) {
            val lp = params ?: return
            runCatching { windowManager.addView(view, lp) }
        }
    }

    private var isExpanded = true

    private fun observeAgentState() {
        stateCollectJob = serviceScope.launch {
            AgentExecutionController.state.collect { state ->
                // 计数规则：每 5 步记为 1 格
                val currentGrid = if (state.currentStep > 0) (state.currentStep + 4) / 5 else 0
                val stepPrefix = if (state.isRunning) {
                    if (state.isUnlimited) {
                        "[第${state.currentStep}步·第${currentGrid}格/无上限] "
                    } else {
                        val totalGrids = if (state.maxSteps % 5 == 0) {
                            "${state.maxSteps / 5}"
                        } else {
                            String.format(java.util.Locale.US, "%.1f", state.maxSteps / 5.0)
                        }
                        "[第${state.currentStep}步·第${currentGrid}格/共${state.maxSteps}步·${totalGrids}格] "
                    }
                } else ""
                val briefAction = state.currentAction.take(24)

                val tv = floatingView?.findViewById<TextView>(STATUS_TEXT_ID)
                tv?.text = "$stepPrefix$briefAction"

                // 同步更新通知栏与浮窗状态
                if (state.isRunning) {
                    updateNotification("$stepPrefix$briefAction")
                } else if (state.isPaused) {
                    updateNotification("⏸ 任务已在断点处暂停，点击可恢复")
                    val tv = floatingView?.findViewById<TextView>(STATUS_TEXT_ID)
                    tv?.text = "⏸ [第${state.currentStep}步断点暂停] 点击继续"
                } else {
                    updateNotification(state.currentAction.ifBlank { "任务已结束" })
                    // 任务彻底结束（非断点暂停），延时 1.5 秒自动收起销毁浮窗
                    delay(1500)
                    stopSelf()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stateCollectJob?.cancel()
        com.paw.agent.device.floating.AgentAppForegroundMonitor.removeListener(foregroundListener)
        floatingView?.let {
            runCatching { windowManager.removeView(it) }
        }
        floatingView = null
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val ACTION_STOP_TASK = "com.paw.agent.action.STOP_TASK"
        private const val NOTIFICATION_ID = 2026
        private const val STATUS_TEXT_ID = 10001

        fun start(context: Context) {
            val intent = Intent(context, AgentFloatingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AgentFloatingService::class.java)
            context.stopService(intent)
        }
    }
}
