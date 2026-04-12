package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * User-tunable bridge mode preferences + persisted activity log.
 *
 * Phase 3 Wave 1 — owned by Agent bridge-ui (`bridge-screen-ui`).
 *
 * - [masterEnabled] — the headline "Allow Agent Control" switch on BridgeScreen.
 *   Compose-layer gate only; the real safety fence is that the user must also
 *   enable `HermesAccessibilityService` in Android Settings (which is the
 *   Tier-5 "honest" trust boundary Google cares about). Persisting it here lets
 *   us survive restarts and lets Agent accessibility's `HermesAccessibilityService` query
 *   the same source of truth without needing its own DataStore file.
 *
 * - [activityLog] — rolling window of recent [BridgeActivityEntry] rows that
 *   back the Activity Log card. Capped at [MAX_LOG_ENTRIES] on every append
 *   so we don't grow the DataStore preferences file unbounded (this is a Prefs
 *   datastore, not Room — large blob values hurt commit latency). Serialized
 *   as a single JSON array string under one key so the whole read/update is
 *   atomic. That's cheaper than Proto DataStore for a cap this small and
 *   matches the `VoicePreferences.kt` / `MediaSettings.kt` style already in
 *   the tree.
 *
 * The log schema intentionally does NOT carry the screenshot bytes — those
 * live in `MediaRegistry` on the relay side and are fetched via
 * `RelayHttpClient.fetchMedia(token)` when the user expands the row. Storing
 * `thumbnailToken: String?` as an opaque reference keeps DataStore small and
 * reuses the existing MediaRegistry LRU-cap + FileProvider cache story.
 */
@Serializable
data class BridgeActivityEntry(
    /** Epoch millis when the command was received from the relay. */
    val timestampMs: Long,
    /** Short method name — `tap`, `tap_text`, `type`, `swipe`, `read_screen`, etc. */
    val method: String,
    /** Free-form one-line summary of the args — `"(540, 1200)"`, `"send"`, `"Chrome"`. */
    val summary: String,
    /** Execution status — Pending, Success, Failed, Blocked (by Tier-5 safety rails). */
    val status: BridgeActivityStatus,
    /** Optional longer result text — set on Success / Failed to show in the expanded row. */
    val resultText: String? = null,
    /**
     * Optional screenshot MediaRegistry token (`hermes-relay://<token>` or the
     * raw token stem). The UI resolves this through the existing InboundAttachmentCard
     * pipeline — bridge-ui deliberately does not invent a second thumbnail cache.
     * Null for commands that don't carry a screenshot.
     */
    val thumbnailToken: String? = null,
    /** Unique ID used as the LazyColumn key — lets Compose animate inserts cleanly. */
    val id: String,
)

@Serializable
enum class BridgeActivityStatus { Pending, Success, Failed, Blocked }

data class BridgeSettings(
    val masterEnabled: Boolean = false,
)

class BridgePreferencesRepository(private val context: Context) {

    companion object {
        private val KEY_MASTER_ENABLED = booleanPreferencesKey("bridge_master_enabled")
        private val KEY_ACTIVITY_LOG = stringPreferencesKey("bridge_activity_log")

        /** Hard cap on persisted entries. See file-level KDoc for rationale. */
        const val MAX_LOG_ENTRIES = 100
        const val DEFAULT_MASTER_ENABLED = false
    }

    // Lenient JSON — ignore unknown keys so we can evolve the schema without
    // breaking installed users on app upgrade, mirroring how PairingPreferences
    // and MediaSettings handle forward-compat.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val settings: Flow<BridgeSettings> = context.relayDataStore.data.map { prefs ->
        BridgeSettings(
            masterEnabled = prefs[KEY_MASTER_ENABLED] ?: DEFAULT_MASTER_ENABLED,
        )
    }

    val activityLog: Flow<List<BridgeActivityEntry>> = context.relayDataStore.data.map { prefs ->
        val raw = prefs[KEY_ACTIVITY_LOG] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<BridgeActivityEntry>>(raw) }
            .getOrDefault(emptyList())
    }

    suspend fun setMasterEnabled(enabled: Boolean) {
        context.relayDataStore.edit { it[KEY_MASTER_ENABLED] = enabled }
    }

    /**
     * Prepend a new entry and trim to [MAX_LOG_ENTRIES]. Idempotent on
     * duplicate ids — if an entry with the same id already exists we replace
     * it in-place (used when a Pending entry transitions to Success/Failed).
     */
    suspend fun appendEntry(entry: BridgeActivityEntry) {
        context.relayDataStore.edit { prefs ->
            val current = prefs[KEY_ACTIVITY_LOG]?.let {
                runCatching { json.decodeFromString<List<BridgeActivityEntry>>(it) }
                    .getOrDefault(emptyList())
            } ?: emptyList()
            val deduped = current.filterNot { it.id == entry.id }
            val updated = (listOf(entry) + deduped).take(MAX_LOG_ENTRIES)
            prefs[KEY_ACTIVITY_LOG] = json.encodeToString(updated)
        }
    }

    suspend fun clearLog() {
        context.relayDataStore.edit { prefs ->
            prefs[KEY_ACTIVITY_LOG] = json.encodeToString(emptyList<BridgeActivityEntry>())
        }
    }
}
