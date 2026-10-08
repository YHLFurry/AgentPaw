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
    internal val rootController: com.paw.agent.device.root.RootController = com.paw.agent.device.root.RootController(),
    private val screenshotProcessor: AdaptiveScreenshotProcessor = AdaptiveScreenshotProcessor(),
) : PhoneController {

    @Volatile
    var controlMode: PhoneControlMode = PhoneControlMode.AUTO

    override val isAccessibilityEnabled: Boolean
        get() = AgentAccessibilityService.isRunning

    override val isShizukuAvailable: Boolean
        get() = shizukuController.isAvailable

    override val isRootAvailable: Boolean
        get() = rootController.isAvailable

    private val screenWidth: Int
        get() = context.resources.displayMetrics.widthPixels

    private val screenHeight: Int
        get() = context.resources.displayMetrics.heightPixels

    /**
     * 最近一次截图的真实像素尺寸（截图源分辨率）。
     * 用它作为 [0,1000] 归一化坐标的反向映射基准，而不是 `displayMetrics`——
     * 二者在多数设备一致，但当 Shizuku 的 `screencap` 返回原生分辨率、或在
     * 多 display / 旋转场景下，`displayMetrics` 可能与模型实际"看到"的截图尺寸
     * 不一致，从而让点击整体偏移。以截图源尺寸为基准可消除该偏差。
     */
    @Volatile
    private var lastScreenshotWidth: Int = 0

    @Volatile
    private var lastScreenshotHeight: Int = 0

    @Volatile
    private var lastScreenshotOrientation: Int = 0

    /**
     * 用户是否已通过停止悬浮窗按钮请求停止。
     * 置位后所有无障碍/Shizuku 交互操作快速失败，保证"停止"立即生效。
     */
    private fun userStopRequested(): Boolean = AgentAccessibilityService.isStopRequested

    /** 归一化坐标映射所用的真实屏幕宽度（优先用最近一次截图源尺寸，并检查屏幕旋转是否失效）。 */
    private fun refWidth(): Int {
        val currentOrientation = context.resources.configuration.orientation
        if (currentOrientation != lastScreenshotOrientation) {
            lastScreenshotWidth = 0
            lastScreenshotHeight = 0
        }
        return if (lastScreenshotWidth > 0) lastScreenshotWidth else screenWidth
    }

    /** 归一化坐标映射所用的真实屏幕高度（优先用最近一次截图源尺寸，并检查屏幕旋转是否失效）。 */
    private fun refHeight(): Int {
        val currentOrientation = context.resources.configuration.orientation
        if (currentOrientation != lastScreenshotOrientation) {
            lastScreenshotWidth = 0
            lastScreenshotHeight = 0
        }
        return if (lastScreenshotHeight > 0) lastScreenshotHeight else screenHeight
    }

    /**
     * 将 [0,1000] 归一化坐标还原为真实屏幕像素坐标。若提供了 `cropRoi`
     * （[ymin, xmin, ymax, xmax]，同样是 0..1000 归一化全屏坐标），则先把它
     * 反向展开回全屏空间，再映射到像素——这样基于"裁剪后截图"推断出的坐标
     * 也会落回正确位置，而非整屏中心。
     */
    private fun toPhysical(
        xNormalized: Int,
        yNormalized: Int,
        cropRoi: List<Int>?,
    ): Pair<Float, Float> {
        val w = refWidth().coerceAtLeast(1)
        val h = refHeight().coerceAtLeast(1)

        val (fx, fy) = if (cropRoi != null && cropRoi.size == 4) {
            val xmin = (cropRoi[1] / 1000f).coerceIn(0f, 1f)
            val xmax = (cropRoi[3] / 1000f).coerceIn(0f, 1f)
            val ymin = (cropRoi[0] / 1000f).coerceIn(0f, 1f)
            val ymax = (cropRoi[2] / 1000f).coerceIn(0f, 1f)
            val fxFull = xmin + ((xNormalized.coerceIn(0, 1000) / 1000f)) * (xmax - xmin)
            val fyFull = ymin + ((yNormalized.coerceIn(0, 1000) / 1000f)) * (ymax - ymin)
            Pair(fxFull, fyFull)
        } else {
            Pair(xNormalized.coerceIn(0, 1000) / 1000f, yNormalized.coerceIn(0, 1000) / 1000f)
        }

        return Pair((fx.coerceIn(0f, 1f) * w), (fy.coerceIn(0f, 1f) * h))
    }

    private val isRootAllowed: Boolean
        get() = (controlMode == PhoneControlMode.ROOT || controlMode == PhoneControlMode.AUTO) && rootController.isAvailable

    private val isShizukuAllowed: Boolean
        get() = (controlMode == PhoneControlMode.SHIZUKU || controlMode == PhoneControlMode.AUTO) && shizukuController.isAvailable

    private val shouldUseRoot: Boolean get() = isRootAllowed
    private val shouldUseShizuku: Boolean get() = isShizukuAllowed

    /** 点击后短暂稳定，确保手势被系统处理、下一帧渲染完成，提升连续操作的命中率。 */
    private suspend fun settleAfterTap() {
        kotlinx.coroutines.delay(100)
    }

    override suspend fun tap(xNormalized: Int, yNormalized: Int, cropRoi: List<Int>?): Boolean {
        if (userStopRequested()) return false
        val (x, y) = toPhysical(xNormalized, yNormalized, cropRoi)

        if (isRootAllowed) {
            val ok = rootController.tap(x.roundToInt(), y.roundToInt())
            if (ok) { settleAfterTap(); return true }
        }

        if (isShizukuAllowed) {
            val ok = shizukuController.tap(x.roundToInt(), y.roundToInt())
            if (ok) { settleAfterTap(); return true }
        }

        val service = AgentAccessibilityService.instance
        if (service != null) {
            val ok = service.clickAt(x, y)
            if (ok) { settleAfterTap(); return true }
        }
        return false
    }

    override suspend fun doubleTap(xNormalized: Int, yNormalized: Int, cropRoi: List<Int>?): Boolean {
        if (userStopRequested()) return false
        val (x, y) = toPhysical(xNormalized, yNormalized, cropRoi)

        if (isRootAllowed) {
            rootController.tap(x.roundToInt(), y.roundToInt())
            kotlinx.coroutines.delay(100)
            val ok = rootController.tap(x.roundToInt(), y.roundToInt())
            if (ok) { settleAfterTap(); return true }
        }

        if (isShizukuAllowed) {
            shizukuController.tap(x.roundToInt(), y.roundToInt())
            kotlinx.coroutines.delay(100)
            val ok = shizukuController.tap(x.roundToInt(), y.roundToInt())
            if (ok) { settleAfterTap(); return true }
        }

        val service = AgentAccessibilityService.instance
        if (service != null) {
            val ok = service.doubleClickAt(x, y)
            if (ok) { settleAfterTap(); return true }
        }

        return false
    }

    override suspend fun longPress(xNormalized: Int, yNormalized: Int, durationMs: Long, cropRoi: List<Int>?): Boolean {
        if (userStopRequested()) return false
        val (x, y) = toPhysical(xNormalized, yNormalized, cropRoi)

        if (isRootAllowed) {
            val ok = rootController.swipe(x.roundToInt(), y.roundToInt(), x.roundToInt(), y.roundToInt(), durationMs)
            if (ok) { settleAfterTap(); return true }
        }

        if (isShizukuAllowed) {
            val ok = shizukuController.swipe(x.roundToInt(), y.roundToInt(), x.roundToInt(), y.roundToInt(), durationMs)
            if (ok) { settleAfterTap(); return true }
        }

        val service = AgentAccessibilityService.instance
        if (service != null) {
            val ok = service.longPressAt(x, y, durationMs)
            if (ok) { settleAfterTap(); return true }
        }

        return false
    }

    override suspend fun tapAtPixel(centerX: Float, centerY: Float): Boolean {
        if (userStopRequested()) return false
        if (isRootAllowed) {
            val ok = rootController.tap(centerX.roundToInt(), centerY.roundToInt())
            if (ok) { settleAfterTap(); return true }
        }

        if (isShizukuAllowed) {
            val ok = shizukuController.tap(centerX.roundToInt(), centerY.roundToInt())
            if (ok) { settleAfterTap(); return true }
        }

        val service = AgentAccessibilityService.instance
        if (service != null) {
            val ok = service.clickAt(centerX, centerY)
            if (ok) { settleAfterTap(); return true }
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
        val (x1, y1) = toPhysical(startXNormalized, startYNormalized, null)
        val (x2, y2) = toPhysical(endXNormalized, endYNormalized, null)

        if (isRootAllowed) {
            val ok = rootController.swipe(x1.roundToInt(), y1.roundToInt(), x2.roundToInt(), y2.roundToInt(), durationMs)
            if (ok) return true
        }

        if (isShizukuAllowed) {
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

        // 1. 默认直接输入文本到聊天框/输入框 (优先通过无障碍直接注入，快速且完美兼容各类字符)
        val service = AgentAccessibilityService.instance
        if (service != null) {
            val directOk = service.inputText(text, clearBeforeInput)
            if (directOk) return true
        }

        // 2. 若直接输入失败，则使用键盘保底 (Fallback to keyboard)
        // 2.1 Root 模拟物理/系统键盘输入保底
        if (isRootAllowed) {
            val rootOk = rootController.inputText(text)
            if (rootOk) return true
        }

        // 2.2 Shizuku 模拟键盘输入保底
        if (isShizukuAllowed) {
            val shizukuOk = shizukuController.inputText(text)
            if (shizukuOk) return true
        }

        // 2.3 无障碍输入法软键盘保底（模拟聚焦激活软键盘并键入）
        if (service != null) {
            val fallbackOk = service.keyboardFallbackInput(text)
            if (fallbackOk) return true
        }

        return false
    }

    override suspend fun pressBack(): Boolean {
        if (userStopRequested()) return false
        if (isRootAllowed && rootController.keyEvent(4)) return true
        val service = AgentAccessibilityService.instance
        if (service != null && service.actionBack()) return true
        if (isShizukuAllowed && shizukuController.keyEvent(4)) return true
        return false
    }

    override suspend fun pressHome(): Boolean {
        if (userStopRequested()) return false
        if (isRootAllowed && rootController.keyEvent(3)) return true
        val service = AgentAccessibilityService.instance
        if (service != null && service.actionHome()) return true
        if (isShizukuAllowed && shizukuController.keyEvent(3)) return true
        return false
    }

    override suspend fun pressRecents(): Boolean {
        if (userStopRequested()) return false
        if (isRootAllowed && rootController.keyEvent(187)) return true
        val service = AgentAccessibilityService.instance
        if (service != null && service.actionRecents()) return true
        if (isShizukuAllowed && shizukuController.keyEvent(187)) return true
        return false
    }

    override suspend fun pressEnter(): Boolean {
        if (userStopRequested()) return false
        if (isRootAllowed && rootController.keyEvent(66)) return true
        if (isShizukuAllowed && shizukuController.keyEvent(66)) return true
        val service = AgentAccessibilityService.instance
        if (service != null) {
            // 无障碍模式下模拟点击软键盘右下角的确认/搜索键区域 (normalized coords ~ 920, 940)
            val enterX = refWidth() * 0.92f
            val enterY = refHeight() * 0.94f
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
        if (PACKAGE_NAME_REGEX.matches(query)) {
            val directIntent = pm.getLaunchIntentForPackage(query)
            if (directIntent != null) {
                directIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(directIntent)
                return@withContext true
            }
        }

        // 3. Match by installed app label using prioritized precision ranking
        val matchedPkg = findBestMatchingPackage(query, pm)

        if (matchedPkg != null) {
            val intent = pm.getLaunchIntentForPackage(matchedPkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return@withContext true
            }
        }

        // 4. Resolve safe target package
        // 严格安全防线：只允许合法包名格式；未匹配到已安装应用或合法别名时直接失败，严禁将未经验证的原始输入作为 shell 参数
        val candidatePkg = aliasedPkg ?: matchedPkg ?: if (PACKAGE_NAME_REGEX.matches(query) && isPackageInstalled(query, pm)) query else null
        val targetPkg = candidatePkg?.takeIf { PACKAGE_NAME_REGEX.matches(it) } ?: return@withContext false

        // 5. Root execution via monkey
        if (shouldUseRoot) {
            val res = rootController.executeCommand("monkey -p $targetPkg -c android.intent.category.LAUNCHER 1")
            if (!res.contains("No activities found") && !res.startsWith("Error:")) {
                return@withContext true
            }
        }

        // 6. Fallback to monkey via Shizuku
        if (shouldUseShizuku) {
            val res = shizukuController.executeCommand("monkey -p $targetPkg -c android.intent.category.LAUNCHER 1")
            return@withContext !res.contains("No activities found") && !res.startsWith("Error:")
        }

        false
    }

    private fun isPackageInstalled(packageName: String, pm: android.content.pm.PackageManager): Boolean {
        return runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            true
        }.getOrDefault(false)
    }

    private fun findBestMatchingPackage(query: String, pm: android.content.pm.PackageManager): String? {
        val q = query.trim().lowercase()
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(launcherIntent, 0)

        data class Candidate(val packageName: String, val score: Int, val labelLength: Int)
        val candidates = mutableListOf<Candidate>()

        for (ri in resolveInfos) {
            val label = ri.loadLabel(pm).toString().trim().lowercase()
            val pkg = ri.activityInfo.packageName.lowercase()

            val score = when {
                label == q -> 100
                pkg == q -> 95
                label.startsWith(q) -> 80
                pkg.endsWith(".$q") -> 75
                label.contains(q) -> 60
                pkg.contains(q) -> 40
                else -> 0
            }

            if (score > 0) {
                candidates.add(Candidate(ri.activityInfo.packageName, score, label.length))
            }
        }

        if (candidates.isEmpty()) {
            runCatching {
                pm.getInstalledApplications(0).forEach { app ->
                    val label = pm.getApplicationLabel(app).toString().trim().lowercase()
                    val pkg = app.packageName.lowercase()
                    val score = when {
                        label == q -> 100
                        pkg == q -> 95
                        label.startsWith(q) -> 80
                        pkg.endsWith(".$q") -> 75
                        label.contains(q) -> 60
                        pkg.contains(q) -> 40
                        else -> 0
                    }
                    if (score > 0) {
                        candidates.add(Candidate(app.packageName, score, label.length))
                    }
                }
            }
        }

        return candidates
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.labelLength })
            .firstOrNull()?.packageName
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
        val rawBitmap = (
            if (shouldUseRoot) {
                rootController.captureScreen()
                    ?: if (shouldUseShizuku) shizukuController.captureScreen() else null
                    ?: AgentAccessibilityService.instance?.captureScreen()
            } else if (shouldUseShizuku) {
                shizukuController.captureScreen() ?: AgentAccessibilityService.instance?.captureScreen()
            } else {
                AgentAccessibilityService.instance?.captureScreen()
            }
        ) ?: return null

        // 记录本次截图的真实源分辨率，作为后续 tap 归一化反向映射的基准。
        lastScreenshotWidth = rawBitmap.width
        lastScreenshotHeight = rawBitmap.height

        return try {
            screenshotProcessor.processScreenshot(rawBitmap, mode, cropRoi)
        } finally {
            rawBitmap.recycle()
        }
    }

    override suspend fun getScreenState(): ScreenStateInfo = withContext(Dispatchers.IO) {
        val serviceState = AgentAccessibilityService.instance?.dumpScreenState()
        val fgPkg = if (serviceState?.foregroundPackage.isNullOrBlank()) {
            if (shouldUseRoot) {
                rootController.getForegroundPackage().ifBlank {
                    if (shouldUseShizuku) shizukuController.getForegroundPackage() else ""
                }
            } else if (shouldUseShizuku) {
                shizukuController.getForegroundPackage()
            } else {
                ""
            }
        } else {
            serviceState.foregroundPackage
        }

        ScreenStateInfo(
            foregroundPackage = fgPkg,
            foregroundActivity = serviceState?.foregroundActivity.orEmpty(),
            elements = serviceState?.elements ?: emptyList(),
        )
    }

    companion object {
        private val dynamicAppAliases = java.util.concurrent.ConcurrentHashMap<String, String>()

        /**
         * 动态注册或覆盖应用别名映射，方便宿主应用扩展或从配置资源载入
         */
        fun registerAppAlias(alias: String, packageName: String) {
            dynamicAppAliases[alias.lowercase()] = packageName
        }

        fun registerAppAliases(aliases: Map<String, String>) {
            aliases.forEach { (k, v) -> dynamicAppAliases[k.lowercase()] = v }
        }

        fun getAppPackageName(nameOrAlias: String): String? {
            val lower = nameOrAlias.lowercase()
            return dynamicAppAliases[lower] ?: DEFAULT_APP_ALIASES[lower]
        }

        val DEFAULT_APP_ALIASES = mapOf(
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

        // 兼容已有代码与单测的复合别名映射
        val POPULAR_APP_ALIASES: Map<String, String>
            get() = DEFAULT_APP_ALIASES + dynamicAppAliases

        val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    }
}

