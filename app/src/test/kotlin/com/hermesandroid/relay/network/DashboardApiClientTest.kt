package com.hermesandroid.relay.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DashboardApiClientTest {

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
        )
        val landingPath = DashboardApiClient.authLandingPath("https://example.com/hermes/")

        assertEquals(
            "https://example.com/hermes/auth/login?provider=nous&next=%2Fchat",
            authUrl,
        )
        assertEquals(
            "wss://example.com/hermes/api/ws?ticket=abc%2F123",
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
                      {"id":"sess-a","title":"Refactor","model":"claude-opus-4-8","message_count":4,"started_at":1234.5,"source":"tui","profile":"mizu"},
                      {"id":"sess-b","title":"Notes","message_count":2,"started_at":1200.0,"source":"tui","profile":"mizu"}
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
        assertEquals("mizu", url.queryParameter("profile"))
        assertEquals("1", url.queryParameter("min_messages"))
        assertEquals(2, sessions.size)
        assertEquals("sess-a", sessions[0].id)
        assertEquals("Refactor", sessions[0].title)
        assertEquals("claude-opus-4-8", sessions[0].model)
        assertEquals(4, sessions[0].messageCount)
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
        assertEquals(2, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("assistant", messages[1].role)
    }
}
