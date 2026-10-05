package com.hermesandroid.relay.push

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.hermesandroid.relay.MainActivity
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.setFcmLastToken
import com.hermesandroid.relay.network.upstream.GatewayKeepAliveService
import com.hermesandroid.relay.network.upstream.ActiveTurnKeepAliveRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * High-priority data messages wake the process so proactive WSS can reconnect
 * and drain the 24h buffer. Full message text is NOT carried on FCM.
 *
 * Uses the default [FirebaseApp] initialized by [SideloadFcmPushController].
 */
class HermesFcmMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onNewToken(token: String) {
        Log.i(TAG, "onNewToken len=${token.length}")
        scope.launch {
            try {
                applicationContext.setFcmLastToken(token)
            } catch (t: Throwable) {
                Log.w(TAG, "persist token failed", t)
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val type = data["type"].orEmpty()
        val action = data["action"].orEmpty()
        val messageId = data["message_id"].orEmpty()
        val title = data["title"]?.takeIf { it.isNotBlank() }
        Log.i(
            TAG,
            "onMessageReceived type=$type action=$action message_id=$messageId keys=${data.keys}",
        )
        val isWake =
            type == "hermes_wake" ||
                type == "proactive.wake" ||
                action == "wake" ||
                data.isNotEmpty()
        if (!isWake) return

        postWakeNotification(title = title, messageId = messageId)
        // Bring UI up so ConnectionViewModel reconnects + proactive.subscribe.
        kickMainActivity()
        // Best-effort keep-alive nudge when the user already opted into persistent
        // connection; GatewayKeepAliveService.update is main-thread only.
        mainHandler.post {
            try {
                GatewayKeepAliveService.update(
                    applicationContext,
                    persistent = true,
                    activeTurns = ActiveTurnKeepAliveRegistry.Snapshot(),
                    appForeground = true,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "keep-alive nudge failed", t)
            }
        }
    }

    private fun kickMainActivity() {
        try {
            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_NAV_ROUTE, "chat")
                putExtra(EXTRA_FCM_WAKE, true)
            }
            applicationContext.startActivity(intent)
        } catch (t: Throwable) {
            // Background activity starts are restricted on newer Android; the
            // heads-up notification remains the reliable user-visible path.
            Log.i(TAG, "startActivity after FCM wake deferred: ${t.message}")
        }
    }

    @SuppressLint("MissingPermission", "NotificationPermission")
    private fun postWakeNotification(title: String?, messageId: String) {
        if (!hasPostNotificationsPermission()) {
            Log.i(TAG, "POST_NOTIFICATIONS not granted — skip wake notification")
            return
        }
        ensureChannel()
        val tapIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_NAV_ROUTE, "chat")
            putExtra(EXTRA_FCM_WAKE, true)
        }
        val requestCode = (messageId.ifBlank { "wake" }.hashCode() and 0x7fffffff)
        val pending = PendingIntent.getActivity(
            applicationContext,
            requestCode,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val resolvedTitle = title?.takeIf { it.isNotBlank() }
            ?: getString(R.string.proactive_fcm_wake_title)
        val body = getString(R.string.proactive_fcm_wake_body)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(resolvedTitle)
            .setContentText(body)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID_BASE + (requestCode % 1000), notification)
        }.onFailure { Log.w(TAG, "wake notify failed", it) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.proactive_fcm_wake_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    private fun hasPostNotificationsPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "HermesFcmMsg"
        private const val CHANNEL_ID = "hermes_fcm_wake"
        private const val NOTIFICATION_ID_BASE = 0x46434D00 // "FCM"
        const val EXTRA_FCM_WAKE = "com.hermesandroid.relay.push.EXTRA_FCM_WAKE"
    }
}
