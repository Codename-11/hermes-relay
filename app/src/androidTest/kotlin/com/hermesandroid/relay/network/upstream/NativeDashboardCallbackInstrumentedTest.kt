package com.hermesandroid.relay.network.upstream

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device-local HTTP fixture: no provider accounts, live configuration, or persisted app data. */
@RunWith(AndroidJUnit4::class)
class NativeDashboardCallbackInstrumentedTest {
    @Test
    fun androidLoopbackAcceptsFragmentedCallbackWithIdlePreconnectionAndVerifiesBearer() = runBlocking {
        val gateway = MockWebServer()
        gateway.start()
        val store = InstrumentedNativeTokenStore()
        try {
            gateway.enqueue(MockResponse().setBody("""{"access_token":"fixture-access","expires_at":4102444800,"provider":"basic"}"""))
            gateway.enqueue(MockResponse().setBody("""{"authenticated":true,"provider":"basic","user_id":"fixture"}"""))
            val launched = CompletableDeferred<String>()
            val accepted = CompletableDeferred<Unit>()
            val result = async(Dispatchers.IO) {
                NativeDashboardSignInCoordinator(NativeDashboardAuthClient(gateway.url("/").toString(), store)).signIn(
                    "basic", onDiagnostic = { if (it == "socket_accepted") accepted.complete(Unit) },
                ) { launched.complete(it) }
            }
            val authorization = launched.await().toHttpUrl()
            val redirect = authorization.queryParameter("redirect_uri")!!.toHttpUrl()
            assertEquals("127.0.0.1", redirect.host)
            withContext(Dispatchers.IO) {
                Socket(redirect.host, redirect.port).use {
                    withTimeout(2_000) { accepted.await() }
                    Socket(redirect.host, redirect.port).use { browser ->
                        browser.soTimeout = 3_000
                        val request = "GET /callback?code=fixture-code&state=${authorization.queryParameter("state")} HTTP/1.1\r\nHost: 127.0.0.1:${redirect.port}\r\n\r\n"
                        for (byte in request.toByteArray()) {
                            browser.getOutputStream().write(byte.toInt())
                            browser.getOutputStream().flush()
                        }
                        val response = browser.getInputStream().bufferedReader().readText()
                        assertTrue(response.startsWith("HTTP/1.1 200"))
                        assertTrue(response.contains("Return to Hermes Relay"))
                    }
                }
            }
            assertEquals("fixture-access", withTimeout(2_000) { result.await() }.accessToken)
            val client = DashboardApiClient(
                gateway.url("/").toString(),
                DashboardApiClient.defaultClient(bearerAuth = DashboardBearerAuth(gateway.url("/").toString(), store)),
            )
            try {
                assertTrue(client.currentSession().getOrThrow().authenticated)
            } finally {
                client.shutdown()
            }
            assertEquals("/auth/native/token", gateway.takeRequest().path)
            val verification = gateway.takeRequest()
            assertEquals("/api/auth/me", verification.path)
            assertEquals("Bearer fixture-access", verification.getHeader("Authorization"))
        } finally {
            gateway.shutdown()
        }
    }

    @Test
    fun leavingAttemptClosesAndroidSocketWithoutPersistingCredentials() = runBlocking {
        val gateway = MockWebServer()
        gateway.start()
        val store = InstrumentedNativeTokenStore()
        try {
            repeat(2) {
                val launched = CompletableDeferred<String>()
                val accepted = CompletableDeferred<Unit>()
                val result = async(Dispatchers.IO) {
                    NativeDashboardSignInCoordinator(NativeDashboardAuthClient(gateway.url("/").toString(), store)).signIn(
                        "basic", onDiagnostic = { if (it == "socket_accepted") accepted.complete(Unit) },
                    ) { launched.complete(it) }
                }
                val redirect = launched.await().toHttpUrl().queryParameter("redirect_uri")!!.toHttpUrl()
                withContext(Dispatchers.IO) {
                    Socket(redirect.host, redirect.port).use { socket ->
                        socket.getOutputStream().write("GET /call".toByteArray())
                        withTimeout(2_000) { accepted.await() }
                        withTimeout(1_500) { result.cancelAndJoin() }
                        socket.soTimeout = 1_000
                        assertEquals(-1, runCatching { socket.getInputStream().read() }.getOrDefault(-1))
                    }
                }
            }
            assertEquals(null, store.load())
            assertEquals(0, gateway.requestCount)
        } finally {
            gateway.shutdown()
        }
    }
}

private class InstrumentedNativeTokenStore : NativeDashboardTokenStore {
    override val coordinationKey = UUID.randomUUID().toString()
    private var tokens: NativeDashboardTokens? = null
    override fun load() = tokens
    override fun save(tokens: NativeDashboardTokens) { this.tokens = tokens }
    override fun clear() { tokens = null }
}
