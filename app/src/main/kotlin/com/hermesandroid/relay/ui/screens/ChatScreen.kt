@file:Suppress("LocalContextGetResourceValueCall")

package com.hermesandroid.relay.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.MutatePriority
import com.hermesandroid.relay.ui.theme.LocalBrand
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.theme.radialNavyBackground
import com.hermesandroid.relay.network.upstream.ChatMode
import com.hermesandroid.relay.network.upstream.GatewayAvailability
import com.hermesandroid.relay.network.relay.RelayVoiceClient
import com.hermesandroid.relay.network.relay.RealtimeVoiceConfig
import com.hermesandroid.relay.network.relay.VoiceOutputConfig
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.ui.platform.LocalContext
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.Attachment
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.data.hermesProcessNotificationOrNull
import com.hermesandroid.relay.ui.components.AgentInfoSheet
import com.hermesandroid.relay.ui.components.BackgroundTaskCard
import com.hermesandroid.relay.ui.components.LocalRelayServerImageResolver
import com.hermesandroid.relay.ui.components.RelayServerImageResolver
import com.hermesandroid.relay.ui.components.ChatInputBar
import com.hermesandroid.relay.ui.components.CleanChatMode
import com.hermesandroid.relay.ui.components.ChatInputPickerControl
import com.hermesandroid.relay.ui.components.ChatInputPickerOption
import com.hermesandroid.relay.ui.components.ChatInputTrailing
import com.hermesandroid.relay.ui.components.CommandPalette
import com.hermesandroid.relay.ui.components.KeepScreenOnWhile
import com.hermesandroid.relay.ui.components.ModelPickerSheet
import com.hermesandroid.relay.ui.components.ConnectionStatusBadge
import com.hermesandroid.relay.ui.components.CommandRow
import com.hermesandroid.relay.ui.components.CompactToolCall
import com.hermesandroid.relay.ui.components.ContextMeterBar
import com.hermesandroid.relay.ui.components.GatewayBackgroundProcessSheet
import com.hermesandroid.relay.ui.components.GatewayBackgroundProcessStrip
import com.hermesandroid.relay.ui.components.InjectedContextSheet
import com.hermesandroid.relay.ui.components.InlineAutocomplete
import com.hermesandroid.relay.ui.components.loadedContentTransform
import com.hermesandroid.relay.ui.components.MessageBubble
import com.hermesandroid.relay.ui.components.SyntheticProcessNotificationNotice
import com.hermesandroid.relay.ui.components.avatar.AvatarRenderState
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.hermesandroid.relay.ui.components.LocalAgentIconPath
import com.hermesandroid.relay.ui.components.avatar.LocalAgentAvatar
import java.io.File
import com.hermesandroid.relay.ui.components.RelayChromeIconButton
import com.hermesandroid.relay.ui.components.SphereState
import com.hermesandroid.relay.ui.components.LocalThinkingIndicator
import com.hermesandroid.relay.ui.components.ThinkingIndicatorConfig
import com.hermesandroid.relay.ui.components.ThinkingIndicatorStyle
import com.hermesandroid.relay.ui.components.ThinkingMatrixColor
import com.hermesandroid.relay.ui.components.ThinkingMatrixPattern
import com.hermesandroid.relay.ui.components.SessionDrawerContent
import com.hermesandroid.relay.ui.components.SlashCommand
import com.hermesandroid.relay.ui.components.SubagentLane
import com.hermesandroid.relay.ui.components.ToolProgressCard
import com.hermesandroid.relay.ui.components.VoiceModeOverlay
import com.hermesandroid.relay.ui.LocalSnackbarHost
import com.hermesandroid.relay.ui.showHumanError
import com.hermesandroid.relay.ui.theme.RelayRefresh
import kotlin.math.abs
import com.hermesandroid.relay.ui.theme.relayGridTexture
import com.hermesandroid.relay.ui.theme.relayMetadataStyle
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.roundToInt
import com.hermesandroid.relay.viewmodel.ChatViewModel
import com.hermesandroid.relay.viewmodel.ChatConnectState
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
 * A same-author run breaks into a new visual group once the gap to the
 * neighboring message exceeds this — so a conversation resumed after a pause
 * reads as a fresh beat (its own agent-name label, its own timestamp, more air)
 * instead of one unbroken monologue. Matches the iMessage/Discord convention of
 * resetting grouping after a short idle.
 */
private const val GROUP_GAP_MS = 5 * 60_000L

/**
 * Snapshot of the streaming-state fields the auto-scroll effect watches.
 *
 * Captured inside a `snapshotFlow { ... }` so distinctUntilChanged can
 * detect any meaningful change (new message, longer text, longer reasoning,
 * new tool card, message-id reconciliation, streaming on/off) and re-trigger
 * an auto-follow scroll.
 *
 * `equals` is auto-generated by `data class`, which gives field-wise
 * comparison — exactly the behavior distinctUntilChanged needs.
 */
internal data class ChatScrollSnapshot(
    val messageCount: Int,
    val lastMessageId: String?,
    val lastMessageUiKey: String?,
    val lastContentLength: Int,
    val lastThinkingLength: Int,
    val lastToolCallCount: Int,
    val isStreaming: Boolean
)

internal fun ChatScrollSnapshot.isCompletionAfter(previous: ChatScrollSnapshot?): Boolean =
    previous?.isStreaming == true &&
        !isStreaming &&
        previous.messageCount == messageCount &&
        previous.lastMessageUiKey == lastMessageUiKey

private class ChatTailTransitionRef(
    var snapshot: ChatScrollSnapshot? = null,
)

private data class ChatTailLayoutSnapshot(
    val uiKey: String?,
    val measuredSizePx: Int?,
    val shouldFollowGrowth: Boolean,
)

private fun LazyListState.isAtConversationBottom(slopPx: Int): Boolean {
    val layout = layoutInfo
    if (layout.totalItemsCount == 0) return true
    val last = layout.visibleItemsInfo.lastOrNull() ?: return false
    return last.index == layout.totalItemsCount - 1 &&
        (last.offset + last.size) - layout.viewportEndOffset <= slopPx
}

private suspend fun LazyListState.scrollToConversationBottom(
    animated: Boolean,
    slopPx: Int,
) {
    var animateNext = animated
    var settledFrames = 0
    repeat(10) { attempt ->
        withFrameNanos { }
        val lastIndex = layoutInfo.totalItemsCount - 1
        if (lastIndex < 0) return
        if (attempt == 0 || !isAtConversationBottom(slopPx)) {
            if (animateNext) {
                animateNext = false
                animateScrollToItem(lastIndex)
            } else {
                scrollToItem(lastIndex)
            }
        }
        withFrameNanos { }
        if (isAtConversationBottom(slopPx)) {
            settledFrames += 1
            if (settledFrames >= 2) return
        } else {
            settledFrames = 0
        }
    }
}

private fun LazyListState.scrollTickerProgress(): Float {
    val layout = layoutInfo
    val visibleItems = layout.visibleItemsInfo
    if (layout.totalItemsCount == 0 || visibleItems.isEmpty()) return 1f
    if (!canScrollBackward) return 0f
    if (!canScrollForward) return 1f

    val first = visibleItems.first()
    val visibleItemCount = visibleItems.size.coerceAtLeast(1)
    val maxFirstIndex = (layout.totalItemsCount - visibleItemCount).coerceAtLeast(1)
    val itemScroll =
        (layout.viewportStartOffset - first.offset).coerceAtLeast(0).toFloat() /
            first.size.coerceAtLeast(1)

    return ((first.index + itemScroll) / maxFirstIndex).coerceIn(0f, 1f)
}

@Composable
private fun ChatScrollTicker(
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val isScrollable by remember(listState) {
        derivedStateOf { listState.canScrollBackward || listState.canScrollForward }
    }
    val targetProgress by remember(listState) {
        derivedStateOf { listState.scrollTickerProgress() }
    }
    val progress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(
            durationMillis = if (listState.isScrollInProgress) 90 else 180,
            easing = LinearEasing,
        ),
        label = "chatScrollTickerProgress",
    )
    val alpha by animateFloatAsState(
        targetValue = when {
            !isScrollable -> 0f
            listState.isScrollInProgress -> 0.95f
            else -> 0.54f
        },
        animationSpec = tween(durationMillis = 160),
        label = "chatScrollTickerAlpha",
    )

    if (alpha <= 0.02f) return

    val baseColor = MaterialTheme.colorScheme.onSurfaceVariant
    val activeColor = RelayRefresh.Relay
    Canvas(
        modifier = modifier
            .width(18.dp)
            .fillMaxHeight()
            .alpha(alpha),
    ) {
        if (size.height <= 0f) return@Canvas

        val dashHeight = 8.dp.toPx()
        val dashGap = 7.dp.toPx()
        val step = dashHeight + dashGap
        val minDashWidth = 1.4.dp.toPx()
        val maxDashWidth = 4.4.dp.toPx()
        val rightInset = 6.dp.toPx()
        val activeRadius = 72.dp.toPx().coerceAtMost(size.height * 0.42f)
        val activeCenter = (dashHeight / 2f) +
            progress.coerceIn(0f, 1f) * (size.height - dashHeight).coerceAtLeast(1f)
        val phase = (progress * step * 2f) % step
        val x = size.width - rightInset
        var y = -phase

        while (y < size.height) {
            val dashCenter = y + dashHeight / 2f
            val influence = (1f - abs(dashCenter - activeCenter) / activeRadius)
                .coerceIn(0f, 1f)
            val dashWidth = minDashWidth + (maxDashWidth - minDashWidth) * influence
            val dashAlpha = 0.18f + 0.64f * influence
            val color = if (influence > 0.04f) activeColor else baseColor

            drawRoundRect(
                color = color.copy(alpha = dashAlpha),
                topLeft = Offset(x - dashWidth / 2f, y),
                size = Size(dashWidth, dashHeight),
                cornerRadius = CornerRadius(dashWidth / 2f, dashWidth / 2f),
            )
            y += step
        }
    }
}

private data class ChatLoadingCommand(
    val state: ChatLoadingCommandState,
    val command: String,
    val detail: String,
)

private enum class ChatLoadingCommandState {
    Pending,
    Active,
    Done,
    Failed,
}

private val CHAT_LOADING_SPINNER_FRAMES = listOf("|", "/", "-", "\\")

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
    // Offline demo entry, surfaced on the empty-chat "needs connection" card so a
    // skipped / never-connected first run can explore without a server. null hides it.
    onTryDemo: (() -> Unit)? = null,
    onNavigateToManage: () -> Unit = {},
    onNavigateToBridge: () -> Unit = {},
    onNavigateToTerminal: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    // Voice mode gear button → full Voice Settings screen. Default no-op so
    // existing test/preview call sites keep compiling.
    onNavigateToVoiceSettings: () -> Unit = {},
    onNavigateToProfileInspector: (String) -> Unit = {},
) {
    val voiceUiState by voiceViewModel.uiState.collectAsState()
    val isDemoMode by connectionViewModel.isDemoMode.collectAsState()
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
            if (pendingVoiceEnter && !isDemoMode) {
                pendingVoiceEnter = false
                voiceViewModel.enterVoiceMode()
            } else {
                pendingVoiceEnter = false
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
    // Keep the screen on for the two "actively engaged, hands-off-keyboard"
    // cases: voice mode is a call-like continuous session (mirrors Assistant/
    // phone-call UIs, held the whole time the overlay is up), and an
    // in-flight chat reply is closer to video playback (held only while
    // isStreaming — reading/scrolling an idle transcript uses the OS default,
    // matching WhatsApp/Telegram/Signal norms). Single call site: the window
    // flag isn't ref-counted, see KeepScreenOnWhile's doc comment.
    KeepScreenOnWhile(enabled = voiceUiState.voiceMode || isStreaming)
    val turnStatus by chatViewModel.turnStatus.collectAsState()
    val recoveringAnswer by chatViewModel.recoveringAnswer.collectAsState()
    val voiceStats by voiceViewModel.voiceStats.collectAsState()
    var voiceOutputConfig by remember { mutableStateOf<VoiceOutputConfig?>(null) }
    var realtimeAgentConfig by remember { mutableStateOf<RealtimeVoiceConfig?>(null) }
    val chatReady by connectionViewModel.chatReady.collectAsState()
    val chatConnectState by connectionViewModel.chatConnectState.collectAsState()
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
    val serverAutoTitles by chatViewModel.serverAutoTitles.collectAsState()
    val currentSessionId by chatViewModel.currentSessionId.collectAsState()
    val backgroundProcesses by chatViewModel.backgroundProcesses.collectAsState()
    val backgroundProcessesLoading by chatViewModel.backgroundProcessesLoading.collectAsState()
    val stoppingProcessIds by chatViewModel.stoppingProcessIds.collectAsState()
    val isLoadingHistory by chatViewModel.isLoadingHistory.collectAsState()
    val isLoadingSessions by chatViewModel.isLoadingSessions.collectAsState()
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
    val profileDisplayAlias by connectionViewModel.profileDisplayAlias.collectAsState()
    val activeConnection by connectionViewModel.activeConnection.collectAsState()
    val serverModelName by chatViewModel.serverModelName.collectAsState()
    val availableModels by chatViewModel.availableModels.collectAsState()
    val modelProviders by chatViewModel.modelProviders.collectAsState()
    val modelOptionsRefreshing by chatViewModel.modelOptionsRefreshing.collectAsState()
    val selectedModelOverride by chatViewModel.selectedModelOverride.collectAsState()
    val pendingOneTurnModelOverride by chatViewModel.pendingOneTurnModelOverride.collectAsState()
    val activeOneTurnModelOverride by chatViewModel.activeOneTurnModelOverride.collectAsState()
    val gatewayCurrentModel by chatViewModel.gatewayCurrentModel.collectAsState()
    val selectedReasoningEffort by chatViewModel.selectedReasoningEffort.collectAsState()
    val showThinking by connectionViewModel.showThinking.collectAsState()
    val toolDisplay by connectionViewModel.toolDisplay.collectAsState()
    val smoothAutoScroll by connectionViewModel.smoothAutoScroll.collectAsState()
    val closeDrawerOnSend by connectionViewModel.closeDrawerOnSend.collectAsState()
    val keepComposerFocusedOnSend by
        connectionViewModel.keepComposerFocusedOnSend.collectAsState()

    val availableSkills by chatViewModel.availableSkills.collectAsState()
    val queuedMessages by chatViewModel.queuedMessages.collectAsState()
    val recentPrompts by chatViewModel.recentPrompts.collectAsState()
    val recentPromptsEnabled by connectionViewModel.chatRecentPromptsEnabled.collectAsState()
    // Effective approval-bypass (server-computed: ORs global approvals.mode=off,
    // the --yolo env, and the per-session flag). Drives a subtle status-strip
    // marker so the user knows approvals are off without opening the agent drawer.
    val yoloEnabled by chatViewModel.yoloEnabled.collectAsState()
    val pendingAttachments by chatViewModel.pendingAttachments.collectAsState()
    val maxAttachmentMb by connectionViewModel.maxAttachmentMb.collectAsState()
    val charLimit by connectionViewModel.maxMessageLength.collectAsState()

    // === Gateway desktop-parity state ===
    val serverCommands by chatViewModel.serverCommands.collectAsState()
    val contextUsage by chatViewModel.contextUsage.collectAsState()
    val contextWindow by chatViewModel.contextWindow.collectAsState()
    // Injected-context audit sheet (opened by tapping the context meter).
    var showContextSheet by remember { mutableStateOf(false) }
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

    // Recover any durable in-flight chat checkpoint whenever Chat returns to
    // the foreground. On Gateway this also pre-warms/re-attaches the socket;
    // sessions-SSE falls back to bounded persisted-history reconciliation.
    val appForeground by com.hermesandroid.relay.util.AppForegroundTracker.isForeground.collectAsState()
    LaunchedEffect(isGatewayTransport, appForeground, chatReady) {
        if (appForeground && chatReady) {
            chatViewModel.prewarmGateway()
        }
        if (isGatewayTransport && appForeground && chatReady) {
            chatViewModel.refreshModelOptions()
            chatViewModel.refreshReasoningSettings()
        }
    }

    // Cold-open recovery: the dashboard probe that flips gatewayAvailability to
    // Ready (and with it isGatewayTransport, which gates the model + reasoning-
    // effort controls) is otherwise only retried on the 30s periodic health
    // tick. So a freshly-opened chat — especially when the dashboard route
    // resolves a beat after the API client is built — can sit up to ~30s
    // showing the model pill but no effort chip. Nudge a few quick probes
    // until the verdict settles, then fall back to the slow cadence. Cheap
    // (a public GET /api/status), bounded, and self-disarming.
    LaunchedEffect(appForeground, chatReady) {
        if (!appForeground || !chatReady) return@LaunchedEffect
        var tries = 0
        while (
            tries < 5 &&
            connectionViewModel.gatewayAvailability.value == GatewayAvailability.Unknown
        ) {
            connectionViewModel.refreshStandardVoice()
            tries++
            delay(1_500)
        }
    }

    // Edit-and-resend mode: long-press a user bubble → "Edit & resend"
    // prefills the input; submit rewinds the conversation from that message.
    var editingMessage by remember { mutableStateOf<ChatMessage?>(null) }

    // Animation settings
    val animationEnabled by connectionViewModel.animationEnabled.collectAsState()
    val animationBehindChat by connectionViewModel.animationBehindChat.collectAsState()
    val thinkingIndicatorStyle by connectionViewModel.thinkingIndicatorStyle.collectAsState()
    val thinkingMatrixPattern by connectionViewModel.thinkingMatrixPattern.collectAsState()
    val thinkingMatrixColor by connectionViewModel.thinkingMatrixColor.collectAsState()
    var ambientMode by remember { mutableStateOf(false) } // clean text-flow mode, hides chat
    // Clean-mode discoverability hint: a persistent pill shown ONLY on the
    // empty / new-chat view (no messages) — it teaches the long-press entry
    // without nagging mid-conversation. Derived directly from the message list
    // at the pill below; no one-shot timer, no auto-dismiss.

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
    var showModelSheet by remember { mutableStateOf(false) }
    var showAgentInfo by remember { mutableStateOf(false) }
    var showBackgroundProcesses by remember { mutableStateOf(false) }

    // A process inventory is scoped to one gateway session. Never leave a
    // sheet opened onto a different chat after a drawer/profile switch.
    LaunchedEffect(currentSessionId, selectedProfile?.name, activeConnection?.id) {
        showBackgroundProcesses = false
    }

    // Server command dispatch can ask the composer to prefill (e.g. /undo).
    LaunchedEffect(chatViewModel) {
        chatViewModel.composerPrefill.collect { text ->
            editingMessage = null
            inputText = text.take(charLimit)
        }
    }

    // A bare `/personality` opens the agent sheet (its Personality section is the
    // mobile equivalent of the desktop's inline arg-picker for picker commands).
    LaunchedEffect(chatViewModel) {
        chatViewModel.openPersonalityPicker.collect {
            showAgentInfo = true
        }
    }

    // A bare `/model` opens the model picker — the sibling picker command.
    LaunchedEffect(chatViewModel) {
        chatViewModel.openModelPicker.collect {
            showModelSheet = true
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
    val copiedToClipboardMsg = stringResource(R.string.chat_copied_to_clipboard)
    val hermesMessageLabel = stringResource(R.string.chat_hermes_message)
    val focusManager = LocalFocusManager.current
    val finishSuccessfulSend: () -> Unit = {
        if (closeDrawerOnSend && drawerState.isOpen) {
            scope.launch { drawerState.close() }
        }
        if (!keepComposerFocusedOnSend) {
            focusManager.clearFocus()
        }
    }
    val clipboard = LocalClipboard.current
    val haptic = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Ephemeral notices from the VM (model-switch warnings/errors, etc.) →
    // transient snackbar, never a chat bubble.
    LaunchedEffect(Unit) {
        chatViewModel.transientNotice.collect { msg ->
            snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Short)
        }
    }

    val realtimeAgentActive = voiceStats.voiceEngineMode == "realtime_agent"
    val activeVoiceProvider = if (realtimeAgentActive) {
        realtimeAgentConfig?.default_provider
    } else {
        voiceOutputConfig?.default_provider
    }
    val activeVoiceModel = if (realtimeAgentActive) {
        voiceStats.realtimeModel.takeIf { it.isNotBlank() }
            ?: realtimeAgentConfig?.default_model
    } else {
        voiceOutputConfig?.default_model
    }
    val activeVoiceName = if (realtimeAgentActive) {
        voiceStats.realtimeVoice.takeIf { it.isNotBlank() }
            ?: realtimeAgentConfig?.default_voice
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
                    message = context.getString(R.string.chat_overlay_perm_enable),
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
                        message = context.getString(R.string.chat_overlay_start_failed),
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
                                message = context.getString(R.string.chat_overlay_perm_denied),
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

    // Outbound attach launchers — Files / Photos / Camera all funnel through
    // the top-level ingestAttachmentFromUri helper so the read → size-cap →
    // base64 → addAttachment pipeline lives in exactly one place. The "+" button
    // surfaces them as a small Photos / Files / Camera menu; clipboard paste
    // reuses the same pipeline for desktop `/paste` parity.

    // Files: arbitrary types via the Storage Access Framework (multi-select).
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            ingestAttachmentFromUri(context, uri, maxAttachmentMb) { chatViewModel.addAttachment(it) }
        }
    }

    // Photos: modern permissionless Android Photo Picker (images, multi-select).
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        uris.forEach { uri ->
            ingestAttachmentFromUri(context, uri, maxAttachmentMb) { chatViewModel.addAttachment(it) }
        }
    }

    // Camera: capture into a FileProvider temp uri (held across the launcher
    // round-trip), then ingest on success. CAMERA is declared in the manifest,
    // so the system enforces the runtime grant before ACTION_IMAGE_CAPTURE will
    // launch — hence the permission gate below, mirroring the mic flow.
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (success && uri != null) {
            ingestAttachmentFromUri(context, uri, maxAttachmentMb) { chatViewModel.addAttachment(it) }
        }
    }
    val launchCamera: () -> Unit = {
        runCatching {
            val uri = createCameraCaptureUri(context)
            pendingCameraUri = uri
            cameraLauncher.launch(uri)
        }.onFailure {
            pendingCameraUri = null
            Toast.makeText(context, context.getString(R.string.chat_camera_open_failed), Toast.LENGTH_SHORT).show()
        }
    }
    var pendingCameraAfterPermission by remember { mutableStateOf(false) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val wanted = pendingCameraAfterPermission
        pendingCameraAfterPermission = false
        if (granted && wanted) {
            launchCamera()
        } else if (!granted) {
            Toast.makeText(
                context,
                context.getString(R.string.chat_camera_perm_needed),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
    val requestCameraCapture: () -> Unit = {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            launchCamera()
        } else {
            pendingCameraAfterPermission = true
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    // Clipboard image paste (desktop `/paste` parity). The Compose Clipboard
    // API is suspend-based, so the read rides scope.launch; on a miss we hint
    // the user rather than fail silently.
    val pasteImageFromClipboard: () -> Unit = {
        scope.launch {
            val clip = clipboard.getClipEntry()?.clipData
            val handled = ingestClipboardImage(context, clip, maxAttachmentMb) {
                chatViewModel.addAttachment(it)
            }
            if (!handled) {
                snackbarHostState.showSnackbar(
                    message = context.getString(R.string.chat_no_clipboard_image),
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }

    // True when the LazyColumn is scrolled all the way to the bottom.
    // canScrollForward is the canonical signal — no off-by-one arithmetic on
    // visibleItemsInfo (which has to account for header/footer spacers and
    // the StreamingDots indicator). This is also the trigger that resumes
    // auto-follow after the user scrolls back down manually.
    // "At bottom" with a small slop (~1.5 lines of text) rather than the exact
    // `!canScrollForward`. A burst of streaming content — or a sub-frame layout
    // gap before the auto-follow re-pins — can momentarily make the list
    // scrollable-forward; the strict check would read that as "user scrolled
    // away" and drop the follow. The slop keeps the Telegram-style follow
    // sticky through streaming jitter while still flipping to "scrolled away"
    // on a real read-up gesture.
    val atBottomSlopPx = 140
    val isAtBottom by remember {
        derivedStateOf {
            listState.isAtConversationBottom(atBottomSlopPx)
        }
    }

    // True when the user has scrolled up (away from the bottom). The
    // streaming auto-scroll effect respects this — it will not yank the
    // user back to the latest token while they are reading history.
    // Reset to false the moment the user returns to the bottom.
    var userScrolledAway by remember(currentSessionId) { mutableStateOf(false) }
    var isUserDragging by remember(currentSessionId) { mutableStateOf(false) }
    var programmaticBottomScroll by remember { mutableStateOf(false) }
    var retainedLiveTailUiKey by remember(currentSessionId) { mutableStateOf<String?>(null) }
    var completionSettlingUiKey by remember(currentSessionId) { mutableStateOf<String?>(null) }
    val currentUnreadSnapshot = remember(messages) { messages.toUnreadSnapshot() }
    var lastReadSnapshot by remember(currentSessionId) {
        mutableStateOf(currentUnreadSnapshot)
    }
    LaunchedEffect(currentSessionId, currentUnreadSnapshot, userScrolledAway) {
        if (!userScrolledAway) lastReadSnapshot = currentUnreadSnapshot
    }
    val unreadMessageCount = remember(
        currentUnreadSnapshot,
        lastReadSnapshot,
        userScrolledAway,
    ) {
        if (userScrolledAway) {
            countUnreadMessages(currentUnreadSnapshot, lastReadSnapshot)
        } else {
            0
        }
    }

    suspend fun scrollConversationToBottom(animated: Boolean) {
        programmaticBottomScroll = true
        try {
            listState.scrollToConversationBottom(
                animated = animated,
                slopPx = atBottomSlopPx,
            )
            userScrolledAway = false
        } finally {
            programmaticBottomScroll = false
        }
    }

    // Decide "is the user reading history" from actual touch drags only.
    // isScrollInProgress also becomes true for our own animated bottom scroll;
    // if that animation is cancelled by the next stream batch, its falling
    // edge can race the programmatic flag and falsely disable auto-follow for
    // the rest of the turn. LazyListState's interaction source emits only real
    // drag gestures, so it cleanly separates user intent from app scrolling.
    // Pause follow at drag start so a new token cannot fight the finger, but do
    // not classify the user as reading history until the gesture actually ends
    // above the bottom. A tiny/cancelled touch must not poison the next turn.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    isUserDragging = true
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    isUserDragging = false
                    userScrolledAway = !listState.isAtConversationBottom(atBottomSlopPx)
                }
            }
        }
    }
    // Reaching the bottom by any means (user, follow-pin, content shrank)
    // always re-arms auto-follow.
    LaunchedEffect(isAtBottom) {
        if (isAtBottom) userScrolledAway = false
    }

    // Scroll-to-bottom FAB visibility. The button means "you've scrolled up —
    // tap to catch up", so it must NOT flash while we're auto-pinning to the
    // bottom. Suppress it when:
    //   • a programmatic scroll is in flight (we're actively settling), or
    //   • we're streaming-and-following (smoothAutoScroll on, not scrolled
    //     away) — a content burst can momentarily make the list scrollable-
    //     forward for a frame before the re-pin, which would otherwise blink
    //     the FAB on every token.
    // Once the user genuinely scrolls up (userScrolledAway), the streaming
    // suppression lifts and the button appears.
    val showScrollToBottom by remember {
        derivedStateOf {
            val retainingVisibleTail = retainedLiveTailUiKey != null &&
                messages.lastOrNull()?.uiKey == retainedLiveTailUiKey
            messages.isNotEmpty() &&
                !isAtBottom &&
                !programmaticBottomScroll &&
                !((isStreaming || retainingVisibleTail) &&
                    smoothAutoScroll &&
                    !userScrolledAway)
        }
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
            )

            // Dynamic personality commands from server, plus the upstream
            // "none" (clear the overlay) option that completions list first.
            val personalities = listOf(
                SlashCommand(
                    command = "/personality none",
                    description = "Clear the personality overlay",
                    category = "personality"
                )
            ) + personalityNames.map { name ->
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

    // Opening the drawer re-syncs the list — so a session created on another
    // device (or one whose optimistic row was dropped on a profile switch)
    // shows up without a manual reload. Cheap dashboard read; the optimistic
    // row for the active session is preserved by ChatHandler.updateSessions.
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen && chatReady) {
            chatViewModel.refreshSessions()
        }
    }

    LaunchedEffect(currentSessionId, isLoadingHistory) {
        if (!isLoadingHistory && currentSessionId != null && messages.isNotEmpty()) {
            scrollConversationToBottom(animated = false)
        }
    }

    val tailMessage = messages.lastOrNull()
    val tailTransition = ChatScrollSnapshot(
        messageCount = messages.size,
        lastMessageId = tailMessage?.id,
        lastMessageUiKey = tailMessage?.uiKey,
        lastContentLength = tailMessage?.content?.length ?: 0,
        lastThinkingLength = tailMessage?.thinkingContent?.length ?: 0,
        lastToolCallCount = tailMessage?.toolCalls?.size ?: 0,
        isStreaming = tailMessage?.isStreaming == true,
    )
    val tailTransitionRef = remember(currentSessionId) { ChatTailTransitionRef() }

    // New rows and streaming -> final Markdown are structural transitions.
    // Anchor their trailing spacer in SideEffect so the request participates in
    // the very next remeasure instead of correcting an already-drawn frame.
    SideEffect {
        val previous = tailTransitionRef.snapshot
        val streamStarted = tailTransition.isStreaming && previous?.isStreaming != true
        val completed = tailTransition.isCompletionAfter(previous)
        val tailStructureChanged = tailTransition.lastMessageUiKey != null &&
            (previous == null ||
                previous.messageCount != tailTransition.messageCount ||
                previous.lastMessageUiKey != tailTransition.lastMessageUiKey)

        if (streamStarted) {
            // Sending a turn means "follow my new answer" even if the idle
            // transcript had previously been left above the bottom. Do not
            // clear isUserDragging: a real finger keeps priority until release.
            userScrolledAway = false
            retainedLiveTailUiKey = tailTransition.lastMessageUiKey
        } else if (completed) {
            completionSettlingUiKey = tailTransition.lastMessageUiKey
        } else if (
            tailStructureChanged &&
            retainedLiveTailUiKey != null &&
            retainedLiveTailUiKey != tailTransition.lastMessageUiKey
        ) {
            retainedLiveTailUiKey = null
        }

        val shouldAnchor = smoothAutoScroll &&
            !isUserDragging &&
            (!userScrolledAway || streamStarted) &&
            (streamStarted || completed || tailStructureChanged)
        if (shouldAnchor) {
            listState.requestScrollToItem(tailTransition.messageCount + 1)
        }

        tailTransitionRef.snapshot = tailTransition
    }

    // Completion adds the timestamp/footer after the final token. Keep the
    // stable live renderer, then consume any small remaining forward range for
    // two settled frames. scrollBy preserves the current item anchor and is
    // visually inert when already at the exact bottom; unlike scrollToItem it
    // cannot align the top of a tall response with the viewport.
    LaunchedEffect(
        completionSettlingUiKey,
        smoothAutoScroll,
        userScrolledAway,
        isUserDragging,
    ) {
        val settlingKey = completionSettlingUiKey ?: return@LaunchedEffect
        if (!smoothAutoScroll || userScrolledAway || isUserDragging) {
            completionSettlingUiKey = null
            return@LaunchedEffect
        }

        var settledFrames = 0
        repeat(6) {
            withFrameNanos { }
            if (messages.lastOrNull()?.uiKey != settlingKey) {
                completionSettlingUiKey = null
                return@LaunchedEffect
            }

            if (listState.canScrollForward) {
                settledFrames = 0
                val viewportHeight = listState.layoutInfo.viewportSize.height
                if (viewportHeight > 0) {
                    listState.scroll(MutatePriority.Default) {
                        scrollBy(viewportHeight.toFloat())
                    }
                }
            } else {
                settledFrames += 1
                if (settledFrames >= 2) {
                    completionSettlingUiKey = null
                    return@LaunchedEffect
                }
            }
        }
        completionSettlingUiKey = null
    }

    // Ordinary streaming growth keeps the same row and Text node. Advance the
    // existing scroll position by exactly the measured positive height delta;
    // never replace the logical anchor with scrollToItem(). User input has a
    // higher mutation priority and cancels this work naturally.
    LaunchedEffect(listState, smoothAutoScroll, userScrolledAway, isUserDragging) {
        if (!smoothAutoScroll || userScrolledAway || isUserDragging) return@LaunchedEffect

        var previousLayout: ChatTailLayoutSnapshot? = null
        snapshotFlow {
            val tail = messages.lastOrNull()
            val tailSize = tail?.uiKey?.let { uiKey ->
                listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { item -> item.key == uiKey }
                    ?.size
            }
            ChatTailLayoutSnapshot(
                uiKey = tail?.uiKey,
                measuredSizePx = tailSize,
                shouldFollowGrowth = tail?.isStreaming == true ||
                    (retainedLiveTailUiKey != null && tail?.uiKey == retainedLiveTailUiKey),
            )
        }
            .distinctUntilChanged()
            .collect { current ->
                val previous = previousLayout
                previousLayout = current
                val previousSize = previous?.measuredSizePx ?: return@collect
                val currentSize = current.measuredSizePx ?: return@collect
                if (!current.shouldFollowGrowth || previous.uiKey != current.uiKey) return@collect

                val growthPx = currentSize - previousSize
                if (growthPx > 0) {
                    listState.scroll(MutatePriority.Default) {
                        scrollBy(growthPx.toFloat())
                    }
                }
            }
    }

    // Completion haptic only; scroll ownership remains with the transition and
    // measured-growth paths above.
    var observedActiveStream by remember { mutableStateOf(false) }
    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            observedActiveStream = true
        } else if (observedActiveStream) {
            observedActiveStream = false
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    // Haptic on error
    LaunchedEffect(error) {
        if (error != null) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    // Display profile - the one the header should reflect. Priority:
    //   1. explicit user pick (selectedProfile)
    //   2. server-advertised profile named "default"
    //   3. null (fall back to personality-derived name)
    //
    // Computed as a derived state so the header cross-fades when the user
    // picks a new profile OR when the server's profile catalog finishes
    // loading and a "default" entry shows up.
    val effectiveProfile by remember(selectedProfile, agentProfiles) {
        derivedStateOf {
            AgentDisplay.effectiveDisplayProfile(
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
    // defaultPersonality, local alias, activeConnection) — the previous plain
    // `remember(k1,k2,k3,k4)` form relied on equality diffs against those
    // four keys, which missed updates in some cases (most notably a
    // profile switch while the ConnectionInfoSheet was open, where the
    // ambient sheet scope appeared to swallow the key comparison).
    val agentDisplayName by remember(
        effectiveProfile,
        selectedPersonality,
        defaultPersonality,
        profileDisplayAlias,
        activeConnection?.label,
    ) {
        derivedStateOf {
            val profile = effectiveProfile
            AgentDisplay.agentName(
                profile = profile,
                selectedPersonality = selectedPersonality,
                defaultPersonality = defaultPersonality,
                connectionLabel = activeConnection?.label,
                localDisplayAlias = profileDisplayAlias,
            )
        }
    }
    val hasLiveConversationSurface = messages.isNotEmpty() || isStreaming
    val isChatConnecting = chatConnectState == ChatConnectState.Connecting &&
        !hasLiveConversationSurface

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Disable the drawer's edge-swipe while voice mode is up so the
        // overlay reads as a true modal — the swipe gesture lives on the
        // drawer itself, so the overlay's pointer scrim alone can't block it.
        gesturesEnabled = !voiceUiState.voiceMode,
        drawerContent = {
            val drawerTitle = if (selectedProfile != null) {
                stringResource(R.string.chat_profile_sessions, agentDisplayName)
            } else {
                stringResource(R.string.chat_server_default_sessions)
            }
            val drawerSubtitle = when {
                selectedProfile?.hasIsolatedApi == true ->
                    "${stringResource(R.string.chat_profile_api_label)}: ${selectedProfile?.apiServerUrl}"
                selectedProfile != null ->
                    stringResource(R.string.chat_compatibility_overlay, activeConnection?.label ?: stringResource(R.string.chat_active_connection))
                activeConnection?.label?.isNotBlank() == true ->
                    stringResource(R.string.chat_connection_label, activeConnection?.label ?: "")
                else -> stringResource(R.string.chat_active_connection)
            }
            val threadsProactiveEnabled by connectionViewModel.proactiveEnabled.collectAsState()
            val threadsAuthState by connectionViewModel.authState.collectAsState()
            // Threads capability = "Let Hermes message me" on + relay paired.
            // Shows the drawer's Threads affordance even before the first Thread
            // arrives (the drawer also self-shows it when a source=phone session
            // is already present).
            val threadsCapabilityActive = threadsProactiveEnabled &&
                threadsAuthState is com.hermesandroid.relay.auth.AuthState.Paired
            val hiddenSources by connectionViewModel.hiddenSources.collectAsState()

            SessionDrawerContent(
                sessions = sessions,
                currentSessionId = currentSessionId,
                scopeTitle = drawerTitle,
                scopeSubtitle = drawerSubtitle,
                isLoading = isLoadingSessions,
                isOpen = drawerState.isOpen,
                autoTitlesSupported = serverAutoTitles,
                onRefresh = { chatViewModel.refreshSessions() },
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
                },
                threadsCapabilityActive = threadsCapabilityActive,
                onNewThread = { name ->
                    chatViewModel.startNewThread(name)
                    scope.launch { drawerState.close() }
                },
                hiddenSources = hiddenSources,
                onToggleSourceHidden = { source, hidden ->
                    connectionViewModel.setSourceHidden(source, hidden)
                },
            )
        }
    ) {
        val isDarkTheme = LocalBrand.current.isDark

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
                        Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.cd_sessions))
                    }
                },
                title = {
                    val headerApiReachable = apiReachable || isStreaming
                    val isConnecting = isChatConnecting ||
                        (!headerApiReachable && chatMode != ChatMode.DISCONNECTED)
                    // Once we've been connected this session, a later drop reads as
                    // "Reconnecting…" (we had it, we're getting it back) rather than
                    // a first-time "Connecting…". Honest wording for the WhatsApp-
                    // style subtitle status.
                    var everConnected by remember { mutableStateOf(false) }
                    if (headerApiReachable) everConnected = true
                    val statusText = when {
                        headerApiReachable -> if (isStreaming) {
                            stringResource(R.string.chat_streaming)
                        } else {
                            stringResource(R.string.chat_connected_label)
                        }
                        isConnecting -> if (everConnected) {
                            stringResource(R.string.chat_reconnecting_dots)
                        } else {
                            stringResource(R.string.chat_connecting_dots)
                        }
                        else -> stringResource(R.string.chat_disconnected_label)
                    }
                    val statusColor = when {
                        headerApiReachable -> Color(0xFF4CAF50)
                        isConnecting -> Color(0xFFFFA726)
                        else -> MaterialTheme.colorScheme.error
                    }

                    // Single-line subtitle: when we have a model name we show
                    // `model · personality` so the user sees both dimensions
                    // at once. Before the server config lands (or while
                    // disconnected) we fall back to the connection status.
                    //
                    // Model priority mirrors the input chip (currentModelForInput)
                    // so header, chip, and footer agree on ONE model: the SESSION's
                    // live model wins — the in-chat pick, then the gateway
                    // session.info model — so a mid-session switch shows here
                    // instead of a stale profile/global default. Profile model and
                    // /api/config's serverModelName are the fallbacks.
                    val modelName = AgentDisplay.displayModelName(selectedModelOverride)
                        ?: AgentDisplay.displayModelName(gatewayCurrentModel)
                        ?: AgentDisplay.displayModelName(effectiveProfile?.model)
                        ?: AgentDisplay.displayModelName(serverModelName)
                    // Subtext: a NON-default personality shown BEFORE the model
                    // (e.g. "Catgirl \u00B7 gpt-5.5"). A CLEARED overlay (default /
                    // none / neutral / blank) \u2014 or one that just matches the
                    // server default \u2014 contributes NOTHING; the active identity
                    // already lives in the agent name above, so the subtitle is
                    // just the model. Using isClearedPersonality (not a bare
                    // `!= "default"`) is what keeps "none"/"neutral" from leaking
                    // through as a literal "None" token.
                    val nonDefaultPersonality = selectedPersonality
                        .takeIf {
                            !AgentDisplay.isClearedPersonality(it) &&
                                !it.equals(defaultPersonality, ignoreCase = true)
                        }
                        ?.replaceFirstChar { it.uppercase() }
                    // When neither a real personality nor a model name is known
                    // yet (server config still loading), fall back to the plain
                    // connection status \u2014 never the literal "None"/"Default"
                    // personality label.
                    val subtitleText = when {
                        !headerApiReachable -> statusText
                        else -> listOfNotNull(
                            nonDefaultPersonality,
                            modelName?.takeIf { it.isNotBlank() },
                        ).joinToString(" \u00B7 ").ifBlank { statusText }
                    }
                    val subtitleColor = if (headerApiReachable) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        statusColor
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.clickable { showAgentInfo = true }
                    ) {
                        // Avatar — a plain 40dp circle whose letter swaps to the
                        // active agent (profile or personality). No overlay ring:
                        // the letter itself is the indicator.
                        Box(modifier = Modifier.size(40.dp)) {
                            Surface(
                                modifier = Modifier.size(40.dp),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                if (isChatConnecting) {
                                    ChatConnectingAvatarGlyph()
                                } else {
                                    val agentIconPath = LocalAgentIconPath.current
                                    if (!agentIconPath.isNullOrBlank()) {
                                        AsyncImage(
                                            model = File(agentIconPath),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    } else {
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
                            }
                            ConnectionStatusBadge(
                                isConnected = headerApiReachable,
                                isConnecting = isConnecting,
                                modifier = Modifier
                                    .size(10.dp)
                                    .align(Alignment.BottomEnd),
                                size = 10.dp
                            )
                        }

                        // Name + single-line subtitle.
                        Column(
                            modifier = Modifier.animateContentSize(
                                animationSpec = tween(durationMillis = 220),
                            ),
                            verticalArrangement = if (isChatConnecting) {
                                Arrangement.spacedBy(6.dp)
                            } else {
                                Arrangement.Top
                            },
                        ) {
                            AnimatedContent(
                                targetState = isChatConnecting,
                                transitionSpec = {
                                    (
                                        fadeIn(tween(180)) +
                                            slideInVertically(tween(220)) { it / 6 }
                                        ) togetherWith (
                                        fadeOut(tween(140)) +
                                            slideOutVertically(tween(180)) { -it / 8 }
                                        )
                                },
                                label = "chatHeaderIdentityTransition",
                            ) { connecting ->
                                if (connecting) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        ChatSkeletonLine(
                                            modifier = Modifier.width(112.dp),
                                            height = 15.dp,
                                        )
                                        ChatSkeletonLine(
                                            modifier = Modifier.width(156.dp),
                                            height = 11.dp,
                                        )
                                    }
                                } else {
                                    Column {
                                        Text(
                                            text = if (agentDisplayName.isNotBlank()) agentDisplayName else stringResource(R.string.chat_agent_default),
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                        // Context % lives in the per-session
                                        // ContextMeterBar, and the approval-bypass
                                        // marker now rides a compact ⚡ icon in the
                                        // app bar actions (full detail in the agent
                                        // sheet) instead of being appended here —
                                        // so the subtitle stays a clean single line
                                        // (`personality · model`) and no longer gets
                                        // squeezed out by the trailing action icons.
                                        // Fade the subtitle whenever it changes
                                        // — most importantly the honest
                                        // "Connected" → confirmed-model reveal
                                        // once /api/config lands (the model
                                        // arrives later than the identity, and
                                        // used to pop in). AnimatedContent doesn't
                                        // animate its initial state, so this only
                                        // smooths real changes, not first paint.
                                        AnimatedContent(
                                            targetState = subtitleText,
                                            transitionSpec = { loadedContentTransform() },
                                            label = "chatHeaderSubtitle",
                                        ) { line ->
                                            Text(
                                                text = line,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = subtitleColor,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                actions = {
                    // Approval-bypass marker, demoted from the subtitle to a
                    // single amber ⚡ icon: present only when approvals are
                    // effectively off, tapping into the agent sheet where the
                    // full explanation (global mode / --yolo / per-session)
                    // lives. Keeps the risk visible without eating subtitle
                    // width on every turn.
                    if (yoloEnabled == true) {
                        RelayChromeIconButton(
                            icon = Icons.Filled.Bolt,
                            contentDescription = stringResource(R.string.cd_approvals_off),
                            onClick = { showAgentInfo = true },
                            tint = RelayRefresh.Amber,
                            borderColor = RelayRefresh.Amber.copy(alpha = 0.5f),
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                    // (The ADR-24 LAN/Tailscale/Public endpoint-role chip that
                    // used to live here was redundant with the global footer
                    // status strip, which already renders "<status> / <route>"
                    // from the same activeEndpoint.displayLabel() — and shows it
                    // on every screen, not just chat. The footer strip is now
                    // tappable → Connections, so the affordance moved with the
                    // info. Dropping it here declutters the actions row and frees
                    // width for the title subtitle.)
                    RelayChromeIconButton(
                        icon = Icons.Filled.Code,
                        contentDescription = stringResource(R.string.cd_terminal),
                        onClick = onNavigateToTerminal,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    RelayChromeIconButton(
                        icon = Icons.Filled.Tune,
                        contentDescription = stringResource(R.string.cd_settings),
                        onClick = onNavigateToSettings,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    // Share is the least-used trailing action (and only valid
                    // once there's a conversation), so it folds into a ⋮
                    // overflow instead of competing for width with Terminal +
                    // Settings — which is what was squeezing the title subtitle.
                    // The overflow only appears when there's something to share.
                    if (messages.isNotEmpty()) {
                        var showOverflowMenu by remember { mutableStateOf(false) }
                        Box {
                            RelayChromeIconButton(
                                icon = Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.chat_more_actions_a11y),
                                onClick = { showOverflowMenu = true },
                                modifier = Modifier.padding(end = 4.dp),
                            )
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_share_conversation)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.Share,
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        shareConversation(context, messages)
                                    },
                                )
                            }
                        }
                    }
                    // Clean text-flow mode has no top-bar toggle — it's a quiet
                    // gesture: long-press the conversation background to enter.
                    // Exit is the in-mode dismiss control or system back (not
                    // any-tap, since the mode carries its own composer). A
                    // first-run hint teaches the entry.
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = RelayRefresh.Background.copy(alpha = 0.96f)
                )
            )
            // Per-session context-window gauge at the seam between the app bar
            // and the mode strip — slim bar + `NN% · used/max` token readout,
            // color-graded by fullness. Composes to nothing until the server
            // reports a context_max for the session.
            ContextMeterBar(
                usedFraction = contextUsage,
                usedTokens = contextWindow?.usedTokens,
                maxTokens = contextWindow?.maxTokens,
                onClick = { showContextSheet = true },
            )
            if (showContextSheet) {
                // Live audit of the exact extra context the agent will be
                // injected with on the next turn (transparency / auditability).
                InjectedContextSheet(
                    context = remember(showContextSheet) {
                        chatViewModel.previewInjectedContext()
                    },
                    onDismiss = { showContextSheet = false },
                )
            }
            // Chat is the home: the Chat/Manage/Bridge mode strip was removed here
            // (it spent a chrome band on the most-used screen). Manage and Bridge
            // are reached from Settings (Settings → Hermes management / Bridge);
            // Terminal + Settings remain quick icons in the top app bar above.

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
                        Text(stringResource(R.string.chat_retry))
                    }
                    TextButton(onClick = { chatViewModel.clearError() }) {
                        Text(stringResource(R.string.chat_dismiss))
                    }
                }
            }

            // Loading history indicator — only when there's nothing already on
            // screen. During a profile/session switch the previous transcript is
            // held visible while the new history loads (see
            // ChatViewModel.switchProfileContext), so a spinner over real
            // content would read as noise; the list cross-fades instead.
            if (isLoadingHistory && !isChatConnecting && messages.isEmpty()) {
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
                        text = stringResource(R.string.chat_loading_messages),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Clean text-flow mode is rendered as a full-screen overlay (a
            // sibling of this Column, below) so it can own its own minimal
            // composer and explicit-dismiss control. The Column always renders
            // the normal chat underneath; the overlay covers it when active.

            // Message list or empty state
            if (messages.isEmpty() && !isStreaming && (!isLoadingHistory || isChatConnecting)) {
                AnimatedContent(
                    targetState = chatConnectState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    transitionSpec = {
                        (
                            fadeIn(tween(260)) +
                                slideInVertically(tween(280)) { it / 10 }
                            ) togetherWith (
                            fadeOut(tween(180)) +
                                slideOutVertically(tween(220)) { -it / 12 }
                            )
                    },
                    label = "chatEmptyStatePhaseTransition",
                ) { targetConnectState ->
                    if (targetConnectState == ChatConnectState.Connecting) {
                    ChatColdStartLoadingState(
                        animationEnabled = animationEnabled,
                        streamingIntensity = streamingIntensity,
                        toolCallBurst = toolCallBurst,
                        connectionLabel = activeConnection
                            ?.label
                            ?.takeIf { it.isNotBlank() }
                            ?: activeConnection
                                ?.apiServerUrl
                                ?.let(Connection::extractDefaultLabel),
                        chatMode = chatMode,
                        apiReachable = apiReachable,
                        chatReady = chatReady,
                        isLoadingHistory = isLoadingHistory,
                        isLoadingSessions = isLoadingSessions,
                        onNavigateToConnections = onNavigateToConnections,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    val suggestions = listOf(
                        stringResource(R.string.chat_prompt_what_can_you_do),
                        stringResource(R.string.chat_prompt_help_me_code),
                        stringResource(R.string.chat_prompt_explain),
                    )

                    Box(
                        modifier = Modifier.fillMaxSize(),
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
                                    LocalAgentAvatar.current.Render(
                                        state = AvatarRenderState(
                                            state = if (error != null) SphereState.Error else SphereState.Idle,
                                            intensity = streamingIntensity,
                                            toolCallBurst = toolCallBurst,
                                        ),
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            Text(
                                text = when (targetConnectState) {
                                    // Name the agent when a profile is picked,
                                    // so a profile switch is legible in the
                                    // thread itself (not just the header) -
                                    // the desktop's intro.
                                    ChatConnectState.Ready ->
                                        if (selectedProfile != null) {
                                            stringResource(R.string.chat_prompt_chat_with, agentDisplayName)
                                        } else {
                                            stringResource(R.string.chat_start_conversation)
                                        }
                                    ChatConnectState.Connecting -> stringResource(R.string.chat_connect_to_hermes_dots)
                                    ChatConnectState.NeedsConnection -> stringResource(R.string.chat_needs_connection)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            // The selected agent's role/description - the
                            // rest of the fresh-session intro, shown only
                            // when a profile is active.
                            val profileBlurb = effectiveProfile?.description
                                ?.trim()
                                ?.takeIf { it.isNotBlank() && !it.equals(agentDisplayName, ignoreCase = true) }
                            if (targetConnectState == ChatConnectState.Ready && profileBlurb != null) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = profileBlurb,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }

                            when (targetConnectState) {
                                // Hydration finished and there is genuinely
                                // nothing configured - the only state that
                                // shows the CTA.
                                ChatConnectState.NeedsConnection -> {
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
                                                text = stringResource(R.string.chat_empty_state_body),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Button(
                                                onClick = onNavigateToConnect,
                                                modifier = Modifier.fillMaxWidth(),
                                            ) {
                                                Text(stringResource(R.string.chat_connect_hermes))
                                            }
                                            if (onTryDemo != null) {
                                                TextButton(
                                                    onClick = onTryDemo,
                                                    modifier = Modifier.fillMaxWidth(),
                                                ) {
                                                    Text(stringResource(R.string.chat_try_demo))
                                                }
                                            }
                                        }
                                    }
                                }

                                ChatConnectState.Connecting -> Unit

                                ChatConnectState.Ready -> {
                                    Spacer(modifier = Modifier.height(20.dp))

                                    // Suggestion chips
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        suggestions.forEach { suggestion ->
                                            AssistChip(
                                                onClick = {
                                                    // Send on tap — a casual user expects a
                                                    // suggestion to start the conversation, not
                                                    // prefill the composer for a second tap.
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    chatViewModel.sendMessage(suggestion)
                                                    inputText = ""
                                                    finishSuccessfulSend()
                                                },
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
                            }

                            Spacer(modifier = Modifier.weight(0.15f))
                        }
                    }
                }
                }
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // Quiet entry to clean mode: long-press the
                        // conversation background. Bubbles keep their own
                        // long-press (copy) — they consume the gesture first,
                        // so only presses on empty space land here. Enters
                        // regardless of animationEnabled — clean mode degrades
                        // to static text when motion is suppressed, so the
                        // conversation is never gated on animation.
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    ambientMode = true
                                },
                            )
                        }
                ) {
                    // Ambient avatar behind messages
                    if (animationEnabled && animationBehindChat && !ambientMode) {
                        LocalAgentAvatar.current.Render(
                            state = AvatarRenderState(
                                state = sphereState,
                                intensity = streamingIntensity,
                                toolCallBurst = toolCallBurst,
                            ),
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(0.65f),
                        )
                    }

                    // Let assistant markdown images that point at server-local
                    // paths (`![](/abs/path)`) render through the relay's
                    // /media/by-path route when a relay session is paired,
                    // instead of degrading to the "image is on the server"
                    // notice. Null when no relay (standard no-plugin) → notice.
                    val relayServerImageResolver = remember(chatViewModel) {
                        RelayServerImageResolver { path -> chatViewModel.resolveServerImage(path) }
                    }
                    val thinkingIndicatorConfig = remember(
                        thinkingIndicatorStyle,
                        thinkingMatrixPattern,
                        thinkingMatrixColor,
                        animationEnabled,
                    ) {
                        ThinkingIndicatorConfig(
                            style = if (thinkingIndicatorStyle == "matrix") {
                                ThinkingIndicatorStyle.Matrix
                            } else {
                                ThinkingIndicatorStyle.Dots
                            },
                            pattern = ThinkingMatrixPattern.fromKey(thinkingMatrixPattern),
                            color = ThinkingMatrixColor.fromKey(thinkingMatrixColor),
                            animated = animationEnabled,
                        )
                    }
                    CompositionLocalProvider(
                        LocalRelayServerImageResolver provides relayServerImageResolver,
                        LocalThinkingIndicator provides thinkingIndicatorConfig,
                    ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        item { Spacer(modifier = Modifier.height(8.dp).animateItem()) }

                        // `id` can legitimately change once after a Gateway turn:
                        // the history reconcile adopts the persisted server id.
                        // Keep Compose identity stable across that data update so
                        // LazyColumn retains the visible row and its scroll anchor.
                        items(messages.size, key = { messages[it].uiKey }) { index ->
                            val message = messages[index]
                            val retainLiveLayout =
                                index == messages.lastIndex &&
                                    message.uiKey == retainedLiveTailUiKey
                            val processNotification = message.hermesProcessNotificationOrNull()

                            // Skip empty bubbles (content stripped by annotation parser, no tool calls,
                            // no attachments). Attachments keep the bubble alive for inbound media;
                            // cards keep ask-card-only messages alive the same way.
                            if (message.content.isBlank() &&
                                message.toolCalls.isEmpty() &&
                                message.attachments.isEmpty() &&
                                message.cards.isEmpty() &&
                                message.backgroundTask == null &&
                                !message.isStreaming
                            ) return@items

                            // Break a same-author run on a role change OR a >5min
                            // gap to the neighbor, so a resumed conversation gets a
                            // fresh agent-name label + its own timestamp instead of
                            // silently merging into the previous burst.
                            val isFirstInGroup = index == 0 ||
                                messages[index - 1].role != message.role ||
                                message.timestamp - messages[index - 1].timestamp > GROUP_GAP_MS
                            val isLastInGroup = index == messages.size - 1 ||
                                messages[index + 1].role != message.role ||
                                messages[index + 1].timestamp - message.timestamp > GROUP_GAP_MS

                            // Date separator
                            if (index == 0 || !isSameDay(messages[index - 1].timestamp, message.timestamp)) {
                                DateSeparator(timestamp = message.timestamp)
                            }

                            val hasBackgroundTask = message.backgroundTask != null
                            val shouldRenderBubble =
                                !hasBackgroundTask ||
                                    message.content.isNotBlank() ||
                                    message.thinkingContent.isNotBlank() ||
                                    message.attachments.isNotEmpty() ||
                                    message.cards.isNotEmpty()

                            message.backgroundTask?.let { task ->
                                val taskModifier = Modifier.padding(
                                    top = if (isFirstInGroup) 6.dp else 2.dp,
                                    bottom = if (shouldRenderBubble) 3.dp else 0.dp,
                                )
                                BackgroundTaskCard(
                                    task = task,
                                    toolCalls = message.toolCalls,
                                    showTimeline = toolDisplay != "off",
                                    modifier = taskModifier,
                                )
                            }

                            if (processNotification != null) {
                                val notificationModifier = Modifier.padding(
                                    top = if (isFirstInGroup) 6.dp else 2.dp,
                                )
                                SyntheticProcessNotificationNotice(
                                    notification = processNotification,
                                    modifier = notificationModifier,
                                )
                            } else if (shouldRenderBubble) {
                                val bubbleModifier = Modifier.padding(
                                    top = if (hasBackgroundTask) 1.dp
                                        else if (isFirstInGroup) 6.dp
                                        else 1.dp,
                                )
                                MessageBubble(
                                    message = message,
                                    modifier = bubbleModifier,
                                    maxBubbleWidth = maxBubbleWidth,
                                    showThinking = showThinking,
                                    isFirstInGroup = isFirstInGroup,
                                    isLastInGroup = isLastInGroup,
                                    retainStreamingLayout = retainLiveLayout,
                                    recoveringAnswer = recoveringAnswer,
                                    onAttachmentRetry = { msgId, idx ->
                                        chatViewModel.manualFetchAttachment(msgId, idx)
                                    },
                                    onAttachmentManualFetch = { msgId, idx ->
                                        chatViewModel.manualFetchAttachment(msgId, idx)
                                    },
                                    onCardAction = { msgId, cardKey, action ->
                                        // OPEN_URL is resolved at the UI layer
                                        // because launching ACTION_VIEW needs a
                                        // Context. Record the dispatch first so
                                        // the card collapses even if launch fails.
                                        if (action.mode == com.hermesandroid.relay.data.HermesCardAction.Modes.OPEN_URL) {
                                            chatViewModel.dispatchCardAction(msgId, cardKey, action)
                                            com.hermesandroid.relay.ui.components.handleCardActionExternally(
                                                context,
                                                action,
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
                                        scope.launch {
                                            clipboard.setClipEntry(
                                                ClipEntry(
                                                    ClipData.newPlainText(
                                                        hermesMessageLabel,
                                                        text,
                                                    ),
                                                )
                                            )
                                            snackbarHostState.showSnackbar(
                                                message = copiedToClipboardMsg,
                                                duration = SnackbarDuration.Short,
                                            )
                                        }
                                    },
                                )
                            }

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

                            if (toolDisplay != "off" && !hasBackgroundTask) {
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
                    } // CompositionLocalProvider(LocalRelayServerImageResolver)

                    ChatScrollTicker(
                        listState = listState,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(top = 10.dp, end = 2.dp, bottom = 78.dp)
                            .zIndex(6f),
                    )

                    // Scroll-to-bottom FAB
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showScrollToBottom,
                        enter = fadeIn() + slideInVertically { it },
                        exit = fadeOut() + slideOutVertically { it },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                            .zIndex(8f)
                    ) {
                        SmallFloatingActionButton(
                            modifier = Modifier
                                .size(48.dp)
                                .semantics {
                                    contentDescription = if (unreadMessageCount > 0) {
                                        "Scroll to bottom, $unreadMessageCount unread " +
                                            if (unreadMessageCount == 1) "message" else "messages"
                                    } else {
                                        "Scroll to bottom"
                                    }
                                },
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                scope.launch {
                                    // Match the auto-scroll fix: aim at the
                                    // BOTTOM of the conversation, wait for
                                    // LazyColumn layout, and retry through
                                    // late markdown/code-block measurement.
                                    scrollConversationToBottom(animated = true)
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            BadgedBox(
                                badge = {
                                    if (unreadMessageCount > 0) {
                                        Badge(
                                            modifier = Modifier.clearAndSetSemantics { },
                                        ) {
                                            Text(
                                                if (unreadMessageCount > 99) "99+"
                                                else unreadMessageCount.toString(),
                                            )
                                        }
                                    }
                                },
                            ) {
                                Icon(
                                    Icons.Filled.KeyboardArrowDown,
                                    contentDescription = null,
                                )
                            }
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

            if (isGatewayTransport) {
                GatewayBackgroundProcessStrip(
                    processes = backgroundProcesses,
                    loading = backgroundProcessesLoading,
                    onClick = { showBackgroundProcesses = true },
                )
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
            // Recent-prompt recall — a soft keyboard has no up-arrow, so surface
            // your last prompts as tappable chips while the composer is empty.
            // Tapping prefills (not auto-sends) so you can tweak before resending;
            // the row vanishes the moment you type or a queue/fresh-chat shows.
            AnimatedVisibility(
                visible = recentPromptsEnabled && messages.isNotEmpty() && inputText.isBlank() &&
                    queuedMessages.isEmpty() && recentPrompts.isNotEmpty(),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 2.dp),
                ) {
                    recentPrompts.take(6).forEach { prompt ->
                        AssistChip(
                            onClick = { inputText = prompt },
                            label = {
                                Text(
                                    text = if (prompt.length > 40) prompt.take(40) + "…" else prompt,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }

            AnimatedVisibility(visible = queuedMessages.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.chat_queue_count,
                                queuedMessages.size,
                                queuedMessages.size,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        TextButton(
                            onClick = { chatViewModel.clearQueue() },
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(
                                stringResource(R.string.chat_clear),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    // Per-item: tap the text to pull it back into the composer
                    // for editing; ✕ to drop just that one. (Reorder omitted —
                    // low value vs. drag-handle complexity on a transient queue.)
                    queuedMessages.forEachIndexed { index, msg ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        chatViewModel.takeQueuedForEdit(index)?.let { t ->
                                            inputText = if (inputText.isBlank()) t else "$inputText $t"
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                            )
                            TextButton(
                                onClick = { chatViewModel.removeQueuedAt(index) },
                                modifier = Modifier.height(28.dp),
                            ) {
                                Text("✕", style = MaterialTheme.typography.labelSmall)
                            }
                        }
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
                                        text = attachment.fileName ?: stringResource(R.string.chat_attachment_file_fallback),
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
                                        contentDescription = stringResource(R.string.cd_remove),
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
                        text = stringResource(R.string.chat_editing_notice),
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
                            contentDescription = stringResource(R.string.cd_cancel_editing),
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
                editingMessage != null -> stringResource(R.string.chat_placeholder_edit)
                isStreaming && steerableTurn -> stringResource(R.string.chat_placeholder_steer)
                isStreaming -> stringResource(R.string.chat_placeholder_queue)
                else -> stringResource(R.string.chat_placeholder_message)
            }
            val editBusyMessage = stringResource(R.string.chat_edit_busy_snackbar)
            val stoppedMessage = stringResource(R.string.chat_stopped_snackbar)
            val attachmentPlaceholder = stringResource(R.string.chat_attachment_placeholder)
            val sseModelOptions = remember(availableModels, agentProfiles, selectedModelOverride) {
                (availableModels.mapNotNull(AgentDisplay::displayModelName) +
                    agentProfiles.mapNotNull { AgentDisplay.displayModelName(it.model) } +
                    listOfNotNull(AgentDisplay.displayModelName(selectedModelOverride)))
                    .distinct()
            }
            val oneTurnModelForInput = pendingOneTurnModelOverride ?: activeOneTurnModelOverride
            val currentModelForInput = AgentDisplay.displayModelName(oneTurnModelForInput?.model)
                ?.let { "$it · once" }
                ?: AgentDisplay.displayModelName(selectedModelOverride)
                ?: AgentDisplay.displayModelName(gatewayCurrentModel)
                ?: AgentDisplay.displayModelName(effectiveProfile?.model)
                ?: AgentDisplay.displayModelName(serverModelName)
            val fallbackModelDetail = AgentDisplay.displayModelName(gatewayCurrentModel)
                ?: AgentDisplay.displayModelName(effectiveProfile?.model)
                ?: AgentDisplay.displayModelName(serverModelName)
            // The TRUE server/global default for the "Server default" row caption.
            // Deliberately EXCLUDES gatewayCurrentModel: selectModel() force-sets
            // that to the active OVERRIDE, so using it here mislabeled the user's
            // override as the server default. serverModelName comes from /api/config
            // (never touched by overrides) — the same source the agent drawer uses.
            val serverDefaultModelDetail = AgentDisplay.displayModelName(serverModelName)
                ?: AgentDisplay.displayModelName(effectiveProfile?.model)
            val hasModelChoices = modelProviders.any { it.models.isNotEmpty() } || sseModelOptions.isNotEmpty()
            val serverDefaultLabel = stringResource(R.string.chat_server_default_sessions)
            val notOnPlanLabel = stringResource(R.string.chat_not_on_plan)
            val needsSetupLabel = stringResource(R.string.chat_needs_setup)
            val modelDefaultLabel = stringResource(R.string.chat_model_label)
            val modelPickerOptions = remember(
                modelProviders,
                sseModelOptions,
                selectedModelOverride,
                gatewayCurrentModel,
                fallbackModelDetail,
                serverDefaultModelDetail,
                hasModelChoices,
            ) {
                if (!hasModelChoices && fallbackModelDetail.isNullOrBlank()) {
                    emptyList()
                } else {
                    buildList {
                        add(
                            ChatInputPickerOption(
                                label = serverDefaultLabel,
                                value = null,
                                secondary = serverDefaultModelDetail?.let { compactModelChipLabel(it, modelDefaultLabel) },
                                selected = selectedModelOverride == null,
                            ),
                        )
                        if (modelProviders.any { it.models.isNotEmpty() }) {
                            // Current provider first — matches the desktop picker,
                            // which defaults the selection to is_current, so the
                            // user's authenticated/current provider leads.
                            modelProviders.sortedByDescending { it.isCurrent }.forEach { provider ->
                                provider.models.distinct().forEach { model ->
                                    // Respect upstream's per-provider availability:
                                    // unavailable_models are paid models the account
                                    // can't pick (free-tier / no credits) — disable
                                    // them so a switch can't 400 / credits-fail.
                                    val unavailable = model in provider.unavailableModels
                                    add(
                                        ChatInputPickerOption(
                                            label = model,
                                            value = model,
                                            provider = provider.slug,
                                            group = provider.name,
                                            secondary = when {
                                                unavailable -> notOnPlanLabel
                                                !provider.authenticated -> provider.warning ?: needsSetupLabel
                                                else -> null
                                            },
                                            selected = selectedModelOverride == model,
                                            enabled = !unavailable,
                                        ),
                                    )
                                }
                            }
                        } else {
                            sseModelOptions.forEach { model ->
                                add(
                                    ChatInputPickerOption(
                                        label = model,
                                        value = model,
                                        selected = selectedModelOverride == model,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            val modelControl = modelPickerOptions.takeIf { it.isNotEmpty() }?.let {
                ChatInputPickerControl(
                    value = compactModelChipLabel(currentModelForInput, modelDefaultLabel),
                    contentDescription = stringResource(R.string.cd_select_model),
                    options = it,
                    enabled = chatReady && !isStreaming && it.size > 1,
                )
            }
            val normalizedEffort = normalizeReasoningEffortForInput(selectedReasoningEffort)
            val effortLabels = mapOf(
                "none" to stringResource(R.string.chat_reasoning_none),
                "minimal" to stringResource(R.string.chat_reasoning_minimal),
                "low" to stringResource(R.string.chat_reasoning_low),
                "medium" to stringResource(R.string.chat_reasoning_medium),
                "high" to stringResource(R.string.chat_reasoning_high),
                "xhigh" to stringResource(R.string.chat_reasoning_high),
            )
            val effortPickerOptions = remember(normalizedEffort) {
                CHAT_INPUT_REASONING_EFFORTS.map { effort ->
                    ChatInputPickerOption(
                        label = reasoningEffortChipLabel(effort, effortLabels),
                        value = effort,
                        selected = effort == normalizedEffort,
                    )
                }
            }
            // Show the effort chip as soon as the gateway IS the transport or is
            // still being probed (Unknown) — so it appears alongside the model
            // pill instead of popping in seconds later when the dashboard
            // /api/status verdict flips to Ready (see the cold-open-recovery note
            // above). Interactive only once the gateway is confirmed Ready
            // (config.set reasoning needs a live gateway), so during the probe it
            // shows the current effort but disabled. Hidden only when the gateway
            // is definitively unreachable (SSE-only) — the agent sheet carries the
            // disabled-with-reason version there.
            val effortControl = if (chatGatewayAvailability != GatewayAvailability.Unreachable) {
                ChatInputPickerControl(
                    value = reasoningEffortChipLabel(normalizedEffort, effortLabels),
                    contentDescription = stringResource(R.string.chat_select_reasoning_effort),
                    options = effortPickerOptions,
                    enabled = isGatewayTransport && chatReady && !isStreaming,
                )
            } else {
                null
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
                            finishSuccessfulSend()
                        } else {
                            val editBusyMsg = editBusyMessage
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = editBusyMsg,
                                    duration = SnackbarDuration.Short,
                                )
                            }
                        }
                    } else {
                        chatViewModel.sendMessage(inputText.ifBlank { attachmentPlaceholder })
                        inputText = ""
                        finishSuccessfulSend()
                    }
                },
                onVoice = {
                    dispatchChatVoiceAction(
                        isDemoMode = isDemoMode,
                        voiceReady = voiceReady,
                        onDemoNotice = {
                            Toast.makeText(
                                context,
                                "Voice is unavailable in the offline demo — connect to Hermes to use it",
                                Toast.LENGTH_LONG,
                            ).show()
                        },
                        onStartVoice = requestVoiceMode,
                        onSetupNotice = {
                            Toast.makeText(
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
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    )
                },
                onStop = {
                    chatViewModel.cancelStream()
                    // Firm haptic (LongPress — TextHandleMove was near-
                    // imperceptible) plus a "Stopped" badge stamped on the turn
                    // (see ChatViewModel.cancelStream) so the cancel is
                    // unmistakable, not just a transient toast.
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val stoppedMsg = stoppedMessage
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            message = stoppedMsg,
                            duration = SnackbarDuration.Short,
                        )
                    }
                },
                onAttachPhotos = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onAttachFiles = { filePickerLauncher.launch(arrayOf("*/*")) },
                onAttachCamera = requestCameraCapture,
                onPasteImage = pasteImageFromClipboard,
                onLongPressAttach = { showCommandPalette = true },
                charLimit = charLimit,
                caption = turnStatus ?: inputCaption,
                voiceReady = voiceReady,
                showVoiceHint = !voiceHintSeen,
                onVoiceHintShown = { connectionViewModel.setVoiceHintSeen(true) },
                isDarkTheme = isDarkTheme,
                modelControl = modelControl,
                onModelOptionSelected = { option ->
                    chatViewModel.selectModel(option.value, option.provider)
                },
                effortControl = effortControl,
                onEffortOptionSelected = { option ->
                    option.value?.let { chatViewModel.selectReasoningEffort(it) }
                },
                enabled = chatReady,
                onModelPickerClick = { showModelSheet = true },
            )

            if (showModelSheet) {
                ModelPickerSheet(
                    options = modelPickerOptions,
                    refreshing = modelOptionsRefreshing,
                    onRefresh = { chatViewModel.refreshModelOptions(refresh = true) },
                    onSelect = { option ->
                        showModelSheet = false
                        chatViewModel.selectModel(option.value, option.provider)
                    },
                    onSelectOnce = { option ->
                        showModelSheet = false
                        option.value?.let { chatViewModel.selectModelOnce(it, option.provider) }
                    },
                    onDismiss = { showModelSheet = false },
                )
            }
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
                        text = stringResource(R.string.chat_mic_perm_needed),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = stringResource(R.string.chat_mic_perm_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { micPermissionDenied = false }) {
                            Text(stringResource(R.string.chat_dismiss))
                        }
                        TextButton(onClick = {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            )
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }) {
                            Text(stringResource(R.string.chat_open_settings))
                        }
                    }
                }
            }
        }

        // Clean-mode discoverability hint — a quiet, persistent pill teaching
        // the long-press entry. Shown ONLY on the empty / new-chat view; it
        // disappears the moment a conversation exists or clean mode is entered.
        AnimatedVisibility(
            visible = messages.isEmpty() && !ambientMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 96.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_clean_view_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // Clean text-flow mode — full-screen overlay covering the whole Box.
        // Owns its own minimal composer + explicit dismiss; exit is never
        // any-tap. Renders static text when motion is suppressed.
        AnimatedVisibility(
            visible = ambientMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            CleanChatMode(
                messages = messages,
                isStreaming = isStreaming,
                sphereState = sphereState,
                streamingIntensity = streamingIntensity,
                toolCallBurst = toolCallBurst,
                animationEnabled = animationEnabled,
                enabled = chatReady,
                onSend = { text ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    chatViewModel.sendMessage(text)
                },
                onExit = { ambientMode = false },
            )
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
                onBackgroundRunCancel = { voiceViewModel.cancelBackgroundRun() },
                onBackgroundRunTap = { voiceViewModel.respeakBackgroundResult() },
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
                // Gear button in the overlay's expanded controls. The overlay
                // exits voice mode before invoking this, so navigation lands
                // on Voice Settings with no overlay left on top.
                onOpenSettings = onNavigateToVoiceSettings,
                onCompactModeChange = { compact ->
                    voiceCompactMode = compact
                },
                // === v0.4.1 JIT permission-denied chip ===
                // Tap deep-links to Settings → Apps → Hermes-Relay →
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

    if (showBackgroundProcesses) {
        GatewayBackgroundProcessSheet(
            processes = backgroundProcesses,
            loading = backgroundProcessesLoading,
            stoppingProcessIds = stoppingProcessIds,
            onRefresh = chatViewModel::refreshBackgroundProcesses,
            onStop = chatViewModel::stopBackgroundProcess,
            onDismissProcess = chatViewModel::dismissBackgroundProcess,
            onDismiss = { showBackgroundProcesses = false },
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

@Composable
private fun ChatConnectingAvatarGlyph() {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        val transition = rememberInfiniteTransition(label = "chat-avatar-loading")
        val glyphAlpha by transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 760, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "chat-avatar-loading-alpha",
        )
        CircularProgressIndicator(
            modifier = Modifier
                .size(19.dp)
                .alpha(glyphAlpha),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun ChatSkeletonLine(
    modifier: Modifier = Modifier,
    height: Dp = 12.dp,
) {
    val transition = rememberInfiniteTransition(label = "chat-skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.42f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 980, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "chat-skeleton-alpha",
    )
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)),
    )
}

@Composable
private fun ChatColdStartLoadingState(
    animationEnabled: Boolean,
    streamingIntensity: Float,
    toolCallBurst: Float,
    connectionLabel: String?,
    chatMode: ChatMode,
    apiReachable: Boolean,
    chatReady: Boolean,
    isLoadingHistory: Boolean,
    isLoadingSessions: Boolean,
    onNavigateToConnections: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val commands = remember(
        connectionLabel,
        chatMode,
        apiReachable,
        chatReady,
        isLoadingHistory,
        isLoadingSessions,
    ) {
        buildChatLoadingCommands(
            connectionLabel = connectionLabel,
            chatMode = chatMode,
            apiReachable = apiReachable,
            chatReady = chatReady,
            isLoadingHistory = isLoadingHistory,
            isLoadingSessions = isLoadingSessions,
        )
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        if (animationEnabled) {
            LocalAgentAvatar.current.Render(
                state = AvatarRenderState(
                    state = SphereState.Thinking,
                    intensity = streamingIntensity.coerceAtLeast(0.18f),
                    toolCallBurst = toolCallBurst,
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.44f),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ChatSkeletonBubble(
                widthFraction = 0.78f,
                lineFractions = listOf(0.82f, 0.54f),
                alignEnd = false,
            )
            ChatSkeletonBubble(
                widthFraction = 0.62f,
                lineFractions = listOf(0.70f),
                alignEnd = true,
            )
            ChatSkeletonBubble(
                widthFraction = 0.84f,
                lineFractions = listOf(0.88f, 0.68f, 0.38f),
                alignEnd = false,
            )
        }

        ChatLoadingCommandPanel(
            commands = commands,
            onNavigateToConnections = onNavigateToConnections,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
        )
    }
}

private fun buildChatLoadingCommands(
    connectionLabel: String?,
    chatMode: ChatMode,
    apiReachable: Boolean,
    chatReady: Boolean,
    isLoadingHistory: Boolean,
    isLoadingSessions: Boolean,
): List<ChatLoadingCommand> {
    val hasConnection = !connectionLabel.isNullOrBlank()
    val chatModeDetail = when (chatMode) {
        ChatMode.ENHANCED_HERMES -> "sessions stream"
        ChatMode.PORTABLE -> "portable stream"
        ChatMode.DISCONNECTED -> "waiting"
    }
    return listOf(
        ChatLoadingCommand(
            state = if (hasConnection) ChatLoadingCommandState.Done else ChatLoadingCommandState.Active,
            command = "/state restore",
            detail = if (hasConnection) "active config loaded" else "loading config",
        ),
        ChatLoadingCommand(
            state = when {
                hasConnection -> ChatLoadingCommandState.Done
                else -> ChatLoadingCommandState.Pending
            },
            command = "/route resolve",
            detail = connectionLabel?.takeIf { it.isNotBlank() } ?: "selecting route",
        ),
        ChatLoadingCommand(
            state = when {
                apiReachable -> ChatLoadingCommandState.Done
                hasConnection -> ChatLoadingCommandState.Active
                else -> ChatLoadingCommandState.Pending
            },
            command = "/hermes ping",
            detail = if (apiReachable) "online" else "contacting server",
        ),
        ChatLoadingCommand(
            state = when {
                chatReady -> ChatLoadingCommandState.Done
                apiReachable || isLoadingHistory || isLoadingSessions -> ChatLoadingCommandState.Active
                else -> ChatLoadingCommandState.Pending
            },
            command = "/chat hydrate",
            detail = if (chatReady) "ready via $chatModeDetail" else "loading conversation",
        ),
    )
}

@Composable
private fun ChatLoadingCommandPanel(
    commands: List<ChatLoadingCommand>,
    onNavigateToConnections: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp)
                .animateContentSize(animationSpec = tween(durationMillis = 240)),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                commands.forEach { command ->
                    ChatLoadingCommandRow(command)
                }
            }
        }
        TextButton(onClick = onNavigateToConnections) {
            Text(stringResource(R.string.chat_manage_connections))
        }
    }
}

@Composable
private fun ChatLoadingCommandRow(command: ChatLoadingCommand) {
    val transition = rememberInfiniteTransition(label = "chat-loading-command")
    val spinnerFrame by transition.animateFloat(
        initialValue = 0f,
        targetValue = CHAT_LOADING_SPINNER_FRAMES.size.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = CHAT_LOADING_SPINNER_FRAMES.size * 120,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "chat-loading-command-spinner",
    )
    val dotsFrame by transition.animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "chat-loading-command-dots",
    )
    fun glyphFor(state: ChatLoadingCommandState): String = when (state) {
        ChatLoadingCommandState.Pending -> "."
        ChatLoadingCommandState.Active -> {
            val index = spinnerFrame.toInt()
                .coerceIn(0, CHAT_LOADING_SPINNER_FRAMES.lastIndex)
            CHAT_LOADING_SPINNER_FRAMES[index]
        }
        ChatLoadingCommandState.Done -> "ok"
        ChatLoadingCommandState.Failed -> "!!"
    }
    val dots = if (command.state == ChatLoadingCommandState.Active) {
        ".".repeat(dotsFrame.toInt().coerceIn(1, 3))
    } else {
        ""
    }
    val targetRowAlpha = when (command.state) {
        ChatLoadingCommandState.Pending -> 0.42f
        ChatLoadingCommandState.Active -> 0.95f
        else -> 0.82f
    }
    val rowAlpha by animateFloatAsState(
        targetValue = targetRowAlpha,
        animationSpec = tween(durationMillis = 220),
        label = "chat-loading-command-row-alpha",
    )
    val activeHighlightAlpha by animateFloatAsState(
        targetValue = if (command.state == ChatLoadingCommandState.Active) 0.10f else 0f,
        animationSpec = tween(durationMillis = 240),
        label = "chat-loading-command-highlight",
    )
    val glyphColor = when (command.state) {
        ChatLoadingCommandState.Done -> RelayRefresh.Green
        ChatLoadingCommandState.Failed -> RelayRefresh.Danger
        ChatLoadingCommandState.Active -> RelayRefresh.Amber
        ChatLoadingCommandState.Pending -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(RelayRefresh.Amber.copy(alpha = activeHighlightAlpha))
            .animateContentSize(animationSpec = tween(durationMillis = 220))
            .alpha(rowAlpha)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = command.state,
            transitionSpec = {
                (
                    fadeIn(tween(130)) +
                        slideInVertically(tween(160)) { it / 3 }
                    ) togetherWith (
                    fadeOut(tween(110)) +
                        slideOutVertically(tween(140)) { -it / 3 }
                    )
            },
            label = "chat-loading-command-glyph",
        ) { state ->
            Text(
                text = glyphFor(state),
                modifier = Modifier.width(18.dp),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = glyphColor,
                maxLines = 1,
            )
        }
        Text(
            text = command.command,
            modifier = Modifier.width(104.dp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        AnimatedContent(
            targetState = command.detail,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                fadeIn(tween(170)) togetherWith fadeOut(tween(120))
            },
            label = "chat-loading-command-detail",
        ) { detail ->
            Text(
                text = "$detail$dots",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ChatSkeletonBubble(
    widthFraction: Float,
    lineFractions: List<Float>,
    alignEnd: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(widthFraction),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                lineFractions.forEach { fraction ->
                    ChatSkeletonLine(
                        modifier = Modifier.fillMaxWidth(fraction),
                        height = 10.dp,
                    )
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes} B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}

/**
 * Read an attachment from a content [uri], enforce the [maxAttachmentMb] cap,
 * base64-encode the bytes, and hand a built [Attachment] to [onAttachment].
 * Shared by the Files / Photos / Camera launchers and clipboard paste so the
 * read → cap → encode → add pipeline lives in exactly one place. Best-effort:
 * surfaces a Toast and returns on any failure (unreadable stream, over-cap)
 * rather than throwing. [mimeOverride] lets clipboard paste supply the MIME
 * when the resolver can't (some providers don't resolve a type until read).
 */
private fun ingestAttachmentFromUri(
    context: android.content.Context,
    uri: Uri,
    maxAttachmentMb: Int,
    mimeOverride: String? = null,
    onAttachment: (Attachment) -> Unit,
) {
    try {
        val resolver = context.contentResolver
        val mimeType = mimeOverride ?: resolver.getType(uri) ?: "application/octet-stream"
        val fileName = resolveDisplayName(resolver, uri)
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return
        val maxSize = maxAttachmentMb * 1024 * 1024
        if (bytes.size > maxSize) {
            Toast.makeText(
                context,
                context.getString(R.string.chat_file_too_large, maxAttachmentMb),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        onAttachment(
            Attachment(
                contentType = mimeType,
                content = base64,
                fileName = fileName,
                fileSize = bytes.size.toLong(),
            )
        )
    } catch (e: Exception) {
        Toast.makeText(context, "Failed to read file", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Resolve a human-readable file name for [uri]. Prefers the provider's
 * `OpenableColumns.DISPLAY_NAME` — content-picker and photo-picker URIs rarely
 * carry a usable last path segment — and falls back to the last path segment,
 * then a generic "file".
 */
private fun resolveDisplayName(
    resolver: android.content.ContentResolver,
    uri: Uri,
): String {
    runCatching {
        resolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
}

/**
 * Pull the first image item off a clipboard [clip] into a pending attachment
 * (desktop `/paste` parity). Returns true when an image was found and ingested,
 * false otherwise (empty clipboard / no image item) so the caller can hint the
 * user. Images ride the clipboard as content URIs; raw bitmaps aren't carried
 * in ClipData, so URI items are the only source.
 */
private fun ingestClipboardImage(
    context: android.content.Context,
    clip: ClipData?,
    maxAttachmentMb: Int,
    onAttachment: (Attachment) -> Unit,
): Boolean {
    if (clip == null || clip.itemCount == 0) return false
    // Some providers expose the image MIME only on the ClipDescription, not via
    // resolver.getType — capture it once as a fallback for each item.
    val descriptionImageMime = clip.description?.let { description ->
        (0 until description.mimeTypeCount)
            .map { description.getMimeType(it) }
            .firstOrNull { it.startsWith("image/") }
    }
    for (i in 0 until clip.itemCount) {
        val uri = clip.getItemAt(i).uri ?: continue
        val resolvedMime = context.contentResolver.getType(uri)
        val mime = resolvedMime?.takeIf { it.startsWith("image/") } ?: descriptionImageMime
        if (mime != null) {
            ingestAttachmentFromUri(
                context,
                uri,
                maxAttachmentMb,
                mimeOverride = mime,
                onAttachment = onAttachment,
            )
            return true
        }
    }
    return false
}

/**
 * Create a FileProvider content URI backed by a fresh temp file in the shared
 * `hermes-media/` cache dir (already exported by `file_provider_paths.xml`) for
 * the camera to write its capture into. Reusing that path keeps the manifest
 * unchanged; the authority mirrors MediaCacheWriter / MediaSaver.
 */
private fun createCameraCaptureUri(context: android.content.Context): Uri {
    val dir = java.io.File(context.cacheDir, "hermes-media").apply { mkdirs() }
    val file = java.io.File.createTempFile("camera-", ".jpg", dir)
    return androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
}

private val CHAT_INPUT_REASONING_EFFORTS = listOf("none", "minimal", "low", "medium", "high", "xhigh")

private fun compactModelChipLabel(model: String?, defaultLabel: String): String {
    val raw = model?.trim().orEmpty()
    if (raw.isBlank()) return defaultLabel
    val label = raw.substringAfterLast('/').ifBlank { raw }
    return if (label.length <= 18) label else label.take(15).trimEnd() + "..."
}

private fun normalizeReasoningEffortForInput(value: String?): String {
    val normalized = value?.trim()?.lowercase().orEmpty()
    return normalized.takeIf { it in CHAT_INPUT_REASONING_EFFORTS } ?: "medium"
}

private fun reasoningEffortChipLabel(value: String, labels: Map<String, String>): String =
    labels[value] ?: labels["medium"] ?: value

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
        messageDate == today -> stringResource(R.string.chat_date_today)
        messageDate == today.minusDays(1) -> stringResource(R.string.chat_date_yesterday)
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
        putExtra(android.content.Intent.EXTRA_SUBJECT, context.getString(R.string.chat_share_subject))
    }
    context.startActivity(
        android.content.Intent.createChooser(intent, context.getString(R.string.chat_share_conversation)),
    )
}
