package com.hermesandroid.relay.ui.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hermesandroid.relay.R
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.diagnostics.CheckStatus
import com.hermesandroid.relay.diagnostics.DiagnosticCategory
import com.hermesandroid.relay.diagnostics.DiagnosticLogEntry
import com.hermesandroid.relay.diagnostics.DiagnosticSeverity
import com.hermesandroid.relay.network.shared.ConnectivityObserver
import com.hermesandroid.relay.network.upstream.GatewayAvailability
import com.hermesandroid.relay.network.upstream.ServerCapabilities
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiagnosticsScreenTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun dashboardOnlyHealthy_chatPassesWhileOptionalApiAndRelayRemainUnknown() {
        val checks = buildStatusChecks(
            network = ConnectivityObserver.Status.Available,
            dashboardUrl = "https://hermes.example.com",
            gatewayAvailability = GatewayAvailability.Ready,
            apiConfigured = false,
            apiHealth = ConnectionViewModel.HealthStatus.Unknown,
            apiUrl = "",
            capabilities = ServerCapabilities.DISCONNECTED,
            authState = AuthState.Unpaired,
            relayConfigured = false,
            relayHealth = ConnectionViewModel.HealthStatus.Unknown,
            relayReady = false,
            relayUpdateInfo = null,
            voiceReady = true,
            relayVoiceReady = false,
            recentEntries = emptyList(),
            context = context,
        )

        val dashboardName = context.getString(R.string.cw_dashboard) + " / " +
            context.getString(R.string.chat_settings_gateway)
        assertEquals(CheckStatus.Pass, checks.single { it.name == dashboardName }.status)
        assertEquals(
            CheckStatus.Pass,
            checks.single { it.name == context.getString(R.string.diag_check_chat_transport) }.status,
        )
        assertEquals(
            CheckStatus.Unknown,
            checks.single {
                it.name == context.getString(R.string.active_section_optional_api_fallback)
            }.status,
        )
        assertEquals(
            CheckStatus.Unknown,
            checks.single {
                it.name == context.getString(R.string.active_section_optional_relay)
            }.status,
        )
        assertFalse(
            "Absent optional API/Relay surfaces must not make chat fail",
            checks.any {
                it.name == context.getString(R.string.diag_check_chat_transport) &&
                    it.status == CheckStatus.Fail
            },
        )
    }

    @Test
    fun failedStatusCheckLeadsWithActionableSuggestion() {
        val suggestion = "Verify Relay is running and listening on the configured host and port."
        val entry = DiagnosticLogEntry(
            timestampMs = 123L,
            category = DiagnosticCategory.Relay,
            severity = DiagnosticSeverity.Error,
            title = "Relay connection refused",
            detail = "Failed to connect",
            suggestion = suggestion,
        )

        val checks = buildStatusChecks(
            network = ConnectivityObserver.Status.Available,
            dashboardUrl = "https://hermes.example.com",
            gatewayAvailability = GatewayAvailability.Ready,
            apiConfigured = false,
            apiHealth = ConnectionViewModel.HealthStatus.Unknown,
            apiUrl = "",
            capabilities = ServerCapabilities.DISCONNECTED,
            authState = AuthState.Unpaired,
            relayConfigured = true,
            relayHealth = ConnectionViewModel.HealthStatus.Unreachable,
            relayReady = false,
            relayUpdateInfo = null,
            voiceReady = true,
            relayVoiceReady = false,
            recentEntries = listOf(entry),
            context = context,
        )

        val relay = checks.single {
            it.name == context.getString(R.string.active_section_optional_relay)
        }
        assertEquals(CheckStatus.Fail, relay.status)
        assertEquals(suggestion, relay.reason)
        assertEquals(123L, relay.timestampMs)
    }
}
