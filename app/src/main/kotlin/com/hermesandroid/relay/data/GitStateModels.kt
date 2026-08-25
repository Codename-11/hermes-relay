package com.hermesandroid.relay.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A repository discovered by the plugin's /git/repos endpoint. */
@Serializable
data class GitRepo(
    val id: String,
    val name: String,
    val root: String,
    @SerialName("current_branch") val currentBranch: String? = null,
    val dirty: Boolean = false,
)

/** Working-tree status from /git/status. */
@Serializable
data class GitStatus(
    val counts: GitStatusCounts = GitStatusCounts(),
    val staged: List<GitStatusEntry> = emptyList(),
    val modified: List<GitStatusEntry> = emptyList(),
    val untracked: List<GitStatusEntry> = emptyList(),
    val truncated: Boolean = false,
)

@Serializable
data class GitStatusCounts(
    val staged: Int = 0,
    val modified: Int = 0,
    val untracked: Int = 0,
)

@Serializable
data class GitStatusEntry(
    val path: String,
)

/** A branch from /git/branches. */
@Serializable
data class GitBranch(
    val name: String,
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    @SerialName("is_current") val isCurrent: Boolean = false,
)

/** A per-file diff from /git/diff. */
@Serializable
data class GitDiff(
    val path: String,
    val kind: String,
    val diff: String,
    val truncated: Boolean = false,
)

/** A tracked-file read from /git/file. */
@Serializable
data class GitFile(
    val path: String,
    val content: String,
    val truncated: Boolean = false,
)

/** Wrapper for /git/repos response. */
@Serializable
internal data class ReposResponse(
    val repos: List<GitRepo> = emptyList(),
    val notice: String? = null,
)

/** Wrapper for /git/branches response. */
@Serializable
internal data class BranchesResponse(
    val branches: List<GitBranch> = emptyList(),
)

/** A mutation response: fresh HEAD oid + working-tree status (+ branches). */
@Serializable
data class GitMutationResult(
    val head: String = "",
    val status: GitStatus = GitStatus(),
    val branches: List<GitBranch> = emptyList(),
)

/** A /git/commit_message suggestion: generated message + optional notice. */
@Serializable
data class GitCommitMessage(
    val message: String = "",
    val notice: String = "",
)

/** A /git/stash_checkout result: standard mutation shape + stash flag/message. */
@Serializable
data class GitStashCheckoutResult(
    val head: String = "",
    val status: GitStatus = GitStatus(),
    val branches: List<GitBranch> = emptyList(),
    val stashed: Boolean = false,
    @SerialName("stash_message") val stashMessage: String = "",
)
