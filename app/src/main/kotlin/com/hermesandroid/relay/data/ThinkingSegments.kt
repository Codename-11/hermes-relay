package com.hermesandroid.relay.data

/**
 * Ordering helpers for the live reasoning split.
 *
 * A streaming turn appends every reasoning delta to one string, which renders
 * as a single block above all of that turn's tool rows. Splitting the string at
 * tool boundaries is what lets the chat draw thinking, then the tools it
 * produced, then the next thinking: the same shape the transcript has once the
 * server's per-call rows are reloaded.
 *
 * [appendThinkingDelta] and [sealThinkingSegment] only ever act on the last
 * entry, so a caller can keep the list on the message without tracking an
 * index. A sealed segment is a trailing empty string: blank entries render
 * nothing, and the next delta fills it.
 */

/** Append [delta] to the open segment, opening the first one when there is none. */
internal fun appendThinkingDelta(segments: List<String>, delta: String): List<String> =
    if (segments.isEmpty()) listOf(delta) else segments.dropLast(1) + (segments.last() + delta)

/**
 * Close the open segment so the next [appendThinkingDelta] starts a new block.
 * Idempotent, so several tool starts inside one model call still produce one
 * split: a segment that is already closed (or blank) is left alone.
 */
internal fun sealThinkingSegment(segments: List<String>): List<String> =
    if (segments.isEmpty() || segments.last().isBlank()) segments else segments + ""

/**
 * Index of the block a tool belongs under, read before [sealThinkingSegment]
 * closes it. Null means the call produced no visible reasoning of its own.
 */
internal fun openThinkingSegmentIndex(segments: List<String>): Int? =
    if (segments.isEmpty()) null else segments.lastIndex
