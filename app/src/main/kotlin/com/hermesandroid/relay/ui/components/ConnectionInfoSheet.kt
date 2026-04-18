package com.hermesandroid.relay.ui.components

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.data.FeatureFlags
import com.hermesandroid.relay.network.ConnectionState
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    monospace: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            fontFamily = if (monospace) FontFamily.Monospace else null
        )
    }
}

@Composable
private fun StatusChip(text: String, background: Color, contentColor: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = contentColor,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun ChipRow(label: String, chip: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        chip()
    }
}

@Composable
private fun connectionChip(state: ConnectionState) {
    val (label, bg, fg) = when (state) {
        ConnectionState.Connected -> Triple(
            "Connected",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        ConnectionState.Connecting -> Triple(
            "Connecting\u2026",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        ConnectionState.Reconnecting -> Triple(
            "Reconnecting\u2026",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        ConnectionState.Disconnected -> Triple(
            "Disconnected",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    StatusChip(text = label, background = bg, contentColor = fg)
}

@Composable
private fun authStateChip(state: AuthState) {
    val (label, bg, fg) = when (state) {
        is AuthState.Unpaired -> Triple(
            "Unpaired",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        is AuthState.Pairing -> Triple(
            "Pairing\u2026",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        is AuthState.Paired -> Triple(
            "Paired",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        is AuthState.Failed -> Triple(
            "Failed: ${state.reason}",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
    }
    StatusChip(text = label, background = bg, contentColor = fg)
}

// ---------------------------------------------------------------------------
// 1. SessionInfoSheet
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionInfoSheet(
    connectionViewModel: ConnectionViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current

    val authState by connectionViewModel.authState.collectAsState()
    val relayUrl by connectionViewModel.relayUrl.collectAsState()
    val relayConnectionState by connectionViewModel.relayConnectionState.collectAsState()
    val pairingCode by connectionViewModel.pairingCode.collectAsState()
    // Note: this is the list of server-issued *session labels* pulled from
    // the `auth.ok` payload (e.g. `["chat", "terminal"]`) — not the list of
    // multi-profile connection profiles. The field was renamed on AuthManager
    // to avoid that exact confusion; the local is kept as `sessionLabels`.
    val sessionLabels by connectionViewModel.authManager.sessionLabels.collectAsState()
    val pairedSession by connectionViewModel.currentPairedSession.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Session",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = "Session authenticates the app with the relay server. " +
                    "The pairing code is consumed once, then the server issues a " +
                    "long-lived token stored locally.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            ChipRow(label = "State") { authStateChip(authState) }

            InfoRow(label = "Relay URL", value = relayUrl, monospace = true)

            ChipRow(label = "Connection") { connectionChip(relayConnectionState) }

            HorizontalDivider()

            // Pairing code — big monospace + copy
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Pairing code",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = pairingCode,
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(
                                    ClipData.newPlainText("Pairing code", pairingCode)
                                )
                            )
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy pairing code"
                        )
                    }
                }
            }

            HorizontalDivider()

            InfoRow(
                label = "Device",
                value = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
            )

            InfoRow(
                label = "Session token present",
                value = if (authState is AuthState.Paired) "Yes" else "No"
            )

            InfoRow(
                label = "Session labels",
                value = if (sessionLabels.isEmpty()) "(none)" else sessionLabels.joinToString(", ")
            )

            // Security overhaul (2026-04-11) — show expiry + grants + storage.
            pairedSession?.let { paired ->
                HorizontalDivider()
                val expiryLabel = when {
                    paired.expiresAt == null -> "Never"
                    else -> java.text.DateFormat
                        .getDateInstance(java.text.DateFormat.MEDIUM)
                        .format(java.util.Date(paired.expiresAt * 1000L))
                }
                InfoRow(label = "Expires", value = expiryLabel)

                if (paired.grants.isNotEmpty()) {
                    val grantsLabel = paired.grants.entries.joinToString(", ") { (k, v) ->
                        if (v == null) "$k: never" else k
                    }
                    InfoRow(label = "Channel grants", value = grantsLabel)
                }

                val transportLabel = paired.transportHint?.uppercase() ?: "—"
                InfoRow(label = "Transport", value = transportLabel)

                InfoRow(
                    label = "Key storage",
                    value = if (paired.hasHardwareStorage) "Hardware (StrongBox)" else "Hardware (TEE)"
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        connectionViewModel.clearSession()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(0.5f)
                ) {
                    Text("Clear Session")
                }
                OutlinedButton(
                    onClick = { connectionViewModel.regeneratePairingCode() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Regenerate Code")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 2. ApiServerInfoSheet
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiServerInfoSheet(
    connectionViewModel: ConnectionViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    val apiServerUrl by connectionViewModel.apiServerUrl.collectAsState()
    val apiServerReachable by connectionViewModel.apiServerReachable.collectAsState()
    val chatMode by connectionViewModel.chatMode.collectAsState()
    val streamingEndpoint by connectionViewModel.streamingEndpoint.collectAsState()

    // Reactive flag from AuthManager — updated whenever setApiKey/clearApiKey
    // runs and seeded from stored prefs on init.
    val apiKeyPresent by connectionViewModel.authManager.apiKeyPresent.collectAsState()

    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "API server",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = "Chat traffic goes directly from the app to the Hermes " +
                    "API server over HTTP/SSE. The relay is only involved for " +
                    "terminal and bridge channels.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            InfoRow(label = "URL", value = apiServerUrl, monospace = true)

            ChipRow(label = "Reachable") {
                val (label, bg, fg) = if (apiServerReachable) {
                    Triple(
                        "Yes",
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else {
                    Triple(
                        "No",
                        MaterialTheme.colorScheme.errorContainer,
                        MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                StatusChip(text = label, background = bg, contentColor = fg)
            }

            InfoRow(label = "Streaming mode", value = chatMode.toString())
            InfoRow(label = "Endpoint preference", value = streamingEndpoint)
            InfoRow(
                label = "API key set",
                value = if (apiKeyPresent) "Yes (hidden)" else "No"
            )
            InfoRow(
                label = "Last health check",
                value = if (apiServerReachable) "Just now (ok)" else "Just now (failed)"
            )

            if (testResult != null) {
                Text(
                    text = testResult!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            OutlinedButton(
                onClick = {
                    testing = true
                    testResult = "Testing\u2026"
                    connectionViewModel.testApiConnection { success ->
                        testing = false
                        testResult = if (success) "Connection OK" else "Connection failed"
                    }
                },
                enabled = !testing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (testing) "Testing\u2026" else "Test connection")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 3. RelayInfoSheet
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayInfoSheet(
    connectionViewModel: ConnectionViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val context = LocalContext.current

    val relayUrl by connectionViewModel.relayUrl.collectAsState()
    val relayConnectionState by connectionViewModel.relayConnectionState.collectAsState()
    val isInsecureConnection by connectionViewModel.isInsecureConnection.collectAsState()
    val insecureMode by connectionViewModel.insecureMode.collectAsState()
    val relayEnabled by FeatureFlags.relayEnabled(context)
        .collectAsState(initial = FeatureFlags.isDevBuild)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Relay",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = "The relay is a WebSocket server that brokers terminal " +
                    "and bridge channels between the app and the host. Connect " +
                    "automatically after successful pairing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            InfoRow(label = "URL", value = relayUrl, monospace = true)
            ChipRow(label = "Connection state") { connectionChip(relayConnectionState) }

            if (isInsecureConnection) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = "ws:// \u2014 traffic not encrypted",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            InfoRow(
                label = "Insecure mode allowed",
                value = if (insecureMode) "Yes" else "No"
            )
            InfoRow(
                label = "Relay enabled (feature flag)",
                value = if (relayEnabled) "Yes" else "No"
            )

            Spacer(modifier = Modifier.height(4.dp))

            val isConnected = relayConnectionState == ConnectionState.Connected ||
                relayConnectionState == ConnectionState.Connecting ||
                relayConnectionState == ConnectionState.Reconnecting

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { connectionViewModel.connectRelay() },
                    enabled = relayEnabled && !isConnected,
                    modifier = Modifier.fillMaxWidth(0.5f)
                ) {
                    Text("Connect")
                }
                OutlinedButton(
                    onClick = { connectionViewModel.disconnectRelay() },
                    enabled = relayEnabled && isConnected,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Disconnect")
                }
            }
        }
    }
}
