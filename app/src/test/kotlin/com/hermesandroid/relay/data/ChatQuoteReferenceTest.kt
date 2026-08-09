package com.hermesandroid.relay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatQuoteReferenceTest {
    @Test
    fun `quote envelope round trips without changing authored body`() {
        val reference = ChatQuoteReference(
            messageId = "message/id with spaces",
            authorLabel = "Hermes",
            excerpt = "First line\nsecond line",
        )

        val encoded = buildChatQuotedPrompt("My reply", reference)
        val parsed = parseChatQuotedPrompt(encoded)

        assertEquals("My reply", parsed?.body)
        assertEquals("message/id with spaces", parsed?.reference?.messageId)
        assertEquals("Hermes", parsed?.reference?.authorLabel)
        assertEquals("First line second line", parsed?.reference?.excerpt)
        assertTrue(encoded.startsWith("> **Replying to [@Hermes](hermes-message://"))
    }

    @Test
    fun `plain and malformed text stay ordinary`() {
        assertNull(parseChatQuotedPrompt("ordinary message"))
        assertNull(
            parseChatQuotedPrompt(
                "> **Replying to [@Hermes](hermes-message://not+base64):** text\n\nbody",
            ),
        )
    }

    @Test
    fun `null reference does not alter transport text`() {
        assertEquals("My reply", buildChatQuotedPrompt("My reply", null))
    }
}
