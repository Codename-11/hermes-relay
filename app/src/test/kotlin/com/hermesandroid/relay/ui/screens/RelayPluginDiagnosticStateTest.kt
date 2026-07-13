package com.hermesandroid.relay.ui.screens

import com.hermesandroid.relay.network.relay.RelayHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayPluginDiagnosticStateTest {

    @Test
    fun configurationAndConnectionGateVersionReporting() {
        assertEquals(
            RelayPluginDiagnosticState.NotConfigured,
            classifyRelayPlugin(false, false, null),
        )
        assertEquals(
            RelayPluginDiagnosticState.Unavailable,
            classifyRelayPlugin(true, false, null),
        )
        assertEquals(
            RelayPluginDiagnosticState.VersionUnknown,
            classifyRelayPlugin(true, true, null),
        )
    }

    @Test
    fun currentAndAvailableVersionsRemainDistinct() {
        assertEquals(
            RelayPluginDiagnosticState.Current("1.4.1"),
            classifyRelayPlugin(
                true,
                true,
                RelayHttpClient.RelayUpdateInfo(current = "1.4.1"),
            ),
        )
        assertEquals(
            RelayPluginDiagnosticState.UpdateAvailable("1.4.0", "1.4.1"),
            classifyRelayPlugin(
                true,
                true,
                RelayHttpClient.RelayUpdateInfo(
                    current = "1.4.0",
                    latest = "1.4.1",
                    updateAvailable = true,
                ),
            ),
        )
    }

    @Test
    fun serverReportedCheckErrorsAreWarningsNotVersions() {
        val state = classifyRelayPlugin(
            true,
            true,
            RelayHttpClient.RelayUpdateInfo(error = "release lookup unavailable"),
        )

        assertTrue(state is RelayPluginDiagnosticState.CheckError)
        assertEquals("release lookup unavailable", (state as RelayPluginDiagnosticState.CheckError).message)
    }
}
