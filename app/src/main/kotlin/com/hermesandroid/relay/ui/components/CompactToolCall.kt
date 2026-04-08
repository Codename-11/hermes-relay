package com.hermesandroid.relay.ui.components

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.data.ToolCall

@Composable
fun CompactToolCall(
    toolCall: ToolCall,
    modifier: Modifier = Modifier
) {
    val statusText = when {
        toolCall.isComplete && toolCall.success == true -> "completed"
        toolCall.isComplete && toolCall.success == false -> "failed"
        else -> "running"
    }

    val duration = if (toolCall.completedAt != null && toolCall.completedAt >= toolCall.startedAt) {
        val seconds = (toolCall.completedAt - toolCall.startedAt) / 1000.0
        String.format("%.1fs", seconds)
    } else null

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics {
                contentDescription = "Tool ${toolCall.name} $statusText${duration?.let { " in $it" } ?: ""}"
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
                    contentDescription = "Completed",
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            else -> {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Failed",
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
    }
}
