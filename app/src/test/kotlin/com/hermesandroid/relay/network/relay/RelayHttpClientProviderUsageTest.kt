package com.hermesandroid.relay.network.relay

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RelayHttpClientProviderUsageTest {
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
    fun parsesProviderNeutralPayloadAndAuthenticates() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "schema_version": 2,
                  "providers": [
                    {
                      "id": "openai-codex",
                      "display_name": "Codex",
                      "status": "available",
                      "plan": "Plus",
                      "active_credential_id": "abc123",
                      "active_credential_state": "known",
                      "credentials": [{
                        "id": "abc123",
                        "label": "Work",
                        "active": true,
                        "status": "available",
                        "windows": []
                      }],
                      "windows": [{
                        "id": "session",
                        "label": "Session",
                        "used_percent": 42.5,
                        "reset_at": "2026-08-22T00:00:00Z"
                      }]
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        val response = client(token = "paired-token")
            .fetchProviderUsage(profile = "victor", sessionId = "session-42")
            .getOrThrow()!!
        val request = server.takeRequest()

        assertEquals("/usage/providers?profile=victor&session_id=session-42", request.path)
        assertEquals("Bearer paired-token", request.getHeader("Authorization"))
        assertEquals("Codex", response.providers.single().displayName)
        assertEquals(42.5, response.providers.single().windows.single().usedPercent!!, 0.001)
        assertEquals("Work", response.providers.single().credentials.single().label)
        assertTrue(response.providers.single().credentials.single().active)
    }

    @Test
    fun unsupportedHostIsNullSuccess() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val response = client(token = "paired-token").fetchProviderUsage()
        assertTrue(response.isSuccess)
        assertNull(response.getOrNull())
    }

    @Test
    fun unpairedIsUnsupportedAndDoesNotHitServer() = runTest {
        val response = client(token = null).fetchProviderUsage()
        assertTrue(response.isSuccess)
        assertNull(response.getOrNull())
        assertEquals(0, server.requestCount)
    }

    private fun client(token: String?) = RelayHttpClient(
        okHttpClient = OkHttpClient(),
        relayUrlProvider = { server.url("/").toString() },
        sessionTokenProvider = { token },
    )
}
