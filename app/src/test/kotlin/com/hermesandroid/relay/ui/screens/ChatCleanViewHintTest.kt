package com.hermesandroid.relay.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCleanViewHintTest {

    @Test
    fun emptyTextChatShowsCleanViewHint() {
        assertTrue(
            shouldShowCleanViewHint(
                hasMessages = false,
                ambientMode = false,
                voiceMode = false,
            ),
        )
    }

    @Test
    fun voiceModeOwnsBottomAreaOnEmptyChat() {
        assertFalse(
            shouldShowCleanViewHint(
                hasMessages = false,
                ambientMode = false,
                voiceMode = true,
            ),
        )
    }

    @Test
    fun existingConversationOrCleanModeSuppressesHint() {
        assertFalse(
            shouldShowCleanViewHint(
                hasMessages = true,
                ambientMode = false,
                voiceMode = false,
            ),
        )
        assertFalse(
            shouldShowCleanViewHint(
                hasMessages = false,
                ambientMode = true,
                voiceMode = false,
            ),
        )
    }
}
