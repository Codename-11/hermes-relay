package com.hermesandroid.relay.data

/**
 * Snapshot of a single tool-call invocation for the Stats-for-Nerds +
 * Timeline panels.
 *
 * Derived from [ToolCall] on the assistant messages but denormalized so
 * consumers don't need to walk the message list themselves. The ring
 * buffer that owns these is bounded — usually the last 10 tool calls
 * across all assistant messages in the active chat session.
 *
 * Status is split into two booleans so the UI can render three visual
 * states without introducing an enum dependency:
 *   - `isComplete = false` → in-progress (spinner)
 *   - `isComplete = true  && success == true`  → completed
 *   - `isComplete = true  && success == false` → failed
 */
data class ToolCallEvent(
    val id: String,
    val name: String,
    val startedAtMs: Long,
    val completedAtMs: Long?,
    val isComplete: Boolean,
    val success: Boolean?,
    val resultSummary: String?,
    val errorSummary: String?,
) {
    /** Elapsed wall-clock ms; null if the call hasn't completed. */
    val durationMs: Long?
        get() = completedAtMs?.let { it - startedAtMs }
}
