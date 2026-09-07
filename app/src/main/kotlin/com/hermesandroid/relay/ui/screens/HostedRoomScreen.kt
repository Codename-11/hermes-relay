package com.hermesandroid.relay.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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
fun HostedRoomRoute(room: BotGroupRoom?, controller: HostedRoomController, onBack: () -> Unit) {
    if (room?.hosted != true) {
        BotGroupDetailScreen(room, onBack)
        return
    }
    val state by controller.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
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
                            require(output.size() + count <= 15_000_000) { "Files must be at most 15 MB" }
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
        onDraft = { scope.launch { controller.editDraft(it) } },
        onSend = { scope.launch { controller.send() } },
        onThread = { scope.launch { controller.selectThread(it) } },
        onRefresh = { scope.launch { controller.refresh() } },
        onRead = { scope.launch { controller.markRead() } },
        onAction = { action, choice -> scope.launch { controller.act(action, choice) } },
        onAttach = { pickerOwner = room.key to state.selectedThread; picker.launch(arrayOf("*/*")) },
        onDiscard = { scope.launch { controller.discardDraft() } },
        onRemoveAttachment = { scope.launch { controller.removeAttachment(it) } },
        onDownload = { eventId, attachment -> scope.launch {
            controller.readAttachment(eventId, attachment).onSuccess {
                download = it; saveFile.launch(attachment.roomString("name").ifBlank { "attachment" })
            }.onFailure { fileError = it.message }
        } },
    )
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
    onDiscard: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {}, onDownload: (String, JsonObject) -> Unit = { _, _ -> },
) {
    var search by remember(state.room?.key) { mutableStateOf("") }
    var confirmDiscard by remember(state.room?.key) { mutableStateOf(false) }
    var confirmRetry by remember(state.room?.key) { mutableStateOf<JsonObject?>(null) }
    val messages = state.visibleMessages.filter { search.isBlank() || it.text.contains(search, true) || it.senderName.contains(search, true) }
    Scaffold(topBar = { TopAppBar(title = { Text(state.room?.name ?: "Shared room") },
        navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
        actions = { TextButton(onClick = onRefresh) { Text("Refresh") } }) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(12.dp)) {
                    state.explanation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (state.capabilities.writable) {
                        LazyRow {
                            items(state.room?.members.orEmpty().filter { !it.retired && !it.handle.isNullOrBlank() }) { member ->
                                TextButton(enabled = state.canSend && state.pendingId == null,
                                    onClick = { onDraft(state.draft + " @${member.handle} ") }) { Text("@${member.handle}") }
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
                            TextButton(onClick = onAttach, enabled = state.canSend && state.pendingId == null && "groups.attachment.put" in state.capabilities.methods) { Text("Attach file") }
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
                Text("${state.room?.route?.connectionLabel.orEmpty()} | ${state.unread} unread in this view", style = MaterialTheme.typography.labelMedium)
                Row { TextButton(onClick = onRead) { Text("Mark read") }
                    TextButton(onClick = { onAction(null, null) }, enabled = state.ready && state.capabilities.driver && "groups.stop" in state.capabilities.methods) { Text("Stop whole room") } }
                if (state.status.isNotEmpty()) {
                    val summary = when { state.status.roomBool("blocked") -> "Blocked"; state.status.roomBool("working") -> "Working"; state.status.roomBool("running") -> "Idle"; else -> "Driver unavailable" }
                    Text("Room status: $summary", style = MaterialTheme.typography.labelLarge)
                    (state.status["counts"] as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { counts ->
                        Text(counts.entries.joinToString(" | ") { "${it.key}: ${it.value}" }, style = MaterialTheme.typography.bodySmall)
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("Search loaded history") }, modifier = Modifier.fillMaxWidth())
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
                        "retry" -> TextButton(enabled = state.ready && "groups.retry" in state.capabilities.methods, onClick = { confirmRetry = action }) { Text("Retry task") }
                        "approval" -> Row {
                            TextButton(enabled = state.ready && "groups.approve" in state.capabilities.methods, onClick = { onAction(action, "once") }) { Text("Allow once") }
                            TextButton(enabled = state.ready && "groups.approve" in state.capabilities.methods, onClick = { onAction(action, "deny") }) { Text("Deny") }
                        }
                        else -> Text("This request needs the owning runtime. This gateway has no supported room input action.")
                    }
                } }
            }
            items(messages, key = { it.id ?: it.seq.toString() }) { message ->
                Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(message.senderName, style = MaterialTheme.typography.titleSmall)
                        Text("${message.senderKind} | ${message.senderId.orEmpty()}${message.senderSource?.let { " | $it" }.orEmpty()}", style = MaterialTheme.typography.labelSmall)
                        Text(message.text)
                        message.attachments.forEach { attachment ->
                            TextButton(enabled = state.ready && "groups.attachment.read" in state.capabilities.methods,
                                onClick = { onDownload(message.id.orEmpty(), attachment) }) { Text("Save ${attachment.roomString("name")} | ${attachment.roomLong("size")} bytes") }
                        }
                        TextButton(onClick = { onThread(message.threadId) }) { Text("Reply in thread") }
                    }
                }
            }
        }
    }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("Discard this local draft?") },
        text = { Text("The original send may already have been accepted. This only removes the local draft and retry record; canonical history is preserved.") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onDiscard() }) { Text("Discard draft") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Cancel") } })
    confirmRetry?.let { action -> AlertDialog(onDismissRequest = { confirmRetry = null }, title = { Text("Retry this exact task?") },
        text = { Text("The previous outcome may be uncertain. Retry may repeat native work for task ${action.roomString("task_id")}.") },
        confirmButton = { TextButton(onClick = { confirmRetry = null; onAction(action, null) }) { Text("Retry task") } },
        dismissButton = { TextButton(onClick = { confirmRetry = null }) { Text("Cancel") } }) }
}
