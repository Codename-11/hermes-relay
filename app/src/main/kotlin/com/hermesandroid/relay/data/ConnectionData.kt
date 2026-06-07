package com.hermesandroid.relay.data

import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
data class DashboardConnectionStatus(
    val checkedAtMillis: Long? = null,
    val reachable: Boolean = false,
    val authRequired: Boolean? = null,
    val authProviders: List<String> = emptyList(),
    val message: String? = null,
)

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
    /**
     * Hermes dashboard/admin URL. Dashboard management features use this
     * separately from the relay pairing channel; a blank/null value means
     * "derive from [apiServerUrl] using the conventional same-host :9119".
     */
    val dashboardUrl: String? = null,
    val dashboardAuthRequired: Boolean? = null,
    val dashboardAuthProviders: List<String> = emptyList(),
    val dashboardLastStatus: DashboardConnectionStatus? = null,
    /** Epoch milliseconds. Pass `System.currentTimeMillis()`; do not pass seconds. */
    val pairedAt: Long? = null,
    val lastActiveSessionId: String? = null,
    val transportHint: String? = null,
    /** Epoch milliseconds. The auth.ok `expires_at` field is seconds — multiply by 1000 at the call site. */
    val expiresAt: Long? = null,
) {
    val resolvedDashboardUrl: String
        get() = dashboardUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: deriveDefaultDashboardUrl(apiServerUrl).orEmpty()

    companion object {
        /**
         * The pre-multi-connection EncryptedSharedPreferences filename. Matches
         * [com.hermesandroid.relay.auth.KeystoreTokenStore]'s original
         * hardcoded `PREFS_NAME`. Connection 0 re-uses this file as-is so the
         * existing paired device keeps working across the upgrade.
         */
        const val LEGACY_TOKEN_STORE_KEY: String = "hermes_companion_auth_hw"

        const val DEFAULT_DASHBOARD_PORT: Int = 9119

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

        fun deriveDefaultDashboardUrl(
            apiServerUrl: String,
            dashboardPort: Int = DEFAULT_DASHBOARD_PORT,
        ): String? {
            val trimmed = apiServerUrl.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null

            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
            val scheme = when (uri.scheme?.lowercase()) {
                "http" -> "http"
                "https" -> "https"
                else -> return null
            }
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            val hostPart = if (host.contains(":") && !host.startsWith("[")) {
                "[$host]"
            } else {
                host
            }
            return "$scheme://$hostPart:$dashboardPort"
        }

        fun isAutoManagedDashboardUrl(dashboardUrl: String?, apiServerUrl: String): Boolean {
            val trimmed = dashboardUrl?.trim()?.trimEnd('/').orEmpty()
            if (trimmed.isEmpty()) return true
            val derived = deriveDefaultDashboardUrl(apiServerUrl) ?: return false
            return trimmed.equals(derived, ignoreCase = true)
        }
    }
}
