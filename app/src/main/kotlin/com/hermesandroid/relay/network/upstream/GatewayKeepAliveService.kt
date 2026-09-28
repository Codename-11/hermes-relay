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
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.edit
import com.hermesandroid.relay.MainActivity
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.KEY_GATEWAY_KEEP_ALIVE
import com.hermesandroid.relay.data.relayDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

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
 * idle-close timer while retention is required. Task removal releases local
 * foreground protection; it does not terminate server-owned work or assume
 * that removing a task kills the application process.
 *
 * # Foreground-start obligation (Android 8+)
 *
 * An accepted startForegroundService must promote promptly, even if demand
 * disappears before delivery. Never stopService a pending start: Android 12
 * also treats teardown before promotion as a foreground-start failure.
 * Main-thread demand is coalesced until onStartCommand acknowledges the start.
 */
class GatewayKeepAliveService : Service() {
    companion object {
        private const val TAG = "GatewayKeepAliveSvc"
        const val CHANNEL_ID = "gateway_keepalive"
        private const val CHANNEL_NAME = "Persistent connection"
        const val NOTIFICATION_ID = 4713
        const val ACTION_STOP = "com.hermesandroid.relay.gateway.KEEPALIVE_STOP"
        private const val ACTION_REFRESH = "com.hermesandroid.relay.gateway.KEEPALIVE_REFRESH"
        private const val EXTRA_START_TOKEN = "start_token"
        private var runningInstance: GatewayKeepAliveService? = null
        private var pendingStart: String? = null
        private var desiredPersistent = false
        private var desiredTurns = ActiveTurnKeepAliveRegistry.Snapshot()
        private var wasForeground = false
        private var taskRemoved = false
        @Volatile private var persistentToken: String? = null
        // Preference writes must survive service teardown, but never process death.
        private val preferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        @MainThread
        fun update(
            context: Context,
            persistent: Boolean,
            activeTurns: ActiveTurnKeepAliveRegistry.Snapshot,
            appForeground: Boolean = true,
        ) {
            checkMainThread()
            if (appForeground && !wasForeground) taskRemoved = false
            wasForeground = appForeground
            if (persistent != desiredPersistent) {
                persistentToken = if (persistent) UUID.randomUUID().toString() else null
            }
            desiredPersistent = persistent
            desiredTurns = activeTurns
            // Keep the accepted start alive until Android delivers its command.
            if (pendingStart != null) return
            runningInstance?.let {
                it.reconcile()
                return
            }
            if (taskRemoved || !appForeground || (!persistent && !activeTurns.required)) return
            val token = UUID.randomUUID().toString()
            pendingStart = token
            val intent = Intent(context.applicationContext, GatewayKeepAliveService::class.java)
                .setAction(ACTION_REFRESH)
                .putExtra(EXTRA_START_TOKEN, token)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.applicationContext.startForegroundService(intent)
                } else {
                    context.applicationContext.startService(intent)
                }
            } catch (e: Exception) {
                pendingStart = null
                Log.w(TAG, "Foreground service launch rejected; retaining server-owned work", e)
            }
        }

        @MainThread
        fun stop(context: Context) {
            update(context, false, ActiveTurnKeepAliveRegistry.Snapshot(), wasForeground)
        }

        private fun checkMainThread() {
            check(Looper.myLooper() == Looper.getMainLooper())
        }

        internal fun resetForTest() {
            runningInstance = null
            pendingStart = null
            desiredPersistent = false
            desiredTurns = ActiveTurnKeepAliveRegistry.Snapshot()
            persistentToken = null
            wasForeground = false
            taskRemoved = false
        }
    }

    private var persistent = false
    private var activeTurns = 0
    private var waitingSessions = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // No datastore, socket, coroutine or other owner work ahead of promotion.
        // A cold stale notification action has no accepted foreground start.
        if (pendingStart != null) {
            applyState(desiredPersistent, desiredTurns)
            startForegroundNotification()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = intent?.getStringExtra(EXTRA_START_TOKEN)
        if (intent?.action == ACTION_REFRESH) {
            applyState(desiredPersistent, desiredTurns)
            // Also promote reused service instances before acknowledging the start.
            // A delivered start still owes promotion if process-local demand
            // was lost or its token is stale. Never replay its old demand.
            if (startForegroundNotification()) {
                if (token == pendingStart) pendingStart = null
                if (pendingStart == null) {
                    runningInstance = this
                    reconcile()
                }
            }
        } else if (intent?.action == ACTION_STOP &&
            intent.data?.lastPathSegment == persistentToken && persistentToken != null
        ) {
            val actionToken = persistentToken
            val context = applicationContext
            preferenceScope.launch {
                try {
                    context.relayDataStore.edit { preferences ->
                        // Recheck inside the serialized edit; an old action must
                        // not undo a subsequent disable/re-enable cycle.
                        if (persistentToken == actionToken) preferences[KEY_GATEWAY_KEEP_ALIVE] = false
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not disable persistent connection", e)
                }
            }
            // The preference collector reconciles current active-turn demand
            // after persistence. Never stop from the notification's old snapshot.
        }
        if (runningInstance !== this && pendingStart == null) stopSelfResult(startId)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "onTaskRemoved → app swiped away; stopping keep-alive")
        taskRemoved = true
        // Leases belong to chat owners. Keep them intact so a surviving
        // process can protect unfinished turns again when the user returns.
        // A queued new start still owes Android promotion before retirement.
        if (pendingStart == null) retire()
    }

    override fun onDestroy() {
        if (runningInstance === this) runningInstance = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Per-app locale changes recreate MainActivity but intentionally keep
        // this foreground service (and its Gateway socket) alive. Re-post the
        // existing notification so its localized title/body follow the new
        // application resources without restarting either owner.
        if (runningInstance === this) reconcile()
    }

    private fun reconcile() {
        if (taskRemoved || (!desiredPersistent && !desiredTurns.required)) {
            retire()
        } else {
            applyState(desiredPersistent, desiredTurns)
            startForegroundNotification()
        }
    }

    private fun retire() {
        if (runningInstance === this) runningInstance = null
        // Let Android remove the foreground notification with service teardown.
        // Do not demote an instance while another start may be queued for it.
        stopSelf()
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
    private fun startForegroundNotification(): Boolean {
        try {
            ensureChannel()
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Foreground notification failed; retaining server-owned work", e)
            pendingStart = null
            retire()
            return false
        }
    }

    private fun buildNotification(): android.app.Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val tapPending = PendingIntent.getActivity(this, 0, tapIntent, pendingFlags)

        val stopIntent = Intent(this, GatewayKeepAliveService::class.java).setAction(ACTION_STOP)
            .setData(Uri.parse("hermes-relay://keep-alive/$persistentToken"))
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
