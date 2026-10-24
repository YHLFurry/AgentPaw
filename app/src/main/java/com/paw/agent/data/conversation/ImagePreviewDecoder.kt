package com.paw.agent.data.conversation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.paw.agent.core.io.BoundedIO
import java.io.File

object ImagePreviewDecoder {
    private val previewCache = LinkedHashMap<String, Bitmap>(8, 0.75f, true)
    private var cacheGeneration = 0L
    fun clearCache() = synchronized(previewCache) { cacheGeneration++; previewCache.clear() }

    fun decode(raw: String): Bitmap? = runCatching {
        val generation = synchronized(previewCache) { cacheGeneration }
        val file = if (raw.startsWith("file://")) File(raw.removePrefix("file://"))
            else File(raw).takeIf { it.isFile }
        val cacheKey = file?.takeIf { it.isFile }?.let { "${it.absolutePath}:${it.length()}:${it.lastModified()}" }
        cacheKey?.let { key -> synchronized(previewCache) { previewCache.get(key) } }?.let { return@runCatching it }
        val bytes = if (file != null) {
            file.inputStream().use { BoundedIO.readBytes(it) }
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
        })?.also { bitmap ->
            cacheKey?.let { key -> synchronized(previewCache) {
                if (generation == cacheGeneration) previewCache[key] = bitmap
                while (previewCache.values.sumOf { it.byteCount.toLong() } > 12L * 1024 * 1024) {
                    previewCache.remove(previewCache.keys.first())
                }
            } }
        }
    }.getOrNull()
}
