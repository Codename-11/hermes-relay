package com.hermesandroid.relay.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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
        store.setSessionId("conn-1", null, "session-default")

        assertEquals(
            "session-default",
            store.sessionIdFlow("conn-1", null).first(),
        )
    }

    @Test
    fun profileSessionsAreIndependentFromDefaultAndEachOther() = runBlocking {
        store.setSessionId("conn-1", null, "session-default")
        store.setSessionId("conn-1", "mizu", "session-mizu")
        store.setSessionId("conn-1", "coder", "session-coder")
        store.setSessionId("conn-2", "mizu", "session-other")

        assertEquals("session-default", store.sessionIdFlow("conn-1", null).first())
        assertEquals("session-mizu", store.sessionIdFlow("conn-1", "mizu").first())
        assertEquals("session-coder", store.sessionIdFlow("conn-1", "coder").first())
        assertEquals("session-other", store.sessionIdFlow("conn-2", "mizu").first())
    }

    @Test
    fun nullSessionClearsOnlyThatProfileSlot() = runBlocking {
        store.setSessionId("conn-1", "mizu", "session-mizu")
        store.setSessionId("conn-1", "coder", "session-coder")

        store.setSessionId("conn-1", "mizu", null)

        assertNull(store.sessionIdFlow("conn-1", "mizu").first())
        assertEquals("session-coder", store.sessionIdFlow("conn-1", "coder").first())
    }

    @Test
    fun clearConnectionRemovesAllProfilesForThatConnectionOnly() = runBlocking {
        store.setSessionId("conn-1", null, "session-default")
        store.setSessionId("conn-1", "mizu", "session-mizu")
        store.setSessionId("conn-2", "mizu", "session-other")

        store.clearConnection("conn-1")

        assertNull(store.sessionIdFlow("conn-1", null).first())
        assertNull(store.sessionIdFlow("conn-1", "mizu").first())
        assertEquals("session-other", store.sessionIdFlow("conn-2", "mizu").first())
    }
}
