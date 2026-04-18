package com.hermesandroid.relay.audio

import android.content.Context
import android.media.audiofx.Visualizer
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.io.File
import kotlin.math.sqrt

/**
 * Plays TTS audio files emitted by the relay's `/voice/synthesize` endpoint
 * and exposes a live [amplitude] flow for the MorphingSphere / UI meter.
 *
 * Backed by a single Media3 [ExoPlayer] that lives for the lifetime of this
 * [VoicePlayer] instance. [play] appends a new [MediaItem] to the player's
 * queue so adjacent TTS sentences play back-to-back without the per-file
 * codec re-init seam that the old `MediaPlayer` implementation produced.
 * This is the foundation of the Wave 1 gapless-playback work in the voice
 * quality pass plan — V5 in `docs/plans/2026-04-16-voice-quality-pass.md`.
 *
 * Amplitude is computed from [Visualizer] PCM waveform RMS — one waveform
 * snapshot is captured every ~40 ms (the visualizer's default capture rate)
 * and reduced to a 0..1 float. On devices that don't allow Visualizer
 * construction (missing MODIFY_AUDIO_SETTINGS or OEM quirks) we log and
 * continue with amplitude pinned at 0 rather than crashing the voice session.
 *
 * The Visualizer is attached exactly once against the ExoPlayer's
 * [ExoPlayer.getAudioSessionId]. There is a known gotcha where re-attaching
 * the Visualizer on every track transition invalidates the session id — the
 * single-attach lifecycle here sidesteps it entirely.
 *
 * @param context used for [ExoPlayer.Builder]. Application context is fine;
 *   the player holds no view references.
 */
@OptIn(UnstableApi::class)
class VoicePlayer(context: Context) {

    companion object {
        private const val TAG = "VoicePlayer"
        private const val VISUALIZER_SIZE_BYTES = 1024
    }

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    // Mirrors the most recent value passed to [setVolume] / [duck] / [unduck].
    // ExoPlayer's own `volume` getter is the source of truth for the audio
    // pipeline, but we keep a local copy so callers can introspect current
    // ducking state without racing the underlying ExoPlayer thread, and so
    // future reconfig paths (reconstruct ExoPlayer, swap sink, etc.) can
    // re-apply the same volume without losing the caller's intent.
    @Volatile private var currentVolume: Float = 1f

    // Tracked via Player.Listener.onIsPlayingChanged so awaitCompletion can
    // suspend on the combined (isPlaying, mediaItemCount) signal without
    // polling the player from arbitrary threads.
    private val _isPlaying = MutableStateFlow(false)

    // Mirrors ExoPlayer.getMediaItemCount() via the Player.Listener events
    // that can mutate it (onMediaItemTransition for drain, explicit add/clear
    // calls for growth). Kept as a StateFlow so awaitCompletion can reactively
    // wait for queue-drained + idle without polling.
    private val _queueCount = MutableStateFlow(0)

    private var visualizer: Visualizer? = null
    private var visualizerAttached = false

    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setHandleAudioBecomingNoisy(true)
        .build()
        .also { player ->
            player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _isPlaying.value = isPlaying
                    if (!isPlaying) _amplitude.value = 0f
                    // Lazily attach the Visualizer the first time playback
                    // actually begins — the audio session id is stable from
                    // player construction on Media3 1.x but some OEM pipelines
                    // don't allocate the track until playback starts.
                    if (isPlaying && !visualizerAttached) {
                        attachVisualizer(player.audioSessionId)
                    }
                }

                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int,
                ) {
                    // Refresh on every transition — covers auto-advance drain
                    // at end-of-queue and explicit seekToNext paths.
                    _queueCount.value = player.mediaItemCount
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) {
                        // ENDED fires when the full queue has been consumed;
                        // sync queueCount so awaitCompletion can release.
                        _queueCount.value = player.mediaItemCount
                    }
                }
            })
        }

    /**
     * Append [audioFile] to the ExoPlayer queue. If the player is idle, also
     * [ExoPlayer.prepare] and [ExoPlayer.play]. Non-blocking — completion is
     * delivered via [awaitCompletion], which now observes the entire queue
     * rather than a single file.
     */
    fun play(audioFile: File) {
        val wasIdle = exoPlayer.mediaItemCount == 0 &&
            exoPlayer.playbackState != Player.STATE_READY &&
            exoPlayer.playbackState != Player.STATE_BUFFERING
        exoPlayer.addMediaItem(MediaItem.fromUri(audioFile.toUri()))
        _queueCount.value = exoPlayer.mediaItemCount
        if (wasIdle) {
            exoPlayer.prepare()
            exoPlayer.play()
        } else if (!exoPlayer.isPlaying && exoPlayer.playWhenReady.not()) {
            // Queue had drained but player wasn't torn down — restart.
            exoPlayer.play()
        }
    }

    /**
     * Suspend until the ExoPlayer queue is drained AND playback has stopped.
     *
     * **Semantic change from the old MediaPlayer implementation.** Previously
     * this returned when the *current file* completed. Now it returns when
     * the entire queue has been consumed — i.e. `mediaItemCount == 0 &&
     * !isPlaying`. This matches the gapless-playback model where adjacent
     * sentences play back-to-back from the same ExoPlayer, and it's exactly
     * what the V4 prefetch pipelining rewrite needs (synth worker can enqueue
     * N+1 while play worker is still awaiting queue-drain on N).
     *
     * If a caller appends new items to the queue while this is suspended,
     * the wait extends through the new items as well.
     *
     * Cancellable. If the caller cancels, playback is left running — use
     * [stop] for a hard teardown.
     */
    suspend fun awaitCompletion() {
        // Fast-path: already idle.
        if (_queueCount.value == 0 && !_isPlaying.value) return
        combine(_queueCount, _isPlaying) { count, playing -> count == 0 && !playing }
            .first { drained -> drained }
    }

    /**
     * Hard teardown of the current playback session. Clears the queue,
     * stops ExoPlayer, releases the Visualizer, and resets amplitude.
     * The ExoPlayer itself is kept alive for reuse — the next [play] call
     * will re-prepare it. Safe to call repeatedly.
     */
    fun stop() {
        exoPlayer.clearMediaItems()
        exoPlayer.stop()
        _queueCount.value = 0
        _isPlaying.value = false

        visualizer?.let { v ->
            try { v.enabled = false } catch (_: Exception) { /* ignore */ }
            try { v.release() } catch (_: Exception) { /* ignore */ }
        }
        visualizer = null
        visualizerAttached = false

        _amplitude.value = 0f
    }

    /**
     * True if the ExoPlayer has any queued media items (playing or paused
     * mid-queue). Matches the old semantic of "there's audio in flight".
     */
    fun isPlaying(): Boolean = _queueCount.value > 0

    /**
     * Current ExoPlayer audio session id. Returns `0` until the underlying
     * [android.media.AudioTrack] has been allocated — Media3 defers that
     * allocation to first playback on most devices. Callers that need a
     * non-zero session id (barge-in's [android.media.audiofx.AcousticEchoCanceler]
     * attach path in [com.hermesandroid.relay.audio.BargeInListener]) should
     * poll this property briefly rather than assume it's hot-ready at
     * [VoicePlayer] construction time.
     *
     * Exposed read-only. Internally the same id drives the Visualizer
     * attach logic in [attachVisualizer]; B4 reads it via a provider
     * lambda so the listener can re-check across the 1 s poll window
     * without holding a stale reference.
     */
    val audioSessionId: Int
        get() = exoPlayer.audioSessionId

    /**
     * Set the playback volume of the underlying ExoPlayer.
     *
     * **Barge-in use case.** The barge-in pipeline (see
     * `docs/plans/2026-04-17-voice-barge-in.md`, unit B6) runs the mic
     * through a Silero VAD while TTS plays. On a *single* "maybe speech"
     * frame — one positive frame that hasn't yet passed the hysteresis
     * debounce — we soft-duck via [duck] instead of hard-stopping. If the
     * speech is confirmed (enough consecutive positive frames pass the
     * debounce), [VoiceViewModel] calls the hard-stop path
     * (`interruptSpeaking()`); if the frame was a false positive, a
     * watchdog re-calls [unduck] to restore full volume. The result is a
     * fast-reacting but false-positive-tolerant interruption feel.
     *
     * @param volume linear gain in the range `0f..1f`; values outside this
     *   range are clamped. Forwarded verbatim to `ExoPlayer.volume`.
     */
    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        currentVolume = clamped
        exoPlayer.volume = clamped
    }

    /**
     * Soft-duck TTS to 30% of full volume. See [setVolume] for context —
     * used by barge-in on a single VAD positive frame, before the
     * hysteresis debounce confirms an actual interruption.
     */
    fun duck() {
        setVolume(0.3f)
    }

    /**
     * Restore TTS to full volume. Pair with [duck]; safe to call even if
     * not currently ducked.
     */
    fun unduck() {
        setVolume(1.0f)
    }

    /**
     * Fully release the underlying ExoPlayer. Call when the owning scope is
     * being destroyed; the VoicePlayer instance is unusable after this.
     */
    fun release() {
        stop()
        exoPlayer.release()
    }

    private fun attachVisualizer(audioSessionId: Int) {
        if (audioSessionId == 0) {
            // ExoPlayer returns 0 before the audio track is allocated; retry
            // on the next playback-start event.
            return
        }
        try {
            val viz = Visualizer(audioSessionId)
            viz.captureSize = VISUALIZER_SIZE_BYTES.coerceIn(
                Visualizer.getCaptureSizeRange()[0],
                Visualizer.getCaptureSizeRange()[1],
            )
            viz.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int,
                    ) {
                        if (waveform == null || waveform.isEmpty()) return
                        _amplitude.value = computeRms(waveform)
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int,
                    ) { /* unused */ }
                },
                Visualizer.getMaxCaptureRate() / 2,
                true, // waveform
                false, // fft
            )
            viz.enabled = true
            visualizer = viz
            visualizerAttached = true
        } catch (e: Exception) {
            // Some devices refuse Visualizer (MODIFY_AUDIO_SETTINGS denied,
            // OEM restrictions). Fall back to flat-zero amplitude rather
            // than killing the voice session. Mark as "attached" so we don't
            // keep retrying on every isPlaying transition.
            Log.w(TAG, "Visualizer unavailable — amplitude stuck at 0: ${e.message}")
            _amplitude.value = 0f
            visualizer = null
            visualizerAttached = true
        }
    }

    /**
     * RMS of an 8-bit unsigned PCM waveform, mapped to 0..1. Visualizer
     * emits signed bytes centered at 128 (0x80), so the first step is to
     * re-center at 0.
     *
     * Guards: empty waveform short-circuits to 0 to avoid `0.0 / 0 = NaN`,
     * and the final result is NaN-filtered before returning. A single NaN
     * amplitude frame would otherwise propagate through `animateFloatAsState`
     * and cause Android 15 to log `setRequestedFrameRate frameRate=NaN` on
     * every draw pass.
     */
    private fun computeRms(waveform: ByteArray): Float {
        if (waveform.isEmpty()) return 0f
        var sumSq = 0.0
        for (b in waveform) {
            val sample = (b.toInt() and 0xFF) - 128
            sumSq += (sample * sample).toDouble()
        }
        val rms = sqrt(sumSq / waveform.size)
        // 128 is the theoretical max deviation for re-centered 8-bit PCM.
        val normalized = (rms / 128.0).toFloat()
        return if (normalized.isNaN() || normalized.isInfinite()) 0f
               else normalized.coerceIn(0f, 1f)
    }
}
