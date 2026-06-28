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
 * Phase 1c surfaces every message as a system notification. The `surfacing`
 * hint and [onReceived] callback are the seams for Phase 2 (a dedicated
 * Hermes inbox surface) and session injection — the routing decision keyed on
 * `surfacing` is centralized in [dispatch] so those phases extend one place.
 */
class ProactiveMessageHandler(
    private val context: Context,
    /**
     * Optional downstream sink for parsed messages (Phase 2: inbox store /
     * session injection). Invoked for every received message regardless of
     * surfacing; the notification decision is separate.
     */
    private val onReceived: ((ProactiveMessage) -> Unit)? = null,
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

    /**
     * Route a parsed message to the right surface(s). Phase 1c: always raise a
     * notification. Phase 2 will branch on [ProactiveMessage.surfacing]
     * (notification / inbox / session) here.
     */
    private fun dispatch(msg: ProactiveMessage) {
        ProactiveMessageNotifier.notify(
            context = context,
            title = msg.title,
            text = msg.text,
            messageId = msg.messageId,
        )
        onReceived?.invoke(msg)
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
