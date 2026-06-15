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
)

object DiagnosticsLog {
    private const val MAX_ENTRIES = 200
    private const val MAX_TEXT_LENGTH = 180

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
        )
        synchronized(lock) {
            _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
        }
    }

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
        return trimmed
            .replace(Regex("""(?i)(bearer|token|api[_-]?key|session[_-]?token)\s*[:=]\s*\S+""")) {
                "${it.groupValues[1]}=[hidden]"
            }
            .take(MAX_TEXT_LENGTH)
    }
}
