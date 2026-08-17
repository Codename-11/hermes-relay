package com.hermesandroid.relay.runtime

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.hermesandroid.relay.HermesRelayApp
import com.hermesandroid.relay.data.VoicePreferencesRepository
import com.hermesandroid.relay.data.VoiceSettings
import com.hermesandroid.relay.data.PersistentChatComposerDraftStore
import java.io.File
import com.hermesandroid.relay.network.relay.RelayVoiceClient
import com.hermesandroid.relay.viewmodel.ChatViewModel
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.viewmodel.VoiceViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Application-lifetime owner for the runtime state shared by the normal app UI
 * and service-started assistant turns.
 *
 * The runtime deliberately lives in the main application process. The isolated
 * `:assistant_session` process exchanges snapshots and lifecycle commands over
 * the assistant protocol and must never construct a second set of voice
 * ViewModels or microphone collaborators.
 */
class HermesProcessRuntime internal constructor(
    private val application: HermesRelayApp,
) {
    private val viewModelStore = ViewModelStore()
    private val viewModelProvider = ViewModelProvider(
        viewModelStore,
        ViewModelProvider.AndroidViewModelFactory.getInstance(application),
    )
    private val initializationMutex = Mutex()
    private val activationLock = Any()
    private var activationGeneration = 0L
    private var currentActivationId: String? = null
    private var activationJob: Job? = null
    private val runtimeJob = SupervisorJob()
    private val binder by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        HermesRuntimeBinder(application, this)
    }
    private val _initializationState =
        MutableStateFlow(HermesRuntimeInitializationState.Uninitialized)

    /**
     * Process-lifetime scope for runtime binders and state bridges. UI work
     * should continue to use its lifecycle-aware scopes.
     */
    val coroutineScope: CoroutineScope =
        CoroutineScope(runtimeJob + Dispatchers.Main.immediate)

    /**
     * Existing app ViewModels, now owned by the process runtime instead of an
     * Activity. Each property remains lazy so merely starting the Application
     * does not initialize network, chat, or microphone state.
     */
    val connectionViewModel: ConnectionViewModel by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        viewModelProvider[ConnectionViewModel::class.java]
    }

    val chatViewModel: ChatViewModel by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        viewModelProvider[ChatViewModel::class.java].also { chat ->
            chat.installComposerDraftStore(
                PersistentChatComposerDraftStore(
                    File(application.noBackupFilesDir, "chat-composer-drafts"),
                ),
            )
        }
    }

    val voiceViewModel: VoiceViewModel by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        viewModelProvider[VoiceViewModel::class.java]
    }

    val initializationState: StateFlow<HermesRuntimeInitializationState> =
        _initializationState.asStateFlow()

    val voiceActivationReadiness: StateFlow<HermesVoiceActivationReadiness>
        get() = binder.voiceActivationReadiness

    val assistantSnapshot: StateFlow<com.hermesandroid.relay.assistant.AssistantSessionSnapshot>
        get() = binder.assistantSnapshot

    /**
     * Process-owned collaborators still consumed by Compose screens. Callers
     * must await [ensureInitialized] before reading [relayVoiceClient].
     */
    val relayVoiceClient: RelayVoiceClient
        get() = binder.requireRelayVoiceClient()

    val voicePreferences: VoicePreferencesRepository
        get() = binder.voicePreferencesRepository

    val voiceSettings: StateFlow<VoiceSettings>
        get() = binder.voiceSettings

    /**
     * Runs the non-UI runtime wiring exactly once. A failed attempt returns the
     * runtime to [HermesRuntimeInitializationState.Uninitialized] so a later
     * foreground or assistant activation can retry cleanly.
     */
    suspend fun ensureInitialized() {
        if (_initializationState.value == HermesRuntimeInitializationState.Ready) return
        initializationMutex.withLock {
            if (_initializationState.value == HermesRuntimeInitializationState.Ready) return
            _initializationState.value = HermesRuntimeInitializationState.Initializing
            try {
                withContext(Dispatchers.Main.immediate) {
                    binder.bind()
                }
                _initializationState.value = HermesRuntimeInitializationState.Ready
            } catch (failure: Throwable) {
                _initializationState.value = HermesRuntimeInitializationState.Uninitialized
                throw failure
            }
        }
    }

    fun requestVoiceActivation(
        activationId: String,
        startNewSession: Boolean = true,
        timeoutMs: Long = DEFAULT_VOICE_ACTIVATION_TIMEOUT_MS,
        onFailure: (Throwable) -> Unit = {},
    ) {
        val nextJob = synchronized(activationLock) {
            // The assistant session process can replay the same activation while
            // being recreated. That replay must not re-arm the recorder.
            if (currentActivationId == activationId) return

            activationJob?.cancel()
            activationGeneration += 1
            val generation = activationGeneration
            currentActivationId = activationId
            coroutineScope.launch(start = CoroutineStart.LAZY) {
                try {
                    ensureInitialized()
                    binder.activateVoice(
                        startNewSession = startNewSession,
                        timeoutMs = timeoutMs,
                        isCurrent = {
                            synchronized(activationLock) {
                                activationGeneration == generation &&
                                    currentActivationId == activationId
                            }
                        },
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    onFailure(failure)
                }
            }.also { activationJob = it }
        }
        nextJob.start()
    }

    fun cancelVoice() {
        synchronized(activationLock) {
            activationGeneration += 1
            currentActivationId = null
            activationJob?.cancel()
            activationJob = null
        }
        if (_initializationState.value != HermesRuntimeInitializationState.Uninitialized) {
            binder.cancelVoice()
        }
    }

    /**
     * Production Android processes are torn down as a unit. This explicit
     * cleanup seam exists for local/instrumentation hosts that construct more
     * than one Application instance in a single VM.
     */
    internal fun clear() {
        synchronized(activationLock) {
            activationGeneration += 1
            currentActivationId = null
            activationJob?.cancel()
            activationJob = null
        }
        if (_initializationState.value != HermesRuntimeInitializationState.Uninitialized) {
            binder.clear()
        }
        coroutineScope.cancel()
        viewModelStore.clear()
        _initializationState.value = HermesRuntimeInitializationState.Uninitialized
    }

    private companion object {
        const val DEFAULT_VOICE_ACTIVATION_TIMEOUT_MS = 20_000L
    }
}

enum class HermesRuntimeInitializationState {
    Uninitialized,
    Initializing,
    Ready,
}
