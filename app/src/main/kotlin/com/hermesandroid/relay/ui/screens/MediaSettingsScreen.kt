package com.hermesandroid.relay.ui.screens

import android.content.Context
import android.widget.Toast
import com.hermesandroid.relay.ui.theme.LocalBrand
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.BlurMode
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
    val isDarkTheme = LocalBrand.current.isDark
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
                title = { Text(stringResource(R.string.media_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.media_back),
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
                        text = stringResource(R.string.media_intro_1),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.media_intro_2),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Max inbound attachment size — 5..100 MB in 5 MB steps
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.media_max_inbound),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.media_max_inbound_value, settings.maxInboundSizeMb),
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
                            text = stringResource(R.string.media_max_inbound_desc),
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
                                text = stringResource(R.string.media_auto_fetch_threshold),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.media_auto_fetch_value, settings.autoFetchThresholdMb),
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
                            text = stringResource(R.string.media_auto_fetch_desc),
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
                                text = stringResource(R.string.media_auto_fetch_cellular),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.media_auto_fetch_cellular_desc),
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
                                text = stringResource(R.string.media_cache_cap),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.media_cache_cap_value, settings.cachedMediaCapMb),
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
                            text = stringResource(R.string.media_cache_cap_desc),
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
                                text = stringResource(R.string.media_clear_cache),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.media_clear_cache_current, formatBytesHuman(context, currentCacheBytes)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val freed = runCatching { cacheWriter.clear() }.getOrDefault(0L)
                                    currentCacheBytes = runCatching { cacheWriter.currentSizeBytes() }.getOrDefault(0L)
                                    val freedMsg = context.getString(
                                        R.string.media_clear_cache_freed,
                                        formatBytesHuman(context, freed)
                                    )
                                    Toast.makeText(
                                        context,
                                        freedMsg,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        ) {
                            Text(stringResource(R.string.media_clear))
                        }
                    }
                }
            }

            // Sensitive-media blur. Separate card because — unlike the four
            // relay-only knobs above — this applies on the standard (no-Relay)
            // path too: ALL_IMAGES needs zero server support.
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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.media_sensitive),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = stringResource(R.string.media_sensitive_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val blurOptions = listOf(
                        BlurMode.OFF to stringResource(R.string.media_blur_off),
                        BlurMode.FLAGGED to stringResource(R.string.media_blur_flagged),
                        BlurMode.ALL_IMAGES to stringResource(R.string.media_blur_all)
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        blurOptions.forEachIndexed { index, (mode, label) ->
                            SegmentedButton(
                                selected = settings.blurSensitive == mode,
                                onClick = { scope.launch { repo.setBlurSensitive(mode) } },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = blurOptions.size
                                )
                            ) {
                                Text(label)
                            }
                        }
                    }
                    Text(
                        text = when (settings.blurSensitive) {
                            BlurMode.OFF -> stringResource(R.string.media_blur_off_desc)
                            BlurMode.FLAGGED -> stringResource(R.string.media_blur_flagged_desc)
                            BlurMode.ALL_IMAGES -> stringResource(R.string.media_blur_all_desc)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Human-readable byte count for the inbound-media Settings UI. Keeps the
 * logic out of the composable so it stays cheap on recomposition.
 */
private fun formatBytesHuman(context: Context, bytes: Long): String {
    if (bytes <= 0) return context.getString(R.string.media_bytes_0)
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(context.getString(R.string.media_bytes_gb), gb)
        mb >= 1.0 -> String.format(context.getString(R.string.media_bytes_mb), mb)
        kb >= 1.0 -> String.format(context.getString(R.string.media_bytes_kb), kb)
        else -> context.getString(R.string.media_bytes_b, bytes)
    }
}
