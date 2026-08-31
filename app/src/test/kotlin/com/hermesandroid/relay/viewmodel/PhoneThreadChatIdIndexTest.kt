package com.hermesandroid.relay.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneThreadChatIdIndexTest {
    @Test
    fun `index is visible only to its owning connection`() {
        val index = PhoneThreadChatIdIndex(
            connectionId = "connection-a",
            values = mapOf("session-a" to "reminders"),
        )

        assertEquals(index.values, visiblePhoneThreadChatIds("connection-a", index))
        assertEquals(emptyMap<String, String>(), visiblePhoneThreadChatIds("connection-b", index))
        assertEquals(emptyMap<String, String>(), visiblePhoneThreadChatIds(null, index))
    }

    @Test
    fun `a later failed refresh preserves the last successful index`() {
        val successful = reconcilePhoneThreadChatIdIndex(
            current = PhoneThreadChatIdIndex(),
            requestedConnectionId = "connection-a",
            activeConnectionId = "connection-a",
            fetched = Result.success(mapOf("session-a" to "reminders")),
        )

        val afterFailure = reconcilePhoneThreadChatIdIndex(
            current = successful,
            requestedConnectionId = "connection-a",
            activeConnectionId = "connection-a",
            fetched = Result.failure(IllegalStateException("offline")),
        )

        assertEquals(successful, afterFailure)
    }
}
