package com.hermesandroid.relay.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * Tiny synthesized-PCM chime player for voice-mode enter/exit feedback.
 *
 * Two buffers are generated once at construction — an ascending sweep
 * (440 → 660 Hz, a perfect fifth) for enter, and its mirror (660 → 440 Hz)
 * for exit. Both use the same attack / hold / decay envelope so the pair
 * sounds symmetrical. Played via [AudioTrack] in [AudioTrack.MODE_STATIC]
 * so replays are low-latency — the buffer is written once and we just
 * rewind with [AudioTrack.reloadStaticData] on each trigger.
 *
 * Construction and every runtime call is wrapped in try/catch: some OEM
 * devices refuse AudioTrack under unusual states (busy audio HAL, no route)
 * and we never want a UI chime to crash voice mode.
 */
class VoiceSfxPlayer(@Suppress("UNUSED_PARAMETER") context: Context) {

    private val enterTrack: AudioTrack? = buildChimeTrack(ascending = true)
    private val exitTrack: AudioTrack? = buildChimeTrack(ascending = false)

    fun playEnter() {
        playTrack(enterTrack)
    }

    fun playExit() {
        playTrack(exitTrack)
    }

    fun release() {
        try { enterTrack?.release() } catch (e: Exception) { Log.w(TAG, "enter release failed: ${e.message}") }
        try { exitTrack?.release() } catch (e: Exception) { Log.w(TAG, "exit release failed: ${e.message}") }
    }

    // ---------------------------------------------------------------------

    private fun playTrack(track: AudioTrack?) {
        if (track == null) return
        try {
            // Rewind the static buffer before each play so repeat invocations
            // start from frame 0. stop() is a no-op if it's already stopped.
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause()
            }
            track.stop()
            track.reloadStaticData()
            track.play()
        } catch (e: Exception) {
            Log.w(TAG, "chime play failed: ${e.message}")
        }
    }

    private fun buildChimeTrack(ascending: Boolean): AudioTrack? {
        return try {
            val pcm = synthesizeSweep(ascending)
            val bytes = pcm.size * 2 // 16-bit = 2 bytes/sample
            val attrs = AudioAttributes.Builder()
                // Assistant chime — closest semantic match for a voice-mode ping.
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            val written = track.write(pcm, 0, pcm.size)
            if (written < 0) {
                Log.w(TAG, "AudioTrack.write returned $written; skipping chime")
                track.release()
                return null
            }
            track
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack construction failed: ${e.message}")
            null
        }
    }

    /**
     * Generate a 200 ms sine sweep with a fast attack / smooth decay envelope.
     * Peak amplitude capped at 0.35 of full-scale so it reads as a subtle UI
     * chime rather than a notification.
     */
    private fun synthesizeSweep(ascending: Boolean): ShortArray {
        val totalSamples = (SAMPLE_RATE * DURATION_SEC).toInt()
        val attackSamples = (SAMPLE_RATE * ATTACK_SEC).toInt()
        val decaySamples = (SAMPLE_RATE * DECAY_SEC).toInt()
        val out = ShortArray(totalSamples)

        val startHz = if (ascending) LOW_HZ else HIGH_HZ
        val endHz = if (ascending) HIGH_HZ else LOW_HZ

        // Accumulate phase from instantaneous frequency so the sweep is
        // continuous — computing sin(2π·f(t)·t) directly produces chirp
        // artifacts because f is itself a function of t.
        var phase = 0.0
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / totalSamples
            val freq = startHz + (endHz - startHz) * t
            phase += 2.0 * PI * freq / SAMPLE_RATE

            // Envelope: linear ramp up over attack, smooth cosine fade over decay.
            val env = when {
                i < attackSamples -> i.toFloat() / attackSamples
                i >= totalSamples - decaySamples -> {
                    val d = (totalSamples - i).toFloat() / decaySamples
                    // Half-cosine ease: 0 → 1 as d goes 0 → 1.
                    (0.5f * (1f - kotlin.math.cos(PI.toFloat() * d)))
                }
                else -> 1f
            }

            val sample = sin(phase).toFloat() * env * PEAK_AMPLITUDE
            out[i] = (sample * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return out
    }

    companion object {
        private const val TAG = "VoiceSfxPlayer"
        private const val SAMPLE_RATE = 44100
        private const val DURATION_SEC = 0.20f
        private const val ATTACK_SEC = 0.015f
        private const val DECAY_SEC = 0.040f
        private const val LOW_HZ = 440.0
        private const val HIGH_HZ = 660.0
        private const val PEAK_AMPLITUDE = 0.35f
    }
}
