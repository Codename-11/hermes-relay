package com.hermesandroid.relay.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.hermesandroid.relay.reliability.ReliabilityCenter
import com.hermesandroid.relay.reliability.ReliabilityRedactor
import java.time.Instant

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
    /** Human-readable action that produced this event, not merely its subsystem. */
    val operation: String? = null,
    val endpointRole: String? = null,
    /** User/configuration-facing route before protocol/path normalization. */
    val configuredUrl: String? = null,
    /** Exact sanitized URL attempted on the wire, including the diagnostic path. */
    val requestUrl: String? = null,
    /** Concrete next troubleshooting step for failures with a known interpretation. */
    val suggestion: String? = null,
    /** Legacy single-URL field retained for diagnostics that have not needed split context. */
    val url: String? = null,
    val elapsedMs: Long? = null,
    /**
     * Full (multi-KB) redacted stacktrace for the detail page. Kept OUT of the
     * 180-char [detail] truncation — the list still shows the short title/detail,
     * the detail view shows this. Null for non-error / manually-recorded entries.
     */
    val stacktrace: String? = null,
) {
    /** Best route for mode inference and compact list rendering. */
    val primaryUrl: String?
        get() = configuredUrl ?: requestUrl ?: url
}

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
    const val SUPPORT_ENTRY_LIMIT = 80
    private const val MAX_SUPPORT_TEXT_LENGTH = 32_000

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
        operation: String? = null,
        endpointRole: String? = null,
        configuredUrl: String? = null,
        requestUrl: String? = null,
        suggestion: String? = null,
        url: String? = null,
        elapsedMs: Long? = null,
        stacktrace: String? = null,
    ) {
        val safeConfiguredUrl = sanitizeUrl(configuredUrl)
        val safeRequestUrl = sanitizeUrl(requestUrl)
        val entry = DiagnosticLogEntry(
            timestampMs = System.currentTimeMillis(),
            category = category,
            severity = severity,
            title = clean(title) ?: title.take(MAX_TEXT_LENGTH),
            detail = clean(detail),
            operation = clean(operation),
            endpointRole = clean(endpointRole),
            configuredUrl = safeConfiguredUrl,
            requestUrl = safeRequestUrl,
            suggestion = clean(suggestion),
            url = if (safeConfiguredUrl == null && safeRequestUrl == null) sanitizeUrl(url) else null,
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
        operation: String? = null,
        endpointRole: String? = null,
        configuredUrl: String? = null,
        requestUrl: String? = null,
        suggestion: String? = null,
        url: String? = null,
        elapsedMs: Long? = null,
        reliabilityContext: String? = null,
    ) {
        record(
            category = category,
            severity = DiagnosticSeverity.Error,
            title = title,
            detail = detail ?: throwable?.message,
            operation = operation,
            endpointRole = endpointRole,
            configuredUrl = configuredUrl,
            requestUrl = requestUrl,
            suggestion = suggestion,
            url = url,
            elapsedMs = elapsedMs,
            stacktrace = throwable?.let { stackTraceText(it) },
        )
        if (throwable != null) {
            runCatching {
                ReliabilityCenter.recordHandled(
                    title = title,
                    detail = detail ?: throwable.message,
                    throwable = throwable,
                    context = reliabilityContext,
                    routeRole = endpointRole,
                )
            }
        }
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

    /**
     * Exact, bounded diagnostics section used by the review-before-sharing
     * support export. Entries were already sanitized at record time; the final
     * redaction pass protects legacy entries and keeps this safe to compose with
     * persistent reliability reports.
     */
    fun supportText(entries: List<DiagnosticLogEntry>): String {
        val selected = entries.takeLast(SUPPORT_ENTRY_LIMIT)
        if (selected.isEmpty()) return ""
        return ReliabilityRedactor.redact(
            buildString {
                appendLine("Recent in-app diagnostics")
                appendLine("Diagnostics: ${selected.size}")
                selected.forEachIndexed { index, entry ->
                    appendLine()
                    appendLine("===== Diagnostic ${index + 1} =====")
                    appendLine("Time:     ${Instant.ofEpochMilli(entry.timestampMs)}")
                    appendLine("Category: ${entry.category.label}")
                    appendLine("Severity: ${entry.severity.name}")
                    appendLine("Title:    ${entry.title}")
                    entry.operation?.let { appendLine("Operation: $it") }
                    entry.endpointRole?.let { appendLine("Route:    $it") }
                    entry.configuredUrl?.let { appendLine("Configured URL: $it") }
                    entry.requestUrl?.let { appendLine("Request:  $it") }
                    if (entry.configuredUrl == null && entry.requestUrl == null) {
                        entry.url?.let { appendLine("URL:      $it") }
                    }
                    entry.elapsedMs?.let { appendLine("Elapsed:  ${it}ms") }
                    entry.detail?.let { appendLine("Detail:   $it") }
                    entry.suggestion?.let { appendLine("Next:     $it") }
                    entry.stacktrace?.let {
                        appendLine("Technical detail (redacted)")
                        appendLine(it)
                    }
                }
            },
            MAX_SUPPORT_TEXT_LENGTH,
        )
    }

    fun sanitizeUrl(value: String?): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val noQuery = trimmed.substringBefore('?').substringBefore('#')
        val schemeEnd = noQuery.indexOf("://")
        val noUserInfo = if (schemeEnd >= 0) {
            val prefix = noQuery.substring(0, schemeEnd + 3)
            val rest = noQuery.substring(schemeEnd + 3)
            val slash = rest.indexOf('/').let { if (it < 0) rest.length else it }
            val path = rest.substring(slash)
            prefix + "[host]" + path
        } else {
            noQuery
        }
        return noUserInfo.take(MAX_TEXT_LENGTH)
    }

    /**
     * Public secret redaction for user-composed report text (e.g. the "what
     * were you expecting?" answer embedded in a GitHub issue body). Same
     * redaction + cap as the stored stacktraces — entry fields are already
     * sanitized at record time; this covers text added after the fact.
     */
    fun redactReportText(value: String?): String? = redactTrace(value)

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
        val redacted = ReliabilityRedactor.redact(trimmed, MAX_TRACE_LENGTH)
        return if (redacted.length > MAX_TRACE_LENGTH) {
            redacted.take(MAX_TRACE_LENGTH) + "\n… (truncated)"
        } else {
            redacted
        }
    }

    private fun redact(value: String): String = ReliabilityRedactor.redact(value, MAX_TRACE_LENGTH)
}
