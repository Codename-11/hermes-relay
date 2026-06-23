package com.hermesandroid.relay.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DiagnosticCategory(val label: String) {
    Api("API"),
    Relay("Relay"),
    Session("Session"),
    Voice("Voice"),
    Endpoint("Route"),
    Auth("Auth"),
}

enum class DiagnosticSeverity {
    Info,
    Warning,
    Error,
}

data class DiagnosticLogEntry(
    val timestampMs: Long,
    val category: DiagnosticCategory,
    val severity: DiagnosticSeverity,
    val title: String,
    val detail: String? = null,
    val endpointRole: String? = null,
    val url: String? = null,
    val elapsedMs: Long? = null,
    /**
     * Full (multi-KB) redacted stacktrace for the detail page. Kept OUT of the
     * 180-char [detail] truncation — the list still shows the short title/detail,
     * the detail view shows this. Null for non-error / manually-recorded entries.
     */
    val stacktrace: String? = null,
)

/**
 * Current health of a single subsystem on the Diagnostics status timeline.
 *
 * Distinct from [DiagnosticSeverity], which classifies a *logged event* after
 * the fact. A [CheckStatus] is the *live* state of a subsystem, derived
 * read-only from connection state + the recent [DiagnosticsLog]. [Unknown] is
 * a first-class, honest state — "not checked / not applicable" — never an
 * implied pass or fail.
 */
enum class CheckStatus { Pass, Warn, Fail, Unknown }

/**
 * One row on the Diagnostics status timeline: a named subsystem check with its
 * current [status] and, when not [CheckStatus.Pass], a human [reason] — the
 * whole point of the screen is answering "why is this failing?".
 *
 * [category] links the check back to a [DiagnosticCategory]; when [timestampMs]
 * is non-null the reason came from a concrete [DiagnosticLogEntry], so the row
 * is tappable and the UI can open that entry's full detail.
 */
data class StatusCheck(
    val name: String,
    val status: CheckStatus,
    val reason: String? = null,
    val category: DiagnosticCategory? = null,
    val timestampMs: Long? = null,
    val durationMs: Long? = null,
)

object DiagnosticsLog {
    private const val MAX_ENTRIES = 200
    private const val MAX_TEXT_LENGTH = 180

    /** Cap for the full stacktrace kept on an error entry — a few KB is plenty. */
    private const val MAX_TRACE_LENGTH = 8000

    private val lock = Any()
    private val _entries = MutableStateFlow<List<DiagnosticLogEntry>>(emptyList())
    val entries: StateFlow<List<DiagnosticLogEntry>> = _entries.asStateFlow()

    fun record(
        category: DiagnosticCategory,
        severity: DiagnosticSeverity = DiagnosticSeverity.Info,
        title: String,
        detail: String? = null,
        endpointRole: String? = null,
        url: String? = null,
        elapsedMs: Long? = null,
        stacktrace: String? = null,
    ) {
        val entry = DiagnosticLogEntry(
            timestampMs = System.currentTimeMillis(),
            category = category,
            severity = severity,
            title = clean(title) ?: title.take(MAX_TEXT_LENGTH),
            detail = clean(detail),
            endpointRole = clean(endpointRole),
            url = sanitizeUrl(url),
            elapsedMs = elapsedMs,
            stacktrace = redactTrace(stacktrace),
        )
        synchronized(lock) {
            _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
        }
    }

    /**
     * Record an [DiagnosticSeverity.Error] entry from a classified failure. The
     * list keeps showing the clean [title] (+ short [detail]); the detail page
     * shows the full redacted stacktrace.
     *
     * Called centrally from [com.hermesandroid.relay.util.classifyError] as a
     * side effect, so every classified error lands here with no per-call-site
     * churn. The flow is one-way (classify -> record); nothing here re-enters
     * the classifier, so there is no recursion.
     *
     * @param title  clean, human title (e.g. [com.hermesandroid.relay.util.HumanError.title]).
     * @param detail short one-line summary shown in the list row (truncated to 180).
     * @param throwable source error — its stacktrace is captured, redacted, and capped.
     */
    fun recordError(
        category: DiagnosticCategory,
        title: String,
        detail: String? = null,
        throwable: Throwable? = null,
        endpointRole: String? = null,
        url: String? = null,
        elapsedMs: Long? = null,
    ) {
        record(
            category = category,
            severity = DiagnosticSeverity.Error,
            title = title,
            detail = detail ?: throwable?.message,
            endpointRole = endpointRole,
            url = url,
            elapsedMs = elapsedMs,
            stacktrace = throwable?.let { stackTraceText(it) },
        )
    }

    private fun stackTraceText(t: Throwable): String =
        java.io.StringWriter().also { t.printStackTrace(java.io.PrintWriter(it)) }.toString().trim()

    fun recent(
        categories: Set<DiagnosticCategory>? = null,
        limit: Int = 30,
    ): List<DiagnosticLogEntry> {
        val source = entries.value.asReversed()
        val filtered = if (categories == null) {
            source
        } else {
            source.filter { it.category in categories }
        }
        return filtered.take(limit.coerceAtLeast(0))
    }

    fun clear() {
        synchronized(lock) {
            _entries.value = emptyList()
        }
    }

    fun sanitizeUrl(value: String?): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val noQuery = trimmed.substringBefore('?').substringBefore('#')
        val schemeEnd = noQuery.indexOf("://")
        val noUserInfo = if (schemeEnd >= 0) {
            val prefix = noQuery.substring(0, schemeEnd + 3)
            val rest = noQuery.substring(schemeEnd + 3)
            val slash = rest.indexOf('/').let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, slash)
            val path = rest.substring(slash)
            val safeAuthority = authority.substringAfterLast('@')
            prefix + safeAuthority + path
        } else {
            noQuery
        }
        return noUserInfo.take(MAX_TEXT_LENGTH)
    }

    private fun clean(value: String?): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return redact(trimmed).take(MAX_TEXT_LENGTH)
    }

    /**
     * Same secret redaction as [clean] but WITHOUT the 180-char list truncation —
     * for the full stacktrace shown on the detail page. Still capped at
     * [MAX_TRACE_LENGTH] so a runaway trace can't bloat the ring.
     */
    private fun redactTrace(value: String?): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val redacted = redact(trimmed)
        return if (redacted.length > MAX_TRACE_LENGTH) {
            redacted.take(MAX_TRACE_LENGTH) + "\n… (truncated)"
        } else {
            redacted
        }
    }

    private fun redact(value: String): String =
        value.replace(Regex("""(?i)(bearer|token|api[_-]?key|session[_-]?token)\s*[:=]\s*\S+""")) {
            "${it.groupValues[1]}=[hidden]"
        }
}
