package com.hermesandroid.relay.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ProfileDisplayAliasStoreTest {

    private lateinit var store: ProfileDisplayAliasStore

    @Before
    fun setUp() {
        store = ProfileDisplayAliasStore(InMemoryPreferencesDataStore())
    }

    @Test
    fun aliasesAreScopedByConnectionAndProfile() = runBlocking {
        store.setAlias("conn-1", null, "Victor")
        store.setAlias("conn-1", "mizu", "Mizu")
        store.setAlias("conn-2", null, "House")

        assertEquals("Victor", store.aliasFlow("conn-1", null).first())
        assertEquals("Mizu", store.aliasFlow("conn-1", "mizu").first())
        assertEquals("House", store.aliasFlow("conn-2", null).first())
    }

    @Test
    fun nullAliasClearsOnlyThatContext() = runBlocking {
        store.setAlias("conn-1", null, "Victor")
        store.setAlias("conn-1", "mizu", "Mizu")

        store.setAlias("conn-1", null, null)

        assertNull(store.aliasFlow("conn-1", null).first())
        assertEquals("Mizu", store.aliasFlow("conn-1", "mizu").first())
    }

    @Test
    fun clearConnectionRemovesEveryAliasForThatConnectionOnly() = runBlocking {
        store.setAlias("conn-1", null, "Victor")
        store.setAlias("conn-1", "mizu", "Mizu")
        store.setAlias("conn-2", null, "House")

        store.clearConnection("conn-1")

        assertNull(store.aliasFlow("conn-1", null).first())
        assertNull(store.aliasFlow("conn-1", "mizu").first())
        assertEquals("House", store.aliasFlow("conn-2", null).first())
    }

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
