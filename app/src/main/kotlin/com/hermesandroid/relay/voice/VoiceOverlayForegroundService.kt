package com.hermesandroid.relay.voice

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
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
    private var activeSessionId: Long? = null
    private val handler = Handler(Looper.getMainLooper())
    private val accessMonitor = object : Runnable {
        override fun run() {
            val id = activeSessionId ?: return
            if (VoiceOverlayHost.peek()?.canContinue(id) != true) {
                endSession()
            } else {
                // Notification channels can be disabled without a runtime-permission event.
                handler.postDelayed(this, 1_000)
            }
        }
    }
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) endSession()
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_SESSION_ID, -1L) ?: -1L
        val host = VoiceOverlayHost.peek()
        if (intent?.action == ACTION_STOP) {
            if (id == activeSessionId) endSession()
            else if (activeSessionId == null && host?.sessionId == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        // No sticky restart, unowned Intent, or stale callback can start a microphone session.
        if (intent?.action != ACTION_START || host?.canStart(id) != true) {
            if (host?.sessionId == id) host.exitVoiceSession(id)
            if (activeSessionId == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        activeSessionId = id
        if (!startForegroundNotification() || !host.onServiceReady(id)) {
            endSession()
        } else {
            handler.removeCallbacks(accessMonitor)
            handler.post(accessMonitor)
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        endSession()
    }

    private fun endSession() {
        val id = activeSessionId
        activeSessionId = null
        handler.removeCallbacks(accessMonitor)
        if (id != null) VoiceOverlayHost.peek()?.exitVoiceSession(id)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        val id = activeSessionId
        activeSessionId = null
        handler.removeCallbacks(accessMonitor)
        unregisterReceiver(screenOffReceiver)
        if (id != null) VoiceOverlayHost.peek()?.exitVoiceSession(id)
        super.onDestroy()
    }

    @SuppressLint("ForegroundServiceType")
    private fun startForegroundNotification(): Boolean {
        try {
            ensureChannel()
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
            return false
        }
        return true
    }

    private fun buildNotification(): Notification {
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(this, 0, openIntent, pendingFlags)
        val stopPending = PendingIntent.getService(
            this,
            activeSessionId?.toInt() ?: 0,
            Intent(this, VoiceOverlayForegroundService::class.java).setAction(ACTION_STOP)
                .setData(android.net.Uri.parse("hermes-voice-overlay:stop/$activeSessionId"))
                .putExtra(EXTRA_SESSION_ID, activeSessionId ?: -1L),
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
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
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
        const val EXTRA_SESSION_ID = "voice_overlay_session_id"

        fun start(context: Context, sessionId: Long): Boolean {
            val appContext = context.applicationContext
            return runCatching {
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, VoiceOverlayForegroundService::class.java)
                        .setAction(ACTION_START).putExtra(EXTRA_SESSION_ID, sessionId),
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
