package com.hermesandroid.relay.network.upstream

import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.Attachment
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * === Upstream request contract (HRUI-001) ===
 *
 * Verified against hermes-agent `gateway/platforms/api_server.py`. These
 * builders send ONLY fields the target handler consumes (plus a small,
 * documented set of legacy hint fields — see below). Fields upstream
 * ignores are never emitted: a dead field on the wire misrepresents
 * capability and masks data loss.
 *
 * Per-endpoint parsing truth (current upstream main):
 *
 *  - `POST /api/sessions/{id}/chat/stream` (`_handle_session_chat_stream`)
 *    consumes `message` (or `input`) and `system_message` (or
 *    `instructions`, string only). `message` accepts either a plain string
 *    or OpenAI-style content parts (text + `image_url`) via
 *    `_normalize_multimodal_content`. Top-level `messages`, `attachments`,
 *    `model`, and `profile` are NOT parsed.
 *
 *  - `POST /v1/runs` (`_handle_runs`) consumes `input` (string or message
 *    array), `instructions`, `conversation_history` (array of
 *    `{role, content}` objects, string-coerced), `previous_response_id`,
 *    `session_id`, and `model`. It does NOT parse `system_message`,
 *    `stream`, `messages`, `attachments`, or `profile` — and always
 *    answers `202 {"run_id": ...}` JSON (no SSE on POST).
 *
 *  - `POST /v1/chat/completions` (`_handle_chat_completions`) consumes
 *    `messages`, `stream`, and `model`. Within `messages`: `system` roles
 *    fold into the ephemeral system prompt; `user`/`assistant` entries are
 *    kept as history with multimodal content normalization; `tool`-role
 *    entries are silently skipped and `tool_calls` fields are stripped.
 *    Top-level `attachments` and `profile` are NOT parsed.
 *
 * Legacy hint fields we deliberately keep sending although current native
 * upstream ignores them: `model` + `profile` on the sessions path,
 * `profile` on runs/completions, and `stream` on runs. They are
 * configuration hints (never user content, so they cannot mask data
 * loss) honored by legacy fork builds — the runs path in particular only
 * activates against servers that explicitly advertise SSE-on-POST, which
 * vanilla upstream never does. See `ServerCapabilities`.
 *
 * === Synthetic-history mapping ===
 *
 * Phone-local synthetic turns (voice-intent traces, card dispatches,
 * provider-answered realtime voice turns — see `VoiceIntentSyncBuilder`,
 * `CardDispatchSyncBuilder`, `RealtimeTurnSyncBuilder`) arrive here as one
 * OpenAI-format array. Historically they were sent as a top-level
 * `messages` field on sessions/runs, which upstream never consumed —
 * silent data loss. They now map onto channels each endpoint actually
 * supports:
 *
 *  - Tool-call pairs (`assistant` + `tool` with `tool_call_id`) have no
 *    surviving wire shape on ANY fallback endpoint, so they render as a
 *    plain-text digest ([renderSyntheticHistoryDigest]) folded into the
 *    per-turn ephemeral system prompt: `system_message` on sessions,
 *    `instructions` on runs, the `system` message on completions.
 *  - Plain `user`/`assistant` text turns ride a real history channel
 *    where one exists: spliced into `messages` on completions, sent as
 *    `conversation_history` on runs. The sessions endpoint has no
 *    client-provided history channel, so there they join the digest.
 *
 * This mapping is ephemeral where the digest is used: the model sees the
 * context for THIS turn only; it is not persisted into the server-side
 * session transcript. That is strictly better than the previous behavior
 * (context arrived never) and matches the existing voice-turn pattern of
 * per-turn non-persisted instructions.
 *
 * === Attachments ===
 *
 * Only the completions endpoint has an upstream-supported attachment
 * channel on this surface: inline `image_url` content parts (images
 * only). Sessions/runs payloads carry no attachments at all. Anything
 * that cannot be delivered is returned in
 * [ChatPayloadResult.droppedAttachments] so callers can surface the drop
 * (HermesApiClient logs it; ChatViewModel shows a user-visible notice) —
 * never a silent discard. Note: current upstream's sessions `message`
 * field does accept inline `image_url` content parts, so image delivery
 * on the sessions path is a possible future improvement; it is not wired
 * yet because the caller's attachment warning and this builder must move
 * together.
 */

/**
 * Result of building a fallback-transport chat payload.
 *
 * @property payload The JSON request body — contains only fields the
 *   target endpoint consumes (plus documented legacy hint fields).
 * @property droppedAttachments Attachments that have NO supported channel
 *   on the target endpoint and were therefore not encoded into [payload].
 *   Callers must surface these (log + user notice), never ignore them.
 */
internal data class ChatPayloadResult(
    val payload: JsonObject,
    val droppedAttachments: List<Attachment>,
)

/**
 * Header line for the synthetic phone-context digest. Tells the model the
 * listed activity already happened on-device so it treats the lines as
 * history, not instructions to act on.
 */
internal const val SYNTHETIC_DIGEST_HEADER =
    "Phone-side activity since the previous server turn " +
        "(already completed on-device; context only — do not re-execute):"

private fun JsonObject.roleOrNull(): String? =
    (this["role"] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.contentStringOrNull(): String? =
    (this["content"] as? JsonPrimitive)?.contentOrNull

/**
 * True for a synthetic entry deliverable as a REAL conversation turn on
 * endpoints with a client-history channel: plain `user`/`assistant` role,
 * string content, no `tool_calls`. Matches the shape emitted by
 * `RealtimeTurnSyncBuilder`; tool-call pairs from the voice-intent and
 * card-dispatch builders fail this check and go through the digest.
 */
internal fun isPlainSyntheticTurn(entry: JsonObject): Boolean {
    val role = entry.roleOrNull()
    if (role != "user" && role != "assistant") return false
    if (entry.containsKey("tool_calls")) return false
    return !entry.contentStringOrNull().isNullOrBlank()
}

/**
 * Render the synthetic sync stream as a compact plain-text digest for the
 * per-turn ephemeral system prompt.
 *
 * Tool-call pairs (`assistant.tool_calls` + matching `tool` result keyed
 * by `tool_call_id`) always render, one line per call:
 * `- called <name> with <arguments> -> <result>`. Plain text turns render
 * as `- user: ...` / `- assistant: ...` lines only when
 * [includePlainTurns] is true (sessions path — no real history channel);
 * endpoints that deliver plain turns natively pass false so the same turn
 * is never delivered twice.
 *
 * @return null when nothing renders (no synthetic messages, or only plain
 *   turns while [includePlainTurns] is false).
 */
internal fun renderSyntheticHistoryDigest(
    syntheticMessages: JsonArray?,
    includePlainTurns: Boolean,
): String? {
    if (syntheticMessages.isNullOrEmpty()) return null

    // Pair tool results with their originating call.
    val resultsByCallId = HashMap<String, String>()
    for (element in syntheticMessages) {
        val obj = element as? JsonObject ?: continue
        if (obj.roleOrNull() != "tool") continue
        val callId = (obj["tool_call_id"] as? JsonPrimitive)?.contentOrNull ?: continue
        resultsByCallId[callId] = obj.contentStringOrNull().orEmpty()
    }

    val lines = mutableListOf<String>()
    for (element in syntheticMessages) {
        val obj = element as? JsonObject ?: continue
        when (obj.roleOrNull()) {
            "assistant" -> {
                val toolCalls = obj["tool_calls"] as? JsonArray
                if (toolCalls != null) {
                    for (call in toolCalls) {
                        val callObj = call as? JsonObject ?: continue
                        val function = callObj["function"] as? JsonObject
                        val name = (function?.get("name") as? JsonPrimitive)
                            ?.contentOrNull ?: "unknown_tool"
                        val args = (function?.get("arguments") as? JsonPrimitive)
                            ?.contentOrNull ?: "{}"
                        val callId = (callObj["id"] as? JsonPrimitive)?.contentOrNull
                        val result = callId?.let(resultsByCallId::get)
                        lines += if (result.isNullOrBlank()) {
                            "- called $name with $args"
                        } else {
                            "- called $name with $args -> $result"
                        }
                    }
                } else if (includePlainTurns) {
                    obj.contentStringOrNull()?.takeIf { it.isNotBlank() }
                        ?.let { lines += "- assistant: $it" }
                }
            }
            "user" -> if (includePlainTurns) {
                obj.contentStringOrNull()?.takeIf { it.isNotBlank() }
                    ?.let { lines += "- user: $it" }
            }
            // "tool" entries fold into their assistant line via resultsByCallId.
        }
    }
    if (lines.isEmpty()) return null
    return SYNTHETIC_DIGEST_HEADER + "\n" + lines.joinToString("\n")
}

/**
 * Merge the caller's per-turn system message with the synthetic-history
 * digest into one ephemeral prompt string. Either side may be absent.
 */
internal fun mergeEphemeralContext(systemMessage: String?, digest: String?): String? = when {
    digest.isNullOrBlank() -> systemMessage?.takeIf { it.isNotBlank() }
    systemMessage.isNullOrBlank() -> digest
    else -> systemMessage + "\n\n" + digest
}

/** Synthetic entries deliverable as real history turns (see [isPlainSyntheticTurn]). */
private fun plainSyntheticTurns(syntheticMessages: JsonArray?): List<JsonObject> =
    (syntheticMessages ?: emptyList())
        .mapNotNull { it as? JsonObject }
        .filter(::isPlainSyntheticTurn)

/**
 * Body for `POST /api/sessions/{id}/chat/stream`.
 *
 * Emits `message` + `system_message` (upstream-consumed) and `model` +
 * `profile` (legacy hints — current native upstream ignores both on this
 * route; legacy fork builds honor them; see the file header). ALL
 * synthetic history folds into `system_message` via the digest: the
 * endpoint has no client-provided history channel. Attachments have no
 * supported channel here and are returned as dropped.
 */
internal fun buildSessionChatStreamPayload(
    message: String,
    systemMessage: String? = null,
    attachments: List<Attachment>? = null,
    voiceIntentMessages: JsonArray? = null,
    modelOverride: String? = null,
    profileName: String? = null,
): ChatPayloadResult {
    val digest = renderSyntheticHistoryDigest(voiceIntentMessages, includePlainTurns = true)
    val effectiveSystem = mergeEphemeralContext(systemMessage, digest)
    val payload = buildJsonObject {
        put("message", message)
        if (!effectiveSystem.isNullOrBlank()) {
            put("system_message", effectiveSystem)
        }
        if (!modelOverride.isNullOrBlank()) {
            put("model", modelOverride)
        }
        AgentDisplay.profileRequestName(profileName)?.let { put("profile", it) }
    }
    return ChatPayloadResult(payload, droppedAttachments = attachments.orEmpty())
}

/**
 * Body for `POST /v1/runs`.
 *
 * Emits `input`, `model`, and `instructions` (upstream-consumed; note the
 * runs handler reads `instructions`, NOT `system_message` — the latter was
 * a silent drop before HRUI-001), plus `stream` + `profile` legacy hints.
 * Synthetic history: plain text turns ride `conversation_history` (a real
 * upstream channel — entries are `{role, content}` objects); tool-call
 * pairs fold into the `instructions` digest. Attachments have no
 * supported channel here and are returned as dropped.
 */
internal fun buildRunStreamPayload(
    message: String,
    model: String? = null,
    systemMessage: String? = null,
    attachments: List<Attachment>? = null,
    voiceIntentMessages: JsonArray? = null,
    modelOverride: String? = null,
    profileName: String? = null,
): ChatPayloadResult {
    val resolvedModel = when {
        !modelOverride.isNullOrBlank() -> modelOverride
        !model.isNullOrBlank() -> model
        else -> "default"
    }
    val digest = renderSyntheticHistoryDigest(voiceIntentMessages, includePlainTurns = false)
    val effectiveInstructions = mergeEphemeralContext(systemMessage, digest)
    val plainTurns = plainSyntheticTurns(voiceIntentMessages)
    val payload = buildJsonObject {
        put("model", resolvedModel)
        put("input", message)
        put("stream", true)
        if (!effectiveInstructions.isNullOrBlank()) {
            put("instructions", effectiveInstructions)
        }
        if (plainTurns.isNotEmpty()) {
            putJsonArray("conversation_history") {
                plainTurns.forEach { add(it) }
            }
        }
        AgentDisplay.profileRequestName(profileName)?.let { put("profile", it) }
    }
    return ChatPayloadResult(payload, droppedAttachments = attachments.orEmpty())
}

/**
 * Body for `POST /v1/chat/completions`.
 *
 * Emits `model`, `stream`, and `messages` (all upstream-consumed) plus
 * the `profile` legacy hint. Synthetic history: plain text turns splice
 * into `messages` before the live user message (upstream keeps
 * `user`/`assistant` history entries verbatim); tool-call pairs fold into
 * the system message digest, because upstream SKIPS `tool`-role messages
 * and STRIPS `tool_calls` — splicing them produced junk empty-content
 * assistant entries and lost the results entirely. Image attachments ride
 * inline `image_url` content parts on the user message (upstream vision
 * format); non-image attachments have no channel and are returned as
 * dropped.
 */
internal fun buildChatCompletionsStreamPayload(
    message: String,
    model: String? = null,
    systemMessage: String? = null,
    attachments: List<Attachment>? = null,
    voiceIntentMessages: JsonArray? = null,
    modelOverride: String? = null,
    profileName: String? = null,
): ChatPayloadResult {
    val resolvedModel = when {
        !modelOverride.isNullOrBlank() -> modelOverride
        !model.isNullOrBlank() -> model
        else -> "default"
    }
    val digest = renderSyntheticHistoryDigest(voiceIntentMessages, includePlainTurns = false)
    val effectiveSystem = mergeEphemeralContext(systemMessage, digest)
    val plainTurns = plainSyntheticTurns(voiceIntentMessages)
    val imageAttachments = attachments.orEmpty().filter { it.isImage }
    val payload = buildJsonObject {
        put("model", resolvedModel)
        put("stream", true)
        AgentDisplay.profileRequestName(profileName)?.let { put("profile", it) }
        putJsonArray("messages") {
            if (!effectiveSystem.isNullOrBlank()) {
                addJsonObject {
                    put("role", "system")
                    put("content", effectiveSystem)
                }
            }
            plainTurns.forEach { add(it) }
            addJsonObject {
                put("role", "user")
                if (imageAttachments.isNotEmpty()) {
                    put("content", buildJsonArray {
                        addJsonObject {
                            put("type", "text")
                            put("text", message)
                        }
                        imageAttachments.forEach { att ->
                            addJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") {
                                    put("url", "data:${att.contentType};base64,${att.content}")
                                }
                            }
                        }
                    })
                } else {
                    put("content", message)
                }
            }
        }
    }
    return ChatPayloadResult(
        payload = payload,
        droppedAttachments = attachments.orEmpty().filter { !it.isImage },
    )
}
