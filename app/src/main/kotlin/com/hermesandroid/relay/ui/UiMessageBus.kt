package com.hermesandroid.relay.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** Visual tone of a transient banner message. */
enum class UiMessageSeverity { Info, Success, Status, Warning, Error }

data class UiMessage(
    val id: Long,
    val text: String,
    val severity: UiMessageSeverity,
    val ttlMillis: Long,
    /** Stable upstream key for replace-in-place and exact dismissal. */
    val key: String? = null,
)

sealed interface UiMessageEvent {
    data class Show(val message: UiMessage) : UiMessageEvent
    data class Clear(val key: String) : UiMessageEvent
    data object ClearAll : UiMessageEvent
}

internal fun reduceUiMessages(
    current: List<UiMessage>,
    event: UiMessageEvent,
    maxRetained: Int,
): List<UiMessage> = when (event) {
    UiMessageEvent.ClearAll -> emptyList()
    is UiMessageEvent.Clear -> current.filterNot { it.key == event.key }
    is UiMessageEvent.Show -> {
        val incoming = event.message
        val withoutDuplicate = current.filterNot { existing ->
            if (incoming.key != null) existing.key == incoming.key else existing.text == incoming.text
        }
        (withoutDuplicate + incoming).takeLast(maxRetained)
    }
}

/**
 * App-wide bus for transient status, confirmation, and error messages that
 * surface in the top [com.hermesandroid.relay.ui.components.MessageBannerHost]
 * — a thin banner that takes its own space (content slides down, no overlay),
 * shows the newest line collapsed, expands to a few recent lines, auto-dismisses
 * and coalesces duplicates.
 *
 * Messages requiring Retry or Undo use ThemedMessageHost and retain their
 * action-result contract. Upstream keyed AgentNotices own their sticky/clear lifecycle.
 * Migrate frequent
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
    private val hosts = linkedMapOf<Long, Boolean>()
    private val _activeHost = MutableStateFlow<Long?>(null)
    internal val activeHost = _activeHost.asStateFlow()
    private val _modalHostActive = MutableStateFlow(false)
    internal val modalHostActive = _modalHostActive.asStateFlow()
    internal fun registerHost(primary: Boolean): Long = synchronized(hosts) {
        counter.incrementAndGet().also {
            hosts[it] = primary
            _activeHost.value = hosts.keys.lastOrNull()
            _modalHostActive.value = hosts.values.any { primaryHost -> !primaryHost }
        }
    }
    internal fun unregisterHost(id: Long) { synchronized(hosts) {
        hosts.remove(id)
        _activeHost.value = hosts.keys.lastOrNull()
        _modalHostActive.value = hosts.values.any { !it }
    } }
    private val _events = MutableSharedFlow<UiMessageEvent>(extraBufferCapacity = 24)
    val events: SharedFlow<UiMessageEvent> = _events.asSharedFlow()

    // Number of messages currently shown by the host. Lifted here so the app
    // scaffold can fold banner visibility into its status-bar inset accounting
    // without duplicating the host's queue logic.
    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    fun post(
        text: String,
        severity: UiMessageSeverity = UiMessageSeverity.Info,
        ttlMillis: Long = DEFAULT_TTL_MS,
        key: String? = null,
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        _events.tryEmit(
            UiMessageEvent.Show(
                UiMessage(
                    id = counter.incrementAndGet(),
                    text = trimmed,
                    severity = severity,
                    ttlMillis = ttlMillis.coerceAtLeast(0L),
                    key = key?.trim()?.takeIf(String::isNotEmpty),
                ),
            ),
        )
    }

    /** Dismiss only the keyed message owned by the matching upstream notice. */
    fun clear(key: String) {
        key.trim().takeIf(String::isNotEmpty)?.let { _events.tryEmit(UiMessageEvent.Clear(it)) }
    }

    fun clearAll() { _events.tryEmit(UiMessageEvent.ClearAll) }

    fun warning(text: String, ttlMillis: Long = STATUS_TTL_MS) = post(text, UiMessageSeverity.Warning, ttlMillis)
    fun error(text: String, ttlMillis: Long = 10_000L) = post(text, UiMessageSeverity.Error, ttlMillis)

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
