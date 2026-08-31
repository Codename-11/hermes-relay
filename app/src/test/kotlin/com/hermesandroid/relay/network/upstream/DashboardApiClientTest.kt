package com.hermesandroid.relay.network.upstream

import com.hermesandroid.relay.network.upstream.models.SessionPruneFilters
import com.hermesandroid.relay.network.upstream.models.SessionPrunePreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import okio.Buffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class DashboardApiClientTest {

    @Test
    fun `multiplex API routing uses served profiles instead of installed inventory`() {
        val status = DashboardStatus(
            authRequired = true,
            profiles = listOf("default", "research", "excluded"),
            gatewayMode = "multiplex",
            gateways = listOf(
                DashboardGatewayTopology(
                    profile = "default",
                    servedProfiles = listOf("default", "research", "research", " "),
                ),
            ),
        )

        assertEquals(listOf("default", "research"), status.multiplexServedProfiles())
        assertFalse("excluded" in status.multiplexServedProfiles())
    }

    @Test
    fun `multiplex API routing fails closed without launch gateway served profiles`() {
        val status = DashboardStatus(
            authRequired = true,
            profiles = listOf("default", "research"),
            gatewayMode = "multiplex",
            gateways = emptyList(),
        )

        assertTrue(status.multiplexServedProfiles().isEmpty())
    }

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun getStatus_parsesAuthRequiredAndProviders() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "version": "0.16.0",
                      "install_id": "  install-a  ",
                      "auth_required": true,
                      "auth_providers": ["basic", "nous"]
                    }
                    """.trimIndent(),
                ),
        )

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        val status = client.getStatus().getOrThrow()
        val request = server.takeRequest()

        assertEquals("/api/status", request.path)
        assertTrue(status.authRequired)
        assertEquals(listOf("basic", "nous"), status.authProviders)
        assertEquals("basic", status.authProviderDetails.first().name)
        assertEquals("0.16.0", status.version)
        assertEquals("install-a", status.installId)
    }

    @Test
    fun getStatus_parsesNousAndOptionalGatewayTopology() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {
                  "auth_required": false,
                  "nous_session_valid": "terminal",
                  "profiles": ["default", "worker"],
                  "gateway_mode": "multiplex",
                  "gateways": [{
                    "profile": "default",
                    "ports": {"api_server": 8642, "webhook": 8080},
                    "served_profiles": ["default", "worker"]
                  }]
                }
                """.trimIndent(),
            ),
        )

        val status = DashboardApiClient(baseUrl = server.url("/").toString())
            .getStatus().getOrThrow()

        assertEquals("terminal", status.nousSessionValid)
        assertEquals(listOf("default", "worker"), status.profiles)
        assertEquals("multiplex", status.gatewayMode)
        assertEquals(8642, status.gateways.single().ports["api_server"])
        assertEquals(listOf("default", "worker"), status.gateways.single().servedProfiles)
    }

    @Test
    fun getProviderUsage_carriesSessionAndParsesCredentialPool() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {
                  "schema_version": 2,
                  "capabilities": ["credential_pools", "structured_balances", "opencode_go"],
                  "providers": [{
                    "id": "openai-codex",
                    "display_name": "Codex",
                    "status": "available",
                    "active_credential_state": "known",
                    "credentials": [{
                      "id": "abc123",
                      "label": "bailey",
                      "active": true,
                      "status": "available"
                    }]
                  }]
                }
                """.trimIndent(),
            ),
        )

        val usage = DashboardApiClient(baseUrl = server.url("/").toString())
            .getProviderUsage(profile = "victor", sessionId = "session/42")
            .getOrThrow()!!
        val request = server.takeRequest().requestUrl!!

        assertEquals("/api/plugins/hermes-relay/provider-usage", request.encodedPath)
        assertEquals("victor", request.queryParameter("profile"))
        assertEquals("session/42", request.queryParameter("session_id"))
        assertEquals("bailey", usage.providers.single().credentials.single().label)
        assertTrue(usage.providers.single().credentials.single().active)
        assertTrue(usage.relayEnhanced)
    }

    @Test
    fun getModelOptions_alwaysRequestsUnconfiguredProviders() = runTest {
        // HRUI-022: newer upstream hides unconfigured provider skeleton rows
        // unless the client opts in — without include_unconfigured=1 the
        // Manage picker loses its Keys-setup affordance. Both the cached and
        // the refresh path must carry the opt-in.
        val body = """{"providers": []}"""
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))

        val client = DashboardApiClient(baseUrl = server.url("/").toString())

        client.getModelOptions().getOrThrow()
        val bare = server.takeRequest().requestUrl!!
        assertEquals("/api/model/options", bare.encodedPath)
        assertEquals("1", bare.queryParameter("include_unconfigured"))
        assertEquals(null, bare.queryParameter("refresh"))

        client.getModelOptions(refresh = true).getOrThrow()
        val refreshed = server.takeRequest().requestUrl!!
        assertEquals("/api/model/options", refreshed.encodedPath)
        assertEquals("1", refreshed.queryParameter("include_unconfigured"))
        assertEquals("1", refreshed.queryParameter("refresh"))
    }

    @Test
    fun currentSession_onConnectionAbort_returnsFailure_doesNotThrow() = runTest {
        // Reproduces the crash: a stale pooled connection aborting mid-flight
        // ("Software caused connection abort"). currentSession() returns a
        // Result, so a network failure MUST surface as Result.failure — never a
        // throw that escapes withContext(IO) and crashes the Main coroutine.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        val result = client.currentSession()

        assertTrue("network abort must be Result.failure, not a throw", result.isFailure)
    }

    @Test
    fun malformedBaseUrl_returnsFailure_doesNotThrow() = runTest {
        // The #131 crash: a non-URL value (here the exact reported UI label,
        // normalized to http://<spaces> at save) reached the client as baseUrl.
        // okhttp's Request.Builder.url(String) THROWS IllegalArgumentException
        // ("Invalid URL host") on it; before this guard that throw escaped
        // withContext(IO) onto a Main coroutine and force-closed the app. Every
        // request method must now short-circuit to Result.failure instead.
        val client = DashboardApiClient(baseUrl = "http://Manage sign-in and admin screens")

        // A representative spread across the verb helpers — none may throw.
        assertTrue(client.getStatus().isFailure)
        assertTrue(client.currentSession().isFailure)
        assertTrue(client.requestWsTicket().isFailure)
        assertTrue(client.getJsonObject("/api/config").isFailure)
        assertTrue(client.loginPassword(username = "u", password = "p").isFailure)
        // Boolean probe degrades to false rather than throwing.
        assertFalse(client.audioRoutesPresent())
    }

    @Test
    fun getStatus_acceptsProviderObjects() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "auth": {
                        "required": true,
                        "providers": [
                          {"id": "basic", "label": "Username & Password"},
                          {"type": "oauth", "name": "nous"}
                        ]
                      }
                    }
                    """.trimIndent(),
                ),
        )

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        val status = client.getStatus().getOrThrow()

        assertTrue(status.authRequired)
        assertEquals(listOf("basic", "nous"), status.authProviders)
        assertTrue(status.authProviderDetails.first { it.name == "basic" }.supportsPassword)
        assertFalse(status.authProviderDetails.first { it.name == "nous" }.supportsPassword)
    }

    @Test
    fun getAuthProviders_parsesProviderMetadata() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "providers": [
                        {
                          "name": "basic",
                          "display_name": "Username & Password",
                          "supports_password": true
                        },
                        {
                          "name": "nous",
                          "display_name": "Nous Research",
                          "supports_password": false
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        val providers = client.getAuthProviders().getOrThrow()

        assertEquals("/api/auth/providers", server.takeRequest().path)
        assertEquals("Username & Password", providers[0].displayName)
        assertTrue(providers[0].supportsPassword)
        assertEquals("nous", providers[1].name)
        assertTrue(providers[1].isRedirectProvider)
    }

    @Test
    fun getAuthProviders_acceptsProviderMapMetadata() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "providers": {
                        "basic": {
                          "display_name": "Username & Password",
                          "supports_password": true
                        },
                        "nous": {
                          "type": "oauth",
                          "display_name": "Nous Research"
                        }
                      }
                    }
                    """.trimIndent(),
                ),
        )

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        val providers = client.getAuthProviders().getOrThrow()

        assertEquals(listOf("basic", "nous"), providers.map { it.name })
        assertTrue(providers.first { it.name == "basic" }.supportsPassword)
        assertEquals("Nous Research", providers.first { it.name == "nous" }.displayName)
        assertTrue(providers.first { it.name == "nous" }.isRedirectProvider)
    }

    @Test
    fun authUrlAndGatewayWebSocketUrl_preserveReverseProxyPrefix() {
        val authUrl = DashboardApiClient.authLoginUrl(
            baseUrl = "https://example.com/hermes/",
            provider = "nous",
            next = "/chat",
        )
        val wsUrl = DashboardApiClient.gatewayWebSocketUrl(
            baseUrl = "https://example.com/hermes/",
            ticket = "abc/123",
            profile = "research bot",
        )
        val landingPath = DashboardApiClient.authLandingPath("https://example.com/hermes/")

        assertEquals(
            "https://example.com/hermes/auth/login?provider=nous&next=%2Fchat",
            authUrl,
        )
        assertEquals(
            "wss://example.com/hermes/api/ws?ticket=abc%2F123&profile=research%20bot",
            wsUrl,
        )
        assertEquals("/hermes/", landingPath)
    }

    @Test
    fun importDashboardCookieHeader_storesWebViewCookiesForDashboardClient() {
        val store = InMemoryDashboardCookieStore()
        val imported = importDashboardCookieHeader(
            store = store,
            url = "https://example.com/hermes/",
            cookieHeader = "hermes_session_at=access; hermes_session_rt=refresh",
        )
        val client = DashboardCookieJar(store)
        val cookies = client.loadForRequest(
            "https://example.com/hermes/api/auth/me".toHttpUrl(),
        )

        assertEquals(2, imported)
        assertEquals(listOf("hermes_session_at", "hermes_session_rt"), cookies.map { it.name })
        assertTrue(cookies.all { it.secure })
    }

    @Test
    fun importDashboardCookieHeader_callbackPathStillMatchesApiSession() {
        val store = InMemoryDashboardCookieStore()
        val imported = importDashboardCookieHeader(
            store = store,
            url = "https://example.com/auth/callback?nous=ok",
            cookieHeader = "hermes_session=abc123",
        )
        val client = DashboardCookieJar(store)
        val cookies = client.loadForRequest(
            "https://example.com/api/auth/me".toHttpUrl(),
        )

        assertEquals(1, imported)
        assertEquals(listOf("hermes_session"), cookies.map { it.name })
    }

    @Test
    fun dashboardCookieJar_doesNotCopyBasicSessionAcrossHttpHosts() {
        val store = InMemoryDashboardCookieStore()
        store.save(
            listOf(
                storedCookie("hermes_session", "basic-session", "192.168.1.20"),
            ),
        )

        val jar = DashboardCookieJar(store)
        val foreignHostCookies = jar.loadForRequest(
            "http://100.64.0.20:9119/api/auth/me".toHttpUrl(),
        )
        val exactHostCookies = jar.loadForRequest(
            "http://192.168.1.20:9119/api/auth/me".toHttpUrl(),
        )

        assertTrue(foreignHostCookies.isEmpty())
        assertEquals(listOf("hermes_session"), exactHostCookies.map { it.name })
        assertEquals(setOf("192.168.1.20"), store.load().mapTo(mutableSetOf()) { it.domain })
    }

    @Test
    fun dashboardCookieJar_keepsHostPrefixedSecureCookieOnExactHttpsOrigin() {
        val store = InMemoryDashboardCookieStore()
        store.save(
            listOf(
                storedCookie(
                    name = "__Host-hermes_session_at",
                    value = "secure-session",
                    domain = "hermes.example.test",
                    secure = true,
                ),
            ),
        )

        val jar = DashboardCookieJar(store)
        val exactOrigin = jar.loadForRequest(
            "https://hermes.example.test/api/auth/me".toHttpUrl(),
        )
        val otherHttpsHost = jar.loadForRequest(
            "https://tailscale.example.test/api/auth/me".toHttpUrl(),
        )
        val cleartextSameHost = jar.loadForRequest(
            "http://hermes.example.test/api/auth/me".toHttpUrl(),
        )

        assertEquals(listOf("__Host-hermes_session_at"), exactOrigin.map { it.name })
        assertTrue(otherHttpsHost.isEmpty())
        assertTrue(cleartextSameHost.isEmpty())
        assertEquals("hermes.example.test", store.load().single().domain)
    }

    @Test
    fun importingNewProviderSessionReplacesOldCookiePrefixVariants() {
        val store = InMemoryDashboardCookieStore()
        store.save(
            listOf(
                storedCookie("__Host-hermes_session_at", "old-access", "hermes.example.test", secure = true),
                storedCookie("__Host-hermes_session_rt", "old-refresh", "hermes.example.test", secure = true),
                storedCookie("__Host-hermes_session_provider", "nous", "hermes.example.test", secure = true),
            ),
        )

        importDashboardCookieHeader(
            store = store,
            url = "https://hermes.example.test/auth/callback",
            cookieHeader =
                "hermes_session_at=new-access; " +
                    "hermes_session_rt=new-refresh; " +
                    "hermes_session_provider=self-hosted",
        )

        assertEquals(
            listOf("hermes_session_at", "hermes_session_rt", "hermes_session_provider"),
            store.load().map { it.name },
        )
        assertEquals(
            listOf("new-access", "new-refresh", "self-hosted"),
            store.load().map { it.value },
        )
    }

    @Test
    fun existingCookiePrefixDuplicatesCollapseToNewestVariantBeforeRequest() {
        val store = InMemoryDashboardCookieStore()
        store.save(
            listOf(
                storedCookie("__Host-hermes_session_at", "old-access", "hermes.example.test", secure = true),
                storedCookie("__Host-hermes_session_provider", "nous", "hermes.example.test", secure = true),
                storedCookie("hermes_session_at", "new-access", "hermes.example.test", secure = true),
                storedCookie("hermes_session_provider", "self-hosted", "hermes.example.test", secure = true),
            ),
        )

        val cookies = DashboardCookieJar(store).loadForRequest(
            "https://hermes.example.test/api/auth/me".toHttpUrl(),
        )

        assertEquals(listOf("hermes_session_at", "hermes_session_provider"), cookies.map { it.name })
        assertEquals(listOf("new-access", "self-hosted"), cookies.map { it.value })
        assertEquals(listOf("hermes_session_at", "hermes_session_provider"), store.load().map { it.name })
    }

    @Test
    fun explicitSignInClearsOnlyMatchingHermesSessionCookies() {
        val store = InMemoryDashboardCookieStore()
        store.save(
            listOf(
                StoredDashboardCookie(
                    "__Host-hermes_session_at", "old-access", Long.MAX_VALUE,
                    "hermes.example.test", "/", true, true, true, true,
                ),
                StoredDashboardCookie(
                    "__Secure-hermes_session_rt", "old-refresh", Long.MAX_VALUE,
                    "hermes.example.test", "/base", true, true, true, true,
                ),
                StoredDashboardCookie(
                    "hermes_session_provider", "nous", Long.MAX_VALUE,
                    "hermes.example.test", "/base", true, true, true, true,
                ),
                StoredDashboardCookie(
                    "hermes_session", "legacy", Long.MAX_VALUE,
                    "hermes.example.test", "/base", true, true, true, true,
                ),
                StoredDashboardCookie(
                    "theme", "dark", Long.MAX_VALUE,
                    "hermes.example.test", "/", true, false, true, true,
                ),
                StoredDashboardCookie(
                    "hermes_session_at", "foreign", Long.MAX_VALUE,
                    "other.example.test", "/", true, true, true, true,
                ),
                StoredDashboardCookie(
                    "hermes_session_at", "other-path", Long.MAX_VALUE,
                    "hermes.example.test", "/other", true, true, true, true,
                ),
            ),
        )

        val cleared = clearDashboardSessionCookiesForRequest(
            store,
            "https://hermes.example.test/base/auth/login",
        )

        assertEquals(4, cleared)
        assertEquals(
            listOf("theme", "hermes_session_at", "hermes_session_at"),
            store.load().map { it.name },
        )
        assertEquals(
            listOf("hermes.example.test", "other.example.test", "hermes.example.test"),
            store.load().map { it.domain },
        )
    }

    @Test
    fun providerUnavailableClassifierRejectsGeneric503() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(503)
                .setBody("""{"detail":"Auth provider 'nous' unreachable"}"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(503)
                .setBody("""{"detail":"Service unavailable"}"""),
        )
        val client = DashboardApiClient(baseUrl = server.url("/").toString())

        val providerFailure = client.getStatus().exceptionOrNull()
        val genericFailure = client.getStatus().exceptionOrNull()

        assertTrue(providerFailure?.isDashboardAuthProviderUnavailable() == true)
        assertFalse(genericFailure?.isDashboardAuthProviderUnavailable() == true)
    }

    @Test
    fun signInRequiredClassifierAcceptsEveryUnauthorizedShapeButRejectsForbidden() {
        val noCookie = DashboardHttpException(
            401,
            "Session failed - HTTP 401: {\"reason\":\"no_cookie\",\"detail\":\"Unauthorized\"}",
        )
        val generic = DashboardHttpException(401, "Session failed - HTTP 401: Unauthorized")
        val expired = DashboardHttpException(
            401,
            "Session failed - HTTP 401: {\"reason\":\"session_expired\"}",
        )
        val forbidden = DashboardHttpException(
            403,
            "Session failed - HTTP 403: forbidden",
        )

        assertTrue(noCookie.isDashboardSignInRequiredFailure())
        assertTrue(generic.isDashboardSignInRequiredFailure())
        assertTrue(expired.isDashboardSignInRequiredFailure())
        assertFalse(forbidden.isDashboardSignInRequiredFailure())
    }

    @Test
    fun getStatus_defaultsMissingAuthFieldsForOlderDashboard() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"version": "legacy"}"""),
        )

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        val status = client.getStatus().getOrThrow()

        assertFalse(status.authRequired)
        assertEquals(emptyList<String>(), status.authProviders)
        assertEquals("legacy", status.version)
    }

    @Test
    fun passwordLogin_postsExpectedBodyAndPersistsSessionCookie() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .addHeader("Set-Cookie", "hermes_session=abc123; Path=/; HttpOnly")
                .setBody("""{"ok": true, "next": "/"}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"authenticated": true, "username": "bailey", "provider": "basic"}"""),
        )

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        val login = client.loginPassword(
            username = "bailey",
            password = "secret",
        ).getOrThrow()
        val session = client.currentSession().getOrThrow()

        val loginRequest = server.takeRequest()
        val sessionRequest = server.takeRequest()

        assertEquals("/auth/password-login", loginRequest.path)
        val body = loginRequest.body.readUtf8()
        assertTrue(body.contains(""""provider":"basic""""))
        assertTrue(body.contains(""""username":"bailey""""))
        assertTrue(body.contains(""""password":"secret""""))
        assertTrue(login.ok)
        assertEquals("/", login.next)

        assertEquals("/api/auth/me", sessionRequest.path)
        assertEquals("hermes_session=abc123", sessionRequest.getHeader("Cookie"))
        assertTrue(session.authenticated)
        assertEquals("bailey", session.username)
        assertEquals("basic", session.provider)
    }

    private fun storedCookie(
        name: String,
        value: String,
        domain: String,
        secure: Boolean = false,
    ) = StoredDashboardCookie(
        name = name,
        value = value,
        expiresAt = Long.MAX_VALUE,
        domain = domain,
        path = "/",
        secure = secure,
        httpOnly = true,
        hostOnly = true,
        persistent = true,
    )

    @Test
    fun currentSession_mapsUnauthorizedToUnauthenticated() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        val session = client.currentSession().getOrThrow()

        assertFalse(session.authenticated)
    }

    @Test
    fun currentSession_acceptsUpstreamFlatDashboardSession() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "user_id": "user_123",
                      "email": "bailey@example.com",
                      "display_name": "Bailey",
                      "provider": "nous",
                      "expires_at": 1893456000
                    }
                    """.trimIndent(),
                ),
        )

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        val session = client.currentSession().getOrThrow()

        assertEquals("/api/auth/me", server.takeRequest().path)
        assertTrue(session.authenticated)
        assertEquals("Bailey", session.username)
        assertEquals("nous", session.provider)
    }

    @Test
    fun dashboardRequest_reportsUnsupportedEndpointAsHttpFailure() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("""{"detail": "not found"}"""),
        )

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        val failure = client.getJsonObject("/api/mcp/servers").exceptionOrNull()

        assertEquals("/api/mcp/servers", server.takeRequest().path)
        assertTrue(failure?.message.orEmpty().contains("/api/mcp/servers failed - HTTP 404"))
    }

    @Test
    fun getJsonElement_acceptsTopLevelArray() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""[{"name":"default"}]"""),
        )

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        val root = client.getJsonElement("/api/profiles").getOrThrow()

        assertEquals("/api/profiles", server.takeRequest().path)
        assertTrue(root is JsonArray)
        assertEquals(1, (root as JsonArray).size)
    }

    @Test
    fun toggleSkill_putsExpectedBody() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok": true, "name": "research", "enabled": false}"""),
        )

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        client.toggleSkill("research", enabled = false).getOrThrow()

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/skills/toggle", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains(""""name":"research""""))
        assertTrue(body.contains(""""enabled":false"""))
    }

    @Test
    fun cronActions_encodeJobIdAndProfile() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ok": true}"""))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"runs": []}"""))

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        client.pauseCronJob("daily report", profile = "work profile").getOrThrow()
        client.getCronJobRuns("daily report", profile = "work profile", limit = 250).getOrThrow()

        val pause = server.takeRequest()
        val runs = server.takeRequest()
        assertEquals("POST", pause.method)
        assertEquals("/api/cron/jobs/daily%20report/pause?profile=work%20profile", pause.path)
        assertEquals("GET", runs.method)
        assertEquals("/api/cron/jobs/daily%20report/runs?profile=work%20profile&limit=100", runs.path)
    }

    @Test
    fun mcpActions_useEnabledTestAndRemoveRoutes() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ok": true}"""))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ok": true, "tools": []}"""))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ok": true}"""))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ok": true, "background": false}"""))

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        client.setMcpServerEnabled("github tools", enabled = true).getOrThrow()
        client.testMcpServer("github tools").getOrThrow()
        client.removeMcpServer("github tools").getOrThrow()
        client.installMcpCatalogEntry(
            name = "linear",
            env = mapOf("LINEAR_API_KEY" to "secret"),
            enable = false,
        ).getOrThrow()

        val enable = server.takeRequest()
        val test = server.takeRequest()
        val remove = server.takeRequest()
        val install = server.takeRequest()
        assertEquals("PUT", enable.method)
        assertEquals("/api/mcp/servers/github%20tools/enabled", enable.path)
        assertTrue(enable.body.readUtf8().contains(""""enabled":true"""))
        assertEquals("POST", test.method)
        assertEquals("/api/mcp/servers/github%20tools/test", test.path)
        assertEquals("DELETE", remove.method)
        assertEquals("/api/mcp/servers/github%20tools", remove.path)
        assertEquals("POST", install.method)
        assertEquals("/api/mcp/catalog/install", install.path)
        val body = install.body.readUtf8()
        assertTrue(body.contains(""""name":"linear""""))
        assertTrue(body.contains(""""LINEAR_API_KEY":"secret""""))
        assertTrue(body.contains(""""enable":false"""))
    }

    @Test
    fun mcpOAuth_preservesProfileAndParsesOpaqueFlow() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"flow_id":"opaque-flow","server_name":"hosted","status":"authorization_required","authorization_url":"https://auth.example/authorize?state=secret"}""",
            ),
        )
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"flow_id":"opaque-flow","server_name":"hosted","status":"approved","authorization_url":null}""",
            ),
        )
        val client = DashboardApiClient(server.url("/").toString())

        val started = client.startMcpOAuth("hosted tools", profile = "work profile").getOrThrow()
        val approved = client.getMcpOAuthFlow(started.flowId).getOrThrow()

        assertEquals("opaque-flow", started.flowId)
        assertEquals("approved", approved.status)
        assertEquals("/api/mcp/servers/hosted%20tools/auth?profile=work%20profile", server.takeRequest().path)
        assertEquals("/api/mcp/oauth/flows/opaque-flow", server.takeRequest().path)
    }

    @Test
    fun mcpOAuthCapability_canonicalMissingFlowUsesReadOnlyGetAndIsSupported() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"detail":"OAuth flow not found or expired"}"""),
        )
        val client = DashboardApiClient(server.url("/").toString())

        assertTrue(client.supportsHostedMcpOAuth().getOrThrow())

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/mcp/oauth/flows/__relay_capability_probe_never_a_flow__", request.path)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun mcpOAuthCapability_genericFastApi404UsesReadOnlyGetAndIsUnsupported() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"detail":"Not Found"}"""),
        )
        val client = DashboardApiClient(server.url("/").toString())

        assertFalse(client.supportsHostedMcpOAuth().getOrThrow())

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/mcp/oauth/flows/__relay_capability_probe_never_a_flow__", request.path)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun mcpMutations_preserveSelectedProfile() = runTest {
        repeat(5) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{}"))
        }
        val client = DashboardApiClient(server.url("/").toString())

        client.setMcpServerEnabled("hosted", true, "work profile").getOrThrow()
        client.testMcpServer("hosted", "work profile").getOrThrow()
        client.removeMcpServer("hosted", "work profile").getOrThrow()
        client.installMcpCatalogEntry("hosted", profile = "work profile").getOrThrow()

        assertEquals("/api/mcp/servers/hosted/enabled?profile=work%20profile", server.takeRequest().path)
        assertEquals("/api/mcp/servers/hosted/test?profile=work%20profile", server.takeRequest().path)
        assertEquals("/api/mcp/servers/hosted?profile=work%20profile", server.takeRequest().path)
        assertEquals("/api/mcp/catalog/install?profile=work%20profile", server.takeRequest().path)
    }

    @Test
    fun customEndpointCrud_usesPublicDashboardRoutesAndRedactedResponse() = runTest {
        val listBody = """
            {"endpoints":[{"id":"local","name":"Local","base_url":"https://llm.example/v1","model":"qwen","models":["qwen"],"has_api_key":true,"api_key_preview":"sk-…1234","is_current":true}],"current":{"provider":"local","model":"qwen"}}
        """.trimIndent()
        repeat(5) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                if (it == 2) """{"ok":true,"reachable":true,"message":"","models":["qwen"]}"""
                else if (it == 3) """{"ok":true,"provider":"local","model":"qwen"}"""
                else listBody,
            ))
        }
        val client = DashboardApiClient(server.url("/").toString())
        val draft = DashboardCustomEndpointDraft(
            id = "local",
            name = "Local",
            baseUrl = "https://llm.example/v1",
            model = "qwen",
            models = listOf("qwen", "qwen-vl", " qwen ", ""),
            apiKey = "never-persist-this",
        )

        val listed = client.getCustomEndpoints("work profile").getOrThrow()
        client.saveCustomEndpoint(draft, "work profile").getOrThrow()
        val validation = client.validateCustomEndpoint(draft).getOrThrow()
        client.activateCustomEndpoint("local", "work profile").getOrThrow()
        client.deleteCustomEndpoint("local", "work profile").getOrThrow()

        assertEquals("local", listed.currentProvider)
        assertTrue(listed.endpoints.single().hasApiKey)
        assertEquals(listOf("qwen"), validation.models)
        assertEquals("/api/providers/custom-endpoints?profile=work%20profile", server.takeRequest().path)
        val save = server.takeRequest()
        assertEquals("/api/providers/custom-endpoints?profile=work%20profile", save.path)
        val saveBody = save.body.readUtf8()
        assertTrue(saveBody.contains("never-persist-this"))
        assertTrue(saveBody.contains(""""models":["qwen","qwen-vl"]"""))
        assertEquals("/api/providers/custom-endpoints/validate", server.takeRequest().path)
        assertEquals(
            "/api/providers/custom-endpoints/local/activate?profile=work%20profile",
            server.takeRequest().path,
        )
        assertEquals(
            "/api/providers/custom-endpoints/local?profile=work%20profile",
            server.takeRequest().path,
        )
    }

    @Test
    fun profileActions_useActiveAndDeleteRoutes() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ok": true}"""))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"content": "soul", "exists": true}"""))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ok": true}"""))

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        client.setActiveProfile("research").getOrThrow()
        client.getProfileSoul("research profile").getOrThrow()
        client.deleteProfile("old profile").getOrThrow()

        val active = server.takeRequest()
        val soul = server.takeRequest()
        val delete = server.takeRequest()
        assertEquals("POST", active.method)
        assertEquals("/api/profiles/active", active.path)
        assertTrue(active.body.readUtf8().contains(""""name":"research""""))
        assertEquals("GET", soul.method)
        assertEquals("/api/profiles/research%20profile/soul", soul.path)
        assertEquals("DELETE", delete.method)
        assertEquals("/api/profiles/old%20profile", delete.path)
    }

    @Test
    fun profileCreationCarriesMcpServersAndServerBackupIsDistinct() = runTest {
        repeat(2) {
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ok":true}"""),
            )
        }
        val client = DashboardApiClient(baseUrl = server.url("/").toString())

        client.createProfile(
            name = "research",
            description = "Deep work",
            mcpServers = listOf("github", "memory"),
        ).getOrThrow()
        client.createServerBackup().getOrThrow()

        val create = server.takeRequest()
        assertEquals("/api/profiles", create.path)
        assertTrue(create.body.readUtf8().contains(""""mcp_servers":["github","memory"]"""))
        val backup = server.takeRequest()
        assertEquals("POST", backup.method)
        assertEquals("/api/ops/backup", backup.path)
    }

    @Test
    fun getActiveProfileScope_distinguishesStickyDefaultFromDashboardProcess() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"active":"victor","current":"default"}"""),
        )

        val scope = DashboardApiClient(baseUrl = server.url("/").toString())
            .getActiveProfileScope()
            .getOrThrow()

        assertEquals("victor", scope.active)
        assertEquals("default", scope.current)
        assertEquals("/api/profiles/active", server.takeRequest().path)
    }

    @Test
    fun listProfiles_parsesArrayShapeIntoProfiles() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"profiles":[
                      {"name":"default","model":"gpt-5.5","description":"Victor","gateway_running":true,"skill_count":3,"is_default":true},
                      {"name":"mizu","model":"claude-opus-4-8","description":"Code assistant","gateway_running":false}
                    ]}
                    """.trimIndent(),
                ),
        )
        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        val profiles = client.listProfiles().getOrThrow()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/profiles", request.path)
        assertEquals(2, profiles.size)
        assertEquals("default", profiles[0].name)
        assertEquals("gpt-5.5", profiles[0].model)
        assertEquals("Victor", profiles[0].description)
        assertTrue(profiles[0].gatewayRunning)
        assertEquals(3, profiles[0].skillCount)
        assertEquals("mizu", profiles[1].name)
        assertEquals("claude-opus-4-8", profiles[1].model)
    }

    @Test
    fun listProfiles_parsesObjectMapShapeWithInjectedName() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"profiles":{"default":{"model":"gpt-5.5"},"mizu":{"model":"claude-opus-4-8","description":"Coder"}}}"""),
        )
        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        val profiles = client.listProfiles().getOrThrow().sortedBy { it.name }

        assertEquals(2, profiles.size)
        // The map key is injected as the profile name when the object omits it.
        assertEquals("default", profiles[0].name)
        assertEquals("mizu", profiles[1].name)
        assertEquals("Coder", profiles[1].description)
    }

    @Test
    fun listSessions_scopesToProfileAndParsesUpstreamEnvelope() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"sessions":[
                      {"id":"sess-a","title":"Refactor","preview":"Refactor the session API","model":"claude-opus-4-8","message_count":4,"started_at":1234.5,"last_active":1250.5,"source":"tui","profile":"mizu"},
                      {"id":"sess-b","title":null,"preview":"Review title fallbacks","message_count":2,"started_at":1200.0,"source":"tui","profile":"mizu"}
                    ],"total":2,"limit":50,"offset":0}
                    """.trimIndent(),
                ),
        )
        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        val sessions = client.listSessions(profile = "mizu").getOrThrow()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        // Server-side per-profile scoping is the whole point — the request must
        // carry profile=mizu (the desktop's `_open_session_db_for_profile` path).
        val url = request.requestUrl!!
        assertEquals("/api/sessions", url.encodedPath)
        assertEquals("100", url.queryParameter("limit"))
        assertEquals("0", url.queryParameter("offset"))
        assertEquals("mizu", url.queryParameter("profile"))
        assertEquals("1", url.queryParameter("min_messages"))
        assertEquals(2, sessions.size)
        assertEquals("sess-a", sessions[0].id)
        assertEquals("Refactor", sessions[0].title)
        assertEquals("claude-opus-4-8", sessions[0].model)
        assertEquals(4, sessions[0].messageCount)
        assertEquals(1250.5, sessions[0].lastActive!!, 0.001)
        assertEquals("Refactor the session API", sessions[0].preview)
        assertEquals("Review title fallbacks", sessions[1].preview)
    }

    @Test
    fun listSessions_carriesProgressiveOffsetAndHiddenSourceExclusions() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"sessions":[],"total":120,"limit":50,"offset":50}"""),
        )

        DashboardApiClient(baseUrl = server.url("/").toString())
            .listSessions(
                profile = "victor",
                limit = 50,
                offset = 50,
                excludeSources = setOf("Webhook", "cron", "cron"),
            )
            .getOrThrow()

        val url = server.takeRequest().requestUrl!!
        assertEquals("50", url.queryParameter("limit"))
        assertEquals("50", url.queryParameter("offset"))
        assertEquals("victor", url.queryParameter("profile"))
        assertEquals("cron,webhook", url.queryParameter("exclude_sources"))
    }

    @Test
    fun listSessions_enrichesWorkspaceRowsWithTranscriptBackedPullRequest() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"sessions":[{"id":"coding-1","title":"Ship it","cwd":"/work/hermes-relay","git_branch":"feature/session-context","git_repo_root":"/work/hermes-relay"}]}""",
            ),
        )
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"pull_requests":{"coding-1":{"number":134,"url":"https://github.com/example/hermes-relay/pull/134"}},"scanned":["coding-1"]}""",
            ),
        )
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"ghReady":true,"prs":[{"branch":"feature/session-context","draft":false,"number":134,"state":"open","title":"Session context","url":"https://github.com/example/hermes-relay/pull/134"}]}""",
            ),
        )

        val session = DashboardApiClient(baseUrl = server.url("/").toString())
            .listSessions()
            .getOrThrow()
            .single()

        assertEquals("/work/hermes-relay", session.cwd)
        assertEquals("feature/session-context", session.gitBranch)
        assertEquals("/work/hermes-relay", session.gitRepoRoot)
        assertEquals(134, session.pullRequest?.number)
        assertEquals("https://github.com/example/hermes-relay/pull/134", session.pullRequest?.url)
        assertEquals("open", session.pullRequest?.state)
        assertEquals(false, session.pullRequest?.draft)
        server.takeRequest()
        val scanRequest = server.takeRequest()
        assertEquals("POST", scanRequest.method)
        assertEquals("/api/profiles/sessions/pull-requests", scanRequest.requestUrl!!.encodedPath)
        assertEquals(
            listOf("coding-1"),
            Json.parseToJsonElement(scanRequest.body.readUtf8()).jsonObject["ids"]
                ?.let { it as JsonArray }
                ?.map { it.toString().trim('"') },
        )
        val stateRequest = server.takeRequest()
        assertEquals("/api/git/review/pr-list", stateRequest.requestUrl!!.encodedPath)
        val stateBody = Json.parseToJsonElement(stateRequest.body.readUtf8()).jsonObject
        assertEquals("/work/hermes-relay", stateBody["path"]?.toString()?.trim('"'))
        assertEquals(listOf("feature/session-context"), (stateBody["branches"] as JsonArray).map { it.toString().trim('"') })
        assertEquals(listOf("134"), (stateBody["numbers"] as JsonArray).map { it.toString() })
    }

    @Test
    fun listSessions_keepsWorkspaceMetadataWhenPullRequestEndpointIsUnavailable() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"sessions":[{"id":"legacy-1","git_branch":"dev","git_repo_root":"/work/legacy"}]}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))

        val session = DashboardApiClient(baseUrl = server.url("/").toString())
            .listSessions()
            .getOrThrow()
            .single()

        assertEquals("dev", session.gitBranch)
        assertEquals("/work/legacy", session.gitRepoRoot)
        assertEquals(null, session.pullRequest)
    }

    @Test
    fun listSessions_retriesActiveSessionPullRequestMissAfterBoundedTtl() = runTest {
        var now = 1_000L
        val client = DashboardApiClient(
            baseUrl = server.url("/").toString(),
            nowMillis = { now },
        )
        val sessionList = """{"sessions":[{"id":"active-1","cwd":"/work/repo"}]}"""
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(sessionList))
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"pull_requests":{},"scanned":["active-1"]}"""),
        )

        assertEquals(null, client.listSessions().getOrThrow().single().pullRequest)
        server.takeRequest()
        server.takeRequest()

        now += DashboardApiClient.ACTIVE_SESSION_PR_MISS_TTL_MILLIS - 1
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(sessionList))
        assertEquals(null, client.listSessions().getOrThrow().single().pullRequest)
        assertEquals("GET", server.takeRequest().method)

        now += 1
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(sessionList))
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"pull_requests":{"active-1":{"number":12,"url":"https://github.com/example/repo/pull/12"}},"scanned":["active-1"]}""",
            ),
        )
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"ghReady":false,"prs":[]}"""),
        )

        assertEquals(12, client.listSessions().getOrThrow().single().pullRequest?.number)
        assertEquals("GET", server.takeRequest().method)
        assertEquals("/api/profiles/sessions/pull-requests", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("/api/git/review/pr-list", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun listSessions_performsOneFinalScanWhenAnActiveMissBecomesTerminal() = runTest {
        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"sessions":[{"id":"finishing","cwd":"/work/repo"}]}"""),
        )
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"pull_requests":{},"scanned":["finishing"]}"""),
        )
        client.listSessions().getOrThrow()
        repeat(2) { server.takeRequest() }

        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"sessions":[{"id":"finishing","cwd":"/work/repo","ended_at":2000.0}]}"""),
        )
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"pull_requests":{"finishing":{"number":13,"url":"https://github.com/example/repo/pull/13"}},"scanned":["finishing"]}""",
            ),
        )
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"ghReady":false,"prs":[]}"""),
        )

        assertEquals(13, client.listSessions().getOrThrow().single().pullRequest?.number)
        repeat(3) { server.takeRequest() }
    }

    @Test
    fun listSessions_scopesPullRequestCacheByProfileAndSessionId() = runTest {
        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        fun enqueueProfileRead(number: Int) {
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"sessions":[{"id":"same","cwd":"/work/repo"}]}"""),
            )
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"pull_requests":{"same":{"number":$number,"url":"https://github.com/example/repo/pull/$number"}},"scanned":["same"]}""",
                ),
            )
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"ghReady":false,"prs":[]}"""),
            )
        }

        enqueueProfileRead(11)
        assertEquals(11, client.listSessions(profile = "alpha").getOrThrow().single().pullRequest?.number)
        repeat(3) { server.takeRequest() }

        enqueueProfileRead(22)
        assertEquals(22, client.listSessions(profile = "beta").getOrThrow().single().pullRequest?.number)
        repeat(3) { server.takeRequest() }
    }

    @Test
    fun listAllProfileSessions_doesNotGuessAcrossDuplicateSessionIds() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"sessions":[{"id":"same","profile":"alpha","cwd":"/work/a"},{"id":"same","profile":"beta","cwd":"/work/b"}]}""",
            ),
        )

        val sessions = DashboardApiClient(baseUrl = server.url("/").toString())
            .listAllProfileSessions()
            .getOrThrow()

        assertEquals(2, sessions.size)
        assertTrue(sessions.all { it.pullRequest == null })
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun listAllProfileSessions_preservesOwnerAndCompositeIdentity() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"sessions":[{"id":"same","title":"A","profile":"default"},{"id":"same","title":"B","profile":"work"},{"id":"unsafe"}],"total":3}""",
                ),
        )

        val sessions = DashboardApiClient(baseUrl = server.url("/").toString())
            .listAllProfileSessions()
            .getOrThrow()

        val url = server.takeRequest().requestUrl!!
        assertEquals("/api/profiles/sessions", url.encodedPath)
        assertEquals("all", url.queryParameter("profile"))
        assertEquals("include", url.queryParameter("archived"))
        assertEquals(listOf("default", "work"), sessions.map { it.profile })
        assertEquals(listOf("A", "B"), sessions.map { it.title })
    }

    @Test
    fun listSessions_pagesAtUpstreamMaximumWhilePreservingTwoHundredRowWindow() = runTest {
        val firstPage = (0 until 100).joinToString(",") { "{\"id\":\"sess-$it\"}" }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"sessions\":[$firstPage],\"total\":102,\"limit\":100,\"offset\":0}"),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"sessions":[{"id":"sess-100"},{"id":"sess-101"}],"total":102,"limit":100,"offset":100}""",
                ),
        )

        val sessions = DashboardApiClient(baseUrl = server.url("/").toString())
            .listSessions(profile = "mizu")
            .getOrThrow()

        val firstRequest = server.takeRequest().requestUrl!!
        val secondRequest = server.takeRequest().requestUrl!!
        assertEquals("100", firstRequest.queryParameter("limit"))
        assertEquals("0", firstRequest.queryParameter("offset"))
        assertEquals("mizu", firstRequest.queryParameter("profile"))
        assertEquals("100", secondRequest.queryParameter("limit"))
        assertEquals("100", secondRequest.queryParameter("offset"))
        assertEquals("mizu", secondRequest.queryParameter("profile"))
        assertEquals(102, sessions.size)
        assertEquals("sess-0", sessions.first().id)
        assertEquals("sess-101", sessions.last().id)
    }

    @Test
    fun listSessions_omitsProfileParamForTheDefaultSelection() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"sessions":[],"total":0,"limit":50,"offset":0}"""),
        )
        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        client.listSessions(profile = null).getOrThrow()

        val request = server.takeRequest()
        // No profile → omit the param so upstream reads the launch (default) DB.
        assertEquals(null, request.requestUrl!!.queryParameter("profile"))
    }

    @Test
    fun listSessions_cancellationCancelsTheActiveHttpCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val httpClient = DashboardApiClient.defaultClient()
        val client = DashboardApiClient(
            baseUrl = server.url("/").toString(),
            okHttpClient = httpClient,
        )

        val read = async(Dispatchers.IO) { client.listSessions(profile = "mizu") }
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
        read.cancelAndJoin()

        withTimeout(2_000L) {
            while (httpClient.dispatcher.runningCallsCount() != 0) delay(10L)
        }
        assertEquals(0, httpClient.dispatcher.runningCallsCount())
    }

    @Test
    fun listSessions_returnsRowsAndRetriesOptionalEnrichmentAfterItsBudgetExpires() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"sessions":[{"id":"sess-a","profile":"mizu","cwd":"/work/repo"}]}""",
                ),
        )
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = DashboardApiClient(
            baseUrl = server.url("/").toString(),
            sessionEnrichmentBudgetMillis = 500L,
        )

        val sessions = client.listSessions(profile = "mizu").getOrThrow()

        assertEquals(listOf("sess-a"), sessions.map { it.id })
        assertEquals(null, sessions.single().pullRequest)
        assertEquals("/api/sessions", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("/api/profiles/sessions/pull-requests", server.takeRequest().requestUrl!!.encodedPath)

        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"sessions":[{"id":"sess-a","profile":"mizu","cwd":"/work/repo","git_branch":"fix/latency"}]}""",
            ),
        )
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"pull_requests":{"sess-a":{"number":399,"url":"https://github.com/example/repo/pull/399"}},"scanned":["sess-a"]}""",
            ),
        )
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"ghReady":true,"prs":[{"branch":"fix/latency","draft":false,"number":399,"state":"open","title":"Latency","url":"https://github.com/example/repo/pull/399"}]}""",
            ),
        )

        val retried = client.listSessions(profile = "mizu").getOrThrow()

        assertEquals(399, retried.single().pullRequest?.number)
    }

    @Test
    fun listSessions_failsWithinTheBoundedSessionReadTimeout() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = DashboardApiClient(
            baseUrl = server.url("/").toString(),
            sessionReadTimeoutMillis = 100L,
        )

        assertTrue(client.listSessions(profile = "mizu").isFailure)
    }

    @Test
    fun getSessionMessages_failsWithinTheBoundedSessionReadTimeout() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = DashboardApiClient(
            baseUrl = server.url("/").toString(),
            sessionReadTimeoutMillis = 100L,
        )

        assertTrue(client.getSessionMessages("sess-a", profile = "mizu").isFailure)
    }

    @Test
    fun requestWsTicket_failsWithinTheBoundedReadTimeout() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = DashboardApiClient(
            baseUrl = server.url("/").toString(),
            sessionReadTimeoutMillis = 5_000L,
            controlReadTimeoutMillis = 100L,
        )

        assertTrue(client.requestWsTicket().isFailure)
    }

    @Test
    fun getSessionMessages_scopesToProfileAndParsesUpstreamEnvelope() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"session_id":"sess-a","messages":[
                      {"id":"m1","role":"user","content":"hi"},
                      {"id":"m2","role":"assistant","content":"hello"}
                    ]}""".trimIndent(),
                ),
        )
        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        val messages = client.getSessionMessages("sess-a", profile = "mizu").getOrThrow()

        val request = server.takeRequest()
        val url = request.requestUrl!!
        assertEquals("/api/sessions/sess-a/messages", url.encodedPath)
        assertEquals("mizu", url.queryParameter("profile"))
        assertEquals("500", url.queryParameter("limit"))
        assertEquals("0", url.queryParameter("offset"))
        assertEquals("oldest", url.queryParameter("order"))
        assertEquals(2, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("assistant", messages[1].role)
    }

    @Test
    fun getSessionMessages_pagesCompleteHistoryAndPreservesProfileScope() = runTest {
        server.enqueue(messagePageResponse(key = "messages", start = 0, count = 500, returned = 500))
        server.enqueue(messagePageResponse(key = "messages", start = 500, count = 1, returned = 1))

        val messages = DashboardApiClient(baseUrl = server.url("/").toString())
            .getSessionMessages("sess-a", profile = "mizu")
            .getOrThrow()

        val first = server.takeRequest().requestUrl!!
        val second = server.takeRequest().requestUrl!!
        assertEquals(listOf("0", "500"), listOf(first, second).map { it.queryParameter("offset") })
        assertEquals(listOf("mizu", "mizu"), listOf(first, second).map { it.queryParameter("profile") })
        assertEquals((0..500).map(Int::toString), messages.map { it.id })
    }

    @Test
    fun getSessionMessages_latestUsesOneBoundedPage() = runTest {
        server.enqueue(messagePageResponse(key = "messages", start = 500, count = 500, returned = 500))

        val messages = DashboardApiClient(baseUrl = server.url("/").toString())
            .getSessionMessages("sess-a", profile = null, mode = SessionMessageLoadMode.LATEST)
            .getOrThrow()

        val request = server.takeRequest().requestUrl!!
        assertEquals("latest", request.queryParameter("order"))
        assertEquals("0", request.queryParameter("offset"))
        assertEquals(500, messages.size)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun parseChatDisplaySettings_readsNestedDisplayConfig() {
        val root = Json.parseToJsonElement(
            """
            {
              "config": {
                "display": {
                  "show_reasoning": false,
                  "tool_progress": "all"
                }
              }
            }
            """.trimIndent(),
        ).jsonObject

        val settings = DashboardApiClient.parseChatDisplaySettings(root)

        assertEquals(false, settings.showReasoning)
        assertEquals("detailed", settings.toolDisplay)
    }

    @Test
    fun getElevenLabsVoices_parsesAvailableVoices() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "available": true,
                      "voices": [
                        {"voice_id": "pNInz6obpgDQGcFmaJgB", "name": "Adam", "label": "Adam (premade)"},
                        {"voice_id": "21m00Tcm4TlvDq8ikWAM", "name": "Rachel", "label": "Rachel (premade)"}
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        val result = client.getElevenLabsVoices("research profile").getOrThrow()

        assertEquals(
            "/api/audio/elevenlabs/voices?profile=research%20profile",
            server.takeRequest().path,
        )
        assertTrue(result.available)
        assertEquals(2, result.voices.size)
        assertEquals("pNInz6obpgDQGcFmaJgB", result.voices[0].voiceId)
        assertEquals("Adam", result.voices[0].name)
        assertEquals("Rachel (premade)", result.voices[1].label)
    }

    @Test
    fun getElevenLabsVoices_reportsUnavailableWhenNoApiKey() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"available": false, "voices": []}"""),
        )

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        val result = client.getElevenLabsVoices().getOrThrow()

        assertFalse(result.available)
        assertTrue(result.voices.isEmpty())
    }

    @Test
    fun updateConfig_putsWholeTreeWithProfile() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok": true}"""),
        )

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        val tree = Json.parseToJsonElement(
            """{"model":"claude-opus-4-8","tts":{"provider":"elevenlabs"}}""",
        ).jsonObject
        client.updateConfig(tree, profile = "work").getOrThrow()

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/config", request.path)
        val body = request.body.readUtf8()
        // The whole tree must be nested under "config" (upstream ConfigUpdate),
        // and the profile must ride along.
        assertTrue(body.contains(""""config":{"""))
        assertTrue(body.contains(""""model":"claude-opus-4-8""""))
        assertTrue(body.contains(""""provider":"elevenlabs""""))
        assertTrue(body.contains(""""profile":"work""""))
    }

    @Test
    fun updateConfig_omitsProfileWhenBlank() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ok": true}"""),
        )

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        client.updateConfig(Json.parseToJsonElement("""{"stt":{"provider":"local"}}""").jsonObject)
            .getOrThrow()

        val body = server.takeRequest().body.readUtf8()
        assertFalse(body.contains("profile"))
    }

    @Test
    fun getConfigAndSchema_hitExpectedPaths() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"tts":{}}"""))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"fields":{},"category_order":[]}"""))

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        client.getConfig().getOrThrow()
        client.getConfigSchema().getOrThrow()

        assertEquals("/api/config", server.takeRequest().path)
        assertEquals("/api/config/schema", server.takeRequest().path)
    }

    @Test
    fun getTtsToolsetConfig_hitsRuntimeProviderRegistry() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"name":"tts","has_category":true,"providers":[]}"""),
        )

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        client.getTtsToolsetConfig().getOrThrow()

        assertEquals("/api/tools/toolsets/tts/config", server.takeRequest().path)
    }

    @Test
    fun previewSessionPrune_postsDryRunAndParsesPreview() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"ok":true,"removed":0,"matched":2,
                     "oldest_started_at":1000.5,"newest_started_at":2000.5,
                     "sessions":[
                       {"id":"sess-old","source":"phone","title":"Old plan","model":"claude-opus-4-8","started_at":1000.5,"message_count":3},
                       {"id":"sess-new","source":"phone","started_at":2000.5,"message_count":1}
                     ]}
                    """.trimIndent(),
                ),
        )

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        val preview = client.previewSessionPrune(
            SessionPruneFilters(olderThanDays = 30.0, source = "phone", profile = "mizu"),
        ).getOrThrow()

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/sessions/prune", request.path)
        val body = request.body.readUtf8()
        // The preview MUST be a dry run — this call may never delete.
        assertTrue(body.contains(""""dry_run":true"""))
        assertTrue(body.contains(""""older_than_days":30.0"""))
        assertTrue(body.contains(""""source":"phone""""))
        assertTrue(body.contains(""""profile":"mizu""""))
        assertEquals(2, preview.matched)
        assertEquals(1000.5, preview.oldestStartedAt!!, 0.001)
        assertEquals(2000.5, preview.newestStartedAt!!, 0.001)
        assertEquals("sess-old", preview.sessions[0].id)
        assertEquals(3, preview.sessions[0].messageCount)
        assertEquals("Old plan", preview.sessions[0].title)
    }

    @Test
    fun previewSessionPrune_bareFiltersOmitOptionalFields() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true,"removed":0,"matched":0,"sessions":[]}"""),
        )

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        client.previewSessionPrune(SessionPruneFilters()).getOrThrow()

        val body = server.takeRequest().body.readUtf8()
        // A bare prune sends only dry_run; upstream then applies its own
        // implicit ended-more-than-90-days-ago cutoff.
        assertTrue(body.contains(""""dry_run":true"""))
        assertFalse(body.contains("older_than_days"))
        assertFalse(body.contains("source"))
        assertFalse(body.contains("profile"))
        assertFalse(body.contains("include_archived"))
    }

    @Test
    fun pruneSessions_appliesWithDryRunFalseAndParsesRemoved() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true,"removed":2}"""),
        )

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        val filters = SessionPruneFilters(olderThanDays = 30.0, source = "phone")
        val preview = SessionPrunePreview(matched = 2)
        val result = client.pruneSessions(filters, confirmedPreview = preview).getOrThrow()

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/sessions/prune", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains(""""dry_run":false"""))
        assertTrue(body.contains(""""older_than_days":30.0"""))
        assertEquals(2, result.removed)
    }

    @Test
    fun pruneSessions_skipsServerCallWhenPreviewMatchedNothing() = runTest {
        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        val result = client.pruneSessions(
            SessionPruneFilters(olderThanDays = 30.0),
            confirmedPreview = SessionPrunePreview(matched = 0),
        ).getOrThrow()

        // Nothing matched at preview time → nothing to delete. The client must
        // not fire the destructive POST at all (sessions that aged in after
        // the preview are not covered by what the user confirmed).
        assertEquals(0, server.requestCount)
        assertEquals(0, result.removed)
    }

    @Test
    fun exportSession_getsServerOwnedArchiveJsonScopedToProfile() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":"sess-old","messages":[]}"""),
        )

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        val exported = client.exportSession("sess-old", profile = "mizu").getOrThrow()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/sessions/sess-old/export", request.requestUrl!!.encodedPath)
        assertEquals("mizu", request.requestUrl!!.queryParameter("profile"))
        assertEquals("sess-old", exported["id"].toString().trim('"'))
    }

    @Test
    fun setSessionArchived_patchesArchivedScopedToProfile() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true,"title":"Old plan","archived":true}"""),
        )

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        client.setSessionArchived("sess-old", archived = true, profile = "mizu").getOrThrow()

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        // Current upstream reads profile from the PATCH body (SessionRename
        // model); the query param rides along for builds that scoped by query.
        assertEquals("/api/sessions/sess-old", request.requestUrl!!.encodedPath)
        assertEquals("mizu", request.requestUrl!!.queryParameter("profile"))
        val body = request.body.readUtf8()
        assertTrue(body.contains(""""archived":true"""))
        assertTrue(body.contains(""""profile":"mizu""""))
    }

    @Test
    fun setSessionArchived_omitsProfileForDefaultSelection() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true,"title":"","archived":false}"""),
        )

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        client.setSessionArchived("sess-old", archived = false, profile = null).getOrThrow()

        val request = server.takeRequest()
        assertEquals(null, request.requestUrl!!.queryParameter("profile"))
        val body = request.body.readUtf8()
        assertTrue(body.contains(""""archived":false"""))
        assertFalse(body.contains("profile"))
    }

    @Test
    fun setSessionPinned_patchesPinnedScopedToProfile() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true,"pinned":true}"""),
        )

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        client.setSessionPinned("sess-keep", pinned = true, profile = "mizu").getOrThrow()

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/sessions/sess-keep", request.requestUrl!!.encodedPath)
        assertEquals("mizu", request.requestUrl!!.queryParameter("profile"))
        val body = request.body.readUtf8()
        assertTrue(body.contains(""""pinned":true"""))
        assertTrue(body.contains(""""profile":"mizu"""))
    }

    @Test
    fun renameSession_carriesProfileInBodyAndQuery() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true,"title":"New title"}"""),
        )

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        client.renameSession("sess-a", title = "New title", profile = "mizu").getOrThrow()

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/sessions/sess-a", request.requestUrl!!.encodedPath)
        assertEquals("mizu", request.requestUrl!!.queryParameter("profile"))
        val body = request.body.readUtf8()
        assertTrue(body.contains(""""title":"New title""""))
        // Current upstream reads profile from the PATCH body, not the query.
        assertTrue(body.contains(""""profile":"mizu""""))
    }

    @Test
    fun listSessions_passesArchivedFilterThrough() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"sessions":[],"total":0,"limit":50,"offset":0}"""),
        )

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        client.listSessions(archived = "only").getOrThrow()

        val url = server.takeRequest().requestUrl!!
        assertEquals("only", url.queryParameter("archived"))
    }

    @Test
    fun listSessions_omitsArchivedParamByDefault() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"sessions":[],"total":0,"limit":50,"offset":0}"""),
        )

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        client.listSessions().getOrThrow()

        // Default stays upstream's default (exclude) with no param, so older
        // hosts that predate the archived filter see an unchanged request.
        assertEquals(null, server.takeRequest().requestUrl!!.queryParameter("archived"))
    }

    @Test
    fun parseChatDisplaySettings_mapsToolProgressNoneToOff() {
        val root = Json.parseToJsonElement(
            """
            {
              "display": {
                "show_reasoning": true,
                "tool_progress": "none"
              }
            }
            """.trimIndent(),
        ).jsonObject

        val settings = DashboardApiClient.parseChatDisplaySettings(root)

        assertEquals(true, settings.showReasoning)
        assertEquals("off", settings.toolDisplay)
    }

    @Test
    fun serverBackup_createDownloadAndImport_useUpstreamContracts() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ok":true,"archive":"/srv/backups/a.zip"}"""))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/zip")
                .setHeader("Content-Disposition", "attachment; filename=\"a.zip\"")
                .setBody("archive-bytes"),
        )
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ok":true,"name":"import"}"""))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ok":true,"name":"import"}"""))

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        val created = client.createServerBackup().getOrThrow()
        val downloadSink = ByteArrayOutputStream()
        val downloadedFilename = client.downloadServerBackup(created["archive"]!!.toString().trim('"')) {
            downloadSink
        }.getOrThrow()
        client.importServerBackup("/srv/backups/a.zip").getOrThrow()
        val uploadBytes = "zip-data".encodeToByteArray()
        client.uploadServerBackup(
            filename = "phone.zip",
            contentLength = uploadBytes.size.toLong(),
            openStream = { ByteArrayInputStream(uploadBytes) },
        ).getOrThrow()

        assertEquals("POST", server.takeRequest().method)
        val downloadRequest = server.takeRequest()
        assertEquals("/api/ops/backup/download", downloadRequest.requestUrl!!.encodedPath)
        assertEquals("/srv/backups/a.zip", downloadRequest.requestUrl!!.queryParameter("archive"))
        assertEquals("a.zip", downloadedFilename)
        assertEquals("archive-bytes", downloadSink.toString(Charsets.UTF_8.name()))
        val importRequest = server.takeRequest()
        assertEquals("/api/ops/import", importRequest.requestUrl!!.encodedPath)
        assertTrue(importRequest.body.readUtf8().contains(""""archive":"/srv/backups/a.zip""""))
        val upload = server.takeRequest()
        assertEquals("/api/ops/import-upload", upload.requestUrl!!.encodedPath)
        val uploadBody = upload.body.readUtf8()
        assertTrue(uploadBody.contains("filename=\"phone.zip\""))
        assertTrue(uploadBody.contains("zip-data"))
    }

    @Test
    fun boundedStreamRequestBody_streamsAndEnforcesDeclaredAndObservedLimits() {
        val payload = "streamed-archive".encodeToByteArray()
        val sink = Buffer()
        BoundedStreamRequestBody(payload.size.toLong(), 32L) {
            ByteArrayInputStream(payload)
        }.writeTo(sink)
        assertEquals("streamed-archive", sink.readUtf8())

        assertThrows(IllegalArgumentException::class.java) {
            BoundedStreamRequestBody(declaredLength = 33L, limitBytes = 32L) {
                ByteArrayInputStream(byteArrayOf())
            }
        }

        val oversizedUnknownLength = BoundedStreamRequestBody(null, 8L) {
            ByteArrayInputStream("ninebytes".encodeToByteArray())
        }
        assertThrows(IOException::class.java) { oversizedUnknownLength.writeTo(Buffer()) }
    }

    @Test
    fun copyBounded_streamsDownloadAndRejectsDeclaredAndObservedOverflow() {
        val output = ByteArrayOutputStream()
        val copied = copyBounded(
            ByteArrayInputStream("download".encodeToByteArray()),
            output,
            declaredLength = 8L,
            limitBytes = 16L,
        )
        assertEquals(8L, copied)
        assertEquals("download", output.toString(Charsets.UTF_8.name()))

        assertThrows(IllegalArgumentException::class.java) {
            copyBounded(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), 17L, 16L)
        }
        assertThrows(IOException::class.java) {
            copyBounded(
                ByteArrayInputStream("seventeen-byte-doc".encodeToByteArray()),
                ByteArrayOutputStream(),
                declaredLength = null,
                limitBytes = 16L,
            )
        }
    }

    @Test
    fun downloadServerBackup_rejectsDeclaredOversizeBeforeOpeningDestination() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/zip")
                .setHeader("Content-Length", DashboardApiClient.MAX_BACKUP_TRANSFER_BYTES + 1),
        )
        var destinationOpened = false
        val result = DashboardApiClient(baseUrl = server.url("/").toString())
            .downloadServerBackup("/srv/backups/oversize.zip") {
                destinationOpened = true
                ByteArrayOutputStream()
            }

        assertTrue(result.isFailure)
        assertFalse(destinationOpened)
    }

    @Test
    fun learningMutations_preserveNodeIdAndProfile() = runTest {
        repeat(3) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ok":true,"content":"body"}""")) }
        val client = DashboardApiClient(baseUrl = server.url("/").toString())

        client.getLearningNode("memory:MEMORY.md:0", "worker").getOrThrow()
        client.updateLearningNode("memory:MEMORY.md:0", "replacement", "worker").getOrThrow()
        client.deleteLearningNode("memory:MEMORY.md:0", "worker").getOrThrow()

        val get = server.takeRequest()
        assertEquals("memory:MEMORY.md:0", get.requestUrl!!.queryParameter("id"))
        assertEquals("worker", get.requestUrl!!.queryParameter("profile"))
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertTrue(put.body.readUtf8().contains(""""profile":"worker""""))
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertTrue(delete.body.readUtf8().contains(""""id":"memory:MEMORY.md:0""""))
    }

    @Test
    fun memoryProviderAndWhatsApp_calls_areProfileScoped() = runTest {
        repeat(5) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ok":true,"pairing_id":"pair-1","status":"waiting"}""")) }
        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        val values = Json.parseToJsonElement("""{"url":"https://memory.example"}""").jsonObject

        client.getMemoryProviderConfig("honcho", "worker").getOrThrow()
        client.updateMemoryProviderConfig("honcho", values, "worker").getOrThrow()
        client.selectMemoryProvider("honcho").getOrThrow()
        client.startWhatsAppOnboarding("self-chat", "15551234567", "worker").getOrThrow()
        client.applyWhatsAppOnboarding("pair-1", "self-chat", "15551234567", "worker").getOrThrow()

        assertEquals("worker", server.takeRequest().requestUrl!!.queryParameter("profile"))
        assertTrue(server.takeRequest().body.readUtf8().contains(""""values":{"url":"https://memory.example"}"""))
        assertTrue(server.takeRequest().body.readUtf8().contains(""""provider":"honcho""""))
        val start = server.takeRequest()
        assertEquals("/api/messaging/whatsapp/onboarding/start", start.requestUrl!!.encodedPath)
        assertTrue(start.body.readUtf8().contains(""""profile":"worker""""))
        val apply = server.takeRequest()
        assertEquals("/api/messaging/whatsapp/onboarding/pair-1/apply", apply.requestUrl!!.encodedPath)
        assertTrue(apply.body.readUtf8().contains(""""profile":"worker""""))
    }
}

private fun messagePageResponse(
    key: String,
    start: Int,
    count: Int,
    returned: Int,
): MockResponse {
    val messages = (start until start + count).joinToString(",") { index ->
        """{"id":"$index","role":"user","content":"m$index"}"""
    }
    return MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"session_id":"sess-a","$key":[$messages],"pagination":{"limit":500,"offset":$start,"order":"oldest","returned":$returned}}""",
        )
}
