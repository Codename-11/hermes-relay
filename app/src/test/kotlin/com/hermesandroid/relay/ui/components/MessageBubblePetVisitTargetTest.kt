package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.Attachment
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.data.ToolCall
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageBubblePetVisitTargetTest {

    @Test
    fun `settled plain assistant response is eligible`() {
        assertTrue(isPetVisitTargetCandidate(message()))
    }

    @Test
    fun `user system and streaming rows are excluded`() {
        assertFalse(isPetVisitTargetCandidate(message(role = MessageRole.USER)))
        assertFalse(isPetVisitTargetCandidate(message(role = MessageRole.SYSTEM)))
        assertFalse(isPetVisitTargetCandidate(message(isStreaming = true)))
        assertFalse(isPetVisitTargetCandidate(message(isThinkingStreaming = true)))
    }

    @Test
    fun `empty and phone action responses are excluded`() {
        assertFalse(isPetVisitTargetCandidate(message(content = "")))
        assertFalse(isPetVisitTargetCandidate(message(agentName = "Phone action")))
        assertFalse(isPetVisitTargetCandidate(message(id = "voice-intent-result-1")))
    }

    @Test
    fun `interactive tool and attachment responses are excluded`() {
        assertFalse(
            isPetVisitTargetCandidate(
                message().copy(
                    toolCalls = listOf(
                        ToolCall(
                            name = "search",
                            args = null,
                            result = null,
                            success = true,
                        ),
                    ),
                ),
            ),
        )
        assertFalse(
            isPetVisitTargetCandidate(
                message().copy(
                    attachments = listOf(Attachment("image/png", "")),
                ),
            ),
        )
    }

    @Test
    fun `settled text response can be a perch even when it reports tool activity`() {
        val responseWithTool = message().copy(
            toolCalls = listOf(
                ToolCall(
                    name = "search",
                    args = null,
                    result = "done",
                    success = true,
                ),
            ),
        )

        assertTrue(isPetPerchCandidate(responseWithTool))
        assertTrue(newestPetPerchUiKey(listOf(responseWithTool)) == responseWithTool.uiKey)
    }

    @Test
    fun `latest settled user bubble can provide an opposite side pocket`() {
        val assistant = message(id = "assistant").copy(uiKey = "assistant-key")
        val user = message(id = "user", role = MessageRole.USER).copy(uiKey = "user-key")

        assertTrue(isPetPerchCandidate(user))
        assertTrue(newestPetPerchUiKey(listOf(assistant, user)) == user.uiKey)
        assertTrue(petPerchUiKeys(listOf(assistant, user)) == setOf(assistant.uiKey, user.uiKey))
    }

    @Test
    fun `ineligible transcript tail does not expose an older perch`() {
        val assistant = message(id = "assistant").copy(uiKey = "assistant-key")
        val system = message(id = "system", role = MessageRole.SYSTEM).copy(uiKey = "system-key")

        assertTrue(newestPetPerchUiKey(listOf(assistant, system)) == null)
    }

    @Test
    fun `later interactive response does not fall back to an older response`() {
        val older = message(id = "older").copy(uiKey = "stable-older")
        val newerInteractive = message(id = "newer").copy(
            attachments = listOf(Attachment("image/png", "")),
        )

        assertTrue(newestPetVisitTargetUiKey(listOf(older, newerInteractive)) == null)
    }

    @Test
    fun `later tool-only or empty response does not revisit an older bubble`() {
        val older = message(id = "older").copy(uiKey = "stable-older")
        val toolOnly = message(id = "tool-only", content = "").copy(
            toolCalls = listOf(
                ToolCall(
                    name = "search",
                    args = null,
                    result = "done",
                    success = true,
                ),
            ),
        )
        val empty = message(id = "empty", content = "")

        assertTrue(newestPetVisitTargetUiKey(listOf(older, toolOnly)) == null)
        assertTrue(newestPetVisitTargetUiKey(listOf(older, empty)) == null)
    }

    @Test
    fun `streaming tail does not expose an older settled response`() {
        val older = message(id = "older").copy(uiKey = "stable-older")
        val streaming = message(id = "streaming", isStreaming = true)

        assertTrue(newestPetVisitTargetUiKey(listOf(older, streaming)) == null)
        assertTrue(newestPetPerchUiKey(listOf(older, streaming)) == null)
        assertFalse(newestPetAssistantIsSettled(listOf(older, streaming)))
        assertTrue(newestPetAssistantIsSettled(listOf(older, streaming.copy(isStreaming = false))))
    }

    private fun message(
        id: String = "assistant-1",
        role: MessageRole = MessageRole.ASSISTANT,
        content: String = "Done.",
        isStreaming: Boolean = false,
        isThinkingStreaming: Boolean = false,
        agentName: String? = null,
    ) = ChatMessage(
        id = id,
        role = role,
        content = content,
        timestamp = 1L,
        isStreaming = isStreaming,
        isThinkingStreaming = isThinkingStreaming,
        agentName = agentName,
    )
}
