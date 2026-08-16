package com.hermesandroid.relay.network.upstream

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Parse both upstream personality formats: the original prompt string and the
 * structured form introduced for richer descriptions, tone, and style.
 */
internal fun parsePersonalityPrompts(personalities: JsonObject?): Map<String, String> =
    personalities
        ?.mapValues { (_, value) -> personalityPrompt(value) }
        .orEmpty()

private fun personalityPrompt(value: JsonElement): String = when (value) {
    is JsonPrimitive -> value.contentOrNull.orEmpty()
    is JsonObject -> listOfNotNull(
        value.stringValue("system_prompt"),
        value.stringValue("tone")?.let { "Tone: $it" },
        value.stringValue("style")?.let { "Style: $it" },
    ).joinToString("\n")
    else -> ""
}

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotBlank() }
