package com.hermesandroid.relay.network.models

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for SessionModels kotlinx.serialization round-trips.
 */
class SessionModelsTest {

    private lateinit var json: Json

    @Before
    fun setUp() {
        json = Json { ignoreUnknownKeys = true }
    }

    // --- SessionItem ---

    @Test
    fun sessionItem_serialization_roundTrip() {
        val item = SessionItem(
            id = "sess-123",
            title = "My Session",
            model = "gpt-4",
            source = "api",
            startedAt = 1700000000.0,
            endedAt = 1700001000.0,
            messageCount = 10,
            toolCallCount = 3,
            inputTokens = 500,
            outputTokens = 1000
        )

        val jsonStr = json.encodeToString(item)
        val restored = json.decodeFromString<SessionItem>(jsonStr)

        assertEquals("sess-123", restored.id)
        assertEquals("My Session", restored.title)
        assertEquals("gpt-4", restored.model)
        assertEquals("api", restored.source)
        assertEquals(1700000000.0, restored.startedAt!!, 0.001)
        assertEquals(1700001000.0, restored.endedAt!!, 0.001)
        assertEquals(10, restored.messageCount)
        assertEquals(3, restored.toolCallCount)
        assertEquals(500, restored.inputTokens)
        assertEquals(1000, restored.outputTokens)
    }

    @Test
    fun sessionItem_deserialization_withSnakeCaseFields() {
        val jsonStr = """
            {
                "id": "s1",
                "title": "Test",
                "started_at": 1700000000.0,
                "ended_at": 1700001000.0,
                "message_count": 5,
                "tool_call_count": 2,
                "input_tokens": 100,
                "output_tokens": 200
            }
        """.trimIndent()

        val item = json.decodeFromString<SessionItem>(jsonStr)
        assertEquals("s1", item.id)
        assertEquals(1700000000.0, item.startedAt!!, 0.001)
        assertEquals(5, item.messageCount)
        assertEquals(2, item.toolCallCount)
    }

    @Test
    fun sessionItem_nullableFields_defaultToNull() {
        val jsonStr = """{"id": "s1"}"""
        val item = json.decodeFromString<SessionItem>(jsonStr)

        assertEquals("s1", item.id)
        assertNull(item.title)
        assertNull(item.model)
        assertNull(item.source)
        assertNull(item.startedAt)
        assertNull(item.endedAt)
        assertNull(item.messageCount)
        assertNull(item.toolCallCount)
        assertNull(item.inputTokens)
        assertNull(item.outputTokens)
    }

    // --- SessionListResponse ---

    @Test
    fun sessionListResponse_withItemsField() {
        val jsonStr = """
            {
                "items": [
                    {"id": "s1", "title": "Session 1"},
                    {"id": "s2", "title": "Session 2"}
                ],
                "total": 2
            }
        """.trimIndent()

        val response = json.decodeFromString<SessionListResponse>(jsonStr)
        assertNotNull(response.items)
        assertEquals(2, response.items!!.size)
        assertEquals("s1", response.items!![0].id)
        assertEquals("s2", response.items!![1].id)
        assertEquals(2, response.total)
    }

    @Test
    fun sessionListResponse_withSessionsField() {
        val jsonStr = """
            {
                "sessions": [
                    {"id": "s1", "title": "Session 1"}
                ]
            }
        """.trimIndent()

        val response = json.decodeFromString<SessionListResponse>(jsonStr)
        assertNull(response.items)
        assertNotNull(response.sessions)
        assertEquals(1, response.sessions!!.size)
        assertEquals("s1", response.sessions!![0].id)
    }

    @Test
    fun sessionListResponse_bothFieldsNull_whenEmpty() {
        val jsonStr = """{}"""
        val response = json.decodeFromString<SessionListResponse>(jsonStr)

        assertNull(response.items)
        assertNull(response.sessions)
    }

    // --- SessionResponse ---

    @Test
    fun sessionResponse_withNestedSession() {
        val jsonStr = """
            {
                "session": {
                    "id": "s1",
                    "title": "Created Session",
                    "model": "gpt-4"
                }
            }
        """.trimIndent()

        val response = json.decodeFromString<SessionResponse>(jsonStr)
        assertNotNull(response.session)
        assertEquals("s1", response.session!!.id)
        assertEquals("Created Session", response.session!!.title)
    }

    @Test
    fun sessionResponse_withFlatFields() {
        val jsonStr = """
            {
                "id": "s1",
                "title": "Flat Session",
                "model": "claude-3"
            }
        """.trimIndent()

        val response = json.decodeFromString<SessionResponse>(jsonStr)
        assertNull(response.session)
        assertEquals("s1", response.id)
        assertEquals("Flat Session", response.title)
        assertEquals("claude-3", response.model)
    }

    // --- MessageItem ---

    @Test
    fun messageItem_deserialization_allFields() {
        val jsonStr = """
            {
                "id": "msg-1",
                "session_id": "sess-1",
                "role": "assistant",
                "content": "Hello there!",
                "tool_calls": "[{\"name\": \"read_file\"}]",
                "tool_name": "read_file",
                "tool_call_id": "call-1",
                "timestamp": 1700000000.5,
                "finish_reason": "stop"
            }
        """.trimIndent()

        val item = json.decodeFromString<MessageItem>(jsonStr)
        assertEquals("msg-1", item.id)
        assertEquals("sess-1", item.sessionId)
        assertEquals("assistant", item.role)
        assertEquals("Hello there!", item.contentText)
        assertNotNull(item.toolCalls)
        assertEquals("read_file", item.toolName)
        assertEquals("call-1", item.toolCallId)
        assertEquals(1700000000.5, item.timestamp!!, 0.001)
        assertEquals("stop", item.finishReason)
    }

    @Test
    fun messageItem_nullableFields() {
        val jsonStr = """{"role": "user"}"""
        val item = json.decodeFromString<MessageItem>(jsonStr)

        assertEquals("user", item.role)
        assertNull(item.id)
        assertNull(item.sessionId)
        assertNull(item.content)
        assertNull(item.toolCalls)
        assertNull(item.toolName)
        assertNull(item.toolCallId)
        assertNull(item.timestamp)
        assertNull(item.finishReason)
    }

    @Test
    fun messageItem_roundTrip() {
        val original = MessageItem(
            id = "1",
            role = "user",
            content = JsonPrimitive("What is 2+2?"),
            timestamp = 1700000000.0
        )

        val jsonStr = json.encodeToString(original)
        val restored = json.decodeFromString<MessageItem>(jsonStr)

        assertEquals(original.id, restored.id)
        assertEquals(original.role, restored.role)
        assertEquals(original.contentText, restored.contentText)
        assertEquals(original.timestamp!!, restored.timestamp!!, 0.001)
    }

    // --- MessageListResponse ---

    @Test
    fun messageListResponse_withItemsField() {
        val jsonStr = """
            {
                "items": [
                    {"role": "user", "content": "Hello"},
                    {"role": "assistant", "content": "Hi there"}
                ],
                "total": 2
            }
        """.trimIndent()

        val response = json.decodeFromString<MessageListResponse>(jsonStr)
        assertNotNull(response.items)
        assertEquals(2, response.items!!.size)
    }

    @Test
    fun messageListResponse_withMessagesField() {
        val jsonStr = """
            {
                "messages": [
                    {"role": "user", "content": "Test"}
                ]
            }
        """.trimIndent()

        val response = json.decodeFromString<MessageListResponse>(jsonStr)
        assertNull(response.items)
        assertNotNull(response.messages)
        assertEquals(1, response.messages!!.size)
    }

    // --- HermesSseEvent ---

    @Test
    fun hermesSseEvent_assistantDelta() {
        val jsonStr = """
            {
                "type": "assistant.delta",
                "session_id": "sess-1",
                "delta": "Hello, how can I help?"
            }
        """.trimIndent()

        val event = json.decodeFromString<HermesSseEvent>(jsonStr)
        assertEquals("assistant.delta", event.type)
        assertEquals("sess-1", event.sessionId)
        assertEquals("Hello, how can I help?", event.delta)
    }

    @Test
    fun hermesSseEvent_toolStarted() {
        val jsonStr = """
            {
                "type": "tool.started",
                "tool_name": "read_file",
                "call_id": "call-abc",
                "args": {"path": "/tmp/test.txt"}
            }
        """.trimIndent()

        val event = json.decodeFromString<HermesSseEvent>(jsonStr)
        assertEquals("tool.started", event.type)
        assertEquals("read_file", event.toolName)
        assertEquals("call-abc", event.callId)
        assertNotNull(event.args)
    }

    @Test
    fun hermesSseEvent_toolCompleted() {
        val jsonStr = """
            {
                "type": "tool.completed",
                "call_id": "call-abc",
                "tool_name": "read_file",
                "result_preview": "file contents...",
                "success": true
            }
        """.trimIndent()

        val event = json.decodeFromString<HermesSseEvent>(jsonStr)
        assertEquals("tool.completed", event.type)
        assertEquals("call-abc", event.callId)
        assertEquals("file contents...", event.resultPreview)
        assertEquals(true, event.success)
    }

    @Test
    fun hermesSseEvent_toolFailed() {
        val jsonStr = """
            {
                "type": "tool.failed",
                "call_id": "call-xyz",
                "error": "Permission denied"
            }
        """.trimIndent()

        val event = json.decodeFromString<HermesSseEvent>(jsonStr)
        assertEquals("tool.failed", event.type)
        assertEquals("call-xyz", event.callId)
        assertEquals("Permission denied", event.error)
    }

    @Test
    fun hermesSseEvent_error() {
        val jsonStr = """
            {
                "type": "error",
                "message": "Rate limit exceeded",
                "error": "rate_limit"
            }
        """.trimIndent()

        val event = json.decodeFromString<HermesSseEvent>(jsonStr)
        assertEquals("error", event.type)
        assertEquals("Rate limit exceeded", event.messageText)
        assertEquals("rate_limit", event.error)
    }

    @Test
    fun hermesSseEvent_assistantCompleted_withUsage() {
        val jsonStr = """
            {
                "type": "assistant.completed",
                "content": "Final response text",
                "usage": {
                    "input_tokens": 100,
                    "output_tokens": 250,
                    "total_tokens": 350
                }
            }
        """.trimIndent()

        val event = json.decodeFromString<HermesSseEvent>(jsonStr)
        assertEquals("assistant.completed", event.type)
        assertEquals("Final response text", event.content)
        assertNotNull(event.usage)
        assertEquals(100, event.usage!!.inputTokens)
        assertEquals(250, event.usage!!.outputTokens)
        assertEquals(350, event.usage!!.totalTokens)
    }

    @Test
    fun hermesSseEvent_legacyThinkingFields() {
        val jsonStr = """
            {
                "type": "thinking_delta",
                "thinking": "Let me consider...",
                "thinking_delta": "more thoughts"
            }
        """.trimIndent()

        val event = json.decodeFromString<HermesSseEvent>(jsonStr)
        assertEquals("Let me consider...", event.thinking)
        assertEquals("more thoughts", event.thinkingDelta)
    }

    @Test
    fun hermesSseEvent_unknownFields_ignored() {
        val jsonStr = """
            {
                "type": "assistant.delta",
                "delta": "text",
                "unknown_field_1": "should be ignored",
                "another_unknown": 42,
                "nested_unknown": {"key": "value"}
            }
        """.trimIndent()

        val event = json.decodeFromString<HermesSseEvent>(jsonStr)
        assertEquals("assistant.delta", event.type)
        assertEquals("text", event.delta)
    }

    @Test
    fun hermesSseEvent_allNullableFields() {
        val jsonStr = """{}"""
        val event = json.decodeFromString<HermesSseEvent>(jsonStr)

        assertNull(event.type)
        assertNull(event.sessionId)
        assertNull(event.runId)
        assertNull(event.messageId)
        assertNull(event.seq)
        assertNull(event.ts)
        assertNull(event.delta)
        assertNull(event.name)
        assertNull(event.toolName)
        assertNull(event.preview)
        assertNull(event.args)
        assertNull(event.resultPreview)
        assertNull(event.success)
        assertNull(event.callId)
        assertNull(event.toolCallId)
        assertNull(event.content)
        assertNull(event.finalResponse)
        assertNull(event.completed)
        assertNull(event.partial)
        assertNull(event.interrupted)
        assertNull(event.apiCalls)
        assertNull(event.title)
        assertNull(event.userMessage)
        assertNull(event.message)
        assertNull(event.error)
        assertNull(event.state)
        assertNull(event.thinking)
        assertNull(event.thinkingDelta)
        assertNull(event.usage)
    }

    @Test
    fun hermesSseEvent_messageStarted_withObjectMessage() {
        val jsonStr = """{"type":"message.started","session_id":"sess-1","run_id":"run-1","message":{"id":"msg-1","role":"assistant"}}"""
        val event = json.decodeFromString<HermesSseEvent>(jsonStr)
        assertEquals("message.started", event.type)
        assertNotNull(event.message)
        assertNull(event.messageText) // object, not string
    }

    @Test
    fun hermesSseEvent_error_withStringMessage() {
        val jsonStr = """{"type":"error","message":"Something failed","error":"internal"}"""
        val event = json.decodeFromString<HermesSseEvent>(jsonStr)
        assertEquals("Something failed", event.messageText)
    }

    @Test
    fun hermesSseEvent_assistantCompleted_withStatus() {
        val jsonStr = """{"type":"assistant.completed","session_id":"s1","run_id":"r1","message_id":"m1","content":"Hello","completed":true,"partial":false,"interrupted":false}"""
        val event = json.decodeFromString<HermesSseEvent>(jsonStr)
        assertEquals(true, event.completed)
        assertEquals(false, event.partial)
        assertEquals(false, event.interrupted)
    }

    @Test
    fun hermesSseEvent_runCompleted_withApiCalls() {
        val jsonStr = """{"type":"run.completed","session_id":"s1","run_id":"r1","message_id":"m1","completed":true,"partial":false,"interrupted":false,"api_calls":3}"""
        val event = json.decodeFromString<HermesSseEvent>(jsonStr)
        assertEquals(3, event.apiCalls)
        assertEquals(true, event.completed)
    }

    @Test
    fun hermesSseEvent_sessionCreated_withTitle() {
        val jsonStr = """{"type":"session.created","session_id":"s1","run_id":"r1","title":"My Chat"}"""
        val event = json.decodeFromString<HermesSseEvent>(jsonStr)
        assertEquals("My Chat", event.title)
    }

    @Test
    fun hermesSseEvent_runStarted_withUserMessage() {
        val jsonStr = """{"type":"run.started","session_id":"s1","run_id":"r1","user_message":{"id":"msg-1","role":"user","content":"Hello"}}"""
        val event = json.decodeFromString<HermesSseEvent>(jsonStr)
        assertNotNull(event.userMessage)
    }

    @Test
    fun hermesSseEvent_done_withState() {
        val jsonStr = """{"type":"done","session_id":"s1","run_id":"r1","state":"final"}"""
        val event = json.decodeFromString<HermesSseEvent>(jsonStr)
        assertEquals("final", event.state)
    }

    @Test
    fun hermesSseEvent_interrupted() {
        val jsonStr = """{"type":"assistant.completed","message_id":"m1","content":"partial response","completed":false,"partial":true,"interrupted":true}"""
        val event = json.decodeFromString<HermesSseEvent>(jsonStr)
        assertEquals(true, event.interrupted)
        assertEquals(true, event.partial)
        assertEquals(false, event.completed)
    }

    @Test
    fun hermesSseEvent_roundTrip() {
        val original = HermesSseEvent(
            type = "assistant.delta",
            sessionId = "sess-1",
            runId = "run-1",
            messageId = "msg-1",
            seq = 5,
            ts = 1700000000.123,
            delta = "Hello"
        )

        val jsonStr = json.encodeToString(original)
        val restored = json.decodeFromString<HermesSseEvent>(jsonStr)

        assertEquals(original.type, restored.type)
        assertEquals(original.sessionId, restored.sessionId)
        assertEquals(original.runId, restored.runId)
        assertEquals(original.messageId, restored.messageId)
        assertEquals(original.seq, restored.seq)
        assertEquals(original.delta, restored.delta)
    }

    // --- UsageInfo ---

    @Test
    fun usageInfo_deserialization() {
        val jsonStr = """
            {
                "input_tokens": 500,
                "output_tokens": 1000,
                "total_tokens": 1500,
                "cache_creation_input_tokens": 200,
                "cache_read_input_tokens": 100
            }
        """.trimIndent()

        val usage = json.decodeFromString<UsageInfo>(jsonStr)
        assertEquals(500, usage.inputTokens)
        assertEquals(1000, usage.outputTokens)
        assertEquals(1500, usage.totalTokens)
        assertEquals(200, usage.cacheCreationInputTokens)
        assertEquals(100, usage.cacheReadInputTokens)
    }

    @Test
    fun usageInfo_partialFields() {
        val jsonStr = """{"input_tokens": 500, "output_tokens": 1000}"""
        val usage = json.decodeFromString<UsageInfo>(jsonStr)

        assertEquals(500, usage.inputTokens)
        assertEquals(1000, usage.outputTokens)
        assertNull(usage.totalTokens)
        assertNull(usage.cacheCreationInputTokens)
        assertNull(usage.cacheReadInputTokens)
    }

    @Test
    fun usageInfo_roundTrip() {
        val original = UsageInfo(
            inputTokens = 100,
            outputTokens = 200,
            totalTokens = 300,
            cacheCreationInputTokens = 50,
            cacheReadInputTokens = 25
        )

        val jsonStr = json.encodeToString(original)
        val restored = json.decodeFromString<UsageInfo>(jsonStr)

        assertEquals(original, restored)
    }

    // --- CreateSessionRequest ---

    @Test
    fun createSessionRequest_serialization() {
        val request = CreateSessionRequest(title = "New Session", model = "gpt-4")
        val jsonStr = json.encodeToString(request)

        assertTrue(jsonStr.contains("\"title\""))
        assertTrue(jsonStr.contains("New Session"))
    }

    @Test
    fun createSessionRequest_nullFields() {
        val request = CreateSessionRequest()
        val jsonStr = json.encodeToString(request)
        val restored = json.decodeFromString<CreateSessionRequest>(jsonStr)

        assertNull(restored.title)
        assertNull(restored.model)
    }

    // --- RenameSessionRequest ---

    @Test
    fun renameSessionRequest_serialization() {
        val request = RenameSessionRequest(title = "Renamed")
        val jsonStr = json.encodeToString(request)

        assertTrue(jsonStr.contains("Renamed"))

        val restored = json.decodeFromString<RenameSessionRequest>(jsonStr)
        assertEquals("Renamed", restored.title)
    }

    // --- FlexibleIdSerializer / int-ID handling ---

    @Test
    fun messageItem_intId_deserializes() {
        val jsonStr = """{"id": 123, "role": "user"}"""
        val item = json.decodeFromString<MessageItem>(jsonStr)
        assertEquals("123", item.id)
    }

    @Test
    fun sessionItem_intId_deserializes() {
        val jsonStr = """{"id": 456}"""
        val item = json.decodeFromString<SessionItem>(jsonStr)
        assertEquals("456", item.id)
    }

    @Test
    fun messageItem_contentAsArray_deserializes() {
        val jsonStr = """
            {
                "role": "assistant",
                "content": [
                    {"type": "text", "text": "Hello "},
                    {"type": "text", "text": "world"}
                ]
            }
        """.trimIndent()

        val item = json.decodeFromString<MessageItem>(jsonStr)
        assertEquals("Hello world", item.contentText)
    }

    @Test
    fun flexibleIdSerializer_handlesNull() {
        val jsonStr = """{"id": null, "role": "user"}"""
        val item = json.decodeFromString<MessageItem>(jsonStr)
        assertNull(item.id)
    }
}
