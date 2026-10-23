package com.paw.agent.core.io

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.Reader

/** Limits apply while reading, before a large allocation can occur. */
object BoundedIO {
    const val MAX_TEXT_CHARS = 1024 * 1024
    const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
    const val MAX_CONVERSATION_BYTES = 16 * 1024 * 1024

    fun readText(reader: Reader, maxChars: Int = MAX_TEXT_CHARS): String {
        require(maxChars >= 0)
        val result = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("read cancelled")
            val count = reader.read(buffer, 0, minOf(buffer.size, maxChars - result.length + 1))
            if (count < 0) break
            if (result.length + count > maxChars) throw IOException("Text exceeds $maxChars characters")
            result.append(buffer, 0, count)
        }
        return result.toString()
    }

    fun readBytes(input: InputStream, maxBytes: Int = MAX_IMAGE_BYTES): ByteArray {
        require(maxBytes >= 0)
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("read cancelled")
            val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - result.size() + 1))
            if (count < 0) break
            if (result.size() + count > maxBytes) throw IOException("Data exceeds $maxBytes bytes")
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }

    fun readFile(file: File, maxBytes: Int = MAX_CONVERSATION_BYTES): String =
        file.inputStream().use { readBytes(it, maxBytes).toString(Charsets.UTF_8) }
}
