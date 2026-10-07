package com.paw.agent.device

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Intelligent adaptive screenshot processor designed to optimize VLM inference speed,
 * latency, and token consumption on mobile devices.
 *
 * Capabilities:
 * 1. FAST Mode: Downscales to ~720p long-edge, saving up to 80% token costs and ~1-3s latency per round.
 * 2. HIGH Mode: Scales to 1080p for dense text, small icons, or explicit verification.
 * 3. Crop ROI: Crops targeted region [ymin, xmin, ymax, xmax] at native resolution with minimal token count.
 * 4. Coordinate Normalization: Maps between physical pixels and [0, 1000] coordinate grid.
 */
class AdaptiveScreenshotProcessor {

    fun processScreenshot(
        original: Bitmap,
        mode: VisionResolutionMode = VisionResolutionMode.AUTO,
        cropRoi: List<Int>? = null,
        maxByteSize: Int = DEFAULT_MAX_IMAGE_BYTES,
    ): ScreenshotResult {
        val origWidth = original.width
        val origHeight = original.height

        // 1. Check ROI crop first if specified by model (e.g. [ymin, xmin, ymax, xmax] in 0..1000)
        val workingBitmap: Bitmap
        val isCropped: Boolean
        if (cropRoi != null && cropRoi.size == 4) {
            val ymin = ((cropRoi[0] / 1000f) * origHeight).roundToInt().coerceIn(0, origHeight - 1)
            val xmin = ((cropRoi[1] / 1000f) * origWidth).roundToInt().coerceIn(0, origWidth - 1)
            val ymax = ((cropRoi[2] / 1000f) * origHeight).roundToInt().coerceIn(ymin + 1, origHeight)
            val xmax = ((cropRoi[3] / 1000f) * origWidth).roundToInt().coerceIn(xmin + 1, origWidth)

            val cropWidth = max(1, xmax - xmin)
            val cropHeight = max(1, ymax - ymin)

            workingBitmap = Bitmap.createBitmap(original, xmin, ymin, cropWidth, cropHeight)
            isCropped = true
        } else {
            workingBitmap = original
            isCropped = false
        }

        // 2. Determine target long-edge dimension and quality
        val maxDimension = max(workingBitmap.width, workingBitmap.height)
        val (targetMaxDim, quality) = when (mode) {
            VisionResolutionMode.FAST -> Pair(720, 65)
            VisionResolutionMode.HIGH -> Pair(1080, 82)
            VisionResolutionMode.AUTO -> {
                // In auto mode, if cropped or already small, preserve size; otherwise use fast 720p
                if (isCropped || maxDimension <= 800) Pair(maxDimension, 75) else Pair(720, 68)
            }
        }

        val scale = if (maxDimension > targetMaxDim) {
            targetMaxDim.toFloat() / maxDimension.toFloat()
        } else {
            1.0f
        }

        val finalBitmap = if (scale < 1.0f) {
            val newW = (workingBitmap.width * scale).roundToInt().coerceAtLeast(1)
            val newH = (workingBitmap.height * scale).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(workingBitmap, newW, newH, true)
        } else {
            workingBitmap
        }

        // 3. Compress to JPEG with size cap enforcement
        var currentBitmap = finalBitmap
        var currentQuality = quality
        var outputStream = ByteArrayOutputStream()
        currentBitmap.compress(Bitmap.CompressFormat.JPEG, currentQuality, outputStream)
        var bytes = outputStream.toByteArray()

        var iterations = 0
        while (bytes.size > maxByteSize && iterations < 5) {
            iterations++
            if (currentQuality > 40) {
                currentQuality = (currentQuality - 15).coerceAtLeast(30)
            } else {
                val nextW = (currentBitmap.width * 0.8f).roundToInt().coerceAtLeast(240)
                val nextH = (currentBitmap.height * 0.8f).roundToInt().coerceAtLeast(240)
                val scaled = Bitmap.createScaledBitmap(currentBitmap, nextW, nextH, true)
                if (currentBitmap != finalBitmap && currentBitmap != workingBitmap && currentBitmap != original) {
                    currentBitmap.recycle()
                }
                currentBitmap = scaled
            }
            outputStream = ByteArrayOutputStream()
            currentBitmap.compress(Bitmap.CompressFormat.JPEG, currentQuality, outputStream)
            bytes = outputStream.toByteArray()
        }

        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

        // Estimated tokens based on OpenAI / VLM tiled token formula (~85 tokens base + ~170 per 512x512 tile)
        val tiles = max(1, (currentBitmap.width + 511) / 512 * ((currentBitmap.height + 511) / 512))
        val estimatedTokens = 85 + tiles * 170

        if (currentBitmap != finalBitmap && currentBitmap != workingBitmap && currentBitmap != original) {
            currentBitmap.recycle()
        }
        if (finalBitmap != workingBitmap && finalBitmap != original) {
            finalBitmap.recycle()
        }
        if (workingBitmap != original) {
            workingBitmap.recycle()
        }

        return ScreenshotResult(
            base64Data = base64,
            width = origWidth,
            height = origHeight,
            isDownscaled = scale < 1.0f || isCropped || iterations > 0,
            scaleFactor = scale,
            estimatedTokens = estimatedTokens,
            modeUsed = mode,
        )
    }

    companion object {
        const val DEFAULT_MAX_IMAGE_BYTES: Int = 384 * 1024 // 384KB byte limit per screenshot

        fun toPhysicalX(xNormalized: Int, screenWidth: Int): Float =
            ((xNormalized.coerceIn(0, 1000) / 1000f) * screenWidth)

        fun toPhysicalY(yNormalized: Int, screenHeight: Int): Float =
            ((yNormalized.coerceIn(0, 1000) / 1000f) * screenHeight)

        fun toNormalizedX(physicalX: Float, screenWidth: Int): Int =
            ((physicalX / screenWidth.coerceAtLeast(1)) * 1000).roundToInt().coerceIn(0, 1000)

        fun toNormalizedY(physicalY: Float, screenHeight: Int): Int =
            ((physicalY / screenHeight.coerceAtLeast(1)) * 1000).roundToInt().coerceIn(0, 1000)
    }
}
