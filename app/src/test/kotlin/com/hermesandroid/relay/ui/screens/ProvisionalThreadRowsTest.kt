package com.hermesandroid.relay.ui.screens

import com.hermesandroid.relay.data.ProactiveInboxEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisionalThreadRowsTest {
    @Test
    fun rowsAreConnectionScopedAndDisappearWhenRealThreadExists() {
        val entries = listOf(
            entry("one", connectionId = "connection-a", chatId = "phone"),
            entry("two", connectionId = "connection-b", chatId = "phone"),
            entry("three", connectionId = "connection-a", chatId = "project"),
        )

        val rows = buildProvisionalThreadRows(
            entries = entries,
            activeConnectionId = "connection-a",
            realThreadChatIds = listOf("project"),
        )

        assertEquals(listOf("one"), rows.getValue("phone").map { it.id })
        assertFalse("project" in rows)
    }

    @Test
    fun legacyUnscopedEntriesRemainVisibleOnTheActiveConnection() {
        val rows = buildProvisionalThreadRows(
            entries = listOf(entry("legacy", connectionId = null, chatId = null)),
            activeConnectionId = "connection-a",
            realThreadChatIds = emptyList(),
        )

        assertTrue("phone" in rows)
    }

    @Test
    fun promotedChatIdSuppressesOnlyItsProvisionalRow() {
        val rows = buildProvisionalThreadRows(
            entries = listOf(
                entry("promoted", connectionId = "connection-a", chatId = "reminders"),
                entry("still-local", connectionId = "connection-a", chatId = "updates"),
            ),
            activeConnectionId = "connection-a",
            realThreadChatIds = listOf("reminders"),
        )

        assertFalse("reminders" in rows)
        assertEquals(listOf("still-local"), rows.getValue("updates").map { it.id })
    }

    @Test
    fun staleThreadMappingDoesNotSuppressAProvisionalRow() {
        val liveChatIds = livePhoneThreadChatIds(
            phoneThreadChatIds = mapOf(
                "missing-session" to "morning-summaries",
                "live-session" to "chuck-updates",
            ),
            realPhoneSessionIds = setOf("live-session"),
        )

        val rows = buildProvisionalThreadRows(
            entries = listOf(
                entry("morning", connectionId = "connection-a", chatId = "morning-summaries"),
                entry("updates", connectionId = "connection-a", chatId = "chuck-updates"),
            ),
            activeConnectionId = "connection-a",
            realThreadChatIds = liveChatIds,
        )

        assertTrue("morning-summaries" in rows)
        assertFalse("chuck-updates" in rows)
    }

    private fun entry(id: String, connectionId: String?, chatId: String?) =
        ProactiveInboxEntry(
            id = id,
            title = "Hermes",
            text = id,
            receivedAt = 1L,
            chatId = chatId,
            connectionId = connectionId,
        )
}
