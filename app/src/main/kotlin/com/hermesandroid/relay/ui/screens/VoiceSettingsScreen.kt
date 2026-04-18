package com.hermesandroid.relay.ui.screens

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesandroid.relay.data.BargeInSensitivity
import com.hermesandroid.relay.data.VoicePreferencesRepository
import com.hermesandroid.relay.data.VoiceSettings
import com.hermesandroid.relay.network.RelayVoiceClient
import com.hermesandroid.relay.network.VoiceConfig
import com.hermesandroid.relay.ui.LocalSnackbarHost
import com.hermesandroid.relay.ui.showHumanError
import com.hermesandroid.relay.util.classifyError
import com.hermesandroid.relay.viewmodel.InteractionMode
import com.hermesandroid.relay.viewmodel.VoiceSettingsViewModel
import com.hermesandroid.relay.viewmodel.VoiceViewModel
import kotlinx.coroutines.launch

/**
 * Dedicated voice-mode settings screen. Reachable from Settings → Voice.
 *
 * Sections:
 *   1. Voice Mode       — interaction mode, silence threshold, auto-TTS
 *   2. Barge-in         — interrupt TTS by speaking; sensitivity + resume (V barge-in)
 *   3. Text-to-Speech   — provider label + voice (from GET /voice/config)
 *   4. Speech-to-Text   — provider label + language picker (stored, not wired)
 *   5. Test Voice       — one-shot synth + playback
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsScreen(
    voiceViewModel: VoiceViewModel,
    voiceClient: RelayVoiceClient?,
    onBack: () -> Unit,
    settingsViewModel: VoiceSettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val prefsRepo = remember { VoicePreferencesRepository(context) }
    val voiceSettings by prefsRepo.settings.collectAsState(initial = VoiceSettings())

    val bargeInPrefs by settingsViewModel.bargeInPrefs.collectAsState()
    val aecAvailable = settingsViewModel.aecAvailable

    var voiceConfig by remember { mutableStateOf<VoiceConfig?>(null) }
    var voiceConfigError by remember { mutableStateOf<String?>(null) }

    // Global snackbar host — voice errors routed through the classifier get
    // shown as snackbars here as well as the inline "unavailable" label below.
    val snackbarHost = LocalSnackbarHost.current

    LaunchedEffect(voiceClient) {
        val client = voiceClient ?: return@LaunchedEffect
        val result = client.getVoiceConfig()
        if (result.isSuccess) {
            voiceConfig = result.getOrNull()
            voiceConfigError = null
        } else {
            // Use the classifier body so "unavailable" expands into something
            // like "Relay unreachable" / "Voice provider offline".
            val human = classifyError(result.exceptionOrNull(), context = "voice_config")
            voiceConfigError = human.body
            snackbarHost.showHumanError(human)
        }
    }

    // Surface VoiceViewModel errors (testVoice synthesize failures etc.) as
    // snackbars while the user is on this screen.
    LaunchedEffect(voiceViewModel) {
        voiceViewModel.errorEvents.collect { err ->
            snackbarHost.showHumanError(err)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice") },
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
            // --- Voice Mode ---
            SectionCard(title = "Voice Mode") {
                Text(
                    text = "Interaction mode",
                    style = MaterialTheme.typography.labelLarge,
                )
                val currentMode = voiceSettings.interactionMode
                listOf(
                    "tap" to "Tap to talk",
                    "hold" to "Hold to talk",
                    "continuous" to "Continuous",
                ).forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = currentMode == value,
                                onClick = {
                                    scope.launch { prefsRepo.setInteractionMode(value) }
                                    voiceViewModel.setInteractionMode(
                                        when (value) {
                                            "hold" -> InteractionMode.HoldToTalk
                                            "continuous" -> InteractionMode.Continuous
                                            else -> InteractionMode.TapToTalk
                                        }
                                    )
                                },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = currentMode == value,
                            onClick = null,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = "Silence threshold: ${voiceSettings.silenceThresholdMs / 1000}s",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = "Auto-stop listening after this much silence",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = voiceSettings.silenceThresholdMs.toFloat(),
                    onValueChange = { newValue ->
                        scope.launch { prefsRepo.setSilenceThresholdMs(newValue.toLong()) }
                    },
                    valueRange = 1000f..10000f,
                    steps = 8,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-TTS", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "Read aloud non-voice assistant messages (coming soon)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = voiceSettings.autoTts,
                        onCheckedChange = { enabled ->
                            scope.launch { prefsRepo.setAutoTts(enabled) }
                        },
                    )
                }
            }

            // --- Barge-in ---
            // Wave 2 / unit B5 of the voice-barge-in plan. Default off. AEC
            // probe at VM init drives the compatibility warning badge.
            SectionCard(title = "Barge-in") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Interrupt when I speak", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "Let me cut in by speaking, like a real conversation",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = bargeInPrefs.enabled,
                        onCheckedChange = { settingsViewModel.setBargeInEnabled(it) },
                    )
                }

                if (!aecAvailable) {
                    // Badge sits directly below the master toggle so the
                    // explanation stays adjacent to the control. We do NOT
                    // disable the toggle — barge-in still works on AEC-less
                    // devices, just more false-trigger-prone.
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = "Your device may have limited echo cancellation. Barge-in quality will vary.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (bargeInPrefs.enabled) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Text(
                        text = "Sensitivity",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = "Off disables detection without flipping the toggle above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    val sensitivityOptions = listOf(
                        BargeInSensitivity.Off,
                        BargeInSensitivity.Low,
                        BargeInSensitivity.Default,
                        BargeInSensitivity.High,
                    )
                    val sensitivityLabels = listOf("Off", "Low", "Default", "High")
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        sensitivityOptions.forEachIndexed { index, option ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = sensitivityOptions.size,
                                ),
                                onClick = { settingsViewModel.setBargeInSensitivity(option) },
                                selected = option == bargeInPrefs.sensitivity,
                            ) {
                                Text(
                                    sensitivityLabels[index],
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Resume after interruption",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = "Continue from the next sentence if you don't keep talking",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = bargeInPrefs.resumeAfterInterruption,
                            onCheckedChange = {
                                settingsViewModel.setResumeAfterInterruption(it)
                            },
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Works best with headphones. On some phones the speaker may false-trigger.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // --- Text-to-Speech ---
            SectionCard(title = "Text-to-Speech") {
                ProviderRow(
                    label = "Provider",
                    value = voiceConfig?.tts?.provider ?: (voiceConfigError?.let { "unavailable" } ?: "loading..."),
                )
                voiceConfig?.tts?.model?.let { model ->
                    ProviderRow(label = "Model", value = model)
                }
                voiceConfig?.tts?.voice?.let { voice ->
                    ProviderRow(label = "Voice", value = voice)
                }
            }

            // --- Speech-to-Text ---
            SectionCard(title = "Speech-to-Text") {
                ProviderRow(
                    label = "Provider",
                    value = voiceConfig?.stt?.provider ?: (voiceConfigError?.let { "unavailable" } ?: "loading..."),
                )
                voiceConfig?.stt?.model?.let { model ->
                    ProviderRow(label = "Model", value = model)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = "Language",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = "Stored only — relay uses its own auto-detect for now",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val languages = listOf(
                    "" to "Auto",
                    "en" to "English",
                    "es" to "Spanish",
                    "fr" to "French",
                    "de" to "German",
                    "ja" to "Japanese",
                    "zh" to "Chinese",
                )
                languages.forEach { (code, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = voiceSettings.language == code,
                                onClick = {
                                    scope.launch { prefsRepo.setLanguage(code) }
                                },
                            )
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = voiceSettings.language == code,
                            onClick = null,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // --- Test Voice ---
            SectionCard(title = "Test Voice") {
                Text(
                    text = "Play a short sample to verify TTS end-to-end.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = { voiceViewModel.testVoice() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Play test")
                }
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
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
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
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
