package com.hermesandroid.relay.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Captures the user's voice as 16 kHz mono PCM and writes a `.wav` file for
 * the relay STT endpoint. The raw PCM is retained for the server-mediated
 * `/voice/realtime/{session}` path so the main voice UI can send the same utterance
 * through the realtime websocket without opening a second microphone stream.
 *
 * A live [amplitude] flow is exposed for the UI (MorphingSphere + meter). The
 * value is computed from the same PCM frames that are written to disk, which
 * keeps legacy STT fallback and realtime voice testing on a single capture
 * path.
 */
class VoiceRecorder(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") private val scope: kotlinx.coroutines.CoroutineScope,
) {

    companion object {
        private const val TAG = "VoiceRecorder"
        private const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_SAMPLE = 2
        private const val CHANNEL_COUNT = 1
        private const val MAX_AMPLITUDE_SHORT = 32_767f
        private const val MAX_PCM_BYTES = 25 * 1024 * 1024

        // Keep the perceptual curve from the previous MediaRecorder-backed
        // implementation so the on-screen meter feels the same.
        private const val NOISE_FLOOR = 0.01f
        private const val SPEECH_CEILING = 0.35f
    }

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    val sampleRate: Int get() = SAMPLE_RATE

    private val bufferLock = Any()
    private val stopRequested = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var currentOutputFile: File? = null
    private var readThread: Thread? = null
    private var readDone: CountDownLatch? = null
    private var pcmBuffer = ByteArrayOutputStream(SAMPLE_RATE * BYTES_PER_SAMPLE * 4)
    private var lastPcmBytes: ByteArray = ByteArray(0)

    /**
     * Begin a new recording. Returns the output [File] that will contain WAV
     * audio once [stopRecording] is called.
     */
    @SuppressLint("MissingPermission")
    fun startRecording(): File {
        if (audioRecord != null) {
            Log.w(TAG, "startRecording called while another recording is in flight; stopping it first")
            try {
                stopRecording()
            } catch (_: Exception) {
                releaseRecorder()
            }
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(SAMPLE_RATE / 10 * BYTES_PER_SAMPLE)

        val outFile = File(context.cacheDir, "voice_rec_${System.currentTimeMillis()}.wav")
        currentOutputFile = outFile
        synchronized(bufferLock) {
            pcmBuffer = ByteArrayOutputStream(SAMPLE_RATE * BYTES_PER_SAMPLE * 4)
            lastPcmBytes = ByteArray(0)
        }
        stopRequested.set(false)
        _amplitude.value = 0f

        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .build()

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            currentOutputFile = null
            throw IllegalStateException("AudioRecord failed to initialize")
        }

        try {
            recorder.startRecording()
        } catch (e: Exception) {
            recorder.release()
            currentOutputFile = null
            throw e
        }

        attachVoiceEffects(recorder.audioSessionId)
        audioRecord = recorder
        val done = CountDownLatch(1)
        readDone = done
        readThread = Thread(
            {
                readPcmLoop(recorder, minBuffer)
                done.countDown()
            },
            "HermesVoiceRecorder",
        ).also { it.start() }
        return outFile
    }

    /**
     * Stop the active recording, write the WAV container, and return it.
     */
    fun stopRecording(): File {
        val file = currentOutputFile
            ?: throw IllegalStateException("stopRecording called with no active recording")

        val record = audioRecord
        stopRequested.set(true)
        if (record != null) {
            try {
                record.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "AudioRecord.stop threw; recording may be empty: ${e.message}")
            }
        }
        readDone?.await(1, TimeUnit.SECONDS)
        releaseRecorder()

        val pcm = synchronized(bufferLock) {
            pcmBuffer.toByteArray().also { lastPcmBytes = it }
        }
        writeWav(file, pcm)
        _amplitude.value = 0f
        return file
    }

    fun isRecording(): Boolean = audioRecord != null && !stopRequested.get()

    fun lastPcmBytes(): ByteArray = synchronized(bufferLock) {
        lastPcmBytes.copyOf()
    }

    /**
     * Release any recorder resources without returning a file.
     */
    fun cancel() {
        stopRequested.set(true)
        audioRecord?.let { record ->
            try { record.stop() } catch (_: Exception) { }
        }
        readDone?.await(500, TimeUnit.MILLISECONDS)
        releaseRecorder()
        currentOutputFile?.let { file ->
            try { file.delete() } catch (_: Exception) { }
        }
        currentOutputFile = null
        synchronized(bufferLock) {
            pcmBuffer.reset()
            lastPcmBytes = ByteArray(0)
        }
        _amplitude.value = 0f
    }

    private fun readPcmLoop(record: AudioRecord, minBuffer: Int) {
        val buffer = ByteArray(minBuffer)
        while (!stopRequested.get()) {
            val read = try {
                record.read(buffer, 0, buffer.size)
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord.read failed: ${e.message}")
                break
            }
            if (read > 0) {
                synchronized(bufferLock) {
                    if (pcmBuffer.size() + read <= MAX_PCM_BYTES) {
                        pcmBuffer.write(buffer, 0, read)
                    } else {
                        stopRequested.set(true)
                    }
                }
                updateAmplitude(buffer, read)
            }
        }
    }

    private fun updateAmplitude(buffer: ByteArray, read: Int) {
        var peak = 0
        var index = 0
        val usable = read - (read % BYTES_PER_SAMPLE)
        while (index < usable) {
            val low = buffer[index].toInt() and 0xff
            val high = buffer[index + 1].toInt()
            val sample = (high shl 8) or low
            val abs = kotlin.math.abs(sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
            if (abs > peak) peak = abs
            index += BYTES_PER_SAMPLE
        }
        val raw01 = (peak.toFloat() / MAX_AMPLITUDE_SHORT).coerceIn(0f, 1f)
        val floored = ((raw01 - NOISE_FLOOR) / (SPEECH_CEILING - NOISE_FLOOR))
            .coerceIn(0f, 1f)
        _amplitude.value = sqrt(floored)
    }

    /**
     * Engage the platform's hardware echo-cancellation and noise-suppression
     * on the [AudioRecord] capture session when the device exposes them —
     * parity with hermes-desktop's `getUserMedia({echoCancellation,
     * noiseSuppression})`. Both are best-effort: many mid-range and older
     * devices report [AcousticEchoCanceler.isAvailable] / [NoiseSuppressor.isAvailable]
     * false, in which case capture proceeds raw (the same behaviour as before
     * this change). AEC in particular keeps the device's own TTS playback from
     * bleeding into the next captured utterance during back-to-back voice turns.
     */
    private fun attachVoiceEffects(sessionId: Int) {
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = try {
                AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            } catch (e: Exception) {
                Log.w(TAG, "AcousticEchoCanceler unavailable: ${e.message}")
                null
            }
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = try {
                NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            } catch (e: Exception) {
                Log.w(TAG, "NoiseSuppressor unavailable: ${e.message}")
                null
            }
        }
    }

    private fun releaseRecorder() {
        echoCanceler?.let { fx ->
            try { fx.release() } catch (_: Exception) { }
        }
        echoCanceler = null
        noiseSuppressor?.let { fx ->
            try { fx.release() } catch (_: Exception) { }
        }
        noiseSuppressor = null
        audioRecord?.let { record ->
            try { record.release() } catch (_: Exception) { }
        }
        audioRecord = null
        readThread = null
        readDone = null
    }

    private fun writeWav(file: File, pcm: ByteArray) {
        try {
            file.outputStream().use { out ->
                out.write(wavHeader(pcm.size))
                out.write(pcm)
            }
        } catch (e: IOException) {
            throw IOException("Failed to write WAV recording: ${e.message}", e)
        }
    }

    private fun wavHeader(pcmBytes: Int): ByteArray {
        val totalDataLen = pcmBytes + 36
        val byteRate = SAMPLE_RATE * CHANNEL_COUNT * BYTES_PER_SAMPLE
        return ByteArray(44).also { header ->
            fun ascii(offset: Int, value: String) {
                value.encodeToByteArray().copyInto(header, offset)
            }
            fun leInt(offset: Int, value: Int) {
                header[offset] = (value and 0xff).toByte()
                header[offset + 1] = ((value shr 8) and 0xff).toByte()
                header[offset + 2] = ((value shr 16) and 0xff).toByte()
                header[offset + 3] = ((value shr 24) and 0xff).toByte()
            }
            fun leShort(offset: Int, value: Int) {
                header[offset] = (value and 0xff).toByte()
                header[offset + 1] = ((value shr 8) and 0xff).toByte()
            }

            ascii(0, "RIFF")
            leInt(4, totalDataLen)
            ascii(8, "WAVE")
            ascii(12, "fmt ")
            leInt(16, 16)
            leShort(20, 1)
            leShort(22, CHANNEL_COUNT)
            leInt(24, SAMPLE_RATE)
            leInt(28, byteRate)
            leShort(32, CHANNEL_COUNT * BYTES_PER_SAMPLE)
            leShort(34, 16)
            ascii(36, "data")
            leInt(40, pcmBytes)
        }
    }
}
