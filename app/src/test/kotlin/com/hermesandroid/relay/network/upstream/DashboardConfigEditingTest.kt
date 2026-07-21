package com.hermesandroid.relay.network.upstream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardConfigEditingTest {

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private val schema = obj(
        """
        {
          "fields": {
            "model": {"type": "string", "description": "Model", "category": "general"},
            "tts.provider": {"type": "select", "description": "TTS provider", "category": "tts",
                             "options": ["edge", "elevenlabs", "openai"]},
            "tts.elevenlabs.voice_id": {"type": "string", "description": "Voice", "category": "tts"},
            "stt.enabled": {"type": "boolean", "description": "STT on", "category": "stt"},
            "stt.local.model": {"type": "string", "description": "Whisper model", "category": "stt"},
            "voice.silence_threshold": {"type": "number", "description": "RMS floor", "category": "voice"}
          },
          "category_order": ["general", "tts", "stt", "voice"]
        }
        """.trimIndent(),
    )

    @Test
    fun parseConfigSchema_preservesOrderAndTypesAndOptions() {
        val fields = parseConfigSchema(schema)

        assertEquals(
            listOf(
                "model",
                "tts.provider",
                "tts.elevenlabs.voice_id",
                "stt.enabled",
                "stt.local.model",
                "voice.silence_threshold",
            ),
            fields.map { it.key },
        )
        val provider = fields.first { it.key == "tts.provider" }
        assertEquals(ConfigFieldType.Select, provider.type)
        assertEquals(listOf("edge", "elevenlabs", "openai"), provider.options)
        assertEquals(ConfigFieldType.Boolean, fields.first { it.key == "stt.enabled" }.type)
        assertEquals(ConfigFieldType.Number, fields.first { it.key == "voice.silence_threshold" }.type)
    }

    @Test
    fun voiceConfigFields_includesStandardVoiceBehaviorFields() {
        val voice = voiceConfigFields(parseConfigSchema(schema)).map { it.key }

        assertEquals(
            listOf(
                "tts.provider",
                "tts.elevenlabs.voice_id",
                "stt.enabled",
                "stt.local.model",
                "voice.silence_threshold",
            ),
            voice,
        )
        assertFalse(voice.contains("model"))
    }

    @Test
    fun parseTtsToolsetProviders_usesStableIdsAndKeepsRuntimeStatus() {
        val providers = parseTtsToolsetProviders(
            obj(
                """
                {
                  "providers": [
                    {"name":"Edge TTS","tts_provider":"edge","status":"ready","is_active":true},
                    {"name":"My Plugin","tts_provider":"my_plugin","status":"needs_keys","is_active":false},
                    {"name":"Old backend without a stable id","status":"ready"},
                    {"name":"Duplicate","tts_provider":"edge","status":"ready"}
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(listOf("edge", "my_plugin"), providers.map { it.id })
        assertEquals("Edge TTS", providers.first().name)
        assertEquals("ready", providers.first().status)
        assertTrue(providers.first().isActive)
        assertEquals("needs_keys", providers.last().status)
    }

    @Test
    fun configValueAt_readsNestedDotPathsAndMissing() {
        val tree = obj(
            """{"model":"opus","tts":{"provider":"elevenlabs","elevenlabs":{"voice_id":"adam"}}}""",
        )

        assertEquals("opus", (configValueAt(tree, "model") as JsonPrimitive).contentOrNull)
        assertEquals("elevenlabs", (configValueAt(tree, "tts.provider") as JsonPrimitive).contentOrNull)
        assertEquals("adam", (configValueAt(tree, "tts.elevenlabs.voice_id") as JsonPrimitive).contentOrNull)
        assertNull(configValueAt(tree, "tts.openai.voice"))
        assertNull(configValueAt(tree, "nope.nope"))
    }

    @Test
    fun withConfigValue_setsLeafImmutablyAndDoesNotMutateInput() {
        val tree = obj("""{"model":"opus","tts":{"provider":"edge","elevenlabs":{"voice_id":"adam"}}}""")
        val updated = withConfigValue(tree, "tts.elevenlabs.voice_id", JsonPrimitive("rachel"))

        // Input untouched; only the target leaf changed in the copy.
        assertEquals("adam", (configValueAt(tree, "tts.elevenlabs.voice_id") as JsonPrimitive).contentOrNull)
        assertEquals("rachel", (configValueAt(updated, "tts.elevenlabs.voice_id") as JsonPrimitive).contentOrNull)
        // Sibling values in the same branch survive.
        assertEquals("edge", (configValueAt(updated, "tts.provider") as JsonPrimitive).contentOrNull)
        assertEquals("opus", (configValueAt(updated, "model") as JsonPrimitive).contentOrNull)
    }

    @Test
    fun withConfigValue_createsMissingIntermediateObjects() {
        val tree = obj("""{"tts":{"provider":"openai"}}""")
        val updated = withConfigValue(tree, "tts.openai.voice", JsonPrimitive("nova"))

        assertEquals("nova", (configValueAt(updated, "tts.openai.voice") as JsonPrimitive).contentOrNull)
        assertEquals("openai", (configValueAt(updated, "tts.provider") as JsonPrimitive).contentOrNull)
    }

    @Test
    fun withConfigValue_preservesTopLevelKeyOrder() {
        val tree = obj("""{"a":"1","tts":{"provider":"edge"},"z":"9"}""")
        val updated = withConfigValue(tree, "tts.provider", JsonPrimitive("openai"))

        assertEquals(listOf("a", "tts", "z"), updated.keys.toList())
    }

    @Test
    fun applyConfigEdits_appliesEveryEdit() {
        val tree = obj("""{"tts":{"provider":"edge"},"stt":{"enabled":true,"provider":"local"}}""")
        val updated = applyConfigEdits(
            tree,
            mapOf(
                "tts.provider" to JsonPrimitive("elevenlabs"),
                "stt.provider" to JsonPrimitive("openai"),
            ),
        )

        assertEquals("elevenlabs", (configValueAt(updated, "tts.provider") as JsonPrimitive).contentOrNull)
        assertEquals("openai", (configValueAt(updated, "stt.provider") as JsonPrimitive).contentOrNull)
        assertTrue((configValueAt(updated, "stt.enabled") as JsonPrimitive).contentOrNull == "true")
    }

    @Test
    fun parseConfigSchema_emptyWhenNoFields() {
        assertTrue(parseConfigSchema(obj("""{"category_order":[]}""")).isEmpty())
    }
}
