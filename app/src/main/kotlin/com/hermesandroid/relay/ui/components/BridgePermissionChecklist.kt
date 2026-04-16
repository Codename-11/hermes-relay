package com.hermesandroid.relay.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
 * Permission checklist card — one row per required Android permission.
 * Tapping a non-granted row fires an Intent to the corresponding Android
 * Settings screen so the user can grant the permission without leaving
 * their muscle-memory path.
 *
 * Phase 3 Wave 1 — bridge-ui (`bridge-screen-ui`). Uses vector Material icons to
 * stay inside the already-shipped icon set (no dependency on
 * compose-icons-extended, which has bitten us before — see
 * `fix(settings): revert ChevronRight…`).
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
    // Tapping the Screen Capture row launches the system MediaProjection
    // consent dialog (no Settings page exists for this permission, so
    // the row's onClick was previously null). Notification Listener gets
    // its own Test button on the Bridge tab for parity with the others —
    // the dedicated test on NotificationCompanionSettingsScreen still ships.
    onRequestScreenCapture: (() -> Unit)? = null,
    onTestNotificationListener: (() -> Unit)? = null,
    // === END PHASE3-bridge-ui-followup ===
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
                text = "Tap a row to open Android Settings · Tap Test to verify the permission works.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

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
                    // MediaProjection has no Android Settings page; tapping the
                    // row launches the system consent dialog directly via
                    // ScreenCaptureRequester. Falls back to inert (no chevron)
                    // if the parent didn't provide a launcher (e.g., previews).
                    onClick = onRequestScreenCapture,
                    onTest = onTestScreenCapture,
                )
            }
            PermissionRow(
                icon = Icons.Filled.PictureInPicture,
                title = "Display over other apps",
                subtitle = "Status overlay while bridge is active",
                granted = status.overlayPermitted,
                onClick = { openOverlaySettings(context) },
                onTest = onTestOverlay,
            )
            PermissionRow(
                icon = Icons.Filled.Notifications,
                title = "Notification Listener",
                subtitle = "Read notifications for agent summaries",
                granted = status.notificationListenerPermitted,
                onClick = { openNotificationListenerSettings(context) },
                // Parity Test button on the Bridge tab. The full functional
                // round-trip test still lives on NotificationCompanionSettingsScreen.
                onTest = onTestNotificationListener,
            )
        }
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
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // === PHASE3-safety-rails-followup: in-app Test button ===
        // Compact text button next to the status icon. Tapping it runs a
        // diagnostic check on the permission and surfaces the result via
        // BridgeScreen → LocalSnackbarHost. Only renders when the parent
        // provides an onTest lambda; null hides the button on rows where
        // a meaningful diagnostic isn't available at this layer.
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
        // === END PHASE3-safety-rails-followup ===
        // Status icon: green check when granted, red empty circle otherwise.
        Icon(
            imageVector = if (granted) Icons.Filled.CheckCircle
            else Icons.Filled.RadioButtonUnchecked,
            contentDescription = if (granted) "Granted" else "Not granted",
            tint = if (granted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
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
