package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.viewmodel.BridgeStatus

/**
 * Master "Allow Agent Control" card — the headline of the Bridge tab.
 *
 * Phase 3 Wave 1 — δ (`bridge-screen-ui`). Visual style mirrors the status
 * cards in `PairedDevicesScreen`: surfaceVariant background, 16dp padding,
 * 10dp row spacing. Uses [ConnectionStatusBadge] for the pulsing status dot
 * so the Bridge tab looks visually consistent with the Settings → Connection
 * section.
 *
 * The headline switch is `enabled = allowEnable` so users can't flip it on
 * when the a11y service isn't granted — we instead bounce them to the
 * permission checklist. Tapping the info icon opens an explanation dialog
 * (required by Google Play's a11y review process, per the Phase 3 plan's
 * Play Store Strategy section).
 */
@Composable
fun BridgeMasterToggle(
    enabled: Boolean,
    status: BridgeStatus?,
    accessibilityGranted: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showExplain by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Agent Control",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (enabled) "Active — agent can interact with this device"
                        else "Off — agent cannot control this device",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { showExplain = true }) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = "What does this do?",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled && accessibilityGranted,
                    onCheckedChange = { onToggle(it) },
                    enabled = accessibilityGranted || enabled,
                )
            }

            if (!accessibilityGranted) {
                Text(
                    text = "Grant the Accessibility Service permission below to enable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (status != null) {
                Spacer(modifier = Modifier.height(2.dp))
                StatusInlineRow(
                    icon = Icons.Filled.PhoneAndroid,
                    label = "Device",
                    value = status.deviceName
                )
                StatusInlineRow(
                    icon = Icons.Filled.BatteryFull,
                    label = "Battery",
                    value = status.batteryPercent?.let { "$it%" } ?: "—"
                )
                StatusInlineRow(
                    icon = Icons.Filled.ScreenLockPortrait,
                    label = "Screen",
                    value = if (status.screenOn) "ON" else "OFF"
                )
                StatusInlineRow(
                    icon = Icons.Filled.Smartphone,
                    label = "Current app",
                    value = status.currentApp ?: "—"
                )
            }
        }
    }

    if (showExplain) {
        AlertDialog(
            onDismissRequest = { showExplain = false },
            title = { Text("About Agent Control") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Agent Control lets your Hermes agent read what's on " +
                            "your screen and interact with apps on your behalf " +
                            "(tap, type, scroll, screenshot).",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "This uses Android's Accessibility Service API, which " +
                            "is the same permission screen readers use. You must " +
                            "enable it in Android Settings before this switch works.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "You can turn Agent Control off at any time from this " +
                            "screen or by disabling the service in Android " +
                            "Settings. All bridge commands are logged in the " +
                            "Activity Log below.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showExplain = false }) { Text("Got it") }
            }
        )
    }
}

@Composable
private fun StatusInlineRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.height(16.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun BridgeMasterTogglePreviewOn() {
    MaterialTheme {
        BridgeMasterToggle(
            enabled = true,
            status = BridgeStatus(
                deviceName = "Galaxy S24",
                batteryPercent = 78,
                screenOn = true,
                currentApp = "Chrome",
                accessibilityEnabled = true,
            ),
            accessibilityGranted = true,
            onToggle = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun BridgeMasterTogglePreviewBlocked() {
    MaterialTheme {
        BridgeMasterToggle(
            enabled = false,
            status = BridgeStatus(
                deviceName = "Pixel 9",
                batteryPercent = 42,
                screenOn = true,
                currentApp = null,
                accessibilityEnabled = false,
            ),
            accessibilityGranted = false,
            onToggle = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}
