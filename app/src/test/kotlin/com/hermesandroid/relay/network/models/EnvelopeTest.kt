package com.hermesandroid.relay.network.models

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the Envelope model used for WSS relay communication.
 */
class EnvelopeTest {

    private lateinit var json: Json

    @Before
    fun setUp() {
        json = Json { ignoreUnknownKeys = true }
    }

    // --- Envelope creation ---

    @Test
    fun envelope_creation_withRequiredFields() {
        val envelope = Envelope(
            channel = "chat",
            type = "message"
        )

        assertEquals("chat", envelope.channel)
        assertEquals("message", envelope.type)
        assertNotNull(envelope.id) // UUID auto-generated
        assertNotNull(envelope.payload)
    }

    @Test
    fun envelope_defaultPayload_isEmptyJsonObject() {
        val envelope = Envelope(channel = "system", type = "ping")
        assertTrue(envelope.payload.isEmpty())
    }

    @Test
    fun envelope_defaultId_isUUID() {
        val envelope = Envelope(channel = "chat", type = "message")
        // UUID format: 8-4-4-4-12 hex chars
        val uuidRegex = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        assertTrue(
            "ID should be a valid UUID, got: ${envelope.id}",
            uuidRegex.matches(envelope.id)
        )
    }

    @Test
    fun envelope_customId() {
        val envelope = Envelope(
            channel = "chat",
            type = "message",
            id = "custom-id-123"
        )
        assertEquals("custom-id-123", envelope.id)
    }

    @Test
    fun envelope_withPayload() {
        val payload = buildJsonObject {
            put("text", "Hello world")
            put("count", 42)
        }
        val envelope = Envelope(
            channel = "chat",
            type = "message",
            payload = payload
        )

        assertEquals("Hello world", envelope.payload["text"]?.toString()?.trim('"'))
    }

    // --- Channel types ---

    @Test
    fun envelope_chatChannel() {
        val envelope = Envelope(channel = "chat", type = "message")
        assertEquals("chat", envelope.channel)
    }

    @Test
    fun envelope_systemChannel() {
        val envelope = Envelope(channel = "system", type = "auth")
        assertEquals("system", envelope.channel)
    }

    @Test
    fun envelope_terminalChannel() {
        val envelope = Envelope(channel = "terminal", type = "input")
        assertEquals("terminal", envelope.channel)
    }

    @Test
    fun envelope_bridgeChannel() {
        val envelope = Envelope(channel = "bridge", type = "command")
        assertEquals("bridge", envelope.channel)
    }

    // --- Serialization ---

    @Test
    fun envelope_serialization_containsAllFields() {
        val payload = buildJsonObject { put("key", "value") }
        val envelope = Envelope(
            channel = "chat",
            type = "message",
            id = "test-id",
            payload = payload
        )

        val jsonStr = json.encodeToString(envelope)

        assertTrue(jsonStr.contains("\"channel\""))
        assertTrue(jsonStr.contains("\"chat\""))
        assertTrue(jsonStr.contains("\"type\""))
        assertTrue(jsonStr.contains("\"message\""))
        assertTrue(jsonStr.contains("\"id\""))
        assertTrue(jsonStr.contains("\"test-id\""))
        assertTrue(jsonStr.contains("\"payload\""))
        assertTrue(jsonStr.contains("\"key\""))
    }

    @Test
    fun envelope_deserialization_fromJson() {
        val jsonStr = """
            {
                "channel": "system",
                "type": "ping",
                "id": "ping-123",
                "payload": {}
            }
        """.trimIndent()

        val envelope = json.decodeFromString<Envelope>(jsonStr)
        assertEquals("system", envelope.channel)
        assertEquals("ping", envelope.type)
        assertEquals("ping-123", envelope.id)
        assertTrue(envelope.payload.isEmpty())
    }

    @Test
    fun envelope_deserialization_withPayload() {
        val jsonStr = """
            {
                "channel": "chat",
                "type": "message",
                "id": "msg-1",
                "payload": {
                    "text": "Hello",
                    "sender": "user"
                }
            }
        """.trimIndent()

        val envelope = json.decodeFromString<Envelope>(jsonStr)
        assertEquals("chat", envelope.channel)
        assertEquals(2, envelope.payload.size)
    }

    @Test
    fun envelope_roundTrip() {
        val payload = buildJsonObject {
            put("session_token", "abc-123")
            put("device_id", "dev-456")
        }
        val original = Envelope(
            channel = "system",
            type = "auth",
            id = "auth-id",
            payload = payload
        )

        val jsonStr = json.encodeToString(original)
        val restored = json.decodeFromString<Envelope>(jsonStr)

        assertEquals(original.channel, restored.channel)
        assertEquals(original.type, restored.type)
        assertEquals(original.id, restored.id)
        assertEquals(original.payload, restored.payload)
    }

    // --- System channel messages ---

    @Test
    fun envelope_systemPing() {
        val ping = Envelope(
            channel = "system",
            type = "ping",
            id = "ping-1"
        )

        val jsonStr = json.encodeToString(ping)
        val restored = json.decodeFromString<Envelope>(jsonStr)

        assertEquals("system", restored.channel)
        assertEquals("ping", restored.type)
    }

    @Test
    fun envelope_systemPong() {
        val pong = Envelope(
            channel = "system",
            type = "pong",
            id = "pong-1"
        )

        assertEquals("system", pong.channel)
        assertEquals("pong", pong.type)
    }

    @Test
    fun envelope_systemAuth() {
        val payload = buildJsonObject {
            put("pairing_code", "ABC123")
            put("device_name", "Pixel 8")
        }
        val auth = Envelope(
            channel = "system",
            type = "auth",
            payload = payload
        )

        assertEquals("system", auth.channel)
        assertEquals("auth", auth.type)
        assertEquals(2, auth.payload.size)
    }

    @Test
    fun envelope_systemAuthOk() {
        val payload = buildJsonObject {
            put("session_token", "token-xyz")
        }
        val authOk = Envelope(
            channel = "system",
            type = "auth.ok",
            payload = payload
        )

        assertEquals("auth.ok", authOk.type)
    }

    @Test
    fun envelope_systemAuthFail() {
        val payload = buildJsonObject {
            put("reason", "Invalid pairing code")
        }
        val authFail = Envelope(
            channel = "system",
            type = "auth.fail",
            payload = payload
        )

        assertEquals("auth.fail", authFail.type)
    }

    // --- Unique IDs ---

    @Test
    fun envelope_autoGeneratedIds_areUnique() {
        val ids = (1..100).map {
            Envelope(channel = "test", type = "test").id
        }.toSet()

        assertEquals("All 100 auto-generated IDs should be unique", 100, ids.size)
    }

    // --- Unknown fields ---

    @Test
    fun envelope_deserialization_unknownFields_ignored() {
        val jsonStr = """
            {
                "channel": "chat",
                "type": "message",
                "id": "msg-1",
                "payload": {},
                "unknown_field": "ignored",
                "extra": 42
            }
        """.trimIndent()

        val envelope = json.decodeFromString<Envelope>(jsonStr)
        assertEquals("chat", envelope.channel)
        assertEquals("message", envelope.type)
    }
}
