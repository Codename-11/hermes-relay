package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.data.SessionActivityOwner
import com.hermesandroid.relay.data.SessionLiveStatus
import com.hermesandroid.relay.network.upstream.GatewayActiveSession
import com.hermesandroid.relay.network.upstream.GatewayActiveSessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionActivityResolutionTest {
    private val alpha = SessionActivityOwner.of("connection", "alpha", "same")
    private val beta = SessionActivityOwner.of("connection", "beta", "same")

    @Test
    fun `duplicate stored ids stay unresolved without exact runtime binding`() {
        val result = resolveGatewayActiveSessions(
            sessions = listOf(active("runtime-beta", GatewayActiveSessionStatus.Working)),
            directory = setOf(alpha, beta),
            currentOwner = alpha,
        )

        assertNull(result.runtimes.single().owner)
        assertTrue(result.ambiguous)
        assertTrue(result.ambiguousForCurrent)
    }

    @Test
    fun `exact foreground runtime binding resolves duplicate stored id`() {
        val result = resolveGatewayActiveSessions(
            sessions = listOf(active("runtime-alpha", GatewayActiveSessionStatus.Waiting)),
            directory = setOf(alpha, beta),
            currentOwner = alpha,
            currentRuntimeId = "runtime-alpha",
        )

        assertEquals(alpha, result.runtimes.single().owner)
        assertEquals(SessionLiveStatus.Waiting, result.runtimes.single().status)
    }

    @Test
    fun `unscoped stored id stays unresolved even when bounded directory looks unique`() {
        val unique = SessionActivityOwner.of("connection", "beta", "unique")
        val result = resolveGatewayActiveSessions(
            sessions = listOf(
                GatewayActiveSession(
                    runtimeSessionId = "runtime",
                    storedSessionId = "unique",
                    status = GatewayActiveSessionStatus.Starting,
                    lastActiveEpochSeconds = 1.0,
                ),
            ),
            directory = setOf(alpha, unique),
            currentOwner = alpha,
        )

        assertNull(result.runtimes.single().owner)
        assertTrue(result.ambiguous)
    }

    @Test
    fun `client known detached runtime resolves exact profile owner`() {
        val unique = SessionActivityOwner.of("connection", "beta", "unique")
        val result = resolveGatewayActiveSessions(
            sessions = listOf(
                GatewayActiveSession(
                    runtimeSessionId = "runtime",
                    storedSessionId = "unique",
                    status = GatewayActiveSessionStatus.Starting,
                    lastActiveEpochSeconds = 1.0,
                ),
            ),
            directory = setOf(alpha, unique),
            currentOwner = alpha,
            knownOwnersByRuntime = mapOf("runtime" to unique),
        )

        assertEquals(unique, result.runtimes.single().owner)
        assertEquals(SessionLiveStatus.Starting, result.runtimes.single().status)
    }

    private fun active(runtimeId: String, status: GatewayActiveSessionStatus) =
        GatewayActiveSession(
            runtimeSessionId = runtimeId,
            storedSessionId = "same",
            status = status,
            lastActiveEpochSeconds = 1.0,
        )
}
