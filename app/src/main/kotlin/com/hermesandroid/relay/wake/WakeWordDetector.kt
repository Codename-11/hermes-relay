package com.hermesandroid.relay.wake

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

interface WakeWordDetector : AutoCloseable {
    /**
     * Accept a 16 kHz mono PCM16 frame. Returns true when sherpa emits a
     * completed match for the configured phrase.
     */
    fun accept(samples: ShortArray, count: Int): Boolean
}

fun interface WakeWordDetectorFactory {
    fun create(
        files: WakeWordModelFiles,
        sensitivity: Float,
        confirmationFrames: Int,
    ): WakeWordDetector
}

object WakeWordTuning {
    /** sherpa threshold is 0..1 and higher is harder to trigger. */
    fun threshold(sensitivity: Float): Float = sensitivity.coerceIn(0.2f, 0.9f)

    /**
     * sherpa confirms a decoded keyword after this many trailing blank frames.
     * This is the native KWS confirmation control; a completed keyword result
     * must not be counted again in application code.
     */
    fun trailingBlanks(confirmationFrames: Int): Int = confirmationFrames.coerceIn(1, 5)

    fun matchesConfiguredPhrase(keyword: String): Boolean =
        keyword
            .replace('_', ' ')
            .trim()
            .replace(Regex("\\s+"), " ")
            .equals(DEFAULT_WAKE_PHRASE, ignoreCase = true)
}

class SherpaWakeWordDetector(
    files: WakeWordModelFiles,
    sensitivity: Float,
    confirmationFrames: Int,
) : WakeWordDetector {
    private val spotter = KeywordSpotter(
        config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = files.encoder.absolutePath,
                    decoder = files.decoder.absolutePath,
                    joiner = files.joiner.absolutePath,
                ),
                tokens = files.tokens.absolutePath,
                numThreads = 2,
                provider = "cpu",
                modelType = "zipformer2",
            ),
            keywordsFile = files.keywords.absolutePath,
            keywordsScore = 1.5f,
            keywordsThreshold = WakeWordTuning.threshold(sensitivity),
            numTrailingBlanks = WakeWordTuning.trailingBlanks(confirmationFrames),
        ),
    )
    private val stream: OnlineStream = spotter.createStream()
    private var closed = false

    override fun accept(samples: ShortArray, count: Int): Boolean {
        if (closed || count <= 0) return false
        val normalized = FloatArray(count) { index -> samples[index] / 32768.0f }
        stream.acceptWaveform(normalized, sampleRate = 16_000)
        var detected = false
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            val keyword = spotter.getResult(stream).keyword
            if (keyword.isNotBlank()) {
                detected = WakeWordTuning.matchesConfiguredPhrase(keyword)
                // sherpa's KWS contract requires reset immediately after any
                // completed keyword result. Without it the completed result
                // remains attached to the stream and later frames are stale.
                spotter.reset(stream)
                if (detected) break
            }
        }
        return detected
    }

    override fun close() {
        if (closed) return
        closed = true
        stream.release()
        spotter.release()
    }
}
