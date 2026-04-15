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
import com.hermesandroid.relay.accessibility.MediaProjectionHolder
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

        // === PHASE3-bridge-ui-followup: MediaProjection upgrade action ===
        // Fired by MainActivity.mediaProjectionLauncher after the user has
        // granted the system consent dialog. Carries the resultCode + data
        // Intent the launcher received. The service handles this by:
        //   1. Calling startForeground AGAIN with the dual SPECIAL_USE |
        //      MEDIA_PROJECTION type bitmask (legal NOW because consent
        //      has been granted).
        //   2. Calling MediaProjectionHolder.acceptGrantInsideForegroundService
        //      to construct and store the projection.
        // Splitting the FGS type slot out of the initial start-up is
        // critical: Android 14+ silently auto-revokes any projection created
        // by a service that called startForeground(type=mediaProjection)
        // BEFORE the consent dialog was granted. The previous code had
        // mediaProjection in the initial type bitmask the moment the master
        // toggle flipped on, which is exactly that violation. Symptom:
        // "I tap Allow but the row never turns green" — Bailey, 2026-04-13.
        const val ACTION_GRANT_PROJECTION = "com.hermesandroid.relay.bridge.GRANT_PROJECTION"
        const val EXTRA_RESULT_CODE = "com.hermesandroid.relay.bridge.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "com.hermesandroid.relay.bridge.RESULT_DATA"
        // === END PHASE3-bridge-ui-followup ===

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
            // Use stopService() rather than startService(ACTION_STOP) — on
            // Android 15+ (target SDK 35), the system routes ANY intent to
            // a service with foregroundServiceType through the foreground
            // watchdog and demands a startForeground call within 5s, even
            // if the intent is an internal "please shut down" message. By
            // going through stopService() we bypass onStartCommand entirely
            // and call onDestroy directly — clean shutdown, no watchdog.
            val intent = Intent(context.applicationContext, BridgeForegroundService::class.java)
            context.applicationContext.stopService(intent)
        }

        /**
         * Fire-and-forget: hand the consent result off to the foreground
         * service so it can upgrade its FGS type to include MEDIA_PROJECTION
         * and construct the projection. Called from
         * `MainActivity.mediaProjectionLauncher` immediately after the user
         * grants the system consent dialog.
         *
         * The service must already be running (master toggle on) — that is
         * guaranteed by `BridgeViewModel.requestScreenCapture()` which gates
         * the consent flow on the master toggle being on.
         */
        fun grantMediaProjection(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context.applicationContext, BridgeForegroundService::class.java)
                .setAction(ACTION_GRANT_PROJECTION)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.applicationContext.startForegroundService(intent)
            } else {
                context.applicationContext.startService(intent)
            }
        }
    }

    // True after we've successfully called startForeground with the
    // mediaProjection type slot (i.e. after consent + grant). Drives the
    // type bitmask passed to subsequent startForeground calls so we don't
    // accidentally drop the slot on a re-start.
    private var hasMediaProjectionType: Boolean = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action} flags=$flags startId=$startId")
        // === PHASE3-bridge-ui-followup: always startForeground first ===
        // CRITICAL: on Android 15+ (target SDK 35), ANY intent delivered to
        // a service that declares foregroundServiceType in the manifest —
        // including intents dispatched via Context.startService() — gets
        // tracked by the system's foreground-service watchdog. The service
        // has 5 seconds to call startForeground() or the system throws
        // ForegroundServiceDidNotStartInTimeException and crashes the app.
        //
        // Symptom we hit on 2026-04-13: opening the Bridge tab → BridgeViewModel
        // collector fires masterToggle (initial value false) → calls
        // BridgeForegroundService.stop(ctx) → startService(ACTION_STOP) →
        // service onStartCommand handles ACTION_STOP, calls stopForeground
        // and stopSelf without ever calling startForeground → 5s later the
        // system kills the process.
        //
        // The fix: ALWAYS call startForeground at the top of onStartCommand,
        // before any action branching. The brief notification flash for
        // stop-only paths is acceptable; the alternative (using a bound
        // service or broadcast receiver for control commands) is a much
        // bigger refactor for the same outcome.
        startForegroundNotification()
        // === END PHASE3-bridge-ui-followup ===

        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "ACTION_STOP → stopping foreground service")
                hasMediaProjectionType = false
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
            ACTION_GRANT_PROJECTION -> {
                // === PHASE3-bridge-ui-followup: post-consent FGS type upgrade ===
                // The launcher result has just landed in MainActivity. The
                // top-of-onStartCommand call already brought us into the
                // foreground (with SPECIAL_USE only). Now flip the type
                // flag and call startForeground AGAIN to upgrade to
                // SPECIAL_USE | MEDIA_PROJECTION — legal NOW because the
                // consent has been granted — then construct the projection
                // from inside the foreground state. Two startForeground
                // calls on the same service is well-supported; the second
                // just changes the type bitmask.
                Log.i(TAG, "ACTION_GRANT_PROJECTION → upgrading FGS type and accepting grant")
                hasMediaProjectionType = true
                startForegroundNotification()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val accepted = MediaProjectionHolder.acceptGrantInsideForegroundService(
                    this, resultCode, data
                )
                if (!accepted) {
                    // Rolling back the type slot keeps us honest: if the
                    // user actually denied (or the API failed), we shouldn't
                    // claim a grant we don't have. Re-run startForeground
                    // with SPECIAL_USE only so the FGS type matches reality.
                    Log.w(TAG, "grant not accepted — reverting FGS to SPECIAL_USE only")
                    hasMediaProjectionType = false
                    startForegroundNotification()
                }
                return START_STICKY
                // === END PHASE3-bridge-ui-followup ===
            }
        }

        // Fall-through for ACTION_START / null intent — startForegroundNotification
        // was already called at the top of this method.
        return START_STICKY
    }

    override fun onDestroy() {
        // Reset state so a fresh service instance starts in the
        // SPECIAL_USE-only configuration. Also drop any held MediaProjection
        // — a projection without an active bridge is meaningless and the
        // next bridge enable should always prompt for fresh consent.
        hasMediaProjectionType = false
        runCatching { MediaProjectionHolder.revoke() }
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        ensureChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // === PHASE3-bridge-ui-followup: gated MediaProjection type slot ===
                // CRITICAL: on Android 14+, startForeground(type=mediaProjection)
                // is only legal AFTER the user has granted the system consent
                // dialog. Calling it before — even if you intend to "wait
                // until consent arrives" — makes the eventual projection
                // get auto-revoked by the system within a frame, with no
                // app-visible error.
                //
                // So we start with SPECIAL_USE only when the bridge first
                // comes up (master toggle on, no projection yet), and the
                // ACTION_GRANT_PROJECTION handler upgrades us to
                // SPECIAL_USE | MEDIA_PROJECTION right after consent and
                // before getMediaProjection. That's why this method reads
                // [hasMediaProjectionType] instead of always OR-ing both.
                //
                // Both subtypes share this single notification + this single
                // service. Manifest lists both in `foregroundServiceType`.
                val typeMask = if (hasMediaProjectionType) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                startForeground(NOTIFICATION_ID, notification, typeMask)
                // === END PHASE3-bridge-ui-followup ===
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
