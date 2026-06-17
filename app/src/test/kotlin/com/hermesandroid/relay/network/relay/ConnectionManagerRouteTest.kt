package com.hermesandroid.relay.network.relay

import android.content.Context
import android.net.ConnectivityManager
import com.hermesandroid.relay.data.ApiEndpoint
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.RelayEndpoint
import com.hermesandroid.relay.network.shared.EndpointResolver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
                if (request.path?.endsWith("/health") == true) {
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
}
