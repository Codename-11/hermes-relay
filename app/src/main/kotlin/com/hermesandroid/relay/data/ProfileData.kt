package com.hermesandroid.relay.data

import kotlinx.serialization.Serializable
import java.net.URI

/**
 * A "profile" = a distinct Hermes server connection the app can switch between.
 *
 * Each profile has its own:
 *  - API server URL + relay URL
 *  - EncryptedSharedPreferences file (keyed by [tokenStoreKey]) holding the
 *    session token, device ID, API key, and paired-session metadata.
 *  - Cert pin (already host-keyed in [com.hermesandroid.relay.auth.CertPinStore]
 *    so that store is intrinsically per-profile as long as hosts differ).
 *  - Last-active session ID (to restore the open chat on profile switch).
 *  - Transport hint + session expiry mirrored from the server's `auth.ok`
 *    payload so the profile list can show "expires in 3d" without cracking
 *    open the token store.
 *
 * Switching profile is a HEAVY context swap — caller is expected to tear down
 * the current [com.hermesandroid.relay.network.ConnectionManager],
 * [com.hermesandroid.relay.auth.AuthManager], and API client, then construct
 * fresh ones pointed at the new profile's `tokenStoreKey`.
 *
 * **Zero-disruption migration:** the legacy pre-multi-profile install kept all
 * of its auth state in a single EncryptedSharedPreferences file named
 * [LEGACY_TOKEN_STORE_KEY]. On first launch after the multi-profile upgrade,
 * [ProfileStore.migrateLegacyProfileIfNeeded] seeds profile 0 pointing at that
 * existing file — no token migration, no re-pair.
 */
@Serializable
data class Profile(
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
         * The pre-multi-profile EncryptedSharedPreferences filename. Matches
         * [com.hermesandroid.relay.auth.KeystoreTokenStore]'s original
         * hardcoded `PREFS_NAME`. Profile 0 re-uses this file as-is so the
         * existing paired device keeps working across the upgrade.
         */
        const val LEGACY_TOKEN_STORE_KEY: String = "hermes_companion_auth_hw"

        /**
         * Derive a stable per-profile EncryptedSharedPreferences filename from
         * a profile UUID. Trimmed to the first 8 characters of the UUID so
         * the on-disk filename stays short and human-diffable, which matters
         * because [android.content.Context.deleteSharedPreferences] only
         * accepts a filename string.
         */
        fun buildTokenStoreKey(id: String): String = "hermes_auth_${id.take(8)}"

        /**
         * Human-friendly default label for a newly-added profile. Uses the
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
