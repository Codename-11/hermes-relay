package com.hermesandroid.relay.network.relay

import com.hermesandroid.relay.diagnostics.DiagnosticCategory
import com.hermesandroid.relay.diagnostics.DiagnosticsLog
import java.net.ServerSocket
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RelayHttpClientDiagnosticsTest {
    @Before
    fun setUp() {
        DiagnosticsLog.clear()
    }

    @After
    fun tearDown() {
        DiagnosticsLog.clear()
    }

    @Test
    fun refusedHealthProbeExplainsProtocolConversionAndOwningListener() = runTest {
        val unusedPort = ServerSocket(0).use { it.localPort }
        val configuredRelay = "ws://127.0.0.1:$unusedPort"
        val client = RelayHttpClient(
            okHttpClient = OkHttpClient(),
            relayUrlProvider = { configuredRelay },
            sessionTokenProvider = { null },
        )

        val result = client.probeHealth(configuredRelay)

        assertTrue(result.isFailure)
        val entry = DiagnosticsLog.recent(setOf(DiagnosticCategory.Relay)).first()
        assertEquals("Relay health probe before WebSocket connection", entry.operation)
        assertEquals("ws://[host]", entry.configuredUrl)
        assertEquals("http://[host]/health", entry.requestUrl)
        assertEquals(
            "Verify Relay is running and listening on the configured host and port.",
            entry.suggestion,
        )
        assertFalse(entry.detail.orEmpty().contains(unusedPort.toString()))
    }

    @Test
    fun routeQualifiedWebSocketInputProbesSiblingHealthRoute() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":"ok","version":"1.0.0","clients":0,"sessions":0}""",
            ),
        )
        server.start()
        try {
            val configuredRelay = "ws://${server.hostName}:${server.port}/relay/ws"
            val client = RelayHttpClient(
                okHttpClient = OkHttpClient(),
                relayUrlProvider = { configuredRelay },
                sessionTokenProvider = { null },
            )

            assertTrue(client.probeHealth(configuredRelay).isSuccess)
            assertEquals("/relay/health", server.takeRequest().path)
            val entry = DiagnosticsLog.recent(setOf(DiagnosticCategory.Relay)).first()
            assertEquals("ws://[host]/relay/ws", entry.configuredUrl)
            assertEquals("http://[host]/relay/health", entry.requestUrl)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun dashboardIngressUsesOuterAuthClientAndSeparateRelayHeader() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"sessions":[]}"""))
        server.start()
        try {
            val ingress = "http://${server.hostName}:${server.port}" +
                "/api/plugins/hermes-relay/transport"
            val providerCalls = AtomicInteger(0)
            val outerAuthClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("X-Dashboard-Test-Auth", "ready")
                            .build(),
                    )
                }
                .build()
            val client = RelayHttpClient(
                okHttpClient = OkHttpClient(),
                relayUrlProvider = { ingress },
                sessionTokenProvider = { "relay-session" },
                dashboardHttpClientProvider = {
                    providerCalls.incrementAndGet()
                    outerAuthClient
                },
            )

            assertTrue(client.listSessions().isSuccess)
            val request = server.takeRequest()
            assertEquals(1, providerCalls.get())
            assertEquals("/api/plugins/hermes-relay/transport/sessions", request.path)
            assertEquals("ready", request.getHeader("X-Dashboard-Test-Auth"))
            assertEquals("relay-session", request.getHeader(RELAY_SESSION_HEADER))
            assertNull(request.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun dashboardIngressHealthTimeoutClientDerivesFromOuterAuthClient() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":"ok","version":"1.0.0","clients":0,"sessions":0}""",
            ),
        )
        server.start()
        try {
            val ingress = "http://${server.hostName}:${server.port}" +
                "/api/plugins/hermes-relay/transport"
            val providerCalls = AtomicInteger(0)
            val client = RelayHttpClient(
                okHttpClient = OkHttpClient(),
                relayUrlProvider = { ingress },
                sessionTokenProvider = { null },
                dashboardHttpClientProvider = {
                    providerCalls.incrementAndGet()
                    OkHttpClient.Builder()
                        .addInterceptor { chain ->
                            chain.proceed(
                                chain.request().newBuilder()
                                    .header("X-Dashboard-Test-Auth", "ready")
                                    .build(),
                            )
                        }
                        .build()
                },
            )

            assertTrue(client.probeHealth(ingress).isSuccess)
            val request = server.takeRequest()
            assertEquals(1, providerCalls.get())
            assertEquals("ready", request.getHeader("X-Dashboard-Test-Auth"))
            assertEquals("/api/plugins/hermes-relay/transport/health", request.path)
        } finally {
            server.shutdown()
        }
    }
}
