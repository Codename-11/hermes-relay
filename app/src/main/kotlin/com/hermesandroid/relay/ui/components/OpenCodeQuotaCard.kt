package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.network.relay.RelayHttpClient
import com.hermesandroid.relay.network.relay.RelayHttpClient.OpenCodeUsageLimit
import com.hermesandroid.relay.network.relay.RelayHttpClient.OpenCodeUsageResponse
import com.hermesandroid.relay.network.relay.RelayHttpClient.OpenCodeUsageWindow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * Inline card that shows the OpenCode Go subscription usage — the 5-hour
 * (rolling), weekly, and monthly windows — as progress bars with the dollar
 * amount used, the window cap, and a live "resets in …" countdown.
 *
 * The data is proxied by the relay host (`GET /usage/opencode`): the OpenCode
 * Go API key stays in `~/.hermes/.env` on the Mac and never reaches the device.
 * Fetches on first composition and on every refresh tap. Renders distinct
 * empty / loading / error / loaded states and degrades gracefully when the
 * relay isn't configured or isn't paired yet.
 */
@Composable
fun OpenCodeQuotaCard(
    relayHttpClient: RelayHttpClient,
    modifier: Modifier = Modifier,
) {
    var refreshKey by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<QuotaUiState>(QuotaUiState.Empty) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(refreshKey) {
        state = QuotaUiState.Loading
        state = relayHttpClient.fetchOpenCodeUsage().fold(
            onSuccess = { it?.let(QuotaUiState::Loaded) ?: QuotaUiState.NotApplicable },
            onFailure = { QuotaUiState.Error(it.message ?: "Unknown error") },
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.open_code_quota_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.open_code_quota_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = {
                    scope.launch { refreshKey++ }
                }) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.open_code_quota_refresh),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            when (val s = state) {
                is QuotaUiState.Empty -> {}
                is QuotaUiState.Loading -> LoadingRow()
                is QuotaUiState.NotApplicable -> NotApplicableRow()
                is QuotaUiState.Error -> ErrorRow(
                    message = s.message,
                    onRetry = { scope.launch { refreshKey++ } },
                )
                is QuotaUiState.Loaded -> LoadedBody(s.response)
            }
        }
    }
}

private sealed interface QuotaUiState {
    data object Empty : QuotaUiState
    data object Loading : QuotaUiState
    data object NotApplicable : QuotaUiState
    data class Error(val message: String) : QuotaUiState
    data class Loaded(val response: OpenCodeUsageResponse) : QuotaUiState
}

@Composable
private fun LoadingRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(
            text = stringResource(R.string.open_code_quota_loading),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NotApplicableRow() {
    Text(
        text = stringResource(R.string.open_code_quota_not_configured),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ErrorRow(message: String, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val (line, isEnvironmental) = when {
            message.contains("Relay URL not configured") ->
                stringResource(R.string.open_code_quota_unconfigured) to true
            message.contains("Relay not paired") ->
                stringResource(R.string.open_code_quota_unpaired) to true
            else -> stringResource(R.string.open_code_quota_error) to false
        }
        Text(
            text = line,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!isEnvironmental) {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.open_code_quota_retry))
            }
        }
    }
}

@Composable
private fun LoadedBody(response: OpenCodeUsageResponse) {
    val usage = response.usage
    val rows = mutableListOf<QuotaRow>()
    listOf(
        Triple("rolling", usage.rolling, R.string.open_code_quota_rolling),
        Triple("weekly", usage.weekly, R.string.open_code_quota_weekly),
        Triple("monthly", usage.monthly, R.string.open_code_quota_monthly),
    ).forEach { (key, window, labelRes) ->
        val limit = response.limits[key as String] ?: OpenCodeUsageLimit()
        rows.add(
            QuotaRow(
                title = stringResource(labelRes),
                limit = limit.limit ?: 0.0,
                window = window,
            )
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        rows.forEach { QuotaWindowRow(it) }
    }
}

private data class QuotaRow(
    val title: String,
    val limit: Double,
    val window: OpenCodeUsageWindow?,
)

@Composable
private fun QuotaWindowRow(row: QuotaRow) {
    val now = remember { Instant.now() }
    val percent = (row.window?.percent ?: 0).coerceIn(0, 100)
    val used = percent / 100.0 * row.limit
    val fraction = percent / 100f

    val barColor = when {
        percent >= 80 -> MaterialTheme.colorScheme.error
        percent >= 50 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            val usedText = if (row.limit > 0.0) {
                stringResource(
                    R.string.open_code_quota_used_of,
                    formatMoney(used),
                    formatMoney(row.limit),
                )
            } else {
                "$percent%"
            }
            Text(
                text = usedText,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (percent >= 80) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
        }

        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = barColor,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )

        val resets = row.window?.resetsAt?.let { formatResetsIn(it, now) }
        if (resets != null) {
            Text(
                text = stringResource(R.string.open_code_quota_resets, resets),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "$1,234" / "$12.50" style helper — whole dollars render without decimals. */
private fun formatMoney(value: Double): String {
    if (value <= 0.0) return "0"
    if (value == value.toLong().toDouble()) return value.toLong().toString()
    val rounded = Math.round(value * 100.0) / 100.0
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        String.format("%.2f", rounded)
    }
}

/** Render an ISO-8601 reset timestamp as a human "Xd Xh" / "Xh Xm" / "Xm" string. */
private fun formatResetsIn(iso: String, from: Instant): String? {
    return runCatching {
        val reset = Instant.parse(iso)
        val remaining = Duration.between(from, reset)
        if (remaining.isNegative) return@runCatching "now"
        val days = remaining.toDays()
        val hours = remaining.toHours() % 24
        val minutes = remaining.toMinutes() % 60
        when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }.getOrNull()
}
