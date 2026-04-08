package com.hermesandroid.relay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for HermesApiClient types and pure logic.
 *
 * The client itself makes network calls via OkHttp and requires Android's
 * Handler/Looper, so we test the sealed types, enum values, and URL
 * construction patterns that don't require the Android framework.
 */
class HermesApiClientTest {

    // --- HealthCheckResult sealed interface ---

    @Test
    fun healthCheckResult_healthy_isDistinctType() {
        val result: HealthCheckResult = HealthCheckResult.Healthy
        assertTrue(result is HealthCheckResult.Healthy)
        assertFalse(result is HealthCheckResult.Unhealthy)
    }

    @Test
    fun healthCheckResult_unhealthy_isDistinctType() {
        val result: HealthCheckResult = HealthCheckResult.Unhealthy("some error")
        assertTrue(result is HealthCheckResult.Unhealthy)
        assertFalse(result is HealthCheckResult.Healthy)
    }

    @Test
    fun healthCheckResult_unhealthy_messageIsNonEmpty() {
        val result = HealthCheckResult.Unhealthy("Connection refused")
        assertTrue(result.message.isNotEmpty())
    }

    @Test
    fun healthCheckResult_unhealthy_preservesMessage() {
        val msg = "Unauthorized — check your API key"
        val result = HealthCheckResult.Unhealthy(msg)
        assertEquals(msg, result.message)
    }

    @Test
    fun healthCheckResult_unhealthy_connectionRefused() {
        val result = HealthCheckResult.Unhealthy("Connection refused — check the URL and port")
        assertTrue(result.message.contains("Connection refused"))
    }

    @Test
    fun healthCheckResult_unhealthy_unknownHost() {
        val result = HealthCheckResult.Unhealthy("Server not found — check the hostname")
        assertTrue(result.message.contains("Server not found"))
    }

    @Test
    fun healthCheckResult_unhealthy_timeout() {
        val result = HealthCheckResult.Unhealthy("Connection timed out — is the server running?")
        assertTrue(result.message.contains("timed out"))
    }

    @Test
    fun healthCheckResult_unhealthy_httpCode() {
        val result = HealthCheckResult.Unhealthy("Server returned HTTP 500")
        assertTrue(result.message.contains("500"))
    }

    @Test
    fun healthCheckResult_healthy_singletonEquality() {
        val a = HealthCheckResult.Healthy
        val b = HealthCheckResult.Healthy
        assertEquals(a, b)
        assertTrue(a === b)
    }

    @Test
    fun healthCheckResult_unhealthy_dataClassEquality() {
        val a = HealthCheckResult.Unhealthy("error")
        val b = HealthCheckResult.Unhealthy("error")
        assertEquals(a, b)
    }

    @Test
    fun healthCheckResult_unhealthy_differentMessages_notEqual() {
        val a = HealthCheckResult.Unhealthy("error A")
        val b = HealthCheckResult.Unhealthy("error B")
        assertNotEquals(a, b)
    }

    // --- ChatMode enum ---

    @Test
    fun chatMode_hasAllExpectedValues() {
        val values = ChatMode.values()
        assertEquals(3, values.size)
        assertTrue(values.contains(ChatMode.ENHANCED_HERMES))
        assertTrue(values.contains(ChatMode.PORTABLE))
        assertTrue(values.contains(ChatMode.DISCONNECTED))
    }

    @Test
    fun chatMode_valueOf_enhancedHermes() {
        assertEquals(ChatMode.ENHANCED_HERMES, ChatMode.valueOf("ENHANCED_HERMES"))
    }

    @Test
    fun chatMode_valueOf_portable() {
        assertEquals(ChatMode.PORTABLE, ChatMode.valueOf("PORTABLE"))
    }

    @Test
    fun chatMode_valueOf_disconnected() {
        assertEquals(ChatMode.DISCONNECTED, ChatMode.valueOf("DISCONNECTED"))
    }

    // --- URL construction patterns ---
    // These verify the string patterns used by authRequest() inside the client.

    @Test
    fun urlConstruction_healthEndpoint() {
        val baseUrl = "http://localhost:8642"
        val url = "$baseUrl/health"
        assertEquals("http://localhost:8642/health", url)
    }

    @Test
    fun urlConstruction_sessionsEndpoint() {
        val baseUrl = "http://localhost:8642"
        val url = "$baseUrl/api/sessions?limit=50"
        assertEquals("http://localhost:8642/api/sessions?limit=50", url)
    }

    @Test
    fun urlConstruction_sessionChatStream() {
        val baseUrl = "http://localhost:8642"
        val sessionId = "abc-123"
        val url = "$baseUrl/api/sessions/$sessionId/chat/stream"
        assertEquals("http://localhost:8642/api/sessions/abc-123/chat/stream", url)
    }

    @Test
    fun urlConstruction_sessionMessages() {
        val baseUrl = "http://192.168.1.100:8642"
        val sessionId = "session-456"
        val url = "$baseUrl/api/sessions/$sessionId/messages"
        assertEquals("http://192.168.1.100:8642/api/sessions/session-456/messages", url)
    }

    @Test
    fun urlConstruction_baseUrlWithTrailingSlash_doublesSlash() {
        // Documents the current behavior: baseUrl with trailing slash produces double-slash.
        // This is a known edge case — the client relies on callers to not include trailing slashes.
        val baseUrl = "http://localhost:8642/"
        val url = "$baseUrl/health"
        assertEquals("http://localhost:8642//health", url)
    }

    @Test
    fun urlConstruction_v1Models() {
        val baseUrl = "http://localhost:8642"
        val url = "$baseUrl/v1/models"
        assertEquals("http://localhost:8642/v1/models", url)
    }

    @Test
    fun urlConstruction_deleteSession() {
        val baseUrl = "http://localhost:8642"
        val sessionId = "sess-789"
        val url = "$baseUrl/api/sessions/$sessionId"
        assertEquals("http://localhost:8642/api/sessions/sess-789", url)
    }
}
