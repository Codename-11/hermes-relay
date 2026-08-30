package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.network.upstream.GatewaySubagentEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class SubagentActivityPhase {
    STARTED,
    THINKING,
    TOOL,
    PROGRESS,
    COMPLETED,
    FAILED,
    INTERRUPTED,
    ENDED_WITH_PARENT,
}

internal enum class SubagentActivityEventKind { STARTED, UPDATE, TOOL, COMPLETED }

internal data class SubagentActivityEvent(
    val sequence: Long,
    val kind: SubagentActivityEventKind,
    val text: String? = null,
    val toolName: String? = null,
    val phase: SubagentActivityPhase,
    val observedAtMillis: Long,
)

/**
 * A bounded, ephemeral projection of parent-session `subagent.*` events.
 *
 * This is intentionally not a child transcript. Upstream currently exposes no
 * durable child-session key or child-history route, so the projection is owned
 * by the exact profile-scoped parent session and parent turn that emitted it.
 */
internal data class SubagentActivity(
    val laneId: Long,
    val turnId: String,
    val taskIndex: Int,
    val taskCount: Int,
    val goal: String,
    val subagentId: String? = null,
    val childSessionId: String? = null,
    val parentId: String? = null,
    val depth: Int? = null,
    val model: String? = null,
    val profile: String? = null,
    val phase: SubagentActivityPhase,
    val summary: String? = null,
    val durationSeconds: Double? = null,
    val events: List<SubagentActivityEvent> = emptyList(),
    val truncated: Boolean = false,
    val partialAfterGap: Boolean = false,
    val revision: Long = 0L,
) {
    val stableKey: String
        get() = "$turnId:$laneId"

    val isTerminal: Boolean
        get() = phase in setOf(
            SubagentActivityPhase.COMPLETED,
            SubagentActivityPhase.FAILED,
            SubagentActivityPhase.INTERRUPTED,
            SubagentActivityPhase.ENDED_WITH_PARENT,
        )
}

/**
 * Keeps live child activity isolated from the unrelated `process.list`
 * registry. All text is control-sanitized and bounded before entering UI state.
 */
internal class SubagentActivityController(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        internal const val MAX_EVENTS_PER_CHILD = 50
        internal const val MAX_CHARS_PER_CHILD = 32_000
        internal const val MAX_GOAL_CHARS = 500
        internal const val MAX_EVENT_TEXT_CHARS = 2_000
        internal const val MAX_TOOL_NAME_CHARS = 160
    }

    private val _activities = MutableStateFlow<List<SubagentActivity>>(emptyList())
    val activities: StateFlow<List<SubagentActivity>> = _activities.asStateFlow()

    private var storedSessionId: String? = null
    private var scopeKey: String? = null
    private var activeTurnId: String? = null
    private var sequence = 0L
    private var laneSequence = 0L
    private var connectionWasReady = false
    private var pendingGap = false

    fun selectSession(sessionId: String?, newScopeKey: String?) {
        if (storedSessionId == sessionId && scopeKey == newScopeKey) return
        storedSessionId = sessionId
        scopeKey = newScopeKey
        activeTurnId = null
        sequence = 0L
        laneSequence = 0L
        connectionWasReady = false
        pendingGap = false
        _activities.value = emptyList()
    }

    fun resetConnection() {
        activeTurnId = null
        sequence = 0L
        laneSequence = 0L
        connectionWasReady = false
        pendingGap = false
        _activities.value = emptyList()
    }

    fun onConnectionReady(ready: Boolean) {
        if (connectionWasReady && !ready && _activities.value.any { !it.isTerminal }) {
            pendingGap = true
        }
        if (ready && pendingGap) {
            _activities.value = _activities.value.map { activity ->
                if (activity.isTerminal) activity else activity.copy(
                    partialAfterGap = true,
                    revision = activity.revision + 1,
                )
            }
            pendingGap = false
        }
        connectionWasReady = ready
    }

    fun beginTurn(sessionId: String?, eventScopeKey: String?, turnId: String) {
        if (sessionId == null || sessionId != storedSessionId || eventScopeKey != scopeKey) return
        if (activeTurnId == turnId) return
        activeTurnId = turnId
        sequence = 0L
        laneSequence = 0L
        _activities.value = emptyList()
    }

    fun onEvent(
        sessionId: String?,
        eventScopeKey: String?,
        turnId: String,
        event: GatewaySubagentEvent,
        profile: String? = null,
    ) {
        if (sessionId == null || sessionId != storedSessionId || eventScopeKey != scopeKey) return
        if (activeTurnId != turnId) return

        val taskIndex = event.taskIndex.coerceAtLeast(0)
        val eventIdentity = event.subagentId?.takeIf(String::isNotBlank)
            ?: event.childSessionId?.takeIf(String::isNotBlank)
        val identityMatch = eventIdentity?.let { identity ->
            _activities.value.firstOrNull {
                it.subagentId == identity || it.childSessionId == identity
            }
        }
        val compatibleIndexMatches = _activities.value.filter { activity ->
            activity.taskIndex == taskIndex &&
                (event.subagentId.isNullOrBlank() || activity.subagentId.isNullOrBlank() ||
                    event.subagentId == activity.subagentId) &&
                (event.childSessionId.isNullOrBlank() || activity.childSessionId.isNullOrBlank() ||
                    event.childSessionId == activity.childSessionId) &&
                (event.parentId.isNullOrBlank() || activity.parentId.isNullOrBlank() ||
                    event.parentId == activity.parentId) &&
                (event.depth == null || activity.depth == null || event.depth == activity.depth)
        }
        val current = identityMatch ?: compatibleIndexMatches.singleOrNull()
        if (
            current?.isTerminal == true &&
            event.phase != GatewaySubagentEvent.Phase.SPAWN_REQUESTED &&
            event.phase != GatewaySubagentEvent.Phase.START
        ) return

        val base = if (current?.isTerminal == true) null else current
        val phase = event.toActivityPhase()
        val goal = sanitize(event.goal, MAX_GOAL_CHARS)
        val preview = sanitize(event.preview, MAX_EVENT_TEXT_CHARS).ifBlank { null }
        val summary = sanitize(event.summary, MAX_EVENT_TEXT_CHARS).ifBlank { null }
        val toolName = sanitize(event.toolName, MAX_TOOL_NAME_CHARS).ifBlank { null }
        val eventRow = SubagentActivityEvent(
            sequence = sequence++,
            kind = when (event.phase) {
                GatewaySubagentEvent.Phase.SPAWN_REQUESTED,
                GatewaySubagentEvent.Phase.START,
                -> SubagentActivityEventKind.STARTED
                GatewaySubagentEvent.Phase.THINKING,
                GatewaySubagentEvent.Phase.PROGRESS,
                -> SubagentActivityEventKind.UPDATE
                GatewaySubagentEvent.Phase.TOOL -> SubagentActivityEventKind.TOOL
                GatewaySubagentEvent.Phase.COMPLETE -> SubagentActivityEventKind.COMPLETED
            },
            text = if (event.phase == GatewaySubagentEvent.Phase.COMPLETE) summary else preview,
            toolName = toolName,
            phase = phase,
            observedAtMillis = clock(),
        )

        val priorEvents = base?.events.orEmpty()
        val coalesced = eventRow.kind == SubagentActivityEventKind.UPDATE &&
            priorEvents.lastOrNull()?.let { previous ->
                previous.kind == eventRow.kind && previous.text == eventRow.text
            } == true
        val appended = if (coalesced) priorEvents else priorEvents + eventRow
        val (boundedEvents, truncated) = boundEvents(appended)
        val next = SubagentActivity(
            laneId = base?.laneId ?: laneSequence++,
            turnId = turnId,
            taskIndex = taskIndex,
            taskCount = maxOf(1, event.taskCount, base?.taskCount ?: 1),
            goal = goal.ifBlank { base?.goal.orEmpty() },
            subagentId = event.subagentId?.takeIf(String::isNotBlank) ?: base?.subagentId,
            childSessionId = event.childSessionId?.takeIf(String::isNotBlank) ?: base?.childSessionId,
            parentId = event.parentId?.takeIf(String::isNotBlank) ?: base?.parentId,
            depth = event.depth ?: base?.depth,
            model = event.model?.takeIf(String::isNotBlank) ?: base?.model,
            profile = profile?.takeIf(String::isNotBlank) ?: base?.profile,
            phase = phase,
            summary = summary ?: base?.summary,
            durationSeconds = event.durationSeconds ?: base?.durationSeconds,
            events = boundedEvents,
            truncated = base?.truncated == true || truncated,
            partialAfterGap = base?.partialAfterGap == true,
            revision = (base?.revision ?: 0L) + 1,
        )
        _activities.value = (_activities.value.filterNot { it.stableKey == next.stableKey } + next)
            .sortedWith(compareBy<SubagentActivity> { it.isTerminal }.thenBy { it.taskIndex })
    }

    fun endTurn(turnId: String) {
        if (activeTurnId != turnId) return
        _activities.value = _activities.value.map { activity ->
            if (activity.isTerminal) activity else activity.copy(
                phase = SubagentActivityPhase.ENDED_WITH_PARENT,
                partialAfterGap = true,
                revision = activity.revision + 1,
            )
        }
    }

    private fun boundEvents(
        events: List<SubagentActivityEvent>,
    ): Pair<List<SubagentActivityEvent>, Boolean> {
        val bounded = events.toMutableList()
        var truncated = false
        fun charCount(): Int = bounded.sumOf { (it.text?.length ?: 0) + (it.toolName?.length ?: 0) }
        while (bounded.size > MAX_EVENTS_PER_CHILD || charCount() > MAX_CHARS_PER_CHILD) {
            if (bounded.size <= 1) break
            bounded.removeAt(if (bounded.first().kind == SubagentActivityEventKind.STARTED) 1 else 0)
            truncated = true
        }
        return bounded to truncated
    }
}

private fun GatewaySubagentEvent.toActivityPhase(): SubagentActivityPhase = when (phase) {
    GatewaySubagentEvent.Phase.SPAWN_REQUESTED,
    GatewaySubagentEvent.Phase.START -> SubagentActivityPhase.STARTED
    GatewaySubagentEvent.Phase.THINKING -> SubagentActivityPhase.THINKING
    GatewaySubagentEvent.Phase.TOOL -> SubagentActivityPhase.TOOL
    GatewaySubagentEvent.Phase.PROGRESS -> SubagentActivityPhase.PROGRESS
    GatewaySubagentEvent.Phase.COMPLETE -> when (status?.trim()?.lowercase()) {
        "failed", "error" -> SubagentActivityPhase.FAILED
        "interrupted", "cancelled", "canceled" -> SubagentActivityPhase.INTERRUPTED
        else -> SubagentActivityPhase.COMPLETED
    }
}

private val ANSI_ESCAPE = Regex("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))")

private fun sanitize(value: String?, maxChars: Int): String = value.orEmpty()
    .replace(ANSI_ESCAPE, "")
    .filter { it == '\n' || it == '\t' || it >= ' ' }
    .trim()
    .take(maxChars)
