package com.hermesandroid.relay.wake

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

interface WakeWordDetector : AutoCloseable {
    /**
     * Accept a 16 kHz mono PCM16 frame. Returns true exactly once when the
     * configured confirmation count is met.
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

internal class WakeWordConfirmationGate(private val requiredFrames: Int) {
    private var matchingFrames = 0
    private var fired = false

    fun update(matches: Boolean): Boolean {
        if (fired) return false
        matchingFrames = if (matches) matchingFrames + 1 else 0
        if (matchingFrames < requiredFrames.coerceIn(1, 5)) return false
        fired = true
        return true
    }
}

object WakeWordTuning {
    /** sherpa threshold is 0..1 and higher is harder to trigger. */
    fun threshold(sensitivity: Float): Float = sensitivity.coerceIn(0.2f, 0.9f)
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
            numTrailingBlanks = 2,
        ),
    )
    private val stream: OnlineStream = spotter.createStream()
    private val confirmationGate = WakeWordConfirmationGate(confirmationFrames)
    private var closed = false

    override fun accept(samples: ShortArray, count: Int): Boolean {
        if (closed || count <= 0) return false
        val normalized = FloatArray(count) { index -> samples[index] / 32768.0f }
        stream.acceptWaveform(normalized, sampleRate = 16_000)
        var detected = false
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            val matches = spotter.getResult(stream).keyword
                .replace('_', ' ')
                .trim()
                .equals(DEFAULT_WAKE_PHRASE, ignoreCase = true)
            if (confirmationGate.update(matches)) {
                detected = true
                break
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
