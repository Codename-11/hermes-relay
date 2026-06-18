package com.hermesandroid.relay.permissions

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.hermesandroid.relay.accessibility.HermesAccessibilityService
import com.hermesandroid.relay.accessibility.MediaProjectionHolder

/**
 * One snapshot of Android grants and special-access switches that Hermes-Relay
 * features can consume. Standard Chat and Manage do not need dangerous runtime
 * permissions, so they are intentionally not represented as a "required"
 * Android grant here.
 */
data class AppPermissionStatus(
    val notificationsPermitted: Boolean = false,
    val microphonePermitted: Boolean = false,
    val cameraPermitted: Boolean = false,
    val notificationListenerPermitted: Boolean = false,
    val accessibilityServiceEnabled: Boolean = false,
    val screenCapturePermitted: Boolean = false,
    val overlayPermitted: Boolean = false,
    val contactsPermitted: Boolean = false,
    val smsPermitted: Boolean = false,
    val phonePermitted: Boolean = false,
    val locationPermitted: Boolean = false,
)

object AppPermissionStatusProbe {
    fun snapshot(context: Context): AppPermissionStatus {
        val appContext = context.applicationContext
        return AppPermissionStatus(
            notificationsPermitted = hasPostNotifications(appContext),
            microphonePermitted = hasPermission(appContext, Manifest.permission.RECORD_AUDIO),
            cameraPermitted = hasPermission(appContext, Manifest.permission.CAMERA),
            notificationListenerPermitted = isNotificationListenerEnabled(appContext),
            accessibilityServiceEnabled = isAccessibilityServiceEnabled(appContext),
            screenCapturePermitted = MediaProjectionHolder.projection != null,
            overlayPermitted = Settings.canDrawOverlays(appContext),
            contactsPermitted = hasPermission(appContext, Manifest.permission.READ_CONTACTS),
            smsPermitted = hasPermission(appContext, Manifest.permission.SEND_SMS),
            phonePermitted = hasPermission(appContext, Manifest.permission.CALL_PHONE),
            locationPermitted = hasPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION),
        )
    }

    private fun hasPostNotifications(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
    }

    private fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val expected = ComponentName(
            context.packageName,
            HermesAccessibilityService::class.java.name,
        ).flattenToString()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) } ||
            enabled.contains(context.packageName, ignoreCase = true)
    }

    private fun isNotificationListenerEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        return enabled.contains(context.packageName, ignoreCase = true)
    }
}
