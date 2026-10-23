package com.paw.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
            android.media.ExifInterface(source.absolutePath).apply {
                setAttribute(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE, "37/1,0/1,0/1")
                setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                saveAttributes()
            }
            target = File(ImageAttachmentStore(context).importImage(Uri.fromFile(source).toString(), "instrumented").removePrefix("file://"))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, bounds)
            assertEquals(10, bounds.outWidth)
            assertEquals(20, bounds.outHeight)
            org.junit.Assert.assertNull(android.media.ExifInterface(target.absolutePath).getAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE))
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
