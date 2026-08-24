package com.hermesandroid.relay.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.util.Log
import androidx.core.content.ContextCompat
import com.hermesandroid.relay.wake.MicrophoneLease
import com.hermesandroid.relay.wake.MicrophoneOwner
import com.hermesandroid.relay.wake.MicrophoneOwnershipCoordinator
import com.hermesandroid.relay.wake.SherpaWakeWordDetector
import com.hermesandroid.relay.wake.WakeWordModelInstaller
import com.hermesandroid.relay.wake.WakeWordPreferences
import com.hermesandroid.relay.wake.WakeWordPreferencesRepository
import java.util.concurrent.atomic.AtomicBoolean
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stopRequested = AtomicBoolean(false)
    private val resourceLock = Any()
    private var preferencesJob: Job? = null
    private var recognitionJob: Job? = null
    private var recorder: AudioRecord? = null
    private var detector: SherpaWakeWordDetector? = null
    private var microphoneLease: MicrophoneLease? = null
    @Volatile private var latestPreferences = WakeWordPreferences()
    @Volatile private var voiceSessionActive = false
    @Volatile private var serviceReady = false
    @Volatile private var preferencesLoaded = false

    override fun onCreate() {
        super.onCreate()
        runningInstance = this
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
                if (prefs.assistantEnabled && !voiceSessionActive) {
                    restartRecognition(prefs)
                } else {
                    stopRecognition()
                    setRuntimeState(
                        if (voiceSessionActive) {
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

    private suspend fun restartRecognition(preferences: WakeWordPreferences) {
        val previous = recognitionJob
        stopRecognition()
        previous?.join()
        if (!voiceSessionActive && preferences.assistantEnabled) {
            startRecognition(preferences)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecognition(preferences: WakeWordPreferences) {
        if (voiceSessionActive || recognitionJob?.isActive == true) return
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
        val lease = MicrophoneOwnershipCoordinator.tryAcquire(MicrophoneOwner.WakeWord)
        if (lease == null) {
            setRuntimeState(AssistantWakeRuntimeState.PausedForVoice)
            scheduleRetry()
            return
        }
        microphoneLease = lease
        stopRequested.set(false)
        setRuntimeState(AssistantWakeRuntimeState.Starting)
        recognitionJob = scope.launch {
            var detected = false
            var unattachedDetector: SherpaWakeWordDetector? = null
            try {
                val createdDetector = SherpaWakeWordDetector(
                    files,
                    preferences.sensitivity,
                    preferences.confirmationFrames,
                )
                unattachedDetector = createdDetector
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(SAMPLE_RATE / 5 * 2)
                val createdRecorder = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuffer * 2)
                    .build()
                if (createdRecorder.state != AudioRecord.STATE_INITIALIZED) {
                    createdRecorder.release()
                    error("Assistant wake microphone failed to initialize")
                }
                synchronized(resourceLock) {
                    if (stopRequested.get()) {
                        createdRecorder.release()
                        return@launch
                    }
                    recorder = createdRecorder
                    detector = createdDetector
                    unattachedDetector = null
                }
                createdRecorder.startRecording()
                setRuntimeState(AssistantWakeRuntimeState.Listening)
                val samples = ShortArray(FRAME_SAMPLES)
                while (!stopRequested.get()) {
                    val count = createdRecorder.read(samples, 0, samples.size)
                    if (count < 0) error("Assistant wake microphone read failed: $count")
                    if (count > 0 && createdDetector.accept(samples, count)) {
                        detected = true
                        break
                    }
                }
            } catch (t: Throwable) {
                if (!stopRequested.get()) {
                    Log.w(TAG, "Assistant wake listening failed", t)
                    setRuntimeState(AssistantWakeRuntimeState.Error)
                }
            } finally {
                runCatching { unattachedDetector?.close() }
                releaseResources()
                recognitionJob = null
            }
            if (detected && !stopRequested.get()) {
                setRuntimeState(AssistantWakeRuntimeState.AwaitingSession)
                mainHandler.post {
                    showAssistantSession()
                }
            }
        }
    }

    private fun showAssistantSession(
        activationId: String = java.util.UUID.randomUUID().toString(),
        manualMic: Boolean = false,
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
        val fromKeyguard = getSystemService(android.app.KeyguardManager::class.java)
            ?.isKeyguardLocked == true
        val expectScreenContext = manualMic && !fromKeyguard
        voiceSessionActive = true
        stopRecognition()
        setRuntimeState(AssistantWakeRuntimeState.AwaitingSession)
        runCatching {
            showSession(
                Bundle().apply {
                    putBoolean(EXTRA_FROM_KEYGUARD, fromKeyguard)
                    putString(AssistantSessionProtocol.EXTRA_ACTIVATION_ID, activationId)
                    putBoolean(AssistantSessionProtocol.EXTRA_MANUAL_MIC, manualMic)
                    putBoolean(
                        AssistantSessionProtocol.EXTRA_EXPECT_SCREEN_CONTEXT,
                        expectScreenContext,
                    )
                    putBoolean(
                        AssistantSessionProtocol.EXTRA_START_NEW_SESSION,
                        latestPreferences.startNewSession,
                    )
                },
                assistantSessionShowFlags(fromKeyguard, manualMic),
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
        )
    }

    private fun setVoiceSessionActiveInternal(active: Boolean) {
        voiceSessionActive = active
        if (active) {
            stopRecognition()
            setRuntimeState(AssistantWakeRuntimeState.PausedForVoice)
        } else if (latestPreferences.assistantEnabled) {
            scheduleRetry()
        } else {
            setRuntimeState(AssistantWakeRuntimeState.Stopped)
        }
    }

    private fun scheduleRetry() {
        if (recognitionJob?.isActive == true || voiceSessionActive) return
        recognitionJob = scope.launch {
            delay(RETRY_DELAY_MS)
            recognitionJob = null
            if (!voiceSessionActive && latestPreferences.assistantEnabled) {
                startRecognition(latestPreferences)
            }
        }
    }

    private fun stopRecognition() {
        stopRequested.set(true)
        synchronized(resourceLock) {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            recorder = null
            microphoneLease?.let(MicrophoneOwnershipCoordinator::release)
            microphoneLease = null
        }
        recognitionJob?.cancel()
    }

    private fun releaseResources() {
        synchronized(resourceLock) {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            recorder = null
            runCatching { detector?.close() }
            detector = null
            microphoneLease?.let(MicrophoneOwnershipCoordinator::release)
            microphoneLease = null
        }
    }

    private fun setRuntimeState(state: AssistantWakeRuntimeState) {
        _runtimeState.value = state
    }

    companion object {
        private const val TAG = "HermesAssistant"
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_SAMPLES = 1_600
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
        ) {
            pendingHandler.removeCallbacks(pendingExpiry)
            val request = PendingSessionRequest(
                manualMic = manualMic,
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
                    instance.showAssistantSession(manualMic = currentRequest.manualMic)
                    return@post
                }
                pendingHandler.removeCallbacks(pendingExpiry)
                pendingHandler.postDelayed(pendingExpiry, PENDING_SESSION_TIMEOUT_MS)
            }
        }

        fun setVoiceSessionActive(active: Boolean) {
            runningInstance?.setVoiceSessionActiveInternal(active)
            if (!active) AssistantLaunchActivity.finishActive()
        }

        private data class PendingSessionRequest(
            val manualMic: Boolean,
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

internal fun assistantSessionShowFlags(fromKeyguard: Boolean, manualMic: Boolean): Int =
    if (fromKeyguard || !manualMic) {
        0
    } else {
        VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT
    }
