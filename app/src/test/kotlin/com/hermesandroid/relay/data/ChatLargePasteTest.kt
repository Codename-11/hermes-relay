package com.hermesandroid.relay.data

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLargePasteTest {

    @Test
    fun `text transport materializes large paste and keeps ordinary attachments`() {
        val paste = largePasteAttachment("line one\nline two")
        val image = Attachment("image/png", "aW1hZ2U=", "image.png", 5)

        val prepared = prepareTextTransportAttachments(
            message = "Please review this context",
            attachments = listOf(paste, image),
        )

        assertTrue(prepared.message.contains("line one\nline two"))
        assertTrue(prepared.message.contains("pasted-text.txt"))
        assertEquals(listOf(image), prepared.attachments)
    }

    @Test
    fun `large paste attachment is utf8 text with stable metadata`() {
        val attachment = largePasteAttachment("héllo")

        assertEquals("text/plain; charset=utf-8", attachment.contentType)
        assertEquals("pasted-text.txt", attachment.fileName)
        assertEquals(6L, attachment.fileSize)
        assertTrue(attachment.isLargePaste)
        assertEquals("héllo", String(Base64.getDecoder().decode(attachment.content), Charsets.UTF_8))
    }
}
