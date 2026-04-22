package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

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
    private val KEY_ALL_INSECURE_PAIR_ACK_SEEN =
        booleanPreferencesKey("all_insecure_pair_ack_seen")

    /**
     * Prefix for per-device endpoint-candidate keys. Full key is
     * `device_endpoints:<deviceId>`; the value is a JSON-encoded
     * `List<EndpointCandidate>` (ADR 24, 2026-04-19). One key per paired
     * device so the phone can store + retrieve multi-endpoint pairing
     * candidates for each host without multiplexing into a single blob.
     */
    private const val KEY_DEVICE_ENDPOINTS_PREFIX = "device_endpoints:"

    private val endpointJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val endpointListSerializer = ListSerializer(EndpointCandidate.serializer())

    private fun deviceEndpointsKey(deviceId: String) =
        stringPreferencesKey("$KEY_DEVICE_ENDPOINTS_PREFIX$deviceId")

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
     * Per-install acknowledgment that the user understands the implications of
     * pairing to a QR where *every* endpoint candidate is plain text
     * (`ws://` / `http://` with no secure Tailscale/wss fallback — the
     * [TransportSecurityState.AllInsecure] case).
     *
     * Gates the Pair button on the wizard's Confirm step only for the absolute-
     * boundary AllInsecure scenario. Mixed pairings (any secure route present)
     * are NOT gated — the app auto-falls back to the secure one, so the
     * existing amber advisory is sufficient. Once the user has acknowledged
     * once on this install the gate is removed for all future AllInsecure
     * pairs — matches the precedent set by [insecureAckSeen] for the
     * per-install insecure-mode dialog.
     */
    fun allInsecurePairAckSeen(context: Context): Flow<Boolean> =
        context.relayDataStore.data.map { it[KEY_ALL_INSECURE_PAIR_ACK_SEEN] ?: false }

    suspend fun setAllInsecurePairAckSeen(context: Context, seen: Boolean) {
        context.relayDataStore.edit { it[KEY_ALL_INSECURE_PAIR_ACK_SEEN] = seen }
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

    // --- Per-device endpoint candidates (ADR 24) ---------------------------
    //
    // Multi-endpoint pairing carries an ordered list of API+relay candidates
    // (LAN, Tailscale, public, custom-VPN, ...). The phone persists the list
    // per-device so the reachability-probe + network-aware switch (Kt-Probe)
    // can pick the best candidate on every connect without re-reading the
    // original QR.
    //
    // Storage shape: one DataStore string key per deviceId, value is the
    // JSON-encoded `List<EndpointCandidate>`. JSON keeps us flexible on
    // schema growth without touching the DataStore key layout (e.g. future
    // `weight`, `region`, `last_successful_at` per-candidate fields land as
    // new JSON properties, not new preference keys).

    /**
     * Persist the ordered endpoint-candidate list for [deviceId]. Overwrites
     * any previously-stored list for that device. Encodes as JSON so future
     * schema fields land cleanly without a migration.
     */
    suspend fun setDeviceEndpoints(
        context: Context,
        deviceId: String,
        endpoints: List<EndpointCandidate>,
    ) {
        val encoded = endpointJson.encodeToString(endpointListSerializer, endpoints)
        context.relayDataStore.edit { prefs ->
            prefs[deviceEndpointsKey(deviceId)] = encoded
        }
    }

    /**
     * Observe the stored endpoint-candidate list for [deviceId]. Emits an
     * empty list when none has been persisted yet (first-pair / pre-ADR-24
     * legacy devices) or when decoding fails (forward-compat safety net —
     * a bad blob shouldn't crash the reachability probe).
     */
    fun getDeviceEndpoints(context: Context, deviceId: String): Flow<List<EndpointCandidate>> =
        context.relayDataStore.data.map { prefs ->
            val raw = prefs[deviceEndpointsKey(deviceId)] ?: return@map emptyList()
            try {
                endpointJson.decodeFromString(endpointListSerializer, raw)
            } catch (_: Exception) {
                emptyList()
            }
        }

    /**
     * Remove the stored endpoint list for [deviceId]. Used when a device is
     * revoked / re-paired and its old candidate list is no longer trusted.
     * No-op if no record exists.
     */
    suspend fun removeDeviceEndpoints(context: Context, deviceId: String) {
        context.relayDataStore.edit { prefs ->
            prefs.remove(deviceEndpointsKey(deviceId))
        }
    }
}
