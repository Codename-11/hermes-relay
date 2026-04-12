package com.hermesandroid.relay.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.theme.purpleGlow
import com.hermesandroid.relay.ui.theme.radialNavyBackground
import com.hermesandroid.relay.network.ChatMode
import com.hermesandroid.relay.network.ConnectivityObserver
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import android.content.ClipData
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.ui.platform.LocalContext
import com.hermesandroid.relay.data.Attachment
import com.hermesandroid.relay.ui.components.CommandPalette
import com.hermesandroid.relay.ui.components.ConnectionStatusBadge
import com.hermesandroid.relay.ui.components.CommandRow
import com.hermesandroid.relay.ui.components.CompactToolCall
import com.hermesandroid.relay.ui.components.InlineAutocomplete
import com.hermesandroid.relay.ui.components.MessageBubble
import com.hermesandroid.relay.ui.components.MorphingSphere
import com.hermesandroid.relay.ui.components.SphereState
import com.hermesandroid.relay.ui.components.PersonalityPicker
import com.hermesandroid.relay.ui.components.SessionDrawerContent
import com.hermesandroid.relay.ui.components.SlashCommand
import com.hermesandroid.relay.ui.components.StreamingDots
import com.hermesandroid.relay.ui.components.ToolProgressCard
import com.hermesandroid.relay.ui.components.VoiceModeOverlay
import com.hermesandroid.relay.viewmodel.ChatViewModel
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.viewmodel.VoiceViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DEFAULT_CHAR_LIMIT = 4096

/**
 * Snapshot of the streaming-state fields the auto-scroll effect watches.
 *
 * Captured inside a `snapshotFlow { ... }` so distinctUntilChanged can
 * detect any meaningful change (new message, longer text, longer reasoning,
 * new tool card, streaming on/off) and re-trigger an auto-follow scroll.
 *
 * `equals` is auto-generated by `data class`, which gives field-wise
 * comparison — exactly the behavior distinctUntilChanged needs.
 */
private data class ChatScrollSnapshot(
    val messageCount: Int,
    val lastContentLength: Int,
    val lastThinkingLength: Int,
    val lastToolCallCount: Int,
    val isStreaming: Boolean
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    chatViewModel: ChatViewModel,
    connectionViewModel: ConnectionViewModel,
    voiceViewModel: VoiceViewModel,
    maxBubbleWidth: Dp = 300.dp
) {
    val voiceUiState by voiceViewModel.uiState.collectAsState()
    val chatAlpha by animateFloatAsState(
        targetValue = if (voiceUiState.voiceMode) 0.4f else 1f,
        animationSpec = tween(300),
        label = "chatAlpha",
    )

    // RECORD_AUDIO permission flow — user taps the mic FAB → if not granted,
    // request; on grant, latch the pending-enter and fire enterVoiceMode() in
    // the callback. Denial shows an inline banner above the input.
    val context = LocalContext.current
    var pendingVoiceEnter by remember { mutableStateOf(false) }
    var micPermissionDenied by remember { mutableStateOf(false) }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            micPermissionDenied = false
            if (pendingVoiceEnter) {
                pendingVoiceEnter = false
                voiceViewModel.enterVoiceMode()
            }
        } else {
            pendingVoiceEnter = false
            micPermissionDenied = true
        }
    }
    val requestVoiceMode: () -> Unit = {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            micPermissionDenied = false
            voiceViewModel.enterVoiceMode()
        } else {
            pendingVoiceEnter = true
            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }


    val messages by chatViewModel.messages.collectAsState()
    val isStreaming by chatViewModel.isStreaming.collectAsState()
    val chatReady by connectionViewModel.chatReady.collectAsState()
    val apiReachable by connectionViewModel.apiServerReachable.collectAsState()
    val chatMode by connectionViewModel.chatMode.collectAsState()
    val error by chatViewModel.error.collectAsState()
    val sessions by chatViewModel.sessions.collectAsState()
    val currentSessionId by chatViewModel.currentSessionId.collectAsState()
    val isLoadingHistory by chatViewModel.isLoadingHistory.collectAsState()
    val selectedPersonality by chatViewModel.selectedPersonality.collectAsState()
    val personalityNames by chatViewModel.personalityNames.collectAsState()
    val defaultPersonality by chatViewModel.defaultPersonality.collectAsState()
    val serverModelName by chatViewModel.serverModelName.collectAsState()
    val networkStatus by connectionViewModel.networkStatus.collectAsState()
    val showThinking by connectionViewModel.showThinking.collectAsState()
    val toolDisplay by connectionViewModel.toolDisplay.collectAsState()
    val smoothAutoScroll by connectionViewModel.smoothAutoScroll.collectAsState()

    val availableSkills by chatViewModel.availableSkills.collectAsState()
    val queuedMessages by chatViewModel.queuedMessages.collectAsState()
    val pendingAttachments by chatViewModel.pendingAttachments.collectAsState()
    val maxAttachmentMb by connectionViewModel.maxAttachmentMb.collectAsState()
    val charLimit by connectionViewModel.maxMessageLength.collectAsState()

    // Animation settings
    val animationEnabled by connectionViewModel.animationEnabled.collectAsState()
    val animationBehindChat by connectionViewModel.animationBehindChat.collectAsState()
    var ambientMode by remember { mutableStateOf(false) } // fullscreen sphere, hides chat

    // Sphere state with debounced Thinking→Streaming (min 1.5s in Thinking)
    val rawSphereState by remember {
        derivedStateOf {
            when {
                error != null -> SphereState.Error
                isStreaming -> {
                    val lastMsg = messages.lastOrNull()
                    if (lastMsg?.isThinkingStreaming == true) SphereState.Thinking
                    else SphereState.Streaming
                }
                else -> SphereState.Idle
            }
        }
    }
    var sphereState by remember { mutableStateOf(SphereState.Idle) }
    LaunchedEffect(rawSphereState) {
        if (sphereState == SphereState.Thinking && rawSphereState == SphereState.Streaming) {
            delay(1500L) // hold Thinking for minimum 1.5s
        }
        sphereState = rawSphereState
    }

    // Streaming intensity: ramps up when streaming, decays when idle
    val streamingIntensity by animateFloatAsState(
        targetValue = if (isStreaming) 0.7f else 0f,
        animationSpec = tween(if (isStreaming) 1000 else 2000),
        label = "streamIntensity"
    )

    // Tool call burst: spikes on active tool calls, slow decay
    val hasActiveToolCalls = messages.lastOrNull()?.toolCalls?.any { !it.isComplete } == true
    val toolCallBurst by animateFloatAsState(
        targetValue = if (hasActiveToolCalls) 1f else 0f,
        animationSpec = tween(if (hasActiveToolCalls) 200 else 1200),
        label = "toolBurst"
    )

    var inputText by remember { mutableStateOf("") }
    var showCommandPalette by remember { mutableStateOf(false) }
    var showAgentInfo by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val haptic = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }

    // File picker for attachments (any file type)
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        for (uri in uris) {
            try {
                val contentResolver = context.contentResolver
                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: continue
                val maxSize = maxAttachmentMb * 1024 * 1024
                if (bytes.size > maxSize) {
                    Toast.makeText(context, "File too large (max $maxAttachmentMb MB)", Toast.LENGTH_SHORT).show()
                    continue
                }
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                chatViewModel.addAttachment(
                    Attachment(
                        contentType = mimeType,
                        content = base64,
                        fileName = fileName,
                        fileSize = bytes.size.toLong()
                    )
                )
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to read file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // True when the LazyColumn is scrolled all the way to the bottom.
    // canScrollForward is the canonical signal — no off-by-one arithmetic on
    // visibleItemsInfo (which has to account for header/footer spacers and
    // the StreamingDots indicator). This is also the trigger that resumes
    // auto-follow after the user scrolls back down manually.
    val isAtBottom by remember {
        derivedStateOf { !listState.canScrollForward }
    }

    // True when the user has scrolled up (away from the bottom). The
    // streaming auto-scroll effect respects this — it will not yank the
    // user back to the latest token while they are reading history.
    // Reset to false the moment the user returns to the bottom.
    var userScrolledAway by remember { mutableStateOf(false) }

    // Watch the user's scroll state. Any drag/fling that ends with the list
    // not at the bottom flips userScrolledAway = true. Returning to the
    // bottom (manually or via the FAB) resets it to false.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to isAtBottom }
            .distinctUntilChanged()
            .collectLatest { (scrolling, atBottom) ->
                if (atBottom) {
                    userScrolledAway = false
                } else if (!scrolling) {
                    // Scroll gesture ended above the bottom — user is reading.
                    userScrolledAway = true
                }
            }
    }

    // Scroll-to-bottom FAB visibility
    val showScrollToBottom by remember {
        derivedStateOf { messages.isNotEmpty() && !isAtBottom }
    }

    // Build all commands dynamically: built-in + personalities + server skills
    val allCommands by remember(availableSkills, personalityNames) {
        derivedStateOf {
            // Built-in hermes gateway commands (from hermes_cli/commands.py)
            // Only includes commands available via gateway (not cli_only)
            val builtIn = listOf(
                // Session
                SlashCommand("/new", "Start a new session", "session"),
                SlashCommand("/retry", "Retry the last message", "session"),
                SlashCommand("/undo", "Remove the last exchange", "session"),
                SlashCommand("/title", "Set a title for this session", "session"),
                SlashCommand("/branch", "Branch/fork the current session", "session"),
                SlashCommand("/compress", "Compress conversation context", "session"),
                SlashCommand("/rollback", "List or restore checkpoints", "session"),
                SlashCommand("/stop", "Kill running background processes", "session"),
                SlashCommand("/resume", "Resume a previous session", "session"),
                SlashCommand("/background", "Run a prompt in the background", "session"),
                SlashCommand("/btw", "Side question using session context", "session"),
                SlashCommand("/queue", "Queue a prompt for the next turn", "session"),
                SlashCommand("/approve", "Approve a pending command", "session"),
                SlashCommand("/deny", "Deny a pending command", "session"),
                // Configuration
                SlashCommand("/model", "Switch model for this session", "configuration"),
                SlashCommand("/provider", "Show available providers", "configuration"),
                SlashCommand("/personality", "Set a predefined personality", "configuration"),
                SlashCommand("/verbose", "Cycle tool progress display", "configuration"),
                SlashCommand("/yolo", "Toggle auto-approve mode", "configuration"),
                SlashCommand("/reasoning", "Set reasoning effort level", "configuration"),
                SlashCommand("/voice", "Toggle voice mode", "configuration"),
                SlashCommand("/reload-mcp", "Reload MCP servers", "configuration"),
                // Info
                SlashCommand("/help", "Show available commands", "info"),
                SlashCommand("/status", "Show session info", "info"),
                SlashCommand("/usage", "Show token usage", "info"),
                SlashCommand("/insights", "Usage analytics", "info"),
                SlashCommand("/commands", "Browse all commands", "info"),
                SlashCommand("/profile", "Show active profile", "info"),
                SlashCommand("/update", "Update Hermes Agent", "info"),
            )

            // Dynamic personality commands from server
            val personalities = personalityNames.map { name ->
                SlashCommand(
                    command = "/personality $name",
                    description = name.replaceFirstChar { it.uppercase() } + " personality",
                    category = "personality"
                )
            }

            // Server skills from GET /api/skills
            val skills = availableSkills.map { skill ->
                SlashCommand(
                    command = "/${skill.name}",
                    description = skill.description ?: "Skill",
                    category = skill.category ?: "uncategorized"
                )
            }

            builtIn + personalities + skills
        }
    }

    // Inline autocomplete — filters as user types "/"
    val filteredCommands by remember(inputText, allCommands) {
        derivedStateOf {
            if (inputText.startsWith("/") && !inputText.contains(" ")) {
                val query = inputText.lowercase()
                allCommands.filter { it.command.lowercase().startsWith(query) }.take(8)
            } else {
                emptyList()
            }
        }
    }
    val showAutocomplete by remember(filteredCommands, inputText) {
        derivedStateOf {
            inputText.startsWith("/") && filteredCommands.isNotEmpty()
        }
    }

    // Refresh sessions when screen appears and API is ready
    LaunchedEffect(chatReady) {
        if (chatReady) {
            chatViewModel.refreshSessions()
        }
    }

    // Auto-scroll to bottom while streaming.
    //
    // Bugs the previous versions had:
    //   1. Keys only watched messages.size and content.length — so growth of
    //      the thinking block (thinkingContent) and tool-card additions
    //      (toolCalls) silently froze the auto-follow during long reasoning
    //      and tool execution phases.
    //   2. animateScrollToItem(lastIndex) defaults scrollOffset = 0, which
    //      aligns the TOP of the item with the top of the viewport. For a
    //      tall streaming bubble (reasoning + tool cards + text) that means
    //      the user gets snapped back to the start of the message instead
    //      of staying at the latest token. Fix: scrollOffset = Int.MAX_VALUE
    //      pins the bottom of the item to the bottom of the viewport.
    //   3. There was no userScrolledAway gate, so any delta would yank a
    //      user reading history back to the bottom.
    //   4. The isStreaming flag was a snapshot key, so the stream-complete
    //      transition (true → false) re-triggered animateScrollToItem even
    //      when no content actually changed — producing a visible jiggle.
    //   5. Sessions endpoint reloads the entire message list on stream
    //      complete via loadMessageHistory(), and the resulting animateItem()
    //      placement animations on every bubble fought with our concurrent
    //      animateScrollToItem — producing a flash where the viewport
    //      visibly settled twice.
    //
    // The fix below uses snapshotFlow on a snapshot of every meaningful
    // streaming-state field. distinctUntilChanged debounces identical
    // emissions; collectLatest cancels any in-flight scroll animation when
    // a newer delta arrives, preventing animation pile-ups during rapid
    // SSE bursts. The pref `smoothAutoScroll` (default true) gates the
    // entire effect — when off, only manual scrolling occurs.
    //
    // The previous-snapshot var inside the LaunchedEffect coroutine lets us
    // distinguish "content arrived" from "state flipped" and "single
    // append" from "list rebuild", and pick the right scroll strategy.
    LaunchedEffect(listState, smoothAutoScroll) {
        if (!smoothAutoScroll) return@LaunchedEffect
        var previousSnapshot: ChatScrollSnapshot? = null
        snapshotFlow {
            val last = messages.lastOrNull()
            // Snapshot every field that can grow during a single turn.
            // Any change here means "more content arrived, try to follow".
            ChatScrollSnapshot(
                messageCount = messages.size,
                lastContentLength = last?.content?.length ?: 0,
                lastThinkingLength = last?.thinkingContent?.length ?: 0,
                lastToolCallCount = last?.toolCalls?.size ?: 0,
                isStreaming = last?.isStreaming == true
            )
        }
            .distinctUntilChanged()
            .collectLatest { snapshot ->
                val prev = previousSnapshot
                previousSnapshot = snapshot

                if (messages.isEmpty()) return@collectLatest
                if (userScrolledAway) return@collectLatest

                // Skip "state-only" snapshot deltas where the only thing
                // that changed is the isStreaming flag. The viewport is
                // already at the right position from the last content
                // delta — animating again on the state flip causes a
                // visible flash, especially in sessions mode where the
                // StreamingDots row vanishes when isStreaming flips false.
                val onlyStreamingFlagChanged = prev != null
                    && prev.messageCount == snapshot.messageCount
                    && prev.lastContentLength == snapshot.lastContentLength
                    && prev.lastThinkingLength == snapshot.lastThinkingLength
                    && prev.lastToolCallCount == snapshot.lastToolCallCount
                    && prev.isStreaming != snapshot.isStreaming
                if (onlyStreamingFlagChanged) return@collectLatest

                val lastIndex = listState.layoutInfo.totalItemsCount - 1
                if (lastIndex < 0) return@collectLatest

                // Sessions endpoint reloads the entire message list on
                // stream complete (one streaming message → multiple final
                // messages with proper boundaries + tool call cards).
                // animateScrollToItem during a list rebuild conflicts with
                // the items' animateItem() placement animations and produces
                // a visible flash. Use the instant scrollToItem path so the
                // viewport snaps to the new bottom while the items animate
                // into their final positions independently.
                val isListRebuild = prev != null
                    && snapshot.messageCount - prev.messageCount > 1

                if (isListRebuild) {
                    // Instant — no animation, no conflict with animateItem.
                    listState.scrollToItem(lastIndex, Int.MAX_VALUE)
                } else {
                    // scrollOffset = Int.MAX_VALUE → Compose clamps to
                    // (item height - viewport height), pinning the bottom
                    // of the last item to the bottom of the viewport
                    // regardless of how tall the streaming bubble has grown.
                    listState.animateScrollToItem(lastIndex, Int.MAX_VALUE)
                }
            }
    }

    // Haptic on stream complete
    LaunchedEffect(isStreaming) {
        if (!isStreaming && messages.isNotEmpty()) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    // Haptic on error
    LaunchedEffect(error) {
        if (error != null) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    // Agent display name — used in header and info dialog
    val agentDisplayName = remember(selectedPersonality, defaultPersonality) {
        val name = if (selectedPersonality == "default" && defaultPersonality.isNotBlank()) {
            defaultPersonality
        } else {
            selectedPersonality
        }
        name.replaceFirstChar { it.uppercase() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SessionDrawerContent(
                sessions = sessions,
                currentSessionId = currentSessionId,
                onNewChat = {
                    chatViewModel.createNewChat()
                    scope.launch { drawerState.close() }
                },
                onSelectSession = { sessionId ->
                    chatViewModel.switchSession(sessionId)
                    scope.launch { drawerState.close() }
                },
                onDeleteSession = { sessionId ->
                    chatViewModel.deleteSession(sessionId)
                },
                onRenameSession = { sessionId, title ->
                    chatViewModel.renameSession(sessionId, title)
                }
            )
        }
    ) {
        val isDarkTheme = isSystemInDarkTheme()

        Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .radialNavyBackground(isDarkTheme = isDarkTheme)
                .imePadding()
                .alpha(chatAlpha)
        ) {
            // Top bar — messaging app style with avatar, name, model subtitle
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Filled.Menu, contentDescription = "Sessions")
                    }
                },
                title = {
                    val isConnecting = !apiReachable && chatMode != ChatMode.DISCONNECTED
                    val statusText = when {
                        apiReachable -> "Connected"
                        isConnecting -> "Connecting..."
                        else -> "Disconnected"
                    }
                    val statusColor = when {
                        apiReachable -> Color(0xFF4CAF50)
                        isConnecting -> Color(0xFFFFA726)
                        else -> MaterialTheme.colorScheme.error
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.clickable { showAgentInfo = true }
                    ) {
                        // Avatar with status dot overlay
                        Box(modifier = Modifier.size(36.dp)) {
                            Surface(
                                modifier = Modifier.size(36.dp),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = if (agentDisplayName.isNotBlank()) {
                                            agentDisplayName.first().uppercase()
                                        } else "H",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                            ConnectionStatusBadge(
                                isConnected = apiReachable,
                                isConnecting = isConnecting,
                                modifier = Modifier
                                    .size(10.dp)
                                    .align(Alignment.BottomEnd),
                                size = 10.dp
                            )
                        }

                        // Name + Hermes Agent + model + status
                        Column {
                            Text(
                                text = if (agentDisplayName.isNotBlank()) agentDisplayName else "Hermes",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1
                            )
                            Text(
                                text = buildString {
                                    append("Hermes Agent")
                                    if (serverModelName.isNotBlank()) {
                                        append(" · $serverModelName")
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor,
                                maxLines = 1
                            )
                        }
                    }
                },
                actions = {
                    // Ambient mode toggle (show/hide sphere visualization)
                    if (animationEnabled) {
                        IconButton(onClick = { ambientMode = !ambientMode }) {
                            Icon(
                                imageVector = if (ambientMode) Icons.Filled.ChatBubble else Icons.Filled.AutoAwesome,
                                contentDescription = if (ambientMode) "Show chat" else "Ambient mode",
                                tint = if (ambientMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Personality picker
                    PersonalityPicker(
                        selected = selectedPersonality,
                        personalities = personalityNames,
                        defaultName = defaultPersonality,
                        onSelect = { chatViewModel.selectPersonality(it) }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )

            // Offline banner
            AnimatedVisibility(
                visible = networkStatus is ConnectivityObserver.Status.Lost ||
                    networkStatus is ConnectivityObserver.Status.Unavailable
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.WifiOff,
                        contentDescription = "No internet",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "No internet connection",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Error banner with retry
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
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    TextButton(onClick = { chatViewModel.retryLastMessage() }) {
                        Text("Retry")
                    }
                    TextButton(onClick = { chatViewModel.clearError() }) {
                        Text("Dismiss")
                    }
                }
            }

            // Loading history indicator
            if (isLoadingHistory) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = "Loading messages...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Ambient mode: fullscreen sphere visualization
            if (ambientMode && animationEnabled) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    MorphingSphere(
                        modifier = Modifier.fillMaxSize(),
                        state = sphereState,
                        intensity = streamingIntensity,
                        toolCallBurst = toolCallBurst
                    )
                }
            }
            // Message list or empty state
            else if (messages.isEmpty() && !isStreaming && !isLoadingHistory) {
                val suggestions = listOf(
                    "What can you do?",
                    "Help me code",
                    "Explain something"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    ) {
                        Spacer(modifier = Modifier.weight(0.15f))

                        // ASCII sphere (constrained to square aspect)
                        if (animationEnabled) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .weight(0.7f, fill = false)
                            ) {
                                MorphingSphere(
                                    modifier = Modifier.fillMaxSize(),
                                    state = if (error != null) SphereState.Error else SphereState.Idle,
                                    intensity = streamingIntensity,
                                    toolCallBurst = toolCallBurst
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        Text(
                            text = "Start a conversation",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        if (!chatReady) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Configure API server in Settings",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Suggestion chips
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            suggestions.forEach { suggestion ->
                                AssistChip(
                                    onClick = { inputText = suggestion },
                                    label = {
                                        Text(
                                            text = suggestion,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.weight(0.15f))
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    // Ambient sphere behind messages
                    if (animationEnabled && animationBehindChat && !ambientMode) {
                        MorphingSphere(
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(0.65f),
                            state = sphereState,
                            intensity = streamingIntensity,
                            toolCallBurst = toolCallBurst
                        )
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        item { Spacer(modifier = Modifier.height(8.dp).animateItem()) }

                        items(messages.size, key = { messages[it].id }) { index ->
                            val message = messages[index]

                            // Skip empty bubbles (content stripped by annotation parser, no tool calls,
                            // no attachments). Attachments keep the bubble alive for inbound media.
                            if (message.content.isBlank() &&
                                message.toolCalls.isEmpty() &&
                                message.attachments.isEmpty() &&
                                !message.isStreaming
                            ) return@items

                            val isFirstInGroup = index == 0 || messages[index - 1].role != message.role
                            val isLastInGroup = index == messages.size - 1 || messages[index + 1].role != message.role

                            // Date separator
                            if (index == 0 || !isSameDay(messages[index - 1].timestamp, message.timestamp)) {
                                DateSeparator(timestamp = message.timestamp)
                            }

                            MessageBubble(
                                message = message,
                                modifier = Modifier
                                    .padding(top = if (isFirstInGroup) 6.dp else 1.dp)
                                    .animateItem(),
                                maxBubbleWidth = maxBubbleWidth,
                                showThinking = showThinking,
                                isFirstInGroup = isFirstInGroup,
                                isLastInGroup = isLastInGroup,
                                onAttachmentRetry = { msgId, idx ->
                                    chatViewModel.manualFetchAttachment(msgId, idx)
                                },
                                onAttachmentManualFetch = { msgId, idx ->
                                    chatViewModel.manualFetchAttachment(msgId, idx)
                                },
                                onCopyMessage = { text ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    // The new Clipboard API is suspend-based, so the
                                    // setClipEntry call has to live inside a coroutine.
                                    // We piggyback on the same scope.launch that posts
                                    // the snackbar — they are sequential anyway.
                                    scope.launch {
                                        clipboard.setClipEntry(
                                            ClipEntry(
                                                ClipData.newPlainText(
                                                    "Hermes message",
                                                    text
                                                )
                                            )
                                        )
                                        snackbarHostState.showSnackbar(
                                            message = "Copied to clipboard",
                                            duration = SnackbarDuration.Short
                                        )
                                    }
                                }
                            )

                            if (toolDisplay != "off") {
                                message.toolCalls.forEach { toolCall ->
                                    Spacer(modifier = Modifier.height(4.dp))
                                    when (toolDisplay) {
                                        "compact" -> CompactToolCall(toolCall = toolCall)
                                        else -> ToolProgressCard(toolCall = toolCall)
                                    }
                                }
                            }
                        }

                        if (isStreaming) {
                            item {
                                StreamingDots(
                                    modifier = Modifier
                                        .padding(start = 12.dp, top = 4.dp)
                                        .animateItem()
                                )
                            }
                        }

                        item { Spacer(modifier = Modifier.height(8.dp).animateItem()) }
                    }

                    // Scroll-to-bottom FAB
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showScrollToBottom,
                        enter = fadeIn() + slideInVertically { it },
                        exit = fadeOut() + slideOutVertically { it },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                    ) {
                        SmallFloatingActionButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                scope.launch {
                                    val lastIndex = listState.layoutInfo.totalItemsCount - 1
                                    if (lastIndex >= 0) {
                                        // Match the auto-scroll fix: aim at the
                                        // BOTTOM of the last item, not the top.
                                        listState.animateScrollToItem(lastIndex, Int.MAX_VALUE)
                                    }
                                }
                                // Tapping the FAB is an explicit "take me back to
                                // live" action — clear the user-scrolled-away gate
                                // so streaming auto-follow resumes immediately,
                                // even before the snapshotFlow notices isAtBottom
                                // (which only flips after the scroll animation
                                // settles).
                                userScrolledAway = false
                            },
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = "Scroll to bottom"
                            )
                        }
                    }

                    // Copy feedback snackbar
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                    )
                }
            }

            // Inline slash command autocomplete
            AnimatedVisibility(visible = showAutocomplete) {
                InlineAutocomplete(
                    commands = filteredCommands,
                    onSelect = { cmd ->
                        val base = cmd.command.split(" ").first()
                        inputText = if (cmd.command.contains(" ")) cmd.command + " " else "$base "
                    },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // Queue indicator
            AnimatedVisibility(visible = queuedMessages.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${queuedMessages.size} message${if (queuedMessages.size > 1) "s" else ""} queued",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(
                        onClick = { chatViewModel.clearQueue() },
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(
                            "Clear",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            // Attachment preview strip
            AnimatedVisibility(visible = pendingAttachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pendingAttachments.forEachIndexed { index, attachment ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            tonalElevation = 2.dp,
                            modifier = Modifier.height(56.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 8.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (attachment.isImage) {
                                    // Decode and show thumbnail
                                    val bitmap = remember(attachment.content) {
                                        try {
                                            val bytes = Base64.decode(attachment.content, Base64.DEFAULT)
                                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                        } catch (_: Exception) { null }
                                    }
                                    if (bitmap != null) {
                                        val imageBitmap = remember(bitmap) {
                                            bitmap.asImageBitmap()
                                        }
                                        Image(
                                            bitmap = imageBitmap,
                                            contentDescription = attachment.fileName,
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                        )
                                    } else {
                                        Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(24.dp))
                                    }
                                } else {
                                    Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(24.dp))
                                }
                                Column(modifier = Modifier.widthIn(max = 100.dp)) {
                                    Text(
                                        text = attachment.fileName ?: "File",
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = formatFileSize(attachment.fileSize ?: 0),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = { chatViewModel.removeAttachment(index) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Remove",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Input bar with character limit
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                // Attach file button
                IconButton(
                    onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Attach file",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Command palette button
                IconButton(
                    onClick = { showCommandPalette = true },
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Text(
                        text = "/",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { if (it.length <= charLimit) inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(if (isStreaming && inputText.isBlank()) "Queue a message..." else "Message...")
                    },
                    maxLines = 4,
                    enabled = chatReady,
                    supportingText = if (inputText.length > charLimit - 200) {
                        {
                            Text(
                                "${inputText.length}/$charLimit",
                                color = if (inputText.length >= charLimit) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    } else null
                )

                // Stop button — visible during streaming
                AnimatedVisibility(visible = isStreaming) {
                    IconButton(onClick = { chatViewModel.cancelStream() }) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = "Stop streaming",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }

                // Trailing button — smart-swap between mic and send.
                // Empty input → Mic (taps into voice mode overlay).
                // Any typed text or attachment → Send (morph into send arrow,
                // queues during streaming). Stop button during streaming is a
                // separate IconButton above this one; both can coexist since
                // they have distinct semantics.
                val hasContent = inputText.isNotBlank() || pendingAttachments.isNotEmpty()
                val sendEnabled = hasContent && chatReady
                Box(
                    modifier = if (sendEnabled && isDarkTheme && !isStreaming) {
                        Modifier.purpleGlow(
                            radius = 24.dp,
                            alpha = 0.35f,
                            isDarkTheme = true
                        )
                    } else Modifier
                ) {
                    if (hasContent) {
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                chatViewModel.sendMessage(inputText.ifBlank { "[attachment]" })
                                inputText = ""
                            },
                            enabled = sendEnabled
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = if (isStreaming) "Queue message" else "Send message",
                                tint = if (sendEnabled) {
                                    if (isStreaming) MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    } else {
                        IconButton(onClick = { requestVoiceMode() }) {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = "Voice mode",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        } // end Column

        // Mic permission denied banner
        AnimatedVisibility(
            visible = micPermissionDenied && !voiceUiState.voiceMode,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 80.dp, start = 16.dp, end = 16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                tonalElevation = 2.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Microphone permission is required for voice mode",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { micPermissionDenied = false }) {
                        Text("Dismiss")
                    }
                }
            }
        }

        // Voice mode overlay — covers the whole Box when voiceUiState.voiceMode
        AnimatedVisibility(
            visible = voiceUiState.voiceMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            VoiceModeOverlay(
                uiState = voiceUiState,
                onMicTap = { voiceViewModel.startListening() },
                onMicRelease = { voiceViewModel.stopListening() },
                onInterrupt = { voiceViewModel.interruptSpeaking() },
                onDismiss = { voiceViewModel.exitVoiceMode() },
                onModeChange = { voiceViewModel.setInteractionMode(it) },
                onClearError = { voiceViewModel.clearError() },
            )
        }
        } // end Box
    }

    // Command palette bottom sheet
    if (showCommandPalette) {
        CommandPalette(
            commands = allCommands,
            onSelect = { cmd ->
                val base = cmd.command.split(" ").first()
                inputText = if (cmd.command.contains(" ")) cmd.command + " " else "$base "
                showCommandPalette = false
            },
            onDismiss = { showCommandPalette = false }
        )
    }

    // Agent info dialog — shown when tapping the header
    if (showAgentInfo) {
        val currentSession = sessions.find { it.sessionId == currentSessionId }

        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showAgentInfo = false },
            title = {
                Text(
                    text = if (agentDisplayName.isNotBlank()) agentDisplayName else "Hermes Agent"
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (serverModelName.isNotBlank()) {
                        Row {
                            Text("Model", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(80.dp))
                            Text(serverModelName, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Row {
                        Text("Status", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(80.dp))
                        Text(
                            text = when {
                                apiReachable -> "Connected"
                                chatMode != ChatMode.DISCONNECTED -> "Connecting..."
                                else -> "Disconnected"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = when {
                                apiReachable -> Color(0xFF4CAF50)
                                chatMode != ChatMode.DISCONNECTED -> Color(0xFFFFA726)
                                else -> MaterialTheme.colorScheme.error
                            }
                        )
                    }
                    Row {
                        Text("Personality", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(80.dp))
                        Text(
                            text = if (selectedPersonality == "default") {
                                "${defaultPersonality.replaceFirstChar { it.uppercase() }} (default)"
                            } else {
                                selectedPersonality.replaceFirstChar { it.uppercase() }
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (currentSession != null) {
                        HorizontalDivider()
                        Row {
                            Text("Session", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(80.dp))
                            Text(currentSession.title ?: currentSession.sessionId.take(12), style = MaterialTheme.typography.bodyMedium)
                        }
                        Row {
                            Text("Messages", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(80.dp))
                            Text("${messages.size}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAgentInfo = false }) {
                    Text("Close")
                }
            }
        )
    }
}

// --- Helper functions ---

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes} B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}

private fun isSameDay(ts1: Long, ts2: Long): Boolean {
    val d1 = java.time.Instant.ofEpochMilli(ts1).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val d2 = java.time.Instant.ofEpochMilli(ts2).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    return d1 == d2
}

@Composable
private fun DateSeparator(timestamp: Long) {
    val today = java.time.LocalDate.now()
    val messageDate = java.time.Instant.ofEpochMilli(timestamp)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()

    val label = when {
        messageDate == today -> "Today"
        messageDate == today.minusDays(1) -> "Yesterday"
        messageDate.year == today.year -> messageDate.format(
            java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d")
        )
        else -> messageDate.format(
            java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy")
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
