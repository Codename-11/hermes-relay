package com.hermesandroid.relay.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayBackgroundProcessesTest {
    @Test
    fun `elapsed time stays compact from seconds through hours`() {
        assertEquals("0s", formatElapsed(-5))
        assertEquals("42s", formatElapsed(42))
        assertEquals("2m 5s", formatElapsed(125))
        assertEquals("1h 2m", formatElapsed(3_725))
    }

    @Test
    fun `terminal output strips ansi control and normalizes progress carriage returns`() {
        val raw =
            "\u001B[31mFAIL\u001B[0m\r50%\r100%\u001B]0;secret title\u0007\n" +
                "ok\t!\u0000"

        assertEquals("FAIL\n50%\n100%\nok\t!", sanitizeTerminalText(raw))
    }
}
