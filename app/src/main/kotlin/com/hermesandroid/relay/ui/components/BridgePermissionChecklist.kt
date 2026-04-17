package com.hermesandroid.relay.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.hermesandroid.relay.data.BuildFlavor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.viewmodel.BridgePermissionStatus

/**
 * Tiered permission checklist (v0.4.1).
 *
 * Replaces the original four-row flat layout with four explicit sections so
 * users (and Play Store reviewers) can see at a glance which permissions are
 * required, which are optional, and which are sideload-only.
 *
 * | Section                | Flavor          | All rows required? |
 * |------------------------|-----------------|--------------------|
 * | Core bridge            | both            | yes                |
 * | Notification companion | both            | optional           |
 * | Voice & camera         | both            | optional (used on-demand) |
 * | Sideload features      | sideload only   | optional           |
 *
 * Each row dispatches its tap depending on permission type:
 *  - Special perms (Accessibility, Notification Listener, Overlay) deep-link
 *    to the matching `Settings.ACTION_*` intent. Same pattern as v0.4.
 *  - Screen Capture launches the system MediaProjection consent dialog via
 *    [com.hermesandroid.relay.accessibility.ScreenCaptureRequester] (no
 *    Settings page exists for it).
 *  - Runtime dangerous perms (Notifications, Mic, Camera, Contacts, SMS,
 *    Phone, Location) request via parent-supplied lambdas that wrap
 *    `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission)`.
 *    Status re-probes on `Lifecycle.Event.ON_RESUME` (see
 *    [com.hermesandroid.relay.viewmodel.BridgeViewModel.refreshPermissionStatus]).
 *
 * Optional rows render an "Optional" badge so users don't feel pressured to
 * grant the full set. Sideload-only rows are wholly omitted on googlePlay.
 */
@Composable
fun BridgePermissionChecklist(
    status: BridgePermissionStatus,
    modifier: Modifier = Modifier,
    // === PHASE3-safety-rails-followup: in-app permission Test handlers ===
    onTestAccessibility: (() -> Unit)? = null,
    onTestScreenCapture: (() -> Unit)? = null,
    onTestOverlay: (() -> Unit)? = null,
    // === END PHASE3-safety-rails-followup ===
    // === PHASE3-bridge-ui-followup: extended interactions ===
    onRequestScreenCapture: (() -> Unit)? = null,
    onTestNotificationListener: (() -> Unit)? = null,
    // === END PHASE3-bridge-ui-followup ===
    onRequestNotifications: (() -> Unit)? = null,
    // === v0.4.1 tiered runtime permission requesters ===
    // Each lambda calls `rememberLauncherForActivityResult` from BridgeScreen.
    // Null disables the row's tap action (used by previews).
    onRequestMicrophone: (() -> Unit)? = null,
    onRequestCamera: (() -> Unit)? = null,
    onRequestContacts: (() -> Unit)? = null,
    onRequestSms: (() -> Unit)? = null,
    onRequestPhone: (() -> Unit)? = null,
    onRequestLocation: (() -> Unit)? = null,
    // === END v0.4.1 ===
) {
    val context = LocalContext.current

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Permissions",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Tap a row to grant or open Android Settings · Tap Test to verify.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

            // ── Core bridge (required, both flavors) ──────────────────────
            TierHeader(
                label = "Core bridge",
                subtitle = "Required for the agent to read and act on screen content.",
            )
            PermissionRow(
                icon = Icons.Filled.Accessibility,
                title = "Accessibility Service",
                subtitle = if (BuildFlavor.isSideload)
                    "Read screen content, dispatch taps/types"
                else
                    "Read screen content for chat context",
                granted = status.accessibilityServiceEnabled,
                onClick = { openAccessibilitySettings(context) },
                onTest = onTestAccessibility,
            )
            // Screen Capture — sideload only. googlePlay doesn't declare
            // FOREGROUND_SERVICE_MEDIA_PROJECTION or the /screenshot route,
            // and showing a consent toggle for a capability the APK can't
            // use would confuse both users and Play reviewers.
            if (BuildFlavor.isSideload) {
                PermissionRow(
                    icon = Icons.Filled.ScreenShare,
                    title = "Screen Capture",
                    subtitle = if (status.screenCapturePermitted)
                        "Granted for this session — agent can take screenshots"
                    else
                        "Tap to grant — agent needs this for /screenshot",
                    granted = status.screenCapturePermitted,
                    onClick = onRequestScreenCapture,
                    onTest = onTestScreenCapture,
                )
            }
            // Display over other apps — sideload only. googlePlay has no
            // destructive-verb safety modal (action routes are blocked) and
            // no status overlay chip, so the SYSTEM_ALERT_WINDOW permission
            // isn't needed and showing the row would confuse users + reviewers.
            if (BuildFlavor.isSideload) {
                PermissionRow(
                    icon = Icons.Filled.PictureInPicture,
                    title = "Display over other apps",
                    subtitle = "Status overlay while bridge is active",
                    granted = status.overlayPermitted,
                    onClick = { openOverlaySettings(context) },
                    onTest = onTestOverlay,
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionRow(
                    icon = Icons.Filled.Notifications,
                    title = "Notifications",
                    subtitle = if (status.notificationsPermitted)
                        "Bridge service notification can display"
                    else
                        "Required for the bridge foreground service indicator",
                    granted = status.notificationsPermitted,
                    onClick = onRequestNotifications,
                )
            }

            // ── Notification companion (optional, both flavors) ─────────────
            TierSpacer()
            TierHeader(
                label = "Notification companion",
                subtitle = "Optional. Lets the agent see incoming notifications for summaries and replies.",
            )
            PermissionRow(
                icon = Icons.Filled.Notifications,
                title = "Notification Listener",
                subtitle = "Read notifications for agent summaries",
                granted = status.notificationListenerPermitted,
                onClick = { openNotificationListenerSettings(context) },
                onTest = onTestNotificationListener,
                optional = true,
            )

            // ── Voice & camera (optional, both flavors) ────────────────────
            TierSpacer()
            TierHeader(
                label = "Voice & camera",
                subtitle = "Required when you use voice mode or attach camera media.",
            )
            PermissionRow(
                icon = Icons.Filled.Mic,
                title = "Microphone",
                subtitle = "Required for voice mode (record + transcribe).",
                granted = status.microphonePermitted,
                onClick = onRequestMicrophone,
                optional = true,
            )
            PermissionRow(
                icon = Icons.Filled.CameraAlt,
                title = "Camera",
                subtitle = "Required to attach photos taken in-app.",
                granted = status.cameraPermitted,
                onClick = onRequestCamera,
                optional = true,
            )

            // ── Sideload features (sideload-only, all optional) ───────────
            if (BuildFlavor.isSideload) {
                TierSpacer()
                TierHeader(
                    label = "Sideload features",
                    subtitle = "Optional. Powers contact lookup, SMS, dialer, and location tools.",
                )
                PermissionRow(
                    icon = Icons.Filled.Contacts,
                    title = "Contacts",
                    subtitle = "Resolve names to phone numbers (android_search_contacts).",
                    granted = status.contactsPermitted,
                    onClick = onRequestContacts,
                    optional = true,
                )
                PermissionRow(
                    icon = Icons.Filled.Sms,
                    title = "SMS",
                    subtitle = "Send text messages directly (android_send_sms).",
                    granted = status.smsPermitted,
                    onClick = onRequestSms,
                    optional = true,
                )
                PermissionRow(
                    icon = Icons.Filled.Call,
                    title = "Phone",
                    subtitle = "Place calls directly without opening the dialer (android_call).",
                    granted = status.phonePermitted,
                    onClick = onRequestPhone,
                    optional = true,
                )
                PermissionRow(
                    icon = Icons.Filled.LocationOn,
                    title = "Location",
                    subtitle = "Last-known GPS fix for context-aware queries (android_location).",
                    granted = status.locationPermitted,
                    onClick = onRequestLocation,
                    optional = true,
                )
            }
        }
    }
}

/**
 * Section header for each tier of the checklist. Light visual separator
 * pattern: a primary-coloured label + a one-line caption underneath.
 */
@Composable
private fun TierHeader(label: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Vertical spacer + thin divider between tiers. Cheaper than nesting each
 * tier in its own Card — keeps the "one card per checklist" affordance.
 */
@Composable
private fun TierSpacer() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 6.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
    )
}

/**
 * Material 3 "Optional" pill — small Surface with rounded-pill corners and
 * the secondary-container colour. Sits inline with the row title so the
 * affordance reads at a glance without breaking the row's vertical rhythm.
 */
@Composable
private fun OptionalBadge() {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = "Optional",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean,
    onClick: (() -> Unit)?,
    onTest: (() -> Unit)? = null,
    optional: Boolean = false,
) {
    val rowModifier = if (onClick != null) {
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
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
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (optional) {
                    OptionalBadge()
                }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onTest != null) {
            TextButton(
                onClick = onTest,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 8.dp,
                    vertical = 0.dp,
                ),
            ) {
                Text(
                    text = "Test",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        // Status icon: green check when granted, red empty circle otherwise.
        // Optional rows that are *not* granted use a neutral tint instead of
        // error red so users don't perceive them as urgent action items.
        val (statusTint, statusDescription) = when {
            granted -> Color(0xFF4CAF50) to "Granted"
            optional -> MaterialTheme.colorScheme.onSurfaceVariant to "Not granted (optional)"
            else -> MaterialTheme.colorScheme.error to "Not granted"
        }
        Icon(
            imageVector = if (granted) Icons.Filled.CheckCircle
            else Icons.Filled.RadioButtonUnchecked,
            contentDescription = statusDescription,
            tint = statusTint,
            modifier = Modifier.size(22.dp),
        )
        if (onClick != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ── Intent helpers ──────────────────────────────────────────────────────
//
// All three intent launchers guard with runCatching because Samsung / Xiaomi
// OEM skins occasionally ship without the standard ACTION_* constants, and
// we'd rather degrade to a no-op than crash the Bridge screen.

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
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

private fun openNotificationListenerSettings(context: Context) {
    runCatching {
        val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun BridgePermissionChecklistPreviewAllGranted() {
    MaterialTheme {
        BridgePermissionChecklist(
            status = BridgePermissionStatus(
                accessibilityServiceEnabled = true,
                screenCapturePermitted = true,
                overlayPermitted = true,
                notificationListenerPermitted = true,
                notificationsPermitted = true,
                microphonePermitted = true,
                cameraPermitted = true,
                contactsPermitted = true,
                smsPermitted = true,
                phonePermitted = true,
                locationPermitted = true,
            ),
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun BridgePermissionChecklistPreviewNoneGranted() {
    MaterialTheme {
        BridgePermissionChecklist(
            status = BridgePermissionStatus(),
            modifier = Modifier.padding(16.dp)
        )
    }
}
