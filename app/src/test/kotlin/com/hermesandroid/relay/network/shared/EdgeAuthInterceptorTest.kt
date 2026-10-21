package com.hermesandroid.relay.network.shared

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeAuthInterceptorTest {

    private val credential = EdgeAuthCredential(
        clientId = "client-id-value",
        clientSecret = "client-secret-value",
    )

    private fun clientFor(scope: okhttp3.HttpUrl) = OkHttpClient.Builder()
        .addInterceptor(
            EdgeAuthInterceptor { url -> credential.takeIf { sameEdgeAuthScope(url, scope) } },
        )
        .build()

    @Test
    fun `adds the service token to requests for the scoped origin`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val client = clientFor(server.url("/"))

            client.newCall(Request.Builder().url(server.url("/api/status")).build()).execute().use { response ->
                assertEquals(200, response.code)
            }

            val recorded = server.takeRequest()
            assertEquals("client-id-value", recorded.getHeader(EdgeAuthHeaders.CLIENT_ID))
            assertEquals("client-secret-value", recorded.getHeader(EdgeAuthHeaders.CLIENT_SECRET))
        }
    }

    @Test
    fun `never sends the service token to another origin`() {
        MockWebServer().use { scoped ->
            MockWebServer().use { other ->
                scoped.enqueue(MockResponse().setResponseCode(200))
                other.enqueue(MockResponse().setResponseCode(200))
                val client = clientFor(scoped.url("/"))

                client.newCall(Request.Builder().url(other.url("/api/status")).build()).execute().close()

                val recorded = other.takeRequest()
                assertNull(recorded.getHeader(EdgeAuthHeaders.CLIENT_ID))
                assertNull(recorded.getHeader(EdgeAuthHeaders.CLIENT_SECRET))
            }
        }
    }

    @Test
    fun `sends nothing when the credential is incomplete`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200))
            val halfPair = OkHttpClient.Builder()
                .addInterceptor(
                    EdgeAuthInterceptor { EdgeAuthCredential(clientId = "only-the-id", clientSecret = "") },
                )
                .build()

            halfPair.newCall(Request.Builder().url(server.url("/api/status")).build()).execute().close()

            val recorded = server.takeRequest()
            assertNull(recorded.getHeader(EdgeAuthHeaders.CLIENT_ID))
            assertNull(recorded.getHeader(EdgeAuthHeaders.CLIENT_SECRET))
        }
    }

    @Test
    fun `a malformed value degrades to an unauthenticated request instead of failing the call`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200))
            val malformed = OkHttpClient.Builder()
                .addInterceptor(
                    EdgeAuthInterceptor { credential.copy(clientSecret = "line\nbreak") },
                )
                .build()

            malformed.newCall(Request.Builder().url(server.url("/api/status")).build()).execute().use { response ->
                assertEquals(200, response.code)
            }

            val recorded = server.takeRequest()
            assertNull(recorded.getHeader(EdgeAuthHeaders.CLIENT_ID))
        }
    }

    @Test
    fun `scope comparison requires scheme host and port`() {
        val base = "https://hermes.example.com".toHttpUrl()

        assertTrue(sameEdgeAuthScope("https://hermes.example.com/api/ws".toHttpUrl(), base))
        assertTrue(sameEdgeAuthScope("https://hermes.example.com:443/api/ws".toHttpUrl(), base))
        assertFalse(sameEdgeAuthScope("http://hermes.example.com/api/ws".toHttpUrl(), base))
        assertFalse(sameEdgeAuthScope("https://hermes.example.com:8443/api/ws".toHttpUrl(), base))
        assertFalse(sameEdgeAuthScope("https://example.com/api/ws".toHttpUrl(), base))
        assertFalse(sameEdgeAuthScope("https://other.example.com/api/ws".toHttpUrl(), base))
    }
}
