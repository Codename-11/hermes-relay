package com.hermesandroid.relay.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hermesandroid.relay.audio.RealtimePcmPlayer
import com.hermesandroid.relay.audio.RealtimePcmRecorder
import com.hermesandroid.relay.network.RealtimeVoiceConfig
import com.hermesandroid.relay.network.RealtimeVoiceEvent
import com.hermesandroid.relay.network.RealtimeVoiceSummary
import com.hermesandroid.relay.network.RelayVoiceClient
import kotlinx.coroutines.launch
import java.util.Base64

/**
 * Dev-only realtime provider testbench for Android Studio builds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RealtimeVoiceTestScreen(
    voiceClient: RelayVoiceClient?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val player = remember { RealtimePcmPlayer() }
    val recorder = remember { RealtimePcmRecorder() }
    val waveform = remember { mutableStateListOf<Float>() }
    val events = remember { mutableStateListOf<String>() }

    var prompt by remember {
        mutableStateOf("Let me check that. Confirm the realtime voice provider path is working.")
    }
    var config by remember { mutableStateOf<RealtimeVoiceConfig?>(null) }
    var status by remember { mutableStateOf("Idle") }
    var summary by remember { mutableStateOf<RealtimeVoiceSummary?>(null) }
    var running by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scope.launch {
                runRealtimeDemo(
                    voiceClient = voiceClient,
                    recorder = recorder,
                    player = player,
                    prompt = prompt,
                    mainHandler = mainHandler,
                    waveform = waveform,
                    events = events,
                    onStatus = { status = it },
                    onSummary = { summary = it },
                    onRunning = { running = it },
                )
            }
        } else {
            status = "Microphone permission denied"
        }
    }

    LaunchedEffect(voiceClient) {
        val client = voiceClient ?: return@LaunchedEffect
        val result = client.getRealtimeVoiceConfig()
        if (result.isSuccess) {
            config = result.getOrNull()
            status = if (config?.enabled == true) "Ready" else "Realtime route disabled"
        } else {
            status = result.exceptionOrNull()?.message ?: "Realtime config failed"
        }
    }

    DisposableEffect(Unit) {
        onDispose { player.stop() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Realtime Voice Lab") },
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
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(title = "Provider") {
                ProviderRow("Status", status)
                ProviderRow("Provider", config?.default_provider ?: "loading")
                ProviderRow("Model", config?.default_model ?: "loading")
                ProviderRow("Voice", config?.default_voice ?: "loading")
                ProviderRow(
                    "Advertised",
                    config?.providers
                        ?.map { it.id }
                        ?.filter { it.isNotBlank() }
                        ?.joinToString(", ")
                        ?.ifBlank { "none" }
                        ?: "loading",
                )
                ProviderRow(
                    "Auth",
                    when {
                        config?.auth?.xai_oauth == true -> "xAI OAuth"
                        config?.auth?.xai_env == true -> "xAI env"
                        else -> "not detected"
                    },
                )
            }

            SectionCard(title = "Prompt") {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    label = { Text("Test prompt") },
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FilledTonalButton(
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                scope.launch {
                                    runRealtimeDemo(
                                        voiceClient = voiceClient,
                                        recorder = recorder,
                                        player = player,
                                        prompt = prompt,
                                        mainHandler = mainHandler,
                                        waveform = waveform,
                                        events = events,
                                        onStatus = { status = it },
                                        onSummary = { summary = it },
                                        onRunning = { running = it },
                                    )
                                }
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = !running && voiceClient != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Mic, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Mic demo")
                    }
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                runRealtimeDemo(
                                    voiceClient = voiceClient,
                                    recorder = null,
                                    player = player,
                                    prompt = prompt,
                                    mainHandler = mainHandler,
                                    waveform = waveform,
                                    events = events,
                                    onStatus = { status = it },
                                    onSummary = { summary = it },
                                    onRunning = { running = it },
                                )
                            }
                        },
                        enabled = !running && voiceClient != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Text demo")
                    }
                }
            }

            SectionCard(title = "Waveform") {
                RealtimeWaveform(values = waveform.toList())
                summary?.let { item ->
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    ProviderRow("First audio", item.firstAudioMs?.let { "${it.toInt()} ms" } ?: "---")
                    ProviderRow("Done", item.responseDoneMs?.let { "${it.toInt()} ms" } ?: "---")
                    ProviderRow("Chunks", item.audioChunks.toString())
                    ProviderRow("Bytes", item.audioBytes.toString())
                    item.eventLogPath?.let { ProviderRow("Log", it) }
                }
            }

            SectionCard(title = "Events") {
                if (events.isEmpty()) {
                    Text(
                        text = "No events yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    events.takeLast(16).forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private suspend fun runRealtimeDemo(
    voiceClient: RelayVoiceClient?,
    recorder: RealtimePcmRecorder?,
    player: RealtimePcmPlayer,
    prompt: String,
    mainHandler: Handler,
    waveform: MutableList<Float>,
    events: MutableList<String>,
    onStatus: (String) -> Unit,
    onSummary: (RealtimeVoiceSummary?) -> Unit,
    onRunning: (Boolean) -> Unit,
) {
    val client = voiceClient ?: return
    onRunning(true)
    onSummary(null)
    waveform.clear()
    events.clear()
    onStatus(if (recorder == null) "Opening websocket" else "Capturing mic")
    val pcm = try {
        recorder?.capture() ?: ByteArray(320)
    } catch (e: Exception) {
        onStatus("Mic capture failed: ${e.message}")
        onRunning(false)
        return
    }
    onStatus("Streaming")
    val result = client.runRealtimeDemo(prompt, pcm) { event ->
        handleRealtimeEvent(event, player, mainHandler, waveform, events)
    }
    if (result.isSuccess) {
        onSummary(result.getOrNull())
        onStatus("Complete")
    } else {
        onStatus(result.exceptionOrNull()?.message ?: "Realtime demo failed")
    }
    onRunning(false)
}

private fun handleRealtimeEvent(
    event: RealtimeVoiceEvent,
    player: RealtimePcmPlayer,
    mainHandler: Handler,
    waveform: MutableList<Float>,
    events: MutableList<String>,
) {
    if (event.type == "voice.audio.delta") {
        val audio = event.audioBase64?.let {
            runCatching { Base64.getDecoder().decode(it) }.getOrNull()
        }
        if (audio != null) {
            player.write(audio, event.sampleRate ?: 24_000)
        }
    }
    mainHandler.post {
        if (event.rmsLevel != null) {
            waveform.add(event.rmsLevel)
            while (waveform.size > 64) waveform.removeAt(0)
        }
        val suffix = when {
            event.byteCount != null -> " ${event.byteCount}b"
            event.message != null -> " ${event.message}"
            else -> ""
        }
        events.add("${event.type}$suffix")
        while (events.size > 64) events.removeAt(0)
    }
}

@Composable
private fun RealtimeWaveform(values: List<Float>) {
    val color = MaterialTheme.colorScheme.primary
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        ),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
        ) {
            val samples = if (values.isEmpty()) List(32) { 0f } else values
            val step = size.width / samples.size.coerceAtLeast(1)
            samples.forEachIndexed { index, value ->
                val clamped = value.coerceIn(0f, 1f)
                val x = index * step + step / 2f
                val half = (size.height * (0.1f + clamped * 0.85f)) / 2f
                drawLine(
                    color = if (values.isEmpty()) Color.Gray.copy(alpha = 0.25f) else color,
                    start = Offset(x, size.height / 2f - half),
                    end = Offset(x, size.height / 2f + half),
                    strokeWidth = step.coerceAtMost(8f).coerceAtLeast(2f),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun ProviderRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.58f),
        )
    }
}
