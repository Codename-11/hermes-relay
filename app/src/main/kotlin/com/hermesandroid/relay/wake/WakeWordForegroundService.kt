package com.hermesandroid.relay.wake

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hermesandroid.relay.MainActivity
import com.hermesandroid.relay.R
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class WakeWordRuntimeState {
    Stopped,
    Starting,
    Listening,
    PausedForVoice,
    AwaitingUser,
    Error,
}

/**
 * User-started microphone foreground service for experimental local wake word.
 *
 * The service never sends captured audio over a network. Network access is
 * used only by the explicit first-enable model install before this service is
 * started.
 */
class WakeWordForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resourceLock = Any()
    private val stopRequested = AtomicBoolean(false)
    @Volatile private var recognitionJob: Job? = null
    private var recorder: AudioRecord? = null
    private var detector: WakeWordDetector? = null
    private var microphoneLease: MicrophoneLease? = null
    @Volatile private var voiceSessionActive = false
    @Volatile private var currentPreferences = WakeWordPreferences()

    override fun onCreate() {
        super.onCreate()
        runningInstance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Satisfy the modern five-second watchdog before any action branch.
        startForegroundNotification(runtimeState.value)
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecognition()
                setRuntimeState(WakeWordRuntimeState.Stopped)
                scope.launch {
                    runCatching {
                        WakeWordPreferencesRepository(applicationContext).setEnabled(false)
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_START, null -> startFromPersistedSettings()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRecognition()
        if (runningInstance === this) runningInstance = null
        _runtimeState.value = WakeWordRuntimeState.Stopped
        scope.cancel()
        super.onDestroy()
    }

    private fun startFromPersistedSettings() {
        if (recognitionJob?.isActive == true || voiceSessionActive) return
        setRuntimeState(WakeWordRuntimeState.Starting)
        scope.launch {
            val prefs = WakeWordPreferencesRepository(applicationContext).flow.first()
            currentPreferences = prefs
            if (!prefs.enabled) {
                setRuntimeState(WakeWordRuntimeState.Stopped)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }
            startRecognition(prefs)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecognition(preferences: WakeWordPreferences) {
        if (voiceSessionActive || recognitionJob?.isActive == true) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            fail("Microphone permission is required")
            return
        }
        val files = WakeWordModelInstaller(this).installedFiles()
        if (files == null) {
            fail("Wake-word model is not installed")
            return
        }
        val lease = MicrophoneOwnershipCoordinator.tryAcquire(MicrophoneOwner.WakeWord)
        if (lease == null) {
            setRuntimeState(WakeWordRuntimeState.PausedForVoice)
            return
        }
        microphoneLease = lease
        stopRequested.set(false)
        recognitionJob = scope.launch {
            var detected = false
            var unattachedDetector: WakeWordDetector? = null
            try {
                val createdDetector = SherpaWakeWordDetector(
                    files = files,
                    sensitivity = preferences.sensitivity,
                    confirmationFrames = preferences.confirmationFrames,
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
                    throw IllegalStateException("Wake-word microphone failed to initialize")
                }
                synchronized(resourceLock) {
                    if (stopRequested.get()) {
                        createdRecorder.release()
                        return@launch
                    }
                    detector = createdDetector
                    recorder = createdRecorder
                    unattachedDetector = null
                }
                createdRecorder.startRecording()
                setRuntimeState(WakeWordRuntimeState.Listening)

                val samples = ShortArray(FRAME_SAMPLES)
                while (!stopRequested.get()) {
                    val count = createdRecorder.read(samples, 0, samples.size)
                    if (count < 0) throw IllegalStateException("Wake-word microphone read failed: $count")
                    if (count > 0 && createdDetector.accept(samples, count)) {
                        detected = true
                        break
                    }
                }
            } catch (t: Throwable) {
                if (!stopRequested.get()) {
                    Log.w(TAG, "wake listening failed", t)
                    fail(t.message ?: "Wake-word listener failed")
                }
            } finally {
                runCatching { unattachedDetector?.close() }
                releaseRecognitionResources()
                recognitionJob = null
            }
            if (detected && !stopRequested.get()) {
                onWakeDetected(preferences)
            }
        }
    }

    /**
     * Synchronous mic handoff: stop and release AudioRecord before any caller
     * enters the existing voice capture path.
     */
    private fun pauseForVoice() {
        voiceSessionActive = true
        stopRecognition()
        setRuntimeState(WakeWordRuntimeState.PausedForVoice)
    }

    private fun setVoiceSessionActive(active: Boolean) {
        voiceSessionActive = active
        if (active) {
            pauseForVoice()
        } else {
            val previousJob = recognitionJob
            scope.launch {
                // The cancelled reader's finally block owns detector teardown.
                // Waiting prevents it from closing a newly created detector or
                // releasing the new listener's microphone lease as stale work.
                previousJob?.join()
                if (!voiceSessionActive &&
                    currentPreferences.enabled &&
                    WakeWordActivationCoordinator.pending.value == null
                ) {
                    startRecognition(currentPreferences)
                }
            }
        }
    }

    private fun reloadSettings() {
        val previousJob = recognitionJob
        stopRecognition()
        scope.launch {
            // The previous job owns the JNI detector teardown. Do not create a
            // replacement until that teardown has completed.
            previousJob?.join()
            currentPreferences = WakeWordPreferencesRepository(applicationContext).flow.first()
            if (!voiceSessionActive && currentPreferences.enabled) {
                startRecognition(currentPreferences)
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
        // Closing the native detector concurrently with accept() can race in
        // JNI. Stopping AudioRecord unblocks the reader; its finally block owns
        // detector close after accept() has returned.
        recognitionJob?.cancel()
    }

    private fun releaseRecognitionResources() {
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

    private fun onWakeDetected(preferences: WakeWordPreferences) {
        // releaseRecognitionResources() has completed before this callback.
        WakeWordActivationCoordinator.request(
            WakeWordActivation(
                startNewSession = preferences.startNewSession,
                profileRouting = preferences.profileRouting,
            )
        )
        setRuntimeState(WakeWordRuntimeState.AwaitingUser)
    }

    private fun fail(message: String) {
        Log.w(TAG, message)
        setRuntimeState(WakeWordRuntimeState.Error)
    }

    private fun setRuntimeState(state: WakeWordRuntimeState) {
        _runtimeState.value = state
        startForegroundNotification(state)
    }

    @SuppressLint("ForegroundServiceType")
    private fun startForegroundNotification(state: WakeWordRuntimeState) {
        ensureChannel()
        val notification = buildNotification(state)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not foreground wake-word microphone service", t)
            stopSelf()
        }
    }

    private fun buildNotification(state: WakeWordRuntimeState): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val immutableUpdate = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val launchPending = PendingIntent.getActivity(this, 0, launchIntent, immutableUpdate)
        val stopPending = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeWordForegroundService::class.java).setAction(ACTION_STOP),
            immutableUpdate,
        )
        val text = when (state) {
            WakeWordRuntimeState.Starting -> getString(R.string.wake_word_notification_starting)
            WakeWordRuntimeState.Listening -> getString(R.string.wake_word_notification_listening)
            WakeWordRuntimeState.PausedForVoice ->
                getString(R.string.wake_word_notification_paused)
            WakeWordRuntimeState.AwaitingUser ->
                getString(R.string.wake_word_notification_detected)
            WakeWordRuntimeState.Error -> getString(R.string.wake_word_notification_error)
            WakeWordRuntimeState.Stopped -> getString(R.string.wake_word_notification_stopped)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.wake_word_notification_title))
            .setContentText(text)
            .setContentIntent(launchPending)
            .setOngoing(true)
            .setOnlyAlertOnce(state != WakeWordRuntimeState.AwaitingUser)
            .setPriority(
                if (state == WakeWordRuntimeState.AwaitingUser) {
                    NotificationCompat.PRIORITY_HIGH
                } else {
                    NotificationCompat.PRIORITY_LOW
                }
            )
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.wake_word_notification_stop), stopPending)
            .apply {
                if (state == WakeWordRuntimeState.AwaitingUser) {
                    addAction(0, getString(R.string.wake_word_notification_open), launchPending)
                }
            }
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.wake_word_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.wake_word_notification_channel_desc)
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val TAG = "WakeWordService"
        const val CHANNEL_ID = "wake_word_microphone"
        const val NOTIFICATION_ID = 4714
        const val ACTION_START = "com.hermesandroid.relay.wake.START"
        const val ACTION_STOP = "com.hermesandroid.relay.wake.STOP"
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_SAMPLES = 1_600

        private val _runtimeState = MutableStateFlow(WakeWordRuntimeState.Stopped)
        val runtimeState: StateFlow<WakeWordRuntimeState> = _runtimeState.asStateFlow()

        @Volatile
        private var runningInstance: WakeWordForegroundService? = null

        fun start(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, WakeWordForegroundService::class.java)
                .setAction(ACTION_START)
            ContextCompat.startForegroundService(appContext, intent)
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, WakeWordForegroundService::class.java)
            )
            _runtimeState.value = WakeWordRuntimeState.Stopped
        }

        fun prepareForVoice() {
            runningInstance?.pauseForVoice()
        }

        fun setVoiceSessionActive(active: Boolean) {
            runningInstance?.setVoiceSessionActive(active)
        }

        fun reloadSettings() {
            runningInstance?.reloadSettings()
        }
    }
}
