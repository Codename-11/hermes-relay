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
import android.service.voice.VoiceInteractionService
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

    override fun onCreate() {
        super.onCreate()
        runningInstance = this
    }

    override fun onReady() {
        super.onReady()
        if (runningInstance !== this) return
        voiceSessionActive = AssistantSessionPersistence.isActive(this)
        preferencesJob?.cancel()
        preferencesJob = scope.launch {
            WakeWordPreferencesRepository(applicationContext).flow.collectLatest { prefs ->
                latestPreferences = prefs
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
        val activityStarted = runCatching {
            startActivity(
                AssistantSessionProtocol.activationIntent(
                    this,
                    activationId,
                    latestPreferences.startNewSession,
                )
                    .putExtra(EXTRA_FROM_KEYGUARD, true)
            )
        }.onFailure {
            Log.w(TAG, "Could not open keyguard assistant host", it)
        }.isSuccess
        showAssistantSession(
            fromKeyguard = true,
            activationId = activationId.takeIf { activityStarted },
        )
    }

    override fun onShutdown() {
        stopRecognition()
        preferencesJob?.cancel()
        setRuntimeState(AssistantWakeRuntimeState.Stopped)
        super.onShutdown()
    }

    override fun onDestroy() {
        stopRecognition()
        preferencesJob?.cancel()
        if (runningInstance === this) runningInstance = null
        scope.cancel()
        super.onDestroy()
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
                mainHandler.post { showAssistantSession(fromKeyguard = false) }
            }
        }
    }

    private fun showAssistantSession(fromKeyguard: Boolean, activationId: String? = null) {
        voiceSessionActive = true
        stopRecognition()
        setRuntimeState(AssistantWakeRuntimeState.AwaitingSession)
        showSession(
            Bundle().apply {
                putBoolean(EXTRA_FROM_KEYGUARD, fromKeyguard)
                activationId?.let { putString(AssistantSessionProtocol.EXTRA_ACTIVATION_ID, it) }
                putBoolean(
                    AssistantSessionProtocol.EXTRA_START_NEW_SESSION,
                    latestPreferences.startNewSession,
                )
            },
            0,
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
        const val EXTRA_FROM_KEYGUARD = "from_keyguard"

        private val _runtimeState = kotlinx.coroutines.flow.MutableStateFlow(
            AssistantWakeRuntimeState.Stopped
        )
        val runtimeState = _runtimeState.asStateFlow()

        @Volatile private var runningInstance: HermesVoiceInteractionService? = null

        fun setVoiceSessionActive(active: Boolean) {
            runningInstance?.setVoiceSessionActiveInternal(active)
        }
    }
}
