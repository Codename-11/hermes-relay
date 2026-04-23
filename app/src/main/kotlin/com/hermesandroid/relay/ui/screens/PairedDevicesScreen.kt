package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.auth.PairedDeviceInfo
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.displayLabel
import com.hermesandroid.relay.ui.components.SessionTtlPickerDialog
import com.hermesandroid.relay.ui.components.TransportSecurityBadge
import com.hermesandroid.relay.ui.components.TransportSecuritySize
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Full-screen list of all devices currently paired with the relay.
 *
 * Each row is a revocable [PairedDeviceInfo]. Data comes from
 * [ConnectionViewModel.pairedDevices], loaded on-demand via
 * [ConnectionViewModel.loadPairedDevices]. A pull-to-refresh-style "Refresh"
 * button sits in the top bar — keeping it explicit rather than gesture-based
 * avoids Material3's still-experimental PullRefresh surface.
 *
 * Revoking the currently-paired device is a special case: we surface a
 * confirmation dialog that warns the user they'll need to re-pair, then call
 * [ConnectionViewModel.clearSession] in addition to the server-side DELETE.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PairedDevicesScreen(
    connectionViewModel: ConnectionViewModel,
    onBack: () -> Unit,
    onRequestRepair: () -> Unit,
) {
    val devices by connectionViewModel.pairedDevices.collectAsState()
    val loading by connectionViewModel.pairedDevicesLoading.collectAsState()
    val loadError by connectionViewModel.pairedDevicesError.collectAsState()
    val currentPairedSession by connectionViewModel.currentPairedSession.collectAsState()
    // ADR 24 — per-endpoint sub-rows for the current device. Other devices
    // don't expose their endpoint list to us (the store is phone-local),
    // so we only render sub-rows under the row that matches this phone.
    val myEndpoints by connectionViewModel.observeDeviceEndpoints()
        .collectAsState(initial = emptyList())
    val activeEndpoint by connectionViewModel.activeEndpoint.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var pendingRevoke by remember { mutableStateOf<PairedDeviceInfo?>(null) }
    var pendingExtend by remember { mutableStateOf<PairedDeviceInfo?>(null) }
    val isTailscaleDetected by connectionViewModel.isTailscaleDetected.collectAsState()

    // === PER-CHANNEL-REVOKE: state for per-channel revoke confirm dialog ===
    var pendingChannelRevoke by remember {
        mutableStateOf<Pair<PairedDeviceInfo, String>?>(null)
    }
    // === END PER-CHANNEL-REVOKE ===

    // Kick off the initial fetch when the screen is first composed.
    LaunchedEffect(Unit) {
        connectionViewModel.loadPairedDevices()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Relay sessions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { connectionViewModel.loadPairedDevices() }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            when {
                loading && devices.isEmpty() -> LoadingState()
                loadError != null && devices.isEmpty() -> ErrorState(
                    message = loadError!!,
                    onRetry = { connectionViewModel.loadPairedDevices() }
                )
                devices.isEmpty() -> EmptyState(onRequestRepair = onRequestRepair)
                else -> DeviceList(
                    devices = devices,
                    currentToken = currentPairedSession?.token,
                    onRevoke = { pendingRevoke = it },
                    onExtend = { pendingExtend = it },
                    // === PER-CHANNEL-REVOKE: forward the per-channel chip tap ===
                    onRevokeChannel = { device, channel ->
                        pendingChannelRevoke = device to channel
                    },
                    // === END PER-CHANNEL-REVOKE ===
                    myEndpoints = myEndpoints,
                    activeEndpoint = activeEndpoint,
                )
            }
        }
    }

    pendingRevoke?.let { target ->
        val isCurrentDevice = target.isCurrent ||
            (currentPairedSession?.token?.startsWith(target.tokenPrefix) == true)

        AlertDialog(
            onDismissRequest = { pendingRevoke = null },
            title = {
                Text(
                    text = if (isCurrentDevice) "Revoke this device?"
                    else "Revoke device?"
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = target.deviceName.ifBlank { "Unnamed device" },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Token prefix: ${target.tokenPrefix}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (isCurrentDevice) {
                            "This is the current device. Revoking will " +
                                "end the session on this phone and you'll " +
                                "need to re-pair with the server."
                        } else {
                            "This will end that device's relay session. " +
                                "It can re-pair later using a new pairing code."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toRevoke = target
                        pendingRevoke = null
                        scope.launch {
                            val success = connectionViewModel.revokeDevice(toRevoke.tokenPrefix)
                            if (success) {
                                if (isCurrentDevice) {
                                    connectionViewModel.clearSession()
                                    snackbarHostState.showSnackbar("This device was unpaired. Re-pair to continue.")
                                    onRequestRepair()
                                } else {
                                    snackbarHostState.showSnackbar("Device revoked")
                                }
                            } else {
                                snackbarHostState.showSnackbar("Revoke failed")
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Revoke")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRevoke = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    pendingExtend?.let { target ->
        // Pre-select the session's CURRENT remaining lifetime rounded to
        // the nearest picker option, so the dialog reflects what's
        // already in place. Falls back to 30 days if the session is
        // unbounded or expired.
        val nowSec = System.currentTimeMillis() / 1000L
        val remaining: Long = target.expiresAt?.let { (it.toLong() - nowSec).coerceAtLeast(0L) }
            ?: 0L  // 0 = never expire (matches the picker's "Never" option)
        val initialTtl = if (target.expiresAt == null) 0L else remaining
        SessionTtlPickerDialog(
            initialTtlSeconds = initialTtl,
            isTailscaleDetected = isTailscaleDetected,
            transportHint = target.transportHint,
            onConfirm = { newTtl ->
                val toExtend = target
                pendingExtend = null
                scope.launch {
                    val success = connectionViewModel.extendDevice(
                        toExtend.tokenPrefix, newTtl
                    )
                    if (success) {
                        snackbarHostState.showSnackbar(
                            if (newTtl == 0L) "Session set to never expire"
                            else "Session expiry updated"
                        )
                    } else {
                        snackbarHostState.showSnackbar("Failed to update session")
                    }
                }
            },
            onCancel = { pendingExtend = null }
        )
    }

    // === PER-CHANNEL-REVOKE: confirm dialog ===
    pendingChannelRevoke?.let { (device, channel) ->
        val otherChannels = device.grants.keys
            .filter { it != channel }
            .joinToString(", ")
            .ifBlank { "none" }
        AlertDialog(
            onDismissRequest = { pendingChannelRevoke = null },
            title = { Text("Revoke channel access?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Revoke $channel access for " +
                            device.deviceName.ifBlank { "this device" } + "?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "The session itself stays paired — only the " +
                            "$channel grant is removed. Other channels " +
                            "($otherChannels) keep their current expiry.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val (target, ch) = device to channel
                        pendingChannelRevoke = null
                        scope.launch {
                            val ok = connectionViewModel.revokeChannelGrant(
                                target.tokenPrefix,
                                ch,
                            )
                            snackbarHostState.showSnackbar(
                                if (ok) "$ch access revoked"
                                else "Failed to revoke $ch access"
                            )
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Revoke")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingChannelRevoke = null }) {
                    Text("Cancel")
                }
            },
        )
    }
    // === END PER-CHANNEL-REVOKE ===
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Devices,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .height(48.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Couldn't load relay sessions",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onRetry) {
            Text("Try again")
        }
    }
}

@Composable
private fun EmptyState(onRequestRepair: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Devices,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.height(48.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "No relay sessions (yet)",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Pair this phone with your relay to see it here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequestRepair) { Text("Pair now") }
    }
}

@Composable
private fun DeviceList(
    devices: List<PairedDeviceInfo>,
    currentToken: String?,
    onRevoke: (PairedDeviceInfo) -> Unit,
    onExtend: (PairedDeviceInfo) -> Unit,
    // === PER-CHANNEL-REVOKE: per-chip revoke callback ===
    onRevokeChannel: (PairedDeviceInfo, String) -> Unit,
    // === END PER-CHANNEL-REVOKE ===
    myEndpoints: List<EndpointCandidate>,
    activeEndpoint: EndpointCandidate?,
) {
    // Channel-grants explainer sheet — shared across all DeviceCard rows
    // since the explanation is identical per row. Hoisted here instead of
    // per-card so repeated taps on different rows reuse one dialog.
    var showChannelInfoSheet by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header paragraph — orients the user before the device list so
        // the revoke / channel-grant vocabulary isn't load-bearing without
        // context. `bodyMedium` + `onSurfaceVariant` keeps it subordinate
        // to the device cards below.
        item {
            Text(
                text = "Each row is a phone that has paired with this server.\n" +
                    "Channel grants are per-feature authorizations (chat, bridge, voice) " +
                    "that can expire independently.\n" +
                    "Revoke a session to cut off that phone's access without affecting others.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 0.dp, vertical = 0.dp),
            )
        }
        items(devices, key = { it.tokenPrefix }) { device ->
            val isCurrent = device.isCurrent ||
                (currentToken?.startsWith(device.tokenPrefix) == true)
            DeviceCard(
                device = device,
                isCurrent = isCurrent,
                onRevoke = { onRevoke(device) },
                onExtend = { onExtend(device) },
                // === PER-CHANNEL-REVOKE: forward to card ===
                onRevokeChannel = { channel -> onRevokeChannel(device, channel) },
                // === END PER-CHANNEL-REVOKE ===
                onOpenChannelInfo = { showChannelInfoSheet = true },
                // ADR 24 — endpoints only render under the current-device
                // row because the store is phone-local. Other devices get
                // the unchanged single-row treatment.
                endpoints = if (isCurrent) myEndpoints else emptyList(),
                activeEndpoint = activeEndpoint.takeIf { isCurrent },
            )
        }
    }

    if (showChannelInfoSheet) {
        AlertDialog(
            onDismissRequest = { showChannelInfoSheet = false },
            title = { Text("About channel grants") },
            text = {
                Text(
                    text = "Channel grants are per-feature permissions.\n\n" +
                        "• Chat controls sent/received messages.\n" +
                        "• Bridge controls accessibility actions.\n" +
                        "• Voice controls microphone + TTS.\n\n" +
                        "Each grant has its own expiry, so a phone can keep chat " +
                        "access long after bridge access has lapsed — or you can " +
                        "revoke one channel without ending the whole session.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { showChannelInfoSheet = false }) {
                    Text("Got it")
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceCard(
    device: PairedDeviceInfo,
    isCurrent: Boolean,
    onRevoke: () -> Unit,
    onExtend: () -> Unit,
    // === PER-CHANNEL-REVOKE: per-chip revoke callback ===
    onRevokeChannel: (String) -> Unit,
    // === END PER-CHANNEL-REVOKE ===
    onOpenChannelInfo: () -> Unit,
    endpoints: List<EndpointCandidate> = emptyList(),
    activeEndpoint: EndpointCandidate? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Device name + current badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.deviceName.ifBlank { "Unnamed device" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (device.deviceId.isNotBlank()) {
                        Text(
                            text = device.deviceId,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                if (isCurrent) {
                    CurrentBadge()
                }
            }

            HorizontalDivider()

            // Transport security row
            val transportSecure = when (device.transportHint?.lowercase()) {
                "wss", "https" -> true
                "ws", "http" -> false
                else -> null
            }
            if (transportSecure != null) {
                // Prefer the live active-endpoint role (ADR 24) when this is
                // the current device; otherwise let the neutral fallback
                // ("Plain (no TLS)") render. The old hardcoded "lan_only"
                // lied for Tailscale/public rows.
                val rowRole = if (isCurrent) activeEndpoint?.role else null
                TransportSecurityBadge(
                    isSecure = transportSecure,
                    reason = null,
                    size = TransportSecuritySize.Row,
                    activeRole = rowRole,
                )
            }

            // ADR 24 — per-endpoint sub-rows for this device. Only populated
            // for the current device; other devices render with endpoints
            // empty so the unchanged single-row treatment still applies.
            if (endpoints.isNotEmpty()) {
                EndpointsSubList(
                    endpoints = endpoints,
                    activeEndpoint = activeEndpoint,
                )
            }

            // Expiry row
            ExpiryRow(expiresAt = device.expiresAt)

            // Per-channel grant chips
            if (device.grants.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "Channel grants",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(
                        onClick = onOpenChannelInfo,
                        modifier = Modifier.size(20.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = "About channel grants",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for ((channel, expiry) in device.grants) {
                        // === PER-CHANNEL-REVOKE: tap-x to revoke a single channel ===
                        GrantChip(
                            channel = channel,
                            expiresAt = expiry,
                            onRevoke = { onRevokeChannel(channel) },
                        )
                        // === END PER-CHANNEL-REVOKE ===
                    }
                }
            }

            // Metadata rows (first seen / last seen)
            if (device.createdAt != null) {
                MetaRow(label = "First seen", value = formatEpoch(device.createdAt.toLong()))
            }
            if (device.lastSeen != null) {
                MetaRow(label = "Last seen", value = formatEpoch(device.lastSeen.toLong()))
            }

            // Extend + Revoke actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onExtend,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Extend")
                }
                OutlinedButton(
                    onClick = onRevoke,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(if (isCurrent) "Revoke this" else "Revoke")
                }
            }
        }
    }
}

@Composable
private fun CurrentBadge() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Shield,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.height(14.dp)
        )
        Text(
            text = "This device",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun ExpiryRow(expiresAt: Double?) {
    val text = when {
        expiresAt == null -> "Never expires"
        else -> "Expires ${formatEpoch(expiresAt.toLong())}"
    }
    val color = when {
        expiresAt == null -> MaterialTheme.colorScheme.primary
        expiresAt < System.currentTimeMillis() / 1000.0 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color
    )
}

// === PER-CHANNEL-REVOKE: GrantChip now has an x button for revoke ===
@Composable
private fun GrantChip(
    channel: String,
    expiresAt: Double?,
    onRevoke: () -> Unit,
) {
    val bg = MaterialTheme.colorScheme.surface
    val nowSec = System.currentTimeMillis() / 1000.0
    val isExpired = expiresAt != null && expiresAt < nowSec
    val fg = if (isExpired) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$channel · ${formatRelativeTtl(expiresAt, nowSec)}",
            style = MaterialTheme.typography.labelSmall,
            color = fg,
        )
        Spacer(Modifier.width(4.dp))
        // Small clickable Box instead of IconButton — IconButton inflates
        // to a 48dp touch target which would make the chip explode in
        // FlowRow. The chip lives in a settings list, not a content feed,
        // so the smaller touch target is acceptable for the revoke action.
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .clickable(
                    onClick = onRevoke,
                    role = androidx.compose.ui.semantics.Role.Button,
                )
                .padding(2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Revoke $channel access",
                modifier = Modifier.height(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Render a relative TTL label for a grant expiry epoch.
 *
 *   null      → "never"
 *   in past   → "expired"
 *   < 1m      → "in <1m"
 *   minutes   → "in Nm"
 *   hours     → "in Nh"
 *   days      → "in Nd"
 */
private fun formatRelativeTtl(expiresAt: Double?, nowSec: Double): String {
    if (expiresAt == null) return "never"
    val deltaSec = (expiresAt - nowSec).toLong()
    if (deltaSec <= 0) return "expired"
    val days = deltaSec / 86_400
    val hours = deltaSec / 3_600
    val minutes = deltaSec / 60
    return when {
        days >= 1 -> "in ${days}d"
        hours >= 1 -> "in ${hours}h"
        minutes >= 1 -> "in ${minutes}m"
        else -> "in <1m"
    }
}
// === END PER-CHANNEL-REVOKE ===

@Composable
private fun MetaRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun formatEpoch(epochSeconds: Long): String {
    return try {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochSeconds * 1000L))
    } catch (_: Exception) {
        "—"
    }
}

/**
 * ADR 24 — one visual row per (device, endpoint). Rendered only for the
 * current device since the phone-local DataStore doesn't hold endpoints
 * for other peers. Revoke stays at the device level (kills the session
 * token, which wipes every endpoint row at once).
 */
@Composable
private fun EndpointsSubList(
    endpoints: List<EndpointCandidate>,
    activeEndpoint: EndpointCandidate?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Routes",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (candidate in endpoints) {
            val isActive = activeEndpoint != null &&
                activeEndpoint.role.equals(candidate.role, ignoreCase = true) &&
                activeEndpoint.api.host.equals(candidate.api.host, ignoreCase = true) &&
                activeEndpoint.api.port == candidate.api.port
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isActive) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                        } else {
                            Color.Transparent
                        },
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = candidate.displayLabel(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = "${candidate.api.host}:${candidate.api.port}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                if (isActive) {
                    Text(
                        text = "active",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

