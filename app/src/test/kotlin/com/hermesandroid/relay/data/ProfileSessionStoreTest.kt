package com.hermesandroid.relay.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.hermesandroid.relay.data.SessionTransport.GATEWAY
import com.hermesandroid.relay.data.SessionTransport.SSE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProfileSessionStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: ProfileSessionStore

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val file: File = tempFolder.newFile("profile_sessions_test.preferences_pb")
        if (file.exists()) file.delete()
        dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file },
        )
        store = ProfileSessionStore(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun setAndGet_defaultProfileSession() = runBlocking {
        store.setSessionId("conn-1", null, GATEWAY, "session-default")

        assertEquals(
            "session-default",
            store.sessionIdFlow("conn-1", null, GATEWAY).first(),
        )
    }

    @Test
    fun profileSessionsAreIndependentFromDefaultAndEachOther() = runBlocking {
        store.setSessionId("conn-1", null, GATEWAY, "session-default")
        store.setSessionId("conn-1", "mizu", GATEWAY, "session-mizu")
        store.setSessionId("conn-1", "coder", GATEWAY, "session-coder")
        store.setSessionId("conn-2", "mizu", GATEWAY, "session-other")

        assertEquals("session-default", store.sessionIdFlow("conn-1", null, GATEWAY).first())
        assertEquals("session-mizu", store.sessionIdFlow("conn-1", "mizu", GATEWAY).first())
        assertEquals("session-coder", store.sessionIdFlow("conn-1", "coder", GATEWAY).first())
        assertEquals("session-other", store.sessionIdFlow("conn-2", "mizu", GATEWAY).first())
    }

    @Test
    fun gatewayAndSseSlotsAreIndependentForSameProfile() = runBlocking {
        // The core of the continuity fix: a profile's gateway session and its
        // api_server (SSE) session must not clobber one another.
        store.setSessionId("conn-1", "mizu", GATEWAY, "20260614_192846_4bf8d3")
        store.setSessionId("conn-1", "mizu", SSE, "api_1781479723_f60ca534")

        assertEquals(
            "20260614_192846_4bf8d3",
            store.sessionIdFlow("conn-1", "mizu", GATEWAY).first(),
        )
        assertEquals(
            "api_1781479723_f60ca534",
            store.sessionIdFlow("conn-1", "mizu", SSE).first(),
        )
    }

    @Test
    fun nullSessionClearsOnlyThatTransportSlot() = runBlocking {
        store.setSessionId("conn-1", "mizu", GATEWAY, "session-gw")
        store.setSessionId("conn-1", "mizu", SSE, "session-sse")

        store.setSessionId("conn-1", "mizu", GATEWAY, null)

        assertNull(store.sessionIdFlow("conn-1", "mizu", GATEWAY).first())
        assertEquals("session-sse", store.sessionIdFlow("conn-1", "mizu", SSE).first())
    }

    @Test
    fun clearConnectionRemovesAllProfilesAndTransportsForThatConnectionOnly() = runBlocking {
        store.setSessionId("conn-1", null, GATEWAY, "session-default")
        store.setSessionId("conn-1", "mizu", GATEWAY, "session-mizu")
        store.setSessionId("conn-1", "mizu", SSE, "session-mizu-sse")
        store.setSessionId("conn-2", "mizu", GATEWAY, "session-other")

        store.clearConnection("conn-1")

        assertNull(store.sessionIdFlow("conn-1", null, GATEWAY).first())
        assertNull(store.sessionIdFlow("conn-1", "mizu", GATEWAY).first())
        assertNull(store.sessionIdFlow("conn-1", "mizu", SSE).first())
        assertEquals("session-other", store.sessionIdFlow("conn-2", "mizu", GATEWAY).first())
    }

    @Test
    fun forSessionId_bucketsByNamespace() {
        // api_ ids are api_server (launch DB / SSE path); everything else is gateway.
        assertEquals(SSE, SessionTransport.forSessionId("api_1781479723_f60ca534"))
        assertEquals(GATEWAY, SessionTransport.forSessionId("20260614_192846_4bf8d3"))
    }

    @Test
    fun forEndpoint_onlyGatewayMapsToGateway() {
        assertEquals(GATEWAY, SessionTransport.forEndpoint("gateway"))
        assertEquals(SSE, SessionTransport.forEndpoint("sessions"))
        assertEquals(SSE, SessionTransport.forEndpoint("completions"))
        assertEquals(SSE, SessionTransport.forEndpoint("runs"))
    }
}
