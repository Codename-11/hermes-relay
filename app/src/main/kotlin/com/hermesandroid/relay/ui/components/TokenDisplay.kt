package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TokenDisplay(
    inputTokens: Int?,
    outputTokens: Int?,
    modifier: Modifier = Modifier
) {
    if (inputTokens == null && outputTokens == null) return

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val parts = mutableListOf<String>()

        if (inputTokens != null) {
            parts.add("↑${formatTokens(inputTokens)}")
        }
        if (outputTokens != null) {
            parts.add("↓${formatTokens(outputTokens)}")
        }

        Text(
            text = parts.joinToString(" ") + " tokens",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

private fun formatTokens(count: Int): String {
    return when {
        count >= 1_000_000 -> "${String.format("%.1f", count / 1_000_000.0)}M"
        count >= 1_000 -> "${String.format("%.1f", count / 1_000.0)}K"
        else -> count.toString()
    }
}
