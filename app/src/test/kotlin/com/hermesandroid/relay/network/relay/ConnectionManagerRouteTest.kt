package com.hermesandroid.relay.network.relay

import android.content.Context
import android.net.ConnectivityManager
import com.hermesandroid.relay.data.ApiEndpoint
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.RelayEndpoint
import com.hermesandroid.relay.data.DashboardEndpoint
import com.hermesandroid.relay.network.shared.EndpointResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

/**
 * Standard-route (no relay socket) coverage for [ConnectionManager]'s ADR 24
 * network-aware switching, added when the machinery was decoupled from the
 * WSS connect path:
 *
 *  1. The [ConnectivityManager.NetworkCallback] registers at construction —
 *     previously only `connect()` registered it, so standard (no-Relay)
 *     connections never saw network changes at all.
 *  2. A network `onAvailable` with **no relay socket** still re-resolves and
 *     publishes [ConnectionManager.activeEndpoint], which is what the
 *     HTTP-only surfaces (chat, dashboard, standard voice) follow.
 *  3. `refreshActiveEndpoint(clearProbeCache = true)` forgets a
 *     cached-reachable route so a just-died endpoint can't win the resolve
 *     for the remainder of the 60s positive cache TTL.
 *
 * Runs under Robolectric for ConnectivityManager + a real [MockWebServer]
 * for the resolver's `HEAD /health` probes — same probe contract as
 * [EndpointResolverTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectionManagerRouteTest {

    private lateinit var context: Context
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var server: MockWebServer
    private val managers = mutableListOf<ConnectionManager>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        connectivityManager = context.getSystemService(ConnectivityManager::class.java)!!
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path?.endsWith("/health") == true || request.path == "/api/status") {
                    MockResponse().setResponseCode(200)
                } else {
                    MockResponse().setResponseCode(404)
                }
        }
        server.start()
    }

    @After
    fun tearDown() {
        managers.forEach { runCatching { it.shutdown() } }
        managers.clear()
        runCatching { server.shutdown() }
    }

    private fun registeredCallbacks(): Set<ConnectivityManager.NetworkCallback> =
        shadowOf(connectivityManager).networkCallbacks.toSet()

    private fun candidate(role: String = "tailscale"): EndpointCandidate =
        EndpointCandidate(
            role = role,
            priority = 0,
            api = ApiEndpoint(host = server.hostName, port = server.port, tls = false),
            relay = RelayEndpoint(url = "ws://${server.hostName}:${server.port}"),
        )

    private fun buildManager(
        candidates: () -> List<EndpointCandidate>,
    ): ConnectionManager = ConnectionManager(
        ChannelMultiplexer(),
        context = context,
        endpointResolver = EndpointResolver(httpClient = OkHttpClient()),
        endpointCandidatesProvider = { candidates() },
    ).also { managers.add(it) }

    @Test
    fun `network callback registers at construction without connect`() {
        val before = registeredCallbacks()

        buildManager { emptyList() }

        assertEquals(
            "ConnectionManager must register its NetworkCallback at construction " +
                "so standard (no-Relay) connections follow network changes",
            before.size + 1,
            registeredCallbacks().size,
        )
    }

    @Test
    fun `shutdown unregisters the construction-time network callback`() {
        val before = registeredCallbacks()
        val manager = buildManager { emptyList() }

        manager.shutdown()

        assertEquals(before, registeredCallbacks())
    }

    @Test
    fun `onAvailable with no relay socket re-resolves and publishes activeEndpoint`() {
        val before = registeredCallbacks()
        val manager = buildManager { listOf(candidate()) }
        assertNull("no endpoint should be published before any trigger", manager.activeEndpoint.value)

        val callback = (registeredCallbacks() - before).single()
        callback.onAvailable(ShadowNetwork.newInstance(101))

        val published = runBlocking {
            withTimeout(10_000) { manager.activeEndpoint.first { it != null } }
        }
        assertEquals("tailscale", published!!.role)
    }

    @Test
    fun `refreshActiveEndpoint returns stale cached winner unless clearProbeCache`() {
        val manager = buildManager { listOf(candidate()) }

        // Prime: server up → candidate resolves and its probe result caches.
        val first = runBlocking { manager.refreshActiveEndpoint() }
        assertNotNull("expected the live candidate to resolve", first)

        // Route dies inside the positive cache TTL.
        server.shutdown()

        // Without clearing, the 60s positive cache still vouches for it.
        val stale = runBlocking { manager.refreshActiveEndpoint() }
        assertEquals(
            "cached-reachable entry should still win within the TTL",
            "tailscale",
            stale?.role,
        )

        // Clearing the cache forces a fresh probe, which now fails.
        val fresh = runBlocking { manager.refreshActiveEndpoint(clearProbeCache = true) }
        assertNull("fresh probe against the dead route must yield no winner", fresh)
        assertNull(manager.activeEndpoint.value)
    }

    @Test
    fun `probeAndReconnectNow publishes the winner on the standard (no socket) path`() {
        val manager = buildManager { listOf(candidate()) }

        val winner = runBlocking { manager.probeAndReconnectNow() }

        assertEquals("tailscale", winner?.role)
        assertEquals(
            "probeAndReconnectNow must publish activeEndpoint even with no relay socket",
            "tailscale",
            manager.activeEndpoint.value?.role,
        )
    }

    @Test
    fun `probeAndReconnectNow publishes null when every route fails its probe`() {
        val manager = buildManager { listOf(candidate()) }

        // Prime a winner, then kill the route. The old implementation
        // early-returned here without publishing, leaving the Routes card
        // stuck on the stale winner / "Resolving" with no feedback.
        runBlocking { manager.refreshActiveEndpoint() }
        assertNotNull(manager.activeEndpoint.value)
        server.shutdown()

        val winner = runBlocking { manager.probeAndReconnectNow() }

        assertNull("no reachable route → null winner", winner)
        assertNull(
            "the failed outcome must be published, not silently swallowed",
            manager.activeEndpoint.value,
        )
    }

    @Test
    fun `optional API timeout does not block healthy Dashboard refresh and is deduplicated`() {
        val slowApi = MockWebServer().apply {
            enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            start()
        }
        val route = EndpointCandidate(
            role = "lan",
            priority = 0,
            dashboard = DashboardEndpoint(server.url("/").toString().trimEnd('/')),
            api = ApiEndpoint(host = slowApi.hostName, port = slowApi.port, tls = false),
        )
        val manager = buildManager { listOf(route) }

        try {
            val firstElapsed = measureTimeMillis {
                val winner = runBlocking { manager.refreshActiveEndpoint() }
                assertEquals("lan", winner?.role)
            }
            assertEquals("the healthy Dashboard route must publish immediately", "lan", manager.activeEndpoint.value?.role)
            org.junit.Assert.assertTrue(
                "optional API discovery must not add its four-second timeout to Dashboard readiness (elapsed=${firstElapsed}ms)",
                firstElapsed < 1_500L,
            )
            assertNotNull("background API discovery should still run", slowApi.takeRequest(2, TimeUnit.SECONDS))

            val secondElapsed = measureTimeMillis {
                runBlocking { manager.refreshActiveEndpoint() }
            }
            org.junit.Assert.assertTrue(
                "a concurrent refresh must not wait for the in-flight optional probe (elapsed=${secondElapsed}ms)",
                secondElapsed < 1_500L,
            )
            assertEquals("the in-flight optional probe must be coalesced", 1, slowApi.requestCount)
        } finally {
            slowApi.shutdown()
        }
    }

    @Test
    fun `stale optional API probe cannot publish over a newer route set`() {
        val slowApi = MockWebServer().apply {
            enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            start()
        }
        val fastApi = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(200))
            start()
        }
        var routes = listOf(
            EndpointCandidate(
                role = "connection-a",
                dashboard = DashboardEndpoint(server.url("/").toString().trimEnd('/')),
                api = ApiEndpoint(slowApi.hostName, slowApi.port, tls = false),
            ),
        )
        val manager = buildManager { routes }

        try {
            runBlocking { manager.refreshActiveEndpoint() }
            assertNotNull(slowApi.takeRequest(2, TimeUnit.SECONDS))

            routes = listOf(
                EndpointCandidate(
                    role = "connection-b",
                    dashboard = DashboardEndpoint(server.url("/").toString().trimEnd('/')),
                    api = ApiEndpoint(fastApi.hostName, fastApi.port, tls = false),
                ),
            )
            runBlocking { manager.refreshActiveEndpoint() }

            val current = runBlocking {
                withTimeout(10_000L) {
                    manager.activeApiEndpoint.first { it?.role == "connection-b" }
                }
            }
            assertEquals("connection-b", current?.role)
            assertEquals(
                "the completed stale probe must never become active while the current route resolves",
                "connection-b",
                manager.activeApiEndpoint.value?.role,
            )
            assertEquals(1, fastApi.requestCount)
        } finally {
            slowApi.shutdown()
            fastApi.shutdown()
        }
    }

    @Test
    fun `route qualified input opens canonical websocket without double append`() {
        val manager = ConnectionManager(ChannelMultiplexer()).also { managers.add(it) }
        manager.setInsecureMode(true)

        manager.connect("ws://${server.hostName}:${server.port}/relay/ws")

        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("/relay/ws", request!!.path)
    }

    @Test
    fun `dashboard relay ingress asks for a fresh authorized request per dial`() {
        val requestCount = AtomicInteger(0)
        val manager = ConnectionManager(
            ChannelMultiplexer(),
            dashboardRelayRequestProvider = { url ->
                val ticket = requestCount.incrementAndGet()
                Request.Builder().url("$url?ticket=ticket-$ticket").build()
            },
        ).also { managers.add(it) }
        manager.setInsecureMode(true)
        val ingress = "ws://${server.hostName}:${server.port}" +
            "/api/plugins/hermes-relay/transport/ws"

        manager.connect(ingress)
        val first = server.takeRequest(5, TimeUnit.SECONDS)
        manager.disconnect()
        manager.connect(ingress)
        val second = server.takeRequest(5, TimeUnit.SECONDS)

        assertEquals(2, requestCount.get())
        assertEquals(
            "/api/plugins/hermes-relay/transport/ws?ticket=ticket-1",
            first?.path,
        )
        assertEquals(
            "/api/plugins/hermes-relay/transport/ws?ticket=ticket-2",
            second?.path,
        )
    }

    private fun ingressCandidate(priority: Int = 0) = EndpointCandidate(
        role = "dashboard-ingress",
        priority = priority,
        relay = RelayEndpoint(
            "ws://${server.hostName}:${server.port}/api/plugins/hermes-relay/transport",
        ),
    )

    private fun directCandidate(priority: Int = 1) = EndpointCandidate(
        role = "direct",
        priority = priority,
        relay = RelayEndpoint("ws://${server.hostName}:${server.port}"),
    )

    private fun ingressFallbackManager(
        requestProvider: (suspend (String) -> Request?)?,
    ): ConnectionManager = ConnectionManager(
        ChannelMultiplexer(),
        context = context,
        endpointResolver = EndpointResolver(httpClient = OkHttpClient()),
        endpointCandidatesProvider = { listOf(ingressCandidate(), directCandidate()) },
        dashboardRelayRequestProvider = requestProvider,
    ).also {
        managers.add(it)
        it.setInsecureMode(true)
    }

    @Test
    fun `missing dashboard ingress request provider falls back to direct relay`() {
        val manager = ingressFallbackManager(requestProvider = null)

        manager.connect("ws://${server.hostName}:${server.port}")

        val winner = runBlocking {
            withTimeout(5_000) { manager.activeRelayEndpoint.first { it?.role == "direct" } }
        }
        assertEquals("direct", winner?.role)
    }

    @Test
    fun `dashboard ingress ticket failure falls back to direct relay`() {
        val manager = ingressFallbackManager(requestProvider = { null })

        manager.connect("ws://${server.hostName}:${server.port}")

        val winner = runBlocking {
            withTimeout(5_000) { manager.activeRelayEndpoint.first { it?.role == "direct" } }
        }
        assertEquals("direct", winner?.role)
    }

    @Test
    fun `dashboard ingress admission close falls back to direct relay`() {
        val ingressListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.close(4403, "admission denied")
            }
        }
        val directListener = object : WebSocketListener() {}
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.endsWith("/health") == true -> MockResponse().setResponseCode(200)
                request.path?.startsWith("/api/plugins/hermes-relay/transport/ws") == true ->
                    MockResponse().withWebSocketUpgrade(ingressListener)
                request.path == "/ws" -> MockResponse().withWebSocketUpgrade(directListener)
                else -> MockResponse().setResponseCode(404)
            }
        }
        val manager = ingressFallbackManager { url ->
            Request.Builder().url("$url?ticket=admission-ticket").build()
        }

        manager.connect("ws://${server.hostName}:${server.port}")

        val winner = runBlocking {
            withTimeout(5_000) { manager.activeRelayEndpoint.first { it?.role == "direct" } }
        }
        assertEquals("direct", winner?.role)
    }

    @Test
    fun `stale ingress close cannot poison successful same-url replacement`() {
        val fallbackStarted = CompletableDeferred<Unit>()
        val releaseFallback = CompletableDeferred<Unit>()
        val replacementOpened = CompletableDeferred<Unit>()
        val ingressSockets = AtomicInteger(0)
        val ticketRequests = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.endsWith("/health") == true -> MockResponse().setResponseCode(200)
                request.path?.startsWith("/api/plugins/hermes-relay/transport/ws") == true -> {
                    val number = ingressSockets.incrementAndGet()
                    MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            if (number == 1) {
                                webSocket.close(4403, "old admission denied")
                            } else {
                                webSocket.send(
                                    """{"channel":"system","type":"auth.ok","payload":{}}""",
                                )
                                replacementOpened.complete(Unit)
                            }
                        }
                    })
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val manager = ConnectionManager(
            ChannelMultiplexer(),
            context = context,
            endpointResolver = EndpointResolver(httpClient = OkHttpClient()),
            endpointCandidatesProvider = { listOf(ingressCandidate(), directCandidate()) },
            dashboardRelayRequestProvider = { url ->
                val ticket = ticketRequests.incrementAndGet()
                Request.Builder().url("$url?ticket=ticket-$ticket").build()
            },
            beforeIngressFailureCommit = {
                fallbackStarted.complete(Unit)
                releaseFallback.await()
            },
        ).also {
            managers.add(it)
            it.setInsecureMode(true)
        }

        manager.connect("ws://${server.hostName}:${server.port}")
        runBlocking { withTimeout(5_000) { fallbackStarted.await() } }
        assertEquals(true, manager.reconnectForAuthenticatedMetadataUpdate())
        runBlocking { withTimeout(5_000) { replacementOpened.await() } }
        releaseFallback.complete(Unit)
        Thread.sleep(100)

        assertEquals("dashboard-ingress", manager.activeRelayEndpoint.value?.role)
        assertEquals(ConnectionState.Connected, manager.connectionState.value)
        assertEquals(2, ticketRequests.get())
    }
}
