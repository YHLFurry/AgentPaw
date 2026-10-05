package com.paw.agent.device

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import com.paw.agent.device.accessibility.AgentAccessibilityService
import com.paw.agent.device.shizuku.ShizukuController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class HybridPhoneController(
    private val context: Context,
    private val shizukuController: ShizukuController = ShizukuController(),
    private val screenshotProcessor: AdaptiveScreenshotProcessor = AdaptiveScreenshotProcessor(),
) : PhoneController {

    override val isAccessibilityEnabled: Boolean
        get() = AgentAccessibilityService.isRunning

    override val isShizukuAvailable: Boolean
        get() = shizukuController.isAvailable

    private val screenWidth: Int
        get() = context.resources.displayMetrics.widthPixels

    private val screenHeight: Int
        get() = context.resources.displayMetrics.heightPixels

    /**
     * 用户是否已通过停止悬浮窗按钮请求停止。
     * 置位后所有无障碍/Shizuku 交互操作快速失败，保证"停止"立即生效。
     */
    private fun userStopRequested(): Boolean = AgentAccessibilityService.isStopRequested

    override suspend fun tap(xNormalized: Int, yNormalized: Int): Boolean {
        if (userStopRequested()) return false
        val x = AdaptiveScreenshotProcessor.toPhysicalX(xNormalized, screenWidth)
        val y = AdaptiveScreenshotProcessor.toPhysicalY(yNormalized, screenHeight)

        if (shizukuController.isAvailable) {
            val ok = shizukuController.tap(x.roundToInt(), y.roundToInt())
            if (ok) return true
        }

        val service = AgentAccessibilityService.instance
        if (service != null) {
            return service.clickAt(x, y)
        }
        return false
    }

    override suspend fun doubleTap(xNormalized: Int, yNormalized: Int): Boolean {
        if (userStopRequested()) return false
        val x = AdaptiveScreenshotProcessor.toPhysicalX(xNormalized, screenWidth)
        val y = AdaptiveScreenshotProcessor.toPhysicalY(yNormalized, screenHeight)

        val service = AgentAccessibilityService.instance
        if (service != null) {
            val ok = service.doubleClickAt(x, y)
            if (ok) return true
        }

        if (shizukuController.isAvailable) {
            shizukuController.tap(x.roundToInt(), y.roundToInt())
            kotlinx.coroutines.delay(100)
            return shizukuController.tap(x.roundToInt(), y.roundToInt())
        }
        return false
    }

    override suspend fun longPress(xNormalized: Int, yNormalized: Int, durationMs: Long): Boolean {
        if (userStopRequested()) return false
        val x = AdaptiveScreenshotProcessor.toPhysicalX(xNormalized, screenWidth)
        val y = AdaptiveScreenshotProcessor.toPhysicalY(yNormalized, screenHeight)

        val service = AgentAccessibilityService.instance
        if (service != null) {
            val ok = service.longPressAt(x, y, durationMs)
            if (ok) return true
        }

        if (shizukuController.isAvailable) {
            return shizukuController.swipe(x.roundToInt(), y.roundToInt(), x.roundToInt(), y.roundToInt(), durationMs)
        }
        return false
    }

    override suspend fun swipe(
        startXNormalized: Int,
        startYNormalized: Int,
        endXNormalized: Int,
        endYNormalized: Int,
        durationMs: Long,
    ): Boolean {
        if (userStopRequested()) return false
        val x1 = AdaptiveScreenshotProcessor.toPhysicalX(startXNormalized, screenWidth)
        val y1 = AdaptiveScreenshotProcessor.toPhysicalY(startYNormalized, screenHeight)
        val x2 = AdaptiveScreenshotProcessor.toPhysicalX(endXNormalized, screenWidth)
        val y2 = AdaptiveScreenshotProcessor.toPhysicalY(endYNormalized, screenHeight)

        if (shizukuController.isAvailable) {
            val ok = shizukuController.swipe(x1.roundToInt(), y1.roundToInt(), x2.roundToInt(), y2.roundToInt(), durationMs)
            if (ok) return true
        }

        val service = AgentAccessibilityService.instance
        if (service != null) {
            return service.swipe(x1, y1, x2, y2, durationMs)
        }
        return false
    }

    override suspend fun inputText(text: String, clearBeforeInput: Boolean): Boolean {
        if (userStopRequested()) return false
        val service = AgentAccessibilityService.instance
        if (service != null) {
            val ok = service.inputText(text, clearBeforeInput)
            if (ok) return true
        }

        if (shizukuController.isAvailable) {
            return shizukuController.inputText(text)
        }
        return false
    }

    override suspend fun pressBack(): Boolean {
        if (userStopRequested()) return false
        val service = AgentAccessibilityService.instance
        if (service != null && service.actionBack()) return true
        if (shizukuController.isAvailable) return shizukuController.keyEvent(4)
        return false
    }

    override suspend fun pressHome(): Boolean {
        if (userStopRequested()) return false
        val service = AgentAccessibilityService.instance
        if (service != null && service.actionHome()) return true
        if (shizukuController.isAvailable) return shizukuController.keyEvent(3)
        return false
    }

    override suspend fun pressRecents(): Boolean {
        if (userStopRequested()) return false
        val service = AgentAccessibilityService.instance
        if (service != null && service.actionRecents()) return true
        if (shizukuController.isAvailable) return shizukuController.keyEvent(187)
        return false
    }

    override suspend fun pressEnter(): Boolean {
        if (userStopRequested()) return false
        if (shizukuController.isAvailable) return shizukuController.keyEvent(66)
        val service = AgentAccessibilityService.instance
        if (service != null) {
            // 无障碍模式下模拟点击软键盘右下角的确认/搜索键区域 (normalized coords ~ 920, 940)
            val enterX = screenWidth * 0.92f
            val enterY = screenHeight * 0.94f
            return service.clickAt(enterX, enterY)
        }
        return false
    }

    override suspend fun launchApp(packageNameOrName: String): Boolean = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val query = packageNameOrName.trim().lowercase()

        // 1. Popular alias dictionary match (e.g. "微信" -> "com.tencent.mm")
        val aliasedPkg = POPULAR_APP_ALIASES[query]
        if (aliasedPkg != null) {
            val aliasIntent = pm.getLaunchIntentForPackage(aliasedPkg)
            if (aliasIntent != null) {
                aliasIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(aliasIntent)
                return@withContext true
            }
        }

        // 2. Direct package match
        val directIntent = pm.getLaunchIntentForPackage(query)
        if (directIntent != null) {
            directIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(directIntent)
            return@withContext true
        }

        // 3. Match by installed app label
        val installed = pm.getInstalledApplications(0)
        val matched = installed.firstOrNull { app ->
            val label = pm.getApplicationLabel(app).toString().lowercase()
            label == query || label.contains(query) || app.packageName.lowercase().contains(query)
        }

        if (matched != null) {
            val intent = pm.getLaunchIntentForPackage(matched.packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return@withContext true
            }
        }

        // 4. Fallback to monkey via Shizuku
        if (shizukuController.isAvailable) {
            val targetPkg = aliasedPkg ?: matched?.packageName ?: query
            val res = shizukuController.executeCommand("monkey -p $targetPkg -c android.intent.category.LAUNCHER 1")
            return@withContext !res.contains("No activities found") && !res.startsWith("Error:")
        }

        false
    }

    override suspend fun openDeepLink(uri: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    override suspend fun takeScreenshot(
        mode: VisionResolutionMode,
        cropRoi: List<Int>?,
    ): ScreenshotResult? {
        val rawBitmap = if (shizukuController.isAvailable) {
            shizukuController.captureScreen() ?: AgentAccessibilityService.instance?.captureScreen()
        } else {
            AgentAccessibilityService.instance?.captureScreen()
        } ?: return null

        return screenshotProcessor.processScreenshot(rawBitmap, mode, cropRoi)
    }

    override suspend fun getScreenState(): ScreenStateInfo {
        val serviceState = AgentAccessibilityService.instance?.dumpScreenState()
        val fgPkg = if (serviceState?.foregroundPackage.isNullOrBlank() && shizukuController.isAvailable) {
            shizukuController.getForegroundPackage()
        } else {
            serviceState?.foregroundPackage.orEmpty()
        }

        return ScreenStateInfo(
            foregroundPackage = fgPkg,
            foregroundActivity = serviceState?.foregroundActivity.orEmpty(),
            elements = serviceState?.elements ?: emptyList(),
        )
    }

    companion object {
        val POPULAR_APP_ALIASES = mapOf(
            "微信" to "com.tencent.mm",
            "wechat" to "com.tencent.mm",
            "支付宝" to "com.eg.android.AlipayGphone",
            "alipay" to "com.eg.android.AlipayGphone",
            "淘宝" to "com.taobao.taobao",
            "taobao" to "com.taobao.taobao",
            "京东" to "com.jingdong.app.mall",
            "jd" to "com.jingdong.app.mall",
            "拼多多" to "com.xunmeng.pinduoduo",
            "pdd" to "com.xunmeng.pinduoduo",
            "抖音" to "com.ss.android.ugc.aweme",
            "douyin" to "com.ss.android.ugc.aweme",
            "tiktok" to "com.ss.android.ugc.aweme",
            "快手" to "com.smile.gifmaker",
            "美团" to "com.sankuai.meituan",
            "meituan" to "com.sankuai.meituan",
            "大众点评" to "com.dianping.v1",
            "小红书" to "com.xingin.xhs",
            "xhs" to "com.xingin.xhs",
            "哔哩哔哩" to "tv.danmaku.bili",
            "b站" to "tv.danmaku.bili",
            "bilibili" to "tv.danmaku.bili",
            "网易云音乐" to "com.netease.cloudmusic",
            "qq音乐" to "com.tencent.qqmusic",
            "高德地图" to "com.autonavi.minimap",
            "百度地图" to "com.baidu.BaiduMap",
            "微博" to "com.sina.weibo",
            "知乎" to "com.zhihu.android",
            "设置" to "com.android.settings",
            "settings" to "com.android.settings",
        )
    }
}

