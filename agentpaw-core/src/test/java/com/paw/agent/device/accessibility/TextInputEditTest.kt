package com.paw.agent.device.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class TextInputEditTest {
    @Test fun `append preserves existing text when selection is unavailable`() {
        assertEquals(TextInputEdit("hello世界", 7), TextInputEdit.resolve("hello", "世界", -1, -1, false))
    }

    @Test fun `typing replaces selected range and restores insertion cursor`() {
        assertEquals(TextInputEdit("a新文本d", 4), TextInputEdit.resolve("abcd", "新文本", 1, 3, false))
        assertEquals(TextInputEdit("aXd", 2), TextInputEdit.resolve("abcd", "X", 3, 1, false))
    }

    @Test fun `insert handles emoji utf16 cursor offsets`() {
        assertEquals(TextInputEdit("A🐾中B", 4), TextInputEdit.resolve("A🐾B", "中", 3, 3, false))
    }

    @Test fun `clear replaces whole field in one operation`() {
        assertEquals(TextInputEdit("new", 3), TextInputEdit.resolve("old content", "new", 2, 4, true))
        assertEquals(TextInputEdit("", 0), TextInputEdit.resolve("old", "", -1, -1, true))
    }
}
