package com.hermesandroid.relay.ui.screens

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import com.hermesandroid.relay.data.FeatureFlags
import com.hermesandroid.relay.data.MediaSettings
import com.hermesandroid.relay.data.MediaSettingsRepository
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.theme.gradientBorder
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.network.ConnectionState
import com.hermesandroid.relay.data.PairingPreferences
import com.hermesandroid.relay.ui.components.ApiServerInfoSheet
import com.hermesandroid.relay.ui.components.ConnectionStatusRow
import com.hermesandroid.relay.ui.components.InsecureConnectionAckDialog
import com.hermesandroid.relay.ui.components.PairingWalkthroughDialog
import com.hermesandroid.relay.ui.components.RelayInfoSheet
import com.hermesandroid.relay.ui.components.SessionInfoSheet
import com.hermesandroid.relay.ui.components.SessionTtlPickerDialog
import com.hermesandroid.relay.ui.components.QrPairingScanner
import com.hermesandroid.relay.ui.components.HermesPairingPayload
import com.hermesandroid.relay.ui.components.StatsForNerds
import com.hermesandroid.relay.ui.components.TransportSecurityBadge
import com.hermesandroid.relay.ui.components.TransportSecuritySize
import com.hermesandroid.relay.ui.components.WhatsNewDialog
import com.hermesandroid.relay.ui.components.defaultTtlSeconds
import com.hermesandroid.relay.ui.components.isUrlSecure
import com.hermesandroid.relay.viewmodel.ConnectionViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    connectionViewModel: ConnectionViewModel,
    onNavigateToPairedDevices: () -> Unit = {},
) {
    val relayConnectionState by connectionViewModel.relayConnectionState.collectAsState()
    val authState by connectionViewModel.authState.collectAsState()
    val apiServerUrl by connectionViewModel.apiServerUrl.collectAsState()
    val apiReachable by connectionViewModel.apiServerReachable.collectAsState()
    val relayUrl by connectionViewModel.relayUrl.collectAsState()
    val pairingCode by connectionViewModel.pairingCode.collectAsState()
    val apiKeyPresent by connectionViewModel.authManager.apiKeyPresent.collectAsState()
    val theme by connectionViewModel.theme.collectAsState()
    val insecureMode by connectionViewModel.insecureMode.collectAsState()
    val isInsecureConnection by connectionViewModel.isInsecureConnection.collectAsState()
    val showThinkingSetting by connectionViewModel.showThinking.collectAsState()
    val toolDisplay by connectionViewModel.toolDisplay.collectAsState()
    val appContextEnabled by connectionViewModel.appContextEnabled.collectAsState()
    val parseToolAnnotations by connectionViewModel.parseToolAnnotations.collectAsState()
    val streamingEndpoint by connectionViewModel.streamingEndpoint.collectAsState()
    val maxAttachmentMb by connectionViewModel.maxAttachmentMb.collectAsState()
    val maxMessageLength by connectionViewModel.maxMessageLength.collectAsState()

    // Security overhaul (2026-04-11):
    val currentPairedSession by connectionViewModel.currentPairedSession.collectAsState()
    val pairedDevices by connectionViewModel.pairedDevices.collectAsState()
    val isTailscaleDetected by connectionViewModel.isTailscaleDetected.collectAsState()
    val insecureAckSeen by connectionViewModel.insecureAckSeen.collectAsState()
    val insecureReason by connectionViewModel.insecureReason.collectAsState()

    val isDarkTheme = isSystemInDarkTheme()

    var apiUrlInput by remember(apiServerUrl) { mutableStateOf(apiServerUrl) }
    var apiKeyInput by remember { mutableStateOf("") }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var relayUrlInput by remember(relayUrl) { mutableStateOf(relayUrl) }
    var isTesting by remember { mutableStateOf(false) }
    var showWhatsNew by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var showManualCodeDialog by remember { mutableStateOf(false) }
    var manualCodeInput by remember { mutableStateOf("") }
    var manualPairingInProgress by remember { mutableStateOf(false) }
    var manualPairingError by remember { mutableStateOf<String?>(null) }
    var manualPairingAttempt by remember { mutableStateOf(0) }

    // Tap-for-info sheets — mirror the chat "tap agent name" overlay
    // pattern so users can dig into what each Connection status row means.
    var showSessionInfoSheet by remember { mutableStateOf(false) }
    var showApiInfoSheet by remember { mutableStateOf(false) }
    var showRelayInfoSheet by remember { mutableStateOf(false) }

    // Full-screen guided pairing walkthrough — 4-step wizard triggered from
    // the Connection card, for users setting up from scratch.
    var showPairingWalkthrough by remember { mutableStateOf(false) }

    // Manual config: pairing code input. Relay reachability state now
    // lives on the ViewModel (relayReachableResult StateFlow) — the old
    // relayTestInProgress / relayTestResult locals were removed when Save
    // & Test was rewired to ConnectionViewModel.testRelayReachable().
    var manualConfigCodeInput by remember { mutableStateOf("") }
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Feature flags
    val devOptionsUnlocked by FeatureFlags.devOptionsUnlocked(context).collectAsState(initial = FeatureFlags.isDevBuild)
    val relayEnabled by FeatureFlags.relayEnabled(context).collectAsState(initial = FeatureFlags.isDevBuild)

    // "Stale" = we have a paired session token but the WS is not currently
    // open. Happens after backgrounding, network flaps, or manual disconnect.
    // Shown as amber "Stale — tap to reconnect" instead of red "Disconnected"
    // because the fix is a single reconnect, not a re-pair.
    val isRelayStale = authState is AuthState.Paired &&
        relayConnectionState == ConnectionState.Disconnected

    // Auto-reconnect when entering the Settings screen in a stale state.
    // Keeps 90% of users out of the contradictory "Paired + Disconnected"
    // state without requiring a manual tap.
    //
    // [isAutoReconnecting] is a screen-local gate that unifies the brief
    // "Stale → Connecting → Connected" transition into one consistent
    // "Reconnecting..." label so users don't see the row flash between
    // states. Flipped off when we either reach Connected, or after a
    // 5s timeout (at which point we fall through to the real
    // "Stale — tap to reconnect" affordance).
    var isAutoReconnecting by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        connectionViewModel.reconnectIfStale()
        kotlinx.coroutines.delay(5000)
        isAutoReconnecting = false
    }
    LaunchedEffect(relayConnectionState) {
        if (relayConnectionState == ConnectionState.Connected) {
            isAutoReconnecting = false
        }
    }

    // Connection section expand state — seeded from the current pair/reach
    // status on first composition, then driven by the user. rememberSaveable
    // preserves user intent across config changes so tapping "collapse" when
    // the connection drops doesn't re-open the card.
    val manualConfigExpandedDefault =
        !(apiReachable && (authState is AuthState.Paired || !relayEnabled))
    var manualConfigExpanded by rememberSaveable { mutableStateOf(manualConfigExpandedDefault) }
    var bridgePairingExpanded by rememberSaveable { mutableStateOf(false) }

    // Tap-to-unlock developer options (tap version 7 times)
    var versionTapCount by remember { mutableStateOf(0) }
    var lastTapTime by remember { mutableStateOf(0L) }

    // --- Security overhaul state (2026-04-11) ------------------------------
    //
    // After a successful QR parse we stash the payload and open a TTL picker
    // dialog. The picker's confirm callback then completes the pair (applies
    // the code, connects, etc.). This matches Bailey's request that the user
    // always gets a confirmation step so the trust model is explicit.
    var pendingQrPayload by remember { mutableStateOf<HermesPairingPayload?>(null) }

    // The insecure ack dialog opens the first time the user toggles insecure
    // mode on. We stash the "new state" so we can revert the toggle if they
    // cancel the dialog without picking a reason.
    var showInsecureAckDialog by remember { mutableStateOf(false) }

    // Camera permission launcher for QR scanning
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showQrScanner = true
        } else {
            Toast.makeText(context, "Camera permission needed to scan QR codes", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        TopAppBar(
            title = { Text("Settings") },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Connection section ──────────────────────────────────────────
            //
            // Merged layout as of 2026-04-11. Replaces the separate "API
            // Server", "Relay Server", and "Pairing" cards. The primary
            // card is the one-button QR pairing flow; manual configuration
            // and the Phase-3 bridge code live in collapsibles below.

            Text(
                text = "Connection",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            // Card 1 — Pair with your server (primary, always visible)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .gradientBorder(
                        shape = RoundedCornerShape(12.dp),
                        isDarkTheme = isDarkTheme
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Pair with your server",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "Scan a QR from `/hermes-relay-pair` (in any Hermes chat) or `hermes-pair` (shell). One scan configures chat and terminal.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Primary scan button — full width, dominant
                    Button(
                        onClick = {
                            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Scan Pairing QR")
                    }

                    // Secondary: guided 4-step walkthrough for users who
                    // want a stepper-driven setup (or can't scan the QR).
                    OutlinedButton(
                        onClick = { showPairingWalkthrough = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Route,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Guided setup")
                    }

                    // Tertiary: quick re-pair for power users who already
                    // have URLs set and just need to enter a fresh code.
                    TextButton(
                        onClick = {
                            manualCodeInput = ""
                            showManualCodeDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Already configured? Enter code only")
                    }

                    HorizontalDivider()

                    // Unified status summary. Each row is tappable to open a
                    // detail bottom sheet — same pattern as chat's
                    // tap-agent-name info overlay.
                    ConnectionStatusRow(
                        label = "API Server",
                        isConnected = apiReachable,
                        statusText = if (apiReachable) "Reachable" else "Unreachable",
                        onClick = { showApiInfoSheet = true },
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (relayEnabled) {
                        ConnectionStatusRow(
                            label = "Relay",
                            isConnected = relayConnectionState == ConnectionState.Connected,
                            // Treat "stale" AND the initial auto-reconnect
                            // window as the amber in-flight state so the
                            // row renders in amber instead of red — a tap
                            // will bring it back, no re-pair needed.
                            isConnecting = relayConnectionState == ConnectionState.Connecting ||
                                relayConnectionState == ConnectionState.Reconnecting ||
                                isRelayStale ||
                                isAutoReconnecting,
                            statusText = when {
                                relayConnectionState == ConnectionState.Connected -> "Connected"
                                // During the initial auto-reconnect window
                                // (screen just opened, stale session is being
                                // revived), show ONE consistent label even as
                                // the underlying state bounces from
                                // Disconnected → Connecting → Connected. Avoids
                                // the flash of "Stale — tap to reconnect"
                                // followed immediately by "Connecting..." that
                                // users were seeing on every Settings entry.
                                isAutoReconnecting && relayConnectionState != ConnectionState.Connected -> "Reconnecting..."
                                relayConnectionState == ConnectionState.Connecting -> "Connecting..."
                                relayConnectionState == ConnectionState.Reconnecting -> "Reconnecting..."
                                isRelayStale -> "Stale — tap to reconnect"
                                else -> "Disconnected"
                            },
                            onClick = {
                                // When stale, the primary affordance is
                                // reconnect. Otherwise fall through to
                                // the info bottom sheet.
                                if (isRelayStale) {
                                    connectionViewModel.connectRelay()
                                } else {
                                    showRelayInfoSheet = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        ConnectionStatusRow(
                            label = "Session",
                            isConnected = authState is AuthState.Paired,
                            isConnecting = authState is AuthState.Pairing,
                            statusText = when (authState) {
                                is AuthState.Paired -> "Paired"
                                is AuthState.Pairing -> "Pairing..."
                                is AuthState.Unpaired -> "Unpaired"
                                is AuthState.Failed -> "Failed: ${(authState as AuthState.Failed).reason}"
                            },
                            onClick = { showSessionInfoSheet = true },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Secondary actions — only shown when paired. Promotes
                    // Reconnect to primary when the session is stale so the
                    // user has an explicit button if they don't realize the
                    // status row itself is tappable.
                    if (authState is AuthState.Paired) {
                        HorizontalDivider()
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (isRelayStale) {
                                Button(
                                    onClick = { connectionViewModel.connectRelay() }
                                ) {
                                    Text("Reconnect")
                                }
                            }
                            OutlinedButton(
                                onClick = { connectionViewModel.clearSession() }
                            ) {
                                Text("Clear Session")
                            }
                            OutlinedButton(
                                onClick = {
                                    connectionViewModel.clearSession()
                                    cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                                }
                            ) {
                                Text("Re-pair")
                            }
                        }
                    }

                    // --- Security overhaul row set --------------------------
                    //
                    // Transport security badge + Tailscale chip + Paired
                    // Devices entry point. Rendered at the bottom of Card 1
                    // so they're part of the "what's my pairing posture"
                    // mental model instead of buried in manual config.

                    HorizontalDivider()

                    // Transport security badge — reflects the currently-
                    // configured relay URL, not the live connection state.
                    // Secure = wss://, insecure = ws://. Reason comes from
                    // the insecure ack dialog.
                    val transportSecure = isUrlSecure(relayUrl)
                    TransportSecurityBadge(
                        isSecure = transportSecure,
                        reason = insecureReason.ifBlank { null },
                        size = TransportSecuritySize.Row,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Tailscale detected chip — purely informational. Only
                    // shows when detected; absence implies "not on Tailscale".
                    if (isTailscaleDetected) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Shield,
                                contentDescription = null,
                                tint = Color(0xFF2E7D32),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Tailscale detected",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF2E7D32)
                            )
                        }
                    }

                    // Hardware-backed storage badge — shows the 🛡 when
                    // the session token is in StrongBox.
                    if (currentPairedSession?.hasHardwareStorage == true) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Session token stored in hardware keystore",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Paired Devices entry point. Navigates to
                    // PairedDevicesScreen which fetches GET /sessions. Shows
                    // a cached count when we've loaded the list before.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToPairedDevices() }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Paired Devices",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = if (pairedDevices.isNotEmpty()) {
                                    "${pairedDevices.size} active"
                                } else {
                                    "View and revoke paired phones"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Card 2 — Manual configuration (collapsible)
            SettingsExpandableCard(
                title = "Manual configuration",
                expanded = manualConfigExpanded,
                onToggle = { manualConfigExpanded = !manualConfigExpanded },
                isDarkTheme = isDarkTheme
            ) {
                // API Server URL
                OutlinedTextField(
                    value = apiUrlInput,
                    onValueChange = { apiUrlInput = it },
                    label = { Text("API Server URL") },
                    placeholder = { Text("http://your-server:8642") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // API Key (optional)
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = { Text("API Key (optional)") },
                    placeholder = {
                        Text(
                            if (apiKeyPresent) "•••• already set (leave blank to keep)"
                            else "Leave empty if not configured"
                        )
                    },
                    supportingText = {
                        Text(
                            if (apiKeyPresent && apiKeyInput.isBlank()) {
                                "A key is already stored — leave blank to keep it, or type to replace"
                            } else {
                                "Only needed if Hermes is configured with API_SERVER_KEY"
                            }
                        )
                    },
                    singleLine = true,
                    visualTransformation = if (apiKeyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                            Icon(
                                imageVector = if (apiKeyVisible) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = if (apiKeyVisible) "Hide" else "Show"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // Save & Test
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            connectionViewModel.updateApiServerUrl(apiUrlInput)
                            if (apiKeyInput.isNotBlank()) {
                                connectionViewModel.updateApiKey(apiKeyInput)
                            }
                            isTesting = true
                            connectionViewModel.testApiConnection { success ->
                                isTesting = false
                                Toast.makeText(
                                    context,
                                    if (success) "API server reachable" else "Cannot reach API server",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        enabled = apiUrlInput.isNotBlank() && !isTesting
                    ) {
                        Text("Save & Test")
                    }

                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }

                // Relay-only fields
                if (relayEnabled) {
                    HorizontalDivider()

                    OutlinedTextField(
                        value = relayUrlInput,
                        onValueChange = {
                            relayUrlInput = it
                            // Clear any stale Save & Test result — the
                            // previous probe was for a different URL.
                            connectionViewModel.clearRelayReachableResult()
                        },
                        label = { Text("Relay URL") },
                        placeholder = { Text("wss://your-server:8767") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Save & Test + Disconnect.
                    //
                    // The Connect button was removed in the Option B cycle
                    // — it was the source of the "try to connect while
                    // unpaired → auth fails → rate-limited for 5 min" trap.
                    // "Save & Test" persists the typed URL AND probes the
                    // relay's /health endpoint over unauthenticated HTTP,
                    // with no WSS handshake and no auth envelope. Green =
                    // the URL points at a live hermes-relay; red = doesn't.
                    // Actual WSS connect happens through the pair flow
                    // (Scan Pairing QR) or auto-reconnect, both of which
                    // already have valid pair context.
                    val relayReachable by connectionViewModel.relayReachableResult.collectAsState()
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                connectionViewModel.testRelayReachable(relayUrlInput)
                            },
                            enabled = relayUrlInput.isNotBlank() &&
                                relayReachable !is ConnectionViewModel.RelayReachable.Probing
                        ) {
                            Text("Save & Test")
                        }
                        OutlinedButton(
                            onClick = { connectionViewModel.disconnectRelay() },
                            enabled = relayConnectionState != ConnectionState.Disconnected
                        ) {
                            Text("Disconnect")
                        }
                    }

                    // Save & Test result — one of (Probing / Ok with
                    // version + client count / Fail with reason). Cleared
                    // when the user edits the URL (via the OutlinedTextField
                    // onValueChange below).
                    when (val r = relayReachable) {
                        is ConnectionViewModel.RelayReachable.Probing -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = "Probing /health…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        is ConnectionViewModel.RelayReachable.Ok -> {
                            Text(
                                text = "✓ Reachable — hermes-relay v${r.version} (${r.clients} client, ${r.sessions} session${if (r.sessions == 1) "" else "s"})",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF4CAF50)
                            )
                        }
                        is ConnectionViewModel.RelayReachable.Fail -> {
                            Text(
                                text = "✗ ${r.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        null -> { /* idle — no row */ }
                    }

                    // Insecure active warning
                    if (isInsecureConnection && relayConnectionState == ConnectionState.Connected) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Insecure connection — traffic is not encrypted",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    // Insecure mode toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Allow insecure connections",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Enable ws:// and http:// for local dev/testing only",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = insecureMode,
                            onCheckedChange = { enabled ->
                                if (enabled && !insecureAckSeen) {
                                    // First time enabling insecure mode —
                                    // show the one-time threat model dialog
                                    // so the user understands what they're
                                    // opting into. Leave the toggle OFF
                                    // until they confirm a reason.
                                    showInsecureAckDialog = true
                                } else {
                                    connectionViewModel.setInsecureMode(enabled)
                                }
                            }
                        )
                    }

                    HorizontalDivider()

                    // Direct pairing — enter the server-issued code and pair
                    // without going through the dialog/walkthrough. The same
                    // applyServerIssuedCodeAndReset path; just inline here
                    // for power users who already have everything set.
                    Text(
                        text = "Pairing code",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = manualConfigCodeInput,
                        onValueChange = { manualConfigCodeInput = it.uppercase() },
                        label = { Text("Code from hermes-pair / /hermes-relay-pair") },
                        placeholder = { Text("e.g. ABC123") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Done,
                            autoCorrectEnabled = false
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    val trimmedManualCode = manualConfigCodeInput.trim().uppercase()
                    val manualCodeValid = trimmedManualCode.length in 4..12 &&
                        trimmedManualCode.all { it.isLetterOrDigit() }

                    Button(
                        onClick = {
                            connectionViewModel.authManager
                                .applyServerIssuedCodeAndReset(trimmedManualCode)
                            connectionViewModel.disconnectRelay()
                            connectionViewModel.connectRelay(relayUrlInput)
                            Toast.makeText(
                                context,
                                "Pairing — check Session status",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        enabled = manualCodeValid && relayUrlInput.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Pair now")
                    }
                }
            }

            // Card 3 — Bridge pairing code (collapsible, relay-gated)
            if (relayEnabled) {
                SettingsExpandableCard(
                    title = "Bridge pairing code",
                    expanded = bridgePairingExpanded,
                    onToggle = { bridgePairingExpanded = !bridgePairingExpanded },
                    isDarkTheme = isDarkTheme
                ) {
                    Text(
                        text = "For Phase 3 bridge feature — the host approves this code to enable Android tool control. Not used for initial pairing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = "Pairing Code",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = pairingCode,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = MaterialTheme.typography.headlineMedium.fontSize * 0.15
                            ),
                            color = MaterialTheme.colorScheme.onSurface
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
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = "Copy pairing code"
                            )
                        }

                        IconButton(onClick = { connectionViewModel.regeneratePairingCode() }) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Generate new code"
                            )
                        }
                    }
                }
            }
            // ── end Connection section ──────────────────────────────────────

            // Chat section
            Text(
                text = "Chat",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .gradientBorder(
                        shape = RoundedCornerShape(12.dp),
                        isDarkTheme = isDarkTheme
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Show reasoning toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Show reasoning",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Display the AI's thinking process above responses",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = showThinkingSetting,
                            onCheckedChange = { connectionViewModel.setShowThinking(it) }
                        )
                    }

                    HorizontalDivider()

                    // Smooth auto-scroll toggle
                    val smoothAutoScroll by connectionViewModel.smoothAutoScroll.collectAsState()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Smooth auto-scroll",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Follow new messages while streaming. Scroll up to pause; tap the arrow or scroll back to the bottom to resume.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = smoothAutoScroll,
                            onCheckedChange = { connectionViewModel.setSmoothAutoScroll(it) }
                        )
                    }

                    HorizontalDivider()

                    // Tool call display mode
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Tool call display",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "How tool calls (file reads, searches, etc.) appear in chat",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        val toolDisplayOptions = listOf("off", "compact", "detailed")
                        val toolDisplayLabels = listOf("Off", "Compact", "Detailed")
                        val selectedToolIndex = toolDisplayOptions.indexOf(toolDisplay).coerceAtLeast(0)

                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            toolDisplayOptions.forEachIndexed { index, option ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = toolDisplayOptions.size
                                    ),
                                    onClick = { connectionViewModel.setToolDisplay(option) },
                                    selected = index == selectedToolIndex
                                ) {
                                    Text(toolDisplayLabels[index])
                                }
                            }
                        }
                    }

                    HorizontalDivider()

                    // App context prompt toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "App context prompt",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Tell the agent you're on mobile for concise responses",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = appContextEnabled,
                            onCheckedChange = { connectionViewModel.setAppContext(it) }
                        )
                    }

                    HorizontalDivider()

                    // Parse tool annotations toggle (Sessions mode only)
                    val isSessionsMode = streamingEndpoint == "sessions"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (!isSessionsMode) Modifier.alpha(0.5f) else Modifier),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Parse tool annotations",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "Experimental",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiary,
                                    modifier = Modifier
                                        .background(
                                            color = MaterialTheme.colorScheme.tertiary,
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                text = if (isSessionsMode) {
                                    "Detect tool usage from text markers. May delay message display until stream completes."
                                } else {
                                    "Only available in Sessions mode — Runs already provides structured tool events"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = parseToolAnnotations && isSessionsMode,
                            onCheckedChange = { connectionViewModel.setParseToolAnnotations(it) },
                            enabled = isSessionsMode
                        )
                    }

                    HorizontalDivider()

                    // Streaming endpoint selector
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Streaming endpoint",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Sessions: tool calls shown as inline text annotations. Runs: structured tool events with real-time progress cards.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        val endpointOptions = listOf("sessions", "runs")
                        val endpointLabels = listOf("Sessions", "Runs")
                        val selectedEndpointIndex = endpointOptions.indexOf(streamingEndpoint).coerceAtLeast(0)

                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            endpointOptions.forEachIndexed { index, option ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = endpointOptions.size
                                    ),
                                    onClick = { connectionViewModel.setStreamingEndpoint(option) },
                                    selected = index == selectedEndpointIndex
                                ) {
                                    Text(endpointLabels[index])
                                }
                            }
                        }
                    }

                    HorizontalDivider()

                    // Limits — expandable
                    var limitsExpanded by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { limitsExpanded = !limitsExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Limits",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Icon(
                            imageVector = if (limitsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (limitsExpanded) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    AnimatedVisibility(visible = limitsExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Max attachment size
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Max attachment size",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = "${maxAttachmentMb} MB",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                val attachmentOptions = listOf(1, 5, 10, 25, 50)
                                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                    attachmentOptions.forEachIndexed { index, mb ->
                                        SegmentedButton(
                                            shape = SegmentedButtonDefaults.itemShape(
                                                index = index,
                                                count = attachmentOptions.size
                                            ),
                                            onClick = { connectionViewModel.setMaxAttachmentMb(mb) },
                                            selected = mb == maxAttachmentMb
                                        ) {
                                            Text("${mb}MB", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }

                            // Max message length
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Max message length",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = "${maxMessageLength} chars",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                val lengthOptions = listOf(1024, 2048, 4096, 8192, 16384)
                                val lengthLabels = listOf("1K", "2K", "4K", "8K", "16K")
                                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                    lengthOptions.forEachIndexed { index, len ->
                                        SegmentedButton(
                                            shape = SegmentedButtonDefaults.itemShape(
                                                index = index,
                                                count = lengthOptions.size
                                            ),
                                            onClick = { connectionViewModel.setMaxMessageLength(len) },
                                            selected = len == maxMessageLength
                                        ) {
                                            Text(lengthLabels[index], style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Inbound media section ───────────────────────────────────────
            //
            // Controls how the app handles files fetched from the relay when
            // the agent emits `MEDIA:hermes-relay://<token>` markers (e.g.
            // screenshots, audio transcripts, generated docs). All four knobs
            // live in MediaSettingsRepository.
            InboundMediaSection(connectionViewModel = connectionViewModel)

            // Theme section
            Text(
                text = "Appearance",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .gradientBorder(
                        shape = RoundedCornerShape(12.dp),
                        isDarkTheme = isDarkTheme
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Theme",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val themeOptions = listOf("auto", "light", "dark")
                    val themeLabels = listOf("Auto", "Light", "Dark")
                    val selectedIndex = themeOptions.indexOf(theme).coerceAtLeast(0)

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        themeOptions.forEachIndexed { index, option ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = themeOptions.size
                                ),
                                onClick = { connectionViewModel.setTheme(option) },
                                selected = index == selectedIndex
                            ) {
                                Text(themeLabels[index])
                            }
                        }
                    }
                }
            }

            // Animation section
            Text(
                text = "Animation",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .gradientBorder(
                        shape = RoundedCornerShape(12.dp),
                        isDarkTheme = isDarkTheme
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                val animEnabled by connectionViewModel.animationEnabled.collectAsState()
                val animBehindChat by connectionViewModel.animationBehindChat.collectAsState()

                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Animation enabled toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "ASCII sphere",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Show animated sphere on empty chat screen and ambient mode",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = animEnabled,
                            onCheckedChange = { connectionViewModel.setAnimationEnabled(it) }
                        )
                    }

                    HorizontalDivider()

                    // Behind chat toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (!animEnabled) Modifier.alpha(0.5f) else Modifier),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Behind messages",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Show subtle sphere animation behind chat messages",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = animBehindChat && animEnabled,
                            onCheckedChange = { connectionViewModel.setAnimationBehindChat(it) },
                            enabled = animEnabled
                        )
                    }
                }
            }

            // Data Management section
            Text(
                text = "Data",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            DataManagementSection(connectionViewModel = connectionViewModel)

            // Stats for Nerds section
            Text(
                text = "Stats for Nerds",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            StatsForNerds()

            // About section
            Text(
                text = "About",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .gradientBorder(
                        shape = RoundedCornerShape(12.dp),
                        isDarkTheme = isDarkTheme
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Logo
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF1A1A2E)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = "Hermes-Relay",
                            modifier = Modifier.size(80.dp)
                        )
                    }

                    Text(
                        text = "Hermes-Relay",
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Native Android client for Hermes Agent",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Version info (dynamic)
                    val versionName = remember {
                        try {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"
                        } catch (_: Exception) { "—" }
                    }
                    val versionCode = remember {
                        try {
                            @Suppress("DEPRECATION")
                            context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toString()
                        } catch (_: Exception) { "—" }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (devOptionsUnlocked) return@clickable
                                val now = System.currentTimeMillis()
                                if (now - lastTapTime > 2000) {
                                    versionTapCount = 1
                                } else {
                                    versionTapCount++
                                }
                                lastTapTime = now
                                val remaining = 7 - versionTapCount
                                when {
                                    remaining <= 0 -> {
                                        scope.launch { FeatureFlags.unlockDevOptions(context) }
                                        Toast.makeText(context, "Developer options unlocked", Toast.LENGTH_SHORT).show()
                                        versionTapCount = 0
                                    }
                                    remaining <= 3 -> {
                                        Toast.makeText(context, "$remaining taps to unlock developer options", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Version",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "$versionName ($versionCode)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    HorizontalDivider()

                    // Links
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Codename-11/hermes-relay"))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_github),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("GitHub")
                        }
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://codename-11.github.io/hermes-relay/"))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("App Docs")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://hermes-agent.nousresearch.com"))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Code,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Hermes Docs")
                        }
                    }

                    // Privacy policy link
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Codename-11/hermes-relay/blob/main/docs/privacy.md"))
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Shield,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Privacy Policy")
                    }

                    // What's New
                    TextButton(onClick = { showWhatsNew = true }) {
                        Text("What's New in This Version")
                    }

                    // Credits
                    Text(
                        text = "Axiom Labs \u2764\uFE0F Hermes Agent · Nous Research",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Developer Options section (only visible when unlocked)
            if (devOptionsUnlocked) {
                Text(
                    text = "Developer Options",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.tertiary
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .gradientBorder(
                            shape = RoundedCornerShape(12.dp),
                            isDarkTheme = isDarkTheme
                        ),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Relay features toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Science,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.tertiary
                                    )
                                    Text(
                                        text = "Relay features",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                Text(
                                    text = "Show Relay Server and Pairing settings for Bridge/Terminal development",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = relayEnabled,
                                onCheckedChange = { scope.launch { FeatureFlags.setRelayEnabled(context, it) } }
                            )
                        }

                        HorizontalDivider()

                        // Lock developer options
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Lock developer options",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "Hide this section and disable experimental features",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = {
                                scope.launch { FeatureFlags.lockDevOptions(context) }
                                Toast.makeText(context, "Developer options locked", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.Lock,
                                    contentDescription = "Lock developer options"
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // What's New dialog
    if (showWhatsNew) {
        WhatsNewDialog(onDismiss = { showWhatsNew = false })
    }

    // Guided pairing walkthrough — full-screen 4-step wizard
    if (showPairingWalkthrough) {
        PairingWalkthroughDialog(
            connectionViewModel = connectionViewModel,
            onDismiss = { showPairingWalkthrough = false }
        )
    }

    // Connection info bottom sheets — tap-to-reveal details for each row
    // in Settings → Connection. Mirrors the chat tap-agent-name overlay
    // pattern.
    if (showSessionInfoSheet) {
        SessionInfoSheet(
            connectionViewModel = connectionViewModel,
            onDismiss = { showSessionInfoSheet = false }
        )
    }
    if (showApiInfoSheet) {
        ApiServerInfoSheet(
            connectionViewModel = connectionViewModel,
            onDismiss = { showApiInfoSheet = false }
        )
    }
    if (showRelayInfoSheet) {
        RelayInfoSheet(
            connectionViewModel = connectionViewModel,
            onDismiss = { showRelayInfoSheet = false }
        )
    }

    // Manual pairing-code entry dialog — fallback for users who can't scan
    // the QR (headless server, tiny terminal, bad lighting). Mirrors the QR
    // scanner's applyServerIssuedCode path; URLs are still configured via
    // Manual configuration below. Tracks pairing progress/error inline so
    // the user gets clear feedback without having to scroll to the Session
    // status row.
    if (showManualCodeDialog) {
        val relayUrlBlank = relayUrlInput.isBlank()
        val trimmedCode = manualCodeInput.trim().uppercase()
        val codeValid = trimmedCode.length in 4..12 &&
            trimmedCode.all { it.isLetterOrDigit() }
        val canSubmit = codeValid && !relayUrlBlank && !manualPairingInProgress

        val focusRequester = remember { FocusRequester() }
        val haptic = LocalHapticFeedback.current

        // Auto-focus the code field the moment the dialog opens so the soft
        // keyboard pops immediately. One-shot per open — re-opens will get
        // a fresh FocusRequester via remember.
        LaunchedEffect(showManualCodeDialog) {
            focusRequester.requestFocus()
        }

        // Extract "host:port" from the relay URL for a compact display —
        // the full wss://foo.example.com:8767/ws wraps awkwardly in the
        // dialog's narrow text area.
        val relayHostLabel = remember(relayUrlInput) {
            try {
                val uri = java.net.URI(relayUrlInput.trim())
                val host = uri.host ?: relayUrlInput
                if (uri.port > 0) "$host:${uri.port}" else host
            } catch (_: Exception) {
                relayUrlInput
            }
        }

        // Shared submit action — used by both the Pair button and the IME
        // Go action so "Done" on the keyboard submits like tapping Pair.
        val submitPairing = {
            manualPairingError = null
            manualPairingInProgress = true
            manualPairingAttempt += 1
            // Atomic reset avoids the race between clearSession's async
            // code regeneration and applyServerIssuedCode's mirror write,
            // and forces authenticate() to use the new code instead of a
            // stale session token.
            connectionViewModel.authManager
                .applyServerIssuedCodeAndReset(trimmedCode)
            connectionViewModel.disconnectRelay()
            connectionViewModel.connectRelay(relayUrlInput)
        }

        // Observe the authState transition after each Pair attempt. Keyed on
        // the attempt counter so retrying cancels and restarts the watcher.
        LaunchedEffect(manualPairingAttempt) {
            if (manualPairingAttempt == 0) return@LaunchedEffect
            try {
                val terminal = withTimeout(15_000) {
                    connectionViewModel.authState.first {
                        it is AuthState.Paired || it is AuthState.Failed
                    }
                }
                when (terminal) {
                    is AuthState.Paired -> {
                        manualPairingInProgress = false
                        manualPairingError = null
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showManualCodeDialog = false
                        Toast.makeText(
                            context,
                            "Paired successfully",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    is AuthState.Failed -> {
                        manualPairingInProgress = false
                        manualPairingError = "Server rejected code: ${terminal.reason}"
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    else -> {
                        manualPairingInProgress = false
                    }
                }
            } catch (_: TimeoutCancellationException) {
                manualPairingInProgress = false
                manualPairingError =
                    "No response from relay. Check the Relay URL and that the server is running."
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            } catch (e: Exception) {
                manualPairingInProgress = false
                manualPairingError = "Error: ${e.message ?: "unknown"}"
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }

        AlertDialog(
            onDismissRequest = {
                if (!manualPairingInProgress) showManualCodeDialog = false
            },
            title = { Text("Enter pairing code") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Type the code printed by `hermes-pair` or `/hermes-relay-pair`.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = manualCodeInput,
                        onValueChange = {
                            manualCodeInput = it.uppercase()
                            // Clear stale error as soon as the user edits
                            if (manualPairingError != null) manualPairingError = null
                        },
                        label = { Text("Pairing code") },
                        placeholder = { Text("e.g. ABC123") },
                        singleLine = true,
                        enabled = !manualPairingInProgress,
                        isError = manualPairingError != null,
                        textStyle = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = MaterialTheme.typography.headlineSmall.fontSize * 0.15
                        ),
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Go,
                            autoCorrectEnabled = false
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = { if (canSubmit) submitPairing() }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )

                    when {
                        manualPairingError != null -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = manualPairingError!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        manualPairingInProgress -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = "Connecting to relay and pairing…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        relayUrlBlank -> {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Relay URL not set. Configure it first, then come back.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                TextButton(
                                    onClick = {
                                        showManualCodeDialog = false
                                        manualConfigExpanded = true
                                    },
                                    contentPadding = PaddingValues(horizontal = 0.dp)
                                ) {
                                    Text("Open Manual configuration")
                                }
                            }
                        }
                        else -> {
                            Text(
                                text = "Will connect to: $relayHostLabel",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = canSubmit,
                    onClick = { submitPairing() }
                ) {
                    Text(
                        if (manualPairingError != null) "Retry"
                        else if (manualPairingInProgress) "Pairing…"
                        else "Pair"
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showManualCodeDialog = false },
                    enabled = !manualPairingInProgress
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // QR Scanner overlay
    //
    // On successful parse we stash the payload and open the TTL picker —
    // the picker's confirm callback completes the pair. This ensures the
    // user always gets an explicit "Keep this pairing for…" confirmation
    // step so the trust model (who has access, for how long) is visible.
    if (showQrScanner) {
        QrPairingScanner(
            onPairingDetected = { payload ->
                showQrScanner = false
                pendingQrPayload = payload
            },
            onDismiss = { showQrScanner = false }
        )
    }

    // TTL picker — opens when pendingQrPayload becomes non-null. Confirm
    // persists URLs/keys/code and kicks the relay connect; cancel drops the
    // payload and leaves the user on Settings unchanged.
    pendingQrPayload?.let { payload ->
        val defaultTtl = defaultTtlSeconds(
            qrTtlSeconds = payload.relay?.ttlSeconds,
            transportHint = payload.relay?.transportHint,
            isTailscaleDetected = isTailscaleDetected,
        )
        // Previously-selected TTL from DataStore (seeded from the default).
        // collectAsState with a default so we don't block composition while
        // DataStore spins up its first emit.
        val persistedTtl by PairingPreferences.pairTtlSeconds(context)
            .collectAsState(initial = PairingPreferences.DEFAULT_TTL_SECONDS)
        val initialTtl = if (payload.relay?.ttlSeconds != null) defaultTtl else persistedTtl

        SessionTtlPickerDialog(
            initialTtlSeconds = initialTtl,
            isTailscaleDetected = isTailscaleDetected,
            transportHint = payload.relay?.transportHint,
            onConfirm = { ttl ->
                // Apply the scanned values
                apiUrlInput = payload.serverUrl
                apiKeyInput = payload.key
                connectionViewModel.updateApiServerUrl(payload.serverUrl)
                connectionViewModel.updateApiKey(payload.key)

                payload.relay?.let { relay ->
                    connectionViewModel.updateRelayUrl(relay.url)
                    if (relay.url.startsWith("ws://")) {
                        connectionViewModel.setInsecureMode(true)
                    }
                    // Wipe any TOFU pin for the new host + apply the code.
                    // applyServerIssuedCodeAndReset now takes the relay URL
                    // so the cert-pin store can remove its per-host entry
                    // for legitimate cert rotation.
                    connectionViewModel.authManager.applyServerIssuedCodeAndReset(
                        code = relay.code,
                        relayUrl = relay.url,
                    )
                    // Propagate per-channel grants into the next auth.
                    connectionViewModel.authManager.setPendingGrants(relay.grants)
                }
                // Stash TTL for the next authenticate() call.
                connectionViewModel.authManager.setPendingTtlSeconds(ttl)

                // Kick off the WSS handshake now — AuthManager is holding a
                // fresh server-issued code so the pair-context gate on
                // connectRelay will let it through. Without this call, the
                // Settings QR flow stashed credentials but never actually
                // opened the WSS, forcing the user to hit Reconnect
                // manually or reopen Settings to hit reconnectIfStale.
                payload.relay?.let { relay ->
                    connectionViewModel.disconnectRelay()
                    connectionViewModel.connectRelay(relay.url)
                }

                pendingQrPayload = null

                isTesting = true
                connectionViewModel.testApiConnection { success ->
                    isTesting = false
                    val msg = if (success) {
                        if (payload.relay != null)
                            "Paired — API + relay configured"
                        else
                            "Paired — server reachable"
                    } else {
                        "Paired — but server unreachable"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            },
            onCancel = { pendingQrPayload = null }
        )
    }

    // Insecure ack dialog — first time the user flips the "Allow insecure"
    // toggle on. Only gates the toggle itself; all actual pairing flows
    // trust the user's judgment.
    if (showInsecureAckDialog) {
        InsecureConnectionAckDialog(
            onConfirm = { reason ->
                connectionViewModel.setInsecureAckComplete(reason)
                connectionViewModel.setInsecureMode(true)
                showInsecureAckDialog = false
            },
            onCancel = {
                showInsecureAckDialog = false
                // Leave the toggle off — the user bailed out of the ack.
            }
        )
    }
}

@Composable
private fun DataManagementSection(connectionViewModel: ConnectionViewModel) {
    val isDarkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    var showResetDialog by remember { mutableStateOf(false) }
    var backupJson by remember { mutableStateOf<String?>(null) }

    // SAF file picker for export
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null && backupJson != null) {
            connectionViewModel.writeBackupToUri(uri, backupJson!!) { success ->
                Toast.makeText(
                    context,
                    if (success) "Settings exported" else "Export failed",
                    Toast.LENGTH_SHORT
                ).show()
                backupJson = null
            }
        }
    }

    // SAF file picker for import
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            connectionViewModel.importFromUri(uri) { success ->
                Toast.makeText(
                    context,
                    if (success) "Settings imported" else "Import failed — invalid file",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .gradientBorder(
                shape = RoundedCornerShape(12.dp),
                isDarkTheme = isDarkTheme
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Reset Onboarding
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Reset Onboarding",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Show the setup guide again on next launch",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = {
                    connectionViewModel.resetOnboarding()
                    Toast.makeText(context, "Onboarding will show on next launch", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(
                        imageVector = Icons.Filled.RestartAlt,
                        contentDescription = "Reset onboarding"
                    )
                }
            }

            HorizontalDivider()

            // Export Settings
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Export Settings",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Save settings to a file (no tokens or API keys)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = {
                    connectionViewModel.exportSettings { json ->
                        backupJson = json
                        exportLauncher.launch("hermes-relay-backup.json")
                    }
                }) {
                    Icon(
                        imageVector = Icons.Filled.FileDownload,
                        contentDescription = "Export settings"
                    )
                }
            }

            // Import Settings
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Import Settings",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Restore settings from a backup file",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = {
                    importLauncher.launch(arrayOf("application/json"))
                }) {
                    Icon(
                        imageVector = Icons.Filled.FileUpload,
                        contentDescription = "Import settings"
                    )
                }
            }

            HorizontalDivider()

            // Reset All Data
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Reset All Data",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "Clear all settings, tokens, API keys, and cached data",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { showResetDialog = true }) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Reset all data",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    // Confirmation dialog for data reset
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset All Data?") },
            text = { Text("This will clear all settings, API keys, authentication tokens, and cached data. You'll need to reconfigure your API server and re-pair with your relay. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        connectionViewModel.resetAppData()
                        Toast.makeText(context, "App data reset", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * A card with a tappable header row that reveals/hides its [content] via
 * [AnimatedVisibility]. Used for the "Manual configuration" and "Bridge
 * pairing code" sections in the Connection settings.
 *
 * [content] is a Column slot — each direct child is laid out with 12.dp
 * vertical spacing, matching the rest of SettingsScreen's card interiors.
 */
@Composable
private fun SettingsExpandableCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .gradientBorder(
                shape = RoundedCornerShape(12.dp),
                isDarkTheme = isDarkTheme
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row — always visible, tappable to toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand"
                )
            }

            // Expandable content
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * Settings section for inbound media (files fetched from the relay via
 * `MEDIA:hermes-relay://<token>` markers in tool output).
 *
 * Four user-tunable knobs backed by [MediaSettingsRepository] + a button to
 * wipe the on-disk cache. Sliders intentionally use discrete integer steps
 * so the user can't land on fractional MB values that would read awkwardly
 * in the attachment cards.
 */
@Composable
private fun InboundMediaSection(connectionViewModel: ConnectionViewModel) {
    val isDarkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = connectionViewModel.mediaSettingsRepo
    val cacheWriter = connectionViewModel.mediaCacheWriter

    val settings by repo.settings.collectAsState(
        initial = MediaSettings(
            maxInboundSizeMb = MediaSettingsRepository.DEFAULT_MAX_INBOUND_MB,
            autoFetchThresholdMb = MediaSettingsRepository.DEFAULT_AUTO_FETCH_THRESHOLD_MB,
            autoFetchOnCellular = MediaSettingsRepository.DEFAULT_AUTO_FETCH_ON_CELLULAR,
            cachedMediaCapMb = MediaSettingsRepository.DEFAULT_CACHED_MEDIA_CAP_MB
        )
    )

    var currentCacheBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        currentCacheBytes = runCatching { cacheWriter.currentSizeBytes() }.getOrDefault(0L)
    }

    Text(
        text = "Inbound media",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .gradientBorder(
                shape = RoundedCornerShape(12.dp),
                isDarkTheme = isDarkTheme
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Controls how the app handles files sent by tool results (screenshots, PDFs, etc.) over the relay.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Max inbound attachment size — 5..100 MB in 5 MB steps
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Max inbound attachment size",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "${settings.maxInboundSizeMb} MB",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = settings.maxInboundSizeMb.toFloat(),
                    onValueChange = { scope.launch { repo.setMaxInboundSize(it.toInt()) } },
                    valueRange = 5f..100f,
                    steps = 18 // (100 - 5) / 5 - 1
                )
                Text(
                    text = "Files larger than this are rejected after download.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()

            // Auto-fetch threshold — 0..50 MB
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Auto-fetch threshold",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "${settings.autoFetchThresholdMb} MB",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = settings.autoFetchThresholdMb.toFloat(),
                    onValueChange = { scope.launch { repo.setAutoFetchThreshold(it.toInt()) } },
                    valueRange = 0f..50f,
                    steps = 49
                )
                Text(
                    text = "Files at or below this size are fetched automatically on Wi-Fi.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()

            // Auto-fetch on cellular switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-fetch on cellular",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "When off, media over cellular shows a tap-to-download card instead of auto-downloading.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.autoFetchOnCellular,
                    onCheckedChange = { scope.launch { repo.setAutoFetchOnCellular(it) } }
                )
            }

            HorizontalDivider()

            // Cached media cap — 50..500 MB in 25 MB steps
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Cached media cap",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "${settings.cachedMediaCapMb} MB",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = settings.cachedMediaCapMb.toFloat(),
                    onValueChange = { scope.launch { repo.setCachedMediaCap(it.toInt()) } },
                    valueRange = 50f..500f,
                    steps = 17 // (500 - 50) / 25 - 1
                )
                Text(
                    text = "Oldest files are evicted when the cache exceeds this limit.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()

            // Clear cache button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Clear cached media",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Currently using ${formatBytesHuman(currentCacheBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val freed = runCatching { cacheWriter.clear() }.getOrDefault(0L)
                            currentCacheBytes = runCatching { cacheWriter.currentSizeBytes() }.getOrDefault(0L)
                            Toast.makeText(
                                context,
                                "Freed ${formatBytesHuman(freed)}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                ) {
                    Text("Clear")
                }
            }
        }
    }
}

/**
 * Human-readable byte count for the inbound-media Settings UI. Keeps the
 * logic out of the composable so it stays cheap on recomposition.
 */
private fun formatBytesHuman(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> "%.2f GB".format(gb)
        mb >= 1.0 -> "%.1f MB".format(mb)
        kb >= 1.0 -> "%.0f KB".format(kb)
        else -> "$bytes B"
    }
}
