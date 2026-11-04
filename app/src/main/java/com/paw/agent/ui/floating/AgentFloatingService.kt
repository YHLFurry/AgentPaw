package com.paw.agent.ui.floating

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.paw.agent.MainActivity
import com.paw.agent.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.cancel

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
            .setSmallIcon(R.drawable.ic_paw)
            .setContentIntent(appPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "⏹ 停止任务", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    @SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
    private fun setupFloatingView() {
        params = WindowManager.LayoutParams(
            dpToPx(296),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = dpToPx(16)
            y = dpToPx(96)
        }
        val panel = AgentFloatingPanel(
            context = this,
            onAction = {
                val state = AgentExecutionController.state.value
                when {
                    state.isRunning -> {
                        stopAccessibilityOperations()
                        AgentExecutionController.requestStop()
                    }
                    state.isPaused -> AgentExecutionController.requestResume()
                    else -> openChat()
                }
            },
            onOpenChat = ::openChat,
            onSizeChanged = ::updatePanelBounds,
        )
        floatingView = panel
        panel.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                updatePanelBounds()
            }
        }
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        panel.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var moved = false

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                val lp = params ?: return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = lp.x
                        initialY = lp.y
                        touchX = event.rawX
                        touchY = event.rawY
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()
                        if (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) moved = true
                        if (moved) {
                            lp.x = initialX + dx
                            lp.y = initialY + dy
                            updatePanelBounds()
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) view.performClick()
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> return true
                }
                return false
            }
        })
        updatePanelBounds()
        syncFloatingVisibility()
    }

    private fun openChat() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
    }

    private fun updatePanelBounds() {
        val panel = floatingView as? AgentFloatingPanel ?: return
        val lp = params ?: return
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(panel)?.getInsetsIgnoringVisibility(
            androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout(),
        ) ?: androidx.core.graphics.Insets.NONE
        val margin = dpToPx(8)
        val availableWidth = (bounds.width() - insets.left - insets.right - margin * 2).coerceAtLeast(1)
        val preferredWidth = if (panel.isExpanded || resources.configuration.fontScale > 1.3f) 296 else 232
        lp.width = minOf(dpToPx(preferredWidth), availableWidth)
        val minX = insets.left + margin
        val minY = insets.top + margin
        lp.x = lp.x.coerceIn(minX, (bounds.width() - insets.right - lp.width - margin).coerceAtLeast(minX))
        lp.y = lp.y.coerceIn(minY, (bounds.height() - insets.bottom - panel.height - margin).coerceAtLeast(minY))
        if (panel.isAttachedToWindow) runCatching { windowManager.updateViewLayout(panel, lp) }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updatePanelBounds()
    }

    /** The host app keeps the same foreground/background visibility policy. */
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
            updatePanelBounds()
            runCatching { windowManager.addView(view, lp) }
        }
    }

    private fun observeAgentState() {
        stateCollectJob = serviceScope.launch {
            AgentExecutionController.state.collectLatest { state ->
                (floatingView as? AgentFloatingPanel)?.render(state)
                val stateLabel = getString(when {
                    state.isRunning -> R.string.floating_running
                    state.isPaused -> R.string.floating_paused
                    else -> R.string.floating_finished
                })
                val steps = if (state.maxSteps > 0) {
                    getString(R.string.floating_step_budget, state.currentStep, state.maxSteps)
                } else {
                    getString(R.string.floating_step_unlimited, state.currentStep)
                }
                updateNotification("$stateLabel · $steps · ${state.currentAction}")
                if (!state.isRunning && !state.isPaused) {
                    // A new task cancels this delay immediately, keeping its UI responsive.
                    delay(1500)
                    val latest = AgentExecutionController.state.value
                    if (com.paw.agent.device.floating.AgentFloatingServicePolicy
                            .shouldAutoStopAfterFinish(latest.isRunning, latest.isPaused)
                    ) {
                        stopSelf()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stateCollectJob?.cancel()
        serviceScope.cancel()
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

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, AgentFloatingService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { e ->
                android.util.Log.w("AgentFloatingService", "Failed to start foreground service: ${e.message}", e)
            }
        }

        fun stop(context: Context) {
            runCatching {
                val intent = Intent(context, AgentFloatingService::class.java)
                context.stopService(intent)
            }.onFailure { e ->
                android.util.Log.w("AgentFloatingService", "Failed to stop service: ${e.message}", e)
            }
        }
    }
}
