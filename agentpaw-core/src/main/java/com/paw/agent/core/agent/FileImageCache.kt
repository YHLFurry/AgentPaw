package com.paw.agent.core.agent

import com.paw.agent.core.io.BoundedIO
import java.io.File
import java.util.Base64

/** Reuses a local image encoding across agent rounds; metadata changes invalidate it. */
internal object FileImageCache {
    private data class Entry(val path: String, val size: Long, val modified: Long, val dataUrl: String)
    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun dataUrl(file: File): String {
        val key = file.canonicalPath
        val size = file.length()
        val modified = file.lastModified()
        entries.firstOrNull { it.path == key && it.size == size && it.modified == modified }?.let {
            return it.dataUrl
        }
        val bytes = file.inputStream().use { BoundedIO.readBytes(it) }
        val mime = when {
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
                bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "image/png"
            bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() -> "image/jpeg"
            bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() -> "image/bmp"
            else -> error("Unsupported image format")
        }
        val encoded = "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)
        entries.removeAll { it.path == key }
        // Large images are sent once but are not retained in the process cache.
        if (encoded.length <= 1_500_000) {
            entries.addFirst(Entry(key, size, modified, encoded))
            while (entries.size > 2) entries.removeLast()
        }
        return encoded
    }
}
