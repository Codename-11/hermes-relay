package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed preferences for the pairing + security overhaul introduced
 * 2026-04-11.
 *
 * Stores:
 *  - `pair_ttl_seconds` — user's last-selected session TTL (0 = never). Used
 *    to preselect the [SessionTtlPickerDialog] on the next pair attempt.
 *  - `insecure_ack_seen` — whether the user has dismissed the one-time
 *    [InsecureConnectionAckDialog] explaining the threat model of ws://.
 *  - `insecure_reason` — the reason the user selected in that dialog. Used
 *    for display only (drives the transport security badge label) — never
 *    gating. Empty string when unset.
 *  - `tofu_pins` — stringified `host:port=sha256:hex|host:port=sha256:hex`
 *    map of TOFU-pinned certificate fingerprints. Simple delimited encoding
 *    keeps us out of `Gson`/full-object serialization while still handling
 *    multiple hosts.
 *
 * Lives alongside [FeatureFlags] on the same DataStore instance
 * (`relay_settings`) so all settings share one file.
 */
object PairingPreferences {

    // 30 days — same default the TTL picker uses for wss/Tailscale setups.
    const val DEFAULT_TTL_SECONDS: Long = 30L * 24L * 60L * 60L

    // Sentinel — matches the wire contract ("never expire" when ttl_seconds
    // is 0 or missing).
    const val TTL_NEVER: Long = 0L

    private val KEY_PAIR_TTL_SECONDS = longPreferencesKey("pair_ttl_seconds")
    private val KEY_INSECURE_ACK_SEEN = booleanPreferencesKey("insecure_ack_seen")
    private val KEY_INSECURE_REASON = stringPreferencesKey("insecure_reason")
    private val KEY_TOFU_PINS = stringPreferencesKey("tofu_pins")

    // --- Pair TTL -----------------------------------------------------------

    /**
     * Observe the user's last-selected TTL (epoch-seconds delta). Defaults to
     * [DEFAULT_TTL_SECONDS] on first run.
     *
     * Callers that need to preselect the [SessionTtlPickerDialog] should use
     * this. A value of [TTL_NEVER] means "never expire".
     */
    fun pairTtlSeconds(context: Context): Flow<Long> =
        context.relayDataStore.data.map { prefs ->
            prefs[KEY_PAIR_TTL_SECONDS] ?: DEFAULT_TTL_SECONDS
        }

    suspend fun getPairTtlSeconds(context: Context): Long =
        pairTtlSeconds(context).first()

    suspend fun setPairTtlSeconds(context: Context, ttlSeconds: Long) {
        context.relayDataStore.edit { prefs ->
            prefs[KEY_PAIR_TTL_SECONDS] = ttlSeconds
        }
    }

    // --- Insecure acknowledgment -------------------------------------------

    fun insecureAckSeen(context: Context): Flow<Boolean> =
        context.relayDataStore.data.map { it[KEY_INSECURE_ACK_SEEN] ?: false }

    suspend fun setInsecureAckSeen(context: Context, seen: Boolean) {
        context.relayDataStore.edit { it[KEY_INSECURE_ACK_SEEN] = seen }
    }

    /**
     * Reason the user selected when they flipped insecure mode on.
     *
     * Expected values:
     *  - `"lan_only"`      — "LAN only (trusted network)"
     *  - `"tailscale_vpn"` — "Tailscale or VPN"
     *  - `"local_dev"`     — "Local development only"
     *  - `""`              — not yet acknowledged
     *
     * Used for display in [TransportSecurityBadge] — never gates behavior.
     */
    fun insecureReason(context: Context): Flow<String> =
        context.relayDataStore.data.map { it[KEY_INSECURE_REASON] ?: "" }

    suspend fun setInsecureReason(context: Context, reason: String) {
        context.relayDataStore.edit { it[KEY_INSECURE_REASON] = reason }
    }

    // --- TOFU pins ----------------------------------------------------------
    //
    // Stored as a compact `host:port=fingerprint|host:port=fingerprint` string
    // so we can handle multiple hosts without pulling in json-schema or
    // bumping to Proto DataStore. Hosts are normalized lowercase; fingerprints
    // are OkHttp CertificatePinner-compatible (`sha256/<base64>`).

    fun tofuPins(context: Context): Flow<Map<String, String>> =
        context.relayDataStore.data.map { prefs ->
            decodePins(prefs[KEY_TOFU_PINS] ?: "")
        }

    suspend fun getTofuPins(context: Context): Map<String, String> =
        tofuPins(context).first()

    suspend fun setTofuPin(context: Context, hostPort: String, pin: String) {
        val normalized = hostPort.lowercase()
        context.relayDataStore.edit { prefs ->
            val current = decodePins(prefs[KEY_TOFU_PINS] ?: "").toMutableMap()
            current[normalized] = pin
            prefs[KEY_TOFU_PINS] = encodePins(current)
        }
    }

    suspend fun removeTofuPin(context: Context, hostPort: String) {
        val normalized = hostPort.lowercase()
        context.relayDataStore.edit { prefs ->
            val current = decodePins(prefs[KEY_TOFU_PINS] ?: "").toMutableMap()
            current.remove(normalized)
            prefs[KEY_TOFU_PINS] = encodePins(current)
        }
    }

    private fun decodePins(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split('|')
            .mapNotNull { entry ->
                val idx = entry.indexOf('=')
                if (idx <= 0 || idx == entry.lastIndex) null
                else entry.substring(0, idx) to entry.substring(idx + 1)
            }
            .toMap()
    }

    private fun encodePins(pins: Map<String, String>): String =
        pins.entries.joinToString("|") { (host, pin) -> "$host=$pin" }
}
