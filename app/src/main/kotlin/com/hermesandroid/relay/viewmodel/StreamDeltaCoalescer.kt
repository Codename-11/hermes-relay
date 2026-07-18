package com.hermesandroid.relay.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Frame-paced publication cadence for streaming text. */
internal const val STREAM_DELTA_FRAME_MS = 16L

private const val STREAM_DELTA_MIN_CHARS_PER_FRAME = 8
private const val STREAM_DELTA_MAX_CHARS_PER_FRAME = 48
private const val STREAM_DELTA_TARGET_DRAIN_FRAMES = 8

private enum class StreamDeltaKind {
    TEXT,
    THINKING,
}

private data class PendingStreamDelta(
    val kind: StreamDeltaKind,
    val content: StringBuilder,
)

/**
 * Main-thread-confined stream-delta frame pacer.
 *
 * Providers often deliver many token events in one scheduler burst, followed
 * by a network gap. Publishing that burst as one Compose state update makes
 * text appear in large steps even when rendering itself is fast. This buffer
 * drains a bounded slice every display-sized interval instead. The slice grows
 * with backlog, keeping presentation close to real time without returning to
 * hundreds of full-message republishes per second.
 *
 * Adjacent deltas of the same kind retain their bytes and their ordering
 * relative to thinking/text transitions. Lifecycle boundaries call [flushNow]
 * so no buffered content can arrive after a tool event, completion,
 * cancellation, or error has settled the message.
 */
internal class StreamDeltaCoalescer(
    private val scope: CoroutineScope,
    private val onTextDelta: (String) -> Unit,
    private val onThinkingDelta: (String) -> Unit,
    private val frameMs: Long = STREAM_DELTA_FRAME_MS,
) {
    private val pending = mutableListOf<PendingStreamDelta>()
    private var flushJob: Job? = null

    init {
        require(frameMs >= 0L) { "frameMs must be non-negative" }
    }

    fun appendText(delta: String) {
        enqueue(StreamDeltaKind.TEXT, delta)
    }

    fun appendThinking(delta: String) {
        enqueue(StreamDeltaKind.THINKING, delta)
    }

    fun flushNow() {
        flushJob?.cancel()
        flushJob = null
        flushPending()
    }

    fun discard() {
        flushJob?.cancel()
        flushJob = null
        pending.clear()
    }

    private fun enqueue(kind: StreamDeltaKind, delta: String) {
        if (delta.isEmpty()) return

        val tail = pending.lastOrNull()
        if (tail?.kind == kind) {
            tail.content.append(delta)
        } else {
            pending += PendingStreamDelta(kind, StringBuilder(delta))
        }

        scheduleFrame()
    }

    private fun scheduleFrame() {
        if (flushJob != null || pending.isEmpty()) return

        flushJob = scope.launch {
            delay(frameMs)
            flushJob = null
            publishFrame()
            scheduleFrame()
        }
    }

    private fun publishFrame() {
        if (pending.isEmpty()) return

        var remainingBudget = streamDeltaFrameBudget(
            pending.sumOf { it.content.length },
        )
        val frame = mutableListOf<Pair<StreamDeltaKind, String>>()

        while (remainingBudget > 0 && pending.isNotEmpty()) {
            val head = pending.first()
            val requestedLength = minOf(remainingBudget, head.content.length)
            val safeLength = head.content.codePointSafePrefixLength(requestedLength)
            if (safeLength == 0) break

            val content = head.content.substring(0, safeLength)
            head.content.delete(0, safeLength)
            remainingBudget -= safeLength
            if (head.content.isEmpty()) pending.removeAt(0)

            val previous = frame.lastOrNull()
            if (previous?.first == head.kind) {
                frame[frame.lastIndex] = head.kind to (previous.second + content)
            } else {
                frame += head.kind to content
            }
        }

        publish(frame)
    }

    private fun flushPending() {
        if (pending.isEmpty()) return

        // Copy before invoking callbacks so a callback that indirectly queues
        // more work starts a fresh window instead of mutating this drain.
        val batch = pending.map { it.kind to it.content.toString() }
        pending.clear()
        publish(batch)
    }

    private fun publish(batch: List<Pair<StreamDeltaKind, String>>) {
        batch.forEach { (kind, content) ->
            when (kind) {
                StreamDeltaKind.TEXT -> onTextDelta(content)
                StreamDeltaKind.THINKING -> onThinkingDelta(content)
            }
        }
    }
}

internal fun streamDeltaFrameBudget(pendingChars: Int): Int {
    if (pendingChars <= 0) return 0
    val adaptiveBudget =
        (pendingChars + STREAM_DELTA_TARGET_DRAIN_FRAMES - 1) /
            STREAM_DELTA_TARGET_DRAIN_FRAMES
    return adaptiveBudget
        .coerceIn(STREAM_DELTA_MIN_CHARS_PER_FRAME, STREAM_DELTA_MAX_CHARS_PER_FRAME)
        .coerceAtMost(pendingChars)
}

private fun StringBuilder.codePointSafePrefixLength(requestedLength: Int): Int {
    if (requestedLength <= 0) return 0
    if (requestedLength >= length) return length

    return if (
        Character.isHighSurrogate(this[requestedLength - 1]) &&
        Character.isLowSurrogate(this[requestedLength])
    ) {
        requestedLength - 1
    } else {
        requestedLength
    }
}
