package com.paw.agent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellEscapeTest {

    @Test
    fun `isPrintableAscii validates ascii range correctly`() {
        assertTrue(ShellEscape.isPrintableAscii("Hello World 123!@#$%^&*()_+-=[]{}|;:,.<>?/`~'\""))
        assertFalse("Newlines should be rejected", ShellEscape.isPrintableAscii("Hello\nWorld"))
        assertFalse("Tabs should be rejected", ShellEscape.isPrintableAscii("Hello\tWorld"))
        assertFalse("Carriage return should be rejected", ShellEscape.isPrintableAscii("Hello\rWorld"))
        assertFalse("Unicode/Chinese should be rejected", ShellEscape.isPrintableAscii("你好世界"))
        assertFalse("Emojis should be rejected", ShellEscape.isPrintableAscii("Hello 😊"))
    }

    @Test
    fun `escapeForInputText handles single quotes, spaces, and percent signs`() {
        val input = "It's 100% guaranteed!"
        val escaped = ShellEscape.escapeForInputText(input)
        assertEquals("It'\\''s%s100%%%sguaranteed!", escaped)
    }

    @Test
    fun `buildInputTextCommand wraps properly in single quotes`() {
        val line = "echo 'hello'"
        val command = ShellEscape.buildInputTextCommand(line)
        assertEquals("input text 'echo%s'\\''hello'\\'''", command)
    }
}
