package com.hermesandroid.relay.ui

import com.hermesandroid.relay.data.GitBranch
import com.hermesandroid.relay.data.GitRepo
import com.hermesandroid.relay.data.GitStatus
import com.hermesandroid.relay.data.GitStatusCounts
import com.hermesandroid.relay.data.GitStatusEntry
import com.hermesandroid.relay.viewmodel.GitRepoDetailState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitWorkspaceProjectionTest {
    private val parent = GitRepo("parent", "projects", "/srv/projects")
    private val nested = GitRepo("nested", "relay", "/srv/projects/hermes-relay")

    @Test
    fun exactSessionRootWinsAndCwdUsesLongestSegmentMatch() {
        assertEquals(
            nested,
            selectGitRepoForWorkspace(listOf(parent, nested), null, nested.root, null),
        )
        assertEquals(
            nested,
            selectGitRepoForWorkspace(
                listOf(parent, nested),
                null,
                null,
                "/srv/projects/hermes-relay/app/src",
            ),
        )
    }

    @Test
    fun ambiguousCatalogDoesNotInventASelection() {
        assertNull(selectGitRepoForWorkspace(listOf(parent, nested), null, null, null))
        assertNull(selectGitRepoForWorkspace(listOf(parent, nested), nested.id, null, null))
        assertEquals(parent, selectGitRepoForWorkspace(listOf(parent), null, null, null))
    }

    @Test
    fun summaryCountsEachChangedPathOnce() {
        val summary = buildChatGitWorkspaceSummary(
            nested,
            GitRepoDetailState.Ready(
                status = GitStatus(
                    counts = GitStatusCounts(additions = 24, deletions = 7),
                    staged = listOf(GitStatusEntry("shared.kt")),
                    modified = listOf(GitStatusEntry("shared.kt"), GitStatusEntry("other.kt")),
                    untracked = listOf(GitStatusEntry("new.kt")),
                ),
                branches = listOf(GitBranch("dev", isCurrent = true)),
            ),
        )

        assertEquals("dev", summary?.branch)
        assertEquals(3, summary?.changeCount)
        assertEquals(24, summary?.additions)
        assertEquals(7, summary?.deletions)
    }
}
