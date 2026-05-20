package com.hermesandroid.relay.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.util.Log
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
class RealtimePcmPlayer {
    private var audioTrack: AudioTrack? = null
    private var currentSampleRate: Int = 0
    private var currentVolume: Float = 1f
    private val playbackLock = Any()
    private var estimatedPlaybackEndAtMs: Long = 0L
    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    val isActive: Boolean
        get() = audioTrack != null

    val audioSessionId: Int
        get() = audioTrack?.audioSessionId ?: 0

    fun write(pcm: ByteArray, sampleRate: Int): Float {
        if (pcm.isEmpty()) return 0f
        val level = computePcm16LeRms(pcm)
        val track = ensureTrack(sampleRate)
        try {
            val written = track.write(pcm, 0, pcm.size)
            if (written > 0) {
                noteWrittenBytes(written, sampleRate)
                _amplitude.value = level
            }
        } catch (e: Exception) {
            Log.w(TAG, "PCM write failed: ${e.message}")
            return 0f
        }
        return level
    }

    fun stop() {
        audioTrack?.let { track ->
            Log.i(TAG, "Stopping streaming PCM playback")
            try { track.pause() } catch (_: Exception) { }
            try { track.flush() } catch (_: Exception) { }
            try { track.release() } catch (_: Exception) { }
        }
        audioTrack = null
        currentSampleRate = 0
        synchronized(playbackLock) {
            estimatedPlaybackEndAtMs = 0L
        }
        _amplitude.value = 0f
    }

    fun estimatedRemainingPlaybackMs(cushionMs: Long = DEFAULT_DRAIN_CUSHION_MS): Long {
        if (audioTrack == null) return 0L
        val now = SystemClock.elapsedRealtime()
        return synchronized(playbackLock) {
            (estimatedPlaybackEndAtMs - now + cushionMs).coerceAtLeast(0L)
        }
    }

    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        currentVolume = clamped
        try { audioTrack?.setVolume(clamped) } catch (_: Exception) { }
    }

    fun duck() {
        setVolume(0.3f)
    }

    fun unduck() {
        setVolume(1f)
    }

    private fun ensureTrack(sampleRate: Int): AudioTrack {
        val existing = audioTrack
        if (existing != null && currentSampleRate == sampleRate) {
            return existing
        }
        stop()

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate / 10 * 2)
        val bufferSize = max(minBuffer, sampleRate / 2 * 2)
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
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
        track.play()
        try { track.setVolume(currentVolume) } catch (_: Exception) { }
        audioTrack = track
        currentSampleRate = sampleRate
        Log.i(TAG, "Started streaming PCM playback at ${sampleRate}Hz session=${track.audioSessionId}")
        return track
    }

    private fun noteWrittenBytes(writtenBytes: Int, sampleRate: Int) {
        if (writtenBytes <= 0 || sampleRate <= 0) return
        val durationMs = ((writtenBytes / 2.0) / sampleRate * 1000.0)
            .toLong()
            .coerceAtLeast(1L)
        val now = SystemClock.elapsedRealtime()
        synchronized(playbackLock) {
            val base = max(now, estimatedPlaybackEndAtMs)
            estimatedPlaybackEndAtMs = base + durationMs
        }
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
    }
}
