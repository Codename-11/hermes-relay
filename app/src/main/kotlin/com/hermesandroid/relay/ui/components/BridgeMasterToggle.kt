package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Surface
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
 * Phase 3 Wave 1 — bridge-ui (`bridge-screen-ui`). Visual style mirrors the status
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
    label: String = "Agent Control",
    // Called when the user taps the switch to enable but accessibility
    // hasn't been granted yet. The default no-op keeps the v0.4 behaviour
    // for callers that don't wire this up; BridgeScreen hooks a snackbar
    // + settings-deep-link so users get visible feedback instead of a
    // dead-feeling tap. See kdoc at the call site.
    onAccessibilityNeeded: () -> Unit = {},
) {
    var showExplain by remember { mutableStateOf(false) }

    // Subtitle is phrased around "master switch" so users understand
    // this is the parent gate for everything on the page. Sub-features
    // (unattended access, permissions, voice intents) all require this
    // to be ON before they do anything — calling it out in plain text
    // here prevents the "I toggled unattended but nothing happened"
    // confusion pattern. Copy differs by flavor: sideload is the full
    // agent-control story, googlePlay is the read-only "chat context"
    // framing.
    val isSideloadLabel = label.contains("Agent", ignoreCase = true)
    val subtitle = if (isSideloadLabel) {
        if (enabled) {
            "Master switch — agent can read screen and act via the " +
                "sub-features below."
        } else {
            "Master switch — off. All bridge features (unattended, " +
                "commands, voice intents) are inactive."
        }
    } else {
        if (enabled) {
            "Master switch — bridge is providing screen content to chat."
        } else {
            "Master switch — off. Bridge is not reading screen content."
        }
    }

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
                    // Title + MASTER pill flow together so the pill drops
                    // below on narrow widths instead of squishing the title.
                    // Same pattern + rationale as the "Optional" pill in
                    // BridgePermissionChecklist (see its KDoc).
                    MasterTitleRow(label = label)
                    Text(
                        text = subtitle,
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
                    // Always interactive — a disabled Switch swallows taps
                    // silently on Android, which made users feel like the
                    // app was broken when accessibility wasn't granted yet.
                    // We now route the blocked-enable path through
                    // onAccessibilityNeeded so the caller can surface a
                    // snackbar with an "Open Settings" action.
                    onCheckedChange = { wantsOn ->
                        if (wantsOn && !accessibilityGranted) {
                            onAccessibilityNeeded()
                        } else {
                            onToggle(wantsOn)
                        }
                    },
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
                        "While this is on, a 'Hermes has device control' " +
                            "notification stays in your notification shade — " +
                            "that's tied to this master switch, not to any " +
                            "sub-feature (like Unattended Access), and goes " +
                            "away the moment you turn this off.",
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

/**
 * Title row: the flavor-dependent label plus a compact "MASTER" pill so
 * users read it as the parent gate on the page at a glance. FlowRow lets
 * the pill drop below the label on narrow widths (matches the pattern
 * used by the Optional pill in BridgePermissionChecklist).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MasterTitleRow(label: String) {
    FlowRow(
        verticalArrangement = Arrangement.Center,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        MasterPill()
    }
}

@Composable
private fun MasterPill() {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            text = "MASTER",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
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
