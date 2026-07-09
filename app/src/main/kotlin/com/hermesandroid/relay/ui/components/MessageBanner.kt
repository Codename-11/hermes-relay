package com.hermesandroid.relay.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.UiMessage
import com.hermesandroid.relay.ui.UiMessageBus
import com.hermesandroid.relay.ui.UiMessageSeverity
import kotlinx.coroutines.delay

private const val MAX_RETAINED = 6
private const val MAX_VISIBLE_EXPANDED = 3
private const val ROW_MIN_HEIGHT_DP = 34

/**
 * Top, thin, info-only banner host. Collects [UiMessageBus] and renders the
 * newest transient message on one line; tapping expands to the recent few
 * (scrolling past three). It takes its own vertical space — the Scaffold below
 * reflows, so content slides down smoothly instead of being covered by an
 * overlay. Auto-dismisses (paused while expanded) and coalesces duplicates so a
 * burst of the same status collapses to one refreshed row.
 *
 * Errors stay on the snackbar — only post info/success/status here.
 */
@Composable
fun MessageBannerHost(
    modifier: Modifier = Modifier,
    includeStatusBarPadding: Boolean = true,
) {
    // Backing queue (oldest first; newest is last). expiresAt is kept in a
    // parallel map so coalescing/auto-dismiss can address rows by id.
    val shown = remember { mutableStateListOf<UiMessage>() }
    val expiresAt = remember { mutableStateMapOf<Long, Long>() }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        UiMessageBus.events.collect { msg ->
            // Coalesce identical text so e.g. repeated "Reconnecting…" collapses
            // to a single, freshly-timed row rather than stacking.
            shown.filter { it.text == msg.text }.forEach { dup ->
                shown.remove(dup)
                expiresAt.remove(dup.id)
            }
            shown.add(msg)
            expiresAt[msg.id] = nowMs() + msg.ttlMillis
            while (shown.size > MAX_RETAINED) {
                val dropped = shown.removeAt(0)
                expiresAt.remove(dropped.id)
            }
        }
    }

    // Auto-dismiss — paused while expanded so the user can read the list.
    LaunchedEffect(shown.toList(), expanded) {
        if (expanded) return@LaunchedEffect
        while (shown.isNotEmpty()) {
            val now = nowMs()
            val soonest = shown.minOfOrNull { expiresAt[it.id] ?: Long.MAX_VALUE } ?: break
            if (soonest <= now) {
                shown.filter { (expiresAt[it.id] ?: Long.MAX_VALUE) <= now }.forEach { expired ->
                    shown.remove(expired)
                    expiresAt.remove(expired.id)
                }
            } else {
                delay(soonest - now)
            }
        }
    }

    // Collapse + report count to the scaffold (for inset accounting).
    LaunchedEffect(shown.size) {
        if (shown.isEmpty()) expanded = false
        UiMessageBus.reportActiveCount(shown.size)
    }
    DisposableEffect(Unit) {
        onDispose { UiMessageBus.reportActiveCount(0) }
    }

    // Mirror the live queue into a retained copy so the exit animation still
    // has content to slide/fade out after `shown` has emptied (otherwise the
    // banner would read empty mid-animation and pop instead of glide).
    val rendered = remember { mutableStateListOf<UiMessage>() }
    LaunchedEffect(shown.toList()) {
        if (shown.isNotEmpty()) {
            rendered.clear()
            rendered.addAll(shown)
        }
    }

    // Enter/exit is a fade with an instant reflow — the same treatment as the
    // Demo/Unattended banners. A height-slide here would desync from the
    // Scaffold's status-bar inset hand-off and briefly push the top app bar
    // under the notch. The smooth "slide" lives in animateContentSize below
    // (collapsed↔expanded and message-count changes).
    AnimatedVisibility(
        visible = shown.isNotEmpty(),
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(160)),
        modifier = modifier,
    ) {
        MessageBannerContent(
            messages = rendered,
            expanded = expanded,
            onToggle = { if (rendered.size > 1) expanded = !expanded },
            includeStatusBarPadding = includeStatusBarPadding,
        )
    }
}

@Composable
private fun MessageBannerContent(
    messages: SnapshotStateList<UiMessage>,
    expanded: Boolean,
    onToggle: () -> Unit,
    includeStatusBarPadding: Boolean,
) {
    val newest = messages.lastOrNull() ?: return
    val multiple = messages.size > 1
    val insetModifier = if (includeStatusBarPadding) {
        Modifier.windowInsetsPadding(WindowInsets.statusBars)
    } else {
        Modifier
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .then(insetModifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Surface(
            color = severityContainer(newest.severity),
            contentColor = severityOnContainer(newest.severity),
            shape = RoundedCornerShape(10.dp),
            tonalElevation = 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (multiple) Modifier.clickable(onClick = onToggle) else Modifier)
                .animateContentSize(animationSpec = tween(durationMillis = 180)),
        ) {
            if (!expanded) {
                MessageRow(
                    message = newest,
                    trailing = {
                        if (multiple) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${messages.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Icon(
                                    imageVector = Icons.Filled.KeyboardArrowDown,
                                    contentDescription = stringResource(R.string.cd_show_recent),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    },
                )
            } else {
                // Newest first; cap the visible height to ~3 rows and scroll the
                // rest so a long burst can't push the whole UI down.
                val ordered = messages.reversed()
                val scroll = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (ordered.size > MAX_VISIBLE_EXPANDED) {
                                Modifier
                                    .heightIn(max = (ROW_MIN_HEIGHT_DP * MAX_VISIBLE_EXPANDED).dp)
                                    .verticalScroll(scroll)
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    ordered.forEachIndexed { index, message ->
                        MessageRow(
                            message = message,
                            trailing = {
                                if (index == 0) {
                                    Icon(
                                        imageVector = Icons.Filled.KeyboardArrowUp,
                                        contentDescription = stringResource(R.string.cd_collapse),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageRow(
    message: UiMessage,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT_DP.dp)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = severityIcon(message.severity),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = message.text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
private fun severityContainer(severity: UiMessageSeverity): Color = when (severity) {
    UiMessageSeverity.Success -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.58f)
    UiMessageSeverity.Status -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.74f)
    UiMessageSeverity.Info -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.90f)
}

@Composable
private fun severityOnContainer(severity: UiMessageSeverity): Color = when (severity) {
    UiMessageSeverity.Success -> MaterialTheme.colorScheme.onTertiaryContainer
    UiMessageSeverity.Status -> MaterialTheme.colorScheme.onSecondaryContainer
    UiMessageSeverity.Info -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun severityIcon(severity: UiMessageSeverity): ImageVector = when (severity) {
    UiMessageSeverity.Success -> Icons.Filled.CheckCircle
    UiMessageSeverity.Status -> Icons.Filled.Sync
    UiMessageSeverity.Info -> Icons.Filled.Info
}

private fun nowMs(): Long = System.currentTimeMillis()
