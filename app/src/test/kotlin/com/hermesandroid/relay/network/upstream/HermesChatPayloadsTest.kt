package com.hermesandroid.relay.network.upstream

import com.hermesandroid.relay.data.Attachment
import com.hermesandroid.relay.network.upstream.models.CreateSessionRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HRUI-001 contract tests: the sessions/runs/completions fallback payloads
 * must contain ONLY fields upstream consumes (plus the documented legacy
 * hint fields), synthetic phone-local history must land in a supported
 * channel, and undeliverable attachments must surface explicitly through
 * [ChatPayloadResult.droppedAttachments] — never a silent drop.
 */
class HermesChatPayloadsTest {
    @Test
    fun `model route alias id is sent unchanged on every SSE payload`() {
        val alias = "fast-route"
        val session = buildSessionChatStreamPayload(message = "hi", modelOverride = alias)
        val completion = buildChatCompletionsStreamPayload(message = "hi", modelOverride = alias)
        val run = buildRunStreamPayload(message = "hi", modelOverride = alias)

        assertEquals(alias, session.payload["model"]?.jsonPrimitive?.content)
        assertEquals(alias, completion.payload["model"]?.jsonPrimitive?.content)
        assertEquals(alias, run.payload["model"]?.jsonPrimitive?.content)
    }


    private val json = Json { ignoreUnknownKeys = true }

    // --- fixtures ---

    private val imageAttachment = Attachment(
        contentType = "image/png",
        content = "IMGB64",
        fileName = "shot.png",
    )
    private val pdfAttachment = Attachment(
        contentType = "application/pdf",
        content = "PDFB64",
        fileName = "doc.pdf",
    )

    /** OpenAI-format assistant tool-call + tool-result pair (voice intent / card dispatch shape). */
    private fun toolCallPair(
        callId: String,
        name: String,
        arguments: String,
        result: String,
    ): List<JsonObject> = listOf(
        buildJsonObject {
            put("role", "assistant")
            put("content", "")
            putJsonArray("tool_calls") {
                add(buildJsonObject {
                    put("id", callId)
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", name)
                        put("arguments", arguments)
                    }
                })
            }
        },
        buildJsonObject {
            put("role", "tool")
            put("tool_call_id", callId)
            put("content", result)
        },
    )

    /** Plain text turn (realtime voice sync shape). */
    private fun plainTurn(role: String, content: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private fun syntheticArray(entries: List<JsonObject>): JsonArray = buildJsonArray {
        entries.forEach { add(it) }
    }

    private val voiceIntentPair = toolCallPair(
        callId = "call_voiceintent_1",
        name = "android_open_app",
        arguments = """{"app_name":"Chrome"}""",
        result = """{"ok":true,"package":"com.android.chrome"}""",
    )
    private val realtimeTurns = listOf(
        plainTurn("user", "what's the capital of France?"),
        plainTurn("assistant", "Paris."),
    )

    // --- CreateSessionRequest (unchanged surface) ---

    @Test
    fun createSessionRequest_serializesProfileWhenExplicitlySelected() {
        val body = json.encodeToString(
            CreateSessionRequest(
                title = "Mizu chat",
                model = "grok-mizu",
                profile = "mizu",
            ),
        )
        val parsed = json.decodeFromString<JsonObject>(body)

        assertEquals("Mizu chat", parsed["title"]?.jsonPrimitive?.contentOrNull)
        assertEquals("grok-mizu", parsed["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("mizu", parsed["profile"]?.jsonPrimitive?.contentOrNull)
    }

    // --- sessions payload ---

    @Test
    fun sessionChatPayload_includesProfileModelAndSystemMessage() {
        val payload = buildSessionChatStreamPayload(
            message = "hello",
            systemMessage = "You are Mizu.",
            modelOverride = "grok-mizu",
            profileName = "mizu",
        ).payload

        assertEquals("hello", payload["message"]?.jsonPrimitive?.contentOrNull)
        assertEquals("You are Mizu.", payload["system_message"]?.jsonPrimitive?.contentOrNull)
        assertEquals("grok-mizu", payload["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("mizu", payload["profile"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun sessionChatPayload_omitsProfileWhenServerDefaultSelected() {
        val payload = buildSessionChatStreamPayload(
            message = "hello",
            profileName = null,
        ).payload

        assertFalse(payload.containsKey("profile"))
    }

    @Test
    fun sessionChatPayload_sendsOnlyUpstreamContractFields() {
        val payload = buildSessionChatStreamPayload(
            message = "hello",
            systemMessage = "sys",
            attachments = listOf(imageAttachment, pdfAttachment),
            voiceIntentMessages = syntheticArray(voiceIntentPair + realtimeTurns),
            modelOverride = "grok-mizu",
            profileName = "mizu",
        ).payload

        // Upstream parses message + system_message; model + profile are
        // documented legacy hints. Nothing else may go on the wire —
        // in particular no top-level `messages` or `attachments`.
        assertEquals(
            setOf("message", "system_message", "model", "profile"),
            payload.keys,
        )
    }

    @Test
    fun sessionChatPayload_foldsSyntheticHistoryIntoSystemMessage() {
        val payload = buildSessionChatStreamPayload(
            message = "hello",
            systemMessage = "You are Mizu.",
            voiceIntentMessages = syntheticArray(voiceIntentPair + realtimeTurns),
        ).payload

        val system = payload["system_message"]?.jsonPrimitive?.contentOrNull.orEmpty()
        // Caller's per-turn system message stays first.
        assertTrue(system.startsWith("You are Mizu."))
        assertTrue(system.contains(SYNTHETIC_DIGEST_HEADER))
        // Tool-call pair renders name + arguments + result on one line.
        assertTrue(
            system.contains(
                """- called android_open_app with {"app_name":"Chrome"} -> {"ok":true,"package":"com.android.chrome"}""",
            ),
        )
        // Sessions has no history channel, so plain turns join the digest.
        assertTrue(system.contains("- user: what's the capital of France?"))
        assertTrue(system.contains("- assistant: Paris."))
    }

    @Test
    fun sessionChatPayload_digestAloneWhenNoSystemMessage() {
        val payload = buildSessionChatStreamPayload(
            message = "hello",
            voiceIntentMessages = syntheticArray(voiceIntentPair),
        ).payload

        val system = payload["system_message"]?.jsonPrimitive?.contentOrNull.orEmpty()
        assertTrue(system.startsWith(SYNTHETIC_DIGEST_HEADER))
    }

    @Test
    fun sessionChatPayload_reportsAllAttachmentsDropped() {
        val result = buildSessionChatStreamPayload(
            message = "hello",
            attachments = listOf(imageAttachment, pdfAttachment),
        )

        assertEquals(listOf(imageAttachment, pdfAttachment), result.droppedAttachments)
        assertFalse(result.payload.containsKey("attachments"))
    }

    // --- runs payload ---

    @Test
    fun runPayload_includesProfileAlongsideFallbackModelAndInstructions() {
        val payload = buildRunStreamPayload(
            message = "hello",
            model = "default-model",
            systemMessage = "You are Coder.",
            modelOverride = "grok-coder",
            profileName = "coder",
        ).payload

        assertEquals("hello", payload["input"]?.jsonPrimitive?.contentOrNull)
        assertEquals("grok-coder", payload["model"]?.jsonPrimitive?.contentOrNull)
        // Upstream's runs handler reads `instructions`, not `system_message`.
        assertEquals("You are Coder.", payload["instructions"]?.jsonPrimitive?.contentOrNull)
        assertFalse(payload.containsKey("system_message"))
        assertEquals("coder", payload["profile"]?.jsonPrimitive?.contentOrNull)
        assertTrue(payload["stream"]?.jsonPrimitive?.boolean == true)
    }

    @Test
    fun runPayload_sendsOnlyUpstreamContractFields() {
        val payload = buildRunStreamPayload(
            message = "hello",
            systemMessage = "sys",
            attachments = listOf(imageAttachment, pdfAttachment),
            voiceIntentMessages = syntheticArray(voiceIntentPair + realtimeTurns),
            modelOverride = "grok-coder",
            profileName = "coder",
        ).payload

        assertEquals(
            setOf("model", "input", "stream", "instructions", "conversation_history", "profile"),
            payload.keys,
        )
    }

    @Test
    fun runPayload_splicesPlainTurnsIntoConversationHistoryAndDigestsToolPairs() {
        val payload = buildRunStreamPayload(
            message = "hello",
            voiceIntentMessages = syntheticArray(voiceIntentPair + realtimeTurns),
        ).payload

        // Plain realtime turns ride the upstream-parsed history channel verbatim.
        val history = payload["conversation_history"]!!.jsonArray
        assertEquals(2, history.size)
        assertEquals("user", history[0].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            "what's the capital of France?",
            history[0].jsonObject["content"]?.jsonPrimitive?.contentOrNull,
        )
        assertEquals("assistant", history[1].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Paris.", history[1].jsonObject["content"]?.jsonPrimitive?.contentOrNull)

        // Tool-call pairs go through the instructions digest — and only them
        // (plain turns must not be delivered twice).
        val instructions = payload["instructions"]?.jsonPrimitive?.contentOrNull.orEmpty()
        assertTrue(instructions.contains("- called android_open_app"))
        assertFalse(instructions.contains("- user:"))
        assertFalse(instructions.contains("- assistant:"))
    }

    @Test
    fun runPayload_omitsConversationHistoryWhenOnlyToolPairs() {
        val payload = buildRunStreamPayload(
            message = "hello",
            voiceIntentMessages = syntheticArray(voiceIntentPair),
        ).payload

        assertFalse(payload.containsKey("conversation_history"))
        assertTrue(
            payload["instructions"]?.jsonPrimitive?.contentOrNull.orEmpty()
                .contains("- called android_open_app"),
        )
    }

    @Test
    fun runPayload_reportsAllAttachmentsDropped() {
        val result = buildRunStreamPayload(
            message = "hello",
            attachments = listOf(imageAttachment, pdfAttachment),
        )

        assertEquals(listOf(imageAttachment, pdfAttachment), result.droppedAttachments)
        assertFalse(result.payload.containsKey("attachments"))
    }

    // --- completions payload ---

    @Test
    fun chatCompletionsPayload_usesOpenAiMessagesAndSseStream() {
        val payload = buildChatCompletionsStreamPayload(
            message = "hello",
            model = "default-model",
            systemMessage = "You are Coder.",
            modelOverride = "grok-coder",
            profileName = "coder",
        ).payload

        assertEquals("grok-coder", payload["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("coder", payload["profile"]?.jsonPrimitive?.contentOrNull)
        assertTrue(payload["stream"]?.jsonPrimitive?.boolean == true)

        val messages = payload["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("You are Coder.", messages[0].jsonObject["content"]?.jsonPrimitive?.contentOrNull)
        assertEquals("user", messages[1].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("hello", messages[1].jsonObject["content"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun chatCompletionsPayload_inlinesImageAttachmentsAsImageUrlParts() {
        val result = buildChatCompletionsStreamPayload(
            message = "what is this?",
            attachments = listOf(imageAttachment),
        )

        val messages = result.payload["messages"]!!.jsonArray
        val userContent = messages.last().jsonObject["content"]!!.jsonArray
        assertEquals("text", userContent[0].jsonObject["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("what is this?", userContent[0].jsonObject["text"]?.jsonPrimitive?.contentOrNull)
        assertEquals("image_url", userContent[1].jsonObject["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            "data:image/png;base64,IMGB64",
            userContent[1].jsonObject["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull,
        )
        // Images have a real channel here — nothing dropped.
        assertTrue(result.droppedAttachments.isEmpty())
    }

    @Test
    fun chatCompletionsPayload_splicesPlainTurnsAndDigestsToolPairs() {
        val payload = buildChatCompletionsStreamPayload(
            message = "hello",
            voiceIntentMessages = syntheticArray(voiceIntentPair + realtimeTurns),
        ).payload

        val messages = payload["messages"]!!.jsonArray
        val roles = messages.map { it.jsonObject["role"]?.jsonPrimitive?.contentOrNull }
        // system digest + spliced realtime turns + live user message; the
        // tool-call pair must NOT be spliced (upstream skips tool-role
        // messages and strips tool_calls, destroying the record).
        assertEquals(listOf("system", "user", "assistant", "user"), roles)
        assertFalse(messages.any { it.jsonObject.containsKey("tool_calls") })

        val system = messages[0].jsonObject["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        assertTrue(system.contains("- called android_open_app"))
        assertEquals(
            "what's the capital of France?",
            messages[1].jsonObject["content"]?.jsonPrimitive?.contentOrNull,
        )
        assertEquals("Paris.", messages[2].jsonObject["content"]?.jsonPrimitive?.contentOrNull)
        assertEquals("hello", messages[3].jsonObject["content"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun chatCompletionsPayload_dropsOnlyNonImageAttachments() {
        val result = buildChatCompletionsStreamPayload(
            message = "hello",
            attachments = listOf(imageAttachment, pdfAttachment),
        )

        assertEquals(listOf(pdfAttachment), result.droppedAttachments)
        // The dead top-level `attachments` field is gone for good.
        assertFalse(result.payload.containsKey("attachments"))
    }

    // --- digest helper edge cases ---

    @Test
    fun renderSyntheticHistoryDigest_nullWhenNothingRenders() {
        assertNull(renderSyntheticHistoryDigest(null, includePlainTurns = true))
        assertNull(renderSyntheticHistoryDigest(buildJsonArray {}, includePlainTurns = true))
        // Plain turns excluded (endpoints with a real history channel) and
        // no tool pairs present -> nothing to fold into the prompt.
        assertNull(
            renderSyntheticHistoryDigest(
                syntheticArray(realtimeTurns),
                includePlainTurns = false,
            ),
        )
    }

    @Test
    fun renderSyntheticHistoryDigest_pairsResultsByToolCallId() {
        val pairA = toolCallPair("call_a", "android_open_app", """{"app_name":"Maps"}""", """{"ok":true}""")
        val pairB = toolCallPair(
            "call_b",
            "hermes_card_action",
            """{"card_key":"k","action_value":"/approve"}""",
            """{"ok":true,"dispatched_at":1}""",
        )
        val digest = renderSyntheticHistoryDigest(
            syntheticArray(pairA + pairB),
            includePlainTurns = false,
        ).orEmpty()

        assertTrue(digest.startsWith(SYNTHETIC_DIGEST_HEADER))
        assertTrue(digest.contains("""- called android_open_app with {"app_name":"Maps"} -> {"ok":true}"""))
        assertTrue(
            digest.contains(
                """- called hermes_card_action with {"card_key":"k","action_value":"/approve"} -> {"ok":true,"dispatched_at":1}""",
            ),
        )
    }
}
