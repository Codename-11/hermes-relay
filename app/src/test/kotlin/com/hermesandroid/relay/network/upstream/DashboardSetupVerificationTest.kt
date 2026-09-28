package com.hermesandroid.relay.network.upstream

import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class DashboardSetupVerificationTest {
    private val server = MockWebServer()
    private val client = DashboardApiClient(server.url("/").toString().trimEnd('/'))
    @After fun close() { client.shutdown(); server.shutdown() }
    private fun response(body: String, code: Int = 200) = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    @Test fun publicStatusDoesNotAuthorizeLoopbackProtectedRoutes() = runTest {
        response("""{"auth_required":false}""")
        response("""{"detail":"Unauthorized"}""", 401)
        assertTrue(runCatching { client.verifySetup() }.exceptionOrNull() is DashboardLocalAuthenticationRequiredException)
        assertEquals("/api/status", server.takeRequest().path)
        assertEquals("/api/auth/me", server.takeRequest().path)
        assertEquals(2, server.requestCount)
    }

    @Test fun ordinaryExpiredSessionRequestsSignInWithoutClaimingMisconfiguration() = runTest {
        response("""{"auth_required":true}""")
        response("""{"error":"session_expired"}""", 401)
        val result = client.verifySetup()
        assertFalse(result.authenticated)
        assertFalse(result.ticketAvailable)
        assertTrue(result.status.authRequired)
        assertEquals(2, server.requestCount)
    }

    @Test fun successfulRetryProvesSessionAndTicketOnTheSameDashboard() = runTest {
        response("""{"auth_required":true}""")
        response("""{"detail":"Unauthorized"}""", 401)
        assertFalse(client.verifySetup().authenticated)
        response("""{"auth_required":true}""")
        response("""{"authenticated":true,"username":"fixture"}""")
        response("""{"ticket":"fixture-ticket","ttl_seconds":30}""")
        assertTrue(client.verifySetup().authenticated)
        val paths = (1..5).map { server.takeRequest().path }
        assertEquals(listOf("/api/status", "/api/auth/me", "/api/status", "/api/auth/me", "/api/auth/ws-ticket"), paths)
    }

    @Test fun ticketTransportFailureCannotProduceReady() = runTest {
        response("""{"auth_required":true}""")
        response("""{"authenticated":true}""")
        response("""{"detail":"temporarily unavailable"}""", 503)
        assertTrue(runCatching { client.verifySetup() }.exceptionOrNull() is IOException)
    }

    @Test fun expiryBetweenSessionVerificationAndTicketReturnsToSignIn() = runTest {
        response("""{"auth_required":true}""")
        response("""{"authenticated":true}""")
        response("""{"error":"session_expired"}""", 401)
        assertFalse(client.verifySetup().authenticated)
    }
}
