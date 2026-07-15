package com.hermesandroid.relay.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfilePresentationStoreTest {
    private val dataStore = InMemoryPreferencesDataStore()
    private val store = ProfilePresentationStore(dataStore)
    private val profiles = listOf(
        Profile(name = "alpha", model = "a"),
        Profile(name = "default", model = "root"),
        Profile(name = "beta", model = "b"),
        Profile(name = "gamma", model = "g"),
    )

    @Test
    fun orderAndHiddenProfilesRoundTripPerConnection() = runBlocking {
        store.setOrder("one", listOf("beta", "alpha"))
        store.setHidden("one", setOf("gamma"))

        assertEquals(
            ProfilePresentation(order = listOf("beta", "alpha"), hidden = setOf("gamma")),
            store.presentationFlow("one").first(),
        )
        assertEquals(ProfilePresentation(), store.presentationFlow("two").first())
    }

    @Test
    fun policyKeepsDefaultDistinctAndAppendsNewProfiles() {
        val presentation = ProfilePresentation(
            order = listOf("beta", "missing", AgentDisplay.SERVER_DEFAULT_PROFILE_KEY),
            hidden = setOf("alpha"),
        )

        assertEquals(
            listOf("beta", AgentDisplay.SERVER_DEFAULT_PROFILE_KEY, "alpha", "gamma"),
            ProfilePresentationPolicy.orderedKeys(profiles, presentation),
        )
        assertEquals(
            listOf("beta", AgentDisplay.SERVER_DEFAULT_PROFILE_KEY, "gamma"),
            ProfilePresentationPolicy.visibleKeys(
                profiles,
                presentation,
                selectedKey = AgentDisplay.SERVER_DEFAULT_PROFILE_KEY,
            ),
        )
    }

    @Test
    fun activeHiddenProfileRemainsVisible() {
        val presentation = ProfilePresentation(hidden = setOf("beta"))

        assertEquals(
            listOf(AgentDisplay.SERVER_DEFAULT_PROFILE_KEY, "alpha", "beta", "gamma"),
            ProfilePresentationPolicy.visibleKeys(profiles, presentation, selectedKey = "beta"),
        )
    }

    @Test
    fun hiddenServerDefaultRemainsVisibleWhileSelected() {
        val presentation = ProfilePresentation(
            hidden = setOf(AgentDisplay.SERVER_DEFAULT_PROFILE_KEY),
        )

        assertEquals(
            AgentDisplay.SERVER_DEFAULT_PROFILE_KEY,
            ProfilePresentationPolicy.visibleKeys(
                profiles,
                presentation,
                selectedKey = AgentDisplay.SERVER_DEFAULT_PROFILE_KEY,
            ).first(),
        )
    }

    @Test
    fun clearRemovesOnlyOneConnectionsPreferences() = runBlocking {
        store.setOrder("one", listOf("beta"))
        store.setHidden("one", setOf("alpha"))
        store.setOrder("two", listOf("gamma"))

        store.clear("one")

        assertEquals(ProfilePresentation(), store.presentationFlow("one").first())
        assertEquals(listOf("gamma"), store.presentationFlow("two").first().order)
    }

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            return transform(state.value).also { state.value = it }
        }
    }
}
