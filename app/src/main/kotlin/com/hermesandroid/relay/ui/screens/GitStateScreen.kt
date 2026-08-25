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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.hermesandroid.relay.viewmodel.GitConfirmationStrings
import com.hermesandroid.relay.viewmodel.GitContentViewState
import com.hermesandroid.relay.viewmodel.GitMessageGenerationState
import com.hermesandroid.relay.viewmodel.GitMutationState
import com.hermesandroid.relay.viewmodel.GitRepoDetailState
import com.hermesandroid.relay.viewmodel.GitStateUiState
import com.hermesandroid.relay.viewmodel.GitStateViewModel

/**
 * Git State screen (read + write): repo picker → working-tree status/branches →
 * per-file diff or content. Writes require the ``plugin.api.write`` grant and
 * destructive ops (discard/push/dirty-checkout) require an explicit per-use
 * confirmation dialog; the fixed confirmation token is sent only on confirm.
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
    val mutationState by viewModel.mutation.collectAsState()
    val hasGrant = viewModel.hasWriteGrant()

    // Hoisted at screen level so confirmation/commit dialogs are modal.
    var pendingConfirm by remember { mutableStateOf<ConfirmationRequest?>(null) }
    var showCommitDialog by remember { mutableStateOf(false) }
    var pushAfterCommit by rememberSaveable { mutableStateOf(false) }

    // Staged paths for the AI magic-wand (commit_message_selected) + commit.
    val stagedPaths = (detailState as? GitRepoDetailState.Ready)
        ?.status?.staged?.map { it.path } ?: emptyList()

    val messageGenerationState by viewModel.messageGeneration.collectAsState()
    val stashNotice by viewModel.stashNotice.collectAsState()

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
                        is GitRepoDetailState.Ready -> {
                            MutationBanner(
                                mutation = mutationState,
                                onClear = viewModel::clearMutationError,
                            )
                            stashNotice?.let { notice ->
                                Card(Modifier.fillMaxWidth()) {
                                    Text(
                                        notice,
                                        modifier = Modifier.padding(16.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                            if (!hasGrant) {
                                WriteGrantNotice()
                            }
                            RepoDetail(
                                status = current.status,
                                branches = current.branches,
                                hasGrant = hasGrant,
                                onShowDiff = viewModel::loadDiff,
                                onShowFile = viewModel::loadFile,
                                onStage = { path -> viewModel.stage(listOf(path)) },
                                onUnstage = { path -> viewModel.unstage(listOf(path)) },
                                onDiscard = { paths, deleteUntracked ->
                                    pendingConfirm = ConfirmationRequest.Discard(paths, deleteUntracked)
                                },
                                onCommitRequest = { showCommitDialog = true },
                                onFetch = { viewModel.fetch() },
                                onPull = { viewModel.pull() },
                                onPush = { pendingConfirm = ConfirmationRequest.Push },
                                onSwitchBranch = { ref ->
                                    val dirty = current.status.counts.staged > 0 ||
                                        current.status.counts.modified > 0 ||
                                        current.status.counts.untracked > 0
                                    if (dirty) {
                                        pendingConfirm = ConfirmationRequest.DirtyCheckout(ref)
                                    } else {
                                        viewModel.checkout(ref)
                                    }
                                },
                                onStashSwitchBranch = { ref ->
                                    viewModel.stashCheckout(ref)
                                },
                                onCreateBranch = { name, track ->
                                    viewModel.checkout("", newBranch = name, track = track)
                                },
                            )
                        }
                    }
                    ContentView(state = contentState)
                }
            }
        }
    }

    if (showCommitDialog) {
        CommitDialog(
            onDismiss = { showCommitDialog = false },
            hasStaged = stagedPaths.isNotEmpty(),
            generatingMessage = messageGenerationState is GitMessageGenerationState.Loading,
            onGenerate = {
                viewModel.generateCommitMessage(
                    if (stagedPaths.isNotEmpty()) stagedPaths else null,
                )
            },
            generatedMessage = (messageGenerationState as? GitMessageGenerationState.Ready)?.message ?: "",
            generationNotice = (messageGenerationState as? GitMessageGenerationState.Ready)?.notice,
            pushAfterCommit = pushAfterCommit,
            onPushAfterCommitChange = { pushAfterCommit = it },
            onCommit = { message ->
                showCommitDialog = false
                viewModel.commit(message)
                if (pushAfterCommit) {
                    pendingConfirm = ConfirmationRequest.Push
                }
            },
        )
    }

    pendingConfirm?.let { request ->
        val onDismiss = { pendingConfirm = null }
        when (request) {
            is ConfirmationRequest.Discard -> AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.git_state_confirm_discard_title)) },
                text = { Text(stringResource(R.string.git_state_confirm_discard_text)) },
                confirmButton = {
                    TextButton(onClick = {
                        pendingConfirm = null
                        viewModel.discard(
                            request.paths,
                            GitConfirmationStrings.DISCARD,
                            request.deleteUntracked,
                        )
                    }) {
                        Text(stringResource(R.string.git_state_confirm_discard_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.git_state_cancel))
                    }
                },
            )
            ConfirmationRequest.Push -> AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.git_state_confirm_push_title)) },
                text = { Text(stringResource(R.string.git_state_confirm_push_text)) },
                confirmButton = {
                    TextButton(onClick = {
                        pendingConfirm = null
                        viewModel.push(GitConfirmationStrings.PUSH)
                    }) {
                        Text(stringResource(R.string.git_state_confirm_push_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.git_state_cancel))
                    }
                },
            )
            is ConfirmationRequest.DirtyCheckout -> AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.git_state_confirm_checkout_dirty_title)) },
                text = { Text(stringResource(R.string.git_state_confirm_checkout_dirty_text)) },
                confirmButton = {
                    TextButton(onClick = {
                        pendingConfirm = null
                        viewModel.checkout(request.ref, GitConfirmationStrings.DIRTY_CHECKOUT)
                    }) {
                        Text(stringResource(R.string.git_state_confirm_checkout_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.git_state_cancel))
                    }
                },
            )
        }
    }
}

/** A destructive action awaiting explicit user confirmation. */
private sealed interface ConfirmationRequest {
    data class Discard(val paths: List<String>, val deleteUntracked: Boolean) : ConfirmationRequest
    data object Push : ConfirmationRequest
    data class DirtyCheckout(val ref: String) : ConfirmationRequest
}

@Composable
private fun CommitDialog(
    onDismiss: () -> Unit,
    hasStaged: Boolean,
    generatingMessage: Boolean,
    onGenerate: () -> Unit,
    generatedMessage: String,
    generationNotice: String?,
    pushAfterCommit: Boolean,
    onPushAfterCommitChange: (Boolean) -> Unit,
    onCommit: (String) -> Unit,
) {
    var message by rememberSaveable { mutableStateOf("") }
    // Pre-fill with the latest generated suggestion when it arrives.
    if (generatedMessage.isNotEmpty() && message.isBlank()) {
        message = generatedMessage
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.git_state_commit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text(stringResource(R.string.git_state_commit_message_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    trailingIcon = {
                        IconButton(onClick = onGenerate, enabled = hasStaged && !generatingMessage) {
                            Icon(
                                Icons.Filled.AutoAwesome,
                                stringResource(R.string.git_state_generate_message),
                            )
                        }
                    },
                )
                if (generatingMessage) {
                    Text(
                        stringResource(R.string.git_state_generating_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                generationNotice?.let { notice ->
                    Text(
                        notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = pushAfterCommit, onCheckedChange = onPushAfterCommitChange)
                    Text(stringResource(R.string.git_state_push_after_commit))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCommit(message) },
                enabled = message.isNotBlank(),
            ) {
                Text(stringResource(R.string.git_state_commit_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.git_state_cancel))
            }
        },
    )
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
private fun MutationBanner(
    mutation: GitMutationState,
    onClear: () -> Unit,
) {
    when (mutation) {
        GitMutationState.Idle -> Unit
        is GitMutationState.InProgress -> Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.git_state_mutation_in_progress, displayLabel(mutation.label)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        is GitMutationState.Success -> Card(Modifier.fillMaxWidth()) {
            Text(
                stringResource(
                    R.string.git_state_mutation_success,
                    displayLabel(mutation.label),
                    mutation.head,
                ),
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        is GitMutationState.Error -> Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(
                        R.string.git_state_mutation_failed,
                        displayLabel(mutation.label),
                        mutation.message,
                    ),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.git_state_cancel))
                }
            }
        }
    }
}

private fun displayLabel(label: String): String =
    label.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

@Composable
private fun WriteGrantNotice() {
    Card(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.git_state_write_grant_required),
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun RepoDetail(
    status: GitStatus,
    branches: List<GitBranch>,
    hasGrant: Boolean,
    onShowDiff: (String, String) -> Unit,
    onShowFile: (String) -> Unit,
    onStage: (String) -> Unit,
    onUnstage: (String) -> Unit,
    onDiscard: (List<String>, Boolean) -> Unit,
    onCommitRequest: () -> Unit,
    onFetch: () -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit,
    onSwitchBranch: (String) -> Unit,
    onStashSwitchBranch: (String) -> Unit,
    onCreateBranch: (String, Boolean) -> Unit,
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
                    staged.forEach { file ->
                        StatusRow(
                            path = file.path,
                            primaryLabel = stringResource(R.string.git_state_unstage),
                            onPrimary = { onUnstage(file.path) },
                            secondaryLabel = stringResource(R.string.git_state_discard),
                            onSecondary = { onDiscard(listOf(file.path), false) },
                            onOpen = { onShowDiff(file.path, "staged") },
                            enabled = hasGrant,
                        )
                    }
                }
                status.modified.takeIf { it.isNotEmpty() }?.let { modified ->
                    GroupHeader(stringResource(R.string.git_state_modified))
                    modified.forEach { file ->
                        StatusRow(
                            path = file.path,
                            primaryLabel = stringResource(R.string.git_state_stage),
                            onPrimary = { onStage(file.path) },
                            secondaryLabel = stringResource(R.string.git_state_discard),
                            onSecondary = { onDiscard(listOf(file.path), false) },
                            onOpen = { onShowDiff(file.path, "unstaged") },
                            enabled = hasGrant,
                        )
                    }
                }
                status.untracked.takeIf { it.isNotEmpty() }?.let { untracked ->
                    GroupHeader(stringResource(R.string.git_state_untracked))
                    untracked.forEach { file ->
                        StatusRow(
                            path = file.path,
                            primaryLabel = stringResource(R.string.git_state_stage),
                            onPrimary = { onStage(file.path) },
                            secondaryLabel = stringResource(R.string.git_state_discard),
                            onSecondary = { onDiscard(listOf(file.path), true) },
                            onOpen = { onShowFile(file.path) },
                            enabled = hasGrant,
                        )
                    }
                }

                // Commit + sync controls (writes; all gated by the grant).
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onFetch,
                        enabled = hasGrant && status.counts.untracked == 0,
                    ) {
                        Text(stringResource(R.string.git_state_fetch))
                    }
                    OutlinedButton(onClick = onPull, enabled = hasGrant) {
                        Text(stringResource(R.string.git_state_pull))
                    }
                    OutlinedButton(
                        onClick = onPush,
                        enabled = hasGrant && status.counts.staged == 0,
                    ) {
                        Text(stringResource(R.string.git_state_push))
                    }
                }
                Button(
                    onClick = onCommitRequest,
                    enabled = hasGrant && status.counts.staged > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.git_state_commit))
                }
            }
        }

        if (branches.isNotEmpty()) {
            BranchCard(
                branches = branches,
                hasGrant = hasGrant,
                onSwitch = onSwitchBranch,
                onStashSwitch = onStashSwitchBranch,
                onCreate = onCreateBranch,
            )
        }
    }
}

@Composable
private fun StatusRow(
    path: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
    onOpen: () -> Unit,
    enabled: Boolean,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
            Text(
                path,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
        TextButton(onClick = onPrimary, enabled = enabled) {
            Text(primaryLabel)
        }
        TextButton(onClick = onSecondary, enabled = enabled) {
            Text(secondaryLabel)
        }
    }
}

@Composable
private fun BranchCard(
    branches: List<GitBranch>,
    hasGrant: Boolean,
    onSwitch: (String) -> Unit,
    onStashSwitch: (String) -> Unit,
    onCreate: (String, Boolean) -> Unit,
) {
    var newBranchName by rememberSaveable { mutableStateOf("") }
    var track by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.git_state_branches),
                style = MaterialTheme.typography.titleSmall,
            )
            branches.forEach { branch ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        branchLabel(branch),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (branch.isCurrent) {
                        Text(
                            stringResource(R.string.git_state_current),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else {
                        OutlinedButton(
                            onClick = { onSwitch(branch.name) },
                            enabled = hasGrant,
                        ) {
                            Text(stringResource(R.string.git_state_switch))
                        }
                        OutlinedButton(
                            onClick = { onStashSwitch(branch.name) },
                            enabled = hasGrant,
                        ) {
                            Text(stringResource(R.string.git_state_switch_stash))
                        }
                    }
                }
            }

            // Create a new branch (optionally tracking the remote).
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newBranchName,
                    onValueChange = { newBranchName = it },
                    label = { Text(stringResource(R.string.git_state_new_branch_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        val name = newBranchName.trim()
                        if (name.isNotEmpty()) {
                            onCreate(name, track)
                            newBranchName = ""
                            track = false
                        }
                    },
                    enabled = hasGrant && newBranchName.isNotBlank(),
                ) {
                    Text(stringResource(R.string.git_state_create_branch))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = track, onCheckedChange = { track = it }, enabled = hasGrant)
                Text(stringResource(R.string.git_state_track_remote))
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
