package com.hermesandroid.relay.plugins.document

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginDocumentTest {
    private val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    @Test
    fun state_resolvesBindingsAndConditions() {
        val state = PluginDocumentState(
            mapOf(
                "name" to PluginValue.StringValue("Hermes"),
                "ready" to PluginValue.BooleanValue(true),
                "progress" to PluginValue.NumberValue(0.75),
            ),
        )

        assertEquals("Hermes", state.resolve(PluginText.Binding("name", "Unknown")))
        assertEquals("Unknown", state.resolve(PluginText.Binding("missing", "Unknown")))
        assertTrue(state.matches(PluginCondition.Truthy("ready")))
        assertTrue(
            state.matches(
                PluginCondition.All(
                    listOf(
                        PluginCondition.Truthy("name"),
                        PluginCondition.Equals("progress", PluginValue.NumberValue(0.75)),
                    ),
                ),
            ),
        )
        assertFalse(state.matches(PluginCondition.Not(PluginCondition.Truthy("ready"))))
    }

    @Test
    fun document_roundTripsPolymorphicElementsAndValues() {
        val document = validDocument()

        val encoded = json.encodeToString(document)
        val decoded = json.decodeFromString<PluginDocument>(encoded)

        assertEquals(document, decoded)
        assertTrue(encoded.contains("\"type\":\"group\""))
        assertTrue(encoded.contains("\"type\":\"boolean\""))
    }

    @Test
    fun validator_acceptsBoundedDeclarativeDocument() {
        assertEquals(PluginDocumentValidation.Valid, PluginDocumentValidator.validate(validDocument()))
    }

    @Test
    fun validator_rejectsDuplicateIdsUnsafeReferencesAndUnboundedInput() {
        val invalid = PluginDocument(
            initialState = mapOf("bad key" to PluginValue.BooleanValue(true)),
            pages = listOf(
                PluginPage(
                    id = "home",
                    title = PluginText.Literal("Home"),
                    content = PluginElement.Group(
                        id = "root",
                        children = listOf(
                            PluginElement.TextInput(
                                id = "duplicate",
                                label = PluginText.Literal("Value"),
                                binding = "bad binding",
                                maxLength = PluginElement.DEFAULT_INPUT_LIMIT + 1,
                            ),
                            PluginElement.Image(
                                id = "duplicate",
                                asset = PluginAssetReference("https://example.com/image.png"),
                                contentDescription = PluginText.Literal("Remote image"),
                                aspectRatio = 10f,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val result = PluginDocumentValidator.validate(invalid) as PluginDocumentValidation.Invalid

        assertTrue(result.errors.any { it.contains("Duplicate element id") })
        assertTrue(result.errors.any { it.contains("Invalid asset id") })
        assertTrue(result.errors.any { it.contains("invalid maxLength") })
        assertTrue(result.errors.any { it.contains("Invalid state key") })
    }

    private fun validDocument() = PluginDocument(
        initialState = mapOf(
            "enabled" to PluginValue.BooleanValue(true),
            "name" to PluginValue.StringValue("Hermes"),
        ),
        pages = listOf(
            PluginPage(
                id = "home",
                title = PluginText.Binding("name", "Plugin"),
                content = PluginElement.Group(
                    id = "root",
                    children = listOf(
                        PluginElement.Text(
                            id = "intro",
                            text = PluginText.Literal("A reactive page"),
                            animation = PluginAnimation(
                                enter = PluginAnimationKind.FADE,
                                change = PluginAnimationKind.HIGHLIGHT,
                            ),
                        ),
                        PluginElement.Toggle(
                            id = "toggle",
                            label = PluginText.Literal("Enabled"),
                            binding = "enabled",
                        ),
                        PluginElement.Button(
                            id = "run",
                            label = PluginText.Literal("Run"),
                            action = PluginAction("run"),
                            enabledWhen = PluginCondition.Truthy("enabled"),
                        ),
                    ),
                ),
            ),
        ),
    )
}
