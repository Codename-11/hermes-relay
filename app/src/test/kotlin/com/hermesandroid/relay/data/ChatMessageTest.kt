package com.hermesandroid.relay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for ChatMessage, ToolCall, MessageRole, and ChatSession data models.
 */
class ChatMessageTest {

    // --- ChatMessage creation with defaults ---

    @Test
    fun chatMessage_creation_withRequiredFields() {
        val msg = ChatMessage(
            id = "msg-1",
            role = MessageRole.USER,
            content = "Hello",
            timestamp = 1700000000L
        )

        assertEquals("msg-1", msg.id)
        assertEquals(MessageRole.USER, msg.role)
        assertEquals("Hello", msg.content)
        assertEquals(1700000000L, msg.timestamp)
    }

    @Test
    fun chatMessage_defaults_isStreamingFalse() {
        val msg = ChatMessage(
            id = "1", role = MessageRole.USER, content = "", timestamp = 0L
        )
        assertFalse(msg.isStreaming)
    }

    @Test
    fun chatMessage_defaults_emptyToolCalls() {
        val msg = ChatMessage(
            id = "1", role = MessageRole.USER, content = "", timestamp = 0L
        )
        assertTrue(msg.toolCalls.isEmpty())
    }

    @Test
    fun chatMessage_defaults_emptyThinkingContent() {
        val msg = ChatMessage(
            id = "1", role = MessageRole.USER, content = "", timestamp = 0L
        )
        assertEquals("", msg.thinkingContent)
    }

    @Test
    fun chatMessage_defaults_isThinkingStreamingFalse() {
        val msg = ChatMessage(
            id = "1", role = MessageRole.USER, content = "", timestamp = 0L
        )
        assertFalse(msg.isThinkingStreaming)
    }

    @Test
    fun chatMessage_defaults_nullTokenFields() {
        val msg = ChatMessage(
            id = "1", role = MessageRole.USER, content = "", timestamp = 0L
        )
        assertNull(msg.inputTokens)
        assertNull(msg.outputTokens)
        assertNull(msg.totalTokens)
        assertNull(msg.estimatedCost)
    }

    @Test
    fun chatMessage_withAllFields() {
        val toolCalls = listOf(
            ToolCall(id = "tc-1", name = "read_file", args = "{}", result = "contents", success = true, isComplete = true)
        )
        val msg = ChatMessage(
            id = "msg-2",
            role = MessageRole.ASSISTANT,
            content = "Here is the file",
            timestamp = 1700000000L,
            isStreaming = true,
            toolCalls = toolCalls,
            thinkingContent = "Let me check...",
            isThinkingStreaming = true,
            inputTokens = 50,
            outputTokens = 100,
            totalTokens = 150,
            estimatedCost = 0.003
        )

        assertTrue(msg.isStreaming)
        assertEquals(1, msg.toolCalls.size)
        assertEquals("Let me check...", msg.thinkingContent)
        assertTrue(msg.isThinkingStreaming)
        assertEquals(50, msg.inputTokens)
        assertEquals(100, msg.outputTokens)
        assertEquals(150, msg.totalTokens)
        assertEquals(0.003, msg.estimatedCost!!, 0.0001)
    }

    @Test
    fun chatMessage_copy_preservesFields() {
        val original = ChatMessage(
            id = "msg-1",
            role = MessageRole.ASSISTANT,
            content = "Hello",
            timestamp = 1000L,
            isStreaming = true
        )

        val copy = original.copy(content = "Hello world", isStreaming = false)

        assertEquals("msg-1", copy.id)
        assertEquals(MessageRole.ASSISTANT, copy.role)
        assertEquals("Hello world", copy.content)
        assertEquals(1000L, copy.timestamp)
        assertFalse(copy.isStreaming)
    }

    // --- MessageRole enum ---

    @Test
    fun messageRole_hasAllExpectedValues() {
        val values = MessageRole.values()
        assertEquals(3, values.size)
    }

    @Test
    fun messageRole_user() {
        assertEquals(MessageRole.USER, MessageRole.valueOf("USER"))
    }

    @Test
    fun messageRole_assistant() {
        assertEquals(MessageRole.ASSISTANT, MessageRole.valueOf("ASSISTANT"))
    }

    @Test
    fun messageRole_system() {
        assertEquals(MessageRole.SYSTEM, MessageRole.valueOf("SYSTEM"))
    }

    // --- ToolCall ---

    @Test
    fun toolCall_creation_withDefaults() {
        val tc = ToolCall(
            name = "read_file",
            args = null,
            result = null,
            success = null
        )

        assertNull(tc.id)
        assertEquals("read_file", tc.name)
        assertNull(tc.args)
        assertNull(tc.result)
        assertNull(tc.success)
        assertFalse(tc.isComplete)
        assertNull(tc.error)
        assertNotNull(tc.startedAt) // Default to System.currentTimeMillis()
        assertNull(tc.completedAt)
    }

    @Test
    fun toolCall_pending_state() {
        val tc = ToolCall(
            id = "call-1",
            name = "write_file",
            args = """{"path": "test.txt"}""",
            result = null,
            success = null,
            isComplete = false
        )

        assertFalse(tc.isComplete)
        assertNull(tc.success)
        assertNull(tc.result)
    }

    @Test
    fun toolCall_completed_state() {
        val now = System.currentTimeMillis()
        val tc = ToolCall(
            id = "call-1",
            name = "read_file",
            args = """{"path": "data.json"}""",
            result = "file contents here",
            success = true,
            isComplete = true,
            completedAt = now
        )

        assertTrue(tc.isComplete)
        assertEquals(true, tc.success)
        assertEquals("file contents here", tc.result)
        assertEquals(now, tc.completedAt)
        assertNull(tc.error)
    }

    @Test
    fun toolCall_failed_state() {
        val tc = ToolCall(
            id = "call-2",
            name = "dangerous_operation",
            args = null,
            result = null,
            success = false,
            isComplete = true,
            error = "Permission denied",
            completedAt = System.currentTimeMillis()
        )

        assertTrue(tc.isComplete)
        assertEquals(false, tc.success)
        assertEquals("Permission denied", tc.error)
    }

    @Test
    fun toolCall_copy_updatesCompletion() {
        val pending = ToolCall(
            id = "call-1",
            name = "tool",
            args = null,
            result = null,
            success = null,
            isComplete = false
        )

        val completed = pending.copy(
            success = true,
            isComplete = true,
            result = "done",
            completedAt = System.currentTimeMillis()
        )

        assertFalse(pending.isComplete)
        assertTrue(completed.isComplete)
        assertEquals(true, completed.success)
        assertEquals("done", completed.result)
    }

    @Test
    fun toolCall_startedAt_defaultsToCurrentTime() {
        val before = System.currentTimeMillis()
        val tc = ToolCall(name = "tool", args = null, result = null, success = null)
        val after = System.currentTimeMillis()

        assertTrue(tc.startedAt >= before)
        assertTrue(tc.startedAt <= after)
    }

    @Test
    fun toolCall_duration_calculable() {
        val startTime = 1000L
        val endTime = 2500L
        val tc = ToolCall(
            name = "slow_tool",
            args = null,
            result = "done",
            success = true,
            isComplete = true,
            startedAt = startTime,
            completedAt = endTime
        )

        val duration = tc.completedAt!! - tc.startedAt
        assertEquals(1500L, duration)
    }

    // --- ChatSession ---

    @Test
    fun chatSession_creation_withAllFields() {
        val session = ChatSession(
            sessionId = "sess-1",
            title = "My Session",
            model = "gpt-4",
            messageCount = 10,
            updatedAt = 1700000000L
        )

        assertEquals("sess-1", session.sessionId)
        assertEquals("My Session", session.title)
        assertEquals("gpt-4", session.model)
        assertEquals(10, session.messageCount)
        assertEquals(1700000000L, session.updatedAt)
    }

    @Test
    fun chatSession_defaults() {
        val session = ChatSession(
            sessionId = "sess-2",
            title = null,
            model = null
        )

        assertNull(session.title)
        assertNull(session.model)
        assertEquals(0, session.messageCount)
        assertEquals(0L, session.updatedAt)
    }

    @Test
    fun chatSession_nullableFields() {
        val session = ChatSession(
            sessionId = "s1",
            title = null,
            model = null,
            messageCount = 5
        )

        assertNull(session.title)
        assertNull(session.model)
    }

    @Test
    fun chatSession_copy_updatesTitle() {
        val original = ChatSession(sessionId = "s1", title = "Old", model = "gpt-4")
        val renamed = original.copy(title = "New Title")

        assertEquals("New Title", renamed.title)
        assertEquals("s1", renamed.sessionId)
        assertEquals("gpt-4", renamed.model)
    }

    @Test
    fun chatSession_equality() {
        val a = ChatSession(sessionId = "s1", title = "Test", model = "gpt-4")
        val b = ChatSession(sessionId = "s1", title = "Test", model = "gpt-4")
        assertEquals(a, b)
    }
}
