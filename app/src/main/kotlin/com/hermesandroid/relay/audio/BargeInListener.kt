package com.hermesandroid.relay.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.hermesandroid.relay.wake.MicrophoneLease
import com.hermesandroid.relay.wake.MicrophoneOwner
import com.hermesandroid.relay.wake.MicrophoneOwnershipCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.math.max

/**
 * Duplex audio capture for voice barge-in (plan unit B3).
 *
 * During response generation and playback, this listener continuously pulls
 * 32 ms / 512-sample PCM frames off the microphone and feeds them to
 * [VadEngine]. One instance owns the full active turn. It emits two
 * SharedFlows wired into the voice state machine:
 *
 *  - [maybeSpeech] fires on the **first** positive raw-VAD frame — before the
 *    second-layer debouncer latches. B4 uses this to softly [VoicePlayer.duck]
 *    the TTS so the user's voice has acoustic headroom while we decide whether
 *    to cut off.
 *
 *  - [bargeInDetected] fires when [VadEngine] confirms speech post-hysteresis
 *    and the calibrated RMS majority gate accepts it. The owner uses this to
 *    interrupt generation/playback and flip state to Listening.
 *
 * ### Acoustic echo cancellation
 *
 * We configure [AudioRecord] with [MediaRecorder.AudioSource.VOICE_COMMUNICATION]
 * so the platform's voice-call AEC pipeline is in play, and additionally try
 * to attach [AcousticEchoCanceler] + [NoiseSuppressor] to the capture
 * [AudioRecord] session. Android audio preprocessors belong to the capture
 * path; a playback session is not a valid attachment target for AEC/NS.
 * Without AEC, the device's own speaker output would trip the VAD the moment
 * TTS started and we'd interrupt ourselves.
 *
 * ### Graceful degradation
 *
 * - `AudioRecord.getState() != STATE_INITIALIZED` → log WARN, emit nothing,
 *   [stop] remains safe to call. Typical cause: RECORD_AUDIO denied at runtime
 *   or another app holding the mic.
 * - `AcousticEchoCanceler.isAvailable() == false` → log INFO, proceed without.
 *   Many mid-range and older devices lack the effect; the VAD still works with
 *   the mic-hardware AEC from VOICE_COMMUNICATION alone (at the cost of some
 *   false positives during loud TTS).
 *
 * ### Testability seam
 *
 * The hot path is abstracted behind [AudioFrameSource]. Production code uses
 * [AudioRecordSource]; unit tests inject a deterministic fake. This keeps the
 * test on the JVM unit test path with no Robolectric or `android.jar` shim,
 * matching the [VadEngine] test pattern.
 *
 * ### Thread model
 *
 * [start] launches a single reader coroutine on [Dispatchers.IO]. The reader
 * blocks on [AudioFrameSource.read], then synchronously invokes
 * [VadEngine.analyze] on the same dispatcher — VadEngine is synchronous,
 * allocation-free, and callers promise single-threaded access. Flow emissions
 * use [MutableSharedFlow] with `extraBufferCapacity = 1` so slow subscribers
 * drop events instead of backpressuring the audio loop.
 */
class BargeInListener internal constructor(
    private val audioSource: AudioFrameSource,
    private val vadEngine: VadEngine,
    private val readerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMsProvider: () -> Long = System::currentTimeMillis,
) {

    companion object {
        private const val TAG = "BargeInListener"

        /** Bytes per PCM sample at [AudioFormat.ENCODING_PCM_16BIT]. */
        private const val BYTES_PER_SAMPLE = 2

        /** Frames of buffering on the [AudioRecord] side. 4× keeps read() from
         *  ever racing the DMA when the reader coroutine is scheduled with a
         *  brief delay (GC pause, dispatcher contention). */
        private const val AUDIO_BUFFER_FRAMES = 4

        /**
         * Factory for the production path. Builds an [AudioRecordSource] from
         * a `Context` and wires it to the listener. The returned listener has
         * no allocated `AudioRecord` yet — that happens inside [start].
         */
        fun create(
            context: Context,
            vadEngine: VadEngine,
        ): BargeInListener = BargeInListener(
            audioSource = AudioRecordSource(context.applicationContext),
            vadEngine = vadEngine,
        )
    }

    private val _bargeInDetected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Fires post-hysteresis when [VadEngine] confirms the user is speaking. */
    val bargeInDetected: SharedFlow<Unit> = _bargeInDetected.asSharedFlow()

    private val _maybeSpeech = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Fires on the first positive raw VAD frame, before hysteresis latches. */
    val maybeSpeech: SharedFlow<Unit> = _maybeSpeech.asSharedFlow()

    private val _aecAttached = MutableStateFlow(false)
    /** True once [AcousticEchoCanceler] has been attached for the current
     *  listen session. Exposed for observability and B5's compatibility hint. */
    val aecAttached: StateFlow<Boolean> = _aecAttached.asStateFlow()

    // Reused across every read() call so the hot loop never allocates a
    // fresh buffer. Length matches the VAD engine's contract (512 samples).
    private val frameBuffer: ShortArray = ShortArray(VadEngine.FRAME_SIZE_SAMPLES)

    @Volatile private var readerJob: Job? = null
    @Volatile private var microphoneLease: MicrophoneLease? = null
    @Volatile private var aec: AcousticEchoCanceler? = null
    @Volatile private var noiseSuppressor: NoiseSuppressor? = null
    private val rmsGate = RmsBargeInGate()
    @Volatile private var playbackGraceMs: Long = RmsBargeInGate.DEFAULT_PLAYBACK_GRACE_MS
    @Volatile private var playbackActiveProvider: (() -> Boolean)? = null
    @Volatile private var diagnosticsEnabled: Boolean = false
    private var wasCalibrating: Boolean = false

    /** Apply the user-facing barge-in sensitivity to the quiet-room RMS gate. */
    fun setThresholdMultiplier(multiplier: Float) {
        rmsGate.thresholdMultiplier = multiplier
    }

    fun setDiagnosticsEnabled(enabled: Boolean) {
        diagnosticsEnabled = enabled
    }

    /** Supplies the renderer's current playback phase for upstream-style gaps. */
    fun setPlaybackActiveProvider(provider: () -> Boolean) {
        playbackActiveProvider = provider
    }

    /**
     * Freeze quiet-room calibration and begin the playback-only grace window.
     * Idempotent so every renderer may call it at its first audible chunk.
     */
    fun markPlaybackStarted(
        nowMs: Long = System.currentTimeMillis(),
        graceMs: Long = RmsBargeInGate.DEFAULT_PLAYBACK_GRACE_MS,
    ) {
        playbackGraceMs = graceMs.coerceAtLeast(0L)
        rmsGate.markPlaybackStarted(nowMs)
        if (diagnosticsEnabled) {
            Log.d(TAG, "voice-vad playback started; grace=${playbackGraceMs}ms")
        }
    }

    /**
     * Allocate the audio pipeline and begin reading frames into [vadEngine].
     *
     * Launches on the supplied [scope] so the reader coroutine dies with its
     * owner (the ViewModel scope in B4). [start] is not suspend in the usual
     * "blocks until ready" sense — it returns as soon as the reader job is
     * launched; the AEC attach happens lazily inside the coroutine so a
     * caller waiting on the first [maybeSpeech] / [bargeInDetected] emission
     * is not gated on an AudioTrack that hasn't been allocated yet.
     *
     * Idempotent: calling [start] again while a previous session is still
     * active is a no-op with a WARN log — B4 is expected to bracket each
     * listen session with a matching [stop].
     */
    fun start(scope: CoroutineScope) {
        if (readerJob?.isActive == true) {
            Log.w(TAG, "start() called while a reader is already active — ignoring")
            return
        }

        val lease = MicrophoneOwnershipCoordinator.tryAcquire(MicrophoneOwner.BargeIn)
        if (lease == null) {
            Log.i(TAG, "Barge-in listener inactive — microphone is owned by another voice surface")
            return
        }
        microphoneLease = lease
        if (!audioSource.initialize()) {
            Log.w(
                TAG,
                "AudioFrameSource failed to initialize " +
                    "(missing RECORD_AUDIO permission or mic busy) — listener inactive",
            )
            _aecAttached.value = false
            MicrophoneOwnershipCoordinator.release(lease)
            microphoneLease = null
            return
        }

        _aecAttached.value = false
        rmsGate.reset()
        wasCalibrating = true
        readerJob = scope.launch(readerDispatcher) {
            var effectsJob: Job? = null
            try {
                try {
                    audioSource.start()
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    Log.w(TAG, "AudioFrameSource.start failed: ${t.message}")
                    return@launch
                }
                Log.i(TAG, "Barge-in AudioRecord reader started")
                // Effects attach beside the reader so capture can begin even
                // on devices that reject or omit the optional preprocessors.
                effectsJob = launch { maybeAttachEffects() }

                while (isActive) {
                    val read = try {
                        audioSource.read(frameBuffer, VadEngine.FRAME_SIZE_SAMPLES)
                    } catch (t: CancellationException) {
                        throw t
                    } catch (t: Throwable) {
                        Log.w(TAG, "AudioFrameSource.read failed; stopping reader: ${t.message}")
                        break
                    }
                    if (read <= 0) {
                        // Negative values are AudioRecord error codes; 0 means
                        // no data yet. Either way, yield briefly and retry
                        // rather than spinning — but don't swallow the
                        // cancellation check for too long.
                        delay(5)
                        continue
                    }
                    if (read < VadEngine.FRAME_SIZE_SAMPLES) {
                        // Short read — skip this frame rather than feeding
                        // the VAD a partially-populated buffer. This is rare;
                        // AudioRecord.read(…, SIZE_IN_SHORTS) normally fills
                        // the requested length when state is correct.
                        continue
                    }

                    if (!isActive) break

                    val result = try {
                        vadEngine.analyze(frameBuffer)
                    } catch (t: CancellationException) {
                        throw t
                    } catch (t: Throwable) {
                        Log.w(TAG, "VadEngine.analyze failed; stopping reader: ${t.message}")
                        break
                    }
                    val gated = rmsGate.observe(
                        frame = frameBuffer,
                        rawSpeech = result.probability > 0f,
                        nowMs = nowMsProvider(),
                        playbackGraceMs = playbackGraceMs,
                        confirmedSpeech = result.isSpeech,
                        playbackActiveOverride = playbackActiveProvider?.invoke(),
                    )
                    if (diagnosticsEnabled) {
                        if (wasCalibrating && !gated.calibrating) {
                            Log.d(
                                TAG,
                                "voice-vad calibrated quiet floor=${gated.floor.toInt()} " +
                                    "mult=${rmsGate.thresholdMultiplier}",
                            )
                        }
                        wasCalibrating = gated.calibrating
                        if (
                            gated.detected || gated.playbackGrace ||
                            gated.rms >= gated.threshold * 0.5f
                        ) {
                            Log.d(
                                TAG,
                                "voice-vad rms=${gated.rms.toInt()} floor=${gated.floor.toInt()} " +
                                    "trigger=${gated.threshold.toInt()} raw=${result.probability > 0f} " +
                                    "confirmed=${result.isSpeech} detected=${gated.detected} " +
                                    "grace=${gated.playbackGrace} " +
                                    "phase=${if (gated.playback) "playback" else "generation"}",
                            )
                        }
                    }
                    if (gated.maybeSpeech) {
                        _maybeSpeech.tryEmit(Unit)
                    }
                    if (gated.detected) {
                        _bargeInDetected.tryEmit(Unit)
                    }
                    // Give the dispatcher a chance to observe cancellation
                    // between frames. Production-side the AudioRecord.read
                    // call already blocks until a frame is available, so
                    // this is effectively free; test-side it prevents the
                    // reader from monopolizing the test scheduler on fakes
                    // that return data synchronously.
                    yield()
                }
            } finally {
                // The reader reaches this block with its Job cancelled.
                // Teardown still has to wait for the sibling AEC poll before
                // releasing the AudioRecord and microphone lease.
                withContext(NonCancellable) {
                    effectsJob?.cancelAndJoin()
                }
                // Release effects + AudioRecord in the reverse of attach order
                // so the AudioSessionId is still valid when AEC teardown runs.
                releaseEffects()
                runCatching { audioSource.stop() }
                runCatching { audioSource.release() }
                microphoneLease?.let(MicrophoneOwnershipCoordinator::release)
                microphoneLease = null
                _aecAttached.value = false
            }
        }
    }

    /**
     * Cancel the reader loop and release the mic + effects. Safe to call
     * repeatedly and safe to call before [start]. Returns immediately; the
     * actual release happens in the reader coroutine's `finally` block, which
     * is typically a single frame later.
     */
    fun stop(): Job? {
        val job = readerJob
        if (job?.isActive == true) {
            Log.i(TAG, "Stopping barge-in AudioRecord reader")
        }
        // AudioRecord.read() may be blocked in native code, so stop the source
        // before cancellation to make the reader observe shutdown promptly.
        runCatching { audioSource.stop() }
        job?.cancel()
        readerJob = null
        if (job == null) {
            runCatching { audioSource.release() }
            microphoneLease?.let(MicrophoneOwnershipCoordinator::release)
            microphoneLease = null
        }
        return job
    }

    private suspend fun maybeAttachEffects() {
        val sessionId = audioSource.audioSessionId
        if (sessionId == 0) {
            Log.i(
                TAG,
                "AEC not attached — AudioRecord capture session id is 0; " +
                    "continuing without optional effects",
            )
            return
        }

        if (AcousticEchoCanceler.isAvailable()) {
            try {
                val created = AcousticEchoCanceler.create(sessionId)
                if (created != null) {
                    created.enabled = true
                    aec = created
                    _aecAttached.value = true
                    Log.i(TAG, "AcousticEchoCanceler attached to session=$sessionId")
                } else {
                    Log.i(TAG, "AcousticEchoCanceler.create returned null; continuing without")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "AcousticEchoCanceler attach failed: ${t.message}")
            }
        } else {
            Log.i(TAG, "AEC unavailable on this device; continuing without echo cancellation")
        }

        if (NoiseSuppressor.isAvailable()) {
            try {
                val ns = NoiseSuppressor.create(sessionId)
                if (ns != null) {
                    ns.enabled = true
                    noiseSuppressor = ns
                }
            } catch (t: Throwable) {
                Log.w(TAG, "NoiseSuppressor attach failed: ${t.message}")
            }
        }
    }

    private fun releaseEffects() {
        aec?.let {
            runCatching { it.enabled = false }
            runCatching { it.release() }
        }
        aec = null
        noiseSuppressor?.let {
            runCatching { it.enabled = false }
            runCatching { it.release() }
        }
        noiseSuppressor = null
    }

    /**
     * Minimal seam over [AudioRecord] so the audio-source pipeline can be
     * replaced with a deterministic fake in unit tests. Implementations are
     * not thread-safe — callers promise single-threaded access from the
     * reader coroutine.
     */
    internal interface AudioFrameSource {
        /** Capture-session id used by Android audio preprocessors. */
        val audioSessionId: Int

        /**
         * Allocate underlying native resources. Returns true on success.
         * Returning false from here short-circuits the listener without any
         * downstream state flapping.
         */
        fun initialize(): Boolean

        /** Begin streaming frames. Must be preceded by a successful [initialize]. */
        fun start()

        /** Read up to [sizeInShorts] samples into [buffer]; returns the number
         *  of samples actually read (possibly 0 or negative for error states). */
        fun read(buffer: ShortArray, sizeInShorts: Int): Int

        /** Stop streaming. May be called multiple times. */
        fun stop()

        /** Release native resources. After this, the source is dead. */
        fun release()
    }

    /**
     * Real [AudioRecord]-backed frame source. Configures 16 kHz mono 16-bit
     * PCM with [MediaRecorder.AudioSource.VOICE_COMMUNICATION] so the mic
     * hardware AEC is engaged.
     *
     * The [Context] parameter is currently unused — [AudioRecord] doesn't
     * need one — but we take it to keep the production factory signature
     * symmetric with the rest of the audio stack (e.g. [VoiceRecorder])
     * and to leave room for future permission-probe / audio-focus hooks
     * without a constructor signature change.
     */
    @Suppress("unused", "UNUSED_PARAMETER")
    private class AudioRecordSource(context: Context) : AudioFrameSource {
        private var record: AudioRecord? = null

        override val audioSessionId: Int
            get() = record?.audioSessionId ?: 0

        @SuppressLint("MissingPermission")
        override fun initialize(): Boolean {
            val sampleRate = 16_000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT

            val minBytes = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
            if (minBytes <= 0) {
                Log.w(TAG, "AudioRecord.getMinBufferSize returned $minBytes — aborting")
                return false
            }
            val ourBytes =
                VadEngine.FRAME_SIZE_SAMPLES * BYTES_PER_SAMPLE * AUDIO_BUFFER_FRAMES
            val bufferBytes = max(minBytes, ourBytes)

            val r = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    channelConfig,
                    encoding,
                    bufferBytes,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "AudioRecord constructor threw: ${t.message}")
                return false
            }

            if (r.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord state=${r.state} (expected STATE_INITIALIZED)")
                runCatching { r.release() }
                return false
            }

            record = r
            return true
        }

        override fun start() {
            record?.startRecording()
        }

        override fun read(buffer: ShortArray, sizeInShorts: Int): Int {
            val r = record ?: return -1
            return r.read(buffer, 0, sizeInShorts)
        }

        override fun stop() {
            runCatching { record?.stop() }
        }

        override fun release() {
            runCatching { record?.release() }
            record = null
        }
    }
}
