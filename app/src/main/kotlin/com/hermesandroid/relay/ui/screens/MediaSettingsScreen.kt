package com.hermesandroid.relay.ui.screens

import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.data.MediaSettings
import com.hermesandroid.relay.data.MediaSettingsRepository
import com.hermesandroid.relay.ui.theme.gradientBorder
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import kotlinx.coroutines.launch

/**
 * Dedicated Media settings screen. Hosts the inbound media controls:
 * max inbound size, auto-fetch threshold, auto-fetch on cellular toggle,
 * cached media cap, and clear-cache action.
 *
 * Controls how the app handles files fetched from the relay when the agent
 * emits `MEDIA:hermes-relay://<token>` markers (screenshots, audio transcripts,
 * generated docs). All four knobs live in [MediaSettingsRepository].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaSettingsScreen(
    connectionViewModel: ConnectionViewModel,
    onBack: () -> Unit,
) {
    val isDarkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = connectionViewModel.mediaSettingsRepo
    val cacheWriter = connectionViewModel.mediaCacheWriter

    val settings by repo.settings.collectAsState(
        initial = MediaSettings(
            maxInboundSizeMb = MediaSettingsRepository.DEFAULT_MAX_INBOUND_MB,
            autoFetchThresholdMb = MediaSettingsRepository.DEFAULT_AUTO_FETCH_THRESHOLD_MB,
            autoFetchOnCellular = MediaSettingsRepository.DEFAULT_AUTO_FETCH_ON_CELLULAR,
            cachedMediaCapMb = MediaSettingsRepository.DEFAULT_CACHED_MEDIA_CAP_MB
        )
    )

    var currentCacheBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        currentCacheBytes = runCatching { cacheWriter.currentSizeBytes() }.getOrDefault(0L)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Media") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .gradientBorder(
                        shape = RoundedCornerShape(12.dp),
                        isDarkTheme = isDarkTheme
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Controls how the app handles files sent by tool results (screenshots, PDFs, etc.) over the relay.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Max inbound attachment size — 5..100 MB in 5 MB steps
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Max inbound attachment size",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "${settings.maxInboundSizeMb} MB",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = settings.maxInboundSizeMb.toFloat(),
                            onValueChange = { scope.launch { repo.setMaxInboundSize(it.toInt()) } },
                            valueRange = 5f..100f,
                            steps = 18 // (100 - 5) / 5 - 1
                        )
                        Text(
                            text = "Files larger than this are rejected after download.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider()

                    // Auto-fetch threshold — 0..50 MB
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Auto-fetch threshold",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "${settings.autoFetchThresholdMb} MB",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = settings.autoFetchThresholdMb.toFloat(),
                            onValueChange = { scope.launch { repo.setAutoFetchThreshold(it.toInt()) } },
                            valueRange = 0f..50f,
                            steps = 49
                        )
                        Text(
                            text = "Files at or below this size are fetched automatically on Wi-Fi.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider()

                    // Auto-fetch on cellular switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-fetch on cellular",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "When off, media over cellular shows a tap-to-download card instead of auto-downloading.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.autoFetchOnCellular,
                            onCheckedChange = { scope.launch { repo.setAutoFetchOnCellular(it) } }
                        )
                    }

                    HorizontalDivider()

                    // Cached media cap — 50..500 MB in 25 MB steps
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Cached media cap",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "${settings.cachedMediaCapMb} MB",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = settings.cachedMediaCapMb.toFloat(),
                            onValueChange = { scope.launch { repo.setCachedMediaCap(it.toInt()) } },
                            valueRange = 50f..500f,
                            steps = 17 // (500 - 50) / 25 - 1
                        )
                        Text(
                            text = "Oldest files are evicted when the cache exceeds this limit.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider()

                    // Clear cache button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Clear cached media",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Currently using ${formatBytesHuman(currentCacheBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val freed = runCatching { cacheWriter.clear() }.getOrDefault(0L)
                                    currentCacheBytes = runCatching { cacheWriter.currentSizeBytes() }.getOrDefault(0L)
                                    Toast.makeText(
                                        context,
                                        "Freed ${formatBytesHuman(freed)}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        ) {
                            Text("Clear")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Human-readable byte count for the inbound-media Settings UI. Keeps the
 * logic out of the composable so it stays cheap on recomposition.
 */
private fun formatBytesHuman(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> "%.2f GB".format(gb)
        mb >= 1.0 -> "%.1f MB".format(mb)
        kb >= 1.0 -> "%.0f KB".format(kb)
        else -> "$bytes B"
    }
}
