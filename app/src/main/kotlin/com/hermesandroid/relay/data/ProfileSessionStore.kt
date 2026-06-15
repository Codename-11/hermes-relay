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
 * Which chat transport created (and can resume) a stored session.
 *
 * The two chat transports do NOT share session storage, so their ids are not
 * interchangeable on a non-default profile:
 *  - [GATEWAY] — the `/api/ws` tui_gateway path. `session.create`/`session.resume`
 *    bind the profile's own HERMES_HOME, so sessions live in that profile's
 *    `state.db`. Ids look like `YYYYMMDD_HHMMSS_<hex>`.
 *  - [SSE] — the api_server chat path (`/api/sessions/.../chat/stream`,
 *    `/v1/runs`). The api_server has no per-request profile scoping; it always
 *    persists to its launch `state.db`. Ids look like `api_<unixsecs>_<hex>`.
 *
 * Resuming an [SSE] id over the [GATEWAY] (which opens the profile DB) — or vice
 * versa — fails with "session not found" and silently forks a new session. So
 * each transport gets its own persisted slot, and a stored id is only ever
 * restored for the transport that can actually resume it.
 */
enum class SessionTransport(val key: String) {
    GATEWAY("gw"),
    SSE("sse");

    companion object {
        /**
         * Bucket a stored session id by the subsystem that created it — the
         * id's namespace is the server's own ground truth about which transport
         * can resume it, more reliable than re-deriving the resolved endpoint
         * (a turn can fall back from gateway to SSE per-turn).
         */
        fun forSessionId(sessionId: String): SessionTransport =
            if (sessionId.startsWith("api_")) SSE else GATEWAY

        /**
         * Bucket a resolved streaming endpoint. Only `"gateway"` resumes from
         * the per-profile DB; every SSE-family member (`"sessions"` /
         * `"completions"` / `"runs"`) rides the api_server's launch DB.
         */
        fun forEndpoint(resolvedEndpoint: String): SessionTransport =
            if (resolvedEndpoint == "gateway") GATEWAY else SSE
    }
}

/**
 * Per-connection, per-Hermes-profile, per-transport last active chat session.
 *
 * This is intentionally separate from [ProfileSelectionStore]. Selection says
 * which agent is active; this store says which chat session belongs to that
 * agent on that connection. Null profile name is the explicit Server default
 * context.
 *
 * **Transport dimension (v1.0.0).** The slot is keyed by [SessionTransport] too,
 * because a gateway session and an api_server (SSE) session are stored in
 * different databases and cannot be cross-resumed on a non-default profile.
 * Keying by transport keeps the two from clobbering one slot and guarantees a
 * restored id is always resumable by the transport asking for it. The key shape
 * changed in this release, so pre-existing (untransported) slots are not read —
 * a one-time drop of the "last session" pointer that also clears the exact stale
 * cross-transport ids that caused mid-conversation forks.
 */
class ProfileSessionStore(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.profileSessionsDataStore)

    companion object {
        private const val PREFIX = "profile_session__"

        private fun keyName(
            connectionId: String,
            profileName: String?,
            transport: SessionTransport,
        ): String =
            "$PREFIX${connectionId}__${AgentDisplay.profileSessionKey(profileName)}__${transport.key}"

        private fun keyFor(
            connectionId: String,
            profileName: String?,
            transport: SessionTransport,
        ) = stringPreferencesKey(keyName(connectionId, profileName, transport))

        private fun connectionPrefix(connectionId: String): String =
            "$PREFIX${connectionId}__"
    }

    suspend fun setSessionId(
        connectionId: String,
        profileName: String?,
        transport: SessionTransport,
        sessionId: String?,
    ) {
        dataStore.edit { prefs ->
            val key = keyFor(connectionId, profileName, transport)
            if (sessionId.isNullOrBlank()) {
                prefs.remove(key)
            } else {
                prefs[key] = sessionId
            }
        }
    }

    fun sessionIdFlow(
        connectionId: String,
        profileName: String?,
        transport: SessionTransport,
    ): Flow<String?> {
        val key = keyFor(connectionId, profileName, transport)
        return dataStore.data.map { prefs -> prefs[key] }
    }

    suspend fun clearConnection(connectionId: String) {
        val prefix = connectionPrefix(connectionId)
        dataStore.edit { prefs ->
            prefs.asMap().keys
                .filter { it.name.startsWith(prefix) }
                .forEach { prefs.remove(it) }
        }
    }

    suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.clear()
        }
    }
}

internal val Context.profileSessionsDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "profile_sessions")
