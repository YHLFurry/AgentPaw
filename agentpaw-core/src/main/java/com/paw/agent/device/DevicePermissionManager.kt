package com.paw.agent.device

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import com.paw.agent.device.accessibility.AgentAccessibilityService
import com.paw.agent.device.shizuku.ShizukuInitializer
import com.paw.agent.device.shizuku.ShizukuStatus
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku

/**
 * 集中管理系统权限（无障碍、Shizuku、悬浮窗）的检测与跳转
 */
object DevicePermissionManager {

    /**
     * 检测无障碍服务是否已启用
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedComponentName = ComponentName(context, AgentAccessibilityService::class.java).flattenToString()
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            if (componentNameString.equals(expectedComponentName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    /**
     * 打开系统无障碍设置页面
     */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    /**
     * 检测 Shizuku 服务是否运行（binder 是否就绪）
     */
    fun isShizukuRunning(): Boolean {
        return runCatching {
            Shizuku.pingBinder()
        }.getOrDefault(false)
    }

    /**
     * 检测是否拥有 Shizuku 权限
     */
    fun hasShizukuPermission(): Boolean {
        return runCatching {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    /**
     * 订阅 Shizuku 状态（NOT_RUNNING / RUNNING_NO_PERMISSION / GRANTED）。
     * 首次调用会触发 [ShizukuInitializer.initialize]。
     */
    fun observeShizukuState(): StateFlow<ShizukuStatus> {
        ShizukuInitializer.initialize()
        return ShizukuInitializer.status
    }

    /**
     * 申请 Shizuku 权限（会拉起 Shizuku Manager 授权对话框，
     * 授权结果通过 [observeShizukuState] 实时回调）。
     *
     * @return true 表示授权请求已成功发起；false 表示 Shizuku 未运行或请求失败，
     *         调用方可引导用户先启动 Shizuku 服务（见 [openShizukuApp]）。
     */
    fun requestShizukuPermission(requestCode: Int = ShizukuInitializer.DEFAULT_REQUEST_CODE): Boolean {
        return runCatching {
            ShizukuInitializer.requestAuthorization(requestCode)
        }.getOrDefault(false)
    }

    /**
     * 尝试打开 Shizuku 应用（用于 Shizuku 服务未运行时引导用户启动）。
     *
     * @return true 表示已成功跳转；false 表示设备上未安装 Shizuku。
     */
    fun openShizukuApp(context: Context): Boolean {
        return runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                ?: return@runCatching false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /**
     * 检测是否拥有悬浮窗权限
     */
    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * 打开悬浮窗权限设置页面
     */
    fun openOverlaySettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    val rootController by lazy { com.paw.agent.device.root.RootController() }

    /**
     * 检测 ROOT 权限是否可用
     */
    fun isRootAvailable(): Boolean = rootController.isAvailable

    /**
     * 刷新并测试 ROOT 权限
     */
    suspend fun requestOrTestRoot(): Boolean = rootController.refreshAvailability()

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
}
