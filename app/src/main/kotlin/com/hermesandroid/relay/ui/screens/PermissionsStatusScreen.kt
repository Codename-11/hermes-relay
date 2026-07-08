package com.hermesandroid.relay.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.BuildFlavor
import com.hermesandroid.relay.permissions.AppPermissionStatus
import com.hermesandroid.relay.permissions.AppPermissionStatusProbe

/**
 * Central permission status surface for onboarding and Settings.
 *
 * This intentionally does not replace feature-local just-in-time prompts. It
 * gives users a readable map of what each permission unlocks, while standard
 * Chat and Manage remain usable with no dangerous Android grant.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsStatusScreen(
    onBack: () -> Unit,
    onOpenBridge: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var status by remember { mutableStateOf(AppPermissionStatusProbe.snapshot(context)) }

    fun refreshStatus() {
        status = AppPermissionStatusProbe.snapshot(context)
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.perms_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.perms_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { refreshStatus() }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.perms_refresh_status_cd),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PermissionsIntroCard()

            PermissionSection(
                title = stringResource(R.string.perms_section_hermes),
                subtitle = stringResource(R.string.perms_section_hermes_desc),
            ) {
                PermissionStatusRow(
                    icon = Icons.Filled.CheckCircle,
                    title = stringResource(R.string.perms_chat_and_manage),
                    subtitle = stringResource(R.string.perms_chat_and_manage_desc),
                    badge = stringResource(R.string.perms_badge_included),
                    statusLabel = stringResource(R.string.perms_status_ready),
                    granted = true,
                    onClick = null,
                )
            }

            PermissionSection(
                title = stringResource(R.string.perms_section_on_demand),
                subtitle = stringResource(R.string.perms_section_on_demand_desc),
            ) {
                PermissionStatusRow(
                    icon = Icons.Filled.CameraAlt,
                    title = stringResource(R.string.perms_camera),
                    subtitle = stringResource(R.string.perms_camera_desc),
                    badge = stringResource(R.string.perms_badge_optional),
                    statusLabel = optionalStatus(context, status.cameraPermitted),
                    granted = status.cameraPermitted,
                    onClick = { openAppDetailsSettings(context) },
                )
                PermissionStatusRow(
                    icon = Icons.Filled.Mic,
                    title = stringResource(R.string.perms_microphone),
                    subtitle = stringResource(R.string.perms_microphone_desc),
                    badge = stringResource(R.string.perms_badge_optional),
                    statusLabel = optionalStatus(context, status.microphonePermitted),
                    granted = status.microphonePermitted,
                    onClick = { openAppDetailsSettings(context) },
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PermissionStatusRow(
                        icon = Icons.Filled.Notifications,
                        title = stringResource(R.string.perms_app_notifications),
                        subtitle = stringResource(R.string.perms_app_notifications_desc),
                        badge = stringResource(R.string.perms_badge_optional),
                        statusLabel = optionalStatus(context, status.notificationsPermitted),
                        granted = status.notificationsPermitted,
                        onClick = { openAppDetailsSettings(context) },
                    )
                } else {
                    PermissionStatusRow(
                        icon = Icons.Filled.Notifications,
                        title = stringResource(R.string.perms_app_notifications),
                        subtitle = stringResource(R.string.perms_app_notifications_legacy_desc),
                        badge = stringResource(R.string.perms_badge_included),
                        statusLabel = stringResource(R.string.perms_status_ready),
                        granted = true,
                        onClick = null,
                    )
                }
                PermissionStatusRow(
                    icon = Icons.Filled.Notifications,
                    title = stringResource(R.string.perms_notification_access),
                    subtitle = stringResource(R.string.perms_notification_access_desc),
                    badge = stringResource(R.string.perms_badge_optional),
                    statusLabel = optionalStatus(context, status.notificationListenerPermitted),
                    granted = status.notificationListenerPermitted,
                    onClick = { openNotificationListenerSettings(context) },
                )
            }

            if (BuildFlavor.isSideload) {
                SideloadPermissionsSection(
                    status = status,
                    onOpenBridge = onOpenBridge,
                    onOpenAppDetails = { openAppDetailsSettings(context) },
                    onOpenAccessibility = { openAccessibilitySettings(context) },
                    onOpenOverlay = { openOverlaySettings(context) },
                )
            } else {
                GooglePlayBridgeCoreCard()
            }
        }
    }
}

@Composable
private fun PermissionsIntroCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.perms_intro_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.perms_intro_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SideloadPermissionsSection(
    status: AppPermissionStatus,
    onOpenBridge: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay: () -> Unit,
) {
    val context = LocalContext.current
    PermissionSection(
        title = stringResource(R.string.perms_section_sideload),
        subtitle = stringResource(R.string.perms_section_sideload_desc),
    ) {
        PermissionStatusRow(
            icon = Icons.Filled.Accessibility,
            title = stringResource(R.string.perms_accessibility_service),
            subtitle = stringResource(R.string.perms_accessibility_service_desc),
            badge = stringResource(R.string.perms_badge_required),
            statusLabel = requiredStatus(context, status.accessibilityServiceEnabled),
            granted = status.accessibilityServiceEnabled,
            onClick = onOpenAccessibility,
        )
        PermissionStatusRow(
            icon = Icons.Filled.PictureInPicture,
            title = stringResource(R.string.perms_display_over_apps),
            subtitle = stringResource(R.string.perms_display_over_apps_desc),
            badge = stringResource(R.string.perms_badge_required),
            statusLabel = requiredStatus(context, status.overlayPermitted),
            granted = status.overlayPermitted,
            onClick = onOpenOverlay,
        )
        PermissionStatusRow(
            icon = Icons.AutoMirrored.Filled.ScreenShare,
            title = stringResource(R.string.perms_screen_capture),
            subtitle = stringResource(R.string.perms_screen_capture_desc),
            badge = stringResource(R.string.perms_badge_session),
            statusLabel = if (status.screenCapturePermitted) stringResource(R.string.perms_status_active) else stringResource(R.string.perms_status_per_session),
            granted = status.screenCapturePermitted,
            onClick = onOpenBridge,
        )
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
            modifier = Modifier.padding(vertical = 4.dp),
        )
        PermissionStatusRow(
            icon = Icons.Filled.Contacts,
            title = stringResource(R.string.perms_contacts),
            subtitle = stringResource(R.string.perms_contacts_desc),
            badge = stringResource(R.string.perms_badge_optional),
            statusLabel = optionalStatus(context, status.contactsPermitted),
            granted = status.contactsPermitted,
            onClick = onOpenAppDetails,
        )
        PermissionStatusRow(
            icon = Icons.Filled.Sms,
            title = stringResource(R.string.perms_sms),
            subtitle = stringResource(R.string.perms_sms_desc),
            badge = stringResource(R.string.perms_badge_optional),
            statusLabel = optionalStatus(context, status.smsPermitted),
            granted = status.smsPermitted,
            onClick = onOpenAppDetails,
        )
        PermissionStatusRow(
            icon = Icons.Filled.Call,
            title = stringResource(R.string.perms_phone),
            subtitle = stringResource(R.string.perms_phone_desc),
            badge = stringResource(R.string.perms_badge_optional),
            statusLabel = optionalStatus(context, status.phonePermitted),
            granted = status.phonePermitted,
            onClick = onOpenAppDetails,
        )
        PermissionStatusRow(
            icon = Icons.Filled.LocationOn,
            title = stringResource(R.string.perms_location),
            subtitle = stringResource(R.string.perms_location_desc),
            badge = stringResource(R.string.perms_badge_optional),
            statusLabel = optionalStatus(context, status.locationPermitted),
            granted = status.locationPermitted,
            onClick = onOpenAppDetails,
        )
    }
}

@Composable
private fun GooglePlayBridgeCoreCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.perms_bridge_core),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.perms_bridge_core_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PermissionSection(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
            )
            content()
        }
    }
}

@Composable
private fun PermissionStatusRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badge: String,
    statusLabel: String,
    granted: Boolean,
    onClick: (() -> Unit)?,
) {
    val rowModifier = if (onClick != null) {
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    }
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                NeedBadge(text = badge)
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusBadge(
            label = statusLabel,
            granted = granted,
        )
        if (onClick != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun NeedBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun StatusBadge(label: String, granted: Boolean) {
    val tint = if (granted) {
        Color(0xFF4CAF50)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = if (granted) {
                Icons.Filled.CheckCircle
            } else {
                Icons.Filled.RadioButtonUnchecked
            },
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
        )
    }
}

private fun optionalStatus(context: Context, granted: Boolean): String =
    if (granted) context.getString(R.string.perms_status_granted) else context.getString(R.string.perms_status_optional)

private fun requiredStatus(context: Context, granted: Boolean): String =
    if (granted) context.getString(R.string.perms_status_granted) else context.getString(R.string.perms_status_needed)

private fun openAppDetailsSettings(context: Context) {
    runCatching {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

private fun openAccessibilitySettings(context: Context) {
    runCatching {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

private fun openOverlaySettings(context: Context) {
    runCatching {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

private fun openNotificationListenerSettings(context: Context) {
    runCatching {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
