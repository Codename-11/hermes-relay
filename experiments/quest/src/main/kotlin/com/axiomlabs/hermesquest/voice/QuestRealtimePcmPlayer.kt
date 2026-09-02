package com.axiomlabs.hermesquest.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlin.math.max

class QuestRealtimePcmPlayer {
    private var audioTrack: AudioTrack? = null
    private var currentSampleRate: Int = 0

    fun write(pcm: ByteArray, sampleRate: Int) {
        if (pcm.isEmpty()) return
        runCatching {
            ensureTrack(sampleRate).write(pcm, 0, pcm.size)
        }.onFailure {
            Log.w(TAG, "PCM playback failed: ${it.message}")
        }
    }

    fun stop() {
        audioTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
        audioTrack = null
        currentSampleRate = 0
    }

    private fun ensureTrack(sampleRate: Int): AudioTrack {
        val existing = audioTrack
        if (existing != null && currentSampleRate == sampleRate) return existing
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
                        .build(),
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
        audioTrack = track
        currentSampleRate = sampleRate
        return track
    }

    companion object {
        private const val TAG = "QuestPcmPlayer"
    }
}
