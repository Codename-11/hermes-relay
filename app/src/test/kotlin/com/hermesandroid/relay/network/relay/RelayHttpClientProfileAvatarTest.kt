package com.hermesandroid.relay.network.relay

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RelayHttpClientProfileAvatarTest {
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
    fun fetchProfileAvatarEncodesProfileAndReturnsImage() = runTest {
        val expected = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/png")
                .setHeader("Content-Disposition", "inline; filename=\"avatar.png\"")
                .setBody(okio.Buffer().write(expected))
        )
        val client = RelayHttpClient(
            okHttpClient = OkHttpClient(),
            relayUrlProvider = { server.url("/").toString() },
            sessionTokenProvider = { "paired-token" },
        )

        val media = client.fetchProfileAvatar("My Agent").getOrThrow()
        val request = server.takeRequest()

        assertEquals("/api/profiles/My%20Agent/avatar", request.path)
        assertEquals("Bearer paired-token", request.getHeader("Authorization"))
        assertEquals("image/png", media.contentType)
        assertEquals("avatar.png", media.fileName)
        assertArrayEquals(expected, media.bytes)
    }

    @Test
    fun fetchProfileAvatarUsesDefaultAndExplainsMissingFile() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val client = RelayHttpClient(
            okHttpClient = OkHttpClient(),
            relayUrlProvider = { server.url("/").toString() },
            sessionTokenProvider = { "paired-token" },
        )

        val result = client.fetchProfileAvatar(null)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("avatar.png"))
        assertEquals("/api/profiles/default/avatar", server.takeRequest().path)
    }
}
