package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.viewmodel.BridgeStatus

/**
 * Standalone status-only card — can render with or without [BridgeMasterToggle]
 * above it. Used by `BridgeScreen` as a secondary "connection state at a
 * glance" block when there's enough info to show but the user hasn't touched
 * the master toggle yet.
 *
 * Phase 3 Wave 1 — bridge-ui (`bridge-screen-ui`). Kept distinct from
 * [BridgeMasterToggle] so that Agent safety-rails in Wave 2 can relocate the master
 * toggle without losing the status surface (and so we can reuse this card
 * in the Settings → Connection section later if desired).
 */
@Composable
fun BridgeStatusCard(
    status: BridgeStatus?,
    isConnected: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Status",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                ConnectionStatusBadge(
                    isConnected = isConnected,
                    isConnecting = false,
                )
                Text(
                    text = if (isConnected) "Connected" else "Disconnected",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isConnected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

            if (status == null) {
                Text(
                    text = "Bridge runtime not yet reporting status. Enable " +
                        "Agent Control above to begin receiving device telemetry.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                StatusKeyValue("Device", status.deviceName)
                StatusKeyValue(
                    "Battery",
                    status.batteryPercent?.let { "$it%" } ?: "Unknown"
                )
                StatusKeyValue("Screen", if (status.screenOn) "ON" else "OFF")
                StatusKeyValue("Current app", status.currentApp ?: "—")
                StatusKeyValue(
                    "Accessibility service",
                    if (status.accessibilityEnabled) "Enabled" else "Disabled"
                )
            }
        }
    }
}

@Composable
private fun StatusKeyValue(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun BridgeStatusCardPreviewConnected() {
    MaterialTheme {
        BridgeStatusCard(
            status = BridgeStatus(
                deviceName = "Galaxy S24",
                batteryPercent = 78,
                screenOn = true,
                currentApp = "Chrome",
                accessibilityEnabled = true,
            ),
            isConnected = true,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun BridgeStatusCardPreviewEmpty() {
    MaterialTheme {
        BridgeStatusCard(
            status = null,
            isConnected = false,
            modifier = Modifier.padding(16.dp)
        )
    }
}
