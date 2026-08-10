package com.hermesandroid.relay.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import com.hermesandroid.relay.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.ui.components.pet.petObstacleSurface

private val THINKING_BLOCK_PET_ROUTES = setOf("chat")

@Composable
fun ThinkingBlock(
    thinkingContent: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
    /** Message timestamp shown right-aligned in the header (null hides it). */
    timestamp: Long? = null,
    headerText: String? = null,
    accessibilityLabel: String = stringResource(R.string.thinking_thinking_short),
    /** Stable key when this interactive block participates in Chat pet terrain. */
    petObstacleKey: String? = null,
) {
    if (thinkingContent.isBlank()) return
    var expanded by remember { mutableStateOf(isStreaming) }
    var userToggled by remember { mutableStateOf(false) }
    LaunchedEffect(isStreaming) {
        if (!userToggled) expanded = isStreaming
    }
    val locale = LocalLocale.current.platformLocale
    val timeLabel = timestamp?.let {
        remember(it, locale) {
            java.text.SimpleDateFormat("h:mm a", locale)
                .format(java.util.Date(it))
        }
    }
    val expansionState = if (expanded) {
        stringResource(R.string.tool_progress_state_expanded)
    } else {
        stringResource(R.string.tool_progress_state_collapsed)
    }

    Column(
        modifier = modifier
            .then(
                if (petObstacleKey != null) {
                    Modifier.petObstacleSurface(
                        key = petObstacleKey,
                        routes = THINKING_BLOCK_PET_ROUTES,
                    )
                } else {
                    Modifier
                },
            )
            .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) {
                    userToggled = true
                    expanded = !expanded
                }
                .semantics {
                    stateDescription = expansionState
                }
                .padding(horizontal = 4.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
                Icon(
                    imageVector = Icons.Filled.Psychology,
                    contentDescription = accessibilityLabel,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.tertiary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = headerText ?: if (isStreaming) {
                        stringResource(R.string.thinking_thinking)
                    } else {
                        stringResource(R.string.thinking_settled)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f))
                if (!isStreaming && timeLabel != null) {
                    Text(
                        text = timeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.tool_progress_cd_collapse) else stringResource(R.string.tool_progress_cd_expand),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Text(
                text = thinkingContent,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Default,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier.padding(start = 26.dp, top = 2.dp, end = 4.dp, bottom = 4.dp),
                maxLines = if (isStreaming) Int.MAX_VALUE else 50,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
