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

/**
 * One-shot "Hermes finished responding" notification — posted from
 * ChatViewModel's turn-complete path when the app is backgrounded, never
 * for cancelled streams or errors. Structural twin of
 * [com.hermesandroid.relay.bridge.AutoDisableWorker]'s notification half:
 * same channel-ensure, permission-gate, and tap-intent anatomy.
 *
 * One stable slot ([NOTIFICATION_ID]) — chat is one stream, so the latest
 * turn replaces any prior notification rather than stacking noise. The
 * caller cancels it via [cancel] when the user returns to the app
 * (MainActivity onResume).
 *
 * Tap routes through the existing cross-layer deep-link path: the intent
 * carries [MainActivity.EXTRA_NAV_ROUTE] → MainActivity pumps it onto
 * NavRouteRequest → RelayApp's collector navigates.
 */
object TurnCompleteNotifier {

    private const val TAG = "TurnCompleteNotifier"
    private const val CHANNEL_ID = "chat_turn_complete"
    private const val CHANNEL_NAME = "Hermes replies"
    const val NOTIFICATION_ID = 3822

    /**
     * Compose nav route for the Chat tab. Hardcoded on purpose to avoid
     * pulling the ui.RelayApp graph into the notifications classpath — if
     * Screen.Chat.route() changes in RelayApp.kt, change it here too.
     * (Same convention as BridgeForegroundService's settings deep-link.)
     */
    private const val CHAT_ROUTE = "chat"

    /**
     * Post (or replace) the turn-complete notification.
     *
     * @param agentName Display name for the title; blank falls back to
     *   "Hermes".
     * @param responseText Final assistant text — collapsed line is the
     *   first 120 chars, BigTextStyle expands to 400.
     * @param toolCount Number of tool calls the turn ran; 0 hides the
     *   subText line.
     * @param durationSeconds Wall-clock turn duration for the subText
     *   ("3 tools · 42s"); null renders the count alone.
     */
    // Lint can't trace through [hasPostNotificationsPermission] to see that
    // we early-return when the runtime grant isn't held, and the notify()
    // call is also wrapped in runCatching to swallow SecurityException as
    // a belt-and-braces. Suppress here rather than inlining the check —
    // the helper exists so the same gate can grow more conditions later
    // without each call site re-implementing it. Both IDs are needed:
    // `NotificationPermission` is the notify()-specific check (POST_NOTIFICATIONS
    // on API 33+); `MissingPermission` is the generic fallback.
    @SuppressLint("MissingPermission", "NotificationPermission")
    fun notifyTurnComplete(
        context: Context,
        agentName: String?,
        responseText: String,
        toolCount: Int = 0,
        durationSeconds: Long? = null,
    ) {
        ensureChannel(context)
        if (!hasPostNotificationsPermission(context)) {
            Log.i(TAG, "POST_NOTIFICATIONS not granted — skipping turn-complete notification")
            return
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_NAV_ROUTE, CHAT_ROUTE)
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val tapPending = PendingIntent.getActivity(context, 0, tapIntent, pendingFlags)

        val title = agentName?.takeIf { it.isNotBlank() } ?: "Hermes"
        val collapsed = responseText.take(120)
        val expanded = responseText.take(400)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(collapsed)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
            .setContentIntent(tapPending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (toolCount > 0) {
            val tools = "$toolCount tool${if (toolCount == 1) "" else "s"}"
            builder.setSubText(
                durationSeconds?.let { "$tools · ${it}s" } ?: tools
            )
        }

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        }.onFailure { Log.w(TAG, "notifyTurnComplete: notify failed", it) }
    }

    /** Clear the slot — call from MainActivity.onResume so returning to the app dismisses it. */
    fun cancel(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }.onFailure { Log.w(TAG, "cancel: failed", it) }
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
