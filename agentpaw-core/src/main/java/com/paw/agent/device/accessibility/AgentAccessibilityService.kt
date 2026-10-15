package com.paw.agent.device.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.paw.agent.device.RectBounds
import com.paw.agent.device.ScreenStateInfo
import com.paw.agent.device.UiElementInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

class AgentAccessibilityService : AccessibilityService() {

    private val currentPackage = AtomicReference<String>("")
    private val currentActivity = AtomicReference<String>("")

    /**
     * 用户主动停止标志：由停止悬浮窗按钮（AgentStopFloatingButton）触发。
     * 置位后所有未完成/后续的无障碍手势立即中止。
     */
    @Volatile
    var isStopRequested: Boolean = false
        private set

    /**
     * 请求停止所有无障碍操作（停止悬浮窗按钮点击时调用）。
     * 注意：已派发给系统的手势无法撤销，但结果会被忽略且后续操作全部中止。
     */
    fun requestUserStop() {
        isStopRequested = true
    }

    /** 清除停止标志，供新一轮任务开始前调用 */
    fun clearUserStop() {
        isStopRequested = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        event.packageName?.let {
            currentPackage.set(it.toString())
            // 供 AgentAppForegroundMonitor 判定宿主 APP 前后台（无 Activity 场景的兜底信号）
            com.paw.agent.device.floating.AgentAppForegroundMonitor.notifyAccessibilityForegroundPackage(it.toString())
        }
        event.className?.let { currentActivity.set(it.toString()) }
    }

    override fun onInterrupt() {
        // Required callback
    }

    suspend fun clickAt(x: Float, y: Float): Boolean = withContext(Dispatchers.Main) {
        if (isStopRequested) return@withContext false
        // 钳制到真实显示边界：截图源尺寸与当前屏幕不一致（旋转/多屏）时，
        // 越界坐标会被 dispatchGesture 直接拒收，钳制后至少能落在屏幕内。
        val (cx, cy) = clampToDisplay(x, y)
        val path = Path().apply {
            moveTo(cx, cy)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_STROKE_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGestureSuspend(gesture)
    }

    suspend fun doubleClickAt(x: Float, y: Float): Boolean = withContext(Dispatchers.Main) {
        if (isStopRequested) return@withContext false
        val (cx, cy) = clampToDisplay(x, y)
        val path = Path().apply { moveTo(cx, cy) }
        val stroke1 = GestureDescription.StrokeDescription(path, 0, TAP_STROKE_DURATION_MS)
        val stroke2 = GestureDescription.StrokeDescription(path, 100, TAP_STROKE_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke1).addStroke(stroke2).build()
        dispatchGestureSuspend(gesture)
    }

    suspend fun longPressAt(x: Float, y: Float, durationMs: Long = 1000L): Boolean = withContext(Dispatchers.Main) {
        if (isStopRequested) return@withContext false
        val (cx, cy) = clampToDisplay(x, y)
        val path = Path().apply { moveTo(cx, cy) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(500L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGestureSuspend(gesture)
    }

    suspend fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 350L,
    ): Boolean = withContext(Dispatchers.Main) {
        if (isStopRequested) return@withContext false
        val (sx, sy) = clampToDisplay(startX, startY)
        val (ex, ey) = clampToDisplay(endX, endY)
        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(100L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGestureSuspend(gesture)
    }

    // ------------------------------------------------------------------
    // 节点锚定点击（无障碍模式点击精度的核心路径）
    // ------------------------------------------------------------------

    /**
     * 节点锚定点击：把 (x, y) 解析为最深层可见节点并沿父链找可点击祖先，
     * 直接对其执行 ACTION_CLICK——命中判定由 framework 完成，零坐标误差。
     *
     * 返回 false 表示该处没有可点击节点（WebView / 游戏 / SurfaceView 等
     * 自定义渲染区域）或动作未被接受，调用方应回退手势注入点击。
     */
    suspend fun clickNodeAt(x: Float, y: Float): Boolean = withContext(Dispatchers.Main) {
        if (isStopRequested) return@withContext false
        val (cx, cy) = clampToDisplay(x, y)
        val collected = collectClickableNodes() ?: return@withContext false
        try {
            val target = AccessibilityClickResolver.resolveClickTargetAt(cx, cy, collected.snapshots)
                ?: return@withContext false
            if (isStopRequested) return@withContext false
            val node = collected.nodeById[target.id] ?: return@withContext false
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally {
            recycleCollectedNodes(collected.nodeById.values)
        }
    }

    /**
     * 节点锚定长按：仅在无障碍树显式声明 isLongClickable 的节点链上执行
     * ACTION_LONG_CLICK；否则返回 false 交回手势长按（应用自绘长按不向
     * 无障碍树声明时，手势才是保真路径）。
     */
    suspend fun longPressNodeAt(x: Float, y: Float): Boolean = withContext(Dispatchers.Main) {
        if (isStopRequested) return@withContext false
        val (cx, cy) = clampToDisplay(x, y)
        val collected = collectClickableNodes() ?: return@withContext false
        try {
            val target = AccessibilityClickResolver.resolveLongPressTargetAt(cx, cy, collected.snapshots)
                ?: return@withContext false
            if (isStopRequested) return@withContext false
            val node = collected.nodeById[target.id] ?: return@withContext false
            node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        } finally {
            recycleCollectedNodes(collected.nodeById.values)
        }
    }

    /**
     * 点击底部操作区中语义为「发送 / 提交 / 确认」的可点击节点
     * （聊天发送键、表单确认键等），供 pressEnter 在焦点节点 IME 动作
     * 不可用时替代「盲点右下角 92%/94%」的粗糙兜底。
     *
     * 仅当当前存在获得焦点的可编辑节点（输入场景）时才启用该启发式，
     * 避免在非输入场景误点底部按钮；找不到则返回 false。
     */
    suspend fun clickSubmitActionNode(): Boolean = withContext(Dispatchers.Main) {
        if (isStopRequested) return@withContext false
        val root = rootInActiveWindow ?: return@withContext false
        val focusedEditable = runCatching {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }.getOrNull()?.takeIf { it.isEditable || it.isFocused } ?: return@withContext false

        val collected = collectClickableNodes() ?: return@withContext false
        try {
            val display = displayBounds()
            if (display.height() <= 0) return@withContext false
            // 底部 25% 操作带（发送键通常贴底，个别应用居中）
            val stripTop = display.top + display.height() * 0.75f
            val candidates = collected.snapshots.filter { snap ->
                snap.visibleToUser &&
                    snap.isClickable &&
                    snap.bounds.centerY >= stripTop &&
                    (snap.text + " " + snap.contentDescription).lowercase().let { label ->
                        SUBMIT_KEYWORDS.any { label.contains(it) }
                    }
            }
            // 发送键通常在最下方；同一水平线取更靠右者
            val best = candidates.maxWithOrNull(
                compareBy<ClickableNodeSnapshot> { it.bounds.centerY }
                    .thenBy { it.bounds.centerX },
            ) ?: return@withContext false
            if (isStopRequested) return@withContext false
            val node = collected.nodeById[best.id] ?: return@withContext false
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally {
            recycleCollectedNodes(collected.nodeById.values)
        }
    }

    // ------------------------------------------------------------------
    // 坐标空间与手势派发细节
    // ------------------------------------------------------------------

    /** 当前真实显示边界（含系统装饰区），用于手势坐标钳制 */
    private fun displayBounds(): Rect {
        return try {
            val wm = getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wm?.maximumWindowMetrics?.bounds ?: fallbackDisplayBounds()
            } else {
                @Suppress("DEPRECATION")
                val display = wm?.defaultDisplay
                val size = android.graphics.Point()
                @Suppress("DEPRECATION")
                display?.getRealSize(size)
                Rect(0, 0, size.x, size.y)
            }
        } catch (t: Throwable) {
            fallbackDisplayBounds()
        }
    }

    private fun fallbackDisplayBounds(): Rect {
        val dm = resources.displayMetrics
        return Rect(0, 0, dm.widthPixels.coerceAtLeast(1), dm.heightPixels.coerceAtLeast(1))
    }

    /** 把坐标钳制进真实显示边界，杜绝越界坐标被 dispatchGesture 拒收 */
    private fun clampToDisplay(x: Float, y: Float): Pair<Float, Float> {
        val b = displayBounds()
        if (b.width() <= 0 || b.height() <= 0) return Pair(x, y)
        return Pair(
            x.coerceIn(b.left.toFloat(), b.right.toFloat()),
            y.coerceIn(b.top.toFloat(), b.bottom.toFloat()),
        )
    }

    private class CollectedNodes(
        val snapshots: List<ClickableNodeSnapshot>,
        val nodeById: Map<Int, AccessibilityNodeInfo>,
    )

    /**
     * 扁平化当前活跃窗口的无障碍树：先序分配稳定 id、记录父子关系，
     * 同时保留 id → AccessibilityNodeInfo 映射供命中后执行动作。
     * 采集期间不回收任何节点（调用方用完统一 [recycleCollectedNodes]）。
     */
    private fun collectClickableNodes(): CollectedNodes? {
        val root = rootInActiveWindow ?: return null
        val snapshots = mutableListOf<ClickableNodeSnapshot>()
        val nodeById = HashMap<Int, AccessibilityNodeInfo>()
        try {
            traverseForClick(root, ClickableNodeSnapshot.NO_PARENT, snapshots, nodeById, 0)
        } catch (t: Throwable) {
            // 树遍历异常（极端 ROM 兼容问题）时放弃节点路径，交回手势
            recycleCollectedNodes(nodeById.values)
            return null
        }
        return CollectedNodes(snapshots, nodeById)
    }

    private fun traverseForClick(
        node: AccessibilityNodeInfo,
        parentId: Int,
        snapshots: MutableList<ClickableNodeSnapshot>,
        nodeById: MutableMap<Int, AccessibilityNodeInfo>,
        depth: Int,
    ) {
        if (depth > MAX_CLICK_TREE_DEPTH || snapshots.size >= MAX_CLICK_NODES) return

        val id = snapshots.size
        val rect = Rect()
        node.getBoundsInScreen(rect)
        snapshots.add(
            ClickableNodeSnapshot(
                id = id,
                parentId = parentId,
                bounds = RectBounds(rect.left, rect.top, rect.right, rect.bottom),
                isClickable = runCatching { node.isClickable }.getOrDefault(false),
                isLongClickable = runCatching { node.isLongClickable }.getOrDefault(false),
                visibleToUser = runCatching { node.isVisibleToUser }.getOrDefault(true),
                text = node.text?.toString().orEmpty(),
                contentDescription = node.contentDescription?.toString().orEmpty(),
            ),
        )
        nodeById[id] = node

        for (i in 0 until node.childCount) {
            if (snapshots.size >= MAX_CLICK_NODES) return
            val child = node.getChild(i) ?: continue
            try {
                traverseForClick(child, id, snapshots, nodeById, depth + 1)
            } catch (t: Throwable) {
                // 单个子节点异常不影响其余节点采集
            }
        }
    }

    /** API < 30 时回收采集到的节点，避免实例池泄漏（API 30+ recycle 为 no-op，跳过） */
    @Suppress("DEPRECATION")
    private fun recycleCollectedNodes(nodes: Collection<AccessibilityNodeInfo>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return
        nodes.forEach { runCatching { it.recycle() } }
    }

    suspend fun inputText(text: String, clearBeforeInput: Boolean = false): Boolean = withContext(Dispatchers.Main) {
        if (isStopRequested) return@withContext false
        val root = rootInActiveWindow ?: return@withContext false
        val targetNode = findTargetInputNode(root) ?: return@withContext false

        // 默认直接键入：优先通过无障碍 ACTION_SET_TEXT 直接输入到聊天框/输入框
        if (clearBeforeInput) {
            val emptyBundle = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
            targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, emptyBundle)
        }

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val setOk = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        if (setOk) return@withContext true

        // 直接输入辅助尝试：通过系统剪贴板执行 ACTION_PASTE 直接注入文本
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        if (clipboard != null) {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("agent_paw_direct", text))
            val pasteOk = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            clearClipboardSafely()
            if (pasteOk) return@withContext true
        }

        false
    }

    private fun clearClipboardSafely() {
        runCatching {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            if (clipboard != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    clipboard.clearPrimaryClip()
                } else {
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
                }
            }
        }
    }

    /**
     * 键盘保底逻辑：当直接输入失败时，点击激活输入框软键盘，并借助剪贴板或按键操作保底
     */
    suspend fun keyboardFallbackInput(text: String): Boolean = withContext(Dispatchers.Main) {
        if (isStopRequested) return@withContext false
        val root = rootInActiveWindow ?: return@withContext false
        val targetNode = findTargetInputNode(root) ?: return@withContext false

        // 激活目标输入框焦点以调起软键盘
        targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        kotlinx.coroutines.delay(150)

        // 尝试通过激活软键盘环境后的系统剪贴板进行保底键入
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        if (clipboard != null) {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("agent_paw_keyboard_fallback", text))
            val ok = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            clearClipboardSafely()
            return@withContext ok
        }
        false
    }


    fun actionBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun actionHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun actionRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val deferred = CompletableDeferred<Bitmap?>()
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                Dispatchers.Default.asExecutor(),
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hardwareBuffer = screenshot.hardwareBuffer
                        val colorSpace = screenshot.colorSpace
                        val bitmap = runCatching {
                            Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                        }.getOrNull()
                        hardwareBuffer.close()
                        if (!deferred.complete(bitmap)) {
                            bitmap?.recycle()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        deferred.complete(null)
                    }
                },
            )
            val res = kotlinx.coroutines.withTimeoutOrNull(5000L) {
                deferred.await()
            }
            if (res == null) {
                deferred.cancel()
            }
            res
        } else {
            null
        }
    }

    fun dumpScreenState(): ScreenStateInfo {
        val elements = mutableListOf<UiElementInfo>()
        val root = rootInActiveWindow
        val isTruncated = BooleanArray(1)
        val totalVisited = IntArray(1)
        if (root != null) {
            try {
                traverseNode(root, elements, 0, isTruncated, totalVisited)
            } finally {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    @Suppress("DEPRECATION")
                    root.recycle()
                }
            }
        }
        return ScreenStateInfo(
            foregroundPackage = currentPackage.get(),
            foregroundActivity = currentActivity.get(),
            elements = elements,
            truncated = isTruncated[0],
            totalNodes = totalVisited[0],
        )
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        list: MutableList<UiElementInfo>,
        depth: Int = 0,
        truncated: BooleanArray,
        totalVisited: IntArray,
    ) {
        totalVisited[0]++
        if (depth > 32 || list.size >= 300) {
            truncated[0] = true
            return
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)

        val text = node.text?.toString().orEmpty().trim()
        val desc = node.contentDescription?.toString().orEmpty().trim()
        val viewId = node.viewIdResourceName.orEmpty()
        val isClickable = node.isClickable
        val isEditable = node.isEditable

        if (text.isNotEmpty() || desc.isNotEmpty() || isClickable || isEditable) {
            list.add(
                UiElementInfo(
                    text = text,
                    contentDescription = desc,
                    viewId = viewId,
                    className = node.className?.toString().orEmpty(),
                    isClickable = isClickable,
                    isEditable = isEditable,
                    bounds = RectBounds(rect.left, rect.top, rect.right, rect.bottom),
                ),
            )
        }

        for (i in 0 until node.childCount) {
            if (list.size >= 300) {
                truncated[0] = true
                break
            }
            val child = node.getChild(i) ?: continue
            try {
                traverseNode(child, list, depth + 1, truncated, totalVisited)
            } finally {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    @Suppress("DEPRECATION")
                    child.recycle()
                }
            }
        }
    }

    private fun findTargetInputNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) return focused

        val editable = findEditableNode(root)
        if (editable != null) return editable

        return findChatInputLikeNode(root)
    }

    private fun findChatInputLikeNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val className = node.className?.toString().orEmpty()
        if (className.contains("EditText", ignoreCase = true) || className.contains("TextField", ignoreCase = true)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findChatInputLikeNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNode(child)
            if (found != null) return found
        }
        return null
    }

    /**
     * 派发手势并挂起等待结果。
     *
     * 精度/健壮性细节：
     * 1. 单次重试——dispatchGesture 在「系统正忙（上一个手势未消化完）/ 触摸探索
     *    抢占」时会立即返回 false 或回调 onCancelled，这类瞬时失败重试一次即可救回；
     * 2. 重试间隔与用户停止门禁——每轮 attempt 前都检查 isStopRequested，
     *    保证「停止」在重试窗口内依然立即生效。
     */
    private suspend fun dispatchGestureSuspend(gesture: GestureDescription): Boolean {
        var attempt = 0
        while (attempt < MAX_GESTURE_ATTEMPTS) {
            if (isStopRequested) return false
            val completed = dispatchGestureOnce(gesture)
            if (completed) return !isStopRequested
            attempt++
            if (attempt < MAX_GESTURE_ATTEMPTS) {
                kotlinx.coroutines.delay(GESTURE_RETRY_DELAY_MS)
            }
        }
        return false
    }

    /** 单次派发；返回 true 仅当手势完整执行（onCompleted） */
    private suspend fun dispatchGestureOnce(gesture: GestureDescription): Boolean {
        if (isStopRequested) return false
        val deferred = CompletableDeferred<Boolean>()
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    deferred.complete(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    deferred.complete(false)
                }
            },
            null,
        )
        if (!dispatched) return false
        return deferred.await()
    }

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        val isRunning: Boolean get() = instance != null

        /** 单击注入手势的保持时长（ms）：过短在部分 ROM 上会被吞击 */
        const val TAP_STROKE_DURATION_MS = 60L

        /** 手势瞬时失败（系统忙/被抢占）的重试次数与间隔 */
        const val MAX_GESTURE_ATTEMPTS = 2
        const val GESTURE_RETRY_DELAY_MS = 120L

        /** 节点树采集预算：防超深/超大树把点击拖慢 */
        const val MAX_CLICK_NODES = 500
        const val MAX_CLICK_TREE_DEPTH = 40

        /**
         * 「发送/提交/确认」类语义关键词，用于 pressEnter 兜底时在底部操作带
         * 定位提交按钮（中英文 + 常见变体）。
         */
        val SUBMIT_KEYWORDS = listOf(
            "发送", "提交", "确认", "确定", "完成", "搜索", "前往", "立即", "预约", "保存", "发布",
            "send", "submit", "confirm", "done", "ok", "search", "go", "enter", "next", "apply", "save", "post",
        )

        @Volatile
        private var globalStopRequested: Boolean = false

        /** 类级别读取用户停止标志（无服务实例时也响应全局停止） */
        val isStopRequested: Boolean
            get() = (instance?.isStopRequested ?: false) || globalStopRequested

        fun requestGlobalStop() {
            globalStopRequested = true
            instance?.requestUserStop()
        }

        fun clearGlobalStop() {
            globalStopRequested = false
            instance?.clearUserStop()
        }
    }
}
