package com.hermesandroid.relay.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hermesandroid.relay.MainActivity
import com.hermesandroid.relay.R
import com.hermesandroid.relay.network.upstream.GatewayAsk

/**
 * Action-required notifications for Gateway turns blocked on user input.
 *
 * Notification copy is deliberately generic. Approval commands, clarification
 * questions, secret prompts, environment-variable names, and password requests
 * stay inside the authenticated chat surface and never appear on the lock
 * screen. A stable tag derived from the durable session and request identity
 * makes replay/reconnect delivery replace the existing notification.
 */
object InteractionRequestNotifier {

    private const val TAG = "InteractionNotifier"
    internal const val CHANNEL_ID = "chat_interactions"
    private const val CHANNEL_NAME = "Hermes needs input"
    internal const val NOTIFICATION_ID = 3823
    internal const val DEFAULT_PROFILE_ROUTE_VALUE = "__server_default__"

    internal fun shouldPost(
        alertsEnabled: Boolean,
        appForeground: Boolean,
        hasPermission: Boolean,
    ): Boolean = alertsEnabled && !appForeground && hasPermission

    internal fun requestKey(sessionId: String, ask: GatewayAsk, profile: String? = null): String {
        val requestIdentity = ask.requestId?.takeIf { it.isNotBlank() } ?: "session"
        return "${profile ?: DEFAULT_PROFILE_ROUTE_VALUE}:$sessionId:${ask.kind.name}:$requestIdentity"
    }

    internal fun notificationTag(
        sessionId: String,
        ask: GatewayAsk,
        profile: String? = null,
    ): String = "gateway-interaction:${requestKey(sessionId, ask, profile)}"

    internal fun chatRoute(sessionId: String, profile: String? = null): String =
        "chat?sessionId=${Uri.encode(sessionId)}&profile=${Uri.encode(profile ?: DEFAULT_PROFILE_ROUTE_VALUE)}"

    internal fun safeTitle(ask: GatewayAsk): String = when (ask.kind) {
        GatewayAsk.Kind.APPROVAL -> "Hermes needs approval"
        GatewayAsk.Kind.CLARIFY -> "Hermes has a question"
        GatewayAsk.Kind.SUDO,
        GatewayAsk.Kind.SECRET,
        -> "Hermes needs sensitive input"
    }

    internal fun safeBody(ask: GatewayAsk): String = when (ask.kind) {
        GatewayAsk.Kind.APPROVAL -> "Open Hermes to review the requested action."
        GatewayAsk.Kind.CLARIFY -> "Open Hermes to answer and continue this turn."
        GatewayAsk.Kind.SUDO,
        GatewayAsk.Kind.SECRET,
        -> "Open Hermes to respond securely."
    }

    @SuppressLint("MissingPermission", "NotificationPermission")
    fun notify(
        context: Context,
        sessionId: String,
        ask: GatewayAsk,
        profile: String? = null,
        alertsEnabled: Boolean,
        appForeground: Boolean,
    ): Boolean {
        ensureChannel(context)
        if (!shouldPost(alertsEnabled, appForeground, hasPostNotificationsPermission(context))) {
            return false
        }

        val tag = notificationTag(sessionId, ask, profile)
        val requestKey = requestKey(sessionId, ask, profile)
        val requestCode = requestKey.hashCode()
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = Uri.Builder()
                .scheme("hermes-relay")
                .authority("interaction")
                .appendPath(requestKey)
                .build()
            putExtra(MainActivity.EXTRA_NAV_ROUTE, chatRoute(sessionId, profile))
        }
        val tapPending = PendingIntent.getActivity(
            context,
            requestCode,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = safeTitle(ask)
        val body = safeBody(ask)
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Hermes needs your input")
            .setContentText("Open Hermes to continue.")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(tapPending)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()

        return runCatching {
            NotificationManagerCompat.from(context).notify(tag, NOTIFICATION_ID, notification)
            true
        }.onFailure {
            Log.w(TAG, "notify failed", it)
        }.getOrDefault(false)
    }

    fun cancel(context: Context, sessionId: String, ask: GatewayAsk, profile: String? = null) {
        runCatching {
            NotificationManagerCompat.from(context)
                .cancel(notificationTag(sessionId, ask, profile), NOTIFICATION_ID)
        }.onFailure {
            Log.w(TAG, "cancel failed", it)
        }
    }

    /**
     * Clear only this feature's notifications. Android keeps notifications
     * across process death, so the active-notification scan is also used when
     * MainActivity returns without an in-memory request registry.
     */
    fun cancelAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.activeNotifications
                .filter { it.notification.channelId == CHANNEL_ID }
                .forEach { manager.cancel(it.tag, it.id) }
        }.onFailure {
            Log.w(TAG, "cancelAll failed", it)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Alerts when a Hermes turn is waiting for your response."
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setShowBadge(true)
            },
        )
    }

    private fun hasPostNotificationsPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
