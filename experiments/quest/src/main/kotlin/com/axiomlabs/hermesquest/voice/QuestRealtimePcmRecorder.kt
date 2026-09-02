package com.axiomlabs.hermesquest.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.min

class QuestRealtimePcmRecorder(
    private val sampleRate: Int = SAMPLE_RATE,
) {
    @SuppressLint("MissingPermission")
    suspend fun capture(durationMs: Long = 1_000): ByteArray = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate / 10 * 2)
        val targetBytes = ((sampleRate * durationMs) / 1_000L * 2L)
            .toInt()
            .coerceAtLeast(minBuffer)

        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
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
            runCatching { recorder.stop() }
            recorder.release()
        }
        out.toByteArray()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
