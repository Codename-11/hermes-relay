package com.hermesandroid.relay.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.data.ToolCall

@Composable
fun ToolProgressCard(
    toolCall: ToolCall,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(!toolCall.isComplete) }

    // Auto-collapse when tool completes
    LaunchedEffect(toolCall.isComplete) {
        if (toolCall.isComplete) expanded = false
    }

    val statusIcon: ImageVector
    val statusColor = when {
        toolCall.isComplete && toolCall.success == true -> {
            statusIcon = Icons.Filled.Check
            MaterialTheme.colorScheme.primary
        }
        toolCall.isComplete && toolCall.success == false -> {
            statusIcon = Icons.Filled.Close
            MaterialTheme.colorScheme.error
        }
        else -> {
            statusIcon = Icons.Filled.HourglassTop
            MaterialTheme.colorScheme.tertiary
        }
    }

    val toolIcon = toolIcon(toolCall.name)
    val statusText = when {
        toolCall.isComplete && toolCall.success == true -> "completed"
        toolCall.isComplete && toolCall.success == false -> "failed"
        else -> "running"
    }

    val duration = if (toolCall.completedAt != null && toolCall.completedAt >= toolCall.startedAt) {
        val seconds = (toolCall.completedAt - toolCall.startedAt) / 1000.0
        String.format("%.1fs", seconds)
    } else null

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Tool ${toolCall.name} $statusText${duration?.let { " in $it" } ?: ""}"
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Tool type icon
                Icon(
                    imageVector = toolIcon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.width(6.dp))

                // Tool name
                Text(
                    text = toolCall.name,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f)
                )

                // Duration
                if (duration != null) {
                    Text(
                        text = duration,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }

                // Status icon
                Icon(
                    imageVector = statusIcon,
                    contentDescription = statusText,
                    tint = statusColor,
                    modifier = Modifier.size(16.dp)
                )

                Spacer(modifier = Modifier.width(4.dp))

                // Expand/collapse
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Progress bar while running
            if (!toolCall.isComplete) {
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            // Expandable details
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    // Arguments
                    toolCall.args?.let { args ->
                        Text(
                            text = "Arguments:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = args,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    // Error (tool.failed)
                    toolCall.error?.let { error ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Error:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    // Result
                    toolCall.result?.let { result ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Result:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = result,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

internal fun toolIcon(toolName: String): ImageVector = when {
    toolName.contains("screenshot") || toolName.contains("read_screen") || toolName.contains("vision") -> Icons.Filled.Description
    toolName.contains("tap") || toolName.contains("swipe") || toolName.contains("click") || toolName.contains("scroll") -> Icons.Filled.TouchApp
    toolName.contains("type") || toolName.contains("input") || toolName.contains("keyboard") -> Icons.Filled.Keyboard
    toolName.contains("launch") || toolName.contains("open") -> Icons.Filled.OpenInNew
    toolName.contains("bash") || toolName.contains("terminal") || toolName.contains("execute") || toolName.contains("shell") -> Icons.Filled.Code
    toolName.contains("file") || toolName.contains("read") || toolName.contains("write") -> Icons.Filled.Description
    toolName.contains("search") || toolName.contains("web") -> Icons.Filled.Search
    else -> Icons.Filled.Build
}
