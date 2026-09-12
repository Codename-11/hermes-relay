package com.hermesandroid.relay.voice

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hermesandroid.relay.R

/** Settings and Voice Focus share the explanation; only Voice Focus supplies a start action. */
@Composable
fun VoiceOverlaySetupDialog(
    onDismiss: () -> Unit,
    onStart: (() -> Unit)? = null,
    onBeforePermission: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var access by remember { mutableStateOf(VoiceOverlayAccess.read(context)) }
    var requestedMicrophone by remember { mutableStateOf(false) }
    var requestedNotifications by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        access = VoiceOverlayAccess.read(context)
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) access = VoiceOverlayAccess.read(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun open(intent: Intent) {
        onBeforePermission()
        failed = runCatching { context.startActivity(intent) }.isFailure
    }
    VoiceOverlaySetupContent(
        access = access,
        failed = failed,
        onDismiss = onDismiss,
        onStart = onStart?.let { start -> {
            access = VoiceOverlayAccess.read(context)
            if (access.ready && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
        } },
        onMicrophone = {
            if (access.microphone || requestedMicrophone) {
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            } else {
                onBeforePermission()
                requestedMicrophone = true
                permission.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        onNotifications = {
            if (Build.VERSION.SDK_INT >= 33 && !requestedNotifications &&
                androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                onBeforePermission()
                requestedNotifications = true
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        },
        onOverlay = {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
        },
    )
}

@Composable
internal fun VoiceOverlaySetupContent(
    access: VoiceOverlayAccess,
    failed: Boolean,
    onDismiss: () -> Unit,
    onStart: (() -> Unit)?,
    onMicrophone: () -> Unit,
    onNotifications: () -> Unit,
    onOverlay: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_overlay_setup_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.voice_overlay_setup_body))
                AccessButton(R.string.perms_microphone, access.microphone, onMicrophone)
                AccessButton(R.string.perms_app_notifications, access.notifications, onNotifications)
                AccessButton(R.string.perms_display_over_apps, access.overlay, onOverlay)
                if (onStart == null) Text(stringResource(R.string.voice_overlay_settings_hint))
                if (failed) Text(stringResource(R.string.chat_overlay_start_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            if (onStart != null) TextButton(onClick = onStart, enabled = access.ready) {
                Text(stringResource(R.string.voice_overlay_setup_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cw_cancel)) }
        },
    )
}

@Composable
private fun AccessButton(@androidx.annotation.StringRes label: Int, granted: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(label) + " · " + stringResource(
            if (granted) R.string.ncs_access_granted else R.string.chat_open_settings,
        ))
    }
}

@Composable
fun VoiceOverlaySettingsCard() {
    var show by remember { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.voice_overlay_setup_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.voice_overlay_settings_hint))
            TextButton(onClick = { show = true }) { Text(stringResource(R.string.onboarding_review_optional_permissions)) }
        }
    }
    if (show) VoiceOverlaySetupDialog(onDismiss = { show = false })
}
