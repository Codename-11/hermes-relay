package com.hermesandroid.relay.ui.screens

import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.data.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatUnreadStateTest {

    @Test
    fun appendedMessagesAreCountedFromTheLastReadSnapshot() {
        val readMessages = listOf(message("user", "Hello"))
        val currentMessages = readMessages + listOf(
            message("assistant", "Hi there", MessageRole.ASSISTANT),
            message("system", "Connection restored", MessageRole.SYSTEM),
        )

        assertEquals(
            2,
            countUnreadMessages(
                current = currentMessages.toUnreadSnapshot(),
                lastRead = readMessages.toUnreadSnapshot(),
            ),
        )
    }

    @Test
    fun streamingGrowthCountsTheBubbleOnce() {
        val readMessages = listOf(message("assistant", "Partial", MessageRole.ASSISTANT))
        val firstUpdate = listOf(message("assistant", "Partial answer", MessageRole.ASSISTANT))
        val secondUpdate = listOf(message("assistant", "Partial answer completed", MessageRole.ASSISTANT))
        val lastRead = readMessages.toUnreadSnapshot()

        assertEquals(1, countUnreadMessages(firstUpdate.toUnreadSnapshot(), lastRead))
        assertEquals(1, countUnreadMessages(secondUpdate.toUnreadSnapshot(), lastRead))
    }

    @Test
    fun visibleToolProgressCountsAsUnreadContent() {
        val pending = message("assistant", "Working", MessageRole.ASSISTANT).copy(
            toolCalls = listOf(
                ToolCall(
                    id = "tool-1",
                    name = "search",
                    args = null,
                    result = null,
                    success = null,
                ),
            ),
        )
        val completed = pending.copy(
            toolCalls = pending.toolCalls.map {
                it.copy(result = "done", success = true, isComplete = true)
            },
        )

        assertEquals(
            1,
            countUnreadMessages(
                listOf(completed).toUnreadSnapshot(),
                listOf(pending).toUnreadSnapshot(),
            ),
        )
    }

    @Test
    fun streamingFlagOnlyChangeDoesNotCreateUnreadContent() {
        val streaming = message("assistant", "Complete text", MessageRole.ASSISTANT)
            .copy(isStreaming = true)
        val settled = streaming.copy(isStreaming = false)

        assertEquals(
            0,
            countUnreadMessages(
                listOf(settled).toUnreadSnapshot(),
                listOf(streaming).toUnreadSnapshot(),
            ),
        )
    }

    @Test
    fun aTranscriptThatShrinksDoesNotCreateUnreadContent() {
        val lastRead = listOf(
            message("user", "Question"),
            message("assistant", "Answer", MessageRole.ASSISTANT),
        )
        val current = listOf(message("user", "Question"))

        assertEquals(
            0,
            countUnreadMessages(current.toUnreadSnapshot(), lastRead.toUnreadSnapshot()),
        )
    }

    @Test
    fun demoModeTakesPriorityOverLiveVoiceReadiness() {
        assertEquals(
            ChatVoiceAction.ShowDemoNotice,
            resolveChatVoiceAction(isDemoMode = true, voiceReady = true),
        )
        assertEquals(
            ChatVoiceAction.ShowDemoNotice,
            resolveChatVoiceAction(isDemoMode = true, voiceReady = false),
        )
    }

    @Test
    fun demoDispatchNeverInvokesTheLiveVoiceCallback() {
        var demoNotices = 0
        var voiceStarts = 0
        var setupNotices = 0

        dispatchChatVoiceAction(
            isDemoMode = true,
            voiceReady = true,
            onDemoNotice = { demoNotices += 1 },
            onStartVoice = { voiceStarts += 1 },
            onSetupNotice = { setupNotices += 1 },
        )

        assertEquals(1, demoNotices)
        assertEquals(0, voiceStarts)
        assertEquals(0, setupNotices)
    }

    @Test
    fun liveChatUsesTheExistingVoiceReadinessGate() {
        assertEquals(
            ChatVoiceAction.StartVoice,
            resolveChatVoiceAction(isDemoMode = false, voiceReady = true),
        )
        assertEquals(
            ChatVoiceAction.ShowSetupNotice,
            resolveChatVoiceAction(isDemoMode = false, voiceReady = false),
        )
    }

    private fun message(
        id: String,
        content: String,
        role: MessageRole = MessageRole.USER,
    ) = ChatMessage(
        id = id,
        role = role,
        content = content,
        timestamp = 0L,
    )
}
