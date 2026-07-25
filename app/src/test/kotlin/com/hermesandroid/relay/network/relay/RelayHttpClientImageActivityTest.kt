package com.hermesandroid.relay.network.relay

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class RelayHttpClientImageActivityTest {
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
    fun fetchImageActivityDecodesLifecycleAndScopesRequest() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "session_id": "session-1",
                  "profile": "victor",
                  "activities": [{
                    "call_id": "call-1",
                    "tool_name": "image_generate",
                    "state": "running",
                    "started_at": 100.0,
                    "completed_at": null
                  }]
                }
                """.trimIndent()
            )
        )
        val client = RelayHttpClient(
            okHttpClient = OkHttpClient(),
            relayUrlProvider = { server.url("/").toString() },
            sessionTokenProvider = { "paired-token" },
        )

        val snapshot = client.fetchImageActivity("victor", "session-1", 99.0).getOrThrow()
        val request = server.takeRequest()

        assertEquals("Bearer paired-token", request.getHeader("Authorization"))
        assertEquals("victor", request.requestUrl?.queryParameter("profile"))
        assertEquals("session-1", request.requestUrl?.queryParameter("session_id"))
        assertEquals("call-1", snapshot?.activities?.single()?.callId)
        assertEquals("running", snapshot?.activities?.single()?.state)
    }

    @Test
    fun fetchImageActivityTreatsMissingOptionalRouteAsUnsupported() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val client = RelayHttpClient(
            okHttpClient = OkHttpClient(),
            relayUrlProvider = { server.url("/").toString() },
            sessionTokenProvider = { "paired-token" },
        )

        assertNull(client.fetchImageActivity("default", "session-1", 99.0).getOrThrow())
    }
}
