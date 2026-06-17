package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.HermesCard
import com.hermesandroid.relay.data.HermesCardAction
import com.hermesandroid.relay.data.HermesCardDispatch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * === v0.7.x rich-card dispatch → server session sync ===
 *
 * Twin of [com.hermesandroid.relay.voice.VoiceIntentSyncBuilder] for
 * [HermesCardDispatch] records. On the next chat send, unsynced card
 * dispatches in local history materialize as OpenAI-format `assistant`
 * (with `tool_calls`) + `tool` (with `tool_call_id`) message pairs. The
 * Hermes API server splices them into the session conversation it feeds
 * the LLM, so the gateway-side model sees which card action the user
 * picked — even when the action mode wouldn't normally reach the server
 * (e.g. `open_url` fires a local intent and never calls `sendMessage`).
 *
 * ## Why a separate builder (not a merge into VoiceIntentSyncBuilder)
 *
 * Voice intents are keyed by real upstream tool names (`android_open_app`,
 * `android_send_sms`). Card dispatches are keyed by a synthetic tool name
 * (`hermes_card_action`) the server doesn't actually know how to execute —
 * it's a historical audit record, not an instruction. Keeping the two
 * builders separate makes that distinction structurally obvious and
 * prevents a future refactor from accidentally treating a card dispatch
 * as a dispatchable android tool.
 *
 * ## Wire shape
 *
 * ```json
 * [
 *   {"role": "assistant", "content": "", "tool_calls": [
 *     {"id": "call_carddispatch_<uuid>", "type": "function",
 *      "function": {"name": "hermes_card_action",
 *                   "arguments": "{\"card_type\":\"approval_request\",
 *                                  \"card_key\":\"shell-001\",
 *                                  \"card_title\":\"Run shell command?\",
 *                                  \"action_value\":\"/approve\",
 *                                  \"action_label\":\"Allow\",
 *                                  \"action_mode\":\"slash_command\"}"}}
 *   ]},
 *   {"role": "tool", "tool_call_id": "call_carddispatch_<uuid>",
 *    "content": "{\"ok\":true,\"dispatched_at\":1713614400000}"}
 * ]
 * ```
 *
 * `card_title` is included so the LLM can describe the interaction
 * naturally ("you approved the card titled _Run shell command?_") even
 * if the card itself got trimmed from rolling context.
 *
 * ## Idempotency
 *
 * Pure function — does NOT mutate input. Caller
 * ([com.hermesandroid.relay.viewmodel.ChatViewModel.startStream]) is
 * responsible for calling
 * [com.hermesandroid.relay.network.upstream.ChatHandler.markCardDispatchesSynced]
 * after the API client accepts the request. That timing mirrors the
 * voice-intent path exactly — commit-after-handoff so a thrown
 * request-building exception leaves the dispatches unsynced for the
 * next turn.
 */
object CardDispatchSyncBuilder {

    /** Prefix for synthetic tool-call IDs. Stable so tests can match. */
    internal const val CALL_ID_PREFIX = "call_carddispatch_"

    /**
     * Gateway ask cards (clarify/approval/sudo/secret) are EXCLUDED from
     * sync entirely: the server already absorbed the answer through the
     * blocking ask RPC, and for secrets the value (even its sanitized
     * stamp) must not be replayed into session memory. Ask cards live only
     * on locally-built messages with this id prefix (see
     * ChatViewModel.presentInteractionAsk), and carry `ask.*` card types.
     */
    internal const val ASK_MESSAGE_ID_PREFIX = "ask-"
    private const val ASK_CARD_TYPE_PREFIX = "ask."

    /** Synthetic tool name. Intentionally namespaced so the upstream tool
     *  dispatcher has no chance of mistaking it for a real executor. */
    internal const val TOOL_NAME = "hermes_card_action"

    fun buildSyntheticMessages(
        history: List<ChatMessage>,
        idGenerator: () -> String = { java.util.UUID.randomUUID().toString() },
    ): JsonArray = buildJsonArray {
        for (msg in history) {
            // Only the dispatch list gates emission. A message whose cards
            // were trimmed from the rolling buffer (cards empty, dispatches
            // present) MUST still sync its dispatches as bare envelopes —
            // see the class docstring + the card==null fallback below. The
            // old `msg.cards.isEmpty()` short-circuit silently dropped those
            // audit records (regression caught by
            // CardDispatchSyncBuilderTest.buildSyntheticMessages_unknownCardKey_stillEmitsBareEnvelope).
            if (msg.cardDispatches.isEmpty()) continue
            if (msg.id.startsWith(ASK_MESSAGE_ID_PREFIX)) continue

            // Index cards by their resolved cardKey so the dispatch
            // lookup is O(1). Mirrors the key formula in MessageBubble.kt
            // (`card.id ?: "idx:$index"`) so the two paths can never
            // disagree.
            val cardByKey = buildCardKeyIndex(msg.cards)

            for (dispatch in msg.cardDispatches) {
                if (dispatch.syncedToServer) continue

                val card = cardByKey[dispatch.cardKey]
                // Belt for ask cards that somehow live on a non-ask message.
                if (card?.type?.startsWith(ASK_CARD_TYPE_PREFIX) == true) continue
                // Dispatches whose card is gone from the message (trimmed
                // by the rolling MAX_MESSAGES buffer, for instance) still
                // get synced, just with less context — the key + value
                // is enough for audit. Falls back to a bare envelope.
                val action = card?.actions?.firstOrNull {
                    it.value == dispatch.actionValue
                }

                val callId = "$CALL_ID_PREFIX${idGenerator()}"

                addJsonObject {
                    put("role", "assistant")
                    put("content", "")
                    putJsonArray("tool_calls") {
                        addJsonObject {
                            put("id", callId)
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", TOOL_NAME)
                                // OpenAI spec: `arguments` is a
                                // JSON-encoded STRING, not a nested
                                // object. Build the inner object then
                                // stringify it.
                                put(
                                    "arguments",
                                    buildArgumentsJson(card, dispatch, action),
                                )
                            }
                        }
                    }
                }
                addJsonObject {
                    put("role", "tool")
                    put("tool_call_id", callId)
                    put("content", buildResultJson(dispatch))
                }
            }
        }
    }

    fun hasUnsynced(history: List<ChatMessage>): Boolean =
        history.any { msg ->
            !msg.id.startsWith(ASK_MESSAGE_ID_PREFIX) &&
                msg.cardDispatches.any { !it.syncedToServer }
        }

    // === helpers ===

    /** Resolve every card to the same key MessageBubble uses for rendering. */
    private fun buildCardKeyIndex(cards: List<HermesCard>): Map<String, HermesCard> {
        val out = HashMap<String, HermesCard>(cards.size)
        cards.forEachIndexed { index, card ->
            val key = card.id ?: "idx:$index"
            out[key] = card
        }
        return out
    }

    private fun buildArgumentsJson(
        card: HermesCard?,
        dispatch: HermesCardDispatch,
        action: HermesCardAction?,
    ): String = buildJsonObject {
        put("card_key", dispatch.cardKey)
        put("action_value", dispatch.actionValue)
        if (card != null) {
            put("card_type", card.type)
            if (!card.title.isNullOrBlank()) put("card_title", card.title)
        }
        if (action != null) {
            put("action_label", action.label)
            put(
                "action_mode",
                action.mode ?: HermesCardAction.Modes.SEND_TEXT,
            )
            if (!action.style.isNullOrBlank()) put("action_style", action.style)
        }
    }.toString()

    private fun buildResultJson(dispatch: HermesCardDispatch): String =
        buildJsonObject {
            put("ok", true)
            put("dispatched_at", dispatch.timestamp)
        }.toString()
}
