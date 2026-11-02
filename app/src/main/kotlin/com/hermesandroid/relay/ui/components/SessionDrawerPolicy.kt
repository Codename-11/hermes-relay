package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.data.AgentDisplay
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
    Starting,
    Working,
    BackgroundWork,
    Checking,
    Unavailable,
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
    "${AgentDisplay.profileSessionKey(row.profile).lowercase(Locale.ROOT)}:${row.session.sessionId}"

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
): SessionDrawerStatus = when (activityStates[sessionRowKey(row)]) {
    SessionActivityState.NeedsInput -> SessionDrawerStatus.NeedsInput
    SessionActivityState.Starting -> SessionDrawerStatus.Starting
    SessionActivityState.Working -> SessionDrawerStatus.Working
    SessionActivityState.BackgroundWork -> SessionDrawerStatus.BackgroundWork
    SessionActivityState.Checking -> SessionDrawerStatus.Checking
    SessionActivityState.Unavailable -> SessionDrawerStatus.Unavailable
    null -> SessionDrawerStatus.Idle
}

/** Desktop-style row emphasis is reserved for an actual foreground turn. */
internal fun sessionActivityShowsRowBorder(state: SessionActivityState?): Boolean =
    state == SessionActivityState.Starting || state == SessionActivityState.Working

/**
 * Normalizes live activity to the drawer's profile-scoped row identity.
 *
 * A selected-profile drawer may accept the legacy bare session id because every row belongs
 * to that one explicit profile. All Profiles must use composite keys exclusively: session ids
 * are only unique inside their owning profile.
 */
internal fun scopedSessionActivityStates(
    rows: List<ProfileSessionRow>,
    activityStates: Map<String, SessionActivityState>,
    allowBareSessionIds: Boolean,
): Map<String, SessionActivityState> = buildMap {
    rows.forEach { row ->
        val rowKey = sessionRowKey(row)
        val state = activityStates[rowKey]
            ?: activityStates[row.session.sessionId].takeIf { allowBareSessionIds }
        state?.let { put(rowKey, it) }
    }
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
        SessionDrawerStatus.Starting to 1,
        SessionDrawerStatus.Working to 2,
        SessionDrawerStatus.BackgroundWork to 3,
        SessionDrawerStatus.Checking to 4,
        SessionDrawerStatus.Unavailable to 5,
        SessionDrawerStatus.Idle to 6,
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
        SessionDrawerStatus.Starting -> "Starting"
        SessionDrawerStatus.Working -> "Working"
        SessionDrawerStatus.BackgroundWork -> "Background work"
        SessionDrawerStatus.Checking -> "Checking"
        SessionDrawerStatus.Unavailable -> "Unavailable"
        SessionDrawerStatus.Idle -> "Idle"
    }

internal fun sessionMatchesContentSearch(
    sessionId: String,
    contentMatchIds: Set<String>,
): Boolean = sessionId in contentMatchIds

internal fun mergeContentSearchSessions(
    loaded: List<ChatSession>,
    hits: List<ChatSession>,
): List<ChatSession> {
    if (hits.isEmpty()) return loaded
    val hitById = hits.associateBy { it.sessionId }
    val mergedLoaded = loaded.map { session ->
        val hit = hitById[session.sessionId] ?: return@map session
        session.copy(
            searchSnippet = hit.searchSnippet ?: session.searchSnippet,
            matchKind = hit.matchKind ?: session.matchKind,
            // Prefer richer title from either side.
            title = session.title?.takeIf { it.isNotBlank() } ?: hit.title,
        )
    }
    val seen = mergedLoaded.mapTo(mutableSetOf()) { it.sessionId }
    return mergedLoaded + hits.filter { seen.add(it.sessionId) }
}

/**
 * Drawer list chrome while a gateway content search is in flight.
 * Searching must never fall back to the full recent-session list.
 */
internal enum class SessionDrawerSearchPresentation {
    Idle,
    Searching,
    Results,
    Empty,
}

internal fun sessionDrawerSearchPresentation(
    query: String,
    contentSearchPending: Boolean,
    visibleMatchCount: Int,
): SessionDrawerSearchPresentation {
    if (query.trim().isEmpty()) return SessionDrawerSearchPresentation.Idle
    if (contentSearchPending) return SessionDrawerSearchPresentation.Searching
    return if (visibleMatchCount > 0) {
        SessionDrawerSearchPresentation.Results
    } else {
        SessionDrawerSearchPresentation.Empty
    }
}

/** Keep first occurrence so gateway rank survives message-level duplicate session ids. */
internal fun dedupeContentSearchHits(hits: List<ChatSession>): List<ChatSession> {
    if (hits.isEmpty()) return hits
    val seen = linkedSetOf<String>()
    return hits.filter { seen.add(it.sessionId) }
}

/** Sort mode for an active content-search result set. */
internal enum class SessionSearchSort {
    /** Keep gateway / RRF rank (default). */
    Relevant,
    /** Same hit set, newest activity first. */
    Recent,
}

/**
 * When the gateway ranked sessions, put those hits first in gateway order.
 * Local-only title matches that were not gateway hits keep their relative order after.
 * [sort] only reorders the gateway hit block (Relevant = API order, Recent = activity).
 */
internal fun orderRowsForContentSearch(
    rows: List<ProfileSessionRow>,
    contentHitOrder: List<String>,
    sort: SessionSearchSort = SessionSearchSort.Relevant,
): List<ProfileSessionRow> {
    if (contentHitOrder.isEmpty() || rows.isEmpty()) return rows
    val byId = rows.groupBy { it.session.sessionId }
    val used = mutableSetOf<String>()
    val ordered = ArrayList<ProfileSessionRow>(rows.size)
    val hitIds = when (sort) {
        SessionSearchSort.Relevant -> contentHitOrder
        SessionSearchSort.Recent -> contentHitOrder
            .mapNotNull { id -> byId[id]?.firstOrNull()?.let { id to it } }
            .sortedByDescending { it.second.session.activityTimestamp }
            .map { it.first }
            .ifEmpty { contentHitOrder }
    }
    for (id in hitIds) {
        val group = byId[id] ?: continue
        if (!used.add(id)) continue
        ordered.addAll(group)
    }
    for (row in rows) {
        if (used.add(row.session.sessionId)) {
            ordered.add(row)
        }
    }
    return ordered
}

/**
 * Build a Compose-friendly plain snippet and whether the hit is embedding-only.
 * Gateway may mark the matched term with `>>>term<<<`.
 */
internal fun searchSnippetDisplay(snippet: String?): Pair<String, List<IntRange>> {
    val raw = snippet?.trim().orEmpty()
    if (raw.isEmpty()) return "" to emptyList()
    val ranges = ArrayList<IntRange>()
    val out = StringBuilder()
    var i = 0
    while (i < raw.length) {
        val open = raw.indexOf(">>>", i)
        if (open < 0) {
            out.append(raw, i, raw.length)
            break
        }
        out.append(raw, i, open)
        val close = raw.indexOf("<<<", open + 3)
        if (close < 0) {
            out.append(raw, open, raw.length)
            break
        }
        val start = out.length
        out.append(raw, open + 3, close)
        ranges.add(start until out.length)
        i = close + 3
    }
    return out.toString() to ranges
}

internal fun isSemanticOnlyMatch(matchKind: String?): Boolean =
    matchKind.equals("semantic", ignoreCase = true)

internal fun chatSessionFromSearchHit(
    sessionId: String,
    title: String?,
    preview: String?,
    model: String?,
    source: String?,
    matchKind: String? = null,
    updatedAt: Long = 0L,
): ChatSession = ChatSession(
    sessionId = sessionId,
    // Never promote body snippet into the title slot.
    title = title?.takeIf { it.isNotBlank() },
    model = model,
    source = source,
    searchSnippet = preview?.takeIf { it.isNotBlank() },
    matchKind = matchKind?.takeIf { it.isNotBlank() },
    updatedAt = updatedAt,
    lastActivityAt = updatedAt,
)

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
