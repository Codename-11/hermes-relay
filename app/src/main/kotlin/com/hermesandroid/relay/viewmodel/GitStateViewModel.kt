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

/**
 * View model for the read-only Git State Android surface.
 *
 * Loads the scanned repo list from the Hermes-Relay plugin and, on selection,
 * fetches working-tree status + branches. Diff/file reads are triggered by
 * explicit user taps and are bounded by the server's truncation caps, which
 * surface as a [GitDiff.truncated] / [GitFile.truncated] flag for the UI.
 */
class GitStateViewModel(application: Application) : AndroidViewModel(application) {
    private val _repos = MutableStateFlow<GitStateUiState>(GitStateUiState.Loading)
    val repos: StateFlow<GitStateUiState> = _repos.asStateFlow()

    private val _detail = MutableStateFlow<GitRepoDetailState>(GitRepoDetailState.Idle)
    val detail: StateFlow<GitRepoDetailState> = _detail.asStateFlow()

    private val _content = MutableStateFlow<GitContentViewState>(GitContentViewState.Idle)
    val content: StateFlow<GitContentViewState> = _content.asStateFlow()

    private var api: GitStateApiClient? = null
    private var loadJob: Job? = null
    private var selectedRepoId: String? = null

    fun selectedRepoIdForDisplay(): String? = selectedRepoId

    fun configure(dashboard: DashboardApiClient?) {
        api = dashboard?.let(::GitStateApiClient)
        loadRepos()
    }

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
}
