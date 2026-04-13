package com.hermesandroid.relay.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hermesandroid.relay.MainActivity
import com.hermesandroid.relay.R
import com.hermesandroid.relay.accessibility.HermesAccessibilityService

/**
 * Phase 3 — safety-rails `bridge-safety-rails`
 *
 * Canonical "turn the bridge off after idle" unit of work. Not a real
 * `androidx.work.CoroutineWorker` — the project intentionally does not
 * depend on androidx.work — but its shape mirrors one exactly: a single
 * suspend [run] method that performs the work and returns.
 *
 * Why this pattern instead of dropping a WorkManager dep:
 *  - Auto-disable is a pure in-memory decision: the toggle lives in our
 *    own DataStore, no inter-process scheduling is required.
 *  - Android's AlarmManager / WorkManager are needed when the work must
 *    survive process death. For bridge, process death already implies
 *    the service is disconnected and the master toggle re-evaluates
 *    fresh on the next launch. So a coroutine-owned `delay` does it.
 *  - Every command reschedules the timer, so the idle window is always
 *    reset against wall clock. No drift concerns.
 *
 * When WorkManager is added later (say, if notif-listener needs background-posted
 * notifications on a schedule), this file is a natural upgrade point:
 * change the class to `CoroutineWorker(appContext, params)` and have
 * [BridgeSafetyManager.rescheduleAutoDisable] enqueue a [OneTimeWorkRequest]
 * instead of launching a local coroutine.
 */
class AutoDisableWorker(private val context: Context) {

    companion object {
        private const val TAG = "AutoDisableWorker"
        private const val CHANNEL_ID = "bridge_auto_disable"
        private const val CHANNEL_NAME = "Bridge auto-disable"
        private const val NOTIFICATION_ID = 3821
    }

    /**
     * Execute the auto-disable: flip the master toggle off and post a
     * one-shot "bridge paused" notification. Idempotent — safe to call
     * twice (the second call just re-writes the same DataStore value
     * and overrides the existing notification).
     */
    suspend fun run() {
        try {
            HermesAccessibilityService.setMasterEnabled(context, false)
        } catch (t: Throwable) {
            Log.w(TAG, "run: failed to flip master toggle", t)
        }
        postNotification()
    }

    // Lint can't trace through [hasPostNotificationsPermission] to see that
    // we early-return when the runtime grant isn't held, and the notify()
    // call is also wrapped in runCatching to swallow SecurityException as
    // a belt-and-braces. Suppress here rather than inlining the check —
    // the helper exists so the same gate can grow more conditions later
    // without each call site re-implementing it.
    @SuppressLint("MissingPermission")
    private fun postNotification() {
        ensureChannel()
        if (!hasPostNotificationsPermission()) {
            Log.i(TAG, "POST_NOTIFICATIONS not granted — skipping auto-disable notification")
            return
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val tapPending = PendingIntent.getActivity(context, 0, tapIntent, pendingFlags)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Bridge auto-disabled")
            .setContentText("Paused after idle — tap to re-enable in the Bridge tab.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Hermes bridge was idle for too long, so device control has been turned off " +
                    "automatically. Open the Bridge tab to turn it back on if you still need it."
            ))
            .setContentIntent(tapPending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        }.onFailure { Log.w(TAG, "postNotification: notify failed", it) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Fires once when the bridge auto-disables after being idle."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun hasPostNotificationsPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}
