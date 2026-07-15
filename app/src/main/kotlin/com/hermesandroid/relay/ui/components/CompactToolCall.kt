package com.hermesandroid.relay.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.ToolCall

@Composable
fun CompactToolCall(
    toolCall: ToolCall,
    modifier: Modifier = Modifier
) {
    val isPreparing = toolCall.isGenerating && !toolCall.isComplete
    val statusText = when {
        toolCall.isComplete && toolCall.success == true -> stringResource(R.string.tool_progress_status_completed)
        toolCall.isComplete && toolCall.success == false -> stringResource(R.string.tool_progress_status_failed)
        isPreparing -> stringResource(R.string.tool_preparing_a11y)
        else -> stringResource(R.string.tool_progress_status_running)
    }

    val duration = if (toolCall.completedAt != null && toolCall.completedAt >= toolCall.startedAt) {
        val seconds = (toolCall.completedAt - toolCall.startedAt) / 1000.0
        String.format("%.1fs", seconds)
    } else null
    val durationDescription = duration?.let { stringResource(R.string.tool_duration_a11y, it) }.orEmpty()
    val riskDescription = toolCall.outputRisk?.let {
        stringResource(R.string.tool_output_risk_a11y, it)
    }.orEmpty()
    val toolDescription = stringResource(R.string.tool_a11y, toolCall.name, statusText, durationDescription) +
        riskDescription

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics {
                contentDescription = toolDescription
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tool type icon
        Icon(
            imageVector = toolIcon(toolCall.name),
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Tool name
        Text(
            text = toolCall.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.width(6.dp))

        // Status indicator
        when {
            // tool.generating parity with ToolProgressCard's "preparing"
            // state — MoreHoriz in Muted with the same alpha breathe; the
            // Cyan spinner stays reserved for actually-executing tools.
            isPreparing -> {
                val breathe = rememberInfiniteTransition(label = "compactToolGenerating")
                val alpha = breathe.animateFloat(
                    initialValue = 0.35f,
                    targetValue = 0.9f,
                    animationSpec = infiniteRepeatable(tween(900), repeatMode = RepeatMode.Reverse),
                    label = "compactToolGeneratingAlpha",
                ).value
                Icon(
                    imageVector = Icons.Filled.MoreHoriz,
                    contentDescription = stringResource(R.string.tool_preparing_a11y),
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
                )
            }
            !toolCall.isComplete -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            toolCall.success == true -> {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = stringResource(R.string.tool_completed_a11y),
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            else -> {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.tool_failed_a11y),
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }

        // Duration
        if (duration != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = duration,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (toolCall.outputRisk != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = stringResource(
                    R.string.tool_output_risk_badge,
                    toolCall.outputRisk.uppercase(),
                ),
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}
