package com.hermesandroid.relay.network.upstream

import com.hermesandroid.relay.network.upstream.models.SkillListResponse
import kotlinx.serialization.json.Json
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
    fun getMessages_pagesApiFallbackOldestFirst() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(apiMessagePageResponse(start = 0, count = 500, returned = 500))
            server.enqueue(apiMessagePageResponse(start = 500, count = 1, returned = 1))

            val messages = HermesApiClient(server.url("/").toString(), "test-key")
                .getMessages("sess-a")

            val first = server.takeRequest().requestUrl!!
            val second = server.takeRequest().requestUrl!!
            assertEquals(listOf("0", "500"), listOf(first, second).map { it.queryParameter("offset") })
            assertEquals(listOf("oldest", "oldest"), listOf(first, second).map { it.queryParameter("order") })
            assertEquals((0..500).map(Int::toString), messages.map { it.id })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun getMessages_acceptsLegacyUnpaginatedApiEnvelope() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"object":"list","data":[{"id":"1","role":"user","content":"hi"}]}""",
                ),
            )

            val messages = HermesApiClient(server.url("/").toString(), "test-key")
                .getMessages("sess-a")

            assertEquals(listOf("1"), messages.map { it.id })
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

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
                    "model_options": true,
                    "session_model_lock": true,
                    "skills_api": true
                },
                "endpoints": {
                    "chat_completions": {"method": "POST", "path": "/v1/chat/completions"},
                    "run_events": {"method": "GET", "path": "/v1/runs/{run_id}/events"},
                    "sessions": {"method": "GET", "path": "/api/sessions"},
                    "session_chat_stream": {"method": "POST", "path": "/api/sessions/{session_id}/chat/stream"},
                    "model_options": {"method": "GET", "path": "/api/model/options"},
                    "session_model_lock": {"method": "POST", "path": "/api/sessions/{session_id}/model"},
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
        assertEquals(true, capabilities?.modelOptions)
        assertEquals(true, capabilities?.sessionModelLock)
        assertEquals("sessions", capabilities?.preferredChatEndpoint())
    }

    @Test
    fun providerModelOptions_preserveAuthenticatedAndUnavailableInventory() {
        val parsed = parseApiProviderModelOptionsBody(
            Json { ignoreUnknownKeys = true },
            """
            {
              "model": "grok-4.3",
              "provider": "xai",
              "providers": [
                {
                  "slug": "xai",
                  "name": "xAI",
                  "authenticated": true,
                  "is_current": true,
                  "models": ["grok-4.3", "grok-4.2"],
                  "capabilities": {
                    "grok-4.3": {
                      "reasoning": true,
                      "reasoning_efforts": ["low", "high", "max"],
                      "reasoning_efforts_exact": true
                    }
                  },
                  "unavailable_models": ["grok-4.2"],
                  "free_tier": true,
                  "total_models": 2
                },
                {
                  "slug": "anthropic",
                  "name": "Anthropic",
                  "authenticated": false,
                  "models": ["claude-opus-4-6"]
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("grok-4.3", parsed?.currentModel)
        assertEquals("xai", parsed?.currentProvider)
        assertEquals(listOf("grok-4.3", "grok-4.2"), parsed?.providers?.first()?.models)
        assertEquals(listOf("grok-4.2"), parsed?.providers?.first()?.unavailableModels)
        assertTrue(parsed?.providers?.first()?.authenticated == true)
        assertFalse(parsed?.providers?.last()?.authenticated == true)
        assertEquals(
            listOf("low", "high", "max"),
            parsed?.providers?.first()?.capabilities?.get("grok-4.3")?.reasoningEfforts,
        )
        assertEquals(
            true,
            parsed?.providers?.first()?.capabilities?.get("grok-4.3")?.reasoningEffortsExact,
        )
    }

    @Test
    fun providerModelOptions_requireProviderEnvelope() {
        assertNull(parseApiProviderModelOptionsBody(Json, """{"data":[]}"""))
        assertNull(parseApiProviderModelOptionsBody(Json, "not-json"))
    }

    @Test
    fun modelLockAck_requiresExplicitRequestedRouteAndAcceptedState() {
        val ack = parseApiModelLockAck(
            Json,
            """
            {
              "object": "hermes.session.model_lock",
              "session_id": "session-1",
              "runtime": {
                "requested": {"model": "grok-4.3", "provider": "xai"},
                "effective": {"model": "grok-4.3", "provider": "xai"},
                "model_lock": "accepted"
              }
            }
            """.trimIndent(),
        )

        assertEquals("session-1", ack?.sessionId)
        assertEquals("grok-4.3", ack?.model)
        assertEquals("xai", ack?.provider)
        assertEquals("accepted", ack?.state)
        assertEquals("grok-4.3", ack?.effectiveModel)
        assertEquals("xai", ack?.effectiveProvider)
        assertNull(parseApiModelLockAck(Json, """{"session_id":"session-1"}"""))
    }

    @Test
    fun modelOptionsWithoutSessionLockUsesLegacyHintContract() {
        val capabilities = ServerCapabilities(
            sessionsApi = true,
            sessionsChatStream = true,
            runs = false,
            portable = true,
            healthy = true,
            modelOptions = true,
            sessionModelLock = false,
        )

        assertEquals(ApiModelRoutingStrategy.LEGACY_HINT, apiModelRoutingStrategy(capabilities))
    }

    @Test
    fun terminalRuntimeMustConfirmExactEffectiveRoute() {
        val expected = ApiModelSelectionAck.Locked(
            sessionId = "session-1",
            model = "fast-route",
            provider = "openai",
            effectiveModel = "gpt-5-mini",
            effectiveProvider = "openai",
        )
        val confirmed = Json.parseToJsonElement(
            """{"model_lock":"confirmed","effective":{"model":"gpt-5-mini","provider":"openai"}}""",
        ) as kotlinx.serialization.json.JsonObject
        val wrongProvider = Json.parseToJsonElement(
            """{"model_lock":"confirmed","effective":{"model":"gpt-5-mini","provider":"azure"}}""",
        ) as kotlinx.serialization.json.JsonObject
        val merelyAccepted = Json.parseToJsonElement(
            """{"model_lock":"accepted","effective":{"model":"gpt-5-mini","provider":"openai"}}""",
        ) as kotlinx.serialization.json.JsonObject

        assertTrue(confirmedRuntimeMatches(confirmed, expected))
        assertFalse(confirmedRuntimeMatches(wrongProvider, expected))
        assertFalse(confirmedRuntimeMatches(merelyAccepted, expected))
    }

    @Test
    fun confirmedLockOmitsTurnModelWhileLegacyFallbackKeepsHint() {
        assertNull(
            sessionTurnModelHint(
                ApiModelSelectionAck.Locked("session-1", "grok-4.3", "xai"),
                "grok-4.3",
            ),
        )
        assertEquals(
            "fast-route",
            sessionTurnModelHint(
                ApiModelSelectionAck.LegacyModelHint("fast-route"),
                "fast-route",
            ),
        )
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
        val page = sessionListPages(200).first()
        val url = "$baseUrl/api/sessions?limit=${page.limit}&offset=${page.offset}"
        assertEquals("http://localhost:8642/api/sessions?limit=100&offset=0", url)
    }

    @Test
    fun sessionListPages_preservesWindowWithoutExceedingUpstreamMaximum() {
        assertEquals(
            listOf(SessionListPage(limit = 100, offset = 0), SessionListPage(limit = 100, offset = 100)),
            sessionListPages(200),
        )
        assertEquals(
            listOf(SessionListPage(limit = 100, offset = 0), SessionListPage(limit = 100, offset = 100)),
            sessionListPages(999),
        )
        assertEquals(listOf(SessionListPage(limit = 25, offset = 0)), sessionListPages(25))
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
    fun modelOptionsPreserveAliasRequestIdAndRootMetadata() {
        val parsed = parseModelOptionsBody(
            Json { ignoreUnknownKeys = true },
            """{"object":"list","data":[{"id":"fast","root":"gpt-5-mini","parent":"hermes-agent"}]}""",
        )
        assertEquals("fast", parsed?.single()?.id)
        assertEquals("gpt-5-mini", parsed?.single()?.root)
        assertEquals("hermes-agent", parsed?.single()?.parent)
        assertEquals("Routes to gpt-5-mini", parsed?.single()?.routeDetail)
    }

    @Test
    fun modelOptionsDeduplicateRepeatedAliasRowsByRequestIdentity() {
        val parsed = parseModelOptionsBody(
            Json { ignoreUnknownKeys = true },
            """{"data":[{"id":"fast","root":"gpt-5-mini"},{"id":"fast","root":"gpt-5-mini"}]}""",
        )

        assertEquals(listOf("fast"), parsed?.map { it.id })
    }

    @Test
    fun modelOptionsIgnoreMalformedRowsButKeepCompatibilityShape() {
        val parsed = parseModelOptionsBody(
            Json { ignoreUnknownKeys = true },
            """{"data":[null,{"root":"missing-id"},{"id":"hermes-agent","extra":true}]}""",
        )
        assertEquals(listOf("hermes-agent"), parsed?.map { it.id })
        assertNull(parseModelOptionsBody(Json, "not-json"))
    }

    @Test
    fun drainFailurePreservesCodeMessageAndBoundedRetryHint() {
        val message = streamHttpFailureMessage(
            code = 503,
            reason = "Service Unavailable",
            retryAfter = "1",
            body = """{"error":{"message":"Gateway is shutting down","code":"gateway_draining"}}""",
            json = Json,
        )
        assertEquals(
            "API error 503: gateway_draining: Gateway is shutting down (Retry-After: 1s)",
            message,
        )
    }

    @Test
    fun ordinaryProvider503KeepsGenericClassificationAndUnsafeRetryHintIsIgnored() {
        val message = streamHttpFailureMessage(
            code = 503,
            reason = "Service Unavailable",
            retryAfter = "999999999",
            body = """{"error":{"message":"provider offline","code":"provider_unavailable"}}""",
            json = Json,
        )
        assertEquals("API error 503: provider_unavailable: provider offline", message)
    }

    @Test
    fun drainRetryIsSinglePreEventAndBounded() {
        val drain = "API error 503: gateway_draining: Gateway is shutting down"
        assertEquals(1_000L, gatewayDrainRetryDelayMillis(503, "1", drain, false, false))
        assertEquals(5_000L, gatewayDrainRetryDelayMillis(503, "999", drain, false, false))
        assertNull(gatewayDrainRetryDelayMillis(503, "1", drain, true, false))
        assertNull(gatewayDrainRetryDelayMillis(503, "1", drain, false, true))
        assertNull(gatewayDrainRetryDelayMillis(503, "1", "provider unavailable", false, false))
        assertNull(gatewayDrainRetryDelayMillis(
            503,
            "1",
            "API error 503: provider_unavailable: message mentions gateway_draining",
            false,
            false,
        ))
        assertNull(gatewayDrainRetryDelayMillis(500, "1", drain, false, false))
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

private fun apiMessagePageResponse(start: Int, count: Int, returned: Int): MockResponse {
    val messages = (start until start + count).joinToString(",") { index ->
        """{"id":"$index","role":"user","content":"m$index"}"""
    }
    return MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"object":"list","session_id":"sess-a","data":[$messages],"pagination":{"limit":500,"offset":$start,"order":"oldest","returned":$returned}}""",
        )
}
