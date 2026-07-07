package com.hermesandroid.relay.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Doze / battery-optimization helpers for the opt-in "Persistent connection"
 * keep-alive.
 *
 * A `specialUse` foreground service holds the app **process** up, but on stock
 * Android it does NOT exempt the app from Doze's network deferral — in deep
 * Doze (screen off + stationary) the socket's pings can't fire and the
 * connection drops until a maintenance window. The one reliable way to keep it
 * open is the battery-optimization allow-list. These helpers read that state
 * and launch the system request.
 *
 * **Sideload only.** `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is declared only in
 * the sideload manifest — Google Play restricts it to a short list of app
 * categories — so callers gate the prompt on [BuildFlavor.isSideload]. On the
 * googlePlay flavor the request intent simply won't be granted the permission.
 */
object BatteryOptimizations {

    /** True if this app is on the OS battery-optimization allow-list. */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return runCatching {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)
    }

    /**
     * Pop the system "Allow <app> to ignore battery optimizations?" dialog.
     * Requires `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (sideload manifest). Falls
     * back to the battery-optimization settings list if the direct request
     * can't be launched (permission absent / OEM quirk). Launch from an
     * Activity context.
     */
    fun launchRequest(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        runCatching { context.startActivity(direct) }.onFailure {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }
}
