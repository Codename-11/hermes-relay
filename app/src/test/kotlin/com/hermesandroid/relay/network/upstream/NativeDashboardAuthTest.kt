package com.hermesandroid.relay.network.upstream

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLHandshakeException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
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
        assertEquals(null, query["provider"])
        assertTrue(query.getValue("state").length >= 32)
        assertEquals(43, query.getValue("code_challenge").length)
        assertFalse(query.getValue("code_challenge").contains('='))
        assertNotEquals(query["state"], query["code_challenge"])
    }

    @Test
    fun beginAuthorization_keepsExplicitSelectorForNonNousProviders() {
        val client = NativeDashboardAuthClient(server.url("/").toString(), store)

        val authorization = client.beginAuthorization(
            redirectUri = "http://127.0.0.1:43123/callback",
            provider = "oidc",
        )

        val query = java.net.URI(authorization.authorizationUrl).rawQuery
            .split("&")
            .associate {
                val pair = it.split("=", limit = 2)
                java.net.URLDecoder.decode(pair[0], "UTF-8") to
                    java.net.URLDecoder.decode(pair[1], "UTF-8")
            }
        assertEquals("oidc", query["provider"])
    }

    @Test
    fun canonicalNousCallbackBase_usesSecurePublicOriginAndPreservesPrefix() {
        val location = "https://portal.nousresearch.com/oauth/authorize" +
            "?redirect_uri=https%3A%2F%2Fhermes.example.test%2Fgateway%2Fauth%2Fcallback"

        assertEquals(
            "https://hermes.example.test/gateway",
            canonicalDashboardBaseFromNousRedirect(location),
        )
        assertEquals(
            null,
            canonicalDashboardBaseFromNousRedirect(
                "https://portal.nousresearch.com/oauth/authorize" +
                    "?redirect_uri=http%3A%2F%2Fhermes.example.test%2Fauth%2Fcallback",
            ),
        )
        assertEquals(
            null,
            canonicalDashboardBaseFromNousRedirect(
                "https://attacker.example/oauth/authorize" +
                    "?redirect_uri=https%3A%2F%2Fhermes.example.test%2Fauth%2Fcallback",
            ),
        )
    }

    @Test
    fun canonicalProviderCallback_supportsSelfHostedPublicAndPrivateOriginsSafely() {
        val selfHostedPublic = "https://id.example.test/authorize" +
            "?redirect_uri=https%3A%2F%2Fhermes.example.test%2Fauth%2Fcallback"
        val privateOverlay = "https://id.example.test/authorize" +
            "?redirect_uri=http%3A%2F%2F100.71.8.99%3A9119%2Fauth%2Fcallback"
        val publicCleartext = "https://id.example.test/authorize" +
            "?redirect_uri=http%3A%2F%2Fpublic.example.test%2Fauth%2Fcallback"

        assertEquals(
            "https://hermes.example.test",
            canonicalDashboardBaseFromProviderRedirect(
                "http://192.168.1.20:9119",
                selfHostedPublic,
            ),
        )
        assertEquals(
            "http://100.71.8.99:9119",
            canonicalDashboardBaseFromProviderRedirect(
                "http://192.168.1.20:9119",
                privateOverlay,
            ),
        )
        assertEquals(
            "http://192.168.1.20:9119",
            canonicalDashboardBaseFromProviderRedirect(
                "http://192.168.1.20:9119",
                publicCleartext,
            ),
        )
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
            .trimEnd('=')
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
    fun exchangeCallback_doesNotRestoreTokensAfterSessionClear() {
        val responseStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                responseStarted.countDown()
                check(releaseResponse.await(5, TimeUnit.SECONDS))
                return MockResponse().setBody(
                    """{"access_token":"late","refresh_token":"late-refresh","expires_at":3000,"provider":"nous","user_id":"u"}""",
                )
            }
        }
        val client = NativeDashboardAuthClient(server.url("/").toString(), store)
        val authorization = client.beginAuthorization("http://127.0.0.1:43123/callback")
        val failure = AtomicReference<Throwable?>()
        val exchange = Thread {
            runCatching {
                client.exchangeCallback(
                    authorization,
                    "/callback?code=late-code&state=${authorization.state}",
                )
            }.exceptionOrNull()?.let(failure::set)
        }.apply { start() }

        assertTrue(responseStarted.await(5, TimeUnit.SECONDS))
        client.clearStoredSession()
        releaseResponse.countDown()
        exchange.join(5_000)

        assertFalse(exchange.isAlive)
        assertTrue(failure.get() is java.io.IOException)
        assertEquals(null, store.load())
    }

    @Test
    fun exchangeCallback_doesNotCommitAfterAttemptCancellation() {
        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"cancelled","refresh_token":"refresh","expires_at":3000,"provider":"nous","user_id":"u"}""",
            ),
        )
        val client = NativeDashboardAuthClient(server.url("/").toString(), store)
        val authorization = client.beginAuthorization("http://127.0.0.1:43123/callback")

        val result = runCatching {
            client.exchangeCallback(
                authorization,
                "/callback?code=code&state=${authorization.state}",
                commitAllowed = { false },
            )
        }

        assertTrue(result.isFailure)
        assertEquals(null, store.load())
    }

    @Test
    fun trustedBearerPolicy_rejectsCleartextDashboardRoute() {
        store.save(
            NativeDashboardTokens(
                accessToken = "must-not-leak",
                refreshToken = "must-not-refresh",
                expiresAt = 1,
                provider = "nous",
            ),
        )

        val bearer = trustedDashboardBearerAuthOrNull(
            candidate = "http://hermes.local:9119",
            trusted = "http://hermes.local:9119",
            tokenStoreProvider = { store },
        )

        assertEquals(null, bearer)
        assertEquals(0, server.requestCount)
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
    fun bearerAuth_refreshesOnceWhenTicketMintMasksExpiryAsProviderUnavailable() {
        store.save(
            NativeDashboardTokens(
                accessToken = "expired-access",
                refreshToken = "current-refresh",
                expiresAt = 3000,
                provider = "self-hosted",
            ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setBody("""{"detail":"Auth provider 'nous' unreachable"}"""),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"new-access","refresh_token":"new-refresh","expires_at":4000,"provider":"self-hosted","user_id":"u"}""",
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
        val failedTicket = server.takeRequest()
        assertEquals("/api/auth/ws-ticket", failedTicket.path)
        assertEquals("Bearer expired-access", failedTicket.getHeader("Authorization"))
        val refresh = server.takeRequest()
        assertEquals("/auth/native/refresh", refresh.path)
        val recoveredTicket = server.takeRequest()
        assertEquals("/api/auth/ws-ticket", recoveredTicket.path)
        assertEquals("Bearer new-access", recoveredTicket.getHeader("Authorization"))
        assertEquals("new-access", store.load()?.accessToken)
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

    @Test
    fun nativeSignInFailureStagesAreSecretFreeAndActionable() {
        assertEquals(
            "callback_error",
            nativeDashboardSignInFailureStage(NativeDashboardCallbackException("rejected")),
        )
        assertEquals(
            "token_http_400",
            nativeDashboardSignInFailureStage(NativeDashboardAuthHttpException(400)),
        )
        assertEquals(
            "token_shape",
            nativeDashboardSignInFailureStage(
                NativeDashboardTokenShapeException("Dashboard token response was malformed"),
            ),
        )
        assertEquals(
            "inactive_generation",
            nativeDashboardSignInFailureStage(NativeDashboardInactiveAuthorizationException()),
        )
        assertEquals(
            "token_transport",
            nativeDashboardSignInFailureStage(IOException("connection reset")),
        )
        assertEquals(
            "token_store",
            nativeDashboardSignInFailureStage(IllegalStateException("keystore unavailable")),
        )

        val secret = "code=secret-code&state=secret-state&access_token=secret-token"
        val diagnostic = nativeDashboardSignInFailureDiagnostic(IOException(secret))
        assertEquals("dashboard_native_pkce_failed stage=token_transport", diagnostic)
        assertFalse(diagnostic.contains("secret-code"))
        assertFalse(diagnostic.contains("secret-state"))
        assertFalse(diagnostic.contains("secret-token"))

        val classified = listOf(
            InterruptedIOException(secret) to "token_transport_timeout",
            UnknownHostException(secret) to "token_transport_dns",
            ConnectException(secret) to "token_transport_connect",
            SSLHandshakeException(secret) to "token_transport_tls",
            SocketException(secret) to "token_transport_socket",
        )
        classified.forEach { (cause, expectedStage) ->
            val wrapped = IOException(secret, cause)
            val safeDiagnostic = nativeDashboardSignInFailureDiagnostic(wrapped)
            assertEquals("dashboard_native_pkce_failed stage=$expectedStage", safeDiagnostic)
            assertFalse(safeDiagnostic.contains("secret-code"))
            assertFalse(safeDiagnostic.contains("secret-state"))
            assertFalse(safeDiagnostic.contains("secret-token"))
        }
    }

    @Test
    fun exchangeCallback_dnsFailureThenSuccess_sendsTokenPostOnce() {
        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"access","refresh_token":"refresh","expires_at":2000,"provider":"nous","user_id":"u"}""",
            ),
        )
        val calls = AtomicInteger(0)
        val backoffs = mutableListOf<Long>()
        val delegate = Dns { _ ->
            if (calls.incrementAndGet() == 1) {
                throw UnknownHostException("secret-first-lookup")
            }
            listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        }
        val httpClient = OkHttpClient.Builder()
            .dns(
                RetryingNativeAuthDns(
                    delegate = delegate,
                    backoffMillis = 7L,
                    sleeper = backoffs::add,
                ),
            )
            .retryOnConnectionFailure(false)
            .build()
        val baseUrl = server.url("/").newBuilder().host("native-auth.test").build().toString()
        val client = NativeDashboardAuthClient(baseUrl, store, client = httpClient)
        val authorization = client.beginAuthorization("http://127.0.0.1:43123/callback")

        val tokens = client.exchangeCallback(
            authorization,
            "/callback?code=one-time-code&state=${authorization.state}",
        )

        assertEquals("access", tokens.accessToken)
        assertEquals(2, calls.get())
        assertEquals(listOf(7L), backoffs)
        assertEquals(1, server.requestCount)
        assertEquals("/auth/native/token", server.takeRequest().path)
    }

    @Test
    fun exchangeCallback_dnsRetryExhausted_reportsTypedDnsStage_withoutHttpRequest() {
        val secret = "secret-host-detail"
        val calls = AtomicInteger(0)
        val delegate = Dns { _ ->
            calls.incrementAndGet()
            throw UnknownHostException(secret)
        }
        val httpClient = OkHttpClient.Builder()
            .dns(RetryingNativeAuthDns(delegate, backoffMillis = 0L))
            .retryOnConnectionFailure(false)
            .build()
        val client = NativeDashboardAuthClient(
            "https://native-auth.invalid",
            store,
            client = httpClient,
        )
        val authorization = client.beginAuthorization("http://127.0.0.1:43123/callback")

        val failure = runCatching {
            client.exchangeCallback(
                authorization,
                "/callback?code=one-time-code&state=${authorization.state}",
            )
        }.exceptionOrNull()

        assertEquals(2, calls.get())
        assertEquals(0, server.requestCount)
        assertEquals("token_transport_dns", nativeDashboardSignInFailureStage(failure!!))
        assertFalse(nativeDashboardSignInFailureDiagnostic(failure).contains(secret))
    }

    @Test
    fun retryingNativeAuthDns_nonDnsFailure_isNotRetriedOrChanged() {
        val expected = IllegalStateException("non-dns-secret")
        val calls = AtomicInteger(0)
        val backoffs = mutableListOf<Long>()
        val dns = RetryingNativeAuthDns(
            delegate = Dns { _ ->
                calls.incrementAndGet()
                throw expected
            },
            backoffMillis = 7L,
            sleeper = backoffs::add,
        )

        val actual = runCatching { dns.lookup("native-auth.test") }.exceptionOrNull()

        assertSame(expected, actual)
        assertEquals(1, calls.get())
        assertTrue(backoffs.isEmpty())
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
