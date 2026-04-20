package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.displayLabel
import com.hermesandroid.relay.data.isKnownRole
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import kotlinx.coroutines.launch

/**
 * ADR 24 — per-endpoint visibility + override card for the Connection
 * settings screen. Renders one row per [EndpointCandidate] stored for the
 * active device, with a health chip, a 3-dot menu (Prefer / Probe now /
 * View pin), and a bottom "Clear manual override" action when a preferred
 * role is set.
 *
 * Verbose by design — Bailey explicitly asked for per-row visibility, so we
 * do NOT collapse into a master row. The card itself is wrapped in a
 * [SettingsExpandableCard] by the caller for page layout hygiene.
 *
 * Not visible on legacy installs: when [endpoints] is empty we render a
 * helpful one-liner instead of an empty card, so freshly-upgraded users
 * who haven't re-paired yet understand why they can't see anything.
 */
@Composable
fun EndpointsCard(
    endpoints: List<EndpointCandidate>,
    activeEndpoint: EndpointCandidate?,
    preferredRole: String?,
    onPreferEndpoint: (EndpointCandidate) -> Unit,
    onClearOverride: () -> Unit,
    onProbeNow: () -> Unit,
    onViewPin: suspend (EndpointCandidate) -> String?,
) {
    if (endpoints.isEmpty()) {
        Text(
            text = "No endpoint candidates stored for this device yet. " +
                "Scan a v3 pairing QR (Hermes 0.4.2+) to enable multi-endpoint " +
                "switching — LAN + Tailscale + public URLs.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        endpoints.forEachIndexed { index, candidate ->
            if (index > 0) HorizontalDivider()
            EndpointRow(
                candidate = candidate,
                isActive = activeEndpoint != null &&
                    activeEndpoint.role.equals(candidate.role, ignoreCase = true) &&
                    activeEndpoint.api.host.equals(candidate.api.host, ignoreCase = true) &&
                    activeEndpoint.api.port == candidate.api.port,
                isPreferred = preferredRole?.equals(candidate.role, ignoreCase = true) == true,
                onPrefer = { onPreferEndpoint(candidate) },
                onProbeNow = onProbeNow,
                onViewPin = onViewPin,
            )
        }

        if (preferredRole != null) {
            HorizontalDivider()
            TextButton(onClick = onClearOverride, modifier = Modifier.fillMaxWidth()) {
                Text("Clear manual override (preferring $preferredRole)")
            }
        }
    }
}

/**
 * One row: role chip + host:port + transport hint + health chip + 3-dot menu.
 */
@Composable
private fun EndpointRow(
    candidate: EndpointCandidate,
    isActive: Boolean,
    isPreferred: Boolean,
    onPrefer: () -> Unit,
    onProbeNow: () -> Unit,
    onViewPin: suspend (EndpointCandidate) -> String?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var pinDialogText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = roleIcon(candidate.role),
                contentDescription = null,
                tint = if (isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(18.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = candidate.displayLabel(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (isActive) {
                        ActiveChip()
                    } else if (isPreferred) {
                        PreferredChip()
                    }
                    if (!candidate.isKnownRole()) {
                        // Show the raw role for custom-VPN entries so users
                        // can tell "netbird-eu" from "wireguard-home" at a
                        // glance without poking into the menu.
                        Text(
                            text = "(${candidate.role})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                Text(
                    text = "${candidate.api.host}:${candidate.api.port}" +
                        (candidate.relay.transportHint?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }

            // 3-dot overflow menu — actions per-row so the card stays flat
            // without needing to expand into a detail sheet. "View pin"
            // suspends to read CertPinStore, so we resolve it into a dialog
            // when the user taps it.
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "Endpoint actions",
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Prefer this endpoint") },
                        onClick = {
                            menuOpen = false
                            onPrefer()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Probe now") },
                        onClick = {
                            menuOpen = false
                            onProbeNow()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("View pin") },
                        onClick = {
                            menuOpen = false
                            scope.launch {
                                pinDialogText = onViewPin(candidate)
                                    ?: "No pin recorded yet — the phone will " +
                                        "record one on first TOFU connect."
                            }
                        },
                    )
                }
            }
        }
    }

    pinDialogText?.let { body ->
        AlertDialog(
            onDismissRequest = { pinDialogText = null },
            title = { Text("TOFU pin · ${candidate.api.host}") },
            text = {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            },
            confirmButton = {
                TextButton(onClick = { pinDialogText = null }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun ActiveChip() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Shield,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.height(10.dp),
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "Active",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun PreferredChip() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFFFA726).copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "Preferred",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFB26A00),
        )
    }
}

/**
 * Role → Material icon. Known roles get their canonical glyph; anything
 * else falls through to [Icons.Filled.Shield] (generic "Custom VPN").
 */
private fun roleIcon(role: String): ImageVector = when (role.lowercase()) {
    "lan" -> Icons.Filled.Lan
    "tailscale" -> Icons.Filled.VpnKey
    "public" -> Icons.Filled.Public
    else -> Icons.Filled.Shield
}
