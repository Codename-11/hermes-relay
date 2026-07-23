package com.hermesandroid.relay.network.upstream

import com.hermesandroid.relay.data.Attachment
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.ChatSession
import com.hermesandroid.relay.data.ChatTurnAssistantCheckpoint
import com.hermesandroid.relay.data.ChatTurnCheckpoint
import com.hermesandroid.relay.data.ChatTurnToolCheckpoint
import com.hermesandroid.relay.data.ChatTurnUserCheckpoint
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.data.RealtimeTurnTrace
import com.hermesandroid.relay.data.ToolCall
import com.hermesandroid.relay.data.VoiceIntentTrace
import com.hermesandroid.relay.network.upstream.models.MessageItem
import com.hermesandroid.relay.network.upstream.models.RelayStreamEventEnvelope
import com.hermesandroid.relay.network.upstream.models.SessionItem
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ChatHandler message state management.
 *
 * ChatHandler uses StateFlow internally but all methods are synchronous
 * and don't require Android framework (no Handler/Looper needed).
 */
class ChatHandlerTest {

    private lateinit var handler: ChatHandler

    @Before
    fun setUp() {
        handler = ChatHandler()
    }

    // --- addUserMessage ---

    @Test
    fun addUserMessage_addsMessageToList() {
        val msg = createUserMessage("msg-1", "Hello")
        handler.addUserMessage(msg)

        val messages = handler.messages.value
        assertEquals(1, messages.size)
        assertEquals("Hello", messages[0].content)
        assertEquals(MessageRole.USER, messages[0].role)
    }

    @Test
    fun addUserMessage_appendsMultipleMessages() {
        handler.addUserMessage(createUserMessage("msg-1", "First"))
        handler.addUserMessage(createUserMessage("msg-2", "Second"))

        val messages = handler.messages.value
        assertEquals(2, messages.size)
        assertEquals("First", messages[0].content)
        assertEquals("Second", messages[1].content)
    }

    @Test
    fun clearMessages_emptiesMessageList() {
        handler.addUserMessage(createUserMessage("msg-1", "Test"))
        handler.clearMessages()

        assertTrue(handler.messages.value.isEmpty())
    }

    // --- onTextDelta ---

    @Test
    fun onTextDelta_createsNewAssistantMessage_whenNoneExists() {
        handler.onTextDelta("assist-1", "Hello")

        val messages = handler.messages.value
        assertEquals(1, messages.size)
        assertEquals("assist-1", messages[0].id)
        assertEquals(MessageRole.ASSISTANT, messages[0].role)
        assertEquals("Hello", messages[0].content)
        assertTrue(messages[0].isStreaming)
    }

    @Test
    fun onTextDelta_appendsToExistingMessage() {
        handler.onTextDelta("assist-1", "Hello")
        handler.onTextDelta("assist-1", " world")

        val messages = handler.messages.value
        assertEquals(1, messages.size)
        assertEquals("Hello world", messages[0].content)
    }

    @Test
    fun onTextDelta_appendsMultipleDeltas() {
        handler.onTextDelta("assist-1", "A")
        handler.onTextDelta("assist-1", "B")
        handler.onTextDelta("assist-1", "C")

        val messages = handler.messages.value
        assertEquals(1, messages.size)
        assertEquals("ABC", messages[0].content)
    }

    @Test
    fun onTextDelta_setsStreamingFlag() {
        handler.onTextDelta("assist-1", "delta")

        assertTrue(handler.isStreaming.value)
    }

    @Test
    fun onTextDelta_differentMessageIds_createsSeparateMessages() {
        handler.onTextDelta("assist-1", "First")
        handler.onTextDelta("assist-2", "Second")

        val messages = handler.messages.value
        assertEquals(2, messages.size)
        assertEquals("First", messages[0].content)
        assertEquals("Second", messages[1].content)
    }

    @Test
    fun onStreamComplete_removesExactIntentionalSilenceMarker() {
        handler.onTextDelta("assist-1", "NO")
        handler.onTextDelta("assist-1", "_REPLY")

        handler.onStreamComplete("assist-1")

        assertTrue(handler.messages.value.isEmpty())
        assertFalse(handler.isStreaming.value)
    }

    @Test
    fun onStreamComplete_removesEmptyAssistantPlaceholder() {
        handler.addPlaceholderMessage(
            ChatMessage(
                id = "assist-empty",
                role = MessageRole.ASSISTANT,
                content = "",
                timestamp = 1L,
                isStreaming = true,
            ),
        )

        handler.onStreamComplete("assist-empty")

        assertTrue(handler.messages.value.isEmpty())
        assertFalse(handler.isStreaming.value)
    }

    @Test
    fun onStreamComplete_keepsProseThatMentionsSilenceMarker() {
        handler.onTextDelta("assist-1", "I will not use NO_REPLY here.")

        handler.onStreamComplete("assist-1")

        val messages = handler.messages.value
        assertEquals(1, messages.size)
        assertEquals("I will not use NO_REPLY here.", messages.single().content)
        assertFalse(messages.single().isStreaming)
    }

    // --- onStreamComplete ---

    @Test
    fun onStreamComplete_clearsStreamingFlag() {
        handler.onTextDelta("assist-1", "hello")
        assertTrue(handler.isStreaming.value)

        handler.onStreamComplete("assist-1")
        assertFalse(handler.isStreaming.value)
    }

    @Test
    fun onStreamComplete_marksMessageAsNotStreaming() {
        handler.onTextDelta("assist-1", "hello")
        handler.onStreamComplete("assist-1")

        val msg = handler.messages.value[0]
        assertFalse(msg.isStreaming)
        assertFalse(msg.isThinkingStreaming)
    }

    // --- onStreamError ---

    @Test
    fun onStreamError_setsErrorState() {
        handler.onStreamError("Connection failed")

        assertEquals("Connection failed", handler.error.value)
    }

    @Test
    fun onStreamError_clearsStreamingFlag() {
        handler.onTextDelta("assist-1", "partial")
        assertTrue(handler.isStreaming.value)

        handler.onStreamError("Error occurred")
        assertFalse(handler.isStreaming.value)
    }

    @Test
    fun onStreamError_clearsStreamingOnActiveMessages() {
        handler.onTextDelta("assist-1", "streaming content")
        assertTrue(handler.messages.value[0].isStreaming)

        handler.onStreamError("Network error")

        assertFalse(handler.messages.value[0].isStreaming)
        assertFalse(handler.messages.value[0].isThinkingStreaming)
    }

    @Test
    fun clearError_resetsErrorToNull() {
        handler.onStreamError("Some error")
        assertNotNull(handler.error.value)

        handler.clearError()
        assertNull(handler.error.value)
    }

    // --- onToolCallStart ---

    @Test
    fun onToolCallStart_createsToolCallEntry() {
        handler.onTextDelta("assist-1", "")
        handler.onToolCallStart("assist-1", "call-1", "read_file")

        val msg = handler.messages.value.find { it.id == "assist-1" }
        assertNotNull(msg)
        assertEquals(1, msg!!.toolCalls.size)

        val toolCall = msg.toolCalls[0]
        assertEquals("call-1", toolCall.id)
        assertEquals("read_file", toolCall.name)
        assertFalse(toolCall.isComplete)
        assertNull(toolCall.success)
    }

    @Test
    fun onToolCallStart_createsNewMessage_whenNoneExists() {
        handler.onToolCallStart("assist-new", "call-1", "write_file")

        val messages = handler.messages.value
        assertEquals(1, messages.size)
        assertEquals("assist-new", messages[0].id)
        assertEquals(MessageRole.ASSISTANT, messages[0].role)
        assertEquals(1, messages[0].toolCalls.size)
        assertEquals("write_file", messages[0].toolCalls[0].name)
    }

    @Test
    fun onToolCallStart_addsMultipleToolCalls() {
        handler.onTextDelta("assist-1", "processing")
        handler.onToolCallStart("assist-1", "call-1", "read_file")
        handler.onToolCallStart("assist-1", "call-2", "write_file")

        val msg = handler.messages.value.find { it.id == "assist-1" }
        assertEquals(2, msg!!.toolCalls.size)
        assertEquals("read_file", msg.toolCalls[0].name)
        assertEquals("write_file", msg.toolCalls[1].name)
    }

    @Test
    fun onToolCallStart_setsStreamingTrue() {
        handler.onToolCallStart("assist-1", "call-1", "tool")
        assertTrue(handler.isStreaming.value)
    }

    // --- onToolCallComplete ---

    @Test
    fun onToolCallComplete_matchesByToolCallId() {
        handler.onTextDelta("assist-1", "")
        handler.onToolCallStart("assist-1", "call-1", "read_file")
        handler.onToolCallStart("assist-1", "call-2", "write_file")

        handler.onToolCallComplete("assist-1", "call-1", "file contents here")

        val msg = handler.messages.value.find { it.id == "assist-1" }!!
        val call1 = msg.toolCalls.find { it.id == "call-1" }!!
        val call2 = msg.toolCalls.find { it.id == "call-2" }!!

        assertTrue(call1.isComplete)
        assertEquals(true, call1.success)
        assertEquals("file contents here", call1.result)
        assertNotNull(call1.completedAt)

        assertFalse(call2.isComplete)
        assertNull(call2.success)
    }

    @Test
    fun onToolCallComplete_doesNotCompleteAlreadyCompletedCall() {
        handler.onTextDelta("assist-1", "")
        handler.onToolCallStart("assist-1", "call-1", "read_file")
        handler.onToolCallComplete("assist-1", "call-1", "result A")

        // Try completing the same call again with different result
        handler.onToolCallComplete("assist-1", "call-1", "result B")

        val msg = handler.messages.value.find { it.id == "assist-1" }!!
        val call = msg.toolCalls.find { it.id == "call-1" }!!
        assertEquals("result A", call.result) // First result preserved
    }

    @Test
    fun onToolCallComplete_withNullPreview_preservesExistingResult() {
        handler.onTextDelta("assist-1", "")
        handler.onToolCallStart("assist-1", "call-1", "tool")
        handler.onToolCallComplete("assist-1", "call-1", null)

        val call = handler.messages.value[0].toolCalls[0]
        assertTrue(call.isComplete)
        assertEquals(true, call.success)
        assertNull(call.result) // No result preview provided
    }

    // --- onToolCallFailed ---

    @Test
    fun onToolCallFailed_matchesByToolCallId() {
        handler.onTextDelta("assist-1", "")
        handler.onToolCallStart("assist-1", "call-1", "dangerous_tool")

        handler.onToolCallFailed("assist-1", "call-1", "Permission denied")

        val call = handler.messages.value[0].toolCalls[0]
        assertTrue(call.isComplete)
        assertEquals(false, call.success)
        assertEquals("Permission denied", call.error)
        assertNotNull(call.completedAt)
    }

    @Test
    fun onToolCallFailed_doesNotFailAlreadyCompletedCall() {
        handler.onTextDelta("assist-1", "")
        handler.onToolCallStart("assist-1", "call-1", "tool")
        handler.onToolCallComplete("assist-1", "call-1", "success result")

        handler.onToolCallFailed("assist-1", "call-1", "error")

        val call = handler.messages.value[0].toolCalls[0]
        assertEquals(true, call.success) // Still successful, not overwritten
    }

    @Test
    fun onToolOutputRisk_attachesOnlyToMatchingToolCall() {
        handler.onToolCallStart("assist-1", "call-1", "browser")
        handler.onToolCallStart("assist-1", "call-2", "read_file")

        handler.onToolOutputRisk(
            "assist-1",
            GatewayToolOutputRisk(
                toolCallId = "call-1",
                toolName = "browser",
                risk = "high",
                findings = listOf("Prompt injection detected"),
                redacted = true,
            ),
        )

        val calls = handler.messages.value.single().toolCalls
        assertEquals("high", calls[0].outputRisk)
        assertEquals(listOf("Prompt injection detected"), calls[0].outputRiskFindings)
        assertTrue(calls[0].outputRiskRedacted)
        assertNull(calls[1].outputRisk)
    }

    // --- onThinkingDelta ---

    @Test
    fun onThinkingDelta_createsNewMessage_whenNoneExists() {
        handler.onThinkingDelta("assist-1", "Let me think...")

        val msg = handler.messages.value[0]
        assertEquals("assist-1", msg.id)
        assertEquals(MessageRole.ASSISTANT, msg.role)
        assertEquals("Let me think...", msg.thinkingContent)
        assertTrue(msg.isThinkingStreaming)
    }

    @Test
    fun onThinkingDelta_appendsToExistingMessage() {
        handler.onThinkingDelta("assist-1", "First ")
        handler.onThinkingDelta("assist-1", "thought")

        assertEquals("First thought", handler.messages.value[0].thinkingContent)
    }

    @Test
    fun onThinkingDelta_setsThinkingStreamingFlag() {
        handler.onThinkingDelta("assist-1", "thinking")

        assertTrue(handler.messages.value[0].isThinkingStreaming)
    }

    // --- updateSessions ---

    @Test
    fun updateSessions_populatesSessionList() {
        val items = listOf(
            SessionItem(id = "s1", title = "Session 1", model = "gpt-4"),
            SessionItem(id = "s2", title = "Session 2", model = "claude-3")
        )
        handler.updateSessions(items)

        val sessions = handler.sessions.value
        assertEquals(2, sessions.size)
        assertEquals("s1", sessions[0].sessionId)
        assertEquals("Session 1", sessions[0].title)
        assertEquals("gpt-4", sessions[0].model)
    }

    @Test
    fun updateSessions_replacesExistingSessions() {
        handler.updateSessions(listOf(SessionItem(id = "s1", title = "Old")))
        handler.updateSessions(listOf(SessionItem(id = "s2", title = "New")))

        val sessions = handler.sessions.value
        assertEquals(1, sessions.size)
        assertEquals("s2", sessions[0].sessionId)
    }

    @Test
    fun updateSessions_deduplicatesDuplicateServerRows() {
        handler.updateSessions(
            listOf(
                SessionItem(id = "s1", title = "First"),
                SessionItem(id = "s1", title = "Duplicate"),
                SessionItem(id = "s2", title = "Other"),
            ),
        )

        assertEquals(listOf("s1", "s2"), handler.sessions.value.map { it.sessionId })
        assertEquals("First", handler.sessions.value.first().title)
    }

    @Test
    fun updateSessions_preservesLocalTitle_whenServerReturnsNullTitle() {
        // The server titles a session asynchronously after the first turn (and
        // never on the SSE/runs surfaces), so a re-list often returns the row
        // with title == null before/without the write. The optimistic preview
        // we already show must survive that null instead of becoming "Untitled".
        handler.updateSessions(listOf(SessionItem(id = "s1", title = "Fix the build")))
        handler.updateSessions(listOf(SessionItem(id = "s1", title = null)))

        assertEquals("Fix the build", handler.sessions.value.single().title)
    }

    @Test
    fun updateSessions_preservesLocalTitle_whenServerReturnsBlankTitle() {
        handler.updateSessions(listOf(SessionItem(id = "s1", title = "Fix the build")))
        handler.updateSessions(listOf(SessionItem(id = "s1", title = "   ")))

        assertEquals("Fix the build", handler.sessions.value.single().title)
    }

    @Test
    fun updateSessions_usesUpstreamPreview_whenPersistedTitleIsBlank() {
        handler.updateSessions(
            listOf(
                SessionItem(
                    id = "s1",
                    title = null,
                    preview = "Investigate the session drawer titles",
                ),
            ),
        )

        assertEquals(
            "Investigate the session drawer titles",
            handler.sessions.value.single().title,
        )
    }

    @Test
    fun updateSessions_keepsKnownLocalTitle_overUpstreamPreview() {
        handler.updateSessions(listOf(SessionItem(id = "s1", title = "Full local preview")))
        handler.updateSessions(
            listOf(
                SessionItem(
                    id = "s1",
                    title = null,
                    preview = "Truncated upstream preview...",
                ),
            ),
        )

        assertEquals("Full local preview", handler.sessions.value.single().title)
    }

    @Test
    fun updateSessions_serverTitleWins_overLocalPreview() {
        // Once the server generates a real title it replaces the local preview.
        handler.updateSessions(listOf(SessionItem(id = "s1", title = "Fix the build")))
        handler.updateSessions(listOf(SessionItem(id = "s1", title = "CI pipeline failure")))

        assertEquals("CI pipeline failure", handler.sessions.value.single().title)
    }

    @Test
    fun updateSessions_handlesNullMessageCount() {
        val item = SessionItem(id = "s1", messageCount = null)
        handler.updateSessions(listOf(item))

        assertEquals(0, handler.sessions.value[0].messageCount)
    }

    @Test
    fun updateSessions_sortsByLastActivityAndKeepsStartedAt() {
        handler.updateSessions(
            listOf(
                SessionItem(
                    id = "started-later",
                    title = "Started later",
                    startedAt = 2_000.0,
                    lastActive = 2_000.0,
                ),
                SessionItem(
                    id = "active-later",
                    title = "Active later",
                    startedAt = 1_000.0,
                    lastActive = 3_000.0,
                ),
            ),
        )

        val sessions = handler.sessions.value
        assertEquals("active-later", sessions[0].sessionId)
        assertEquals(1_000_000L, sessions[0].startedAt)
        assertEquals(3_000_000L, sessions[0].lastActivityAt)
        assertEquals(3_000_000L, sessions[0].updatedAt)
        assertEquals("started-later", sessions[1].sessionId)
    }

    // --- removeSession ---

    @Test
    fun removeSession_removesFromList() {
        handler.updateSessions(listOf(
            SessionItem(id = "s1", title = "Keep"),
            SessionItem(id = "s2", title = "Remove")
        ))

        handler.removeSession("s2")

        val sessions = handler.sessions.value
        assertEquals(1, sessions.size)
        assertEquals("s1", sessions[0].sessionId)
    }

    @Test
    fun removeSession_clearsCurrentSessionIfRemoved() {
        handler.setSessionId("s1")
        handler.updateSessions(listOf(SessionItem(id = "s1")))

        handler.removeSession("s1")

        assertNull(handler.currentSessionId.value)
    }

    @Test
    fun removeSession_clearsMessages_whenCurrentSessionRemoved() {
        handler.setSessionId("s1")
        handler.addUserMessage(createUserMessage("msg-1", "hello"))
        handler.updateSessions(listOf(SessionItem(id = "s1")))

        handler.removeSession("s1")

        assertTrue(handler.messages.value.isEmpty())
    }

    @Test
    fun removeSession_doesNotAffectCurrentSession_whenDifferentSessionRemoved() {
        handler.setSessionId("s1")
        handler.updateSessions(listOf(
            SessionItem(id = "s1"),
            SessionItem(id = "s2")
        ))

        handler.removeSession("s2")

        assertEquals("s1", handler.currentSessionId.value)
    }

    // --- renameSessionLocal ---

    @Test
    fun renameSessionLocal_updatesTitle() {
        handler.updateSessions(listOf(SessionItem(id = "s1", title = "Old Title")))

        handler.renameSessionLocal("s1", "New Title")

        assertEquals("New Title", handler.sessions.value[0].title)
    }

    @Test
    fun renameSessionLocal_doesNotAffectOtherSessions() {
        handler.updateSessions(listOf(
            SessionItem(id = "s1", title = "One"),
            SessionItem(id = "s2", title = "Two")
        ))

        handler.renameSessionLocal("s1", "Updated")

        assertEquals("Updated", handler.sessions.value[0].title)
        assertEquals("Two", handler.sessions.value[1].title)
    }

    // --- addSession ---

    @Test
    fun addSession_prependsToList() {
        handler.updateSessions(listOf(SessionItem(id = "s1", title = "Existing")))

        handler.addSession(ChatSession(sessionId = "s2", title = "New", model = null))

        val sessions = handler.sessions.value
        assertEquals(2, sessions.size)
        assertEquals("s2", sessions[0].sessionId) // Prepended
        assertEquals("s1", sessions[1].sessionId)
    }

    @Test
    fun addSession_collapsesRepeatedOptimisticRows() {
        val session = ChatSession(sessionId = "s1", title = "Fresh", model = null)

        handler.addSession(session)
        handler.addSession(session)

        assertEquals(listOf("s1"), handler.sessions.value.map { it.sessionId })
    }

    @Test
    fun addSession_afterRefreshKeepsSingleServerEnrichedRow() {
        handler.updateSessions(
            listOf(
                SessionItem(
                    id = "s1",
                    title = "Server title",
                    model = "gpt-5.6",
                    messageCount = 2,
                ),
            ),
        )

        handler.addSession(ChatSession(sessionId = "s1", title = "Optimistic", model = null))

        val session = handler.sessions.value.single()
        assertEquals("s1", session.sessionId)
        assertEquals("Server title", session.title)
        assertEquals("gpt-5.6", session.model)
        assertEquals(2, session.messageCount)
    }

    // --- setSessionId ---

    @Test
    fun setSessionId_updatesCurrentSessionId() {
        handler.setSessionId("abc-123")
        assertEquals("abc-123", handler.currentSessionId.value)
    }

    @Test
    fun setSessionId_canBeSetToNull() {
        handler.setSessionId("abc-123")
        handler.setSessionId(null)
        assertNull(handler.currentSessionId.value)
    }

    // --- loadMessageHistory ---

    @Test
    fun loadMessageHistory_convertsUserMessages() {
        val items = listOf(
            MessageItem(id = "1", role = "user", content = JsonPrimitive("Hello"), timestamp = 1700000000.0)
        )
        handler.loadMessageHistory(items)

        val msg = handler.messages.value[0]
        assertEquals(MessageRole.USER, msg.role)
        assertEquals("Hello", msg.content)
        assertFalse(msg.isStreaming)
    }

    @Test
    fun loadMessageHistory_convertsAssistantMessages() {
        val items = listOf(
            MessageItem(id = "1", role = "assistant", content = JsonPrimitive("Hi there"))
        )
        handler.loadMessageHistory(items)

        assertEquals(MessageRole.ASSISTANT, handler.messages.value[0].role)
    }

    @Test
    fun loadMessageHistory_preservesActiveAgentNameOnAssistantMessages() {
        handler.activeAgentName = "Mizuki"
        val items = listOf(
            MessageItem(id = "1", role = "user", content = JsonPrimitive("Hello")),
            MessageItem(id = "2", role = "assistant", content = JsonPrimitive("Hi there")),
        )

        handler.loadMessageHistory(items)

        assertNull(handler.messages.value[0].agentName)
        assertEquals("Mizuki", handler.messages.value[1].agentName)
    }

    @Test
    fun relabelGenericAssistantMessages_replacesHermesButPreservesActionLabels() {
        handler.loadMessageHistory(
            listOf(
                MessageItem(id = "1", role = "assistant", content = JsonPrimitive("Hi")),
                MessageItem(id = "2", role = "assistant", content = JsonPrimitive("Custom")),
            ),
        )
        handler.activeAgentName = "Hermes"
        handler.relabelGenericAssistantMessages("Hermes")
        handler.appendLocalVoiceIntentResult("Opened Chrome")

        handler.relabelGenericAssistantMessages("Victor")

        val messages = handler.messages.value
        assertEquals("Victor", messages[0].agentName)
        assertEquals("Victor", messages[1].agentName)
        assertEquals("Voice action", messages.last().agentName)
    }

    @Test
    fun loadMessageHistory_convertsSystemMessages() {
        val items = listOf(
            MessageItem(id = "1", role = "system", content = JsonPrimitive("You are helpful"))
        )
        handler.loadMessageHistory(items)

        assertEquals(MessageRole.SYSTEM, handler.messages.value[0].role)
    }

    @Test
    fun loadMessageHistory_skipsToolMessages() {
        val items = listOf(
            MessageItem(id = "1", role = "user", content = JsonPrimitive("Hello")),
            MessageItem(id = "2", role = "tool", content = JsonPrimitive("Tool result")),
            MessageItem(id = "3", role = "assistant", content = JsonPrimitive("Based on the result..."))
        )
        handler.loadMessageHistory(items)

        assertEquals(2, handler.messages.value.size)
        assertEquals(MessageRole.USER, handler.messages.value[0].role)
        assertEquals(MessageRole.ASSISTANT, handler.messages.value[1].role)
    }

    @Test
    fun loadMessageHistory_skipsUnknownRoles() {
        val items = listOf(
            MessageItem(id = "1", role = "unknown_role", content = JsonPrimitive("mystery")),
            MessageItem(id = "2", role = "user", content = JsonPrimitive("valid"))
        )
        handler.loadMessageHistory(items)

        assertEquals(1, handler.messages.value.size)
        assertEquals("valid", handler.messages.value[0].content)
    }

    @Test
    fun loadMessageHistory_replacesCurrentMessages() {
        handler.addUserMessage(createUserMessage("old", "old message"))
        assertEquals(1, handler.messages.value.size)

        handler.loadMessageHistory(listOf(
            MessageItem(id = "1", role = "user", content = JsonPrimitive("new message"))
        ))

        assertEquals(1, handler.messages.value.size)
        assertEquals("new message", handler.messages.value[0].content)
    }

    @Test
    fun loadMessageHistory_handlesNullContent() {
        val items = listOf(
            MessageItem(id = "1", role = "user", content = null)
        )
        handler.loadMessageHistory(items)

        assertEquals("", handler.messages.value[0].content)
    }

    // --- loadMessageHistory: outbound attachment preservation (GAP 1) ---

    @Test
    fun loadMessageHistory_carriesOutboundAttachmentForward_whenIdMatches() {
        // Assistant placeholder ids are reconciled to the server id, so the
        // id-keyed carry path must keep an outbound attachment when ids match.
        handler.addUserMessage(
            ChatMessage(
                id = "100",
                role = MessageRole.USER,
                content = "look at this",
                timestamp = 1L,
                attachments = listOf(Attachment(contentType = "image/png", content = "b64data")),
            )
        )

        handler.loadMessageHistory(
            listOf(MessageItem(id = "100", role = "user", content = JsonPrimitive("look at this")))
        )

        val msg = handler.messages.value.single()
        assertEquals(1, msg.attachments.size)
        assertEquals("b64data", msg.attachments[0].content)
    }

    @Test
    fun loadMessageHistory_carriesOutboundAttachmentForward_whenUserIdDiffers() {
        // The real gateway/SSE case: the live user bubble keeps its client UUID
        // (only assistant placeholders swap ids), so the reloaded server row has
        // a different id. The content-keyed fallback must still preserve it.
        handler.addUserMessage(
            ChatMessage(
                id = "client-uuid-abc",
                role = MessageRole.USER,
                content = "see this photo",
                timestamp = 1L,
                attachments = listOf(Attachment(contentType = "image/jpeg", content = "jpegbytes")),
            )
        )

        handler.loadMessageHistory(
            listOf(MessageItem(id = "55", role = "user", content = JsonPrimitive("see this photo")))
        )

        val msg = handler.messages.value.single()
        assertEquals("55", msg.id)
        assertEquals(1, msg.attachments.size)
        assertEquals("jpegbytes", msg.attachments[0].content)
    }

    @Test
    fun loadMessageHistory_doesNotCarryInboundAttachment() {
        // Inbound attachments (relayToken != null) come back via the marker
        // re-dispatch; carrying them in the reload too would double-add them.
        handler.addUserMessage(
            ChatMessage(
                id = "client-uuid-xyz",
                role = MessageRole.USER,
                content = "fetched file",
                timestamp = 1L,
                attachments = listOf(
                    Attachment(contentType = "image/png", content = "", relayToken = "tok-123"),
                ),
            )
        )

        handler.loadMessageHistory(
            listOf(MessageItem(id = "77", role = "user", content = JsonPrimitive("fetched file")))
        )

        assertTrue(handler.messages.value.single().attachments.isEmpty())
    }

    @Test
    fun loadMessageHistory_consumesOutboundAttachmentOncePerDuplicateContent() {
        // Two identical-text sends, only the first with an attachment: the
        // attachment must land on exactly one reloaded row, not both.
        handler.addUserMessage(
            ChatMessage(
                id = "uuid-1",
                role = MessageRole.USER,
                content = "hi",
                timestamp = 1L,
                attachments = listOf(Attachment(contentType = "image/png", content = "first")),
            )
        )
        handler.addUserMessage(
            ChatMessage(id = "uuid-2", role = MessageRole.USER, content = "hi", timestamp = 2L)
        )

        handler.loadMessageHistory(
            listOf(
                MessageItem(id = "10", role = "user", content = JsonPrimitive("hi"), timestamp = 1.0),
                MessageItem(id = "11", role = "user", content = JsonPrimitive("hi"), timestamp = 2.0),
            )
        )

        val msgs = handler.messages.value
        assertEquals(2, msgs.size)
        assertEquals(1, msgs[0].attachments.size)
        assertEquals("first", msgs[0].attachments[0].content)
        assertTrue(msgs[1].attachments.isEmpty())
    }

    // --- loadMessageHistory: client-only orphan preservation (GAP 2) ---

    @Test
    fun loadMessageHistory_preservesClientOnlyOrphan() {
        // A slash-command notice (addSystemNotice → clientOnly) has no server
        // row; a reload that omits its id must keep it.
        handler.addSystemNotice("/status result")

        handler.loadMessageHistory(
            listOf(MessageItem(id = "1", role = "user", content = JsonPrimitive("hi"), timestamp = 1.0))
        )

        val msgs = handler.messages.value
        assertEquals(2, msgs.size)
        assertTrue(
            msgs.any { it.role == MessageRole.SYSTEM && it.content == "/status result" && it.clientOnly }
        )
    }

    @Test
    fun loadMessageHistory_preservesErroredAssistantOrphan() {
        // markError flags the bubble clientOnly because the gateway ❌ path fires
        // on a turn the server never persists — so a later reload that omits the
        // id must keep the error visible.
        handler.onTextDelta("assist-err", "partial reply")
        handler.markError("assist-err")

        handler.loadMessageHistory(
            listOf(MessageItem(id = "u1", role = "user", content = JsonPrimitive("do it"), timestamp = 1.0))
        )

        val orphan = handler.messages.value.single { it.id == "assist-err" }
        assertTrue("Error" in orphan.badges)
        assertTrue(orphan.clientOnly)
    }

    @Test
    fun loadMessageHistory_reconcilesPersistedErrorWithoutDuplicating() {
        // A turn that errors AFTER persisting keeps an "Error" badge but IS in
        // the transcript: it must reconcile as one server-backed message (badge
        // carried via priorById), never get double-added as a clientOnly orphan.
        handler.onTextDelta("100", "reply that errored after persisting")
        handler.markError("100")

        handler.loadMessageHistory(
            listOf(
                MessageItem(
                    id = "100",
                    role = "assistant",
                    content = JsonPrimitive("reply that errored after persisting"),
                )
            )
        )

        val matches = handler.messages.value.filter { it.id == "100" }
        assertEquals(1, matches.size)
        assertTrue("Error" in matches[0].badges)
    }

    @Test
    fun loadMessageHistory_dropsServerBackedMessageRemovedFromTranscript() {
        // A normal (non-clientOnly) assistant turn that's no longer in the
        // transcript was genuinely deleted/forked server-side — drop it.
        handler.onTextDelta("a1", "old reply")
        handler.onStreamComplete("a1")

        handler.loadMessageHistory(
            listOf(MessageItem(id = "b2", role = "assistant", content = JsonPrimitive("new reply")))
        )

        val msgs = handler.messages.value
        assertEquals(1, msgs.size)
        assertEquals("b2", msgs[0].id)
    }

    @Test
    fun attachRealtimeTurnTrace_marksBubbleClientOnly() {
        // A provider-only realtime turn (trace attached) has no server row, so
        // attaching the trace must also flag it clientOnly for reload survival.
        handler.onTextDelta("realtime-agent-1", "spoken answer")
        handler.attachRealtimeTurnTrace(
            "realtime-agent-1",
            RealtimeTurnTrace(userText = "hi", assistantText = "spoken answer"),
        )

        val msg = handler.messages.value.single { it.id == "realtime-agent-1" }
        assertTrue(msg.clientOnly)
        assertEquals("hi", msg.realtimeTurn!!.userText)
    }

    // --- loadMessageHistory: delta-merge semantics (GAP 3) ---

    @Test
    fun loadMessageHistory_updatesServerContentInPlace() {
        // (b) a server message's content updates in place on the matching id.
        handler.onTextDelta("100", "old content")
        handler.onStreamComplete("100")

        handler.loadMessageHistory(
            listOf(MessageItem(id = "100", role = "assistant", content = JsonPrimitive("updated content")))
        )

        val msg = handler.messages.value.single { it.id == "100" }
        assertEquals("updated content", msg.content)
        assertFalse(msg.isStreaming)
    }

    @Test
    fun loadMessageHistory_keepsTokensAndBadgesOnInPlaceUpdate() {
        // (d) per-message tokens/cost/badges (not server-persisted) survive the
        // in-place update while content is refreshed from the server.
        handler.addPlaceholderMessage(
            ChatMessage(
                id = "a-1",
                role = MessageRole.ASSISTANT,
                content = "draft",
                timestamp = 1L,
                badges = listOf("Voice"),
                inputTokens = 7,
                outputTokens = 11,
                totalTokens = 18,
                estimatedCost = 0.002,
            )
        )

        handler.loadMessageHistory(
            listOf(MessageItem(id = "a-1", role = "assistant", content = JsonPrimitive("final answer")))
        )

        val msg = handler.messages.value.single { it.id == "a-1" }
        assertEquals("final answer", msg.content)
        assertEquals(listOf("Voice"), msg.badges)
        assertEquals(7, msg.inputTokens)
        assertEquals(18, msg.totalTokens)
        assertEquals(0.002, msg.estimatedCost!!, 0.0001)
    }

    @Test
    fun loadMessageHistory_reflectsServerSideDeletion() {
        // (c) two server-backed turns, then a reload that omits the first one
        // (deleted/forked/truncated server-side) drops it.
        handler.onTextDelta("m1", "first")
        handler.onStreamComplete("m1")
        handler.onTextDelta("m2", "second")
        handler.onStreamComplete("m2")
        assertEquals(2, handler.messages.value.size)

        handler.loadMessageHistory(
            listOf(MessageItem(id = "m2", role = "assistant", content = JsonPrimitive("second")))
        )

        val msgs = handler.messages.value
        assertEquals(1, msgs.size)
        assertEquals("m2", msgs[0].id)
    }

    @Test
    fun loadMessageHistory_keepsLiveThinkingWhenServerOmitsReasoning() {
        // The delta-merge must not blank live-streamed reasoning when the server
        // transcript carries none for that message.
        handler.onThinkingDelta("t-1", "reasoning trace")
        handler.onTextDelta("t-1", "answer")
        handler.onStreamComplete("t-1")

        handler.loadMessageHistory(
            listOf(MessageItem(id = "t-1", role = "assistant", content = JsonPrimitive("answer")))
        )

        assertEquals("reasoning trace", handler.messages.value.single { it.id == "t-1" }.thinkingContent)
    }

    @Test
    fun loadMessageHistory_prefersServerReasoningOnUpdate() {
        // When the server DOES persist reasoning it is authoritative.
        handler.onThinkingDelta("t-2", "live reasoning")
        handler.onStreamComplete("t-2")

        handler.loadMessageHistory(
            listOf(
                MessageItem(
                    id = "t-2",
                    role = "assistant",
                    content = JsonPrimitive("a"),
                    reasoning = "server reasoning",
                )
            )
        )

        assertEquals("server reasoning", handler.messages.value.single { it.id == "t-2" }.thinkingContent)
    }

    // --- loadMessageHistory: live→server id reconciliation ---

    @Test
    fun loadMessageHistory_adoptsServerIdForGatewayUuidRows_carriesStateById() {
        // Gateway-style: live rows keep client UUIDs (no mid-turn server id); the
        // reloaded transcript carries the server ids. Positional (role+content)
        // reconciliation must adopt those ids so tokens/badges/attachments carry
        // BY ID, in place — not drop-and-reinsert, not luck-of-the-content-match.
        handler.addUserMessage(
            ChatMessage(
                id = "uuid-user",
                role = MessageRole.USER,
                content = "run a tool",
                timestamp = 1L,
                attachments = listOf(Attachment(contentType = "image/png", content = "outb64")),
            )
        )
        handler.addPlaceholderMessage(
            ChatMessage(
                id = "uuid-assistant",
                role = MessageRole.ASSISTANT,
                content = "done",
                timestamp = 2L,
                badges = listOf("Voice"),
                inputTokens = 5,
                outputTokens = 9,
                totalTokens = 14,
                estimatedCost = 0.003,
            )
        )

        handler.loadMessageHistory(
            listOf(
                MessageItem(id = "srv-1", role = "user", content = JsonPrimitive("run a tool"), timestamp = 1.0),
                MessageItem(id = "srv-2", role = "assistant", content = JsonPrimitive("done"), timestamp = 2.0),
            )
        )

        val msgs = handler.messages.value
        assertEquals(2, msgs.size)
        val user = msgs.single { it.role == MessageRole.USER }
        val assistant = msgs.single { it.role == MessageRole.ASSISTANT }
        // Server ids adopted onto the live rows.
        assertEquals("srv-1", user.id)
        assertEquals("srv-2", assistant.id)
        // Compose identity stays on the live rows. A same-count post-turn
        // reload must not remove/reinsert the two bubbles just because their
        // authoritative ids arrived.
        assertEquals("uuid-user", user.uiKey)
        assertEquals("uuid-assistant", assistant.uiKey)
        // State carried by id, in place.
        assertEquals(1, user.attachments.size)
        assertEquals("outb64", user.attachments[0].content)
        assertEquals(listOf("Voice"), assistant.badges)
        assertEquals(5, assistant.inputTokens)
        assertEquals(14, assistant.totalTokens)
        assertEquals(0.003, assistant.estimatedCost!!, 0.0001)
        assertFalse(assistant.isStreaming)
    }

    @Test
    fun loadMessageHistory_listRebuildPreservesMatchedTailUiKey() {
        // Some persisted turns rebuild one live streaming bubble into several
        // server rows (for example, restored message boundaries/tool output).
        // The reconciled tail must retain its UI identity even while new rows
        // are inserted around it, otherwise LazyColumn loses the viewport
        // anchor on a long answer.
        handler.addPlaceholderMessage(
            ChatMessage(
                id = "uuid-live-tail",
                role = MessageRole.ASSISTANT,
                content = "final chunk",
                timestamp = 3L,
                isStreaming = false,
            )
        )

        handler.loadMessageHistory(
            listOf(
                MessageItem(id = "srv-user", role = "user", content = JsonPrimitive("question"), timestamp = 1.0),
                MessageItem(id = "srv-prefix", role = "assistant", content = JsonPrimitive("earlier chunk"), timestamp = 2.0),
                MessageItem(id = "srv-tail", role = "assistant", content = JsonPrimitive("final chunk"), timestamp = 3.0),
            )
        )

        val messages = handler.messages.value
        assertEquals(3, messages.size)
        assertEquals("uuid-live-tail", messages.single { it.id == "srv-tail" }.uiKey)
        assertEquals("srv-user", messages.single { it.id == "srv-user" }.uiKey)
        assertEquals("srv-prefix", messages.single { it.id == "srv-prefix" }.uiKey)
        assertEquals(messages.size, messages.map { it.uiKey }.distinct().size)
    }

    @Test
    fun loadMessageHistory_secondReloadMatchesByIdAfterReconciliation() {
        // Once the first reload adopts the server id, subsequent reloads match by
        // id and update in place while keeping client-only state.
        handler.addPlaceholderMessage(
            ChatMessage(
                id = "uuid-a",
                role = MessageRole.ASSISTANT,
                content = "answer",
                timestamp = 1L,
                inputTokens = 3,
                badges = listOf("Voice"),
            )
        )

        handler.loadMessageHistory(
            listOf(MessageItem(id = "srv-1", role = "assistant", content = JsonPrimitive("answer")))
        )
        assertEquals("srv-1", handler.messages.value.single().id)

        handler.loadMessageHistory(
            listOf(MessageItem(id = "srv-1", role = "assistant", content = JsonPrimitive("answer v2")))
        )

        val msg = handler.messages.value.single()
        assertEquals("srv-1", msg.id)
        assertEquals("answer v2", msg.content)
        assertEquals(3, msg.inputTokens)
        assertEquals(listOf("Voice"), msg.badges)
    }

    @Test
    fun loadMessageHistory_doesNotAdoptServerIdForClientOnlyOrphan() {
        // A clientOnly bubble whose content happens to match a server row must
        // NOT be mapped to it — it has no server identity; it stays an orphan and
        // the server row inserts independently.
        handler.appendLocalVoiceIntentResult(description = "done")

        handler.loadMessageHistory(
            listOf(MessageItem(id = "srv-9", role = "assistant", content = JsonPrimitive("done")))
        )

        val msgs = handler.messages.value
        assertEquals(2, msgs.size)
        assertTrue(msgs.any { it.id == "srv-9" && it.role == MessageRole.ASSISTANT })
        assertTrue(msgs.any { it.id.startsWith("voice-intent-result-") && it.clientOnly })
    }

    @Test
    fun loadMessageHistory_leavesRowUnmappedWhenContentDiverges() {
        // Live UUID row whose content does NOT match any server row → not mapped:
        // the live row drops, the server row inserts, and nothing is force-carried
        // (no wrong map on count/content divergence).
        handler.addUserMessage(
            ChatMessage(
                id = "uuid-x",
                role = MessageRole.USER,
                content = "hello",
                timestamp = 1L,
                attachments = listOf(Attachment(contentType = "image/png", content = "b64")),
            )
        )

        handler.loadMessageHistory(
            listOf(MessageItem(id = "srv-x", role = "user", content = JsonPrimitive("totally different")))
        )

        val msgs = handler.messages.value
        assertEquals(1, msgs.size)
        assertEquals("srv-x", msgs[0].id)
        assertEquals("totally different", msgs[0].content)
        assertTrue(msgs[0].attachments.isEmpty())
    }

    @Test
    fun loadMessageHistory_reconcilesMarkerBearingAssistantRowByStrippedContent() {
        // Live assistant content is marker-stripped during streaming; the server
        // row still carries the raw MEDIA: marker. Reconciliation strips markers
        // on both sides so the row still matches and carries its tokens by id.
        handler.addPlaceholderMessage(
            ChatMessage(
                id = "uuid-shot",
                role = MessageRole.ASSISTANT,
                content = "here is the screenshot",
                timestamp = 1L,
                inputTokens = 8,
            )
        )

        handler.loadMessageHistory(
            listOf(
                MessageItem(
                    id = "srv-shot",
                    role = "assistant",
                    content = JsonPrimitive("here is the screenshot\nMEDIA:hermes-relay://tok123"),
                )
            )
        )

        val msg = handler.messages.value.single()
        assertEquals("srv-shot", msg.id)
        assertEquals(8, msg.inputTokens)
    }

    // --- onUsageReceived ---

    @Test
    fun onUsageReceived_setsTokenFields() {
        handler.onTextDelta("assist-1", "response text")
        handler.onUsageReceived("assist-1", inputTokens = 100, outputTokens = 200, totalTokens = 300, cost = 0.005)

        val msg = handler.messages.value[0]
        assertEquals(100, msg.inputTokens)
        assertEquals(200, msg.outputTokens)
        assertEquals(300, msg.totalTokens)
        assertEquals(0.005, msg.estimatedCost!!, 0.0001)
    }

    @Test
    fun onUsageReceived_handlesNullValues() {
        handler.onTextDelta("assist-1", "text")
        handler.onUsageReceived("assist-1", inputTokens = null, outputTokens = null, totalTokens = null, cost = null)

        val msg = handler.messages.value[0]
        assertNull(msg.inputTokens)
        assertNull(msg.outputTokens)
        assertNull(msg.totalTokens)
        assertNull(msg.estimatedCost)
    }

    // --- setLastSentMessage ---

    @Test
    fun setLastSentMessage_storesValue() {
        handler.setLastSentMessage("retry this")
        assertEquals("retry this", handler.lastSentMessage.value)
    }

    // --- Smoke tests for thread safety ---

    @Test
    fun concurrentOperations_doNotCrash() {
        // Rapid-fire operations that exercise all state paths
        repeat(100) { i ->
            handler.onTextDelta("msg-$i", "delta-$i ")
            handler.onToolCallStart("msg-$i", "call-$i", "tool_$i")
            handler.onToolCallComplete("msg-$i", "call-$i", "result")
            handler.onStreamComplete("msg-$i")
        }

        // Should have 100 messages, none still streaming
        assertEquals(100, handler.messages.value.size)
        assertFalse(handler.isStreaming.value)
    }

    // --- Voice intent → server session sync (v0.4.1) ---

    @Test
    fun appendLocalVoiceIntentTrace_storesStructuredVoiceIntent() {
        val trace = VoiceIntentTrace(
            toolName = "android_open_app",
            argumentsJson = """{"app_name":"Chrome"}""",
            success = true,
            resultJson = """{"ok":true}""",
        )
        handler.appendLocalVoiceIntentTrace(
            userText = "open chrome",
            actionDescription = "**Opened Chrome**",
            voiceIntent = trace,
        )
        val messages = handler.messages.value
        // user bubble + assistant bubble
        assertEquals(2, messages.size)
        // The user bubble carries no trace (it's the raw utterance)
        assertNull(messages[0].voiceIntent)
        // The assistant action bubble owns the structured trace
        assertEquals(trace, messages[1].voiceIntent)
        assertFalse(messages[1].voiceIntent!!.syncedToServer)
    }

    @Test
    fun appendLocalVoiceIntentResult_storesStructuredVoiceIntent() {
        val trace = VoiceIntentTrace(
            toolName = "android_send_sms",
            argumentsJson = """{"to":"+15551234567","body":"hi"}""",
            success = true,
            resultJson = """{"ok":true}""",
        )
        handler.appendLocalVoiceIntentResult(
            description = "**Send SMS — sent** ✓",
            voiceIntent = trace,
        )
        val messages = handler.messages.value
        assertEquals(1, messages.size)
        assertEquals(trace, messages[0].voiceIntent)
    }

    @Test
    fun markVoiceIntentsSynced_flipsAllTracesToTrue() {
        handler.appendLocalVoiceIntentTrace(
            userText = "open chrome",
            actionDescription = "**Opened**",
            voiceIntent = VoiceIntentTrace("android_open_app", "{}", true, "{}"),
        )
        handler.appendLocalVoiceIntentResult(
            description = "**Send SMS — sent**",
            voiceIntent = VoiceIntentTrace("android_send_sms", "{}", true, "{}"),
        )

        // Pre-condition: both traces unsynced
        assertEquals(2, handler.messages.value.count { it.voiceIntent?.syncedToServer == false })

        handler.markVoiceIntentsSynced()

        // Post-condition: every trace marked synced; non-trace messages untouched
        val msgs = handler.messages.value
        assertTrue(msgs.all { it.voiceIntent == null || it.voiceIntent!!.syncedToServer })
        // Already-synced traces are not double-flipped (same state)
        handler.markVoiceIntentsSynced()
        assertTrue(handler.messages.value.all {
            it.voiceIntent == null || it.voiceIntent!!.syncedToServer
        })
    }

    @Test
    fun markVoiceIntentsSynced_skipsMessagesWithoutTraces() {
        handler.addUserMessage(createUserMessage("plain", "hello"))
        handler.markVoiceIntentsSynced()
        // No crash, plain message preserved unchanged
        assertEquals(1, handler.messages.value.size)
        assertNull(handler.messages.value[0].voiceIntent)
    }


    // --- Relay typed stream.event rendering ---

    @Test
    fun applyRelayStreamEvent_rendersAssistantDeltaToolLifecycleAndDone() {
        handler.addPlaceholderMessage(
            ChatMessage(
                id = "assist-relay",
                role = MessageRole.ASSISTANT,
                content = "",
                timestamp = 1L,
                isStreaming = true,
            )
        )

        handler.applyRelayStreamEvent(
            "assist-relay",
            RelayStreamEventEnvelope(
                sessionId = "sess-1",
                runId = "run-1",
                seq = 1,
                event = "assistant.delta",
                payload = buildJsonObject { put("delta", "Hello") },
            ),
        )
        handler.applyRelayStreamEvent(
            "assist-relay",
            RelayStreamEventEnvelope(
                sessionId = "sess-1",
                runId = "run-1",
                seq = 2,
                event = "tool.started",
                payload = buildJsonObject {
                    put("tool_name", "terminal")
                    put("call_id", "call-1")
                },
            ),
        )
        handler.applyRelayStreamEvent(
            "assist-relay",
            RelayStreamEventEnvelope(
                sessionId = "sess-1",
                runId = "run-1",
                seq = 3,
                event = "tool.completed",
                payload = buildJsonObject {
                    put("tool_name", "terminal")
                    put("call_id", "call-1")
                    put("result_preview", "ok")
                },
            ),
        )
        handler.applyRelayStreamEvent(
            "assist-relay",
            RelayStreamEventEnvelope(
                sessionId = "sess-1",
                runId = "run-1",
                seq = 4,
                event = "done",
                payload = buildJsonObject { put("state", "final") },
            ),
        )

        val msg = handler.messages.value.single()
        assertEquals("Hello", msg.content)
        assertFalse(msg.isStreaming)
        assertEquals(1, msg.toolCalls.size)
        assertEquals("terminal", msg.toolCalls[0].name)
        assertTrue(msg.toolCalls[0].isComplete)
        assertEquals("ok", msg.toolCalls[0].result)
    }

    @Test
    fun applyRelayStreamEvent_rendersProgressArtifactAndErrorStates() {
        handler.addPlaceholderMessage(
            ChatMessage(
                id = "assist-relay",
                role = MessageRole.ASSISTANT,
                content = "",
                timestamp = 1L,
                isStreaming = true,
            )
        )

        handler.applyRelayStreamEvent(
            "assist-relay",
            RelayStreamEventEnvelope(
                sessionId = "sess-1",
                runId = "run-1",
                seq = 1,
                event = "tool.progress",
                payload = buildJsonObject { put("delta", "Thinking...") },
            ),
        )
        handler.applyRelayStreamEvent(
            "assist-relay",
            RelayStreamEventEnvelope(
                sessionId = "sess-1",
                runId = "run-1",
                seq = 2,
                event = "artifact.created",
                payload = buildJsonObject { put("url", "https://example.invalid/artifact") },
            ),
        )
        handler.applyRelayStreamEvent(
            "assist-relay",
            RelayStreamEventEnvelope(
                sessionId = "sess-1",
                runId = "run-1",
                seq = 3,
                event = "error",
                payload = buildJsonObject { put("message", "boom") },
            ),
        )

        val msg = handler.messages.value.single()
        assertTrue(msg.thinkingContent.contains("Thinking..."))
        assertTrue(msg.thinkingContent.contains("Artifact:"))
        assertTrue(msg.badges.contains("Error"))
        assertEquals("boom", handler.error.value)
    }

    // --- loadMessageHistory: synced realtime-turn provenance (marker → badge) ---

    @Test
    fun loadMessageHistory_stripsRealtimeProvenanceMarkerIntoBadge() {
        // A provider-answered realtime turn synced by RealtimeTurnSyncBuilder
        // comes back in server history with a trailing provenance marker. The
        // reload must strip the bracket noise and restore the same quiet
        // "Realtime Agent" badge a live turn gets.
        handler.loadMessageHistory(
            listOf(
                MessageItem(
                    id = "1",
                    role = "assistant",
                    content = JsonPrimitive(
                        "It syncs vault metadata.\n\n" +
                            "[Realtime Agent provider-native voice turn: " +
                            "provider=xai_realtime, model=grok-voice-latest]",
                    ),
                ),
            ),
        )

        val msg = handler.messages.value.single()
        assertEquals("It syncs vault metadata.", msg.content)
        assertTrue(msg.badges.contains("Realtime Agent"))
    }

    @Test
    fun loadMessageHistory_noBadgeWithoutProvenanceMarker() {
        handler.loadMessageHistory(
            listOf(
                MessageItem(id = "1", role = "assistant", content = JsonPrimitive("Plain reply")),
            ),
        )

        val msg = handler.messages.value.single()
        assertEquals("Plain reply", msg.content)
        assertFalse(msg.badges.contains("Realtime Agent"))
    }

    @Test
    fun loadMessageHistory_dropsSyncedRealtimeOrphanSupersededByServerCopy() {
        // Once a provider-answered turn's synced copy exists in the server
        // transcript, the pre-sync local clientOnly bubble is redundant —
        // preserving both would render the exchange twice.
        handler.onTextDelta("realtime-agent-1", "It syncs vault metadata.")
        handler.attachRealtimeTurnTrace(
            "realtime-agent-1",
            RealtimeTurnTrace(
                userText = "What does it do?",
                assistantText = "It syncs vault metadata.",
                provider = "xai_realtime",
            ),
        )
        handler.markRealtimeTurnsSynced()

        handler.loadMessageHistory(
            listOf(
                MessageItem(id = "10", role = "user", content = JsonPrimitive("What does it do?")),
                MessageItem(
                    id = "11",
                    role = "assistant",
                    content = JsonPrimitive(
                        "It syncs vault metadata.\n\n" +
                            "[Realtime Agent provider-native voice turn: provider=xai_realtime]",
                    ),
                ),
            ),
        )

        val msgs = handler.messages.value
        // Only the server pair remains — the local orphan was dropped.
        assertEquals(2, msgs.size)
        assertTrue(msgs.none { it.id == "realtime-agent-1" })
        val serverCopy = msgs.single { it.id == "11" }
        assertEquals("It syncs vault metadata.", serverCopy.content)
        assertTrue(serverCopy.badges.contains("Realtime Agent"))
    }

    @Test
    fun loadMessageHistory_preservesUnsyncedRealtimeOrphan() {
        // An UNSYNCED trace is still the only record of the turn — it must
        // survive the reload even though it has no server row.
        handler.onTextDelta("realtime-agent-1", "spoken answer")
        handler.attachRealtimeTurnTrace(
            "realtime-agent-1",
            RealtimeTurnTrace(userText = "hi", assistantText = "spoken answer"),
        )

        handler.loadMessageHistory(
            listOf(
                MessageItem(id = "10", role = "user", content = JsonPrimitive("unrelated")),
            ),
        )

        val orphan = handler.messages.value.single { it.id == "realtime-agent-1" }
        assertEquals("spoken answer", orphan.content)
        assertFalse(orphan.realtimeTurn!!.syncedToServer)
    }

    @Test
    fun restoreInFlightTurn_restoresThinkingAndToolState_withoutDuplicatingPersistedUser() {
        handler.addUserMessage(createUserMessage("old-user", "Earlier"))
        handler.addPlaceholderMessage(
            ChatMessage(
                id = "old-assistant",
                role = MessageRole.ASSISTANT,
                content = "Earlier answer",
                timestamp = 2L,
                isStreaming = true,
            ),
        )
        handler.onStreamComplete("old-assistant")
        // Models history having persisted the pending user before Android
        // reopens; the restore must not append a second identical row.
        handler.addUserMessage(createUserMessage("server-user", "Run the checks"))
        val checkpoint = ChatTurnCheckpoint(
            contextKey = "connection/profile",
            sessionId = "stored-1",
            liveSessionId = "live-1",
            transport = "gateway",
            user = ChatTurnUserCheckpoint("local-user", "Run the checks", 3L),
            assistant = ChatTurnAssistantCheckpoint(
                id = "assistant-live",
                content = "I am checking",
                timestamp = 4L,
                thinkingContent = "Inspect the project first",
                isThinkingStreaming = true,
                toolCalls = listOf(
                    ChatTurnToolCheckpoint(
                        id = "tool-1",
                        name = "terminal",
                        isComplete = false,
                        startedAt = 5L,
                    ),
                    ChatTurnToolCheckpoint(
                        id = "tool-2",
                        name = "search",
                        result = "3 matches",
                        success = true,
                        isComplete = true,
                        startedAt = 6L,
                        completedAt = 7L,
                    ),
                ),
            ),
            turnStatus = "Running terminal",
            priorUserMessageCount = 1,
            baselineAssistantCount = 1,
            startedAt = 4L,
            updatedAt = 8L,
        )

        handler.restoreInFlightTurn(checkpoint, upstreamAssistantText = "I am checking the tests")

        assertEquals(2, handler.messages.value.count { it.role == MessageRole.USER })
        val restored = handler.messages.value.single { it.id == "assistant-live" }
        assertEquals("I am checking the tests", restored.content)
        assertEquals("Inspect the project first", restored.thinkingContent)
        assertTrue(restored.isThinkingStreaming)
        assertFalse(restored.toolCalls[0].isComplete)
        assertTrue(restored.toolCalls[1].isComplete)
        assertEquals(true, restored.toolCalls[1].success)
        assertTrue(handler.isStreaming.value)
        assertEquals("Running terminal", handler.turnStatus.value)
    }

    // --- Helper ---

    private fun createUserMessage(id: String, content: String) = ChatMessage(
        id = id,
        role = MessageRole.USER,
        content = content,
        timestamp = System.currentTimeMillis()
    )
}
