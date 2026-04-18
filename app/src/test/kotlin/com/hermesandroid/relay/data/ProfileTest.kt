package com.hermesandroid.relay.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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
