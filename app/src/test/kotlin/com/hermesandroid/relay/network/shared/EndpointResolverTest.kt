package com.hermesandroid.relay.network.shared

import com.hermesandroid.relay.data.ApiEndpoint
import com.hermesandroid.relay.data.DashboardEndpoint
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.RelayEndpoint
import com.hermesandroid.relay.data.ProxyEndpoint
import com.hermesandroid.relay.diagnostics.DiagnosticCategory
import com.hermesandroid.relay.diagnostics.DiagnosticSeverity
import com.hermesandroid.relay.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureTimeMillis

/**
 * Unit tests for [EndpointResolver] — ADR 24 "Multi-endpoint pairing +
 * network-aware switching" (2026-04-19).
 *
 * Uses [MockWebServer] to stand up real loopback sockets so the
 * OkHttp → GET /health path exercises the production code unchanged.
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
        DiagnosticsLog.clear()
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
        DiagnosticsLog.clear()
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

    @Test
    fun lowerPriorityProbeStartsSpeculativelyButCannotDisplaceReachablePriorityZero() = runTest {
        val lowerStarted = CountDownLatch(1)
        reachableServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setHeadersDelay(750, TimeUnit.MILLISECONDS)
        }
        secondReachableServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                lowerStarted.countDown()
                return MockResponse().setResponseCode(200)
            }
        }
        val resolver = EndpointResolver(fastClient, clock = { clockMillis.get() })
        val preferred = candidate("https", priority = 0, server = reachableServer)
        val fallback = candidate("lan", priority = 1, server = secondReachableServer)

        val resolving = async(Dispatchers.Default) { resolver.resolve(listOf(preferred, fallback)) }

        assertTrue(
            "fallback probe should start before the slower preferred response completes",
            lowerStarted.await(500, TimeUnit.MILLISECONDS),
        )
        assertEquals("https", resolving.await()?.role)
    }

    @Test
    fun supportedRouteWins_overHigherPriorityExperimentalReach() = runTest {
        val resolver = EndpointResolver(fastClient, clock = { clockMillis.get() })
        val reach = candidate("outbound_broker", priority = 0, server = reachableServer)
            .copy(experimental = true)
        val tailscale = candidate("tailscale", priority = 1, server = secondReachableServer)

        val winner = resolver.resolve(listOf(reach, tailscale))

        assertEquals("tailscale", winner?.role)
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
        // within the TTL. No new probe should fire; winner stays null.
        reachableServer.dispatcher = healthDispatcher(statusCode = 200)
        clockMillis.set(1_000L) // +1s, well within TTL
        val second = resolver.resolve(listOf(lan))
        assertNull("within TTL, cached unreachable wins → no new probe", second)
        assertEquals(
            "no extra probe should have fired inside TTL",
            requestsAfterFirst,
            reachableServer.requestCount,
        )

        // Stage 3: advance past TTL + re-probe. Now the server's 200 lands.
        clockMillis.set(EndpointResolver.NEGATIVE_CACHE_TTL_MS + 1_000L)
        val third = resolver.resolve(listOf(lan))
        assertNotNull("after TTL expiry, resolver re-probes and sees 200", third)
        assertTrue(
            "a fresh probe should have fired after TTL",
            reachableServer.requestCount > requestsAfterFirst,
        )
    }

    @Test
    fun samePriority_fastSiblingWinsWithoutWaitingForSlowFirstCandidate() = runTest {
        val slow = MockWebServer().apply {
            enqueue(MockResponse().setHeadersDelay(3, TimeUnit.SECONDS).setResponseCode(200))
            start()
        }
        try {
            val slowFirst = candidate("slow-first", priority = 0, server = slow)
            val fastSecond = candidate("fast-second", priority = 0, server = reachableServer)
            val resolver = EndpointResolver(fastClient, clock = { clockMillis.get() })
            lateinit var winner: EndpointCandidate

            val elapsed = measureTimeMillis {
                winner = resolver.resolve(listOf(slowFirst, fastSecond))!!
            }

            assertEquals("fast-second", winner.role)
            assertTrue(
                "completion order must win; input order must not add the slow sibling delay (elapsed=${elapsed}ms)",
                elapsed < 1_500L,
            )
        } finally {
            slow.shutdown()
        }
    }

    @Test
    fun concurrentResolve_sharesOnePhysicalProbe() = runTest {
        reachableServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse()
                    .setResponseCode(200)
                    .setHeadersDelay(300, TimeUnit.MILLISECONDS)
        }
        val resolver = EndpointResolver(fastClient, clock = { clockMillis.get() })
        val lan = candidate("lan", priority = 0, server = reachableServer)

        val winners = listOf(
            async { resolver.resolve(listOf(lan), EndpointSurface.Api) },
            async { resolver.resolve(listOf(lan), EndpointSurface.Api) },
        ).awaitAll()

        assertEquals(listOf(lan, lan), winners)
        assertEquals(
            "concurrent lifecycle callers must share the same route probe",
            1,
            reachableServer.requestCount,
        )
    }

    @Test
    fun cancellingFirstWaiter_doesNotCancelSharedProbeForSecondWaiter() = runTest {
        reachableServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse()
                    .setResponseCode(200)
                    .setHeadersDelay(300, TimeUnit.MILLISECONDS)
        }
        val resolver = EndpointResolver(fastClient, clock = { clockMillis.get() })
        val lan = candidate("lan", priority = 0, server = reachableServer)

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            resolver.resolve(listOf(lan), EndpointSurface.Api)
        }
        assertNotNull(reachableServer.takeRequest(5, TimeUnit.SECONDS))
        first.cancel()
        val second = resolver.resolve(listOf(lan), EndpointSurface.Api)

        assertEquals(lan, second)
        assertEquals(1, reachableServer.requestCount)
    }

    @Test
    fun clearCache_cancelsActiveProbe_withoutPublishingUnreachableState() = runTest {
        val firstRequestStarted = CountDownLatch(1)
        val releaseFirstRequest = CountDownLatch(1)
        val firstRequestFinished = CountDownLatch(1)
        val requestSequence = AtomicInteger(0)
        val blockingClient = fastClient.newBuilder()
            .addInterceptor { chain ->
                if (requestSequence.incrementAndGet() == 1) {
                    firstRequestStarted.countDown()
                    try {
                        releaseFirstRequest.await(5, TimeUnit.SECONDS)
                    } finally {
                        firstRequestFinished.countDown()
                    }
                    throw InterruptedIOException("invalidated test probe")
                }
                chain.proceed(chain.request())
            }
            .build()
        val resolver = EndpointResolver(blockingClient, clock = { clockMillis.get() })
        val lan = candidate("cancelled-probe-test", priority = 0, server = reachableServer)
        val key = EndpointResolver.cacheKey(lan, EndpointSurface.Api)

        val staleResolve = async(start = CoroutineStart.UNDISPATCHED) {
            resolver.resolve(listOf(lan), EndpointSurface.Api)
        }
        assertTrue(
            "the first physical probe must be active before invalidation",
            firstRequestStarted.await(5, TimeUnit.SECONDS),
        )

        resolver.clearCache()

        assertNull("cancellation is not an unreachable verdict", resolver.probeOutcomes.value[key])
        assertTrue("an invalidated probe must not populate the cache", resolver.cacheSnapshot().isEmpty())
        assertTrue(
            "an invalidated probe must not emit a failure diagnostic",
            DiagnosticsLog.entries.value.none {
                it.category == DiagnosticCategory.Endpoint &&
                    it.endpointRole == lan.role &&
                    it.severity != DiagnosticSeverity.Info
            },
        )

        val freshWinner = resolver.resolve(listOf(lan), EndpointSurface.Api)
        assertEquals("a fresh probe after invalidation can succeed", lan, freshWinner)

        releaseFirstRequest.countDown()
        assertTrue(firstRequestFinished.await(5, TimeUnit.SECONDS))
        staleResolve.join()

        val finalOutcome = resolver.probeOutcomes.value[key]
        assertNotNull(finalOutcome)
        assertTrue("the stale failure must not overwrite the fresh success", finalOutcome!!.reachable)
        assertTrue(
            "the stale failure must not emit a delayed failure diagnostic",
            DiagnosticsLog.entries.value.none {
                it.category == DiagnosticCategory.Endpoint &&
                    it.endpointRole == lan.role &&
                    it.severity != DiagnosticSeverity.Info
            },
        )
        assertEquals(true, resolver.cacheSnapshot()[key]?.second)
    }

    @Test
    fun clearCache_completesSamePriorityRace_withoutPublishingStaleFailures() = runTest {
        val staleRequestsStarted = CountDownLatch(2)
        val releaseStaleRequests = CountDownLatch(1)
        val staleRequestsFinished = CountDownLatch(2)
        val requestSequence = AtomicInteger(0)
        val blockingClient = fastClient.newBuilder()
            .addInterceptor { chain ->
                if (requestSequence.incrementAndGet() <= 2) {
                    staleRequestsStarted.countDown()
                    try {
                        releaseStaleRequests.await(5, TimeUnit.SECONDS)
                    } finally {
                        staleRequestsFinished.countDown()
                    }
                    throw InterruptedIOException("invalidated test probe")
                }
                chain.proceed(chain.request())
            }
            .build()
        val resolver = EndpointResolver(blockingClient, clock = { clockMillis.get() })
        val first = candidate("cancelled-race-first", priority = 0, server = reachableServer)
        val second = candidate("cancelled-race-second", priority = 0, server = secondReachableServer)
        val testedRoles = setOf(first.role, second.role)
        val testedKeys = setOf(
            EndpointResolver.cacheKey(first, EndpointSurface.Api),
            EndpointResolver.cacheKey(second, EndpointSurface.Api),
        )

        try {
            val staleResolve = async(start = CoroutineStart.UNDISPATCHED) {
                resolver.resolve(listOf(first, second), EndpointSurface.Api)
            }
            assertTrue(
                "both shared probes must be active before invalidation",
                staleRequestsStarted.await(5, TimeUnit.SECONDS),
            )

            resolver.clearCache()
            releaseStaleRequests.countDown()
            assertTrue(staleRequestsFinished.await(5, TimeUnit.SECONDS))

            assertNull(
                "invalidated candidates are non-winners, not failures",
                withContext(Dispatchers.Default.limitedParallelism(1)) {
                    withTimeout(1_000L) { staleResolve.await() }
                },
            )
            assertTrue(testedKeys.none { it in resolver.probeOutcomes.value })
            assertTrue(resolver.cacheSnapshot().isEmpty())

            val freshWinner = withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(2_000L) {
                    resolver.resolve(listOf(first, second), EndpointSurface.Api)
                }
            }
            assertNotNull("a fresh same-priority race can succeed", freshWinner)
            assertTrue(freshWinner == first || freshWinner == second)
            assertTrue(
                "invalidated failures must not emit diagnostics",
                DiagnosticsLog.entries.value.none {
                    it.category == DiagnosticCategory.Endpoint &&
                        it.endpointRole in testedRoles &&
                        it.severity != DiagnosticSeverity.Info
                },
            )
        } finally {
            releaseStaleRequests.countDown()
        }
        assertTrue(
            "stale failures must not overwrite fresh race outcomes",
            resolver.probeOutcomes.value
                .filterKeys { it in testedKeys }
                .values
                .all { it.reachable },
        )
        assertTrue(
            "stale failures must not emit delayed diagnostics",
            DiagnosticsLog.entries.value.none {
                it.category == DiagnosticCategory.Endpoint &&
                    it.endpointRole in testedRoles &&
                    it.severity != DiagnosticSeverity.Info
            },
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
        clockMillis.set(EndpointResolver.CACHE_TTL_MS + 1_000L)
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

    @Test
    fun markUnreachable_promotesLowerPriorityFallbackWithinTtl() = runTest {
        val resolver = EndpointResolver(fastClient, clock = { clockMillis.get() })
        val lan = candidate("lan", priority = 0, server = reachableServer)
        val tail = candidate("tailscale", priority = 1, server = secondReachableServer)

        clockMillis.set(0L)
        resolver.markUnreachable(lan)

        val winner = resolver.resolve(listOf(lan, tail))

        assertNotNull(winner)
        assertEquals("tailscale", winner!!.role)
    }

    // ---------------------------------------------------------------
    // Test 8 — probeOutcomes records UI-facing verdicts per candidate
    // ---------------------------------------------------------------

    @Test
    fun probeOutcomes_recordsReachableAndUnreachableVerdicts() = runTest {
        val resolver = EndpointResolver(fastClient, clock = { clockMillis.get() })
        val lan = candidate("lan", priority = 0, server = reachableServer)

        reachableServer.dispatcher = healthDispatcher(statusCode = 200)
        resolver.resolve(listOf(lan))
        val reachableOutcome = resolver.probeOutcomes.value[EndpointResolver.cacheKey(lan)]
        assertNotNull("successful probe must record an outcome", reachableOutcome)
        assertTrue(reachableOutcome!!.reachable)
        assertNull("reachable outcomes carry no failure detail", reachableOutcome.detail)

        // Flip to 500 past the positive TTL so a real probe fires again.
        reachableServer.dispatcher = healthDispatcher(statusCode = 500)
        clockMillis.set(EndpointResolver.CACHE_TTL_MS + 1_000L)
        resolver.resolve(listOf(lan))
        val failedOutcome = resolver.probeOutcomes.value[EndpointResolver.cacheKey(lan)]
        assertNotNull(failedOutcome)
        assertTrue("failed probe must flip the outcome", !failedOutcome!!.reachable)
        assertEquals("HTTP 500 from /health", failedOutcome.detail)
    }

    @Test
    fun probeOutcomes_recordsConnectionFailureDetail() = runTest {
        val dead = MockWebServer().apply { start() }
        val deadHost = dead.hostName
        val deadPort = dead.port
        dead.shutdown()

        val resolver = EndpointResolver(fastClient, clock = { clockMillis.get() })
        val deadCandidate = EndpointCandidate(
            role = "tailscale", priority = 0,
            api = ApiEndpoint(deadHost, deadPort, tls = false),
            relay = RelayEndpoint("ws://$deadHost:$deadPort", transportHint = "ws"),
        )
        resolver.resolve(listOf(deadCandidate))
        val outcome = resolver.probeOutcomes.value[EndpointResolver.cacheKey(deadCandidate)]
        assertNotNull("dead-port probe must record an outcome", outcome)
        assertTrue(!outcome!!.reachable)
        assertNotNull("failure outcomes must carry a human detail", outcome.detail)
    }

    @Test
    fun probeOutcomes_surviveClearCache() = runTest {
        val resolver = EndpointResolver(fastClient, clock = { clockMillis.get() })
        val lan = candidate("lan", priority = 0, server = reachableServer)
        resolver.resolve(listOf(lan))
        assertNotNull(resolver.probeOutcomes.value[EndpointResolver.cacheKey(lan)])

        resolver.clearCache()

        assertNotNull(
            "clearCache resets probe *caching*, not the UI-facing verdict history",
            resolver.probeOutcomes.value[EndpointResolver.cacheKey(lan)],
        )
    }

    @Test
    fun markUnreachable_recordsOutcome() = runTest {
        val resolver = EndpointResolver(fastClient, clock = { clockMillis.get() })
        val lan = candidate("lan", priority = 0, server = reachableServer)

        resolver.markUnreachable(lan)

        val outcome = resolver.probeOutcomes.value[EndpointResolver.cacheKey(lan)]
        assertNotNull(outcome)
        assertTrue(!outcome!!.reachable)
    }

    @Test
    fun probeSurfaces_recordsIndependentDashboardApiAndRelayOutcomesConcurrently() = runTest {
        val relayServer = MockWebServer().apply { start() }
        val probesStarted = CountDownLatch(3)
        val releaseProbes = CountDownLatch(1)
        fun surfaceDispatcher(statusCode: Int): Dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                probesStarted.countDown()
                releaseProbes.await(5, TimeUnit.SECONDS)
                return MockResponse().setResponseCode(statusCode)
            }
        }
        reachableServer.dispatcher = surfaceDispatcher(statusCode = 200)
        secondReachableServer.dispatcher = surfaceDispatcher(statusCode = 500)
        relayServer.dispatcher = surfaceDispatcher(statusCode = 503)
        val candidate = EndpointCandidate(
            role = "split-surface-test",
            dashboard = DashboardEndpoint(reachableServer.url("/").toString().trimEnd('/')),
            api = ApiEndpoint(secondReachableServer.hostName, secondReachableServer.port, tls = false),
            relay = RelayEndpoint(
                url = "ws://${relayServer.hostName}:${relayServer.port}",
                transportHint = "ws",
            ),
        )
        val resolver = EndpointResolver(fastClient, clock = { clockMillis.get() })

        try {
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                withContext(Dispatchers.Default) { resolver.probeSurfaces(candidate) }
            }
            assertTrue(
                "Dashboard, API, and Relay probes must all start before any completes",
                probesStarted.await(5, TimeUnit.SECONDS),
            )
            releaseProbes.countDown()

            val outcomes = withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(2_000L) { pending.await() }
            }

            assertEquals(
                setOf(EndpointSurface.Dashboard, EndpointSurface.Api, EndpointSurface.Relay),
                outcomes.keys,
            )
            assertTrue(outcomes.getValue(EndpointSurface.Dashboard).reachable)
            assertTrue(!outcomes.getValue(EndpointSurface.Api).reachable)
            assertEquals("HTTP 500 from /health", outcomes.getValue(EndpointSurface.Api).detail)
            assertTrue(!outcomes.getValue(EndpointSurface.Relay).reachable)
            assertEquals("HTTP 503 from /health", outcomes.getValue(EndpointSurface.Relay).detail)

            EndpointSurface.entries
                .filter { it != EndpointSurface.Standard }
                .forEach { surface ->
                    assertEquals(
                        outcomes[surface],
                        resolver.outcomeFor(candidate, surface),
                    )
                }
            assertTrue(
                "surface keys must remain independent on one route",
                EndpointSurface.entries
                    .filter { it != EndpointSurface.Standard }
                    .map { EndpointResolver.outcomeKey(candidate, it) }
                    .toSet()
                    .size == 3,
            )
            assertEquals("/api/health", reachableServer.takeRequest(1, TimeUnit.SECONDS)?.path)
            assertEquals("/health", secondReachableServer.takeRequest(1, TimeUnit.SECONDS)?.path)
            assertEquals("/health", relayServer.takeRequest(1, TimeUnit.SECONDS)?.path)
        } finally {
            releaseProbes.countDown()
            relayServer.shutdown()
        }
    }

    @Test
    fun dashboardOnlyCandidate_probesLightweightHealthWithoutApiOrRelay() = runTest {
        reachableServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/health" -> MockResponse().setResponseCode(200)
                else -> MockResponse().setResponseCode(404)
            }
        }
        val dashboardOnly = EndpointCandidate(
            role = "tailscale",
            dashboard = DashboardEndpoint(reachableServer.url("/").toString().trimEnd('/')),
        )

        val winner = EndpointResolver(fastClient, clock = { clockMillis.get() })
            .resolve(listOf(dashboardOnly))

        assertEquals(dashboardOnly, winner)
        val request = reachableServer.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("GET", request!!.method)
        assertEquals("/api/health", request.path)
        val diagnostic = DiagnosticsLog.recent(setOf(DiagnosticCategory.Endpoint))
            .first { it.operation != null }
        assertEquals("Dashboard or API route health probe", diagnostic.operation)
        assertEquals("http://[host]", diagnostic.configuredUrl)
        assertEquals("http://[host]/api/health", diagnostic.requestUrl)
    }

    @Test
    fun missingDashboardHealthFallsBackToLegacyStatus() = runTest {
        reachableServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/health" -> MockResponse().setResponseCode(404)
                "/api/status" -> MockResponse().setResponseCode(200)
                else -> MockResponse().setResponseCode(500)
            }
        }
        val candidate = EndpointCandidate(
            role = "legacy",
            dashboard = DashboardEndpoint(reachableServer.url("/").toString().trimEnd('/')),
        )

        val winner = EndpointResolver(fastClient, clock = { clockMillis.get() })
            .resolve(listOf(candidate), EndpointSurface.Dashboard)

        assertEquals(candidate, winner)
        assertEquals("/api/health", reachableServer.takeRequest(1, TimeUnit.SECONDS)?.path)
        assertEquals("/api/status", reachableServer.takeRequest(1, TimeUnit.SECONDS)?.path)
    }

    @Test
    fun anonymousLegacyGateShapeFallsBackToStatus() = runTest {
        reachableServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/health" -> MockResponse()
                    .setResponseCode(401)
                    .setBody("""{"error":"unauthenticated","reason":"no_cookie"}""")
                "/api/status" -> MockResponse().setResponseCode(200)
                else -> MockResponse().setResponseCode(500)
            }
        }
        val candidate = EndpointCandidate(
            role = "legacy-gated",
            dashboard = DashboardEndpoint(reachableServer.url("/").toString().trimEnd('/')),
        )

        val winner = EndpointResolver(fastClient, clock = { clockMillis.get() })
            .resolve(listOf(candidate), EndpointSurface.Dashboard)

        assertEquals(candidate, winner)
        assertEquals("/api/health", reachableServer.takeRequest(1, TimeUnit.SECONDS)?.path)
        assertEquals("/api/status", reachableServer.takeRequest(1, TimeUnit.SECONDS)?.path)
    }

    @Test
    fun transientDashboardHealthFailureNeverFallsBackToHeavyStatus() = runTest {
        listOf(429, 503).forEach { status ->
            reachableServer.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/health" -> MockResponse().setResponseCode(status)
                    "/api/status" -> MockResponse().setResponseCode(200)
                    else -> MockResponse().setResponseCode(500)
                }
            }
            val candidate = EndpointCandidate(
                role = "transient-$status",
                dashboard = DashboardEndpoint(reachableServer.url("/").toString().trimEnd('/')),
            )
            val baseline = reachableServer.requestCount

            val winner = EndpointResolver(fastClient, clock = { clockMillis.get() })
                .resolve(listOf(candidate), EndpointSurface.Dashboard)

            assertNull(winner)
            assertEquals(baseline + 1, reachableServer.requestCount)
            assertEquals("/api/health", reachableServer.takeRequest(1, TimeUnit.SECONDS)?.path)
        }
    }

    @Test
    fun dashboardHealthTimeoutNeverFallsBackToHeavyStatus() = runTest {
        val attempts = AtomicInteger(0)
        val timeoutClient = fastClient.newBuilder()
            .addInterceptor {
                attempts.incrementAndGet()
                throw InterruptedIOException("health timed out")
            }
            .build()
        val candidate = EndpointCandidate(
            role = "timeout",
            dashboard = DashboardEndpoint(reachableServer.url("/").toString().trimEnd('/')),
        )

        val winner = EndpointResolver(timeoutClient, clock = { clockMillis.get() })
            .resolve(listOf(candidate), EndpointSurface.Dashboard)

        assertNull(winner)
        assertEquals(1, attempts.get())
        assertEquals(
            "${reachableServer.url("/").toString().trimEnd('/')}/api/health",
            EndpointResolver(timeoutClient).probeRequestUrlForTest(
                candidate,
                EndpointSurface.Dashboard,
            ),
        )
    }

    @Test
    fun ordinaryDashboardAuthFailureNeverFallsBackToPublicStatus() = runTest {
        reachableServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/health" -> MockResponse()
                    .setResponseCode(401)
                    .setBody("""{"detail":"Unauthorized"}""")
                "/api/status" -> MockResponse().setResponseCode(200)
                else -> MockResponse().setResponseCode(500)
            }
        }
        val candidate = EndpointCandidate(
            role = "auth-rejected",
            dashboard = DashboardEndpoint(reachableServer.url("/").toString().trimEnd('/')),
        )

        val winner = EndpointResolver(fastClient, clock = { clockMillis.get() })
            .resolve(listOf(candidate), EndpointSurface.Dashboard)

        assertNull(winner)
        assertEquals(1, reachableServer.requestCount)
        assertEquals("/api/health", reachableServer.takeRequest(1, TimeUnit.SECONDS)?.path)
    }

    @Test
    fun explicitDashboard_isProbeTarget_whenOptionalApiAndRelayArePresent() = runTest {
        reachableServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/health" -> MockResponse().setResponseCode(200)
                "/health" -> MockResponse().setResponseCode(500)
                else -> MockResponse().setResponseCode(404)
            }
        }
        val candidate = EndpointCandidate(
            role = "tailscale",
            dashboard = DashboardEndpoint(reachableServer.url("/").toString().trimEnd('/')),
            api = ApiEndpoint(reachableServer.hostName, reachableServer.port),
            relay = RelayEndpoint("ws://${reachableServer.hostName}:${reachableServer.port}"),
        )

        val winner = EndpointResolver(fastClient, clock = { clockMillis.get() })
            .resolve(listOf(candidate))

        assertEquals(candidate, winner)
        assertEquals("/api/health", reachableServer.takeRequest(1, TimeUnit.SECONDS)?.path)
    }

    @Test
    fun dashboardHealthDoesNotVouchForRelayHealthOnTheSameRoute() = runTest {
        reachableServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/health" -> MockResponse().setResponseCode(200)
                else -> MockResponse().setResponseCode(404)
            }
        }
        secondReachableServer.dispatcher = healthDispatcher(statusCode = 503)
        val candidate = EndpointCandidate(
            role = "lan",
            dashboard = DashboardEndpoint(reachableServer.url("/").toString().trimEnd('/')),
            relay = RelayEndpoint(
                "ws://${secondReachableServer.hostName}:${secondReachableServer.port}",
            ),
        )
        val resolver = EndpointResolver(fastClient, clock = { clockMillis.get() })

        val standardWinner = resolver.resolve(listOf(candidate), EndpointSurface.Standard)
        val relayWinner = resolver.resolve(listOf(candidate), EndpointSurface.Relay)

        assertEquals("the healthy Dashboard keeps the standard route usable", candidate, standardWinner)
        assertNull("a failed Relay /health must not be masked by Dashboard health", relayWinner)
        assertEquals("/api/health", reachableServer.takeRequest(1, TimeUnit.SECONDS)?.path)
        assertEquals("/health", secondReachableServer.takeRequest(1, TimeUnit.SECONDS)?.path)
    }

    @Test
    fun protectedDashboardRelayIngressTreats401And403AsReachableAuthRequired() = runTest {
        listOf(401, 403).forEach { status ->
            reachableServer.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path?.endsWith("/health") == true) {
                        MockResponse().setResponseCode(status)
                    } else {
                        MockResponse().setResponseCode(404)
                    }
            }
            val candidate = EndpointCandidate(
                role = "public-$status",
                relay = RelayEndpoint(
                    "ws://${reachableServer.hostName}:${reachableServer.port}" +
                        "/api/plugins/hermes-relay/transport",
                ),
            )
            val resolver = EndpointResolver(fastClient, clock = { clockMillis.get() })

            val winner = resolver.resolve(listOf(candidate), EndpointSurface.Relay)

            assertEquals(candidate, winner)
            val outcome = resolver.probeOutcomes.value[
                EndpointResolver.outcomeKey(candidate, EndpointSurface.Relay)
            ]
            assertEquals(true, outcome?.reachable)
            assertTrue(outcome?.detail?.contains("authorization required") == true)
            assertEquals(
                "/api/plugins/hermes-relay/transport/health",
                reachableServer.takeRequest(1, TimeUnit.SECONDS)?.path,
            )
        }
    }

    @Test
    fun authChallengeOnDirectRelayDoesNotProveReachability() = runTest {
        reachableServer.dispatcher = healthDispatcher(statusCode = 401)
        val direct = EndpointCandidate(
            role = "direct",
            relay = RelayEndpoint("ws://${reachableServer.hostName}:${reachableServer.port}"),
        )

        val winner = EndpointResolver(fastClient, clock = { clockMillis.get() })
            .resolve(listOf(direct), EndpointSurface.Relay)

        assertNull(winner)
    }

    @Test
    fun protectedDashboardRelayIngress404AndTransportFailureRemainUnreachable() = runTest {
        reachableServer.dispatcher = healthDispatcher(statusCode = 404)
        val missing = EndpointCandidate(
            role = "missing",
            relay = RelayEndpoint(
                "ws://${reachableServer.hostName}:${reachableServer.port}" +
                    "/api/plugins/hermes-relay/transport",
            ),
        )
        val closedServer = MockWebServer().also {
            it.start()
            it.shutdown()
        }
        val offline = EndpointCandidate(
            role = "offline",
            priority = 1,
            relay = RelayEndpoint(
                "ws://${closedServer.hostName}:${closedServer.port}" +
                    "/api/plugins/hermes-relay/transport",
            ),
        )

        val resolver = EndpointResolver(fastClient, clock = { clockMillis.get() })

        assertNull(resolver.resolve(listOf(missing), EndpointSurface.Relay))
        assertNull(resolver.resolve(listOf(offline), EndpointSurface.Relay))
    }

    @Test
    fun secureLinkStandardSurfacesProbeIndependently() {
        val pin = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val candidate = EndpointCandidate(
            role = "plugin_proxy",
            proxy = ProxyEndpoint(
                url = "https://relay.example:9443",
                pinSha256 = pin,
                surfaces = listOf("relay", "api", "dashboard"),
            ),
        )
        val resolver = EndpointResolver(fastClient)

        assertEquals(
            "https://relay.example:9443/dashboard/api/health",
            resolver.probeRequestUrlForTest(candidate, EndpointSurface.Dashboard),
        )
        assertEquals(
            "https://relay.example:9443/api/health",
            resolver.probeRequestUrlForTest(candidate, EndpointSurface.Api),
        )
        assertEquals(
            "https://relay.example:9443/relay/health",
            resolver.probeRequestUrlForTest(candidate, EndpointSurface.Relay),
        )
    }

    @Test
    fun relayProbeUsesCanonicalSiblingHealthRoute() {
        val resolver = EndpointResolver(fastClient)
        val cases = mapOf(
            "wss://relay.example.test" to "https://relay.example.test/health",
            "wss://relay.example.test/ws" to "https://relay.example.test/health",
            "wss://relay.example.test/relay" to "https://relay.example.test/relay/health",
            "wss://relay.example.test/relay/ws/" to "https://relay.example.test/relay/health",
            "https://relay.example.test/custom/health" to "https://relay.example.test/custom/health",
        )

        cases.forEach { (configured, expected) ->
            val candidate = EndpointCandidate(
                role = "custom",
                relay = RelayEndpoint(configured),
            )
            assertEquals(
                configured,
                expected,
                resolver.probeRequestUrlForTest(candidate, EndpointSurface.Relay),
            )
        }
    }

    @Test
    fun secureLinkDoesNotProbeUnadvertisedStandardService() {
        val candidate = EndpointCandidate(
            role = "plugin_proxy",
            proxy = ProxyEndpoint(
                url = "https://relay.example:9443",
                pinSha256 = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                surfaces = listOf("relay"),
            ),
        )
        val resolver = EndpointResolver(fastClient)

        assertNull(resolver.probeRequestUrlForTest(candidate, EndpointSurface.Dashboard))
        assertNull(resolver.probeRequestUrlForTest(candidate, EndpointSurface.Api))
        assertNotNull(resolver.probeRequestUrlForTest(candidate, EndpointSurface.Relay))
    }

    @Test
    fun apiHealthProbe_usesGetWhenServerRejectsHead() = runTest {
        reachableServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path != "/health" -> MockResponse().setResponseCode(404)
                request.method == "HEAD" -> MockResponse().setResponseCode(405)
                request.method == "GET" -> MockResponse().setResponseCode(200)
                else -> MockResponse().setResponseCode(405)
            }
        }
        val apiOnly = EndpointCandidate(
            role = "tailscale",
            api = ApiEndpoint(reachableServer.hostName, reachableServer.port),
        )

        val winner = EndpointResolver(fastClient, clock = { clockMillis.get() })
            .resolve(listOf(apiOnly))

        assertEquals(apiOnly, winner)
        val request = reachableServer.takeRequest(1, TimeUnit.SECONDS)
        assertEquals("GET", request?.method)
        assertEquals("/health", request?.path)
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
