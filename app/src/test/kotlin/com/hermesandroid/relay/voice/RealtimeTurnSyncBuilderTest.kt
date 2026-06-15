package com.hermesandroid.relay.voice

import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.data.RealtimeTurnTrace
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeTurnSyncBuilderTest {
    @Test
    fun buildSyntheticMessages_emitsProviderOnlyRealtimeTurnAsUserAssistantPair() {
        val history = listOf(
            chatMessage(
                id = "rt-1",
                role = MessageRole.ASSISTANT,
                content = "It syncs vault metadata.",
                realtimeTurn = RealtimeTurnTrace(
                    userText = "What does the Bitwarden integration do?",
                    assistantText = "It syncs vault metadata.",
                    provider = "xai_realtime",
                    model = "grok-voice-latest",
                    voice = "leo",
                ),
            ),
        )

        val out = RealtimeTurnSyncBuilder.buildSyntheticMessages(history)

        assertEquals(2, out.size)
        assertEquals("user", out[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals(
            "What does the Bitwarden integration do?",
            out[0].jsonObject["content"]?.jsonPrimitive?.content,
        )
        assertEquals("assistant", out[1].jsonObject["role"]?.jsonPrimitive?.content)
        val assistantContent = out[1].jsonObject["content"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(assistantContent.startsWith("It syncs vault metadata."))
        assertTrue(assistantContent.contains("provider=xai_realtime"))
        assertTrue(RealtimeTurnSyncBuilder.hasUnsynced(history))
    }

    @Test
    fun buildSyntheticMessages_skipsSyncedTurns() {
        val history = listOf(
            chatMessage(
                id = "rt-1",
                role = MessageRole.ASSISTANT,
                realtimeTurn = RealtimeTurnTrace(
                    userText = "Hi",
                    assistantText = "Hello",
                    syncedToServer = true,
                ),
            ),
        )

        assertTrue(RealtimeTurnSyncBuilder.buildSyntheticMessages(history).isEmpty())
        assertFalse(RealtimeTurnSyncBuilder.hasUnsynced(history))
    }

    private fun chatMessage(
        id: String,
        role: MessageRole,
        content: String = "",
        realtimeTurn: RealtimeTurnTrace? = null,
    ) = ChatMessage(
        id = id,
        role = role,
        content = content,
        timestamp = 1L,
        realtimeTurn = realtimeTurn,
    )
}
