package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.diagnostics.CheckStatus
import com.hermesandroid.relay.diagnostics.DiagnosticCategory
import com.hermesandroid.relay.diagnostics.DiagnosticLogEntry
import com.hermesandroid.relay.diagnostics.DiagnosticSeverity
import com.hermesandroid.relay.diagnostics.DiagnosticsLog
import com.hermesandroid.relay.diagnostics.StatusCheck
import com.hermesandroid.relay.network.shared.ConnectivityObserver
import com.hermesandroid.relay.network.upstream.ServerCapabilities
import com.hermesandroid.relay.ui.components.DiagnosticDetailDialog
import com.hermesandroid.relay.ui.components.DiagnosticsLogPanel
import com.hermesandroid.relay.ui.components.StatusCheckTimeline
import com.hermesandroid.relay.viewmodel.ConnectionViewModel

/**
 * Dedicated Diagnostics screen — replaces the old modal bottom sheet. Hosts a
 * vertical timeline of subsystem **status checks** (with failure reasons) at
 * the top, then the existing "Recent diagnostics" activity log below it.
 *
 * The checks are derived **read-only** from the flows [ConnectionViewModel]
 * already exposes (network / API health, capability snapshot, auth + relay
 * readiness, voice readiness) plus the recent [DiagnosticsLog] — no new probing
 * is started here, so the screen stays an honest snapshot of current state.
 * A failing check whose reason came from a logged error is tappable and opens
 * that entry's full [DiagnosticDetailDialog].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    connectionViewModel: ConnectionViewModel,
    onBack: () -> Unit,
) {
    val network by connectionViewModel.networkStatus.collectAsState()
    val apiHealth by connectionViewModel.apiServerHealth.collectAsState()
    val apiUrl by connectionViewModel.apiServerUrl.collectAsState()
    val capabilities by connectionViewModel.serverCapabilities.collectAsState()
    val authState by connectionViewModel.authState.collectAsState()
    val chatReady by connectionViewModel.chatReady.collectAsState()
    val relayConfigured by connectionViewModel.relayConfigured.collectAsState()
    val relayHealth by connectionViewModel.relayServerHealth.collectAsState()
    val relayReady by connectionViewModel.relayReady.collectAsState()
    val voiceReady by connectionViewModel.voiceReady.collectAsState()
    val relayVoiceReady by connectionViewModel.relayVoiceReady.collectAsState()
    val entries by DiagnosticsLog.entries.collectAsState()

    val context = androidx.compose.ui.platform.LocalContext.current

    val checks = remember(
        network, apiHealth, apiUrl, capabilities, authState, chatReady,
        relayConfigured, relayHealth, relayReady, voiceReady, relayVoiceReady, entries,
    ) {
        buildStatusChecks(
            network = network,
            apiHealth = apiHealth,
            apiUrl = apiUrl,
            capabilities = capabilities,
            authState = authState,
            chatReady = chatReady,
            relayConfigured = relayConfigured,
            relayHealth = relayHealth,
            relayReady = relayReady,
            voiceReady = voiceReady,
            relayVoiceReady = relayVoiceReady,
            recentEntries = entries,
            context = context,
        )
    }

    // Tapping a check backed by a concrete log entry opens its full detail.
    var selectedEntry by remember { mutableStateOf<DiagnosticLogEntry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diag_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.diag_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.diag_status),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            StatusCheckTimeline(
                checks = checks,
                onCheckClick = { check ->
                    selectedEntry = entries.lastOrNull { entry ->
                        check.category != null &&
                            entry.category == check.category &&
                            (check.timestampMs == null || entry.timestampMs == check.timestampMs)
                    }
                },
            )

            Text(
                text = stringResource(R.string.diag_recent_diagnostics),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.diag_recent_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            DiagnosticsLogPanel(
                title = stringResource(R.string.diag_activity_log),
                limit = 80,
                showCategory = true,
                showClear = true,
                showSeverityFilter = true,
            )
        }
    }

    selectedEntry?.let { entry ->
        DiagnosticDetailDialog(entry = entry, onDismiss = { selectedEntry = null })
    }
}

// -----------------------------------------------------------------------------
// Read-only check derivation
// -----------------------------------------------------------------------------

/**
 * Derive the status-check list from a snapshot of connection state + the recent
 * [DiagnosticsLog]. Pure and side-effect free (no probing) so it is trivially
 * testable and re-runs cheaply whenever any input flow emits.
 *
 * When a check fails or warns and a matching-category error sits in
 * [recentEntries], that entry's message becomes the reason and its timestamp is
 * stamped onto the check — which is what makes the row tappable for full detail.
 */
internal fun buildStatusChecks(
    network: ConnectivityObserver.Status,
    apiHealth: ConnectionViewModel.HealthStatus,
    apiUrl: String,
    capabilities: ServerCapabilities,
    authState: AuthState,
    chatReady: Boolean,
    relayConfigured: Boolean,
    relayHealth: ConnectionViewModel.HealthStatus,
    relayReady: Boolean,
    voiceReady: Boolean,
    relayVoiceReady: Boolean,
    recentEntries: List<DiagnosticLogEntry>,
    context: android.content.Context,
): List<StatusCheck> {
    // Most recent ERROR for a category (entries are oldest -> newest).
    fun recentError(category: DiagnosticCategory): DiagnosticLogEntry? =
        recentEntries.lastOrNull {
            it.category == category && it.severity == DiagnosticSeverity.Error
        }

    fun DiagnosticLogEntry.message(): String = detail ?: title

    val checks = mutableListOf<StatusCheck>()

    // 1) Network reachability.
    val networkLabel = context.getString(R.string.diag_check_network)
    val deviceOnline = context.getString(R.string.diag_check_device_online)
    val networkLost = context.getString(R.string.diag_check_network_lost)
    val noNetwork = context.getString(R.string.diag_check_no_network)
    checks += when (network) {
        ConnectivityObserver.Status.Available ->
            StatusCheck(networkLabel, CheckStatus.Pass, reason = deviceOnline)
        ConnectivityObserver.Status.Lost ->
            StatusCheck(
                networkLabel, CheckStatus.Fail,
                reason = networkLost,
                category = DiagnosticCategory.Endpoint,
            )
        ConnectivityObserver.Status.Unavailable ->
            StatusCheck(
                networkLabel, CheckStatus.Warn,
                reason = noNetwork,
                category = DiagnosticCategory.Endpoint,
            )
    }

    // 2) API server reachability.
    val apiLabel = context.getString(R.string.diag_check_api_server)
    val reachableAt = context.getString(R.string.diag_check_reachable_at)
    val reachable = context.getString(R.string.diag_check_reachable)
    val notReachableAt = context.getString(R.string.diag_check_not_reachable_at)
    val notReachable = context.getString(R.string.diag_check_not_reachable)
    val probing = context.getString(R.string.diag_check_probing)
    val notChecked = context.getString(R.string.diag_check_not_checked)
    val host = DiagnosticsLog.sanitizeUrl(apiUrl)
    val apiErr = recentError(DiagnosticCategory.Api)
    checks += when (apiHealth) {
        ConnectionViewModel.HealthStatus.Reachable ->
            StatusCheck(
                apiLabel, CheckStatus.Pass,
                reason = host?.let { context.getString(R.string.diag_check_reachable_at, it) } ?: reachable,
                category = DiagnosticCategory.Api,
            )
        ConnectionViewModel.HealthStatus.Unreachable ->
            StatusCheck(
                apiLabel, CheckStatus.Fail,
                reason = apiErr?.message() ?: (host?.let { context.getString(R.string.diag_check_not_reachable_at, it) } ?: notReachable),
                category = DiagnosticCategory.Api,
                timestampMs = apiErr?.timestampMs,
            )
        ConnectionViewModel.HealthStatus.Probing ->
            StatusCheck(
                apiLabel, CheckStatus.Unknown,
                reason = probing,
                category = DiagnosticCategory.Api,
            )
        ConnectionViewModel.HealthStatus.Unknown ->
            StatusCheck(
                apiLabel, CheckStatus.Unknown,
                reason = notChecked,
                category = DiagnosticCategory.Api,
            )
    }

    // 3) Server capabilities (which chat surfaces the server advertises).
    val capsLabel = context.getString(R.string.diag_check_server_capabilities)
    val capsNoHealthy = context.getString(R.string.diag_check_no_server_yet)
    val capsNativeSessions = context.getString(R.string.diag_check_native_sessions)
    val capsSseFallback = context.getString(R.string.diag_check_sse_fallback)
    val capsNoEndpoint = context.getString(R.string.diag_check_no_chat_endpoint)
    checks += when {
        !capabilities.healthy ->
            StatusCheck(
                capsLabel, CheckStatus.Unknown,
                reason = capsNoHealthy,
                category = DiagnosticCategory.Api,
            )
        capabilities.sessionsChatStream ->
            StatusCheck(
                capsLabel, CheckStatus.Pass,
                reason = capsNativeSessions,
                category = DiagnosticCategory.Api,
            )
        capabilities.sessionsApi || capabilities.runs || capabilities.portable ->
            StatusCheck(
                capsLabel, CheckStatus.Warn,
                reason = context.getString(R.string.diag_check_sse_fallback, capabilities.preferredChatEndpoint()),
                category = DiagnosticCategory.Api,
            )
        else ->
            StatusCheck(
                capsLabel, CheckStatus.Fail,
                reason = capsNoEndpoint,
                category = DiagnosticCategory.Api,
            )
    }

    // 4) Chat transport readiness.
    val chatLabel = context.getString(R.string.diag_check_chat_transport)
    val chatReadyFmt = context.getString(R.string.diag_check_ready_with)
    val chatNotReady = context.getString(R.string.diag_check_not_ready)
    val chatErr = recentError(DiagnosticCategory.Session) ?: recentError(DiagnosticCategory.Api)
    checks += if (chatReady) {
        StatusCheck(
            chatLabel, CheckStatus.Pass,
            reason = context.getString(R.string.diag_check_ready_with, capabilities.preferredChatEndpoint()),
            category = DiagnosticCategory.Session,
        )
    } else {
        val degraded = apiHealth == ConnectionViewModel.HealthStatus.Reachable
        StatusCheck(
            chatLabel,
            if (degraded) CheckStatus.Warn else CheckStatus.Fail,
            reason = chatErr?.message() ?: chatNotReady,
            category = DiagnosticCategory.Session,
            timestampMs = chatErr?.timestampMs,
        )
    }

    // 5) Relay / pairing auth.
    val authLabel = context.getString(R.string.diag_check_pairing_auth)
    val authRelayActive = context.getString(R.string.diag_check_relay_active)
    val authPairingProg = context.getString(R.string.diag_check_pairing_progress)
    val authNotPaired = context.getString(R.string.diag_check_not_paired)
    val authErr = recentError(DiagnosticCategory.Auth)
    checks += when (authState) {
        is AuthState.Paired ->
            StatusCheck(
                authLabel, CheckStatus.Pass,
                reason = authRelayActive,
                category = DiagnosticCategory.Auth,
            )
        is AuthState.Pairing ->
            StatusCheck(
                authLabel, CheckStatus.Warn,
                reason = authPairingProg,
                category = DiagnosticCategory.Auth,
            )
        is AuthState.Failed ->
            StatusCheck(
                authLabel, CheckStatus.Fail,
                reason = authState.reason,
                category = DiagnosticCategory.Auth,
                timestampMs = authErr?.timestampMs,
            )
        is AuthState.Unpaired ->
            StatusCheck(
                authLabel, CheckStatus.Unknown,
                reason = authNotPaired,
                category = DiagnosticCategory.Auth,
            )
    }

    // 6) Relay server (optional — Unknown when not paired/configured).
    val relayLabel = context.getString(R.string.diag_check_relay_server)
    val relayNotConfigured = context.getString(R.string.diag_check_relay_not_configured)
    val relayConnected = context.getString(R.string.diag_check_connected)
    val relayReachableNotReady = context.getString(R.string.diag_check_reachable_not_ready)
    val relayConfiguredNotReachable = context.getString(R.string.diag_check_configured_not_reachable)
    val relayErr = recentError(DiagnosticCategory.Relay)
    checks += when {
        !relayConfigured ->
            StatusCheck(
                relayLabel, CheckStatus.Unknown,
                reason = relayNotConfigured,
                category = DiagnosticCategory.Relay,
            )
        relayReady ->
            StatusCheck(
                relayLabel, CheckStatus.Pass,
                reason = relayConnected,
                category = DiagnosticCategory.Relay,
            )
        relayHealth == ConnectionViewModel.HealthStatus.Reachable ->
            StatusCheck(
                relayLabel, CheckStatus.Warn,
                reason = relayErr?.message() ?: relayReachableNotReady,
                category = DiagnosticCategory.Relay,
                timestampMs = relayErr?.timestampMs,
            )
        else ->
            StatusCheck(
                relayLabel, CheckStatus.Fail,
                reason = relayErr?.message() ?: relayConfiguredNotReachable,
                category = DiagnosticCategory.Relay,
                timestampMs = relayErr?.timestampMs,
            )
    }

    // 7) Voice readiness.
    val voiceLabel = context.getString(R.string.diag_check_voice)
    val voiceRelayReady = context.getString(R.string.diag_check_voice_relay)
    val voiceStandardReady = context.getString(R.string.diag_check_voice_standard)
    val voiceNotConfigured = context.getString(R.string.diag_check_voice_not_configured)
    val voiceErr = recentError(DiagnosticCategory.Voice)
    checks += if (voiceReady) {
        StatusCheck(
            voiceLabel, CheckStatus.Pass,
            reason = if (relayVoiceReady) voiceRelayReady else voiceStandardReady,
            category = DiagnosticCategory.Voice,
        )
    } else {
        StatusCheck(
            voiceLabel,
            if (voiceErr != null) CheckStatus.Fail else CheckStatus.Unknown,
            reason = voiceErr?.message() ?: voiceNotConfigured,
            category = DiagnosticCategory.Voice,
            timestampMs = voiceErr?.timestampMs,
        )
    }

    return checks
}
