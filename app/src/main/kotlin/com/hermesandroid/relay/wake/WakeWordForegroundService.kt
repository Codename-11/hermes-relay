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
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hermesandroid.relay.MainActivity
import com.hermesandroid.relay.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class WakeWordRuntimeState {
    Stopped,
    Starting,
    Listening,
    PausedForVoice,
    AwaitingUser,
    Error,
}

enum class WakeWordTestPhase {
    Idle,
    Listening,
    Detected,
    TimedOut,
    Unavailable,
}

data class WakeWordTestState(
    val phase: WakeWordTestPhase = WakeWordTestPhase.Idle,
    val inputLevel: Float = 0f,
)

internal fun wakeWordInputLevel(samples: ShortArray, count: Int): Float {
    if (count <= 0) return 0f
    var peak = 0
    for (index in 0 until count.coerceAtMost(samples.size)) {
        peak = maxOf(peak, abs(samples[index].toInt()))
    }
    return (peak / 32768f).coerceIn(0f, 1f)
}

/**
 * User-started microphone foreground service for experimental local wake word.
 *
 * The service never sends captured audio over a network. Network access is
 * used only by the explicit first-enable model install before this service is
 * started.
 */
class WakeWordForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recognition = WakeWordRecognition(scope)
    private var settingsJob: Job? = null
    private var settingsGeneration = 0L
    private var destroyed = false
    @Volatile private var currentPreferences = WakeWordPreferences()
    private var testTimeoutJob: Job? = null
    private val voiceSessionActive: Boolean
        get() = MicrophoneOwnershipCoordinator.voiceSessionActive.value

    override fun onCreate() {
        super.onCreate()
        runningInstance = this
        scope.launch {
            combine(
                MicrophoneOwnershipCoordinator.voiceSessionActive,
                WakeWordActivationCoordinator.pending,
                MicrophoneOwnershipCoordinator.owner,
            ) { active, pending, _ -> active to pending }.collectLatest { (active, pending) ->
                if (destroyed || runningInstance !== this@WakeWordForegroundService) return@collectLatest
                if (active) {
                    pauseForVoice()
                } else if (pending == null) {
                    startFromPersistedSettings()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Satisfy the modern five-second watchdog before any action branch.
        startForegroundNotification(runtimeState.value)
        when (intent?.action) {
            ACTION_STOP -> {
                destroyed = true
                settingsGeneration++
                settingsJob?.cancel()
                finishWakeWordTest(WakeWordTestPhase.Idle)
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
        destroyed = true
        settingsGeneration++
        stopRecognition()
        finishWakeWordTest(WakeWordTestPhase.Idle)
        if (runningInstance === this) runningInstance = null
        _runtimeState.value = WakeWordRuntimeState.Stopped
        scope.cancel()
        super.onDestroy()
    }

    private fun startFromPersistedSettings() {
        if (destroyed || recognition.isActive || voiceSessionActive) return
        settingsJob?.cancel()
        val expected = ++settingsGeneration
        setRuntimeState(WakeWordRuntimeState.Starting)
        settingsJob = scope.launch {
            val prefs = WakeWordPreferencesRepository(applicationContext).flow.first()
            if (destroyed || expected != settingsGeneration) return@launch
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

    private fun canListen(): Boolean = runningInstance === this && !destroyed && !voiceSessionActive &&
        currentPreferences.enabled && WakeWordActivationCoordinator.pending.value == null

    private fun startRecognition(preferences: WakeWordPreferences) {
        if (!canListen()) return
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
        recognition.start(
            canListen = ::canListen,
            createAudio = { WakeWordAudioRecord(files, preferences) },
            onListening = { setRuntimeState(WakeWordRuntimeState.Listening) },
            onSamples = { samples, count ->
                if (_testState.value.phase == WakeWordTestPhase.Listening) {
                    _testState.value = _testState.value.copy(inputLevel = wakeWordInputLevel(samples, count))
                }
            },
            onDetected = {
                if (_testState.value.phase == WakeWordTestPhase.Listening) {
                    finishWakeWordTest(WakeWordTestPhase.Detected)
                    startRecognition(currentPreferences)
                } else {
                    onWakeDetected(preferences)
                }
            },
            onBusy = { setRuntimeState(WakeWordRuntimeState.PausedForVoice) },
            onError = { fail(it.message ?: "Wake-word listener failed") },
        )
    }

    private fun startWakeWordTest() {
        if (voiceSessionActive ||
            runtimeState.value != WakeWordRuntimeState.Listening ||
            !recognition.isActive
        ) {
            finishWakeWordTest(WakeWordTestPhase.Unavailable)
            return
        }
        testTimeoutJob?.cancel()
        _testState.value = WakeWordTestState(phase = WakeWordTestPhase.Listening)
        Log.i(TAG, "wake-word microphone test armed")
        testTimeoutJob = scope.launch {
            delay(TEST_DURATION_MS)
            if (_testState.value.phase == WakeWordTestPhase.Listening) {
                Log.i(TAG, "wake-word microphone test timed out")
                finishWakeWordTest(WakeWordTestPhase.TimedOut)
            }
        }
    }

    private fun finishWakeWordTest(phase: WakeWordTestPhase) {
        testTimeoutJob?.cancel()
        testTimeoutJob = null
        _testState.value = WakeWordTestState(phase = phase)
    }

    private fun pauseForVoice() {
        settingsGeneration++
        settingsJob?.cancel()
        if (_testState.value.phase == WakeWordTestPhase.Listening) {
            finishWakeWordTest(WakeWordTestPhase.Unavailable)
        }
        stopRecognition()
        setRuntimeState(WakeWordRuntimeState.PausedForVoice)
    }

    private fun reloadSettings() {
        finishWakeWordTest(WakeWordTestPhase.Idle)
        stopRecognition()
        startFromPersistedSettings()
    }

    private fun stopRecognition() {
        recognition.stop()
    }

    private fun onWakeDetected(preferences: WakeWordPreferences) {
        // The reader and its lease have been released before this callback.
        Log.i(TAG, "wake phrase detected; microphone released before activation")
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
        private const val TEST_DURATION_MS = 10_000L

        private val _runtimeState = MutableStateFlow(WakeWordRuntimeState.Stopped)
        val runtimeState: StateFlow<WakeWordRuntimeState> = _runtimeState.asStateFlow()
        private val _testState = MutableStateFlow(WakeWordTestState())
        val testState: StateFlow<WakeWordTestState> = _testState.asStateFlow()

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
            _testState.value = WakeWordTestState()
        }

        fun reloadSettings() {
            runningInstance?.reloadSettings()
        }

        fun startTest() {
            runningInstance?.startWakeWordTest()
                ?: run {
                    _testState.value = WakeWordTestState(
                        phase = WakeWordTestPhase.Unavailable,
                    )
                }
        }
    }
}
