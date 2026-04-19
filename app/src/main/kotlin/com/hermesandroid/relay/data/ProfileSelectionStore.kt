package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Per-connection persisted selection of the active profile name.
 *
 * Why separate from [relayDataStore]: the main `relay_settings` store holds
 * a large pile of global settings that are expensive to iterate on every
 * profile-selection change, and we want these mappings to survive clean-up
 * passes over the main store without special-casing per-connection keys.
 * The dedicated `profile_selections` DataStore is small, scoped, and can be
 * cleared wholesale without collateral damage.
 *
 * Stored shape: one string-preference key per connection id, value is the
 * profile `name` string (NOT the serialized Profile object — the Profile
 * itself is advertised fresh by the server on every `auth.ok` and the
 * on-server set can drift between app launches, so we resolve name → Profile
 * at read time against the current [ConnectionViewModel.agentProfiles] list).
 *
 * A `null` value means "clear" — the key is removed from the store rather
 * than written as an empty string. That way the flow emits null cleanly
 * on fresh installs and on explicit clears.
 *
 * See Commit 3 of feature/profile-config-readonly for wiring. The caller
 * ([com.hermesandroid.relay.viewmodel.ConnectionViewModel]) handles the
 * name → Profile resolution and is the sole consumer.
 */
class ProfileSelectionStore(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.profileSelectionsDataStore)

    companion object {
        /**
         * Preference-key factory. Per-connection so every connection gets
         * its own slot — wholesale clearing of the store still works by
         * calling [clear] per connection id (or, in disaster-recovery,
         * dropping the file).
         */
        private fun keyFor(connectionId: String) =
            stringPreferencesKey("selected_profile_$connectionId")
    }

    /**
     * Persist the selected profile name for [connectionId]. Passing `null`
     * removes the key — fresh installs and explicit clears both converge
     * on the same "no key" state.
     */
    suspend fun setSelectedProfile(connectionId: String, profileName: String?) {
        dataStore.edit { prefs ->
            val key = keyFor(connectionId)
            if (profileName == null) {
                prefs.remove(key)
            } else {
                prefs[key] = profileName
            }
        }
    }

    /**
     * Emits the persisted profile name for [connectionId], or `null` when
     * no selection has been stored. Callers must resolve the name against
     * the current server-advertised profile list — if the profile was
     * removed on the server, the caller should treat the resolution as
     * null (see ConnectionViewModel for the reference resolver).
     */
    fun selectedProfileFlow(connectionId: String): Flow<String?> {
        val key = keyFor(connectionId)
        return dataStore.data.map { prefs -> prefs[key] }
    }

    /**
     * Remove the persisted selection for [connectionId]. Called from the
     * Connection removal path AFTER the switch-away job completes so we
     * don't delete the selection the just-unmounted store is still writing.
     */
    suspend fun clear(connectionId: String) {
        dataStore.edit { prefs ->
            prefs.remove(keyFor(connectionId))
        }
    }
}

/**
 * Dedicated DataStore for [ProfileSelectionStore]. Kept separate from
 * [relayDataStore] so the two stores can evolve independently and a nuke
 * of one doesn't take out the other.
 */
internal val Context.profileSelectionsDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "profile_selections")
