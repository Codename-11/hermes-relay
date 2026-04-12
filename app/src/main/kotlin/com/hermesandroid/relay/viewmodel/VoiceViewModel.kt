package com.hermesandroid.relay.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermesandroid.relay.audio.VoicePlayer
import com.hermesandroid.relay.audio.VoiceRecorder
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.network.RelayVoiceClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Where we are in the voice conversation cycle. Used to drive the UI
 * overlay + MorphingSphere state in V2b/V3.
 */
enum class VoiceState { Idle, Listening, Transcribing, Thinking, Speaking, Error }

/**
 * Tap-to-talk vs hold-to-talk vs always-listening. Persisted via Settings
 * in V2b — the ViewModel just stores the current selection.
 */
enum class InteractionMode { TapToTalk, HoldToTalk, Continuous }

/**
 * The single piece of state the voice UI renders. This shape is frozen
 * as of V2a so the V2b UI teammate can start wiring without chasing
 * further changes. Add new fields rather than renaming existing ones.
 */
data class VoiceUiState(
    /** True when the voice-mode overlay is shown. */
    val voiceMode: Boolean = false,
    /** Current phase of the voice turn. */
    val state: VoiceState = VoiceState.Idle,
    /** 0.0-1.0. Drives MorphingSphere pulse + on-screen meter. ~60 FPS. */
    val amplitude: Float = 0f,
    /** Last successful user transcript (shown briefly in overlay). */
    val transcribedText: String? = null,
    /** Streaming agent text for the current turn. */
    val responseText: String = "",
    /** Human-readable error surfaced in the overlay. */
    val error: String? = null,
    /** Currently-selected interaction mode. */
    val interactionMode: InteractionMode = InteractionMode.TapToTalk,
)

/**
 * Orchestrates the voice-mode turn cycle:
 *
 *   Idle → Listening (record mic) → Transcribing (upload) → Thinking
 *        → Speaking (sentence-buffered TTS) → Idle
 *
 * Requires [VoiceRecorder], [VoicePlayer], [RelayVoiceClient], and a
 * [ChatViewModel] for sending the transcribed text through the normal
 * chat pipeline. All four are wired via [initialize] after construction.
 *
 * ### Sentence-boundary streaming TTS
 * The SSE stream emits text one token at a time, but TTS wants whole
 * sentences to sound natural. We observe [ChatViewModel.messages],
 * extract deltas from the currently-streaming assistant message, and
 * feed each completed sentence into a bounded [ttsQueue]. A dedicated
 * consumer coroutine pulls from the queue, synthesizes each sentence,
 * and plays them back-to-back via [VoicePlayer.awaitCompletion].
 *
 * ### Integration note (V2a → V2b cleanup)
 * This first version uses the public [ChatViewModel.messages] StateFlow
 * to observe streaming deltas rather than adding a `// VOICE HOOK`
 * callback inside ChatViewModel. It's clean but depends on the
 * "last message with isStreaming=true" invariant — if ChatViewModel
 * ever streams multiple assistant messages concurrently this will need
 * a dedicated per-turn flow. See `DEVLOG.md` and V2b ticket.
 */
class VoiceViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "VoiceViewModel"
        private const val TTS_CACHE_CAP = 6 // keep the last N mp3s on disk
    }

    // --- Dependencies (injected via initialize) --------------------------

    private var voiceClient: RelayVoiceClient? = null
    private var chatViewModel: ChatViewModel? = null
    private var recorder: VoiceRecorder? = null
    private var player: VoicePlayer? = null

    // --- UI state --------------------------------------------------------

    private val _uiState = MutableStateFlow(VoiceUiState())
    val uiState: StateFlow<VoiceUiState> = _uiState.asStateFlow()

    // --- Internal streaming/playback state -------------------------------

    /** Bounded channel queues sentences pending synthesis/playback. */
    private val ttsQueue = Channel<String>(Channel.UNLIMITED)

    /** Tracks which assistant-message IDs have already been consumed so
     *  we don't re-process older turns when the history list updates. */
    private var lastObservedMessageId: String? = null
    private var lastObservedContentLength: Int = 0
    private var sentenceBuffer: StringBuilder = StringBuilder()

    private var streamObserverJob: Job? = null
    private var ttsConsumerJob: Job? = null
    private var amplitudeBridgeJob: Job? = null
    private var currentTurnJob: Job? = null

    /** MP3 files produced by synthesize — trimmed to [TTS_CACHE_CAP]. */
    private val ttsFileHistory = ArrayDeque<File>()

    // ---------------------------------------------------------------------
    // Initialization
    // ---------------------------------------------------------------------

    /**
     * Wire dependencies. Called once from `RelayApp` after the singletons
     * are constructed. Safe to call multiple times — later calls rewire.
     */
    fun initialize(
        voiceClient: RelayVoiceClient,
        chatViewModel: ChatViewModel,
        recorder: VoiceRecorder,
        player: VoicePlayer,
    ) {
        this.voiceClient = voiceClient
        this.chatViewModel = chatViewModel
        this.recorder = recorder
        this.player = player

        startTtsConsumer()
        bridgeAmplitudeFlows()
    }

    fun setInteractionMode(mode: InteractionMode) {
        _uiState.update { it.copy(interactionMode = mode) }
    }

    // ---------------------------------------------------------------------
    // Voice-mode lifecycle
    // ---------------------------------------------------------------------

    fun enterVoiceMode() {
        _uiState.update { it.copy(voiceMode = true, state = VoiceState.Idle, error = null) }
    }

    fun exitVoiceMode() {
        // Tear down anything in flight.
        try {
            recorder?.cancel()
        } catch (_: Exception) { /* ignore */ }
        try {
            player?.stop()
        } catch (_: Exception) { /* ignore */ }
        currentTurnJob?.cancel()
        currentTurnJob = null
        streamObserverJob?.cancel()
        streamObserverJob = null
        sentenceBuffer = StringBuilder()
        lastObservedMessageId = null
        lastObservedContentLength = 0

        _uiState.update {
            it.copy(
                voiceMode = false,
                state = VoiceState.Idle,
                amplitude = 0f,
                transcribedText = null,
                responseText = "",
                error = null,
            )
        }
    }

    // ---------------------------------------------------------------------
    // Listening (record user's voice)
    // ---------------------------------------------------------------------

    fun startListening() {
        val rec = recorder
        if (rec == null) {
            setError("Recorder not initialized")
            return
        }
        if (_uiState.value.state == VoiceState.Listening) return

        // Stop any TTS playback when the user starts talking.
        try { player?.stop() } catch (_: Exception) { /* ignore */ }

        try {
            rec.startRecording()
            _uiState.update {
                it.copy(state = VoiceState.Listening, error = null, responseText = "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed: ${e.message}")
            setError("Couldn't start recording: ${e.message ?: "unknown"}")
        }
    }

    fun stopListening() {
        val rec = recorder ?: return
        if (_uiState.value.state != VoiceState.Listening) return

        val file = try {
            rec.stopRecording()
        } catch (e: Exception) {
            Log.e(TAG, "stopListening failed: ${e.message}")
            setError("Couldn't stop recording: ${e.message ?: "unknown"}")
            return
        }

        currentTurnJob?.cancel()
        currentTurnJob = viewModelScope.launch {
            processVoiceInput(file)
        }
    }

    /**
     * Stop speaking (and any queued sentences) and transition back to
     * Listening. Drains the TTS queue so the agent stops mid-sentence.
     */
    fun interruptSpeaking() {
        // Empty the queue without closing it.
        while (true) {
            val r = ttsQueue.tryReceive()
            if (r.isFailure || r.isClosed) break
        }
        try { player?.stop() } catch (_: Exception) { /* ignore */ }
        _uiState.update { it.copy(state = VoiceState.Listening, responseText = "") }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    // V2b EXTENSION --------------------------------------------------------
    // Fire a one-shot TTS synth+playback to verify the voice pipeline from
    // the settings screen without entering full voice mode. Runs outside
    // the turn state machine so it doesn't disturb uiState.
    fun testVoice(sample: String = "Hello, this is Hermes. Voice mode is working.") {
        val client = voiceClient
        val p = player
        if (client == null || p == null) {
            setError("Voice pipeline not initialized")
            return
        }
        viewModelScope.launch {
            val result = client.synthesize(sample)
            if (result.isFailure) {
                setError("Test voice failed: ${result.exceptionOrNull()?.message ?: "unknown"}")
                return@launch
            }
            val file = result.getOrNull() ?: return@launch
            trackTtsFile(file)
            try {
                p.play(file)
                p.awaitCompletion()
            } catch (e: Exception) {
                Log.w(TAG, "test playback failed: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------------
    // Voice turn processing
    // ---------------------------------------------------------------------

    private suspend fun processVoiceInput(audioFile: File) {
        val client = voiceClient
        val chatVm = chatViewModel
        if (client == null || chatVm == null) {
            setError("Voice pipeline not initialized")
            return
        }

        // Transcribe
        _uiState.update { it.copy(state = VoiceState.Transcribing) }
        val transcribeResult = client.transcribe(audioFile)
        if (transcribeResult.isFailure) {
            val err = transcribeResult.exceptionOrNull()
            Log.w(TAG, "transcribe failed: ${err?.message}")
            setError("Transcription failed: ${err?.message ?: "unknown"}")
            return
        }
        val userText = transcribeResult.getOrNull().orEmpty()
        if (userText.isBlank()) {
            setError("No speech detected")
            return
        }

        _uiState.update {
            it.copy(
                state = VoiceState.Thinking,
                transcribedText = userText,
                responseText = "",
            )
        }

        // Reset sentence buffering state for the new turn.
        sentenceBuffer = StringBuilder()
        lastObservedMessageId = null
        lastObservedContentLength = 0

        // Kick off streaming observer BEFORE sending the message so we don't
        // miss early deltas that arrive synchronously from the callback.
        startStreamObserver(chatVm)

        // Route the transcribed text through the normal chat pipeline.
        // This will create a user message + kick off the SSE stream.
        chatVm.sendMessage(userText)
    }

    // ---------------------------------------------------------------------
    // Sentence-boundary streaming from ChatViewModel
    // ---------------------------------------------------------------------

    /**
     * Observe [ChatViewModel.messages]. When the last assistant message
     * grows (isStreaming=true), diff the content against our last snapshot,
     * push the new delta into [sentenceBuffer], and flush completed
     * sentences into [ttsQueue]. On isStreaming=false, flush the remaining
     * buffer and end the turn.
     */
    private fun startStreamObserver(chatVm: ChatViewModel) {
        streamObserverJob?.cancel()
        streamObserverJob = viewModelScope.launch {
            chatVm.messages.collect { messages ->
                val lastAssistant = messages.lastOrNull {
                    it.role == MessageRole.ASSISTANT
                } ?: return@collect

                val msgId = lastAssistant.id
                if (lastObservedMessageId == null) {
                    lastObservedMessageId = msgId
                    lastObservedContentLength = 0
                } else if (lastObservedMessageId != msgId) {
                    // A new assistant turn appeared — flush whatever's left
                    // from the previous one, then switch tracking.
                    flushRemainingBuffer()
                    lastObservedMessageId = msgId
                    lastObservedContentLength = 0
                }

                val content = lastAssistant.content
                if (content.length > lastObservedContentLength) {
                    val delta = content.substring(lastObservedContentLength)
                    lastObservedContentLength = content.length
                    onStreamDelta(delta, content)
                }

                if (!lastAssistant.isStreaming && lastObservedContentLength > 0) {
                    // Stream ended — flush any trailing buffer and move to Speaking.
                    flushRemainingBuffer()
                    // Speaking state will naturally end when TTS queue drains.
                    // We can't easily wait here without blocking the collector;
                    // the TTS consumer transitions back to Idle.
                    streamObserverJob?.cancel()
                }
            }
        }
    }

    /**
     * Handle a single SSE delta chunk: append to the sentence buffer, flush
     * any completed sentences, update the visible responseText.
     */
    private fun onStreamDelta(delta: String, fullContent: String) {
        sentenceBuffer.append(delta)
        _uiState.update { it.copy(state = VoiceState.Speaking, responseText = fullContent) }
        drainSentences()
    }

    private fun drainSentences() {
        while (true) {
            val sentence = extractNextSentence(sentenceBuffer) ?: return
            if (sentence.isBlank()) continue
            val sent = ttsQueue.trySend(sentence)
            if (sent.isFailure) {
                Log.w(TAG, "TTS queue refused sentence: ${sent.exceptionOrNull()?.message}")
                return
            }
        }
    }

    private fun flushRemainingBuffer() {
        val trailing = sentenceBuffer.toString().trim()
        sentenceBuffer = StringBuilder()
        if (trailing.isNotEmpty()) {
            ttsQueue.trySend(trailing)
        }
    }

    // ---------------------------------------------------------------------
    // TTS consumer — one sentence at a time, play then wait
    // ---------------------------------------------------------------------

    private fun startTtsConsumer() {
        ttsConsumerJob?.cancel()
        ttsConsumerJob = viewModelScope.launch {
            for (sentence in ttsQueue) {
                val client = voiceClient ?: continue
                val p = player ?: continue

                val synthesizeResult = client.synthesize(sentence)
                if (synthesizeResult.isFailure) {
                    val err = synthesizeResult.exceptionOrNull()
                    Log.w(TAG, "synthesize failed for sentence: ${err?.message}")
                    setError("TTS failed: ${err?.message ?: "unknown"}")
                    continue
                }
                val file = synthesizeResult.getOrNull() ?: continue

                trackTtsFile(file)

                try {
                    p.play(file)
                    p.awaitCompletion()
                } catch (e: Exception) {
                    Log.w(TAG, "playback failed: ${e.message}")
                }

                // V2b EXTENSION — after each sentence finishes, if nothing
                // else is queued and the streaming observer has torn down
                // (meaning the assistant turn is fully complete), transition
                // back to Idle so the sphere stops pulsing. In Continuous
                // mode, also auto-resume listening.
                maybeAutoResume()
            }
            // Channel closed — nothing more to do.
        }
    }

    // V2b EXTENSION
    //
    // Called after each TTS sentence finishes playing. If the streaming
    // observer has already torn down (the assistant turn is fully complete)
    // and no more sentences are pending, transition Speaking → Idle so the
    // sphere stops pulsing. In Continuous mode, kick off another listen.
    //
    // We can't easily ask the Channel "are you empty?" — instead, use a
    // short yield + re-check of the streamObserverJob state. If the
    // consumer sees another tryReceive succeed in the next loop iteration,
    // it'll transition back to Speaking before the user notices.
    private fun maybeAutoResume() {
        val observerStopped = streamObserverJob?.isActive != true
        if (!observerStopped) return
        if (!_uiState.value.voiceMode) return
        if (_uiState.value.state == VoiceState.Speaking) {
            _uiState.update { it.copy(state = VoiceState.Idle, amplitude = 0f) }
        }
        if (_uiState.value.interactionMode == InteractionMode.Continuous &&
            _uiState.value.state == VoiceState.Idle
        ) {
            startListening()
        }
    }

    private fun trackTtsFile(file: File) {
        ttsFileHistory.addLast(file)
        while (ttsFileHistory.size > TTS_CACHE_CAP) {
            val old = ttsFileHistory.removeFirst()
            try { old.delete() } catch (_: Exception) { /* ignore */ }
        }
    }

    // ---------------------------------------------------------------------
    // Amplitude bridge — forwards recorder/player amplitude into uiState
    // ---------------------------------------------------------------------

    private fun bridgeAmplitudeFlows() {
        amplitudeBridgeJob?.cancel()
        amplitudeBridgeJob = viewModelScope.launch {
            // Recorder amplitude bridge.
            launch {
                recorder?.amplitude?.collect { amp ->
                    if (_uiState.value.state == VoiceState.Listening) {
                        _uiState.update { it.copy(amplitude = amp) }
                    }
                }
            }
            // Player amplitude bridge.
            launch {
                player?.amplitude?.collect { amp ->
                    if (_uiState.value.state == VoiceState.Speaking) {
                        _uiState.update { it.copy(amplitude = amp) }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun setError(msg: String) {
        _uiState.update { it.copy(state = VoiceState.Error, error = msg, amplitude = 0f) }
    }

    override fun onCleared() {
        super.onCleared()
        try { recorder?.cancel() } catch (_: Exception) { /* ignore */ }
        try { player?.stop() } catch (_: Exception) { /* ignore */ }
        ttsQueue.close()
        streamObserverJob?.cancel()
        ttsConsumerJob?.cancel()
        amplitudeBridgeJob?.cancel()
        currentTurnJob?.cancel()
    }

}

// -------------------------------------------------------------------------
// Sentence-boundary detection (top-level so it's unit-testable without
// instantiating an AndroidViewModel + Application context).
// -------------------------------------------------------------------------

/**
 * Minimum characters before we accept a sentence as complete. Prevents
 * tiny fragments like "Mr." or "Hi!" from triggering premature playback.
 */
private const val MIN_SENTENCE_LEN = 6

private val SENTENCE_TERMINATORS = charArrayOf('.', '!', '?', '\n')

/**
 * Pop the next complete sentence off [buffer] (mutating it) and return it.
 * Returns null when no complete sentence is present.
 *
 * A sentence is "complete" when:
 *   - it contains at least [MIN_SENTENCE_LEN] chars, AND
 *   - the last char is a terminator, AND
 *   - the next char (if present) is whitespace OR the buffer ends.
 *
 * The whitespace-lookahead rule avoids splitting on abbreviations like
 * "e.g." or "U.S." where the period is followed by a letter.
 *
 * Exposed as `internal` for unit testing from the test source set.
 */
internal fun extractNextSentence(buffer: StringBuilder): String? {
    var idx = 0
    while (idx < buffer.length) {
        val ch = buffer[idx]
        if (ch in SENTENCE_TERMINATORS) {
            val nextChar = if (idx + 1 < buffer.length) buffer[idx + 1] else null
            val isBoundary = nextChar == null || nextChar.isWhitespace()
            if (isBoundary && idx + 1 >= MIN_SENTENCE_LEN) {
                val endExclusive = idx + 1
                val sentence = buffer.substring(0, endExclusive).trim()
                var consume = endExclusive
                while (consume < buffer.length && buffer[consume].isWhitespace()) {
                    consume++
                }
                buffer.delete(0, consume)
                return sentence
            }
        }
        idx++
    }
    return null
}
