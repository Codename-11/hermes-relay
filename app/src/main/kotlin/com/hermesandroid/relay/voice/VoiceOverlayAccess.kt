package com.hermesandroid.relay.voice

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Permissions do not grant permission to start a session: a resumed UI action is also required. */
data class VoiceOverlayAccess(
    val microphone: Boolean,
    val notifications: Boolean,
    val overlay: Boolean,
    val unlocked: Boolean,
) {
    val ready: Boolean get() = microphone && notifications && overlay && unlocked

    companion object {
        fun read(context: Context): VoiceOverlayAccess {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channelEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                manager?.getNotificationChannel(VoiceOverlayForegroundService.CHANNEL_ID)?.importance !=
                NotificationManager.IMPORTANCE_NONE
            return VoiceOverlayAccess(
                microphone = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED,
                notifications = NotificationManagerCompat.from(context).areNotificationsEnabled() && channelEnabled,
                overlay = Settings.canDrawOverlays(context),
                unlocked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false &&
                    context.getSystemService(PowerManager::class.java)?.isInteractive == true,
            )
        }
    }
}
