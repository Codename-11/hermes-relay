package com.hermesandroid.relay.network.upstream

import com.hermesandroid.relay.network.upstream.models.CreateSessionRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesChatPayloadsTest {

    private val json = Json { ignoreUnknownKeys = true }

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

    @Test
    fun sessionChatPayload_includesProfileModelAndSystemMessage() {
        val payload = buildSessionChatStreamPayload(
            message = "hello",
            systemMessage = "You are Mizu.",
            modelOverride = "grok-mizu",
            profileName = "mizu",
        )

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
        )

        assertFalse(payload.containsKey("profile"))
    }

    @Test
    fun runPayload_includesProfileAlongsideFallbackModelAndSystemMessage() {
        val payload = buildRunStreamPayload(
            message = "hello",
            model = "default-model",
            systemMessage = "You are Coder.",
            modelOverride = "grok-coder",
            profileName = "coder",
        )

        assertEquals("hello", payload["input"]?.jsonPrimitive?.contentOrNull)
        assertEquals("grok-coder", payload["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("You are Coder.", payload["system_message"]?.jsonPrimitive?.contentOrNull)
        assertEquals("coder", payload["profile"]?.jsonPrimitive?.contentOrNull)
        assertTrue(payload["stream"]?.jsonPrimitive?.boolean == true)
    }

    @Test
    fun chatCompletionsPayload_usesOpenAiMessagesAndSseStream() {
        val payload = buildChatCompletionsStreamPayload(
            message = "hello",
            model = "default-model",
            systemMessage = "You are Coder.",
            modelOverride = "grok-coder",
            profileName = "coder",
        )

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
}
