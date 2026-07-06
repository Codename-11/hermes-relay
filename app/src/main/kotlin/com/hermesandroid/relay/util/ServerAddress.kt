package com.hermesandroid.relay.util

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Validation + non-throwing parsing for user-entered Hermes server addresses.
 *
 * Why this exists: okhttp's `Request.Builder.url(String)` / `String.toHttpUrl()`
 * **throw** `IllegalArgumentException` (e.g. `Invalid URL host: "..."`) on a
 * malformed value. When such a throw escapes a `suspend` lambda running on a
 * `Dispatchers.Main` coroutine, it is uncaught and the app force-closes — the
 * crash class behind issue #131 (a UI label / docs line pasted into the
 * dashboard-URL field reached the request builder unvalidated). The non-throwing
 * twin `toHttpUrlOrNull()` returns `null` instead of throwing.
 *
 * This object is the single place that turns a possibly-bad address string into
 * a typed `HttpUrl?` / error message, so neither the connection-setup UI
 * (Layer 1 — inline validation) nor a request builder (Layer 2 — crash guard)
 * ever hands raw junk to the throwing okhttp API.
 */
object ServerAddress {

    private val SCHEME_REGEX = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

    /**
     * **Strict** parse — [raw] must already carry an `http://` / `https://`
     * scheme. Returns the parsed [HttpUrl], or `null` when the value is blank,
     * has no scheme, has a non-http(s) scheme, or has a malformed host. NEVER
     * throws.
     *
     * This is the request-builder guard primitive: a *stored* base URL is
     * always scheme-bearing (the save path normalizes bare hosts to `http://`
     * first), so resolving it here instead of via okhttp's throwing
     * `url(String)` turns junk into a clean `null` — never a crash.
     */
    fun parse(raw: String?): HttpUrl? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (!SCHEME_REGEX.containsMatchIn(trimmed)) return null
        return trimmed.toHttpUrlOrNull()?.takeIf { it.scheme == "http" || it.scheme == "https" }
    }

    /**
     * **Lenient** parse for hand-typed setup input — a bare host gets `http://`
     * prepended (mirrors
     * [com.hermesandroid.relay.data.Connection.normalizeApiUrlInput]) before
     * parsing, so `192.168.1.10`, `localhost`, and `host:port` validate.
     * Returns `null` when the value can't become a valid http(s) URL — e.g. text
     * with spaces like `"Manage sign-in and admin screens"`. NEVER throws.
     */
    fun parseUserInput(raw: String?): HttpUrl? {
        val trimmed = raw?.trim()?.trimEnd('/').orEmpty()
        if (trimmed.isEmpty()) return null
        val withScheme = if (SCHEME_REGEX.containsMatchIn(trimmed)) trimmed else "http://$trimmed"
        return parse(withScheme)
    }

    /** True when [raw] forms a valid http(s) address once normalized. Blank → false. */
    fun isValidUserInput(raw: String?): Boolean = parseUserInput(raw) != null

    /**
     * Inline error for a server-URL / host text field, or `null` when the value
     * is acceptable. Blank returns `null` so callers can gate required-ness
     * separately (the dashboard-URL field is optional). A value that can't
     * become a valid http(s) URL — text with spaces, control chars, no host —
     * returns a short, user-facing message.
     */
    /**
     * Advisory for a server address whose host is loopback / any-interface —
     * `localhost`, `127.x.x.x`, `::1`, `0.0.0.0`. Such an address works in a
     * browser *on the server* but can never reach the server from the phone,
     * a recurring source of "Testing API connection" dead-ends. Accepts the
     * same scheme-less input [parseUserInput] normalizes. Returns `null` for
     * blank, unparseable, or non-loopback addresses. NEVER throws.
     *
     * Pure helper only — not yet surfaced anywhere; UI wiring is a follow-up.
     */
    fun loopbackHostWarning(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        // Bare "::1" never parses without brackets — normalize it directly.
        val host = parseUserInput(trimmed)?.host?.lowercase()
            ?: trimmed.removePrefix("[").removeSuffix("]").lowercase().takeIf { it == "::1" }
            ?: return null
        val loopback = host == "localhost" || host == "::1" || host == "0.0.0.0" || isLoopbackIpv4(host)
        return if (loopback) {
            "On your phone, localhost points at the phone itself — use the server's LAN IP or Tailscale address."
        } else {
            null
        }
    }

    private fun isLoopbackIpv4(host: String): Boolean {
        val labels = host.split('.')
        val parts = labels.mapNotNull { it.toIntOrNull() }
        return labels.size == 4 && parts.size == 4 && parts[0] == 127
    }

    fun fieldError(raw: String, fieldLabel: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return if (isValidUserInput(trimmed)) {
            null
        } else {
            "$fieldLabel doesn't look like a valid address — use a host or http(s):// URL"
        }
    }
}
