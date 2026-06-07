package com.hermesandroid.relay.network

import kotlinx.coroutines.test.runTest
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
                      "auth_providers": ["password", "nous"]
                    }
                    """.trimIndent(),
                ),
        )

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        val status = client.getStatus().getOrThrow()
        val request = server.takeRequest()

        assertEquals("/api/status", request.path)
        assertTrue(status.authRequired)
        assertEquals(listOf("password", "nous"), status.authProviders)
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
                          {"id": "password", "label": "Password"},
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
        assertEquals(listOf("password", "nous"), status.authProviders)
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
                .setBody("""{"authenticated": true, "username": "bailey", "provider": "password"}"""),
        )

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        val login = client.loginPassword(
            provider = "password",
            username = "bailey",
            password = "secret",
        ).getOrThrow()
        val session = client.currentSession().getOrThrow()

        val loginRequest = server.takeRequest()
        val sessionRequest = server.takeRequest()

        assertEquals("/auth/password-login", loginRequest.path)
        val body = loginRequest.body.readUtf8()
        assertTrue(body.contains(""""provider":"password""""))
        assertTrue(body.contains(""""username":"bailey""""))
        assertTrue(body.contains(""""password":"secret""""))
        assertTrue(login.ok)
        assertEquals("/", login.next)

        assertEquals("/api/auth/me", sessionRequest.path)
        assertEquals("hermes_session=abc123", sessionRequest.getHeader("Cookie"))
        assertTrue(session.authenticated)
        assertEquals("bailey", session.username)
        assertEquals("password", session.provider)
    }

    @Test
    fun currentSession_mapsUnauthorizedToUnauthenticated() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val client = DashboardApiClient(baseUrl = server.url("/").toString())
        val session = client.currentSession().getOrThrow()

        assertFalse(session.authenticated)
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
}
