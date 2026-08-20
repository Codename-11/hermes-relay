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
}
