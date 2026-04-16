package com.hermesandroid.relay.viewmodel

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermesandroid.relay.audio.VoicePlayer
import com.hermesandroid.relay.audio.VoiceRecorder
import com.hermesandroid.relay.audio.VoiceSfxPlayer
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.network.ChannelMultiplexer
import com.hermesandroid.relay.network.RelayVoiceClient
import com.hermesandroid.relay.network.handlers.LocalDispatchResult
import com.hermesandroid.relay.util.HumanError
import com.hermesandroid.relay.util.classifyError
// === PHASE3-voice-intents: voice→bridge intent routing ===
import com.hermesandroid.relay.voice.IntentResult
import com.hermesandroid.relay.voice.LocalBridgeDispatcher
import com.hermesandroid.relay.voice.VoiceBridgeIntentHandler
import com.hermesandroid.relay.voice.createVoiceBridgeIntentHandler
// === END PHASE3-voice-intents ===
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    /**
     * Non-null while a destructive voice intent (e.g. Send SMS) is inside the
     * v1 5-second confirmation countdown. Set by [VoiceViewModel] from the
     * sideload handler's `onCountdownStart` callback and cleared when the
     * dispatch completes or the user cancels. Drives the countdown progress
     * indicator in [com.hermesandroid.relay.ui.components.VoiceModeOverlay].
     */
    val destructiveCountdown: DestructiveCountdownState? = null,
)

/**
 * Snapshot of a destructive voice intent waiting on the v1 confirmation
 * countdown. The overlay reads [startedAtMs] + [durationMs] to drive a
 * local progress animation; voice mode shows the human-readable
 * [intentLabel] above the progress bar so the user knows what's about to
 * fire.
 */
data class DestructiveCountdownState(
    /** Short label of the intent, e.g. "Send SMS". */
    val intentLabel: String,
    /** `System.currentTimeMillis()` at the moment the countdown began. */
    val startedAtMs: Long,
    /** Total countdown window in milliseconds (usually 5000). */
    val durationMs: Long,
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

        /**
         * Explicit allow-list of cancel utterances. Intentionally NOT
         * fuzzy — ambiguous words like "yes"/"ok" must NOT terminate a
         * pending SMS by accident. Matching: lowercase, trim, trim
         * trailing `.!?`, then exact OR startsWith against this set.
         *
         * Grow the list cautiously. False negatives (user has to double-
         * tap Cancel on the overlay) are better than false positives
         * (a queued text disappears because the user muttered "no wait,
         * that's right").
         */
        private val CANCEL_PHRASES = setOf(
            "cancel",
            "stop",
            "never mind",
            "nevermind",
            "don't",
            "dont",
            "abort",
            "no",
            "no don't",
            "no dont",
            "forget it",
            "wait",
        )
    }

    // --- Dependencies (injected via initialize) --------------------------

    private var voiceClient: RelayVoiceClient? = null
    private var chatViewModel: ChatViewModel? = null
    private var recorder: VoiceRecorder? = null
    private var player: VoicePlayer? = null
    private var sfxPlayer: VoiceSfxPlayer? = null

    // === PHASE3-voice-intents: voice→bridge intent routing ===
    // Bridge intent handler — the flavor-selected impl, set in initialize().
    // On googlePlay this is the no-op that always returns NotApplicable.
    // On sideload this is the real keyword-classifier + bridge emitter.
    // See `VoiceBridgeIntentHandler` (main/) for the interface contract and
    // cross-flavor compile pattern. No reflection: the factory lives in
    // each flavor source set and Gradle picks the right one at build time.
    private var voiceBridgeIntentHandler: VoiceBridgeIntentHandler? = null
    // === END PHASE3-voice-intents ===

    // --- UI state --------------------------------------------------------

    private val _uiState = MutableStateFlow(VoiceUiState())
    val uiState: StateFlow<VoiceUiState> = _uiState.asStateFlow()

    // One-shot snackbar stream. DROP_OLDEST so a burst of errors during a
    // flaky turn doesn't back-pressure the producer — we only need to show
    // the latest couple to the user anyway.
    private val _errorEvents = MutableSharedFlow<HumanError>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val errorEvents: SharedFlow<HumanError> = _errorEvents.asSharedFlow()

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

    /** Attack/release envelope follower state for the mic amplitude path. */
    private var listenEnvelope: Float = 0f
    /** Attack/release envelope follower state for the TTS playback path. */
    private var speakEnvelope: Float = 0f

    /**
     * Assistant-message-id that already existed BEFORE the current turn's
     * [chatVm.sendMessage] call. The stream observer ignores any emission
     * whose `lastAssistant.id` equals this, so StateFlow's initial replay
     * of the previous turn's response doesn't get spoken as a reply to
     * the current voice input.
     */
    private var ignoreAssistantId: String? = null

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
        sfxPlayer: VoiceSfxPlayer,
        // === PHASE3-voice-intents: voice→bridge intent routing ===
        // Optional so existing call sites keep compiling. On googlePlay both
        // are ignored by the no-op factory; on sideload, [localBridgeDispatcher]
        // is the in-process entry point into BridgeCommandHandler that runs
        // bridge actions through the same dispatch + Tier 5 safety pipeline as
        // WSS-incoming commands but without the WSS round-trip. The
        // [bridgeMultiplexer] is retained for non-bridge envelope use cases
        // and for backwards-compat with the previous wiring.
        bridgeMultiplexer: ChannelMultiplexer? = null,
        localBridgeDispatcher: LocalBridgeDispatcher? = null,
        // === END PHASE3-voice-intents ===
    ) {
        this.voiceClient = voiceClient
        this.chatViewModel = chatViewModel
        this.recorder = recorder
        this.player = player
        this.sfxPlayer = sfxPlayer

        // === PHASE3-voice-intents: voice→bridge intent routing ===
        // Call the flavor-selected factory exactly once. On googlePlay this
        // returns a no-op handler; on sideload it returns the real
        // classifier-backed handler. VoiceViewModel only ever sees the
        // interface — the concrete impl is picked at compile time by the
        // active product flavor. No runtime flavor check, no reflection.
        //
        // The onDispatchResult callback runs on the RealVoiceBridgeIntentHandler's
        // own scope (Dispatchers.Default) after each dispatch completes,
        // so it needs to route back to ChatViewModel safely. We call
        // chatViewModel.recordVoiceIntentResult which is a plain function
        // that updates a StateFlow — safe from any dispatcher.
        voiceBridgeIntentHandler = createVoiceBridgeIntentHandler(
            multiplexer = bridgeMultiplexer,
            localBridgeDispatcher = localBridgeDispatcher,
            onDispatchResult = { label, result ->
                // Chat trace (markdown bubble) — pre-existing.
                chatViewModel.recordVoiceIntentResult(label, result)
                // Spoken follow-up so the user hears the outcome without
                // looking at the screen. Wave-2 voice mode was visually
                // honest but audibly silent after dispatch — Bailey hit
                // this 2026-04-15.
                speakDispatchResult(label, result)
                // Countdown (if any) is over — clear the on-screen progress.
                _uiState.update { it.copy(destructiveCountdown = null) }
            },
            onCountdownStart = { label, durationMs ->
                _uiState.update {
                    it.copy(
                        destructiveCountdown = DestructiveCountdownState(
                            intentLabel = label,
                            startedAtMs = System.currentTimeMillis(),
                            durationMs = durationMs,
                        ),
                    )
                }
            },
        )
        // === END PHASE3-voice-intents ===

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
        // Fire the chime first so the sound lands with the overlay appearing.
        try { sfxPlayer?.playEnter() } catch (_: Exception) { /* ignore */ }
        _uiState.update { it.copy(voiceMode = true, state = VoiceState.Idle, error = null) }
    }

    fun exitVoiceMode() {
        // Chime BEFORE teardown — AudioTrack release would cut it off otherwise.
        try { sfxPlayer?.playExit() } catch (_: Exception) { /* ignore */ }
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

        // Also abort any destructive countdown — leaving voice mode with a
        // queued SMS would execute the action after the overlay closed,
        // which is exactly the surprise we're trying to prevent.
        voiceBridgeIntentHandler?.cancelPending()
        _uiState.update {
            it.copy(
                voiceMode = false,
                state = VoiceState.Idle,
                amplitude = 0f,
                transcribedText = null,
                responseText = "",
                error = null,
                destructiveCountdown = null,
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
            // SecurityException path becomes "Permission needed" via the classifier.
            surfaceError(e, context = "record")
        }
    }

    fun stopListening() {
        val rec = recorder ?: return
        if (_uiState.value.state != VoiceState.Listening) return

        val file = try {
            rec.stopRecording()
        } catch (e: Exception) {
            Log.e(TAG, "stopListening failed: ${e.message}")
            surfaceError(e, context = "record")
            return
        }

        currentTurnJob?.cancel()
        currentTurnJob = viewModelScope.launch {
            processVoiceInput(file)
        }
    }

    /**
     * Hard-stop everything in the current voice turn and return to Idle.
     *
     * The old version only drained the queue and paused playback — the
     * upstream SSE stream kept generating and the observer kept pushing
     * fresh deltas, so playback resumed on the next sentence. We now
     * cancel the stream, tear down the observer + turn job, reset all
     * per-turn state, and go back to Idle (not Listening — Bailey's
     * mental model is "stop" = ready to start a new turn on mic tap).
     */
    fun interruptSpeaking() {
        // Drain queued sentences without closing the channel.
        while (true) {
            val r = ttsQueue.tryReceive()
            if (r.isFailure || r.isClosed) break
        }
        try { player?.stop() } catch (_: Exception) { /* ignore */ }
        // Stop the upstream SSE stream so the agent quits generating tokens.
        try { chatViewModel?.cancelStream() } catch (_: Exception) { /* ignore */ }
        streamObserverJob?.cancel()
        streamObserverJob = null
        currentTurnJob?.cancel()
        currentTurnJob = null
        // Reset per-turn buffering + tracking so the next turn starts clean.
        sentenceBuffer = StringBuilder()
        lastObservedMessageId = null
        lastObservedContentLength = 0
        speakEnvelope = 0f
        // Deliberately do NOT clear responseText here. "Stop" should freeze
        // the visible response so the user can read whatever was already
        // said before they hit stop — Bailey hit this and pointed out the
        // old behavior felt like the screen evaporated under his hand. The
        // chat history was always preserved server-side; the bug was the
        // voice overlay's local copy getting blanked. The next [startListening]
        // resets responseText at the moment the user explicitly starts a
        // new turn, so old text only sticks around until they choose to
        // move on.
        _uiState.update {
            it.copy(state = VoiceState.Idle, amplitude = 0f)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    // === PHASE3-voice-intents: voice→bridge intent routing ===
    /**
     * Cancel any voice-originated bridge action that is currently inside
     * its v1 confirmation countdown window. Called from the voice overlay's
     * Cancel button. No-op on googlePlay (the no-op handler has nothing to
     * cancel) and no-op on sideload when nothing is pending.
     *
     * See [VoiceBridgeIntentHandler] for the v1 confirmation model and
     * why full conversational cancellation ("say cancel") is a Wave 3
     * follow-up.
     */
    fun cancelPendingBridgeIntent() {
        voiceBridgeIntentHandler?.cancelPending()
        // Clear the countdown indicator regardless of state — if the user
        // tapped Cancel while the countdown was still inside Thinking (the
        // classifier just returned Handled and the preview hasn't finished
        // speaking yet) we still need to pull the progress bar down.
        _uiState.update { it.copy(destructiveCountdown = null) }
        if (_uiState.value.state == VoiceState.Speaking) {
            _uiState.update { it.copy(state = VoiceState.Idle, responseText = "Cancelled.") }
        }
    }
    // === END PHASE3-voice-intents ===

    // V2b EXTENSION --------------------------------------------------------
    // Fire a one-shot TTS synth+playback to verify the voice pipeline from
    // the settings screen without entering full voice mode. Runs outside
    // the turn state machine so it doesn't disturb uiState.
    //
    // Three toasts so the user knows what's happening: "Testing voice…" on
    // trigger, "Voice test successful" on completion, "Voice test failed" on
    // any error. The trigger toast is held in [triggerToast] so it can be
    // cancelled the moment the result toast fires — without that the two
    // would briefly overlap on screen. viewModelScope.launch defaults to
    // Main.immediate so Toast.show() is safe inline without a dispatcher
    // switch.
    fun testVoice(sample: String = "Hello, this is Hermes. Voice mode is working.") {
        val app = getApplication<Application>()
        val client = voiceClient
        val p = player
        if (client == null || p == null) {
            Toast.makeText(app, "Voice test failed: pipeline not initialized", Toast.LENGTH_SHORT).show()
            setError("Voice pipeline not initialized")
            return
        }
        val triggerToast = Toast.makeText(app, "Testing voice…", Toast.LENGTH_SHORT).also { it.show() }
        viewModelScope.launch {
            val result = client.synthesize(sample)
            if (result.isFailure) {
                triggerToast.cancel()
                val msg = result.exceptionOrNull()?.message ?: "synthesize failed"
                Toast.makeText(app, "Voice test failed: $msg", Toast.LENGTH_LONG).show()
                surfaceError(result.exceptionOrNull(), context = "synthesize")
                return@launch
            }
            val file = result.getOrNull()
            if (file == null) {
                triggerToast.cancel()
                Toast.makeText(app, "Voice test failed: no audio returned", Toast.LENGTH_LONG).show()
                return@launch
            }
            trackTtsFile(file)
            try {
                p.play(file)
                p.awaitCompletion()
                triggerToast.cancel()
                Toast.makeText(app, "Voice test successful", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.w(TAG, "test playback failed: ${e.message}")
                triggerToast.cancel()
                Toast.makeText(app, "Voice test failed: ${e.message ?: "playback error"}", Toast.LENGTH_LONG).show()
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
            surfaceError(err, context = "transcribe")
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

        // === PHASE3-voice-intents: voice→bridge intent routing ===
        // Before routing to chat, ask the flavor-specific intent handler
        // whether this is a phone-control utterance ("text Sam I'll be
        // late", "open camera", "scroll down"). On googlePlay the handler
        // is a no-op and always returns NotApplicable → we fall straight
        // through to chat, behaviour unchanged. On sideload a match fires
        // a bridge envelope (with a 5 s cancel window for destructive
        // actions) and we do NOT send the text to chat — the user's
        // utterance was a command, not a question.
        //
        // Defense-in-depth note: the cross-flavor split at the FACTORY
        // level is the real gate. The googlePlay factory returns a no-op
        // that literally never references any bridge or accessibility
        // class, so the Play APK stays clean even if this call site is
        // ever mis-edited. If/when a `BuildFlavor.bridgeTier3` compile-
        // time constant exists we should still short-circuit here for
        // clarity, but today the factory already does the right thing.
        val bridgeHandler = voiceBridgeIntentHandler

        // === PHASE3-voice-cancel-midcountdown ===
        // Voice-in-voice cancel: if a destructive action is currently
        // waiting on its 5 s confirmation window and the user just said
        // one of the cancel phrases, intercept BEFORE the classifier.
        // Without this, "cancel" falls through to the SMS classifier
        // (no match), then chat (LLM fall-through) and the queued SMS
        // fires anyway. We intentionally use an explicit allow-list
        // instead of fuzzy matching — "yes"/"ok" during the countdown
        // should NOT cancel, they should fall through to normal routing.
        if (bridgeHandler != null && bridgeHandler.hasPendingDestructive() &&
            isCancelUtterance(userText)
        ) {
            Log.i(TAG, "cancel-mid-countdown: intercepted '$userText'")
            bridgeHandler.cancelPending()
            // Speak a short "Cancelled." and return to idle. Reuse the
            // same sentence-buffer path as speakDispatchResult so the
            // existing TTS queue picks it up in order.
            sentenceBuffer = StringBuilder("Cancelled.")
            _uiState.update {
                it.copy(
                    state = VoiceState.Speaking,
                    responseText = "Cancelled.",
                    destructiveCountdown = null,
                )
            }
            flushRemainingBuffer()
            return
        }
        // === END PHASE3-voice-cancel-midcountdown ===

        if (bridgeHandler != null) {
            try {
                val result = bridgeHandler.tryHandle(userText)
                if (result is IntentResult.Handled) {
                    Log.i(TAG, "voice intent handled by bridge: ${result.intentLabel}")

                    // === PHASE3-voice-intents-chathistory ===
                    // Append a local-only trace to chat history so the user
                    // sees a record of what they said and what was done. Pre-
                    // v0.4.0 the voice intent path returned silently here and
                    // the chat scroll had zero record of voice utterances —
                    // making follow-up questions feel like the chat had
                    // "reset". The trace is local-only (does NOT reach the
                    // server-side session) so the gateway-side LLM still won't
                    // see prior voice actions in its session memory; that's a
                    // v0.4.1 follow-up tracked in ROADMAP.md.
                    val actionDescription = formatVoiceIntentTrace(result)
                    chatVm.recordVoiceIntent(
                        userText = userText,
                        actionDescription = actionDescription,
                    )
                    // === END PHASE3-voice-intents-chathistory ===

                    // Speak the confirmation if one was provided, otherwise
                    // just flip state so the UI shows the recognized intent
                    // and the turn ends without spinning up chat SSE.
                    val confirmation = result.spokenConfirmation
                    if (confirmation != null) {
                        sentenceBuffer = StringBuilder(confirmation)
                        _uiState.update {
                            it.copy(state = VoiceState.Speaking, responseText = confirmation)
                        }
                        flushRemainingBuffer()
                    } else {
                        _uiState.update {
                            it.copy(
                                state = VoiceState.Idle,
                                responseText = "${result.intentLabel}: $userText",
                            )
                        }
                    }
                    return
                }
            } catch (e: Exception) {
                // Classifier/bridge dispatch failed. Degrade gracefully to
                // chat — the user said something, we'd rather answer it as
                // a message than swallow the turn.
                Log.w(TAG, "voiceBridgeIntentHandler.tryHandle failed: ${e.message}")
            }
        }
        // === END PHASE3-voice-intents ===

        // === PHASE3-voice-intents-fallback-visibility ===
        // Classifier returned NotApplicable — we're about to fall through
        // to chatVm.sendMessage(). The SSE stream takes 500–1500 ms to
        // open and start emitting deltas, during which the voice UI would
        // sit in Idle showing nothing new. Users interpret that as "the
        // utterance was dropped." Flip to Thinking immediately so the
        // empty-state hint shows "Thinking..." until tokens arrive and
        // the stream observer takes over. Also pin the transcribed text
        // so the user can see what we heard while we wait.
        _uiState.update {
            it.copy(
                state = VoiceState.Thinking,
                transcribedText = userText,
                responseText = "",
            )
        }
        // === END PHASE3-voice-intents-fallback-visibility ===

        // Reset sentence buffering state for the new turn.
        sentenceBuffer = StringBuilder()
        lastObservedMessageId = null
        lastObservedContentLength = 0

        // Capture the id of the assistant message that currently sits at
        // the end of history. StateFlow.collect replays the current value
        // to new subscribers, so without this guard the observer would
        // treat the previous turn's full response as one giant delta for
        // the new turn and TTS the wrong answer.
        ignoreAssistantId = chatVm.messages.value
            .lastOrNull { it.role == MessageRole.ASSISTANT }?.id

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

                // Skip the assistant message that existed BEFORE the current
                // turn's sendMessage. Without this, StateFlow's replay of the
                // current list (containing the PREVIOUS turn's response) gets
                // treated as a delta and the agent voices the old answer.
                if (lastAssistant.id == ignoreAssistantId) return@collect

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
            while (true) {
                // Non-blocking peek: is a sentence already waiting?
                val immediate = ttsQueue.tryReceive()
                val sentence: String

                if (immediate.isSuccess) {
                    sentence = immediate.getOrNull() ?: continue
                } else if (immediate.isClosed) {
                    break
                } else {
                    // Queue is momentarily empty. If the SSE stream observer
                    // is also done, this is the true end of the turn — safe
                    // to leave Speaking. The old code called maybeAutoResume
                    // after EVERY sentence, which prematurely killed the
                    // waveform between sentences of a multi-sentence response
                    // because the SSE stream finishes long before TTS finishes
                    // playing all the queued audio.
                    maybeAutoResume()

                    // Block until the next sentence arrives (or channel closes).
                    // If maybeAutoResume just went to Idle, we park here until
                    // the next voice turn pushes a new sentence.
                    sentence = ttsQueue.receiveCatching().getOrNull() ?: break
                }

                // About to synthesize + play — ensure state is Speaking so
                // the amplitude bridge forwards player output to the UI.
                // Between sentences within the same turn the queue usually
                // yields immediately (no maybeAutoResume call), but if the
                // channel was briefly empty before the observer pushed the
                // next sentence, state may have transiently flipped to Idle.
                if (_uiState.value.voiceMode && _uiState.value.state != VoiceState.Speaking) {
                    _uiState.update { it.copy(state = VoiceState.Speaking) }
                }

                val client = voiceClient ?: continue
                val p = player ?: continue

                val synthesizeResult = client.synthesize(sentence)
                if (synthesizeResult.isFailure) {
                    val err = synthesizeResult.exceptionOrNull()
                    Log.w(TAG, "synthesize failed for sentence: ${err?.message}")
                    surfaceError(err, context = "synthesize")
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
            }
        }
    }

    // Called when the TTS queue is empty (tryReceive returned failure) and
    // the streaming observer has already torn down (the assistant turn's SSE
    // is fully consumed). Transitions Speaking → Idle so the sphere stops
    // pulsing. In Continuous mode, also kicks off a new recording turn.
    //
    // Key invariant: this ONLY fires when the queue is actually drained,
    // not after every sentence. Between sentences of a multi-sentence
    // response the queue still has entries — the consumer peeks via
    // tryReceive and skips this entirely, so the amplitude bridge stays
    // active and the waveform keeps rendering while audio plays.
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
            // Recorder amplitude bridge — runs an attack-fast / release-slow
            // envelope follower so the UI sees instant response on speech
            // peaks and a smooth ~350ms fade into silence. No Compose spring
            // downstream.
            launch {
                recorder?.amplitude?.collect { amp ->
                    if (_uiState.value.state == VoiceState.Listening) {
                        listenEnvelope = applyEnvelope(listenEnvelope, amp)
                        _uiState.update { it.copy(amplitude = listenEnvelope) }
                    } else if (listenEnvelope != 0f) {
                        listenEnvelope = 0f
                    }
                }
            }
            // Player amplitude bridge.
            launch {
                player?.amplitude?.collect { amp ->
                    if (_uiState.value.state == VoiceState.Speaking) {
                        speakEnvelope = applyEnvelope(speakEnvelope, amp)
                        _uiState.update { it.copy(amplitude = speakEnvelope) }
                    } else if (speakEnvelope != 0f) {
                        speakEnvelope = 0f
                    }
                }
            }
        }
    }

    /**
     * Attack-fast / release-slow envelope follower. Called per amplitude
     * sample (~60 Hz). At these coefficients the envelope reaches 90 % of a
     * new peak in roughly 30 ms, and fades 90 % back to silence over about
     * 350 ms — the same shape that makes Siri / WhatsApp voice meters feel
     * "alive": spike instantly on a consonant, trail naturally on the tail.
     */
    private fun applyEnvelope(current: Float, target: Float): Float {
        val t = sanitizeAmplitude(target)
        val attack = 0.75f
        val release = 0.10f
        val next = if (t > current) {
            current + (t - current) * attack
        } else {
            current + (t - current) * release
        }
        return next.coerceIn(0f, 1f)
    }

    /**
     * Clamp amplitude to [0f, 1f] and filter out NaN / Infinity. Critical
     * because both VoiceRecorder and VoicePlayer can produce NaN under edge
     * conditions (empty PCM frame from the Visualizer callback, or an
     * `maxAmplitude` read between stop and teardown), and NaN propagates
     * through `Float.coerceIn` silently — `NaN < 0f` is false, so the
     * branch returns `this` unchanged. Without this guard, a single NaN
     * frame would contaminate `animateFloatAsState` targets in the
     * waveform / sphere / mic button, which in turn feeds NaN to Compose's
     * Android 15 variable-refresh-rate frame-rate hint and produces a
     * `setRequestedFrameRate frameRate=NaN` log on every draw pass.
     */
    private fun sanitizeAmplitude(amp: Float): Float =
        if (amp.isNaN() || amp.isInfinite()) 0f else amp.coerceIn(0f, 1f)

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun setError(msg: String) {
        _uiState.update { it.copy(state = VoiceState.Error, error = msg, amplitude = 0f) }
    }

    /**
     * Classify an exception and surface it both as the in-overlay banner
     * (uiState.error) and as a one-shot snackbar event (errorEvents). Callers
     * pass a context string so generic exceptions still get a meaningful
     * title — see RelayErrorClassifier for the supported contexts.
     */
    private fun surfaceError(t: Throwable?, context: String?) {
        val err = classifyError(t, context = context)
        _errorEvents.tryEmit(err)
        _uiState.update {
            it.copy(state = VoiceState.Error, error = err.body, amplitude = 0f)
        }
    }

    override fun onCleared() {
        super.onCleared()
        try { recorder?.cancel() } catch (_: Exception) { /* ignore */ }
        try { player?.stop() } catch (_: Exception) { /* ignore */ }
        try { sfxPlayer?.release() } catch (_: Exception) { /* ignore */ }
        ttsQueue.close()
        streamObserverJob?.cancel()
        ttsConsumerJob?.cancel()
        amplitudeBridgeJob?.cancel()
        currentTurnJob?.cancel()
    }

    /**
     * Speak a short follow-up line summarizing the real outcome of a voice
     * intent dispatch. Fires from the `onDispatchResult` callback after the
     * 5 s countdown + safety modal resolve, so the user hears "Text sent."
     * or "Cancelled." without needing to look at the screen. Categories
     * mirror [com.hermesandroid.relay.viewmodel.ChatViewModel.formatVoiceIntentResult]
     * so the spoken and visual traces agree.
     *
     * Spoken strings are kept **short** on purpose — TTS feels slow when it
     * reads full sentences, and voice mode is already a low-latency surface.
     * The message funnels through the existing sentence-buffer → [ttsQueue]
     * pipeline so it plays back-to-back with any in-flight synthesized
     * audio instead of racing the player.
     */
    private fun speakDispatchResult(label: String, result: LocalDispatchResult) {
        val spoken = buildDispatchResultSpoken(label, result)
        if (spoken.isBlank()) return
        // Use the same sentence-buffer / flush path the classifier uses for
        // the pre-dispatch preview. This keeps the queue ordered and reuses
        // the visible responseText Speaking state update.
        sentenceBuffer = StringBuilder(spoken)
        _uiState.update {
            it.copy(state = VoiceState.Speaking, responseText = spoken)
        }
        flushRemainingBuffer()
    }

    /**
     * Map a [LocalDispatchResult] into a short spoken sentence. Public-ish
     * (internal) so unit tests in the test source set can assert the
     * category mapping without having to spin up a full VoiceViewModel.
     *
     * Any markdown syntax is stripped — TTS should not read `**` aloud.
     */
    internal fun buildDispatchResultSpoken(
        label: String,
        result: LocalDispatchResult,
    ): String {
        val base = when {
            result.isSuccess -> when (label) {
                "Send SMS" -> "Text sent."
                "Open App" -> "App opened."
                "Tap" -> "Tapped."
                "Navigate back" -> "Done."
                "Home" -> "Done."
                else -> "Done."
            }
            result.errorCode == "user_denied" -> "Cancelled."
            result.errorCode == "bridge_disabled" ->
                "Agent control is off. Enable it in the Bridge tab to retry."
            result.errorCode == "permission_denied" -> {
                val hint = firstClause(result.errorMessage)
                if (hint.isNullOrBlank()) "Permission needed."
                else "Permission needed. $hint"
            }
            result.errorCode == "service_unavailable" -> "Bridge is offline."
            result.errorCode == "cancelled" -> "Cancelled before dispatch."
            else -> {
                val hint = firstClause(result.errorMessage)
                if (hint.isNullOrBlank()) "Action failed."
                else "Action failed. $hint"
            }
        }
        return stripMarkdown(base)
    }

    /**
     * Return the first clause of [raw] (up to the first `.`, `!`, `?`, or
     * newline) so spoken errors stay terse. Returns null for null input or
     * a blank result.
     */
    private fun firstClause(raw: String?): String? {
        if (raw == null) return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val idx = trimmed.indexOfAny(charArrayOf('.', '!', '?', '\n'))
        val clause = if (idx < 0) trimmed else trimmed.substring(0, idx)
        return clause.trim().ifBlank { null }
    }

    /**
     * Test whether [rawText] is an explicit cancel utterance — one of the
     * vocabulary entries in [CANCEL_PHRASES], matched case-insensitively
     * after trimming trailing punctuation. Exact match OR a startsWith
     * against any phrase wins ("cancel please" still cancels; "cancel
     * your plans for dinner" also cancels — that's fine, the user said
     * cancel). Ambiguous words like "yes"/"ok" do not appear in the list
     * on purpose so those fall through to normal routing.
     *
     * Exposed as `internal` so unit tests can assert the vocabulary
     * without going through the full VoiceViewModel dispatch path.
     */
    internal fun isCancelUtterance(rawText: String): Boolean {
        val normalized = rawText.lowercase().trim().trimEnd('.', '!', '?')
        if (normalized.isEmpty()) return false
        if (normalized in CANCEL_PHRASES) return true
        return CANCEL_PHRASES.any { phrase ->
            normalized == phrase || normalized.startsWith("$phrase ")
        }
    }

    /**
     * Strip the subset of markdown we actually emit in voice traces —
     * bold (`**text**`), inline code (`` `text` ``), and stray backticks.
     * TTS engines read `**` and `` ` `` literally, which derails the
     * spoken follow-up.
     */
    private fun stripMarkdown(text: String): String =
        text.replace("**", "")
            .replace("`", "")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Render a voice-intent trace for the chat scroll. Uses the structured
     * [IntentResult.Handled.details] map to produce a richer message than the
     * pre-v0.4.0 formatter, which could only say "Open App — done" and left
     * the user wondering *which* app actually launched (see the 2026-04-15
     * "open Chrome → opens Google app" report).
     *
     * Output is markdown so the existing chat bubble renderer picks up bold
     * labels and inline `code` for package names. Format differs per intent:
     *
     *  - `Open App` success → **Opened Chrome**\n`com.android.chrome` — exact match
     *  - `Open App` failure → **Open App** — I couldn't find an app called 'foo'
     *  - `Send SMS` awaiting confirmation → **Send SMS — awaiting confirmation**
     *    \nTo: Hannah (+1555...)\nBody: smoke test
     *  - Safe intents without details → bare label
     */
    private fun formatVoiceIntentTrace(result: IntentResult.Handled): String {
        val d = result.details
        val errorCode = d["error"]
        return when (result.intentLabel) {
            "Open App" -> {
                val label = d["appLabel"]
                val pkg = d["packageName"]
                val tier = d["matchTier"]
                val requested = d["requestedName"]
                when {
                    label != null && pkg != null -> buildString {
                        append("**Opened ")
                        append(label)
                        append("**")
                        append('\n')
                        append('`')
                        append(pkg)
                        append('`')
                        if (tier != null) {
                            append(" — ")
                            append(tier)
                            append(" match")
                        }
                    }
                    errorCode == "app_not_found" -> buildString {
                        append("**Open App**")
                        append('\n')
                        append("Couldn't find an app called '")
                        append(requested ?: "?")
                        append("'.")
                    }
                    errorCode == "service_missing" -> buildString {
                        append("**Open App — bridge offline**")
                        append('\n')
                        append("Enable Hermes accessibility in Settings to open apps by voice.")
                    }
                    errorCode == "other_error" -> buildString {
                        append("**Open App — error**")
                        append('\n')
                        append(d["errorMessage"] ?: "unknown error")
                    }
                    else -> "**Open App** — done"
                }
            }
            "Send SMS" -> {
                val contact = d["contact"]
                val number = d["resolvedNumber"]
                val body = d["body"]
                when {
                    number != null -> buildString {
                        append("**Send SMS — awaiting confirmation**")
                        append('\n')
                        append("To: ")
                        append(contact ?: "?")
                        append(" (")
                        append(number)
                        append(')')
                        if (body != null) {
                            append('\n')
                            append("Body: ")
                            append(body)
                        }
                    }
                    errorCode == "permission_missing_sms" -> buildString {
                        append("**Send SMS — permission needed**")
                        append('\n')
                        append("Grant SMS permission in Settings › Apps › Hermes Relay › Permissions.")
                    }
                    errorCode == "permission_missing_contacts" -> buildString {
                        append("**Send SMS — permission needed**")
                        append('\n')
                        append("Grant Contacts permission to look up '")
                        append(contact ?: "?")
                        append("' in Settings › Apps › Hermes Relay › Permissions.")
                    }
                    errorCode == "service_missing" -> buildString {
                        append("**Send SMS — bridge offline**")
                        append('\n')
                        append("Enable Hermes accessibility in Settings first.")
                    }
                    errorCode == "contact_not_found" -> buildString {
                        append("**Send SMS**")
                        append('\n')
                        append("Couldn't find a contact called '")
                        append(contact ?: "?")
                        append("'.")
                    }
                    errorCode == "contact_no_phone" -> buildString {
                        append("**Send SMS**")
                        append('\n')
                        append(contact ?: "Contact")
                        append(" has no phone number on file.")
                    }
                    errorCode == "other_error" -> buildString {
                        append("**Send SMS — error**")
                        append('\n')
                        append(d["errorMessage"] ?: "unknown error")
                    }
                    else -> "**Send SMS** — dispatched"
                }
            }
            else -> buildString {
                append("**")
                append(result.intentLabel)
                append("**")
                if (result.spokenConfirmation != null) {
                    append(" — ")
                    append(result.spokenConfirmation)
                } else {
                    append(" — done")
                }
            }
        }
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
            val isBoundary = ch == '\n' || nextChar == null || nextChar.isWhitespace()
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
