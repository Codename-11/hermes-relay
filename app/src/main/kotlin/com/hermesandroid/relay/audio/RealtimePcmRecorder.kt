package com.hermesandroid.relay.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * Captures a short mono 16-bit PCM sample for realtime voice test runs.
 */
class RealtimePcmRecorder(
    private val sampleRate: Int = 16_000,
) {
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
}
