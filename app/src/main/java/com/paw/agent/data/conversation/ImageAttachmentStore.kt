package com.paw.agent.data.conversation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import com.paw.agent.core.io.BoundedIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Imports only user-selected images. Decoding and metadata removal happen off the UI thread. */
class ImageAttachmentStore(private val context: Context) {
    fun discardImage(reference: String) {
        if (!reference.startsWith("file://")) return
        val file = File(reference.removePrefix("file://")).canonicalFile
        val directory = File(context.filesDir, "conversations/images").canonicalFile
        if (file.parentFile == directory && file.name.contains("_upload_") && file.isFile) file.delete()
    }

    suspend fun importImage(uri: String, conversationId: String): String = withContext(Dispatchers.IO) {
        require(Regex("[A-Za-z0-9_-]{1,128}").matches(conversationId))
        val bytes = context.contentResolver.openInputStream(Uri.parse(uri))?.use { BoundedIO.readBytes(it) }
            ?: error("Cannot open image")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..32768 && bounds.outHeight in 1..32768) { "Invalid image dimensions" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Unsupported image")
        var bitmap = decoded
        try {
            val orientation = runCatching {
                ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                }
            }
            if (!matrix.isIdentity) bitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            val output = ByteArrayOutputStream()
            for (quality in listOf(85, 70, 55, 40)) {
                coroutineContext.ensureActive()
                output.reset()
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output))
                if (output.size() <= 1024 * 1024) break
            }
            require(output.size() <= 1024 * 1024) { "Image cannot be compressed within budget" }
            val directory = File(context.filesDir, "conversations/images").apply { mkdirs() }
            val target = File(directory, "${conversationId}_upload_${UUID.randomUUID()}.jpg")
            coroutineContext.ensureActive()
            target.outputStream().use { output.writeTo(it) }
            "file://${target.absolutePath}"
        } finally {
            if (bitmap !== decoded) bitmap.recycle()
            decoded.recycle()
        }
    }
}
