package com.hermesandroid.relay.share

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareIntentParserTest {
    @Test
    fun parse_acceptsActionSendText() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "  hello Hermes  ")
        }

        val payload = ShareIntentParser.parse(intent)!!

        assertEquals("hello Hermes", payload.text)
        assertEquals("text/plain", payload.mimeType)
    }

    @Test
    fun parse_rejectsNonTextShare() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_TEXT, "ignored")
        }

        assertNull(ShareIntentParser.parse(intent))
    }
}
