package com.hermesandroid.relay.network.upstream

import com.hermesandroid.relay.BuildConfig
import com.hermesandroid.relay.ui.screens.nativeDashboardAuthDiagnosticDetail
import com.hermesandroid.relay.ui.screens.nativeDashboardDiagnosticProviderKind
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.util.Collections
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NativeDashboardSignInCoordinatorTest {
    private lateinit var server: MockWebServer
    private lateinit var store: CoordinatorTokenStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = CoordinatorTokenStore()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun signIn_tricklingRequestCannotExtendAttemptDeadline() = runBlocking {
        val coordinator = NativeDashboardSignInCoordinator(
            NativeDashboardAuthClient(server.url("/").toString(), store),
            timeoutMillis = 350,
        )
        val launched = CompletableDeferred<String>()
        val result = async(Dispatchers.IO) {
            runCatching { coordinator.signIn("basic") { launched.complete(it) } }
        }
        val redirect = URI(query(URI(launched.await())).getValue("redirect_uri"))
        Socket("127.0.0.1", redirect.port).use { socket ->
            val trickle = async(Dispatchers.IO) {
                runCatching {
                    while (true) {
                        socket.getOutputStream().write('G'.code)
                        delay(30)
                    }
                }
            }
            try {
                val outcome = withTimeoutOrNull(1_500) { result.await() }
                assertTrue("An incomplete socket must not defeat the attempt deadline", outcome != null)
                assertTrue(outcome?.exceptionOrNull() is NativeDashboardSignInTimeoutException)
            } finally {
                socket.close()
                trickle.cancelAndJoin()
                result.cancelAndJoin()
            }
        }
    }

    @Test
    fun signIn_idlePreconnectionDoesNotBlockFragmentedCallback() = runBlocking {
        server.enqueue(tokenResponse())
        val stages = Collections.synchronizedList(mutableListOf<String>())
        val accepted = CompletableDeferred<Unit>()
        val launched = CompletableDeferred<String>()
        val coordinator = NativeDashboardSignInCoordinator(
            NativeDashboardAuthClient(server.url("/").toString(), store),
            providerDisplayName = "Username & Password <provider>",
        )
        val result = async(Dispatchers.IO) {
            coordinator.signIn("basic", onDiagnostic = {
                stages.add(it)
                if (it == "socket_accepted") accepted.complete(Unit)
            }) { launched.complete(it) }
        }
        val auth = query(URI(launched.await()))
        val redirect = URI(auth.getValue("redirect_uri"))
        Socket("127.0.0.1", redirect.port).use { idle ->
            withTimeout(2_000) { accepted.await() }
            val response = withContext(Dispatchers.IO) {
                Socket("127.0.0.1", redirect.port).use { socket ->
                    socket.soTimeout = 2_000
                    val request = "GET /callback?code=code-1&state=${auth.getValue("state")} HTTP/1.1\r\nHost: 127.0.0.1:${redirect.port}\r\n\r\n"
                    for (byte in request.toByteArray()) {
                        socket.getOutputStream().write(byte.toInt())
                        socket.getOutputStream().flush()
                        delay(1)
                    }
                    socket.getInputStream().bufferedReader().readText()
                }
            }
            assertTrue(response.contains("Sign-in complete"))
            assertTrue(response.contains("Username &amp; Password &lt;provider&gt;"))
            assertFalse(response.contains("<provider>"))
            assertEquals("access-1", withTimeout(2_000) { result.await() }.accessToken)
            idle.soTimeout = 1_000
            assertEquals(-1, idle.getInputStream().read())
        }
        assertTrue(stages.contains("request_read_completed"))
        assertFalse(stages.any { it.contains(auth.getValue("state")) || it.contains("code-1") })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun signIn_cancellationClosesReadingSocketAndRetrySucceeds() = runBlocking {
        val coordinator = NativeDashboardSignInCoordinator(NativeDashboardAuthClient(server.url("/").toString(), store))
        val launched = CompletableDeferred<String>()
        val accepted = CompletableDeferred<Unit>()
        val result = async(Dispatchers.IO) {
            coordinator.signIn("basic", onDiagnostic = {
                if (it == "socket_accepted") accepted.complete(Unit)
            }) { launched.complete(it) }
        }
        val redirect = URI(query(URI(launched.await())).getValue("redirect_uri"))
        Socket("127.0.0.1", redirect.port).use { socket ->
            socket.getOutputStream().write("GET /call".toByteArray())
            withTimeout(2_000) { accepted.await() }
            withTimeout(1_500) { result.cancelAndJoin() }
            socket.soTimeout = 1_000
            // Some TCP stacks reset a socket closed with unread request data.
            val closed = runCatching { socket.getInputStream().read() }.getOrDefault(-1)
            assertEquals(-1, closed)
        }
        assertThrows(Exception::class.java) { Socket("127.0.0.1", redirect.port).close() }
        assertEquals(null, store.tokens)
        server.enqueue(tokenResponse())
        assertEquals("access-1", completeSignIn(coordinator, "basic").accessToken)
    }

    @Test
    fun signIn_requestDeadlineExpiresEvenWithIncomingBytes() = runBlocking {
        val launched = CompletableDeferred<String>()
        val expired = CompletableDeferred<Unit>()
        val coordinator = NativeDashboardSignInCoordinator(NativeDashboardAuthClient(server.url("/").toString(), store))
        val result = async(Dispatchers.IO) {
            coordinator.signIn("basic", onDiagnostic = {
                if (it == "request_read_timeout") expired.complete(Unit)
            }) { launched.complete(it) }
        }
        val redirect = URI(query(URI(launched.await())).getValue("redirect_uri"))
        Socket("127.0.0.1", redirect.port).use { socket ->
            val trickle = async(Dispatchers.IO) {
                runCatching { while (true) { socket.getOutputStream().write('G'.code); delay(30) } }
            }
            try {
                withTimeout(7_000) { expired.await() }
                assertFalse(result.isCompleted)
            } finally {
                socket.close()
                trickle.cancelAndJoin()
                result.cancelAndJoin()
            }
        }
    }

    @Test
    fun signIn_cancelDuringTokenExchangeDoesNotSaveLateCredentials() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        val launched = CompletableDeferred<String>()
        val coordinator = NativeDashboardSignInCoordinator(NativeDashboardAuthClient(server.url("/").toString(), store))
        val result = async(Dispatchers.IO) { coordinator.signIn("basic") { launched.complete(it) } }
        val auth = query(URI(launched.await()))
        val redirect = URI(auth.getValue("redirect_uri"))
        val browser = async(Dispatchers.IO) {
            runCatching { sendCallbackResponse(redirect, "/callback?code=code-1&state=${auth.getValue("state")}") }
        }
        withContext(Dispatchers.IO) { assertTrue(server.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS) != null) }
        withTimeout(1_500) { result.cancelAndJoin() }
        withTimeout(1_500) { browser.await() }
        assertEquals(null, store.tokens)
    }

    @Test
    fun signIn_bindFailureDoesNotLaunchBrowserOrChangeExistingSession() = runBlocking {
        val previous = NativeDashboardTokens("previous")
        store.tokens = previous
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { bound ->
            val coordinator = NativeDashboardSignInCoordinator(
                NativeDashboardAuthClient(server.url("/").toString(), store),
                serverSocketFactory = { bound },
            )
            val error = runCatching { coordinator.signIn("basic") { error("must not launch") } }.exceptionOrNull()
            assertTrue(error is java.net.SocketException)
            assertTrue(bound.isClosed)
            assertEquals(previous, store.tokens)
        }
    }

    @Test
    fun signIn_bindsBeforeLaunch_forwardsProviderAndExchangesValidCallback() = runBlocking {
        server.enqueue(tokenResponse())
        val authClient = NativeDashboardAuthClient(server.url("/").toString(), store)
        val coordinator = NativeDashboardSignInCoordinator(authClient)

        val tokens = completeSignIn(
            coordinator,
            provider = "google",
            onAuthorizationPrepared = { error("diagnostic observer failure") },
        )

        assertEquals("access-1", tokens.accessToken)
        assertEquals(tokens, store.tokens)
        val authorizeRequest = server.takeRequest()
        assertEquals("/auth/native/token", authorizeRequest.path)
        assertTrue(authorizeRequest.body.readUtf8().contains("\"code\":\"code-1\""))
    }

    @Test
    fun signIn_ignoresWrongStateThenAcceptsValidCallback() = runBlocking {
        server.enqueue(tokenResponse())
        val coordinator = NativeDashboardSignInCoordinator(
            NativeDashboardAuthClient(server.url("/").toString(), store),
        )
        val validatedCallbacks = AtomicInteger()

        coroutineScope {
            val authorizationUrl = CompletableDeferred<String>()
            val result = async {
                coordinator.signIn(
                    provider = "github",
                    onCallbackValidated = { validatedCallbacks.incrementAndGet() },
                ) { authorizationUrl.complete(it) }
            }
            val authorize = URI(authorizationUrl.await())
            val redirect = URI(query(authorize)["redirect_uri"]!!)
            val state = query(authorize)["state"]!!

            val rejected = sendCallbackResponse(
                redirect,
                "/callback?code=attacker-secret&state=wrong-secret",
            )
            assertTrue(rejected.startsWith("HTTP/1.1 400"))
            assertTrue(rejected.contains("Callback not accepted"))
            assertTrue(rejected.contains("Content-Security-Policy:"))
            assertTrue(rejected.contains("Cache-Control: no-store"))
            assertReturnLinkIsFixedAndPrivate(rejected)
            assertFalse(rejected.contains("attacker-secret"))
            assertFalse(rejected.contains("wrong-secret"))
            assertFalse(result.isCompleted)
            assertEquals(0, validatedCallbacks.get())

            val accepted = sendCallbackResponse(
                redirect,
                "/callback?code=code-1&state=$state",
            )
            assertTrue(accepted.startsWith("HTTP/1.1 200"))
            assertTrue(accepted.contains("Content-Type: text/html; charset=utf-8"))
            assertTrue(accepted.contains("Sign-in complete"))
            assertReturnLinkIsFixedAndPrivate(accepted)
            assertTrue(accepted.contains("prefers-reduced-motion:reduce"))
            assertTrue(accepted.contains("frame-ancestors 'none'"))
            assertTrue(accepted.contains("style-src 'sha256-"))
            assertTrue(accepted.contains("script-src 'none'"))
            assertFalse(accepted.contains("'unsafe-inline'"))
            assertFalse(accepted.contains("window.close"))
            assertFalse(accepted.contains("code-1"))
            assertFalse(accepted.contains(state))
            assertEquals("access-1", result.await().accessToken)
            assertEquals(1, validatedCallbacks.get())
        }
    }

    @Test
    fun signIn_tokenExchangeFailure_returnsPrivateActionablePage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("sensitive-upstream-detail"))
        val coordinator = NativeDashboardSignInCoordinator(
            NativeDashboardAuthClient(server.url("/").toString(), store),
            providerDisplayName = "Self-Hosted OIDC",
        )

        coroutineScope {
            val authorizationUrl = CompletableDeferred<String>()
            val result = async {
                runCatching {
                    coordinator.signIn("nous") { authorizationUrl.complete(it) }
                }
            }
            val authorize = URI(authorizationUrl.await())
            val query = query(authorize)
            val redirect = URI(query.getValue("redirect_uri"))
            val response = sendCallbackResponse(
                redirect,
                "/callback?code=one-time-secret&state=${query.getValue("state")}",
            )

            assertTrue(response.startsWith("HTTP/1.1 400"))
            assertTrue(response.contains("Hermes rejected the sign-in code"))
            assertTrue(response.contains("Self-Hosted OIDC"))
            assertTrue(response.contains("Hermes could not exchange its one-time callback code"))
            assertFalse(response.contains("Google"))
            assertTrue(response.contains("Referrer-Policy: no-referrer"))
            assertTrue(response.contains("X-Content-Type-Options: nosniff"))
            assertTrue(response.contains("Permissions-Policy:"))
            assertReturnLinkIsFixedAndPrivate(response)
            assertFalse(response.contains("one-time-secret"))
            assertFalse(response.contains(query.getValue("state")))
            assertFalse(response.contains("sensitive-upstream-detail"))
            assertTrue(result.await().exceptionOrNull() is NativeDashboardAuthHttpException)
        }
    }

    @Test
    fun signIn_timeoutClosesEphemeralListener() = runBlocking {
        val coordinator = NativeDashboardSignInCoordinator(
            authClient = NativeDashboardAuthClient(server.url("/").toString(), store),
            timeoutMillis = 100,
        )
        val authorizationUrl = CompletableDeferred<String>()

        assertThrows(java.io.IOException::class.java) {
            runBlocking {
                coordinator.signIn("google") { authorizationUrl.complete(it) }
            }
        }
        val redirect = URI(query(URI(authorizationUrl.await()))["redirect_uri"]!!)
        assertThrows(Exception::class.java) {
            Socket("127.0.0.1", redirect.port).use { }
        }
        Unit
    }

    @Test
    fun redirectMode_requiresExactCapability_andNativeTransportRequiresHttps() {
        assertEquals(
            DashboardRedirectAuthMode.NativePkce,
            dashboardRedirectAuthMode(listOf("cookie", "native_pkce")),
        )
        assertEquals(
            DashboardRedirectAuthMode.WebView,
            dashboardRedirectAuthMode(listOf("cookie", "NATIVE_PKCE")),
        )
        assertTrue(isNativeDashboardTransportEligible("https://hermes.example.test/prefix"))
        assertTrue(isNativeDashboardTransportEligible("http://127.0.0.1:9119"))
        assertTrue(isNativeDashboardTransportEligible("http://172.16.24.250:9119"))
        assertTrue(isNativeDashboardTransportEligible("http://100.71.8.56:9119"))
        assertFalse(isNativeDashboardTransportEligible("http://hermes.local:9119"))
        assertFalse(isNativeDashboardTransportEligible("http://203.0.113.10:9119"))
    }

    @Test
    fun androidRedirectMode_usesAdvertisedCapability_forEveryProviderName() {
        val flows = listOf("cookie", "native_pkce")

        listOf("nous", "self-hosted", "oidc", "google", "basic").forEach { provider ->
            assertEquals(
                provider,
                DashboardRedirectAuthMode.NativePkce,
                androidDashboardRedirectAuthMode(provider, flows),
            )
            assertEquals(
                provider,
                DashboardRedirectAuthMode.WebView,
                androidDashboardRedirectAuthMode(provider, listOf("cookie")),
            )
        }
        assertEquals(
            DashboardRedirectAuthMode.NativePkce,
            androidDashboardRedirectAuthMode(
                providerName = "self-hosted",
                authFlows = flows,
                competingRedirectProviders = 2,
            ),
        )
        assertEquals(
            DashboardRedirectAuthMode.NativePkce,
            androidDashboardRedirectAuthMode(
                providerName = "nous",
                authFlows = flows,
                competingRedirectProviders = 2,
            ),
        )
        assertEquals(
            DashboardRedirectAuthMode.NativePkce,
            androidDashboardRedirectAuthMode(
                providerName = "basic",
                authFlows = flows,
                competingRedirectProviders = 1,
            ),
        )
        assertEquals(null, nativeDashboardAuthorizationProvider("nous", 1))
        assertEquals("nous", nativeDashboardAuthorizationProvider("nous", 2))
        assertEquals("self-hosted", nativeDashboardAuthorizationProvider("self-hosted", 1))
    }

    @Test
    fun authDiagnosticsUseSafeProviderClassesAndBoundedAttemptMetadata() {
        assertEquals("nous", nativeDashboardDiagnosticProviderKind("Nous"))
        assertEquals("self_hosted_oidc", nativeDashboardDiagnosticProviderKind("self-hosted"))
        assertEquals("password", nativeDashboardDiagnosticProviderKind("basic"))
        assertEquals("custom", nativeDashboardDiagnosticProviderKind("private-provider-name"))
        assertEquals("gateway_selected", nativeDashboardDiagnosticProviderKind(null))
        assertEquals(
            "stage=failed attempt=2 provider=nous failure=token_http_503 " +
                "authorization_origin=alternate",
            nativeDashboardAuthDiagnosticDetail(
                stage = "failed",
                attempt = 2,
                providerKind = "nous",
                failureStage = "token_http_503",
                authorizationOrigin = "alternate",
            ),
        )
    }

    @Test
    fun returnUri_usesTheExactFlavorApplicationId_withoutAuthMaterial() {
        val returnUri = URI(NATIVE_SIGN_IN_RETURN_URI)

        assertEquals(BuildConfig.APPLICATION_ID, returnUri.scheme)
        assertEquals("return", returnUri.host)
        assertEquals("", returnUri.path)
        assertEquals(null, returnUri.userInfo)
        assertEquals(null, returnUri.query)
        assertEquals(null, returnUri.fragment)
    }

    private suspend fun completeSignIn(
        coordinator: NativeDashboardSignInCoordinator,
        provider: String,
        onAuthorizationPrepared: (Boolean) -> Unit = {},
    ): NativeDashboardTokens = coroutineScope {
        val authorizationUrl = CompletableDeferred<String>()
        val result = async {
            coordinator.signIn(
                provider = provider,
                onAuthorizationPrepared = onAuthorizationPrepared,
            ) { authorizationUrl.complete(it) }
        }
        val authorize = URI(authorizationUrl.await())
        val authorizeQuery = query(authorize)
        if (provider.equals("nous", ignoreCase = true)) {
            assertEquals(null, authorizeQuery["provider"])
        } else {
            assertEquals(provider, authorizeQuery["provider"])
        }
        assertEquals("S256", authorizeQuery["code_challenge_method"])
        val redirect = URI(authorizeQuery["redirect_uri"]!!)
        assertEquals("127.0.0.1", redirect.host)
        assertTrue(redirect.port > 0)
        val response = sendCallbackResponse(
            redirect,
            "/callback?code=code-1&state=${authorizeQuery["state"]}",
        )
        assertTrue(response.startsWith("HTTP/1.1 200"))
        result.await()
    }

    private fun sendCallbackResponse(redirect: URI, target: String): String =
        Socket("127.0.0.1", redirect.port).use { socket ->
            socket.getOutputStream().write(
                "GET $target HTTP/1.1\r\nHost: 127.0.0.1:${redirect.port}\r\n\r\n"
                    .toByteArray(StandardCharsets.US_ASCII),
            )
            socket.getOutputStream().flush()
            BufferedReader(InputStreamReader(socket.getInputStream())).readText()
        }

    private fun assertReturnLinkIsFixedAndPrivate(response: String) {
        assertTrue(
            response.contains(
                "<a class=\"return-link\" href=\"$NATIVE_SIGN_IN_RETURN_URI\">" +
                    "Return to Hermes Relay</a>",
            ),
        )
        assertFalse(NATIVE_SIGN_IN_RETURN_URI.contains('?'))
        assertFalse(NATIVE_SIGN_IN_RETURN_URI.contains('#'))
        assertFalse(NATIVE_SIGN_IN_RETURN_URI.contains('@'))
    }

    private fun query(uri: URI): Map<String, String> =
        uri.rawQuery.orEmpty()
            .split('&')
            .filter(String::isNotBlank)
            .associate { part ->
                val pieces = part.split('=', limit = 2)
                URLDecoder.decode(pieces[0], StandardCharsets.UTF_8) to
                    URLDecoder.decode(pieces.getOrElse(1) { "" }, StandardCharsets.UTF_8)
            }

    private fun tokenResponse(): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """
                {
                  "access_token": "access-1",
                  "refresh_token": "refresh-1",
                  "expires_at": 4102444800,
                  "provider": "google",
                  "user_id": "user-1"
                }
            """.trimIndent(),
        )
}

private class CoordinatorTokenStore : NativeDashboardTokenStore {
    override val coordinationKey = "coordinator-test"
    var tokens: NativeDashboardTokens? = null

    override fun load(): NativeDashboardTokens? = tokens
    override fun save(tokens: NativeDashboardTokens) {
        this.tokens = tokens
    }
    override fun clear() {
        tokens = null
    }
}
