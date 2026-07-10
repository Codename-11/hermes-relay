package com.hermesandroid.relay.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.data.HermesProcessNotification
import com.hermesandroid.relay.ui.theme.relayMetadataStyle

/**
 * Compact transcript treatment for the user-role process events that upstream
 * Hermes injects when background work completes or matches a watch pattern.
 *
 * This component is intentionally quieter than a user bubble: the row is
 * machine-authored process state, while the following assistant message is the
 * conversational response. Command and output detail remain selectable behind
 * progressive disclosure.
 */
@Composable
fun SyntheticProcessNotificationNotice(
    notification: HermesProcessNotification,
    modifier: Modifier = Modifier,
) {
    val detail = notification.detail?.let(::sanitizeTerminalText)
    val hasDetail = !detail.isNullOrBlank()
    var expanded by rememberSaveable(notification.processId, notification.headline) {
        mutableStateOf(false)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(
                        enabled = hasDetail,
                        onClickLabel = if (expanded) "Collapse process output" else "Expand process output",
                    ) { expanded = !expanded }
                    .semantics {
                        contentDescription = buildString {
                            append("Background process notice. ")
                            append(notification.headline)
                            if (hasDetail) {
                                append(if (expanded) ". Output expanded" else ". Output collapsed")
                            }
                        }
                        if (hasDetail) {
                            stateDescription = if (expanded) "Output expanded" else "Output collapsed"
                        }
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Code,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = notification.headline,
                    style = relayMetadataStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    modifier = Modifier.weight(1f),
                )
                if (hasDetail) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "output",
                        style = relayMetadataStyle(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f),
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            AnimatedVisibility(visible = expanded && hasDetail) {
                SelectionContainer {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 192.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = detail.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}
