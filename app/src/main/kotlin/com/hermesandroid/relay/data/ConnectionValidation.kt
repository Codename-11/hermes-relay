package com.hermesandroid.relay.data

import java.net.URI
import java.net.URISyntaxException

/**
 * Validation rules for user-editable connection fields. Kept as a standalone
 * object so the same rules fire in all save paths — inline dialog feedback,
 * post-pairing add-connection, and any future import path — without the VM,
 * the UI, and the data layer each re-implementing string checks.
 *
 * All validators return null on success or a short human-readable message
 * suitable for surfacing in an OutlinedTextField `supportingText` slot or a
 * Snackbar. Messages are intentionally concise — callers add context ("in
 * connection label", "in relay URL") if the surface needs it.
 */
object ConnectionValidation {

    const val LABEL_MAX_LEN: Int = 40

    /**
     * @return null when valid, else a message describing the problem.
     *         Callers should `.trim()` before persisting — this does not
     *         mutate the input.
     */
    fun validateLabel(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "Label can't be blank"
        if (trimmed.length > LABEL_MAX_LEN) return "Label too long (max $LABEL_MAX_LEN)"
        // Control characters (newlines, tabs, nul, etc.) would render badly
        // in chips and are almost certainly a paste-accident.
        if (trimmed.any { it.isISOControl() }) return "Label can't contain control characters"
        return null
    }

    /**
     * API server must be http:// or https:// with a host. The user pairs
     * before they can save a connection, so malformed URLs shouldn't reach
     * this path — but defense-in-depth against manual edits / restored
     * backups is worth the few lines.
     */
    fun validateApiServerUrl(raw: String): String? = validateUrl(
        raw = raw,
        allowedSchemes = setOf("http", "https"),
        kind = "API server URL",
    )

    /** Relay URL must be ws:// or wss:// with a host. */
    fun validateRelayUrl(raw: String): String? = validateUrl(
        raw = raw,
        allowedSchemes = setOf("ws", "wss"),
        kind = "relay URL",
    )

    /**
     * Catches the "added the same server twice" mistake. Matches when the
     * candidate's api + relay URLs exactly match an existing connection
     * (case-insensitive on scheme + host, per RFC 3986). [excludeId] skips
     * a specific connection so renames don't trip over their own entry.
     *
     * Deliberately lenient: two connections may share either URL alone (dev
     * that points API at prod + relay at a test box, say). Full exact-match
     * on both is the only blocked case.
     */
    fun findDuplicate(
        connections: List<Connection>,
        apiServerUrl: String,
        relayUrl: String,
        excludeId: String? = null,
    ): Connection? = connections.firstOrNull { c ->
        c.id != excludeId &&
            c.apiServerUrl.equals(apiServerUrl, ignoreCase = true) &&
            c.relayUrl.equals(relayUrl, ignoreCase = true)
    }

    private fun validateUrl(raw: String, allowedSchemes: Set<String>, kind: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "$kind can't be blank"
        val uri = try {
            URI(trimmed)
        } catch (_: URISyntaxException) {
            return "$kind is malformed"
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme == null || scheme !in allowedSchemes) {
            return "$kind must start with ${allowedSchemes.joinToString(" or ") { "$it://" }}"
        }
        if (uri.host.isNullOrBlank()) return "$kind has no host"
        val port = uri.port
        if (port != -1 && (port < 1 || port > 65535)) return "$kind has an invalid port"
        return null
    }
}
