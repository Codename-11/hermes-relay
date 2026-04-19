package com.hermesandroid.relay.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [Profile] — verifies kotlinx.serialization round-trips
 * against the on-the-wire shape Hermes sends in `auth.ok` payloads (and
 * the config.yaml shape the server loads from).
 */
class ProfileTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun deserializesFullEntry() {
        val payload = """
            {
                "name": "coder",
                "model": "claude-opus-4-6",
                "description": "Pair programmer"
            }
        """.trimIndent()

        val profile = json.decodeFromString(Profile.serializer(), payload)

        assertEquals("coder", profile.name)
        assertEquals("claude-opus-4-6", profile.model)
        assertEquals("Pair programmer", profile.description)
    }

    @Test
    fun descriptionDefaultsToEmptyWhenMissing() {
        val payload = """
            {
                "name": "minimalist",
                "model": "llama-3-70b"
            }
        """.trimIndent()

        val profile = json.decodeFromString(Profile.serializer(), payload)

        assertEquals("minimalist", profile.name)
        assertEquals("llama-3-70b", profile.model)
        assertEquals("", profile.description)
    }

    @Test
    fun roundTripsThroughSerialization() {
        val original = Profile(
            name = "default",
            model = "gpt-4o",
            description = "General-purpose assistant",
        )

        val encoded = json.encodeToString(Profile.serializer(), original)
        val decoded = json.decodeFromString(Profile.serializer(), encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun deserializesSystemMessageFromSnakeCaseWire() {
        // Worker R1's auth.ok wire contract: `system_message` in snake_case
        // on the wire, mapped to `systemMessage` in Kotlin via @SerialName.
        val payload = """
            {
                "name": "mizu",
                "model": "claude-opus-4-6",
                "description": "Axiom-Labs Public Ops",
                "system_message": "You are Mizu, the public-facing agent at Axiom-Labs."
            }
        """.trimIndent()

        val profile = json.decodeFromString(Profile.serializer(), payload)

        assertEquals(
            "You are Mizu, the public-facing agent at Axiom-Labs.",
            profile.systemMessage,
        )
    }

    @Test
    fun systemMessageNullWhenWireValueIsJsonNull() {
        val payload = """
            {
                "name": "bare",
                "model": "claude-opus-4-6",
                "description": "",
                "system_message": null
            }
        """.trimIndent()

        val profile = json.decodeFromString(Profile.serializer(), payload)

        assertNull(profile.systemMessage)
    }

    @Test
    fun systemMessageDefaultsToNullWhenKeyMissing() {
        val payload = """
            {
                "name": "legacy",
                "model": "claude-opus-4-6",
                "description": "Pre-R1 server"
            }
        """.trimIndent()

        val profile = json.decodeFromString(Profile.serializer(), payload)

        assertNull(profile.systemMessage)
    }

    @Test
    fun roundTripsSystemMessageWithSnakeCaseKey() {
        // Encode → decode must be lossless, AND the encoded form must use
        // the snake_case wire name so the server can parse what we send
        // (even though we currently never send Profile objects outbound).
        val original = Profile(
            name = "mizu",
            model = "claude-opus-4-6",
            description = "Axiom-Labs Public Ops",
            systemMessage = "You are Mizu, the public-facing agent.",
        )

        val encoded = json.encodeToString(Profile.serializer(), original)
        assertTrue(
            "expected snake_case system_message in encoded form, got: $encoded",
            encoded.contains("\"system_message\""),
        )

        val decoded = json.decodeFromString(Profile.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun deserializesV7RuntimeMetadata() {
        // v0.7.0 runtime metadata (gateway_running / has_soul / skill_count)
        // round-trips from the wire into the data class.
        val payload = """
            {
                "name": "mizu",
                "model": "anthropic/claude-opus-4",
                "description": "",
                "gateway_running": true,
                "has_soul": true,
                "skill_count": 12
            }
        """.trimIndent()

        val profile = json.decodeFromString(Profile.serializer(), payload)

        assertTrue(profile.gatewayRunning)
        assertTrue(profile.hasSoul)
        assertEquals(12, profile.skillCount)
    }

    @Test
    fun runtimeMetadataDefaultsWhenKeysMissing() {
        // Pre-v0.7 servers don't emit the new keys. Must default to false
        // / false / 0 so the agent sheet renders a sensible "no indicator"
        // state instead of throwing.
        val payload = """
            {
                "name": "legacy",
                "model": "m1",
                "description": ""
            }
        """.trimIndent()

        val profile = json.decodeFromString(Profile.serializer(), payload)

        assertFalse(profile.gatewayRunning)
        assertFalse(profile.hasSoul)
        assertEquals(0, profile.skillCount)
    }

    @Test
    fun roundTripsRuntimeMetadataWithSnakeCaseKeys() {
        val original = Profile(
            name = "mizu",
            model = "anthropic/claude-opus-4",
            description = "",
            gatewayRunning = true,
            hasSoul = true,
            skillCount = 12,
        )

        val encoded = json.encodeToString(Profile.serializer(), original)
        assertTrue(encoded.contains("\"gateway_running\""))
        assertTrue(encoded.contains("\"has_soul\""))
        assertTrue(encoded.contains("\"skill_count\""))

        val decoded = json.decodeFromString(Profile.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun ignoresUnknownServerSideFields() {
        // Upstream might add fields (temperature, tools, …) without a
        // phone update — the parser must not choke on them.
        val payload = """
            {
                "name": "future-proof",
                "model": "some-model",
                "description": "",
                "temperature": 0.7,
                "max_tokens": 4096
            }
        """.trimIndent()

        val profile = json.decodeFromString(Profile.serializer(), payload)

        assertEquals("future-proof", profile.name)
        assertEquals("some-model", profile.model)
    }
}
