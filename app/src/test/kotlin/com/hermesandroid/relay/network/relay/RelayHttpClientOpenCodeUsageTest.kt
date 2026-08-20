package com.hermesandroid.relay.network.relay

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RelayHttpClientOpenCodeUsageTest {
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
    fun parsesWindowsAndLimitsAndAuthenticates() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "usage": {
                    "rolling": {"status": "ok", "percent": 42, "resetsAt": "2026-08-20T05:00:00Z"},
                    "weekly": {"status": "ok", "percent": 18, "resetsAt": "2026-08-24T00:00:00Z"},
                    "monthly": {"status": "ok", "percent": 55, "resetsAt": "2026-09-01T00:00:00Z"}
                  },
                  "limits": {
                    "rolling": {"label": "5h", "limit": 12.0},
                    "weekly": {"label": "weekly", "limit": 30.0},
                    "monthly": {"label": "monthly", "limit": 60.0}
                  }
                }
                """.trimIndent(),
            ),
        )

        val response = client(token = "paired-token").fetchOpenCodeUsage().getOrThrow()!!
        val request = server.takeRequest()

        assertEquals("/usage/opencode", request.path)
        assertEquals("Bearer paired-token", request.getHeader("Authorization"))
        assertEquals("application/json", request.getHeader("Accept"))

        assertEquals(42, response.usage.rolling?.percent)
        assertEquals(18, response.usage.weekly?.percent)
        assertEquals(55, response.usage.monthly?.percent)
        assertEquals("2026-08-24T00:00:00Z", response.usage.weekly?.resetsAt)

        assertEquals(12.0, response.limits["rolling"]?.limit ?: 0.0, 0.001)
        assertEquals(30.0, response.limits["weekly"]?.limit ?: 0.0, 0.001)
        assertEquals(60.0, response.limits["monthly"]?.limit ?: 0.0, 0.001)
        assertEquals("5h", response.limits["rolling"]?.label)
    }

    @Test
    fun tolerateMissingWindowsThreshold() = runTest {
        // A partially-populated payload must still decode (all optional).
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"usage":{},"limits":{}}"""),
        )
        val response = client(token = "paired-token").fetchOpenCodeUsage().getOrThrow()!!
        assertNotNull(response.usage)
        assertTrue(response.limits.isEmpty())
    }

    @Test
    fun unauthorizedBecomesFailure() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val failure = client(token = "paired-token").fetchOpenCodeUsage()
        assertTrue(failure.isFailure)
    }

    @Test
    fun notConfiguredOnHostIsNullSuccess() = runTest {
        // Relay returns 404 when OpenCode Go isn't configured on the host.
        // The client treats that as a normal "feature not applicable" state
        // (null success), not an error — the UI shows a quiet note, not retry.
        server.enqueue(MockResponse().setResponseCode(404))
        val response = client(token = "paired-token").fetchOpenCodeUsage()
        assertTrue(response.isSuccess)
        assertTrue(response.getOrNull() == null)
    }

    @Test
    fun unpairedDoesNotHitServer() = runTest {
        val failure = client(token = null).fetchOpenCodeUsage()
        assertTrue(failure.isFailure)
        assertEquals(0, server.requestCount)
    }

    private fun client(token: String?) = RelayHttpClient(
        okHttpClient = OkHttpClient(),
        relayUrlProvider = { server.url("/").toString() },
        sessionTokenProvider = { token },
    )
}
