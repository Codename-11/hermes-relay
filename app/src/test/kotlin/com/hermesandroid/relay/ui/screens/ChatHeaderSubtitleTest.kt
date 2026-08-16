package com.hermesandroid.relay.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHeaderSubtitleTest {
    @Test
    fun `active turn status replaces static model metadata`() {
        assertEquals(
            "Streaming",
            resolveChatHeaderSubtitle(
                isStreaming = true,
                statusText = "Streaming",
                personalityName = "Victor",
                modelName = "GPT-5.6",
            ),
        )
    }

    @Test
    fun `idle subtitle retains only personality and model metadata`() {
        assertEquals(
            "Victor \u00B7 GPT-5.6",
            resolveChatHeaderSubtitle(
                isStreaming = false,
                statusText = "Connected",
                personalityName = "Victor",
                modelName = "GPT-5.6",
            ),
        )
    }
}
