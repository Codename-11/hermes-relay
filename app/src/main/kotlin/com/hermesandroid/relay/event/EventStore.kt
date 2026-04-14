package com.hermesandroid.relay.event

import android.view.accessibility.AccessibilityEvent

/**
 * Phase 3 — `B1 event-stream`
 *
 * Process-wide bounded ring buffer of recent [AccessibilityEvent]s,
 * exposed via the `android_events(limit, since)` polling tool on the
 * host. Off by default — capture only runs when
 * [isStreaming] is true, which the agent toggles explicitly via
 * `android_event_stream(true)`.
 *
 * # Why a ring buffer
 *
 * `onAccessibilityEvent` can fire hundreds of times per second during a
 * scroll or text-change burst. Even with per-`(type, package)` throttling
 * we can easily accumulate thousands of entries an hour, so we cap the
 * store at [MAX_ENTRIES] and evict the oldest in FIFO order. The `since`
 * parameter on the poll tool lets the agent efficiently ask "what's new
 * since the last time I checked" without re-downloading history.
 *
 * # Why we filter event types
 *
 * Android emits a deep taxonomy of events (focus, selection, hover,
 * gesture-detection, touch-exploration, view-scrolled-sub-tree, etc.).
 * The overwhelming majority are low-signal for a reactive-agent use
 * case — we keep only the five that answer "what is the user DOING":
 *
 *  * click — the user pressed something
 *  * text changed — the user typed / pasted
 *  * window state changed — the foreground activity switched
 *  * window content changed — something on the current screen updated
 *  * scroll — the user scrolled a list
 *
 * Everything else is dropped at [append] so the buffer stays dense.
 *
 * # Throttling semantics
 *
 * We keep one event per `(eventTypeInt, packageName)` per
 * [THROTTLE_WINDOW_MS]. This collapses scroll bursts and text-change
 * storms into a single entry per app per 100ms, which is the natural
 * human-observable granularity anyway. The throttle map is garbage-
 * collected every [THROTTLE_GC_INTERVAL_MS] to keep its size bounded
 * even if the user flips through many packages.
 *
 * # Thread safety
 *
 * Reads and writes both take the single [lock]. Android's accessibility
 * service pumps events on its own binder thread; the poll tool flows in
 * over the WSS multiplexer thread. A simple `synchronized` block is
 * plenty — we're not in a hot rendering path.
 *
 * # Privacy
 *
 * Events can contain search queries, typed messages, password form
 * values, and anything else that touches an EditText. The buffer is
 * cleared automatically on any [setStreaming] transition (both
 * enable→disable and disable→enable) so the agent cannot observe
 * history from a previous "on" interval without the user's explicit
 * second opt-in.
 */
object EventStore {

    /** Maximum number of entries retained in the ring buffer. */
    const val MAX_ENTRIES: Int = 500

    /** Throttle window per `(eventTypeInt, packageName)` pair. */
    const val THROTTLE_WINDOW_MS: Long = 100L

    /** Sweep interval for the throttle map (keeps memory bounded). */
    const val THROTTLE_GC_INTERVAL_MS: Long = 5_000L

    /**
     * One recorded accessibility event. Snake-case fields on the wire
     * so the Python tool client sees a consistent envelope across every
     * `android_*` surface.
     */
    data class Entry(
        val timestamp: Long,
        val eventType: String,
        val packageName: String?,
        val className: String?,
        val text: String?,
        val contentDescription: String?,
        val source: String,
    )

    private val lock = Any()
    private val buffer: ArrayDeque<Entry> = ArrayDeque(MAX_ENTRIES)

    /**
     * Per-`(eventTypeInt, packageName)` last-appended timestamp for the
     * [THROTTLE_WINDOW_MS] gate. Swept by [maybeGcThrottleMap] whenever
     * [append] runs and the clock has advanced past the last sweep by
     * [THROTTLE_GC_INTERVAL_MS].
     */
    private val throttleMap: MutableMap<Pair<Int, String>, Long> = HashMap()
    private var lastThrottleGcAt: Long = 0L

    @Volatile
    var isStreaming: Boolean = false
        private set

    /**
     * Enable or disable event capture. Always clears the buffer on a
     * state change (both directions) to avoid two kinds of leak:
     *
     *  * on disable → the buffer is wiped so a subsequent re-enable
     *    cannot replay stale "off"-interval events.
     *  * on enable → we start with a fresh slate so no pre-existing
     *    state from a prior session is mixed with the new stream.
     *
     * A no-op call (same value) leaves the buffer alone.
     */
    fun setStreaming(enabled: Boolean) {
        synchronized(lock) {
            if (isStreaming == enabled) return
            isStreaming = enabled
            buffer.clear()
            throttleMap.clear()
            lastThrottleGcAt = 0L
        }
    }

    /**
     * Append an AccessibilityEvent to the buffer if it survives the
     * type filter and the `(type, package)` throttle. Silently drops
     * events when [isStreaming] is false — the caller in
     * `HermesAccessibilityService.onAccessibilityEvent` can still call
     * us unconditionally.
     */
    fun append(event: AccessibilityEvent) {
        if (!isStreaming) return

        val eventTypeInt = event.eventType
        val humanType = humanEventType(eventTypeInt) ?: return
        val pkg = event.packageName?.toString()
        val now = System.currentTimeMillis()

        synchronized(lock) {
            // Throttle: drop if we saw a same-kind event in the last window.
            val key = Pair(eventTypeInt, pkg ?: "")
            val last = throttleMap[key]
            if (last != null && (now - last) < THROTTLE_WINDOW_MS) {
                return
            }
            throttleMap[key] = now
            maybeGcThrottleMap(now)

            val entry = Entry(
                timestamp = now,
                eventType = humanType,
                packageName = pkg,
                className = event.className?.toString(),
                text = concatEventText(event),
                contentDescription = event.contentDescription?.toString(),
                source = sourceForEventType(eventTypeInt),
            )

            if (buffer.size >= MAX_ENTRIES) {
                buffer.removeFirst()
            }
            buffer.addLast(entry)
        }
    }

    /**
     * Return up to [limit] most-recent entries (oldest-first inside
     * the returned list, matching poll semantics of "play these back in
     * chronological order"). If [since] is non-zero, only entries with
     * `timestamp > since` are returned.
     */
    fun recent(limit: Int = 50, since: Long = 0L): List<Entry> {
        if (limit <= 0) return emptyList()
        synchronized(lock) {
            if (buffer.isEmpty()) return emptyList()

            // Collect chronologically (buffer is already oldest → newest)
            // filtered by since, then tail-trimmed to limit.
            val filtered = if (since > 0L) {
                buffer.filter { it.timestamp > since }
            } else {
                buffer.toList()
            }
            return if (filtered.size <= limit) {
                filtered
            } else {
                filtered.subList(filtered.size - limit, filtered.size).toList()
            }
        }
    }

    /** Clear both the buffer and the throttle map. */
    fun clear() {
        synchronized(lock) {
            buffer.clear()
            throttleMap.clear()
            lastThrottleGcAt = 0L
        }
    }

    /** Test / diag helper — current number of buffered entries. */
    fun size(): Int = synchronized(lock) { buffer.size }

    // ── Internals ───────────────────────────────────────────────────

    /**
     * Map an Android event-type int to the short human string the
     * polling tool returns. Returning `null` is the drop signal — the
     * five accepted types are the signal-rich ones per the Phase 3
     * event-stream plan; every other type is silently ignored.
     */
    private fun humanEventType(eventType: Int): String? = when (eventType) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "click"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "text_changed"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "window_content_changed"
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "window_state_changed"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "scroll"
        else -> null
    }

    /**
     * Stable `TYPE_*` name for the `source` field — mirrors the Android
     * constant name so downstream consumers can correlate with logcat.
     */
    private fun sourceForEventType(eventType: Int): String = when (eventType) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "TYPE_VIEW_CLICKED"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TYPE_VIEW_TEXT_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "TYPE_WINDOW_CONTENT_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "TYPE_WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "TYPE_VIEW_SCROLLED"
        else -> "TYPE_UNKNOWN_$eventType"
    }

    /**
     * Concatenate [AccessibilityEvent.getText] into one display string,
     * truncated to 200 chars so a runaway EditText doesn't blow the
     * buffer. Returns null when the event had no text at all (we'd
     * rather emit `null` than empty string so the JSON stays tight).
     */
    private fun concatEventText(event: AccessibilityEvent): String? {
        val parts = event.text ?: return null
        if (parts.isEmpty()) return null
        val joined = parts.joinToString(separator = " ") { it?.toString().orEmpty() }.trim()
        if (joined.isEmpty()) return null
        return if (joined.length > 200) joined.substring(0, 200) else joined
    }

    /**
     * Amortised GC of the throttle map. Called from [append] under the
     * lock. Walks the map only every [THROTTLE_GC_INTERVAL_MS] to avoid
     * O(n) work per event.
     */
    private fun maybeGcThrottleMap(now: Long) {
        if (lastThrottleGcAt == 0L) {
            lastThrottleGcAt = now
            return
        }
        if ((now - lastThrottleGcAt) < THROTTLE_GC_INTERVAL_MS) return
        lastThrottleGcAt = now
        val cutoff = now - THROTTLE_WINDOW_MS
        val it = throttleMap.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.value < cutoff) it.remove()
        }
    }
}
