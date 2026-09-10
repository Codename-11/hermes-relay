package com.hermesandroid.relay.viewmodel

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.hermesandroid.relay.auth.AuthManager
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.auth.SecureStoreCache
import com.hermesandroid.relay.auth.SessionTokenStore
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.ConnectionStore
import com.hermesandroid.relay.data.DashboardConnectionStatus
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.network.relay.ConnectionManager
import com.hermesandroid.relay.network.upstream.EncryptedNativeDashboardTokenStore
import com.hermesandroid.relay.network.upstream.InMemoryDashboardCookieStore
import com.hermesandroid.relay.network.upstream.NativeDashboardAuthClient
import com.hermesandroid.relay.network.upstream.NativeDashboardTokenStore
import com.hermesandroid.relay.viewmodel.connection.UpstreamTransportController
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.Properties
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Extends the switch-coordinator/route-pool seams with real HTTP and production
 * token JSON. Only Android Keystore is substituted: its raw string backend is
 * file-backed and rereads the file on every access. No NativeDashboardTokens
 * object survives a store reload. ConnectionStore owns its real JSON encoding.
 * Loopback HTTP is the documented fixture exception, not a production TLS bypass.
 */
class MultiGatewayAuthPersistenceTest {
    @get:Rule
    val files = TemporaryFolder()

    @Test
    fun switchingWithOutgoingResolverSnapshotPreservesBothSerializedSessions() = runTest {
        Fixture(backgroundScope).use { fixture ->
            fixture.initialize()
            fixture.signInBoth()
            fixture.assertAuthenticated("a")
            fixture.assertAuthenticated("b")

            // The coordinator publishes B's id before the endpoint resolver
            // finishes. Keep A's endpoint deliberately stale through the probe.
            fixture.switchTo("b")
            fixture.switchTo("a")
            fixture.switchTo("b")
            fixture.assertBothStored()
            assertEquals(0, fixture.a.foreignBearerRequests.get())
            assertEquals(0, fixture.b.foreignBearerRequests.get())
            assertEquals(0, fixture.a.rejectedRefreshes.get())
            assertEquals(0, fixture.b.rejectedRefreshes.get())

            fixture.reload()
            fixture.assertBothStored()
            fixture.assertAuthenticated("a")
            fixture.assertAuthenticated("b")
        }
    }

    @Test
    fun coldReloadPreservesBothSessionsWithoutAnySwitch() = runTest {
        Fixture(backgroundScope).use { fixture ->
            fixture.initialize()
            fixture.signInBoth()
            fixture.reload()
            fixture.assertBothStored()
            fixture.assertAuthenticated("a")
            fixture.assertAuthenticated("b")
        }
    }

    @Test
    fun rotatedCredentialsSurviveClientAndStoreRecreation() = runTest {
        Fixture(backgroundScope).use { fixture ->
            fixture.initialize()
            fixture.signInBoth()
            fixture.a.rejectCurrentAccess = true
            fixture.assertAuthenticated("a")
            assertEquals(1, fixture.a.refreshes.get())
            assertEquals(0, fixture.b.refreshes.get())
            fixture.reload()
            fixture.assertAuthenticated("a")
            fixture.assertAuthenticated("b")
            assertEquals(1, fixture.a.refreshes.get())
        }
    }

    @Test
    fun mismatchedAndIneligibleRoutesNeverAttachOrClearStoredBearer() = runTest {
        Fixture(backgroundScope).use { fixture ->
            fixture.initialize()
            fixture.signInBoth()
            val wrongRoute = fixture.transport.dashboardClientFor("a", fixture.b.url)
            assertFalse(wrongRoute.currentSession().getOrThrow().authenticated)
            wrongRoute.shutdown()
            fixture.eligible = false
            // A new controller ensures this assertion tests eligibility itself,
            // independently of the separate cached-client policy transition.
            fixture.replaceTransport()
            val ineligible = fixture.transport.dashboardClientFor("a", fixture.a.url)
            assertFalse(ineligible.currentSession().getOrThrow().authenticated)
            ineligible.shutdown()
            fixture.assertBothStored()
            assertEquals(0, fixture.a.refreshes.get())
            assertEquals(0, fixture.b.refreshes.get())
            assertEquals(0, fixture.b.foreignBearerRequests.get())
        }
    }

    private inner class Fixture(private val scope: CoroutineScope) : AutoCloseable {
        val a = AuthPeer("a")
        val b = AuthPeer("b")
        private val context = mockk<Context>().also { every { it.applicationContext } returns it }
        private val preferences = PreferencesBackend()
        private val suffix = UUID.randomUUID().toString()
        private val rawStores = listOf("a", "b").associateWith {
            FileStrings(files.newFile("$suffix-$it.properties"))
        }
        private val definitions = listOf(a, b).map { peer ->
            Connection(
                id = peer.id,
                label = peer.id,
                apiServerUrl = "",
                relayUrl = "",
                dashboardUrl = peer.url,
                routeCandidates = listOf(EndpointCandidate(
                    role = "lan",
                    dashboard = com.hermesandroid.relay.data.DashboardEndpoint(peer.url),
                )),
                tokenStoreKey = "fixture-$suffix-${peer.id}",
            )
        }
        private var store = ConnectionStore(preferences, scope)
        private var endpoint: EndpointCandidate? = null
        private var tokenStores = emptyMap<String, NativeDashboardTokenStore>()
        var eligible = true
        lateinit var transport: UpstreamTransportController
            private set

        suspend fun initialize() {
            store.isHydrated.first { it }
            definitions.forEach { store.addConnection(it) }
            store.setActiveConnection("a")
            endpoint = connection("a").routeCandidates.single()
            tokenStores = definitions.associate { connection ->
                SecureStoreCache.getOrBuild(connection.tokenStoreKey) { rawStores.getValue(connection.id) }
                connection.id to EncryptedNativeDashboardTokenStore(context, connection.tokenStoreKey)
            }
            replaceTransport()
        }

        private fun connection(id: String): Connection = store.connections.value.single { it.id == id }

        // Same synchronous ownership inputs as ConnectionViewModel.activeDashboardUrl.
        // Endpoint publication intentionally lags connection-id publication.
        private fun activeUrl(): String = resolveEffectiveDashboardUrl(
            store.connections.value.firstOrNull { it.id == store.activeConnectionId.value },
            endpoint,
        )

        fun replaceTransport() {
            if (::transport.isInitialized) definitions.forEach { transport.disposeConnectionRouteClients(it.id) }
            transport = UpstreamTransportController(
                context = context,
                activeConnectionIdProvider = { store.activeConnectionId.value },
                dashboardUrlProvider = { activeUrl() },
                gatewayKeepAliveProvider = { false },
                tokenStoreKeyProvider = { connection(it).tokenStoreKey },
                trustedDashboardUrlProvider = { id ->
                    if (id == store.activeConnectionId.value) activeUrl() else connection(id).resolvedDashboardUrl
                },
                nativeDashboardBearerEligibleProvider = { id ->
                    eligible && nativeDashboardBearerCompatible(
                        connection(id).dashboardLastStatus?.authProviders
                            ?.takeIf { it.isNotEmpty() }
                            ?: connection(id).dashboardAuthProviders,
                    )
                },
                dashboardTokenStoreFactory = { key ->
                    tokenStores.getValue(definitions.single { it.tokenStoreKey == key }.id)
                },
                dashboardCookieStoreFactory = { _, _ -> InMemoryDashboardCookieStore() },
            )
        }

        fun signInBoth() {
            listOf(a, b).forEach { peer ->
                val client = NativeDashboardAuthClient(peer.url, tokenStores.getValue(peer.id))
                val authorization = client.beginAuthorization("http://127.0.0.1:43123/callback")
                client.exchangeCallback(authorization, "/callback?code=fixture&state=${authorization.state}")
            }
            assertBothStored()
        }

        suspend fun assertAuthenticated(id: String) {
            val client = transport.dashboardClientFor(id, connection(id).resolvedDashboardUrl)
            try {
                val status = client.getStatus().getOrThrow()
                assertTrue(status.authRequired)
                store.setDashboardStatus(
                    id,
                    DashboardConnectionStatus(authRequired = status.authRequired, authProviders = status.authProviders),
                )
                assertTrue("REST session must authenticate for its owner", client.currentSession().getOrThrow().authenticated)
                assertTrue("WebSocket admission must authenticate for its owner", client.requestWsTicket().isSuccess)
            } finally {
                client.shutdown()
            }
        }

        suspend fun switchTo(id: String) {
            val manager = mockk<ConnectionManager>(relaxed = true)
            val auth = mockk<AuthManager>(relaxed = true)
            every { auth.authState } returns MutableStateFlow<AuthState>(AuthState.Unpaired)
            every { auth.hasPairContext } returns false
            val coordinator = ConnectionSwitchCoordinator(
                connectionStore = store,
                connectionManager = manager,
                scope = scope,
                authManagerFactory = { auth },
                installAuthManager = {},
                setApiServerUrl = {},
                setRelayUrl = {},
                persistUrls = { _, _ -> },
                rebuildApiClient = {
                    val client = transport.dashboardClientFor(id, activeUrl())
                    try {
                        client.getStatus().getOrThrow()
                        client.currentSession().getOrThrow()
                    } finally {
                        client.shutdown()
                    }
                },
            )
            transport.resetGatewayForConnectionSwitch()
            coordinator.switchConnection(id).join()
            endpoint = connection(id).routeCandidates.single()
            assertBothStored()
            assertAuthenticated(id)
        }

        fun assertBothStored() {
            assertTrue("A's serialized token must remain readable", tokenStores.getValue("a").load() != null)
            assertTrue("B's serialized token must remain readable", tokenStores.getValue("b").load() != null)
        }

        suspend fun reload() {
            definitions.forEach { transport.disposeConnectionRouteClients(it.id) }
            // ConnectionStore decodes its persisted connections_v1 JSON again;
            // the native stores decode their saved JSON from files again.
            store = ConnectionStore(preferences, scope)
            store.isHydrated.first { it }
            tokenStores = definitions.associate {
                it.id to EncryptedNativeDashboardTokenStore(context, it.tokenStoreKey)
            }
            endpoint = connection(store.activeConnectionId.value!!).routeCandidates.single()
            replaceTransport()
        }

        override fun close() {
            if (::transport.isInitialized) definitions.forEach { transport.disposeConnectionRouteClients(it.id) }
            a.close()
            b.close()
        }
    }

    private class AuthPeer(val id: String) : AutoCloseable {
        private val server = MockWebServer()
        private var generation = 0
        @Volatile
        var rejectCurrentAccess = false
        val refreshes = AtomicInteger()
        val rejectedRefreshes = AtomicInteger()
        val foreignBearerRequests = AtomicInteger()
        val url: String

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/api/status") {
                        return MockResponse().setBody(
                            """{"auth_required":true,"auth_providers":["basic"],"auth_flows":["cookie","native_pkce"]}""",
                        )
                    }
                    if (request.path == "/auth/native/token") return tokens()
                    if (request.path == "/auth/native/refresh") {
                        if (!request.body.readUtf8().contains("fixture-refresh-$id-$generation")) {
                            rejectedRefreshes.incrementAndGet()
                            return MockResponse().setResponseCode(401)
                        }
                        generation += 1
                        rejectCurrentAccess = false
                        refreshes.incrementAndGet()
                        return tokens()
                    }
                    val header = request.getHeader("Authorization")
                    if (header != null && !header.startsWith("Bearer fixture-access-$id-")) {
                        foreignBearerRequests.incrementAndGet()
                    }
                    if (header != "Bearer fixture-access-$id-$generation" || rejectCurrentAccess) {
                        return MockResponse().setResponseCode(401)
                    }
                    return when (request.path) {
                        "/api/auth/me" -> MockResponse().setBody("""{"authenticated":true,"provider":"basic"}""")
                        "/api/auth/ws-ticket" -> MockResponse().setBody("""{"ticket":"fixture-ticket"}""")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            url = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
        }

        private fun tokens() = MockResponse().setBody(
            """{"access_token":"fixture-access-$id-$generation","refresh_token":"fixture-refresh-$id-$generation","expires_at":4102444800,"provider":"basic"}""",
        )

        override fun close() = server.shutdown()
    }

    /** Raw storage seam only: production NativeDashboardTokenStore owns JSON. */
    private class FileStrings(private val file: File) : SessionTokenStore {
        override val hasHardwareBackedStorage = false
        private fun read() = Properties().apply { file.inputStream().use { load(it) } }
        private fun write(values: Properties) = file.outputStream().use { values.store(it, null) }
        @Synchronized override fun getString(key: String): String? = read().getProperty(key)
        @Synchronized override fun putString(key: String, value: String) { write(read().apply { setProperty(key, value) }) }
        @Synchronized override fun remove(key: String) { write(read().apply { remove(key) }) }
        @Synchronized override fun contains(key: String): Boolean = read().containsKey(key)
        @Synchronized override fun clearAll() { write(Properties()) }
    }

    private class PreferencesBackend : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
}
