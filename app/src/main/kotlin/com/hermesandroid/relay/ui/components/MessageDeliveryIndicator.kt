package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.data.MessageDeliveryStatus
import com.hermesandroid.relay.ui.theme.relayMetadataStyle

/**
 * Localized copy for [MessageDeliveryIndicator]. Keeping copy at the call site
 * lets MessageBubble use Android string resources without coupling this small
 * presentation component to a particular resource catalog.
 */
data class MessageDeliveryIndicatorText(
    val sending: String,
    val queued: String,
    val steered: String,
    val delivered: String,
    val failed: String,
    val tapToRetry: String,
)

/**
 * Compact, accessible lifecycle feedback for a user-authored chat message.
 *
 * Every state has both a distinct icon and a visible text label, so meaning is
 * never carried by color alone. A failed state becomes a button only when
 * [onRetry] is supplied; its entire 48dp row is then the retry target.
 */
@Composable
fun MessageDeliveryIndicator(
    status: MessageDeliveryStatus,
    text: MessageDeliveryIndicatorText,
    modifier: Modifier = Modifier,
    failureMessage: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    val retryAction = onRetry.takeIf { status == MessageDeliveryStatus.FAILED }
    val retryable = retryAction != null
    val line = messageDeliveryStatusLine(
        status = status,
        text = text,
        failureMessage = failureMessage,
        retryable = retryable,
    )
    val presentation = messageDeliveryPresentation(status)
    val tint = when (presentation.colorRole) {
        DeliveryColorRole.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        DeliveryColorRole.ACCENT -> MaterialTheme.colorScheme.primary
        DeliveryColorRole.QUEUED -> MaterialTheme.colorScheme.tertiary
        DeliveryColorRole.ERROR -> MaterialTheme.colorScheme.error
    }

    Row(
        modifier = modifier
            .then(
                if (retryable) {
                    Modifier
                        .minimumInteractiveComponentSize()
                        .clickable(
                            role = Role.Button,
                            onClickLabel = text.tapToRetry,
                            onClick = retryAction,
                        )
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) {
                contentDescription = line
                liveRegion = LiveRegionMode.Polite
            }
            .padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = presentation.icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = line,
            style = relayMetadataStyle(),
            color = tint,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun messageDeliveryStatusLine(
    status: MessageDeliveryStatus,
    text: MessageDeliveryIndicatorText,
    failureMessage: String? = null,
    retryable: Boolean = false,
): String {
    val label = when (status) {
        MessageDeliveryStatus.SENDING -> text.sending
        MessageDeliveryStatus.QUEUED -> text.queued
        MessageDeliveryStatus.STEERED -> text.steered
        MessageDeliveryStatus.DELIVERED -> text.delivered
        MessageDeliveryStatus.FAILED -> text.failed
    }
    if (status != MessageDeliveryStatus.FAILED) return label

    return buildList {
        add(label)
        failureMessage?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
        if (retryable) add(text.tapToRetry)
    }.joinToString(" · ")
}

private data class DeliveryPresentation(
    val icon: ImageVector,
    val colorRole: DeliveryColorRole,
)

private enum class DeliveryColorRole { NEUTRAL, ACCENT, QUEUED, ERROR }

private fun messageDeliveryPresentation(status: MessageDeliveryStatus): DeliveryPresentation =
    when (status) {
        MessageDeliveryStatus.SENDING -> DeliveryPresentation(
            icon = Icons.AutoMirrored.Filled.Send,
            colorRole = DeliveryColorRole.NEUTRAL,
        )
        MessageDeliveryStatus.QUEUED -> DeliveryPresentation(
            icon = Icons.Filled.Schedule,
            colorRole = DeliveryColorRole.QUEUED,
        )
        MessageDeliveryStatus.STEERED -> DeliveryPresentation(
            icon = Icons.Filled.Bolt,
            colorRole = DeliveryColorRole.ACCENT,
        )
        MessageDeliveryStatus.DELIVERED -> DeliveryPresentation(
            icon = Icons.Filled.CheckCircle,
            colorRole = DeliveryColorRole.ACCENT,
        )
        MessageDeliveryStatus.FAILED -> DeliveryPresentation(
            icon = Icons.Filled.ErrorOutline,
            colorRole = DeliveryColorRole.ERROR,
        )
    }
