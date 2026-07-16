package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Per-connection local display preferences for the profile picker. */
data class ProfilePresentation(
    val order: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
)

/**
 * Applies saved presentation preferences without changing the server profile catalog.
 * Unknown saved names are dropped and newly-discovered profiles append in server order.
 */
object ProfilePresentationPolicy {
    fun availableKeys(profiles: List<Profile>): List<String> = buildList {
        add(AgentDisplay.SERVER_DEFAULT_PROFILE_KEY)
        profiles.asSequence()
            .filterNot { AgentDisplay.isServerDefaultAlias(it.name) }
            .map(Profile::name)
            .distinct()
            .forEach(::add)
    }

    fun orderedKeys(
        profiles: List<Profile>,
        presentation: ProfilePresentation,
    ): List<String> {
        val available = availableKeys(profiles)
        val availableSet = available.toSet()
        return presentation.order.filter { it in availableSet }.distinct() +
            available.filterNot(presentation.order.toSet()::contains)
    }

    fun visibleKeys(
        profiles: List<Profile>,
        presentation: ProfilePresentation,
        selectedKey: String,
    ): List<String> = orderedKeys(profiles, presentation).filter { key ->
        key == selectedKey || key !in presentation.hidden
    }
}

class ProfilePresentationStore(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.profilePresentationDataStore)

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(String.serializer())

    private fun orderKey(connectionId: String) = stringPreferencesKey("order_$connectionId")
    private fun hiddenKey(connectionId: String) = stringPreferencesKey("hidden_$connectionId")

    fun presentationFlow(connectionId: String): Flow<ProfilePresentation> = dataStore.data.map { prefs ->
        ProfilePresentation(
            order = decode(prefs[orderKey(connectionId)]),
            hidden = decode(prefs[hiddenKey(connectionId)]).toSet(),
        )
    }

    suspend fun setOrder(connectionId: String, order: List<String>) {
        dataStore.edit { it[orderKey(connectionId)] = json.encodeToString(listSerializer, order.distinct()) }
    }

    suspend fun setHidden(connectionId: String, hidden: Set<String>) {
        dataStore.edit { it[hiddenKey(connectionId)] = json.encodeToString(listSerializer, hidden.sorted()) }
    }

    suspend fun clear(connectionId: String) {
        dataStore.edit {
            it.remove(orderKey(connectionId))
            it.remove(hiddenKey(connectionId))
        }
    }

    suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }

    private fun decode(raw: String?): List<String> = if (raw == null) {
        emptyList()
    } else {
        runCatching { json.decodeFromString(listSerializer, raw) }.getOrDefault(emptyList())
    }
}

internal val Context.profilePresentationDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "profile_presentation")
