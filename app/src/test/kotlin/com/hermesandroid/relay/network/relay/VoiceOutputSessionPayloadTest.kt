package com.hermesandroid.relay.network.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VoiceOutputSessionPayloadTest {

    @Test
    fun draftOverridesAreIncludedWithoutPersistingConfig() {
        val payload = Json.parseToJsonElement(
            buildVoiceOutputSessionPayload(
                profile = "default",
                provider = "xai_tts",
                model = "tts-1",
                voice = "eve",
                sampleRate = 24_000,
                language = "en",
            ),
        ).jsonObject

        assertEquals("default", payload.getValue("profile").jsonPrimitive.content)
        assertEquals("xai_tts", payload.getValue("provider").jsonPrimitive.content)
        assertEquals("tts-1", payload.getValue("model").jsonPrimitive.content)
        assertEquals("eve", payload.getValue("voice").jsonPrimitive.content)
        assertEquals(24_000, payload.getValue("sample_rate").jsonPrimitive.int)
        assertEquals("en", payload.getValue("language").jsonPrimitive.content)
        assertFalse(payload.containsKey("enabled"))
    }

    @Test
    fun blankDraftValuesFallBackToSavedServerDefaults() {
        val payload = Json.parseToJsonElement(
            buildVoiceOutputSessionPayload(
                profile = "default",
                provider = " ",
                model = "",
                voice = null,
            ),
        ).jsonObject

        assertEquals(setOf("profile"), payload.keys)
    }
}
