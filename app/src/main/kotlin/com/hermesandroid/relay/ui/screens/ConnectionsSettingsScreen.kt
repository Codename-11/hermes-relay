package com.hermesandroid.relay.ui.screens

import android.content.Context
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.network.relay.RelayUrlDeriver
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.viewmodel.RelayUiState
import com.hermesandroid.relay.viewmodel.StandardVoiceAvailability
import com.hermesandroid.relay.viewmodel.statusText
import java.util.concurrent.TimeUnit

/**
 * Level-1 list of Hermes connections. Reachable via Settings → Connections.
 *
 * Each connection is a flat, tappable card — label + an `Active` badge (the
 * active connection) + a one-line status + the steps/timeline capability
 * summary ([ConnectionSurfaceSummary]). Tapping drills into the tabbed
 * [ConnectionDetailScreen] where rename / re-pair / revoke / remove, routes,
 * advanced setup, and relay sessions all live.
 *
 * This screen used to also host the active connection's entire deep body
 * inline (Features / Routes / Advanced / Security + a 5-button action row),
 * which made it overloaded and the list unscannable. That content now lives
 * on the detail screen; this screen is purely the list + the Add-connection FAB.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsSettingsScreen(
    connections: List<Connection>,
    activeConnectionId: String?,
    // Live WSS state for the currently-active connection — surfaced in the
    // active card's summary so users see "Connected" / "Reconnecting…" /
    // "Stale — tap to reconnect" in real time. Ignored for non-active cards.
    activeRelayUiState: RelayUiState,
    onOpenConnection: (id: String) -> Unit,
    onAddConnection: () -> Unit,
    onBack: () -> Unit,
    // `null` means no VM wired (test harness / @Preview); the active card's
    // live summary falls back to the static pairing metadata.
    connectionViewModel: ConnectionViewModel? = null,
) {
    val activeRelayConfigured: Boolean = if (connectionViewModel != null) {
        val configured by connectionViewModel.relayConfigured.collectAsState()
        configured
    } else {
        false
    }

    // Kick a WSS reconnect on entry in case the user landed here from a Stale
    // chip (same intent as the old inline screen).
    LaunchedEffect(Unit) {
        connectionViewModel?.reconnectIfStale()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conn_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.conn_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddConnection,
                icon = { Icon(imageVector = Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.conn_add_connection)) },
            )
        },
    ) { innerPadding ->
        if (connections.isEmpty()) {
            // Shown in practice only during tests / after a wipe; cold start
            // seeds a default connection, so the list is rarely truly empty.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = stringResource(R.string.conn_no_connections), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.conn_no_connections_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(connections, key = { it.id }) { connection ->
                    val isActive = connection.id == activeConnectionId
                    ConnectionListCard(
                        connection = connection,
                        isActive = isActive,
                        liveState = if (isActive) activeRelayUiState else null,
                        activeConnectionViewModel = if (isActive) connectionViewModel else null,
                        relayConfigured = if (isActive) {
                            activeRelayConfigured
                        } else {
                            connection.hasConfiguredRelay()
                        },
                        onClick = { onOpenConnection(connection.id) },
                    )
                }
                // Footer spacer so the last card isn't hidden by the FAB.
                item { Spacer(modifier = Modifier.height(72.dp)) }
            }
        }
    }
}

/**
 * One flat, tappable connection card: title row (label + Active badge +
 * chevron), a one-line status subtitle, and the steps/timeline capability
 * summary. The whole card opens [ConnectionDetailScreen]; the summary rows
 * are display-only (their taps fall through to the card).
 */
@Composable
private fun ConnectionListCard(
    connection: Connection,
    isActive: Boolean,
    liveState: RelayUiState?,
    activeConnectionViewModel: ConnectionViewModel?,
    relayConfigured: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    // Active card: muted indigo wash instead of full-strength primaryContainer —
    // a card-sized fill of the brand blue overwhelmed body text (2026-06-10
    // feedback); small accents keep the vivid blue.
    val containerColor = if (isActive) {
        com.hermesandroid.relay.ui.theme.RelayRefresh.ElectricMuted.copy(alpha = 0.42f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── Title row ────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = connection.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isActive) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Text(text = stringResource(R.string.conn_active), modifier = Modifier.padding(horizontal = 6.dp))
                    }
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            // ── Subtitle: hostname + status ──────────────────────────────
            val hostname = Connection.extractDefaultLabel(connection.apiServerUrl)
            val hasStandardApi = connection.apiServerUrl.isNotBlank()
            val pairedStatus = when {
                liveState != null &&
                    (connection.pairedAt != null || liveState != RelayUiState.NotConfigured) ->
                    liveState.statusText(connectedLabel = stringResource(R.string.conn_connected))
                connection.pairedAt != null -> formatPairedRelative(context, connection.pairedAt)
                hasStandardApi -> stringResource(R.string.conn_standard_not_paired)
                else -> stringResource(R.string.conn_not_paired)
            }
            val statusColor = if (liveState == RelayUiState.Stale) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                text = "$hostname • $pairedStatus",
                style = MaterialTheme.typography.bodySmall,
                color = statusColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // ── Steps/timeline summary (every card) ──────────────────────
            ConnectionSurfaceSummary(
                connection = connection,
                isActive = isActive,
                liveState = liveState,
                activeConnectionViewModel = activeConnectionViewModel,
                relayConfigured = relayConfigured,
                // The dashboard "Sign in" row, when shown, just opens the
                // detail — the same destination as the card tap.
                onOpenDashboard = onClick,
            )
        }
    }
}

@Composable
private fun ConnectionSurfaceSummary(
    connection: Connection,
    isActive: Boolean,
    liveState: RelayUiState?,
    activeConnectionViewModel: ConnectionViewModel?,
    relayConfigured: Boolean,
    onOpenDashboard: () -> Unit,
) {
    val activeApiReachable: Boolean? = if (activeConnectionViewModel != null) {
        val reachable by activeConnectionViewModel.apiServerReachable.collectAsState()
        reachable
    } else {
        null
    }
    val activeApiHealth: ConnectionViewModel.HealthStatus? = if (activeConnectionViewModel != null) {
        val health by activeConnectionViewModel.apiServerHealth.collectAsState()
        health
    } else {
        null
    }
    val activeConnection: Connection? = if (activeConnectionViewModel != null) {
        val current by activeConnectionViewModel.activeConnection.collectAsState()
        current
    } else {
        null
    }
    val activeVoiceReady: Boolean? = if (activeConnectionViewModel != null) {
        val ready by activeConnectionViewModel.voiceReady.collectAsState()
        ready
    } else {
        null
    }
    val standardVoiceAvailability: StandardVoiceAvailability? =
        if (activeConnectionViewModel != null) {
            val availability by activeConnectionViewModel.standardVoiceAvailability.collectAsState()
            availability
        } else {
            null
        }
    val dashboardStatus = (activeConnection ?: connection).dashboardLastStatus
    val dashboardSignInRequired =
        dashboardStatus?.authRequired == true && dashboardStatus.authenticated != true

    val apiText = when {
        connection.apiServerUrl.isBlank() -> stringResource(R.string.conn_api_missing)
        activeApiHealth == ConnectionViewModel.HealthStatus.Probing -> stringResource(R.string.conn_api_checking)
        activeApiReachable == true -> stringResource(R.string.conn_api_ready)
        isActive && activeApiReachable == false -> stringResource(R.string.conn_api_offline)
        else -> stringResource(R.string.conn_api_configured)
    }
    val apiTone = when {
        activeApiReachable == true -> SummaryTone.Good
        connection.apiServerUrl.isBlank() -> SummaryTone.Warning
        isActive && activeApiReachable == false -> SummaryTone.Warning
        else -> SummaryTone.Neutral
    }

    val dashboardText = when {
        connection.resolvedDashboardUrl.isBlank() -> stringResource(R.string.conn_dashboard_missing)
        dashboardStatus == null -> stringResource(R.string.conn_dashboard_unchecked)
        !dashboardStatus.reachable -> stringResource(R.string.conn_dashboard_offline)
        dashboardSignInRequired -> stringResource(R.string.conn_dashboard_sign_in)
        dashboardStatus.authenticated == true -> stringResource(R.string.conn_dashboard_signed_in)
        else -> stringResource(R.string.conn_dashboard_available)
    }
    val dashboardTone = when {
        dashboardStatus?.authenticated == true && dashboardStatus.reachable -> SummaryTone.Good
        dashboardSignInRequired -> SummaryTone.Info
        connection.resolvedDashboardUrl.isBlank() || (dashboardStatus != null && !dashboardStatus.reachable) -> SummaryTone.Warning
        else -> SummaryTone.Neutral
    }

    val relayText = when {
        !relayConfigured -> stringResource(R.string.conn_relay_optional)
        liveState != null -> liveState.statusText(connectedLabel = stringResource(R.string.conn_relay_ready))
        connection.pairedAt != null -> stringResource(R.string.conn_relay_paired)
        connection.relayUrl.isNotBlank() -> stringResource(R.string.conn_relay_configured)
        else -> stringResource(R.string.conn_relay_configure)
    }
    val relayTone = when {
        !relayConfigured -> SummaryTone.Neutral
        liveState == RelayUiState.Connected -> SummaryTone.Good
        liveState == RelayUiState.Stale || liveState == RelayUiState.Disconnected -> SummaryTone.Warning
        else -> SummaryTone.Info
    }

    val voiceText = when {
        activeVoiceReady == true -> stringResource(R.string.conn_voice_ready)
        standardVoiceAvailability == StandardVoiceAvailability.SignInRequired -> stringResource(R.string.conn_voice_sign_in)
        standardVoiceAvailability == StandardVoiceAvailability.Unsupported -> stringResource(R.string.conn_voice_unsupported)
        standardVoiceAvailability == StandardVoiceAvailability.Unreachable -> stringResource(R.string.conn_voice_offline)
        standardVoiceAvailability == StandardVoiceAvailability.Unknown && isActive -> stringResource(R.string.conn_voice_checking)
        relayConfigured -> stringResource(R.string.conn_voice_relay)
        else -> stringResource(R.string.conn_voice_optional)
    }
    val voiceTone = when {
        activeVoiceReady == true -> SummaryTone.Good
        standardVoiceAvailability == StandardVoiceAvailability.SignInRequired || relayConfigured -> SummaryTone.Info
        standardVoiceAvailability == StandardVoiceAvailability.Unsupported || standardVoiceAvailability == StandardVoiceAvailability.Unreachable -> SummaryTone.Warning
        else -> SummaryTone.Neutral
    }

    // A single grouped surface with dot + label + value rows — the
    // steps/timeline vocabulary shared with the detail's Features list.
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
            ConnectionSurfaceRow(label = stringResource(R.string.conn_api_label), value = apiText, tone = apiTone)
            SurfaceRowDivider()
            ConnectionSurfaceRow(
                label = stringResource(R.string.conn_dashboard_label),
                value = dashboardText,
                tone = dashboardTone,
                onClick = if (dashboardSignInRequired) onOpenDashboard else null,
            )
            SurfaceRowDivider()
            ConnectionSurfaceRow(
                label = stringResource(R.string.conn_voice_label),
                value = voiceText,
                tone = voiceTone,
                onClick = if (standardVoiceAvailability == StandardVoiceAvailability.SignInRequired) onOpenDashboard else null,
            )
            SurfaceRowDivider()
            ConnectionSurfaceRow(label = stringResource(R.string.conn_relay_label), value = relayText, tone = relayTone)
        }
    }
}

private enum class SummaryTone { Neutral, Good, Info, Warning }

/** Hairline divider between summary rows — inset so it reads as a list. */
@Composable
private fun SurfaceRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 12.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

/**
 * One health line: status dot + label + value. The dot carries the tone so
 * the value text stays short and the row stays light.
 */
@Composable
private fun ConnectionSurfaceRow(
    label: String,
    value: String,
    tone: SummaryTone,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val dotColor = when (tone) {
        SummaryTone.Good -> com.hermesandroid.relay.ui.theme.RelayRefresh.Green
        SummaryTone.Info -> MaterialTheme.colorScheme.primary
        SummaryTone.Warning -> MaterialTheme.colorScheme.error
        SummaryTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    val valueColor = when (tone) {
        SummaryTone.Good -> com.hermesandroid.relay.ui.theme.RelayRefresh.Green
        SummaryTone.Info -> MaterialTheme.colorScheme.primary
        SummaryTone.Warning -> MaterialTheme.colorScheme.error
        SummaryTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 9.dp),
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
    }
}

private fun Connection.hasConfiguredRelay(): Boolean {
    val trimmedRelayUrl = relayUrl.trim()
    return pairedAt != null ||
        trimmedRelayUrl.isNotBlank() &&
        !RelayUrlDeriver.isAutoManagedRelayUrl(trimmedRelayUrl, apiServerUrl)
}

/**
 * Hand-rolled "N minutes ago" formatter for the card subtitle. `DateUtils`
 * returns awkward copy ("in 0 minutes") for small deltas.
 */
private fun formatPairedRelative(context: Context, pairedAtMillis: Long): String {
    val deltaMs = System.currentTimeMillis() - pairedAtMillis
    if (deltaMs < 0) return context.getString(R.string.conn_just_paired)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(deltaMs)
    val hours = TimeUnit.MILLISECONDS.toHours(deltaMs)
    val days = TimeUnit.MILLISECONDS.toDays(deltaMs)
    return when {
        minutes < 1L -> context.getString(R.string.conn_just_paired)
        minutes < 60L -> context.resources.getQuantityString(R.plurals.conn_paired_minutes_ago, minutes.toInt(), minutes)
        hours < 24L -> context.resources.getQuantityString(R.plurals.conn_paired_hours_ago, hours.toInt(), hours)
        days < 30L -> context.resources.getQuantityString(R.plurals.conn_paired_days_ago, days.toInt(), days)
        else -> context.getString(R.string.conn_paired)
    }
}
