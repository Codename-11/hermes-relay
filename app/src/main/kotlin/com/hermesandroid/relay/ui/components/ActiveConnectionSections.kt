package com.hermesandroid.relay.ui.components

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.displayLabel
import com.hermesandroid.relay.data.hasSecureProxy
import com.hermesandroid.relay.data.primaryRouteUrl
import com.hermesandroid.relay.network.relay.ConnectionState
import com.hermesandroid.relay.network.relay.RelayUrlDeriver
import com.hermesandroid.relay.network.upstream.GatewayAvailability
import com.hermesandroid.relay.ui.LocalSnackbarHost
import com.hermesandroid.relay.ui.UiMessageBus
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
 * Hermes status rows (API / Dashboard). Dashboard auth is surfaced
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
    val connectionSecurity by connectionViewModel.connectionSecurity.collectAsState()

    // At-a-glance security rollup, promoted out of the Advanced fold. Tap for
    // the per-surface breakdown. Single source of truth: ConnectionSecurity.
    ConnectionSecurityBadgeWithSheet(
        security = connectionSecurity,
        size = TransportSecuritySize.Row,
        modifier = Modifier.fillMaxWidth(),
    )

    ConnectionStatusRow(
        label = stringResource(R.string.active_section_api_server),
        isConnected = apiReachable,
        isProbing = apiHealth == ConnectionViewModel.HealthStatus.Probing,
        statusText = when {
            apiHealth == ConnectionViewModel.HealthStatus.Probing -> stringResource(R.string.active_section_checking)
            apiReachable -> stringResource(R.string.active_section_reachable)
            else -> stringResource(R.string.active_section_unreachable)
        },
        onClick = onOpenApiInfo,
        modifier = Modifier.fillMaxWidth(),
    )

    ConnectionStatusRow(
        label = stringResource(R.string.active_section_dashboard),
        isConnected = dashboardStatus?.reachable == true && !dashboardSignInRequired,
        statusText = when {
            activeConnection?.resolvedDashboardUrl.isNullOrBlank() -> stringResource(R.string.active_section_not_configured)
            dashboardStatus == null -> stringResource(R.string.active_section_not_checked)
            !dashboardStatus.reachable -> stringResource(R.string.active_section_unreachable)
            dashboardSignInRequired -> stringResource(R.string.active_section_sign_in_required)
            dashboardStatus.authenticated == true -> stringResource(R.string.active_section_signed_in)
            dashboardStatus.authRequired == false -> stringResource(R.string.active_section_available)
            else -> stringResource(R.string.active_section_available)
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
                text = stringResource(R.string.active_section_dashboard_sign_in_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenDashboard) {
                Text(stringResource(R.string.active_section_sign_in))
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

    // Pre-resolve strings for Toast (non-composable context)
    val reconnectingRelayToast = stringResource(R.string.active_section_reconnecting_relay)
    val connectedLabel = stringResource(R.string.conn_info_connected)

    // ADR 24: relayRowState carries both the phase and the active endpoint
    // role. statusText appends " · <Role>" when the resolver has picked one.
    ConnectionStatusRow(
        label = stringResource(R.string.active_section_relay),
        state = relayRowState.asBadgeState(),
        statusText = relayRowState.statusText(connectedLabel = connectedLabel),
        onClick = {
            if (relayUiState == RelayUiState.Stale) {
                connectionViewModel.connectRelay()
                Toast.makeText(
                    context,
                    reconnectingRelayToast,
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                onOpenRelayInfo()
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )

    // Pre-resolve strings for Session status
    val pairedLabel = stringResource(R.string.conn_info_paired)
    val pairingLabel = stringResource(R.string.conn_info_pairing)
    val unpairedLabel = stringResource(R.string.conn_info_unpaired)
    val failedReasonLabel = stringResource(R.string.active_section_failed_reason)

    ConnectionStatusRow(
        label = stringResource(R.string.active_section_session),
        isConnected = authState is AuthState.Paired,
        isConnecting = authState is AuthState.Pairing,
        statusText = when (authState) {
            is AuthState.Paired -> pairedLabel
            is AuthState.Pairing -> pairingLabel
            is AuthState.Unpaired -> unpairedLabel
            is AuthState.Failed -> failedReasonLabel.format((authState as AuthState.Failed).reason)
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
    onPairRelay: () -> Unit,
) {
    val apiReachable by connectionViewModel.apiServerReachable.collectAsState()
    val apiHealth by connectionViewModel.apiServerHealth.collectAsState()
    val activeConnection by connectionViewModel.activeConnection.collectAsState()
    val standardVoiceAvailability by
        connectionViewModel.standardVoiceAvailability.collectAsState()
    val gatewayAvailability by connectionViewModel.gatewayAvailability.collectAsState()
    val relayConfigured by connectionViewModel.relayConfigured.collectAsState()
    val relayReady by connectionViewModel.relayReady.collectAsState()
    val relayUiState by connectionViewModel.relayUiState.collectAsState()
    val authState by connectionViewModel.authState.collectAsState()

    val dashboardStatus = activeConnection?.dashboardLastStatus
    val dashboardSignInRequired =
        dashboardStatus?.authRequired == true && dashboardStatus.authenticated != true
    val secureProxyAdvertised =
        activeConnection?.routeCandidates.orEmpty().any { it.hasSecureProxy() }

    val chatValue = when {
        gatewayAvailability == GatewayAvailability.Ready || apiReachable -> stringResource(R.string.active_section_ready)
        gatewayAvailability == GatewayAvailability.SignInRequired -> stringResource(R.string.active_section_sign_in)
        gatewayAvailability == GatewayAvailability.Unreachable && !apiReachable -> stringResource(R.string.active_section_offline)
        else -> stringResource(R.string.active_section_checking)
    }
    val chatTone = when {
        gatewayAvailability == GatewayAvailability.Ready || apiReachable -> CapabilityTone.Good
        gatewayAvailability == GatewayAvailability.SignInRequired -> CapabilityTone.Info
        gatewayAvailability == GatewayAvailability.Unreachable && !apiReachable -> CapabilityTone.Warning
        else -> CapabilityTone.Neutral
    }

    val apiValue = when {
        apiHealth == ConnectionViewModel.HealthStatus.Probing -> stringResource(R.string.active_section_checking)
        apiReachable -> stringResource(R.string.active_section_ready)
        activeConnection?.apiServerUrl.isNullOrBlank() -> stringResource(R.string.active_section_missing)
        else -> stringResource(R.string.active_section_offline)
    }
    val apiTone = when {
        apiReachable -> CapabilityTone.Good
        apiHealth == ConnectionViewModel.HealthStatus.Probing -> CapabilityTone.Neutral
        else -> CapabilityTone.Warning
    }

    val dashboardValue = when {
        activeConnection?.resolvedDashboardUrl.isNullOrBlank() -> stringResource(R.string.active_section_missing)
        dashboardStatus == null -> stringResource(R.string.active_section_unchecked)
        !dashboardStatus.reachable -> stringResource(R.string.active_section_offline)
        dashboardSignInRequired -> stringResource(R.string.active_section_sign_in)
        dashboardStatus.authenticated == true -> stringResource(R.string.active_section_signed_in)
        else -> stringResource(R.string.active_section_available)
    }
    val dashboardTone = when {
        dashboardStatus?.authenticated == true -> CapabilityTone.Good
        dashboardStatus?.reachable == true && !dashboardSignInRequired -> CapabilityTone.Good
        dashboardSignInRequired -> CapabilityTone.Info
        activeConnection?.resolvedDashboardUrl.isNullOrBlank() || dashboardStatus?.reachable != true -> CapabilityTone.Warning
        else -> CapabilityTone.Neutral
    }

    val voiceValue = when (standardVoiceAvailability) {
        StandardVoiceAvailability.Ready -> stringResource(R.string.active_section_ready)
        StandardVoiceAvailability.SignInRequired -> stringResource(R.string.active_section_sign_in)
        StandardVoiceAvailability.Unsupported -> stringResource(R.string.active_section_unsupported)
        StandardVoiceAvailability.Unreachable -> stringResource(R.string.active_section_offline)
        StandardVoiceAvailability.Unknown -> stringResource(R.string.active_section_checking)
    }
    val voiceTone = when (standardVoiceAvailability) {
        StandardVoiceAvailability.Ready -> CapabilityTone.Good
        StandardVoiceAvailability.SignInRequired -> CapabilityTone.Info
        StandardVoiceAvailability.Unsupported,
        StandardVoiceAvailability.Unreachable -> CapabilityTone.Warning
        StandardVoiceAvailability.Unknown -> CapabilityTone.Neutral
    }

    val relayValue = when {
        !relayEnabled -> stringResource(R.string.active_section_disabled)
        !relayConfigured -> stringResource(R.string.active_section_optional)
        relayReady -> stringResource(R.string.active_section_ready)
        relayUiState == RelayUiState.Stale -> stringResource(R.string.active_section_reconnect)
        else -> stringResource(R.string.active_section_configured)
    }
    val relayTone = when {
        !relayEnabled || !relayConfigured -> CapabilityTone.Neutral
        relayReady -> CapabilityTone.Good
        relayUiState == RelayUiState.Stale -> CapabilityTone.Warning
        else -> CapabilityTone.Info
    }

    val terminalValue = when {
        !relayEnabled -> stringResource(R.string.active_section_disabled)
        authState is AuthState.Paired -> stringResource(R.string.active_section_ready)
        relayConfigured -> stringResource(R.string.active_section_pair_relay)
        else -> stringResource(R.string.active_section_optional)
    }
    val terminalTone = when {
        authState is AuthState.Paired -> CapabilityTone.Good
        relayConfigured && authState !is AuthState.Paired -> CapabilityTone.Info
        else -> CapabilityTone.Neutral
    }

    val proxyValue = if (secureProxyAdvertised) stringResource(R.string.active_section_available) else stringResource(R.string.active_section_not_advertised)
    val proxyTone = if (secureProxyAdvertised) CapabilityTone.Good else CapabilityTone.Neutral

    // Pre-resolve labels for CapabilityRow
    val hermesApiLabel = stringResource(R.string.active_section_hermes_api)
    val dashboardLabel = stringResource(R.string.active_section_dashboard)
    val hermesVoiceLabel = stringResource(R.string.active_section_hermes_voice)
    val relayToolsLabel = stringResource(R.string.active_section_relay_tools)
    val terminalLabel = stringResource(R.string.active_section_terminal)
    val secureProxyLabel = stringResource(R.string.active_section_secure_proxy)

    Text(
        text = stringResource(R.string.active_section_core_hermes),
        style = MaterialTheme.typography.titleSmall,
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
            CapabilityRow(
                icon = Icons.Filled.Chat,
                label = stringResource(R.string.conn_chat_label),
                value = chatValue,
                tone = chatTone,
            )
            CapabilityDivider()
            CapabilityRow(
                icon = Icons.Filled.Dashboard,
                label = stringResource(R.string.conn_manage_label),
                value = dashboardValue,
                tone = dashboardTone,
                onClick = onOpenDashboard,
            )
            CapabilityDivider()
            CapabilityRow(
                icon = Icons.Filled.GraphicEq,
                label = hermesVoiceLabel,
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
        }
    }

    Text(
        text = stringResource(R.string.active_section_optional_relay),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 6.dp),
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (relayConfigured) {
                            stringResource(R.string.active_section_relay_connected_features)
                        } else {
                            stringResource(R.string.active_section_extend_connection)
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.active_section_relay_optional_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
            CapabilityRow(
                icon = Icons.Filled.Link,
                label = relayToolsLabel,
                value = relayValue,
                tone = relayTone,
                onClick = if (relayConfigured) onOpenRelayInfo else null,
            )
            CapabilityDivider()
            CapabilityRow(
                icon = Icons.Filled.Devices,
                label = terminalLabel,
                value = terminalValue,
                tone = terminalTone,
                onClick = if (authState is AuthState.Paired) onOpenSessionInfo else null,
            )
            CapabilityDivider()
            CapabilityRow(
                icon = Icons.Filled.Shield,
                label = secureProxyLabel,
                value = proxyValue,
                tone = proxyTone,
            )
            OutlinedButton(
                onClick = if (relayConfigured) onOpenRelayInfo else onPairRelay,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    if (relayConfigured) Icons.Filled.Link else Icons.Filled.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    if (relayConfigured) {
                        stringResource(R.string.active_section_view_relay_details)
                    } else {
                        stringResource(R.string.active_section_scan_relay_qr)
                    },
                )
            }
            if (!relayConfigured) {
                Text(
                    text = stringResource(R.string.active_section_core_unchanged),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = dotColor,
            modifier = Modifier.size(20.dp),
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
 * Advanced tab content — three directly visible subsections:
 *  - Manual URL configuration (API URL + key + Save & Test,
 *    Relay URL + Save & Test + Disconnect)
 *  - Allow-insecure-connections toggle (with first-enable Ack dialog)
 *  - Manual pairing code fallback (3-step flow with in-flight Connect
 *    watcher + snackbar feedback)
 *
 * [onInsecureAckRequested] opens the `InsecureConnectionAckDialog` at
 * screen scope; this composable never owns it directly so the dialog
 * can persist through card recomposition in a LazyColumn.
 */
@Composable
fun ActiveCardAdvancedSection(
    connectionViewModel: ConnectionViewModel,
    relayEnabled: Boolean,
    @Suppress("UNUSED_PARAMETER") isDarkTheme: Boolean,
    onPairRelay: () -> Unit,
    onInsecureAckRequested: () -> Unit,
) {
    val apiServerUrl by connectionViewModel.apiServerUrl.collectAsState()
    val relayUrl by connectionViewModel.relayUrl.collectAsState()
    var apiEditorOpen by rememberSaveable { mutableStateOf(false) }
    var apiHelpOpen by rememberSaveable { mutableStateOf(false) }
    var relayEditorOpen by rememberSaveable { mutableStateOf(false) }
    var pairingOpen by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.active_section_optional_api_fallback),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.active_section_api_not_required),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (apiEditorOpen) {
            Surface(
                color = Color.Transparent,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { apiEditorOpen = false }) {
                            Text(stringResource(R.string.active_section_done))
                        }
                    }
                    ManualUrlSubsection(connectionViewModel, relayEnabled, showApi = true, showRelay = false)
                }
            }
        } else {
            Surface(
                color = Color.Transparent,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = apiServerUrl.ifBlank { stringResource(R.string.active_section_not_configured) },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.active_section_api_server_url_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.active_section_api_optional_direct),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { apiEditorOpen = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(stringResource(R.string.active_section_configure_test))
                    }
                    TextButton(
                        onClick = { apiHelpOpen = !apiHelpOpen },
                        contentPadding = PaddingValues(horizontal = 0.dp),
                    ) {
                        Text(stringResource(R.string.active_section_where_api_key))
                    }
                    if (apiHelpOpen) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.active_section_api_key_explainer),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        Text(text = stringResource(R.string.active_section_relay), style = MaterialTheme.typography.titleMedium)
        Text(
            text = relayUrl.ifBlank { stringResource(R.string.active_section_not_configured) },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.active_section_relay_optional_bridge),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onPairRelay) {
            Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.size(8.dp))
            Text(stringResource(R.string.active_section_scan_relay_qr))
        }
        TextButton(onClick = { relayEditorOpen = !relayEditorOpen }) {
            Text(stringResource(R.string.active_section_other_relay_methods))
        }
        if (relayEnabled && relayEditorOpen) {
            Surface(
                color = Color.Transparent,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { relayEditorOpen = false }) {
                            Text(stringResource(R.string.active_section_done))
                        }
                    }
                    ManualUrlSubsection(connectionViewModel, relayEnabled = true, showApi = false, showRelay = true)
                }
            }
        }

        HorizontalDivider()
        Text(
            text = stringResource(R.string.active_section_connection_behavior),
            style = MaterialTheme.typography.titleMedium,
        )
        InsecureToggleSubsection(connectionViewModel, onInsecureAckRequested)

        HorizontalDivider()
        Text(
            text = stringResource(R.string.active_section_manual_pairing),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.active_section_pair_device_using_code),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { pairingOpen = !pairingOpen }) {
            Icon(Icons.Filled.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.size(8.dp))
            Text(stringResource(R.string.active_section_enter_pairing_code))
        }
        if (pairingOpen) {
            Surface(
                color = Color.Transparent,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    ManualPairingCodeSubsection(connectionViewModel)
                }
            }
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
    showApi: Boolean,
    showRelay: Boolean,
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

    // Pre-resolve strings for Toast (non-composable context)
    val apiHermesVoiceReachableToast = stringResource(R.string.active_section_api_hermes_voice_reachable)
    val apiRelayVoiceReachableToast = stringResource(R.string.active_section_api_relay_voice_reachable)
    val apiReachableVoiceReviewToast = stringResource(R.string.active_section_api_reachable_voice_review)
    val cannotReachApiToast = stringResource(R.string.active_section_cannot_reach_api)

    // Pre-resolve other strings
    val apiServerUrlLabel = stringResource(R.string.active_section_api_server_url_label)
    val apiServerUrlPlaceholder = stringResource(R.string.active_section_api_server_url_placeholder)
    val apiKeyOptionalLabel = stringResource(R.string.active_section_api_key_optional)
    val apiKeyAlreadySetPlaceholder = stringResource(R.string.active_section_api_key_already_set)
    val apiKeyNotConfiguredPlaceholder = stringResource(R.string.active_section_api_key_not_configured)
    val apiKeyStoredHint = stringResource(R.string.active_section_api_key_stored_hint)
    val apiKeyNeededHint = stringResource(R.string.active_section_api_key_needed_hint)
    val hideDesc = stringResource(R.string.active_section_hide)
    val showDesc = stringResource(R.string.active_section_show)
    val saveAndTestText = stringResource(R.string.active_section_save_and_test)
    val relayUrlText = stringResource(R.string.active_section_relay_url)
    val autoRelayUrlText = stringResource(R.string.active_section_auto_relay_url)
    val manualOverrideText = stringResource(R.string.active_section_manual_override)
    val relayOptionalForVoiceText = stringResource(R.string.active_section_relay_optional_for_voice)
    val useAutoRelayUrlText = stringResource(R.string.active_section_use_auto_relay_url)
    val useCustomRelayUrlText = stringResource(R.string.active_section_use_custom_relay_url)
    val relayUrlOverrideLabel = stringResource(R.string.active_section_relay_url_override)
    val relayUrlOverridePlaceholder = stringResource(R.string.active_section_relay_url_override_placeholder)
    val relayUrlOverrideHint = stringResource(R.string.active_section_relay_url_override_hint)
    val voiceReadyViaHermesApiText = stringResource(R.string.active_section_voice_ready_via_hermes_api)
    val voiceReadyViaRelayText = stringResource(R.string.active_section_voice_ready_via_relay)
    val voiceRouteNeedsReviewText = stringResource(R.string.active_section_voice_route_needs_review)
    val testRelayText = stringResource(R.string.active_section_test_relay)
    val disconnectText = stringResource(R.string.active_section_disconnect)
    val probingHealthText = stringResource(R.string.active_section_probing_health)
    val reachableRelayVersionText = stringResource(R.string.active_section_reachable_relay_version)
    val unreachableRelayText = stringResource(R.string.active_section_unreachable_relay)

    LaunchedEffect(apiUrlInput, relayOverrideVisible, autoRelayUrl) {
        if (!relayOverrideVisible && autoRelayUrl != null && relayUrlInput != autoRelayUrl) {
            relayUrlInput = autoRelayUrl
            connectionViewModel.clearRelayReachableResult()
        }
    }

    if (showApi) OutlinedTextField(
        value = apiUrlInput,
        onValueChange = { apiUrlInput = it },
        label = { Text(apiServerUrlLabel) },
        placeholder = { Text(apiServerUrlPlaceholder) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    if (showApi) OutlinedTextField(
        value = apiKeyInput,
        onValueChange = { apiKeyInput = it },
        label = { Text(apiKeyOptionalLabel) },
        placeholder = {
            Text(
                if (apiKeyPresent) apiKeyAlreadySetPlaceholder
                else apiKeyNotConfiguredPlaceholder,
            )
        },
        supportingText = {
            Text(
                if (apiKeyPresent && apiKeyInput.isBlank()) {
                    apiKeyStoredHint
                } else {
                    apiKeyNeededHint
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
                    contentDescription = if (apiKeyVisible) hideDesc else showDesc,
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )

    if (showApi) Row(
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
                                    apiHermesVoiceReachableToast
                                } else {
                                    apiRelayVoiceReachableToast
                                }
                            result.apiReachable ->
                                apiReachableVoiceReviewToast
                            else -> cannotReachApiToast
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            enabled = apiUrlInput.isNotBlank() && !isTestingApi,
        ) {
            Text(saveAndTestText)
        }
        if (isTestingApi) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
        }
    }

    if (relayEnabled && showRelay) {
        HorizontalDivider()

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = relayUrlText,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = if (autoRelayUrl != null && !relayOverrideVisible) {
                    autoRelayUrlText.format(autoRelayUrl)
                } else {
                    manualOverrideText
                },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = relayOptionalForVoiceText,
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
                Text(if (relayOverrideVisible) useAutoRelayUrlText else useCustomRelayUrlText)
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
                label = { Text(relayUrlOverrideLabel) },
                placeholder = { Text(relayUrlOverridePlaceholder) },
                singleLine = true,
                supportingText = {
                    Text(relayUrlOverrideHint)
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
                        voiceReadyViaHermesApiText
                    } else {
                        voiceReadyViaRelayText.format(result.relayUrl ?: "relay")
                    }
                } else {
                    voiceRouteNeedsReviewText.format(result.voiceConfigError ?: stringResource(R.string.active_section_voice_config_probe_failed))
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
                Text(testRelayText)
            }
            OutlinedButton(
                onClick = { connectionViewModel.disconnectRelay() },
                enabled = relayConnectionState != ConnectionState.Disconnected,
            ) {
                Text(disconnectText)
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
                        text = probingHealthText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            is ConnectionViewModel.RelayReachable.Ok -> {
                val sessionsSuffix = if (r.sessions == 1) "" else stringResource(R.string.active_section_sessions_suffix)
                Text(
                    text = reachableRelayVersionText.format(r.version, r.clients, r.sessions, sessionsSuffix),
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
                        text = unreachableRelayText.format(humanErr.title),
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

    // Pre-resolve strings
    val plainConnectionNotEncrypted = stringResource(R.string.active_section_plain_connection_not_encrypted)
    val allowPlainConnections = stringResource(R.string.active_section_allow_plain_connections)
    val enableWsHttpForDev = stringResource(R.string.active_section_enable_ws_http_for_dev)

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
                text = plainConnectionNotEncrypted,
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
                text = allowPlainConnections,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = enableWsHttpForDev,
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

    // Pre-resolve strings for non-composable contexts (LaunchedEffect, UiMessageBus)
    val pairedSuccessfullyToast = stringResource(R.string.active_section_paired_successfully)
    val noResponseFromRelayToast = stringResource(R.string.active_section_no_response_from_relay)
    val pairingCodeCopiedToast = stringResource(R.string.active_section_pairing_code_copied)
    val commandCopiedToast = stringResource(R.string.active_section_command_copied)
    val hermesPairCommandLabel = stringResource(R.string.active_section_hermes_pair_command)
    val copyPairingCodeDesc = stringResource(R.string.conn_info_copy_pairing_code)
    val generateNewCodeDesc = stringResource(R.string.active_section_generate_new_code)
    val copyHermesPairCommandDesc = stringResource(R.string.active_section_copy_hermes_pair_command)
    val connectingText = stringResource(R.string.active_section_connecting)
    val connectText = stringResource(R.string.active_section_connect)
    val relayUrlNotSetText = stringResource(R.string.active_section_relay_url_not_set)
    val hideExplanationText = stringResource(R.string.active_section_hide_explanation)
    val howDoesThisWorkText = stringResource(R.string.active_section_how_does_this_work)
    val manualPairingExplanation = stringResource(R.string.active_section_manual_pairing_explanation)
    val pairingCodeLabel = stringResource(R.string.conn_info_pairing_code)

    LaunchedEffect(connectAttempt) {
        if (connectAttempt == 0) return@LaunchedEffect
        try {
            val terminal = kotlinx.coroutines.withTimeout(15_000) {
                connectionViewModel.authState
                    .first { it is AuthState.Paired || it is AuthState.Failed }
            }
            connectInProgress = false
            when (terminal) {
                is AuthState.Paired -> UiMessageBus.success(pairedSuccessfullyToast)
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
                java.io.IOException(noResponseFromRelayToast),
                context = "pair",
            )
            snackbarHost.showHumanError(human)
        } catch (e: Exception) {
            connectInProgress = false
            snackbarHost.showHumanError(classifyError(e, context = "pair"))
        }
    }

    // Pre-resolve other UI strings
    val manualPairingCodeTitle = stringResource(R.string.active_section_manual_pairing_code_title)
    val manualPairingCodeDesc = stringResource(R.string.active_section_manual_pairing_code_desc)
    val step1CopyCode = stringResource(R.string.active_section_step_1_copy_code)
    val step2RunCommand = stringResource(R.string.active_section_step_2_run_command)
    val step3TapConnect = stringResource(R.string.active_section_step_3_tap_connect)

    Text(
        text = manualPairingCodeTitle,
        style = MaterialTheme.typography.titleSmall,
    )
    Text(
        text = manualPairingCodeDesc,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // Step 1 — display + copy + regenerate
    ManualPairStep(number = 1, title = step1CopyCode) {
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
                        ClipEntry(ClipData.newPlainText(pairingCodeLabel, pairingCode)),
                    )
                    UiMessageBus.info(pairingCodeCopiedToast)
                }
            }) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = copyPairingCodeDesc,
                )
            }
            IconButton(onClick = { connectionViewModel.regeneratePairingCode() }) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = generateNewCodeDesc,
                )
            }
        }
    }

    // Step 2 — host command
    ManualPairStep(number = 2, title = step2RunCommand) {
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
                                ClipEntry(ClipData.newPlainText(hermesPairCommandLabel, cmd)),
                            )
                            UiMessageBus.info(commandCopiedToast)
                        }
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = copyHermesPairCommandDesc,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }

    // Step 3 — Connect button + relay-URL prerequisite check
    ManualPairStep(number = 3, title = step3TapConnect) {
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
                Text(connectingText)
            } else {
                Text(connectText)
            }
        }
        if (relayUrl.isBlank()) {
            Text(
                text = relayUrlNotSetText,
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
            text = if (explainerExpanded) hideExplanationText else howDoesThisWorkText,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (explainerExpanded) {
        Text(
            text = manualPairingExplanation,
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
    onRevokeRelay: () -> Unit,
) {
    val activeConnection by connectionViewModel.activeConnection.collectAsState()
    val connectionSecurity by connectionViewModel.connectionSecurity.collectAsState()
    val isTailscaleDetected by connectionViewModel.isTailscaleDetected.collectAsState()
    val currentPairedSession by connectionViewModel.currentPairedSession.collectAsState()
    val pairedDevices by connectionViewModel.pairedDevices.collectAsState()
    var showSecurityDetails by remember { mutableStateOf(false) }

    val dashboardStatus = activeConnection?.dashboardLastStatus
    val dashboardUrl = activeConnection?.resolvedDashboardUrl.orEmpty()
    val dashboardTransport = when {
        dashboardUrl.startsWith("https://", ignoreCase = true) -> "HTTPS"
        dashboardUrl.startsWith("http://", ignoreCase = true) -> "HTTP"
        else -> stringResource(R.string.active_section_not_configured)
    }
    val pairedLabel = if (currentPairedSession != null) {
        stringResource(R.string.active_section_paired)
    } else {
        stringResource(R.string.active_section_not_paired)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                val postureColor = if (connectionSecurity.isEncrypted) {
                    com.hermesandroid.relay.ui.theme.RelayRefresh.Green
                } else {
                    MaterialTheme.colorScheme.error
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showSecurityDetails = true }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shield,
                        contentDescription = null,
                        tint = postureColor,
                        modifier = Modifier.size(48.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = if (connectionSecurity.isEncrypted) {
                                stringResource(R.string.active_section_protected)
                            } else {
                                stringResource(R.string.active_section_not_encrypted)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = postureColor,
                        )
                        Text(
                            text = if (connectionSecurity.isEncrypted) {
                                stringResource(R.string.active_section_no_security_issues)
                            } else {
                                stringResource(R.string.active_section_unencrypted_transport)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                if (dashboardUrl.isNotBlank()) {
                    SecurityFactRow(
                        icon = Icons.Filled.Dashboard,
                        label = stringResource(R.string.active_section_dashboard_session),
                        value = when {
                            dashboardStatus?.authenticated == true -> stringResource(R.string.active_section_signed_in)
                            dashboardStatus?.authRequired == true -> stringResource(R.string.active_section_sign_in_required)
                            dashboardStatus?.reachable == true -> stringResource(R.string.active_section_available)
                            else -> stringResource(R.string.active_section_not_checked)
                        },
                        positive = dashboardStatus?.authenticated == true,
                    )
                    HorizontalDivider()
                }
                SecurityFactRow(
                    icon = Icons.Filled.Shield,
                    label = stringResource(R.string.active_section_transport),
                    value = if (isTailscaleDetected) "$dashboardTransport · Tailscale" else dashboardTransport,
                    positive = dashboardTransport == "HTTPS" || isTailscaleDetected,
                )
                HorizontalDivider()
                SecurityFactRow(
                    icon = Icons.Filled.VpnKey,
                    label = stringResource(R.string.active_section_credential_storage),
                    value = when {
                        currentPairedSession?.hasHardwareStorage == true -> stringResource(R.string.active_section_hardware_backed)
                        currentPairedSession != null -> stringResource(R.string.active_section_encrypted_storage)
                        else -> stringResource(R.string.active_section_no_relay_credential)
                    },
                    positive = currentPairedSession?.hasHardwareStorage == true,
                )
                HorizontalDivider()
                SecurityFactRow(
                    icon = Icons.Filled.Link,
                    label = stringResource(R.string.active_section_relay_session),
                    value = pairedLabel,
                    positive = currentPairedSession != null,
                )
            }
        }

        Text(text = stringResource(R.string.active_section_access), style = MaterialTheme.typography.titleSmall)
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                SecurityAccessRow(
                    icon = Icons.Filled.Devices,
                    label = stringResource(R.string.active_section_paired_devices),
                    value = stringResource(R.string.active_section_device_count, pairedDevices.size),
                    onClick = onNavigateToPairedDevices,
                )
                HorizontalDivider()
                SecurityAccessRow(
                    icon = Icons.Filled.Schedule,
                    label = stringResource(R.string.active_section_session_activity),
                    value = stringResource(R.string.active_section_last_checked_just_now),
                    onClick = onNavigateToPairedDevices,
                )
            }
        }

        Text(text = stringResource(R.string.active_section_actions), style = MaterialTheme.typography.titleSmall)
        OutlinedButton(
            onClick = { connectionViewModel.clearDashboardSession() },
            enabled = dashboardStatus?.authenticated == true,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Logout, contentDescription = null)
            Text(stringResource(R.string.active_section_sign_out_dashboard), modifier = Modifier.padding(start = 8.dp))
        }
        OutlinedButton(
            onClick = onRevokeRelay,
            enabled = currentPairedSession != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.LinkOff, contentDescription = null)
            Text(stringResource(R.string.active_section_revoke_relay), modifier = Modifier.padding(start = 8.dp))
        }
        Text(
            text = stringResource(R.string.active_section_credentials_encrypted),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (showSecurityDetails) {
        ConnectionSecuritySheet(
            security = connectionSecurity,
            onDismiss = { showSecurityDetails = false },
        )
    }
}

@Composable
private fun SecurityAccessRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun SecurityFactRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    positive: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.size(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = if (positive) {
                com.hermesandroid.relay.ui.theme.RelayRefresh.Green
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * Routes section for the tabbed connection detail (ADR 24 multi-endpoint).
 * Current-route panel, Tailscale nudges, Re-check / Auto controls, the
 * per-route list ([EndpointsCard]) and the add/edit [RouteEditorDialog].
 *
 * Relocated from the old inline active-card Route block so behavior is
 * unchanged; because it now owns a dedicated tab it drops the old
 * "Show available routes (N)" expander and always shows the list.
 */
@Composable
fun ActiveCardRoutesSection(
    connectionViewModel: ConnectionViewModel,
    connection: Connection,
    liveState: RelayUiState?,
    onEditDashboard: () -> Unit,
) {
    val context = LocalContext.current
    val endpoints: List<EndpointCandidate> by connectionViewModel.observeDeviceEndpoints()
        .collectAsState(initial = emptyList())
    val activeEndpoint by connectionViewModel.activeEndpoint.collectAsState()
    val isTailscaleDetected by connectionViewModel.isTailscaleDetected.collectAsState()
    // Plain val (not a delegated property) so the `is Done && .winner` smart
    // cast below resolves — a `by` delegate would break it.
    val routeProbeStatus: ConnectionViewModel.RouteProbeStatus =
        connectionViewModel.routeProbeStatus.collectAsState().value
    val routeProbeOutcomes by connectionViewModel.routeProbeOutcomes.collectAsState()

    var preferredRole by remember(connection.id) {
        mutableStateOf(connectionViewModel.getPreferredEndpointRole())
    }
    val manualOverrideRole by connectionViewModel.manualRouteOverride.collectAsState()
    val manualSwitchActive = manualOverrideRole != null &&
        !manualOverrideRole.equals(preferredRole, ignoreCase = true)
    var routeEditorOpen by remember(connection.id) { mutableStateOf(false) }
    var routeEditorOriginal by remember(connection.id) {
        mutableStateOf<EndpointCandidate?>(null)
    }
    val hasTailscaleRoute = endpoints.any { it.role.equals("tailscale", ignoreCase = true) }
    val tailscalePreferred = preferredRole?.equals("tailscale", ignoreCase = true) == true
    val routeNeedsAttention = activeEndpoint == null && liveState != RelayUiState.Connected
    val showTailscaleUnavailableHint =
        hasTailscaleRoute && !isTailscaleDetected && (tailscalePreferred || routeNeedsAttention)
    val tailscaleLaunchIntent = remember(context) {
        context.packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
    }
    val isRouteProbing = routeProbeStatus is ConnectionViewModel.RouteProbeStatus.Probing
    val dashboardOnly = endpoints.isEmpty() && connection.resolvedDashboardUrl.isNotBlank()
    val dashboardReachable = connection.dashboardLastStatus?.reachable == true
    val probeCameUpEmpty = !dashboardOnly && activeEndpoint == null &&
        routeProbeStatus is ConnectionViewModel.RouteProbeStatus.Done &&
        routeProbeStatus.winner == null

    // Pre-resolve strings for route status labels
    val checkingRoutesText = stringResource(R.string.active_section_checking_routes)
    val noRouteReachableText = stringResource(R.string.active_section_no_route_reachable)
    val resolvingText = stringResource(R.string.active_section_resolving)
    val usingSavedUrlText = stringResource(R.string.active_section_using_saved_url)

    val activeRouteLabel = when {
        activeEndpoint != null -> activeEndpoint!!.displayLabel()
        dashboardOnly -> stringResource(R.string.active_section_primary_dashboard)
        isRouteProbing -> checkingRoutesText
        probeCameUpEmpty -> noRouteReachableText
        else -> resolvingText
    }
    val activeRouteHost = activeEndpoint?.primaryRouteUrl()
        ?: if (dashboardOnly) connection.resolvedDashboardUrl
        else usingSavedUrlText.format(connection.apiServerUrl.ifBlank { connection.relayUrl })

    // Pre-resolve other UI strings
    val chooseHowPhoneReachesText = stringResource(
        R.string.active_section_choose_how_phone_reaches_named,
        connection.label,
    )
    val currentRouteText = stringResource(R.string.active_section_current_route)
    val noRoutesAnsweredProbeText = stringResource(R.string.active_section_no_routes_answered_probe)
    val tailscaleRouteNotActiveText = stringResource(R.string.active_section_tailscale_route_not_active)
    val connectPhoneInTailscaleText = stringResource(R.string.active_section_connect_phone_in_tailscale)
    val openTailscaleText = stringResource(R.string.active_section_open_tailscale)
    val checkingText = stringResource(R.string.active_section_checking)
    val recheckText = stringResource(R.string.active_section_recheck)
    val phoneOnTailscaleNoRouteText = stringResource(R.string.active_section_phone_on_tailscale_no_route)
    val addServerTailscaleUrlText = stringResource(R.string.active_section_add_server_tailscale_url)
    val addTailscaleRouteText = stringResource(R.string.active_section_add_tailscale_route)
    val autoText = stringResource(R.string.active_section_auto)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = chooseHowPhoneReachesText,
            style = MaterialTheme.typography.titleSmall,
        )

        Surface(
            color = if (dashboardOnly) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (dashboardOnly) {
                        Icon(
                            imageVector = Icons.Filled.Dashboard,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Text(
                        text = if (dashboardOnly) {
                            stringResource(R.string.active_section_primary_dashboard)
                        } else {
                            currentRouteText.format(activeRouteLabel)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (probeCameUpEmpty) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    if (isRouteProbing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    if (dashboardOnly) {
                        Surface(
                            color = if (dashboardReachable) {
                                com.hermesandroid.relay.ui.theme.RelayRefresh.Green.copy(alpha = 0.18f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                text = if (dashboardReachable) {
                                    stringResource(R.string.active_section_reachable_badge)
                                } else {
                                    stringResource(R.string.active_section_unchecked_badge)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (dashboardReachable) {
                                    com.hermesandroid.relay.ui.theme.RelayRefresh.Green
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
                Text(
                    text = activeRouteHost,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (dashboardOnly) {
                    Text(
                        text = stringResource(R.string.active_section_dashboard_home_lan),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = if (dashboardReachable) {
                                com.hermesandroid.relay.ui.theme.RelayRefresh.Green
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = when {
                                dashboardReachable && connection.dashboardLastStatus?.authenticated == true ->
                                    stringResource(R.string.active_section_signed_in)
                                dashboardReachable -> stringResource(R.string.active_section_dashboard_reachable)
                                connection.dashboardLastStatus == null -> stringResource(R.string.active_section_dashboard_not_checked)
                                else -> stringResource(R.string.active_section_dashboard_unreachable)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { connectionViewModel.probeNow() },
                            enabled = !isRouteProbing,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(if (isRouteProbing) checkingText else recheckText)
                        }
                        OutlinedButton(
                            onClick = onEditDashboard,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(stringResource(R.string.active_section_edit))
                        }
                    }
                }
                if (probeCameUpEmpty) {
                    Text(
                        text = noRoutesAnsweredProbeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (showTailscaleUnavailableHint) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = tailscaleRouteNotActiveText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Text(
                        text = connectPhoneInTailscaleText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (tailscaleLaunchIntent != null) {
                            TextButton(
                                onClick = {
                                    runCatching { context.startActivity(tailscaleLaunchIntent) }
                                },
                                contentPadding = PaddingValues(horizontal = 0.dp),
                            ) {
                                Text(openTailscaleText)
                            }
                        }
                        TextButton(
                            onClick = { connectionViewModel.probeNow() },
                            enabled = !isRouteProbing,
                            contentPadding = PaddingValues(horizontal = 0.dp),
                        ) {
                            Text(if (isRouteProbing) checkingText else recheckText)
                        }
                    }
                }
            }
        }

        if (isTailscaleDetected && !hasTailscaleRoute) {
            // Phone is on Tailscale but this connection has nothing to roam to —
            // the strongest signal the user wants remote access but never set it
            // up. Offer the route editor directly.
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = phoneOnTailscaleNoRouteText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Text(
                        text = addServerTailscaleUrlText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    TextButton(
                        onClick = {
                            routeEditorOriginal = null
                            routeEditorOpen = true
                        },
                        contentPadding = PaddingValues(horizontal = 0.dp),
                    ) {
                        Text(addTailscaleRouteText)
                    }
                }
            }
        }

        if (!dashboardOnly) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { connectionViewModel.probeNow() },
                    enabled = !isRouteProbing,
                ) {
                    Text(if (isRouteProbing) checkingText else recheckText)
                }
                if (preferredRole != null || manualSwitchActive) {
                    TextButton(
                        onClick = {
                            connectionViewModel.setPreferredEndpointRole(null)
                            preferredRole = null
                        },
                    ) {
                        Text(autoText)
                    }
                }
            }
        }

        if (dashboardOnly) {
            Text(
                text = stringResource(R.string.active_section_fallback_routes),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lan,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp),
                    )
                    Text(
                        text = stringResource(R.string.active_section_no_fallback_routes),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.active_section_no_fallback_routes_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            routeEditorOriginal = null
                            routeEditorOpen = true
                        },
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(stringResource(R.string.active_section_add_api_fallback))
                    }
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.active_section_route_selection),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.active_section_automatic),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        imageVector = Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp).size(18.dp),
                    )
                }
            }
        } else {
            EndpointsCard(
                endpoints = endpoints,
                activeEndpoint = activeEndpoint,
                isProbing = isRouteProbing,
                outcomeFor = { candidate ->
                    routeProbeOutcomes[connectionViewModel.routeOutcomeKey(candidate)]
                },
                preferredRole = preferredRole,
                manualOverrideRole = manualOverrideRole,
                onUseNow = { candidate -> connectionViewModel.useRouteNow(candidate.role) },
                onCancelUseNow = { connectionViewModel.useRouteNow(null) },
                onPreferEndpoint = { candidate ->
                    connectionViewModel.setPreferredEndpointRole(candidate.role)
                    preferredRole = candidate.role
                },
                onClearPreferred = {
                    connectionViewModel.setPreferredEndpointRole(null)
                    preferredRole = null
                },
                onProbeNow = { connectionViewModel.probeNow() },
                onViewPin = { candidate -> connectionViewModel.lookupEndpointPin(candidate) },
                onAddRoute = {
                    routeEditorOriginal = null
                    routeEditorOpen = true
                },
                onEditRoute = { candidate ->
                    routeEditorOriginal = candidate
                    routeEditorOpen = true
                },
                onRemoveRoute = { candidate -> connectionViewModel.removeExtraRoute(candidate) },
            )
        }

        if (routeEditorOpen) {
            RouteEditorDialog(
                original = routeEditorOriginal,
                onSave = { role, apiUrl, onResult ->
                    connectionViewModel.saveExtraRoute(
                        role = role,
                        apiUrl = apiUrl,
                        original = routeEditorOriginal,
                        onResult = onResult,
                    )
                },
                onDismiss = { routeEditorOpen = false },
            )
        }
    }
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
