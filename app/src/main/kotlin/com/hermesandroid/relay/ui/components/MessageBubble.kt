package com.hermesandroid.relay.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.ui.theme.leftEdgeGlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
     * Invoked when the user taps a FAILED inbound attachment card.
     * `attachmentIndex` is the position in [ChatMessage.attachments] so the
     * ViewModel can re-fetch the exact placeholder that needs re-trying.
     */
    onAttachmentRetry: (messageId: String, attachmentIndex: Int) -> Unit = { _, _ -> },
    /**
     * Invoked when the user taps a LOADING+"Tap to download" placeholder
     * (the cellular deferral case).
     */
    onAttachmentManualFetch: (messageId: String, attachmentIndex: Int) -> Unit = { _, _ -> }
) {
    val isUser = message.role == MessageRole.USER
    val isSystem = message.role == MessageRole.SYSTEM

    val backgroundColor = when (message.role) {
        MessageRole.USER -> MaterialTheme.colorScheme.primary
        MessageRole.ASSISTANT -> MaterialTheme.colorScheme.surfaceVariant
        MessageRole.SYSTEM -> MaterialTheme.colorScheme.tertiaryContainer
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
    val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    val a11yDescription = "${message.role.name.lowercase()} message: ${message.content.take(100)}"
    val isDarkTheme = isSystemInDarkTheme()

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        // Agent name label (above assistant bubbles, only first in group)
        if (!isUser && !isSystem && isFirstInGroup && !message.agentName.isNullOrBlank()) {
            Text(
                text = message.agentName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 2.dp, start = 4.dp)
            )
        }

        // Thinking block (above the bubble, only for assistant messages)
        if (!isUser && showThinking && message.thinkingContent.isNotEmpty()) {
            ThinkingBlock(
                thinkingContent = message.thinkingContent,
                isStreaming = message.isThinkingStreaming,
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .padding(bottom = 4.dp)
            )
        }

        // Message bubble
        Surface(
            shape = bubbleShape,
            color = backgroundColor,
            modifier = Modifier
                .widthIn(max = maxBubbleWidth)
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
                    onLongClick = { onCopyMessage(message.content) }
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
                        // Markdown for assistant messages
                        if (message.content.isNotEmpty()) {
                            MarkdownContent(
                                content = message.content,
                                textColor = textColor
                            )
                        }
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
                    StreamingDots(
                        color = textColor.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
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
