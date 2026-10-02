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
import androidx.core.content.ContextCompat
import com.hermesandroid.relay.MainActivity
import com.hermesandroid.relay.R

/** One reply slot per durable conversation, routed to its exact owner. */
object TurnCompleteNotifier {

    private const val TAG = "TurnCompleteNotifier"
    private const val CHANNEL_ID = "chat_turn_complete"
    private const val CHANNEL_NAME = "Hermes replies"
    const val NOTIFICATION_ID = 3822

    private const val EXTRA_TURN = "chat_notification_turn"

    internal fun notificationTag(target: ChatNotificationTarget): String = "chat-reply:${target.key}"

    @SuppressLint("MissingPermission", "NotificationPermission")
    fun notifyTurnComplete(
        context: Context,
        target: ChatNotificationTarget,
        turnId: String,
        agentName: String?,
        responseText: String,
        toolCount: Int = 0,
        durationSeconds: Long? = null,
        identity: ReplyNotificationIdentity? = null,
        avatar: android.graphics.Bitmap? = null,
    ) {
        ensureChannel(context)
        if (!hasPostNotificationsPermission(context)) {
            Log.i(TAG, "POST_NOTIFICATIONS not granted — skipping turn-complete notification")
            return
        }

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val tag = notificationTag(target)
        // Replay of the same terminal event cannot alert twice; a later turn
        // replaces only this conversation's slot and may alert again.
        if (manager.activeNotifications.any {
                it.tag == tag && it.notification.extras.getString(EXTRA_TURN) == turnId
            }) return
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = android.net.Uri.parse(target.key)
            putExtra(MainActivity.EXTRA_NAV_ROUTE, target.route())
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val tapPending = PendingIntent.getActivity(context, 0, tapIntent, pendingFlags)

        val agent = identity?.displayName?.takeIf { it.isNotBlank() }
            ?: identity?.profileName?.takeIf { it.isNotBlank() }
            ?: agentName?.takeIf { it.isNotBlank() } ?: "Hermes"
        val conversation = identity?.conversationTitle?.takeIf { it.isNotBlank() }
            ?: "…${target.sessionId.takeLast(12)}"
        val title = "$agent · ${conversation.take(100)}"
        val collapsed = responseText.take(120)
        val expanded = responseText.take(400)

        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Hermes replied")
            .setContentText("Open Hermes to view the reply.")
            .build()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setLargeIcon(avatar)
            .setContentText(collapsed)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
            .setContentIntent(tapPending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .addExtras(android.os.Bundle().apply {
                putString(ChatNotificationTarget.EXTRA_OWNER, target.key)
                putString(EXTRA_TURN, turnId)
            })
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (toolCount > 0) {
            val tools = "$toolCount tool${if (toolCount == 1) "" else "s"}"
            builder.setSubText(
                durationSeconds?.let { "$tools · ${it}s" } ?: tools
            )
        }

        runCatching {
            NotificationManagerCompat.from(context).notify(tag, NOTIFICATION_ID, builder.build())
        }.onFailure { Log.w(TAG, "notifyTurnComplete: notify failed", it) }
    }

    fun cancel(context: Context, target: ChatNotificationTarget) {
        NotificationManagerCompat.from(context).cancel(notificationTag(target), NOTIFICATION_ID)
    }

    /** Only the explicit alerts-off preference clears every conversation. */
    fun cancelAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.activeNotifications.filter { it.notification.channelId == CHANNEL_ID }
            .forEach { manager.cancel(it.tag, it.id) }
        // Retire the pre-ownership version's untagged slot too.
        manager.cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Notifies when Hermes finishes responding while the app is in the background."
            // Unlike bridge_auto_disable, a reply badge is desirable.
            setShowBadge(true)
        }
        nm.createNotificationChannel(channel)
    }

    private fun hasPostNotificationsPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}
