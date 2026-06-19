package com.hermesandroid.relay.ui.components

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.hasSecureProxy
import com.hermesandroid.relay.network.relay.ConnectionState
import com.hermesandroid.relay.network.relay.RelayUrlDeriver
import com.hermesandroid.relay.ui.LocalSnackbarHost
import com.hermesandroid.relay.ui.showHumanError
import com.hermesandroid.relay.util.classifyError
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.viewmodel.RelayUiState
import com.hermesandroid.relay.viewmodel.StandardVoiceAvailability
import com.hermesandroid.relay.viewmodel.asBadgeState
import com.hermesandroid.relay.viewmodel.statusText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ──────────────────────────────────────────────────────────────────────
 * Active-connection card body sections — the "what was Settings →
 * Connection" feature set, now living inline on the active `ConnectionCard`
 * inside [com.hermesandroid.relay.ui.screens.ConnectionsSettingsScreen].
 *
 * The per-card action row (Reconnect/Rename/Re-pair/Revoke/Remove) stays
 * on `ConnectionCard` itself. Everything below the first divider — status
 * rows, endpoints expander, advanced expander (manual URL, insecure
 * toggle, manual pairing code), and the security-posture strip — is
 * extracted here so `ConnectionsSettingsScreen` stays focused on the list
 * layout and this file owns the active-card deep content.
 *
 * All composables in this file assume they render INSIDE an active
 * `ConnectionCard`'s Column (16dp padding, 8dp vertical spacing). None of
 * them introduce a new Card wrapper or scroll container.
 *
 * Call sites pass a non-null `connectionViewModel` — these sections are
 * never rendered for non-active cards, so the VM guard happens at the
 * call site.
 * ──────────────────────────────────────────────────────────────────────
 */

/**
 * Standard Hermes status rows (API / Dashboard). Dashboard auth is surfaced
 * here so users do not have to open Manage just to discover sign-in is needed.
 */
@Composable
fun ActiveCardStandardStatusSection(
    connectionViewModel: ConnectionViewModel,
    onOpenApiInfo: () -> Unit,
    onOpenDashboard: () -> Unit,
) {
    val apiReachable by connectionViewModel.apiServerReachable.collectAsState()
    val apiHealth by connectionViewModel.apiServerHealth.collectAsState()
    val activeConnection by connectionViewModel.activeConnection.collectAsState()
    val dashboardStatus = activeConnection?.dashboardLastStatus
    val dashboardSignInRequired =
        dashboardStatus?.authRequired == true && dashboardStatus.authenticated != true

    ConnectionStatusRow(
        label = "API Server",
        isConnected = apiReachable,
        isProbing = apiHealth == ConnectionViewModel.HealthStatus.Probing,
        statusText = when {
            apiHealth == ConnectionViewModel.HealthStatus.Probing -> "Checking…"
            apiReachable -> "Reachable"
            else -> "Unreachable"
        },
        onClick = onOpenApiInfo,
        modifier = Modifier.fillMaxWidth(),
    )

    ConnectionStatusRow(
        label = "Dashboard",
        isConnected = dashboardStatus?.reachable == true && !dashboardSignInRequired,
        statusText = when {
            activeConnection?.resolvedDashboardUrl.isNullOrBlank() -> "Not configured"
            dashboardStatus == null -> "Not checked"
            !dashboardStatus.reachable -> "Unreachable"
            dashboardSignInRequired -> "Sign-in required"
            dashboardStatus.authenticated == true -> "Signed in"
            dashboardStatus.authRequired == false -> "Available"
            else -> "Available"
        },
        onClick = onOpenDashboard,
        modifier = Modifier.fillMaxWidth(),
    )

    if (dashboardSignInRequired) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Dashboard controls need a sign-in for this route.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenDashboard) {
                Text("Sign in")
            }
        }
    }
}

/**
 * Optional Relay status rows (transport / paired session). Kept separate from
 * [ActiveCardStandardStatusSection] so API/dashboard setup does not visually
 * read as incomplete when Relay is not paired.
 */
@Composable
fun ActiveCardRelayStatusSection(
    connectionViewModel: ConnectionViewModel,
    onOpenRelayInfo: () -> Unit,
    onOpenSessionInfo: () -> Unit,
) {
    val context = LocalContext.current

    val authState by connectionViewModel.authState.collectAsState()
    val relayUiState by connectionViewModel.relayUiState.collectAsState()
    val relayRowState by connectionViewModel.relayRowState.collectAsState()

    // ADR 24: relayRowState carries both the phase and the active endpoint
    // role. statusText appends " · <Role>" when the resolver has picked one.
    ConnectionStatusRow(
        label = "Relay",
        state = relayRowState.asBadgeState(),
        statusText = relayRowState.statusText(connectedLabel = "Connected"),
        onClick = {
            if (relayUiState == RelayUiState.Stale) {
                connectionViewModel.connectRelay()
                Toast.makeText(
                    context,
                    "Reconnecting to relay…",
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                onOpenRelayInfo()
            }
        },
        modifier = Modifier.fillMaxWidth(),
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
        onClick = onOpenSessionInfo,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Capability overview for the active connection. Features are intentionally
 * separate from routes: users can see what Hermes can do without reading the
 * selected network path as the feature boundary.
 */
@Composable
fun ActiveCardFeaturesSection(
    connectionViewModel: ConnectionViewModel,
    relayEnabled: Boolean,
    onOpenApiInfo: () -> Unit,
    onOpenDashboard: () -> Unit,
    onOpenRelayInfo: () -> Unit,
    onOpenSessionInfo: () -> Unit,
) {
    val apiReachable by connectionViewModel.apiServerReachable.collectAsState()
    val apiHealth by connectionViewModel.apiServerHealth.collectAsState()
    val activeConnection by connectionViewModel.activeConnection.collectAsState()
    val standardVoiceAvailability by
        connectionViewModel.standardVoiceAvailability.collectAsState()
    val relayConfigured by connectionViewModel.relayConfigured.collectAsState()
    val relayReady by connectionViewModel.relayReady.collectAsState()
    val relayUiState by connectionViewModel.relayUiState.collectAsState()
    val authState by connectionViewModel.authState.collectAsState()

    val dashboardStatus = activeConnection?.dashboardLastStatus
    val dashboardSignInRequired =
        dashboardStatus?.authRequired == true && dashboardStatus.authenticated != true
    val secureProxyAdvertised =
        activeConnection?.routeCandidates.orEmpty().any { it.hasSecureProxy() }

    val apiValue = when {
        apiHealth == ConnectionViewModel.HealthStatus.Probing -> "Checking"
        apiReachable -> "Ready"
        activeConnection?.apiServerUrl.isNullOrBlank() -> "Missing"
        else -> "Offline"
    }
    val apiTone = when (apiValue) {
        "Ready" -> CapabilityTone.Good
        "Offline", "Missing" -> CapabilityTone.Warning
        else -> CapabilityTone.Neutral
    }

    val dashboardValue = when {
        activeConnection?.resolvedDashboardUrl.isNullOrBlank() -> "Missing"
        dashboardStatus == null -> "Unchecked"
        !dashboardStatus.reachable -> "Offline"
        dashboardSignInRequired -> "Sign in"
        dashboardStatus.authenticated == true -> "Signed in"
        else -> "Available"
    }
    val dashboardTone = when (dashboardValue) {
        "Signed in", "Available" -> CapabilityTone.Good
        "Sign in" -> CapabilityTone.Info
        "Offline", "Missing" -> CapabilityTone.Warning
        else -> CapabilityTone.Neutral
    }

    val voiceValue = when (standardVoiceAvailability) {
        StandardVoiceAvailability.Ready -> "Ready"
        StandardVoiceAvailability.SignInRequired -> "Sign in"
        StandardVoiceAvailability.Unsupported -> "Unsupported"
        StandardVoiceAvailability.Unreachable -> "Offline"
        StandardVoiceAvailability.Unknown -> "Checking"
    }
    val voiceTone = when (standardVoiceAvailability) {
        StandardVoiceAvailability.Ready -> CapabilityTone.Good
        StandardVoiceAvailability.SignInRequired -> CapabilityTone.Info
        StandardVoiceAvailability.Unsupported,
        StandardVoiceAvailability.Unreachable -> CapabilityTone.Warning
        StandardVoiceAvailability.Unknown -> CapabilityTone.Neutral
    }

    val relayValue = when {
        !relayEnabled -> "Disabled"
        !relayConfigured -> "Optional"
        relayReady -> "Ready"
        relayUiState == RelayUiState.Stale -> "Reconnect"
        else -> "Configured"
    }
    val relayTone = when {
        !relayEnabled || !relayConfigured -> CapabilityTone.Neutral
        relayReady -> CapabilityTone.Good
        relayUiState == RelayUiState.Stale -> CapabilityTone.Warning
        else -> CapabilityTone.Info
    }

    val terminalValue = when {
        !relayEnabled -> "Disabled"
        authState is AuthState.Paired -> "Ready"
        relayConfigured -> "Pair Relay"
        else -> "Optional"
    }
    val terminalTone = when (terminalValue) {
        "Ready" -> CapabilityTone.Good
        "Pair Relay" -> CapabilityTone.Info
        else -> CapabilityTone.Neutral
    }

    val proxyValue = if (secureProxyAdvertised) "Available" else "Not advertised"
    val proxyTone = if (secureProxyAdvertised) CapabilityTone.Good else CapabilityTone.Neutral

    // Lighter than the old six-filled-tile grid: one subtle grouped surface
    // with a status dot + value per capability, dividers between rows. The
    // header/glance pills used to duplicate API/Dashboard/Voice/Relay state;
    // this list is now the single place those facts live on the active card.
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
            CapabilityRow(
                label = "Vanilla Hermes API",
                value = apiValue,
                tone = apiTone,
                onClick = onOpenApiInfo,
            )
            CapabilityDivider()
            CapabilityRow(
                label = "Dashboard",
                value = dashboardValue,
                tone = dashboardTone,
                onClick = onOpenDashboard,
            )
            CapabilityDivider()
            CapabilityRow(
                label = "Vanilla Hermes voice",
                value = voiceValue,
                tone = voiceTone,
                onClick = if (standardVoiceAvailability ==
                    StandardVoiceAvailability.SignInRequired
                ) {
                    onOpenDashboard
                } else {
                    null
                },
            )
            CapabilityDivider()
            CapabilityRow(
                label = "Relay tools",
                value = relayValue,
                tone = relayTone,
                onClick = onOpenRelayInfo,
            )
            CapabilityDivider()
            CapabilityRow(
                label = "Terminal",
                value = terminalValue,
                tone = terminalTone,
                onClick = onOpenSessionInfo,
            )
            CapabilityDivider()
            CapabilityRow(
                label = "Secure proxy",
                value = proxyValue,
                tone = proxyTone,
            )
        }
    }
}

private enum class CapabilityTone { Neutral, Good, Info, Warning }

/** Hairline divider between capability rows — inset so it reads as a list. */
@Composable
private fun CapabilityDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 12.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

/**
 * One capability line: a status dot, the feature name, and its current
 * value (right-aligned, colored by tone). Replaces the old filled
 * [CapabilityChip] tile — status now reads as a dot + value, so the row
 * stays light and the six features chunk as a scannable list rather than a
 * dense grid of dark-blue blocks. Honesty principle: every feature is shown
 * even when unavailable, with its short reason (e.g. "Not advertised") as
 * the value rather than being hidden.
 */
@Composable
private fun CapabilityRow(
    label: String,
    value: String,
    tone: CapabilityTone,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val dotColor = when (tone) {
        CapabilityTone.Good -> Color(0xFF4CAF50)
        CapabilityTone.Info -> MaterialTheme.colorScheme.primary
        CapabilityTone.Warning -> MaterialTheme.colorScheme.error
        CapabilityTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    val valueColor = when (tone) {
        CapabilityTone.Good -> Color(0xFF4CAF50)
        CapabilityTone.Info -> MaterialTheme.colorScheme.primary
        CapabilityTone.Warning -> MaterialTheme.colorScheme.error
        CapabilityTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val rowModifier = modifier
        .fillMaxWidth()
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 8.dp, vertical = 10.dp)
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(dotColor),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onClick != null) {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Advanced expandable section — three subsections:
 *  - Manual URL configuration (API URL + key + Save & Test,
 *    Relay URL + Save & Test + Disconnect)
 *  - Allow-insecure-connections toggle (with first-enable Ack dialog)
 *  - Manual pairing code fallback (3-step flow with in-flight Connect
 *    watcher + snackbar feedback)
 *
 * Wrapped in a single `SettingsExpandableCard` so the user can collapse
 * the entire block — none of it is needed for the common paired-via-QR
 * flow. Expanded-state is `rememberSaveable` so rotation / process death
 * preserves user intent.
 *
 * [onInsecureAckRequested] opens the `InsecureConnectionAckDialog` at
 * screen scope; this composable never owns it directly so the dialog
 * can persist through card recomposition in a LazyColumn.
 */
@Composable
fun ActiveCardAdvancedSection(
    connectionViewModel: ConnectionViewModel,
    relayEnabled: Boolean,
    isDarkTheme: Boolean,
    onInsecureAckRequested: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    SettingsExpandableCard(
        title = "Advanced",
        expanded = expanded,
        onToggle = { expanded = !expanded },
        isDarkTheme = isDarkTheme,
    ) {
        ManualUrlSubsection(
            connectionViewModel = connectionViewModel,
            relayEnabled = relayEnabled,
        )

        if (relayEnabled) {
            HorizontalDivider()
            InsecureToggleSubsection(
                connectionViewModel = connectionViewModel,
                onInsecureAckRequested = onInsecureAckRequested,
            )
            HorizontalDivider()
            ManualPairingCodeSubsection(
                connectionViewModel = connectionViewModel,
            )
        }
    }
}

/**
 * Manual URL configuration subsection. Power-user only — the canonical
 * path is QR pair via the Re-pair button on the card row above.
 *
 * Kept internal rather than split further because the API + relay
 * fields share the `isTesting` + `Save & Test` idiom and the two test
 * paths talk to the same VM.
 */
@Composable
private fun ManualUrlSubsection(
    connectionViewModel: ConnectionViewModel,
    relayEnabled: Boolean,
) {
    val context = LocalContext.current

    val apiServerUrl by connectionViewModel.apiServerUrl.collectAsState()
    val relayUrl by connectionViewModel.relayUrl.collectAsState()
    val apiKeyPresent by connectionViewModel.authManager.apiKeyPresent.collectAsState()
    val relayConnectionState by connectionViewModel.relayConnectionState.collectAsState()

    // Keyed on the backing URL so a connection switch refreshes the input.
    var apiUrlInput by remember(apiServerUrl) { mutableStateOf(apiServerUrl) }
    var apiKeyInput by remember { mutableStateOf("") }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var relayUrlInput by remember(relayUrl) { mutableStateOf(relayUrl) }
    var isTestingApi by remember { mutableStateOf(false) }
    var apiVoiceSetupResult by remember {
        mutableStateOf<ConnectionViewModel.ApiVoiceSetupResult?>(null)
    }
    var relayOverrideVisible by rememberSaveable(apiServerUrl) {
        mutableStateOf(!RelayUrlDeriver.isAutoManagedRelayUrl(relayUrl, apiServerUrl))
    }
    val autoRelayUrl = RelayUrlDeriver.deriveFromApiUrl(apiUrlInput)

    LaunchedEffect(apiUrlInput, relayOverrideVisible, autoRelayUrl) {
        if (!relayOverrideVisible && autoRelayUrl != null && relayUrlInput != autoRelayUrl) {
            relayUrlInput = autoRelayUrl
            connectionViewModel.clearRelayReachableResult()
        }
    }

    OutlinedTextField(
        value = apiUrlInput,
        onValueChange = { apiUrlInput = it },
        label = { Text("API Server URL") },
        placeholder = { Text("http://your-server:8642") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = apiKeyInput,
        onValueChange = { apiKeyInput = it },
        label = { Text("API Key (optional)") },
        placeholder = {
            Text(
                if (apiKeyPresent) "•••• already set (leave blank to keep)"
                else "Leave empty if not configured",
            )
        },
        supportingText = {
            Text(
                if (apiKeyPresent && apiKeyInput.isBlank()) {
                    "A key is already stored — leave blank to keep it, or type to replace"
                } else {
                    "Only needed if Hermes is configured with API_SERVER_KEY"
                },
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
                    imageVector = if (apiKeyVisible) Icons.Filled.VisibilityOff
                    else Icons.Filled.Visibility,
                    contentDescription = if (apiKeyVisible) "Hide" else "Show",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = {
                isTestingApi = true
                apiVoiceSetupResult = null
                val relayOverride = if (relayOverrideVisible) relayUrlInput else null
                connectionViewModel.saveApiAndProbeVoice(
                    apiUrl = apiUrlInput,
                    apiKey = apiKeyInput,
                    manualRelayUrlOverride = relayOverride,
                ) { result ->
                    isTestingApi = false
                    apiVoiceSetupResult = result
                    if (!result.voiceConfigReachable && result.voiceRoute == "relay" && result.relayAutoDerived) {
                        relayOverrideVisible = true
                        result.relayUrl?.let { relayUrlInput = it }
                    }
                    Toast.makeText(
                        context,
                        when {
                            result.apiReachable && result.voiceConfigReachable ->
                                if (result.voiceRoute == "standard") {
                                    "API and standard voice reachable"
                                } else {
                                    "API and relay voice reachable"
                                }
                            result.apiReachable ->
                                "API reachable; voice route needs review"
                            else -> "Cannot reach API server"
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            enabled = apiUrlInput.isNotBlank() && !isTestingApi,
        ) {
            Text("Save & Test")
        }
        if (isTestingApi) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
        }
    }

    if (relayEnabled) {
        HorizontalDivider()

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Relay URL",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = if (autoRelayUrl != null && !relayOverrideVisible) {
                    "Auto: $autoRelayUrl"
                } else {
                    "Manual override"
                },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Relay is optional for voice. Vanilla Hermes voice uses the Hermes API; Relay voice uses this route when selected or needed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    relayOverrideVisible = !relayOverrideVisible
                    if (!relayOverrideVisible) {
                        relayUrlInput = autoRelayUrl.orEmpty()
                    }
                    connectionViewModel.clearRelayReachableResult()
                },
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
                Text(if (relayOverrideVisible) "Use auto relay URL" else "Use custom relay URL")
            }
        }

        if (relayOverrideVisible) {
            OutlinedTextField(
                value = relayUrlInput,
                onValueChange = {
                    relayUrlInput = it
                    // Stale reachability results belong to the prior URL.
                    connectionViewModel.clearRelayReachableResult()
                },
                label = { Text("Relay URL override") },
                placeholder = { Text("wss://your-server:8767") },
                singleLine = true,
                supportingText = {
                    Text("Only needed when the optional Relay route cannot be auto-derived")
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        apiVoiceSetupResult?.let { result ->
            val color = if (result.voiceConfigReachable) {
                Color(0xFF4CAF50)
            } else {
                MaterialTheme.colorScheme.error
            }
            Text(
                text = if (result.voiceConfigReachable) {
                    if (result.voiceRoute == "standard") {
                        "Voice ready via standard Hermes API"
                    } else {
                        "Voice ready via ${result.relayUrl ?: "relay"}"
                    }
                } else {
                    "Voice route needs review: ${result.voiceConfigError ?: "voice config probe failed"}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
        }

        // Save & Test + Disconnect. Connect button intentionally absent
        // — it used to cause an unpaired-auth-then-rate-limited trap.
        // /health probe with no WSS handshake is the safe surface.
        val relayReachable by connectionViewModel.relayReachableResult.collectAsState()
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    connectionViewModel.testRelayReachable(
                        if (relayOverrideVisible) relayUrlInput else autoRelayUrl.orEmpty(),
                    )
                },
                enabled = (if (relayOverrideVisible) relayUrlInput else autoRelayUrl.orEmpty()).isNotBlank() &&
                    relayReachable !is ConnectionViewModel.RelayReachable.Probing,
            ) {
                Text("Test Relay")
            }
            OutlinedButton(
                onClick = { connectionViewModel.disconnectRelay() },
                enabled = relayConnectionState != ConnectionState.Disconnected,
            ) {
                Text("Disconnect")
            }
        }

        when (val r = relayReachable) {
            is ConnectionViewModel.RelayReachable.Probing -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = "Probing /health…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            is ConnectionViewModel.RelayReachable.Ok -> {
                Text(
                    text = "✓ Reachable — hermes-relay v${r.version} (${r.clients} client, ${r.sessions} session${if (r.sessions == 1) "" else "s"})",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF4CAF50),
                )
            }
            is ConnectionViewModel.RelayReachable.Fail -> {
                val humanErr = classifyError(
                    Exception(r.message),
                    context = "save_and_test",
                )
                Column {
                    Text(
                        text = "✗ ${humanErr.title}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = humanErr.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            null -> { /* idle */ }
        }
    }
}

/**
 * Insecure-mode toggle subsection. First enable triggers the
 * [InsecureConnectionAckDialog] at screen scope via
 * [onInsecureAckRequested]; subsequent toggles fire through the VM
 * directly.
 */
@Composable
private fun InsecureToggleSubsection(
    connectionViewModel: ConnectionViewModel,
    onInsecureAckRequested: () -> Unit,
) {
    val insecureMode by connectionViewModel.insecureMode.collectAsState()
    val insecureAckSeen by connectionViewModel.insecureAckSeen.collectAsState()
    val isInsecureConnection by connectionViewModel.isInsecureConnection.collectAsState()
    val relayConnectionState by connectionViewModel.relayConnectionState.collectAsState()

    if (isInsecureConnection && relayConnectionState == ConnectionState.Connected) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "Plain connection — traffic is not encrypted",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Allow plain (unencrypted) connections",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Enable ws:// and http:// for local dev/testing only",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = insecureMode,
            onCheckedChange = { enabled ->
                if (enabled && !insecureAckSeen) {
                    // First enable → open threat-model Ack dialog at
                    // screen scope. VM is written only on confirm.
                    onInsecureAckRequested()
                } else {
                    connectionViewModel.setInsecureMode(enabled)
                }
            },
        )
    }
}

/**
 * Manual pairing code subsection — the 3-step fallback flow for when
 * the QR scanner isn't usable (no camera, headless host, bad lighting).
 *
 *  1. Copy the phone-generated code (with Refresh to regenerate)
 *  2. Run `hermes pair --register-code <code>` on the host
 *  3. Tap Connect — with a 15s auth watcher that surfaces success /
 *     failure through the global snackbar host
 *
 * Mirrors the legacy `ConnectionSettingsScreen` Card 3 flow verbatim
 * since it's already battle-tested. The top-level "Connect" button on
 * this card requires a relay URL — if the user hasn't set one in the
 * Manual URL subsection above, the button is disabled and an inline
 * hint points them there.
 */
@Composable
private fun ManualPairingCodeSubsection(
    connectionViewModel: ConnectionViewModel,
) {
    val pairingCode by connectionViewModel.pairingCode.collectAsState()
    val relayUrl by connectionViewModel.relayUrl.collectAsState()

    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbarHost = LocalSnackbarHost.current

    // Connect state + auth-watcher attempt counter. Keyed counter so
    // retrying cancels any in-flight watcher and restarts with a fresh
    // 15s budget — matches the legacy Card 3 behavior byte-for-byte.
    var connectInProgress by remember { mutableStateOf(false) }
    var connectAttempt by remember { mutableStateOf(0) }
    var explainerExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(connectAttempt) {
        if (connectAttempt == 0) return@LaunchedEffect
        try {
            val terminal = kotlinx.coroutines.withTimeout(15_000) {
                connectionViewModel.authState
                    .first { it is AuthState.Paired || it is AuthState.Failed }
            }
            connectInProgress = false
            when (terminal) {
                is AuthState.Paired -> snackbarHost.showSnackbar("Paired successfully")
                is AuthState.Failed -> {
                    val human = classifyError(
                        IllegalStateException(terminal.reason),
                        context = "pair",
                    )
                    snackbarHost.showHumanError(human)
                }
                else -> Unit
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            connectInProgress = false
            val human = classifyError(
                java.io.IOException("No response from relay"),
                context = "pair",
            )
            snackbarHost.showHumanError(human)
        } catch (e: Exception) {
            connectInProgress = false
            snackbarHost.showHumanError(classifyError(e, context = "pair"))
        }
    }

    Text(
        text = "Manual pairing code (fallback)",
        style = MaterialTheme.typography.titleSmall,
    )
    Text(
        text = "Use this when you can't scan the pairing QR. " +
            "Follow the three steps — they're meant to be done in order on " +
            "whatever machine you have shell access to.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // Step 1 — display + copy + regenerate
    ManualPairStep(number = 1, title = "Copy the code below") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = pairingCode,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = MaterialTheme.typography.headlineMedium.fontSize * 0.15,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                scope.launch {
                    clipboard.setClipEntry(
                        ClipEntry(ClipData.newPlainText("Pairing code", pairingCode)),
                    )
                    snackbarHost.showSnackbar("Pairing code copied")
                }
            }) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "Copy pairing code",
                )
            }
            IconButton(onClick = { connectionViewModel.regeneratePairingCode() }) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Generate new code",
                )
            }
        }
    }

    // Step 2 — host command
    ManualPairStep(number = 2, title = "On the host running Hermes-Relay, run:") {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "hermes pair --register-code $pairingCode",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        val cmd = "hermes pair --register-code $pairingCode"
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("hermes pair command", cmd)),
                            )
                            snackbarHost.showSnackbar("Command copied")
                        }
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "Copy hermes pair command",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }

    // Step 3 — Connect button + relay-URL prerequisite check
    ManualPairStep(number = 3, title = "Come back here and tap Connect") {
        val canConnect = !connectInProgress &&
            relayUrl.isNotBlank() &&
            pairingCode.isNotBlank()
        Button(
            onClick = {
                connectInProgress = true
                connectAttempt += 1
                // Atomic apply-code-and-reset avoids races between the
                // stale session's code-regeneration and this fresh
                // authenticate()'s mirror-write. Then kick a disconnect
                // + connect so the WSS handshake uses the new code.
                connectionViewModel.authManager
                    .applyServerIssuedCodeAndReset(pairingCode)
                connectionViewModel.disconnectRelay()
                connectionViewModel.connectRelay(relayUrl)
            },
            enabled = canConnect,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (connectInProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text("Connecting…")
            } else {
                Text("Connect")
            }
        }
        if (relayUrl.isBlank()) {
            Text(
                text = "Relay URL not set — open the Manual URL section above to set it first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    HorizontalDivider()

    TextButton(
        onClick = { explainerExpanded = !explainerExpanded },
        contentPadding = PaddingValues(horizontal = 0.dp),
    ) {
        Text(
            text = if (explainerExpanded) "Hide explanation" else "How does this work?",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (explainerExpanded) {
        Text(
            text = "This is a fallback for when you can't scan the pairing QR " +
                "— for example, no camera, the host can't render a QR, or you " +
                "only have SSH access from a single device. The canonical flow " +
                "is the QR scan from `/hermes-relay-pair` or `hermes pair`.\n\n" +
                "How it works: the phone generates a 6-character code locally. " +
                "You paste that code into the host's `hermes pair --register-code` " +
                "command, which pre-registers it with the relay. When you tap " +
                "Connect here, the phone presents the same code to the relay " +
                "and gets a long-lived session token in return.\n\n" +
                "Bridge / device-control is gated by the master toggle on the " +
                "Bridge tab, NOT by this pairing code. Pairing only authorizes " +
                "the relay session.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Security posture strip — always visible on the active card, directly
 * below the Advanced expander. Renders, in order:
 *  - Transport security badge (wss:// vs ws://)
 *  - Tailscale detected chip (conditional)
 *  - Hardware keystore badge (conditional)
 *  - Relay sessions row (always — tap to navigate)
 *
 * These are the "what's my pairing posture?" facts. Short + info-dense,
 * so they don't live behind an expander.
 */
@Composable
fun ActiveCardSecurityPosture(
    connectionViewModel: ConnectionViewModel,
    onNavigateToPairedDevices: () -> Unit,
) {
    val relayUrl by connectionViewModel.relayUrl.collectAsState()
    val effectiveApiServerUrl by connectionViewModel.effectiveApiServerUrl.collectAsState()
    val effectiveDashboardUrl by connectionViewModel.effectiveDashboardUrl.collectAsState()
    val effectiveRelayUrl by connectionViewModel.effectiveRelayUrl.collectAsState()
    val relayConfigured by connectionViewModel.relayConfigured.collectAsState()
    val insecureReason by connectionViewModel.insecureReason.collectAsState()
    val isTailscaleDetected by connectionViewModel.isTailscaleDetected.collectAsState()
    val currentPairedSession by connectionViewModel.currentPairedSession.collectAsState()
    val pairedDevices by connectionViewModel.pairedDevices.collectAsState()
    // ADR 24 — surface the live endpoint role so the insecure badge can
    // say "Plain (on LAN)" instead of "Insecure (network unknown)" when
    // the resolver already knows which candidate we're on.
    val activeEndpoint by connectionViewModel.activeEndpoint.collectAsState()
    val selectedRouteUrls = buildList {
        effectiveApiServerUrl.trim().takeIf { it.isNotBlank() }?.let(::add)
        effectiveDashboardUrl.trim().takeIf { it.isNotBlank() }?.let(::add)
        val selectedRelayUrl = effectiveRelayUrl.ifBlank { relayUrl }
        if (relayConfigured || selectedRelayUrl.isNotBlank()) {
            selectedRelayUrl.trim().takeIf { it.isNotBlank() }?.let(::add)
        }
    }
    val secureUrlCount = selectedRouteUrls.count { url ->
        isSelectedRouteUrlSecure(
            url = url,
            activeEndpoint = activeEndpoint,
            isTailscaleDetected = isTailscaleDetected,
        )
    }
    val transportState = when {
        selectedRouteUrls.isEmpty() -> null
        secureUrlCount == selectedRouteUrls.size -> TransportSecurityState.AllSecure
        secureUrlCount > 0 -> TransportSecurityState.Mixed
        else -> TransportSecurityState.AllInsecure
    }

    if (transportState != null) {
        TransportSecurityBadge(
            state = transportState,
            size = TransportSecuritySize.Row,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        TransportSecurityBadge(
            isSecure = isUrlSecure(relayUrl),
            reason = insecureReason.ifBlank { null },
            size = TransportSecuritySize.Row,
            modifier = Modifier.fillMaxWidth(),
            activeRole = activeEndpoint?.role,
        )
    }

    if (isTailscaleDetected) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Shield,
                contentDescription = null,
                tint = Color(0xFF2E7D32),
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "Tailscale detected",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF2E7D32),
            )
        }
    }

    if (currentPairedSession?.hasHardwareStorage == true) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "Session token stored in hardware keystore",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigateToPairedDevices() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Relay sessions",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = if (pairedDevices.isNotEmpty()) {
                    "${pairedDevices.size} active sessions on this server"
                } else {
                    "Manage which phones can connect"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun isSelectedRouteUrlSecure(
    url: String,
    activeEndpoint: EndpointCandidate?,
    isTailscaleDetected: Boolean,
): Boolean {
    if (isUrlSecure(url)) return true
    return activeEndpoint.isEncryptedOverlayRoute(isTailscaleDetected)
}

private fun EndpointCandidate?.isEncryptedOverlayRoute(isTailscaleDetected: Boolean): Boolean {
    if (this == null) return false
    val role = role.lowercase()
    val securityHint = security.orEmpty().lowercase()
    return role == "tailscale" ||
        (isTailscaleDetected && securityHint.contains("tailscale")) ||
        role == "plugin_proxy" ||
        role == "plugin-proxy" ||
        hasSecureProxy() ||
        securityHint.contains("wireguard") ||
        securityHint.contains("https") ||
        securityHint.contains("tls")
}

/**
 * Numbered step row for the Manual pairing code fallback. Tightly
 * coupled to its Card 3 layout — step badge sizing + content shape —
 * so it stays private-ish here rather than promoted to a shared
 * component. Lift to `ui.components` if a second caller appears.
 */
@Composable
private fun ManualPairStep(
    number: Int,
    title: String,
    content: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(percent = 50),
            modifier = Modifier.size(24.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize(),
            ) {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            content()
        }
    }
}
