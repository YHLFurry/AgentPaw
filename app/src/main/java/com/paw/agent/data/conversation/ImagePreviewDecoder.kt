package com.paw.agent.data.conversation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.paw.agent.core.io.BoundedIO
import java.io.File

object ImagePreviewDecoder {
    fun decode(raw: String): Bitmap? = runCatching {
        val bytes = if (raw.startsWith("file://") || File(raw).isFile) {
            File(raw.removePrefix("file://")).inputStream().use { BoundedIO.readBytes(it) }
        } else {
            val payload = raw.substringAfter(',')
            require(payload.length <= BoundedIO.MAX_IMAGE_BYTES * 4 / 3 + 4)
            Base64.decode(payload, Base64.DEFAULT)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..32768 && bounds.outHeight in 1..32768)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1280) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }.getOrNull()
}
