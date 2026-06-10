package com.hermesandroid.relay.data

import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
data class DashboardConnectionStatus(
    val checkedAtMillis: Long? = null,
    val reachable: Boolean = false,
    val authRequired: Boolean? = null,
    val authProviders: List<String> = emptyList(),
    val authenticated: Boolean? = null,
    val authProvider: String? = null,
    val gatewayTicketAvailable: Boolean? = null,
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
 * free to mean upstream Hermes profiles: separate host-side Hermes homes
 * under `~/.hermes/profiles/<name>/`, each with its own config, SOUL, memory,
 * sessions, skills, cron, and provider state.
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
    /**
     * Candidate host routes for this saved Hermes server. Standard setup
     * stores at least one candidate here so API, dashboard, voice, and Relay
     * helpers can follow LAN/Tailscale/public handoff before Relay pairing.
     * Older installs and legacy serialized records default to an empty list.
     */
    val routeCandidates: List<EndpointCandidate> = emptyList(),
    /** Optional user preference such as "lan" or "tailscale"; null means Auto. */
    val preferredRouteRole: String? = null,
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

        fun deriveDefaultRelayUrl(
            apiServerUrl: String,
            relayPort: Int = 8767,
        ): String? {
            val trimmed = apiServerUrl.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null

            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
            val scheme = when (uri.scheme?.lowercase()) {
                "http" -> "ws"
                "https" -> "wss"
                else -> return null
            }
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            val hostPart = if (host.contains(":") && !host.startsWith("[")) {
                "[$host]"
            } else {
                host
            }
            return "$scheme://$hostPart:$relayPort"
        }

        fun buildRouteCandidates(
            apiServerUrl: String,
            relayUrl: String,
            extraApiUrls: List<Pair<String, String>> = emptyList(),
        ): List<EndpointCandidate> {
            val routes = buildList {
                endpointCandidateFromApiUrl(
                    role = inferRouteRole(apiServerUrl),
                    priority = 0,
                    apiServerUrl = apiServerUrl,
                    relayUrl = relayUrl.takeIf { it.isNotBlank() }
                        ?: deriveDefaultRelayUrl(apiServerUrl).orEmpty(),
                )?.let(::add)

                extraApiUrls
                    .map { it.first.trim() to it.second.trim() }
                    .filter { (_, url) -> url.isNotBlank() }
                    .forEachIndexed { index, (role, url) ->
                        endpointCandidateFromApiUrl(
                            role = role.ifBlank { inferRouteRole(url) },
                            priority = index + 1,
                            apiServerUrl = url,
                            relayUrl = deriveDefaultRelayUrl(url).orEmpty(),
                        )?.let(::add)
                    }
            }

            return routes
                .distinctBy {
                    "${it.role.lowercase()}|${it.api.host.lowercase()}:${it.api.port}"
                }
                .sortedWith(compareBy<EndpointCandidate> { it.priority }.thenBy { it.role })
        }

        fun endpointCandidateFromApiUrl(
            role: String,
            priority: Int,
            apiServerUrl: String,
            relayUrl: String,
        ): EndpointCandidate? {
            val uri = runCatching { URI(apiServerUrl.trim().trimEnd('/')) }.getOrNull()
                ?: return null
            val scheme = uri.scheme?.lowercase()
            val tls = when (scheme) {
                "http" -> false
                "https" -> true
                else -> return null
            }
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            val port = if (uri.port > 0) uri.port else 8642
            val resolvedRelayUrl = relayUrl.trim().takeIf { it.isNotBlank() }
                ?: deriveDefaultRelayUrl(apiServerUrl)
                ?: return null
            val transportHint = when {
                resolvedRelayUrl.startsWith("wss://", ignoreCase = true) -> "wss"
                resolvedRelayUrl.startsWith("ws://", ignoreCase = true) -> "ws"
                else -> null
            }
            return EndpointCandidate(
                role = role.ifBlank { inferRouteRole(apiServerUrl) },
                priority = priority,
                api = ApiEndpoint(host = host, port = port, tls = tls),
                relay = RelayEndpoint(url = resolvedRelayUrl, transportHint = transportHint),
            )
        }

        fun inferRouteRole(apiServerUrl: String): String {
            val host = runCatching { URI(apiServerUrl.trim().trimEnd('/')).host }
                .getOrNull()
                ?.lowercase()
                ?: return "custom"
            return when {
                host.endsWith(".ts.net") || isTailscaleIpv4(host) -> "tailscale"
                host == "localhost" ||
                    host == "127.0.0.1" ||
                    host == "::1" ||
                    isPrivateLanIpv4(host) -> "lan"
                else -> "public"
            }
        }

        private fun isTailscaleIpv4(host: String): Boolean {
            val parts = host.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.size != 4) return false
            return parts[0] == 100 && parts[1] in 64..127
        }

        private fun isPrivateLanIpv4(host: String): Boolean {
            val parts = host.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.size != 4) return false
            return when {
                parts[0] == 10 -> true
                parts[0] == 172 && parts[1] in 16..31 -> true
                parts[0] == 192 && parts[1] == 168 -> true
                parts[0] == 169 && parts[1] == 254 -> true
                else -> false
            }
        }
    }
}
