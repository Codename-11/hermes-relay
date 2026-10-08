package com.hermesandroid.relay.wake

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean

/** Hardware belongs to one recognition attempt, including partially built resources. */
internal class WakeWordAudioRecord(
    private val files: WakeWordModelFiles,
    private val preferences: WakeWordPreferences,
) : WakeWordAudio {
    private val lock = Any()
    private val stopped = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var detector: WakeWordDetector? = null

    @SuppressLint("MissingPermission")
    override suspend fun detect(onListening: () -> Unit, onSamples: (ShortArray, Int) -> Unit): Boolean {
        if (stopped.get()) return false
        detector = SherpaWakeWordDetector(files, preferences.sensitivity, preferences.confirmationFrames)
        val minBuffer = AudioRecord.getMinBufferSize(
            16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(6_400)
        val created = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(16_000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .build()
        synchronized(lock) {
            recorder = created
            if (stopped.get()) return false
            check(created.state == AudioRecord.STATE_INITIALIZED) { "Wake-word microphone failed to initialize" }
            created.startRecording()
        }
        onListening()
        val samples = ShortArray(1_600)
        while (!stopped.get()) {
            val count = created.read(samples, 0, samples.size)
            if (stopped.get()) return false
            check(count >= 0) { "Wake-word microphone read failed: $count" }
            onSamples(samples, count)
            if (count > 0 && detector!!.accept(samples, count)) return !stopped.get()
        }
        return false
    }

    override fun stop() {
        stopped.set(true)
        synchronized(lock) { runCatching { recorder?.stop() } }
    }

    override fun close() {
        stop()
        synchronized(lock) {
            runCatching { recorder?.release() }
            recorder = null
        }
        runCatching { detector?.close() }
        detector = null
    }
}
