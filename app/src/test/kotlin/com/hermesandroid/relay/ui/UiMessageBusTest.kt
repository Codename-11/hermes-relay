package com.hermesandroid.relay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiMessageBusTest {
    @Test
    fun keyedShowReplacesInPlaceAndClearRemovesOnlyExactOwner() {
        val unrelated = message(1, "Network ready", key = "network")
        val first = message(2, "Half used", key = "credits.usage")
        val replacement = message(3, "Almost used", key = "credits.usage")

        val replaced = reduceUiMessages(
            listOf(unrelated, first),
            UiMessageEvent.Show(replacement),
            maxRetained = 6,
        )
        val cleared = reduceUiMessages(
            replaced,
            UiMessageEvent.Clear("credits.usage"),
            maxRetained = 6,
        )

        assertEquals(listOf(unrelated, replacement), replaced)
        assertEquals(listOf(unrelated), cleared)
    }

    @Test
    fun unkeyedMessagesStillCoalesceByTextAndRespectBound() {
        var state = emptyList<UiMessage>()
        repeat(8) { index ->
            state = reduceUiMessages(
                state,
                UiMessageEvent.Show(message(index.toLong(), "message-$index")),
                maxRetained = 6,
            )
        }
        state = reduceUiMessages(
            state,
            UiMessageEvent.Show(message(99, "message-7")),
            maxRetained = 6,
        )

        assertEquals(6, state.size)
        assertEquals(99L, state.last().id)
        assertEquals(1, state.count { it.text == "message-7" })
        assertTrue(state.none { it.text == "message-0" })
    }

    private fun message(id: Long, text: String, key: String? = null) = UiMessage(
        id = id,
        text = text,
        severity = UiMessageSeverity.Info,
        ttlMillis = UiMessageBus.DEFAULT_TTL_MS,
        key = key,
    )
}
