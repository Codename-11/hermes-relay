package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.data.*
import com.hermesandroid.relay.viewmodel.connection.HostedRoomViewState
import kotlinx.coroutines.launch

/** Immutable click-time identity, never a row position or a refreshed room revision. */
data class HostedMessageTarget(val roomKey: String, val message: BotGroupMessage, val operation: String)

internal fun HostedRoomViewState.isOriginalAuthor(message: BotGroupMessage): Boolean =
    reader.roomString("id").isNotBlank() && reader.roomString("kind").isNotBlank() &&
        reader.roomString("id") == message.senderId && reader.roomString("kind") == message.senderKind

@Composable
internal fun HostedMessageControls(
    state: HostedRoomViewState, message: BotGroupMessage, historical: Boolean,
    onOpen: (HostedMessageTarget) -> Unit,
    onReact: suspend (HostedMessageTarget, String, Boolean) -> Result<Unit>,
) {
    val scope = rememberCoroutineScope()
    val owner = state.room?.key ?: return
    val available = !historical && state.ready && !state.busy && state.room.stale.not() && state.capabilities.mutations && !message.deleted && !message.id.isNullOrBlank()
    val react = available && "groups.message.react" in state.capabilities.methods
    if (state.capabilities.projection && message.revision > message.seq && !message.deleted) Text("Edited · revision ${message.revision}", style = MaterialTheme.typography.labelSmall)
    if (!message.deleted) {
        message.reactions.forEach { reaction ->
            val name = reaction.roomString("reaction")
            val actors = reaction.roomObjects("actors")
            val mine = state.reader.roomString("id").isNotBlank() && actors.any { it.roomString("id") == state.reader.roomString("id") && it.roomString("kind") == state.reader.roomString("kind") }
            TextButton(enabled = react, onClick = {
                val target = HostedMessageTarget(owner, message, "react")
                scope.launch { onReact(target, name, !mine) }
            }) { Text("${if (mine) "Remove" else "Add"} $name (${actors.size})") }
        }
        if (available) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.isOriginalAuthor(message) && message.revision > 0) {
                if ("groups.message.edit" in state.capabilities.methods) TextButton(modifier = Modifier.testTag("edit:${message.id}"), onClick = { onOpen(HostedMessageTarget(owner, message, "edit")) }) { Text("Edit") }
                if ("groups.message.delete" in state.capabilities.methods) TextButton(modifier = Modifier.testTag("delete:${message.id}"), onClick = { onOpen(HostedMessageTarget(owner, message, "delete")) }) { Text("Delete") }
            }
            if (react) TextButton(modifier = Modifier.testTag("react:${message.id}"), onClick = { onOpen(HostedMessageTarget(owner, message, "react")) }) { Text("React") }
        }
    }
}

@Composable
internal fun HostedMessageDialog(
    target: HostedMessageTarget, state: HostedRoomViewState, onDismiss: () -> Unit,
    onEdit: suspend (HostedMessageTarget, String) -> Result<Unit>,
    onDelete: suspend (HostedMessageTarget) -> Result<Unit>,
    onReact: suspend (HostedMessageTarget, String, Boolean) -> Result<Unit>,
) {
    var text by remember(target) { mutableStateOf(if (target.operation == "edit") target.message.text else "") }
    var saving by remember(target) { mutableStateOf(false) }
    var error by remember(target) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val current = state.room?.messages?.firstOrNull { it.id == target.message.id }
    val same = state.room?.key == target.roomKey && current != null && !current.deleted &&
        (target.operation == "react" || current.revision == target.message.revision)
    val allowed = same && state.ready && !state.busy && !saving && state.room?.stale != true &&
        state.capabilities.mutations && "groups.message.${target.operation}" in state.capabilities.methods &&
        (target.operation == "react" || state.isOriginalAuthor(target.message))
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(when (target.operation) { "edit" -> "Edit this message"; "delete" -> "Delete this exact message?"; else -> "React to this message" }) },
        text = { Column {
            Text("Message ${target.message.id} · revision ${target.message.revision}", style = MaterialTheme.typography.labelSmall)
            if (target.operation == "delete") Text("Current history will show a tombstone. The immutable source log retains the original content.")
            else OutlinedTextField(text, { text = it }, enabled = !saving && same, label = { Text(if (target.operation == "edit") "Replacement text" else "Reaction") })
            if (!same) Text("Message or room changed. Close and reopen this action.", color = MaterialTheme.colorScheme.error)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = allowed && (target.operation == "delete" || text.isNotBlank()), onClick = {
            scope.launch {
                saving = true
                val result = when (target.operation) { "edit" -> onEdit(target, text); "delete" -> onDelete(target); else -> onReact(target, text, true) }
                saving = false
                result.onSuccess { onDismiss() }.onFailure { error = it.message ?: "Message action failed" }
            }
        }) { Text(when (target.operation) { "edit" -> "Save edit"; "delete" -> "Delete message"; else -> "Add reaction" }) } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } })
}
