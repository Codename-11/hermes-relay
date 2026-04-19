package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.auth.AuthManager
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.ConnectionStore
import com.hermesandroid.relay.network.ConnectionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ConnectionSwitchCoordinator] — the extracted orchestration
 * layer that runs the heavy connection-swap sequence described on its KDoc.
 *
 * Each collaborator is mocked so the test exercises the coordinator in
 * isolation without standing up an Android Application, DataStore, or
 * EncryptedSharedPreferences. The `AuthManager` factory returns relaxed
 * mocks (`AuthManager` is not an interface but its public surface is
 * observable via MockK's `spyk`-through-relaxed-ctor pattern — here we
 * just rely on `mockk<AuthManager>(relaxed = true)` and stub the handful
 * of properties we read).
 *
 * Android platform calls (notably `android.util.Log`) return their Java
 * defaults at test time thanks to
 * `testOptions.unitTests.isReturnDefaultValues = true` in
 * `app/build.gradle.kts` — no MockK static mocking needed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionSwitchTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var testScope: TestScope

    private lateinit var connectionStore: ConnectionStore
    private lateinit var connectionManager: ConnectionManager

    private lateinit var streamCancelInvocations: Channel<Unit>
    private lateinit var voiceStopInvocations: Channel<Unit>

    private lateinit var apiClientRebuildCount: IntArray
    private lateinit var setApiServerUrlCapture: MutableList<String>
    private lateinit var setRelayUrlCapture: MutableList<String>
    private lateinit var persistUrlsCapture: MutableList<Pair<String, String>>

    private val activeConnectionIdFlow = MutableStateFlow<String?>("connection-a")
    private val connectionsFlow = MutableStateFlow<List<Connection>>(emptyList())

    private val sampleA = Connection(
        id = "connection-a",
        label = "A",
        apiServerUrl = "http://host-a:8642",
        relayUrl = "wss://host-a:8767",
        tokenStoreKey = Connection.buildTokenStoreKey("connection-a"),
    )
    private val sampleB = Connection(
        id = "connection-b",
        label = "B",
        apiServerUrl = "http://host-b:8642",
        relayUrl = "wss://host-b:8767",
        tokenStoreKey = Connection.buildTokenStoreKey("connection-b"),
    )

    private var installedAuthManager: AuthManager? = null

    @Before
    fun setUp() {
        // `android.util.Log` calls return 0 at test time thanks to
        // `testOptions.unitTests.isReturnDefaultValues = true` in the
        // module's build.gradle.kts — no MockK static mocks needed.
        testScope = TestScope(dispatcher)

        connectionsFlow.value = listOf(sampleA, sampleB)
        activeConnectionIdFlow.value = "connection-a"

        connectionStore = mockk(relaxed = true)
        every { connectionStore.connections } returns connectionsFlow.asStateFlow()
        every { connectionStore.activeConnectionId } returns activeConnectionIdFlow.asStateFlow()
        coEvery { connectionStore.setActiveConnection(any()) } coAnswers {
            activeConnectionIdFlow.value = firstArg()
        }

        connectionManager = mockk(relaxed = true)

        streamCancelInvocations = Channel(capacity = 16)
        voiceStopInvocations = Channel(capacity = 16)
        apiClientRebuildCount = intArrayOf(0)
        setApiServerUrlCapture = mutableListOf()
        setRelayUrlCapture = mutableListOf()
        persistUrlsCapture = mutableListOf()
    }

    // --- Helpers ------------------------------------------------------------

    private fun newAuthManagerMock(authState: AuthState, hasPairCtx: Boolean): AuthManager {
        val am = mockk<AuthManager>(relaxed = true)
        every { am.authState } returns MutableStateFlow(authState).asStateFlow()
        every { am.hasPairContext } returns hasPairCtx
        return am
    }

    private fun buildCoordinator(
        authFactory: (String) -> AuthManager = { cid ->
            newAuthManagerMock(
                authState = if (cid == "connection-b") {
                    AuthState.Paired("token-for-$cid")
                } else {
                    AuthState.Unpaired
                },
                hasPairCtx = cid == "connection-b",
            )
        },
    ): ConnectionSwitchCoordinator {
        installedAuthManager = newAuthManagerMock(
            authState = AuthState.Paired("initial-token"),
            hasPairCtx = true,
        )
        return ConnectionSwitchCoordinator(
            connectionStore = connectionStore,
            connectionManager = connectionManager,
            scope = testScope,
            authManagerFactory = authFactory,
            installAuthManager = { installedAuthManager = it },
            setApiServerUrl = { setApiServerUrlCapture += it },
            setRelayUrl = { setRelayUrlCapture += it },
            persistUrls = { a, r -> persistUrlsCapture += a to r },
            rebuildApiClient = { apiClientRebuildCount[0]++ },
        ).also { coord ->
            coord.registerStreamCancelCallback { streamCancelInvocations.trySend(Unit) }
            coord.registerVoiceStopCallback { voiceStopInvocations.trySend(Unit) }
        }
    }

    // --- Tests --------------------------------------------------------------

    @Test
    fun switchConnection_noOpWhenSameConnection() = runTest(dispatcher) {
        val coord = buildCoordinator()
        coord.switchConnection("connection-a").join()

        assertEquals("connection-a", activeConnectionIdFlow.value)
        assertTrue(
            "stream-cancel should not fire for same-connection switch",
            streamCancelInvocations.tryReceive().isFailure,
        )
        verify(exactly = 0) { connectionManager.disconnect() }
        assertEquals(0, apiClientRebuildCount[0])
        assertTrue(setApiServerUrlCapture.isEmpty())
        assertTrue(setRelayUrlCapture.isEmpty())
    }

    @Test
    fun switchConnection_cancelsActiveStream() = runTest(dispatcher) {
        val coord = buildCoordinator()
        coord.switchConnection("connection-b").join()

        assertTrue(
            "stream-cancel callback should have been invoked exactly once",
            streamCancelInvocations.tryReceive().isSuccess,
        )
        // Only one stream-cancel per switch.
        assertTrue(streamCancelInvocations.tryReceive().isFailure)
    }

    @Test
    fun switchConnection_stopsVoice() = runTest(dispatcher) {
        val coord = buildCoordinator()
        coord.switchConnection("connection-b").join()

        assertTrue(
            "voice-stop callback should have been invoked exactly once",
            voiceStopInvocations.tryReceive().isSuccess,
        )
        assertTrue(voiceStopInvocations.tryReceive().isFailure)
    }

    @Test
    fun switchConnection_disconnectsAndReconnectsWSS() = runTest(dispatcher) {
        val coord = buildCoordinator()
        coord.switchConnection("connection-b").join()

        verify(exactly = 1) { connectionManager.disconnect() }
        verify(exactly = 1) { connectionManager.connect(sampleB.relayUrl) }
    }

    @Test
    fun switchConnection_rebuildsApiClient() = runTest(dispatcher) {
        val coord = buildCoordinator()
        coord.switchConnection("connection-b").join()

        assertEquals(1, apiClientRebuildCount[0])
        assertEquals(listOf(sampleB.apiServerUrl), setApiServerUrlCapture)
        assertEquals(listOf(sampleB.relayUrl), setRelayUrlCapture)
        assertEquals(
            listOf(sampleB.apiServerUrl to sampleB.relayUrl),
            persistUrlsCapture,
        )
    }

    @Test
    fun switchConnection_emitsConnectionSwitchEventWithNewId() = runTest(dispatcher) {
        val coord = buildCoordinator()
        val collected = mutableListOf<String>()
        val job = launch {
            coord.connectionSwitchEvents.collect { collected += it }
        }
        // Let the collector subscribe before we emit.
        advanceUntilIdle()

        coord.switchConnection("connection-b").join()
        advanceUntilIdle()

        assertEquals(listOf("connection-b"), collected)
        job.cancel()
    }

    @Test
    fun switchConnection_abortsIfConnectionIdUnknown() = runTest(dispatcher) {
        val coord = buildCoordinator()
        coord.switchConnection("connection-does-not-exist").join()

        // Disconnect/stream-cancel do fire (teardown is unconditional by
        // design — we want the current connection torn down even if we
        // end up aborting the switch), but the rebuild/install/connect
        // half must NOT run.
        assertEquals(0, apiClientRebuildCount[0])
        assertTrue(setApiServerUrlCapture.isEmpty())
        assertTrue(setRelayUrlCapture.isEmpty())
        verify(exactly = 0) { connectionManager.connect(any()) }
        coVerify(exactly = 0) { connectionStore.setActiveConnection(any()) }
        // Active pointer unchanged.
        assertEquals("connection-a", activeConnectionIdFlow.value)
    }

    @Test
    fun switchConnection_skipsReconnectWhenNewConnectionHasNoPairContext() = runTest(dispatcher) {
        val coord = buildCoordinator(authFactory = { _ ->
            newAuthManagerMock(authState = AuthState.Paired("t"), hasPairCtx = false)
        })
        coord.switchConnection("connection-b").join()

        verify(exactly = 1) { connectionManager.disconnect() }
        verify(exactly = 0) { connectionManager.connect(any()) }
        // Still persists the new active connection even though we didn't
        // connect — the user explicitly asked to switch.
        coVerify(exactly = 1) { connectionStore.setActiveConnection("connection-b") }
    }

    @Test
    fun switchConnection_installsNewAuthManager() = runTest(dispatcher) {
        val built = mutableListOf<AuthManager>()
        val coord = buildCoordinator(authFactory = { cid ->
            val am = newAuthManagerMock(AuthState.Paired("t-$cid"), hasPairCtx = true)
            built += am
            am
        })
        val before = installedAuthManager
        coord.switchConnection("connection-b").join()

        assertEquals(1, built.size)
        assertTrue(
            "installed AuthManager should be the freshly-built one",
            installedAuthManager === built.single(),
        )
        assertFalse(
            "installed AuthManager should have been replaced",
            installedAuthManager === before,
        )
    }
}
