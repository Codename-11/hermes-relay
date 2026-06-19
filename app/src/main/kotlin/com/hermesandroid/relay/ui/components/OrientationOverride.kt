package com.hermesandroid.relay.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Temporarily allow the device to rotate (portrait or landscape, following the
 * sensor) while this composable is in the composition, overriding the app-wide
 * portrait lock declared in the manifest (`MainActivity` screenOrientation).
 * Restores the previous orientation (portrait) when it leaves the composition.
 *
 * Used by the full-screen media viewers ([ChatImageViewer], [AttachmentViewer])
 * so wide images and video can be viewed in landscape while the rest of the app
 * stays portrait-locked. Uses `SENSOR` (portrait + both landscapes, no
 * upside-down) so the viewer rotates with the device regardless of the system
 * auto-rotate toggle; swap to `SCREEN_ORIENTATION_USER` to instead respect that
 * toggle.
 */
@Composable
fun AllowDeviceRotation() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        onDispose {
            activity?.requestedOrientation =
                previous ?: ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
