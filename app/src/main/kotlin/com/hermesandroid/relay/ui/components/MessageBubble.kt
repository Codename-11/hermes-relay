package com.hermesandroid.relay.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import com.hermesandroid.relay.ui.theme.LocalBrand
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.BlurMode
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.HermesCardAction
import com.hermesandroid.relay.data.MediaSettingsRepository
import com.hermesandroid.relay.data.MessageDeliveryStatus
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.data.parseChatQuotedPrompt
import com.hermesandroid.relay.ui.components.pet.petObstacleSurface
import com.hermesandroid.relay.ui.components.pet.petPerchSurface
import com.hermesandroid.relay.ui.components.pet.petVisitTargetSurface
import com.hermesandroid.relay.ui.theme.leftEdgeGlow
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date

internal const val CHAT_PET_IDENTITY_OBSTACLE_PREFIX = "chat-message-identity:"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    maxBubbleWidth: Dp = 300.dp,
    showThinking: Boolean = true,
    isFirstInGroup: Boolean = true,
    isLastInGroup: Boolean = true,
    /**
     * Keeps the current live tail on its stable Text layout while a final
     * streaming frame commits. The owning list releases it immediately after
     * completion so the same row transitions to full Markdown with its bottom
     * anchor preserved.
     */
    retainStreamingLayout: Boolean = false,
    onCopyMessage: (String) -> Unit = {},
    /**
     * Select this message as a structured composer quote. Null hides Quote.
     */
    onQuoteMessage: ((ChatMessage) -> Unit)? = null,
    /** Navigate a rendered quote chip to its original message id. */
    onNavigateToMessage: ((String) -> Unit)? = null,
    /**
     * Reads a completed assistant response through the active voice renderer.
     * Null hides the entry; the owning screen uses that to limit the action to
     * idle Conversation voice sessions.
     */
    onSpeakMessage: ((String) -> Unit)? = null,
    /** Stops a message-context narration currently owned by the voice pipeline. */
    onStopSpeaking: (() -> Unit)? = null,
    /**
     * Invoked when the user taps a FAILED inbound attachment card.
     * `attachmentIndex` is the position in [ChatMessage.attachments] so the
     * ViewModel can re-fetch the exact placeholder that needs re-trying.
     */
    onAttachmentRetry: (messageId: String, attachmentIndex: Int) -> Unit = { _, _ -> },
    /**
     * Invoked when the user taps a LOADING+"Tap to download" placeholder
     * (the cellular deferral case).
     */
    onAttachmentManualFetch: (messageId: String, attachmentIndex: Int) -> Unit = { _, _ -> },
    /**
     * Invoked when the user taps an action button on an inline
     * [com.hermesandroid.relay.data.HermesCard]. Routed through
     * [com.hermesandroid.relay.viewmodel.ChatViewModel.dispatchCardAction]
     * by the owning screen — that path records the dispatch stamp (so the
     * card collapses) and forwards the action value per its mode.
     * Defaults to no-op so legacy callers / tests don't have to wire it.
     */
    onCardAction: (messageId: String, cardKey: String, action: HermesCardAction) -> Unit = { _, _, _ -> },
    /**
     * Invoked when the user submits a card's interactive input slot (the
     * gateway ask cards — clarify answer, secret value, sudo confirm).
     * Routed to [com.hermesandroid.relay.viewmodel.ChatViewModel.answerAsk]
     * by ChatScreen; defaults to no-op for legacy callers.
     */
    onCardInput: (messageId: String, cardKey: String, value: String) -> Unit = { _, _, _ -> },
    /**
     * "Edit & resend" entry in the USER-bubble long-press menu — gateway
     * transport only (the only path that supports rewinding the server
     * conversation). Null hides the entry.
     */
    onEditMessage: ((ChatMessage) -> Unit)? = null,
    /**
     * True while the ViewModel is recovering a dropped stream's answer by
     * polling the session transcript (issue #166) — the streaming
     * placeholder's slow-turn label reads "Reconnecting to your answer…"
     * instead of "Still working…" so the wait is honest about what's
     * happening.
     */
    recoveringAnswer: Boolean = false,
    imageGenerationStylePreference: String = "rotate",
    imageGenerationRotationIndex: Int = 0,
    petVisitTargetKey: String? = null,
    petPerchKey: String? = null,
    animationEnabled: Boolean = true,
) {
    val isUser = message.role == MessageRole.USER
    val isSystem = message.role == MessageRole.SYSTEM

    // Phone/voice-origin action bubble marker.
    //
    // Voice mode (sideload classifier → RealVoiceBridgeIntentHandler) emits
    // these with `agentName = "Voice action"` and an id prefixed
    // `voice-intent-action-*` / `voice-intent-result-*`. Chat mode parity
    // (ChatHandler.onToolCallComplete for android_* tools) emits them with
    // `agentName = "Phone action"`. We match on either so a single render
    // branch applies the accent to both origins.
    //
    // Chosen marker: subtle thin vertical accent bar on the leading edge of
    // the bubble in colorScheme.tertiary, so an action bubble is
    // immediately distinguishable from a regular LLM reply when they
    // interleave in the scrollback. Subtle on purpose — the content still
    // carries the signal, the bar just flags "this was a phone control
    // action, not LLM narration".
    val isActionBubble = !isUser && !isSystem && (
        message.agentName == "Voice action" ||
            message.agentName == "Phone action" ||
            message.id.startsWith("voice-intent-")
    )

    val backgroundColor = when {
        message.role == MessageRole.USER -> MaterialTheme.colorScheme.primary
        message.role == MessageRole.SYSTEM -> MaterialTheme.colorScheme.tertiaryContainer
        isActionBubble -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    val textColor = when (message.role) {
        MessageRole.USER -> MaterialTheme.colorScheme.onPrimary
        MessageRole.ASSISTANT -> MaterialTheme.colorScheme.onSurface
        MessageRole.SYSTEM -> MaterialTheme.colorScheme.onTertiaryContainer
    }

    // Grouped bubble shapes — flat edges where consecutive messages meet
    val topStart = if (isUser) { if (isFirstInGroup) 16.dp else 16.dp } else { if (isFirstInGroup) 16.dp else 4.dp }
    val topEnd = if (isUser) { if (isFirstInGroup) 16.dp else 4.dp } else { if (isFirstInGroup) 16.dp else 16.dp }
    val bottomStart = if (isUser) 16.dp else 4.dp  // tail side always small
    val bottomEnd = if (isUser) 4.dp else 16.dp     // tail side always small

    val bubbleShape = when (message.role) {
        MessageRole.USER -> RoundedCornerShape(topStart, topEnd, bottomEnd, bottomStart)
        MessageRole.ASSISTANT -> RoundedCornerShape(topStart, topEnd, bottomEnd, bottomStart)
        MessageRole.SYSTEM -> RoundedCornerShape(12.dp)
    }

    val alignment = if (isUser) Alignment.End else Alignment.Start
    val locale = LocalLocale.current.platformLocale
    val timeFormat = remember(locale) { SimpleDateFormat("h:mm a", locale) }
    val quoteEnvelope = remember(message.content) { parseChatQuotedPrompt(message.content) }
    val visibleMessageContent = quoteEnvelope?.body ?: message.content
    val a11yDescription =
        "${message.role.name.lowercase()} message: ${visibleMessageContent.take(100)}"
    val isDarkTheme = LocalBrand.current.isDark

    // Pull generated/inline image links (`![alt](src)`) out of assistant
    // content so they render as real images (remote URLs via Coil) or a
    // graceful inline notice — not the blank element the markdown renderer
    // emits for an image link. User/system bubbles keep their raw content.
    val (markdownBody, inlineImages) = remember(visibleMessageContent, isUser, isSystem) {
        if (isUser || isSystem) {
            visibleMessageContent to emptyList()
        } else {
            extractChatInlineImages(visibleMessageContent)
        }
    }
    val showImageGeneration = shouldShowImageGenerationPlaceholder(
        toolCalls = message.toolCalls,
        isStreaming = message.isStreaming,
        hasMediaResult = message.attachments.isNotEmpty() || inlineImages.isNotEmpty(),
    )
    val hasImageGenerationCall = remember(message.toolCalls) {
        message.toolCalls.any {
            it.name.trim().lowercase() == "image_generate"
        }
    }
    val imageGenerationStartMillis = remember(message.toolCalls) {
        imageGenerationStartedAt(message.toolCalls)
    }
    val imageGenerationVisualStyle = remember(
        imageGenerationStylePreference,
        imageGenerationRotationIndex,
    ) {
        resolveImageGenerationVisualStyle(
            preference = imageGenerationStylePreference,
            rotationIndex = imageGenerationRotationIndex,
        )
    }

    // Provide the sensitive-media blur mode to the attachment / inline-image
    // renderers below, sourced as locally as possible (here, not threaded
    // through ChatScreen). One collector per visible bubble — DataStore
    // shares the underlying read, and the static default (FLAGGED) keeps
    // behavior safe until the first emission lands.
    val context = LocalContext.current
    val blurRepo = remember(context) { MediaSettingsRepository(context.applicationContext) }
    val blurMode by blurRepo.blurMode.collectAsState(initial = BlurMode.FLAGGED)

    CompositionLocalProvider(LocalMediaBlurMode provides blurMode) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        // Keep sender identity in the first-message label rather than a
        // persistent leading column. Long responses and every follow-up in the
        // group therefore retain the full bubble-width allowance.
        if (!isUser && !isSystem && isFirstInGroup && !message.agentName.isNullOrBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .padding(bottom = 3.dp, start = 4.dp)
                    .petObstacleSurface(
                        key = "$CHAT_PET_IDENTITY_OBSTACLE_PREFIX${message.uiKey}",
                        routes = setOf("chat"),
                    ),
            ) {
                Surface(
                    // Decorative: the adjacent visible agent name already owns
                    // the identity announcement, avoiding duplicate TalkBack copy.
                    modifier = Modifier.size(24.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    AgentAvatarFace(
                        name = message.agentName.orEmpty(),
                        letterStyle = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    text = localizeAgentName(message.agentName),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (!isUser && !isSystem && message.badges.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .padding(bottom = 4.dp, start = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                message.badges.take(4).forEach { badge ->
                    MessagePathBadge(
                        text = localizeBadge(badge),
                        // Speaker glyph = the shared "spoken" modality marker.
                        // Both the standard voice-mode chip ("Voice") and the
                        // realtime engine chip ("Realtime Agent") are spoken
                        // turns, so they share it; only the text differs.
                        leadingIcon = if (badge == "Voice" || badge == "Realtime Agent") {
                            Icons.AutoMirrored.Filled.VolumeUp
                        } else {
                            null
                        },
                    )
                }
            }
        }

        // Thinking block (above the bubble, only for assistant messages)
        if (!isUser && showThinking && message.thinkingContent.isNotBlank()) {
            ThinkingBlock(
                thinkingContent = message.thinkingContent,
                isStreaming = message.isThinkingStreaming,
                timestamp = message.timestamp,
                petObstacleKey = "chat-thinking:${message.uiKey}",
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .padding(bottom = 4.dp)
            )
        }

        if (!isUser && !isSystem && showThinking) {
            message.moaReferences.forEach { reference ->
                ThinkingBlock(
                    thinkingContent = if (reference.available) {
                        reference.text
                    } else {
                        stringResource(R.string.bubble_advisor_unavailable)
                    },
                    isStreaming = false,
                    petObstacleKey = "chat-thinking:${message.uiKey}:advisor:${reference.index}",
                    headerText = buildString {
                        append(stringResource(R.string.bubble_advisor_prefix))
                        append(reference.index)
                        reference.count?.let { append("/").append(it) }
                        append(" · ")
                        append(reference.label)
                    },
                    accessibilityLabel = stringResource(R.string.bubble_moa_advisor),
                    modifier = Modifier
                        .widthIn(max = maxBubbleWidth)
                        .padding(bottom = 4.dp),
                )
            }
        }

        // Message bubble.
        //
        // Action bubbles (voice/phone origin) wrap the existing Surface in
        // a Row with a thin leading tertiary-colored accent bar. The bar
        // is rendered as a separate Box so it hugs the bubble's left edge
        // regardless of content height (tall bubbles with multi-line
        // markdown stretch the bar via fillMaxHeight + IntrinsicSize).
        //
        // Suppress an otherwise-empty assistant bubble: a message that
        // carries only thinking and/or tool calls (both rendered OUTSIDE
        // this Surface — the ThinkingBlock above, the tool pills as separate
        // rows) would otherwise paint a bare timestamp-only chip between the
        // Thought-process block and the tool pill. Keep the bubble while
        // streaming (StreamingDots is the live "working" indicator) and
        // whenever there are cards/attachments to render inside it.
        val showBubble = isUser || isSystem ||
            visibleMessageContent.isNotBlank() ||
            quoteEnvelope != null ||
            message.isStreaming ||
            showImageGeneration ||
            message.cards.isNotEmpty() ||
            message.attachments.isNotEmpty() ||
            inlineImages.isNotEmpty()
        if (showBubble) {
        Row(
            modifier = Modifier.widthIn(max = maxBubbleWidth),
            verticalAlignment = Alignment.Top,
        ) {
            if (isActionBubble) {
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp, bottom = 8.dp, end = 6.dp)
                        .width(3.dp)
                        .height(if (visibleMessageContent.isBlank()) 14.dp else 24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f))
                )
            }
        Column(horizontalAlignment = alignment) {
        // A normal tap reveals the compact action strip. Long-press preserves
        // the existing overflow menu (or direct-copy shortcut when Copy is the
        // only available action).
        var showMessageActions by remember { mutableStateOf(false) }
        var showInlineActions by remember(message.uiKey) { mutableStateOf(false) }
        val haptic = LocalHapticFeedback.current
        val accessibleMotion = rememberAccessibleMotionState()
        val animateInlineActions = animationEnabled && accessibleMotion.osAnimations &&
            !accessibleMotion.touchExploration
        val showEditAction = onEditMessage != null && isUser
        val showSpeakAction = shouldShowSpeakResponseAction(message, onSpeakMessage != null)
        val showStopSpeakingAction = shouldShowStopSpeakingAction(message, onStopSpeaking != null)
        if (onQuoteMessage != null || showEditAction || showSpeakAction || showStopSpeakingAction) {
            DropdownMenu(
                expanded = showMessageActions,
                onDismissRequest = { showMessageActions = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.msg_bubble_copy)) },
                    onClick = {
                        showMessageActions = false
                        onCopyMessage(visibleMessageContent)
                    },
                )
                if (onQuoteMessage != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.msg_bubble_quote)) },
                        onClick = {
                            showMessageActions = false
                            onQuoteMessage(message.copy(content = visibleMessageContent))
                        },
                    )
                }
                if (showSpeakAction) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.msg_bubble_speak_response)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            showMessageActions = false
                            onSpeakMessage?.invoke(visibleMessageContent)
                        },
                    )
                }
                if (showStopSpeakingAction) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.msg_bubble_stop_speaking)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Stop,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            showMessageActions = false
                            onStopSpeaking?.invoke()
                        },
                    )
                }
                if (showEditAction) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.msg_bubble_edit)) },
                        onClick = {
                            showMessageActions = false
                            onEditMessage(message)
                        },
                    )
                }
            }
        }
        Surface(
            shape = bubbleShape,
            color = backgroundColor,
            modifier = Modifier
                .then(
                    if (!isUser && !isSystem &&
                        (message.isStreaming || retainStreamingLayout)
                    ) {
                        // The frame-paced text node is already measured at its
                        // new size. Animate and clip the owning surface so a
                        // newly wrapped line is revealed inside the expanding
                        // bubble instead of drawing below the previous bounds
                        // for one frame. TopStart keeps existing prose fixed.
                        Modifier.animateContentSize(
                            animationSpec = tween(
                                durationMillis = 72,
                                easing = LinearOutSlowInEasing,
                            ),
                            alignment = Alignment.TopStart,
                        )
                    } else {
                        Modifier
                    }
                )
                .then(
                    if (!isUser && !isSystem && isDarkTheme) {
                        Modifier.leftEdgeGlow(
                            alpha = 0.12f,
                            width = 28.dp,
                            isDarkTheme = true
                        )
                    } else Modifier
                )
                .combinedClickable(
                    onClick = { showInlineActions = !showInlineActions },
                    onLongClick = {
                        // Buzz the instant the long-press registers — opening the
                        // action menu is the discoverability moment, so it gets the
                        // same tactile confirm every chat app fires.
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (
                            onQuoteMessage != null || showEditAction || showSpeakAction ||
                            showStopSpeakingAction
                        ) {
                            showMessageActions = true
                        } else {
                            onCopyMessage(visibleMessageContent)
                        }
                    }
                )
                .semantics { contentDescription = a11yDescription }
                .then(
                    if (petVisitTargetKey != null) {
                        Modifier.petVisitTargetSurface(
                            key = petVisitTargetKey,
                            routes = setOf("chat"),
                        )
                    } else {
                        Modifier
                    }
                )
                .then(
                    if (petPerchKey != null) {
                        Modifier.petPerchSurface(
                            key = petPerchKey,
                            routes = setOf("chat"),
                        )
                    } else {
                        Modifier
                    }
                )
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                quoteEnvelope?.let { envelope ->
                    ChatQuoteReferenceChip(
                        reference = envelope.reference,
                        onOpenOriginal = onNavigateToMessage?.let { navigate ->
                            { navigate(envelope.reference.messageId) }
                        },
                        modifier = Modifier.padding(bottom = 7.dp),
                    )
                }
                val messageTextContent: @Composable () -> Unit = {
                    if (isUser || isSystem) {
                        // Plain text for user and system messages
                        Text(
                            text = visibleMessageContent,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 15.sp,
                                lineHeight = 21.sp,
                            ),
                            color = textColor
                        )
                    } else {
                        // Keep one plain Text node stable only while content is
                        // incomplete (plus the final committed live frame).
                        // Completion releases this flag and selects the full
                        // Markdown tree on the same stable message row.
                        if (markdownBody.isNotEmpty()) {
                            StreamingMarkdownContent(
                                content = markdownBody,
                                textColor = textColor,
                                isStreaming = message.isStreaming || retainStreamingLayout,
                            )
                        }
                    }
                }
                // Compose's SelectionManager assumes that selectable IDs
                // captured by a drag remain registered. Reset its owner when
                // a live Text node becomes a Markdown tree, or settled
                // Markdown content changes its node topology, so a handle
                // cannot keep pointing at a removed selectable.
                if (showSpeakAction || showStopSpeakingAction) {
                    DisableSelection { messageTextContent() }
                } else {
                    key(
                        messageSelectionTopologyKey(
                            isPlainText = isUser || isSystem,
                            isStreaming = message.isStreaming,
                            retainStreamingLayout = retainStreamingLayout,
                            markdownBody = markdownBody,
                        ),
                    ) {
                        SelectionContainer { messageTextContent() }
                    }
                }

                // Inline generated images (assistant only) — rendered OUTSIDE
                // the SelectionContainer (they're not selectable text). Remote
                // http(s) URLs load via Coil; server-local paths and load
                // failures degrade to a notice that says why, instead of a
                // blank space.
                if (!isUser && !isSystem && inlineImages.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    ChatInlineImages(
                        images = inlineImages,
                        maxWidth = maxBubbleWidth - 24.dp,
                    )
                }

                // Rich cards — rendered between the markdown body and
                // attachments so the reading order stays: narration → card
                // → attached file. Each card gets a stable key built from
                // its optional id or falling back to its positional index,
                // so a reload-from-history doesn't lose "I already chose X"
                // state tracked in [ChatMessage.cardDispatches].
                if (!isUser && !isSystem && message.cards.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    message.cards.forEachIndexed { index, card ->
                        val cardKey = card.id ?: "idx:$index"
                        HermesCardBubble(
                            card = card,
                            cardKey = cardKey,
                            dispatches = message.cardDispatches,
                            onActionTap = { key, action ->
                                onCardAction(message.id, key, action)
                            },
                            onInputSubmit = { key, value ->
                                onCardInput(message.id, key, value)
                            },
                            maxWidth = maxBubbleWidth - 24.dp,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }

                // Image generation owns the bubble's progress slot. Keep the
                // selected progress treatment mounted under the real result,
                // then reveal the same collapsible attachment surface without
                // rebuilding the surrounding message bubble.
                if (hasImageGenerationCall) {
                    Spacer(modifier = Modifier.height(4.dp))
                    ImageGenerationResultTransition(
                        generating = showImageGeneration,
                        startedAtMillis = imageGenerationStartMillis,
                        visualStyle = imageGenerationVisualStyle,
                    ) {
                        if (message.attachments.isNotEmpty()) {
                            CollapsibleAttachmentGroup(
                                messageKey = message.uiKey,
                                attachments = message.attachments,
                            ) {
                                val attachmentItems = attachmentLayoutItems(message.attachments)
                                attachmentItems.forEach { item ->
                                    when (item) {
                                        is AttachmentLayoutItem.Gallery -> AttachmentGallery(
                                            attachments = item.attachmentIndices.map(message.attachments::get),
                                            maxWidth = maxBubbleWidth - 24.dp,
                                            modifier = Modifier.padding(vertical = 2.dp),
                                        )
                                        is AttachmentLayoutItem.Single -> {
                                            val index = item.attachmentIndex
                                            InboundAttachmentCard(
                                                attachment = message.attachments[index],
                                                onRetry = { onAttachmentRetry(message.id, index) },
                                                onManualFetch = { onAttachmentManualFetch(message.id, index) },
                                                maxWidth = maxBubbleWidth - 24.dp,
                                                modifier = Modifier.padding(vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (message.attachments.isNotEmpty()) {
                        // Two or more loaded images collapse into one grid +
                        // swipe-across gallery. Every other item stays on the
                        // unified attachment path, retaining original indices.
                        Spacer(modifier = Modifier.height(4.dp))
                        CollapsibleAttachmentGroup(
                            messageKey = message.uiKey,
                            attachments = message.attachments,
                        ) {
                            // Two or more loaded images collapse into one grid +
                            // swipe-across gallery. Every other item stays on the
                            // unified attachment path, retaining original indices.
                            val attachmentItems = attachmentLayoutItems(message.attachments)
                            attachmentItems.forEach { item ->
                                when (item) {
                                    is AttachmentLayoutItem.Gallery -> AttachmentGallery(
                                        attachments = item.attachmentIndices.map(message.attachments::get),
                                        maxWidth = maxBubbleWidth - 24.dp,
                                        modifier = Modifier.padding(vertical = 2.dp),
                                    )
                                    is AttachmentLayoutItem.Single -> {
                                        val index = item.attachmentIndex
                                        InboundAttachmentCard(
                                            attachment = message.attachments[index],
                                            onRetry = { onAttachmentRetry(message.id, index) },
                                            onManualFetch = { onAttachmentManualFetch(message.id, index) },
                                            maxWidth = maxBubbleWidth - 24.dp,
                                            modifier = Modifier.padding(vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                }

                // Streaming indicator — only while awaiting the first token. Once
                // text starts flowing, the growing reply is itself the progress
                // signal, so the pulsing dots stop (Messenger/Telegram drop the
                // typing bubble the moment content appears) instead of throbbing
                // under the text for the whole turn.
                if (
                    message.isStreaming &&
                    visibleMessageContent.isBlank() &&
                    !showImageGeneration
                ) {
                    // After a few seconds with no content yet, escalate the bare
                    // dots to a labeled "Still working…" so a slow first token
                    // never reads as a hang on the SSE / sessions paths.
                    val awaitingFirstToken = visibleMessageContent.isBlank()
                    var showStillWorking by remember(message.id) { mutableStateOf(false) }
                    LaunchedEffect(message.id, awaitingFirstToken) {
                        showStillWorking = false
                        if (awaitingFirstToken) {
                            delay(4_000)
                            showStillWorking = true
                        }
                    }
                    val thinkingIndicator = LocalThinkingIndicator.current
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when (thinkingIndicator.style) {
                            ThinkingIndicatorStyle.Matrix -> DotMatrixIndicator(
                                // Auto follows the bubble text color; accents
                                // come from the brand palette. The grid modulates
                                // its own alpha (idle dots ≈0.18, lit dots 1.0).
                                color = thinkingIndicator.color.toColor(autoColor = textColor),
                                pattern = thinkingIndicator.pattern,
                                animated = thinkingIndicator.animated,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            ThinkingIndicatorStyle.Dots -> StreamingDots(
                                color = textColor.copy(alpha = 0.6f),
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        // During dropped-stream answer recovery the label shows
                        // immediately (the 4s escalation is for a slow first
                        // token; a recovery is already known to be slow).
                        if ((showStillWorking || recoveringAnswer) && awaitingFirstToken) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (recoveringAnswer) {
                                    stringResource(R.string.msg_bubble_reconnecting)
                                } else {
                                    stringResource(R.string.msg_bubble_still_working)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = textColor.copy(alpha = 0.6f),
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }

                // Timestamp — only on the LAST bubble of a same-author run so a
                // burst of fragments doesn't stack three near-touching time labels.
                // Grouping breaks on a >5min gap (ChatScreen), so every pause still
                // surfaces its own time. Alpha floored at 0.6 for 11sp contrast.
                // Reserve the footer from the first streaming frame so
                // completion is a color-only transition and cannot resize the
                // row. Hide the reserved timestamp from accessibility until it
                // becomes visible.
                if (isLastInGroup) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = timeFormat.format(Date(message.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = if (message.isStreaming) 0f else 0.6f),
                        modifier = if (message.isStreaming) {
                            Modifier.clearAndSetSemantics { }
                        } else {
                            Modifier
                        },
                    )
                }

                // Delivery status — only on agent-Thread reply bubbles (a user
                // message routed over the relay proactive channel). Null on every
                // ordinary chat message, which render nothing here.
                message.deliveryStatus?.takeIf { isUser }?.let { status ->
                    Spacer(modifier = Modifier.height(2.dp))
                    MessageDeliveryIndicator(
                        status = status,
                        text = MessageDeliveryIndicatorText(
                            sending = stringResource(R.string.msg_bubble_sending),
                            queued = stringResource(R.string.msg_bubble_queued),
                            steered = stringResource(R.string.msg_bubble_steered),
                            delivered = stringResource(R.string.msg_bubble_delivered),
                            failed = stringResource(R.string.msg_bubble_not_sent),
                            tapToRetry = stringResource(R.string.chat_retry),
                        ),
                    )
                }

                // Token display (assistant messages only)
                if (!isUser && (message.inputTokens != null || message.outputTokens != null)) {
                    Spacer(modifier = Modifier.height(2.dp))
                    TokenDisplay(
                        inputTokens = message.inputTokens,
                        outputTokens = message.outputTokens
                    )
                }
            }
        }
        val inlineActions: @Composable () -> Unit = {
            MessageInlineActions(
                showQuote = onQuoteMessage != null,
                showSpeak = showSpeakAction,
                showStopSpeaking = showStopSpeakingAction,
                showEdit = showEditAction,
                onCopy = {
                    showInlineActions = false
                    onCopyMessage(visibleMessageContent)
                },
                onQuote = {
                    showInlineActions = false
                    onQuoteMessage?.invoke(message.copy(content = visibleMessageContent))
                },
                onSpeak = {
                    showInlineActions = false
                    onSpeakMessage?.invoke(visibleMessageContent)
                },
                onStopSpeaking = {
                    showInlineActions = false
                    onStopSpeaking?.invoke()
                },
                onEdit = {
                    showInlineActions = false
                    onEditMessage?.invoke(message)
                },
            )
        }
        if (animateInlineActions) {
            AnimatedVisibility(
                visible = showInlineActions,
                enter = fadeIn(tween(120)) + expandVertically(
                    animationSpec = tween(180, easing = LinearOutSlowInEasing),
                    expandFrom = Alignment.Top,
                ),
                exit = fadeOut(tween(90)) + shrinkVertically(
                    animationSpec = tween(140),
                    shrinkTowards = Alignment.Top,
                ),
            ) {
                inlineActions()
            }
        } else if (showInlineActions) {
            inlineActions()
        }
        } // end Column (bubble + revealed actions)
        } // end Row (bubble + optional leading accent bar)
        } // end if (showBubble)
    } // end content Column
    } // end CompositionLocalProvider(LocalMediaBlurMode)
}

@Composable
private fun MessageInlineActions(
    showQuote: Boolean,
    showSpeak: Boolean,
    showStopSpeaking: Boolean,
    showEdit: Boolean,
    onCopy: () -> Unit,
    onQuote: () -> Unit,
    onSpeak: () -> Unit,
    onStopSpeaking: () -> Unit,
    onEdit: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        modifier = Modifier.padding(top = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 2.dp),
        ) {
            IconButton(onClick = onCopy, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.msg_bubble_copy),
                    modifier = Modifier.size(18.dp),
                )
            }
            if (showQuote) {
                IconButton(onClick = onQuote, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Filled.FormatQuote,
                        contentDescription = stringResource(R.string.msg_bubble_quote),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (showSpeak) {
                IconButton(onClick = onSpeak, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = stringResource(R.string.msg_bubble_speak_response),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (showStopSpeaking) {
                IconButton(onClick = onStopSpeaking, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = stringResource(R.string.msg_bubble_stop_speaking),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (showEdit) {
                IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.msg_bubble_edit),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

internal data class MessageSelectionTopologyKey(
    val renderer: String,
    val markdownBody: String?,
)

internal fun messageSelectionTopologyKey(
    isPlainText: Boolean,
    isStreaming: Boolean,
    retainStreamingLayout: Boolean,
    markdownBody: String,
): MessageSelectionTopologyKey = when {
    isPlainText -> MessageSelectionTopologyKey(renderer = "plain", markdownBody = null)
    isStreaming || retainStreamingLayout ->
        MessageSelectionTopologyKey(renderer = "live", markdownBody = null)
    else -> MessageSelectionTopologyKey(renderer = "markdown", markdownBody = markdownBody)
}

/**
 * Only a settled, plain assistant response can become a transient pet visit
 * target. Rich cards, attachments, and tool/action rows remain interaction
 * surfaces for the user rather than pet destinations.
 */
internal fun isPetVisitTargetCandidate(message: ChatMessage): Boolean =
    message.role == MessageRole.ASSISTANT &&
        !message.isStreaming &&
        !message.isThinkingStreaming &&
        message.content.isNotBlank() &&
        message.toolCalls.isEmpty() &&
        message.cards.isEmpty() &&
        message.attachments.isEmpty() &&
        message.backgroundTask == null &&
        message.agentName != "Voice action" &&
        message.agentName != "Phone action" &&
        !message.id.startsWith("voice-intent-")

/** The newest settled user or assistant bubble can provide text-safe habitat geometry. */
internal fun isPetPerchCandidate(message: ChatMessage): Boolean =
    (message.role == MessageRole.ASSISTANT || message.role == MessageRole.USER) &&
        !message.isStreaming &&
        !message.isThinkingStreaming &&
        message.content.isNotBlank() &&
        message.backgroundTask == null &&
        message.agentName != "Voice action" &&
        message.agentName != "Phone action" &&
        !message.id.startsWith("voice-intent-")

internal fun newestPetVisitTargetUiKey(messages: List<ChatMessage>): String? =
    messages.lastOrNull { message ->
        message.role == MessageRole.ASSISTANT
    }?.takeIf(::isPetVisitTargetCandidate)?.uiKey

internal fun newestPetPerchUiKey(messages: List<ChatMessage>): String? =
    messages.lastOrNull()?.takeIf(::isPetPerchCandidate)?.uiKey

/** All settled visible-message candidates that may become journey stepping stones. */
internal fun petPerchUiKeys(messages: List<ChatMessage>): Set<String> =
    messages.asSequence().filter(::isPetPerchCandidate).map { it.uiKey }.toSet()

/** The completion edge is usable only after the newest assistant row itself settles. */
internal fun newestPetAssistantIsSettled(messages: List<ChatMessage>): Boolean =
    messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.let { message ->
        !message.isStreaming && !message.isThinkingStreaming
    } == true

internal fun shouldShowSpeakResponseAction(
    message: ChatMessage,
    handlerAvailable: Boolean,
): Boolean =
    handlerAvailable &&
        message.role == MessageRole.ASSISTANT &&
        !message.isStreaming &&
        message.content.isNotBlank()

internal fun shouldShowStopSpeakingAction(
    message: ChatMessage,
    handlerAvailable: Boolean,
): Boolean =
    handlerAvailable &&
        message.role == MessageRole.ASSISTANT &&
        !message.isStreaming &&
        message.content.isNotBlank()

internal fun shouldShowMessageGroupAvatar(
    isUser: Boolean,
    isSystem: Boolean,
    isFirstInGroup: Boolean,
    agentName: String?,
): Boolean = !isUser && !isSystem && isFirstInGroup && !agentName.isNullOrBlank()

@Composable
private fun MessagePathBadge(text: String, leadingIcon: ImageVector? = null) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            leadingIcon?.let { icon ->
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(12.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Three dots that animate opacity in sequence to indicate streaming is in progress.
 */
@Composable
fun StreamingDots(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
) {
    val transition = rememberInfiniteTransition(label = "streaming")

    val dot1Alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1"
    )
    val dot2Alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, delayMillis = 200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot2"
    )
    val dot3Alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, delayMillis = 400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot3"
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "\u2022",
            fontSize = 14.sp,
            color = color.copy(alpha = dot1Alpha)
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = "\u2022",
            fontSize = 14.sp,
            color = color.copy(alpha = dot2Alpha)
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = "\u2022",
            fontSize = 14.sp,
            color = color.copy(alpha = dot3Alpha)
        )
    }
}
