package com.hermesandroid.relay.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hermesandroid.relay.data.*
import com.hermesandroid.relay.viewmodel.connection.HostedRoomController
import com.hermesandroid.relay.viewmodel.connection.HostedRoomViewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

@Composable
fun HostedRoomRoute(room: BotGroupRoom?, controller: HostedRoomController, onBack: () -> Unit, availableBots: List<BotRosterEntry> = emptyList()) {
    if (room?.hosted != true) {
        BotGroupDetailScreen(room, onBack)
        return
    }
    val state by controller.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var manageRoom by remember { mutableStateOf(false) }
    var showFiles by remember { mutableStateOf(false) }
    var fileError by remember { mutableStateOf<String?>(null) }
    var download by remember { mutableStateOf<ByteArray?>(null) }
    var pickerOwner by remember { mutableStateOf<Pair<String, String?>?>(null) }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = download
        download = null
        if (uri != null && bytes != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Unable to open destination") } }
                .onFailure { fileError = it.message }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val owner = pickerOwner
        pickerOwner = null
        if (uri != null && owner != null) scope.launch {
            runCatching {
                val selected = withContext(Dispatchers.IO) {
                    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: "attachment"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= HOSTED_ROOM_ANDROID_UPLOAD_MAX_BYTES) { "Files must be at most 12 MB on Android" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: error("Unable to open file")
                    Triple(name, mime, bytes)
                }
                controller.upload(owner.first, owner.second, selected.first, selected.second, selected.third)
            }.onFailure { fileError = it.message }
        }
    }
    LaunchedEffect(room.key) { controller.open(room) }
    DisposableEffect(room.key) { onDispose { controller.close() } }
    LaunchedEffect(lifecycle, room.key) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { delay(3000); controller.refresh() }
        }
    }
    HostedRoomContent(
        state = if (state.room?.key == room.key) state else HostedRoomViewState(room = room),
        onBack = onBack,
        onManage = { manageRoom = true },
        onFiles = { showFiles = true },
        onExport = { scope.launch { runCatching { controller.exportHistory(room.key) }.onSuccess { download = it; saveFile.launch("room-history.json") }.onFailure { fileError = it.message } } },
        onMention = { member -> scope.launch { controller.mention(member, room.key) } },
        onDraft = { scope.launch { controller.editDraft(it, room.key) } },
        onSend = { val clicked = state; scope.launch { controller.send(room.key, clicked.selectedThread, clicked.draft) } },
        onThread = { scope.launch { controller.selectThread(it, room.key) } },
        onRefresh = { scope.launch { controller.refresh() } },
        onRead = { scope.launch { controller.markRead(room.key) } },
        onSearch = { query, more, owner -> controller.searchMessages(query, more, owner) },
        onEditMessage = { target, text -> controller.editMessage(target.message.id.orEmpty(), target.message.revision, text, target.roomKey) },
        onDeleteMessage = { target -> controller.deleteMessage(target.message.id.orEmpty(), target.message.revision, target.roomKey) },
        onReactMessage = { target, reaction, present -> controller.reactMessage(target.message.id.orEmpty(), reaction, present, target.roomKey) },
        onAction = { action, choice -> scope.launch { controller.act(action, choice, room.key) } },
        onAttach = { pickerOwner = room.key to state.selectedThread; picker.launch(arrayOf("*/*")) },
        onDiscard = { scope.launch { controller.discardDraft(room.key) } },
        onRemoveAttachment = { scope.launch { controller.removeAttachment(it, room.key) } },
        onDownload = { eventId, attachment -> scope.launch {
            controller.readAttachment(eventId, attachment, room.key).onSuccess {
                download = it; saveFile.launch(attachment.roomString("name").ifBlank { "attachment" })
            }.onFailure { fileError = it.message }
        } },
    )
    if (manageRoom) HostedRoomManagementDialog(state, availableBots, controller, { manageRoom = false }, onBack)
    if (showFiles) HostedRoomFilesDialog(state, controller, { showFiles = false }) { eventId, attachment ->
        scope.launch { controller.readAttachment(eventId, attachment, room.key).onSuccess { download = it; saveFile.launch(attachment.roomString("name")) }.onFailure { fileError = it.message } }
    }
    fileError?.let { error -> AlertDialog(onDismissRequest = { fileError = null }, title = { Text("File unavailable") },
        text = { Text(error) }, confirmButton = { TextButton(onClick = { fileError = null }) { Text("OK") } }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostedRoomContent(
    state: HostedRoomViewState,
    onBack: () -> Unit = {}, onDraft: (String) -> Unit = {}, onSend: () -> Unit = {},
    onThread: (String?) -> Unit = {}, onRefresh: () -> Unit = {}, onRead: () -> Unit = {},
    onAction: (JsonObject?, String?) -> Unit = { _, _ -> }, onAttach: () -> Unit = {},
    onMention: ((String) -> Unit)? = null,
    onSearch: suspend (String, Boolean, String?) -> Result<Unit> = { _, _, _ -> Result.success(Unit) },
    onEditMessage: suspend (HostedMessageTarget, String) -> Result<Unit> = { _, _ -> Result.success(Unit) },
    onDeleteMessage: suspend (HostedMessageTarget) -> Result<Unit> = { Result.success(Unit) },
    onReactMessage: suspend (HostedMessageTarget, String, Boolean) -> Result<Unit> = { _, _, _ -> Result.success(Unit) },
    onManage: () -> Unit = {}, onFiles: () -> Unit = {}, onExport: () -> Unit = {},
    onDiscard: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {}, onDownload: (String, JsonObject) -> Unit = { _, _ -> },
) {
    var menu by remember(state.room?.key) { mutableStateOf(false) }
    var messageAction by remember(state.room?.key) { mutableStateOf<HostedMessageTarget?>(null) }
    var search by remember(state.room?.key, state.selectedThread) { mutableStateOf("") }
    var searching by remember(state.room?.key, state.selectedThread) { mutableStateOf(false) }
    var showSearch by remember(state.room?.key, state.selectedThread) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var confirmDiscard by remember(state.room?.key) { mutableStateOf(false) }
    var confirmRetry by remember(state.room?.key) { mutableStateOf<JsonObject?>(null) }
    val searchView = state.capabilities.searchable && showSearch && state.searchSnapshot != null
    val messages = if (searchView) state.searchResults else state.visibleMessages.filter { state.capabilities.searchable || search.isBlank() || it.text.contains(search, true) || it.senderName.contains(search, true) }
    Scaffold(topBar = { TopAppBar(title = { Text(state.room?.name ?: "Shared room") },
        navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
        actions = {
            TextButton(onClick = { menu = true }) { Text("More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Refresh") }, onClick = { menu = false; onRefresh() })
                DropdownMenuItem(text = { Text("Room settings") }, onClick = { menu = false; onManage() })
                DropdownMenuItem(text = { Text("Shared files") }, enabled = "groups.attachment.list" in state.capabilities.methods, onClick = { menu = false; onFiles() })
                DropdownMenuItem(text = { Column { Text("Export immutable source log"); Text("Original edited/deleted content is retained.", style = MaterialTheme.typography.bodySmall) } }, enabled = state.ready && state.capabilities.rawExport, onClick = { menu = false; onExport() })
            }
        }) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(12.dp)) {
                    state.explanation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (state.capabilities.writable) {
                        LazyRow {
                            items(state.room?.members.orEmpty().filter { !it.retired && !it.handle.isNullOrBlank() }) { member ->
                                TextButton(enabled = state.canSend && state.pendingId == null && state.status.roomObjects("peer_routes").none { it.roomString("member_id") == member.memberId && it.roomString("status") != "ready" },
                                    onClick = { if (onMention != null && member.memberId != null) onMention(member.memberId) else onDraft(state.draft + " @${member.handle} ") }) { Text("@${member.handle}") }
                            }
                        }
                        state.attachments.forEach { attachment ->
                            TextButton(enabled = state.pendingId == null, onClick = { onRemoveAttachment(attachment.roomString("attachment_id")) }) {
                                Text("${attachment.roomString("name")} | Remove")
                            }
                        }
                        OutlinedTextField(value = state.draft, onValueChange = onDraft,
                            enabled = !state.busy && state.pendingId == null, modifier = Modifier.fillMaxWidth(), maxLines = 4,
                            label = { Text(if (state.selectedThread == null) "New thread message" else "Reply in selected thread") })
                        if (state.pendingId != null) TextButton(onClick = { confirmDiscard = true }) { Text("Discard local draft") }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = onAttach, enabled = state.canSend && state.pendingId == null && "groups.attachment.put" in state.capabilities.methods) { Text("Attach file (12 MB max)") }
                            Button(onClick = onSend, enabled = state.canSend && (state.draft.isNotBlank() || state.attachments.isNotEmpty())) {
                                Text(if (state.pendingId == null) "Send" else "Retry same send")
                            }
                        }
                        if (state.attachments.isNotEmpty()) Text("Original files are shared. Runtime media support varies; upload acceptance does not confirm understanding.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                val readSummary = when {
                    !state.capabilities.sharedRead -> "${state.unread} unread (local-only)"
                    state.serverUnread != null -> "${state.serverUnread} unread (shared)"
                    else -> "Shared read state unavailable"
                }
                Text("${state.room?.route?.connectionLabel.orEmpty()} | $readSummary", style = MaterialTheme.typography.labelMedium)
                state.readError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row { TextButton(onClick = onRead, enabled = state.ready && !state.busy) { Text(if (state.capabilities.sharedRead) "Mark read" else "Mark read (local-only)") }
                    TextButton(onClick = { onAction(null, null) }, enabled = state.ready && state.capabilities.driver && "groups.stop" in state.capabilities.methods) { Text("Stop whole room") } }
                if (state.status.isNotEmpty()) {
                    val summary = when { state.status.roomBool("blocked") -> "Blocked"; state.status.roomBool("working") -> "Working"; state.status.roomBool("running") -> "Idle"; else -> "Driver unavailable" }
                    Text("Room status: $summary", style = MaterialTheme.typography.labelLarge)
                    (state.status["counts"] as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { counts ->
                        Text(counts.entries.joinToString(" | ") { "${it.key}: ${it.value}" }, style = MaterialTheme.typography.bodySmall)
                    }
                }
                state.status.roomObjects("peer_routes").filter { it.roomString("status") != "ready" }.forEach { route ->
                    val member = state.room?.members?.firstOrNull { it.memberId == route.roomString("member_id") }
                    Text("${member?.name ?: route.roomString("member_id")}: ${route.roomString("status").replace('_', ' ')}", color = MaterialTheme.colorScheme.error)
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text(if (state.capabilities.searchable) "Search canonical history" else "Search loaded history") }, modifier = Modifier.fillMaxWidth())
                if (state.capabilities.searchable) {
                    Row {
                        TextButton(enabled = state.ready && !searching && search.isNotBlank(), onClick = {
                            val owner = state.room?.key; val query = search
                            scope.launch { searching = true; onSearch(query, false, owner).onSuccess { showSearch = true }; searching = false }
                        }) { Text("Search messages") }
                        if (searchView) TextButton(onClick = { showSearch = false }) { Text("Current history") }
                    }
                    state.searchError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (searchView) {
                        Text("Search results · snapshot ${state.searchSnapshot}", style = MaterialTheme.typography.titleSmall)
                        Text("Pinned historical results for: ${state.searchQuery}. Return to current history to change a message.", style = MaterialTheme.typography.bodySmall)
                        if (state.searchResults.isEmpty()) Text("No matching messages")
                    }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = state.selectedThread == null, onClick = { onThread(null) }, label = { Text("All / new thread") }) }
                    items(state.room?.messages.orEmpty().mapNotNull { it.threadId }.distinct()) { thread ->
                        val first = state.room?.messages.orEmpty().first { it.threadId == thread }
                        FilterChip(selected = state.selectedThread == thread, onClick = { onThread(thread) }, label = { Text(first.text.take(28).ifBlank { "File thread" }) })
                    }
                }
            }
            items(state.status.roomObjects("pending_actions")) { action ->
                val kind = action.roomString("kind")
                Surface(tonalElevation = 2.dp) { Column(Modifier.fillMaxWidth().padding(10.dp)) {
                    Text("$kind | task ${action.roomString("task_id")} | ${action.roomString("member_id")}")
                    (action["approval"] as? JsonObject)?.let { approval ->
                        Text(approval.roomString("description"))
                        Text(approval.roomString("command"), style = MaterialTheme.typography.bodyMedium)
                        Text("Request ${action.roomString("request_id")}", style = MaterialTheme.typography.labelSmall)
                    }
                    when (kind) {
                        "retry" -> TextButton(enabled = state.ready && state.capabilities.driver && action.roomString("task_id").isNotBlank() && "groups.retry" in state.capabilities.methods, onClick = { confirmRetry = action }) { Text("Retry task") }
                        "approval" -> Row {
                            TextButton(enabled = state.ready && state.capabilities.driver && listOf("task_id", "member_id", "request_id").all { action.roomString(it).isNotBlank() } && action.roomLong("execution_generation") > 0 && "groups.approve" in state.capabilities.methods, onClick = { onAction(action, "once") }) { Text("Allow once") }
                            TextButton(enabled = state.ready && state.capabilities.driver && listOf("task_id", "member_id", "request_id").all { action.roomString(it).isNotBlank() } && action.roomLong("execution_generation") > 0 && "groups.approve" in state.capabilities.methods, onClick = { onAction(action, "deny") }) { Text("Deny") }
                        }
                        else -> Text("This request needs the owning runtime. This gateway has no supported room input action.")
                    }
                } }
            }
            val activity = state.activity.filter { event -> state.selectedThread == null || (event["payload"] as? JsonObject)?.roomString("thread_id") == state.selectedThread }.takeLast(5).reversed()
            if (activity.isNotEmpty()) item { Text("Recent room activity", style = MaterialTheme.typography.titleSmall) }
            items(activity, key = { "activity:${it.roomString("event_id")}" }) { event ->
                val payload = event["payload"] as? JsonObject ?: JsonObject(emptyMap())
                val member = state.room?.members?.firstOrNull { it.memberId == payload.roomString("member_id") }
                val phase = when (event.roomString("kind")) {
                    "member.unavailable" -> "Unavailable"
                    "turn.started" -> "Working"
                    "turn.settled" -> "Finished"
                    "turn.failed" -> "Failed"
                    "turn.cancelled" -> "Stopped"
                    "turn.deferred" -> "Waiting"
                    else -> payload.roomString("status").ifBlank { "Activity" }
                }
                Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.small) { Column(Modifier.fillMaxWidth().padding(10.dp)) {
                    Text("$phase | ${member?.name ?: payload.roomString("member_id")}", style = MaterialTheme.typography.labelLarge)
                    val detail = payload.roomString("error").ifBlank { payload.roomString("reason").ifBlank { payload.roomString("reason_code").replace('_', ' ') } }
                    if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall)
                    if (payload.roomString("task_id").isNotBlank()) Text("Task ${payload.roomString("task_id")}", style = MaterialTheme.typography.labelSmall)
                    if (payload.roomString("thread_id").isNotBlank()) TextButton(onClick = { onThread(payload.roomString("thread_id")) }) { Text("Open activity thread") }
                } }
            }
            if (searchView && state.searchHasMore) item {
                TextButton(enabled = state.ready && !searching, onClick = {
                    val owner = state.room?.key; val query = state.searchQuery
                    scope.launch { searching = true; onSearch(query, true, owner); searching = false }
                }) { Text("Load more results") }
            }
            items(messages, key = { it.id ?: it.seq.toString() }) { message ->
                Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(message.senderName, style = MaterialTheme.typography.titleSmall)
                        Text("${message.senderKind} | ${message.senderId.orEmpty()}${message.senderSource?.let { " | $it" }.orEmpty()}", style = MaterialTheme.typography.labelSmall)
                        Text(if (message.deleted) "Message deleted" else message.text)
                        HostedMessageControls(state, message, searchView, { messageAction = it }, onReactMessage)
                        (if (message.deleted) emptyList() else message.attachments).forEach { attachment ->
                            TextButton(enabled = state.ready && "groups.attachment.read" in state.capabilities.methods,
                                onClick = { onDownload(message.id.orEmpty(), attachment) }) { Text("Save ${attachment.roomString("name")} | ${attachment.roomLong("size")} bytes") }
                        }
                        TextButton(onClick = { onThread(message.threadId) }) { Text("Reply in thread") }
                    }
                }
            }
        }
    }
    messageAction?.let { target -> HostedMessageDialog(target, state, { messageAction = null }, onEditMessage, onDeleteMessage, onReactMessage) }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("Discard this local draft?") },
        text = { Text("The original send may already have been accepted. This only removes the local draft and retry record; canonical history is preserved.") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onDiscard() }) { Text("Discard draft") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Cancel") } })
    confirmRetry?.let { action -> AlertDialog(onDismissRequest = { confirmRetry = null }, title = { Text("Retry this exact task?") },
        text = { Text("The previous outcome may be uncertain. Retry may repeat native work for task ${action.roomString("task_id")}.") },
        confirmButton = { TextButton(onClick = { confirmRetry = null; onAction(action, null) }) { Text("Retry task") } },
        dismissButton = { TextButton(onClick = { confirmRetry = null }) { Text("Cancel") } }) }
}

@Composable
internal fun CreateHostedRoomDialog(
    available: List<BotRosterEntry>, supported: Boolean, onDismiss: () -> Unit,
    onCreate: suspend (String, String, List<BotRosterEntry>) -> Result<Unit>,
) {
    val roomId = androidx.compose.runtime.saveable.rememberSaveable { java.util.UUID.randomUUID().toString() }
    var title by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("New shared room") },
        text = { Column {
            if (!supported) Text("This gateway does not support hosted room creation. Select a gateway with an active room driver.")
            OutlinedTextField(title, { title = it }, enabled = !saving, label = { Text("Room name") })
            available.firstOrNull()?.route?.connectionLabel?.let { Text("Gateway: $it") }
            Text("Choose two to six members on this gateway.")
            Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) { available.forEach { bot ->
                Row { Checkbox(checked = bot.profile.name in selected, enabled = !saving && supported,
                    onCheckedChange = { selected = if (it) selected + bot.profile.name else selected - bot.profile.name })
                    Text(bot.displayName, Modifier.padding(top = 12.dp)) }
            } }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = supported && !saving && selected.size in 2..6 && title.isNotBlank(), onClick = {
            scope.launch { saving = true; onCreate(roomId, title, available.filter { it.profile.name in selected }).onFailure { error = it.message }; saving = false }
        }) { Text("Create room") } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } })
}

@Composable
internal fun HostedRoomManagementDialog(state: HostedRoomViewState, available: List<BotRosterEntry>, controller: HostedRoomController, onDismiss: () -> Unit, onClosed: () -> Unit) {
    val openedRevision = remember { state.room?.revision }
    val unchanged = openedRevision == state.room?.revision
    val members = state.room?.members.orEmpty().filterNot { it.retired }
    var name by remember { mutableStateOf(state.room?.name.orEmpty()) }
    var keep by remember { mutableStateOf(members.mapNotNull { it.memberId }.toSet()) }
    var added by remember { mutableStateOf(emptySet<String>()) }
    var saving by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val candidates = available.filter { bot -> bot.route?.connectionId == state.room?.route?.connectionId && !bot.stale &&
        state.roomRecord.roomObjects("members").none { it.roomString("profile") == bot.profile.name } &&
        state.roomRecord.roomObjects("retired_members").none { it.roomString("profile") == bot.profile.name } }
    val canMembers = state.ready && unchanged && "groups.members.update" in state.capabilities.methods && "local_membership_revision" in state.capabilities.features
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("Room settings") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Column {
                OutlinedTextField(name, { name = it }, enabled = !saving, label = { Text("Room name") })
                TextButton(enabled = !saving && state.ready && unchanged && name.isNotBlank() && "groups.rename" in state.capabilities.methods && "rename_revision" in state.capabilities.features,
                    onClick = { scope.launch { saving = true; controller.rename(name, state.room?.key, openedRevision).onSuccess { onDismiss() }.onFailure { error = it.message }; saving = false } }) { Text("Save name") }
                if (!unchanged) Text("Room changed while editing. Close and reopen settings before saving.")
                if (!canMembers) Text("This gateway cannot revise membership here.")
            }
            members.forEach { member -> Row {
                Checkbox(checked = member.memberId in keep, enabled = canMembers && !saving,
                    onCheckedChange = { checked -> member.memberId?.let { keep = if (checked) keep + it else keep - it } })
                Text(member.name, Modifier.padding(top = 12.dp))
            } }
            candidates.forEach { bot -> Row {
                Checkbox(checked = bot.profile.name in added, enabled = canMembers && !saving,
                    onCheckedChange = { added = if (it) added + bot.profile.name else added - bot.profile.name })
                Text("Add ${bot.displayName}", Modifier.padding(top = 12.dp))
            } }
            Column {
                TextButton(enabled = canMembers && !saving && (keep.size + added.size) in 2..6, onClick = {
                    scope.launch { saving = true; controller.changeMembers(keep, candidates.filter { it.profile.name in added }, state.room?.key, openedRevision).onSuccess { onDismiss() }.onFailure { error = it.message }; saving = false }
                }) { Text("Save members") }
                Text("Membership updates use the displayed room revision. If the room changed or is busy, refresh before trying again.", style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = state.ready && !saving && "groups.disband" in state.capabilities.methods, onClick = { confirmClose = true }) { Text("Close room permanently") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }, confirmButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Done") } })
    if (confirmClose) AlertDialog(onDismissRequest = { confirmClose = false }, title = { Text("Permanently close this room?") },
        text = { Text("This stops the whole room and closes it for all members. This gateway does not provide an undoable archive. Export history first if you need a retained copy.") },
        confirmButton = { TextButton(enabled = !saving, onClick = { scope.launch { saving = true; controller.disband(state.room?.key).onSuccess { confirmClose = false; onDismiss(); onClosed() }.onFailure { error = it.message; confirmClose = false }; saving = false } }) { Text("Close room") } },
        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Cancel") } })
}

@Composable
internal fun HostedRoomFilesDialog(state: HostedRoomViewState, controller: HostedRoomController, onDismiss: () -> Unit, onSave: (String, JsonObject) -> Unit) {
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { controller.searchFiles("", expectedRoomKey = state.room?.key).onFailure { error = it.message } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Shared files") }, text = { Column {
        OutlinedTextField(query, { query = it }, label = { Text("Search file names") })
        TextButton(enabled = !loading, onClick = { scope.launch { loading = true; controller.searchFiles(query, expectedRoomKey = state.room?.key).onSuccess { error = null }.onFailure { error = it.message }; loading = false } }) { Text("Search") }
        Text(if (state.fileQuery.isBlank()) "All shared files" else "Results for: ${state.fileQuery}", style = MaterialTheme.typography.labelSmall)
        Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) { state.files.forEach { file ->
            TextButton(onClick = { onSave(file.roomString("event_id"), file) }) { Column {
                Text(file.roomString("name"))
                Text("${file.roomLong("size")} bytes | ${(file["producer"] as? JsonObject)?.roomString("label").orEmpty()}", style = MaterialTheme.typography.bodySmall)
            } }
        } }
        if (state.fileCursor != null) TextButton(enabled = !loading, onClick = { scope.launch { loading = true; controller.searchFiles(state.fileQuery, more = true, expectedRoomKey = state.room?.key).onFailure { error = it.message }; loading = false } }) { Text("Load more files") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}
