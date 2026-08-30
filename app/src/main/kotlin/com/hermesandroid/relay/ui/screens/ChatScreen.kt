@file:Suppress("LocalContextGetResourceValueCall")

package com.hermesandroid.relay.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.ime
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
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
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
import com.hermesandroid.relay.network.upstream.ApiModelOption
import com.hermesandroid.relay.network.upstream.ChatMode
import com.hermesandroid.relay.network.upstream.GatewayAvailability
import com.hermesandroid.relay.network.upstream.ReasoningEfforts
import com.hermesandroid.relay.network.relay.RelayVoiceClient
import com.hermesandroid.relay.network.relay.RealtimeVoiceConfig
import com.hermesandroid.relay.network.relay.VoiceOutputConfig
import com.hermesandroid.relay.ui.components.reasoningEffortLabel
import com.hermesandroid.relay.ui.components.resolveSessionModelUiState
import com.hermesandroid.relay.ui.components.rememberAccessibleMotionState
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.platform.LocalContext
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.Attachment
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.ChatComposerDraft
import com.hermesandroid.relay.data.ChatComposerDraftContext
import com.hermesandroid.relay.util.AttachmentTooLargeException
import com.hermesandroid.relay.util.readBase64Bounded
import com.hermesandroid.relay.data.ChatComposerDraftKey
import com.hermesandroid.relay.data.ChatQuoteReference
import com.hermesandroid.relay.data.BuildFlavor
import com.hermesandroid.relay.data.LARGE_PASTE_THRESHOLD_CHARS
import com.hermesandroid.relay.data.buildChatQuotedPrompt
import com.hermesandroid.relay.data.largePasteAttachment
import com.hermesandroid.relay.data.parseChatQuotedPrompt
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.HermesCardAction
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.data.PhysicalKeyboardEnterBehavior
import com.hermesandroid.relay.data.ProfilePresentationPolicy
import com.hermesandroid.relay.data.ProactiveInboxEntry
import com.hermesandroid.relay.data.SessionActivityState
import com.hermesandroid.relay.data.SupervisedAttachmentCategory
import com.hermesandroid.relay.data.SupervisedModePolicy
import com.hermesandroid.relay.data.SupervisedSessionAction
import com.hermesandroid.relay.data.allowsSessionAction
import com.hermesandroid.relay.data.VoicePresentationMode
import com.hermesandroid.relay.data.hermesProcessNotificationOrNull
import com.hermesandroid.relay.ui.components.AgentInfoSheet
import com.hermesandroid.relay.ui.components.BackgroundTaskCard
import com.hermesandroid.relay.ui.components.LocalRelayServerImageResolver
import com.hermesandroid.relay.ui.components.RelayServerImageResolver
import com.hermesandroid.relay.ui.components.ChatInputBar
import com.hermesandroid.relay.ui.components.ChatGitContextButton
import com.hermesandroid.relay.ui.components.ChatGitWorkspaceRail
import com.hermesandroid.relay.ui.components.ChatGitWorkspaceSummary
import com.hermesandroid.relay.ui.components.ChatFailureDetailsDialog
import com.hermesandroid.relay.ui.components.ChatFailurePanel
import com.hermesandroid.relay.viewmodel.ChatFailureRoute
import com.hermesandroid.relay.viewmodel.ChatFailureNotice
import com.hermesandroid.relay.viewmodel.scopedChatFailure
import com.hermesandroid.relay.ui.components.ConversationVoiceDock
import com.hermesandroid.relay.ui.components.ChatInputPickerControl
import com.hermesandroid.relay.ui.components.ChatInputPickerOption
import com.hermesandroid.relay.ui.components.ChatInputTrailing
import com.hermesandroid.relay.ui.components.CommandPalette
import com.hermesandroid.relay.ui.components.KeepScreenOnWhile
import com.hermesandroid.relay.ui.components.ModelPickerSheet
import com.hermesandroid.relay.ui.components.OptionPickerSheet
import com.hermesandroid.relay.ui.components.ConnectionStatusBadge
import com.hermesandroid.relay.ui.components.CommandRow
import com.hermesandroid.relay.ui.components.ContextMeterBar
import com.hermesandroid.relay.ui.components.CHAT_PET_WALK_REGION
import com.hermesandroid.relay.ui.components.CHAT_PET_ASSISTANT_MESSAGE_PERCH_PREFIX
import com.hermesandroid.relay.ui.components.CHAT_PET_STEP_MESSAGE_MARKER
import com.hermesandroid.relay.ui.components.CHAT_PET_USER_MESSAGE_PERCH_PREFIX
import com.hermesandroid.relay.ui.components.GatewayBackgroundProcessSheet
import com.hermesandroid.relay.ui.components.GatewayBackgroundProcessStrip
import com.hermesandroid.relay.ui.components.InjectedContextSheet
import com.hermesandroid.relay.ui.components.InlineAutocomplete
import com.hermesandroid.relay.ui.components.loadedContentTransform
import com.hermesandroid.relay.ui.components.MessageBubble
import com.hermesandroid.relay.ui.components.PendingAttachmentComposer
import com.hermesandroid.relay.ui.components.AttachmentViewer
import com.hermesandroid.relay.ui.components.ChatQuoteReferenceChip
import com.hermesandroid.relay.ui.components.TranscriptSearchNavigator
import com.hermesandroid.relay.ui.components.TranscriptNavigatorStrings
import com.hermesandroid.relay.ui.components.newestPetPerchUiKey
import com.hermesandroid.relay.ui.components.newestPetVisitTargetUiKey
import com.hermesandroid.relay.ui.components.petPerchUiKeys
import com.hermesandroid.relay.ui.components.SyntheticProcessNotificationNotice
import com.hermesandroid.relay.ui.components.avatar.AvatarRenderState
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.hermesandroid.relay.ui.components.LocalAgentIconPath
import com.hermesandroid.relay.ui.components.avatar.LocalAgentAvatar
import com.hermesandroid.relay.ui.components.avatar.LocalBackgroundVisualizationEnabled
import com.hermesandroid.relay.ui.components.pet.LocalPetCompanionCoordinator
import com.hermesandroid.relay.ui.components.pet.PetInteractionLayer
import com.hermesandroid.relay.ui.components.pet.petObstacleSurface
import com.hermesandroid.relay.ui.components.pet.petPerchSurface
import java.io.File
import java.util.UUID
import com.hermesandroid.relay.ui.components.RelayChromeIconButton
import com.hermesandroid.relay.ui.components.SphereState
import com.hermesandroid.relay.ui.components.LocalThinkingIndicator
import com.hermesandroid.relay.ui.components.ThinkingIndicatorConfig
import com.hermesandroid.relay.ui.components.ThinkingIndicatorStyle
import com.hermesandroid.relay.ui.components.ThinkingMatrixColor
import com.hermesandroid.relay.ui.components.ThinkingMatrixPattern
import com.hermesandroid.relay.ui.components.SessionDrawerContent
import com.hermesandroid.relay.ui.components.ProfileSessionRow
import com.hermesandroid.relay.ui.components.ProvisionalThreadRow
import com.hermesandroid.relay.ui.components.ProfileDisplayManagerDialog
import com.hermesandroid.relay.ui.components.ProfileShelf
import com.hermesandroid.relay.ui.components.ProfileSwitcherSheet
import com.hermesandroid.relay.ui.components.SlashCommand
import com.hermesandroid.relay.ui.components.StreamingDots
import com.hermesandroid.relay.ui.components.SubagentLane
import com.hermesandroid.relay.ui.components.ToolActivityRun
import com.hermesandroid.relay.ui.components.ToolProgressCard
import com.hermesandroid.relay.ui.components.ToolTranscriptItem
import com.hermesandroid.relay.ui.components.groupTranscriptTools
import com.hermesandroid.relay.ui.components.isVisibleForToolDisplay
import com.hermesandroid.relay.ui.components.showsImageGenerationPlaceholder
import com.hermesandroid.relay.ui.components.VoiceModeOverlay
import com.hermesandroid.relay.ui.LocalSnackbarHost
import com.hermesandroid.relay.ui.showHumanError
import com.hermesandroid.relay.util.HumanErrorAction
import com.hermesandroid.relay.ui.theme.RelayRefresh
import com.hermesandroid.relay.ui.theme.appearanceRoundedCornerShape
import kotlin.math.abs
import com.hermesandroid.relay.ui.theme.relayGridTexture
import com.hermesandroid.relay.ui.theme.relayMetadataStyle
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.roundToInt
import com.hermesandroid.relay.viewmodel.ChatViewModel
import com.hermesandroid.relay.viewmodel.ChatConnectState
import com.hermesandroid.relay.viewmodel.ChatRuntimeStatus
import com.hermesandroid.relay.viewmodel.ChatTransportReadiness
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.viewmodel.resolveChatRuntimeStatus
import com.hermesandroid.relay.viewmodel.VoiceViewModel
import com.hermesandroid.relay.viewmodel.VoiceState
import com.hermesandroid.relay.assistant.AssistantAppSessionState
import com.hermesandroid.relay.voice.VoiceOverlayHost
import com.hermesandroid.relay.voice.VoiceOverlaySession
import com.hermesandroid.relay.voice.openHermesFromOverlay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DEFAULT_CHAR_LIMIT = 4096
private const val CHAT_SCROLL_TO_BOTTOM_PET_OBSTACLE = "chat-scroll-to-bottom-obstacle"
private const val CHAT_AUTOCOMPLETE_PET_OBSTACLE = "chat-autocomplete-obstacle"
private const val CHAT_RECENT_PROMPTS_PET_OBSTACLE = "chat-recent-prompts-obstacle"
private val CHAT_PET_ROUTES = setOf("chat")

internal fun resolveChatHeaderSubtitle(
    isStreaming: Boolean,
    statusText: String,
    personalityName: String?,
    modelName: String?,
): String = if (isStreaming) {
    statusText
} else {
    listOfNotNull(
        personalityName?.takeIf { it.isNotBlank() },
        modelName?.takeIf { it.isNotBlank() },
    ).joinToString(" \u00B7 ").ifBlank { statusText }
}

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

private class ChatTailTransitionRef(
    var snapshot: ChatScrollSnapshot? = null,
)

internal data class ChatViewportFollowSnapshot(
    val totalItemsCount: Int,
    val tailUiKey: String?,
    val tailSizePx: Int?,
    val viewportHeightPx: Int,
    val visibleBottomDistancePx: Int?,
    val followTailGrowth: Boolean,
    val followViewportResize: Boolean,
)

internal fun shouldCorrectConversationBottomAfterLayout(
    previous: ChatViewportFollowSnapshot?,
    current: ChatViewportFollowSnapshot,
    atExactBottom: Boolean,
    userScrolledAway: Boolean,
    userDragging: Boolean,
    isStreaming: Boolean,
    smoothAutoScroll: Boolean,
    viewportFollowAllowed: Boolean,
): Boolean {
    val old = previous ?: return false
    if (
        atExactBottom || userScrolledAway || userDragging || !viewportFollowAllowed ||
        (isStreaming && !smoothAutoScroll)
    ) {
        return false
    }
    if (
        old.totalItemsCount != current.totalItemsCount ||
        old.tailUiKey == null ||
        old.tailUiKey != current.tailUiKey
    ) {
        return false
    }
    // Tail-row remeasurement (including completion chrome and Markdown
    // settlement) is already owned by requiredBottomFollowScroll. Sending it
    // through this fallback as well performs a full scrollToItem after the
    // measured ramp, visibly resetting the anchor for one frame.
    if (old.tailSizePx != current.tailSizePx) return false

    return old.viewportHeightPx != current.viewportHeightPx ||
        old.visibleBottomDistancePx != current.visibleBottomDistancePx
}

internal fun requiredBottomFollowScroll(
    previous: ChatViewportFollowSnapshot?,
    current: ChatViewportFollowSnapshot,
): Int {
    if (!current.followTailGrowth && !current.followViewportResize) return 0

    // When the footer is visible, its trailing edge is the authoritative
    // distance to the exact bottom. This also consumes rounding and any small
    // non-tail layout changes that a tail-height delta cannot represent.
    current.visibleBottomDistancePx?.let { return it.coerceAtLeast(0) }

    val previousSnapshot = previous ?: return 0
    val tailGrowthPx = if (
        current.followTailGrowth &&
        previousSnapshot.tailUiKey == current.tailUiKey
    ) {
        ((current.tailSizePx ?: 0) - (previousSnapshot.tailSizePx ?: 0)).coerceAtLeast(0)
    } else {
        0
    }
    val viewportLossPx = if (current.followViewportResize) {
        (previousSnapshot.viewportHeightPx - current.viewportHeightPx).coerceAtLeast(0)
    } else {
        0
    }
    return maxOf(tailGrowthPx, viewportLossPx)
}

internal fun ownedBottomFollowScroll(
    previous: ChatViewportFollowSnapshot?,
    current: ChatViewportFollowSnapshot,
): Int {
    val sameTranscript = previous != null &&
        previous.totalItemsCount == current.totalItemsCount &&
        previous.tailUiKey == current.tailUiKey
    // A structural/new-tail transition belongs to the existing streaming
    // owner. Ordinary restore-layout following must never opt a reader into
    // new-message auto-follow when smooth auto-scroll is disabled.
    if (!sameTranscript && !current.followTailGrowth) return 0
    return requiredBottomFollowScroll(previous, current)
}

/**
 * One frame of the live bottom-follow ramp. The maximum step is derived from
 * the viewport, never the transcript distance, so a long session cannot make
 * token updates accelerate into a full-list race. The symmetric braking limit
 * keeps the last few frames from stopping abruptly.
 */
internal fun boundedBottomFollowStep(
    remainingPx: Int,
    previousStepPx: Int,
    viewportHeightPx: Int,
    motionEnabled: Boolean,
): Int {
    if (remainingPx <= 0) return 0
    if (!motionEnabled) return remainingPx

    val maxStep = (viewportHeightPx / 14).coerceIn(12, 56)
    val acceleration = (maxStep / 5).coerceAtLeast(3)
    val accelerated = (previousStepPx + acceleration).coerceIn(acceleration, maxStep)
    val brakingLimit = kotlin.math.sqrt(
        2.0 * acceleration.toDouble() * remainingPx.toDouble(),
    ).toInt().coerceAtLeast(acceleration)
    return minOf(remainingPx, accelerated, brakingLimit)
}

internal fun shouldInitiallyPositionConversation(
    positionedSessionId: String?,
    currentSessionId: String?,
    isLoadingHistory: Boolean,
    hasMessages: Boolean,
): Boolean = currentSessionId != null &&
    positionedSessionId != currentSessionId &&
    !isLoadingHistory &&
    hasMessages

internal enum class ChatFollowEvent {
    UserSend,
    UserMovedAway,
    ReturnedToBottom,
    JumpToLatest,
    StreamStarted,
    StreamUpdated,
    QueuedTurnStarted,
    TurnCompleted,
    HistoryRefreshed,
    AppResumed,
}

/** User intent is the only state transition; transport/layout events retain it. */
internal fun reduceUserScrolledAway(
    current: Boolean,
    event: ChatFollowEvent,
): Boolean = when (event) {
    ChatFollowEvent.UserSend,
    ChatFollowEvent.ReturnedToBottom,
    ChatFollowEvent.JumpToLatest -> false

    ChatFollowEvent.UserMovedAway -> true

    ChatFollowEvent.StreamStarted,
    ChatFollowEvent.StreamUpdated,
    ChatFollowEvent.QueuedTurnStarted,
    ChatFollowEvent.TurnCompleted,
    ChatFollowEvent.HistoryRefreshed,
    ChatFollowEvent.AppResumed -> current
}

internal fun shouldFollowImeAfterInsetChange(
    wasFollowing: Boolean,
    previousImeBottomPx: Int,
    currentImeBottomPx: Int,
    wasAtBottom: Boolean,
    userDragging: Boolean,
): Boolean = when {
    currentImeBottomPx == 0 || userDragging -> false
    previousImeBottomPx == 0 -> wasAtBottom
    else -> wasFollowing
}

internal fun shouldExactlySettleConversation(
    autoFollowEnabled: Boolean,
    userScrolledAway: Boolean,
    userDragging: Boolean,
    hasMessages: Boolean,
): Boolean = autoFollowEnabled && hasMessages && !userScrolledAway && !userDragging

internal fun shouldFollowConversationViewportResize(
    userScrolledAway: Boolean,
    userDragging: Boolean,
    imeBottomPx: Int,
    followImeResize: Boolean,
    voiceDockAnchorTransitionActive: Boolean,
): Boolean = !userScrolledAway &&
    !userDragging &&
    !voiceDockAnchorTransitionActive &&
    (followImeResize || imeBottomPx == 0)

private fun LazyListState.isAtConversationBottom(slopPx: Int): Boolean {
    val layout = layoutInfo
    if (layout.totalItemsCount == 0) return true
    val last = layout.visibleItemsInfo.lastOrNull() ?: return false
    return last.index == layout.totalItemsCount - 1 &&
        (last.offset + last.size) - layout.viewportEndOffset <= slopPx
}

private fun LazyListState.visibleConversationBottomDistancePx(): Int? {
    val layout = layoutInfo
    val lastIndex = layout.totalItemsCount - 1
    if (lastIndex < 0) return 0
    val footer = layout.visibleItemsInfo.firstOrNull { it.index == lastIndex } ?: return null
    return ((footer.offset + footer.size) - layout.viewportEndOffset).coerceAtLeast(0)
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
    voicePresentationMode: VoicePresentationMode = VoicePresentationMode.Focus,
    onVoicePresentationModeChange: (VoicePresentationMode) -> Unit = {},
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
    onRepairConnection: () -> Unit = onNavigateToConnect,
    // Offline demo entry, surfaced on the empty-chat "needs connection" card so a
    // skipped / never-connected first run can explore without a server. null hides it.
    onTryDemo: (() -> Unit)? = null,
    onNavigateToManage: () -> Unit = {},
    onNavigateToBridge: () -> Unit = {},
    onNavigateToTerminal: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToAppearanceSettings: () -> Unit = {},
    // Voice mode gear button → full Voice Settings screen. Default no-op so
    // existing test/preview call sites keep compiling.
    onNavigateToVoiceSettings: () -> Unit = {},
    onNavigateToProfileInspector: (String) -> Unit = {},
    supervisedPolicy: SupervisedModePolicy = SupervisedModePolicy(),
    onNavigateToBotMode: () -> Unit = {},
    gitWorkspaceSummary: ChatGitWorkspaceSummary? = null,
    gitWorkspaceAvailable: Boolean = gitWorkspaceSummary != null,
    onNavigateToGitWorkspace: () -> Unit = {},
) {
    val supervised = supervisedPolicy.enabled
    val supervisedVisibility = supervisedPolicy.visibility.resolved()
    LaunchedEffect(supervisedPolicy) {
        voiceViewModel.updateSupervisedModePolicy(supervisedPolicy)
    }
    if (supervised && !supervisedPolicy.isActive) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Supervised chat unavailable") },
                    actions = {
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    },
                )
            },
        ) { padding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "The supervised profile is unavailable. Parent access is required to update this connection.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    val voiceUiState by voiceViewModel.uiState.collectAsState()
    val responseSpeechActive by voiceViewModel.responseSpeechActive.collectAsState()
    val isDemoMode by connectionViewModel.isDemoMode.collectAsState()
    var voicePresentationOverride by remember { mutableStateOf<VoicePresentationMode?>(null) }
    val effectiveVoicePresentationMode = voicePresentationOverride ?: voicePresentationMode
    val conversationVoiceDockVisible =
        voiceUiState.voiceMode &&
            effectiveVoicePresentationMode == VoicePresentationMode.Conversation
    val setVoicePresentationMode: (VoicePresentationMode) -> Unit = { mode ->
        voicePresentationOverride = mode
        onVoicePresentationModeChange(mode)
    }
    val chatAlpha by animateFloatAsState(
        targetValue = if (
            voiceUiState.voiceMode && effectiveVoicePresentationMode == VoicePresentationMode.Focus
        ) 0.4f else 1f,
        animationSpec = tween(300),
        label = "chatAlpha",
    )
    LaunchedEffect(voiceUiState.voiceMode) {
        if (!voiceUiState.voiceMode) voicePresentationOverride = null
    }
    // Route classified chat errors (media cache, streaming failures, …) to
    // the app-wide snackbar. Same pattern every VM-bound screen uses.
    val snackbarHost = LocalSnackbarHost.current
    LaunchedEffect(chatViewModel) {
        chatViewModel.errorEvents.collect { err ->
            val result = snackbarHost.showHumanError(err)
            if (
                result == SnackbarResult.ActionPerformed &&
                err.action == HumanErrorAction.Repair
            ) {
                onRepairConnection()
            }
        }
    }

    // RECORD_AUDIO permission flow — user taps the mic FAB → if not granted,
    // request; on grant, latch the pending-enter and fire enterVoiceMode() in
    // the callback. Denial shows an inline banner above the input.
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val voiceOverlayHost = remember { VoiceOverlayHost.install(context) }
    val assistantSessionActive by AssistantAppSessionState.active.collectAsState()
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
                setVoicePresentationMode(VoicePresentationMode.Conversation)
                com.hermesandroid.relay.wake.WakeWordForegroundService.prepareForVoice()
                voiceViewModel.enterVoiceMode()
            } else {
                pendingVoiceEnter = false
            }
        } else {
            pendingVoiceEnter = false
            voicePresentationOverride = null
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
            setVoicePresentationMode(VoicePresentationMode.Conversation)
            com.hermesandroid.relay.wake.WakeWordForegroundService.prepareForVoice()
            voiceViewModel.enterVoiceMode()
        } else {
            pendingVoiceEnter = true
            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }


    val rawMessages by chatViewModel.messages.collectAsState()
    val messages = remember(rawMessages, supervised, supervisedPolicy.capabilities.generatedImages) {
        if (!supervised) rawMessages
        else rawMessages.map { message ->
            if (message.role == MessageRole.ASSISTANT) {
                message.copy(
                    attachments = if (supervisedPolicy.capabilities.generatedImages) {
                        message.attachments.filter { it.isImage }
                    } else {
                        emptyList()
                    },
                    cards = emptyList(),
                )
            } else message
        }
    }
    val messageReactionsSupported by chatViewModel.messageReactionsSupported.collectAsState()
    val newestReactableMessageKeys = remember(messages) {
        setOfNotNull(
            messages.lastOrNull { it.role == MessageRole.USER }?.uiKey,
            messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.uiKey,
        )
    }
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
    val connectionVoiceReady by connectionViewModel.voiceReady.collectAsState()
    val standardVoiceAvailability by connectionViewModel.standardVoiceAvailability.collectAsState()
    val voiceReady = if (supervised) {
        supervisedPolicy.capabilities.voice &&
            standardVoiceAvailability ==
            com.hermesandroid.relay.viewmodel.StandardVoiceAvailability.Ready
    } else {
        connectionVoiceReady
    }
    val chatSpeakResponseActionsEnabled =
        shouldOfferChatSpeakAction(voiceReady, voiceUiState.state)
    val standardVoiceSignInRouteHint by
        connectionViewModel.standardVoiceSignInRouteHint.collectAsState()
    val dashboardRouteMovedHint by connectionViewModel.dashboardRouteMovedHint.collectAsState()
    val apiReachable by connectionViewModel.apiServerReachable.collectAsState()
    val chatMode by connectionViewModel.chatMode.collectAsState()
    val error by chatViewModel.error.collectAsState()
    val sessions by chatViewModel.sessions.collectAsState()
    val backgroundSessionActivityStates by
        chatViewModel.backgroundSessionActivityStates.collectAsState()
    val serverAutoTitles by chatViewModel.serverAutoTitles.collectAsState()
    val sessionArchivingSupported by chatViewModel.sessionArchivingSupported.collectAsState()
    val currentSessionId by chatViewModel.currentSessionId.collectAsState()
    val structuredChatFailure by chatViewModel.chatFailure.collectAsState()
    val visibleChatFailure = scopedChatFailure(
        structuredChatFailure,
        currentSessionId,
    ) ?: error?.let { rawError ->
            ChatFailureNotice(
                sessionId = currentSessionId,
                turnId = "transport-error",
                rawError = rawError,
                route = null,
            )
        }
    var showChatFailureDetails by rememberSaveable(visibleChatFailure?.turnId) {
        mutableStateOf(false)
    }
    val pendingAsk by chatViewModel.pendingAsk.collectAsState()
    val sessionActivityStates = backgroundSessionActivityStates
    LaunchedEffect(currentSessionId, isStreaming, pendingAsk) {
        chatViewModel.updateCurrentSessionActivity(
            isStreaming = isStreaming,
            needsInput = pendingAsk != null,
        )
    }
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
    val effectiveProfile by connectionViewModel.effectiveDisplayProfile.collectAsState()
    val profilePresentation by connectionViewModel.profilePresentation.collectAsState()
    val isProfileLocked by connectionViewModel.isProfileLocked.collectAsState()
    val lockedProfileName by connectionViewModel.lockedProfileName.collectAsState()
    // Server-advertised profile catalog — used to locate the "default" profile
    // so the header can render its description/model when no explicit pick
    // has been made (the /api/config fallback is more useful than the bare
    // connection label).
    val agentProfiles by connectionViewModel.agentProfiles.collectAsState()
    var allProfileSessions by remember { mutableStateOf<List<ProfileSessionRow>>(emptyList()) }
    var allProfileSessionsLoading by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    suspend fun refreshAllProfileSessions(showError: Boolean) {
        if (isProfileLocked || allProfileSessionsLoading) return
        allProfileSessionsLoading = true
        val result = connectionViewModel.listAllProfileSessions()
        result?.fold(
            onSuccess = { items ->
                allProfileSessions = items.mapNotNull { item ->
                    val owner = item.profile?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    ProfileSessionRow(
                        profile = owner,
                        session = com.hermesandroid.relay.data.ChatSession(
                            sessionId = item.id,
                            title = item.title ?: item.preview,
                            model = item.model,
                            messageCount = item.messageCount ?: 0,
                            inputTokens = item.inputTokens ?: 0,
                            outputTokens = item.outputTokens ?: 0,
                            actualCostUsd = item.actualCostUsd,
                            estimatedCostUsd = item.estimatedCostUsd,
                            recentlyActive = item.isActive,
                            startedAt = ((item.startedAt ?: 0.0) * 1000).toLong(),
                            lastActivityAt = ((item.resolvedLastActivity ?: 0.0) * 1000).toLong(),
                            source = item.source,
                            pinned = item.pinned,
                            archived = item.archived,
                            workingDirectory = item.cwd,
                            gitBranch = item.gitBranch,
                            gitRepoRoot = item.gitRepoRoot,
                            pullRequestNumber = item.pullRequest?.number,
                            pullRequestUrl = item.pullRequest?.url,
                            pullRequestState = item.pullRequest?.state,
                            pullRequestDraft = item.pullRequest?.draft == true,
                        ),
                    )
                }
                chatViewModel.updateSessionActivityDirectory(
                    rows = allProfileSessions.map { it.profile to it.session.sessionId },
                )
            },
            onFailure = { error ->
                if (showError) snackbarHostState.showSnackbar(
                    "Couldn't load all profiles: ${error.message ?: "unsupported"}",
                )
            },
        )
        allProfileSessionsLoading = false
    }
    val conversationBinding by chatViewModel.conversationBinding.collectAsState()
    val explicitBindingProfileName = conversationBinding.profileName
        .takeIf { conversationBinding.hasExplicitOwner }
    val explicitBindingProfileIconPath by remember(
        connectionViewModel,
        explicitBindingProfileName,
    ) {
        connectionViewModel.profileIconFlow(explicitBindingProfileName)
    }.collectAsState(initial = null)
    val conversationProfile = explicitBindingProfileName?.let { owner ->
        agentProfiles.firstOrNull { it.name.equals(owner, ignoreCase = true) }
            ?: allProfileSessions.firstOrNull {
                it.profile.equals(owner, ignoreCase = true) &&
                    it.session.sessionId == currentSessionId
            }?.session?.let { session ->
                com.hermesandroid.relay.data.Profile(
                    name = owner,
                    model = session.model.orEmpty(),
                    description = owner,
                )
            }
            ?: com.hermesandroid.relay.data.Profile(
                name = owner,
                model = "",
                description = owner,
            )
    } ?: effectiveProfile
    val profileDisplayAlias by connectionViewModel.profileDisplayAlias.collectAsState()
    val activeConnection by connectionViewModel.activeConnection.collectAsState()
    val serverModelName by chatViewModel.serverModelName.collectAsState()
    val apiModelOptions by chatViewModel.apiModelOptions.collectAsState()
    val modelProviders by chatViewModel.modelProviders.collectAsState()
    val modelOptionsRefreshing by chatViewModel.modelOptionsRefreshing.collectAsState()
    val modelSelectionConfirmation by chatViewModel.modelSelectionConfirmation.collectAsState()
    val reasoningCapabilityRevision by chatViewModel.reasoningCapabilityRevision.collectAsState()
    val selectedModelOverride by chatViewModel.selectedModelOverride.collectAsState()
    val selectedProviderOverride by chatViewModel.selectedProviderOverride.collectAsState()
    val gatewayCurrentModel by chatViewModel.gatewayCurrentModel.collectAsState()
    val gatewayCurrentProvider by chatViewModel.gatewayCurrentProvider.collectAsState()
    val selectedReasoningEffort by chatViewModel.selectedReasoningEffort.collectAsState()
    val currentSession = remember(sessions, currentSessionId) {
        sessions.firstOrNull { it.sessionId == currentSessionId }
    }
    val sessionModelState = resolveSessionModelUiState(
        hasSession = currentSessionId != null,
        pendingModel = selectedModelOverride,
        pendingProvider = selectedProviderOverride,
        gatewayModel = gatewayCurrentModel,
        gatewayProvider = gatewayCurrentProvider,
        persistedSessionModel = currentSession?.model,
        profileDefaultModel = conversationProfile?.model,
        serverDefaultModel = serverModelName.takeIf { explicitBindingProfileName == null },
    )
    val sessionPickerProvider = sessionModelState.pickerProvider
        ?: sessionModelState.pickerModel?.let { model ->
            modelProviders.singleOrNull { model in it.models }?.slug
        }
    val configuredShowThinking by connectionViewModel.showThinking.collectAsState()
    val configuredToolDisplay by connectionViewModel.toolDisplay.collectAsState()
    val showThinking = configuredShowThinking &&
        (!supervised || supervisedVisibility.showReasoning)
    val toolDisplay = if (!supervised) configuredToolDisplay else when {
        supervisedVisibility.showToolDetails -> "detailed"
        supervisedVisibility.showToolNames -> "compact"
        else -> "off"
    }
    val smoothAutoScroll by connectionViewModel.smoothAutoScroll.collectAsState()
    val closeDrawerOnSend by connectionViewModel.closeDrawerOnSend.collectAsState()
    val keepComposerFocusedOnSend by
        connectionViewModel.keepComposerFocusedOnSend.collectAsState()
    val physicalKeyboardEnterBehavior by
        connectionViewModel.physicalKeyboardEnterBehavior.collectAsState()
    val convertLargePastesToAttachments by
        connectionViewModel.convertLargePastesToAttachments.collectAsState()
    val showGitWorkspaceInChat by
        connectionViewModel.showGitWorkspaceInChat.collectAsState()
    val visibleGitWorkspaceSummary = gitWorkspaceSummary?.takeIf {
        !supervised && showGitWorkspaceInChat && it.branch.isNotBlank()
    }
    val showGitWorkspaceContextEntry =
        !supervised && showGitWorkspaceInChat && gitWorkspaceAvailable

    val availableSkills by chatViewModel.availableSkills.collectAsState()
    val queuedMessages by chatViewModel.queuedMessages.collectAsState()
    val recentPrompts by chatViewModel.recentPrompts.collectAsState()
    val recentPromptsEnabled by connectionViewModel.chatRecentPromptsEnabled.collectAsState()
    // Effective approval-bypass (server-computed: ORs global approvals.mode=off,
    // the --yolo env, and the per-session flag). Drives a subtle status-strip
    // marker so the user knows approvals are off without opening the agent drawer.
    val yoloEnabled by chatViewModel.yoloEnabled.collectAsState()
    val pendingAttachments by chatViewModel.pendingAttachments.collectAsState()
    val configuredMaxAttachmentMb by connectionViewModel.maxAttachmentMb.collectAsState()
    val maxAttachmentMb = if (supervised) {
        minOf(configuredMaxAttachmentMb, supervisedPolicy.capabilities.attachmentMaxFileMb)
    } else configuredMaxAttachmentMb
    val charLimit by connectionViewModel.maxMessageLength.collectAsState()

    // === Gateway desktop-parity state ===
    val serverCommands by chatViewModel.serverCommands.collectAsState()
    val contextUsage by chatViewModel.contextUsage.collectAsState()
    val contextWindow by chatViewModel.contextWindow.collectAsState()
    // Injected-context audit sheet (opened by tapping the context meter).
    var showContextSheet by remember { mutableStateOf(false) }
    LaunchedEffect(supervised) {
        if (supervised) showContextSheet = false
    }
    val steerableTurn by chatViewModel.steerableTurn.collectAsState()
    val steerNotice by chatViewModel.steerNotice.collectAsState()
    val voiceHintSeen by connectionViewModel.voiceHintSeen.collectAsState()
    // Whether the NEXT turn would ride the gateway transport — gates the
    // "Edit & resend" menu entry (conversation rewind needs the gateway).
    // Per-turn correction availability comes from [steerableTurn], which also covers
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
    // the foreground. setChatVisible owns that edge; an ordinary Gateway open
    // warms only the observation socket and never attaches a saved session.
    val appForeground by com.hermesandroid.relay.util.AppForegroundTracker.isForeground.collectAsState()
    LaunchedEffect(isGatewayTransport, appForeground, chatReady) {
        val chatVisible = appForeground && chatReady
        val visibilityChanged = chatViewModel.setChatVisible(chatVisible)
        if (isGatewayTransport && chatVisible && !visibilityChanged) {
            // Gateway availability can settle after Chat was already visible.
            // Repeat the socket-only warmup for that edge; ordinary observation
            // still cannot resume or activate a session.
            chatViewModel.prewarmGateway()
        }
        if (isGatewayTransport && appForeground && chatReady) {
            chatViewModel.refreshModelOptions()
            chatViewModel.refreshReasoningSettings()
        }
    }
    DisposableEffect(chatViewModel) {
        onDispose { chatViewModel.setChatVisible(false) }
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
    var quotedMessage by remember { mutableStateOf<ChatMessage?>(null) }

    // Animation settings
    val animationEnabled by connectionViewModel.animationEnabled.collectAsState()
    val accessibleMotion = rememberAccessibleMotionState()
    val animationBehindChat by connectionViewModel.animationBehindChat.collectAsState()
    val imageGenerationStyle by connectionViewModel.imageGenerationStyle.collectAsState()
    val thinkingIndicatorStyle by connectionViewModel.thinkingIndicatorStyle.collectAsState()
    val thinkingMatrixPattern by connectionViewModel.thinkingMatrixPattern.collectAsState()
    val thinkingMatrixColor by connectionViewModel.thinkingMatrixColor.collectAsState()
    val imageGenerationOrdinals = remember(messages) {
        var nextOrdinal = 0
        buildMap {
            messages.forEach { message ->
                val generationCount = message.toolCalls.count {
                    it.name.trim().equals("image_generate", ignoreCase = true)
                }
                if (generationCount > 0) {
                    put(message.uiKey, nextOrdinal + generationCount - 1)
                    nextOrdinal += generationCount
                }
            }
        }
    }
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
    val composerDraftKey = remember(
        activeConnection?.id,
        selectedProfile?.name,
        explicitBindingProfileName,
        currentSessionId,
    ) {
        ChatComposerDraftKey(
            connectionId = activeConnection?.id?.takeIf(String::isNotBlank) ?: "offline",
            profileId = (explicitBindingProfileName ?: selectedProfile?.name)
                ?.takeIf(String::isNotBlank)
                ?: ChatComposerDraftKey.DEFAULT_PROFILE_ID,
            sessionId = currentSessionId?.takeIf(String::isNotBlank) ?: "new-session",
        )
    }
    var activeComposerDraftKey by remember(chatViewModel) {
        mutableStateOf<ChatComposerDraftKey?>(null)
    }
    var restoringComposerDraft by remember { mutableStateOf(false) }
    LaunchedEffect(composerDraftKey) {
        activeComposerDraftKey?.let { previousKey ->
            chatViewModel.composerDraftStore.save(
                previousKey,
                ChatComposerDraft(
                    text = inputText,
                    selectionStart = inputText.length,
                    selectionEnd = inputText.length,
                    context = ChatComposerDraftContext(
                        quotedMessageId = quotedMessage?.id,
                        editingMessageId = editingMessage?.id,
                    ),
                    attachments = pendingAttachments,
                ),
            )
        }
        restoringComposerDraft = true
        val restored = chatViewModel.composerDraftStore.snapshot(composerDraftKey)
        inputText = restored.text
        editingMessage = restored.context.editingMessageId?.let { messageId ->
            messages.firstOrNull { it.id == messageId }
        }
        quotedMessage = restored.context.quotedMessageId?.let { messageId ->
            messages.firstOrNull { it.id == messageId }
        }
        chatViewModel.replacePendingAttachments(restored.attachments)
        activeComposerDraftKey = composerDraftKey
        restoringComposerDraft = false
    }
    val sharedContentRequest by com.hermesandroid.relay.util.SharedContentRequest.pending.collectAsState()
    LaunchedEffect(
        sharedContentRequest,
        composerDraftKey,
        activeComposerDraftKey,
        maxAttachmentMb,
        charLimit,
    ) {
        val request = sharedContentRequest ?: return@LaunchedEffect
        if (!com.hermesandroid.relay.util.canApplySharedContent(
                request = request,
                composerConnectionId = composerDraftKey.connectionId,
                composerProfileId = composerDraftKey.profileId,
                composerSessionId = composerDraftKey.sessionId,
                draftRestored = activeComposerDraftKey == composerDraftKey,
            )
        ) return@LaunchedEffect

        editingMessage = null
        quotedMessage = null
        inputText = request.payload.text.orEmpty().take(charLimit)
        chatViewModel.replacePendingAttachments(emptyList())
        request.payload.uriStrings.forEach { uriString ->
            if (!com.hermesandroid.relay.util.isAllowedSharedContentUri(uriString)) {
                return@forEach
            }
            runCatching { Uri.parse(uriString) }
                .getOrNull()
                ?.let { uri ->
                    ingestAttachmentFromUri(context, uri, maxAttachmentMb) {
                        chatViewModel.addAttachment(it)
                    }
                }
        }
        if (request.payload.omittedUriCount > 0) {
            Toast.makeText(
                context,
                context.getString(
                    R.string.chat_shared_files_limited,
                    com.hermesandroid.relay.util.MAX_SHARED_CONTENT_ATTACHMENTS,
                ),
                Toast.LENGTH_LONG,
            ).show()
        }
        com.hermesandroid.relay.util.SharedContentRequest.consume(request.id)
    }
    LaunchedEffect(
        inputText,
        editingMessage?.id,
        quotedMessage?.id,
        pendingAttachments,
        activeComposerDraftKey,
    ) {
        val key = activeComposerDraftKey ?: return@LaunchedEffect
        if (restoringComposerDraft) return@LaunchedEffect
        delay(200)
        chatViewModel.composerDraftStore.save(
            key,
            ChatComposerDraft(
                text = inputText,
                selectionStart = inputText.length,
                selectionEnd = inputText.length,
                context = ChatComposerDraftContext(
                    quotedMessageId = quotedMessage?.id,
                    editingMessageId = editingMessage?.id,
                ),
                attachments = pendingAttachments,
            ),
        )
    }
    var showCommandPalette by remember { mutableStateOf(false) }
    var showTranscriptSearch by rememberSaveable { mutableStateOf(false) }
    var pendingAttachmentPreview by remember { mutableStateOf<Attachment?>(null) }
    var showModelSheet by remember { mutableStateOf(false) }
    var showEffortSheet by remember { mutableStateOf(false) }
    var showAgentInfo by remember { mutableStateOf(false) }
    var showProfileShelf by remember { mutableStateOf(false) }
    var showProfileSwitcher by remember { mutableStateOf(false) }
    var showProfileManager by remember { mutableStateOf(false) }
    var showBackgroundProcesses by remember { mutableStateOf(false) }

    val pendingComposerDraft by chatViewModel.pendingComposerDraft.collectAsState()
    LaunchedEffect(pendingComposerDraft) {
        pendingComposerDraft?.let { draft ->
            editingMessage = null
            inputText = draft.take(charLimit)
            chatViewModel.consumeComposerDraft(draft)
        }
    }

    // A process inventory is scoped to one gateway session. Never leave a
    // sheet opened onto a different chat after a drawer/profile switch.
    LaunchedEffect(currentSessionId, selectedProfile?.name, activeConnection?.id) {
        showBackgroundProcesses = false
    }

    val currentMessagesForComposer by rememberUpdatedState(messages)
    // Server command dispatch can ask the composer to prefill (e.g. /undo),
    // and queued quoted replies restore as structured composer state.
    LaunchedEffect(chatViewModel) {
        chatViewModel.composerPrefill.collect { text ->
            val envelope = parseChatQuotedPrompt(text)
            editingMessage = null
            inputText = (envelope?.body ?: text).take(charLimit)
            quotedMessage = envelope?.reference?.messageId?.let { messageId ->
                currentMessagesForComposer.firstOrNull { it.id == messageId }
            }
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
    val userScrolledAwayState = remember(currentSessionId) { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    LaunchedEffect(chatViewModel, drawerState) {
        chatViewModel.sessionDirectoryRefreshRequests.collect {
            if (drawerState.isOpen || allProfileSessions.isNotEmpty()) {
                refreshAllProfileSessions(showError = false)
            }
        }
    }
    PetInteractionLayer(
        owner = "chat-interaction-layer",
        active = shouldHideChatPet(
            voiceMode = voiceUiState.voiceMode,
            drawerOpenOrMoving =
                drawerState.currentValue != DrawerValue.Closed ||
                    drawerState.targetValue != DrawerValue.Closed,
            commandPaletteVisible = showCommandPalette,
            modelSheetVisible = showModelSheet,
            effortSheetVisible = showEffortSheet,
            contextSheetVisible = showContextSheet,
            backgroundProcessesVisible = showBackgroundProcesses,
            agentInfoVisible = showAgentInfo,
        ),
    )
    // Publish only surface-local visibility signals. Live turn state is owned at
    // the app root so navigation cannot reset an in-flight companion to Idle.
    // Modal Chat chrome owns the interaction layer while it is entering, open,
    // or leaving. Suppress the root-hosted pet for the whole transition so it
    // cannot render above the drawer scrim or a bottom sheet.
    val petCompanionCoordinator = LocalPetCompanionCoordinator.current
    LaunchedEffect(listState, petCompanionCoordinator) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                petCompanionCoordinator.publishSurface(
                    owner = "chat",
                    scrolling = scrolling,
                    hidden = false,
                )
            }
    }
    DisposableEffect(petCompanionCoordinator) {
        onDispose { petCompanionCoordinator.clearSurface("chat") }
    }
    val scope = rememberCoroutineScope()
    val latestComposerDraft by rememberUpdatedState {
        activeComposerDraftKey?.let { key ->
            key to ChatComposerDraft(
                text = inputText,
                selectionStart = inputText.length,
                selectionEnd = inputText.length,
                context = ChatComposerDraftContext(
                    quotedMessageId = quotedMessage?.id,
                    editingMessageId = editingMessage?.id,
                ),
                attachments = pendingAttachments,
            )
        }
    }
    DisposableEffect(lifecycleOwner, chatViewModel.composerDraftStore) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                latestComposerDraft()?.let { (key, draft) ->
                    chatViewModel.persistComposerDraft(key, draft)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val copiedToClipboardMsg = stringResource(R.string.chat_copied_to_clipboard)
    val copySessionIdLabel = stringResource(R.string.chat_copy_session_id)
    val hermesMessageLabel = stringResource(R.string.chat_hermes_message)
    val focusManager = LocalFocusManager.current
    val finishSuccessfulSend: () -> Unit = {
        // Sending is an explicit "follow my turn" action, including when it
        // queues behind an active run. Automatic queued-turn handoff itself
        // never re-arms follow after the reader has deliberately moved away.
        userScrolledAwayState.value = reduceUserScrolledAway(
            current = userScrolledAwayState.value,
            event = ChatFollowEvent.UserSend,
        )
        activeComposerDraftKey?.let { key ->
            chatViewModel.removeComposerDraft(key)
        }
        quotedMessage = null
        if (closeDrawerOnSend && drawerState.isOpen) {
            scope.launch { drawerState.close() }
        }
        if (!keepComposerFocusedOnSend) {
            focusManager.clearFocus()
        }
    }
    val clipboard = LocalClipboard.current
    val haptic = LocalHapticFeedback.current
    val handleCardAction: (String, String, HermesCardAction) -> Unit =
        remember(chatViewModel, context) {
            { messageId, cardKey, action ->
                if (action.mode == HermesCardAction.Modes.OPEN_URL) {
                    chatViewModel.dispatchCardAction(messageId, cardKey, action)
                    com.hermesandroid.relay.ui.components.handleCardActionExternally(
                        context,
                        action,
                    )
                } else {
                    chatViewModel.dispatchCardAction(messageId, cardKey, action)
                }
            }
        }
    val handleCardInput: (String, String, String) -> Unit = remember(chatViewModel) {
        { messageId, cardKey, value ->
            chatViewModel.answerAsk(messageId, cardKey, value)
        }
    }

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

    val voiceSystemOverlayAvailable = BuildFlavor.isSideload
    val showVoiceSystemOverlay: () -> Unit = {
        if (!voiceSystemOverlayAvailable) {
            pendingVoiceOverlayPermission = false
        } else if (assistantSessionActive) {
            voiceOverlayHost.hide()
        } else if (!voiceOverlayHost.hasOverlayPermission()) {
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
                    profileName = AgentDisplay.profileDisplayName(effectiveProfile),
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
        }
    }

    LaunchedEffect(assistantSessionActive) {
        if (assistantSessionActive) {
            voiceOverlayHost.hide()
            pendingVoiceOverlayPermission = false
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
        scope.launch {
            uris.forEach { uri ->
                ingestAttachmentFromUri(context, uri, maxAttachmentMb) { chatViewModel.addAttachment(it) }
            }
        }
    }

    // Photos: modern permissionless Android Photo Picker (images, multi-select).
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        scope.launch {
            uris.forEach { uri ->
                ingestAttachmentFromUri(context, uri, maxAttachmentMb) { chatViewModel.addAttachment(it) }
            }
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
            scope.launch {
                ingestAttachmentFromUri(context, uri, maxAttachmentMb) { chatViewModel.addAttachment(it) }
            }
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
    var userScrolledAway by userScrolledAwayState
    var isUserDragging by remember(currentSessionId) { mutableStateOf(false) }
    var programmaticBottomScroll by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val latestImeBottomPx by rememberUpdatedState(imeBottomPx)
    // Start from the closed state so a chat/session first composed while the
    // keyboard is already visible still gets one ownership decision. Starting
    // at the current non-zero inset left followImeResize false forever.
    var previousImeBottomPx by remember(currentSessionId) { mutableStateOf(0) }
    var followImeResize by remember(currentSessionId) { mutableStateOf(false) }
    SideEffect {
        followImeResize = shouldFollowImeAfterInsetChange(
            wasFollowing = followImeResize,
            previousImeBottomPx = previousImeBottomPx,
            currentImeBottomPx = imeBottomPx,
            // At the first non-zero IME inset, LazyColumn still exposes the
            // pre-resize layout. Capture bottom ownership before the viewport
            // starts losing height.
            wasAtBottom = !userScrolledAway &&
                listState.isAtConversationBottom(atBottomSlopPx),
            userDragging = isUserDragging,
        )
        previousImeBottomPx = imeBottomPx
    }
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
                // Slop preserves follow ownership during motion; an explicit
                // settlement must reach the real LazyColumn boundary.
                slopPx = 0,
            )
            userScrolledAway = reduceUserScrolledAway(
                current = userScrolledAway,
                event = ChatFollowEvent.JumpToLatest,
            )
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
                    followImeResize = false
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    isUserDragging = false
                    userScrolledAway = reduceUserScrolledAway(
                        current = userScrolledAway,
                        event = if (listState.isAtConversationBottom(atBottomSlopPx)) {
                            ChatFollowEvent.ReturnedToBottom
                        } else {
                            ChatFollowEvent.UserMovedAway
                        },
                    )
                }
            }
        }
    }
    var voiceDockAnchorGuardReady by remember { mutableStateOf(false) }
    var voiceDockAnchorTransitionActive by remember { mutableStateOf(false) }
    LaunchedEffect(conversationVoiceDockVisible) {
        if (!voiceDockAnchorGuardReady) {
            voiceDockAnchorGuardReady = true
            return@LaunchedEffect
        }
        if (messages.isEmpty()) return@LaunchedEffect

        // The dock expands inside the composer for 240 ms. Keep the transcript's
        // leading visible row fixed while that changes the LazyColumn viewport;
        // otherwise a conversation parked at the bottom is clamped forward on
        // every animation frame and appears to scroll when voice mode opens.
        val anchorIndex = listState.firstVisibleItemIndex
        val anchorOffset = listState.firstVisibleItemScrollOffset
        voiceDockAnchorTransitionActive = true
        try {
            var firstFrameNanos = 0L
            var frameNanos: Long
            do {
                if (isUserDragging) return@LaunchedEffect
                listState.requestScrollToItem(anchorIndex, anchorOffset)
                frameNanos = withFrameNanos { it }
                if (firstFrameNanos == 0L) firstFrameNanos = frameNanos
            } while (frameNanos - firstFrameNanos < 300_000_000L)
        } finally {
            voiceDockAnchorTransitionActive = false
        }
    }
    // Reaching the bottom by any means (user, follow-pin, content shrank)
    // always re-arms auto-follow.
    LaunchedEffect(isAtBottom) {
        if (isAtBottom) {
            userScrolledAway = reduceUserScrolledAway(
                current = userScrolledAway,
                event = ChatFollowEvent.ReturnedToBottom,
            )
        }
    }

    // IME insets arrive as an animation, not one layout. Wait until inset
    // updates pause, then remove any rounding/late-measurement residue if the
    // conversation still owns bottom-follow. This runs for both opening and
    // closing without moving a transcript whose reader scrolled away.
    var lastImeSettleTargetPx by remember(currentSessionId) { mutableStateOf(imeBottomPx) }
    LaunchedEffect(imeBottomPx) {
        if (imeBottomPx == lastImeSettleTargetPx) return@LaunchedEffect
        lastImeSettleTargetPx = imeBottomPx
        delay(96)
        if (
            shouldExactlySettleConversation(
                autoFollowEnabled = true,
                userScrolledAway = userScrolledAway,
                userDragging = isUserDragging,
                hasMessages = messages.isNotEmpty(),
            )
        ) {
            scrollConversationToBottom(animated = false)
        }
    }

    // The latest affordance reflects user intent, not a transient layout gap.
    // Same-row Markdown promotion can briefly put the footer below the viewport
    // before the sole follow owner consumes its measured delta; never flash the
    // button during that settle. Dragging up or explicitly navigating to an
    // earlier message sets userScrolledAway and makes the affordance available.
    val showScrollToBottom by remember {
        derivedStateOf {
            messages.isNotEmpty() &&
                !isAtBottom &&
                !programmaticBottomScroll &&
                userScrolledAway
        }
    }

    // Slash command descriptions (resolved in composable scope — the
    // remember/derivedStateOf block below is NOT composable, so stringResource
    // must be called here, not inside it).
    val slashDescNew = stringResource(R.string.slash_new_session)
    val slashDescRetry = stringResource(R.string.slash_retry_last)
    val slashDescUndo = stringResource(R.string.slash_remove_exchange)
    val slashDescTitle = stringResource(R.string.slash_set_title)
    val slashDescCompress = stringResource(R.string.slash_compress)
    val slashDescRollback = stringResource(R.string.slash_checkpoints)
    val slashDescStop = stringResource(R.string.slash_kill_bg)
    val slashDescResume = stringResource(R.string.slash_resume)
    val slashDescBackground = stringResource(R.string.slash_background_prompt)
    val slashDescBtw = stringResource(R.string.slash_side_question)
    val slashDescQueue = stringResource(R.string.slash_queue_prompt)
    val slashDescApprove = stringResource(R.string.slash_approve)
    val slashDescDeny = stringResource(R.string.slash_deny)
    val slashDescModel = stringResource(R.string.slash_switch_model)
    val slashDescProvider = stringResource(R.string.slash_providers)
    val slashDescPersonality = stringResource(R.string.slash_personality)
    val slashDescVerbose = stringResource(R.string.slash_tool_progress)
    val slashDescYolo = stringResource(R.string.slash_auto_approve)
    val slashDescReasoning = stringResource(R.string.slash_reasoning)
    val slashDescVoice = stringResource(R.string.slash_voice_mode)
    val slashDescReloadMcp = stringResource(R.string.slash_reload_mcp)
    val slashDescHelp = stringResource(R.string.slash_commands)
    val slashDescStatus = stringResource(R.string.slash_session_info)
    val slashDescUsage = stringResource(R.string.slash_token_usage)
    val slashDescInsights = stringResource(R.string.slash_analytics)
    val slashDescCommands = stringResource(R.string.slash_browse)
    val slashDescProfile = stringResource(R.string.slash_active_profile)
    val slashDescClearPersonality = stringResource(R.string.slash_clear_personality)

    // Build all commands dynamically: built-in + personalities + server
    // skills + (gateway) the server's commands.catalog
    val allCommands by remember(availableSkills, personalityNames, serverCommands) {
        derivedStateOf {
            // Built-in hermes gateway commands (from hermes_cli/commands.py)
            // Only includes commands available via gateway (not cli_only)
            val builtIn = listOf(
                // Session
                SlashCommand("/new", slashDescNew, "session"),
                SlashCommand("/retry", slashDescRetry, "session"),
                SlashCommand("/undo", slashDescUndo, "session"),
                SlashCommand("/title", slashDescTitle, "session"),
                SlashCommand("/branch", "Branch/fork the current session", "session"),
                SlashCommand("/compress", slashDescCompress, "session"),
                SlashCommand("/rollback", slashDescRollback, "session"),
                SlashCommand("/stop", slashDescStop, "session"),
                SlashCommand("/resume", slashDescResume, "session"),
                SlashCommand("/background", slashDescBackground, "session"),
                SlashCommand("/btw", slashDescBtw, "session"),
                SlashCommand("/queue", slashDescQueue, "session"),
                SlashCommand("/approve", slashDescApprove, "session"),
                SlashCommand("/deny", slashDescDeny, "session"),
                // Configuration
                SlashCommand("/model", slashDescModel, "configuration"),
                SlashCommand("/provider", slashDescProvider, "configuration"),
                SlashCommand("/personality", slashDescPersonality, "configuration"),
                SlashCommand("/verbose", slashDescVerbose, "configuration"),
                SlashCommand("/yolo", slashDescYolo, "configuration"),
                SlashCommand("/reasoning", slashDescReasoning, "configuration"),
                SlashCommand("/voice", slashDescVoice, "configuration"),
                SlashCommand("/reload-mcp", slashDescReloadMcp, "configuration"),
                // Info
                SlashCommand("/help", slashDescHelp, "info"),
                SlashCommand("/status", slashDescStatus, "info"),
                SlashCommand("/usage", slashDescUsage, "info"),
                SlashCommand("/insights", slashDescInsights, "info"),
                SlashCommand("/commands", slashDescCommands, "info"),
                SlashCommand("/profile", slashDescProfile, "info"),
            )

            // Dynamic personality commands from server, plus the upstream
            // "none" (clear the overlay) option that completions list first.
            val personalities = listOf(
                SlashCommand(
                    command = "/personality none",
                    description = slashDescClearPersonality,
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
    val showAutocomplete by remember(filteredCommands, inputText, supervised) {
        derivedStateOf {
            !supervised && inputText.startsWith("/") && filteredCommands.isNotEmpty()
        }
    }

    // Refresh sessions when screen appears and API is ready
    LaunchedEffect(chatReady) {
        if (chatReady) {
            chatViewModel.refreshSessions()
        }
    }

    // The drawer and composer share this screen's focus owner. Clear the
    // composer's input focus as soon as an open transition is committed so
    // menu activation, accessibility activation, and edge swipes all dismiss
    // the IME without leaving the obscured composer ready for hardware input.
    // Observe the target rather than isOpen so the keyboard closes alongside
    // the drawer animation, not after it settles.
    LaunchedEffect(drawerState, focusManager) {
        snapshotFlow { drawerState.targetValue }
            .distinctUntilChanged()
            .collect { target ->
                if (target == DrawerValue.Open) {
                    focusManager.clearFocus(force = true)
                }
            }
    }

    // Opening the drawer re-syncs the list — so a session created on another
    // device (or one whose optimistic row was dropped on a profile switch)
    // shows up without a manual reload. Cheap dashboard read; the optimistic
    // row for the active session is preserved by ChatHandler.updateSessions.
    LaunchedEffect(drawerState.isOpen) {
        chatViewModel.setSessionActivityDrawerOpen(drawerState.isOpen)
        if (drawerState.isOpen && chatReady) {
            chatViewModel.refreshSessions()
        }
    }

    var positionedSessionId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentSessionId, isLoadingHistory, messages.isNotEmpty()) {
        if (
            shouldInitiallyPositionConversation(
                positionedSessionId = positionedSessionId,
                currentSessionId = currentSessionId,
                isLoadingHistory = isLoadingHistory,
                hasMessages = messages.isNotEmpty(),
            )
        ) {
            scrollConversationToBottom(animated = false)
            positionedSessionId = currentSessionId
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

    // Structural/new-row transitions request the stable footer during the same
    // remeasure. Completion deliberately does not: Markdown tail promotion is
    // a same-row size change owned only by the measured-delta follower below.
    SideEffect {
        val previous = tailTransitionRef.snapshot
        val streamStarted = tailTransition.isStreaming && previous?.isStreaming != true
        val tailStructureChanged = tailTransition.lastMessageUiKey != null &&
            (previous == null ||
                previous.messageCount != tailTransition.messageCount ||
                previous.lastMessageUiKey != tailTransition.lastMessageUiKey)

        val shouldAnchor = smoothAutoScroll &&
            !isUserDragging &&
            !userScrolledAway &&
            (streamStarted || tailStructureChanged)
        if (shouldAnchor) {
            listState.requestScrollToItem(tailTransition.messageCount + 1)
        }

        tailTransitionRef.snapshot = tailTransition
    }

    // One owner follows every bottom-preserving viewport transition. Tail
    // growth, including Markdown promotion/finalization, advances by its single
    // measured delta, while any ordinary viewport loss
    // (IME, late composer controls, status text, or top chrome hydration)
    // advances by the lost height. This matters on restore: model and effort
    // controls can finish resolving after history has already reached the
    // footer. The voice dock's measured 300 ms anchor transition temporarily
    // owns both follow paths; ordinary late layout correction resumes after it
    // settles. A visible footer supplies the authoritative final distance. No
    // transition replaces the logical item anchor.
    val latestMessages = rememberUpdatedState(messages)
    LaunchedEffect(listState, currentSessionId, smoothAutoScroll) {
        var previousLayout: ChatViewportFollowSnapshot? = null
        snapshotFlow {
            // The effect deliberately survives each streamed list replacement.
            // Read through rememberUpdatedState so its long-lived coroutine does
            // not keep the message list captured when the effect first launched.
            val tail = latestMessages.value.lastOrNull()
            val tailSize = tail?.uiKey?.let { uiKey ->
                listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { item -> item.key == uiKey }
                    ?.size
            }
            ChatViewportFollowSnapshot(
                totalItemsCount = listState.layoutInfo.totalItemsCount,
                tailUiKey = tail?.uiKey,
                tailSizePx = tailSize,
                viewportHeightPx = listState.layoutInfo.viewportSize.height,
                visibleBottomDistancePx = listState.visibleConversationBottomDistancePx(),
                followTailGrowth = !voiceDockAnchorTransitionActive &&
                    !isUserDragging &&
                    smoothAutoScroll &&
                    !userScrolledAway,
                followViewportResize = shouldFollowConversationViewportResize(
                    userScrolledAway = userScrolledAway,
                    userDragging = isUserDragging,
                    imeBottomPx = latestImeBottomPx,
                    followImeResize = followImeResize,
                    voiceDockAnchorTransitionActive = voiceDockAnchorTransitionActive,
                ),
            )
        }
            .distinctUntilChanged()
            .collect { current ->
                val previous = previousLayout
                val scrollPx = ownedBottomFollowScroll(previous, current)
                val correctLateLayout = shouldCorrectConversationBottomAfterLayout(
                    previous = previous,
                    current = current,
                    atExactBottom = listState.isAtConversationBottom(0),
                    userScrolledAway = userScrolledAway,
                    userDragging = isUserDragging,
                    isStreaming = latestMessages.value.lastOrNull()?.isStreaming == true,
                    smoothAutoScroll = smoothAutoScroll,
                    viewportFollowAllowed = current.followViewportResize,
                )
                previousLayout = current
                if (scrollPx > 0) {
                    val viewportHeight = current.viewportHeightPx.coerceAtLeast(1)
                    try {
                        if (scrollPx > viewportHeight) {
                            // A large Markdown/table remeasure or restored turn can
                            // exceed a viewport. Snap once to the stable footer;
                            // never animate across the transcript at a velocity
                            // proportional to its total distance.
                            val lastIndex = listState.layoutInfo.totalItemsCount - 1
                            if (lastIndex >= 0) listState.scrollToItem(lastIndex)
                        } else {
                            listState.scroll(MutatePriority.Default) {
                                var remaining = scrollPx
                                var previousStep = 0
                                while (
                                    remaining > 0 &&
                                    !isUserDragging &&
                                    !userScrolledAway
                                ) {
                                    val step = boundedBottomFollowStep(
                                        remainingPx = remaining,
                                        previousStepPx = previousStep,
                                        viewportHeightPx = viewportHeight,
                                        motionEnabled = animationEnabled &&
                                            accessibleMotion.osAnimations &&
                                            !accessibleMotion.touchExploration,
                                    )
                                    val consumed = scrollBy(step.toFloat()).toInt()
                                    if (consumed <= 0) break
                                    remaining = (remaining - consumed).coerceAtLeast(0)
                                    previousStep = step
                                    if (remaining > 0) withFrameNanos { }
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        // User-input mutations have higher priority and cancel
                        // either follow path immediately. Preserve parent/effect
                        // cancellation, but let a drag merely stop this request
                        // rather than kill the long-lived follow owner.
                        if (!currentCoroutineContext().isActive) throw cancelled
                    }
                } else if (correctLateLayout) {
                    val lastIndex = listState.layoutInfo.totalItemsCount - 1
                    if (lastIndex >= 0) {
                        programmaticBottomScroll = true
                        try {
                            listState.scrollToItem(lastIndex)
                            userScrolledAway = false
                        } catch (cancelled: CancellationException) {
                            if (!currentCoroutineContext().isActive) throw cancelled
                        } finally {
                            programmaticBottomScroll = false
                        }
                    }
                }
            }
    }

    // Completion feedback is presentation-only. The measured-delta collector
    // above is the sole scroll owner for the Markdown tail's final remeasure.
    var observedActiveStream by remember(currentSessionId) { mutableStateOf(false) }
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
    val globalSelectedAgentDisplayName by remember(
        effectiveProfile,
        selectedPersonality,
        defaultPersonality,
        profileDisplayAlias,
        activeConnection?.label,
    ) {
        derivedStateOf {
            AgentDisplay.agentName(
                profile = effectiveProfile,
                selectedPersonality = selectedPersonality,
                defaultPersonality = defaultPersonality,
                connectionLabel = activeConnection?.label,
                localDisplayAlias = profileDisplayAlias,
            )
        }
    }
    val agentDisplayName by remember(
        conversationProfile,
        selectedPersonality,
        defaultPersonality,
        profileDisplayAlias,
        explicitBindingProfileName,
        activeConnection?.label,
    ) {
        derivedStateOf {
            val profile = conversationProfile
            AgentDisplay.agentName(
                profile = profile,
                selectedPersonality = selectedPersonality,
                defaultPersonality = defaultPersonality,
                connectionLabel = activeConnection?.label,
                localDisplayAlias = profileDisplayAlias.takeIf { explicitBindingProfileName == null },
            )
        }
    }
    val selectedProfileKey = AgentDisplay.profileSessionKey(selectedProfile?.name)
    val profileShelfAvailable = !supervised && ProfilePresentationPolicy.shouldShowShelf(
        profiles = agentProfiles,
        presentation = profilePresentation,
        selectedKey = selectedProfileKey,
    )
    val profileSwitchEnabled = com.hermesandroid.relay.ui.components.ProfileShelfPolicy.canSwitch(
        isStreaming = isStreaming,
        streamingEndpoint = chatViewModel.streamingEndpoint,
    )
    LaunchedEffect(profileShelfAvailable) {
        if (!profileShelfAvailable) showProfileShelf = false
    }
    val selectProfileFromShelf: (com.hermesandroid.relay.data.Profile?) -> Unit = { profile ->
        if (AgentDisplay.profileSessionKey(profile?.name) != selectedProfileKey) {
            val profileName = profile?.name
            chatViewModel.selectProfileFromHeader(
                profileName = profileName,
                profile = profile,
                contextKey = AgentDisplay.profileContextKey(
                    connectionId = activeConnection?.id,
                    profileName = profileName,
                ),
            )
        }
    }
    val hasLiveConversationSurface = messages.isNotEmpty() || isStreaming
    val isChatConnecting = chatConnectState == ChatConnectState.Connecting &&
        !hasLiveConversationSurface

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Material routes scrim taps through the drawer's gesture handler.
        // Keep it enabled so tapping outside always dismisses the drawer; the
        // voice overlay already owns input while voice mode is visible.
        gesturesEnabled = !supervised || supervisedPolicy.capabilities.conversationHistory,
        drawerContent = {
            val drawerProfileName = explicitBindingProfileName ?: effectiveProfile?.name
            val drawerTitle = if (drawerProfileName != null) {
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
            val proactiveInboxEntries by connectionViewModel.inboxMessages.collectAsState()
            val phoneThreadChatIds by connectionViewModel.phoneThreadChatIds.collectAsState()
            val provisionalThreadEntries = buildProvisionalThreadRows(
                entries = proactiveInboxEntries,
                activeConnectionId = activeConnection?.id,
                realThreadChatIds = phoneThreadChatIds.values,
            )
            val provisionalThreads = provisionalThreadEntries.map { (chatId, entries) ->
                val latest = entries.maxBy { it.receivedAt }
                ProvisionalThreadRow(
                    chatId = chatId,
                    title = latest.title.ifBlank { "Hermes" },
                    messageCount = entries.size,
                    lastActivityAt = latest.receivedAt,
                )
            }

            SessionDrawerContent(
                sessions = if (
                    supervised && !supervisedPolicy.capabilities.conversationHistory
                ) emptyList() else sessions,
                currentSessionId = currentSessionId,
                scopeTitle = drawerTitle,
                scopeSubtitle = drawerSubtitle,
                activeProfileName = drawerProfileName ?: "default",
                isLoading = isLoadingSessions,
                isOpen = drawerState.isOpen,
                activityStates = sessionActivityStates,
                animationEnabled = animationEnabled,
                autoTitlesSupported = serverAutoTitles,
                archiveSupported = sessionArchivingSupported,
                supervisedSessionActions = supervisedPolicy.capabilities.sessionActions
                    .takeIf { supervised },
                newChatEnabled = !supervised || supervisedPolicy.capabilities.newChat,
                onRefresh = { chatViewModel.refreshSessions() },
                onOpenBotMode = {
                    scope.launch { drawerState.close() }
                    onNavigateToBotMode()
                },
                onNewChat = {
                    if (!supervised || supervisedPolicy.capabilities.newChat) {
                        chatViewModel.createNewChat()
                        scope.launch { drawerState.close() }
                    }
                },
                onSelectSession = { sessionId ->
                    chatViewModel.switchSession(sessionId)
                    scope.launch { drawerState.close() }
                },
                onDeleteSession = { sessionId ->
                    if (supervised && !supervisedPolicy.allowsSessionAction(SupervisedSessionAction.Delete)) {
                        return@SessionDrawerContent
                    }
                    val connectionId = activeConnection?.id
                    val profileId = explicitBindingProfileName ?: selectedProfile?.name
                    chatViewModel.deleteSession(sessionId) {
                        if (!connectionId.isNullOrBlank() && !profileId.isNullOrBlank()) {
                            chatViewModel.removeComposerDraftSession(
                                connectionId,
                                profileId,
                                sessionId,
                            )
                        }
                    }
                },
                onRenameSession = { sessionId, title ->
                    if (supervised && !supervisedPolicy.allowsSessionAction(SupervisedSessionAction.Rename)) {
                        return@SessionDrawerContent
                    }
                    chatViewModel.renameSession(sessionId, title)
                },
                onSetSessionPinned = { sessionId, pinned ->
                    if (!supervised || supervisedPolicy.allowsSessionAction(SupervisedSessionAction.Pin)) {
                        chatViewModel.setSessionPinned(sessionId, pinned)
                    }
                },
                onSetSessionArchived = { sessionId, archived ->
                    if (!supervised || supervisedPolicy.allowsSessionAction(SupervisedSessionAction.Archive)) {
                        chatViewModel.setSessionArchived(sessionId, archived)
                    }
                },
                onCopySessionId = { sessionId ->
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText(copySessionIdLabel, sessionId))
                        )
                        Toast.makeText(
                            context,
                            copiedToClipboardMsg,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                threadsCapabilityActive = threadsCapabilityActive,
                onNewThread = { name ->
                    chatViewModel.startNewThread(name)
                    scope.launch { drawerState.close() }
                },
                provisionalThreads = provisionalThreads,
                onSelectProvisionalThread = { chatId ->
                    chatViewModel.openProactiveThread(
                        chatId,
                        provisionalThreadEntries[chatId].orEmpty(),
                    )
                    scope.launch { drawerState.close() }
                },
                hiddenSources = hiddenSources,
                onToggleSourceHidden = { source, hidden ->
                    connectionViewModel.setSourceHidden(source, hidden)
                },
                allProfilesSupported = !supervised && !isProfileLocked &&
                    !activeConnection?.resolvedDashboardUrl.isNullOrBlank(),
                allProfileSessions = allProfileSessions,
                allProfileSessionsLoading = allProfileSessionsLoading,
                profileColors = profilePresentation.colors,
                onProfileColorChange = connectionViewModel::setProfileColor,
                onRefreshAllProfiles = {
                    if (!isProfileLocked && !allProfileSessionsLoading) scope.launch {
                        refreshAllProfileSessions(showError = true)
                    }
                },
                onSelectProfileSession = { profileName, sessionId ->
                    if (!connectionViewModel.isProfileSelectionAllowed(profileName)) {
                        return@SessionDrawerContent
                    }
                    val target = agentProfiles.firstOrNull {
                        it.name.equals(profileName, ignoreCase = true)
                    }
                    if (target != null || profileName.equals("default", ignoreCase = true)) {
                        val ownerProfile = target ?: allProfileSessions.firstOrNull {
                            it.profile.equals(profileName, ignoreCase = true) &&
                                it.session.sessionId == sessionId
                        }?.session?.let { session ->
                            com.hermesandroid.relay.data.Profile(
                                name = profileName,
                                model = session.model.orEmpty(),
                                description = profileName,
                            )
                        } ?: com.hermesandroid.relay.data.Profile(
                            name = profileName,
                            model = "",
                            description = profileName,
                        )
                        val opened = chatViewModel.openProfileSession(
                            profileName = profileName,
                            profile = ownerProfile,
                            contextKey = AgentDisplay.profileContextKey(
                                connectionId = activeConnection?.id,
                                profileName = profileName,
                            ),
                            sessionId = sessionId,
                        )
                        if (opened) scope.launch { drawerState.close() }
                    } else {
                        scope.launch {
                            snackbarHostState.showSnackbar("Profile $profileName is not available.")
                        }
                    }
                },
                onDeleteProfileSession = { profileName, sessionId ->
                    scope.launch {
                        if (connectionViewModel.deleteSession(profileName, sessionId)) {
                            allProfileSessions = allProfileSessions.filterNot {
                                it.profile == profileName && it.session.sessionId == sessionId
                            }
                            activeConnection?.id?.let { connectionId ->
                                chatViewModel.removeComposerDraftSession(
                                    connectionId,
                                    profileName,
                                    sessionId,
                                )
                            }
                        }
                    }
                },
                onRenameProfileSession = { profileName, sessionId, title ->
                    scope.launch {
                        if (connectionViewModel.renameSession(profileName, sessionId, title)) {
                            allProfileSessions = allProfileSessions.map { row ->
                                if (row.profile == profileName && row.session.sessionId == sessionId) {
                                    row.copy(session = row.session.copy(title = title))
                                } else {
                                    row
                                }
                            }
                        }
                    }
                },
                onSetProfileSessionPinned = { profileName, sessionId, pinned ->
                    scope.launch {
                        if (connectionViewModel.setSessionPinned(profileName, sessionId, pinned)) {
                            allProfileSessions = allProfileSessions.map { row ->
                                if (row.profile == profileName && row.session.sessionId == sessionId) {
                                    row.copy(session = row.session.copy(pinned = pinned))
                                } else {
                                    row
                                }
                            }
                        }
                    }
                },
                onSetProfileSessionArchived = { profileName, sessionId, archived ->
                    scope.launch {
                        if (connectionViewModel.setSessionArchived(profileName, sessionId, archived)) {
                            allProfileSessions = allProfileSessions.map { row ->
                                if (row.profile == profileName && row.session.sessionId == sessionId) {
                                    row.copy(session = row.session.copy(archived = archived))
                                } else {
                                    row
                                }
                            }
                        }
                    }
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
                    if (!supervised || supervisedPolicy.capabilities.conversationHistory) {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.cd_sessions))
                        }
                    } else if (supervisedPolicy.capabilities.newChat) {
                        IconButton(onClick = { chatViewModel.createNewChat() }) {
                            Icon(Icons.Filled.Edit, contentDescription = "New chat")
                        }
                    }
                },
                title = {
                    // Resolve the same Gateway-first, API-fallback runtime
                    // model used elsewhere; Relay is intentionally unrelated
                    // to Chat health.
                    val chatRuntimeStatus = resolveChatRuntimeStatus(
                        gateway = when (chatGatewayAvailability) {
                            GatewayAvailability.Ready -> ChatTransportReadiness.Ready
                            GatewayAvailability.Unknown -> if (
                                activeConnection?.resolvedDashboardUrl.isNullOrBlank()
                            ) ChatTransportReadiness.NotConfigured else ChatTransportReadiness.Connecting
                            GatewayAvailability.SignInRequired,
                            GatewayAvailability.Unreachable,
                            GatewayAvailability.Unsupported -> ChatTransportReadiness.Unavailable
                        },
                        apiSse = when {
                            apiReachable -> ChatTransportReadiness.Ready
                            activeConnection?.apiServerUrl.isNullOrBlank() -> ChatTransportReadiness.NotConfigured
                            chatMode != ChatMode.DISCONNECTED -> ChatTransportReadiness.Connecting
                            else -> ChatTransportReadiness.Unavailable
                        },
                    )
                    val headerChatReady = chatRuntimeStatus is ChatRuntimeStatus.Connected || isStreaming
                    val isConnecting = isChatConnecting || chatRuntimeStatus is ChatRuntimeStatus.Connecting
                    // Once we've been connected this session, a later drop reads as
                    // "Reconnecting…" (we had it, we're getting it back) rather than
                    // a first-time "Connecting…". Honest wording for the WhatsApp-
                    // style subtitle status.
                    var everConnected by remember { mutableStateOf(false) }
                    if (headerChatReady) everConnected = true
                    val showStreamingState = isStreaming &&
                        (!supervised || supervisedVisibility.showWorkingStatus)
                    val statusText = when {
                        headerChatReady -> if (showStreamingState) {
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
                        headerChatReady -> Color(0xFF4CAF50)
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
                    val modelName = AgentDisplay.displayModelName(sessionModelState.model)
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
                    val subtitleText = if (!headerChatReady) {
                        statusText
                    } else if (supervised) {
                        buildList {
                            if (supervisedVisibility.showProfileName) {
                                conversationProfile?.name?.takeIf { it.isNotBlank() }?.let(::add)
                            }
                            if (supervisedVisibility.showModelName && !modelName.isNullOrBlank()) add(modelName)
                            if (isEmpty() && supervisedVisibility.showConnectionStatus) add(statusText)
                        }.joinToString(" · ")
                    } else {
                        resolveChatHeaderSubtitle(
                            isStreaming = isStreaming,
                            statusText = statusText,
                            personalityName = nonDefaultPersonality,
                            modelName = modelName,
                        )
                    }
                    val subtitleColor = if (headerChatReady) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        statusColor
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .clickable(enabled = !supervised) {
                                if (profileShelfAvailable) {
                                    showProfileShelf = !showProfileShelf
                                } else {
                                    showAgentInfo = true
                                }
                            }
                            .semantics {
                                contentDescription = if (profileShelfAvailable) {
                                    context.getString(
                                        if (showProfileShelf) {
                                            R.string.profile_shelf_collapse
                                        } else {
                                            R.string.profile_shelf_expand
                                        },
                                    )
                                } else {
                                    context.getString(R.string.profile_shelf_open_passport)
                                }
                            }
                    ) {
                        // Avatar — a plain 40dp circle whose letter swaps to the
                        // active agent (profile or personality). No overlay ring:
                        // the letter itself is the indicator.
                        if (!supervised || supervisedVisibility.showAgentIdentity) Box(modifier = Modifier.size(40.dp)) {
                            Surface(
                                modifier = Modifier.size(40.dp),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                if (isChatConnecting) {
                                    ChatConnectingAvatarGlyph()
                                } else {
                                    // While row selection and persistence
                                    // converge, the header already belongs to
                                    // the explicit binding owner, including its icon.
                                    val agentIconPath = if (explicitBindingProfileName != null) {
                                        explicitBindingProfileIconPath
                                    } else {
                                        LocalAgentIconPath.current
                                    }
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
                            if (!supervised || supervisedVisibility.showConnectionStatus) {
                                ConnectionStatusBadge(
                                    isConnected = headerChatReady,
                                    isConnecting = isConnecting,
                                    modifier = Modifier
                                        .size(10.dp)
                                        .align(Alignment.BottomEnd),
                                    size = 10.dp,
                                )
                            }
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
                                            text = if (supervised && !supervisedVisibility.showAgentIdentity) {
                                                stringResource(R.string.screen_chat_label)
                                            } else if (agentDisplayName.isNotBlank()) {
                                                agentDisplayName
                                            } else {
                                                stringResource(R.string.chat_agent_default)
                                            },
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
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text(
                                                text = line,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = subtitleColor,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            )
                                            if (showStreamingState && animationEnabled) {
                                                StreamingDots(
                                                    color = subtitleColor,
                                                    modifier = Modifier.clearAndSetSemantics { },
                                                )
                                            }
                                        }
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
                    if (!supervised && yoloEnabled == true) {
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
                    if (!supervised) {
                        if (showGitWorkspaceContextEntry) {
                            ChatGitContextButton(
                                onClick = onNavigateToGitWorkspace,
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                        RelayChromeIconButton(
                            icon = Icons.Filled.Code,
                            contentDescription = stringResource(R.string.cd_terminal),
                            onClick = onNavigateToTerminal,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
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
                    // Session identity is useful before the first message; sharing only appears
                    // once the conversation has content.
                    if (
                        (!supervised && (messages.isNotEmpty() || !currentSessionId.isNullOrBlank())) ||
                        (supervised && messages.isNotEmpty() &&
                            supervisedPolicy.allowsSessionAction(SupervisedSessionAction.ShareTranscript))
                    ) {
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
                                currentSessionId?.takeIf { !supervised && it.isNotBlank() }?.let { sessionId ->
                                    DropdownMenuItem(
                                        text = { Text(copySessionIdLabel) },
                                        leadingIcon = {
                                            Icon(Icons.Filled.ContentCopy, contentDescription = null)
                                        },
                                        onClick = {
                                            showOverflowMenu = false
                                            scope.launch {
                                                clipboard.setClipEntry(
                                                    ClipEntry(
                                                        ClipData.newPlainText(
                                                            copySessionIdLabel,
                                                            sessionId,
                                                        )
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
                                if (!supervised && messages.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.chat_search_conversation)) },
                                        leadingIcon = {
                                            Icon(Icons.Filled.Search, contentDescription = null)
                                        },
                                        onClick = {
                                            showOverflowMenu = false
                                            showTranscriptSearch = true
                                        },
                                    )
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
                                } else if (
                                    messages.isNotEmpty() &&
                                    supervisedPolicy.allowsSessionAction(
                                        SupervisedSessionAction.ShareTranscript,
                                    )
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.chat_share_conversation)) },
                                        leadingIcon = {
                                            Icon(Icons.Filled.Share, contentDescription = null)
                                        },
                                        onClick = {
                                            showOverflowMenu = false
                                            shareConversation(context, messages)
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = RelayRefresh.Background.copy(alpha = 0.96f)
                )
            )
            AnimatedVisibility(visible = profileShelfAvailable && showProfileShelf) {
                ProfileShelf(
                    connectionViewModel = connectionViewModel,
                    profiles = agentProfiles,
                    selectedProfile = selectedProfile,
                    resolvedProfile = effectiveProfile,
                    presentation = profilePresentation,
                    activeDisplayName = globalSelectedAgentDisplayName,
                    isProfileLocked = isProfileLocked,
                    lockedProfileName = lockedProfileName,
                    switchEnabled = profileSwitchEnabled,
                    onSelect = selectProfileFromShelf,
                    onOpenPassport = { showAgentInfo = true },
                    onOpenSwitcher = { showProfileSwitcher = true },
                    onInspect = onNavigateToProfileInspector,
                    onLock = { profile ->
                        connectionViewModel.lockProfile(profile)
                        chatViewModel.activateGatewayProfile(profile)
                    },
                    onUnlock = connectionViewModel::unlockProfile,
                    onHide = { connectionViewModel.setProfileHidden(it, true) },
                )
            }
            // Per-session context-window gauge at the seam between the app bar
            // and the mode strip — slim bar + `NN% · used/max` token readout,
            // color-graded by fullness. Composes to nothing until the server
            // reports a context_max for the session.
            if (!supervised || supervisedVisibility.showUsage) {
                ContextMeterBar(
                    usedFraction = contextUsage,
                    usedTokens = contextWindow?.usedTokens,
                    maxTokens = contextWindow?.maxTokens,
                    onClick = if (supervised) null else ({ showContextSheet = true }),
                )
            }
            if (!supervised && showContextSheet) {
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
                    if (supervised && targetConnectState != ChatConnectState.Ready) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (supervisedVisibility.showConnectionStatus) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    if (targetConnectState == ChatConnectState.Connecting) {
                                        CircularProgressIndicator()
                                    }
                                    Text(
                                        text = if (targetConnectState == ChatConnectState.Connecting) {
                                            stringResource(R.string.chat_connecting_dots)
                                        } else {
                                            stringResource(R.string.chat_disconnected_label)
                                        },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    } else if (targetConnectState == ChatConnectState.Connecting) {
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
                        gatewayAvailability = chatGatewayAvailability,
                        dashboardRouteMovedHint = dashboardRouteMovedHint,
                        onNavigateToManage = onNavigateToManage,
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
                            if (
                                LocalBackgroundVisualizationEnabled.current &&
                                (!supervised || supervisedVisibility.showAgentIdentity)
                            ) {
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
                                            paused = !animationEnabled,
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
                                        if (
                                            effectiveProfile != null &&
                                            (!supervised || supervisedVisibility.showAgentIdentity)
                                        ) {
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
                            if (
                                targetConnectState == ChatConnectState.Ready &&
                                profileBlurb != null &&
                                (!supervised || supervisedVisibility.showAgentIdentity)
                            ) {
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
                ) {
                    // Ambient avatar behind messages
                    if (
                        LocalBackgroundVisualizationEnabled.current &&
                        (!supervised || supervisedVisibility.showAgentIdentity) &&
                        animationBehindChat
                    ) {
                        LocalAgentAvatar.current.Render(
                            state = AvatarRenderState(
                                state = sphereState,
                                intensity = streamingIntensity,
                                toolCallBurst = toolCallBurst,
                                paused = !animationEnabled,
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
                    val relayServerImageResolver = remember(
                        chatViewModel,
                        supervised,
                        supervisedPolicy.capabilities.generatedImages,
                    ) {
                        if (supervised && !supervisedPolicy.capabilities.generatedImages) null
                        else RelayServerImageResolver { path -> chatViewModel.resolveServerImage(path) }
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
                    val petVisitTargetUiKey = remember(messages) {
                        newestPetVisitTargetUiKey(messages)
                    }
                    val petPerchUiKey = remember(messages) {
                        newestPetPerchUiKey(messages)
                    }
                    val petJourneyPerchUiKeys = remember(messages) {
                        petPerchUiKeys(messages)
                    }
                    val visibleMessageKeys by remember(listState) {
                        derivedStateOf {
                            listState.layoutInfo.visibleItemsInfo
                                .mapTo(mutableSetOf<Any>()) { it.key }
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp)
                            .padding(top = if (showTranscriptSearch) 112.dp else 0.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        item { Spacer(modifier = Modifier.height(8.dp).animateItem()) }

                        // `id` can legitimately change once after a Gateway turn:
                        // the history reconcile adopts the persisted server id.
                        // Keep Compose identity stable across that data update so
                        // LazyColumn retains the visible row and its scroll anchor.
                        items(messages.size, key = { messages[it].uiKey }) { index ->
                            val message = messages[index]
                            val processNotification = message.hermesProcessNotificationOrNull()
                                ?.takeIf { !supervised || supervisedVisibility.showToolNames }

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
                            if (
                                (!supervised || supervisedVisibility.showTimestamps) &&
                                (index == 0 || !isSameDay(messages[index - 1].timestamp, message.timestamp))
                            ) {
                                DateSeparator(timestamp = message.timestamp)
                            }

                            val hasBackgroundTask = message.backgroundTask != null
                            val shouldRenderBubble =
                                !hasBackgroundTask ||
                                    message.content.isNotBlank() ||
                                    message.thinkingContent.isNotBlank() ||
                                    message.attachments.isNotEmpty() ||
                                    message.cards.isNotEmpty()

                            message.backgroundTask
                                ?.takeIf { !supervised || supervisedVisibility.showWorkingStatus }
                                ?.let { task ->
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
                                    petVisitTargetKey = if (
                                        message.uiKey == petVisitTargetUiKey &&
                                        message.uiKey in visibleMessageKeys
                                    ) {
                                        "chat-message:${message.uiKey}"
                                    } else {
                                        null
                                    },
                                    petPerchKey = if (
                                        message.uiKey in petJourneyPerchUiKeys &&
                                        message.uiKey in visibleMessageKeys
                                    ) {
                                        val prefix = if (message.role == MessageRole.USER) {
                                            CHAT_PET_USER_MESSAGE_PERCH_PREFIX
                                        } else {
                                            CHAT_PET_ASSISTANT_MESSAGE_PERCH_PREFIX
                                        }
                                        val marker = if (message.uiKey == petPerchUiKey) {
                                            ""
                                        } else {
                                            CHAT_PET_STEP_MESSAGE_MARKER
                                        }
                                        "$prefix$marker${message.uiKey}"
                                    } else {
                                        null
                                    },
                                    maxBubbleWidth = maxBubbleWidth,
                                    showThinking = showThinking,
                                    showAgentIdentity = !supervised || supervisedVisibility.showAgentIdentity,
                                    showTimestamps = !supervised || supervisedVisibility.showTimestamps,
                                    showWorkingStatus = !supervised || supervisedVisibility.showWorkingStatus,
                                    showUsage = !supervised || supervisedVisibility.showUsage,
                                    showTechnicalBadges = !supervised || supervisedVisibility.showTechnicalRoute,
                                    showAssistantImages = !supervised || supervisedPolicy.capabilities.generatedImages,
                                    allowAssistantImageExport = !supervised ||
                                        supervisedPolicy.capabilities.shareGeneratedImages,
                                    isFirstInGroup = isFirstInGroup,
                                    isLastInGroup = isLastInGroup,
                                    recoveringAnswer = recoveringAnswer,
                                    imageGenerationStylePreference = imageGenerationStyle,
                                    imageGenerationRotationIndex =
                                        imageGenerationOrdinals[message.uiKey] ?: 0,
                                    onAttachmentRetry = { msgId, idx ->
                                        chatViewModel.manualFetchAttachment(msgId, idx)
                                    },
                                    onAttachmentManualFetch = { msgId, idx ->
                                        chatViewModel.manualFetchAttachment(msgId, idx)
                                    },
                                    onCardAction = if (supervised) ({ _, _, _ -> }) else handleCardAction,
                                    onCardInput = if (supervised) ({ _, _, _ -> }) else handleCardInput,
                                    onSessionReference = if (supervised) null else { reference ->
                                        val target = agentProfiles.firstOrNull {
                                            it.name.equals(reference.profile, ignoreCase = true)
                                        }
                                        if (target != null) {
                                            connectionViewModel.selectProfile(target)
                                            chatViewModel.activateGatewayProfile(target)
                                            chatViewModel.switchSession(reference.sessionId)
                                        } else {
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    message = "Profile ${reference.profile} is not available.",
                                                    duration = SnackbarDuration.Short,
                                                )
                                            }
                                        }
                                    },
                                    onReact = if (
                                        !supervised &&
                                        isGatewayTransport &&
                                        messageReactionsSupported &&
                                        !message.isStreaming &&
                                        (
                                            message.rowId != null ||
                                                message.uiKey in newestReactableMessageKeys
                                        )
                                    ) {
                                        { emoji -> chatViewModel.reactToMessage(message, emoji) }
                                    } else {
                                        null
                                    },
                                    onEditMessage = if (
                                        (!supervised || supervisedPolicy.capabilities.editAndResend) &&
                                        isGatewayTransport &&
                                        !isStreaming &&
                                        message.role == MessageRole.USER &&
                                        !message.id.startsWith("voice-intent-") &&
                                        !message.id.startsWith("steer-")
                                    ) {
                                        { msg ->
                                            val envelope = parseChatQuotedPrompt(msg.content)
                                            editingMessage = msg
                                            inputText = (envelope?.body ?: msg.content).take(charLimit)
                                            quotedMessage = envelope?.reference?.messageId?.let { id ->
                                                messages.firstOrNull { it.id == id }
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                    animationEnabled = animationEnabled,
                                    onQuoteMessage = if (
                                        !supervised || supervisedPolicy.capabilities.quoteReplies
                                    ) {
                                        { quoted ->
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            quotedMessage = quoted
                                        }
                                    } else null,
                                    onNavigateToMessage = { messageId ->
                                        val targetIndex = messages.indexOfFirst { it.id == messageId }
                                        if (targetIndex >= 0) {
                                            userScrolledAway = reduceUserScrolledAway(
                                                current = userScrolledAway,
                                                event = ChatFollowEvent.UserMovedAway,
                                            )
                                            scope.launch { listState.animateScrollToItem(targetIndex + 1) }
                                        }
                                    },
                                    onSpeakMessage = if (
                                        chatSpeakResponseActionsEnabled &&
                                        (!supervised || supervisedPolicy.capabilities.voice)
                                    ) {
                                        { text -> voiceViewModel.speakResponse(text) }
                                    } else {
                                        null
                                    },
                                    onStopSpeaking = if (responseSpeechActive) {
                                        { voiceViewModel.stopResponseSpeech() }
                                    } else {
                                        null
                                    },
                                    onCopyMessage = { text ->
                                        if (supervised && !supervisedPolicy.capabilities.copyResponses) {
                                            return@MessageBubble
                                        }
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

                            // Legacy steered sends live inside a server-side tool
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

                            if (!hasBackgroundTask) {
                                // Subagent children (taskIndex != null) group
                                // into lanes after the top-level transcript
                                // activity; the null group retains source order
                                // while routine calls collapse into runs.
                                val laneGroups = message.toolCalls.groupBy { it.taskIndex }
                                val transcriptTools = groupTranscriptTools(laneGroups[null].orEmpty())
                                transcriptTools.forEachIndexed { itemIndex, item ->
                                    when (item) {
                                        is ToolTranscriptItem.ActivityRun -> {
                                            if (item.isVisibleForToolDisplay(toolDisplay)) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                ToolActivityRun(
                                                    calls = item.calls,
                                                    live = item.calls.any { !it.isComplete } ||
                                                        (message.isStreaming && itemIndex == transcriptTools.lastIndex),
                                                    detailed = toolDisplay == "detailed",
                                                    messageTimestamp = message.timestamp,
                                                    petObstacleKey = "chat-tools:${message.uiKey}:${item.calls.first().uiKey}",
                                                    onExpandedChange = { expanded ->
                                                        if (expanded && isStreaming) {
                                                            userScrolledAway = true
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                        is ToolTranscriptItem.Standalone -> {
                                            // Generated media owns its lifecycle inside the
                                            // message bubble. Every other standalone call is
                                            // attention-bearing and stays visible even when
                                            // ordinary tool scaffolding is Off.
                                            if (!item.call.showsImageGenerationPlaceholder()) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                ToolProgressCard(
                                                    toolCall = item.call,
                                                    messageTimestamp = message.timestamp,
                                                    onExpandedChange = { expanded ->
                                                        if (expanded && isStreaming) {
                                                            userScrolledAway = true
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                                // Delegated work is a lifecycle surface, not
                                // optional diagnostic scaffolding. Keep lanes
                                // visible in Off, Compact, and Detailed modes.
                                laneGroups.keys.filterNotNull().sorted().forEach { taskIndex ->
                                    Spacer(modifier = Modifier.height(4.dp))
                                    SubagentLane(
                                        taskIndex = taskIndex,
                                        calls = laneGroups.getValue(taskIndex),
                                        onSteer = chatViewModel::steerSubagent,
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

                    if (showTranscriptSearch) {
                        TranscriptSearchNavigator(
                            messages = messages,
                            onJumpToMessage = { uiKey ->
                                val messageIndex = messages.indexOfFirst { it.uiKey == uiKey }
                                if (messageIndex >= 0) {
                                    userScrolledAway = reduceUserScrolledAway(
                                        current = userScrolledAway,
                                        event = ChatFollowEvent.UserMovedAway,
                                    )
                                    scope.launch { listState.animateScrollToItem(messageIndex + 1) }
                                }
                            },
                            onClose = { showTranscriptSearch = false },
                            strings = TranscriptNavigatorStrings(
                                searchPlaceholder = stringResource(R.string.chat_search_conversation),
                                previousMatch = stringResource(R.string.term_search_cd_previous),
                                nextMatch = stringResource(R.string.term_search_cd_next),
                                closeSearch = stringResource(R.string.chat_search_close),
                                noResults = stringResource(R.string.chat_search_no_results),
                                resultCount = { current, total ->
                                    context.getString(R.string.chat_search_result_count, current, total)
                                },
                            ),
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .zIndex(10f),
                        )
                    }

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
                        Box(
                            modifier = Modifier
                                // The complete control envelope is forbidden
                                // terrain. Registering it as a perch invited
                                // the pet onto the button and let sibling
                                // composer routes treat it as walkable terrain.
                                .width(72.dp)
                                .height(48.dp)
                                .petObstacleSurface(
                                    key = CHAT_SCROLL_TO_BOTTOM_PET_OBSTACLE,
                                    routes = CHAT_PET_ROUTES,
                                ),
                            contentAlignment = Alignment.Center,
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
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .petObstacleSurface(
                            key = CHAT_AUTOCOMPLETE_PET_OBSTACLE,
                            routes = CHAT_PET_ROUTES,
                        )

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
                        .padding(horizontal = 20.dp, vertical = 2.dp)
                        .petObstacleSurface(
                            key = CHAT_RECENT_PROMPTS_PET_OBSTACLE,
                            routes = CHAT_PET_ROUTES,
                        ),
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

            val quoteYouLabel = stringResource(R.string.chat_quote_you)
            val quoteHermesLabel = stringResource(R.string.chat_quote_hermes)
            val activeQuoteReference = remember(
                quotedMessage?.id,
                quotedMessage?.content,
                quotedMessage?.role,
                quoteYouLabel,
                quoteHermesLabel,
            ) {
                quotedMessage?.let { quoted ->
                    val visibleContent = parseChatQuotedPrompt(quoted.content)?.body ?: quoted.content
                    ChatQuoteReference(
                        messageId = quoted.id,
                        authorLabel = if (quoted.role == MessageRole.USER) {
                            quoteYouLabel
                        } else {
                            quoteHermesLabel
                        },
                        excerpt = compactQuoteExcerpt(visibleContent),
                    )
                }
            }
            AnimatedVisibility(visible = activeQuoteReference != null) {
                activeQuoteReference?.let { reference ->
                    ChatQuoteReferenceChip(
                        reference = reference,
                        onOpenOriginal = {
                            val index = messages.indexOfFirst { it.id == reference.messageId }
                            if (index >= 0) {
                                userScrolledAway = reduceUserScrolledAway(
                                    current = userScrolledAway,
                                    event = ChatFollowEvent.UserMovedAway,
                                )
                                scope.launch { listState.animateScrollToItem(index + 1) }
                            }
                        },
                        onRemove = { quotedMessage = null },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            PendingAttachmentComposer(
                attachments = pendingAttachments,
                onPreview = { attachment, _ -> pendingAttachmentPreview = attachment },
                onRemove = chatViewModel::removeAttachment,
                onMove = chatViewModel::moveAttachment,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            pendingAttachmentPreview?.let { attachment ->
                AttachmentViewer(
                    attachment = attachment,
                    onDismiss = { pendingAttachmentPreview = null },
                    initiallyRevealed = true,
                )
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
                            quotedMessage = null
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
            // Send / Voice / Stop / Correct / Queue. "+" taps the file picker,
            // long-press opens the CommandPalette (the dedicated "/" button
            // is gone — typing "/" still surfaces InlineAutocomplete).
            val hasContent = inputText.isNotBlank() || pendingAttachments.isNotEmpty()
            // Gateway redirect is text-only. Attachment-bearing follow-ups must
            // retain their files in the session-owned queue instead of showing
            // a correction action that cannot carry them.
            val canSteerCurrentMessage = steerableTurn && pendingAttachments.isEmpty() &&
                (!supervised || supervisedPolicy.capabilities.steerResponse)
            val trailing = when {
                !isStreaming && hasContent -> ChatInputTrailing.SEND
                !isStreaming -> ChatInputTrailing.VOICE
                isStreaming && !hasContent -> ChatInputTrailing.STOP
                canSteerCurrentMessage -> ChatInputTrailing.STEER
                else -> ChatInputTrailing.QUEUE
            }
            val inputCaption = when {
                isStreaming && hasContent && canSteerCurrentMessage ->
                    stringResource(R.string.chat_sends_now)
                isStreaming && hasContent -> stringResource(R.string.chat_delivered_after_turn)
                isStreaming && steerNotice != null -> steerNotice
                else -> null
            }
            val inputPlaceholder = when {
                editingMessage != null -> stringResource(R.string.chat_placeholder_edit)
                isStreaming && canSteerCurrentMessage -> stringResource(R.string.chat_placeholder_steer)
                isStreaming -> stringResource(R.string.chat_placeholder_queue)
                else -> stringResource(R.string.chat_placeholder_message)
            }
            val editBusyMessage = stringResource(R.string.chat_edit_busy_snackbar)
            val stoppedMessage = stringResource(R.string.chat_stopped_snackbar)
            val attachmentPlaceholder = stringResource(R.string.chat_attachment_placeholder)
            val largePasteAttachedMessage = stringResource(R.string.chat_large_paste_attached)
            val largePasteTooLargeMessage = stringResource(
                R.string.chat_large_paste_too_large,
                maxAttachmentMb,
            )
            val sseModelOptions = remember(apiModelOptions, agentProfiles, selectedModelOverride) {
                (apiModelOptions +
                    agentProfiles.mapNotNull { profile ->
                        AgentDisplay.requestModelName(profile.model)?.let { ApiModelOption(it) }
                    } +
                    listOfNotNull(AgentDisplay.requestModelName(selectedModelOverride)?.let { ApiModelOption(it) }))
                    .distinctBy { it.id }
            }
            val currentModelForInput = AgentDisplay.displayModelName(sessionModelState.model)
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
            val serverDefaultLabel = stringResource(R.string.chat_server_default)
            val notOnPlanLabel = stringResource(R.string.chat_not_on_plan)
            val needsSetupLabel = stringResource(R.string.chat_needs_setup)
            val modelDefaultLabel = stringResource(R.string.chat_model_label)
            val modelPickerOptions = remember(
                modelProviders,
                sseModelOptions,
                selectedModelOverride,
                selectedProviderOverride,
                sessionModelState,
                sessionPickerProvider,
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
                                selected = sessionModelState.inheritsProfileDefault,
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
                                            selected = sessionModelState.pickerModel == model &&
                                                sessionPickerProvider.equals(provider.slug, ignoreCase = true),
                                            enabled = !unavailable,
                                        ),
                                    )
                                }
                            }
                            val providerModelIds = modelProviders.flatMap { it.models }.toSet()
                            sseModelOptions.filter { it.id !in providerModelIds }.forEach { model ->
                                add(
                                    ChatInputPickerOption(
                                        label = AgentDisplay.displayModelName(model.id) ?: model.id,
                                        value = model.id,
                                        group = "Routes",
                                        secondary = model.routeDetail,
                                        selected = sessionModelState.pickerModel == model.id,
                                    ),
                                )
                            }
                        } else {
                            sseModelOptions.forEach { model ->
                                add(
                                    ChatInputPickerOption(
                                        label = AgentDisplay.displayModelName(model.id) ?: model.id,
                                        value = model.id,
                                        secondary = model.routeDetail,
                                        selected = sessionModelState.pickerModel == model.id,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            val modelControl = modelPickerOptions.takeIf { !supervised && it.isNotEmpty() }?.let {
                ChatInputPickerControl(
                    value = compactModelChipLabel(currentModelForInput, modelDefaultLabel),
                    contentDescription = stringResource(R.string.cd_select_model),
                    options = it,
                    enabled = chatReady && !isStreaming && it.size > 1,
                )
            }
            val normalizedEffort = normalizeReasoningEffortForInput(selectedReasoningEffort)
            // Reads the same provider/model resolver used by session.create.
            // Collected identity flows above keep this synchronous view reactive.
            val effortAvailability = remember(
                selectedModelOverride,
                selectedProviderOverride,
                gatewayCurrentModel,
                gatewayCurrentProvider,
                modelProviders,
                apiModelOptions,
                reasoningCapabilityRevision,
            ) {
                chatViewModel.reasoningEffortAvailability()
            }
            val effortPickerOptions = effortAvailability.choices.map { effort ->
                    ChatInputPickerOption(
                        label = reasoningEffortLabel(effort),
                        value = effort,
                        selected = selectedReasoningEffort != null && effort == normalizedEffort,
                    )
            }
            val effortPickerSubtitle = when {
                effortAvailability.exact &&
                    selectedReasoningEffort != null &&
                    normalizedEffort !in effortAvailability.choices -> stringResource(
                        R.string.reasoning_effort_current_outside_supported,
                        reasoningEffortLabel(normalizedEffort),
                        effortPickerOptions.joinToString { it.label },
                    )
                !effortAvailability.exact ->
                    stringResource(R.string.reasoning_effort_standard_levels_notice)
                else -> null
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
            val effortControl = if (
                !supervised &&
                chatGatewayAvailability != GatewayAvailability.Unreachable &&
                effortAvailability.supported != false &&
                effortPickerOptions.isNotEmpty()
            ) {
                ChatInputPickerControl(
                    value = selectedReasoningEffort?.let { reasoningEffortLabel(it) }
                        ?: stringResource(R.string.conn_info_server_default),
                    contentDescription = stringResource(R.string.chat_select_reasoning_effort),
                    options = effortPickerOptions,
                    enabled = isGatewayTransport && chatReady && !isStreaming,
                )
            } else {
                null
            }

            visibleChatFailure?.let { failure ->
                val displayFailure = if (!supervised) failure else failure.copy(
                    model = failure.model.takeIf { supervisedVisibility.showModelName },
                    provider = failure.provider.takeIf { supervisedVisibility.showTechnicalRoute },
                )
                val failureRouteLabel = if (
                    supervised && !supervisedVisibility.showTechnicalRoute
                ) "" else when (failure.route) {
                    ChatFailureRoute.GATEWAY ->
                        stringResource(R.string.chat_failure_route_gateway)
                    ChatFailureRoute.API_FALLBACK ->
                        stringResource(R.string.chat_failure_route_api)
                    null -> ""
                }
                ChatFailurePanel(
                    failure = displayFailure,
                    routeLabel = failureRouteLabel,
                    onDetails = { showChatFailureDetails = true },
                    onRetry = {
                        if (!supervised || supervisedPolicy.capabilities.retryResponse) {
                            chatViewModel.retryLastMessage()
                        }
                    },
                    onDismiss = chatViewModel::dismissChatFailure,
                    showDetails = !supervised || supervisedVisibility.showTechnicalRoute,
                )
                if (showChatFailureDetails) {
                    ChatFailureDetailsDialog(
                        failure = displayFailure,
                        routeLabel = failureRouteLabel,
                        onCopy = {
                            val details = buildString {
                                append(failureRouteLabel)
                                displayFailure.provider?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                                displayFailure.model?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                                append("\n\n")
                                append(displayFailure.rawError)
                            }
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText("Hermes response failure", details)),
                                )
                                snackbarHostState.showSnackbar(
                                    message = context.getString(R.string.chat_copied_to_clipboard),
                                    duration = SnackbarDuration.Short,
                                )
                            }
                        },
                        onDismiss = { showChatFailureDetails = false },
                    )
                }
            }

            visibleGitWorkspaceSummary?.let { summary ->
                ChatGitWorkspaceRail(
                    summary = summary,
                    onClick = onNavigateToGitWorkspace,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            ChatInputBar(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = inputPlaceholder,
                trailing = trailing,
                onSend = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val outboundText = buildChatQuotedPrompt(
                        inputText.ifBlank { attachmentPlaceholder },
                        activeQuoteReference,
                    )
                    val editing = editingMessage
                    if (editing != null) {
                        // Only drop the edit state once the rewind actually
                        // dispatched — a silent gate must not eat the text.
                        if (chatViewModel.regenerateFromMessage(editing.id, outboundText)) {
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
                        chatViewModel.sendMessage(outboundText)
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
                                        context.getString(R.string.chat_voice_needs_route)
                                },
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    )
                },
                onStop = {
                    if (supervised && !supervisedPolicy.capabilities.cancelResponse) {
                        return@ChatInputBar
                    }
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
                    val allowed = !supervised || (
                        supervisedPolicy.capabilities.attachments &&
                            SupervisedAttachmentCategory.Images in
                            supervisedPolicy.capabilities.attachmentCategories &&
                            pendingAttachments.size < supervisedPolicy.capabilities.attachmentMaxCount
                        )
                    if (allowed) {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                },
                onAttachFiles = {
                    if (!supervised || supervisedPolicy.capabilities.attachments) {
                        val mimeTypes = if (!supervised) arrayOf("*/*") else buildList {
                            val categories = supervisedPolicy.capabilities.attachmentCategories
                            if (SupervisedAttachmentCategory.Images in categories) add("image/*")
                            if (SupervisedAttachmentCategory.Audio in categories) add("audio/*")
                            if (SupervisedAttachmentCategory.Video in categories) add("video/*")
                            if (SupervisedAttachmentCategory.Documents in categories) {
                                add("text/*")
                                add("application/pdf")
                            }
                        }.toTypedArray()
                        if (mimeTypes.isNotEmpty()) filePickerLauncher.launch(mimeTypes)
                    }
                },
                onAttachCamera = if (!supervised || (
                        supervisedPolicy.capabilities.attachments &&
                            SupervisedAttachmentCategory.Images in
                            supervisedPolicy.capabilities.attachmentCategories
                        )) requestCameraCapture else ({ }),
                onPasteImage = if (!supervised || (
                        supervisedPolicy.capabilities.attachments &&
                            SupervisedAttachmentCategory.Images in
                            supervisedPolicy.capabilities.attachmentCategories
                        )) pasteImageFromClipboard else ({ }),
                onLongPressAttach = { if (!supervised) showCommandPalette = true },
                charLimit = charLimit,
                caption = turnStatus ?: inputCaption,
                voiceReady = voiceReady,
                showVoiceHint = !voiceHintSeen && !conversationVoiceDockVisible,
                onVoiceHintShown = { connectionViewModel.setVoiceHintSeen(true) },
                isDarkTheme = isDarkTheme,
                physicalEnterSends = physicalKeyboardEnterBehavior ==
                    PhysicalKeyboardEnterBehavior.SendMessage,
                submitEnabled = pendingAttachments.none {
                    it.state == com.hermesandroid.relay.data.AttachmentState.LOADING
                },
                largePasteThreshold = LARGE_PASTE_THRESHOLD_CHARS.takeIf {
                    convertLargePastesToAttachments && (!supervised || (
                        supervisedPolicy.capabilities.attachments &&
                            SupervisedAttachmentCategory.Documents in
                            supervisedPolicy.capabilities.attachmentCategories
                        ))
                },
                onLargePaste = { pastedText ->
                    val owner = activeComposerDraftKey ?: composerDraftKey
                    val sizeBytes = pastedText.toByteArray(Charsets.UTF_8).size.toLong()
                    val maxBytes = maxAttachmentMb.toLong() * 1024L * 1024L
                    if (sizeBytes > maxBytes) {
                        scope.launch {
                            snackbarHostState.showSnackbar(largePasteTooLargeMessage)
                        }
                    } else {
                        val composerId = UUID.randomUUID().toString()
                        val placeholder = Attachment(
                            contentType = "text/plain; charset=utf-8",
                            content = "",
                            fileName = "pasted-text.txt",
                            fileSize = sizeBytes,
                            state = com.hermesandroid.relay.data.AttachmentState.LOADING,
                            isLargePaste = true,
                            composerId = composerId,
                            composerRawText = pastedText,
                        )
                        if (activeComposerDraftKey == owner) {
                            chatViewModel.addAttachment(placeholder)
                        }
                        scope.launch {
                            val attachment = withContext(Dispatchers.Default) {
                                largePasteAttachment(pastedText, composerId)
                            }
                            if (activeComposerDraftKey == owner) {
                                chatViewModel.replaceAttachment(composerId, attachment)
                            } else {
                                chatViewModel.composerDraftStore.update(owner) { draft ->
                                    draft.copy(
                                        attachments = draft.attachments
                                            .filterNot { it.composerId == composerId } + attachment,
                                    )
                                }
                            }
                            snackbarHostState.showSnackbar(largePasteAttachedMessage)
                        }
                    }
                },
                modelControl = modelControl,
                onModelOptionSelected = { option ->
                    if (option.provider == null && apiModelOptions.any { it.id == option.value }) {
                        option.value?.let(chatViewModel::selectApiModel)
                    } else {
                        chatViewModel.selectModel(option.value, option.provider)
                    }
                },
                effortControl = effortControl,
                onEffortPickerClick = { showEffortSheet = true },
                topContent = {
                    ConversationVoiceDock(
                        uiState = voiceUiState,
                        engineMode = voiceStats.voiceEngineMode,
                        provider = activeVoiceProvider,
                        model = activeVoiceModel,
                        voice = activeVoiceName,
                        profileName = AgentDisplay.profileDisplayName(effectiveProfile),
                        outputEnabled = activeVoiceEnabled,
                        onMicTap = { voiceViewModel.startListening() },
                        onMicRelease = { voiceViewModel.stopListening() },
                        onInterrupt = { voiceViewModel.interruptSpeaking() },
                        onPauseAutoMode = { voiceViewModel.pauseContinuousMode() },
                        onModeChange = { voiceViewModel.setInteractionMode(it) },
                        onFocusRequest = {
                            setVoicePresentationMode(VoicePresentationMode.Focus)
                        },
                        onOverlayRequest = showVoiceSystemOverlay,
                        systemOverlayAvailable = voiceSystemOverlayAvailable,
                        onOpenSettings = onNavigateToVoiceSettings,
                        onExit = { voiceViewModel.exitVoiceMode() },
                    )
                },
                topContentVisible = conversationVoiceDockVisible,
                suppressVoiceTrailing = conversationVoiceDockVisible,
                // Measure the visible composer Surface as a Desktop-style
                // ledge. Registering the outer input column includes its 6dp
                // visual margin and makes a correctly grounded pet look raised.
                surfaceModifier = Modifier.petPerchSurface(
                    key = CHAT_PET_WALK_REGION,
                    routes = CHAT_PET_ROUTES,
                ),
                enabled = chatReady,
                onModelPickerClick = { showModelSheet = true },
            )

            if (showModelSheet) {
                ModelPickerSheet(
                    options = modelPickerOptions,
                    refreshing = modelOptionsRefreshing,
                    onRefresh = {
                        chatViewModel.refreshModelOptions(refresh = true, catalogOnly = true)
                    },
                    onSelect = { option ->
                        showModelSheet = false
                        if (option.provider == null && apiModelOptions.any { it.id == option.value }) {
                            option.value?.let(chatViewModel::selectApiModel)
                        } else {
                            chatViewModel.selectModel(option.value, option.provider)
                        }
                    },
                    onDismiss = { showModelSheet = false },
                )
            }
            modelSelectionConfirmation?.let { confirmation ->
                AlertDialog(
                    onDismissRequest = chatViewModel::dismissModelSelectionConfirmation,
                    title = { Text(stringResource(R.string.chat_model_confirmation_title)) },
                    text = { Text(confirmation.message) },
                    confirmButton = {
                        TextButton(onClick = chatViewModel::confirmModelSelection) {
                            Text(stringResource(R.string.cw_continue))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = chatViewModel::dismissModelSelectionConfirmation) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    },
                )
            }
            if (showEffortSheet) {
                OptionPickerSheet(
                    title = stringResource(R.string.chat_select_reasoning_effort),
                    subtitle = effortPickerSubtitle,
                    options = effortPickerOptions.map { option ->
                        option.copy(enabled = effortControl?.enabled == true)
                    },
                    onSelect = { option ->
                        showEffortSheet = false
                        option.value?.let(chatViewModel::selectReasoningEffort)
                    },
                    onDismiss = { showEffortSheet = false },
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
                shape = appearanceRoundedCornerShape(12.dp),
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
                voiceProfileName = AgentDisplay.profileDisplayName(effectiveProfile),
                voiceOutputEnabled = activeVoiceEnabled,
                voiceOutputFallbackEnabled = voiceOutputConfig?.fallback_enabled,
                presentationMode = effectiveVoicePresentationMode,
                onPresentationModeChange = setVoicePresentationMode,
                onOverlayRequest = showVoiceSystemOverlay,
                systemOverlayAvailable = voiceSystemOverlayAvailable,
                // Gear button in the overlay's expanded controls. The overlay
                // exits voice mode before invoking this, so navigation lands
                // on Voice Settings with no overlay left on top.
                onOpenSettings = onNavigateToVoiceSettings,
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
                onCardAction = handleCardAction,
                onCardInput = handleCardInput,
                // === END v0.4.1 ===
            )
        }
        } // end Box
    }

    // Command palette bottom sheet
    if (showCommandPalette && !supervised) {
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
    if (showAgentInfo && !supervised) {
        AgentInfoSheet(
            connectionViewModel = connectionViewModel,
            chatViewModel = chatViewModel,
            onDismiss = { showAgentInfo = false },
            onNavigateToConnections = onNavigateToConnections,
            onNavigateToProfileInspector = onNavigateToProfileInspector,
        )
    }
    if (showProfileSwitcher) {
        ProfileSwitcherSheet(
            connectionViewModel = connectionViewModel,
            profiles = agentProfiles,
            selectedProfile = selectedProfile,
            resolvedProfile = effectiveProfile,
            presentation = profilePresentation,
            isProfileLocked = isProfileLocked,
            switchEnabled = profileSwitchEnabled,
            onSelect = selectProfileFromShelf,
            onManageDisplay = {
                showProfileSwitcher = false
                showProfileManager = true
            },
            onDismiss = { showProfileSwitcher = false },
        )
    }
    if (showProfileManager) {
        ProfileDisplayManagerDialog(
            profiles = agentProfiles,
            presentation = profilePresentation,
            selectedProfileName = selectedProfile?.name,
            onMove = connectionViewModel::moveProfile,
            onHiddenChange = connectionViewModel::setProfileHidden,
            onReset = connectionViewModel::resetProfilePresentation,
            onDismiss = { showProfileManager = false },
        )
    }
}

internal fun buildProvisionalThreadRows(
    entries: List<ProactiveInboxEntry>,
    activeConnectionId: String?,
    realThreadChatIds: Collection<String>,
): Map<String, List<ProactiveInboxEntry>> = entries
    .filter { it.connectionId == null || it.connectionId == activeConnectionId }
    .groupBy { it.chatId ?: "phone" }
    .filterKeys { it !in realThreadChatIds }

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
    gatewayAvailability: GatewayAvailability,
    dashboardRouteMovedHint: String?,
    onNavigateToManage: () -> Unit,
    onNavigateToConnections: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val commands = remember(
        connectionLabel,
        chatMode,
        apiReachable,
        chatReady,
        isLoadingHistory,
        isLoadingSessions,
    ) {
        buildChatLoadingCommands(
            context = context,
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
        if (LocalBackgroundVisualizationEnabled.current) {
            LocalAgentAvatar.current.Render(
                state = AvatarRenderState(
                    state = SphereState.Thinking,
                    intensity = streamingIntensity.coerceAtLeast(0.18f),
                    toolCallBurst = toolCallBurst,
                    paused = !animationEnabled,
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

        val dashboardSignInRequired =
            gatewayAvailability == GatewayAvailability.SignInRequired && !apiReachable
        if (dashboardSignInRequired) {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.dashboard_signin_required_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = dashboardRouteMovedHint?.let { route ->
                            stringResource(R.string.dashboard_signin_route_hint, route)
                        } ?: stringResource(R.string.chat_settings_gateway_needs_signin_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onNavigateToManage,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.voice_settings_sign_in_via_manage))
                    }
                }
            }
        } else {
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
}

private fun buildChatLoadingCommands(
    context: android.content.Context,
    connectionLabel: String?,
    chatMode: ChatMode,
    apiReachable: Boolean,
    chatReady: Boolean,
    isLoadingHistory: Boolean,
    isLoadingSessions: Boolean,
): List<ChatLoadingCommand> {
    val hasConnection = !connectionLabel.isNullOrBlank()
    val chatModeDetail = when (chatMode) {
        ChatMode.ENHANCED_HERMES -> context.getString(R.string.chat_stream_sessions)
        ChatMode.PORTABLE -> context.getString(R.string.chat_stream_portable)
        ChatMode.DISCONNECTED -> "waiting"
    }
    return listOf(
        ChatLoadingCommand(
            state = if (hasConnection) ChatLoadingCommandState.Done else ChatLoadingCommandState.Active,
            command = "/state restore",
            detail = if (hasConnection) context.getString(R.string.chat_config_active) else context.getString(R.string.chat_config_loading),
        ),
        ChatLoadingCommand(
            state = when {
                hasConnection -> ChatLoadingCommandState.Done
                else -> ChatLoadingCommandState.Pending
            },
            command = "/route resolve",
            detail = connectionLabel?.takeIf { it.isNotBlank() } ?: context.getString(R.string.chat_selecting_route),
        ),
        ChatLoadingCommand(
            state = when {
                apiReachable -> ChatLoadingCommandState.Done
                hasConnection -> ChatLoadingCommandState.Active
                else -> ChatLoadingCommandState.Pending
            },
            command = "/hermes ping",
            detail = if (apiReachable) "online" else context.getString(R.string.chat_contacting_server),
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
            shape = appearanceRoundedCornerShape(18.dp),
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
            .clip(appearanceRoundedCornerShape(10.dp))
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
            shape = appearanceRoundedCornerShape(18.dp),
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

private fun compactQuoteExcerpt(content: String): String =
    content.trim().replace(Regex("\\s+"), " ").take(180)

/**
 * Read an attachment from a content [uri], enforce the [maxAttachmentMb] cap,
 * base64-encode the bytes, and hand a built [Attachment] to [onAttachment].
 * Shared by the Files / Photos / Camera launchers and clipboard paste so the
 * read → cap → encode → add pipeline lives in exactly one place. Best-effort:
 * surfaces a Toast and returns on any failure (unreadable stream, over-cap)
 * rather than throwing. [mimeOverride] lets clipboard paste supply the MIME
 * when the resolver can't (some providers don't resolve a type until read).
 */
private suspend fun ingestAttachmentFromUri(
    context: android.content.Context,
    uri: Uri,
    maxAttachmentMb: Int,
    mimeOverride: String? = null,
    onAttachment: (Attachment) -> Unit,
) {
    try {
        val resolver = context.contentResolver
        val maxSize = maxAttachmentMb.toLong() * 1024L * 1024L
        val source = withContext(Dispatchers.IO) {
            val mimeType = mimeOverride ?: resolver.getType(uri) ?: "application/octet-stream"
            val fileName = resolveDisplayName(resolver, uri)
            val payload = resolver.openInputStream(uri)?.use { input ->
                readBase64Bounded(input, maxSize)
            } ?: return@withContext null
            RawAttachmentSource(mimeType, fileName, payload.base64, payload.sizeBytes)
        } ?: return
        onAttachment(
            Attachment(
                contentType = source.mimeType,
                content = source.base64,
                fileName = source.fileName,
                fileSize = source.sizeBytes,
            )
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: AttachmentTooLargeException) {
        Toast.makeText(
            context,
            context.getString(R.string.chat_file_too_large, maxAttachmentMb),
            Toast.LENGTH_SHORT,
        ).show()
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.chat_failed_read_file), Toast.LENGTH_SHORT).show()
    }
}

private data class RawAttachmentSource(
    val mimeType: String,
    val fileName: String,
    val base64: String,
    val sizeBytes: Long,
)

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
private suspend fun ingestClipboardImage(
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

/** Chat overlays that temporarily own input and must not compete with the root pet host. */
internal fun shouldHideChatPet(
    voiceMode: Boolean = false,
    drawerOpenOrMoving: Boolean = false,
    commandPaletteVisible: Boolean = false,
    modelSheetVisible: Boolean = false,
    effortSheetVisible: Boolean = false,
    contextSheetVisible: Boolean = false,
    backgroundProcessesVisible: Boolean = false,
    agentInfoVisible: Boolean = false,
): Boolean = voiceMode ||
    drawerOpenOrMoving ||
    commandPaletteVisible ||
    modelSheetVisible ||
    effortSheetVisible ||
    contextSheetVisible ||
    backgroundProcessesVisible ||
    agentInfoVisible

private fun compactModelChipLabel(model: String?, defaultLabel: String): String {
    val raw = model?.trim().orEmpty()
    if (raw.isBlank()) return defaultLabel
    val label = raw.substringAfterLast('/').ifBlank { raw }
    return if (label.length <= 18) label else label.take(15).trimEnd() + "..."
}

private fun normalizeReasoningEffortForInput(value: String?): String {
    return ReasoningEfforts.normalize(value)
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
            shape = appearanceRoundedCornerShape(12.dp),
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

internal fun shouldOfferChatSpeakAction(
    voiceReady: Boolean,
    voiceState: VoiceState,
): Boolean = voiceReady && voiceState == VoiceState.Idle
