package com.hermesandroid.relay.viewmodel

import android.app.Application
import android.media.audiofx.AcousticEchoCanceler
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermesandroid.relay.data.BargeInPreferences
import com.hermesandroid.relay.data.BargeInPreferencesRepository
import com.hermesandroid.relay.data.BargeInSensitivity
import com.hermesandroid.relay.network.relay.ProviderOptionsDynamic
import com.hermesandroid.relay.network.relay.RealtimeProviderInfo
import com.hermesandroid.relay.network.relay.RealtimeVoiceConfig
import com.hermesandroid.relay.network.relay.RelayVoiceClient
import com.hermesandroid.relay.network.relay.VoiceConfig
import com.hermesandroid.relay.network.relay.VoiceOutputConfig
import com.hermesandroid.relay.util.HumanError
import com.hermesandroid.relay.util.classifyError
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Read-only snapshot of the relay voice config the settings screen renders.
 *
 * Hoisted out of the composable as part of WP-V3: the relay-config fetch used
 * to live in a `LaunchedEffect` inside `VoiceSettingsScreen` with a dozen
 * sibling `mutableStateOf` holders. Folding the fetch + provider-option maps
 * into one VM-owned state keeps the screen free of network orchestration and
 * makes the read side survive recomposition / config changes.
 *
 * The editable *draft* fields (the provider/model/voice the user is picking
 * before pressing Save) intentionally stay as local state inside the editor
 * cards — only the authoritative server config + advertised provider options
 * live here.
 */
data class VoiceConfigUiState(
    val voiceConfig: VoiceConfig? = null,
    val voiceConfigError: String? = null,
    val voiceOutputConfig: VoiceOutputConfig? = null,
    val voiceOutputConfigError: String? = null,
    val realtimeConfig: RealtimeVoiceConfig? = null,
    val realtimeConfigError: String? = null,
    /** Provider id → freshly fetched provider metadata for the voice-output editor. */
    val voiceOutputProviderOptions: Map<String, RealtimeProviderInfo> = emptyMap(),
    /** Provider id → freshly fetched provider metadata for the realtime editor. */
    val realtimeProviderOptions: Map<String, RealtimeProviderInfo> = emptyMap(),
    /** Provider id currently being refreshed (drives the "Refreshing…" line), or null. */
    val voiceOutputOptionsLoading: String? = null,
    val voiceOutputOptionsStatus: String? = null,
    val realtimeOptionsLoading: String? = null,
    val realtimeOptionsStatus: String? = null,
)

/**
 * View-model backing the Voice Settings screen.
 *
 * Introduced with the voice-barge-in plan (unit B5) to host the once-per-screen
 * [AcousticEchoCanceler.isAvailable] probe. WP-V3 grew it to also own the
 * relay voice-config fetch (previously a `LaunchedEffect` in the screen),
 * exposed as [configState] + [configErrorEvents].
 */
class VoiceSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val bargeInRepo = BargeInPreferencesRepository(application)

    /** Current barge-in preferences — mirrors [BargeInPreferencesRepository.flow]. */
    val bargeInPrefs: StateFlow<BargeInPreferences> = bargeInRepo.flow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BargeInPreferences(),
    )

    /**
     * One-shot probe of [AcousticEchoCanceler.isAvailable] captured at VM
     * construction. The value never changes at runtime on a given device, so
     * we cache it instead of querying on every recomposition. The barge-in
     * section uses this to decide whether to show the "limited echo
     * cancellation" warning badge.
     */
    val aecAvailable: Boolean = AcousticEchoCanceler.isAvailable()

    // --- Relay voice-config fetch (WP-V3) --------------------------------

    private val _configState = MutableStateFlow(VoiceConfigUiState())

    /** Authoritative relay voice config + advertised provider options. */
    val configState: StateFlow<VoiceConfigUiState> = _configState.asStateFlow()

    // One-shot snackbar stream for fetch failures — mirrors VoiceViewModel's
    // pattern so the screen shows the classified error once instead of leaving
    // it only as an inline label. DROP_OLDEST: a burst during a flaky relay
    // shouldn't back-pressure the producer.
    private val _configErrorEvents = MutableSharedFlow<HumanError>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val configErrorEvents: SharedFlow<HumanError> = _configErrorEvents.asSharedFlow()

    private var loadJob: Job? = null

    fun setBargeInEnabled(enabled: Boolean) {
        viewModelScope.launch { bargeInRepo.setEnabled(enabled) }
    }

    fun setBargeInSensitivity(sensitivity: BargeInSensitivity) {
        viewModelScope.launch { bargeInRepo.setSensitivity(sensitivity) }
    }

    fun setResumeAfterInterruption(enabled: Boolean) {
        viewModelScope.launch { bargeInRepo.setResumeAfterInterruption(enabled) }
    }

    /**
     * Fetch the relay voice config (global fallback TTS/STT, streaming voice
     * output, realtime agent) plus the advertised options for each config's
     * default provider. Called from a `LaunchedEffect` keyed on
     * (client, profile, relayReady).
     *
     * On a Standard (no-Relay) connection there is no relay voice surface to
     * query, so we clear to the empty state rather than manufacturing error
     * snackbars for a route the user isn't on.
     */
    fun loadVoiceConfig(client: RelayVoiceClient?, relayVoiceReady: Boolean) {
        loadJob?.cancel()
        if (client == null || !relayVoiceReady) {
            _configState.value = VoiceConfigUiState()
            return
        }
        loadJob = viewModelScope.launch {
            val voiceResult = client.getVoiceConfig()
            if (voiceResult.isSuccess) {
                _configState.update {
                    it.copy(voiceConfig = voiceResult.getOrNull(), voiceConfigError = null)
                }
            } else {
                val human = classifyError(voiceResult.exceptionOrNull(), context = "voice_config", ctx = getApplication())
                _configState.update { it.copy(voiceConfigError = human.body) }
                _configErrorEvents.tryEmit(human)
            }

            val outputResult = client.getVoiceOutputConfig()
            if (outputResult.isSuccess) {
                val config = outputResult.getOrNull()
                _configState.update {
                    it.copy(voiceOutputConfig = config, voiceOutputConfigError = null)
                }
                config?.default_provider?.takeIf { id -> id.isNotBlank() }?.let { providerId ->
                    val optionsResult = client.getVoiceOutputProviderOptions(providerId)
                    optionsResult.getOrNull()?.provider?.let { provider ->
                        _configState.update {
                            it.copy(
                                voiceOutputProviderOptions =
                                    it.voiceOutputProviderOptions + (provider.id to provider),
                            )
                        }
                    }
                }
            } else {
                val human = classifyError(outputResult.exceptionOrNull(), context = "voice_config", ctx = getApplication())
                _configState.update { it.copy(voiceOutputConfigError = human.body) }
                _configErrorEvents.tryEmit(human)
            }

            val realtimeResult = client.getRealtimeAgentConfig()
            if (realtimeResult.isSuccess) {
                val config = realtimeResult.getOrNull()
                _configState.update {
                    it.copy(realtimeConfig = config, realtimeConfigError = null)
                }
                config?.default_provider?.takeIf { id -> id.isNotBlank() }?.let { providerId ->
                    val optionsResult = client.getRealtimeAgentProviderOptions(providerId)
                    optionsResult.getOrNull()?.provider?.let { provider ->
                        _configState.update {
                            it.copy(
                                realtimeProviderOptions =
                                    it.realtimeProviderOptions + (provider.id to provider),
                            )
                        }
                    }
                }
            } else {
                val human = classifyError(realtimeResult.exceptionOrNull(), context = "voice_config", ctx = getApplication())
                _configState.update { it.copy(realtimeConfig = null, realtimeConfigError = human.body) }
            }
        }
    }

    /**
     * Refresh the advertised options for [providerId] in the voice-output
     * editor. When [applyDefaults] is set, [onApplyDefaults] is invoked on the
     * main thread with the fetched provider so the editor can re-seed its draft
     * model/voice/sample-rate from the fresher metadata.
     */
    fun refreshVoiceOutputProviderOptions(
        client: RelayVoiceClient?,
        providerId: String,
        applyDefaults: Boolean,
        onApplyDefaults: (RealtimeProviderInfo) -> Unit,
    ) {
        val trimmed = providerId.trim()
        if (client == null || trimmed.isBlank()) return
        _configState.update {
            it.copy(voiceOutputOptionsLoading = trimmed, voiceOutputOptionsStatus = null)
        }
        viewModelScope.launch {
            val result = client.getVoiceOutputProviderOptions(trimmed)
            _configState.update {
                if (it.voiceOutputOptionsLoading == trimmed) {
                    it.copy(voiceOutputOptionsLoading = null)
                } else {
                    it
                }
            }
            val response = result.getOrNull()
            val provider = response?.provider
            if (provider != null) {
                _configState.update {
                    it.copy(
                        voiceOutputProviderOptions =
                            it.voiceOutputProviderOptions + (provider.id to provider),
                        voiceOutputOptionsStatus = providerOptionStatus(response.dynamic),
                    )
                }
                if (applyDefaults) onApplyDefaults(provider)
            } else if (applyDefaults) {
                _configState.update { it.copy(voiceOutputOptionsStatus = "Provider options unavailable") }
            }
        }
    }

    /** Realtime-editor twin of [refreshVoiceOutputProviderOptions]. */
    fun refreshRealtimeProviderOptions(
        client: RelayVoiceClient?,
        providerId: String,
        applyDefaults: Boolean,
        onApplyDefaults: (RealtimeProviderInfo) -> Unit,
    ) {
        val trimmed = providerId.trim()
        if (client == null || trimmed.isBlank()) return
        _configState.update {
            it.copy(realtimeOptionsLoading = trimmed, realtimeOptionsStatus = null)
        }
        viewModelScope.launch {
            val result = client.getRealtimeAgentProviderOptions(trimmed)
            _configState.update {
                if (it.realtimeOptionsLoading == trimmed) {
                    it.copy(realtimeOptionsLoading = null)
                } else {
                    it
                }
            }
            val response = result.getOrNull()
            val provider = response?.provider
            if (provider != null) {
                _configState.update {
                    it.copy(
                        realtimeProviderOptions =
                            it.realtimeProviderOptions + (provider.id to provider),
                        realtimeOptionsStatus = providerOptionStatus(response.dynamic),
                    )
                }
                if (applyDefaults) onApplyDefaults(provider)
            } else if (applyDefaults) {
                _configState.update { it.copy(realtimeOptionsStatus = "Provider options unavailable") }
            }
        }
    }

    // --- Save write-backs (the editor cards own validation; these just push
    //     the authoritative result back into [configState]) ----------------

    fun setVoiceOutputConfig(config: VoiceOutputConfig?) {
        _configState.update { it.copy(voiceOutputConfig = config, voiceOutputConfigError = null) }
    }

    fun setVoiceOutputError(message: String?) {
        _configState.update { it.copy(voiceOutputConfigError = message) }
    }

    fun setVoiceOutputOptionsStatus(status: String?) {
        _configState.update { it.copy(voiceOutputOptionsStatus = status) }
    }

    fun setRealtimeConfig(config: RealtimeVoiceConfig?) {
        _configState.update { it.copy(realtimeConfig = config, realtimeConfigError = null) }
    }

    fun setRealtimeError(message: String?) {
        _configState.update { it.copy(realtimeConfigError = message) }
    }

    fun setRealtimeOptionsStatus(status: String?) {
        _configState.update { it.copy(realtimeOptionsStatus = status) }
    }
}

/**
 * Collapse a [ProviderOptionsDynamic] block into a one-line status. Moved here
 * with the fetch (WP-V3); previously a private helper in VoiceSettingsScreen.
 */
private fun providerOptionStatus(dynamic: ProviderOptionsDynamic?): String? {
    dynamic ?: return null
    val base = when (dynamic.status) {
        "ok" -> "Provider options refreshed"
        "auth_missing" -> "Server auth missing for dynamic options"
        "error" -> "Dynamic options unavailable"
        "static" -> "Static provider options"
        else -> null
    }
    val count = dynamic.custom_voice_count?.takeIf { it > 0 }?.let { "$it custom voices" }
        ?: dynamic.voice_count?.takeIf { it > 0 }?.let { "$it voices" }
    val cache = dynamic.cache?.takeIf { it == "hit" }?.let { "cached" }
    return listOfNotNull(base, count, cache).joinToString(" · ").takeIf { it.isNotBlank() }
}
