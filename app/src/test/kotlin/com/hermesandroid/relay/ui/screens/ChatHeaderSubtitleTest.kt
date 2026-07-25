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
                projectName = "Hermes Relay",
                personalityName = "Victor",
                modelName = "GPT-5.6",
            ),
        )
    }

    @Test
    fun `idle subtitle retains project personality and model metadata`() {
        assertEquals(
            "Hermes Relay \u00B7 Victor \u00B7 GPT-5.6",
            resolveChatHeaderSubtitle(
                isStreaming = false,
                statusText = "Connected",
                projectName = "Hermes Relay",
                personalityName = "Victor",
                modelName = "GPT-5.6",
            ),
        )
    }
}
