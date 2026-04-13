package com.hermesandroid.relay.accessibility

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.hermesandroid.relay.bridge.BridgeSafetyManager
import com.hermesandroid.relay.network.ChannelMultiplexer
import com.hermesandroid.relay.network.models.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Phase 3 — accessibility `accessibility-runtime`
 *
 * Coroutine-driven status reporter. Emits a `bridge.status` envelope every
 * [TICK_MS] (default 30 seconds) describing the phone's current state.
 *
 * ## Wire format (Phase 3 `phase3-status` expansion)
 *
 * The envelope payload is the full structured status contract that the
 * relay-side [plugin.relay.channels.bridge.BridgeHandler] caches and
 * serves via `GET /bridge/status`. It MUST match the JSON contract used
 * by `android_phone_status()`:
 *
 * ```json
 * {
 *   "device": {
 *     "name": "SM-S921U",
 *     "battery_percent": 78,
 *     "screen_on": true,
 *     "current_app": "com.android.chrome"
 *   },
 *   "bridge": {
 *     "master_enabled": true,
 *     "accessibility_granted": true,
 *     "screen_capture_granted": true,
 *     "overlay_granted": true,
 *     "notification_listener_granted": true
 *   },
 *   "safety": {
 *     "blocklist_count": 30,
 *     "destructive_verbs_count": 12,
 *     "auto_disable_minutes": 30,
 *     "auto_disable_at_ms": null
 *   }
 * }
 * ```
 *
 * The legacy top-level keys (`screen_on`, `battery`, `current_app`,
 * `accessibility_enabled`, `ts`) are ALSO emitted for backwards
 * compatibility with any consumer that hasn't been updated to read the
 * nested `device` / `bridge` / `safety` groups yet. The fields are
 * cheap and additive, and the relay caches the whole payload verbatim.
 *
 * ## Lifecycle
 *
 * The reporter is a no-op until [start] is called, and [stop] is
 * idempotent. [com.hermesandroid.relay.viewmodel.ConnectionViewModel]
 * owns it and ties the lifecycle to the WSS connection — reporting
 * while disconnected is a waste of battery and the multiplexer would
 * drop the envelopes silently anyway.
 *
 * ## Out-of-band pushes
 *
 * Callers may invoke [pushNow] to force an immediate emission outside
 * the 30 s tick — used by `ConnectionViewModel` when the master toggle
 * flips, so the relay-side cache updates immediately instead of waiting
 * up to 30 s for the next periodic tick.
 */
class BridgeStatusReporter(
    private val context: Context,
    private val multiplexer: ChannelMultiplexer,
    private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "BridgeStatusReporter"
        private const val TICK_MS = 30_000L
    }

    private var job: Job? = null

    /**
     * Start the reporter. Safe to call multiple times — if a job is
     * already running we log and return.
     */
    fun start() {
        if (job?.isActive == true) {
            Log.v(TAG, "already running")
            return
        }
        job = scope.launch {
            // Send an immediate first tick so the agent sees fresh status
            // as soon as the WSS connection comes up, rather than waiting
            // up to 30s for the first periodic tick.
            while (isActive) {
                try {
                    emitTick()
                } catch (t: Throwable) {
                    Log.w(TAG, "status emit failed: ${t.message}")
                }
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Force an immediate status emission outside the [TICK_MS] cadence.
     * Useful when state changes that matter to the agent (master toggle
     * flip, accessibility service connected, etc.) — rather than waiting
     * up to 30 s for the relay cache to refresh.
     *
     * No-op if [start] hasn't been called yet — in that case the next
     * start() will fire a tick anyway.
     */
    fun pushNow() {
        if (job?.isActive != true) {
            Log.v(TAG, "pushNow() called while stopped — no-op")
            return
        }
        scope.launch {
            try {
                emitTick()
            } catch (t: Throwable) {
                Log.w(TAG, "pushNow emit failed: ${t.message}")
            }
        }
    }

    /**
     * Build one status envelope from live phone state and push it through
     * the multiplexer. Exposed as internal-ish so unit tests can drive
     * a single tick without spinning the coroutine.
     */
    internal fun emitTick() {
        val screenOn = try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isInteractive
        } catch (t: Throwable) {
            Log.v(TAG, "screenOn probe failed: ${t.message}")
            false
        }

        val battery = try {
            // Prefer the BatteryManager property API — it's the only
            // always-accurate source on modern Android. The legacy sticky
            // intent path is fine too but we'd have to parse two fields.
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (t: Throwable) {
            Log.v(TAG, "battery probe failed: ${t.message}")
            -1
        }

        // If the property API returns 0 (some OEM firmwares do) fall back
        // to the sticky intent read.
        val batteryFinal = if (battery <= 0) readBatteryViaIntent() else battery

        val currentApp = HermesAccessibilityService.instance?.currentApp
        val accessibilityGranted = HermesAccessibilityService.instance != null
        val masterEnabled = HermesAccessibilityService.instance?.isMasterEnabled() ?: false

        // Screen-capture grant — the process-singleton holder is non-null
        // iff the user granted MediaProjection consent this session.
        val screenCaptureGranted = try {
            MediaProjectionHolder.projection != null
        } catch (t: Throwable) {
            Log.v(TAG, "screen_capture probe failed: ${t.message}")
            false
        }

        val overlayGranted = try {
            Settings.canDrawOverlays(context)
        } catch (t: Throwable) {
            Log.v(TAG, "overlay probe failed: ${t.message}")
            false
        }

        val notificationListenerGranted = try {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )
            enabled?.contains(context.packageName) ?: false
        } catch (t: Throwable) {
            Log.v(TAG, "notification_listener probe failed: ${t.message}")
            false
        }

        // Safety snapshot — read lazily through the process-singleton
        // so we don't take a DI dep here. Falls back to zeros if the
        // safety manager hasn't been built yet (cold start).
        val safetyManager = BridgeSafetyManager.peek()
        val safetySnapshot = safetyManager?.settings?.value
        val blocklistCount = safetySnapshot?.blocklist?.size ?: 0
        val destructiveVerbsCount = safetySnapshot?.destructiveVerbs?.size ?: 0
        val autoDisableMinutes = safetySnapshot?.autoDisableMinutes ?: 0
        val autoDisableAtMs = safetyManager?.autoDisableAtMs?.value

        val deviceName = Build.MODEL ?: "unknown"

        val envelope = Envelope(
            channel = "bridge",
            type = "bridge.status",
            payload = buildJsonObject {
                // Nested structured groups matching the relay HTTP contract.
                put("device", buildJsonObject {
                    put("name", deviceName)
                    put("battery_percent", batteryFinal)
                    put("screen_on", screenOn)
                    put("current_app", currentApp ?: "unknown")
                })
                put("bridge", buildJsonObject {
                    put("master_enabled", masterEnabled)
                    put("accessibility_granted", accessibilityGranted)
                    put("screen_capture_granted", screenCaptureGranted)
                    put("overlay_granted", overlayGranted)
                    put("notification_listener_granted", notificationListenerGranted)
                })
                put("safety", buildJsonObject {
                    put("blocklist_count", blocklistCount)
                    put("destructive_verbs_count", destructiveVerbsCount)
                    put("auto_disable_minutes", autoDisableMinutes)
                    if (autoDisableAtMs == null) {
                        put("auto_disable_at_ms", JsonNull)
                    } else {
                        put("auto_disable_at_ms", autoDisableAtMs)
                    }
                })

                // ── Legacy top-level fields (backwards compat) ────────
                // Kept so pre-phase3-status consumers still see the
                // same fields they're already parsing. New consumers
                // should read from the nested `device` / `bridge`
                // groups above.
                put("screen_on", screenOn)
                put("battery", batteryFinal)
                put("current_app", currentApp ?: "unknown")
                put("accessibility_enabled", accessibilityGranted)
                put("ts", System.currentTimeMillis())
            }
        )
        multiplexer.send(envelope)
    }

    /**
     * Legacy fallback for BatteryManager.getIntProperty returning 0 —
     * reads the sticky `ACTION_BATTERY_CHANGED` intent and computes
     * percentage manually.
     */
    private fun readBatteryViaIntent(): Int = try {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        @Suppress("UNUSED_VARIABLE")
        val placeholder: BroadcastReceiver? = null
        val battery = context.registerReceiver(null, filter)
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) (level * 100 / scale) else -1
    } catch (t: Throwable) {
        Log.v(TAG, "battery intent fallback failed: ${t.message}")
        -1
    }
}
