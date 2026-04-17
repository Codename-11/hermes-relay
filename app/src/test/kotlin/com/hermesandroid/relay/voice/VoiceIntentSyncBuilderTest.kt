package com.hermesandroid.relay.voice

import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.data.VoiceIntentTrace
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [VoiceIntentSyncBuilder] — the builder that materializes
 * unsynced phone-local voice-intent traces into OpenAI-format `assistant`
 * (with `tool_calls`) + `tool` (with `tool_call_id`) message pairs the
 * Hermes API server stitches into the LLM's session memory.
 *
 * The builder is a pure function so these tests run on the JVM with no
 * Android dependencies — no Robolectric, no Looper, no MockK.
 */
class VoiceIntentSyncBuilderTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    /** Deterministic ID generator so payload assertions are stable. */
    private val seqIds = generateSequence(1) { it + 1 }.iterator()
    private fun nextId(): String = "test-${seqIds.next()}"

    // --- buildSyntheticMessages ---

    @Test
    fun buildSyntheticMessages_emptyHistory_returnsEmpty() {
        val out = VoiceIntentSyncBuilder.buildSyntheticMessages(emptyList())
        assertTrue(out.isEmpty())
    }

    @Test
    fun buildSyntheticMessages_noVoiceIntents_returnsEmpty() {
        val history = listOf(
            chatMessage(id = "m1", role = MessageRole.USER, content = "hi"),
            chatMessage(id = "m2", role = MessageRole.ASSISTANT, content = "hello"),
        )
        val out = VoiceIntentSyncBuilder.buildSyntheticMessages(history)
        assertTrue(out.isEmpty())
    }

    @Test
    fun buildSyntheticMessages_singleSuccessIntent_emitsAssistantPlusToolPair() {
        val trace = VoiceIntentTrace(
            toolName = "android_open_app",
            argumentsJson = """{"app_name":"Chrome","package":"com.android.chrome"}""",
            success = true,
            resultJson = """{"ok":true}""",
        )
        val history = listOf(
            chatMessage(
                id = "voice-intent-action-100",
                role = MessageRole.ASSISTANT,
                content = "**Opened Chrome**",
                voiceIntent = trace,
            ),
        )
        val out = VoiceIntentSyncBuilder.buildSyntheticMessages(history) { nextId() }

        assertEquals("expected one assistant + one tool message", 2, out.size)

        // assistant message
        val assistantMsg = out[0].jsonObject
        assertEquals("assistant", assistantMsg["role"]?.jsonPrimitive?.content)
        assertEquals("", assistantMsg["content"]?.jsonPrimitive?.content)
        val toolCalls = assistantMsg["tool_calls"]!!.jsonArray
        assertEquals(1, toolCalls.size)
        val call = toolCalls[0].jsonObject
        assertEquals("function", call["type"]?.jsonPrimitive?.content)
        val callId = call["id"]?.jsonPrimitive?.content
        assertNotNull(callId)
        assertTrue("call id should match prefix", callId!!.startsWith("call_voiceintent_"))
        val function = call["function"]!!.jsonObject
        assertEquals("android_open_app", function["name"]?.jsonPrimitive?.content)
        // arguments is a JSON-encoded string per OpenAI spec
        val argsString = function["arguments"]?.jsonPrimitive?.content
        assertEquals("""{"app_name":"Chrome","package":"com.android.chrome"}""", argsString)

        // tool message
        val toolMsg = out[1].jsonObject
        assertEquals("tool", toolMsg["role"]?.jsonPrimitive?.content)
        assertEquals(callId, toolMsg["tool_call_id"]?.jsonPrimitive?.content)
        assertEquals("""{"ok":true}""", toolMsg["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun buildSyntheticMessages_failureIntent_carriesErrorIntoToolContent() {
        val trace = VoiceIntentTrace(
            toolName = "android_send_sms",
            argumentsJson = """{"to":"+15551234567","body":"hi"}""",
            success = false,
            resultJson = VoiceIntentSyncBuilder.failureResultJson(
                errorMessage = "User denied confirmation",
                errorCode = "user_denied",
            ),
        )
        val history = listOf(
            chatMessage(
                id = "voice-intent-result-200",
                role = MessageRole.ASSISTANT,
                content = "**Send SMS — cancelled by you**",
                voiceIntent = trace,
            ),
        )
        val out = VoiceIntentSyncBuilder.buildSyntheticMessages(history) { nextId() }

        assertEquals(2, out.size)

        // tool-role content should embed the failure envelope so the LLM
        // can describe the failure naturally on a follow-up turn.
        val toolMsg = out[1].jsonObject
        val toolContent = toolMsg["content"]?.jsonPrimitive?.content!!
        val parsed = json.parseToJsonElement(toolContent).jsonObject
        assertEquals(false, parsed["ok"]?.jsonPrimitive?.boolean)
        assertEquals("User denied confirmation", parsed["error"]?.jsonPrimitive?.content)
        assertEquals("user_denied", parsed["error_code"]?.jsonPrimitive?.content)
    }

    @Test
    fun buildSyntheticMessages_skipsAlreadySyncedTraces() {
        val syncedTrace = VoiceIntentTrace(
            toolName = "android_open_app",
            argumentsJson = """{"app_name":"Maps"}""",
            success = true,
            resultJson = """{"ok":true}""",
            syncedToServer = true,
        )
        val freshTrace = VoiceIntentTrace(
            toolName = "android_press_key",
            argumentsJson = """{"key":"home"}""",
            success = true,
            resultJson = """{"ok":true}""",
        )
        val history = listOf(
            chatMessage(id = "old", role = MessageRole.ASSISTANT, voiceIntent = syncedTrace),
            chatMessage(id = "new", role = MessageRole.ASSISTANT, voiceIntent = freshTrace),
        )
        val out = VoiceIntentSyncBuilder.buildSyntheticMessages(history) { nextId() }

        assertEquals("only the unsynced trace should be emitted", 2, out.size)
        val function = out[0].jsonObject["tool_calls"]!!.jsonArray[0]
            .jsonObject["function"]!!.jsonObject
        assertEquals("android_press_key", function["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun buildSyntheticMessages_multipleTraces_preservesChronologicalOrder() {
        val traceA = VoiceIntentTrace(
            toolName = "android_open_app",
            argumentsJson = """{"app_name":"Chrome"}""",
            success = true,
            resultJson = """{"ok":true}""",
        )
        val traceB = VoiceIntentTrace(
            toolName = "android_press_key",
            argumentsJson = """{"key":"home"}""",
            success = true,
            resultJson = """{"ok":true}""",
        )
        val history = listOf(
            chatMessage(id = "first", role = MessageRole.ASSISTANT, voiceIntent = traceA, timestamp = 1L),
            chatMessage(id = "user", role = MessageRole.USER, content = "ok", timestamp = 2L),
            chatMessage(id = "second", role = MessageRole.ASSISTANT, voiceIntent = traceB, timestamp = 3L),
        )
        val out = VoiceIntentSyncBuilder.buildSyntheticMessages(history) { nextId() }

        // Expect: assistant(traceA), tool(traceA), assistant(traceB), tool(traceB)
        assertEquals(4, out.size)
        val firstFunction = out[0].jsonObject["tool_calls"]!!.jsonArray[0]
            .jsonObject["function"]!!.jsonObject
        val secondFunction = out[2].jsonObject["tool_calls"]!!.jsonArray[0]
            .jsonObject["function"]!!.jsonObject
        assertEquals("android_open_app", firstFunction["name"]?.jsonPrimitive?.content)
        assertEquals("android_press_key", secondFunction["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun buildSyntheticMessages_skipsTraceWithoutAndroidPrefix() {
        // Defence-in-depth: if a future intent class uses a non-android_*
        // tool name (typo or refactor), the builder should drop it rather
        // than ship a malformed tool_call to the gateway.
        val trace = VoiceIntentTrace(
            toolName = "random_tool",
            argumentsJson = """{"foo":"bar"}""",
            success = true,
            resultJson = """{"ok":true}""",
        )
        val history = listOf(
            chatMessage(id = "bad", role = MessageRole.ASSISTANT, voiceIntent = trace),
        )
        val out = VoiceIntentSyncBuilder.buildSyntheticMessages(history) { nextId() }
        assertTrue(out.isEmpty())
    }

    @Test
    fun buildSyntheticMessages_skipsTraceWithBlankArgs() {
        val trace = VoiceIntentTrace(
            toolName = "android_open_app",
            argumentsJson = "",
            success = true,
            resultJson = """{"ok":true}""",
        )
        val history = listOf(
            chatMessage(id = "bad", role = MessageRole.ASSISTANT, voiceIntent = trace),
        )
        val out = VoiceIntentSyncBuilder.buildSyntheticMessages(history) { nextId() }
        assertTrue(out.isEmpty())
    }

    @Test
    fun buildSyntheticMessages_pairsMatchByCallId() {
        // Sanity check the assistant's tool_call.id and the tool message's
        // tool_call_id always reference the same minted ID so the LLM can
        // correlate them.
        val trace = VoiceIntentTrace(
            toolName = "android_open_app",
            argumentsJson = """{"app_name":"Chrome"}""",
            success = true,
            resultJson = """{"ok":true}""",
        )
        val history = listOf(
            chatMessage(id = "vi", role = MessageRole.ASSISTANT, voiceIntent = trace),
        )
        val out = VoiceIntentSyncBuilder.buildSyntheticMessages(history) { "fixed-uuid" }
        val expectedCallId = "${VoiceIntentSyncBuilder.CALL_ID_PREFIX}fixed-uuid"
        val assistantCallId = out[0].jsonObject["tool_calls"]!!.jsonArray[0]
            .jsonObject["id"]?.jsonPrimitive?.content
        val toolCallIdRef = out[1].jsonObject["tool_call_id"]?.jsonPrimitive?.content
        assertEquals(expectedCallId, assistantCallId)
        assertEquals(expectedCallId, toolCallIdRef)
    }

    // --- hasUnsynced ---

    @Test
    fun hasUnsynced_returnsFalseForEmptyHistory() {
        assertFalse(VoiceIntentSyncBuilder.hasUnsynced(emptyList()))
    }

    @Test
    fun hasUnsynced_returnsTrueForUnsyncedTrace() {
        val history = listOf(
            chatMessage(
                id = "vi",
                role = MessageRole.ASSISTANT,
                voiceIntent = VoiceIntentTrace("android_x", "{}", true, "{}"),
            ),
        )
        assertTrue(VoiceIntentSyncBuilder.hasUnsynced(history))
    }

    @Test
    fun hasUnsynced_returnsFalseWhenAllAlreadySynced() {
        val history = listOf(
            chatMessage(
                id = "vi",
                role = MessageRole.ASSISTANT,
                voiceIntent = VoiceIntentTrace(
                    toolName = "android_x",
                    argumentsJson = "{}",
                    success = true,
                    resultJson = "{}",
                    syncedToServer = true,
                ),
            ),
        )
        assertFalse(VoiceIntentSyncBuilder.hasUnsynced(history))
    }

    // --- helpers ---

    @Test
    fun successResultJson_alwaysMarksOkTrue() {
        val s = VoiceIntentSyncBuilder.successResultJson()
        val parsed = json.parseToJsonElement(s).jsonObject
        assertEquals(true, parsed["ok"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun failureResultJson_omitsErrorCodeWhenBlank() {
        val s = VoiceIntentSyncBuilder.failureResultJson(errorMessage = "boom", errorCode = null)
        val parsed = json.parseToJsonElement(s).jsonObject
        assertEquals(false, parsed["ok"]?.jsonPrimitive?.boolean)
        assertEquals("boom", parsed["error"]?.jsonPrimitive?.content)
        assertNull(parsed["error_code"])
    }

    @Test
    fun failureResultJson_fallsBackToUnknownErrorWhenMessageBlank() {
        val s = VoiceIntentSyncBuilder.failureResultJson(errorMessage = null)
        val parsed = json.parseToJsonElement(s).jsonObject
        assertEquals("unknown error", parsed["error"]?.jsonPrimitive?.content)
    }

    // --- helpers ---

    private fun chatMessage(
        id: String,
        role: MessageRole,
        content: String = "",
        voiceIntent: VoiceIntentTrace? = null,
        timestamp: Long = 0L,
    ) = ChatMessage(
        id = id,
        role = role,
        content = content,
        timestamp = timestamp,
        voiceIntent = voiceIntent,
    )
}
