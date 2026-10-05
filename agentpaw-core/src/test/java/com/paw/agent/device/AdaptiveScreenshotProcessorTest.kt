package com.paw.agent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveScreenshotProcessorTest {

    @Test
    fun `coordinate mapping between normalized and physical coordinates is accurate`() {
        val screenW = 1080
        val screenH = 2400

        // Center point
        val centerXPhys = AdaptiveScreenshotProcessor.toPhysicalX(500, screenW)
        val centerYPhys = AdaptiveScreenshotProcessor.toPhysicalY(500, screenH)
        assertEquals(540f, centerXPhys, 0.01f)
        assertEquals(1200f, centerYPhys, 0.01f)

        // Map back to normalized
        val centerNormX = AdaptiveScreenshotProcessor.toNormalizedX(centerXPhys, screenW)
        val centerNormY = AdaptiveScreenshotProcessor.toNormalizedY(centerYPhys, screenH)
        assertEquals(500, centerNormX)
        assertEquals(500, centerNormY)

        // Top-left
        assertEquals(0f, AdaptiveScreenshotProcessor.toPhysicalX(0, screenW), 0.01f)
        assertEquals(0f, AdaptiveScreenshotProcessor.toPhysicalY(0, screenH), 0.01f)

        // Bottom-right
        assertEquals(1080f, AdaptiveScreenshotProcessor.toPhysicalX(1000, screenW), 0.01f)
        assertEquals(2400f, AdaptiveScreenshotProcessor.toPhysicalY(1000, screenH), 0.01f)

        // Clamping out-of-range
        assertEquals(1080f, AdaptiveScreenshotProcessor.toPhysicalX(1200, screenW), 0.01f)
        assertEquals(0f, AdaptiveScreenshotProcessor.toPhysicalX(-50, screenW), 0.01f)
    }

    @Test
    fun `vision resolution modes are properly defined`() {
        assertEquals(3, VisionResolutionMode.entries.size)
        assertTrue(VisionResolutionMode.entries.contains(VisionResolutionMode.AUTO))
        assertTrue(VisionResolutionMode.entries.contains(VisionResolutionMode.FAST))
        assertTrue(VisionResolutionMode.entries.contains(VisionResolutionMode.HIGH))
    }
}
