package com.hermesandroid.relay.bridge

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
import com.hermesandroid.relay.accessibility.HermesAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Phase 3 — safety-rails `bridge-safety-rails`
 *
 * Persistent foreground service that signals "Hermes agent has device
 * control" to the user whenever the bridge master toggle is on. The
 * notification is:
 *
 *  - Ongoing (can't be swiped away)
 *  - Two actions: "Disable" (broadcast into the master toggle) and
 *    "Settings" (deep-link into BridgeSafetySettingsScreen)
 *  - Channel `bridge_foreground` at DEFAULT importance (we do NOT want
 *    heads-up because that would pop over every screen the agent taps)
 *
 * # Foreground service type
 *
 * On Android 10+ foreground services must declare a `foregroundServiceType`.
 * For Tier 5 we use `specialUse` with the justification string
 * "Persistent indicator that the Hermes agent has device control, per
 * Tier 5 safety rails." Play Store review allowance is declared in the
 * manifest `<property name="...SPECIAL_USE"/>`.
 *
 * We deliberately do NOT use `FOREGROUND_SERVICE_MEDIA_PROJECTION` here
 * even though accessibility's `ScreenCapture.kt` uses MediaProjection — that
 * permission is already declared, and binding the foreground service to
 * mediaProjection would couple its lifecycle to the current screen-grant,
 * which doesn't match our "always on while bridge is active" semantics.
 *
 * # Lifecycle wiring
 *
 * [BridgeViewModel] observes the master-toggle StateFlow and calls
 * [start] / [stop] on transitions. The service itself does not observe
 * the toggle — it's a passive "I'm on" indicator, and bundling the flow
 * subscription into the service would mean the service owns its own
 * coroutine scope + we'd need to worry about bind/unbind timing. Simpler
 * to have the ViewModel drive it.
 */
class BridgeForegroundService : Service() {

    companion object {
        private const val TAG = "BridgeForegroundSvc"

        const val CHANNEL_ID = "bridge_foreground"
        private const val CHANNEL_NAME = "Bridge active"
        const val NOTIFICATION_ID = 4712

        const val ACTION_START = "com.hermesandroid.relay.bridge.START"
        const val ACTION_STOP = "com.hermesandroid.relay.bridge.STOP"
        const val ACTION_DISABLE = "com.hermesandroid.relay.bridge.DISABLE"
        const val ACTION_OPEN_SETTINGS = "com.hermesandroid.relay.bridge.OPEN_SETTINGS"

        fun start(context: Context) {
            val intent = Intent(context.applicationContext, BridgeForegroundService::class.java)
                .setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.applicationContext.startForegroundService(intent)
            } else {
                context.applicationContext.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context.applicationContext, BridgeForegroundService::class.java)
                .setAction(ACTION_STOP)
            context.applicationContext.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "ACTION_STOP → stopping foreground service")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DISABLE -> {
                Log.i(TAG, "ACTION_DISABLE → flipping master toggle off")
                scope.launch {
                    runCatching {
                        HermesAccessibilityService.setMasterEnabled(applicationContext, false)
                    }.onFailure { Log.w(TAG, "master toggle write failed", it) }
                }
                // Keep the service alive until BridgeViewModel sees the
                // toggle flip and calls stop(); that's the canonical path.
                return START_STICKY
            }
            ACTION_OPEN_SETTINGS -> {
                Log.i(TAG, "ACTION_OPEN_SETTINGS → launching MainActivity with deep-link to bridge safety")
                // PHASE3-safety-rails-followup: deep-link to BridgeSafetySettingsScreen.
                // MainActivity reads EXTRA_NAV_ROUTE in onCreate / onNewIntent
                // and emits it on the NavRouteRequest SharedFlow, which RelayApp
                // collects and forwards to the NavController. The route string
                // is hardcoded here on purpose to avoid pulling the entire
                // ui.RelayApp graph into the bridge service classpath — if you
                // change Screen.BridgeSafetySettings.route in RelayApp.kt,
                // change it here too.
                val launch = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(MainActivity.EXTRA_NAV_ROUTE, "settings/bridge_safety")
                }
                runCatching { startActivity(launch) }
                return START_STICKY
            }
        }

        startForegroundNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

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
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Q..U: the type arg is required on Q+ too, but
                // FOREGROUND_SERVICE_TYPE_SPECIAL_USE is Android 14+.
                // Fall through to the plain startForeground on Q..T —
                // AGP will attach the manifest-declared type automatically.
                startForeground(NOTIFICATION_ID, notification)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground failed — bridge indicator will not be visible", t)
            stopSelf()
        }
    }

    private fun buildNotification(): android.app.Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val tapPending = PendingIntent.getActivity(this, 0, tapIntent, pendingFlags)

        val disableIntent = Intent(this, BridgeForegroundService::class.java)
            .setAction(ACTION_DISABLE)
        val disablePending = PendingIntent.getService(this, 1, disableIntent, pendingFlags)

        val settingsIntent = Intent(this, BridgeForegroundService::class.java)
            .setAction(ACTION_OPEN_SETTINGS)
        val settingsPending = PendingIntent.getService(this, 2, settingsIntent, pendingFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Hermes agent has device control")
            .setContentText("Bridge is active — tap Disable to stop at any time.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "The Hermes agent can currently read the screen and perform " +
                    "actions on your behalf through the accessibility service. " +
                    "Tap Disable to turn this off immediately."
            ))
            .setContentIntent(tapPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Disable", disablePending)
            .addAction(0, "Settings", settingsPending)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Persistent indicator while the Hermes agent has device control."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }
}
