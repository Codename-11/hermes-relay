package com.hermesandroid.relay.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatFailurePanelTest {
    @Test
    fun `identity includes only confidently known fields`() {
        assertEquals("Gateway · nous · agnes-2", failureIdentity("Gateway", "agnes-2", "nous"))
        assertEquals("Gateway", failureIdentity("Gateway", null, null))
        assertEquals("", failureIdentity("", null, null))
    }

    @Test
    fun `inference initialization errors get a specific presentation`() {
        assertEquals(
            true,
            isInferenceUnavailableFailure(
                "agent init failed: No Codex credentials stored. " +
                    "setup.status reports configured credentials, but runtime resolution still failed.",
            ),
        )
        assertEquals(false, isInferenceUnavailableFailure("gateway connect cooling down"))
    }
}
