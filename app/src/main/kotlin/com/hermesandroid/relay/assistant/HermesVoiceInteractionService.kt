package com.hermesandroid.relay.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.util.Log
import androidx.core.content.ContextCompat
import com.hermesandroid.relay.wake.WakeWordRecognition
import com.hermesandroid.relay.wake.WakeWordAudioRecord
import com.hermesandroid.relay.wake.MicrophoneOwnershipCoordinator
import com.hermesandroid.relay.wake.WakeWordModelInstaller
import com.hermesandroid.relay.wake.WakeWordPreferences
import com.hermesandroid.relay.wake.WakeWordPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AssistantWakeRuntimeState {
    Stopped,
    Starting,
    Listening,
    PausedForVoice,
    AwaitingSession,
    Error,
}

/**
 * Opt-in Android Digital Assistant service. Android keeps the selected service
 * available in the background; all pre-activation audio is evaluated locally.
 */
class HermesVoiceInteractionService : VoiceInteractionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recognition = WakeWordRecognition(scope)
    private var preferencesJob: Job? = null
    private var retryJob: Job? = null
    private val wakePaused: Boolean
        get() = voiceSessionActive || MicrophoneOwnershipCoordinator.voiceSessionActive.value
    @Volatile private var latestPreferences = WakeWordPreferences()
    @Volatile private var voiceSessionActive = false
    @Volatile private var serviceReady = false
    @Volatile private var preferencesLoaded = false

    override fun onCreate() {
        super.onCreate()
        runningInstance = this
        scope.launch {
            MicrophoneOwnershipCoordinator.voiceSessionActive.collectLatest { active ->
                if (!serviceReady || runningInstance !== this@HermesVoiceInteractionService) return@collectLatest
                if (active) {
                    stopRecognition()
                    setRuntimeState(AssistantWakeRuntimeState.PausedForVoice)
                } else if (preferencesLoaded && !voiceSessionActive) {
                    if (latestPreferences.assistantEnabled) {
                        restartRecognition(latestPreferences)
                    } else {
                        stopRecognition()
                        setRuntimeState(AssistantWakeRuntimeState.Stopped)
                    }
                }
            }
        }
    }

    override fun onReady() {
        super.onReady()
        if (runningInstance !== this) return
        voiceSessionActive = AssistantSessionPersistence.isActive(this)
        serviceReady = true
        preferencesLoaded = false
        preferencesJob?.cancel()
        preferencesJob = scope.launch {
            WakeWordPreferencesRepository(applicationContext).flow.collectLatest { prefs ->
                val firstLoadedPreferences = !preferencesLoaded
                latestPreferences = prefs
                preferencesLoaded = true
                if (firstLoadedPreferences) {
                    mainHandler.post(::drainPendingSessionRequest)
                }
                if (prefs.assistantEnabled && !wakePaused) {
                    restartRecognition(prefs)
                } else {
                    stopRecognition()
                    setRuntimeState(
                        if (wakePaused) {
                            AssistantWakeRuntimeState.PausedForVoice
                        } else {
                            AssistantWakeRuntimeState.Stopped
                        }
                    )
                }
            }
        }
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        val activationId = java.util.UUID.randomUUID().toString()
        showAssistantSession(
            activationId = activationId,
        )
    }

    override fun onShutdown() {
        serviceReady = false
        preferencesLoaded = false
        AssistantLaunchActivity.finishActive()
        stopRecognition()
        preferencesJob?.cancel()
        setRuntimeState(AssistantWakeRuntimeState.Stopped)
        super.onShutdown()
    }

    override fun onDestroy() {
        serviceReady = false
        preferencesLoaded = false
        AssistantLaunchActivity.finishActive()
        stopRecognition()
        preferencesJob?.cancel()
        if (runningInstance === this) runningInstance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onShowSessionFailed(args: Bundle) {
        voiceSessionActive = false
        clearPendingSessionRequest()
        AssistantLaunchActivity.finishActive()
        args.getString(AssistantSessionProtocol.EXTRA_ACTIVATION_ID)?.let { activationId ->
            scope.launch { assistantContextStore(applicationContext).discard(activationId) }
        }
        when (assistantSessionFailureRecovery(latestPreferences.assistantEnabled)) {
            AssistantSessionFailureRecovery.RetryWake -> scheduleRetry()
            AssistantSessionFailureRecovery.Stop ->
                setRuntimeState(AssistantWakeRuntimeState.Stopped)
        }
        super.onShowSessionFailed(args)
    }

    private fun restartRecognition(preferences: WakeWordPreferences) {
        stopRecognition()
        startRecognition(preferences)
    }

    private fun canListen(): Boolean = runningInstance === this && serviceReady &&
        preferencesLoaded && !wakePaused && latestPreferences.assistantEnabled

    private fun startRecognition(preferences: WakeWordPreferences) {
        if (!canListen()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            setRuntimeState(AssistantWakeRuntimeState.Error)
            return
        }
        val files = WakeWordModelInstaller(this).installedFiles()
        if (files == null) {
            setRuntimeState(AssistantWakeRuntimeState.Error)
            return
        }
        setRuntimeState(AssistantWakeRuntimeState.Starting)
        recognition.start(
            canListen = ::canListen,
            createAudio = { WakeWordAudioRecord(files, preferences) },
            onListening = { setRuntimeState(AssistantWakeRuntimeState.Listening) },
            onDetected = {
                setRuntimeState(AssistantWakeRuntimeState.AwaitingSession)
                showAssistantSession()
            },
            onBusy = {
                setRuntimeState(AssistantWakeRuntimeState.PausedForVoice)
                scheduleRetry()
            },
            onError = {
                Log.w(TAG, "Assistant wake listening failed", it)
                setRuntimeState(AssistantWakeRuntimeState.Error)
            },
        )
    }

    private fun showAssistantSession(
        activationId: String = java.util.UUID.randomUUID().toString(),
        manualMic: Boolean = false,
        captureScreenContext: Boolean = false,
    ) {
        if (AssistantRole.status(this) != AssistantRoleStatus.Selected) {
            AssistantLaunchActivity.finishActive()
            return
        }
        if (voiceSessionActive) {
            if (AssistantAppSessionState.active.value) {
                AssistantLaunchActivity.markSessionAccepted()
                return
            }
            voiceSessionActive = false
            AssistantSessionPersistence.setActive(this, false)
        }
        val capturePolicy = assistantSessionCapturePolicy(captureScreenContext) {
            getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
        }
        voiceSessionActive = true
        stopRecognition()
        setRuntimeState(AssistantWakeRuntimeState.AwaitingSession)
        runCatching {
            showSession(
                Bundle().apply {
                    putBoolean(EXTRA_FROM_KEYGUARD, capturePolicy.fromKeyguard)
                    putString(AssistantSessionProtocol.EXTRA_ACTIVATION_ID, activationId)
                    putBoolean(AssistantSessionProtocol.EXTRA_MANUAL_MIC, manualMic)
                    putBoolean(
                        AssistantSessionProtocol.EXTRA_EXPECT_SCREEN_CONTEXT,
                        capturePolicy.expectScreenContext,
                    )
                    putBoolean(
                        AssistantSessionProtocol.EXTRA_START_NEW_SESSION,
                        latestPreferences.startNewSession,
                    )
                },
                capturePolicy.showFlags,
            )
        }.onFailure {
            voiceSessionActive = false
            AssistantLaunchActivity.finishActive()
            if (latestPreferences.assistantEnabled) scheduleRetry()
        }
    }

    private fun drainPendingSessionRequest() {
        if (!assistantPendingRequestCanDrain(serviceReady, preferencesLoaded)) return
        val request = synchronized(pendingLock) {
            pendingSessionRequest.also { pendingSessionRequest = null }
        } ?: return
        pendingHandler.removeCallbacks(pendingExpiry)
        if (request.expiresAtElapsedMs < SystemClock.elapsedRealtime()) {
            AssistantLaunchActivity.finishActive()
            return
        }
        showAssistantSession(
            manualMic = request.manualMic,
            captureScreenContext = request.captureScreenContext,
        )
    }

    private fun setVoiceSessionActiveInternal(active: Boolean) {
        voiceSessionActive = active
        if (wakePaused) {
            stopRecognition()
            setRuntimeState(AssistantWakeRuntimeState.PausedForVoice)
        } else if (latestPreferences.assistantEnabled) {
            scheduleRetry()
        } else {
            setRuntimeState(AssistantWakeRuntimeState.Stopped)
        }
    }

    private fun scheduleRetry() {
        if (!canListen() || retryJob?.isActive == true) return
        retryJob = scope.launch {
            delay(RETRY_DELAY_MS)
            retryJob = null
            if (canListen()) startRecognition(latestPreferences)
        }
    }

    private fun stopRecognition() {
        retryJob?.cancel()
        retryJob = null
        recognition.stop()
    }

    private fun setRuntimeState(state: AssistantWakeRuntimeState) {
        _runtimeState.value = state
    }

    companion object {
        private const val TAG = "HermesAssistant"
        private const val RETRY_DELAY_MS = 500L
        private const val PENDING_SESSION_TIMEOUT_MS = 5_000L
        const val EXTRA_FROM_KEYGUARD = "from_keyguard"

        private val _runtimeState = kotlinx.coroutines.flow.MutableStateFlow(
            AssistantWakeRuntimeState.Stopped
        )
        val runtimeState = _runtimeState.asStateFlow()

        @Volatile private var runningInstance: HermesVoiceInteractionService? = null
        private val pendingLock = Any()
        private val pendingHandler = Handler(Looper.getMainLooper())
        @Volatile private var pendingSessionRequest: PendingSessionRequest? = null
        private var requestDispatchPosted = false
        private val pendingExpiry = Runnable {
            synchronized(pendingLock) { pendingSessionRequest = null }
            AssistantLaunchActivity.finishActive()
        }

        private fun clearPendingSessionRequest() {
            synchronized(pendingLock) {
                pendingSessionRequest = null
                requestDispatchPosted = false
            }
            pendingHandler.removeCallbacks(pendingExpiry)
        }

        /**
         * Public process entry point for strict assistant trampolines. Requests
         * are serialized onto the service main thread and expire rather than
         * being replayed against an unrelated future service lifetime.
         */
        @JvmStatic
        fun requestAssistantSession(
            manualMic: Boolean = false,
            captureScreenContext: Boolean = false,
        ) {
            pendingHandler.removeCallbacks(pendingExpiry)
            val request = PendingSessionRequest(
                manualMic = manualMic,
                captureScreenContext = captureScreenContext,
                expiresAtElapsedMs = SystemClock.elapsedRealtime() + PENDING_SESSION_TIMEOUT_MS,
            )
            val shouldPost = synchronized(pendingLock) {
                pendingSessionRequest = request
                if (requestDispatchPosted) {
                    false
                } else {
                    requestDispatchPosted = true
                    true
                }
            }
            if (!shouldPost) return
            pendingHandler.post {
                synchronized(pendingLock) { requestDispatchPosted = false }
                val currentRequest = synchronized(pendingLock) { pendingSessionRequest } ?: return@post
                val instance = runningInstance
                if (instance != null && assistantPendingRequestCanDrain(
                        instance.serviceReady,
                        instance.preferencesLoaded,
                    )
                ) {
                    pendingHandler.removeCallbacks(pendingExpiry)
                    synchronized(pendingLock) { pendingSessionRequest = null }
                    instance.showAssistantSession(
                        manualMic = currentRequest.manualMic,
                        captureScreenContext = currentRequest.captureScreenContext,
                    )
                    return@post
                }
                pendingHandler.removeCallbacks(pendingExpiry)
                pendingHandler.postDelayed(pendingExpiry, PENDING_SESSION_TIMEOUT_MS)
            }
        }

        fun setVoiceSessionActive(active: Boolean) {
            val instance = runningInstance
            pendingHandler.post {
                if (runningInstance === instance) instance?.setVoiceSessionActiveInternal(active)
            }
            if (!active) AssistantLaunchActivity.finishActive()
        }

        private data class PendingSessionRequest(
            val manualMic: Boolean,
            val captureScreenContext: Boolean,
            val expiresAtElapsedMs: Long,
        )
    }
}

internal enum class AssistantSessionFailureRecovery {
    RetryWake,
    Stop,
}

internal fun assistantSessionFailureRecovery(
    assistantWakeEnabled: Boolean,
): AssistantSessionFailureRecovery = if (assistantWakeEnabled) {
    AssistantSessionFailureRecovery.RetryWake
} else {
    AssistantSessionFailureRecovery.Stop
}

internal fun assistantPendingRequestCanDrain(
    serviceReady: Boolean,
    preferencesLoaded: Boolean,
): Boolean = serviceReady && preferencesLoaded

internal data class AssistantSessionCapturePolicy(
    val fromKeyguard: Boolean,
    val expectScreenContext: Boolean,
    val showFlags: Int,
)

internal fun assistantSessionCapturePolicy(
    captureScreenContext: Boolean,
    isKeyguardLocked: () -> Boolean,
): AssistantSessionCapturePolicy {
    val fromKeyguard = isKeyguardLocked()
    return AssistantSessionCapturePolicy(
        fromKeyguard = fromKeyguard,
        expectScreenContext = captureScreenContext && !fromKeyguard,
        showFlags = assistantSessionShowFlags(fromKeyguard, captureScreenContext),
    )
}

internal fun assistantSessionShowFlags(
    fromKeyguard: Boolean,
    captureScreenContext: Boolean,
): Int =
    if (fromKeyguard || !captureScreenContext) {
        0
    } else {
        VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT
    }
