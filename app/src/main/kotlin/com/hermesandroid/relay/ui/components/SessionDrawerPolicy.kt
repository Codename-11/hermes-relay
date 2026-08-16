package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.data.ChatSession
import com.hermesandroid.relay.data.SessionActivityState
import java.util.Locale

internal enum class SessionDrawerGrouping {
    None,
    Updated,
    Project,
    Status,
    Profile,
}

internal enum class SessionDrawerOrdering {
    Updated,
    Created,
    Title,
    Status,
    Tokens,
    Cost,
}

internal enum class SessionDrawerStatus {
    NeedsInput,
    Working,
    Idle,
}

internal enum class SessionDrawerPrState {
    Open,
    Draft,
    Merged,
    Closed,
    None,
}

internal data class SessionDrawerViewOptions(
    val grouping: SessionDrawerGrouping = SessionDrawerGrouping.None,
    val ordering: SessionDrawerOrdering = SessionDrawerOrdering.Updated,
    val statuses: Set<SessionDrawerStatus> = emptySet(),
    val profiles: Set<String> = emptySet(),
    val projects: Set<String> = emptySet(),
    val pullRequests: Set<SessionDrawerPrState> = emptySet(),
    val showProfile: Boolean = false,
    val showUpdated: Boolean = true,
    val showTokens: Boolean = false,
    val showCost: Boolean = false,
)

internal data class SessionDrawerGroup(
    val key: String,
    val label: String?,
    val rows: List<ProfileSessionRow>,
)

internal fun sessionRowKey(row: ProfileSessionRow): String =
    "${row.profile.lowercase(Locale.ROOT)}:${row.session.sessionId}"

internal fun sessionProjectLabel(session: ChatSession): String {
    val raw = (session.gitRepoRoot ?: session.workingDirectory)
        ?.trim()
        ?.trimEnd('/', '\\')
        .orEmpty()
    if (raw.isBlank()) return "No project"
    return raw.substringAfterLast('/').substringAfterLast('\\').ifBlank { raw }
}

internal fun sessionDrawerStatus(
    row: ProfileSessionRow,
    activityStates: Map<String, SessionActivityState>,
): SessionDrawerStatus = when (
    activityStates[sessionRowKey(row)] ?: activityStates[row.session.sessionId]
) {
    SessionActivityState.NeedsInput -> SessionDrawerStatus.NeedsInput
    SessionActivityState.Working -> SessionDrawerStatus.Working
    null -> if (row.session.isActive) SessionDrawerStatus.Working else SessionDrawerStatus.Idle
}

internal fun sessionDrawerPrState(session: ChatSession): SessionDrawerPrState = when {
    session.pullRequestNumber == null -> SessionDrawerPrState.None
    session.pullRequestDraft -> SessionDrawerPrState.Draft
    session.pullRequestState.equals("merged", ignoreCase = true) -> SessionDrawerPrState.Merged
    session.pullRequestState.equals("closed", ignoreCase = true) -> SessionDrawerPrState.Closed
    else -> SessionDrawerPrState.Open
}

internal fun filterAndSortSessionRows(
    rows: List<ProfileSessionRow>,
    options: SessionDrawerViewOptions,
    activityStates: Map<String, SessionActivityState> = emptyMap(),
): List<ProfileSessionRow> {
    val filtered = rows.asSequence()
        .filter { options.statuses.isEmpty() || sessionDrawerStatus(it, activityStates) in options.statuses }
        .filter { options.profiles.isEmpty() || it.profile in options.profiles }
        .filter { options.projects.isEmpty() || sessionProjectLabel(it.session) in options.projects }
        .filter { options.pullRequests.isEmpty() || sessionDrawerPrState(it.session) in options.pullRequests }
        .toList()
    val statusRank = mapOf(
        SessionDrawerStatus.NeedsInput to 0,
        SessionDrawerStatus.Working to 1,
        SessionDrawerStatus.Idle to 2,
    )
    val comparator = when (options.ordering) {
        SessionDrawerOrdering.Updated -> compareByDescending<ProfileSessionRow> { it.session.activityTimestamp }
        SessionDrawerOrdering.Created -> compareByDescending { it.session.startTimestamp }
        SessionDrawerOrdering.Title -> compareBy { it.session.title.orEmpty().lowercase(Locale.ROOT) }
        SessionDrawerOrdering.Status -> compareBy { statusRank.getValue(sessionDrawerStatus(it, activityStates)) }
        SessionDrawerOrdering.Tokens -> compareByDescending { it.session.totalTokens }
        SessionDrawerOrdering.Cost -> compareByDescending { it.session.costUsd }
    }
    return filtered.sortedWith(
        compareByDescending<ProfileSessionRow> { it.session.pinned }
            .then(comparator)
            .thenBy { it.session.title.orEmpty().lowercase(Locale.ROOT) },
    )
}

internal fun groupSessionRows(
    rows: List<ProfileSessionRow>,
    grouping: SessionDrawerGrouping,
    activityStates: Map<String, SessionActivityState> = emptyMap(),
    nowMillis: Long = System.currentTimeMillis(),
): List<SessionDrawerGroup> {
    if (rows.isEmpty()) return emptyList()
    val grouped = rows.groupBy { row ->
        when (grouping) {
            SessionDrawerGrouping.None -> null
            SessionDrawerGrouping.Updated -> updatedBucket(row.session.activityTimestamp, nowMillis)
            SessionDrawerGrouping.Project -> sessionProjectLabel(row.session)
            SessionDrawerGrouping.Status -> sessionDrawerStatus(row, activityStates).displayLabel
            SessionDrawerGrouping.Profile -> row.profile
        }
    }
    val groups = grouped.map { (label, groupRows) ->
        SessionDrawerGroup(key = "${grouping.name}:$label", label = label, rows = groupRows)
    }
    return if (grouping == SessionDrawerGrouping.Project) {
        groups.sortedWith(
            compareBy<SessionDrawerGroup> { it.label != "No project" }
                .thenByDescending { group -> group.rows.maxOfOrNull { it.session.activityTimestamp } ?: 0L }
                .thenBy { it.label.orEmpty().lowercase(Locale.ROOT) },
        )
    } else {
        groups
    }
}

private val SessionDrawerStatus.displayLabel: String
    get() = when (this) {
        SessionDrawerStatus.NeedsInput -> "Needs input"
        SessionDrawerStatus.Working -> "Working"
        SessionDrawerStatus.Idle -> "Idle"
    }

private fun updatedBucket(timestamp: Long, nowMillis: Long): String {
    if (timestamp <= 0L) return "Older"
    val age = (nowMillis - timestamp).coerceAtLeast(0L)
    return when {
        age < DAY_MILLIS -> "Today"
        age < 2 * DAY_MILLIS -> "Yesterday"
        age < 7 * DAY_MILLIS -> "Last 7 days"
        else -> "Older"
    }
}

private const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
