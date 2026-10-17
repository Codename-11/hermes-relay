package com.hermesandroid.relay.ui.screens

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.hermesandroid.relay.ui.components.VoiceModeOverlay
import com.hermesandroid.relay.R
import com.hermesandroid.relay.audio.RealtimePcmPlayer
import com.hermesandroid.relay.audio.VoicePlayer
import com.hermesandroid.relay.audio.VoiceRecorder
import com.hermesandroid.relay.wake.WakeWordForegroundService
import com.hermesandroid.relay.audio.VoiceSfxPlayer
import com.hermesandroid.relay.data.BotGatewayRoute
import com.hermesandroid.relay.data.VoiceAudioRoute
import com.hermesandroid.relay.data.VoiceEngineMode
import com.hermesandroid.relay.data.VoicePreferencesRepository
import com.hermesandroid.relay.data.VoiceSettings
import com.hermesandroid.relay.network.relay.RelayVoiceClient
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.StandardGptLiveVoiceClient
import com.hermesandroid.relay.network.upstream.StandardHermesVoiceClient
import com.hermesandroid.relay.viewmodel.ChatViewModel
import com.hermesandroid.relay.viewmodel.VoiceViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal fun botVoiceReady(expectedSession: String, actualSession: String?, loading: Boolean, error: String?): Boolean =
    expectedSession.isNotBlank() && expectedSession == actualSession && !loading && error.isNullOrBlank()

internal fun botVoiceUsesStandardFallback(settings: VoiceSettings): Boolean =
    settings.engineMode != VoiceEngineMode.HermesVoiceOutput.storageValue ||
        settings.audioRoute == VoiceAudioRoute.Relay.storageValue

/** The bot owns its audio, history and delegation target for this mounted route only. */
@Composable
internal fun BotChatVoiceHost(
    route: BotGatewayRoute,
    sessionId: String,
    dashboard: DashboardApiClient,
    chat: ChatViewModel,
    onVoiceSessionChanged: (VoiceViewModel, Boolean) -> Unit,
    content: @Composable (onVoice: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val store = remember(route.key, sessionId, dashboard) { ViewModelStore() }
    val voice = remember(store) {
        ViewModelProvider(store, viewModelFactory {
            initializer { VoiceViewModel(context.applicationContext as Application) }
        })[VoiceViewModel::class.java]
    }
    val prefs = remember(voice) {
        VoicePreferencesRepository(context).apply { setActiveScope(route.connectionId, route.profileName) }
    }
    val state by voice.uiState.collectAsState()
    val stats by voice.voiceStats.collectAsState()
    var settings by remember(voice) { mutableStateOf(VoiceSettings()) }
    var initialized by remember(voice) { mutableStateOf(false) }
    var player by remember(voice) { mutableStateOf<VoicePlayer?>(null) }
    var permissionDenied by remember(voice) { mutableStateOf(false) }
    var entering by remember(voice) { mutableStateOf(false) }
    var fallbackDismissed by remember(voice) { mutableStateOf(false) }
    var startupError by remember(voice) { mutableStateOf<String?>(null) }
    val startupErrorMessage = stringResource(R.string.bot_chat_voice_error)

    DisposableEffect(voice, lifecycle) {
        onVoiceSessionChanged(voice, true)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) voice.exitVoiceMode()
        }
        lifecycle.addObserver(observer)
        onDispose {
            onVoiceSessionChanged(voice, false)
            lifecycle.removeObserver(observer)
            voice.exitVoiceMode()
            store.clear()
            player?.release()
        }
    }

    LaunchedEffect(voice, state.voiceMode) {
        if (state.voiceMode) {
            combine(chat.currentSessionId, chat.isLoadingHistory, chat.error) { actual, loading, error ->
                botVoiceReady(sessionId, actual, loading, error)
            }.collect { ready -> if (!ready) voice.exitVoiceMode() }
        }
    }

    val startCall: () -> Unit = {
        if (!entering) {
            entering = true
            scope.launch {
                try {
                    settings = prefs.settings.first()
                    if (lifecycle.currentState == Lifecycle.State.RESUMED &&
                        botVoiceReady(sessionId, chat.currentSessionId.value, chat.isLoadingHistory.value, chat.error.value) &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    ) {
                        if (!initialized) {
                            val audioPlayer = VoicePlayer(context).also { player = it }
                            voice.initialize(
                                // The Relay engine is unavailable here; no Relay URL or credential is supplied.
                                voiceClient = RelayVoiceClient(context, dashboard.okHttpClient, { null }, { null }),
                                voiceAudioClient = StandardHermesVoiceClient(
                                    context, { dashboard.okHttpClient }, { dashboard.baseUrl }, { route.profileName },
                                ),
                                gptLiveVoiceClient = StandardGptLiveVoiceClient(
                                    context, { dashboard.okHttpClient }, { dashboard.baseUrl }, { route.profileName },
                                ),
                                chatViewModel = chat,
                                recorder = VoiceRecorder(context, voice.viewModelScope),
                                player = audioPlayer,
                                realtimePcmPlayer = RealtimePcmPlayer(context),
                                sfxPlayer = VoiceSfxPlayer(context),
                                voicePreferences = prefs,
                                gatewayOnlyVoice = true,
                            )
                            voice.setVoicePrefsConnection(route.connectionId)
                            voice.onProfileChanged(route.profileName)
                            initialized = true
                        }
                        fallbackDismissed = false
                        WakeWordForegroundService.prepareForVoice()
                        voice.enterVoiceMode()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    voice.exitVoiceMode()
                    startupError = error.message ?: startupErrorMessage
                } finally {
                    entering = false
                }
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            scope.launch {
                lifecycle.currentStateFlow.first { it == Lifecycle.State.RESUMED }
                startCall()
            }
        } else permissionDenied = true
    }
    content {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startCall()
        } else {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    BackHandler(state.voiceMode) { voice.exitVoiceMode() }
    if (state.voiceMode) {
        val messages by chat.messages.collectAsState()
        VoiceModeOverlay(
            uiState = state,
            onMicTap = { voice.startListening() },
            onMicRelease = voice::stopListening,
            onInterrupt = { voice.interruptSpeaking() },
            onPauseAutoMode = voice::pauseContinuousMode,
            onDismiss = voice::exitVoiceMode,
            onClearError = voice::clearError,
            transcriptMessages = messages,
            errorEvents = voice.errorEvents,
            voiceEngineMode = if (stats.gptLiveActive) "gpt_live" else stats.voiceEngineMode,
            onCardAction = chat::dispatchCardAction,
            onCardInput = chat::answerAsk,
        )
        if (botVoiceUsesStandardFallback(settings) && !fallbackDismissed) {
            AlertDialog(
                onDismissRequest = { fallbackDismissed = true },
                text = { Text(stringResource(R.string.bot_chat_voice_fallback)) },
                confirmButton = { TextButton(onClick = { fallbackDismissed = true }) { Text(stringResource(android.R.string.ok)) } },
            )
        }
    }
    if (startupError != null) {
        AlertDialog(
            onDismissRequest = { startupError = null },
            text = { Text(startupError.orEmpty()) },
            confirmButton = { TextButton(onClick = { startupError = null }) { Text(stringResource(android.R.string.ok)) } },
        )
    }
    if (permissionDenied) {
        AlertDialog(
            onDismissRequest = { permissionDenied = false },
            text = { Text(stringResource(R.string.bot_chat_voice_permission)) },
            confirmButton = { TextButton(onClick = { permissionDenied = false }) { Text(stringResource(android.R.string.ok)) } },
        )
    }
}
