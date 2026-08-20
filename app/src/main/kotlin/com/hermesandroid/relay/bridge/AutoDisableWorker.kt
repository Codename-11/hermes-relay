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

/**
 * Phase 3 — safety-rails `bridge-safety-rails`
 *
 * Canonical timed-screen-expiry notification unit. Not a real
 * `androidx.work.CoroutineWorker` — the project intentionally does not
 * depend on androidx.work — but its shape mirrors one exactly: a single
 * suspend [run] method that performs the work and returns.
 *
 * Why this pattern instead of dropping a WorkManager dep:
 *  - Capability expiry is persisted as absolute wall-clock timestamps;
 *    the in-process job exists only to prune promptly and notify.
 *  - Android's AlarmManager / WorkManager are needed when the work must
 *    survive process death. Authorization itself does survive because the
 *    command boundary compares persisted expiry with the current clock.
 *  - Only timed screen inspection/control commands reset the timer.
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
     * Post a one-shot notification after timed screen authority is revoked.
     * Idempotent — a repeated call replaces the existing notification.
     */
    suspend fun run() {
        postNotification()
    }

    // Lint can't trace through [hasPostNotificationsPermission] to see that
    // we early-return when the runtime grant isn't held, and the notify()
    // call is also wrapped in runCatching to swallow SecurityException as
    // a belt-and-braces. Suppress here rather than inlining the check —
    // the helper exists so the same gate can grow more conditions later
    // without each call site re-implementing it. Both IDs are needed:
    // `NotificationPermission` is the notify()-specific check (POST_NOTIFICATIONS
    // on API 33+); `MissingPermission` is the generic fallback.
    @SuppressLint("MissingPermission", "NotificationPermission")
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
            .setContentTitle(context.getString(R.string.bridge_notification_auto_disabled_title))
            .setContentText(context.getString(R.string.bridge_notification_auto_disabled_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                context.getString(R.string.bridge_notification_auto_disabled_body)
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
            description = "Fires once when timed Bridge screen access expires after idle."
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
