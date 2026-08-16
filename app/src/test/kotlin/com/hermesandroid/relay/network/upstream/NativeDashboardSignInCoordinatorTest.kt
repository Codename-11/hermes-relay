package com.hermesandroid.relay.network.upstream

import com.hermesandroid.relay.BuildConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
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
    fun signIn_bindsBeforeLaunch_forwardsProviderAndExchangesValidCallback() = runBlocking {
        server.enqueue(tokenResponse())
        val authClient = NativeDashboardAuthClient(server.url("/").toString(), store)
        val coordinator = NativeDashboardSignInCoordinator(authClient)

        val tokens = completeSignIn(coordinator, provider = "google")

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

        coroutineScope {
            val authorizationUrl = CompletableDeferred<String>()
            val result = async {
                coordinator.signIn("github") { authorizationUrl.complete(it) }
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
        }
    }

    @Test
    fun signIn_tokenExchangeFailure_returnsPrivateActionablePage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("sensitive-upstream-detail"))
        val coordinator = NativeDashboardSignInCoordinator(
            NativeDashboardAuthClient(server.url("/").toString(), store),
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
            assertTrue(response.contains("hosted Hermes could not exchange its one-time callback code"))
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
    fun androidRedirectMode_usesBrowserForNous_andCookieFlowForSelfHostedOidc() {
        val flows = listOf("cookie", "native_pkce")

        assertEquals(
            DashboardRedirectAuthMode.NativePkce,
            androidDashboardRedirectAuthMode("nous", flows),
        )
        assertEquals(
            DashboardRedirectAuthMode.WebView,
            androidDashboardRedirectAuthMode("oidc", flows),
        )
        assertEquals(
            DashboardRedirectAuthMode.WebView,
            androidDashboardRedirectAuthMode("nous", listOf("cookie")),
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
    ): NativeDashboardTokens = coroutineScope {
        val authorizationUrl = CompletableDeferred<String>()
        val result = async {
            coordinator.signIn(provider) { authorizationUrl.complete(it) }
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
