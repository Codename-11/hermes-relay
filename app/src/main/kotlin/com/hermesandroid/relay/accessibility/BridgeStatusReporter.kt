package com.hermesandroid.relay.accessibility

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import com.hermesandroid.relay.network.ChannelMultiplexer
import com.hermesandroid.relay.network.models.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Phase 3 — γ `accessibility-runtime`
 *
 * Coroutine-driven status reporter. Emits a `bridge.status` envelope every
 * [TICK_MS] (default 30 seconds) describing the phone's current state:
 *
 * ```json
 * {
 *   "channel": "bridge",
 *   "type": "bridge.status",
 *   "payload": {
 *     "screen_on": true,
 *     "battery": 78,
 *     "current_app": "com.android.chrome",
 *     "accessibility_enabled": true
 *   }
 * }
 * ```
 *
 * Values are probed fresh on every tick — we don't cache battery across
 * ticks because the user-visible number on the agent side is only ever
 * meaningful as a "recently observed" value.
 *
 * The reporter is a no-op until [start] is called, and [stop] is idempotent.
 * [ConnectionViewModel] owns it and ties the lifecycle to the WSS
 * connection — reporting while disconnected is a waste of battery and the
 * multiplexer would drop the envelopes silently anyway.
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

        val accessibilityEnabled = HermesAccessibilityService.instance != null

        val envelope = Envelope(
            channel = "bridge",
            type = "bridge.status",
            payload = buildJsonObject {
                put("screen_on", screenOn)
                put("battery", batteryFinal)
                put("current_app", currentApp ?: "unknown")
                put("accessibility_enabled", accessibilityEnabled)
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
