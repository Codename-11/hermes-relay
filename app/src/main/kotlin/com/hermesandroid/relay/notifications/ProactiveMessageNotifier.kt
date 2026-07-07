package com.hermesandroid.relay.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.hermesandroid.relay.MainActivity
import com.hermesandroid.relay.R

/**
 * Posts a system notification for an agent-initiated ("proactive") message —
 * the agent reaching out via `send_message target=phone`, surfaced over the
 * relay's proactive channel and dispatched by [ProactiveMessageHandler].
 *
 * Structural twin of [TurnCompleteNotifier] (same channel-ensure,
 * permission-gate, tap-intent anatomy), with two differences:
 *  - **Stacks per message.** Turn-complete uses one slot because chat is one
 *    stream; here each distinct agent message deserves its own notification.
 *    The slot id is derived from the server's `message_id` so a re-delivered
 *    message replaces rather than duplicates, while distinct messages stack.
 *  - **Heads-up importance.** A proactive ping is something the user opted
 *    into and should see promptly, so the channel is `IMPORTANCE_HIGH`.
 *
 * Tap routes through the existing deep-link path (MainActivity
 * [MainActivity.EXTRA_NAV_ROUTE] → NavRouteRequest) to Chat, where the message
 * lives as a Thread.
 */
object ProactiveMessageNotifier {

    private const val TAG = "ProactiveNotifier"
    private const val CHANNEL_ID = "hermes_proactive"
    private const val CHANNEL_NAME = "Threads"

    /** Base for derived notification ids — keeps us clear of other slots. */
    private const val ID_BASE = 0x48524D00 // "HRM" + 00

    /**
     * Tap route — opens Chat, where the message lives as a Thread. Must match
     * `Screen.Chat.route()` in RelayApp. Routed via the EXTRA_NAV_ROUTE deep-link
     * path (MainActivity → NavRouteRequest → RelayApp collector). Opening the
     * exact Thread by chat_id is a follow-up (see TODO).
     */
    private const val TAP_ROUTE = "chat"

    /**
     * Post (or replace) a proactive-message notification.
     *
     * @param title Display title; blank falls back to "Hermes".
     * @param text The agent's message body.
     * @param messageId Server-assigned id; used to derive a stable slot so a
     *   re-delivery replaces rather than stacks. Blank → a fresh slot. Also
     *   carried to [ProactiveReplyReceiver] as the reply's `reply_to` anchor.
     * @param chatId Conversation the message belongs to; carried to the reply
     *   receiver so the user's answer continues the same thread.
     */
    @SuppressLint("MissingPermission", "NotificationPermission")
    fun notify(
        context: Context,
        title: String?,
        text: String,
        messageId: String?,
        chatId: String?,
    ) {
        ensureChannel(context)
        if (!hasPostNotificationsPermission(context)) {
            Log.i(TAG, "POST_NOTIFICATIONS not granted — skipping proactive notification")
            return
        }
        if (text.isBlank()) return

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_NAV_ROUTE, TAP_ROUTE)
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        // Distinct requestCode per slot so each notification gets its own
        // PendingIntent rather than all sharing slot 0's intent.
        val notificationId = slotFor(messageId)
        val tapPending =
            PendingIntent.getActivity(context, notificationId, tapIntent, pendingFlags)

        val resolvedTitle = title?.takeIf { it.isNotBlank() } ?: "Hermes"
        val collapsed = text.take(120)
        val expanded = text.take(1000)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(resolvedTitle)
            .setContentText(collapsed)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
            .setContentIntent(tapPending)
            .addAction(buildReplyAction(context, notificationId, resolvedTitle, messageId, chatId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        }.onFailure { Log.w(TAG, "notify failed", it) }
    }

    /**
     * Build the inline Reply action (Phase 2c). The [RemoteInput] lets the user
     * type a reply straight from the shade; the broadcast PendingIntent must be
     * **mutable** so the system can fill the typed text into it before delivery
     * to [ProactiveReplyReceiver].
     */
    private fun buildReplyAction(
        context: Context,
        notificationId: Int,
        title: String,
        messageId: String?,
        chatId: String?,
    ): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(ProactiveReplyReceiver.KEY_REPLY_TEXT)
            .setLabel("Reply to Hermes")
            .build()

        val replyIntent = Intent(context, ProactiveReplyReceiver::class.java).apply {
            action = ProactiveReplyReceiver.ACTION_REPLY
            putExtra(ProactiveReplyReceiver.EXTRA_MESSAGE_ID, messageId)
            putExtra(ProactiveReplyReceiver.EXTRA_CHAT_ID, chatId)
            putExtra(ProactiveReplyReceiver.EXTRA_TITLE, title)
            putExtra(ProactiveReplyReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        // FLAG_MUTABLE is required for RemoteInput on API 31+; the constant is
        // API 31, so guard the reference (pre-31 PendingIntents are mutable by
        // default, which is what RemoteInput needs there too).
        val mutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val replyPending = PendingIntent.getBroadcast(
            context,
            notificationId,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag,
        )

        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            replyPending,
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .build()
    }

    /**
     * Re-post a notification in the same [notificationId] slot to confirm a
     * sent reply (and clear the system's lingering RemoteInput progress
     * spinner). Called by [ProactiveReplyReceiver] after a reply is handed off.
     *
     * @param delivered false only when the relay wasn't reachable (no live
     *   multiplexer) — the user is told to open the app and retry.
     */
    @SuppressLint("MissingPermission", "NotificationPermission")
    fun confirmReply(
        context: Context,
        notificationId: Int,
        title: String?,
        replyText: String,
        delivered: Boolean,
    ) {
        ensureChannel(context)
        if (!hasPostNotificationsPermission(context)) return

        val resolvedTitle = title?.takeIf { it.isNotBlank() } ?: "Hermes"
        val line = if (delivered) {
            "You: ${replyText.take(1000)}"
        } else {
            "Reply not sent — open the app and try again."
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(resolvedTitle)
            .setContentText(line.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // A confirmation, not a fresh ping — don't re-alert the user.
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        }.onFailure { Log.w(TAG, "confirmReply failed", it) }
    }

    /** Derive a stable notification slot from the message id. */
    private fun slotFor(messageId: String?): Int {
        val key = messageId?.takeIf { it.isNotBlank() } ?: return ID_BASE
        // Keep within a small positive window above the base so re-delivery of
        // the same id collapses to one slot and distinct ids spread out.
        return ID_BASE + (key.hashCode() and 0xFFFF)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Messages your Hermes agent sends to you on its own."
            setShowBadge(true)
        }
        nm.createNotificationChannel(channel)
    }

    private fun hasPostNotificationsPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
