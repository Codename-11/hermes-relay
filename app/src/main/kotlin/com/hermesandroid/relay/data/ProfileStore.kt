package com.hermesandroid.relay.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Single source of truth for the list of Hermes connection profiles and which
 * one is currently active.
 *
 * Persistence lives on [Context.relayDataStore] — the same DataStore used by
 * [FeatureFlags] / [PairingPreferences] / etc. — under two keys:
 *
 *  - [KEY_PROFILES]          — JSON array of [Profile] serialized via
 *                              kotlinx.serialization.
 *  - [KEY_ACTIVE_PROFILE_ID] — the UUID of the currently-active profile.
 *                              May be absent on a fresh install or after the
 *                              last profile is removed.
 *
 * The store exposes three [StateFlow]s:
 *
 *  - [profiles]         — the full list, hot on the store's own scope.
 *  - [activeProfileId]  — the active profile's UUID, or null.
 *  - [activeProfile]    — derived from the above two. Null when the active ID
 *                         is missing or refers to a profile that no longer
 *                         exists (stale ID after a delete, for example).
 *
 * The store is **single-writer by convention** — all mutations go through its
 * suspend fns, each of which uses a [DataStore.edit] block under the hood so
 * concurrent writers serialize correctly. No external locking required.
 *
 * Tests instantiate it via the internal constructor that accepts a raw
 * [DataStore] so they can point it at a temp-folder preferences file without
 * needing an Android Context.
 */
class ProfileStore private constructor(
    private val dataStore: DataStore<Preferences>,
    private val context: Context?,
) {

    /**
     * Production constructor — wires the store to [Context.relayDataStore].
     * The context reference is kept so [removeProfile] can call
     * [Context.deleteSharedPreferences] on the profile's
     * EncryptedSharedPreferences file when it's removed.
     */
    constructor(context: Context) : this(
        dataStore = context.relayDataStore,
        context = context.applicationContext,
    )

    /**
     * Test constructor — accepts a raw [DataStore]. The context is null, so
     * [removeProfile] skips the file-deletion side effect (tests don't have
     * access to a real EncryptedSharedPreferences anyway).
     */
    internal constructor(dataStore: DataStore<Preferences>) : this(
        dataStore = dataStore,
        context = null,
    )

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val profileListSerializer = ListSerializer(Profile.serializer())

    private val writeMutex = Mutex()

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    /**
     * Derived: the active profile, or null when the active ID is missing or
     * points to a deleted profile. Recomputes every time either upstream
     * emits — cheap list scan, no memoization needed.
     */
    val activeProfile: StateFlow<Profile?> = combine(_profiles, _activeProfileId) { list, id ->
        if (id == null) null else list.firstOrNull { it.id == id }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch {
            try {
                val prefs = dataStore.data.first()
                _profiles.value = decodeProfiles(prefs[KEY_PROFILES])
                _activeProfileId.value = prefs[KEY_ACTIVE_PROFILE_ID]
            } catch (e: Exception) {
                Log.w(TAG, "Initial hydrate failed: ${e.message}")
            }
        }
    }

    // --- Mutations ----------------------------------------------------------

    suspend fun addProfile(profile: Profile) {
        writeMutex.withLock {
            dataStore.edit { prefs ->
                val current = decodeProfiles(prefs[KEY_PROFILES])
                // If the same ID already exists, treat this as an upsert —
                // callers shouldn't rely on insertion order of a duplicate
                // add, and the alternative (throwing) makes migration code
                // more brittle than it needs to be.
                val next = current.filterNot { it.id == profile.id } + profile
                prefs[KEY_PROFILES] = encodeProfiles(next)
                _profiles.value = next
            }
        }
    }

    /**
     * Replace the profile with [profile]'s id. No-op if no profile with that
     * id exists — callers should use [addProfile] for inserts.
     */
    suspend fun updateProfile(profile: Profile) {
        writeMutex.withLock {
            dataStore.edit { prefs ->
                val current = decodeProfiles(prefs[KEY_PROFILES])
                if (current.none { it.id == profile.id }) {
                    Log.w(TAG, "updateProfile: no profile with id=${profile.id} — ignored")
                    return@edit
                }
                val next = current.map { if (it.id == profile.id) profile else it }
                prefs[KEY_PROFILES] = encodeProfiles(next)
                _profiles.value = next
            }
        }
    }

    /**
     * Remove the profile with [id] from the list and delete its backing
     * EncryptedSharedPreferences file. If [id] is currently active, the
     * active pointer is cleared — callers are responsible for picking a new
     * active profile.
     *
     * The EncryptedSharedPreferences file is deleted via
     * [Context.deleteSharedPreferences], which is documented as API 24+ and
     * is safe on our minSdk 26. The legacy profile's file
     * ([Profile.LEGACY_TOKEN_STORE_KEY]) is deleted the same way — there's
     * nothing structurally special about it once the user explicitly asks
     * to remove profile 0.
     */
    suspend fun removeProfile(id: String) {
        writeMutex.withLock {
            var removed: Profile? = null
            dataStore.edit { prefs ->
                val current = decodeProfiles(prefs[KEY_PROFILES])
                removed = current.firstOrNull { it.id == id }
                val next = current.filterNot { it.id == id }
                prefs[KEY_PROFILES] = encodeProfiles(next)
                _profiles.value = next

                if (prefs[KEY_ACTIVE_PROFILE_ID] == id) {
                    prefs.remove(KEY_ACTIVE_PROFILE_ID)
                    _activeProfileId.value = null
                }
            }
            removed?.let { profile ->
                context?.let { ctx ->
                    try {
                        ctx.deleteSharedPreferences(profile.tokenStoreKey)
                    } catch (e: Exception) {
                        Log.w(
                            TAG,
                            "deleteSharedPreferences(${profile.tokenStoreKey}) failed: ${e.message}",
                        )
                    }
                }
            }
        }
    }

    suspend fun setActiveProfile(id: String) {
        writeMutex.withLock {
            dataStore.edit { prefs ->
                prefs[KEY_ACTIVE_PROFILE_ID] = id
                _activeProfileId.value = id
            }
        }
    }

    /**
     * Update just the `lastActiveSessionId` on the identified profile. Called
     * whenever the user picks a chat session so profile-switch can restore
     * the same session on re-selection. No-op if the profile doesn't exist.
     */
    suspend fun setLastActiveSessionId(profileId: String, sessionId: String?) {
        writeMutex.withLock {
            dataStore.edit { prefs ->
                val current = decodeProfiles(prefs[KEY_PROFILES])
                val target = current.firstOrNull { it.id == profileId } ?: return@edit
                val next = current.map {
                    if (it.id == profileId) target.copy(lastActiveSessionId = sessionId) else it
                }
                prefs[KEY_PROFILES] = encodeProfiles(next)
                _profiles.value = next
            }
        }
    }

    /**
     * Stamp the identified profile with pairing metadata pulled out of an
     * `auth.ok` payload. No-op if the profile doesn't exist — callers are
     * responsible for ordering this after [addProfile].
     *
     * Both [pairedAtMillis] and [expiresAtMillis] are epoch milliseconds —
     * pass `System.currentTimeMillis()` for pairedAt and `expires_at * 1000`
     * for the seconds-based auth.ok payload field. `ProfilesSettingsScreen`
     * assumes millis when rendering relative time; pass seconds here and
     * cards will always read as "Paired decades ago".
     */
    suspend fun markPaired(
        profileId: String,
        pairedAtMillis: Long,
        transportHint: String?,
        expiresAtMillis: Long?,
    ) {
        writeMutex.withLock {
            dataStore.edit { prefs ->
                val current = decodeProfiles(prefs[KEY_PROFILES])
                val target = current.firstOrNull { it.id == profileId } ?: return@edit
                val next = current.map {
                    if (it.id == profileId) {
                        target.copy(
                            pairedAt = pairedAtMillis,
                            transportHint = transportHint,
                            expiresAt = expiresAtMillis,
                        )
                    } else {
                        it
                    }
                }
                prefs[KEY_PROFILES] = encodeProfiles(next)
                _profiles.value = next
            }
        }
    }

    /**
     * One-shot legacy migration: if no profiles are persisted yet, seed a
     * profile 0 pointing at [Profile.LEGACY_TOKEN_STORE_KEY] so the existing
     * paired install keeps working without a re-pair.
     *
     * Idempotent — subsequent calls after profile 0 is seeded (or after the
     * user has added real profiles) are a no-op. Pass the legacy URL / session
     * values from whatever store currently holds them (e.g.,
     * [ConnectionViewModel]'s DataStore-backed URL preferences).
     */
    suspend fun migrateLegacyProfileIfNeeded(
        legacyApiServerUrl: String?,
        legacyRelayUrl: String?,
        legacyLastSessionId: String?,
    ) {
        writeMutex.withLock {
            dataStore.edit { prefs ->
                val existing = decodeProfiles(prefs[KEY_PROFILES])
                if (existing.isNotEmpty()) {
                    // Already migrated or already has user-created profiles.
                    return@edit
                }
                val apiUrl = legacyApiServerUrl ?: DEFAULT_API_URL
                val relayUrl = legacyRelayUrl ?: DEFAULT_RELAY_URL
                val id = java.util.UUID.randomUUID().toString()
                val seed = Profile(
                    id = id,
                    label = Profile.extractDefaultLabel(apiUrl),
                    apiServerUrl = apiUrl,
                    relayUrl = relayUrl,
                    tokenStoreKey = Profile.LEGACY_TOKEN_STORE_KEY,
                    pairedAt = null,
                    lastActiveSessionId = legacyLastSessionId,
                    transportHint = null,
                    expiresAt = null,
                )
                val next = listOf(seed)
                prefs[KEY_PROFILES] = encodeProfiles(next)
                prefs[KEY_ACTIVE_PROFILE_ID] = id
                _profiles.value = next
                _activeProfileId.value = id
                Log.i(TAG, "migrateLegacyProfileIfNeeded: seeded profile 0 id=$id apiUrl=$apiUrl")
            }
        }
    }

    // --- Encoding helpers ---------------------------------------------------

    private fun encodeProfiles(list: List<Profile>): String =
        json.encodeToString(profileListSerializer, list)

    private fun decodeProfiles(raw: String?): List<Profile> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(profileListSerializer, raw)
        } catch (e: Exception) {
            Log.w(TAG, "decodeProfiles failed, returning empty list: ${e.message}")
            emptyList()
        }
    }

    companion object {
        private const val TAG = "ProfileStore"

        private val KEY_PROFILES = stringPreferencesKey("profiles_v1")
        private val KEY_ACTIVE_PROFILE_ID = stringPreferencesKey("active_profile_id")

        // Match the defaults used by ConnectionViewModel so a seeded profile
        // from migrateLegacyProfileIfNeeded() resolves to the same endpoints
        // a fresh install would.
        private const val DEFAULT_API_URL = "http://localhost:8642"
        private const val DEFAULT_RELAY_URL = "wss://localhost:8767"
    }
}
