package com.hermesandroid.relay.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.hermesandroid.relay.ui.theme.radialNavyBackground
import com.hermesandroid.relay.network.ChatMode
import com.hermesandroid.relay.network.RelayVoiceClient
import com.hermesandroid.relay.network.RealtimeVoiceConfig
import com.hermesandroid.relay.network.VoiceOutputConfig
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
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
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.ui.platform.LocalContext
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.Attachment
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.data.displayLabel
import com.hermesandroid.relay.ui.components.AgentInfoSheet
import com.hermesandroid.relay.ui.components.ChatInputBar
import com.hermesandroid.relay.ui.components.ChatInputTrailing
import com.hermesandroid.relay.ui.components.CommandPalette
import com.hermesandroid.relay.ui.components.ConnectionStatusBadge
import com.hermesandroid.relay.ui.components.CommandRow
import com.hermesandroid.relay.ui.components.CompactToolCall
import com.hermesandroid.relay.ui.components.ContextMeterBar
import com.hermesandroid.relay.ui.components.InlineAutocomplete
import com.hermesandroid.relay.ui.components.MessageBubble
import com.hermesandroid.relay.ui.components.MorphingSphere
import com.hermesandroid.relay.ui.components.RelayChromeIconButton
import com.hermesandroid.relay.ui.components.RelayModeStrip
import com.hermesandroid.relay.ui.components.RelayPrimaryMode
import com.hermesandroid.relay.ui.components.SphereState
import com.hermesandroid.relay.ui.components.SessionDrawerContent
import com.hermesandroid.relay.ui.components.SlashCommand
import com.hermesandroid.relay.ui.components.SubagentLane
import com.hermesandroid.relay.ui.components.ToolProgressCard
import com.hermesandroid.relay.ui.components.VoiceModeOverlay
import com.hermesandroid.relay.ui.LocalSnackbarHost
import com.hermesandroid.relay.ui.showHumanError
import com.hermesandroid.relay.ui.theme.RelayRefresh
import com.hermesandroid.relay.ui.theme.relayGridTexture
import com.hermesandroid.relay.ui.theme.relayMetadataStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import kotlin.math.roundToInt
import com.hermesandroid.relay.viewmodel.ChatViewModel
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.viewmodel.VoiceViewModel
import com.hermesandroid.relay.voice.VoiceOverlayHost
import com.hermesandroid.relay.voice.VoiceOverlaySession
import com.hermesandroid.relay.voice.openHermesFromOverlay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    voiceClient: RelayVoiceClient? = null,
    maxBubbleWidth: Dp = 300.dp,
    // Deep-link nudge from Settings → Active Agent card: when `true`, the
    // AgentInfoSheet auto-opens on first composition and [onAgentSheetArgConsumed]
    // fires so the host can clear the nav arg (prevents re-open on tab
    // switches or recomposition). Both default to no-op so existing call
    // sites (previews, tests) don't need to plumb this.
    openAgentSheetOnEntry: Boolean = false,
    onAgentSheetArgConsumed: () -> Unit = {},
    // Sheet footer shortcut: jump out of chat into the full Connections CRUD
    // screen. Default no-op preserves existing test/preview call sites that
    // don't wire navigation.
    onNavigateToConnections: () -> Unit = {},
    onNavigateToConnect: () -> Unit = onNavigateToConnections,
    onNavigateToManage: () -> Unit = {},
    onNavigateToBridge: () -> Unit = {},
    onNavigateToTerminal: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToProfileInspector: (String) -> Unit = {},
) {
    val voiceUiState by voiceViewModel.uiState.collectAsState()
    var voiceCompactMode by remember { mutableStateOf(false) }
    val chatAlpha by animateFloatAsState(
        targetValue = if (voiceUiState.voiceMode && !voiceCompactMode) 0.4f else 1f,
        animationSpec = tween(300),
        label = "chatAlpha",
    )

    // Route classified chat errors (media cache, streaming failures, …) to
    // the app-wide snackbar. Same pattern every VM-bound screen uses.
    val snackbarHost = LocalSnackbarHost.current
    LaunchedEffect(chatViewModel) {
        chatViewModel.errorEvents.collect { err ->
            snackbarHost.showHumanError(err)
        }
    }

    // RECORD_AUDIO permission flow — user taps the mic FAB → if not granted,
    // request; on grant, latch the pending-enter and fire enterVoiceMode() in
    // the callback. Denial shows an inline banner above the input.
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val voiceOverlayHost = remember { VoiceOverlayHost.install(context) }
    var pendingVoiceEnter by remember { mutableStateOf(false) }
    var micPermissionDenied by remember { mutableStateOf(false) }
    var pendingVoiceOverlayPermission by remember { mutableStateOf(false) }
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
    val voiceStats by voiceViewModel.voiceStats.collectAsState()
    var voiceOutputConfig by remember { mutableStateOf<VoiceOutputConfig?>(null) }
    var realtimeAgentConfig by remember { mutableStateOf<RealtimeVoiceConfig?>(null) }
    val chatReady by connectionViewModel.chatReady.collectAsState()
    // Stable voice can use the standard Hermes dashboard audio routes or the
    // optional Relay voice routes. Gate the mic on either route being usable;
    // availability picks the actionable toast when neither is.
    val voiceReady by connectionViewModel.voiceReady.collectAsState()
    val standardVoiceAvailability by connectionViewModel.standardVoiceAvailability.collectAsState()
    val standardVoiceSignInRouteHint by
        connectionViewModel.standardVoiceSignInRouteHint.collectAsState()
    val apiReachable by connectionViewModel.apiServerReachable.collectAsState()
    val chatMode by connectionViewModel.chatMode.collectAsState()
    val error by chatViewModel.error.collectAsState()
    val sessions by chatViewModel.sessions.collectAsState()
    val currentSessionId by chatViewModel.currentSessionId.collectAsState()
    val isLoadingHistory by chatViewModel.isLoadingHistory.collectAsState()
    val selectedPersonality by chatViewModel.selectedPersonality.collectAsState()
    val personalityNames by chatViewModel.personalityNames.collectAsState()
    val defaultPersonality by chatViewModel.defaultPersonality.collectAsState()
    // Agent-profile selection — drives the customization ring on the avatar
    // and is consumed by the AgentInfoSheet for the Profile section. The list
    // of available profiles itself now lives entirely inside the sheet.
    val selectedProfile by connectionViewModel.selectedProfile.collectAsState()
    // Server-advertised profile catalog — used to locate the "default" profile
    // so the header can render its description/model when no explicit pick
    // has been made (the /api/config fallback is more useful than the bare
    // connection label).
    val agentProfiles by connectionViewModel.agentProfiles.collectAsState()
    val activeConnection by connectionViewModel.activeConnection.collectAsState()
    val serverModelName by chatViewModel.serverModelName.collectAsState()
    val showThinking by connectionViewModel.showThinking.collectAsState()
    val toolDisplay by connectionViewModel.toolDisplay.collectAsState()
    val smoothAutoScroll by connectionViewModel.smoothAutoScroll.collectAsState()

    val availableSkills by chatViewModel.availableSkills.collectAsState()
    val queuedMessages by chatViewModel.queuedMessages.collectAsState()
    val pendingAttachments by chatViewModel.pendingAttachments.collectAsState()
    val maxAttachmentMb by connectionViewModel.maxAttachmentMb.collectAsState()
    val charLimit by connectionViewModel.maxMessageLength.collectAsState()

    // === Gateway desktop-parity state ===
    val serverCommands by chatViewModel.serverCommands.collectAsState()
    val contextUsage by chatViewModel.contextUsage.collectAsState()
    val steerableTurn by chatViewModel.steerableTurn.collectAsState()
    val steerNotice by chatViewModel.steerNotice.collectAsState()
    val voiceHintSeen by connectionViewModel.voiceHintSeen.collectAsState()
    // Whether the NEXT turn would ride the gateway transport — gates the
    // "Edit & resend" menu entry (conversation rewind needs the gateway).
    // Per-turn steerability comes from [steerableTurn], which also covers
    // the preflight-SSE-fallback window.
    val streamingEndpointPref by connectionViewModel.streamingEndpoint.collectAsState()
    val chatServerCapabilities by connectionViewModel.serverCapabilities.collectAsState()
    val chatGatewayAvailability by connectionViewModel.gatewayAvailability.collectAsState()
    val isGatewayTransport = remember(
        streamingEndpointPref, chatServerCapabilities, chatGatewayAvailability,
    ) {
        connectionViewModel.resolveStreamingEndpoint(streamingEndpointPref) == "gateway"
    }

    // Edit-and-resend mode: long-press a user bubble → "Edit & resend"
    // prefills the input; submit rewinds the conversation from that message.
    var editingMessage by remember { mutableStateOf<ChatMessage?>(null) }

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

    // Server command dispatch can ask the composer to prefill (e.g. /undo).
    LaunchedEffect(chatViewModel) {
        chatViewModel.composerPrefill.collect { text ->
            editingMessage = null
            inputText = text.take(charLimit)
        }
    }

    // Settings → Active Agent deep-link: when the nav arg says "open the
    // sheet", flip `showAgentInfo` on and call [onAgentSheetArgConsumed] so
    // the host clears the arg. Keyed on [openAgentSheetOnEntry] so the
    // effect re-fires if the user taps the Settings card, goes back, taps
    // again — each navigation brings in a fresh `true` arg that this effect
    // converts into a sheet open.
    LaunchedEffect(openAgentSheetOnEntry) {
        if (openAgentSheetOnEntry) {
            showAgentInfo = true
            onAgentSheetArgConsumed()
        }
    }
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val haptic = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    val realtimeAgentActive = voiceStats.voiceEngineMode == "realtime_agent"
    val activeVoiceProvider = if (realtimeAgentActive) {
        realtimeAgentConfig?.default_provider
    } else {
        voiceOutputConfig?.default_provider
    }
    val activeVoiceModel = if (realtimeAgentActive) {
        realtimeAgentConfig?.default_model
    } else {
        voiceOutputConfig?.default_model
    }
    val activeVoiceName = if (realtimeAgentActive) {
        realtimeAgentConfig?.default_voice
    } else {
        voiceOutputConfig?.default_voice
    }
    val activeVoiceScope = if (realtimeAgentActive) {
        realtimeAgentConfig?.configScope
    } else {
        voiceOutputConfig?.configScope
    }
    val activeVoiceEnabled = if (realtimeAgentActive) {
        realtimeAgentConfig?.enabled
    } else {
        voiceOutputConfig?.enabled
    }

    val showVoiceSystemOverlay: () -> Unit = {
        if (!voiceOverlayHost.hasOverlayPermission()) {
            pendingVoiceOverlayPermission = true
            runCatching {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(intent)
            }
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = "Enable Display over other apps, then return to start Voice Overlay.",
                    duration = SnackbarDuration.Short,
                )
            }
        } else {
            pendingVoiceOverlayPermission = false
            val shown = voiceOverlayHost.show(
                VoiceOverlaySession(
                    uiState = voiceViewModel.uiState,
                    engineMode = voiceStats.voiceEngineMode,
                    provider = activeVoiceProvider,
                    model = activeVoiceModel,
                    voice = activeVoiceName,
                    profileName = selectedProfile?.description?.takeIf { it.isNotBlank() }
                        ?: selectedProfile?.name,
                    configScope = activeVoiceScope,
                    outputEnabled = activeVoiceEnabled,
                    fallbackEnabled = voiceOutputConfig?.fallback_enabled,
                    onStartListening = { voiceViewModel.startListening() },
                    onStopListening = { voiceViewModel.stopListening() },
                    onInterrupt = { voiceViewModel.interruptSpeaking() },
                    onPauseAutoMode = { voiceViewModel.pauseContinuousMode() },
                    onReturnToHermes = {
                        openHermesFromOverlay(context)
                        voiceOverlayHost.hide()
                    },
                    onDismissOverlay = { voiceOverlayHost.hide() },
                    onExit = {
                        voiceOverlayHost.hide()
                        voiceViewModel.exitVoiceMode()
                    },
                ),
            )
            if (!shown) {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = "Voice Overlay could not be started.",
                        duration = SnackbarDuration.Short,
                    )
                }
            }
        }
    }

    LaunchedEffect(voiceUiState.voiceMode) {
        if (!voiceUiState.voiceMode) {
            voiceOverlayHost.hide()
            pendingVoiceOverlayPermission = false
            voiceCompactMode = false
        }
    }

    DisposableEffect(
        lifecycleOwner,
        pendingVoiceOverlayPermission,
        voiceUiState.voiceMode,
        voiceOutputConfig,
        realtimeAgentConfig,
        voiceStats.voiceEngineMode,
    ) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && pendingVoiceOverlayPermission) {
                when {
                    voiceOverlayHost.hasOverlayPermission() && voiceUiState.voiceMode -> {
                        showVoiceSystemOverlay()
                    }
                    !voiceOverlayHost.hasOverlayPermission() -> {
                        pendingVoiceOverlayPermission = false
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = "Voice Overlay permission was not granted.",
                                duration = SnackbarDuration.Short,
                            )
                        }
                    }
                    else -> pendingVoiceOverlayPermission = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(voiceClient, voiceUiState.voiceMode, selectedProfile?.name) {
        if (!voiceUiState.voiceMode) return@LaunchedEffect
        val client = voiceClient ?: return@LaunchedEffect
        val result = client.getVoiceOutputConfig()
        if (result.isSuccess) {
            voiceOutputConfig = result.getOrNull()
        }
        val realtimeResult = client.getRealtimeAgentConfig()
        if (realtimeResult.isSuccess) {
            realtimeAgentConfig = realtimeResult.getOrNull()
        }
    }

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

    // Build all commands dynamically: built-in + personalities + server
    // skills + (gateway) the server's commands.catalog
    val allCommands by remember(availableSkills, personalityNames, serverCommands) {
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

            val base = builtIn + personalities + skills
            if (serverCommands.isEmpty()) {
                base
            } else {
                // Merge the gateway catalog as a 4th source: dedupe by
                // command name with the server description winning;
                // server-only commands append under their catalog category
                // (or the palette's "server" bucket).
                val serverByName = serverCommands.associateBy { it.command.lowercase() }
                val merged = base.map { cmd ->
                    serverByName[cmd.command.lowercase()]?.let { server ->
                        cmd.copy(
                            description = server.description,
                            source = SlashCommand.SOURCE_SERVER,
                        )
                    } ?: cmd
                }
                val baseNames = base.map { it.command.lowercase() }.toSet()
                merged + serverCommands.filter { it.command.lowercase() !in baseNames }
            }
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

                // Growth of the bubble we're already following (thinking /
                // content / tool cards on the same message) arrives at token
                // frequency on the gateway transport. animateScrollToItem
                // per delta is a cancel/restart storm — each collectLatest
                // cancellation strands the viewport mid-animation (showing
                // earlier content) before the next one yanks it back:
                // visible stutter when parked at the bottom during long
                // reasoning. Pin instantly instead; reserve the animation
                // for the discrete new-bubble event.
                val isSameTurnGrowth = prev != null
                    && snapshot.messageCount == prev.messageCount

                if (isListRebuild || isSameTurnGrowth) {
                    // Instant — no animation, no conflict with animateItem,
                    // no pile-up at delta frequency.
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

    // Effective profile — the one the header should reflect. Priority:
    //   1. explicit user pick (selectedProfile)
    //   2. server-advertised profile named "default"
    //   3. null (fall back to personality-derived name)
    //
    // Computed as a derived state so the header cross-fades when the user
    // picks a new profile OR when the server's profile catalog finishes
    // loading and a "default" entry shows up.
    val effectiveProfile by remember(selectedProfile, agentProfiles) {
        derivedStateOf {
            AgentDisplay.effectiveProfile(
                selectedProfile = selectedProfile,
                profiles = agentProfiles,
            )
        }
    }

    // Agent display name — used in header and info dialog.
    //
    // Precedence mirrors the messaging-app pattern: show the profile's
    // human-readable description (e.g. "Victor") if present, then the
    // profile's slug name, then the personality, then the connection label,
    // then the literal "Hermes" fallback.
    //
    // Wrapped in `derivedStateOf` so the recomposition scope tracks every
    // state read inside (effectiveProfile, selectedPersonality,
    // defaultPersonality, activeConnection) — the previous plain
    // `remember(k1,k2,k3,k4)` form relied on equality diffs against those
    // four keys, which missed updates in some cases (most notably a
    // profile switch while the ConnectionInfoSheet was open, where the
    // ambient sheet scope appeared to swallow the key comparison).
    val agentDisplayName by remember {
        derivedStateOf {
            val profile = effectiveProfile
            AgentDisplay.agentName(
                profile = profile,
                selectedPersonality = selectedPersonality,
                defaultPersonality = defaultPersonality,
                connectionLabel = activeConnection?.label,
            )
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            val drawerTitle = if (selectedProfile != null) {
                "$agentDisplayName sessions"
            } else {
                "Server default sessions"
            }
            val drawerSubtitle = when {
                selectedProfile?.hasIsolatedApi == true ->
                    "Profile API: ${selectedProfile?.apiServerUrl}"
                selectedProfile != null ->
                    "Compatibility overlay on ${activeConnection?.label ?: "active connection"}"
                activeConnection?.label?.isNotBlank() == true ->
                    "Connection: ${activeConnection?.label}"
                else -> "Active connection"
            }
            SessionDrawerContent(
                sessions = sessions,
                currentSessionId = currentSessionId,
                scopeTitle = drawerTitle,
                scopeSubtitle = drawerSubtitle,
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
                .background(RelayRefresh.Background)
                .relayGridTexture(alpha = 0.14f)
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

                    // Customization cue — true when the user has overridden
                    // the server defaults via either picker. Drives the 2dp
                    // accent ring on the avatar (subtle, at-a-glance signal
                    // that "you've customized this agent"). Personality
                    // defaults to the sentinel "default" string — any other
                    // value means explicit pick.
                    val customized = selectedProfile != null ||
                        selectedPersonality != "default"

                    // Single-line subtitle: when we have a model name we show
                    // `model · personality` so the user sees both dimensions
                    // at once. Before the server config lands (or while
                    // disconnected) we fall back to the connection status.
                    //
                    // Model priority: profile.model (explicit or default
                    // profile pick) trumps /api/config's `serverModelName`.
                    // The profile picker is the more specific intent.
                    val personalityLabel = AgentDisplay.personalityLabel(
                        selectedPersonality = selectedPersonality,
                        defaultPersonality = defaultPersonality,
                    )
                    val modelName = effectiveProfile?.model
                        ?.takeIf { it.isNotBlank() }
                        ?: serverModelName
                    val subtitleText = when {
                        !apiReachable -> statusText
                        modelName.isNotBlank() ->
                            "$modelName \u00B7 $personalityLabel"
                        else -> personalityLabel
                    }
                    val subtitleColor = if (apiReachable) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        statusColor
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.clickable { showAgentInfo = true }
                    ) {
                        // Avatar — 40dp, optional 2dp primary-color accent ring
                        // when the user has overridden any of the agent
                        // defaults. Ring sits OUTSIDE the avatar circle; the
                        // inner Surface is downsized by ring width so the
                        // overall footprint stays at 40dp.
                        val ringWidth = if (customized) 2.dp else 0.dp
                        val innerSize = 40.dp - (ringWidth * 2)
                        Box(modifier = Modifier.size(40.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .then(
                                        if (customized) {
                                            Modifier.border(
                                                width = ringWidth,
                                                color = MaterialTheme.colorScheme.primary,
                                                shape = CircleShape,
                                            )
                                        } else Modifier
                                    )
                                    .padding(ringWidth),
                                contentAlignment = Alignment.Center,
                            ) {
                                Surface(
                                    modifier = Modifier.size(innerSize),
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary
                                ) {
                                    // Cross-fade the letter when the
                                    // effective agent (profile or personality)
                                    // changes so the avatar feels alive on a
                                    // profile switch instead of snapping.
                                    val avatarLetter = if (agentDisplayName.isNotBlank()) {
                                        agentDisplayName.first().uppercase()
                                    } else "H"
                                    AnimatedContent(
                                        targetState = avatarLetter,
                                        transitionSpec = {
                                            fadeIn(tween(220)) togetherWith fadeOut(tween(220))
                                        },
                                        label = "chatAvatarLetter",
                                    ) { letter ->
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = letter,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = MaterialTheme.colorScheme.onPrimary
                                            )
                                        }
                                    }
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

                        // Name + single-line subtitle.
                        Column {
                            Text(
                                text = if (agentDisplayName.isNotBlank()) agentDisplayName else "Hermes",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            // Context escalation: ≥85% the subtitle gains a
                            // " · NN% ctx" suffix in the caution ladder color
                            // (Amber, Danger past 90%). The ambient strip
                            // below the app bar covers the 50–85% range.
                            val ctxFraction = contextUsage
                            val subtitleAnnotated = buildAnnotatedString {
                                append(subtitleText)
                                if (apiReachable && ctxFraction != null && ctxFraction >= 0.85f) {
                                    val ctxColor = if (ctxFraction >= 0.9f) {
                                        RelayRefresh.Danger
                                    } else {
                                        RelayRefresh.Amber
                                    }
                                    withStyle(SpanStyle(color = ctxColor)) {
                                        append(
                                            " · ${(ctxFraction * 100).roundToInt()}% ctx"
                                        )
                                    }
                                }
                            }
                            Text(
                                text = subtitleAnnotated,
                                style = MaterialTheme.typography.bodySmall,
                                color = subtitleColor,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    // ADR 24 — compact chip surfacing which endpoint role is
                    // currently serving the relay (LAN / Tailscale / Public /
                    // Custom). Only rendered when the active connection
                    // actually has a resolved endpoint; invisible for single-
                    // endpoint legacy pairings where the Settings row already
                    // spells the host out. Tap → Connections CRUD so the
                    // user can re-probe / pin / re-pair without leaving chat.
                    val activeEndpoint by connectionViewModel.activeEndpoint.collectAsState()
                    activeEndpoint?.let { ep ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = RelayRefresh.Navy3.copy(alpha = 0.78f),
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .clickable { onNavigateToConnections() },
                        ) {
                            Text(
                                text = ep.displayLabel(),
                                style = MaterialTheme.typography.labelSmall,
                                color = RelayRefresh.Relay,
                                modifier = Modifier.padding(
                                    horizontal = 8.dp,
                                    vertical = 4.dp,
                                ),
                            )
                        }
                    }
                    if (messages.isNotEmpty()) {
                        RelayChromeIconButton(
                            icon = Icons.Filled.Share,
                            contentDescription = "Share conversation",
                            onClick = { shareConversation(context, messages) },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                    RelayChromeIconButton(
                        icon = Icons.Filled.Code,
                        contentDescription = "Terminal",
                        onClick = onNavigateToTerminal,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    RelayChromeIconButton(
                        icon = Icons.Filled.Tune,
                        contentDescription = "Settings",
                        onClick = onNavigateToSettings,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    // Ambient mode (fullscreen sphere) has no top-bar toggle —
                    // it's a quiet gesture: long-press the conversation
                    // background to enter, tap anywhere to return. A hint pill
                    // on entry teaches the way back.
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = RelayRefresh.Background.copy(alpha = 0.96f)
                )
            )
            // Ambient context-window meter — 2dp strip at the seam between
            // the app bar and the mode strip; composes to nothing below 50%.
            ContextMeterBar(usedFraction = contextUsage)
            RelayModeStrip(
                selected = RelayPrimaryMode.Chat,
                onModeSelected = { mode ->
                    when (mode) {
                        RelayPrimaryMode.Chat -> Unit
                        RelayPrimaryMode.Manage -> onNavigateToManage()
                        RelayPrimaryMode.Bridge -> onNavigateToBridge()
                    }
                },
            )

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

            // Ambient mode: fullscreen sphere visualization. Tap (or
            // long-press) anywhere to return; a transient hint pill teaches
            // the exit on every entry.
            if (ambientMode && animationEnabled) {
                var showAmbientHint by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(2_800)
                    showAmbientHint = false
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { ambientMode = false },
                                onLongPress = { ambientMode = false },
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    MorphingSphere(
                        modifier = Modifier.fillMaxSize(),
                        state = sphereState,
                        intensity = streamingIntensity,
                        toolCallBurst = toolCallBurst
                    )
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showAmbientHint,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 18.dp),
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        Text(
                            text = "tap to return to chat",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
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
                            text = if (chatReady) "Start a conversation" else "Connect to Hermes",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        if (!chatReady) {
                            Spacer(modifier = Modifier.height(12.dp))
                            ElevatedCard(
                                colors = CardDefaults.elevatedCardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.86f),
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        text = "Chat needs a Standard Hermes API connection.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Button(
                                        onClick = onNavigateToConnect,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text("Connect Standard Hermes")
                                    }
                                }
                            }
                        } else {
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
                        }

                        Spacer(modifier = Modifier.weight(0.15f))
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // Quiet entry to ambient mode: long-press the
                        // conversation background. Bubbles keep their own
                        // long-press (copy) — they consume the gesture first,
                        // so only presses on empty space land here.
                        .pointerInput(animationEnabled) {
                            detectTapGestures(
                                onLongPress = {
                                    if (animationEnabled) ambientMode = true
                                },
                            )
                        }
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
                            // no attachments). Attachments keep the bubble alive for inbound media;
                            // cards keep ask-card-only messages alive the same way.
                            if (message.content.isBlank() &&
                                message.toolCalls.isEmpty() &&
                                message.attachments.isEmpty() &&
                                message.cards.isEmpty() &&
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
                                onCardAction = { msgId, cardKey, action ->
                                    // OPEN_URL is resolved at the UI layer
                                    // because launching ACTION_VIEW needs a
                                    // Context. We record the dispatch FIRST
                                    // via the ViewModel so the card collapses
                                    // even if the browser launch throws.
                                    if (action.mode == com.hermesandroid.relay.data.HermesCardAction.Modes.OPEN_URL) {
                                        chatViewModel.dispatchCardAction(msgId, cardKey, action)
                                        com.hermesandroid.relay.ui.components.handleCardActionExternally(
                                            context, action
                                        )
                                    } else {
                                        chatViewModel.dispatchCardAction(msgId, cardKey, action)
                                    }
                                },
                                onCardInput = { msgId, cardKey, value ->
                                    chatViewModel.answerAsk(msgId, cardKey, value)
                                },
                                onEditMessage = if (
                                    isGatewayTransport &&
                                    !isStreaming &&
                                    message.role == MessageRole.USER &&
                                    !message.id.startsWith("voice-intent-") &&
                                    !message.id.startsWith("steer-")
                                ) {
                                    { msg ->
                                        editingMessage = msg
                                        inputText = msg.content.take(charLimit)
                                    }
                                } else {
                                    null
                                },
                                onQuoteMessage = { text ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val quoted = text.take(600)
                                        .trim()
                                        .lines()
                                        .joinToString("\n") { line -> "> $line" }
                                    inputText = if (inputText.isBlank()) {
                                        "$quoted\n\n"
                                    } else {
                                        "$inputText\n$quoted\n\n"
                                    }
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

                            // Steered sends live inside a server-side tool
                            // result, not a user message — flag the local
                            // bubble so the scrollback explains itself.
                            if (message.role == MessageRole.USER && message.id.startsWith("steer-")) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    Text(
                                        text = "↳ steered",
                                        style = relayMetadataStyle(),
                                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f),
                                        modifier = Modifier.padding(top = 1.dp, end = 4.dp),
                                    )
                                }
                            }

                            if (toolDisplay != "off") {
                                // Subagent children (taskIndex != null) group
                                // into lanes after the top-level tool cards;
                                // the null group renders exactly as before.
                                val laneGroups = message.toolCalls.groupBy { it.taskIndex }
                                laneGroups[null]?.forEach { toolCall ->
                                    Spacer(modifier = Modifier.height(4.dp))
                                    when (toolDisplay) {
                                        "compact" -> CompactToolCall(toolCall = toolCall)
                                        else -> ToolProgressCard(
                                            toolCall = toolCall,
                                            messageTimestamp = message.timestamp,
                                        )
                                    }
                                }
                                laneGroups.keys.filterNotNull().sorted().forEach { taskIndex ->
                                    Spacer(modifier = Modifier.height(4.dp))
                                    SubagentLane(
                                        taskIndex = taskIndex,
                                        calls = laneGroups.getValue(taskIndex),
                                    )
                                }
                            }
                        }

                        // NOTE: no standalone StreamingDots item here — the
                        // streaming bubble already renders its own in-bubble
                        // dots (MessageBubble), and a second indicator below
                        // the bubble both read as a duplicate "typing" hint
                        // and churned animateItem placement at the viewport
                        // bottom on every delta (visible jitter at
                        // gateway/token delta frequency). Same reason the
                        // trailing spacer doesn't animateItem(): its position
                        // shifts on every delta of the growing bubble above
                        // it, and a constant 8dp gap gains nothing from
                        // placement animation.
                        item { Spacer(modifier = Modifier.height(8.dp)) }
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

            // Edit-and-resend mode chip — cancelable; submitting rewinds the
            // conversation from the edited message (gateway only).
            AnimatedVisibility(visible = editingMessage != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Editing — will rewind the conversation",
                        style = relayMetadataStyle(),
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            editingMessage = null
                            inputText = ""
                        },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Cancel editing",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Input bar — pill field with ONE trailing slot morphing
            // Send / Voice / Stop / Steer / Queue. "+" taps the file picker,
            // long-press opens the CommandPalette (the dedicated "/" button
            // is gone — typing "/" still surfaces InlineAutocomplete).
            val hasContent = inputText.isNotBlank() || pendingAttachments.isNotEmpty()
            val trailing = when {
                !isStreaming && hasContent -> ChatInputTrailing.SEND
                !isStreaming -> ChatInputTrailing.VOICE
                isStreaming && !hasContent -> ChatInputTrailing.STOP
                steerableTurn -> ChatInputTrailing.STEER
                else -> ChatInputTrailing.QUEUE
            }
            val inputCaption = when {
                isStreaming && hasContent && steerableTurn ->
                    "↳ sends now — Hermes adjusts mid-turn"
                isStreaming && hasContent -> "↳ delivered after this turn finishes"
                isStreaming && steerNotice != null -> steerNotice
                else -> null
            }
            val inputPlaceholder = when {
                editingMessage != null -> "Edit your message…"
                isStreaming && steerableTurn -> "Steer the response…"
                isStreaming -> "Queue a message..."
                else -> "Message..."
            }
            ChatInputBar(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = inputPlaceholder,
                trailing = trailing,
                onSend = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val editing = editingMessage
                    if (editing != null) {
                        // Only drop the edit state once the rewind actually
                        // dispatched — a silent gate must not eat the text.
                        if (chatViewModel.regenerateFromMessage(editing.id, inputText)) {
                            editingMessage = null
                            inputText = ""
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Can't edit right now — wait for the current turn to finish",
                                    duration = SnackbarDuration.Short,
                                )
                            }
                        }
                    } else {
                        chatViewModel.sendMessage(inputText.ifBlank { "[attachment]" })
                        inputText = ""
                    }
                },
                onVoice = {
                    if (voiceReady) {
                        requestVoiceMode()
                    } else {
                        android.widget.Toast.makeText(
                            context,
                            when (standardVoiceAvailability) {
                                com.hermesandroid.relay.viewmodel.StandardVoiceAvailability.SignInRequired ->
                                    standardVoiceSignInRouteHint?.let { route ->
                                        "Voice needs a one-time sign-in on the $route route — open Manage"
                                    } ?: "Voice needs dashboard sign-in — open Manage to sign in"
                                com.hermesandroid.relay.viewmodel.StandardVoiceAvailability.Unsupported ->
                                    "This Hermes build has no voice routes — update hermes-agent or pair Relay"
                                else ->
                                    "Voice needs a reachable Hermes dashboard or Relay voice route"
                            },
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                onStop = { chatViewModel.cancelStream() },
                onAttach = { filePickerLauncher.launch(arrayOf("*/*")) },
                onLongPressAttach = { showCommandPalette = true },
                charLimit = charLimit,
                caption = inputCaption,
                voiceReady = voiceReady,
                showVoiceHint = !voiceHintSeen,
                onVoiceHintShown = { connectionViewModel.setVoiceHintSeen(true) },
                isDarkTheme = isDarkTheme,
                enabled = chatReady,
            )
        } // end Column

        // Mic permission denied banner — title + body + Open Settings action.
        // System "Don't ask again" gives no callback, so a toast would leave
        // the user stranded. Banner + direct-to-app-details deep link is the
        // only reliable recovery path.
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
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "Microphone permission needed",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = "Tap Open Settings and grant Microphone access to use voice mode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { micPermissionDenied = false }) {
                            Text("Dismiss")
                        }
                        TextButton(onClick = {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            )
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }) {
                            Text("Open Settings")
                        }
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
                onPauseAutoMode = { voiceViewModel.pauseContinuousMode() },
                onDismiss = { voiceViewModel.exitVoiceMode() },
                onModeChange = { voiceViewModel.setInteractionMode(it) },
                onClearError = { voiceViewModel.clearError() },
                // Agent B's overlay collects this flow and renders classified
                // voice errors (mic capture, STT/TTS failures, relay drops).
                errorEvents = voiceViewModel.errorEvents,
                // Voice-first transcript: pass the last N chat messages so
                // voice mode can show a compact rolling history including
                // local-only voice-intent traces (agentName="Voice action").
                // Bounded to 12 to keep voice mode focused while still
                // preserving enough recent tool/context rows for voice turns.
                transcriptMessages = messages.takeLast(12),
                showThinking = showThinking,
                voiceEngineMode = voiceStats.voiceEngineMode,
                voiceOutputProvider = activeVoiceProvider,
                voiceOutputModel = activeVoiceModel,
                voiceOutputVoice = activeVoiceName,
                voiceProfileName = selectedProfile?.description?.takeIf { it.isNotBlank() }
                    ?: selectedProfile?.name,
                voiceConfigScope = activeVoiceScope,
                voiceOutputEnabled = activeVoiceEnabled,
                voiceOutputFallbackEnabled = voiceOutputConfig?.fallback_enabled,
                onOverlayRequest = showVoiceSystemOverlay,
                onCompactModeChange = { compact ->
                    voiceCompactMode = compact
                },
                // === v0.4.1 JIT permission-denied chip ===
                // Tap deep-links to Settings → Apps → Hermes Relay →
                // Permissions for the running package. Use BuildConfig
                // .APPLICATION_ID rather than a hard-coded string so both
                // the googlePlay and sideload flavors land on their own
                // package's permission page.
                onPermissionDeniedChipTap = { _ ->
                    runCatching {
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse(
                                "package:${com.hermesandroid.relay.BuildConfig.APPLICATION_ID}"
                            ),
                        ).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                    voiceViewModel.clearPermissionDeniedCallout()
                },
                onHermesConfirmationAnswer = { answer ->
                    voiceViewModel.answerHermesConfirmation(answer)
                },
                // === END v0.4.1 ===
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

    // Agent info sheet — one consolidated surface for agent state (profile,
    // personality, connection summary). Replaces the old AlertDialog and the
    // two top-bar chips (ProfilePicker + PersonalityPicker). Tap target is
    // the title Row in the TopAppBar above.
    if (showAgentInfo) {
        AgentInfoSheet(
            connectionViewModel = connectionViewModel,
            chatViewModel = chatViewModel,
            onDismiss = { showAgentInfo = false },
            onNavigateToConnections = onNavigateToConnections,
            onNavigateToProfileInspector = onNavigateToProfileInspector,
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

/**
 * Share the visible conversation as Markdown via the system share sheet.
 * Role names are matched as strings so this helper stays decoupled from the
 * MessageRole enum's package.
 */
private fun shareConversation(
    context: android.content.Context,
    messages: List<com.hermesandroid.relay.data.ChatMessage>,
) {
    val body = buildString {
        appendLine("# Hermes conversation")
        appendLine()
        messages.forEach { message ->
            if (message.content.isBlank()) return@forEach
            val speaker = when {
                message.role.name.equals("user", ignoreCase = true) -> "**You:**"
                message.role.name.equals("assistant", ignoreCase = true) -> "**Hermes:**"
                else -> "**System:**"
            }
            appendLine(speaker)
            appendLine(message.content.trim())
            appendLine()
        }
    }
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, body)
        putExtra(android.content.Intent.EXTRA_SUBJECT, "Hermes conversation")
    }
    context.startActivity(
        android.content.Intent.createChooser(intent, "Share conversation"),
    )
}
