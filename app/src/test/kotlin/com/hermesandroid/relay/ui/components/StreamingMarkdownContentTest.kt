package com.hermesandroid.relay.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingMarkdownContentTest {
    @Test
    fun liveText_discardsOnlyLeadingBlankLines() {
        assertEquals(
            "First line\n\nSecond line",
            "\n\r\n  \t\nFirst line\n\nSecond line".withoutLeadingBlankLines(),
        )
        assertEquals(
            "    indented code",
            "\n\n    indented code".withoutLeadingBlankLines(),
        )
    }
}
