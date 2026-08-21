package com.hermesandroid.relay.network.upstream

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
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
 * Foreground service that holds the app process up so work the user already
 * started survives Android's background-freeze / Doze. It runs automatically
 * while one or more turns are active, or continuously when the user enables
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
 * at submission. Continuous idle retention is off by default; active work is
 * protected automatically and releases its lease on terminal settlement.
 *
 * # It does NOT own the socket
 *
 * The service's only job is to hold the process in the foreground. The socket
 * stays open because [GatewayChatClient.setKeepAliveInBackground] stops its
 * idle-close timer while retention is required. On task removal (user swipes the app
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
        private const val ACTION_REFRESH = "com.hermesandroid.relay.gateway.KEEPALIVE_REFRESH"
        private const val EXTRA_PERSISTENT = "persistent"
        private const val EXTRA_ACTIVE_TURNS = "active_turns"
        private const val EXTRA_WAITING_SESSIONS = "waiting_sessions"
        @Volatile private var runningInstance: GatewayKeepAliveService? = null

        fun update(
            context: Context,
            persistent: Boolean,
            activeTurns: ActiveTurnKeepAliveRegistry.Snapshot,
        ) {
            if (!persistent && !activeTurns.required) {
                stop(context)
                return
            }
            runningInstance?.let { service ->
                service.applyState(persistent, activeTurns)
                service.startForegroundNotification()
                return
            }
            val intent = Intent(context.applicationContext, GatewayKeepAliveService::class.java)
                .setAction(ACTION_REFRESH)
                .putExtra(EXTRA_PERSISTENT, persistent)
                .putExtra(EXTRA_ACTIVE_TURNS, activeTurns.activeTurnCount)
                .putExtra(EXTRA_WAITING_SESSIONS, activeTurns.waitingSessionCount)
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
    private var persistent = false
    private var activeTurns = 0
    private var waitingSessions = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        runningInstance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH) {
            persistent = intent.getBooleanExtra(EXTRA_PERSISTENT, false)
            activeTurns = intent.getIntExtra(EXTRA_ACTIVE_TURNS, 0).coerceAtLeast(0)
            waitingSessions = intent.getIntExtra(EXTRA_WAITING_SESSIONS, 0)
                .coerceIn(0, activeTurns)
        }
        startForegroundNotification()
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "ACTION_STOP → user disabled continuous background connection")
            scope.launch { runCatching { applicationContext.setGatewayKeepAlive(false) } }
            persistent = false
            if (activeTurns == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                startForegroundNotification()
            }
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // The socket lives in the ViewModel, which dies when the task is
        // removed — keeping the notification would be a lie. Stop cleanly.
        Log.i(TAG, "onTaskRemoved → app swiped away; stopping keep-alive")
        ActiveTurnKeepAliveRegistry.releaseAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (runningInstance === this) runningInstance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Per-app locale changes recreate MainActivity but intentionally keep
        // this foreground service (and its Gateway socket) alive. Re-post the
        // existing notification so its localized title/body follow the new
        // application resources without restarting either owner.
        startForegroundNotification()
    }

    private fun applyState(
        persistent: Boolean,
        turns: ActiveTurnKeepAliveRegistry.Snapshot,
    ) {
        this.persistent = persistent
        activeTurns = turns.activeTurnCount
        waitingSessions = turns.waitingSessionCount.coerceIn(0, activeTurns)
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

        val (title, body) = when {
            waitingSessions > 0 -> {
                val title = if (waitingSessions == 1) {
                    "Hermes is waiting for input"
                } else {
                    "$waitingSessions Hermes sessions need input"
                }
                title to if (activeTurns > waitingSessions) {
                    "$waitingSessions waiting · ${activeTurns - waitingSessions} still working"
                } else {
                    "Open the requested session to review and continue."
                }
            }
            activeTurns > 0 -> {
                val title = if (activeTurns == 1) {
                    "Hermes is finishing a turn"
                } else {
                    "Hermes is finishing $activeTurns turns"
                }
                title to "The connection stays active until this work completes."
            }
            else -> getString(R.string.gateway_keepalive_title) to
                getString(R.string.gateway_keepalive_body)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tapPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (persistent) builder.addAction(0, "Turn off always-on", stopPending)
        return builder.build()
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
