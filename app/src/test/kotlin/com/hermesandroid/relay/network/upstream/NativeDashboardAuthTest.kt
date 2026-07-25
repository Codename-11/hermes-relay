package com.hermesandroid.relay.network.upstream

import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NativeDashboardAuthTest {
    private lateinit var server: MockWebServer
    private lateinit var store: MemoryNativeTokenStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = MemoryNativeTokenStore()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun capabilityGate_requiresAdvertisedNativeFlow() {
        val client = NativeDashboardAuthClient(server.url("/").toString(), store)

        assertFalse(client.supportsNativePkce(DashboardStatus(authRequired = true)))
        assertTrue(
            client.supportsNativePkce(
                DashboardStatus(authRequired = true, authFlows = listOf("cookie", "native_pkce")),
            ),
        )
    }

    @Test
    fun beginAuthorization_usesS256StateAndStrictLoopbackRedirect() {
        val client = NativeDashboardAuthClient(server.url("/prefix").toString(), store)
        val authorization = client.beginAuthorization(
            redirectUri = "http://127.0.0.1:43123/callback",
            provider = "nous",
        )
        val url = java.net.URI(authorization.authorizationUrl)
        val query = url.rawQuery.split("&").associate {
            val pair = it.split("=", limit = 2)
            java.net.URLDecoder.decode(pair[0], "UTF-8") to
                java.net.URLDecoder.decode(pair[1], "UTF-8")
        }

        assertEquals("/prefix/auth/native/authorize", url.path)
        assertEquals("S256", query["code_challenge_method"])
        assertEquals("http://127.0.0.1:43123/callback", query["redirect_uri"])
        assertEquals("nous", query["provider"])
        assertTrue(query.getValue("state").length >= 32)
        assertTrue(query.getValue("code_challenge").length >= 43)
        assertNotEquals(query["state"], query["code_challenge"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun beginAuthorization_rejectsHostnameLoopback() {
        NativeDashboardAuthClient(server.url("/").toString(), store)
            .beginAuthorization("http://localhost:43123/callback")
    }

    @Test
    fun exchangeCallback_validatesStateAndStoresTokens() {
        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"access","refresh_token":"refresh","expires_at":2000,"provider":"nous","user_id":"u"}""",
            ),
        )
        val client = NativeDashboardAuthClient(server.url("/").toString(), store)
        val authorization = client.beginAuthorization("http://127.0.0.1:43123/callback")
        val tokens = client.exchangeCallback(
            authorization,
            "/callback?code=one-time-code&state=${authorization.state}",
        )

        assertEquals("access", tokens.accessToken)
        assertEquals(tokens, store.load())
        val request = server.takeRequest()
        assertEquals("/auth/native/token", request.path)
        val payload = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("one-time-code", payload.getValue("code").jsonPrimitive.content)
        val verifier = payload.getValue("code_verifier").jsonPrimitive.content
        val expectedChallenge = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
            .toByteString()
            .base64Url()
        val authorizeChallenge = java.net.URI(authorization.authorizationUrl).rawQuery
            .split("&")
            .first { it.startsWith("code_challenge=") }
            .substringAfter("=")
            .let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
        assertEquals(expectedChallenge, authorizeChallenge)
    }

    @Test
    fun exchangeCallback_rejectsWrongStateWithoutNetworkOrStorage() {
        val client = NativeDashboardAuthClient(server.url("/").toString(), store)
        val authorization = client.beginAuthorization("http://127.0.0.1:43123/callback")

        val result = runCatching {
            client.exchangeCallback(authorization, "/callback?code=attacker-code&state=wrong")
        }

        assertTrue(result.isFailure)
        assertEquals(0, server.requestCount)
        assertEquals(null, store.load())
    }

    @Test
    fun bearerAuth_refreshesNearExpiryAndAuthenticatesTicketRequest() {
        store.save(
            NativeDashboardTokens(
                accessToken = "old-access",
                refreshToken = "refresh",
                expiresAt = 1005,
                provider = "nous",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"new-access","refresh_token":"new-refresh","expires_at":3000,"provider":"nous","user_id":"u"}""",
            ),
        )
        server.enqueue(MockResponse().setBody("""{"ticket":"ticket","ttl_seconds":30}"""))
        val client = DashboardApiClient(
            server.url("/").toString(),
            DashboardApiClient.defaultClient(
                bearerAuth = DashboardBearerAuth(
                    server.url("/").toString(),
                    store,
                    clockSeconds = { 1000 },
                ),
            ),
        )

        val result = kotlinx.coroutines.runBlocking { client.requestWsTicket().getOrThrow() }

        assertEquals("ticket", result.ticket)
        val refresh = server.takeRequest()
        assertEquals("/auth/native/refresh", refresh.path)
        assertFalse(refresh.headers.names().contains("Authorization"))
        val ticket = server.takeRequest()
        assertEquals("Bearer new-access", ticket.getHeader("Authorization"))
        assertEquals("new-refresh", store.load()?.refreshToken)
    }

    @Test
    fun hostileSetupOrigin_neverReceivesActiveConnectionBearer() {
        store.save(
            NativeDashboardTokens(
                accessToken = "must-not-leak",
                refreshToken = "refresh",
                expiresAt = 3000,
            ),
        )
        server.enqueue(MockResponse().setBody("""{"auth_required":false}"""))
        val hostileUrl = server.url("/attacker").toString()
        val bearer = trustedDashboardBearerAuthOrNull(
            candidate = hostileUrl,
            trusted = "https://trusted.example/hermes",
            tokenStoreProvider = { store },
        )
        val client = DashboardApiClient(
            hostileUrl,
            DashboardApiClient.defaultClient(bearerAuth = bearer),
        )

        kotlinx.coroutines.runBlocking { client.getStatus().getOrThrow() }

        assertEquals(null, bearer)
        assertEquals(null, server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun concurrentClients_rotateSingleUseRefreshTokenExactlyOnce() {
        val shared = AtomicReference<NativeDashboardTokens?>(
            NativeDashboardTokens(
                accessToken = "old-access",
                refreshToken = "single-use-refresh",
                expiresAt = 1005,
                provider = "nous",
            ),
        )
        val refreshCalls = AtomicInteger()
        val ticketCalls = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/auth/native/refresh" -> {
                    refreshCalls.incrementAndGet()
                    MockResponse().setBody(
                        """{"access_token":"new-access","refresh_token":"rotated-refresh","expires_at":3000,"provider":"nous","user_id":"u"}""",
                    )
                }
                "/api/auth/ws-ticket" -> {
                    ticketCalls.incrementAndGet()
                    if (request.getHeader("Authorization") == "Bearer new-access") {
                        MockResponse().setBody("""{"ticket":"ticket","ttl_seconds":30}""")
                    } else {
                        MockResponse().setResponseCode(401)
                    }
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val storeA = SharedMemoryNativeTokenStore("connection-a", shared)
        val storeB = SharedMemoryNativeTokenStore("connection-a", shared)
        val clientA = dashboardClientWithBearer(storeA)
        val clientB = dashboardClientWithBearer(storeB)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())

        listOf(clientA, clientB).forEach { client ->
            Thread {
                try {
                    start.await()
                    kotlinx.coroutines.runBlocking { client.requestWsTicket().getOrThrow() }
                } catch (error: Throwable) {
                    failures += error
                } finally {
                    done.countDown()
                }
            }.start()
        }
        start.countDown()

        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(failures.toString(), failures.isEmpty())
        assertEquals(1, refreshCalls.get())
        assertEquals(2, ticketCalls.get())
        assertEquals("rotated-refresh", shared.get()?.refreshToken)
    }

    private fun dashboardClientWithBearer(store: NativeDashboardTokenStore): DashboardApiClient =
        DashboardApiClient(
            server.url("/").toString(),
            DashboardApiClient.defaultClient(
                bearerAuth = DashboardBearerAuth(
                    server.url("/").toString(),
                    store,
                    clockSeconds = { 1000 },
                ),
            ),
        )
}

private class MemoryNativeTokenStore : NativeDashboardTokenStore {
    override val coordinationKey: String = "memory-${System.identityHashCode(this)}"
    private var tokens: NativeDashboardTokens? = null
    override fun load(): NativeDashboardTokens? = tokens
    override fun save(tokens: NativeDashboardTokens) {
        this.tokens = tokens
    }
    override fun clear() {
        tokens = null
    }
}

private class SharedMemoryNativeTokenStore(
    override val coordinationKey: String,
    private val shared: AtomicReference<NativeDashboardTokens?>,
) : NativeDashboardTokenStore {
    override fun load(): NativeDashboardTokens? = shared.get()
    override fun save(tokens: NativeDashboardTokens) {
        shared.set(tokens)
    }
    override fun clear() {
        shared.set(null)
    }
}
