package com.hermesandroid.relay.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionSourceBadgeTest {
    @Test
    fun `dashboard webui sessions are treated as own chats`() {
        assertNull(sourceBadge("webui"))
        assertNull(sourceBadge(" WebUI "))
    }

    @Test
    fun `browser web sessions retain their source badge`() {
        assertEquals("Web", sourceBadge("web")?.label)
    }
}
