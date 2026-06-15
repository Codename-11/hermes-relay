package com.hermesandroid.relay.util

import android.util.Log

/**
 * Per-turn latency tracer for the chat transports.
 *
 * Stamps monotonic ([System.nanoTime]) marks across a single
 * send → first-token → done turn and emits ONE INFO line on [done] so the
 * gateway and SSE paths are directly comparable in logcat — the whole point
 * being to answer "where did the wait go?" against the official hermes-desktop
 * client, which always speaks the gateway.
 *
 * All marks are cumulative milliseconds since construction (t0 = the moment
 * the send began), tagged with `@` so adjacent marks can be diffed to read a
 * phase duration. Durations only — this NEVER logs message content.
 *
 * Gateway turns set `connect`/`session`/`submit` (the connection-establish
 * phases, skipped on a warm socket) plus `ttfe`/`ttft`. SSE turns are a single
 * POST, so they set only `ttfe`/`ttft` (the connection phases show as absent).
 *
 * - `ttfe` — time to first event (server acknowledged and started producing).
 * - `ttft` — time to first *visible* streamed token (reasoning OR text). This
 *   is the perceptual "it finally responded" metric and the one that exposes
 *   the SSE reasoning dead-air: on the gateway reasoning streams live so `ttft`
 *   is small; on SSE the reasoning phase is invisible until done so `ttft`
 *   balloons.
 *
 * Every mutator is idempotent on first-wins ([mark]) or single-shot ([done]),
 * so terminal paths can call [done] from more than one place safely.
 */
class TurnLatencyTracer(private val transport: String) {
    private val t0 = System.nanoTime()
    private val marks = LinkedHashMap<String, Long>()

    @Volatile
    private var warmTag: String? = null

    @Volatile
    private var finished = false

    private fun nowMs(): Long = (System.nanoTime() - t0) / 1_000_000

    /** Record whether the socket+session were already warm (gateway only). */
    @Synchronized
    fun warm(isWarm: Boolean) {
        warmTag = if (isWarm) "warm" else "cold"
    }

    /** Record cumulative elapsed for [name]; first call wins (later calls ignored). */
    @Synchronized
    fun mark(name: String) {
        if (!marks.containsKey(name)) marks[name] = nowMs()
    }

    /** Emit the consolidated timing line. Single-shot; safe to call from multiple terminals. */
    @Synchronized
    fun done(outcome: String = "") {
        if (finished) return
        finished = true
        val total = nowMs()
        val line = buildString {
            append("turn[").append(transport).append(']')
            warmTag?.let { append(' ').append(it) }
            marks.forEach { (k, v) -> append(' ').append(k).append('@').append(v).append("ms") }
            append(" done@").append(total).append("ms")
            if (outcome.isNotEmpty()) append(' ').append(outcome)
        }
        Log.i(TAG, line)
    }

    companion object {
        private const val TAG = "TurnLatency"
    }
}
