package com.hermesandroid.relay.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.hermesandroid.relay.network.relay.ChannelMultiplexer
import com.hermesandroid.relay.network.relay.models.Envelope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Captures an inline reply typed into a proactive-message notification and
 * sends it back to the agent as a `proactive.reply` envelope (Phase 2c — the
 * inbound half of two-way phone messaging).
 *
 * [ProactiveMessageNotifier] attaches a [RemoteInput] Reply action whose
 * (mutable) PendingIntent targets this receiver, carrying the originating
 * message's `chat_id` / `message_id` as extras. On reply the system fills the
 * RemoteInput results in and delivers the broadcast here; we read the text,
 * push a `proactive.reply` over the live relay WS via [multiplexer], and
 * re-post the notification as a confirmation (clearing the system's reply
 * spinner).
 *
 * **Reach to the relay mirrors [HermesNotificationCompanion].** A receiver
 * lives outside the ViewModel scope (and may run in a freshly-spawned process
 * if the app was killed), so it can't hold a ViewModel reference. It reads the
 * live [ChannelMultiplexer] from a static slot that [ConnectionViewModel]
 * injects. When the slot is null (app process gone / never connected) the
 * reply is dropped best-effort — the same "don't replay while out of range"
 * semantics as the notification companion — and the confirmation tells the
 * user to open the app. The in-app inbox reply box is the reliable path when
 * disconnected.
 */
class ProactiveReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY) return

        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY_TEXT)
            ?.toString()
            ?.trim()
            .orEmpty()

        val chatId = intent.getStringExtra(EXTRA_CHAT_ID)
        val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID)
        val title = intent.getStringExtra(EXTRA_TITLE)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)

        if (text.isEmpty()) {
            Log.d(TAG, "empty reply text — ignoring")
            return
        }

        val delivered = sendReply(text = text, chatId = chatId, replyTo = messageId)

        // Replace the heads-up (and its lingering reply spinner) with a
        // confirmation. `notificationId` matches the slot the original used.
        ProactiveMessageNotifier.confirmReply(
            context = context,
            notificationId = notificationId,
            title = title,
            replyText = text,
            delivered = delivered,
        )
    }

    /**
     * Hand a `proactive.reply` to the relay multiplexer. Returns true when the
     * envelope was handed off (the WS layer drops it silently if the relay is
     * momentarily disconnected); false only when no multiplexer is wired
     * (process not connected) — that's the case worth telling the user about.
     */
    private fun sendReply(text: String, chatId: String?, replyTo: String?): Boolean {
        val mux = multiplexer
        if (mux == null) {
            Log.i(TAG, "no multiplexer — relay not connected; dropping reply")
            return false
        }
        return runCatching {
            mux.send(
                Envelope(
                    channel = "proactive",
                    type = "proactive.reply",
                    payload = buildJsonObject {
                        put("text", text)
                        if (!chatId.isNullOrBlank()) put("chat_id", chatId)
                        if (!replyTo.isNullOrBlank()) put("reply_to", replyTo)
                        put("ts", System.currentTimeMillis())
                    },
                ),
            )
            true
        }.onFailure { Log.w(TAG, "failed to send proactive.reply", it) }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "ProactiveReplyRcvr"

        /** Explicit action so a stray broadcast can't trigger a send. */
        const val ACTION_REPLY = "com.hermesandroid.relay.action.PROACTIVE_REPLY"

        /** RemoteInput result key carrying the typed reply text. */
        const val KEY_REPLY_TEXT = "key_proactive_reply_text"

        const val EXTRA_MESSAGE_ID = "extra_proactive_message_id"
        const val EXTRA_CHAT_ID = "extra_proactive_chat_id"
        const val EXTRA_TITLE = "extra_proactive_title"
        const val EXTRA_NOTIFICATION_ID = "extra_proactive_notification_id"

        /**
         * Live relay multiplexer, injected by [com.hermesandroid.relay.viewmodel.ConnectionViewModel]
         * (mirror of [HermesNotificationCompanion.multiplexer]). Null when the
         * app isn't connected.
         */
        @Volatile
        var multiplexer: ChannelMultiplexer? = null
    }
}
