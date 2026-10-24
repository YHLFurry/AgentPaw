package com.paw.agent.core.agent

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FileImageCacheTest {
    @Test fun `detects actual image type even when filename extension is wrong`() {
        val file = File.createTempFile("image-cache", ".jpg")
        try {
            file.writeBytes(byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10))
            assertTrue(FileImageCache.dataUrl(file).startsWith("data:image/png;base64,"))
        } finally {
            file.delete()
        }
    }

    @Test fun `rejects files that only pretend to be images`() {
        val file = File.createTempFile("image-cache", ".jpg")
        try {
            file.writeText("secret data")
            assertTrue(runCatching { FileImageCache.dataUrl(file) }.isFailure)
        } finally {
            file.delete()
        }
    }
}
