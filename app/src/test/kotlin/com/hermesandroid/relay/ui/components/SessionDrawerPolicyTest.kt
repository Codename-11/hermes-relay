package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.ChatSession
import com.hermesandroid.relay.data.SessionActivityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDrawerPolicyTest {

    @Test
    fun `sessions are ungrouped by default`() {
        assertEquals(SessionDrawerGrouping.None, SessionDrawerViewOptions().grouping)
    }

    @Test
    fun `cross profile rows retain composite identity`() {
        val alpha = row("alpha", "same")
        val beta = row("beta", "same")

        assertEquals("alpha:same", sessionRowKey(alpha))
        assertEquals("beta:same", sessionRowKey(beta))
        assertEquals("default:same", sessionRowKey(row("default", "same")))
    }

    @Test
    fun `rest recent activity alone does not mark a session working`() {
        val recentlyActive = row("default", "recent", recentlyActive = true)

        assertEquals(
            SessionDrawerStatus.Idle,
            sessionDrawerStatus(recentlyActive, activityStates = emptyMap()),
        )
    }

    @Test
    fun `duplicate session ids cannot leak activity across profiles`() {
        val alpha = row("alpha", "same")
        val beta = row("beta", "same")
        val scoped = scopedSessionActivityStates(
            rows = listOf(alpha, beta),
            activityStates = mapOf(sessionRowKey(alpha) to SessionActivityState.Working),
            allowBareSessionIds = false,
        )

        assertEquals(SessionDrawerStatus.Working, sessionDrawerStatus(alpha, scoped))
        assertEquals(SessionDrawerStatus.Idle, sessionDrawerStatus(beta, scoped))
        assertFalse(sessionRowKey(beta) in scoped)
    }

    @Test
    fun `all profiles ignores ambiguous bare session activity`() {
        val alpha = row("alpha", "same")
        val beta = row("beta", "same")

        val scoped = scopedSessionActivityStates(
            rows = listOf(alpha, beta),
            activityStates = mapOf("same" to SessionActivityState.Working),
            allowBareSessionIds = false,
        )

        assertEquals(emptyMap<String, SessionActivityState>(), scoped)
    }

    @Test
    fun `selected profile may scope legacy bare session activity`() {
        val row = row("work", "session")

        val scoped = scopedSessionActivityStates(
            rows = listOf(row),
            activityStates = mapOf("session" to SessionActivityState.NeedsInput),
            allowBareSessionIds = true,
        )

        assertEquals(
            mapOf(sessionRowKey(row) to SessionActivityState.NeedsInput),
            scoped,
        )
    }

    @Test
    fun `status filter and grouping use the same authoritative state`() {
        val restOnly = row("default", "rest-only", recentlyActive = true)
        val working = row("default", "working")
        val states = mapOf(sessionRowKey(working) to SessionActivityState.Working)

        val filtered = filterAndSortSessionRows(
            rows = listOf(restOnly, working),
            options = SessionDrawerViewOptions(statuses = setOf(SessionDrawerStatus.Working)),
            activityStates = states,
        )
        val grouped = groupSessionRows(
            rows = listOf(restOnly, working),
            grouping = SessionDrawerGrouping.Status,
            activityStates = states,
        )

        assertEquals(listOf("working"), filtered.map { it.session.sessionId })
        assertEquals(listOf("Idle", "Working"), grouped.mapNotNull { it.label })
    }

    @Test
    fun `expanded live phases retain distinct drawer statuses and labels`() {
        val phases = listOf(
            SessionActivityState.NeedsInput to (SessionDrawerStatus.NeedsInput to "Needs input"),
            SessionActivityState.Starting to (SessionDrawerStatus.Starting to "Starting"),
            SessionActivityState.Working to (SessionDrawerStatus.Working to "Working"),
            SessionActivityState.BackgroundWork to (SessionDrawerStatus.BackgroundWork to "Background work"),
            SessionActivityState.Checking to (SessionDrawerStatus.Checking to "Checking"),
            SessionActivityState.Unavailable to (SessionDrawerStatus.Unavailable to "Unavailable"),
        )
        val rows = phases.mapIndexed { index, _ -> row("default", "session-$index") }
        val states = rows.zip(phases).associate { (row, phase) -> sessionRowKey(row) to phase.first }

        assertEquals(
            phases.map { it.second.first },
            rows.map { sessionDrawerStatus(it, states) },
        )
        assertEquals(
            phases.map { it.second.second },
            groupSessionRows(rows, SessionDrawerGrouping.Status, states).mapNotNull { it.label },
        )
        assertEquals(
            listOf(
                R.string.drawer_activity_needs_input,
                R.string.drawer_activity_starting,
                R.string.drawer_activity_working,
                R.string.drawer_activity_background_work,
                R.string.drawer_activity_checking,
                R.string.drawer_activity_unavailable,
            ),
            phases.map { sessionActivityLabelResource(it.first) },
        )
        phases.forEachIndexed { index, phase ->
            assertEquals(
                listOf("session-$index"),
                filterAndSortSessionRows(
                    rows = rows,
                    options = SessionDrawerViewOptions(statuses = setOf(phase.second.first)),
                    activityStates = states,
                ).map { it.session.sessionId },
            )
        }
    }

    @Test
    fun `full row border is limited to foreground live work`() {
        assertTrue(sessionActivityShowsRowBorder(SessionActivityState.Starting))
        assertTrue(sessionActivityShowsRowBorder(SessionActivityState.Working))
        assertFalse(sessionActivityShowsRowBorder(SessionActivityState.NeedsInput))
        assertFalse(sessionActivityShowsRowBorder(SessionActivityState.BackgroundWork))
        assertFalse(sessionActivityShowsRowBorder(SessionActivityState.Checking))
        assertFalse(sessionActivityShowsRowBorder(SessionActivityState.Unavailable))
        assertFalse(sessionActivityShowsRowBorder(null))
    }

    @Test
    fun `profile project status and pull request filters compose`() {
        val wanted = row(
            profile = "work",
            id = "wanted",
            repo = "/src/hermes-relay",
            prNumber = 347,
            prState = "open",
        )
        val wrongProfile = row("personal", "other", repo = "/src/hermes-relay", prNumber = 22)
        val wrongProject = row("work", "notes", repo = "/src/notes", prNumber = 23)
        val states = mapOf(sessionRowKey(wanted) to SessionActivityState.NeedsInput)

        val filtered = filterAndSortSessionRows(
            rows = listOf(wrongProfile, wrongProject, wanted),
            options = SessionDrawerViewOptions(
                profiles = setOf("work"),
                projects = setOf("hermes-relay"),
                statuses = setOf(SessionDrawerStatus.NeedsInput),
                pullRequests = setOf(SessionDrawerPrState.Open),
            ),
            activityStates = states,
        )

        assertEquals(listOf("wanted"), filtered.map { it.session.sessionId })
    }

    @Test
    fun `token and cost ordering use authoritative session metrics`() {
        val small = row("default", "small", inputTokens = 10, outputTokens = 20, cost = 3.0)
        val large = row("default", "large", inputTokens = 500, outputTokens = 600, cost = 1.0)

        assertEquals(
            listOf("large", "small"),
            filterAndSortSessionRows(
                listOf(small, large),
                SessionDrawerViewOptions(ordering = SessionDrawerOrdering.Tokens),
            ).map { it.session.sessionId },
        )
        assertEquals(
            listOf("small", "large"),
            filterAndSortSessionRows(
                listOf(small, large),
                SessionDrawerViewOptions(ordering = SessionDrawerOrdering.Cost),
            ).map { it.session.sessionId },
        )
    }

    @Test
    fun `desktop style grouping supports project profile status and updated buckets`() {
        val now = 10 * DAY
        val working = row("work", "working", repo = "/src/hermes-relay", updatedAt = now - 1_000)
        val idle = row("personal", "idle", repo = "/src/notes", updatedAt = now - 3 * DAY)
        val states = mapOf(sessionRowKey(working) to SessionActivityState.Working)

        assertEquals(
            listOf("hermes-relay", "notes"),
            groupSessionRows(listOf(working, idle), SessionDrawerGrouping.Project, states, now)
                .mapNotNull { it.label },
        )
        assertEquals(
            listOf("work", "personal"),
            groupSessionRows(listOf(working, idle), SessionDrawerGrouping.Profile, states, now)
                .mapNotNull { it.label },
        )
        assertEquals(
            listOf("Working", "Idle"),
            groupSessionRows(listOf(working, idle), SessionDrawerGrouping.Status, states, now)
                .mapNotNull { it.label },
        )
        assertEquals(
            listOf("Today", "Last 7 days"),
            groupSessionRows(listOf(working, idle), SessionDrawerGrouping.Updated, states, now)
                .mapNotNull { it.label },
        )
        assertEquals(
            listOf(null),
            groupSessionRows(listOf(working, idle), SessionDrawerGrouping.None, states, now)
                .map { it.label },
        )
    }

    private fun row(
        profile: String,
        id: String,
        repo: String? = null,
        prNumber: Int? = null,
        prState: String? = null,
        inputTokens: Int = 0,
        outputTokens: Int = 0,
        cost: Double? = null,
        updatedAt: Long = 0L,
        recentlyActive: Boolean = false,
    ) = ProfileSessionRow(
        profile = profile,
        session = ChatSession(
            sessionId = id,
            title = id,
            model = null,
            gitRepoRoot = repo,
            pullRequestNumber = prNumber,
            pullRequestState = prState,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            actualCostUsd = cost,
            lastActivityAt = updatedAt,
            recentlyActive = recentlyActive,
        ),
    )

    private companion object {
        const val DAY = 24L * 60L * 60L * 1_000L
    }
}
