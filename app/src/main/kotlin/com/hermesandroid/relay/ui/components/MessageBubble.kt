package com.hermesandroid.relay.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.hermesandroid.relay.data.BlurMode
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.HermesCardAction
import com.hermesandroid.relay.data.MediaSettingsRepository
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.ui.theme.leftEdgeGlow
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    maxBubbleWidth: Dp = 300.dp,
    showThinking: Boolean = true,
    isFirstInGroup: Boolean = true,
    isLastInGroup: Boolean = true,
    onCopyMessage: (String) -> Unit = {},
    /**
     * Quote this message into the input field. Null hides the Quote entry in
     * the long-press menu, so legacy call sites keep the copy-only behavior.
     */
    onQuoteMessage: ((String) -> Unit)? = null,
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
        MessageRole.ASSISTANT -> MaterialTheme.colorScheme.onSurfaceVariant
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
    val a11yDescription = "${message.role.name.lowercase()} message: ${message.content.take(100)}"
    val isDarkTheme = LocalBrand.current.isDark

    // Pull generated/inline image links (`![alt](src)`) out of assistant
    // content so they render as real images (remote URLs via Coil) or a
    // graceful inline notice — not the blank element the markdown renderer
    // emits for an image link. User/system bubbles keep their raw content.
    val (markdownBody, inlineImages) = remember(message.content, isUser, isSystem) {
        if (isUser || isSystem) {
            message.content to emptyList()
        } else {
            extractChatInlineImages(message.content)
        }
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
        horizontalAlignment = alignment
    ) {
        // Agent name label (above assistant bubbles, only first in group), with
        // the active profile's local icon (if set) — a small avatar by the name.
        if (!isUser && !isSystem && isFirstInGroup && !message.agentName.isNullOrBlank()) {
            val agentIconPath = LocalAgentIconPath.current
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(bottom = 2.dp, start = 4.dp),
            ) {
                if (!agentIconPath.isNullOrBlank()) {
                    AsyncImage(
                        model = File(agentIconPath),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape),
                    )
                }
                Text(
                    text = message.agentName,
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
                        text = badge,
                        // Speaker glyph = the shared "spoken" modality marker.
                        // Both the standard voice-mode chip ("Voice") and the
                        // realtime engine chip ("Realtime Agent") are spoken
                        // turns, so they share it; only the text differs.
                        leadingIcon = if (badge == "Voice" || badge == "Realtime Agent") {
                            Icons.Filled.VolumeUp
                        } else {
                            null
                        },
                    )
                }
            }
        }

        // Thinking block (above the bubble, only for assistant messages)
        if (!isUser && showThinking && message.thinkingContent.isNotEmpty()) {
            ThinkingBlock(
                thinkingContent = message.thinkingContent,
                isStreaming = message.isThinkingStreaming,
                timestamp = message.timestamp,
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .padding(bottom = 4.dp)
            )
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
            message.content.isNotBlank() ||
            message.isStreaming ||
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
                        .height(if (message.content.isBlank()) 14.dp else 24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f))
                )
            }
        // Long-press opens a compact action menu when a quote handler is
        // wired; with copy as the only action it stays a direct copy so the
        // one-action case doesn't pay a menu tap.
        var showMessageActions by remember { mutableStateOf(false) }
        val showEditAction = onEditMessage != null && isUser
        if (onQuoteMessage != null || showEditAction) {
            DropdownMenu(
                expanded = showMessageActions,
                onDismissRequest = { showMessageActions = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Copy") },
                    onClick = {
                        showMessageActions = false
                        onCopyMessage(message.content)
                    },
                )
                if (onQuoteMessage != null) {
                    DropdownMenuItem(
                        text = { Text("Quote in reply") },
                        onClick = {
                            showMessageActions = false
                            onQuoteMessage(message.content)
                        },
                    )
                }
                if (showEditAction) {
                    DropdownMenuItem(
                        text = { Text("Edit & resend") },
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
                    if (!isUser && !isSystem && isDarkTheme) {
                        Modifier.leftEdgeGlow(
                            alpha = 0.12f,
                            width = 28.dp,
                            isDarkTheme = true
                        )
                    } else Modifier
                )
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        if (onQuoteMessage != null || showEditAction) {
                            showMessageActions = true
                        } else {
                            onCopyMessage(message.content)
                        }
                    }
                )
                .semantics { contentDescription = a11yDescription }
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                SelectionContainer {
                    if (isUser || isSystem) {
                        // Plain text for user and system messages
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor
                        )
                    } else {
                        // Use a stable lightweight renderer while streaming.
                        // The full parser/highlighter can rebuild block shapes
                        // on every partial fence/token and visibly flicker.
                        if (markdownBody.isNotEmpty()) {
                            if (message.isStreaming) {
                                StreamingMarkdownContent(
                                    content = markdownBody,
                                    textColor = textColor
                                )
                            } else {
                                MarkdownContent(
                                    content = markdownBody,
                                    textColor = textColor
                                )
                            }
                        }
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

                // Attachments — dispatched through the unified InboundAttachmentCard
                // so outbound and inbound attachments share the same render pipeline.
                // Outbound attachments (user-authored) always have state=LOADED so
                // they route straight to the LOADED branch; inbound attachments (via
                // MEDIA markers) cycle through LOADING → LOADED / FAILED as the
                // background fetch progresses.
                if (message.attachments.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    message.attachments.forEachIndexed { index, attachment ->
                        InboundAttachmentCard(
                            attachment = attachment,
                            onRetry = { onAttachmentRetry(message.id, index) },
                            onManualFetch = { onAttachmentManualFetch(message.id, index) },
                            maxWidth = maxBubbleWidth - 24.dp,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }

                // Streaming indicator
                if (message.isStreaming) {
                    // After a few seconds with no content yet, escalate the bare
                    // dots to a labeled "Still working…" so a slow first token
                    // never reads as a hang on the SSE / sessions paths.
                    val awaitingFirstToken = message.content.isBlank()
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
                        if (showStillWorking && awaitingFirstToken) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Still working…",
                                style = MaterialTheme.typography.labelSmall,
                                color = textColor.copy(alpha = 0.6f),
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }

                // Timestamp
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = timeFormat.format(Date(message.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.5f)
                )

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
        } // end Row (bubble + optional leading accent bar)
        } // end if (showBubble)
    }
    } // end CompositionLocalProvider(LocalMediaBlurMode)
}

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
