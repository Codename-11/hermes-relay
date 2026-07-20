package com.hermesandroid.relay.network.upstream

import com.hermesandroid.relay.network.upstream.models.SkillListResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    @Test
    fun toolsetInventory_parserPreservesEnabledAndResolvedTools() {
        val parsed = parseToolsetListBody(
            Json { ignoreUnknownKeys = true },
            """
            {
              "object": "list",
              "platform": "api_server",
              "data": [{
                "name": "relay",
                "label": "Relay",
                "enabled": true,
                "configured": true,
                "tools": ["relay_status", "android_phone_status"]
              }]
            }
            """.trimIndent(),
        )

        assertEquals("relay", parsed?.single()?.name)
        assertTrue(parsed?.single()?.enabled == true)
        assertEquals(listOf("relay_status", "android_phone_status"), parsed?.single()?.tools)
    }

    // --- buildApiRequestOrNull (#131 guard, streaming paths) ---

    @Test
    fun buildApiRequestOrNull_validUrlsBuildARequest() {
        assertTrue(buildApiRequestOrNull("http://192.168.1.10:8642/api/sessions/x/chat/stream") != null)
        assertTrue(buildApiRequestOrNull("https://hermes.example.com/v1/runs") != null)
    }

    @Test
    fun buildApiRequestOrNull_malformedUrlsReturnNullInsteadOfThrowing() {
        // Each would make Request.Builder.url(String) throw
        // IllegalArgumentException on the streaming send path.
        for (bad in listOf(
            "http://", // empty host
            "http://in valid host:8642/v1/runs", // space in host
            "not-a-url/api/sessions/x/chat/stream", // no scheme (corrupt baseUrl)
            "/api/sessions/x/chat/stream", // blank baseUrl
        )) {
            assertNull("expected null for malformed url '$bad'", buildApiRequestOrNull(bad))
        }
    }

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

    // --- ServerCapabilities endpoint resolution ---

    @Test
    fun serverCapabilities_preferredEndpoint_prefersSessionsStream() {
        val capabilities = ServerCapabilities(
            sessionsApi = true,
            sessionsChatStream = true,
            runs = true,
            portable = true,
            healthy = true,
        )

        assertEquals("sessions", capabilities.preferredChatEndpoint())
    }

    @Test
    fun serverCapabilities_preferredEndpoint_prefersCompletionsOverRuns() {
        val capabilities = ServerCapabilities(
            sessionsApi = true,
            sessionsChatStream = false,
            runs = true,
            portable = true,
            healthy = true,
        )

        assertEquals("completions", capabilities.preferredChatEndpoint())
    }

    @Test
    fun serverCapabilities_preferredEndpoint_usesRunsOnlyWhenExplicitlyStreaming() {
        val capabilities = ServerCapabilities(
            sessionsApi = true,
            sessionsChatStream = false,
            runs = true,
            portable = false,
            healthy = true,
        )

        assertEquals("runs", capabilities.preferredChatEndpoint())
    }

    @Test
    fun serverCapabilities_preferredEndpoint_usesCompletionsForIssue52Shape() {
        val capabilities = ServerCapabilities(
            sessionsApi = true,
            sessionsChatStream = false,
            runs = false,
            portable = true,
            healthy = true,
        )

        assertEquals("completions", capabilities.preferredChatEndpoint())
        assertEquals(ChatMode.ENHANCED_HERMES, capabilities.toChatMode())
    }

    @Test
    fun parseCapabilitiesBody_prefersNativeUpstreamSessionFeatures() {
        val body = """
            {
                "object": "hermes.api_server.capabilities",
                "features": {
                    "chat_completions": true,
                    "run_events_sse": true,
                    "session_resources": true,
                    "session_chat_streaming": true,
                    "skills_api": true
                },
                "endpoints": {
                    "chat_completions": {"method": "POST", "path": "/v1/chat/completions"},
                    "run_events": {"method": "GET", "path": "/v1/runs/{run_id}/events"},
                    "sessions": {"method": "GET", "path": "/api/sessions"},
                    "session_chat_stream": {"method": "POST", "path": "/api/sessions/{session_id}/chat/stream"},
                    "skills": {"method": "GET", "path": "/v1/skills"},
                    "toolsets": {"method": "GET", "path": "/v1/toolsets"}
                }
            }
        """.trimIndent()

        val capabilities = parseCapabilitiesBody(Json { ignoreUnknownKeys = true }, body)

        assertEquals(true, capabilities?.sessionsApi)
        assertEquals(true, capabilities?.sessionsChatStream)
        assertEquals(true, capabilities?.portable)
        assertEquals(true, capabilities?.runs)
        assertEquals("sessions", capabilities?.preferredChatEndpoint())
    }

    @Test
    fun parseCapabilitiesBody_returnsNullForUnrelatedJson() {
        val body = """{"status":"ok"}"""

        val capabilities = parseCapabilitiesBody(Json { ignoreUnknownKeys = true }, body)

        assertNull(capabilities)
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
        val url = "$baseUrl/api/sessions?limit=200"
        assertEquals("http://localhost:8642/api/sessions?limit=200", url)
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

    // --- Skills endpoint compatibility ---

    @Test
    fun skillEndpointOrder_prefersUpstreamV1ThenLegacyApiFallback() {
        assertEquals(listOf("/v1/skills", "/api/skills"), HERMES_SKILL_ENDPOINTS)
    }

    @Test
    fun skillListResponse_parsesUpstreamV1DataEnvelope() {
        val body = """
            {
                "object": "list",
                "data": [
                    {"name": "android", "description": "Control phone", "category": "android"}
                ]
            }
        """.trimIndent()

        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<SkillListResponse>(body)

        assertEquals(1, parsed.data?.size)
        assertEquals("android", parsed.data?.first()?.name)
    }

    @Test
    fun parseSkillListBody_acceptsUpstreamV1DataEnvelope() {
        val body = """
            {
                "object": "list",
                "data": [
                    {"name": "android", "description": "Control phone", "category": "android"}
                ]
            }
        """.trimIndent()

        val parsed = parseSkillListBody(Json { ignoreUnknownKeys = true }, body)

        assertEquals(listOf("android"), parsed?.map { it.name })
    }

    @Test
    fun urlConstruction_deleteSession() {
        val baseUrl = "http://localhost:8642"
        val sessionId = "sess-789"
        val url = "$baseUrl/api/sessions/$sessionId"
        assertEquals("http://localhost:8642/api/sessions/sess-789", url)
    }
}
