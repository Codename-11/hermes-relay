package com.hermesandroid.relay.ui.screens

import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.DashboardCookieJar
import com.hermesandroid.relay.network.upstream.InMemoryDashboardCookieStore
import com.hermesandroid.relay.network.upstream.importDashboardCookieHeader
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DashboardWebViewAuthPolicyTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun selfHostedOidc_usesDashboardLoginWithoutNativeOrLoopbackParameters() {
        val url = DashboardApiClient.authLoginUrl(
            baseUrl = "https://hermes.example.test",
            provider = "self-hosted",
            next = "/",
        )

        assertEquals(
            "https://hermes.example.test/auth/login?provider=self-hosted&next=%2F",
            url,
        )
        assertFalse(url.contains("/auth/native/authorize"))
        assertFalse(url.contains("redirect_uri"))
        assertFalse(url.contains("127.0.0.1"))
    }

    @Test
    fun publicDashboardCallback_importsCookieAndVerifiesAuthenticatedSession() = runTest {
        assertEquals(
            DashboardWebViewAuthNavigation.ImportAndVerify,
            dashboardWebViewAuthNavigation(
                "https://hermes.example.test",
                "https://hermes.example.test/auth/callback?code=public-code&state=public-state",
            ),
        )

        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"authenticated":true,"username":"operator","provider":"self-hosted"}""",
                ),
        )
        val store = InMemoryDashboardCookieStore()
        val callbackUrl = server.url("/auth/callback?code=public-code").toString()
        assertEquals(
            1,
            importDashboardCookieHeader(
                store = store,
                url = callbackUrl,
                cookieHeader = "hermes_session=authenticated",
            ),
        )
        val client = DashboardApiClient(
            baseUrl = server.url("/").toString(),
            okHttpClient = OkHttpClient.Builder()
                .cookieJar(DashboardCookieJar(store))
                .build(),
        )

        val session = client.currentSession().getOrThrow()

        assertTrue(session.authenticated)
        val request = server.takeRequest()
        assertEquals("/api/auth/me", request.path)
        assertEquals("hermes_session=authenticated", request.getHeader("Cookie"))
        client.shutdown()
    }

    @Test
    fun foreignLoopbackCallbacksAreRejectedWhileProviderPagesContinue() {
        val dashboard = "https://hermes.example.test"
        listOf(
            "http://127.0.0.1:40179/callback?code=code",
            "http://localhost:40179/callback?code=code",
            "http://[::1]:40179/callback?code=code",
        ).forEach { callback ->
            assertEquals(
                callback,
                DashboardWebViewAuthNavigation.RejectLoopbackCallback,
                dashboardWebViewAuthNavigation(dashboard, callback),
            )
        }
        assertEquals(
            DashboardWebViewAuthNavigation.Continue,
            dashboardWebViewAuthNavigation(
                dashboard,
                "https://auth.example.test/application/o/authorize/",
            ),
        )
    }
}
