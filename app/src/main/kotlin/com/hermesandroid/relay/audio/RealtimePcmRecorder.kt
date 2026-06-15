package com.hermesandroid.relay.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Captures mono 16-bit PCM for realtime voice test runs.
 *
 * [capture] grabs a fixed short window (legacy/back-compat). [captureUntilStopped]
 * records open-endedly until [requestStop] is called (tap-to-stop), which is what
 * the Mic demo needs to capture a full spoken sentence.
 */
class RealtimePcmRecorder(
    private val sampleRate: Int = 16_000,
) {
    @Volatile
    private var capturing = false

    /** Signals an in-flight [captureUntilStopped] to finish and return. */
    fun requestStop() {
        capturing = false
    }

    val isCapturing: Boolean
        get() = capturing

    /**
     * Records until [requestStop] is called or [maxDurationMs] elapses, invoking
     * [onLevel] (0..1 RMS) per read so the UI can show a live input waveform.
     */
    @SuppressLint("MissingPermission")
    suspend fun captureUntilStopped(
        maxDurationMs: Long = 15_000,
        onLevel: ((Float) -> Unit)? = null,
    ): ByteArray = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate / 10 * 2)
        val maxBytes = ((sampleRate * maxDurationMs) / 1000L * 2L).toInt()

        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer)
            .build()

        val out = ByteArrayOutputStream(minBuffer * 4)
        val buffer = ByteArray(minBuffer)
        capturing = true
        try {
            recorder.startRecording()
            while (capturing && out.size() < maxBytes) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    out.write(buffer, 0, read)
                    onLevel?.invoke(rms16Le(buffer, read))
                } else {
                    break
                }
            }
        } finally {
            capturing = false
            try { recorder.stop() } catch (_: Exception) { }
            recorder.release()
        }
        out.toByteArray()
    }

    @SuppressLint("MissingPermission")
    suspend fun capture(durationMs: Long = 800): ByteArray = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate / 10 * 2)
        val targetBytes = ((sampleRate * durationMs) / 1000L * 2L)
            .toInt()
            .coerceAtLeast(minBuffer)

        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer)
            .build()

        val out = ByteArrayOutputStream(targetBytes)
        val buffer = ByteArray(minBuffer)
        try {
            recorder.startRecording()
            while (out.size() < targetBytes) {
                val read = recorder.read(
                    buffer,
                    0,
                    min(buffer.size, targetBytes - out.size()),
                )
                if (read > 0) {
                    out.write(buffer, 0, read)
                } else {
                    break
                }
            }
        } finally {
            try { recorder.stop() } catch (_: Exception) { }
            recorder.release()
        }
        out.toByteArray()
    }

    private fun rms16Le(buffer: ByteArray, length: Int): Float {
        val usable = length - (length % 2)
        if (usable <= 0) return 0f
        var sum = 0.0
        var i = 0
        while (i < usable) {
            val low = buffer[i].toInt() and 0xff
            val high = buffer[i + 1].toInt()
            val sample = ((high shl 8) or low).toShort().toInt() / 32768.0
            sum += sample * sample
            i += 2
        }
        val rms = sqrt(sum / (usable / 2))
        return sqrt((rms / 0.28).coerceIn(0.0, 1.0)).toFloat()
    }
}
