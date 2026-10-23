package com.paw.agent.core.io

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.StringReader

class BoundedIOTest {
    @Test fun `byte budget rejects input before keeping an oversized payload`() {
        assertArrayEquals(byteArrayOf(1, 2), BoundedIO.readBytes(ByteArrayInputStream(byteArrayOf(1, 2)), 2))
        val failure = runCatching { BoundedIO.readBytes(ByteArrayInputStream(ByteArray(8192)), 32) }.exceptionOrNull()
        assertTrue(failure is IOException)
    }
    @Test fun `text budget includes the whole stream and handles an exact boundary`() {
        assertEquals("abc", BoundedIO.readText(StringReader("abc"), 3))
        assertTrue(runCatching { BoundedIO.readText(StringReader("abcd"), 3) }.exceptionOrNull() is IOException)
    }
}
