package com.hermesandroid.relay.ui

import com.hermesandroid.relay.data.GitRepo
import com.hermesandroid.relay.ui.components.ChatGitWorkspaceSummary
import com.hermesandroid.relay.viewmodel.GitRepoDetailState

internal fun selectGitRepoForWorkspace(
    repos: List<GitRepo>,
    selectedRepoId: String?,
    sessionRepoRoot: String?,
    sessionWorkingDirectory: String?,
): GitRepo? {
    if (repos.isEmpty()) return null

    fun normalized(path: String): String =
        path.trim().replace('\\', '/').trimEnd('/')

    val exactRoot = sessionRepoRoot?.let(::normalized).orEmpty()
    val workingDirectory = sessionWorkingDirectory?.let(::normalized).orEmpty()
    val matched = exactRoot.takeIf { it.isNotBlank() }?.let { root ->
        repos.firstOrNull { normalized(it.root).equals(root, ignoreCase = true) }
    } ?: workingDirectory.takeIf { it.isNotBlank() }?.let { cwd ->
        repos.filter { repo ->
            val root = normalized(repo.root)
            cwd.equals(root, ignoreCase = true) ||
                cwd.startsWith("$root/", ignoreCase = true)
        }.maxByOrNull { normalized(it.root).length }
    }
    if (matched != null) return matched
    if (repos.any { it.id == selectedRepoId }) return null
    return repos.singleOrNull()
}

internal fun buildChatGitWorkspaceSummary(
    repo: GitRepo?,
    detail: GitRepoDetailState,
): ChatGitWorkspaceSummary? {
    val ready = detail as? GitRepoDetailState.Ready ?: return null
    repo ?: return null
    val status = ready.status
    val changedPaths = buildSet {
        status.staged.forEach { add(it.path) }
        status.modified.forEach { add(it.path) }
        status.untracked.forEach { add(it.path) }
    }
    val branch = ready.branches.firstOrNull { it.isCurrent }?.name
        ?: repo.currentBranch.orEmpty()
    if (branch.isBlank()) return null
    return ChatGitWorkspaceSummary(
        branch = branch,
        changeCount = status.counts.changes.takeIf { it >= 0 } ?: changedPaths.size,
        additions = status.counts.additions,
        deletions = status.counts.deletions,
    )
}
