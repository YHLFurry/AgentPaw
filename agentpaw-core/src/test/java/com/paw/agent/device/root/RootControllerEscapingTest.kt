package com.paw.agent.device.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootControllerEscapingTest {

    @Test
    fun `escapeForInputText properly escapes single quotes and spaces`() {
        val input = "it's a test"
        val escaped = RootController.escapeForInputText(input)
        assertEquals("it'\\''s%sa%stest", escaped)

        val multipleQuotes = "a'b'c"
        assertEquals("a'\\''b'\\''c", RootController.escapeForInputText(multipleQuotes))
    }

    @Test
    fun `hasNonAscii detects Chinese characters and emojis`() {
        assertTrue(RootController.hasNonAscii("你好世界"))
        assertTrue(RootController.hasNonAscii("hello 😊"))
        assertTrue(RootController.hasNonAscii("微信"))

        assertFalse(RootController.hasNonAscii("hello world"))
        assertFalse(RootController.hasNonAscii("12345!@#$%^&*()"))
        assertFalse(RootController.hasNonAscii("input text 'test'"))
    }
}
