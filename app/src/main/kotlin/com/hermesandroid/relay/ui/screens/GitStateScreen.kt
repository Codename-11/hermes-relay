package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.GitBranch
import com.hermesandroid.relay.data.GitDiff
import com.hermesandroid.relay.data.GitFile
import com.hermesandroid.relay.data.GitRepo
import com.hermesandroid.relay.data.GitStatus
import com.hermesandroid.relay.viewmodel.GitContentViewState
import com.hermesandroid.relay.viewmodel.GitRepoDetailState
import com.hermesandroid.relay.viewmodel.GitStateUiState
import com.hermesandroid.relay.viewmodel.GitStateViewModel

/**
 * Read-only Git State screen: repo picker → working-tree status/branches →
 * per-file diff or content. Bounded by the server's truncation caps, shown as
 * a notice when flagged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitStateScreen(
    viewModel: GitStateViewModel,
    onBack: () -> Unit,
) {
    val reposState by viewModel.repos.collectAsState()
    val detailState by viewModel.detail.collectAsState()
    val contentState by viewModel.content.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.git_state_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.git_state_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val repos = reposState
            when (repos) {
                GitStateUiState.Loading -> CenteredSpinner()
                is GitStateUiState.Error -> ErrorText(repos.message)
                is GitStateUiState.Ready -> {
                    repos.notice?.let { ErrorText(it, warning = true) }
                    RepoPicker(
                        repos = repos.repos,
                        selectedId = viewModel.selectedRepoIdForDisplay(),
                        onSelect = viewModel::selectRepo,
                    )
                    when (val current = detailState) {
                        GitRepoDetailState.Idle -> Unit
                        GitRepoDetailState.Loading -> CenteredSpinner()
                        is GitRepoDetailState.Error -> ErrorText(current.message)
                        is GitRepoDetailState.Ready -> RepoDetail(
                            status = current.status,
                            branches = current.branches,
                            onShowDiff = viewModel::loadDiff,
                            onShowFile = viewModel::loadFile,
                        )
                    }
                    ContentView(state = contentState)
                }
            }
        }
    }
}

@Composable
private fun RepoPicker(
    repos: List<GitRepo>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repos.forEach { repo ->
            AssistChip(
                onClick = { onSelect(repo.id) },
                label = {
                    Text(
                        if (repo.dirty) "${repo.name} •" else repo.name,
                        fontWeight = if (repo.id == selectedId) FontWeight.Bold else FontWeight.Normal,
                    )
                },
            )
        }
    }
}

@Composable
private fun RepoDetail(
    status: GitStatus,
    branches: List<GitBranch>,
    onShowDiff: (String, String) -> Unit,
    onShowFile: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${stringResource(R.string.git_state_staged)} ${status.counts.staged} · " +
                        "${stringResource(R.string.git_state_modified)} ${status.counts.modified} · " +
                        "${stringResource(R.string.git_state_untracked)} ${status.counts.untracked}",
                    style = MaterialTheme.typography.labelLarge,
                )
                if (status.truncated) {
                    Text(
                        stringResource(R.string.git_state_truncated),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                status.staged.takeIf { it.isNotEmpty() }?.let { staged ->
                    GroupHeader(stringResource(R.string.git_state_staged))
                    staged.forEach { onShowDiff(it.path, "staged") }
                }
                status.modified.takeIf { it.isNotEmpty() }?.let { modified ->
                    GroupHeader(stringResource(R.string.git_state_modified))
                    modified.forEach { onShowDiff(it.path, "unstaged") }
                }
                status.untracked.takeIf { it.isNotEmpty() }?.let { untracked ->
                    GroupHeader(stringResource(R.string.git_state_untracked))
                    untracked.forEach { onShowFile(it.path) }
                }
            }
        }

        if (branches.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.git_state_branches),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    branches.forEach { branch ->
                        Text(
                            branchLabel(branch),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

private fun branchLabel(branch: GitBranch): String {
    val base = branch.name
    if (branch.upstream == null) return base
    val track =
        if (branch.ahead > 0 || branch.behind > 0) {
            " (ahead ${branch.ahead}, behind ${branch.behind})"
        } else {
            ""
        }
    return "$base → ${branch.upstream}$track"
}

@Composable
private fun GroupHeader(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ContentView(state: GitContentViewState) {
    when (state) {
        GitContentViewState.Idle -> Unit
        GitContentViewState.Loading -> CenteredSpinner()
        is GitContentViewState.Error -> ErrorText(state.message)
        is GitContentViewState.Diff -> MonospaceBlock(state.diff)
        is GitContentViewState.File -> MonospaceBlock(state.file)
    }
}

@Composable
private fun MonospaceBlock(diff: GitDiff) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${diff.path} (${diff.kind})",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
            if (diff.truncated) {
                Text(
                    stringResource(R.string.git_state_truncated),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                diff.diff.ifEmpty { stringResource(R.string.git_state_no_changes) },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun MonospaceBlock(file: GitFile) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                file.path,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
            if (file.truncated) {
                Text(
                    stringResource(R.string.git_state_truncated),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                file.content,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun CenteredSpinner() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorText(message: String, warning: Boolean = false) {
    Text(
        message,
        color = if (warning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
}
