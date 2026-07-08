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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
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
                title = { Text(stringResource(R.string.paired_devices_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.paired_devices_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { connectionViewModel.loadPairedDevices() }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.paired_devices_refresh)
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

        val unnamedDevice = stringResource(R.string.paired_devices_unnamed_device)
        val revokeThisTitle = stringResource(R.string.paired_devices_revoke_this_title)
        val revokeTitle = stringResource(R.string.paired_devices_revoke_title)
        val revokeCurrentBody = stringResource(R.string.paired_devices_revoke_current_body)
        val revokeOtherBody = stringResource(R.string.paired_devices_revoke_other_body)
        val unpairedMessage = stringResource(R.string.paired_devices_unpaired)
        val revokedMessage = stringResource(R.string.paired_devices_revoked)
        val revokeFailedMessage = stringResource(R.string.paired_devices_revoke_failed)

        AlertDialog(
            onDismissRequest = { pendingRevoke = null },
            title = {
                Text(
                    text = if (isCurrentDevice) revokeThisTitle else revokeTitle
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = target.deviceName.ifBlank { unnamedDevice },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.paired_devices_token_prefix, target.tokenPrefix),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (isCurrentDevice) revokeCurrentBody else revokeOtherBody,
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
                                    snackbarHostState.showSnackbar(unpairedMessage)
                                    onRequestRepair()
                                } else {
                                    snackbarHostState.showSnackbar(revokedMessage)
                                }
                            } else {
                                snackbarHostState.showSnackbar(revokeFailedMessage)
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.paired_devices_revoke))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRevoke = null }) {
                    Text(stringResource(R.string.paired_devices_cancel))
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
        val setNeverMessage = stringResource(R.string.paired_devices_session_never)
        val updatedMessage = stringResource(R.string.paired_devices_session_updated)
        val failedToUpdateMessage = stringResource(R.string.paired_devices_session_update_failed)
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
                            if (newTtl == 0L) setNeverMessage else updatedMessage
                        )
                    } else {
                        snackbarHostState.showSnackbar(failedToUpdateMessage)
                    }
                }
            },
            onCancel = { pendingExtend = null }
        )
    }

    // === PER-CHANNEL-REVOKE: confirm dialog ===
    pendingChannelRevoke?.let { (device, channel) ->
        val channelLabel = grantDisplayName(channel)
        val noneLabel = stringResource(R.string.paired_devices_none)
        val thisDeviceLabel = stringResource(R.string.paired_devices_this_device)
        // Resolve display names for every grant up-front (grantDisplayName
        // is @Composable, so we can't call it inside joinToString's lambda).
        val grantLabels = device.grants.keys.associateWith { grantDisplayName(it) }
        val otherChannels = device.grants.keys
            .filter { it != channel }
            .sortedWith(::compareGrantNames)
            .joinToString(", ") { grantLabels[it] ?: it }
            .ifBlank { noneLabel }
        val revokeChannelTitle = stringResource(R.string.paired_devices_revoke_channel_title)
        val revokeChannelBody = stringResource(
            R.string.paired_devices_revoke_channel_body,
            channelLabel,
            device.deviceName.ifBlank { thisDeviceLabel },
        )
        val revokeChannelDesc = stringResource(
            R.string.paired_devices_revoke_channel_desc,
            channelLabel,
            otherChannels,
        )
        val channelRevokedMsg = stringResource(R.string.paired_devices_channel_revoked, channelLabel)
        val channelRevokeFailedMsg = stringResource(
            R.string.paired_devices_channel_revoke_failed,
            channelLabel,
        )
        AlertDialog(
            onDismissRequest = { pendingChannelRevoke = null },
            title = { Text(revokeChannelTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = revokeChannelBody,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = revokeChannelDesc,
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
                                if (ok) channelRevokedMsg else channelRevokeFailedMsg
                            )
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.paired_devices_revoke))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingChannelRevoke = null }) {
                    Text(stringResource(R.string.paired_devices_cancel))
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
            text = stringResource(R.string.paired_devices_load_error),
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
            Text(stringResource(R.string.paired_devices_try_again))
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
            text = stringResource(R.string.paired_devices_empty_title),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.paired_devices_empty_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequestRepair) { Text(stringResource(R.string.paired_devices_pair_now)) }
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
                text = stringResource(R.string.paired_devices_header),
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
            title = { Text(stringResource(R.string.paired_devices_about_grants_title)) },
            text = {
                Text(
                    text = stringResource(R.string.paired_devices_about_grants_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { showChannelInfoSheet = false }) {
                    Text(stringResource(R.string.paired_devices_got_it))
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
                    val unnamedDevice = stringResource(R.string.paired_devices_unnamed_device)
                    Text(
                        text = device.deviceName.ifBlank { unnamedDevice },
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
                        text = stringResource(R.string.paired_devices_channel_grants),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(
                        onClick = onOpenChannelInfo,
                        modifier = Modifier.size(20.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.paired_devices_about_grants_title),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for ((channel, expiry) in device.grants.toList()
                        .sortedWith { left, right -> compareGrantNames(left.first, right.first) }) {
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
                MetaRow(label = stringResource(R.string.paired_devices_first_seen), value = formatEpoch(device.createdAt.toLong()))
            }
            if (device.lastSeen != null) {
                MetaRow(label = stringResource(R.string.paired_devices_last_seen), value = formatEpoch(device.lastSeen.toLong()))
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
                    Text(stringResource(R.string.paired_devices_extend))
                }
                OutlinedButton(
                    onClick = onRevoke,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(
                        if (isCurrent) stringResource(R.string.paired_devices_revoke_this)
                        else stringResource(R.string.paired_devices_revoke)
                    )
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
            text = stringResource(R.string.paired_devices_this_device_badge),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun ExpiryRow(expiresAt: Double?) {
    val text = when {
        expiresAt == null -> stringResource(R.string.paired_devices_never_expires)
        else -> stringResource(R.string.paired_devices_expires, formatEpoch(expiresAt.toLong()))
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
    val channelLabel = grantDisplayName(channel)
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
            text = stringResource(R.string.paired_devices_grant_chip, channelLabel, formatRelativeTtl(expiresAt, nowSec)),
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
                contentDescription = stringResource(R.string.paired_devices_revoke_channel_access, channelLabel),
                modifier = Modifier.height(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun compareGrantNames(left: String, right: String): Int {
    val byKnownOrder = grantSortKey(left).compareTo(grantSortKey(right))
    return if (byKnownOrder != 0) byKnownOrder else left.compareTo(right, ignoreCase = true)
}

private fun grantSortKey(channel: String): Int = when (channel.lowercase()) {
    "chat" -> 0
    "bridge" -> 10
    "terminal" -> 20
    "tui" -> 30
    "voice:config" -> 40
    "voice:stt" -> 41
    "voice:tts" -> 42
    else -> 100
}

@Composable
@Composable
private fun grantDisplayName(channel: String): String = when (channel.lowercase()) {
    "chat" -> stringResource(R.string.paired_devices_grant_chat)
    "bridge" -> stringResource(R.string.paired_devices_grant_bridge)
    "terminal" -> stringResource(R.string.paired_devices_grant_terminal)
    "tui" -> stringResource(R.string.paired_devices_grant_tui)
    "voice:config" -> stringResource(R.string.paired_devices_grant_voice_config)
    "voice:stt" -> stringResource(R.string.paired_devices_grant_voice_stt)
    "voice:tts" -> stringResource(R.string.paired_devices_grant_voice_tts)
    else -> channel
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
@Composable
private fun formatRelativeTtl(expiresAt: Double?, nowSec: Double): String {
    if (expiresAt == null) return stringResource(R.string.paired_devices_ttl_never)
    val deltaSec = (expiresAt - nowSec).toLong()
    if (deltaSec <= 0) return stringResource(R.string.paired_devices_ttl_expired)
    val days = deltaSec / 86_400
    val hours = deltaSec / 3_600
    val minutes = deltaSec / 60
    return when {
        days >= 1 -> stringResource(R.string.paired_devices_ttl_days, days)
        hours >= 1 -> stringResource(R.string.paired_devices_ttl_hours, hours)
        minutes >= 1 -> stringResource(R.string.paired_devices_ttl_minutes, minutes)
        else -> stringResource(R.string.paired_devices_ttl_seconds)
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
            text = stringResource(R.string.paired_devices_routes),
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
                        text = stringResource(R.string.paired_devices_active),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

