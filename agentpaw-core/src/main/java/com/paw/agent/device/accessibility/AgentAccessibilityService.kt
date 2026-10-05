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
        event.packageName?.let { currentPackage.set(it.toString()) }
        event.className?.let { currentActivity.set(it.toString()) }
    }

    override fun onInterrupt() {
        // Required callback
    }

    suspend fun clickAt(x: Float, y: Float): Boolean = withContext(Dispatchers.Main) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGestureSuspend(gesture)
    }

    suspend fun doubleClickAt(x: Float, y: Float): Boolean = withContext(Dispatchers.Main) {
        val path = Path().apply { moveTo(x, y) }
        val stroke1 = GestureDescription.StrokeDescription(path, 0, 40)
        val stroke2 = GestureDescription.StrokeDescription(path, 100, 40)
        val gesture = GestureDescription.Builder().addStroke(stroke1).addStroke(stroke2).build()
        dispatchGestureSuspend(gesture)
    }

    suspend fun longPressAt(x: Float, y: Float, durationMs: Long = 1000L): Boolean = withContext(Dispatchers.Main) {
        val path = Path().apply { moveTo(x, y) }
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
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(100L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGestureSuspend(gesture)
    }

    suspend fun inputText(text: String, clearBeforeInput: Boolean = false): Boolean = withContext(Dispatchers.Main) {
        val root = rootInActiveWindow ?: return@withContext false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditableNode(root)
        if (focused == null) return@withContext false

        if (clearBeforeInput) {
            val emptyBundle = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, emptyBundle)
        }

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        if (ok) return@withContext true

        // 降级方案：通过系统剪贴板执行 ACTION_PASTE，兼容定制输入控件
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        if (clipboard != null) {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("agent_paw_input", text))
            return@withContext focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
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
                        deferred.complete(bitmap)
                    }

                    override fun onFailure(errorCode: Int) {
                        deferred.complete(null)
                    }
                },
            )
            deferred.await()
        } else {
            null
        }
    }

    fun dumpScreenState(): ScreenStateInfo {
        val elements = mutableListOf<UiElementInfo>()
        val root = rootInActiveWindow
        if (root != null) {
            traverseNode(root, elements)
        }
        return ScreenStateInfo(
            foregroundPackage = currentPackage.get(),
            foregroundActivity = currentActivity.get(),
            elements = elements,
        )
    }

    private fun traverseNode(node: AccessibilityNodeInfo, list: MutableList<UiElementInfo>) {
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
            val child = node.getChild(i) ?: continue
            traverseNode(child, list)
        }
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

    private suspend fun dispatchGestureSuspend(gesture: GestureDescription): Boolean {
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
        return if (!dispatched) false else deferred.await()
    }

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        val isRunning: Boolean get() = instance != null
    }
}
