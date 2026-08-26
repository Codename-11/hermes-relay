package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.GitBranch
import com.hermesandroid.relay.data.GitRepo
import com.hermesandroid.relay.data.GitStatus
import com.hermesandroid.relay.viewmodel.GitConfirmationStrings
import com.hermesandroid.relay.viewmodel.GitContentViewState
import com.hermesandroid.relay.viewmodel.GitMessageGenerationState
import com.hermesandroid.relay.viewmodel.GitMutationState
import com.hermesandroid.relay.viewmodel.GitRepoDetailState
import com.hermesandroid.relay.viewmodel.GitStateUiState
import com.hermesandroid.relay.viewmodel.GitStateViewModel
import com.hermesandroid.relay.viewmodel.GitTarget

private enum class FileFilter { All, Staged, Modified, Untracked }
private enum class ContentMode { Diff, File }
private data class DisplayFile(
    val path: String,
    val filter: FileFilter,
    val additions: Int?,
    val deletions: Int?,
)

/** First-class native Git workspace backed by the optional Relay contribution. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitStateScreen(viewModel: GitStateViewModel, onBack: () -> Unit) {
    val reposState by viewModel.repos.collectAsState()
    val detailState by viewModel.detail.collectAsState()
    val contentState by viewModel.content.collectAsState()
    val mutation by viewModel.mutation.collectAsState()
    val messageGeneration by viewModel.messageGeneration.collectAsState()
    val stashNotice by viewModel.stashNotice.collectAsState()
    val hasGrant by viewModel.writeGrant.collectAsState()
    val selectedRepoId by viewModel.selectedRepoId.collectAsState()

    // A path can be staged and modified at the same time. Keep the category in
    // the selection identity so selecting one row never silently selects the
    // other layer or disables its correct action.
    var selection by remember { mutableStateOf(setOf<DisplayFile>()) }
    var filter by rememberSaveable { mutableStateOf(FileFilter.All) }
    var expandedPath by rememberSaveable { mutableStateOf<String?>(null) }
    var contentMode by rememberSaveable { mutableStateOf(ContentMode.Diff) }
    var showRepos by rememberSaveable { mutableStateOf(false) }
    var showBranches by rememberSaveable { mutableStateOf(false) }
    var showCommit by rememberSaveable { mutableStateOf(false) }
    var showOverflow by rememberSaveable { mutableStateOf(false) }
    var pushAfter by rememberSaveable { mutableStateOf(false) }
    var pendingConfirm by remember { mutableStateOf<ConfirmationRequest?>(null) }

    val repos = (reposState as? GitStateUiState.Ready)?.repos.orEmpty()
    val selectedRepo = repos.firstOrNull { it.id == selectedRepoId }
    val detail = detailState as? GitRepoDetailState.Ready
    val currentBranch = detail?.branches?.firstOrNull { it.isCurrent }
    val changeCount = detail?.status?.uniqueChangeCount() ?: 0
    val stageSelection: () -> Unit = {
        val unstaged = selection.filter { it.filter != FileFilter.Staged }.map { it.path }
        if (unstaged.isEmpty()) {
            viewModel.unstage(selection.map { it.path }.distinct())
        } else {
            viewModel.stage(unstaged.distinct())
        }
        selection = emptySet()
    }
    val discardSelection: () -> Unit = {
        viewModel.currentTarget()?.let { target ->
            val discardable = selection.filter { it.filter != FileFilter.Staged }
            if (discardable.isNotEmpty()) {
                pendingConfirm = ConfirmationRequest.Discard(
                    paths = discardable.map { it.path }.distinct(),
                    deleteUntracked = discardable.any { it.filter == FileFilter.Untracked },
                    target = target,
                )
            }
        }
    }

    LaunchedEffect(repos, selectedRepoId) {
        if (repos.size == 1 && selectedRepoId == null) viewModel.selectRepo(repos.single().id)
    }
    LaunchedEffect(selectedRepoId) {
        selection = emptySet()
        expandedPath = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.git_state_back))
                    }
                },
                title = {
                    Surface(
                        onClick = { showRepos = true },
                        enabled = repos.isNotEmpty(),
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    selectedRepo?.name ?: stringResource(R.string.git_state_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (repos.isNotEmpty()) Icon(Icons.Filled.ExpandMore, "Choose repository", Modifier.size(20.dp))
                            }
                            selectedRepo?.let {
                                Text(branchSummary(it, currentBranch), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { selectedRepo?.let { viewModel.selectRepo(it.id) } ?: viewModel.loadRepos() }) {
                        Icon(Icons.Filled.Refresh, "Refresh Git workspace")
                    }
                    Box {
                        IconButton(onClick = { showOverflow = true }) { Icon(Icons.Filled.MoreVert, "More Git actions") }
                        DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                            DropdownMenuItem(text = { Text("Choose repository") }, onClick = { showOverflow = false; showRepos = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.git_state_branches)) }, enabled = detail != null, onClick = { showOverflow = false; showBranches = true })
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (detail != null) {
                Surface(shadowElevation = 8.dp, tonalElevation = 2.dp) {
                    Column {
                        if (selection.isNotEmpty()) {
                            SelectionRail(
                                count = selection.size,
                                canWrite = hasGrant,
                                allStaged = selection.all { it.filter == FileFilter.Staged },
                                onStage = stageSelection,
                                onDiscard = discardSelection,
                            )
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { showBranches = true }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.AccountTree, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.git_state_branches))
                            }
                            Button(onClick = { showCommit = true }, enabled = hasGrant && detail.status.counts.staged > 0, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.git_state_commit))
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        when (val state = reposState) {
            GitStateUiState.Loading -> FullState(Modifier.padding(padding), true, "Finding repositories")
            is GitStateUiState.Unavailable -> UnavailableState(Modifier.padding(padding), state.message, viewModel::loadRepos)
            is GitStateUiState.Error -> UnavailableState(Modifier.padding(padding), state.message, viewModel::loadRepos)
            is GitStateUiState.Ready -> when {
                state.repos.isEmpty() -> FullState(Modifier.padding(padding), false, "No Git repositories found", "Add a repository to the host's configured Git roots, then refresh.")
                selectedRepo == null -> RepositoryPrompt(Modifier.padding(padding), state.repos, viewModel::selectRepo)
                else -> WorkspaceBody(
                    modifier = Modifier.padding(padding),
                    repo = selectedRepo,
                    reposNotice = state.notice,
                    detailState = detailState,
                    mutation = mutation,
                    stashNotice = stashNotice,
                    hasGrant = hasGrant,
                    filter = filter,
                    onFilter = { filter = it },
                    selection = selection,
                    onToggleSelected = { file ->
                        selection = if (file in selection) selection - file else selection + file
                    },
                    expandedPath = expandedPath,
                    contentMode = contentMode,
                    contentState = contentState,
                    onOpen = { file, mode ->
                        if (file.filter != FileFilter.Untracked) {
                            val opening = expandedPath != file.path || contentMode != mode
                            expandedPath = if (opening) file.path else null
                            contentMode = mode
                            if (opening) {
                                if (mode == ContentMode.File) viewModel.loadFile(file.path)
                                else viewModel.loadDiff(file.path, if (file.filter == FileFilter.Staged) "staged" else "unstaged")
                            }
                        }
                    },
                    onFetch = viewModel::fetch,
                    onPull = viewModel::pull,
                    onPush = { viewModel.currentTarget()?.let { pendingConfirm = ConfirmationRequest.Push(it) } },
                    onClearMutation = viewModel::clearMutationError,
                    onRetry = { viewModel.selectRepo(selectedRepo.id) },
                )
            }
        }
    }

    if (showRepos) RepositorySheet(repos, selectedRepo?.id, { showRepos = false }) { showRepos = false; viewModel.selectRepo(it) }
    if (showBranches && detail != null) {
        BranchSheet(
            branches = detail.branches,
            hasGrant = hasGrant,
            dirty = changeCount > 0,
            onDismiss = { showBranches = false },
            onSwitch = { ref ->
                showBranches = false
                if (changeCount > 0) viewModel.currentTarget()?.let { pendingConfirm = ConfirmationRequest.DirtyCheckout(ref, it) } else viewModel.checkout(ref)
            },
            onStashSwitch = { showBranches = false; viewModel.stashCheckout(it) },
            onCreate = { name, track -> showBranches = false; viewModel.checkout("", newBranch = name, track = track) },
        )
    }
    if (showCommit && detail != null) {
        val stagedPaths = detail.status.staged.map { it.path }
        CommitDialog(
            hasStaged = stagedPaths.isNotEmpty(),
            generating = messageGeneration is GitMessageGenerationState.Loading,
            generatedMessage = (messageGeneration as? GitMessageGenerationState.Ready)?.message.orEmpty(),
            generationNotice = (messageGeneration as? GitMessageGenerationState.Ready)?.notice,
            pushAfter = pushAfter,
            onPushAfterChange = { pushAfter = it },
            onGenerate = { viewModel.generateCommitMessage(stagedPaths) },
            onDismiss = { showCommit = false },
            onCommit = { message ->
                showCommit = false
                viewModel.commit(message) { target -> if (pushAfter) pendingConfirm = ConfirmationRequest.Push(target) }
            },
        )
    }
    pendingConfirm?.let { request ->
        ConfirmationDialog(
            request,
            onDismiss = { pendingConfirm = null },
            onDiscard = { paths, deleteUntracked, target -> pendingConfirm = null; viewModel.discard(paths, GitConfirmationStrings.DISCARD, deleteUntracked, target) },
            onPush = { target -> pendingConfirm = null; viewModel.push(GitConfirmationStrings.PUSH, expectedTarget = target) },
            onCheckout = { ref, target -> pendingConfirm = null; viewModel.checkout(ref, GitConfirmationStrings.DIRTY_CHECKOUT, expectedTarget = target) },
        )
    }
}

@Composable
private fun WorkspaceBody(
    modifier: Modifier,
    repo: GitRepo,
    reposNotice: String?,
    detailState: GitRepoDetailState,
    mutation: GitMutationState,
    stashNotice: String?,
    hasGrant: Boolean,
    filter: FileFilter,
    onFilter: (FileFilter) -> Unit,
    selection: Set<DisplayFile>,
    onToggleSelected: (DisplayFile) -> Unit,
    expandedPath: String?,
    contentMode: ContentMode,
    contentState: GitContentViewState,
    onOpen: (DisplayFile, ContentMode) -> Unit,
    onFetch: () -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit,
    onClearMutation: () -> Unit,
    onRetry: () -> Unit,
) {
    when (detailState) {
        GitRepoDetailState.Idle, GitRepoDetailState.Loading -> FullState(modifier, true, "Loading workspace")
        is GitRepoDetailState.Error -> UnavailableState(modifier, detailState.message, onRetry)
        is GitRepoDetailState.Ready -> {
            val status = detailState.status
            val allFiles = buildList {
                status.staged.forEach { add(DisplayFile(it.path, FileFilter.Staged, it.additions, it.deletions)) }
                status.modified.forEach { add(DisplayFile(it.path, FileFilter.Modified, it.additions, it.deletions)) }
                status.untracked.forEach { add(DisplayFile(it.path, FileFilter.Untracked, it.additions, it.deletions)) }
            }
            val visible = allFiles.filter { filter == FileFilter.All || it.filter == filter }
            LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 18.dp)) {
                item {
                    SummaryRail(repo, detailState)
                    RemoteActions(hasGrant, status, onFetch, onPull, onPush)
                    reposNotice?.let { NoticeCard(it) }
                    if (!hasGrant) WriteGrantNotice()
                    MutationBanner(mutation, onClearMutation)
                    stashNotice?.let { NoticeCard(it) }
                    if (status.truncated) NoticeCard(stringResource(R.string.git_state_truncated), error = true)
                    FilterRow(filter, status, onFilter)
                }
                if (visible.isEmpty()) item { EmptyFilterState(filter) }
                FileFilter.entries.filter { it != FileFilter.All }.forEach { group ->
                    val files = visible.filter { it.filter == group }
                    if (files.isNotEmpty()) {
                        item { GroupHeader(group.name, files.size) }
                        items(files, key = { "${group.name}:${it.path}" }) { file ->
                            FileRow(
                                file = file,
                                selected = file in selection,
                                expanded = expandedPath == file.path,
                                mode = contentMode,
                                contentState = contentState,
                                onToggleSelected = { onToggleSelected(file) },
                                onOpen = { onOpen(file, it) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryRail(repo: GitRepo, detail: GitRepoDetailState.Ready) {
    val count = detail.status.uniqueChangeCount()
    val current = detail.branches.firstOrNull { it.isCurrent }
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$count changes", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            VerticalDivider()
            Text("+${detail.status.counts.additions}", color = Color(0xFF2E7D32), style = MaterialTheme.typography.labelLarge)
            VerticalDivider()
            Text("−${detail.status.counts.deletions}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            Surface(
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                color = Color.Transparent,
            ) {
                Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AccountTree, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp))
                    Text(current?.name ?: repo.currentBranch ?: "Detached", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    if (repo.dirty || count > 0) { Spacer(Modifier.width(7.dp)); Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape)) }
                }
            }
        }
    }
}

@Composable
private fun VerticalDivider() {
    Box(
        Modifier.padding(horizontal = 10.dp).width(1.dp).height(22.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun RemoteActions(hasGrant: Boolean, status: GitStatus, onFetch: () -> Unit, onPull: () -> Unit, onPush: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onFetch, enabled = hasGrant && status.counts.untracked == 0, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.git_state_fetch))
        }
        VerticalDivider()
        TextButton(onClick = onPull, enabled = hasGrant, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.git_state_pull))
        }
        VerticalDivider()
        TextButton(onClick = onPush, enabled = hasGrant && status.counts.staged == 0, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.FileUpload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.git_state_push))
        }
    }
}

@Composable
private fun FilterRow(selected: FileFilter, status: GitStatus, onSelect: (FileFilter) -> Unit) {
    val counts = mapOf(
        FileFilter.All to status.uniqueChangeCount(),
        FileFilter.Staged to status.counts.staged,
        FileFilter.Modified to status.counts.modified,
        FileFilter.Untracked to status.counts.untracked,
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FileFilter.entries.forEach { item ->
            val isSelected = selected == item
            Surface(
                onClick = { onSelect(item) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                border = BorderStroke(
                    1.dp,
                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Box(Modifier.padding(horizontal = 3.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "${item.name} ${counts.getValue(item)}",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun GitStatus.uniqueChangeCount(): Int = counts.changes.takeIf { it >= 0 } ?: buildSet {
    staged.forEach { add(it.path) }
    modified.forEach { add(it.path) }
    untracked.forEach { add(it.path) }
}.size

@Composable
private fun FileRow(
    file: DisplayFile,
    selected: Boolean,
    expanded: Boolean,
    mode: ContentMode,
    contentState: GitContentViewState,
    onToggleSelected: () -> Unit,
    onOpen: (ContentMode) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .then(
                    if (file.filter == FileFilter.Untracked) Modifier
                    else Modifier.clickable { onOpen(ContentMode.Diff) },
                )
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelected() },
                modifier = Modifier.semantics {
                    contentDescription = "Select ${file.path}"
                },
            )
            Column(Modifier.weight(1f)) {
                Text(file.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                file.path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (file.filter == FileFilter.Untracked) {
                    Text("Preview unavailable until staged", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            file.additions?.let { additions ->
                Text("+$additions", color = Color(0xFF2E7D32), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(5.dp))
            }
            file.deletions?.let { deletions ->
                Text("−$deletions", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(5.dp))
            }
            StatusBadge(file.filter)
            if (file.filter != FileFilter.Untracked) {
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (expanded) "Collapse" else "Expand")
            }
        }
        if (expanded && file.filter != FileFilter.Untracked) {
            Row(Modifier.padding(start = 56.dp, end = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (file.filter != FileFilter.Untracked) FilterChip(mode == ContentMode.Diff, { onOpen(ContentMode.Diff) }, label = { Text("Diff") })
                FilterChip(mode == ContentMode.File, { onOpen(ContentMode.File) }, label = { Text("File") })
            }
            InlineContent(contentState, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
        }
        HorizontalDivider(Modifier.padding(start = 56.dp))
    }
}

@Composable
private fun StatusBadge(filter: FileFilter) {
    val label = when (filter) { FileFilter.Staged -> "S"; FileFilter.Modified -> "M"; FileFilter.Untracked -> "U"; else -> "" }
    val color = when (filter) { FileFilter.Staged -> MaterialTheme.colorScheme.primary; FileFilter.Modified -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.secondary }
    Surface(shape = CircleShape, color = color.copy(alpha = 0.14f), modifier = Modifier.padding(horizontal = 8.dp)) {
        Text(label, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
    }
}

@Composable
private fun InlineContent(state: GitContentViewState, modifier: Modifier) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        when (state) {
            GitContentViewState.Idle, GitContentViewState.Loading -> Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
            is GitContentViewState.Error -> Text(state.message, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            is GitContentViewState.Diff -> MonospaceContent(state.diff.diff.ifEmpty { stringResource(R.string.git_state_no_changes) }, state.diff.truncated)
            is GitContentViewState.File -> MonospaceContent(state.file.content, state.file.truncated)
        }
    }
}

@Composable
private fun MonospaceContent(text: String, truncated: Boolean) {
    Column(Modifier.padding(12.dp)) {
        if (truncated) Text(stringResource(R.string.git_state_truncated), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().height(220.dp).verticalScroll(rememberScrollState()))
    }
}

@Composable
private fun SelectionRail(count: Int, canWrite: Boolean, allStaged: Boolean, onStage: () -> Unit, onDiscard: () -> Unit) {
    val canDiscard = canWrite && !allStaged
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$count selected", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = onStage, enabled = canWrite) {
                Text(
                    if (allStaged) "Unstage" else "Stage",
                    color = if (canWrite) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDiscard, enabled = canDiscard) {
                Text(
                    stringResource(R.string.git_state_discard),
                    color = if (canDiscard) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RepositoryPrompt(modifier: Modifier, repos: List<GitRepo>, onSelect: (String) -> Unit) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Choose a repository", style = MaterialTheme.typography.headlineSmall)
        Text("Git actions stay scoped to the repository you select.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        repos.forEach { repo -> RepositoryRow(repo, false) { onSelect(repo.id) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepositorySheet(repos: List<GitRepo>, selectedId: String?, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Repositories", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(8.dp))
            repos.forEach { repo -> RepositoryRow(repo, repo.id == selectedId) { onSelect(repo.id) } }
        }
    }
}

@Composable
private fun RepositoryRow(repo: GitRepo, selected: Boolean, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AccountTree, null); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(repo.name, style = MaterialTheme.typography.titleSmall)
                Text(repo.currentBranch ?: repo.root, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (repo.dirty) Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape))
            if (selected) { Spacer(Modifier.width(8.dp)); Icon(Icons.Filled.Check, "Selected") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BranchSheet(
    branches: List<GitBranch>,
    hasGrant: Boolean,
    dirty: Boolean,
    onDismiss: () -> Unit,
    onSwitch: (String) -> Unit,
    onStashSwitch: (String) -> Unit,
    onCreate: (String, Boolean) -> Unit,
) {
    var newBranch by rememberSaveable { mutableStateOf("") }
    var track by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.git_state_branches), style = MaterialTheme.typography.titleLarge)
            branches.forEach { branch ->
                Surface(shape = RoundedCornerShape(12.dp), color = if (branch.isCurrent) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(branch.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                            branch.upstream?.let { Text("$it · ahead ${branch.ahead} · behind ${branch.behind}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        if (branch.isCurrent) Text(stringResource(R.string.git_state_current), color = MaterialTheme.colorScheme.primary)
                        else {
                            TextButton(onClick = { onSwitch(branch.name) }, enabled = hasGrant) { Text(stringResource(R.string.git_state_switch)) }
                            if (dirty) TextButton(onClick = { onStashSwitch(branch.name) }, enabled = hasGrant) { Text(stringResource(R.string.git_state_switch_stash)) }
                        }
                    }
                }
            }
            HorizontalDivider(); Text("Create branch", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(newBranch, { newBranch = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.git_state_new_branch_hint)) }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(track, { track = it }, enabled = hasGrant); Text(stringResource(R.string.git_state_track_remote)) }
            Button(onClick = { onCreate(newBranch.trim(), track) }, enabled = hasGrant && newBranch.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.git_state_create_branch)) }
        }
    }
}

@Composable
private fun CommitDialog(
    hasStaged: Boolean,
    generating: Boolean,
    generatedMessage: String,
    generationNotice: String?,
    pushAfter: Boolean,
    onPushAfterChange: (Boolean) -> Unit,
    onGenerate: () -> Unit,
    onDismiss: () -> Unit,
    onCommit: (String) -> Unit,
) {
    var message by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(generatedMessage) { if (generatedMessage.isNotBlank()) message = generatedMessage }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.git_state_commit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    message, { message = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.git_state_commit_message_hint)) }, minLines = 3,
                    trailingIcon = {
                        IconButton(onClick = onGenerate, enabled = hasStaged && !generating) {
                            if (generating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Filled.AutoAwesome, stringResource(R.string.git_state_generate_message))
                        }
                    },
                )
                generationNotice?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall) }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(pushAfter, onPushAfterChange); Text(stringResource(R.string.git_state_push_after_commit)) }
            }
        },
        confirmButton = { TextButton(onClick = { onCommit(message.trim()) }, enabled = hasStaged && message.isNotBlank()) { Text(stringResource(R.string.git_state_commit_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.git_state_cancel)) } },
    )
}

private sealed interface ConfirmationRequest {
    data class Discard(val paths: List<String>, val deleteUntracked: Boolean, val target: GitTarget) : ConfirmationRequest
    data class Push(val target: GitTarget) : ConfirmationRequest
    data class DirtyCheckout(val ref: String, val target: GitTarget) : ConfirmationRequest
}

@Composable
private fun ConfirmationDialog(
    request: ConfirmationRequest,
    onDismiss: () -> Unit,
    onDiscard: (List<String>, Boolean, GitTarget) -> Unit,
    onPush: (GitTarget) -> Unit,
    onCheckout: (String, GitTarget) -> Unit,
) {
    val title = stringResource(when (request) {
        is ConfirmationRequest.Discard -> R.string.git_state_confirm_discard_title
        is ConfirmationRequest.Push -> R.string.git_state_confirm_push_title
        is ConfirmationRequest.DirtyCheckout -> R.string.git_state_confirm_checkout_dirty_title
    })
    val text = stringResource(when (request) {
        is ConfirmationRequest.Discard -> R.string.git_state_confirm_discard_text
        is ConfirmationRequest.Push -> R.string.git_state_confirm_push_text
        is ConfirmationRequest.DirtyCheckout -> R.string.git_state_confirm_checkout_dirty_text
    })
    val confirm = stringResource(when (request) {
        is ConfirmationRequest.Discard -> R.string.git_state_confirm_discard_confirm
        is ConfirmationRequest.Push -> R.string.git_state_confirm_push_confirm
        is ConfirmationRequest.DirtyCheckout -> R.string.git_state_confirm_checkout_confirm
    })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = {
            when (request) {
                is ConfirmationRequest.Discard -> onDiscard(request.paths, request.deleteUntracked, request.target)
                is ConfirmationRequest.Push -> onPush(request.target)
                is ConfirmationRequest.DirtyCheckout -> onCheckout(request.ref, request.target)
            }
        }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.git_state_cancel)) } },
    )
}

@Composable
private fun MutationBanner(mutation: GitMutationState, onClear: () -> Unit) {
    when (mutation) {
        GitMutationState.Idle -> Unit
        is GitMutationState.InProgress -> NoticeCard("${mutation.label} in progress…", loading = true)
        is GitMutationState.Success -> NoticeCard("${displayLabel(mutation.label)} completed · ${mutation.head.take(8)}")
        is GitMutationState.Error -> Card(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${displayLabel(mutation.label)} failed: ${mutation.message}",
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
                IconButton(onClick = onClear) { Icon(Icons.Filled.Close, "Dismiss error") }
            }
        }
    }
}

@Composable
private fun NoticeCard(message: String, error: Boolean = false, loading: Boolean = false) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (loading) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
            Text(message, color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun WriteGrantNotice() = NoticeCard(stringResource(R.string.git_state_write_grant_required))

@Composable
private fun GroupHeader(label: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold); Spacer(Modifier.width(6.dp))
        Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyFilterState(filter: FileFilter) {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        Text(if (filter == FileFilter.All) "Working tree clean" else "No ${filter.name.lowercase()} files", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FullState(modifier: Modifier, loading: Boolean, title: String, detail: String? = null) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) CircularProgressIndicator(); Text(title, style = MaterialTheme.typography.titleMedium)
            detail?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun UnavailableState(modifier: Modifier, message: String, onRetry: () -> Unit) {
    val missingRoute = message.contains("404") || message.contains("No such API endpoint", true)
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Close, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(32.dp))
            Text(if (missingRoute) "Git isn't available on this host yet" else "Git workspace unavailable", style = MaterialTheme.typography.titleMedium)
            Text(
                if (missingRoute) "The host advertised Git, but its API route is not currently reachable. Restart or update the Dashboard, then retry." else message.ifBlank { "The Git service did not return a usable response." },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onRetry) { Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Retry") }
        }
    }
}

private fun branchSummary(repo: GitRepo, branch: GitBranch?): String {
    val name = branch?.name ?: repo.currentBranch ?: "Detached HEAD"
    val tracking = buildList {
        if ((branch?.ahead ?: 0) > 0) add("ahead ${branch?.ahead}")
        if ((branch?.behind ?: 0) > 0) add("behind ${branch?.behind}")
    }
    return if (tracking.isEmpty()) name else "$name · ${tracking.joinToString(" · ")}"
}

private fun displayLabel(label: String): String = label.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
