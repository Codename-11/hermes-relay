package com.hermesandroid.relay.data

import kotlinx.serialization.Serializable
import java.net.URI

/**
 * A "connection" = a distinct Hermes server connection the app can switch between.
 *
 * Each connection has its own:
 *  - API server URL + relay URL
 *  - EncryptedSharedPreferences file (keyed by [tokenStoreKey]) holding the
 *    session token, device ID, API key, and paired-session metadata.
 *  - Cert pin (already host-keyed in [com.hermesandroid.relay.auth.CertPinStore]
 *    so that store is intrinsically per-connection as long as hosts differ).
 *  - Last-active session ID (to restore the open chat on connection switch).
 *  - Transport hint + session expiry mirrored from the server's `auth.ok`
 *    payload so the connection list can show "expires in 3d" without cracking
 *    open the token store.
 *
 * Switching connection is a HEAVY context swap — caller is expected to tear down
 * the current [com.hermesandroid.relay.network.ConnectionManager],
 * [com.hermesandroid.relay.auth.AuthManager], and API client, then construct
 * fresh ones pointed at the new connection's `tokenStoreKey`.
 *
 * **Zero-disruption migration:** the legacy pre-multi-connection install kept
 * all of its auth state in a single EncryptedSharedPreferences file named
 * [LEGACY_TOKEN_STORE_KEY]. On first launch after the multi-connection upgrade,
 * [ConnectionStore.migrateLegacyConnectionIfNeeded] seeds connection 0 pointing
 * at that existing file — no token migration, no re-pair.
 *
 * **Terminology note (2026-04-18):** earlier drafts of this feature called the
 * concept "Profile". Renamed to [Connection] so that the term "Profile" is
 * free to mean what Hermes's server config means by it (agent profiles —
 * name + model + description defined under `agent.profiles` in config.yaml).
 * A follow-up pass will introduce the new `Profile` concept on top.
 */
@Serializable
data class Connection(
    val id: String,
    val label: String,
    val apiServerUrl: String,
    val relayUrl: String,
    val tokenStoreKey: String,
    /** Epoch milliseconds. Pass `System.currentTimeMillis()`; do not pass seconds. */
    val pairedAt: Long? = null,
    val lastActiveSessionId: String? = null,
    val transportHint: String? = null,
    /** Epoch milliseconds. The auth.ok `expires_at` field is seconds — multiply by 1000 at the call site. */
    val expiresAt: Long? = null,
) {
    companion object {
        /**
         * The pre-multi-connection EncryptedSharedPreferences filename. Matches
         * [com.hermesandroid.relay.auth.KeystoreTokenStore]'s original
         * hardcoded `PREFS_NAME`. Connection 0 re-uses this file as-is so the
         * existing paired device keeps working across the upgrade.
         */
        const val LEGACY_TOKEN_STORE_KEY: String = "hermes_companion_auth_hw"

        /**
         * Derive a stable per-connection EncryptedSharedPreferences filename
         * from a connection UUID. Trimmed to the first 8 characters of the
         * UUID so the on-disk filename stays short and human-diffable, which
         * matters because [android.content.Context.deleteSharedPreferences]
         * only accepts a filename string.
         */
        fun buildTokenStoreKey(id: String): String = "hermes_auth_${id.take(8)}"

        /**
         * Human-friendly default label for a newly-added connection. Uses the
         * hostname of the API server URL so "http://192.168.1.10:8642" becomes
         * "192.168.1.10". Falls back to the raw URL if parsing fails (e.g.,
         * user typed a malformed value — better to show something recognizable
         * than to crash).
         */
        fun extractDefaultLabel(apiServerUrl: String): String {
            return try {
                URI(apiServerUrl).host ?: apiServerUrl
            } catch (_: Exception) {
                apiServerUrl
            }
        }
    }
}
