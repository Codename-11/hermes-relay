package com.hermesandroid.relay.network.relay

import android.content.Context
import android.util.Log
import com.hermesandroid.relay.network.relay.models.Envelope
import com.hermesandroid.relay.notifications.ProactiveMessageNotifier
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Handles inbound `proactive` channel envelopes — agent-initiated messages
 * the relay pushes over the existing phone WSS (the server→app counterpart of
 * the bridge channel). Sibling of [BridgeCommandHandler].
 *
 * Wire protocol (server → app):
 * ```json
 * {
 *   "channel": "proactive",
 *   "type": "phone.message",
 *   "id": "<uuid>",
 *   "payload": {
 *     "message_id": "...",
 *     "chat_id": "phone",
 *     "text": "build is green",
 *     "title": "Hermes",
 *     "surfacing": null,          // "notification" | "inbox" | "session" | null(default)
 *     "reply_to": null,
 *     "metadata": { ... },
 *     "sent_at": 1719600000000
 *   }
 * }
 * ```
 *
 * The inbox is the **always-present** durable log — every received message is
 * recorded there. The `surfacing` hint then selects the *additional* surface:
 *  - `null` / `"default"` / `"notification"` → also raise a system notification
 *  - `"inbox"`                               → inbox only (silent)
 *  - `"session"`                             → also inject into the active chat
 *      session ([toSession]); falls back to a notification when no session sink
 *      is wired
 *
 * The [toInbox] / [toSession] sinks are injected by [ConnectionViewModel] so
 * the handler stays free of ViewModel/DataStore dependencies and unit-testable.
 * [toSession] is a `var` so it can be wired after construction (the ChatViewModel
 * isn't available when the handler is built).
 */
class ProactiveMessageHandler(
    private val context: Context,
    /** Sink for the dedicated Hermes inbox (Phase 2a) — the always-present log. */
    private val toInbox: ((ProactiveMessage) -> Unit)? = null,
    /** Sink for injecting into the active chat session (Phase 2b). */
    var toSession: ((ProactiveMessage) -> Unit)? = null,
) {

    fun onMessage(envelope: Envelope) {
        when (envelope.type) {
            "phone.message" -> {
                val msg = parse(envelope.payload)
                if (msg == null) {
                    Log.w(TAG, "dropping malformed phone.message")
                    return
                }
                dispatch(msg)
            }
            // Subscribe ack — informational; nothing to do client-side.
            "proactive.subscribed" -> Log.d(TAG, "proactive subscribe acked")
            else -> Log.d(TAG, "ignoring proactive type ${envelope.type}")
        }
    }

    /** Route a parsed message: inbox always, plus the surface its hint selects. */
    private fun dispatch(msg: ProactiveMessage) {
        // The inbox is the always-present durable log of agent-initiated
        // messages — record every one regardless of surfacing.
        toInbox?.invoke(msg)
        when (msg.surfacing?.lowercase()) {
            "inbox" -> { /* inbox only — already recorded above */ }
            "session" -> {
                val sink = toSession
                // Inject into the active session; if no session sink is wired
                // (or no active chat), fall back to a notification so it isn't
                // silently missed (the inbox copy already exists either way).
                if (sink != null) sink.invoke(msg) else notify(msg)
            }
            // null / "default" / "notification" / anything unrecognized.
            else -> notify(msg)
        }
    }

    private fun notify(msg: ProactiveMessage) {
        ProactiveMessageNotifier.notify(
            context = context,
            title = msg.title,
            text = msg.text,
            messageId = msg.messageId,
            chatId = msg.chatId,
        )
    }

    private fun parse(payload: JsonObject): ProactiveMessage? {
        val text = payload["text"]?.jsonPrimitive?.contentOrNull
        if (text.isNullOrBlank()) return null
        return ProactiveMessage(
            messageId = payload["message_id"]?.jsonPrimitive?.contentOrNull,
            chatId = payload["chat_id"]?.jsonPrimitive?.contentOrNull,
            text = text,
            title = payload["title"]?.jsonPrimitive?.contentOrNull,
            surfacing = payload["surfacing"]?.jsonPrimitive?.contentOrNull,
            sentAt = payload["sent_at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
        )
    }

    companion object {
        private const val TAG = "ProactiveMsgHandler"
    }
}

/**
 * A parsed agent-initiated message. `surfacing` is the optional route hint
 * (null = app default); Phase 2 keys inbox/session delivery off it.
 */
data class ProactiveMessage(
    val messageId: String?,
    val chatId: String?,
    val text: String,
    val title: String?,
    val surfacing: String?,
    val sentAt: Long?,
)
