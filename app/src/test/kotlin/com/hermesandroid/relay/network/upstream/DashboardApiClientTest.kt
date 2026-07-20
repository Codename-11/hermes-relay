package com.hermesandroid.relay.network.upstream

import com.hermesandroid.relay.network.upstream.models.SessionPruneFilters
import com.hermesandroid.relay.network.upstream.models.SessionPrunePreview
import kotlinx.coroutines.test.runTest
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
            apiKey = "never-persist-this",
        )

        val listed = client.getCustomEndpoints("work").getOrThrow()
        client.saveCustomEndpoint(draft, "work").getOrThrow()
        val validation = client.validateCustomEndpoint(draft, "work").getOrThrow()
        client.activateCustomEndpoint("local", "work").getOrThrow()
        client.deleteCustomEndpoint("local", "work").getOrThrow()

        assertEquals("local", listed.currentProvider)
        assertTrue(listed.endpoints.single().hasApiKey)
        assertEquals(listOf("qwen"), validation.models)
        assertEquals("/api/providers/custom-endpoints?profile=work", server.takeRequest().path)
        val save = server.takeRequest()
        assertEquals("/api/providers/custom-endpoints?profile=work", save.path)
        assertTrue(save.body.readUtf8().contains("never-persist-this"))
        assertEquals("/api/providers/custom-endpoints/validate?profile=work", server.takeRequest().path)
        assertEquals("/api/providers/custom-endpoints/local/activate?profile=work", server.takeRequest().path)
        assertEquals("/api/providers/custom-endpoints/local?profile=work", server.takeRequest().path)
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
        val result = client.getElevenLabsVoices().getOrThrow()

        assertEquals("/api/audio/elevenlabs/voices", server.takeRequest().path)
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
}
