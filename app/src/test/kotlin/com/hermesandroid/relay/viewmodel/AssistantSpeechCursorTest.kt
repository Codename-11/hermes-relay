package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantSpeechCursorTest {
    @Test
    fun `speaks every assistant bubble created during one tool run`() {
        val history = listOf(message("old", MessageRole.ASSISTANT, "Previous answer."))
        val cursor = AssistantSpeechCursor(history)

        val interim = message(
            id = "interim",
            role = MessageRole.ASSISTANT,
            content = "I'll check that.",
            streaming = false,
        )
        val first = cursor.poll(history + interim)
        assertEquals(listOf("I'll check that."), first.deltas.map { it.text })
        assertTrue(first.hasTurnAssistant)

        val final = message(
            id = "final",
            role = MessageRole.ASSISTANT,
            content = "The check is complete.",
            streaming = false,
        )
        val second = cursor.poll(history + interim + final)
        assertEquals(listOf("The check is complete."), second.deltas.map { it.text })
        assertTrue(second.deltas.single().startsNewBubble)
        assertEquals("I'll check that.\n\nThe check is complete.", second.aggregateText)
    }

    @Test
    fun `history id adoption preserves stable ui identity without replay`() {
        val live = message(
            id = "client-id",
            role = MessageRole.ASSISTANT,
            content = "Already spoken.",
            uiKey = "stable-bubble",
        )
        val cursor = AssistantSpeechCursor(listOf(live))
        val reconciled = live.copy(id = "server-id", uiKey = "stable-bubble")

        val batch = cursor.poll(listOf(reconciled))

        assertTrue(batch.deltas.isEmpty())
        assertFalse(batch.hasTurnAssistant)
    }

    @Test
    fun `new bubble requests a speech boundary without trailing punctuation`() {
        val cursor = AssistantSpeechCursor(emptyList())
        val interim = message("interim", MessageRole.ASSISTANT, "Let me check")
        val final = message("final", MessageRole.ASSISTANT, "Done.")

        val first = cursor.poll(listOf(interim))
        val second = cursor.poll(listOf(interim, final))

        assertFalse(first.deltas.single().startsNewBubble)
        assertTrue(second.deltas.single().startsNewBubble)
        assertEquals("Done.", second.deltas.single().text)
    }

    @Test
    fun `baseline history and repeated emissions are never replayed`() {
        val history = listOf(message("old", MessageRole.ASSISTANT, "Previous answer."))
        val cursor = AssistantSpeechCursor(history)

        assertTrue(cursor.poll(history).deltas.isEmpty())
        assertFalse(cursor.poll(history).hasTurnAssistant)

        val current = history + message("new", MessageRole.ASSISTANT, "Fresh reply.")
        assertEquals(listOf("Fresh reply."), cursor.poll(current).deltas.map { it.text })
        assertTrue(cursor.poll(current).deltas.isEmpty())
    }

    @Test
    fun `only strict suffix growth is spoken after transcript reconciliation`() {
        val cursor = AssistantSpeechCursor(emptyList())

        cursor.poll(listOf(message("answer", MessageRole.ASSISTANT, "Working on")))
        val grown = cursor.poll(
            listOf(message("answer", MessageRole.ASSISTANT, "Working on it now.")),
        )
        assertEquals(listOf(" it now."), grown.deltas.map { it.text })

        val rewritten = cursor.poll(
            listOf(message("answer", MessageRole.ASSISTANT, "Done.")),
        )
        assertTrue(rewritten.deltas.isEmpty())
        assertEquals("Done.", rewritten.aggregateText)
    }

    private fun message(
        id: String,
        role: MessageRole,
        content: String,
        streaming: Boolean = false,
        uiKey: String = id,
    ) = ChatMessage(
        id = id,
        role = role,
        content = content,
        timestamp = 1L,
        isStreaming = streaming,
        uiKey = uiKey,
    )
}
