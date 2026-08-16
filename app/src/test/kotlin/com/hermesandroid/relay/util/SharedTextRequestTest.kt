package com.hermesandroid.relay.util

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedTextRequestTest {
    @Test
    fun textSendIsAcceptedWithoutChangingItsContent() {
        assertEquals(
            "  Review this\ncarefully  ",
            extractSharedText(
                action = Intent.ACTION_SEND,
                mimeType = "text/plain",
                text = "  Review this\ncarefully  ",
            ),
        )
    }

    @Test
    fun nonTextAndBlankSharesAreRejected() {
        assertNull(extractSharedText(Intent.ACTION_VIEW, "text/plain", "hello"))
        assertNull(extractSharedText(Intent.ACTION_SEND, "image/png", "hello"))
        assertNull(extractSharedText(Intent.ACTION_SEND, "text/markdown", "  \n"))
    }

    @Test
    fun consumeOnlyClearsTheMatchingRequest() {
        SharedTextRequest.pending.value?.let { SharedTextRequest.consume(it.id) }
        assertFalse(SharedTextRequest.tryRequest(null))
        assertTrue(SharedTextRequest.tryRequest("first"))
        val first = requireNotNull(SharedTextRequest.pending.value)

        assertTrue(SharedTextRequest.tryRequest("second"))
        val second = requireNotNull(SharedTextRequest.pending.value)
        SharedTextRequest.consume(first.id)
        assertEquals(second, SharedTextRequest.pending.value)

        SharedTextRequest.consume(second.id)
        assertNull(SharedTextRequest.pending.value)
    }
}
