package com.hermesandroid.relay.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** Visual tone of a transient banner message. Errors are NOT modelled here —
 * they stay on the snackbar (see [LocalSnackbarHost]); this bus is info-only. */
enum class UiMessageSeverity { Info, Success, Status }

data class UiMessage(
    val id: Long,
    val text: String,
    val severity: UiMessageSeverity,
    val ttlMillis: Long,
)

/**
 * App-wide bus for transient, non-error status/confirmation messages that
 * surface in the top [com.hermesandroid.relay.ui.components.MessageBannerHost]
 * — a thin banner that takes its own space (content slides down, no overlay),
 * shows the newest line collapsed, expands to a few recent lines, auto-dismisses
 * and coalesces duplicates.
 *
 * Deliberately info-only: errors and persistent/actionable messages keep going
 * to the snackbar so they demand acknowledgement. Migrate frequent
 * `snackbarHostState.showSnackbar("…")` confirmations/status to [info] /
 * [success] / [status] here.
 *
 * A process singleton (not a CompositionLocal) so non-composable code
 * (ViewModels) can post too.
 */
object UiMessageBus {
    const val DEFAULT_TTL_MS = 4_000L
    const val STATUS_TTL_MS = 6_000L

    private val counter = AtomicLong(0L)
    private val _events = MutableSharedFlow<UiMessage>(extraBufferCapacity = 24)
    val events: SharedFlow<UiMessage> = _events.asSharedFlow()

    // Number of messages currently shown by the host. Lifted here so the app
    // scaffold can fold banner visibility into its status-bar inset accounting
    // without duplicating the host's queue logic.
    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    fun post(
        text: String,
        severity: UiMessageSeverity = UiMessageSeverity.Info,
        ttlMillis: Long = DEFAULT_TTL_MS,
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        _events.tryEmit(UiMessage(counter.incrementAndGet(), trimmed, severity, ttlMillis))
    }

    /** Neutral confirmation/info (e.g. "Pairing code copied"). */
    fun info(text: String, ttlMillis: Long = DEFAULT_TTL_MS) =
        post(text, UiMessageSeverity.Info, ttlMillis)

    /** Positive completion (e.g. "Paired successfully", "Profiles updated"). */
    fun success(text: String, ttlMillis: Long = DEFAULT_TTL_MS) =
        post(text, UiMessageSeverity.Success, ttlMillis)

    /** Ongoing/progress status (e.g. "Reconnecting to relay…") — slightly longer TTL. */
    fun status(text: String, ttlMillis: Long = STATUS_TTL_MS) =
        post(text, UiMessageSeverity.Status, ttlMillis)

    /** Host-only: report how many messages are currently visible. */
    internal fun reportActiveCount(count: Int) {
        _activeCount.value = count
    }
}
