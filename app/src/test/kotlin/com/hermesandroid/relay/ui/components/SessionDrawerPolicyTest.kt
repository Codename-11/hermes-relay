package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.data.ChatSession
import com.hermesandroid.relay.data.SessionActivityState
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionDrawerPolicyTest {

    @Test
    fun `cross profile rows retain composite identity`() {
        val alpha = row("alpha", "same")
        val beta = row("beta", "same")

        assertEquals("alpha:same", sessionRowKey(alpha))
        assertEquals("beta:same", sessionRowKey(beta))
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
        ),
    )

    private companion object {
        const val DAY = 24L * 60L * 60L * 1_000L
    }
}
