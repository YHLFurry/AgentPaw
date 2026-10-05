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

    suspend fun tap(xNormalized: Int, yNormalized: Int): Boolean
    suspend fun doubleTap(xNormalized: Int, yNormalized: Int): Boolean
    suspend fun longPress(xNormalized: Int, yNormalized: Int, durationMs: Long = 1000L): Boolean
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
