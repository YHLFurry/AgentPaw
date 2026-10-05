package com.paw.agent

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paw.agent.data.settings.AppSettings
import com.paw.agent.ui.AgentPawApp
import com.paw.agent.ui.theme.AgentPawTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        disableAdaptiveRefreshRateForWindow()
        super.onCreate(savedInstanceState)

        val container = (application as AgentPawApplication).container

        setContent {
            val settings by container.settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings.Default)

            val executionState by com.paw.agent.ui.floating.AgentExecutionController.state
                .collectAsStateWithLifecycle()
            val context = androidx.compose.ui.platform.LocalContext.current

            androidx.compose.runtime.LaunchedEffect(executionState.isRunning) {
                if (executionState.isRunning) {
                    com.paw.agent.ui.floating.AgentFloatingService.start(context)
                }
            }

            SyncSystemBarAppearance(darkTheme = settings.darkTheme)

            AgentPawTheme(
                darkTheme = settings.darkTheme,
                dynamicColor = settings.dynamicColor,
            ) {
                AgentPawApp(container = container)
            }
        }
    }

    /**
     * 规避 Android 15/16 上 dVRR/ARR 动态刷新率与 Adreno 驱动的兼容问题。
     *
     * 背景：Compose（1.9+）动画会通过 RenderNode.setRequestedFrameRate 投票，
     * 触发系统 ARR/dVRR 动态切换刷新率。在部分设备（如 Galaxy S24 Ultra /
     * 骁龙 8 Gen 3 / Adreno 750）上，每次切换都会引发 Vulkan 着色器管线
     * 反复编译失败，首帧渲染卡顿 30~40 秒，表现为启动后长时间紫屏假死。
     *
     * 该 API（API 35+）是官方提供的窗口级 ARR 退出机制，仅影响本窗口的
     * 动态刷新率调节，官方文档明确建议仅在出现严重影响体验的问题时使用。
     */
    private fun disableAdaptiveRefreshRateForWindow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        try {
            window.isFrameRatePowerSavingsBalanced = false
        } catch (_: Exception) {
            // 个别 OEM ROM 对该 API 的实现可能异常。此处仅损失动态刷新率优化，
            // 不应阻断启动流程，故静默忽略。
        }
    }
}

/**
 * Keeps the status bar icons legible against the current theme.
 * Called from the composition so it re-runs when the theme flips.
 */
@Composable
private fun SyncSystemBarAppearance(darkTheme: Boolean) {
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? ComponentActivity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}
