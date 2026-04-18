package com.hermesandroid.relay.voice

import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.VoiceIntentTrace
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * === v0.4.1 voice-intent → server session sync ===
 *
 * Materializes phone-local voice-intent traces into OpenAI-format
 * `assistant` (with `tool_calls`) + `tool` (with `tool_call_id`) message
 * pairs. The result is stitched into the chat payload's `messages` array
 * the next time [com.hermesandroid.relay.viewmodel.ChatViewModel] sends a
 * turn to the Hermes API server, so the gateway-side LLM sees prior voice
 * actions in its session memory.
 *
 * ## Why a separate builder
 *
 * The synthesis has three distinct concerns:
 *
 *  1. **Filter** — pick out only the voice-intent traces that haven't been
 *     synced yet (idempotency).
 *  2. **Format** — map each [VoiceIntentTrace] to a synthetic
 *     `{role:"assistant", tool_calls:[...]}` followed by a matching
 *     `{role:"tool", tool_call_id:..., content:...}`.
 *  3. **Emit** — return the result as a [JsonArray] the API client can
 *     splice into its request body verbatim.
 *
 * Splitting these into a top-level pure function keeps
 * [com.hermesandroid.relay.network.HermesApiClient] free of voice-mode
 * coupling and makes the synthesis trivially testable from a JVM unit
 * test (no Android dependencies, no Looper, no MockK).
 *
 * ## Wire shape (matches OpenAI Chat Completions)
 *
 * ```json
 * [
 *   {"role": "assistant", "content": "", "tool_calls": [
 *     {"id": "call_voiceintent_<uuid>", "type": "function",
 *      "function": {"name": "android_open_app",
 *                   "arguments": "{\"app_name\":\"Chrome\"}"}}
 *   ]},
 *   {"role": "tool", "tool_call_id": "call_voiceintent_<uuid>",
 *    "content": "{\"ok\":true,\"package\":\"com.android.chrome\"}"}
 * ]
 * ```
 *
 * `arguments` is a string (per OpenAI spec), `content` is a string. The
 * synthetic `assistant` message carries an empty `content` so the LLM
 * doesn't double-render the action narration — the structured `tool_calls`
 * field is the canonical source.
 *
 * On dispatch failure, the `tool` message includes an `ok:false`
 * envelope with `error` and optional `error_code`, so the LLM can
 * naturally describe the failure when asked "did that work?":
 *
 * ```json
 * {"role": "tool", "tool_call_id": "call_voiceintent_xyz",
 *  "content": "{\"ok\":false,\"error\":\"User denied confirmation\",
 *              \"error_code\":\"user_denied\"}"}
 * ```
 *
 * ## Idempotency
 *
 * The builder is a pure function — it does NOT mutate the input messages.
 * The caller (`ChatViewModel.startStream`) is responsible for invoking
 * [com.hermesandroid.relay.network.handlers.ChatHandler.markVoiceIntentsSynced]
 * after the request payload has been handed to the API client, which
 * flips [VoiceIntentTrace.syncedToServer] to true on each affected
 * message. Subsequent calls to [buildSyntheticMessages] will skip those
 * traces. This split lets a future caller dry-run the synthesis (e.g. to
 * estimate token cost) without committing to the side effect.
 */
object VoiceIntentSyncBuilder {

    /** Prefix for synthetic tool-call IDs we mint. Stable so tests can match. */
    internal const val CALL_ID_PREFIX = "call_voiceintent_"

    /**
     * Build a [JsonArray] of synthetic OpenAI-format messages from the
     * unsynced voice-intent traces in [history], preserving chronological
     * order.
     *
     * @param history Chat history snapshot from
     *   [com.hermesandroid.relay.network.handlers.ChatHandler.messages].
     *   Order matters — the builder walks the list in-place so synthetic
     *   messages appear in the same chronological position they had in the
     *   user's chat scroll.
     * @param idGenerator Pluggable ID source. Defaults to a `UUID.randomUUID`
     *   string. Tests pass a deterministic generator so payload assertions
     *   are stable.
     * @return Empty array when no unsynced voice-intent traces exist (the
     *   common case after the first sync). Two messages per unsynced trace
     *   otherwise (`assistant` + `tool`).
     */
    fun buildSyntheticMessages(
        history: List<ChatMessage>,
        idGenerator: () -> String = { java.util.UUID.randomUUID().toString() },
    ): JsonArray = buildJsonArray {
        for (msg in history) {
            val trace = msg.voiceIntent ?: continue
            if (trace.syncedToServer) continue
            // Skip malformed traces so a single bad entry doesn't poison
            // the whole turn. The fields are validated lazily here rather
            // than at construction so a refactor of the structured-trace
            // surface (e.g. allow android_navigate to skip args) doesn't
            // need to update a separate validator.
            if (!trace.toolName.startsWith("android_")) continue
            if (trace.argumentsJson.isBlank()) continue

            val callId = "$CALL_ID_PREFIX${idGenerator()}"

            // assistant message with structured tool_call
            addJsonObject {
                put("role", "assistant")
                put("content", "")
                putJsonArray("tool_calls") {
                    addJsonObject {
                        put("id", callId)
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", trace.toolName)
                            // OpenAI spec: arguments is a JSON-encoded string,
                            // not a nested object. Pass through verbatim.
                            put("arguments", trace.argumentsJson)
                        }
                    }
                }
            }
            // tool-role response keyed by the same call id
            addJsonObject {
                put("role", "tool")
                put("tool_call_id", callId)
                // The synthetic tool result content is always a JSON string.
                // The trace's resultJson is already JSON-encoded and may
                // include `ok:false` + `error` + `error_code` on failure.
                put("content", trace.resultJson)
            }
        }
    }

    /**
     * Convenience helper: returns true when [history] contains at least
     * one unsynced voice-intent trace. Lets callers cheaply skip building
     * the (potentially empty) synthetic-messages array on the common-case
     * "no voice intents this turn" path.
     */
    fun hasUnsynced(history: List<ChatMessage>): Boolean =
        history.any { it.voiceIntent?.syncedToServer == false }

    /**
     * Build a JSON-string payload value for a successful dispatch. Mirrors
     * the shape of [com.hermesandroid.relay.network.handlers.LocalDispatchResult.resultJson]
     * but ensures `ok:true` is always present so the synthetic tool message
     * reads cleanly to the LLM. Used by call sites that capture the local
     * dispatch outcome and need to materialize a [VoiceIntentTrace.resultJson]
     * value without hand-rolling the JSON every time.
     */
    fun successResultJson(extra: JsonObject? = null): String = buildJsonObject {
        put("ok", true)
        if (extra != null) {
            for ((k, v) in extra) put(k, v)
        }
    }.toString()

    /**
     * Build a JSON-string payload value for a failed dispatch, encoding
     * `error` (always present, fallback "unknown") and `error_code` when
     * provided. Pairs with [successResultJson] so call sites have a single
     * source of truth for the synthetic tool-response shape.
     */
    fun failureResultJson(
        errorMessage: String?,
        errorCode: String? = null,
    ): String = buildJsonObject {
        put("ok", false)
        put("error", errorMessage?.takeIf { it.isNotBlank() } ?: "unknown error")
        if (!errorCode.isNullOrBlank()) put("error_code", errorCode)
    }.toString()
}
