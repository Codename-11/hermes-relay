package com.hermesandroid.relay.network.handlers

import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.ChatSession
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.data.ToolCall
import com.hermesandroid.relay.network.models.MessageItem
import com.hermesandroid.relay.network.models.SessionItem
import kotlinx.serialization.json.JsonPrimitive
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
    fun updateSessions_handlesNullMessageCount() {
        val item = SessionItem(id = "s1", messageCount = null)
        handler.updateSessions(listOf(item))

        assertEquals(0, handler.sessions.value[0].messageCount)
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

    // --- Helper ---

    private fun createUserMessage(id: String, content: String) = ChatMessage(
        id = id,
        role = MessageRole.USER,
        content = content,
        timestamp = System.currentTimeMillis()
    )
}
