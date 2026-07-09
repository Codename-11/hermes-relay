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
): List<StatusCheck> {
    // Most recent ERROR for a category (entries are oldest -> newest).
    fun recentError(category: DiagnosticCategory): DiagnosticLogEntry? =
        recentEntries.lastOrNull {
            it.category == category && it.severity == DiagnosticSeverity.Error
        }

    fun DiagnosticLogEntry.message(): String = detail ?: title

    val checks = mutableListOf<StatusCheck>()

    // 1) Network reachability.
    checks += when (network) {
        ConnectivityObserver.Status.Available ->
            StatusCheck("Network", CheckStatus.Pass, reason = "Device is online")
        ConnectivityObserver.Status.Lost ->
            StatusCheck(
                "Network", CheckStatus.Fail,
                reason = "Network connection lost",
                category = DiagnosticCategory.Endpoint,
            )
        ConnectivityObserver.Status.Unavailable ->
            StatusCheck(
                "Network", CheckStatus.Warn,
                reason = "No active network detected",
                category = DiagnosticCategory.Endpoint,
            )
    }

    // 2) API server reachability.
    val host = DiagnosticsLog.sanitizeUrl(apiUrl)
    val apiErr = recentError(DiagnosticCategory.Api)
    checks += when (apiHealth) {
        ConnectionViewModel.HealthStatus.Reachable ->
            StatusCheck(
                "API server", CheckStatus.Pass,
                reason = host?.let { "Reachable at $it" } ?: "Reachable",
                category = DiagnosticCategory.Api,
            )
        ConnectionViewModel.HealthStatus.Unreachable ->
            StatusCheck(
                "API server", CheckStatus.Fail,
                reason = apiErr?.message() ?: (host?.let { "Not reachable at $it" } ?: "Not reachable"),
                category = DiagnosticCategory.Api,
                timestampMs = apiErr?.timestampMs,
            )
        ConnectionViewModel.HealthStatus.Probing ->
            StatusCheck(
                "API server", CheckStatus.Unknown,
                reason = "Probing…",
                category = DiagnosticCategory.Api,
            )
        ConnectionViewModel.HealthStatus.Unknown ->
            StatusCheck(
                "API server", CheckStatus.Unknown,
                reason = "Not checked yet",
                category = DiagnosticCategory.Api,
            )
    }

    // 3) Server capabilities (which chat surfaces the server advertises).
    checks += when {
        !capabilities.healthy ->
            StatusCheck(
                "Server capabilities", CheckStatus.Unknown,
                reason = "Not probed — no healthy server yet",
                category = DiagnosticCategory.Api,
            )
        capabilities.sessionsChatStream ->
            StatusCheck(
                "Server capabilities", CheckStatus.Pass,
                reason = "Native session streaming available",
                category = DiagnosticCategory.Api,
            )
        capabilities.sessionsApi || capabilities.runs || capabilities.portable ->
            StatusCheck(
                "Server capabilities", CheckStatus.Warn,
                reason = "No session SSE — falling back to ${capabilities.preferredChatEndpoint()}",
                category = DiagnosticCategory.Api,
            )
        else ->
            StatusCheck(
                "Server capabilities", CheckStatus.Fail,
                reason = "No usable chat endpoint advertised",
                category = DiagnosticCategory.Api,
            )
    }

    // 4) Chat transport readiness.
    val chatErr = recentError(DiagnosticCategory.Session) ?: recentError(DiagnosticCategory.Api)
    checks += if (chatReady) {
        StatusCheck(
            "Chat transport", CheckStatus.Pass,
            reason = "Ready · ${capabilities.preferredChatEndpoint()}",
            category = DiagnosticCategory.Session,
        )
    } else {
        val degraded = apiHealth == ConnectionViewModel.HealthStatus.Reachable
        StatusCheck(
            "Chat transport",
            if (degraded) CheckStatus.Warn else CheckStatus.Fail,
            reason = chatErr?.message() ?: "Not ready — no usable streaming endpoint",
            category = DiagnosticCategory.Session,
            timestampMs = chatErr?.timestampMs,
        )
    }

    // 5) Relay / pairing auth.
    val authErr = recentError(DiagnosticCategory.Auth)
    checks += when (authState) {
        is AuthState.Paired ->
            StatusCheck(
                "Pairing / auth", CheckStatus.Pass,
                reason = "Relay session active",
                category = DiagnosticCategory.Auth,
            )
        is AuthState.Pairing ->
            StatusCheck(
                "Pairing / auth", CheckStatus.Warn,
                reason = "Pairing in progress…",
                category = DiagnosticCategory.Auth,
            )
        is AuthState.Failed ->
            StatusCheck(
                "Pairing / auth", CheckStatus.Fail,
                reason = authState.reason,
                category = DiagnosticCategory.Auth,
                timestampMs = authErr?.timestampMs,
            )
        is AuthState.Unpaired ->
            StatusCheck(
                "Pairing / auth", CheckStatus.Unknown,
                reason = "Not paired — vanilla Hermes path doesn't require pairing",
                category = DiagnosticCategory.Auth,
            )
    }

    // 6) Relay server (optional — Unknown when not paired/configured).
    val relayErr = recentError(DiagnosticCategory.Relay)
    checks += when {
        !relayConfigured ->
            StatusCheck(
                "Relay server", CheckStatus.Unknown,
                reason = "Not paired — relay features are optional",
                category = DiagnosticCategory.Relay,
            )
        relayReady ->
            StatusCheck(
                "Relay server", CheckStatus.Pass,
                reason = "Connected",
                category = DiagnosticCategory.Relay,
            )
        relayHealth == ConnectionViewModel.HealthStatus.Reachable ->
            StatusCheck(
                "Relay server", CheckStatus.Warn,
                reason = relayErr?.message() ?: "Reachable but session not ready",
                category = DiagnosticCategory.Relay,
                timestampMs = relayErr?.timestampMs,
            )
        else ->
            StatusCheck(
                "Relay server", CheckStatus.Fail,
                reason = relayErr?.message() ?: "Configured but not reachable",
                category = DiagnosticCategory.Relay,
                timestampMs = relayErr?.timestampMs,
            )
    }

    // 7) Voice readiness.
    val voiceErr = recentError(DiagnosticCategory.Voice)
    checks += if (voiceReady) {
        StatusCheck(
            "Voice", CheckStatus.Pass,
            reason = if (relayVoiceReady) "Relay voice ready" else "Standard voice ready",
            category = DiagnosticCategory.Voice,
        )
    } else {
        StatusCheck(
            "Voice",
            if (voiceErr != null) CheckStatus.Fail else CheckStatus.Unknown,
            reason = voiceErr?.message() ?: "Not configured or unavailable",
            category = DiagnosticCategory.Voice,
            timestampMs = voiceErr?.timestampMs,
        )
    }

    return checks
}
