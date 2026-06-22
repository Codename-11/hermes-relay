package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Per-connection persisted "profile lock" — pins the app to ONE Hermes
 * profile so the profile pickers/switchers across the app collapse to a
 * single locked state. A dedicated Settings control is the only surface that
 * still lists every profile (to choose the lock target or unlock).
 *
 * Twin of [ProfileSelectionStore]: this deliberately rides the SAME
 * [profileSelectionsDataStore] ("profile_selections") so the lock and the
 * selection clear and migrate together — a per-connection wipe or a wholesale
 * reset takes out both, and there is no second DataStore file to keep in sync.
 *
 * Value semantics (distinct from "selection", which is just a name or absent):
 *  - **absent key** → unlocked. The flow emits `null`. This is distinct from
 *    "locked to Server default", so we can tell "no lock" apart from "lock to
 *    the server's own default profile".
 *  - [AgentDisplay.SERVER_DEFAULT_PROFILE_KEY] sentinel → locked to **Server
 *    default** (the null-profile context). Reusing the existing sentinel keeps
 *    the server-default identity consistent with [AgentDisplay.profileSessionKey].
 *  - any other string → locked to that profile `name`.
 *
 * The caller ([com.hermesandroid.relay.viewmodel.connection.ProfileController])
 * resolves the locked name against the current server-advertised profile list;
 * if the locked profile no longer exists it HOLDS (selection null) and surfaces
 * a banner rather than silently switching.
 */
class ProfileLockStore(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.profileSelectionsDataStore)

    companion object {
        /**
         * Preference-key factory. Per-connection so every connection gets its
         * own lock slot — profiles are server-scoped, so a lock pinned on one
         * server must not leak onto another.
         */
        private fun keyFor(connectionId: String) =
            stringPreferencesKey("locked_profile_$connectionId")
    }

    /**
     * Persist the lock for [connectionId].
     *  - `null` → **unlock**: removes the key (converges with fresh-install
     *    "no key" state).
     *  - any non-null [profileName] → lock to that profile name. Callers lock
     *    to Server default by passing [AgentDisplay.SERVER_DEFAULT_PROFILE_KEY].
     */
    suspend fun setLockedProfile(connectionId: String, profileName: String?) {
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
     * Emits the locked profile name for [connectionId], or `null` when no lock
     * is stored (unlocked). The sentinel
     * [AgentDisplay.SERVER_DEFAULT_PROFILE_KEY] means "locked to Server default".
     */
    fun lockedProfileFlow(connectionId: String): Flow<String?> {
        val key = keyFor(connectionId)
        return dataStore.data.map { prefs -> prefs[key] }
    }

    /**
     * Remove the persisted lock for [connectionId]. Called from the connection
     * removal path alongside the selection clear so a removed connection's lock
     * pointer goes with it.
     */
    suspend fun clear(connectionId: String) {
        dataStore.edit { prefs ->
            prefs.remove(keyFor(connectionId))
        }
    }

    suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.clear()
        }
    }
}
