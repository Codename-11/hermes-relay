package com.hermesandroid.relay.ui.screens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.network.relay.RealtimeProviderInfo
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.viewmodel.VoicePreviewUiState

/** Debug-build-only deterministic host for design QA screenshots. */
class VoiceSettingsDesignQaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HermesRelayTheme { VoiceSettingsDesignQaScene() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceSettingsDesignQaScene() {
    val provider = remember {
        RealtimeProviderInfo(
            id = "xai_tts",
            name = "xAI Grok TTS",
            status = "ready",
            models = listOf("grok-tts", "grok-tts-fast"),
            voices = listOf("eve", "ara", "sal", "rex", "leo"),
            model_labels = mapOf("grok-tts" to "Grok TTS"),
            voice_labels = mapOf("eve" to "Eve", "ara" to "Ara", "sal" to "Sal"),
            recommended_voices = listOf("eve", "ara"),
            supports_tts = true,
        )
    }
    var selectedSection by remember { mutableStateOf(VoiceSettingsSection.Output) }
    var selectedVoice by remember { mutableStateOf("eve") }
    var expanded by remember { mutableStateOf(false) }
    val allVoices = remember {
        listOf(
            VoiceChoice("eve", "Eve", "Warm · expressive", recommended = true),
            VoiceChoice("ara", "Ara", "Clear · balanced", recommended = true),
            VoiceChoice("sal", "Sal", "Calm · grounded"),
            VoiceChoice("rex", "Rex", "Direct · confident"),
            VoiceChoice("leo", "Leo", "Bright · conversational"),
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Voice") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f),
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Hermes Chat + Voice Output", style = MaterialTheme.typography.titleMedium)
                    Text("Default profile · Profile voice", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            VoiceSettingsTabs(selectedSection) { selectedSection = it }
            VoiceProviderGroupCard(
                provider = provider,
                providerValue = provider.id,
                enabled = true,
                providerChoices = listOf(VoiceChoice(provider.id, provider.name.orEmpty())),
                onEnabledChange = {},
                onProviderChange = {},
                controlsEnabled = true,
            )
            ModelAndVoiceGroupCard(
                modelValue = "grok-tts",
                modelChoices = listOf(VoiceChoice("grok-tts", "Grok TTS")),
                voices = previewVoiceChoices(allVoices, selectedVoice),
                allVoices = allVoices,
                selectedVoice = selectedVoice,
                previewState = VoicePreviewUiState(
                    selectionKey = "voice:eve",
                    isPlaying = true,
                    amplitude = 0.42f,
                ),
                onModelChange = {},
                onVoiceChange = { selectedVoice = it },
                onPreviewModel = {},
                onPreviewVoice = {},
                enabled = true,
            )
            LanguageQualityCard(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                language = "English",
                languages = listOf(VoiceChoice("en", "English")),
                onLanguageChange = {},
                sampleRate = "24000",
                sampleRates = listOf(VoiceChoice("24000", "24 kHz")),
                onSampleRateChange = {},
                enabled = true,
            )
        }
    }
}
