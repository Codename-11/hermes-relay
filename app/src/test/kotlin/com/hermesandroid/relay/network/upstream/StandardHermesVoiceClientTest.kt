package com.hermesandroid.relay.network.upstream

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class StandardHermesVoiceClientTest {
    @Test
    fun dashboardAudioTimeoutBoundsMatchUpstreamDesktopFloorAndRelayCap() {
        assertEquals(180L, standardHermesDashboardAudioTimeoutSeconds(90L))
        assertEquals(180L, standardHermesDashboardAudioTimeoutSeconds(180L))
        assertEquals(420L, standardHermesDashboardAudioTimeoutSeconds(420L))
        assertEquals(600L, standardHermesDashboardAudioTimeoutSeconds(900L))
    }

    @Test
    fun dashboardAudioClientRaisesOnlyDashboardAudioTimeouts() {
        val baseClient = DashboardApiClient.defaultClient()
        val audioClient = standardHermesDashboardAudioClient(baseClient)
        val audioTimeoutMillis = TimeUnit.SECONDS.toMillis(180L).toInt()

        assertEquals(audioTimeoutMillis, audioClient.callTimeoutMillis)
        assertEquals(audioTimeoutMillis, audioClient.readTimeoutMillis)
        assertEquals(audioTimeoutMillis, audioClient.writeTimeoutMillis)
        assertEquals(baseClient.connectTimeoutMillis, audioClient.connectTimeoutMillis)

        assertEquals(TimeUnit.SECONDS.toMillis(45L).toInt(), baseClient.readTimeoutMillis)
        assertEquals(TimeUnit.SECONDS.toMillis(30L).toInt(), baseClient.writeTimeoutMillis)
    }
}
