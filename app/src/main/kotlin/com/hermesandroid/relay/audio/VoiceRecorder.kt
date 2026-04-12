package com.hermesandroid.relay.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Captures the user's voice into an `.m4a` (AAC-in-MP4) file for V2a voice
 * mode. The relay's `/voice/transcribe` endpoint feeds this to whisper-1 via
 * OpenAI, which accepts m4a/mp4 natively.
 *
 * A live [amplitude] flow is exposed for the UI (MorphingSphere + meter) —
 * driven by polling `MediaRecorder.maxAmplitude` every ~16 ms. The polling
 * coroutine runs on the caller-supplied [scope] so it dies with the owning
 * ViewModel.
 *
 * One recorder instance owns at most one active recording at a time. Calling
 * [startRecording] again while a recording is in flight will stop the
 * previous one first. [stopRecording] is safe to call when nothing is
 * running (it just returns the last file, or throws if there never was one).
 */
class VoiceRecorder(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "VoiceRecorder"
        private const val SAMPLE_RATE = 16_000
        private const val BIT_RATE = 64_000
        private const val AMPLITUDE_POLL_MS = 16L
        private const val MAX_AMPLITUDE_SHORT = 32_767f
    }

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    private var pollJob: Job? = null

    /**
     * Begin a new recording. Returns the output [File] that will receive the
     * audio once [stopRecording] is called. Throws on permission failure or
     * encoder init failure — callers should catch and surface to the UI.
     */
    @SuppressLint("MissingPermission")
    fun startRecording(): File {
        // Defensive: if a recording is somehow still running, tear it down
        // before starting a new one. MediaRecorder transitions are strict.
        if (mediaRecorder != null) {
            Log.w(TAG, "startRecording called while another recording is in flight — stopping it first")
            try {
                stopRecording()
            } catch (_: Exception) {
                // Swallow — we're about to overwrite state anyway.
                releaseRecorder()
            }
        }

        val outFile = File(context.cacheDir, "voice_rec_${System.currentTimeMillis()}.m4a")
        currentOutputFile = outFile

        val recorder = buildRecorder()
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(SAMPLE_RATE)
            recorder.setAudioEncodingBitRate(BIT_RATE)
            recorder.setAudioChannels(1)
            recorder.setOutputFile(outFile.absolutePath)
            recorder.prepare()
            recorder.start()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "MediaRecorder failed to start: ${e.message}")
            try {
                recorder.reset()
            } catch (_: Exception) { /* ignore */ }
            recorder.release()
            mediaRecorder = null
            currentOutputFile = null
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder setup failed: ${e.message}")
            try {
                recorder.reset()
            } catch (_: Exception) { /* ignore */ }
            recorder.release()
            mediaRecorder = null
            currentOutputFile = null
            throw e
        }

        mediaRecorder = recorder
        startAmplitudePolling()
        return outFile
    }

    /**
     * Stop the active recording, flush the encoder, and return the completed
     * output [File]. Safe to call when nothing is recording — in that case
     * it returns the last file produced, or throws if there never was one.
     */
    fun stopRecording(): File {
        val file = currentOutputFile
            ?: throw IllegalStateException("stopRecording called with no active recording")

        stopAmplitudePolling()

        val recorder = mediaRecorder
        if (recorder != null) {
            try {
                recorder.stop()
            } catch (e: IllegalStateException) {
                // MediaRecorder.stop throws if called before any audio was
                // captured (sub-300ms recordings). Treat as recoverable —
                // the output file may be 0 bytes but the caller can check.
                Log.w(TAG, "MediaRecorder.stop threw — recording may be empty: ${e.message}")
            } catch (e: RuntimeException) {
                Log.w(TAG, "MediaRecorder.stop runtime error: ${e.message}")
            } finally {
                releaseRecorder()
            }
        }

        _amplitude.value = 0f
        return file
    }

    /**
     * True if a recording is currently active. Cheap — just checks whether
     * we have a live [MediaRecorder] reference.
     */
    fun isRecording(): Boolean = mediaRecorder != null

    /**
     * Release any recorder resources without returning a file. Safe fallback
     * for error paths where the output file is known-invalid.
     */
    fun cancel() {
        stopAmplitudePolling()
        mediaRecorder?.let { r ->
            try {
                r.stop()
            } catch (_: Exception) { /* ignore */ }
        }
        releaseRecorder()
        currentOutputFile?.let { f ->
            try { f.delete() } catch (_: Exception) { /* ignore */ }
        }
        currentOutputFile = null
        _amplitude.value = 0f
    }

    private fun releaseRecorder() {
        mediaRecorder?.let { r ->
            try { r.reset() } catch (_: Exception) { /* ignore */ }
            try { r.release() } catch (_: Exception) { /* ignore */ }
        }
        mediaRecorder = null
    }

    @Suppress("DEPRECATION")
    private fun buildRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }

    private fun startAmplitudePolling() {
        pollJob?.cancel()
        pollJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val recorder = mediaRecorder ?: break
                val raw = try {
                    recorder.maxAmplitude
                } catch (e: IllegalStateException) {
                    // Recorder torn down under us — exit quietly.
                    break
                }
                _amplitude.value = (raw.toFloat() / MAX_AMPLITUDE_SHORT).coerceIn(0f, 1f)
                delay(AMPLITUDE_POLL_MS)
            }
        }
    }

    private fun stopAmplitudePolling() {
        pollJob?.cancel()
        pollJob = null
    }
}
