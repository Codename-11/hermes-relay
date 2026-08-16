package com.hermesandroid.relay.plugins.runtime

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PluginPreferenceStoreTest {
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: PluginPreferenceStore

    @Before
    fun setUp() {
        dataStore = InMemoryPreferencesDataStore()
        store = PluginPreferenceStore(dataStore)
    }

    @Test
    fun enablementAndGrants_areScopedByConnectionProfileAndPlugin() = runTest {
        val target = PluginScope("connection-a", "research", "weather")
        val otherProfile = PluginScope("connection-a", "default", "weather")
        val otherConnection = PluginScope("connection-b", "research", "weather")
        val otherPlugin = PluginScope("connection-a", "research", "calendar")

        store.setEnabled(target, true)
        store.setGrants(target, setOf("backend.write", "notifications"))

        assertEquals(
            PluginPreferenceState(true, setOf("backend.write", "notifications"), configured = true),
            store.state(target).first(),
        )
        listOf(otherProfile, otherConnection, otherPlugin).forEach { scope ->
            assertEquals(PluginPreferenceState(), store.state(scope).first())
        }
    }

    @Test
    fun nullProfile_usesStableServerDefaultScope_andClearIsNarrow() = runTest {
        val serverDefault = PluginScope("connection-a", null, "weather")
        val explicitProfile = PluginScope("connection-a", "research", "weather")
        store.setEnabled(serverDefault, true)
        store.setGrants(serverDefault, setOf("backend.read"))
        store.setEnabled(explicitProfile, true)

        store.clear(serverDefault)

        val cleared = store.state(serverDefault).first()
        assertFalse(cleared.enabled)
        assertTrue(cleared.grants.isEmpty())
        assertFalse(cleared.configured)
        assertTrue(store.state(explicitProfile).first().enabled)
    }
}

private class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}
