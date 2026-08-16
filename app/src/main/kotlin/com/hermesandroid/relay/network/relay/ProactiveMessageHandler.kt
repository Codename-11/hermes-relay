package com.hermesandroid.relay.network.relay

import android.content.Context
import android.util.Log
import com.hermesandroid.relay.network.relay.models.Envelope
import com.hermesandroid.relay.notifications.ProactiveMessageNotifier
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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
    var toSession: ((ProactiveMessage) -> Boolean)? = null,
    /**
     * Sink for the relay's per-reply ack (`proactive.reply.ack`) — lets the
     * chat layer settle a Thread reply bubble from SENDING → DELIVERED. Wired
     * after construction (the ChatViewModel isn't available at build time).
     * `(clientMsgId, status)`.
     */
    var onReplyAck: ((String, String) -> Unit)? = null,
    /**
     * Show an inbound message inline in the Chat **Thread** it belongs to, when
     * that Thread is currently open. Returns true if it was shown there — in
     * which case the message is persisted but not also notified (you're already
     * looking at the conversation). The unified-Threads counterpart of
     * [toSession]; wired after construction.
     */
    var injectIntoThread: ((ProactiveMessage) -> Boolean)? = null,
    /** One callback per completed queued-message flush, never per message. */
    var onBacklogDelivered: ((Int) -> Unit)? = null,
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
            "proactive.backlog.complete" -> {
                val count = envelope.payload["count"]?.jsonPrimitive?.intOrNull ?: 0
                if (count > 0) onBacklogDelivered?.invoke(count)
            }
            // Per-reply ack — settle the matching Thread reply bubble (the
            // `client_msg_id` is the id the app stamped on its own reply).
            "proactive.reply.ack" -> {
                val clientMsgId = envelope.payload["client_msg_id"]?.jsonPrimitive?.contentOrNull
                val status = envelope.payload["status"]?.jsonPrimitive?.contentOrNull ?: "received"
                if (!clientMsgId.isNullOrBlank()) onReplyAck?.invoke(clientMsgId, status)
            }
            else -> Log.d(TAG, "ignoring proactive type ${envelope.type}")
        }
    }

    /** Route a parsed message: into the open Thread if it belongs there, else
     *  the durable inbox log + the surface its hint selects. */
    private fun dispatch(msg: ProactiveMessage) {
        // Persist first even when the currently open Thread consumes the live
        // message. Agent-initiated outbound sends do not create a gateway
        // session until the phone replies, so this cache is the provisional
        // Thread transcript during that gap.
        toInbox?.invoke(msg)
        // The surfacing hint selects the additional surface. Thread injection
        // is best-effort presentation of the persisted row, not itself a reason
        // to suppress an explicitly requested notification.
        when (msg.surfacing?.lowercase()) {
            "inbox" -> {
                injectIntoThread?.invoke(msg)
            }
            "session" -> {
                val delivered = injectIntoThread?.invoke(msg) == true ||
                    toSession?.invoke(msg) == true
                if (!delivered) notify(msg)
            }
            // null / "default" / "notification" / anything unrecognized.
            else -> {
                injectIntoThread?.invoke(msg)
                notify(msg)
            }
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
            replyTo = payload["reply_to"]?.jsonPrimitive?.contentOrNull,
            arrivedWhileAway = payload["queued_delivery"]?.jsonPrimitive?.booleanOrNull == true,
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
    /** Id of the message this one answers, if any (server threading hint). */
    val replyTo: String? = null,
    /** True only when Relay explicitly marked this as a reconnect queue flush. */
    val arrivedWhileAway: Boolean = false,
)
