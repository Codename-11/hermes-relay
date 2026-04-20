package com.hermesandroid.relay.network

import com.hermesandroid.relay.data.ApiEndpoint
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.RelayEndpoint
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Unit tests for [EndpointResolver] — ADR 24 "Multi-endpoint pairing +
 * network-aware switching" (2026-04-19).
 *
 * Uses [MockWebServer] to stand up real loopback sockets so the
 * OkHttp → HEAD /health path exercises the production code unchanged.
 * A test-local mutable clock drives cache-TTL assertions deterministically
 * without `Thread.sleep`.
 */
class EndpointResolverTest {

    private lateinit var reachableServer: MockWebServer
    private lateinit var secondReachableServer: MockWebServer
    private lateinit var fastClient: OkHttpClient

    /** Mutable "now" for the resolver — tests advance it to test the cache TTL. */
    private val clockMillis = AtomicLong(0L)

    @Before
    fun setUp() {
        clockMillis.set(0L)
        reachableServer = MockWebServer().apply {
            dispatcher = healthDispatcher(statusCode = 200)
            start()
        }
        secondReachableServer = MockWebServer().apply {
            dispatcher = healthDispatcher(statusCode = 200)
            start()
        }
        // Fresh client per-test so connection pools don't leak probe state
        // across cases. 2-second timeouts mirror the resolver's expectation.
        fastClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        runCatching { reachableServer.shutdown() }
        runCatching { secondReachableServer.shutdown() }
    }

    // ---------------------------------------------------------------
    // Test 1 — priority-0 reachable + priority-1 reachable → picks 0
    // ---------------------------------------------------------------

    @Test
    fun priority0Wins_whenBothReachable() = runTest {
        val resolver = EndpointResolver(fastClient, clock = { clockMillis.get() })
        val lan = candidate("lan", priority = 0, server = reachableServer)
        val tail = candidate("tailscale", priority = 1, server = secondReachableServer)
        val winner = resolver.resolve(listOf(lan, tail))
        assertNotNull("expected priority-0 winner", winner)
        assertEquals("lan", winner!!.role)
    }

    // ---------------------------------------------------------------
    // Test 2 — priority-0 unreachable → falls through to priority-1
    // ---------------------------------------------------------------

    @Test
    fun fallsThroughToLowerPriority_whenHighestUnreachable() = runTest {
        // Stand up an unreachable endpoint by pointing at a shut-down server.
        val dead = MockWebServer().apply { start() }
        val deadPort = dead.port
        val deadHost = dead.hostName
        dead.shutdown() // now the port points at nothing → probe times out

        val resolver = EndpointResolver(fastClient, clock = { clockMillis.get() })
        val deadLan = EndpointCandidate(
            role = "lan",
            priority = 0,
            api = ApiEndpoint(host = deadHost, port = deadPort, tls = false),
            relay = RelayEndpoint(url = "ws://$deadHost:$deadPort", transportHint = "ws"),
        )
        val tail = candidate("tailscale", priority = 1, server = reachableServer)

        val winner = resolver.resolve(listOf(deadLan, tail))
        assertNotNull("expected fall-through to priority-1", winner)
        assertEquals("tailscale", winner!!.role)
    }

    // ---------------------------------------------------------------
    // Test 3 — no candidate reachable → null
    // ---------------------------------------------------------------

    @Test
    fun allUnreachable_returnsNull() = runTest {
        val dead1 = MockWebServer().apply { start() }
        val dead2 = MockWebServer().apply { start() }
        val h1 = dead1.hostName
        val p1 = dead1.port
        val h2 = dead2.hostName
        val p2 = dead2.port
        dead1.shutdown()
        dead2.shutdown()
        val resolver = EndpointResolver(fastClient, clock = { clockMillis.get() })
        val lan = EndpointCandidate(
            role = "lan", priority = 0,
            api = ApiEndpoint(h1, p1, tls = false),
            relay = RelayEndpoint("ws://$h1:$p1", transportHint = "ws"),
        )
        val tail = EndpointCandidate(
            role = "tailscale", priority = 1,
            api = ApiEndpoint(h2, p2, tls = false),
            relay = RelayEndpoint("ws://$h2:$p2", transportHint = "ws"),
        )
        val winner = resolver.resolve(listOf(lan, tail))
        assertNull("all unreachable → null (caller falls back to legacy URL)", winner)
    }

    // ---------------------------------------------------------------
    // Test 4 — tiebreaker inside a priority group picks the reachable one
    // ---------------------------------------------------------------

    @Test
    fun samePriority_picksReachableOverUnreachable() = runTest {
        // Two candidates at the same priority — one dead (should be skipped),
        // the other fast (should win). Together they exercise ADR 24's
        // "reachability is the tiebreaker within a priority group".
        val dead = MockWebServer().apply { start() }
        val deadHost = dead.hostName
        val deadPort = dead.port
        dead.shutdown()

        val resolver = EndpointResolver(fastClient, clock = { clockMillis.get() })
        val slowDead = EndpointCandidate(
            role = "lan-a", priority = 0,
            api = ApiEndpoint(deadHost, deadPort, tls = false),
            relay = RelayEndpoint("ws://$deadHost:$deadPort", transportHint = "ws"),
        )
        val fastAlive = candidate("lan-b", priority = 0, server = reachableServer)
        val winner = resolver.resolve(listOf(slowDead, fastAlive))
        assertNotNull(winner)
        assertEquals("lan-b", winner!!.role)
    }

    // ---------------------------------------------------------------
    // Test 5 — cached-unreachable result bypasses probe inside TTL
    // ---------------------------------------------------------------

    @Test
    fun cachedUnreachable_bypassesProbe_untilTtlExpires() = runTest {
        val resolver = EndpointResolver(fastClient, clock = { clockMillis.get() })

        // Stage 1: probe once with the server returning 500. The result is
        // cached as unreachable.
        reachableServer.dispatcher = healthDispatcher(statusCode = 500)
        val lan = candidate("lan", priority = 0, server = reachableServer)
        clockMillis.set(0L)
        val first = resolver.resolve(listOf(lan))
        assertNull("first call probed and cached unreachable", first)
        val requestsAfterFirst = reachableServer.requestCount

        // Stage 2: server now 200, but our cached unreachable is still
        // within the 30s TTL. No new probe should fire; winner stays null.
        reachableServer.dispatcher = healthDispatcher(statusCode = 200)
        clockMillis.set(1_000L) // +1s, well within 30s
        val second = resolver.resolve(listOf(lan))
        assertNull("within TTL, cached unreachable wins → no new probe", second)
        assertEquals(
            "no extra probe should have fired inside TTL",
            requestsAfterFirst,
            reachableServer.requestCount,
        )

        // Stage 3: advance past TTL + re-probe. Now the server's 200 lands.
        clockMillis.set(35_000L)
        val third = resolver.resolve(listOf(lan))
        assertNotNull("after TTL expiry, resolver re-probes and sees 200", third)
        assertTrue(
            "a fresh probe should have fired after TTL",
            reachableServer.requestCount > requestsAfterFirst,
        )
    }

    // ---------------------------------------------------------------
    // Test 6 — cached-reachable result is re-probed after TTL
    // ---------------------------------------------------------------

    @Test
    fun cachedReachable_reprobesAfterTtl() = runTest {
        val resolver = EndpointResolver(fastClient, clock = { clockMillis.get() })
        val lan = candidate("lan", priority = 0, server = reachableServer)

        // Prime the cache with a successful probe.
        clockMillis.set(0L)
        val first = resolver.resolve(listOf(lan))
        assertNotNull(first)
        val baselineCount = reachableServer.requestCount

        // Still within TTL: second call should hit the cache, no new probe.
        clockMillis.set(5_000L)
        val second = resolver.resolve(listOf(lan))
        assertNotNull(second)
        assertEquals(
            "within TTL, cached reachable should not trigger a new probe",
            baselineCount,
            reachableServer.requestCount,
        )

        // Past TTL: resolver must re-probe. We flip the server to 500 to
        // prove the probe actually ran against the live socket (cache
        // return would have stayed reachable).
        reachableServer.dispatcher = healthDispatcher(statusCode = 500)
        clockMillis.set(40_000L)
        val third = resolver.resolve(listOf(lan))
        assertNull("after TTL, fresh probe sees 500 → null", third)
        assertTrue(
            "a fresh probe should have fired after TTL",
            reachableServer.requestCount > baselineCount,
        )
    }

    // ---------------------------------------------------------------
    // Test 7 — markUnreachable short-circuits next resolve
    // ---------------------------------------------------------------

    @Test
    fun markUnreachable_skipsProbe_untilTtl() = runTest {
        val resolver = EndpointResolver(fastClient, clock = { clockMillis.get() })
        val lan = candidate("lan", priority = 0, server = reachableServer)

        // Mark as unreachable BEFORE any probe runs. resolve() must respect
        // the entry even though we never validated it.
        clockMillis.set(0L)
        resolver.markUnreachable(lan)
        val baselineCount = reachableServer.requestCount

        val winner = resolver.resolve(listOf(lan))
        assertNull(winner)
        assertEquals(
            "markUnreachable should short-circuit probe",
            baselineCount,
            reachableServer.requestCount,
        )
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private fun candidate(
        role: String,
        priority: Int,
        server: MockWebServer,
    ): EndpointCandidate {
        // MockWebServer.hostName is usually "localhost" — safe to embed in
        // both the api record and the relay url.
        val host = server.hostName
        val port = server.port
        return EndpointCandidate(
            role = role,
            priority = priority,
            api = ApiEndpoint(host = host, port = port, tls = false),
            relay = RelayEndpoint(url = "ws://$host:$port", transportHint = "ws"),
        )
    }

    private fun healthDispatcher(statusCode: Int): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.path == "/health") {
                return MockResponse().setResponseCode(statusCode)
            }
            return MockResponse().setResponseCode(404)
        }
    }

}
