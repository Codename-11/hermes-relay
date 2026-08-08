package com.hermesandroid.relay.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Unit tests for [ProfileSelectionStore].
 *
 * Bypasses [android.content.Context] by feeding the store an in-memory
 * [DataStore]. [ProfileSelectionStore]'s primary constructor takes the same
 * raw DataStore shape that its Context constructor resolves in production.
 */
class ProfileSelectionStoreTest {

    private val store = ProfileSelectionStore(InMemoryPreferencesDataStore())

    @Test
    fun unset_connection_emitsNull() = runBlocking {
        // Fresh store — every connection id reads as null until set.
        assertNull(store.selectedProfileFlow("conn-1").first())
        assertNull(store.selectedProfileFlow("conn-unknown").first())
    }

    @Test
    fun set_then_get_roundTrips() = runBlocking {
        store.setSelectedProfile("conn-1", "mizu")
        assertEquals("mizu", store.selectedProfileFlow("conn-1").first())
    }

    @Test
    fun set_null_clearsTheKey() = runBlocking {
        store.setSelectedProfile("conn-1", "mizu")
        assertEquals("mizu", store.selectedProfileFlow("conn-1").first())

        // Writing null removes the key — read path should emit null rather
        // than an empty string. Distinguishable states: fresh install vs.
        // explicit clear converge here, but both produce null downstream.
        store.setSelectedProfile("conn-1", null)
        assertNull(store.selectedProfileFlow("conn-1").first())
    }

    @Test
    fun clear_removesOnlyTheGivenConnection() = runBlocking {
        store.setSelectedProfile("conn-1", "mizu")
        store.setSelectedProfile("conn-2", "coder")

        store.clear("conn-1")

        assertNull(store.selectedProfileFlow("conn-1").first())
        assertEquals("coder", store.selectedProfileFlow("conn-2").first())
    }

    @Test
    fun perConnectionKeys_areIndependent() = runBlocking {
        // Writing to one connection must not touch another — the key
        // factory is the contract and regressions here would cascade.
        store.setSelectedProfile("conn-A", "alpha")
        store.setSelectedProfile("conn-B", "beta")
        store.setSelectedProfile("conn-C", "gamma")

        assertEquals("alpha", store.selectedProfileFlow("conn-A").first())
        assertEquals("beta", store.selectedProfileFlow("conn-B").first())
        assertEquals("gamma", store.selectedProfileFlow("conn-C").first())
    }

    @Test
    fun overwrite_replacesPriorValue() = runBlocking {
        store.setSelectedProfile("conn-1", "mizu")
        store.setSelectedProfile("conn-1", "coder")
        assertEquals("coder", store.selectedProfileFlow("conn-1").first())
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
