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
import org.junit.Before
import org.junit.Test

class ProfileIconStoreTest {

    private lateinit var store: ProfileIconStore

    @Before
    fun setUp() {
        store = ProfileIconStore(InMemoryPreferencesDataStore())
    }

    @Test
    fun iconsAreScopedByConnectionAndProfile() = runBlocking {
        store.setIcon("conn-1", null, "/files/a.png")
        store.setIcon("conn-1", "mizu", "/files/b.png")
        store.setIcon("conn-2", null, "/files/c.png")

        assertEquals("/files/a.png", store.iconFlow("conn-1", null).first())
        assertEquals("/files/b.png", store.iconFlow("conn-1", "mizu").first())
        assertEquals("/files/c.png", store.iconFlow("conn-2", null).first())
    }

    @Test
    fun nullPathClearsTheIcon() = runBlocking {
        store.setIcon("conn-1", null, "/files/a.png")
        store.setIcon("conn-1", null, null)

        assertNull(store.iconFlow("conn-1", null).first())
    }

    @Test
    fun clearConnectionRemovesOnlyThatConnection() = runBlocking {
        store.setIcon("conn-1", null, "/files/a.png")
        store.setIcon("conn-1", "mizu", "/files/b.png")
        store.setIcon("conn-2", null, "/files/c.png")

        store.clearConnection("conn-1")

        assertNull(store.iconFlow("conn-1", null).first())
        assertNull(store.iconFlow("conn-1", "mizu").first())
        assertEquals("/files/c.png", store.iconFlow("conn-2", null).first())
    }

    @Test
    fun serverAvatarsKeepDuplicateProfileNamesConnectionScoped() = runBlocking {
        store.setServerAvatar("conn-1", "operator", "/server/a.png")
        store.setServerAvatar("conn-2", "operator", "/server/b.png")

        assertEquals("/server/a.png", store.serverAvatarFlow("conn-1", "operator").first())
        assertEquals("/server/b.png", store.serverAvatarFlow("conn-2", "operator").first())
    }

    @Test
    fun clearingServerAvatarDoesNotOverwriteLocalFallback() = runBlocking {
        store.setIcon("conn-1", "operator", "/local/icon.png")
        store.setServerAvatar("conn-1", "operator", "/server/avatar.png")
        store.setServerAvatar("conn-1", "operator", null)

        assertNull(store.serverAvatarFlow("conn-1", "operator").first())
        assertEquals("/local/icon.png", store.iconFlow("conn-1", "operator").first())
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
