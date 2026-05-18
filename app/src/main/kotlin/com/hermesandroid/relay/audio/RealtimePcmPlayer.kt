package com.hermesandroid.relay.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlin.math.max

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

    val isActive: Boolean
        get() = audioTrack != null

    val audioSessionId: Int
        get() = audioTrack?.audioSessionId ?: 0

    fun write(pcm: ByteArray, sampleRate: Int) {
        if (pcm.isEmpty()) return
        val track = ensureTrack(sampleRate)
        try {
            track.write(pcm, 0, pcm.size)
        } catch (e: Exception) {
            Log.w(TAG, "PCM write failed: ${e.message}")
        }
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

    companion object {
        private const val TAG = "RealtimePcmPlayer"
    }
}
