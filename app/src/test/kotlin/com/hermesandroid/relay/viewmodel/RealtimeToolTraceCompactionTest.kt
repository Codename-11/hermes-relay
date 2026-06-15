package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.ui.components.compactToolDetail
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeToolTraceCompactionTest {
    @Test
    fun `realtime Hermes tool previews are capped for chat`() {
        val raw = """{"output": "${"line ".repeat(400)}"}"""

        val compact = compactRealtimeToolResultPreview(raw, maxChars = 120)

        assertTrue(compact.length <= 120)
        assertTrue(compact.startsWith("Command output returned"))
        assertFalse(compact.contains("line line line"))
    }

    @Test
    fun `realtime Hermes skill previews show clean provenance`() {
        val raw = """{"success":true,"name":"hermes-update","description":"Update Hermes agent and workspace from upstream"}"""

        val compact = compactRealtimeToolResultPreview(raw, maxChars = 160)

        assertTrue(compact.contains("Loaded hermes-update"))
        assertFalse(compact.contains("\"success\""))
    }

    @Test
    fun `tool cards normalize whitespace before rendering preview`() {
        val raw = "alpha\n\n    beta\tgamma"

        val compact = compactToolDetail(raw, maxChars = 80)

        assertFalse(compact.contains("\n"))
        assertTrue(compact.contains("alpha beta gamma"))
    }
}
