package com.hermesandroid.relay.ui.screens

import com.hermesandroid.relay.data.SessionActivityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SessionActivityStateTest {
    @Test
    fun `multiple background turns remain visible beside current working turn`() {
        val resolved = resolveSessionActivityStates(
            background = mapOf(
                "session-a" to SessionActivityState.Working,
                "session-b" to SessionActivityState.NeedsInput,
            ),
            currentSessionId = "session-c",
            isStreaming = true,
            needsInput = false,
        )

        assertEquals(SessionActivityState.Working, resolved["session-a"])
        assertEquals(SessionActivityState.NeedsInput, resolved["session-b"])
        assertEquals(SessionActivityState.Working, resolved["session-c"])
    }

    @Test
    fun `needs input takes precedence over current working state`() {
        val resolved = resolveSessionActivityStates(
            background = emptyMap(),
            currentSessionId = "session-a",
            isStreaming = true,
            needsInput = true,
        )

        assertEquals(SessionActivityState.NeedsInput, resolved["session-a"])
    }

    @Test
    fun `selected idle session does not retain a stale background state`() {
        val resolved = resolveSessionActivityStates(
            background = mapOf("session-a" to SessionActivityState.Working),
            currentSessionId = "session-a",
            isStreaming = false,
            needsInput = false,
        )

        assertFalse(resolved.containsKey("session-a"))
    }
}
