package com.hermesandroid.relay.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionStoreStartupTest {

    @Test
    fun pinnedStartupOverridesLastUsedOnlyDuringHydration() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val store = ConnectionStore(dataStore, backgroundScope)
        store.isHydrated.first { it }

        val a = sampleConnection("connection-a", "A")
        val b = sampleConnection("connection-b", "B")
        store.addConnection(a)
        store.addConnection(b)
        store.setActiveConnection(b.id)
        store.setStartupConnection(a.id)

        assertEquals(b.id, store.activeConnectionId.value)
        assertEquals(a.id, store.startupConnectionId.value)

        val reloaded = ConnectionStore(dataStore, backgroundScope)
        reloaded.isHydrated.first { it }
        assertEquals(a.id, reloaded.activeConnectionId.value)
        assertEquals(a.id, reloaded.startupConnectionId.value)

        reloaded.setStartupConnection(null)
        assertNull(reloaded.startupConnectionId.value)
    }

    private fun sampleConnection(id: String, label: String) = Connection(
        id = id,
        label = label,
        apiServerUrl = "http://localhost:8642",
        relayUrl = "ws://localhost:8767",
        tokenStoreKey = Connection.buildTokenStoreKey(id),
    )

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            state.value = next
            return next
        }
    }
}
