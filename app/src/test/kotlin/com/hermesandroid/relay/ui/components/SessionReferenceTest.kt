package com.hermesandroid.relay.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionReferenceTest {
    @Test
    fun `references outside code are bounded and parsed`() {
        val text = """
            Open @session:research/20260811_abc123.
            `@session:hidden/inline`
            ```text
            @session:hidden/fenced
            ```
            Also @session:default/session-2
        """.trimIndent()

        assertEquals(
            listOf(
                SessionReference("research", "20260811_abc123"),
                SessionReference("default", "session-2"),
            ),
            parseSessionReferences(text),
        )
    }
}
