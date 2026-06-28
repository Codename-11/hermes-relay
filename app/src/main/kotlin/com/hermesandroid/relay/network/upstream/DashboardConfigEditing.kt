package com.hermesandroid.relay.network.upstream

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Pure helpers for the dashboard config-editing surface (`GET /api/config`,
 * `GET /api/config/schema`, `PUT /api/config`).
 *
 * These are deliberately free of Android / OkHttp dependencies so the
 * GET → mutate → PUT-whole flow can be unit-tested without a server. The
 * critical invariant they protect: upstream `save_config` writes the WHOLE
 * config document, so a write must round-trip the entire values tree with the
 * one changed leaf replaced — never a partial object. [withConfigValue] /
 * [applyConfigEdits] build that full tree immutably.
 *
 * The schema (`fields`) keys are flat dot-paths (`tts.elevenlabs.voice_id`);
 * the values tree (`GET /api/config`) is nested. [configValueAt] bridges the
 * two by walking the dot-path into the nested tree.
 */

/** UI field kinds emitted by upstream `_infer_type` + `_SCHEMA_OVERRIDES`. */
enum class ConfigFieldType {
    String,
    Number,
    Boolean,
    /** A `select` override — render as a dropdown over [ConfigSchemaField.options]. */
    Select,
    List,
    Object,
    Unknown;

    companion object {
        fun fromWire(value: kotlin.String?): ConfigFieldType = when (value?.trim()?.lowercase()) {
            "string" -> String
            "number", "integer", "float" -> Number
            "boolean", "bool" -> Boolean
            "select" -> Select
            "list", "array" -> List
            "object", "dict" -> Object
            else -> Unknown
        }
    }
}

/** One editable field from `GET /api/config/schema` `fields`. */
data class ConfigSchemaField(
    /** Flat dot-path, e.g. `tts.elevenlabs.voice_id`. */
    val key: String,
    val type: ConfigFieldType,
    val description: String?,
    val category: String?,
    /** Allowed values when [type] is [ConfigFieldType.Select]; empty otherwise. */
    val options: List<String> = emptyList(),
)

/**
 * Parse the `fields` map from `GET /api/config/schema` into ordered
 * [ConfigSchemaField]s. Insertion order is preserved (the server orders
 * fields meaningfully — e.g. `model` then `model_context_length`).
 */
fun parseConfigSchema(schemaRoot: JsonObject): List<ConfigSchemaField> {
    val fields = schemaRoot["fields"] as? JsonObject ?: return emptyList()
    return fields.mapNotNull { (key, value) ->
        val obj = value as? JsonObject ?: return@mapNotNull null
        ConfigSchemaField(
            key = key,
            type = ConfigFieldType.fromWire(obj.configString("type")),
            description = obj.configString("description"),
            category = obj.configString("category"),
            options = (obj["options"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?: emptyList(),
        )
    }
}

/**
 * The subset of schema fields that configure standard-path voice — the
 * `tts.*` and `stt.*` keys. Filtered by dot-path prefix rather than the
 * `category` field so it is robust to upstream's category-merging.
 */
fun voiceConfigFields(fields: List<ConfigSchemaField>): List<ConfigSchemaField> =
    fields.filter { it.key.startsWith("tts.") || it.key.startsWith("stt.") }

/** Read the value at a dot-path from the nested config values tree, or null. */
fun configValueAt(tree: JsonObject, dotPath: String): JsonElement? {
    var current: JsonElement = tree
    for (part in dotPath.split('.')) {
        val obj = current as? JsonObject ?: return null
        current = obj[part] ?: return null
    }
    return current
}

/**
 * Return a copy of [tree] with [value] set at [dotPath], creating intermediate
 * objects as needed. Immutable: the input tree is never mutated, and object
 * key order is preserved so a round-trip leaves untouched sections byte-stable.
 */
fun withConfigValue(tree: JsonObject, dotPath: String, value: JsonElement): JsonObject =
    setIn(tree, dotPath.split('.'), 0, value)

/** Apply many dot-path edits onto [tree], returning the fully-merged tree. */
fun applyConfigEdits(tree: JsonObject, edits: Map<String, JsonElement>): JsonObject {
    var result = tree
    for ((path, value) in edits) {
        result = withConfigValue(result, path, value)
    }
    return result
}

private fun setIn(
    obj: JsonObject,
    parts: List<String>,
    index: Int,
    value: JsonElement,
): JsonObject {
    val key = parts[index]
    // LinkedHashMap copy preserves existing key order; a new key appends.
    val next = LinkedHashMap<String, JsonElement>(obj)
    next[key] = if (index == parts.lastIndex) {
        value
    } else {
        val child = obj[key] as? JsonObject ?: JsonObject(emptyMap())
        setIn(child, parts, index + 1, value)
    }
    return JsonObject(next)
}

private fun JsonObject.configString(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
