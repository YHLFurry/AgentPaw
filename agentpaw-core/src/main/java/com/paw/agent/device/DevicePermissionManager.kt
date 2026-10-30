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

    /**
     * 全进程唯一的 RootController 实例。
     *
     * [HybridPhoneController] 默认也使用这同一个实例，保证「设置页刷新 Root 状态」
     * 与「任务执行通道读取 Root 状态」看到的永远是同一份事实（ISSUE-202：
     * 此前两者各持一个实例，设置页刷新的是另一个对象，执行通道的缓存
     * 可能长期停留在陈旧的 false，导致用户已授权 Root 却静默走降级通道）。
     */
    var rootController: com.paw.agent.device.root.RootController =
        com.paw.agent.device.root.RootController()
        private set

    /**
     * 仅供单元测试注入替身，验证共享实例接线与缓存刷新语义。
     * 生产代码不得调用。
     */
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    fun installRootControllerForTesting(controller: com.paw.agent.device.root.RootController) {
        rootController = controller
    }

    /** 恢复真实 RootController（测试清理用，生产代码不得调用）。 */
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    fun resetRootControllerForTesting() {
        rootController = com.paw.agent.device.root.RootController()
    }

    /**
     * 检测 ROOT 权限是否可用（读取缓存，不阻塞探测）。
     */
    fun isRootAvailable(): Boolean = rootController.isAvailable

    /**
     * 重新探测 Root 授权状态并刷新缓存（IO 调度，可安全从主线程协程调用）。
     *
     * Root 授权可能随时变化：用户在 Magisk 完成授权、撤销授权、或重启后
     * 首次探测超时。缓存不会自动过期，因此需要在以下时机主动刷新：
     * 回到 APP 前台、用户在设置里开启 Root 模式、设置页手动测试。
     */
    suspend fun refreshRootAvailability(): Boolean =
        rootController.refreshAvailability()

    /**
     * 设置页「测试 ROOT」按钮：刷新并测试 ROOT 权限。
     * 与 [refreshRootAvailability] 等价，保留原名以兼容既有调用方。
     */
    suspend fun requestOrTestRoot(): Boolean = refreshRootAvailability()

    /**
     * 通过 ROOT 权限为本应用一键静默激活无障碍服务
     */
    suspend fun enableAccessibilityViaRoot(context: Context): Boolean {
        return rootController.enableAccessibilityViaRoot(
            packageName = context.packageName,
            serviceClassName = AgentAccessibilityService::class.java.name,
        )
    }
    /**
     * 同时检查应用通知总开关与 Android 13+ 运行时权限。
     */
    fun hasNotificationPermission(context: Context): Boolean =
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else true

    /**
     * 打开通知设置页面
     */
    fun openNotificationSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    /**
     * 判断当前设备是否为小米/红米/POCO 设备或运行小米澎湃OS (HyperOS) / MIUI
     */
    fun isXiaomiOrHyperOs(): Boolean {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val brand = Build.BRAND.orEmpty()
        if (manufacturer.contains("Xiaomi", ignoreCase = true) ||
            brand.contains("Xiaomi", ignoreCase = true) ||
            brand.contains("Redmi", ignoreCase = true) ||
            brand.contains("POCO", ignoreCase = true)
        ) {
            return true
        }
        return !getSystemProperty("ro.miui.ui.version.name").isNullOrBlank() ||
            !getSystemProperty("ro.mi.os.version.name").isNullOrBlank()
    }

    private fun getSystemProperty(key: String): String? {
        return runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val getMethod = clazz.getMethod("get", String::class.java)
            getMethod.invoke(null, key) as? String
        }.getOrNull()
    }

    /**
     * 打开应用详情页面（针对 Android 13+ 小米澎湃OS 的“受限设置”，
     * 需在此页面右上角点击「允许受限设置」；同时可配置自启动与省电策略）。
     */
    fun openAppDetailsSettings(context: Context) {
        runCatching {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    /**
     * 打开小米澎湃OS / MIUI 权限管理页面（包含“后台弹出界面”、“显示悬浮窗”等专属开关）。
     */
    fun openXiaomiPermissionsSettings(context: Context): Boolean {
        return runCatching {
            val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity",
                )
                putExtra("extra_pkgname", context.packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        }.recoverCatching {
            openAppDetailsSettings(context)
            false
        }.getOrDefault(false)
    }

    /**
     * 打开系统开发者选项页面（用于开启「USB 调试（安全设置）」以支持模拟点击）。
     */
    fun openDeveloperOptions(context: Context): Boolean {
        return runCatching {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
}

