package com.hermesandroid.relay.audio

import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.sqrt

/**
 * Plays a TTS audio file emitted by the relay's `/voice/synthesize` endpoint
 * and exposes a live [amplitude] flow for the MorphingSphere / UI meter.
 *
 * Amplitude is computed from [Visualizer] PCM waveform RMS — one waveform
 * snapshot is captured every ~40 ms (the visualizer's default capture rate)
 * and reduced to a 0..1 float. On devices that don't allow Visualizer
 * construction (missing MODIFY_AUDIO_SETTINGS or OEM quirks) we log and
 * continue with amplitude pinned at 0 rather than crashing the voice session.
 *
 * One player instance owns at most one active playback. Calling [play] again
 * while something is playing stops the previous file first.
 */
class VoicePlayer {

    companion object {
        private const val TAG = "VoicePlayer"
        private const val VISUALIZER_SIZE_BYTES = 1024
    }

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var visualizer: Visualizer? = null
    private var completionListener: (() -> Unit)? = null

    /**
     * Start playback of [audioFile]. Returns immediately; completion is
     * delivered via [awaitCompletion]. If another file is already playing,
     * [stop]s it first.
     */
    fun play(audioFile: File) {
        if (mediaPlayer != null) {
            Log.w(TAG, "play called while another file is playing — stopping first")
            stop()
        }

        val player = MediaPlayer()
        try {
            player.setDataSource(audioFile.absolutePath)
            player.prepare()
            player.setOnCompletionListener {
                _amplitude.value = 0f
                completionListener?.invoke()
            }
            player.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                _amplitude.value = 0f
                completionListener?.invoke()
                true
            }
            player.start()
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer setup failed: ${e.message}")
            try { player.release() } catch (_: Exception) { /* ignore */ }
            throw e
        }

        mediaPlayer = player
        attachVisualizer(player)
    }

    /**
     * Suspend until the current playback completes or errors. Cancellable —
     * if the caller cancels, playback is left running (use [stop] for a
     * hard teardown).
     */
    suspend fun awaitCompletion(): Unit = suspendCancellableCoroutine { cont ->
        if (mediaPlayer == null) {
            cont.resume(Unit)
            return@suspendCancellableCoroutine
        }
        completionListener = {
            completionListener = null
            if (cont.isActive) cont.resume(Unit)
        }
        cont.invokeOnCancellation {
            completionListener = null
        }
    }

    /**
     * Hard teardown: stop playback, release visualizer + player, reset
     * amplitude. Safe to call repeatedly.
     */
    fun stop() {
        completionListener = null

        visualizer?.let { v ->
            try { v.enabled = false } catch (_: Exception) { /* ignore */ }
            try { v.release() } catch (_: Exception) { /* ignore */ }
        }
        visualizer = null

        mediaPlayer?.let { p ->
            try {
                if (p.isPlaying) p.stop()
            } catch (_: Exception) { /* ignore */ }
            try { p.reset() } catch (_: Exception) { /* ignore */ }
            try { p.release() } catch (_: Exception) { /* ignore */ }
        }
        mediaPlayer = null

        _amplitude.value = 0f
    }

    /**
     * True if there's an active [MediaPlayer]. Doesn't check `isPlaying` —
     * that would race with the completion listener.
     */
    fun isPlaying(): Boolean = mediaPlayer != null

    private fun attachVisualizer(player: MediaPlayer) {
        try {
            val viz = Visualizer(player.audioSessionId)
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
        } catch (e: Exception) {
            // Some devices refuse Visualizer (MODIFY_AUDIO_SETTINGS denied,
            // OEM restrictions). Fall back to flat-zero amplitude rather
            // than killing the voice session.
            Log.w(TAG, "Visualizer unavailable — amplitude stuck at 0: ${e.message}")
            _amplitude.value = 0f
            visualizer = null
        }
    }

    /**
     * RMS of an 8-bit unsigned PCM waveform, mapped to 0..1. Visualizer
     * emits signed bytes centered at 128 (0x80), so the first step is to
     * re-center at 0.
     */
    private fun computeRms(waveform: ByteArray): Float {
        var sumSq = 0.0
        for (b in waveform) {
            val sample = (b.toInt() and 0xFF) - 128
            sumSq += (sample * sample).toDouble()
        }
        val rms = sqrt(sumSq / waveform.size)
        // 128 is the theoretical max deviation for re-centered 8-bit PCM.
        return (rms / 128.0).coerceIn(0.0, 1.0).toFloat()
    }
}
