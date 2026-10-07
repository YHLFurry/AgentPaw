package com.paw.agent.device

import kotlinx.serialization.Serializable

enum class VisionResolutionMode {
    AUTO,
    FAST,
    HIGH,
}

@Serializable
data class ScreenshotResult(
    val base64Data: String,
    val width: Int,
    val height: Int,
    val isDownscaled: Boolean,
    val scaleFactor: Float = 1.0f,
    val estimatedTokens: Int = 300,
    val modeUsed: VisionResolutionMode = VisionResolutionMode.AUTO,
)

@Serializable
data class RectBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

@Serializable
data class UiElementInfo(
    val text: String = "",
    val contentDescription: String = "",
    val viewId: String = "",
    val className: String = "",
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val bounds: RectBounds = RectBounds(0, 0, 0, 0),
)

@Serializable
data class ScreenStateInfo(
    val foregroundPackage: String = "",
    val foregroundActivity: String = "",
    val elements: List<UiElementInfo> = emptyList(),
)

enum class PhoneControlMode {
    AUTO,
    ROOT,
    SHIZUKU,
    ACCESSIBILITY,
}

/**
 * Common seam for controlling Android device actions and observing screen state.
 *
 * Coordinates are normalized to [0, 1000] integers where (0, 0) is top-left and
 * (1000, 1000) is bottom-right. This guarantees that model outputs remain invariant
 * whether the screenshot sent was 720p, 1080p, or cropped.
 */
interface PhoneController {
    val isAccessibilityEnabled: Boolean
    val isShizukuAvailable: Boolean
    val isRootAvailable: Boolean get() = false

    /**
     * Tap at normalized [0,1000] coordinates. `cropRoi` (when non-null, in the same
     * [ymin, xmin, ymax, xmax] 0..1000 form used by `take_screenshot`) re-expands the
     * coordinates back to the full screen, so a click derived from a cropped screenshot
     * lands on the correct spot instead of the screen center.
     */
    suspend fun tap(xNormalized: Int, yNormalized: Int, cropRoi: List<Int>? = null): Boolean
    suspend fun doubleTap(xNormalized: Int, yNormalized: Int, cropRoi: List<Int>? = null): Boolean
    suspend fun longPress(xNormalized: Int, yNormalized: Int, durationMs: Long = 1000L, cropRoi: List<Int>? = null): Boolean

    /**
     * Tap at an exact point given in **real screen pixels** (the same coordinate space
     * used by `get_screen_state` bounds and by `dispatchGesture` / `input tap`).
     *
     * This bypasses the normalized [0,1000] round-trip entirely, so it is the most
     * accurate way to click a button the accessibility tree already located — no
     * resolution assumptions, no VLM coordinate guessing.
     */
    suspend fun tapAtPixel(centerX: Float, centerY: Float): Boolean
    suspend fun swipe(
        startXNormalized: Int,
        startYNormalized: Int,
        endXNormalized: Int,
        endYNormalized: Int,
        durationMs: Long = 350L,
    ): Boolean
    suspend fun inputText(text: String, clearBeforeInput: Boolean = false): Boolean
    suspend fun pressBack(): Boolean
    suspend fun pressHome(): Boolean
    suspend fun pressRecents(): Boolean
    suspend fun pressEnter(): Boolean
    suspend fun launchApp(packageNameOrName: String): Boolean
    suspend fun openDeepLink(uri: String): Boolean
    suspend fun takeScreenshot(
        mode: VisionResolutionMode = VisionResolutionMode.AUTO,
        cropRoi: List<Int>? = null,
    ): ScreenshotResult?
    suspend fun getScreenState(): ScreenStateInfo
}
