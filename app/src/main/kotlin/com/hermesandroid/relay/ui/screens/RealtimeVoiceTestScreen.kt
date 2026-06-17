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
import androidx.compose.runtime.mutableFloatStateOf
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
import com.hermesandroid.relay.network.relay.RealtimeAgentSessionControl
import com.hermesandroid.relay.network.relay.RealtimeVoiceConfig
import com.hermesandroid.relay.network.relay.RealtimeVoiceEvent
import com.hermesandroid.relay.network.relay.RealtimeVoiceSummary
import com.hermesandroid.relay.network.relay.RelayVoiceClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
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
    val player = remember { RealtimePcmPlayer(context) }
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
    var recording by remember { mutableStateOf(false) }
    var micLevel by remember { mutableFloatStateOf(0f) }
    var transcript by remember { mutableStateOf("") }

    // Mic demo state machine: tap to start recording, tap again to stop & send.
    // The captured speech runs through the agent path (STT + Hermes + speech).
    val startMicDemo: () -> Unit = {
        transcript = ""
        waveform.clear()
        recording = true
        status = "Recording — tap Stop & send"
        scope.launch {
            val pcm = try {
                recorder.captureUntilStopped(onLevel = { micLevel = it })
            } catch (e: Exception) {
                status = "Mic capture failed: ${e.message}"
                ByteArray(0)
            }
            recording = false
            micLevel = 0f
            if (pcm.size < 3_200) { // < ~100ms @ 16kHz → nothing useful captured
                status = "Recording too short"
                return@launch
            }
            runRealtimeAgentMic(
                voiceClient = voiceClient,
                player = player,
                pcm = pcm,
                mainHandler = mainHandler,
                events = events,
                scope = scope,
                onStatus = { status = it },
                onTranscript = { transcript = it },
                onSummary = { summary = it },
                onRunning = { running = it },
            )
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startMicDemo() else status = "Microphone permission denied"
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

    // Drive the waveform from the playback cursor (~30Hz) so each bar reflects
    // the audio at the hardware head right now — matching what is audible — and
    // continues through the post-stream drain. This replaces the old arrival-time
    // RMS feed, which led the audio by the prebuffer + AudioTrack buffer depth
    // and looked laggy.
    LaunchedEffect(Unit) {
        while (true) {
            when {
                // While recording, show the live mic input level.
                recording -> {
                    waveform.add(micLevel)
                    while (waveform.size > 64) waveform.removeAt(0)
                }
                // During playback, show the audio at the hardware cursor.
                player.isActive -> {
                    waveform.add(player.playbackAmplitude())
                    while (waveform.size > 64) waveform.removeAt(0)
                }
            }
            delay(33)
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
                    label = { Text("Text demo prompt (spoken back via provider)") },
                    enabled = !running && !recording,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Mic demo speaks to the agent: it transcribes what you say, " +
                        "Hermes responds, and the reply is spoken back. Text demo just " +
                        "synthesizes the prompt above (no mic, no agent).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FilledTonalButton(
                        onClick = {
                            if (recording) {
                                recorder.requestStop()
                            } else {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    startMicDemo()
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                        enabled = !running && voiceClient != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Mic, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(if (recording) "Stop & send" else "Mic demo")
                    }
                    FilledTonalButton(
                        onClick = {
                            transcript = ""
                            waveform.clear()
                            scope.launch {
                                runRealtimeDemo(
                                    voiceClient = voiceClient,
                                    player = player,
                                    prompt = prompt,
                                    mainHandler = mainHandler,
                                    events = events,
                                    onStatus = { status = it },
                                    onTranscript = { transcript = it },
                                    onSummary = { summary = it },
                                    onRunning = { running = it },
                                )
                            }
                        },
                        enabled = !running && !recording && voiceClient != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Text demo")
                    }
                }
            }

            if (transcript.isNotBlank()) {
                SectionCard(title = "Transcript") {
                    Text(
                        text = transcript,
                        style = MaterialTheme.typography.bodyMedium,
                    )
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

// Audio for both demos arrives as voice.audio.delta (provider) or
// voice.output_audio.delta (agent); isAudioDelta covers both. The waveform is
// NOT fed here — a ticker in the composable samples the playback cursor so it
// tracks what is audible, not what just arrived over the socket.
private fun writeEventAudio(event: RealtimeVoiceEvent, player: RealtimePcmPlayer) {
    if (!event.isAudioDelta) return
    val audio = event.audioBase64?.let {
        runCatching { Base64.getDecoder().decode(it) }.getOrNull()
    } ?: return
    if (audio.isNotEmpty()) {
        player.write(audio, event.sampleRate ?: 24_000)
    }
}

private fun logLabEvent(
    event: RealtimeVoiceEvent,
    mainHandler: Handler,
    events: MutableList<String>,
) {
    mainHandler.post {
        val suffix = when {
            event.byteCount != null -> " ${event.byteCount}b"
            event.message != null -> " ${event.message}"
            else -> ""
        }
        events.add("${event.type}$suffix")
        while (events.size > 64) events.removeAt(0)
    }
}

/**
 * Text demo — raw provider TTS. Sends the typed prompt to /voice/realtime/ and
 * plays the synthesized speech. No mic, no STT: this exercises the provider's
 * audio-output path in isolation.
 */
private suspend fun runRealtimeDemo(
    voiceClient: RelayVoiceClient?,
    player: RealtimePcmPlayer,
    prompt: String,
    mainHandler: Handler,
    events: MutableList<String>,
    onStatus: (String) -> Unit,
    onTranscript: (String) -> Unit,
    onSummary: (RealtimeVoiceSummary?) -> Unit,
    onRunning: (Boolean) -> Unit,
) {
    val client = voiceClient ?: return
    onRunning(true)
    onSummary(null)
    events.clear()
    onStatus("Streaming")
    val result = client.runRealtimeDemo(prompt, ByteArray(0)) { event ->
        writeEventAudio(event, player)
        logLabEvent(event, mainHandler, events)
        if (event.type == "voice.transcript.final") {
            event.text?.let { t -> mainHandler.post { onTranscript("Spoken: $t") } }
        }
    }
    player.flushBufferedPlayback()
    if (result.isSuccess) {
        onSummary(result.getOrNull())
        onStatus("Complete")
    } else {
        onStatus(result.exceptionOrNull()?.message ?: "Realtime demo failed")
    }
    onRunning(false)
}

/**
 * Mic demo — full agent path. Sends the captured speech to /voice/realtime-agent/,
 * which transcribes it (surfaced as voice.input_transcript.*), lets Hermes respond,
 * and streams the spoken reply back. This is the genuinely conversational test:
 * what you say drives the response, and the transcript shows what was heard.
 */
private suspend fun runRealtimeAgentMic(
    voiceClient: RelayVoiceClient?,
    player: RealtimePcmPlayer,
    pcm: ByteArray,
    mainHandler: Handler,
    events: MutableList<String>,
    scope: CoroutineScope,
    onStatus: (String) -> Unit,
    onTranscript: (String) -> Unit,
    onSummary: (RealtimeVoiceSummary?) -> Unit,
    onRunning: (Boolean) -> Unit,
) {
    val client = voiceClient ?: return
    onRunning(true)
    onSummary(null)
    events.clear()
    onStatus("Transcribing + thinking")
    val heard = StringBuilder()
    val result = client.runRealtimeAgent(
        prompt = "",
        inputPcm = pcm,
        inputSampleRate = 16_000,
    ) { event, control ->
        writeEventAudio(event, player)
        logLabEvent(event, mainHandler, events)
        when (event.type) {
            "voice.input_transcript.delta" -> {
                event.delta?.let { heard.append(it) }
                val text = heard.toString()
                mainHandler.post { onTranscript("You said: $text") }
            }
            "voice.input_transcript.final" -> {
                val text = event.text ?: heard.toString()
                mainHandler.post {
                    onTranscript("You said: $text")
                    onStatus("Speaking")
                }
            }
            "voice.playback_drain.requested" -> {
                // The agent gates tool follow-ups on playback draining; ack it so
                // the turn doesn't stall waiting on us.
                val drainMs = player.flushBufferedPlayback()
                scope.launch {
                    if (drainMs > 0) delay(drainMs.coerceAtMost(2_000))
                    control.sendPlaybackDrained(
                        callId = event.toolCallId,
                        playedAudioEventId = null,
                    )
                }
            }
        }
    }
    player.flushBufferedPlayback()
    if (result.isSuccess) {
        onSummary(result.getOrNull())
        onStatus("Complete")
    } else {
        onStatus(result.exceptionOrNull()?.message ?: "Realtime agent failed")
    }
    onRunning(false)
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
