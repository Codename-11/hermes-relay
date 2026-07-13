package com.hermesandroid.relay.util

import com.hermesandroid.relay.BuildConfig
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.diagnostics.DiagnosticLogEntry
import com.hermesandroid.relay.diagnostics.DiagnosticSeverity
import com.hermesandroid.relay.diagnostics.DiagnosticsLog
import android.os.Build

/**
 * Pure builder for the GitHub "new issue" prefill derived from a diagnostics
 * entry — title, labels, and markdown body. Extracted from the detail dialog so
 * the prefill contract is unit-testable without Compose.
 *
 * Only [DiagnosticSeverity.Error] entries are bug reports. Routine Info/Warning
 * log lines ("Testing API connection", probe results, …) were landing on the
 * tracker as `[Bug]:` issues with an empty boilerplate body, so non-Error
 * entries prefill as `[Diagnostic]:` questions instead; for Info entries the
 * caller additionally collects the reporter's expectation before offering the
 * link (see [com.hermesandroid.relay.ui.components.DiagnosticDetailDialog]).
 */
object DiagnosticIssuePrefill {

    /**
     * Cap for the trace embedded in the prefilled GitHub URL so it stays within
     * browser limits (the full text is on the clipboard).
     */
    private const val MAX_TRACE_FOR_URL = 3000

    private const val DEFAULT_WHAT_HAPPENED = "Captured diagnostic from the in-app activity log."

    /** `[Bug]:` for Error entries, `[Diagnostic]:` for Info/Warning. */
    fun issueTitle(entry: DiagnosticLogEntry): String = when (entry.severity) {
        DiagnosticSeverity.Error -> "[Bug]: ${entry.title}"
        else -> "[Diagnostic]: ${entry.title}"
    }

    /**
     * `bug` for Error entries; `question` (an existing repo label) for
     * Info/Warning so routine diagnostics don't pollute the bug queue.
     */
    fun issueLabels(entry: DiagnosticLogEntry): String = when (entry.severity) {
        DiagnosticSeverity.Error -> "bug"
        else -> "question"
    }

    /**
     * The actual active route for the "Connection mode" line: the role stamped
     * on the entry when present, else inferred from the entry URL, else
     * `unknown` — never the old unedited "LAN / Tailscale / public TLS / other"
     * template text.
     */
    fun connectionMode(entry: DiagnosticLogEntry): String =
        entry.endpointRole
            ?: entry.url?.let { Connection.inferRouteRole(it) }
            ?: "unknown"

    /**
     * Markdown issue body mirroring the crash-report issue format: environment
     * block + the captured entry.
     *
     * @param expectation the reporter's free-text "What were you expecting to
     *   happen?" answer collected by the Info-severity pre-flight; when
     *   non-blank it replaces the boilerplate "What happened" line. Runs
     *   through the shared diagnostics secret redaction before embedding.
     */
    fun issueBody(entry: DiagnosticLogEntry, expectation: String? = null): String {
        val trace = (entry.stacktrace ?: entry.detail).orEmpty().let {
            if (it.length > MAX_TRACE_FOR_URL) {
                it.take(MAX_TRACE_FOR_URL) + "\n… (truncated — full diagnostic copied to your clipboard)"
            } else {
                it
            }
        }
        val surface = if (BuildConfig.FLAVOR.equals("sideload", ignoreCase = true)) "sideload APK" else "Google Play"
        val whatHappened = DiagnosticsLog.redactReportText(expectation)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_WHAT_HAPPENED
        return buildString {
            appendLine(
                "> ⚠️ Before submitting: remove any secrets, tokens, real hostnames/IPs, " +
                    "or personal data from the detail below.",
            )
            appendLine()
            appendLine("### Affected area")
            appendLine("Android app")
            appendLine()
            appendLine("### What happened?")
            appendLine(whatHappened)
            appendLine()
            appendLine("### Environment")
            appendLine("- Hermes-Relay version/tag: ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
            appendLine("- Install surface: $surface")
            appendLine("- Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("- Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("- Connection mode: ${connectionMode(entry)}")
            appendLine()
            appendLine("### Diagnostic")
            appendLine("- Title: ${entry.title}")
            appendLine("- Category: ${entry.category.label}")
            appendLine("- Severity: ${entry.severity.name}")
            entry.endpointRole?.let { appendLine("- Route: $it") }
            entry.url?.let { appendLine("- URL: $it") }
            entry.elapsedMs?.let { appendLine("- Elapsed: ${it}ms") }
            if (trace.isNotBlank()) {
                appendLine()
                appendLine("```")
                appendLine(trace)
                appendLine("```")
            }
            appendLine()
            append("<sub>Captured by the Hermes-Relay in-app diagnostics log</sub>")
        }
    }
}
