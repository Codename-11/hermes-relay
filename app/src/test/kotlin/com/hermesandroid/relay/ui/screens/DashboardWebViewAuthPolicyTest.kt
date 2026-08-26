package com.hermesandroid.relay.ui.screens

import android.webkit.CookieManager
import android.webkit.WebView
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.DashboardCookieJar
import com.hermesandroid.relay.network.upstream.InMemoryDashboardCookieStore
import com.hermesandroid.relay.network.upstream.importDashboardCookieHeader
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
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
        unmockkStatic(CookieManager::class)
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
    fun privateDashboard_usesCanonicalHttpsCallbackOriginAdvertisedByUpstream() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader(
                    "Location",
                    "https://login.example.test/api/oidc/authorization" +
                        "?redirect_uri=https%3A%2F%2Fhermes.example.test%2Fauth%2Fcallback",
                ),
        )
        val selected = server.url("/").toString().trimEnd('/')

        val resolved = resolveDashboardWebViewAuthBase(
            dashboardUrl = selected,
            provider = "self-hosted",
            client = OkHttpClient(),
        )

        assertEquals("https://hermes.example.test", resolved)
        assertEquals(
            "/auth/login?provider=self-hosted&next=%2F",
            server.takeRequest().path,
        )
    }

    @Test
    fun tailscaleHttpsDashboard_usesCanonicalPublicCallbackOrigin() {
        assertEquals(
            "https://hermes.example.test",
            canonicalDashboardWebViewAuthBase(
                "https://100.71.8.56:9119",
                "https://login.example.test/authorize" +
                    "?redirect_uri=https%3A%2F%2Fhermes.example.test%2Fauth%2Fcallback",
            ),
        )
    }

    @Test
    fun sameOriginCallback_keepsSelectedDashboardBase() {
        val selected = "https://hermes.example.test/gateway"
        val location = "https://login.example.test/authorize" +
            "?redirect_uri=https%3A%2F%2Fhermes.example.test%2Fgateway%2Fauth%2Fcallback"

        assertEquals(selected, canonicalDashboardWebViewAuthBase(selected, location))
    }

    @Test
    fun foreignCallback_requiresHttpsProviderAndCallback() {
        val selected = "http://172.16.24.250:9119"

        assertEquals(
            selected,
            canonicalDashboardWebViewAuthBase(
                selected,
                "http://login.example.test/authorize" +
                    "?redirect_uri=https%3A%2F%2Fhermes.example.test%2Fauth%2Fcallback",
            ),
        )
        assertEquals(
            selected,
            canonicalDashboardWebViewAuthBase(
                selected,
                "https://login.example.test/authorize" +
                    "?redirect_uri=http%3A%2F%2Fhermes.example.test%2Fauth%2Fcallback",
            ),
        )
    }

    @Test
    fun canonicalCallback_rejectsCredentialsMalformedUrlsAndWrongCallbackPath() {
        val selected = "http://172.16.24.250:9119"
        listOf(
            "not a redirect",
            "https://user:pass@login.example.test/authorize" +
                "?redirect_uri=https%3A%2F%2Fhermes.example.test%2Fauth%2Fcallback",
            "https://login.example.test/authorize" +
                "?redirect_uri=https%3A%2F%2Fuser%3Apass%40hermes.example.test%2Fauth%2Fcallback",
            "https://login.example.test/authorize" +
                "?redirect_uri=https%3A%2F%2Fhermes.example.test%2Fcallback",
            "https://login.example.test/authorize?redirect_uri=%2Fauth%2Fcallback",
            "https://login.example.test/authorize" +
                "?redirect_uri=https%3A%2F%2Fhermes.example.test%2Fauth%2Fcallback%3Fevil%3D1",
        ).forEach { location ->
            assertEquals(selected, canonicalDashboardWebViewAuthBase(selected, location))
        }
    }

    @Test
    fun selfHostedOidcWebView_acceptsFederatedCookiesAndRequiredBrowserStorage() {
        val manager = mockk<CookieManager>(relaxed = true)
        val webView = mockk<WebView>(relaxed = true)
        mockkStatic(CookieManager::class)
        every { CookieManager.getInstance() } returns manager
        val settings = webView.settings

        configureDashboardAuthWebView(webView)

        verify(exactly = 1) { manager.setAcceptCookie(true) }
        verify(exactly = 1) { manager.setAcceptThirdPartyCookies(webView, true) }
        verify(exactly = 1) { settings.javaScriptEnabled = true }
        verify(exactly = 1) { settings.domStorageEnabled = true }
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
