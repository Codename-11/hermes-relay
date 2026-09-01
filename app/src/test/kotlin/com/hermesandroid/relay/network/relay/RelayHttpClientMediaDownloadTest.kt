package com.hermesandroid.relay.network.relay

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RelayHttpClientMediaDownloadTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun fetchMediaByPathStopsChunkedResponseAtCallerLimit() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/octet-stream")
                .setChunkedBody("x".repeat(64), 8),
        )
        val client = RelayHttpClient(
            okHttpClient = OkHttpClient(),
            relayUrlProvider = {
                server.url("/").toString().replaceFirst("http://", "ws://").trimEnd('/')
            },
            sessionTokenProvider = { "paired-session" },
        )

        val result = client.fetchMediaByPath("/tmp/large.bin", maxBytes = 16)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("download limit"))
        assertEquals("/media/by-path", server.takeRequest().requestUrl?.encodedPath)
    }
}
