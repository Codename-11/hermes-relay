package com.hermesandroid.companion.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermesandroid.companion.network.ConnectionState
import com.hermesandroid.companion.ui.components.MessageBubble
import com.hermesandroid.companion.ui.components.ToolProgressCard
import com.hermesandroid.companion.viewmodel.ChatViewModel
import com.hermesandroid.companion.viewmodel.ConnectionViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatViewModel: ChatViewModel,
    connectionViewModel: ConnectionViewModel
) {
    val messages by chatViewModel.messages.collectAsState()
    val isStreaming by chatViewModel.isStreaming.collectAsState()
    val currentProfile by chatViewModel.currentProfile.collectAsState()
    val profiles by chatViewModel.profiles.collectAsState()
    val connectionState by connectionViewModel.connectionState.collectAsState()
    val error by chatViewModel.error.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var profileMenuExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        // Top bar with profile selector
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Hermes Chat")

                    // Connection indicator
                    Icon(
                        imageVector = Icons.Filled.Circle,
                        contentDescription = connectionState.name,
                        tint = when (connectionState) {
                            ConnectionState.Connected -> MaterialTheme.colorScheme.primary
                            ConnectionState.Connecting,
                            ConnectionState.Reconnecting -> MaterialTheme.colorScheme.tertiary
                            ConnectionState.Disconnected -> MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(8.dp)
                    )
                }
            },
            actions = {
                // Profile selector
                Box {
                    TextButton(onClick = { profileMenuExpanded = true }) {
                        Text(currentProfile)
                        Icon(
                            imageVector = Icons.Filled.ArrowDropDown,
                            contentDescription = "Select profile"
                        )
                    }
                    DropdownMenu(
                        expanded = profileMenuExpanded,
                        onDismissRequest = { profileMenuExpanded = false }
                    ) {
                        profiles.forEach { profile ->
                            DropdownMenuItem(
                                text = { Text(profile) },
                                onClick = {
                                    chatViewModel.selectProfile(profile)
                                    profileMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        // Error banner
        AnimatedVisibility(visible = error != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { chatViewModel.clearError() }) {
                    Text("Dismiss")
                }
            }
        }

        // Message list or empty state
        if (messages.isEmpty() && !isStreaming) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.Chat,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No messages yet",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Send a message to start chatting with your agent",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    if (connectionState != ConnectionState.Connected) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Connect to your server first",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Spacer(modifier = Modifier.height(8.dp)) }

                items(messages, key = { it.id }) { message ->
                    MessageBubble(message = message)

                    // Show tool progress cards for messages with tool calls
                    message.toolCalls.forEach { toolCall ->
                        Spacer(modifier = Modifier.height(4.dp))
                        ToolProgressCard(toolCall = toolCall)
                    }
                }

                // Streaming indicator
                if (isStreaming) {
                    item {
                        Text(
                            text = "...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, top = 4.dp)
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
        }

        // Input bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message...") },
                maxLines = 4,
                enabled = connectionState == ConnectionState.Connected
            )

            IconButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        chatViewModel.sendMessage(inputText)
                        inputText = ""
                    }
                },
                enabled = inputText.isNotBlank() &&
                    connectionState == ConnectionState.Connected &&
                    !isStreaming
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send message",
                    tint = if (inputText.isNotBlank() && connectionState == ConnectionState.Connected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}
