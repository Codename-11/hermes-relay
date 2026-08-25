package com.hermesandroid.relay.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermesandroid.relay.data.GitBranch
import com.hermesandroid.relay.data.GitDiff
import com.hermesandroid.relay.data.GitFile
import com.hermesandroid.relay.data.GitRepo
import com.hermesandroid.relay.data.GitStateApiClient
import com.hermesandroid.relay.data.GitStatus
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface GitStateUiState {
    data object Loading : GitStateUiState
    data class Error(val message: String) : GitStateUiState
    data class Ready(val repos: List<GitRepo>, val notice: String?) : GitStateUiState
}

sealed interface GitRepoDetailState {
    data object Idle : GitRepoDetailState
    data object Loading : GitRepoDetailState
    data class Error(val message: String) : GitRepoDetailState
    data class Ready(
        val status: GitStatus,
        val branches: List<GitBranch>,
    ) : GitRepoDetailState
}

sealed interface GitContentViewState {
    data object Idle : GitContentViewState
    data object Loading : GitContentViewState
    data class Error(val message: String) : GitContentViewState
    data class Diff(val diff: GitDiff) : GitContentViewState
    data class File(val file: GitFile) : GitContentViewState
}

/** A single in-flight or completed write mutation on the selected repo. */
sealed interface GitMutationState {
    data object Idle : GitMutationState
    data class InProgress(val label: String) : GitMutationState
    data class Error(val label: String, val message: String) : GitMutationState
    data class Success(val label: String, val head: String) : GitMutationState
}

/** A commit-message generation attempt (AI magic-wand). */
sealed interface GitMessageGenerationState {
    data object Idle : GitMessageGenerationState
    data object Loading : GitMessageGenerationState
    data class Ready(val message: String, val notice: String) : GitMessageGenerationState
}

/** Fixed per-use confirmation tokens matching the plugin's server constants. */
object GitConfirmationStrings {
    const val DISCARD = "discard"
    const val PUSH = "push"
    const val DIRTY_CHECKOUT = "checkout-dirty"
}

/**
 * View model for the Git State Android surface (read + write).
 *
 * Loads the scanned repo list from the Hermes-Relay plugin and, on selection,
 * fetches working-tree status + branches. Mutations (stage/unstage/discard/
 * commit/fetch/pull/push/checkout) all require the ``plugin.api.write`` grant:
 * ``configure`` receives the grant set and every mutation refuses (surfacing a
 * readable message, never a POST) when the grant is absent. Destructive ops
 * (discard/push/dirty-checkout) additionally require a per-use confirmation
 * string the caller echoes from GitConfirmationStrings.
 */
class GitStateViewModel(application: Application) : AndroidViewModel(application) {
    private val _repos = MutableStateFlow<GitStateUiState>(GitStateUiState.Loading)
    val repos: StateFlow<GitStateUiState> = _repos.asStateFlow()

    private val _detail = MutableStateFlow<GitRepoDetailState>(GitRepoDetailState.Idle)
    val detail: StateFlow<GitRepoDetailState> = _detail.asStateFlow()

    private val _content = MutableStateFlow<GitContentViewState>(GitContentViewState.Idle)
    val content: StateFlow<GitContentViewState> = _content.asStateFlow()

    private val _mutation = MutableStateFlow<GitMutationState>(GitMutationState.Idle)
    val mutation: StateFlow<GitMutationState> = _mutation.asStateFlow()

    private val _messageGeneration =
        MutableStateFlow<GitMessageGenerationState>(GitMessageGenerationState.Idle)
    val messageGeneration: StateFlow<GitMessageGenerationState> = _messageGeneration.asStateFlow()

    private val _pushAfterCommit = MutableStateFlow(false)
    val pushAfterCommit: StateFlow<Boolean> = _pushAfterCommit.asStateFlow()

    private val _stashNotice = MutableStateFlow<String?>(null)
    val stashNotice: StateFlow<String?> = _stashNotice.asStateFlow()

    private var api: GitStateApiClient? = null
    private var loadJob: Job? = null
    private var selectedRepoId: String? = null
    private var writeGrant: Boolean = false

    fun selectedRepoIdForDisplay(): String? = selectedRepoId

    fun configure(dashboard: DashboardApiClient?) {
        api = dashboard?.let(::GitStateApiClient)
        loadRepos()
    }

    /** Grants the plugin.api.write capability for this connection/profile. */
    fun setWriteGrant(granted: Boolean) {
        writeGrant = granted
    }

    fun hasWriteGrant(): Boolean = writeGrant

    fun loadRepos() {
        val client = api ?: run {
            _repos.value = GitStateUiState.Error("Dashboard connection unavailable")
            return
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _repos.value = GitStateUiState.Loading
            client.repos().fold(
                onSuccess = { list -> _repos.value = GitStateUiState.Ready(list, null) },
                onFailure = { error ->
                    _repos.value = GitStateUiState.Error(error.message ?: "Failed to load repositories")
                },
            )
        }
    }

    fun selectRepo(repoId: String) {
        val client = api ?: return
        selectedRepoId = repoId
        _content.value = GitContentViewState.Idle
        _mutation.value = GitMutationState.Idle
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _detail.value = GitRepoDetailState.Loading
            val statusResult = client.status(repoId)
            val branchesResult = client.branches(repoId)
            if (statusResult.isFailure) {
                _detail.value = GitRepoDetailState.Error(
                    statusResult.exceptionOrNull()?.message ?: "Failed to load status",
                )
                return@launch
            }
            val status: GitStatus = statusResult.getOrThrow()
            val branches: List<GitBranch> = branchesResult.getOrDefault(emptyList())
            _detail.value = GitRepoDetailState.Ready(status, branches)
        }
    }

    /** Runs a mutation through the shared gate (grant + confirmation). */
    private fun runMutation(label: String, block: suspend (GitStateApiClient, String) -> Result<GitMutationState>) {
        val client = api ?: run {
            _mutation.value = GitMutationState.Error(label, "Dashboard connection unavailable")
            return
        }
        val repoId = selectedRepoId ?: run {
            _mutation.value = GitMutationState.Error(label, "No repository selected")
            return
        }
        if (!writeGrant) {
            _mutation.value = GitMutationState.Error(
                label,
                "Allow plugin changes (plugin.api.write) before using this action.",
            )
            return
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _mutation.value = GitMutationState.InProgress(label)
            block(client, repoId).fold(
                onSuccess = {
                    _mutation.value = it
                    _content.value = GitContentViewState.Idle
                    refreshDetail(repoId)
                },
                onFailure = { error ->
                    _mutation.value = GitMutationState.Error(
                        label,
                        error.message ?: "Git action failed",
                    )
                },
            )
        }
    }

    private fun refreshDetail(repoId: String) {
        val client = api ?: return
        viewModelScope.launch {
            val statusResult = client.status(repoId)
            val branchesResult = client.branches(repoId)
            if (statusResult.isSuccess) {
                _detail.value = GitRepoDetailState.Ready(
                    statusResult.getOrDefault(GitStatus()),
                    branchesResult.getOrDefault(emptyList()),
                )
            }
        }
    }

    // ── Read operations ────────────────────────────────────────────────────

    fun loadDiff(path: String, kind: String) {
        val repoId = selectedRepoId ?: return
        val client = api ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _content.value = GitContentViewState.Loading
            client.diff(repoId, path, kind).fold(
                onSuccess = { diff -> _content.value = GitContentViewState.Diff(diff) },
                onFailure = { error ->
                    _content.value = GitContentViewState.Error(
                        error.message ?: "Failed to load diff",
                    )
                },
            )
        }
    }

    fun loadFile(path: String) {
        val repoId = selectedRepoId ?: return
        val client = api ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _content.value = GitContentViewState.Loading
            client.file(repoId, path).fold(
                onSuccess = { file -> _content.value = GitContentViewState.File(file) },
                onFailure = { error ->
                    _content.value = GitContentViewState.Error(
                        error.message ?: "Failed to load file",
                    )
                },
            )
        }
    }

    // ── Write operations ───────────────────────────────────────────────────

    fun stage(paths: List<String>) = runMutation("Stage") { c, r ->
        c.stage(r, paths).map { GitMutationState.Success("stage", it.head) }
    }

    fun unstage(paths: List<String>) = runMutation("Unstage") { c, r ->
        c.unstage(r, paths).map { GitMutationState.Success("unstage", it.head) }
    }

    fun discard(paths: List<String>, confirmation: String, deleteUntracked: Boolean = false) =
        runMutation("Discard") { c, r ->
            c.discard(r, paths, confirmation, deleteUntracked)
                .map { GitMutationState.Success("discard", it.head) }
        }

    fun commit(message: String) = runMutation("Commit") { c, r ->
        c.commit(r, message).map {
            GitMutationState.Success("commit", it.head)
        }
    }

    fun commitSelected(message: String, paths: List<String>) = runMutation("Commit") { c, r ->
        c.commitSelected(r, message, paths).map {
            GitMutationState.Success("commit", it.head)
        }
    }

    fun fetch(remote: String = "origin") = runMutation("Fetch") { c, r ->
        c.fetch(r, remote).map { GitMutationState.Success("fetch", it.head) }
    }

    fun pull(remote: String = "origin", branch: String = "") = runMutation("Pull") { c, r ->
        c.pull(r, remote, branch).map { GitMutationState.Success("pull", it.head) }
    }

    fun push(confirmation: String, remote: String = "origin", branch: String = "") =
        runMutation("Push") { c, r ->
            c.push(r, confirmation, remote, branch).map { GitMutationState.Success("push", it.head) }
        }

    fun checkout(
        ref: String,
        confirmation: String? = null,
        newBranch: String = "",
        track: Boolean = false,
    ) = runMutation("Checkout") { c, r ->
        c.checkout(r, ref, confirmation, newBranch, track)
            .map { GitMutationState.Success("checkout", it.head) }
    }

    // ── Phase 3 extras ─────────────────────────────────────────────────────

    /** Toggle the push-after-commit flow (default OFF; never bypasses confirm). */
    fun setPushAfterCommit(enabled: Boolean) {
        _pushAfterCommit.value = enabled
    }

    /**
     * Generate a commit-message suggestion from the staged diff (AI magic-wand).
     * Empty staged diff / model-unavailable degrade to a notice, never an error.
     * Uses the shared write grant gate (a POST is never sent without the grant).
     */
    fun generateCommitMessage(paths: List<String>? = null) {
        val client = api ?: run {
            _messageGeneration.value =
                GitMessageGenerationState.Ready("", "Dashboard connection unavailable")
            return
        }
        val repoId = selectedRepoId ?: run {
            _messageGeneration.value = GitMessageGenerationState.Ready("", "No repository selected")
            return
        }
        if (!writeGrant) {
            _messageGeneration.value = GitMessageGenerationState.Ready(
                "",
                "Allow plugin changes (plugin.api.write) before using this action.",
            )
            return
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _messageGeneration.value = GitMessageGenerationState.Loading
            val result = if (paths != null) {
                client.commitMessageSelected(repoId, paths)
            } else {
                client.commitMessage(repoId)
            }
            result.fold(
                onSuccess = { msg ->
                    _messageGeneration.value = GitMessageGenerationState.Ready(msg.message, msg.notice)
                },
                onFailure = { error ->
                    _messageGeneration.value = GitMessageGenerationState.Ready(
                        "",
                        error.message ?: "Could not generate a commit message.",
                    )
                },
            )
        }
    }

    /**
     * Checkout that auto-stashes a dirty tree first. No confirmation is needed
     * because a stash is recoverable. Surfaces the stash message via [stashNotice].
     */
    fun stashCheckout(ref: String, newBranch: String = "", track: Boolean = false) {
        val client = api ?: run {
            _mutation.value = GitMutationState.Error("Stash Checkout", "Dashboard connection unavailable")
            return
        }
        val repoId = selectedRepoId ?: run {
            _mutation.value = GitMutationState.Error("Stash Checkout", "No repository selected")
            return
        }
        if (!writeGrant) {
            _mutation.value = GitMutationState.Error(
                "Stash Checkout",
                "Allow plugin changes (plugin.api.write) before using this action.",
            )
            return
        }
        _stashNotice.value = null
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _mutation.value = GitMutationState.InProgress("Stash Checkout")
            client.stashCheckout(repoId, ref, newBranch, track).fold(
                onSuccess = { result ->
                    if (result.stashed) {
                        _stashNotice.value =
                            "Stashed changes on $ref as \"${result.stashMessage}\". Use \"git stash pop\" to restore them."
                    }
                    _mutation.value = GitMutationState.Success("stash-checkout", result.head)
                    _content.value = GitContentViewState.Idle
                    refreshDetail(repoId)
                },
                onFailure = { error ->
                    _mutation.value = GitMutationState.Error(
                        "Stash Checkout",
                        error.message ?: "Git action failed",
                    )
                },
            )
        }
    }

    fun clearStashNotice() {
        _stashNotice.value = null
    }

    /** True when the push-after-commit toggle is currently enabled. */
    fun isPushAfterCommitEnabled(): Boolean = _pushAfterCommit.value

    /** True when the named destructive op needs a confirmation echo. */
    fun requiresConfirmation(op: String): Boolean = op in setOf("discard", "push", "dirty-checkout")

    /** Fixed confirmation token for a destructive op (matches the server). */
    fun confirmationFor(op: String): String? = when (op) {
        "discard" -> GitConfirmationStrings.DISCARD
        "push" -> GitConfirmationStrings.PUSH
        "dirty-checkout" -> GitConfirmationStrings.DIRTY_CHECKOUT
        else -> null
    }

    fun clearMutationError() {
        if (_mutation.value is GitMutationState.Error) {
            _mutation.value = GitMutationState.Idle
        }
    }
}
