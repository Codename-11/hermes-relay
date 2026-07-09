package com.hermesandroid.relay.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.hermesandroid.relay.R
import com.hermesandroid.relay.diagnostics.DiagnosticCategory
import com.hermesandroid.relay.diagnostics.DiagnosticSeverity
import com.hermesandroid.relay.diagnostics.DiagnosticsLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Small streaming PCM sink for the realtime voice dev testbench.
 *
 * The relay sends mono 16-bit little-endian PCM chunks over the websocket. This
 * writes them directly to an AudioTrack so the Android Studio dev build can
 * hear provider output without waiting for an encoded file.
 */
class RealtimePcmPlayer(private val context: Context? = null) {
    private val trackLock = Any()
    private val writeLock = Any()
    private val audioManager =
        context?.applicationContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val realtimeAudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.i(TAG, "Realtime PCM audio focus change=$change")
    }
    private var audioTrack: AudioTrack? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusHeld: Boolean = false
    private var currentSampleRate: Int = 0
    private var currentVolume: Float = 1f
    private var estimatedPlaybackEndAtMs: Long = 0L
    private var playbackStarted: Boolean = false
    private var pendingStartBytes: Int = 0
    private var firstBufferedAtMs: Long = 0L
    private var lastUnderrunCount: Int = 0
    private var lastHeadPositionLogAtMs: Long = 0L
    private var lastLoggedHeadFrames: Int = 0
    private var headAdvanceConfirmed: Boolean = false
    private var playbackStartedAtMs: Long = 0L
    private var totalFramesWritten: Long = 0L
    // (endFrame, rms) per written chunk — lets [playbackAmplitude] report the
    // amplitude of the audio actually at the hardware cursor right now, instead
    // of the chunk that most recently *arrived* over the socket.
    private val playbackAmpQueue = ArrayDeque<FrameAmp>()
    private var lastPlaybackGapDiagnosticAtMs: Long = 0L
    private var lastMutedVolumeDiagnosticAtMs: Long = 0L
    private var adaptiveStartPrebufferMs: Long = RealtimePcmBufferPolicy.START_PREBUFFER_MS
    private var playbackGapSeenThisTrack: Boolean = false
    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    val isActive: Boolean
        get() = synchronized(trackLock) { audioTrack != null }

    val audioSessionId: Int
        get() = synchronized(trackLock) { audioTrack?.audioSessionId ?: 0 }

    fun write(pcm: ByteArray, sampleRate: Int): Float {
        if (pcm.isEmpty()) return 0f
        val level = computePcm16LeRms(pcm)
        val now = SystemClock.elapsedRealtime()
        val written = synchronized(writeLock) {
            val track = try {
                synchronized(trackLock) {
                    val currentTrack = ensureTrackLocked(sampleRate)
                    notePlaybackGapLocked(currentTrack, now)
                    currentTrack
                }
            } catch (e: Exception) {
                Log.w(TAG, "PCM track preparation failed: ${e.message}")
                synchronized(trackLock) { releaseTrackLocked(reason = "PCM track preparation failure") }
                return@synchronized 0
            }

            try {
                val prerollWritten = maybeWriteStartupPreroll(track, sampleRate)
                if (prerollWritten < 0) {
                    Log.w(TAG, "PCM preroll write returned $prerollWritten; restarting track")
                    synchronized(trackLock) {
                        if (audioTrack === track) releaseTrackLocked(reason = "PCM preroll write error")
                    }
                    return@synchronized 0
                }
                // Intentionally do NOT start playback on the bare silent preroll.
                // Starting here would begin draining ~120ms of silence with zero
                // real audio queued, guaranteeing an immediate underrun on the
                // first speech chunk. The real audio written just below feeds the
                // normal start decision, and the end-of-turn flush
                // (voice.output_audio.done) force-starts anything still buffered.

                val writtenBytes = writeBlocking(track, pcm)
                if (writtenBytes < 0) {
                    Log.w(TAG, "PCM write returned $writtenBytes; restarting track")
                    synchronized(trackLock) {
                        if (audioTrack === track) releaseTrackLocked(reason = "PCM write error")
                    }
                    return@synchronized 0
                }

                var accepted = 0
                if (writtenBytes > 0) {
                    synchronized(trackLock) {
                        if (audioTrack === track) {
                            noteWrittenBytesLocked(writtenBytes, sampleRate)
                            enqueuePlaybackAmplitudeLocked(level)
                            maybeStartPlaybackLocked(track, sampleRate, force = false)
                            updateUnderrunCursorLocked(track)
                            logPlaybackHealthLocked(track, now)
                            accepted = writtenBytes
                        }
                    }
                }
                accepted
            } catch (e: Exception) {
                Log.w(TAG, "PCM write failed: ${e.message}")
                synchronized(trackLock) {
                    if (audioTrack === track) releaseTrackLocked(reason = "PCM write failure")
                }
                return@synchronized 0
            }
        }
        if (written > 0) {
            _amplitude.value = level
        }
        return level
    }

    private fun writeBlocking(track: AudioTrack, pcm: ByteArray): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
        } else {
            @Suppress("DEPRECATION")
            track.write(pcm, 0, pcm.size)
        }

    fun flushBufferedPlayback(cushionMs: Long = DEFAULT_DRAIN_CUSHION_MS): Long {
        val now = SystemClock.elapsedRealtime()
        return synchronized(trackLock) {
            val track = audioTrack ?: return@synchronized 0L
            maybeStartPlaybackLocked(track, currentSampleRate, force = true)
            val remaining = remainingPlaybackMsLocked(now, cushionMs)
            Log.i(
                TAG,
                "Realtime PCM flush playState=${readPlayState(track)} " +
                    "headFrames=${readHeadFrames(track)} remainingMs=$remaining " +
                    "underruns=${readUnderrunCount(track)}",
            )
            remaining
        }
    }

    fun stop() {
        synchronized(writeLock) {
            synchronized(trackLock) {
                releaseTrackLocked(reason = "stop")
                currentSampleRate = 0
                estimatedPlaybackEndAtMs = 0L
            }
        }
        _amplitude.value = 0f
    }

    fun estimatedRemainingPlaybackMs(cushionMs: Long = DEFAULT_DRAIN_CUSHION_MS): Long {
        val now = SystemClock.elapsedRealtime()
        return synchronized(trackLock) {
            remainingPlaybackMsLocked(now, cushionMs)
        }
    }

    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        synchronized(trackLock) {
            currentVolume = clamped
            try { audioTrack?.setVolume(clamped) } catch (_: Exception) { }
        }
    }

    fun duck() {
        setVolume(0.3f)
    }

    fun unduck() {
        setVolume(1f)
    }

    private fun releaseTrackLocked(reason: String) {
        audioTrack?.let { track ->
            Log.i(TAG, "Stopping streaming PCM playback ($reason)")
            try { track.pause() } catch (_: Exception) { }
            try { track.flush() } catch (_: Exception) { }
            try { track.release() } catch (_: Exception) { }
        }
        abandonAudioFocusLocked()
        settleAdaptivePrebufferLocked()
        audioTrack = null
        playbackStarted = false
        pendingStartBytes = 0
        firstBufferedAtMs = 0L
        lastUnderrunCount = 0
        lastHeadPositionLogAtMs = 0L
        lastLoggedHeadFrames = 0
        headAdvanceConfirmed = false
        playbackStartedAtMs = 0L
        totalFramesWritten = 0L
        playbackAmpQueue.clear()
        playbackGapSeenThisTrack = false
    }

    private fun enqueuePlaybackAmplitudeLocked(rms: Float) {
        // [totalFramesWritten] has already been advanced past this chunk, so it
        // is the chunk's end frame. The cursor reaches this amplitude once
        // playbackHeadPosition passes the previous end frame.
        playbackAmpQueue.addLast(FrameAmp(endFrame = totalFramesWritten, rms = rms))
        while (playbackAmpQueue.size > MAX_AMP_QUEUE) playbackAmpQueue.removeFirst()
    }

    /**
     * Amplitude of the audio currently at the hardware cursor (0 if not playing
     * or drained). This is the playback-synced signal a UI waveform should draw:
     * it advances with [AudioTrack.getPlaybackHeadPosition], so it matches what
     * the user hears rather than what most recently arrived over the socket.
     */
    fun playbackAmplitude(): Float = synchronized(trackLock) {
        val track = audioTrack ?: return@synchronized 0f
        if (!playbackStarted) return@synchronized 0f
        val head = readHeadFrames(track).toLong()
        // Drop fully-played chunks so the head of the queue is the one playing now.
        while (playbackAmpQueue.size > 1 && playbackAmpQueue.first().endFrame <= head) {
            playbackAmpQueue.removeFirst()
        }
        amplitudeAtHead(playbackAmpQueue, head)
    }

    private fun ensureTrackLocked(sampleRate: Int): AudioTrack {
        val existing = audioTrack
        if (existing != null && currentSampleRate == sampleRate) {
            return existing
        }
        releaseTrackLocked(reason = "sample rate changed")

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate / 10 * 2)
        val bufferSize = RealtimePcmBufferPolicy.streamBufferSize(
            minBufferBytes = minBuffer,
            sampleRate = sampleRate,
        )
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(realtimeAudioAttributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM,
            )
        }

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            try { track.release() } catch (_: Exception) { }
            throw IllegalStateException("AudioTrack failed to initialize")
        }

        requestAudioFocusLocked()
        audioTrack = track
        currentSampleRate = sampleRate
        playbackStarted = false
        pendingStartBytes = 0
        firstBufferedAtMs = 0L
        totalFramesWritten = 0L
        lastUnderrunCount = readUnderrunCount(track)
        // Log requested vs. actual allocated frames. If a device coerces our
        // sub-second request back up to a multi-second allocation, that's the
        // tell-tale of deep-buffer routing (the cold-start parking class) and
        // explains a regression of the silent-first-turn bug on new hardware.
        val requestedFrames = bufferSize / BYTES_PER_FRAME
        val actualFrames = try { track.bufferSizeInFrames } catch (_: Exception) { -1 }
        Log.i(
            TAG,
            "Initialized streaming PCM playback at ${sampleRate}Hz " +
                "session=${track.audioSessionId} buffer=${bufferSize}B " +
                "requestedFrames=$requestedFrames actualFrames=$actualFrames " +
                "(${frameMs(actualFrames, sampleRate)}ms)",
        )
        return track
    }

    private fun frameMs(frames: Int, sampleRate: Int): Long {
        if (frames <= 0 || sampleRate <= 0) return 0L
        return (frames * 1000L / sampleRate)
    }

    private fun noteWrittenBytesLocked(writtenBytes: Int, sampleRate: Int) {
        if (writtenBytes <= 0 || sampleRate <= 0) return
        totalFramesWritten += (writtenBytes / BYTES_PER_FRAME).toLong()
        val durationMs = ((writtenBytes / 2.0) / sampleRate * 1000.0)
            .toLong()
            .coerceAtLeast(1L)
        val now = SystemClock.elapsedRealtime()
        if (!playbackStarted) {
            if (firstBufferedAtMs == 0L) firstBufferedAtMs = now
            pendingStartBytes += writtenBytes
            return
        }
        val base = max(now, estimatedPlaybackEndAtMs)
        estimatedPlaybackEndAtMs = base + durationMs
    }

    private fun maybeWriteStartupPreroll(track: AudioTrack, sampleRate: Int): Int {
        if (
            synchronized(trackLock) {
                playbackStarted ||
                    pendingStartBytes > 0 ||
                    firstBufferedAtMs > 0L ||
                    sampleRate <= 0 ||
                    audioTrack !== track
            }
        ) {
            return 0
        }

        val prerollMs = startupPrerollMsLocked()
        val silenceBytes = RealtimePcmBufferPolicy.bytesForDurationMs(sampleRate, prerollMs)
        if (silenceBytes <= 0) return 0

        val written = writeBlocking(track, ByteArray(silenceBytes))
        if (written > 0) {
            synchronized(trackLock) {
                if (audioTrack === track) {
                    noteWrittenBytesLocked(written, sampleRate)
                    enqueuePlaybackAmplitudeLocked(0f) // preroll is silence
                    Log.i(
                        TAG,
                        "Primed realtime PCM playback with " +
                            "${RealtimePcmBufferPolicy.durationMsForBytes(written, sampleRate)}ms " +
                            "silent preroll",
                    )
                }
            }
        }
        return written
    }

    private fun startupPrerollMsLocked(): Long {
        return RealtimePcmBufferPolicy.STARTUP_PREROLL_MS
    }

    private fun maybeStartPlaybackLocked(
        track: AudioTrack,
        sampleRate: Int,
        force: Boolean,
    ) {
        if (playbackStarted || pendingStartBytes <= 0 || sampleRate <= 0) return
        val now = SystemClock.elapsedRealtime()
        val waitedMs = if (firstBufferedAtMs > 0L) now - firstBufferedAtMs else 0L
        val decision = RealtimePcmBufferPolicy.startDecision(
            pendingBytes = pendingStartBytes,
            sampleRate = sampleRate,
            waitedMs = waitedMs,
            force = force,
            startPrebufferMs = adaptiveStartPrebufferMs,
        )
        if (!decision.shouldStart) return

        try {
            requestAudioFocusLocked()
            track.play()
            try { track.setVolume(currentVolume) } catch (_: Exception) { }
        } catch (e: Exception) {
            try { track.release() } catch (_: Exception) { }
            audioTrack = null
            throw e
        }

        playbackStarted = true
        estimatedPlaybackEndAtMs = now + decision.bufferedMs
        playbackStartedAtMs = now
        lastHeadPositionLogAtMs = now
        lastLoggedHeadFrames = readHeadFrames(track)
        headAdvanceConfirmed = false
        Log.i(
            TAG,
            "Started streaming PCM playback at ${sampleRate}Hz " +
                "session=${track.audioSessionId} prebuffer=${decision.bufferedMs}ms " +
                "waited=${waitedMs}ms target=${adaptiveStartPrebufferMs}ms " +
                "reason=${decision.reason} playState=${readPlayState(track)} " +
                "headFrames=$lastLoggedHeadFrames ${mediaVolumeSummaryLocked()}",
        )
        pendingStartBytes = 0
        firstBufferedAtMs = 0L
        lastUnderrunCount = readUnderrunCount(track)
    }

    /**
     * Periodically logs whether the AudioTrack hardware cursor is actually
     * advancing. This is the decisive signal for the "speaking animation + valid
     * PCM logs but no sound" class of bug:
     *
     *  - head frames advancing + still no sound → output route / volume problem
     *    (e.g. the Samsung HAL not opening the path until a volume key nudges it).
     *  - head frames pinned at the start value → the track was play()'d but the
     *    mixer never pulled from it (focus / state problem on this device).
     */
    private fun logPlaybackHealthLocked(track: AudioTrack, now: Long) {
        if (!playbackStarted) return
        val headFrames = readHeadFrames(track)
        // First-frame detection runs on EVERY write until confirmed (not gated by
        // the throttle) and uses a fresh timestamp, so time-to-first-audio is
        // accurate to write cadence rather than the 1s health-log window — the
        // throttle/stale-`now` combination otherwise inflates it by ~1.5s.
        if (!headAdvanceConfirmed && headFrames > 0) {
            headAdvanceConfirmed = true
            val freshNow = SystemClock.elapsedRealtime()
            val ttfaMs = if (playbackStartedAtMs > 0L) freshNow - playbackStartedAtMs else -1L
            Log.i(
                TAG,
                "Realtime PCM time-to-first-audio=${ttfaMs}ms (headFrames=$headFrames)",
            )
            DiagnosticsLog.record(
                category = DiagnosticCategory.Voice,
                severity = DiagnosticSeverity.Info,
                title = context?.getString(R.string.audio_diag_started) ?: "Realtime audio started",
                detail = "First sample reached the speaker after ${ttfaMs}ms.",
            )
        }
        if (now - lastHeadPositionLogAtMs < HEAD_POSITION_LOG_THROTTLE_MS) return
        val advancedFrames = headFrames - lastLoggedHeadFrames
        Log.i(
            TAG,
            "Realtime PCM playback health playState=${readPlayState(track)} " +
                "headFrames=$headFrames advanced=$advancedFrames " +
                "underruns=${readUnderrunCount(track)} ${mediaVolumeSummaryLocked()}",
        )
        if (advancedFrames <= 0) {
            Log.w(
                TAG,
                "Realtime PCM hardware cursor not advancing (headFrames=$headFrames " +
                    "playState=${readPlayState(track)}); audio queued but mixer is not pulling",
            )
            maybeRecordStuckCursorDiagnosticLocked(track, now)
        }
        lastHeadPositionLogAtMs = now
        lastLoggedHeadFrames = headFrames
    }

    /**
     * If the hardware cursor never started after [STUCK_CURSOR_DIAGNOSTIC_MS] of
     * "playing", surface it to the in-app Diagnostics screen once per track —
     * this is the field-visible signal for the cold-start parking class when no
     * logcat cable is attached. Write-sampled here; the [VoiceViewModel] watchdog
     * provides the timer-driven guarantee when writes stall.
     */
    private fun maybeRecordStuckCursorDiagnosticLocked(track: AudioTrack, now: Long) {
        if (headAdvanceConfirmed || playbackStartedAtMs <= 0L) return
        val stuckMs = now - playbackStartedAtMs
        if (stuckMs < STUCK_CURSOR_DIAGNOSTIC_MS) return
        if (now - lastPlaybackGapDiagnosticAtMs < PLAYBACK_GAP_DIAGNOSTIC_THROTTLE_MS) return
        lastPlaybackGapDiagnosticAtMs = now
        DiagnosticsLog.record(
            category = DiagnosticCategory.Voice,
            severity = DiagnosticSeverity.Warning,
            title = context?.getString(R.string.audio_diag_not_starting) ?: "Realtime audio not starting",
            detail = "Playback running ${stuckMs}ms but no audio reached the speaker " +
                "(${mediaVolumeSummaryLocked()}).",
        )
    }

    /**
     * Immutable snapshot of playback progress for the [VoiceViewModel] watchdog
     * and drain cross-check. Reads are cheap and lock-guarded.
     */
    fun snapshot(): RealtimePlaybackSnapshot = synchronized(trackLock) {
        val track = audioTrack
        RealtimePlaybackSnapshot(
            active = track != null,
            playbackStarted = playbackStarted,
            headFrames = track?.let { readHeadFrames(it) } ?: 0,
            framesWritten = totalFramesWritten,
            sampleRate = currentSampleRate,
            playStatePlaying = track != null && readPlayState(track) == "playing",
            startedAtElapsedMs = playbackStartedAtMs,
        )
    }

    private fun readHeadFrames(track: AudioTrack): Int =
        try { track.playbackHeadPosition } catch (_: Exception) { lastLoggedHeadFrames }

    private fun readPlayState(track: AudioTrack): String =
        try {
            when (track.playState) {
                AudioTrack.PLAYSTATE_PLAYING -> "playing"
                AudioTrack.PLAYSTATE_PAUSED -> "paused"
                AudioTrack.PLAYSTATE_STOPPED -> "stopped"
                else -> "unknown"
            }
        } catch (_: Exception) {
            "error"
        }

    private fun notePlaybackGapLocked(track: AudioTrack, now: Long) {
        if (!playbackStarted) return
        val underrunCount = readUnderrunCount(track)
        val platformUnderrun = underrunCount > lastUnderrunCount
        val estimatedDrained = estimatedPlaybackEndAtMs > 0L &&
            now > estimatedPlaybackEndAtMs + RealtimePcmBufferPolicy.UNDERFLOW_GRACE_MS
        if (!platformUnderrun && !estimatedDrained) return

        val reason = if (platformUnderrun) {
            "platform underrun ${lastUnderrunCount}→$underrunCount"
        } else {
            "stream gap ${now - estimatedPlaybackEndAtMs}ms"
        }
        Log.w(TAG, "Realtime PCM continuing after $reason")
        recordPlaybackGapDiagnosticLocked(now, reason)
        playbackGapSeenThisTrack = true
        increaseAdaptivePrebufferLocked(reason)

        // Provider-native realtime streams can legitimately arrive in uneven
        // bursts while the model decides to call tools. Keep the AudioTrack
        // alive so already queued speech is not flushed and the next chunk can
        // resume naturally after Android's underrun recovery.
        if (estimatedDrained) {
            estimatedPlaybackEndAtMs = now
        }
        lastUnderrunCount = underrunCount
    }

    private fun increaseAdaptivePrebufferLocked(reason: String) {
        val previous = adaptiveStartPrebufferMs
        adaptiveStartPrebufferMs = (adaptiveStartPrebufferMs + ADAPTIVE_PREBUFFER_STEP_MS)
            .coerceAtMost(RealtimePcmBufferPolicy.MAX_ADAPTIVE_START_PREBUFFER_MS)
        if (adaptiveStartPrebufferMs != previous) {
            Log.i(
                TAG,
                "Realtime PCM adaptive prebuffer increased to ${adaptiveStartPrebufferMs}ms " +
                    "after $reason",
            )
        }
    }

    private fun settleAdaptivePrebufferLocked() {
        if (playbackGapSeenThisTrack) return
        val previous = adaptiveStartPrebufferMs
        adaptiveStartPrebufferMs = (adaptiveStartPrebufferMs - ADAPTIVE_PREBUFFER_DECAY_MS)
            .coerceAtLeast(RealtimePcmBufferPolicy.START_PREBUFFER_MS)
        if (adaptiveStartPrebufferMs != previous) {
            Log.i(
                TAG,
                "Realtime PCM adaptive prebuffer relaxed to ${adaptiveStartPrebufferMs}ms",
            )
        }
    }

    private fun recordPlaybackGapDiagnosticLocked(now: Long, reason: String) {
        if (now - lastPlaybackGapDiagnosticAtMs < PLAYBACK_GAP_DIAGNOSTIC_THROTTLE_MS) return
        lastPlaybackGapDiagnosticAtMs = now
        DiagnosticsLog.record(
            category = DiagnosticCategory.Voice,
            severity = DiagnosticSeverity.Warning,
            title = context?.getString(R.string.audio_diag_stream_gap) ?: "Realtime audio stream gap",
            detail = reason,
        )
    }

    private fun requestAudioFocusLocked() {
        val manager = audioManager ?: return
        val now = SystemClock.elapsedRealtime()
        val mediaVolume = runCatching { manager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
        val maxVolume = runCatching { manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
        if (mediaVolume == 0 && now - lastMutedVolumeDiagnosticAtMs > MUTED_VOLUME_DIAGNOSTIC_THROTTLE_MS) {
            lastMutedVolumeDiagnosticAtMs = now
            Log.w(TAG, "Realtime PCM playback is starting while media volume is muted")
            DiagnosticsLog.record(
                category = DiagnosticCategory.Voice,
                severity = DiagnosticSeverity.Warning,
                title = context?.getString(R.string.audio_diag_volume_muted) ?: "Realtime voice volume muted",
                detail = "Media volume is 0/${maxVolume ?: "?"}.",
            )
        }
        if (audioFocusHeld) {
            Log.i(TAG, "Realtime PCM audio focus already held ${mediaVolumeSummaryLocked()}")
            return
        }
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
                .setAudioAttributes(realtimeAudioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
                .also { audioFocusRequest = it }
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
        }
        audioFocusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.i(
            TAG,
            "Realtime PCM audio focus result=$result held=$audioFocusHeld ${mediaVolumeSummaryLocked()}",
        )
    }

    private fun abandonAudioFocusLocked() {
        val manager = audioManager ?: return
        if (!audioFocusHeld) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { manager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                manager.abandonAudioFocus(audioFocusChangeListener)
            }
        }.onFailure {
            Log.w(TAG, "Realtime PCM audio focus abandon failed: ${it.message}")
        }
        audioFocusHeld = false
    }

    private fun mediaVolumeSummaryLocked(): String {
        val manager = audioManager ?: return "mediaVolume=unknown"
        val volume = runCatching { manager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
        val maxVolume = runCatching { manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
        val musicActive = runCatching { manager.isMusicActive }.getOrNull()
        return "mediaVolume=${volume ?: "?"}/${maxVolume ?: "?"} musicActive=${musicActive ?: "?"}"
    }

    private fun updateUnderrunCursorLocked(track: AudioTrack) {
        val underrunCount = readUnderrunCount(track)
        if (underrunCount > lastUnderrunCount) {
            lastUnderrunCount = underrunCount
        }
    }

    private fun readUnderrunCount(track: AudioTrack): Int =
        try { track.underrunCount } catch (_: Exception) { lastUnderrunCount }

    private fun remainingPlaybackMsLocked(now: Long, cushionMs: Long): Long {
        if (audioTrack == null) return 0L
        if (!playbackStarted) {
            return RealtimePcmBufferPolicy.durationMsForBytes(
                bytes = pendingStartBytes,
                sampleRate = currentSampleRate,
            ) + cushionMs.coerceAtLeast(0L)
        }
        return (estimatedPlaybackEndAtMs - now + cushionMs).coerceAtLeast(0L)
    }

    private fun computePcm16LeRms(pcm: ByteArray): Float {
        val usable = pcm.size - (pcm.size % 2)
        if (usable <= 0) return 0f

        var sumSquares = 0.0
        var samples = 0
        var index = 0
        while (index < usable) {
            val low = pcm[index].toInt() and 0xff
            val high = pcm[index + 1].toInt()
            val sample = ((high shl 8) or low).toShort().toInt()
            val normalized = sample / Short.MAX_VALUE.toDouble()
            sumSquares += normalized * normalized
            samples++
            index += 2
        }
        if (samples == 0) return 0f

        val rms = sqrt(sumSquares / samples)
        val lifted = sqrt((rms / 0.28).coerceIn(0.0, 1.0))
        return if (lifted.isNaN() || lifted.isInfinite()) 0f else lifted.toFloat().coerceIn(0f, 1f)
    }

    companion object {
        private const val TAG = "RealtimePcmPlayer"
        private const val DEFAULT_DRAIN_CUSHION_MS = 250L
        private const val PLAYBACK_GAP_DIAGNOSTIC_THROTTLE_MS = 5_000L
        private const val MUTED_VOLUME_DIAGNOSTIC_THROTTLE_MS = 10_000L
        private const val ADAPTIVE_PREBUFFER_STEP_MS = 240L
        private const val ADAPTIVE_PREBUFFER_DECAY_MS = 120L
        private const val HEAD_POSITION_LOG_THROTTLE_MS = 1_000L
        private const val STUCK_CURSOR_DIAGNOSTIC_MS = 1_200L
        private const val BYTES_PER_FRAME = 2 // mono 16-bit PCM
        private const val MAX_AMP_QUEUE = 1_024
    }
}

/**
 * Lock-free value snapshot of realtime playback progress, consumed by the
 * [com.hermesandroid.relay.viewmodel.VoiceViewModel] first-frame watchdog and
 * drain cross-check.
 */
data class RealtimePlaybackSnapshot(
    val active: Boolean,
    val playbackStarted: Boolean,
    val headFrames: Int,
    val framesWritten: Long,
    val sampleRate: Int,
    val playStatePlaying: Boolean,
    val startedAtElapsedMs: Long,
)

/** A written PCM chunk's RMS amplitude tagged with the frame it finishes at. */
internal data class FrameAmp(val endFrame: Long, val rms: Float)

/**
 * Returns the amplitude of the first chunk that has not finished playing
 * ([FrameAmp.endFrame] > [headFrames]) — i.e. the audio at the cursor right now.
 * 0 when the queue is empty or fully drained. Pure for unit testing.
 */
internal fun amplitudeAtHead(queue: List<FrameAmp>, headFrames: Long): Float {
    for (entry in queue) {
        if (entry.endFrame > headFrames) return entry.rms
    }
    return 0f
}

internal data class RealtimePcmStartDecision(
    val shouldStart: Boolean,
    val bufferedMs: Long,
    val reason: String,
)

internal object RealtimePcmBufferPolicy {
    // Realtime voice is latency-sensitive: the provider streams PCM at (or faster
    // than) realtime, so the start prebuffer only needs to cover network jitter,
    // not the whole turn. The large [STREAM_BUFFER_MS] AudioTrack buffer absorbs
    // bursts *after* playback starts; the start thresholds just decide when the
    // very first sample is allowed to leave the queue.
    //
    // A short turn whose audio arrives faster than realtime used to satisfy
    // neither the (2.4s) prebuffer nor the (1.2s) max-wait, so it never started
    // mid-stream and depended entirely on the end-of-turn flush. Lowering these
    // lets streaming start on the first few chunks while keeping enough cushion
    // to ride out jitter.
    const val STARTUP_PREROLL_MS = 120L
    const val START_PREBUFFER_MS = 320L
    const val MIN_PREBUFFER_MS = 160L
    const val MAX_PREBUFFER_WAIT_MS = 280L
    const val MAX_ADAPTIVE_START_PREBUFFER_MS = 1_200L
    // Keep the AudioTrack buffer modest. A multi-second buffer gets routed to
    // Samsung's "deep buffer" output mixer, whose thread is suspended at rest and
    // cold-starts very slowly — the hardware cursor (playbackHeadPosition) stays
    // pinned at 0 for ~2-5s after play() even though playState=PLAYING, focus is
    // held and volume is up. That parked window is the inaudible first/short
    // turn. A sub-second buffer keeps playback on the primary (fast) mixer path,
    // which begins pulling immediately. The ~700ms still absorbs normal network
    // jitter; longer provider gaps (tool calls) underrun-and-resume regardless of
    // buffer size and are handled by notePlaybackGapLocked.
    const val STREAM_BUFFER_MS = 700L
    const val UNDERFLOW_GRACE_MS = 180L

    fun streamBufferSize(minBufferBytes: Int, sampleRate: Int): Int {
        val target = bytesForDurationMs(sampleRate, STREAM_BUFFER_MS)
        return max(minBufferBytes, target)
    }

    fun startDecision(
        pendingBytes: Int,
        sampleRate: Int,
        waitedMs: Long,
        force: Boolean,
        startPrebufferMs: Long = START_PREBUFFER_MS,
    ): RealtimePcmStartDecision {
        val bufferedMs = durationMsForBytes(pendingBytes, sampleRate)
        val targetPrebufferMs = startPrebufferMs.coerceIn(
            START_PREBUFFER_MS,
            MAX_ADAPTIVE_START_PREBUFFER_MS,
        )
        val reason = when {
            force && pendingBytes > 0 -> "flush"
            bufferedMs >= targetPrebufferMs -> "prebuffer"
            bufferedMs >= MIN_PREBUFFER_MS && waitedMs >= MAX_PREBUFFER_WAIT_MS -> "max-wait"
            else -> "buffering"
        }
        return RealtimePcmStartDecision(
            shouldStart = reason != "buffering",
            bufferedMs = bufferedMs,
            reason = reason,
        )
    }

    fun durationMsForBytes(bytes: Int, sampleRate: Int): Long {
        if (bytes <= 0 || sampleRate <= 0) return 0L
        return ((bytes / 2.0) / sampleRate * 1000.0)
            .toLong()
            .coerceAtLeast(1L)
    }

    fun bytesForDurationMs(sampleRate: Int, durationMs: Long): Int {
        if (sampleRate <= 0 || durationMs <= 0L) return 0
        return (sampleRate * 2L * durationMs / 1000L).toInt()
    }

    fun startupPrerollBytes(sampleRate: Int): Int =
        bytesForDurationMs(sampleRate, STARTUP_PREROLL_MS)
}
