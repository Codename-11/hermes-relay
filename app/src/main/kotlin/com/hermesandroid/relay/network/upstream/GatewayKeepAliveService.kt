package com.hermesandroid.relay.network.upstream

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hermesandroid.relay.MainActivity
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.setGatewayKeepAlive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Opt-in foreground service that holds the app process up so the app's
 * connection to Hermes survives Android's background-freeze / Doze — i.e.
 * "persistent connection". Concretely it keeps the gateway chat WebSocket
 * (held by [com.hermesandroid.relay.viewmodel.ConnectionViewModel]'s
 * [GatewayChatClient]) open; for relay-paired setups, holding the whole
 * process up incidentally also keeps the relay WSS — device control and
 * notification mirroring — reachable. It does NOT warm Manage (stateless
 * HTTP) or voice (per-turn sockets).
 *
 * # Both flavors (Play declaration required)
 *
 * Declared in the MAIN manifest (unlike the device-control
 * [com.hermesandroid.relay.bridge.BridgeForegroundService], which is sideload
 * only), so googlePlay ships it too — the Home-Assistant-class persistent-
 * connection use case Google Play permits. The `specialUse` type is honest for
 * an always-on connection (`dataSync` is force-stopped after a 6h/day cap on
 * SDK 35) but requires a one-time Play Console foreground-service declaration
 * at submission. Off by default; only runs while the user enables the toggle.
 *
 * # It does NOT own the socket
 *
 * The service's only job is to hold the process in the foreground. The socket
 * stays open because [GatewayChatClient.setKeepAliveInBackground] stops its
 * idle-close timer while the toggle is on. On task removal (user swipes the app
 * away) the ViewModel + socket die with the process, so the service stops
 * itself rather than leave a notification that lies about being connected.
 *
 * # Android 15 watchdog
 *
 * On target SDK 35 any intent to a service that declares a foregroundServiceType
 * must call `startForeground` within 5s — so [onStartCommand] always does that
 * first, before branching on the action. Shutdown goes through [stop]
 * (`stopService`) to bypass [onStartCommand] entirely.
 */
class GatewayKeepAliveService : Service() {
    companion object {
        private const val TAG = "GatewayKeepAliveSvc"
        const val CHANNEL_ID = "gateway_keepalive"
        private const val CHANNEL_NAME = "Persistent connection"
        const val NOTIFICATION_ID = 4713
        const val ACTION_STOP = "com.hermesandroid.relay.gateway.KEEPALIVE_STOP"

        fun start(context: Context) {
            val intent = Intent(context.applicationContext, GatewayKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.applicationContext.startForegroundService(intent)
            } else {
                context.applicationContext.startService(intent)
            }
        }

        fun stop(context: Context) {
            // stopService() bypasses onStartCommand, so a "please shut down"
            // never trips the Android 15 foreground-start watchdog.
            context.applicationContext.stopService(
                Intent(context.applicationContext, GatewayKeepAliveService::class.java),
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "ACTION_STOP → user dismissed background connection")
            // Flip the pref off so ConnectionViewModel's collector won't
            // restart us on the next foreground.
            scope.launch { runCatching { applicationContext.setGatewayKeepAlive(false) } }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // The socket lives in the ViewModel, which dies when the task is
        // removed — keeping the notification would be a lie. Stop cleanly.
        Log.i(TAG, "onTaskRemoved → app swiped away; stopping keep-alive")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // The service + specialUse type + FOREGROUND_SERVICE_SPECIAL_USE permission
    // are all declared in the main manifest (both flavors), so the type is
    // satisfied. Suppress retained defensively — lint's ForegroundServiceType
    // check is finicky about correlating the runtime type arg with the manifest.
    @SuppressLint("ForegroundServiceType")
    private fun startForegroundNotification() {
        ensureChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground failed — stopping keep-alive", t)
            stopSelf()
        }
    }

    private fun buildNotification(): android.app.Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val tapPending = PendingIntent.getActivity(this, 0, tapIntent, pendingFlags)

        val stopIntent = Intent(this, GatewayKeepAliveService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(this, 1, stopIntent, pendingFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Hermes connection active")
            .setContentText("Keeping your connection to Hermes open in the background.")
            .setContentIntent(tapPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Turn off", stopPending)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description =
                    "Shows while Hermes keeps its connection open in the background so messages and live features stay responsive."
                setShowBadge(false)
            },
        )
    }
}
