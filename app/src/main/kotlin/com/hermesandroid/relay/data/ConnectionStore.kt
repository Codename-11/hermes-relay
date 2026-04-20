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
 * Single source of truth for the list of Hermes server connections and which
 * one is currently active.
 *
 * Persistence lives on [Context.relayDataStore] — the same DataStore used by
 * [FeatureFlags] / [PairingPreferences] / etc. — under two keys:
 *
 *  - [KEY_CONNECTIONS]          — JSON array of [Connection] serialized via
 *                                 kotlinx.serialization.
 *  - [KEY_ACTIVE_CONNECTION_ID] — the UUID of the currently-active connection.
 *                                 May be absent on a fresh install or after
 *                                 the last connection is removed.
 *
 * The store exposes three [StateFlow]s:
 *
 *  - [connections]         — the full list, hot on the store's own scope.
 *  - [activeConnectionId]  — the active connection's UUID, or null.
 *  - [activeConnection]    — derived from the above two. Null when the active
 *                            ID is missing or refers to a connection that no
 *                            longer exists (stale ID after a delete, for
 *                            example).
 *
 * The store is **single-writer by convention** — all mutations go through its
 * suspend fns, each of which uses a [DataStore.edit] block under the hood so
 * concurrent writers serialize correctly. No external locking required.
 *
 * Tests instantiate it via the internal constructor that accepts a raw
 * [DataStore] so they can point it at a temp-folder preferences file without
 * needing an Android Context.
 *
 * **Terminology note (2026-04-18):** renamed from `ProfileStore` so that the
 * term "Profile" is free to mean what Hermes's server config means by it
 * (agent profiles). Legacy DataStore keys (`profiles_v1`, `active_profile_id`)
 * are migrated once on first launch — see the init block below.
 */
class ConnectionStore private constructor(
    private val dataStore: DataStore<Preferences>,
    private val context: Context?,
) {

    /**
     * Production constructor — wires the store to [Context.relayDataStore].
     * The context reference is kept so [removeConnection] can call
     * [Context.deleteSharedPreferences] on the connection's
     * EncryptedSharedPreferences file when it's removed.
     */
    constructor(context: Context) : this(
        dataStore = context.relayDataStore,
        context = context.applicationContext,
    )

    /**
     * Test constructor — accepts a raw [DataStore]. The context is null, so
     * [removeConnection] skips the file-deletion side effect (tests don't have
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

    private val connectionListSerializer = ListSerializer(Connection.serializer())

    private val writeMutex = Mutex()

    private val _connections = MutableStateFlow<List<Connection>>(emptyList())
    val connections: StateFlow<List<Connection>> = _connections.asStateFlow()

    private val _activeConnectionId = MutableStateFlow<String?>(null)
    val activeConnectionId: StateFlow<String?> = _activeConnectionId.asStateFlow()

    /**
     * Derived: the active connection, or null when the active ID is missing
     * or points to a deleted connection. Recomputes every time either
     * upstream emits — cheap list scan, no memoization needed.
     */
    val activeConnection: StateFlow<Connection?> = combine(_connections, _activeConnectionId) { list, id ->
        if (id == null) null else list.firstOrNull { it.id == id }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch {
            try {
                val prefs = dataStore.data.first()
                val newJson = prefs[KEY_CONNECTIONS]
                val oldJson = prefs[KEY_LEGACY_PROFILES]
                val activeNew = prefs[KEY_ACTIVE_CONNECTION_ID]
                val activeOld = prefs[KEY_LEGACY_ACTIVE_PROFILE_ID]

                // Prefer the new key. If absent and the old key has data,
                // migrate it once: write to the new key and clear the old ones
                // so we don't thrash every boot. JSON shape is identical
                // between the two names — `{"id": ..., "label": ..., ...}` —
                // so no per-record migration is needed.
                if (newJson == null && oldJson != null) {
                    dataStore.edit { p ->
                        p[KEY_CONNECTIONS] = oldJson
                        p.remove(KEY_LEGACY_PROFILES)
                        if (activeNew == null && activeOld != null) {
                            p[KEY_ACTIVE_CONNECTION_ID] = activeOld
                            p.remove(KEY_LEGACY_ACTIVE_PROFILE_ID)
                        }
                    }
                    _connections.value = decodeConnections(oldJson)
                    _activeConnectionId.value = activeOld
                    Log.i(
                        TAG,
                        "Migrated legacy DataStore keys (profiles_v1 → connections_v1)",
                    )
                } else {
                    _connections.value = decodeConnections(newJson)
                    _activeConnectionId.value = activeNew
                }
            } catch (e: Exception) {
                Log.w(TAG, "Initial hydrate failed: ${e.message}")
            }
        }
    }

    // --- Mutations ----------------------------------------------------------

    suspend fun addConnection(connection: Connection) {
        writeMutex.withLock {
            dataStore.edit { prefs ->
                val current = decodeConnections(prefs[KEY_CONNECTIONS])
                // If the same ID already exists, treat this as an upsert —
                // callers shouldn't rely on insertion order of a duplicate
                // add, and the alternative (throwing) makes migration code
                // more brittle than it needs to be.
                val next = current.filterNot { it.id == connection.id } + connection
                prefs[KEY_CONNECTIONS] = encodeConnections(next)
                _connections.value = next
            }
        }
    }

    /**
     * Replace the connection with [connection]'s id. No-op if no connection
     * with that id exists — callers should use [addConnection] for inserts.
     */
    suspend fun updateConnection(connection: Connection) {
        writeMutex.withLock {
            dataStore.edit { prefs ->
                val current = decodeConnections(prefs[KEY_CONNECTIONS])
                if (current.none { it.id == connection.id }) {
                    Log.w(TAG, "updateConnection: no connection with id=${connection.id} — ignored")
                    return@edit
                }
                val next = current.map { if (it.id == connection.id) connection else it }
                prefs[KEY_CONNECTIONS] = encodeConnections(next)
                _connections.value = next
            }
        }
    }

    /**
     * Remove the connection with [id] from the list and delete its backing
     * EncryptedSharedPreferences file. If [id] is currently active, the
     * active pointer is cleared — callers are responsible for picking a new
     * active connection.
     *
     * The EncryptedSharedPreferences file is deleted via
     * [Context.deleteSharedPreferences], which is documented as API 24+ and
     * is safe on our minSdk 26. The legacy connection's file
     * ([Connection.LEGACY_TOKEN_STORE_KEY]) is deleted the same way — there's
     * nothing structurally special about it once the user explicitly asks
     * to remove connection 0.
     */
    suspend fun removeConnection(id: String) {
        writeMutex.withLock {
            var removed: Connection? = null
            dataStore.edit { prefs ->
                val current = decodeConnections(prefs[KEY_CONNECTIONS])
                removed = current.firstOrNull { it.id == id }
                val next = current.filterNot { it.id == id }
                prefs[KEY_CONNECTIONS] = encodeConnections(next)
                _connections.value = next

                if (prefs[KEY_ACTIVE_CONNECTION_ID] == id) {
                    prefs.remove(KEY_ACTIVE_CONNECTION_ID)
                    _activeConnectionId.value = null
                }
            }
            removed?.let { connection ->
                context?.let { ctx ->
                    try {
                        ctx.deleteSharedPreferences(connection.tokenStoreKey)
                    } catch (e: Exception) {
                        Log.w(
                            TAG,
                            "deleteSharedPreferences(${connection.tokenStoreKey}) failed: ${e.message}",
                        )
                    }
                }
            }
        }
    }

    suspend fun setActiveConnection(id: String) {
        writeMutex.withLock {
            dataStore.edit { prefs ->
                prefs[KEY_ACTIVE_CONNECTION_ID] = id
                _activeConnectionId.value = id
            }
        }
    }

    /**
     * Update just the `lastActiveSessionId` on the identified connection.
     * Called whenever the user picks a chat session so connection-switch can
     * restore the same session on re-selection. No-op if the connection
     * doesn't exist.
     */
    suspend fun setLastActiveSessionId(connectionId: String, sessionId: String?) {
        writeMutex.withLock {
            dataStore.edit { prefs ->
                val current = decodeConnections(prefs[KEY_CONNECTIONS])
                val target = current.firstOrNull { it.id == connectionId } ?: return@edit
                val next = current.map {
                    if (it.id == connectionId) target.copy(lastActiveSessionId = sessionId) else it
                }
                prefs[KEY_CONNECTIONS] = encodeConnections(next)
                _connections.value = next
            }
        }
    }

    /**
     * Stamp the identified connection with pairing metadata pulled out of an
     * `auth.ok` payload. No-op if the connection doesn't exist — callers are
     * responsible for ordering this after [addConnection].
     *
     * Both [pairedAtMillis] and [expiresAtMillis] are epoch milliseconds —
     * pass `System.currentTimeMillis()` for pairedAt and `expires_at * 1000`
     * for the seconds-based auth.ok payload field. `ConnectionsSettingsScreen`
     * assumes millis when rendering relative time; pass seconds here and
     * cards will always read as "Paired decades ago".
     */
    suspend fun markPaired(
        connectionId: String,
        pairedAtMillis: Long,
        transportHint: String?,
        expiresAtMillis: Long?,
    ) {
        writeMutex.withLock {
            dataStore.edit { prefs ->
                val current = decodeConnections(prefs[KEY_CONNECTIONS])
                val target = current.firstOrNull { it.id == connectionId } ?: return@edit
                val next = current.map {
                    if (it.id == connectionId) {
                        target.copy(
                            pairedAt = pairedAtMillis,
                            transportHint = transportHint,
                            expiresAt = expiresAtMillis,
                        )
                    } else {
                        it
                    }
                }
                prefs[KEY_CONNECTIONS] = encodeConnections(next)
                _connections.value = next
            }
        }
    }

    /**
     * One-shot legacy migration: if no connections are persisted yet, seed a
     * connection 0 pointing at [Connection.LEGACY_TOKEN_STORE_KEY] so the
     * existing paired install keeps working without a re-pair.
     *
     * Idempotent — subsequent calls after connection 0 is seeded (or after
     * the user has added real connections) are a no-op. Pass the legacy URL
     * / session values from whatever store currently holds them (e.g.,
     * [ConnectionViewModel]'s DataStore-backed URL preferences).
     */
    suspend fun migrateLegacyConnectionIfNeeded(
        legacyApiServerUrl: String?,
        legacyRelayUrl: String?,
        legacyLastSessionId: String?,
    ) {
        writeMutex.withLock {
            dataStore.edit { prefs ->
                val existing = decodeConnections(prefs[KEY_CONNECTIONS])
                if (existing.isNotEmpty()) {
                    // Already migrated or already has user-created connections.
                    return@edit
                }
                val apiUrl = legacyApiServerUrl ?: DEFAULT_API_URL
                val relayUrl = legacyRelayUrl ?: DEFAULT_RELAY_URL
                val id = java.util.UUID.randomUUID().toString()
                val seed = Connection(
                    id = id,
                    label = Connection.extractDefaultLabel(apiUrl),
                    apiServerUrl = apiUrl,
                    relayUrl = relayUrl,
                    tokenStoreKey = Connection.LEGACY_TOKEN_STORE_KEY,
                    pairedAt = null,
                    lastActiveSessionId = legacyLastSessionId,
                    transportHint = null,
                    expiresAt = null,
                )
                val next = listOf(seed)
                prefs[KEY_CONNECTIONS] = encodeConnections(next)
                prefs[KEY_ACTIVE_CONNECTION_ID] = id
                _connections.value = next
                _activeConnectionId.value = id
                Log.i(TAG, "migrateLegacyConnectionIfNeeded: seeded connection 0 id=$id apiUrl=$apiUrl")
            }
        }
    }

    // --- Encoding helpers ---------------------------------------------------

    private fun encodeConnections(list: List<Connection>): String =
        json.encodeToString(connectionListSerializer, list)

    private fun decodeConnections(raw: String?): List<Connection> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(connectionListSerializer, raw)
        } catch (e: Exception) {
            Log.w(TAG, "decodeConnections failed, returning empty list: ${e.message}")
            emptyList()
        }
    }

    companion object {
        private const val TAG = "ConnectionStore"

        private val KEY_CONNECTIONS = stringPreferencesKey("connections_v1")
        private val KEY_ACTIVE_CONNECTION_ID = stringPreferencesKey("active_connection_id")

        // Pre-rename DataStore keys — read once in init on first launch after
        // the rename, then wiped. See the init block above.
        private val KEY_LEGACY_PROFILES = stringPreferencesKey("profiles_v1")
        private val KEY_LEGACY_ACTIVE_PROFILE_ID = stringPreferencesKey("active_profile_id")

        // Match the defaults used by ConnectionViewModel so a seeded connection
        // from migrateLegacyConnectionIfNeeded() resolves to the same endpoints
        // a fresh install would.
        private const val DEFAULT_API_URL = "http://localhost:8642"
        private const val DEFAULT_RELAY_URL = "wss://localhost:8767"
    }
}
