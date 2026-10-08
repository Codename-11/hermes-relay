package com.hermesandroid.relay.network.upstream

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hermesandroid.relay.MainActivity
import com.hermesandroid.relay.R
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A user-started login binding owns this non-sticky service; no saved auth material. */
class NativeDashboardAuthService : Service() {
    internal inner class AuthBinder : Binder() {
        fun promote() {
            // The visible sign-in screen is still foreground. Use the supported
            // started-and-bound contract; the last unbind stops the service.
            startService(Intent(this@NativeDashboardAuthService, NativeDashboardAuthService::class.java))
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.dashboard_sign_in), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) },
            )
            val notification = NotificationCompat.Builder(this@NativeDashboardAuthService, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.dashboard_signing_in))
                .setContentText(getString(R.string.dashboard_native_signin_opening))
                .setContentIntent(PendingIntent.getActivity(
                    this@NativeDashboardAuthService, NOTIFICATION_ID,
                    Intent(this@NativeDashboardAuthService, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = AuthBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onUnbind(intent: Intent?): Boolean {
        stopSelf()
        return false
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    internal companion object {
        const val CHANNEL_ID = "dashboard_sign_in"
        const val NOTIFICATION_ID = 4716
    }
}

/**
 * Promote before opening the browser: cached/background UIDs can lose even loopback
 * networking. A binding owns the entire lifetime, including cancellation before
 * connection delivery, without a pending startForegroundService obligation.
 */
internal suspend fun <T> withNativeDashboardAuthForeground(
    context: Context,
    block: suspend () -> T,
): T = withContext(Dispatchers.Main.immediate) {
    coroutineScope {
        val owner = this
        val app = context.applicationContext
        val ready = CompletableDeferred<Unit>()
        var bound = false
        var bindingRequested = false
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (!bound || !ready.isActive) return
                try {
                    (binder as NativeDashboardAuthService.AuthBinder).promote()
                    ready.complete(Unit)
                } catch (_: Exception) {
                    ready.completeExceptionally(IOException("Sign-in foreground protection unavailable"))
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                owner.cancel("Sign-in foreground service disconnected")
            }
            override fun onBindingDied(name: ComponentName) {
                owner.cancel("Sign-in foreground binding ended")
            }
            override fun onNullBinding(name: ComponentName) {
                ready.completeExceptionally(IOException("Sign-in foreground binding unavailable"))
            }
        }
        try {
            bindingRequested = true
            bound = app.bindService(
                Intent(app, NativeDashboardAuthService::class.java), connection, Context.BIND_AUTO_CREATE,
            )
            if (!bound) throw IOException("Sign-in foreground binding rejected")
            withTimeoutOrNull(5_000) { ready.await(); true }
                ?: throw IOException("Sign-in foreground binding timed out")
            block()
        } finally {
            ready.cancel()
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (bindingRequested) {
                    bound = false
                    try {
                        app.unbindService(connection)
                    } catch (_: IllegalArgumentException) {
                        // A rejected bind may never have registered a connection.
                    }
                }
            }
        }
    }
}
