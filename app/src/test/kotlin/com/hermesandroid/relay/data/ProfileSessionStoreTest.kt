package com.hermesandroid.relay.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.hermesandroid.relay.data.SessionTransport.GATEWAY
import com.hermesandroid.relay.data.SessionTransport.SSE
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ProfileSessionStoreTest {

    private val dataStore = InMemoryPreferencesDataStore()
    private val store = ProfileSessionStore(dataStore)

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
    fun literalDefaultProfileDoesNotShareServerDefaultSessionSlot() = runBlocking {
        store.setSessionId("conn-1", null, GATEWAY, "sticky-default-session")
        store.setSessionId("conn-1", "default", GATEWAY, "root-profile-session")

        assertEquals(
            "sticky-default-session",
            store.sessionIdFlow("conn-1", null, GATEWAY).first(),
        )
        assertEquals(
            "root-profile-session",
            store.sessionIdFlow("conn-1", "default", GATEWAY).first(),
        )
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
    fun clearedDraftSurvivesStoreRecreationAndPreservesOtherScopes() = runBlocking {
        store.setSessionId("conn-1", "mizu", GATEWAY, "session-gw")
        store.setSessionId("conn-1", "mizu", SSE, "session-sse")
        store.setSessionId("conn-2", "mizu", GATEWAY, "session-other")

        store.setSessionId("conn-1", "mizu", GATEWAY, null)
        val restartedStore = ProfileSessionStore(dataStore)

        assertNull(restartedStore.sessionIdFlow("conn-1", "mizu", GATEWAY).first())
        assertEquals(
            "session-sse",
            restartedStore.sessionIdFlow("conn-1", "mizu", SSE).first(),
        )
        assertEquals(
            "session-other",
            restartedStore.sessionIdFlow("conn-2", "mizu", GATEWAY).first(),
        )
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

    /** Avoids AndroidX's Windows-only atomic-rename failure in filesystem DataStore tests. */
    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        private val updateMutex = Mutex()

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            updateMutex.withLock {
                transform(state.value).also { state.value = it }
            }
    }
}
