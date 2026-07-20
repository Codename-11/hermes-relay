package com.hermesandroid.relay.network.upstream

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class McpOAuthFlowCoordinatorTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    @Test
    fun complete_opensHttpsAndPollsToApproved() = runTest {
        enqueueFlow("authorization_required", "https://auth.example/start")
        enqueueFlow("starting")
        enqueueFlow("approved")
        val opened = mutableListOf<String>()
        val coordinator = McpOAuthFlowCoordinator(
            DashboardApiClient(server.url("/").toString()),
            pollDelayMillis = 0,
            maxPolls = 3,
            sleep = {},
        )

        val result = coordinator.complete("hosted", profile = "victor") {
            opened += it
            true
        }.getOrThrow()

        assertEquals("approved", result.status)
        assertEquals(listOf("https://auth.example/start"), opened)
        assertEquals("/api/mcp/servers/hosted/auth?profile=victor", server.takeRequest().path)
        assertEquals("/api/mcp/oauth/flows/opaque", server.takeRequest().path)
        assertEquals("/api/mcp/oauth/flows/opaque", server.takeRequest().path)
    }

    @Test
    fun complete_rejectsNonHttpsWithoutOpeningBrowser() = runTest {
        enqueueFlow("authorization_required", "http://auth.example/start")
        var opened = false
        val result = McpOAuthFlowCoordinator(
            DashboardApiClient(server.url("/").toString()),
            pollDelayMillis = 0,
            sleep = {},
        ).complete("hosted") {
            opened = true
            true
        }

        assertTrue(result.isFailure)
        assertFalse(opened)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("HTTPS"))
    }

    @Test
    fun complete_boundsTransientPollFailures() = runTest {
        enqueueFlow("authorization_required", "https://auth.example/start")
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable")) }
        val result = McpOAuthFlowCoordinator(
            DashboardApiClient(server.url("/").toString()),
            pollDelayMillis = 0,
            maxPolls = 20,
            maxConsecutiveFailures = 3,
            sleep = {},
        ).complete("hosted") { true }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("HTTP 503"))
    }

    @Test
    fun complete_surfacesOlderServerDuplicateAndCapacityResponsesWithoutOpening() = runTest {
        for (code in listOf(404, 409, 429)) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("{}"))
            var opened = false
            val result = McpOAuthFlowCoordinator(
                DashboardApiClient(server.url("/").toString()),
                pollDelayMillis = 0,
                sleep = {},
            ).complete("hosted") {
                opened = true
                true
            }
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("HTTP $code"))
            assertFalse(opened)
        }
    }

    private fun enqueueFlow(status: String, authorizationUrl: String? = null) {
        val url = authorizationUrl?.let { "\"$it\"" } ?: "null"
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"flow_id":"opaque","server_name":"hosted","status":"$status","authorization_url":$url,"error":null}""",
            ),
        )
    }
}
