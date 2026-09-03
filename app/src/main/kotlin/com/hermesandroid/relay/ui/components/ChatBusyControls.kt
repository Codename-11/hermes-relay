package com.hermesandroid.relay.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import com.hermesandroid.relay.ui.theme.appearanceTopRoundedCornerShape
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.BusyMessageAction

private val COMPOSER_TRAY_UNDERLAP = 12.dp

/** A separate rear surface tucked beneath the composer's foreground edge. */
@Composable
fun ChatComposerLayers(
    action: BusyMessageAction?,
    onActionChange: (BusyMessageAction) -> Unit,
    correctionAvailable: Boolean,
    onStop: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(-COMPOSER_TRAY_UNDERLAP),
    ) {
        if (action != null) {
            ChatBusyActionSelector(
                action, onActionChange,
                correctionAvailable = correctionAvailable,
                onStop = onStop,
                bottomUnderlap = COMPOSER_TRAY_UNDERLAP,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).zIndex(0f),
            )
        }
        Box(Modifier.fillMaxWidth().zIndex(1f).testTag("chatComposerForeground")) { content() }
    }
}

/** Compact current intent; optional plain-text choices slide out from behind it. */
@Composable
fun ChatBusyActionSelector(
    action: BusyMessageAction,
    onActionChange: (BusyMessageAction) -> Unit,
    modifier: Modifier = Modifier,
    correctionAvailable: Boolean = true,
    onStop: (() -> Unit)? = null,
    bottomUnderlap: Dp = 0.dp,
) {
    var expanded by remember { mutableStateOf(false) }
    BackHandler(enabled = expanded) { expanded = false }
    Surface(
        modifier = modifier.testTag("chatBusyActionTray"),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.82f),
        shape = appearanceTopRoundedCornerShape(12.dp),
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(bottom = bottomUnderlap)) {
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(220), expandFrom = Alignment.Bottom) +
                    slideInVertically(tween(220)) { it } + fadeIn(tween(160)),
                exit = shrinkVertically(tween(180), shrinkTowards = Alignment.Bottom) +
                    slideOutVertically(tween(180)) { it } + fadeOut(tween(120)),
            ) {
                Row(
                    Modifier.fillMaxWidth().testTag("chatBusyActionDrawer").padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BusyMessageAction.entries.forEach { option ->
                        val available = option == BusyMessageAction.QueueNext || correctionAvailable
                        Row(
                            Modifier.weight(1f).heightIn(min = 48.dp)
                                .testTag("chatBusyAction-${option.storedValue}")
                                .selectable(selected = option == action, enabled = available, role = Role.RadioButton) {
                                    onActionChange(option)
                                    expanded = false
                                }.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (option == action) {
                                Icon(Icons.Default.Check, null, Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurface)
                            } else Spacer(Modifier.size(14.dp))
                            Text(
                                stringResource(if (option == BusyMessageAction.CorrectNow) R.string.chat_correct_now else R.string.chat_queue_next),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (available) 1f else 0.38f),
                            )
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).heightIn(min = 40.dp)
                        .testTag("chatBusyActionTrigger")
                        .clickable(role = Role.Button) { expanded = !expanded },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(if (action == BusyMessageAction.CorrectNow) R.string.chat_correct_now else R.string.chat_queue_next),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                        null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onStop != null) {
                    IconButton(onClick = onStop) {
                        Icon(Icons.Default.Stop, stringResource(R.string.chat_input_stop_streaming), Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun ChatMessageQueue(
    messages: List<String>,
    paused: Boolean,
    onResume: () -> Unit,
    onClear: () -> Unit,
    onEdit: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    canEdit: Boolean,
    modifier: Modifier = Modifier,
) {
    if (messages.isEmpty()) return
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (paused) stringResource(R.string.chat_queue_paused)
                else pluralStringResource(R.plurals.chat_queue_count, messages.size, messages.size),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            if (paused) TextButton(onClick = onResume) { Text(stringResource(R.string.chat_queue_resume)) }
            TextButton(onClick = onClear) { Text(stringResource(R.string.chat_clear)) }
        }
        Column(
            Modifier.heightIn(max = 144.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            messages.forEachIndexed { index, text ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = text,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                            .clickable(enabled = canEdit, role = Role.Button) { onEdit(index) }
                            .padding(vertical = 12.dp),
                    )
                    IconButton(onClick = { onRemove(index) }) {
                        Icon(Icons.Default.Close, stringResource(R.string.chat_queue_remove))
                    }
                }
            }
        }
    }
}
