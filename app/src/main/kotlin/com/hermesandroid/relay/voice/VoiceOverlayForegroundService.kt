package com.hermesandroid.relay.voice

import android.annotation.SuppressLint
import android.app.Notification
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
import androidx.core.content.ContextCompat
import com.hermesandroid.relay.MainActivity
import com.hermesandroid.relay.R

/**
 * Keeps Android's foreground-only microphone app-op available while the user
 * is actively using the system voice overlay over another app.
 *
 * This service deliberately does not open an [android.media.AudioRecord]. The
 * existing voice runtime and process-wide microphone ownership coordinator
 * remain the sole capture owners; this service supplies only the foreground
 * execution state Android requires once [MainActivity] is backgrounded.
 */
class VoiceOverlayForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Notification stop requested")
            VoiceOverlayHost.peek()?.exitVoiceSession()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "App task removed; closing voice overlay")
        VoiceOverlayHost.peek()?.exitVoiceSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @SuppressLint("ForegroundServiceType")
    private fun startForegroundNotification() {
        ensureChannel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not foreground voice overlay microphone service", t)
            VoiceOverlayHost.peek()?.hide()
            stopSelf()
        }
    }

    private fun buildNotification(): Notification {
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(this, 0, openIntent, pendingFlags)
        val stopPending = PendingIntent.getService(
            this,
            1,
            Intent(this, VoiceOverlayForegroundService::class.java).setAction(ACTION_STOP),
            pendingFlags,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.voice_overlay_notification_title))
            .setContentText(getString(R.string.voice_overlay_notification_body))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(getString(R.string.voice_overlay_notification_body)),
            )
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                0,
                getString(R.string.voice_overlay_notification_stop),
                stopPending,
            )
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.voice_overlay_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.voice_overlay_notification_channel_desc)
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val TAG = "VoiceOverlayMicSvc"
        const val CHANNEL_ID = "voice_overlay_microphone"
        const val NOTIFICATION_ID = 4715
        const val ACTION_START = "com.hermesandroid.relay.voice.OVERLAY_MIC_START"
        const val ACTION_STOP = "com.hermesandroid.relay.voice.OVERLAY_MIC_STOP"

        fun start(context: Context): Boolean {
            val appContext = context.applicationContext
            return runCatching {
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, VoiceOverlayForegroundService::class.java)
                        .setAction(ACTION_START),
                )
                true
            }.getOrElse { error ->
                Log.w(TAG, "Could not start voice overlay microphone service", error)
                false
            }
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, VoiceOverlayForegroundService::class.java),
            )
        }
    }
}
