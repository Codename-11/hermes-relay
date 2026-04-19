package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.ui.components.StatsForNerds
import com.hermesandroid.relay.viewmodel.ChatViewModel
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.viewmodel.VoiceViewModel

/**
 * Dedicated Analytics screen — "Stats for Nerds". Hosts TTFT chart, tokens/
 * message, peak times, stream rate, health metrics, the voice / tool-call
 * sections, the timeline view, and the reset button.
 *
 * [voiceViewModel] and [chatViewModel] are nullable so pre-wiring call
 * sites (previews, legacy tests) still compile; when present, the voice
 * + tool-call + timeline sections render.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    connectionViewModel: ConnectionViewModel,
    onBack: () -> Unit,
    voiceViewModel: VoiceViewModel? = null,
    chatViewModel: ChatViewModel? = null,
) {
    val voiceStats = voiceViewModel?.voiceStats?.collectAsState()?.value
    val toolCalls = chatViewModel?.toolCallHistory?.collectAsState()?.value.orEmpty()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Analytics") },
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
            // Stats for Nerds section
            Text(
                text = "Stats for Nerds",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            StatsForNerds(
                voiceStats = voiceStats,
                toolCalls = toolCalls,
            )
        }
    }
}
