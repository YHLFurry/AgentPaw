package com.paw.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paw.agent.data.conversation.ImageAttachmentStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ImageAttachmentInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun exifOrientationIsAppliedAndGpsMetadataIsRemoved() = runBlocking {
        val source = File(context.cacheDir, "oriented-${UUID.randomUUID()}.jpg")
        val bitmap = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        var target: File? = null
        try {
            // Build an independent EXIF fixture: Android 8's platform writer rejects
            // some freshly encoded JPEGs before the import code can be exercised.
            val tiff = ByteBuffer.allocate(92).order(ByteOrder.LITTLE_ENDIAN).apply {
                put(0x49.toByte()); put(0x49.toByte()); putShort(42); putInt(8)
                putShort(2)
                putShort(0x0112); putShort(3); putInt(1); putShort(6); putShort(0)
                putShort(0x8825.toShort()); putShort(4); putInt(1); putInt(38)
                putInt(0)
                putShort(2)
                putShort(1); putShort(2); putInt(2); putInt('N'.code)
                putShort(2); putShort(5); putInt(3); putInt(68)
                putInt(0)
                putInt(37); putInt(1); putInt(0); putInt(1); putInt(0); putInt(1)
            }.array()
            val jpeg = source.readBytes()
            source.outputStream().use {
                it.write(jpeg, 0, 2)
                it.write(byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0, 100))
                it.write(byteArrayOf(69, 120, 105, 102, 0, 0)) // Exif header
                it.write(tiff)
                it.write(jpeg, 2, jpeg.size - 2)
            }
            val originalExif = ExifInterface(source.absolutePath)
            assertEquals(6, originalExif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
            org.junit.Assert.assertNotNull(originalExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            target = File(ImageAttachmentStore(context).importImage(Uri.fromFile(source).toString(), "instrumented").removePrefix("file://"))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, bounds)
            assertEquals(10, bounds.outWidth)
            assertEquals(20, bounds.outHeight)
            org.junit.Assert.assertNull(ExifInterface(target.absolutePath).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        } finally { source.delete(); target?.delete() }
    }

    @Test fun importsAndDownsamplesAnImageIntoPrivateStorage() = runBlocking {
        val source = File(context.cacheDir, "image-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(8192, 8, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        var target: File? = null
        try {
            val image = ImageAttachmentStore(context).importImage(Uri.fromFile(source).toString(), "instrumented")
            target = File(image.removePrefix("file://"))
            assertTrue(target.canonicalPath.startsWith(File(context.filesDir, "conversations/images").canonicalPath + File.separator))
            assertTrue(target.length() in 1..1024 * 1024)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, bounds)
            assertTrue(bounds.outWidth <= 2048)
            assertEquals("image/jpeg", bounds.outMimeType)
        } finally { source.delete(); target?.delete() }
    }

    @Test fun oversizedOrInvalidAttachmentsAreRejected() = runBlocking {
        val source = File(context.cacheDir, "invalid-${UUID.randomUUID()}.bin")
        try {
            source.writeText("not an image")
            assertTrue(runCatching { ImageAttachmentStore(context).importImage(Uri.fromFile(source).toString(), "instrumented") }.isFailure)
            RandomAccessFile(source, "rw").use { it.setLength(10L * 1024 * 1024 + 1) }
            assertTrue(runCatching { ImageAttachmentStore(context).importImage(Uri.fromFile(source).toString(), "instrumented") }.isFailure)
        } finally { source.delete() }
    }
}
