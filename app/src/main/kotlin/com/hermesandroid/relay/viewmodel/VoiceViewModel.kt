package com.hermesandroid.relay.viewmodel

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermesandroid.relay.audio.BargeInListener
import com.hermesandroid.relay.audio.VadEngine
import com.hermesandroid.relay.audio.VoicePlayer
import com.hermesandroid.relay.audio.VoiceRecorder
import com.hermesandroid.relay.audio.VoiceSfxPlayer
import com.hermesandroid.relay.data.BargeInPreferences
import com.hermesandroid.relay.data.BargeInPreferencesRepository
import com.hermesandroid.relay.data.BargeInSensitivity
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.data.VoiceIntentTrace
import com.hermesandroid.relay.network.ChannelMultiplexer
import com.hermesandroid.relay.network.RelayVoiceClient
import com.hermesandroid.relay.network.handlers.LocalDispatchResult
import com.hermesandroid.relay.util.HumanError
import com.hermesandroid.relay.util.classifyError
import com.hermesandroid.relay.voice.VoiceIntentSyncBuilder
// === PHASE3-voice-intents: voice→bridge intent routing ===
import com.hermesandroid.relay.voice.IntentResult
import com.hermesandroid.relay.voice.LocalBridgeDispatcher
import com.hermesandroid.relay.voice.VoiceBridgeIntentHandler
import com.hermesandroid.relay.voice.createVoiceBridgeIntentHandler
// === END PHASE3-voice-intents ===
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.Collections

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
    /**
     * v0.4.1 JIT permission-denied chip. Non-null when the most recent voice
     * intent dispatch returned `errorCode == "permission_denied"`. Tap-target
     * deep-links to `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` for our
     * package so the user can grant the missing permission without leaving
     * voice mode by hand. Cleared when the user opens the chip OR enters a
     * fresh turn (mic tap), whichever comes first.
     */
    val permissionDeniedCallout: PermissionDeniedCallout? = null,
)

/**
 * Snapshot of a "we need a permission" hint surfaced after a voice intent
 * fails with `error_code == "permission_denied"`. The overlay reads this
 * to render a tappable chip that deep-links to the app's permission page.
 *
 * The [permission] field is the Android permission name (e.g.
 * `android.permission.READ_CONTACTS`); [intentLabel] is the human-readable
 * action label ("Send SMS", "Search contacts"); [hint] is a short
 * imperative copy line shown in the chip body.
 */
data class PermissionDeniedCallout(
    /** Android permission constant string. */
    val permission: String,
    /** Short action label, e.g. "Send SMS". */
    val intentLabel: String,
    /** Short imperative copy, e.g. "I need Contacts to look up Sam — tap to open Settings." */
    val hint: String,
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
         * Idle interval after which the chunker will force-flush whatever
         * remains in [sentenceBuffer]. Covers the case where the SSE stream
         * pauses mid-response without emitting a terminator — without a
         * timer flush, the pending text would never reach TTS.
         *
         * Value picked to be long enough to outlast normal inter-token
         * pauses in a healthy SSE stream (~50–200 ms) but short enough that
         * the user doesn't perceive a stall.
         */
        private const val TIMER_FLUSH_MS = 800L

        /**
         * Duck watchdog window (B4). If `maybeSpeech` ducks the TTS but no
         * `bargeInDetected` fires within this many ms, the single VAD
         * positive was almost certainly a false-positive and we restore
         * full volume so the agent continues audibly.
         */
        internal const val DUCK_WATCHDOG_MS = 500L

        /**
         * Resume watchdog window (B4). After a hard barge-in interrupt, the
         * VoiceViewModel listens for user-speech silence for this many ms
         * before deciding the interrupt was a brief cough / false-positive
         * that didn't lead into an actual utterance. On silence + resume-
         * enabled, we re-enqueue the un-played chunks.
         */
        internal const val RESUME_WATCHDOG_MS = 600L

        /**
         * Amplitude threshold (0f..1f) below which the post-barge-in window
         * is treated as silence. [VoiceRecorder.amplitude] is already
         * perceptually-scaled; empirically a value of 0.08 excludes mic
         * hiss + room tone on Bailey's devices while still catching a
         * whispered continuation. Tune from telemetry if this bites.
         */
        internal const val RESUME_SILENCE_THRESHOLD: Float = 0.08f

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

    /**
     * Raw (unsanitized) deltas held back while a markdown code fence is
     * open across multiple stream chunks. See [appendSanitizedDelta] for
     * the full rationale. Accumulates until the next closing ``` arrives
     * (making the fence-count even) or until the stream ends and we're
     * forced to flush whatever is there. Empty in the common case where
     * a delta contains no fences.
     */
    private var pendingRawDelta: StringBuilder = StringBuilder()

    private var streamObserverJob: Job? = null
    private var ttsConsumerJob: Job? = null
    private var amplitudeBridgeJob: Job? = null
    private var currentTurnJob: Job? = null

    /**
     * Debounced idle-flush job — re-armed on every incoming delta.
     * If no delta arrives within [TIMER_FLUSH_MS] the job force-flushes
     * whatever is in [sentenceBuffer] to the TTS queue so pending text
     * doesn't sit forever on a stalled stream. Cancelled on turn reset,
     * voice-mode exit, and stream-complete.
     */
    private var idleFlushJob: Job? = null

    /**
     * Set true by [startStreamObserver] when the current assistant message's
     * `isStreaming` flag flips to false. Read by [extractNextSentence] (via
     * [drainSentences]) so the chunker can emit a trailing short sentence
     * instead of starving the queue on a final "Okay.". Reset to false at
     * the start of every turn and on voice-mode exit.
     *
     * `@Volatile` because the stream observer runs on [viewModelScope]
     * (Main dispatcher) and the TTS consumer runs on [viewModelScope]
     * (also Main), but drainSentences may be invoked off the observer
     * callback path in the future — cheap insurance.
     */
    @Volatile
    private var streamComplete: Boolean = false

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

    /**
     * V4 prefetch-pipeline tracker (voice-quality-pass 2026-04-16).
     *
     * Files that have been written to disk by the synth worker but not yet
     * handed to [VoicePlayer.play]. On a cancellation path
     * ([stopVoice]/[exitVoiceMode]/[interruptSpeaking]) these files have
     * never reached ExoPlayer's own queue — `player.stop()` can't clean
     * them up, so the synth worker is the only component that knows they
     * exist. We delete them explicitly on teardown to avoid leaking
     * `voice_tts_<ts>.mp3` entries in `cacheDir`.
     *
     * Synchronized via a [Collections.synchronizedSet] wrapper rather than
     * a [Mutex] because the only operations are O(1) add/remove/iterate-
     * snapshot from the pipeline coroutines (all on viewModelScope's Main
     * dispatcher today, but the synchronized wrapper is cheap insurance
     * against a future dispatcher switch).
     */
    private val pendingTtsFiles: MutableSet<File> =
        Collections.synchronizedSet(mutableSetOf())

    // ---------------------------------------------------------------------
    // B4 barge-in state (voice-barge-in 2026-04-17)
    // ---------------------------------------------------------------------
    //
    // BargeInListener is created lazily on the transition into Speaking when
    // the user has barge-in enabled + a non-Off sensitivity, then torn down
    // on Speaking-exit. The lifecycle is "per Speaking turn" — one listener
    // per response the agent gives, not one for the lifetime of voice mode.
    //
    // The listener owns an AudioRecord under the hood; bracketing it around
    // Speaking keeps the mic permission footprint tight, avoids contesting
    // with the VoiceRecorder during Listening, and means "barge-in = off"
    // genuinely means no mic is ever opened during Speaking.
    //
    // [bargeInPreferences] is initialized from [initialize] and mirrors the
    // datastore value via [viewModelScope]. Null before initialize — which
    // matches the rest of the collaborator pattern. Reads during Speaking
    // transitions are against the latest StateFlow value, not the store.

    private var bargeInPreferences: BargeInPreferencesRepository? = null
    private var vadEngineFactory: (() -> VadEngine)? = null
    private var bargeInListenerFactory: ((VadEngine, () -> Int) -> BargeInListener)? = null

    /**
     * Current barge-in settings snapshot. Updated reactively from the
     * repository flow; read synchronously on the Speaking-entry path.
     */
    private val _bargeInPrefs = MutableStateFlow(BargeInPreferences())

    /**
     * The active listener for the current Speaking turn. Null outside of
     * Speaking or when barge-in is off.
     */
    private var bargeInListener: BargeInListener? = null
    private var bargeInListenerJob: Job? = null
    private var bargeInVadEngine: VadEngine? = null

    /**
     * The ordered list of sentence chunks the synth worker has seen during
     * the current Speaking turn. Appended inside `runSynthWorker`'s
     * synthesize callback (additive, no contract change to V4) so the
     * barge-in resume path can re-enqueue un-played chunks.
     *
     * Cleared via [clearSpokenChunksState] at turn boundaries — new user
     * input, successful drain, or exit from voice mode.
     *
     * Synchronized via the same `Collections.synchronizedList` discipline
     * as [pendingTtsFiles]. Access is cheap (append + read-at-index) and
     * concurrent reads are allowed.
     */
    private val spokenChunks: MutableList<String> =
        Collections.synchronizedList(mutableListOf())

    /**
     * Monotonically-incrementing index of the chunk currently handed to
     * [VoicePlayer.play]. `-1` means no chunk has started this turn; the
     * play worker increments it each time [runTtsPlayWorker]'s `onFileReady`
     * fires (which is also the Speaking-state transition point).
     *
     * Exposed as a read-only [StateFlow] for observability; barge-in reads
     * the latest value synchronously when the VAD fires.
     */
    private val _currentPlayingChunkIndex = MutableStateFlow(-1)
    val currentPlayingChunkIndex: StateFlow<Int> = _currentPlayingChunkIndex.asStateFlow()

    /**
     * Chunk index that was playing at the moment of the last barge-in
     * interruption. Null outside an active interruption window. Used by the
     * resume-watchdog to slice [spokenChunks] at `lastInterruptedAtChunkIndex + 1`
     * and re-enqueue the tail.
     */
    private var lastInterruptedAtChunkIndex: Int? = null

    /** True while TTS volume is ducked from a `maybeSpeech` event. */
    @Volatile private var isDucked: Boolean = false

    /**
     * Timer that un-ducks 500 ms after [onMaybeSpeech] if no
     * `bargeInDetected` follows (single-frame VAD false-positive). Cancelled
     * and restarted on every [onMaybeSpeech]; cancelled outright by
     * [onBargeInDetected] which hands off to the hard-interrupt path.
     */
    private var duckingWatchdog: Job? = null

    /**
     * Timer that polls the VoiceRecorder amplitude for 600 ms after a hard
     * barge-in interrupt. If peak stays below [RESUME_SILENCE_THRESHOLD],
     * we treat it as "user didn't actually continue speaking" and
     * re-enqueue the un-played tail of [spokenChunks] (gated on
     * [BargeInPreferences.resumeAfterInterruption]). Cancelled if a new
     * user turn begins normally or if we exit voice mode.
     */
    private var resumeWatchdog: Job? = null

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
        // === B4 barge-in: optional so the pre-barge-in call sites compile.
        // When all three are null, barge-in is silently disabled — e.g. an
        // emulator without a working VAD build, or unit tests that don't
        // exercise the listener path. When any one is non-null the triple
        // must be complete; we log and disable barge-in if not. ===
        bargeInPreferences: BargeInPreferencesRepository? = null,
        vadEngineFactory: (() -> VadEngine)? = null,
        bargeInListenerFactory: ((VadEngine, () -> Int) -> BargeInListener)? = null,
    ) {
        this.voiceClient = voiceClient
        this.chatViewModel = chatViewModel
        this.recorder = recorder
        this.player = player
        this.sfxPlayer = sfxPlayer

        // B4 barge-in wiring. All-or-nothing: if any of the three is null
        // we disable barge-in entirely rather than half-wiring. This
        // matches the "default off at launch" posture in the plan.
        if (bargeInPreferences != null && vadEngineFactory != null && bargeInListenerFactory != null) {
            this.bargeInPreferences = bargeInPreferences
            this.vadEngineFactory = vadEngineFactory
            this.bargeInListenerFactory = bargeInListenerFactory
            viewModelScope.launch {
                bargeInPreferences.flow.collect { prefs ->
                    _bargeInPrefs.value = prefs
                    // If the user flips the toggle off mid-Speaking, tear
                    // the listener down immediately — don't wait for the
                    // next Speaking transition.
                    if ((!prefs.enabled || prefs.sensitivity == BargeInSensitivity.Off) &&
                        bargeInListener != null
                    ) {
                        stopBargeInListener()
                    }
                }
            }
        } else if (bargeInPreferences != null || vadEngineFactory != null ||
            bargeInListenerFactory != null
        ) {
            Log.w(TAG, "barge-in collaborators only partially wired; disabling barge-in")
        }

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
            onDispatchResult = { label, result, androidToolName, androidToolArgsJson ->
                // === v0.4.1 voice-intent → server session sync ===
                // Build a structured trace so VoiceIntentSyncBuilder can
                // synthesize an OpenAI tool_call/response pair on the
                // next chat send. We attach the trace to the post-
                // dispatch result bubble (NOT the pre-dispatch trace
                // bubble) because this is the moment we have the
                // authoritative dispatch outcome — pre-dispatch was
                // either pending (destructive intents in countdown) or
                // not-yet-known when its bubble was created. Skipped
                // when androidToolName is null (intent wasn't classifiable
                // as a concrete `android_*` tool — e.g. local UI flows
                // that exited via a structured "permission missing" or
                // "not found" branch without dispatching anything).
                val voiceTrace = if (androidToolName != null) {
                    val resultJson = if (result.isSuccess) {
                        VoiceIntentSyncBuilder.successResultJson(result.resultJson)
                    } else {
                        VoiceIntentSyncBuilder.failureResultJson(
                            errorMessage = result.errorMessage,
                            errorCode = result.errorCode,
                        )
                    }
                    VoiceIntentTrace(
                        toolName = androidToolName,
                        argumentsJson = androidToolArgsJson,
                        success = result.isSuccess,
                        resultJson = resultJson,
                    )
                } else null
                // === END v0.4.1 sync ===
                // Chat trace (markdown bubble) — pre-existing. Trace
                // attaches the structured payload above when present.
                chatViewModel.recordVoiceIntentResult(label, result, voiceTrace)
                // Spoken follow-up so the user hears the outcome without
                // looking at the screen. Wave-2 voice mode was visually
                // honest but audibly silent after dispatch — Bailey hit
                // this 2026-04-15.
                speakDispatchResult(label, result)
                // v0.4.1 JIT permission-denied chip. When the dispatch
                // failed because the user hasn't granted the matching
                // runtime permission, surface a tappable hint above the
                // mic button so they can fix it without leaving voice
                // mode by hand. Cleared on the next mic tap (see
                // [enterVoiceMode] / [onMicTap] paths).
                val callout = buildPermissionDeniedCallout(label, result)
                _uiState.update {
                    it.copy(
                        destructiveCountdown = null,
                        permissionDeniedCallout = callout,
                    )
                }
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
        // B4: tear down the barge-in listener + timers before we kill the
        // player so AEC doesn't try to track a released audio session.
        stopBargeInListener()
        duckingWatchdog?.cancel(); duckingWatchdog = null
        resumeWatchdog?.cancel(); resumeWatchdog = null
        clearSpokenChunksState()
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
        idleFlushJob?.cancel()
        idleFlushJob = null
        // Drain any queued sentences so the consumer doesn't re-play the
        // interrupted turn when it's restarted below.
        while (true) {
            val r = ttsQueue.tryReceive()
            if (r.isFailure || r.isClosed) break
        }
        // V4 pipeline teardown (see interruptSpeaking for rationale).
        ttsConsumerJob?.cancel()
        ttsConsumerJob = null
        deletePendingSynthFiles()
        startTtsConsumer()
        sentenceBuffer = StringBuilder()
        pendingRawDelta = StringBuilder()
        lastObservedMessageId = null
        lastObservedContentLength = 0
        streamComplete = false

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

        // B4: user manually started a turn → tear down the barge-in
        // listener and cancel any pending resume. Normal "tap mic to
        // talk" path also lands here, so we always leave Speaking cleanly.
        stopBargeInListener()
        resumeWatchdog?.cancel(); resumeWatchdog = null
        lastInterruptedAtChunkIndex = null
        clearSpokenChunksState()

        // Stop any TTS playback when the user starts talking.
        try { player?.stop() } catch (_: Exception) { /* ignore */ }

        try {
            rec.startRecording()
            _uiState.update {
                it.copy(
                    state = VoiceState.Listening,
                    error = null,
                    responseText = "",
                    // v0.4.1 — fresh turn, drop any stale JIT permission chip
                    // from the previous dispatch.
                    permissionDeniedCallout = null,
                )
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
        // B4: tear down the barge-in listener immediately so we don't
        // double-trigger on the ducking watchdog or emit another
        // bargeInDetected while the resume watchdog is deliberating.
        // stopBargeInListener is null-safe.
        stopBargeInListener()
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
        idleFlushJob?.cancel()
        idleFlushJob = null
        // V4 pipeline teardown: cancel the supervisor scope that owns the
        // synth + play workers so any in-flight synthesize() call is
        // cancelled mid-flight and the play worker exits its for-in loop.
        // Delete any written-but-unplayed cache files, then restart the
        // consumer so the next turn has a live pipeline ready. Reordering
        // note: cancel BEFORE delete so the synth worker can't race us by
        // adding a new file to pendingTtsFiles between our snapshot and
        // the restart.
        ttsConsumerJob?.cancel()
        ttsConsumerJob = null
        deletePendingSynthFiles()
        startTtsConsumer()
        // Reset per-turn buffering + tracking so the next turn starts clean.
        sentenceBuffer = StringBuilder()
        pendingRawDelta = StringBuilder()
        lastObservedMessageId = null
        lastObservedContentLength = 0
        streamComplete = false
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
                    // Append a local-only PRE-DISPATCH trace bubble to chat
                    // history so the user sees a record of what they said
                    // and what's about to happen. Pre-v0.4.0 the voice
                    // intent path returned silently here and the chat
                    // scroll had zero record of voice utterances — making
                    // follow-up questions feel like the chat had "reset".
                    //
                    // v0.4.1 — server session sync: this pre-dispatch
                    // bubble intentionally does NOT carry the structured
                    // VoiceIntentTrace. The structured trace lives on the
                    // POST-dispatch result bubble emitted from
                    // `onDispatchResult` (see initialize() above) because
                    // that's the moment we have the authoritative
                    // dispatch outcome — pre-dispatch was either pending
                    // (destructive intents during the 5s countdown) or
                    // not-yet-known. VoiceIntentSyncBuilder reads the
                    // post-dispatch trace on the next chat send and
                    // synthesizes the OpenAI tool_call/response pair the
                    // server-side LLM ingests.
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
        pendingRawDelta = StringBuilder()
        lastObservedMessageId = null
        lastObservedContentLength = 0
        streamComplete = false
        idleFlushJob?.cancel()
        idleFlushJob = null
        // B4: wipe the barge-in resume state — the user started a fresh
        // turn, so anything that was queued for a potential resume is now
        // stale. Also cancel any pending resume watchdog so it can't fire
        // against the new turn's Speaking chunks.
        resumeWatchdog?.cancel(); resumeWatchdog = null
        clearSpokenChunksState()

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
                    // Stream ended — mark complete so the chunker stops
                    // holding short trailing sentences, cancel the idle
                    // timer (we know exactly when the stream is done), and
                    // flush any trailing buffer.
                    streamComplete = true
                    idleFlushJob?.cancel()
                    idleFlushJob = null
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
     * Handle a single SSE delta chunk: sanitize then append to the sentence
     * buffer, flush any completed sentences, update the visible
     * responseText. The visible [responseText] is left as the raw full
     * content — the sanitizer is strictly for the downstream TTS chunker
     * (the chat surface has its own renderer and wants the markdown).
     */
    private fun onStreamDelta(delta: String, fullContent: String) {
        appendSanitizedDelta(delta)
        _uiState.update { it.copy(state = VoiceState.Speaking, responseText = fullContent) }
        drainSentences()
        rearmIdleFlush()
    }

    /**
     * Cancel and re-arm the debounced [TIMER_FLUSH_MS] idle-flush job.
     *
     * Called from [onStreamDelta] on every incoming SSE delta. If the
     * stream stalls and no delta arrives within [TIMER_FLUSH_MS], the job
     * force-flushes whatever is in [sentenceBuffer] as if the stream had
     * completed — short sentences included. The stream-complete handler
     * in [startStreamObserver] cancels this job explicitly when it knows
     * the stream is done, so the timer only fires on a true stall.
     *
     * Structured under [viewModelScope] so `exitVoiceMode`, `stopVoice`
     * (via `onCleared`), and `interruptSpeaking` tear it down naturally
     * alongside the rest of the per-turn state.
     */
    private fun rearmIdleFlush() {
        idleFlushJob?.cancel()
        idleFlushJob = viewModelScope.launch {
            delay(TIMER_FLUSH_MS)
            // Flush whatever is in the buffer — treat as stream-complete
            // for this drain pass so short trailing fragments don't stay
            // stuck. Do NOT permanently set the class-level streamComplete
            // flag — the SSE stream may still be live and more deltas
            // could arrive, in which case we want the next chunker pass
            // to go back to conservative coalescing.
            if (pendingRawDelta.isNotEmpty()) {
                val cleaned = sanitizeForTts(pendingRawDelta.toString())
                pendingRawDelta = StringBuilder()
                if (cleaned.isNotEmpty()) sentenceBuffer.append(cleaned)
            }
            drainSentencesForceFlush()
        }
    }

    /**
     * Feed a raw SSE delta through [sanitizeForTts] before it reaches
     * [sentenceBuffer]. Handles the streaming code-fence edge case.
     *
     * A markdown code fence (``` ``` ```) can span multiple deltas — the
     * opening fence in delta N, contents in delta N+1, closing fence in
     * delta N+2. If we sanitize each delta independently we strip the
     * opening fence but leave the unfenced contents visible to TTS,
     * effectively defeating the point of the code-fence rule. Worse, the
     * trailing closing fence shows up as an orphan backtick.
     *
     * Mitigation: hold raw deltas in [pendingRawDelta] while the running
     * count of ``` is odd (unclosed fence). As soon as the count becomes
     * even (fence closed — or no fences in the text at all) run the full
     * sanitizer on the pending region and append the sanitized result to
     * [sentenceBuffer]. Then clear the pending buffer.
     *
     * The single-delta, no-fence common case collapses to: append delta,
     * count is 0 (even), sanitize, flush, clear.
     */
    private fun appendSanitizedDelta(delta: String) {
        pendingRawDelta.append(delta)
        val fenceCount = countOccurrences(pendingRawDelta, "```")
        if (fenceCount % 2 != 0) {
            // Odd = an unclosed fence is in flight. Hold everything — the
            // code-fence regex can't match yet and sanitizing now would
            // orphan a backtick or strip a half-open fence marker. Re-try
            // on the next delta.
            return
        }
        val cleaned = sanitizeForTts(pendingRawDelta.toString())
        pendingRawDelta = StringBuilder()
        if (cleaned.isNotEmpty()) {
            sentenceBuffer.append(cleaned)
        }
    }

    private fun drainSentences() {
        while (true) {
            val sentence = extractNextSentence(sentenceBuffer, streamComplete) ?: return
            if (sentence.isBlank()) continue
            val sent = ttsQueue.trySend(sentence)
            if (sent.isFailure) {
                Log.w(TAG, "TTS queue refused sentence: ${sent.exceptionOrNull()?.message}")
                return
            }
        }
    }

    /**
     * One-shot drain that temporarily treats the stream as complete so
     * short trailing fragments emit instead of lingering in the buffer.
     * Used by the idle-flush timer path — see [rearmIdleFlush]. The
     * class-level [streamComplete] flag is NOT mutated here because the
     * real SSE stream may still be live (the timer is a safety net, not
     * an authoritative signal).
     */
    private fun drainSentencesForceFlush() {
        while (true) {
            val sentence = extractNextSentence(sentenceBuffer, streamComplete = true) ?: return
            if (sentence.isBlank()) continue
            val sent = ttsQueue.trySend(sentence)
            if (sent.isFailure) {
                Log.w(TAG, "TTS queue refused sentence: ${sent.exceptionOrNull()?.message}")
                return
            }
        }
    }

    private fun flushRemainingBuffer() {
        // If a code fence was still open when the stream ended, force a
        // sanitize on the pending region now — better to strip partial
        // markdown than to silently drop the tail of the assistant turn.
        if (pendingRawDelta.isNotEmpty()) {
            val cleaned = sanitizeForTts(pendingRawDelta.toString())
            pendingRawDelta = StringBuilder()
            if (cleaned.isNotEmpty()) {
                sentenceBuffer.append(cleaned)
            }
        }
        // Run the chunker once with stream-complete semantics so any
        // coalesced short sentences that were held back during streaming
        // now get emitted — then sweep any final remainder into the queue
        // as a single trailing chunk. The sweep covers the case where
        // drainSentencesForceFlush consumed everything up to the last
        // terminator but left a trailing terminator-less tail.
        drainSentencesForceFlush()
        val trailing = sentenceBuffer.toString().trim()
        sentenceBuffer = StringBuilder()
        if (trailing.isNotEmpty()) {
            ttsQueue.trySend(trailing)
        }
    }

    // ---------------------------------------------------------------------
    // TTS consumer — two-coroutine pipeline: synth runs ahead of playback
    // ---------------------------------------------------------------------
    //
    // V4 (voice-quality-pass 2026-04-16) rewrites what used to be a strictly
    // serial synth→play→await loop into two parallel workers joined by a
    // bounded [Channel] so sentence N+1's network round-trip overlaps
    // sentence N's audio playback.
    //
    // ┌──────────────┐  String  ┌────────────┐  File (cap=2)  ┌────────────┐
    // │ streamObserver │──────────▶│ synth worker │────────────────▶│ play worker│
    // └──────────────┘  ttsQueue └────────────┘   audioQueue   └────────────┘
    //
    // Why the capacity-2 Channel<File> (Option B) instead of eagerly
    // appending every synthesized file to the ExoPlayer queue (Option A):
    // VoicePlayer (V5) doesn't expose its internal queue depth as a public
    // API and V4's guardrails forbid modifying VoicePlayer.kt, so we can't
    // read ExoPlayer.mediaItemCount from the synth side to gate
    // "maxMediaItemsAhead = 2." Option B makes the backpressure explicit
    // at the Channel level — [audioQueue.send] suspends the synth worker
    // once two files are queued, so disk usage and ElevenLabs spend both
    // stay bounded regardless of how fast the SSE stream delivers
    // sentences. The play worker calls [VoicePlayer.awaitCompletion] per
    // file (V5's "queue drained + not playing" semantic) so ExoPlayer's
    // own queue never grows past 1 — a single queuing layer, cleanly owned.
    //
    // Supervision: both workers are children of a [supervisorScope] so a
    // failure in one (e.g. a single synth error) does not tear down the
    // other. Cancelling [ttsConsumerJob] cleanly cancels both workers; the
    // interrupt paths exploit this to reset the pipeline between turns.

    private fun startTtsConsumer() {
        ttsConsumerJob?.cancel()
        // Fresh capacity-2 audio channel per pipeline lifecycle (Kotlin
        // [Channel] instances can't be "re-opened" after close). Capacity
        // 2 lets the synth worker stay one sentence ahead of playback —
        // enough to hide the ~200 ms synth round-trip behind the current
        // sentence's audio without unbounded disk growth on long agent
        // responses.
        val queue = Channel<File>(capacity = 2)
        ttsConsumerJob = viewModelScope.launch {
            try {
                supervisorScope {
                    launch { runSynthWorker(queue) }
                    launch { runPlayWorker(queue) }
                }
            } finally {
                // Race defence: if the consumer was cancelled while the
                // synth worker had a `synthesize()` call in flight, that
                // call could complete AFTER cancel was signalled and the
                // file would be written to disk + added to pendingTtsFiles
                // before the cancellation exception propagated to the send
                // site. [interruptSpeaking] snapshots pendingTtsFiles
                // immediately after cancelling this job — there's a window
                // where the late-arriving file would miss the snapshot.
                // Running the cleanup inside this finally-block (which
                // runs after both workers have finished unwinding) closes
                // the race: the synth worker's own cancellation path has
                // completed by the time this executes, so any late-added
                // file is definitely in pendingTtsFiles by now.
                deletePendingSynthFiles()
            }
        }
    }

    /**
     * Synth worker: consume sentences from [ttsQueue], produce audio
     * files into [queue]. Delegates to the testable [runTtsSynthWorker]
     * top-level function with this ViewModel's dependencies bound.
     *
     * B4 hook: on successful synthesize we append the sentence to
     * [spokenChunks] so the barge-in resume path can later re-enqueue
     * un-played tail chunks. Additive to the V4 worker contract — the
     * worker itself is unaware; we wrap the `synthesize` callable.
     */
    private suspend fun runSynthWorker(queue: Channel<File>) {
        runTtsSynthWorker(
            sentences = ttsQueue,
            output = queue,
            synthesize = { sentence ->
                val client = voiceClient
                val result = if (client == null) {
                    Result.failure(IllegalStateException("voiceClient not initialized"))
                } else {
                    client.synthesize(sentence)
                }
                // B4: track the chunk regardless of success so the resume
                // path can decide whether the text was spoken or not. Only
                // successful synthesis makes it into spokenChunks — a
                // failed chunk never reached the player, so replaying it
                // in a resume would double-surface the sentence while the
                // user's screen shows it skipped.
                if (result.isSuccess) {
                    spokenChunks.add(sentence)
                }
                result
            },
            pendingFiles = pendingTtsFiles,
            onSynthError = { err -> surfaceError(err, context = "synthesize") },
        )
    }

    /**
     * Play worker: consume files from [queue], hand each to [VoicePlayer]
     * and await its drain. Delegates to the testable [runTtsPlayWorker]
     * top-level function with this ViewModel's dependencies bound.
     *
     * B4 hook: on each new file we bump [_currentPlayingChunkIndex] and
     * start the BargeInListener if it isn't running and the user has
     * opted into barge-in. Teardown happens elsewhere (interruptSpeaking,
     * exitVoiceMode, maybeAutoResume).
     */
    private suspend fun runPlayWorker(queue: Channel<File>) {
        runTtsPlayWorker(
            input = queue,
            play = { file -> player?.play(file) },
            awaitCompletion = { player?.awaitCompletion() },
            onFileReady = { file ->
                // About to play — ensure Speaking so the amplitude bridge
                // forwards the player output to the UI.
                if (_uiState.value.voiceMode && _uiState.value.state != VoiceState.Speaking) {
                    _uiState.update { it.copy(state = VoiceState.Speaking) }
                }
                // B4: advance the playing-chunk cursor BEFORE we call
                // trackTtsFile so lastInterruptedAtChunkIndex reflects
                // "currently playing" from the moment play() returns.
                _currentPlayingChunkIndex.value = _currentPlayingChunkIndex.value + 1
                trackTtsFile(file)
                // B4: first chunk of a Speaking run → spin up the listener
                // if the user has it enabled. Idempotent — startBargeInListener
                // no-ops if already active.
                startBargeInListenerIfEnabled()
            },
            pendingFiles = pendingTtsFiles,
            onQueueDrained = {
                // B4: queue drained at end of response → Speaking is about
                // to flip to Idle via maybeAutoResume. Tear the listener
                // down now rather than waiting for the state update so we
                // don't leak mic time across the Speaking→Idle boundary.
                stopBargeInListener()
                clearSpokenChunksState()
                maybeAutoResume()
            },
        )
    }

    /**
     * Delete files that were synthesized by the synth worker but never
     * handed to [VoicePlayer.play]. Called from [interruptSpeaking],
     * [exitVoiceMode], and [onCleared] after the consumer scope is
     * cancelled. Safe to call when the set is empty (no-op).
     *
     * ExoPlayer's queue is cleaned up separately by [VoicePlayer.stop];
     * this function only owns the window between "file written to disk"
     * and "file handed to VoicePlayer.play()."
     */
    private fun deletePendingSynthFiles() {
        // Snapshot under the set's intrinsic lock so a racing add() from
        // a mid-cancel synth worker doesn't throw ConcurrentModification.
        // Collections.synchronizedSet requires external locking for iter.
        val snapshot: List<File> = synchronized(pendingTtsFiles) {
            val copy = pendingTtsFiles.toList()
            pendingTtsFiles.clear()
            copy
        }
        for (f in snapshot) {
            try { f.delete() } catch (_: Exception) { /* ignore */ }
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

    // ---------------------------------------------------------------------
    // B4 barge-in — lifecycle + event wiring (voice-barge-in 2026-04-17)
    // ---------------------------------------------------------------------

    /**
     * Start a [BargeInListener] for the current Speaking turn if the user
     * has barge-in enabled with a non-Off sensitivity and the listener is
     * not already running. Idempotent. Safe to call on any dispatcher.
     *
     * The listener's `start` lazily attaches AEC against the ExoPlayer's
     * audio session id — we pass a lambda that reads it every time so the
     * listener's internal poll loop can watch it flip from 0 to non-zero
     * as playback begins.
     */
    private fun startBargeInListenerIfEnabled() {
        // Already running → no-op. We bracket per Speaking turn, not per
        // chunk within a turn.
        if (bargeInListener != null) return

        val factory = bargeInListenerFactory ?: return
        val vadFactory = vadEngineFactory ?: return
        val prefs = _bargeInPrefs.value
        if (!prefs.enabled || prefs.sensitivity == BargeInSensitivity.Off) return

        val p = player ?: return

        val vad = try {
            vadFactory().also { it.setSensitivity(prefs.sensitivity) }
        } catch (t: Throwable) {
            Log.w(TAG, "VadEngine construction failed; skipping barge-in: ${t.message}")
            return
        }
        bargeInVadEngine = vad

        val listener = try {
            factory(vad) { p.audioSessionId }
        } catch (t: Throwable) {
            Log.w(TAG, "BargeInListener construction failed; skipping barge-in: ${t.message}")
            try { vad.close() } catch (_: Throwable) { /* ignore */ }
            bargeInVadEngine = null
            return
        }

        bargeInListener = listener
        bargeInListenerJob = viewModelScope.launch {
            // Fan out the two event flows on child coroutines of this job.
            launch { listener.maybeSpeech.collect { onMaybeSpeech() } }
            launch { listener.bargeInDetected.collect { onBargeInDetected() } }
        }
        try {
            listener.start(viewModelScope)
        } catch (t: Throwable) {
            Log.w(TAG, "BargeInListener.start failed: ${t.message}")
            stopBargeInListener()
        }
    }

    /**
     * Tear down the active [BargeInListener], cancel its event subscribers,
     * unduck the player (in case a ducking watchdog hadn't yet restored
     * volume), and release the owned VAD engine. Idempotent.
     */
    private fun stopBargeInListener() {
        bargeInListenerJob?.cancel(); bargeInListenerJob = null
        try { bargeInListener?.stop() } catch (_: Throwable) { /* ignore */ }
        bargeInListener = null
        try { bargeInVadEngine?.close() } catch (_: Throwable) { /* ignore */ }
        bargeInVadEngine = null
        duckingWatchdog?.cancel(); duckingWatchdog = null
        // Best-effort un-duck so the next playback starts at full volume.
        if (isDucked) {
            try { player?.unduck() } catch (_: Throwable) { /* ignore */ }
            isDucked = false
        }
    }

    /**
     * Raw-VAD positive frame — soft-duck the TTS and arm the un-duck
     * watchdog. If the next frame passes the hysteresis threshold,
     * [onBargeInDetected] fires and cancels the watchdog; otherwise the
     * watchdog restores full volume after [DUCK_WATCHDOG_MS].
     *
     * Idempotent for "already ducked" — keeps the watchdog fresh so a
     * bursty speech signal keeps the duck alive without flickering.
     */
    internal fun onMaybeSpeech() {
        if (!isDucked) {
            try { player?.duck() } catch (_: Throwable) { /* ignore */ }
            isDucked = true
        }
        scheduleDuckingWatchdog()
    }

    /**
     * Post-hysteresis VAD fire — the user is actually speaking. Capture
     * the current playing chunk for resume, call [interruptSpeaking] to
     * tear down the synth/play pipeline, flip state to Listening,
     * pre-warm the recorder, and arm the resume watchdog.
     *
     * The order matters: capture [lastInterruptedAtChunkIndex] BEFORE
     * [interruptSpeaking] because we don't want the interrupt path's
     * state updates racing our read of [_currentPlayingChunkIndex].
     * We keep [spokenChunks] intact here — [interruptSpeaking] must
     * NOT clear it; the resume watchdog will consume it.
     */
    internal fun onBargeInDetected() {
        duckingWatchdog?.cancel(); duckingWatchdog = null
        lastInterruptedAtChunkIndex = _currentPlayingChunkIndex.value

        // interruptSpeaking tears down the listener itself, so subsequent
        // bargeInDetected emissions are impossible until the next
        // Speaking entry. Belt-and-braces: we also cancel the subscriber
        // job as part of stopBargeInListener which interruptSpeaking calls.
        interruptSpeaking()

        // interruptSpeaking landed us in Idle — flip to Listening and
        // pre-warm the recorder so the first ~100 ms of user speech
        // isn't clipped by recorder cold-start.
        _uiState.update { it.copy(state = VoiceState.Listening, error = null, responseText = "") }
        val rec = recorder
        if (rec != null && !rec.isRecording()) {
            try {
                rec.startRecording()
            } catch (t: Throwable) {
                Log.w(TAG, "barge-in pre-warm recorder failed: ${t.message}")
                surfaceError(t, context = "record")
                return
            }
        }

        scheduleResumeWatchdog()
    }

    /**
     * Arm (or re-arm) the duck watchdog. Cancels any pending job first so
     * a rapid burst of `maybeSpeech` frames keeps extending the watchdog
     * window instead of stacking timers.
     */
    private fun scheduleDuckingWatchdog() {
        duckingWatchdog?.cancel()
        duckingWatchdog = viewModelScope.launch {
            delay(DUCK_WATCHDOG_MS)
            // Watchdog fired — no bargeInDetected arrived in time. This
            // was almost certainly a single-frame false positive. Restore
            // full volume.
            if (isDucked) {
                try { player?.unduck() } catch (_: Throwable) { /* ignore */ }
                isDucked = false
            }
        }
    }

    /**
     * Arm the resume watchdog. After [RESUME_WATCHDOG_MS] of user-speech
     * silence (peak VoiceRecorder amplitude < [RESUME_SILENCE_THRESHOLD])
     * and with `resumeAfterInterruption == true`, re-enqueue the un-played
     * tail of [spokenChunks] so TTS picks up where it left off.
     *
     * "Silence" signal choice — we use [VoiceRecorder.amplitude] rather
     * than re-subscribing to the BargeInListener. Reasons:
     *   1. The listener was stopped by [interruptSpeaking]; restarting it
     *      mid-watchdog would double-open AudioRecord.
     *   2. The recorder is already running (pre-warmed in
     *      [onBargeInDetected]) and its amplitude flow is hot.
     *   3. Amplitude is a simpler, threshold-based signal — if the user
     *      trailed off after a cough, the follower settles back to zero
     *      quickly; if they're actively speaking, it stays well above
     *      the threshold across the 600 ms window.
     */
    private fun scheduleResumeWatchdog() {
        resumeWatchdog?.cancel()
        resumeWatchdog = viewModelScope.launch {
            val peak = observePeakAmplitude(RESUME_WATCHDOG_MS)
            val prefs = _bargeInPrefs.value
            val wasSilent = peak < RESUME_SILENCE_THRESHOLD

            if (!wasSilent) {
                // User kept speaking. Clear the resume state — the new
                // turn should proceed normally; their utterance will be
                // handled by the existing stopListening → processVoiceInput
                // pipeline.
                lastInterruptedAtChunkIndex = null
                return@launch
            }

            // Silence after interrupt. If resume is off, drop the tail
            // and return to Idle (the user wanted a hard cancel semantic).
            if (!prefs.resumeAfterInterruption) {
                lastInterruptedAtChunkIndex = null
                // Recorder was pre-warmed under the assumption the user
                // would keep speaking; cancel it so it doesn't hang open.
                try { recorder?.cancel() } catch (_: Throwable) { /* ignore */ }
                _uiState.update { it.copy(state = VoiceState.Idle, amplitude = 0f) }
                return@launch
            }

            // Silence + resume enabled → re-enqueue un-played chunks.
            val startIdx = (lastInterruptedAtChunkIndex ?: -1) + 1
            val snapshot: List<String> = synchronized(spokenChunks) { spokenChunks.toList() }
            val tail = if (startIdx in snapshot.indices) snapshot.drop(startIdx) else emptyList()

            // Cancel the recorder that was pre-warmed for a user turn
            // that didn't materialize.
            try { recorder?.cancel() } catch (_: Throwable) { /* ignore */ }

            if (tail.isEmpty()) {
                lastInterruptedAtChunkIndex = null
                _uiState.update { it.copy(state = VoiceState.Idle, amplitude = 0f) }
                return@launch
            }

            // Reset per-turn tracking so the play worker's chunk cursor
            // starts fresh on the resumed tail and spokenChunks rebuilds
            // cleanly when the synth worker re-processes the re-enqueued
            // text.
            lastInterruptedAtChunkIndex = null
            clearSpokenChunksState()

            // Flip UI back to Speaking and un-duck in case the watchdog
            // didn't clear earlier (shouldn't happen — duckingWatchdog
            // was cancelled in onBargeInDetected — but cheap insurance).
            if (isDucked) {
                try { player?.unduck() } catch (_: Throwable) { /* ignore */ }
                isDucked = false
            }
            _uiState.update { it.copy(state = VoiceState.Speaking) }

            // Send each tail chunk back through the TTS queue. The synth
            // worker will re-synthesize (our cache isn't content-addressed)
            // and the play worker will pick them up in order.
            for (chunk in tail) {
                val sent = ttsQueue.trySend(chunk)
                if (sent.isFailure) {
                    Log.w(TAG, "resume re-enqueue dropped chunk: ${sent.exceptionOrNull()?.message}")
                    break
                }
            }
            // Re-arm the barge-in listener for the resumed Speaking — the
            // play worker's onFileReady will also trigger this but a
            // redundant call is a cheap no-op.
            startBargeInListenerIfEnabled()
        }
    }

    /**
     * Suspend for up to [windowMs] and return the peak
     * [VoiceRecorder.amplitude] observed during the window. If the
     * recorder never emits (not initialized or cancelled mid-window),
     * returns 0f.
     *
     * Implementation note: we `first { ... }` on an above-threshold
     * value with a short timeout so we can fast-exit the moment the user
     * is clearly speaking — but we still need the full window when they
     * stay silent, so the no-match path falls back to the last seen peak.
     * For simplicity we use the full window uniformly; the resume
     * decision is infrequent (one fire per interrupt) so the extra
     * 600 ms latency on a confirmed resume is acceptable.
     */
    private suspend fun observePeakAmplitude(windowMs: Long): Float {
        val rec = recorder ?: return 0f
        var peak = 0f
        withTimeoutOrNull(windowMs) {
            rec.amplitude.collect { amp ->
                val sanitized = sanitizeAmplitude(amp)
                if (sanitized > peak) peak = sanitized
            }
        }
        return peak
    }

    /**
     * Clear the per-turn barge-in state so the next Speaking turn starts
     * from index -1 with an empty [spokenChunks]. Called on turn
     * boundaries and voice-mode exit.
     */
    private fun clearSpokenChunksState() {
        synchronized(spokenChunks) { spokenChunks.clear() }
        _currentPlayingChunkIndex.value = -1
    }

    // ---------------------------------------------------------------------
    // B4 barge-in — test seams (voice-barge-in 2026-04-17)
    // ---------------------------------------------------------------------
    //
    // These are `internal` solely so `VoiceViewModelBargeInTest` in the test
    // source set can exercise the coordinator without spinning up the full
    // VoicePlayer → ExoPlayer → supervisorScope pipeline that owns the
    // production startBargeInListenerIfEnabled call site (runPlayWorker's
    // onFileReady). Production callers never invoke these.

    /**
     * Test hook: force the lazy barge-in listener creation path without
     * pushing a file through the play pipeline. No-op if barge-in is off
     * or collaborators aren't wired — matches the production predicate in
     * [startBargeInListenerIfEnabled].
     */
    @androidx.annotation.VisibleForTesting
    internal fun startBargeInListenerForTest() {
        startBargeInListenerIfEnabled()
    }

    /**
     * Test hook: seed the Speaking state + spoken-chunk ledger so a
     * synthetic `onBargeInDetected()` can exercise the resume-watchdog
     * decisions without re-running the V4 synth worker. Mirrors the
     * invariants the synth worker + play worker would have established on
     * a live turn (one successful synthesize per chunk, chunkIndex reflects
     * currently-playing file).
     */
    @androidx.annotation.VisibleForTesting
    internal fun seedSpeakingStateForTest(chunks: List<String>, currentIdx: Int) {
        synchronized(spokenChunks) {
            spokenChunks.clear()
            spokenChunks.addAll(chunks)
        }
        _currentPlayingChunkIndex.value = currentIdx
        _uiState.update { it.copy(voiceMode = true, state = VoiceState.Speaking) }
    }

    /**
     * Test hook: cancel the V4 synth/play consumer scope so subsequent
     * `ttsQueue.trySend` calls aren't raced by the background worker.
     * Used by the resume-watchdog tests to get a deterministic view of
     * the queue — production never calls this.
     */
    @androidx.annotation.VisibleForTesting
    internal fun stopTtsConsumerForTest() {
        ttsConsumerJob?.cancel()
        ttsConsumerJob = null
    }

    /**
     * Test hook: non-blocking drain of the TTS queue into a list. Used by
     * the resume-watchdog tests to assert which chunks were re-enqueued.
     * The consumer pipeline also reads from this channel, so drain order
     * matters — in tests we stop the consumer (or never touch it) before
     * calling this to get a deterministic view.
     */
    @androidx.annotation.VisibleForTesting
    internal fun drainTtsQueueForTest(): List<String> {
        val out = mutableListOf<String>()
        while (true) {
            val r = ttsQueue.tryReceive()
            if (r.isSuccess) {
                r.getOrNull()?.let { out.add(it) }
            } else {
                break
            }
        }
        return out
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
        // B4: release the listener + VAD engine native resources before
        // anything else. stopBargeInListener is defensive / idempotent.
        stopBargeInListener()
        duckingWatchdog?.cancel()
        resumeWatchdog?.cancel()
        try { recorder?.cancel() } catch (_: Exception) { /* ignore */ }
        try { player?.stop() } catch (_: Exception) { /* ignore */ }
        try { sfxPlayer?.release() } catch (_: Exception) { /* ignore */ }
        ttsQueue.close()
        streamObserverJob?.cancel()
        ttsConsumerJob?.cancel()
        amplitudeBridgeJob?.cancel()
        currentTurnJob?.cancel()
        idleFlushJob?.cancel()
        // V4: clean up any synthesized-but-unplayed cache files before
        // the VM is collected. The consumer job was just cancelled, so
        // the synth worker will never resume draining pendingTtsFiles.
        deletePendingSynthFiles()
    }

    /**
     * v0.4.1 JIT permission-denied callout — convert a permission-denied
     * dispatch outcome into a chip-ready [PermissionDeniedCallout], or
     * return null if the result is not a permission denial.
     *
     * Reads the structured `permission` (canonical, v0.4.1) or
     * `required_permission` (legacy) field off the result JSON to identify
     * which Android permission was missing.
     */
    internal fun buildPermissionDeniedCallout(
        label: String,
        result: LocalDispatchResult,
    ): PermissionDeniedCallout? {
        if (result.errorCode != "permission_denied") return null
        val json = result.resultJson ?: return null
        val permission = (json["permission"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.contentOrNull
            ?: (json["required_permission"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.contentOrNull
            ?: return null
        if (permission.isBlank()) return null
        val friendly = friendlyPermissionName(permission)
        val hint = "I need $friendly to $label here. Tap to open Settings."
        return PermissionDeniedCallout(
            permission = permission,
            intentLabel = label,
            hint = hint,
        )
    }

    /**
     * Map an Android permission constant to a user-readable noun used in
     * the JIT callout copy. Falls back to the trailing dotted token if the
     * permission isn't in the curated list — covers the common surface
     * without forcing every new permission to update this map.
     */
    private fun friendlyPermissionName(permission: String): String = when (permission) {
        "android.permission.READ_CONTACTS" -> "Contacts"
        "android.permission.SEND_SMS" -> "SMS"
        "android.permission.CALL_PHONE" -> "Phone"
        "android.permission.ACCESS_FINE_LOCATION" -> "Location"
        "android.permission.ACCESS_COARSE_LOCATION" -> "Location"
        "android.permission.RECORD_AUDIO" -> "Microphone"
        "android.permission.CAMERA" -> "Camera"
        "android.permission.POST_NOTIFICATIONS" -> "Notifications"
        else -> permission.substringAfterLast('.').replace('_', ' ').lowercase()
            .replaceFirstChar { it.uppercase() }
    }

    /**
     * Public clear-hook called by the overlay after the user taps the chip
     * (so it dismisses immediately instead of lingering after they navigate
     * away). Also called when the user starts a fresh voice turn so a stale
     * callout from a prior turn doesn't haunt the new one.
     */
    fun clearPermissionDeniedCallout() {
        _uiState.update { it.copy(permissionDeniedCallout = null) }
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
        return sanitizeForTts(base)
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
 * Minimum chunk length (in chars) before we hand text to TTS. Was 6 in the
 * pre-V3 chunker, which produced a call-per-short-sentence pattern like
 * "Sure." / "Let me check." / "Here's the result." — each one a separate
 * ElevenLabs round-trip with its own prosody interpretation, which is the
 * root cause of the "muffled switch" symptom in the voice-quality-pass
 * diagnosis.
 *
 * Raised to 40 so adjacent short sentences coalesce into a single synth
 * call. Don't push this much higher — latency-to-first-voice grows roughly
 * linearly with the threshold.
 */
private const val MIN_COALESCE_LEN = 40

/**
 * Absolute escape hatch for run-on text that never emits a terminator.
 * If the buffer exceeds this without a `.?!\n` boundary the chunker falls
 * back to a secondary-break split at the latest `,`, `;`, or em-dash
 * inside the first [MAX_BUFFER_LEN] chars. Without this, a 1000-char
 * paragraph of comma-separated content would block playback for several
 * seconds waiting for a sentence boundary that never comes.
 */
private const val MAX_BUFFER_LEN = 400

private val SENTENCE_TERMINATORS = charArrayOf('.', '!', '?', '\n')

/**
 * Characters we accept as a secondary break for the MAX_BUFFER_LEN escape
 * hatch. `—` (em-dash U+2014) and `–` (en-dash U+2013) both count — agents
 * emit either. The ` - ` ASCII-hyphen-with-spaces case is deliberately
 * excluded; it matches hyphenated prose too aggressively ("well-formed").
 */
private val SECONDARY_BREAKS = charArrayOf(',', ';', '\u2014', '\u2013')

/**
 * Pop the next TTS-ready chunk off [buffer] (mutating it) and return it.
 * Returns null when nothing should be emitted yet.
 *
 * Implementation choice (V3 of the voice-quality-pass, 2026-04-16):
 * **extended hand-rolled regex**, not ICU4J `BreakIterator`. Tried the
 * latter as a spike — it handles abbreviations via its locale-specific
 * rule set (fine on English prose) but behaves unpredictably on partial
 * buffers that may end mid-URL / mid-code-fence-remnant after V2's
 * sanitizer runs, and it can't be parameterized with our
 * `streamComplete` / `MIN_COALESCE_LEN` coalescing semantics without
 * re-inventing the loop on top of it anyway. The hand-rolled loop below
 * is deterministic, keeps the V2-vintage whitespace-lookahead
 * abbreviation rule intact (`e.g.`, `U.S.` don't split), and lets us
 * reason about the secondary-break escape hatch directly.
 *
 * Coalescing rules:
 *   - Find the next terminator `.?!\n` whose lookahead char is whitespace
 *     or end-of-buffer (preserves the abbreviation rule from the original
 *     chunker).
 *   - If the run from [0, idx+1] is >= [MIN_COALESCE_LEN] → emit it.
 *   - Else: remember the boundary, keep scanning. The next terminator
 *     extends the candidate chunk; we emit as soon as the length crosses
 *     the threshold.
 *   - If we exhaust the buffer without crossing the threshold AND
 *     [streamComplete] is true → emit whatever is there (don't starve
 *     the queue on a final "Okay.").
 *   - If exhausted AND [streamComplete] is false → return null and wait
 *     for more deltas.
 *   - If buffer.length > [MAX_BUFFER_LEN] without any terminator at all
 *     → flush at the **last** secondary break (`,` `;` `—` `–`) inside
 *     the first [MAX_BUFFER_LEN] chars. We pick the last, not the first,
 *     so we ship as much text as possible per chunk instead of dribbling
 *     out tiny fragments right at the threshold.
 *
 * Exposed as `internal` for unit testing from the test source set.
 */
internal fun extractNextSentence(
    buffer: StringBuilder,
    streamComplete: Boolean = false,
): String? {
    if (buffer.isEmpty()) return null

    // Pass 1: scan for a terminator-bounded chunk that satisfies
    // MIN_COALESCE_LEN (with abbreviation lookahead).
    var idx = 0
    var lastCoalescableEnd = -1 // exclusive end of the last valid boundary we've seen
    while (idx < buffer.length) {
        val ch = buffer[idx]
        if (ch in SENTENCE_TERMINATORS) {
            val nextChar = if (idx + 1 < buffer.length) buffer[idx + 1] else null
            val isBoundary = ch == '\n' || nextChar == null || nextChar.isWhitespace()
            if (isBoundary) {
                lastCoalescableEnd = idx + 1
                if (lastCoalescableEnd >= MIN_COALESCE_LEN) {
                    return consumeChunk(buffer, lastCoalescableEnd)
                }
                // Otherwise keep scanning — maybe the next sentence extends
                // us past the coalesce threshold.
            }
        }
        idx++
    }

    // Pass 2: no chunk reached the threshold.
    // 2a. MAX_BUFFER_LEN escape hatch — flush at the last secondary break
    //     inside the first MAX_BUFFER_LEN chars so we don't block playback
    //     on a never-terminating run-on.
    if (buffer.length > MAX_BUFFER_LEN) {
        val breakIdx = findLastSecondaryBreak(buffer, MAX_BUFFER_LEN)
        if (breakIdx >= 0) {
            // Consume inclusive of the break char, just like terminators.
            return consumeChunk(buffer, breakIdx + 1)
        }
        // No secondary break at all inside the window — hard flush at
        // MAX_BUFFER_LEN so the queue doesn't stall.
        return consumeChunk(buffer, MAX_BUFFER_LEN)
    }

    // 2b. Stream is complete — emit whatever we have, short or not, so
    //     the final "Okay." doesn't starve in the buffer.
    if (streamComplete) {
        // Prefer cutting at the last terminator-bounded boundary if we
        // saw one (keeps trailing whitespace tidy). Otherwise emit the
        // whole buffer.
        val end = if (lastCoalescableEnd > 0) {
            // If there's more non-whitespace text after the last boundary
            // we still want to emit everything in a final flush — the
            // whole buffer is the right answer.
            val tailStart = lastCoalescableEnd
            var hasTail = false
            var j = tailStart
            while (j < buffer.length) {
                if (!buffer[j].isWhitespace()) { hasTail = true; break }
                j++
            }
            if (hasTail) buffer.length else lastCoalescableEnd
        } else {
            buffer.length
        }
        return consumeChunk(buffer, end)
    }

    return null
}

/**
 * Pop [endExclusive] chars off the front of [buffer] as a trimmed chunk.
 * Swallows trailing whitespace after the consumed region so the next
 * chunk starts clean. Returns the trimmed chunk.
 */
private fun consumeChunk(buffer: StringBuilder, endExclusive: Int): String {
    val chunk = buffer.substring(0, endExclusive).trim()
    var consume = endExclusive
    while (consume < buffer.length && buffer[consume].isWhitespace()) {
        consume++
    }
    buffer.delete(0, consume)
    return chunk
}

/**
 * Find the index of the **last** secondary break inside the first
 * [windowLen] chars of [buffer], or -1 if none. Used by the
 * MAX_BUFFER_LEN escape hatch to flush as much text as possible per
 * chunk rather than dribbling out at the first comma.
 *
 * Internal so [extractNextSentence]'s Max-Buffer-Len path can be
 * unit-tested directly via the test source set if needed.
 */
internal fun findLastSecondaryBreak(buffer: CharSequence, windowLen: Int): Int {
    val end = minOf(buffer.length, windowLen)
    for (i in end - 1 downTo 0) {
        if (buffer[i] in SECONDARY_BREAKS) {
            // Require the next char to be whitespace OR end-of-window.
            // Prevents splitting inside "3,000" (comma with no space after).
            val nextIsWs = i + 1 >= buffer.length || buffer[i + 1].isWhitespace()
            if (nextIsWs) return i
        }
    }
    return -1
}

// -------------------------------------------------------------------------
// TTS text sanitization
//
// Mirrors the regex set in `plugin/relay/tts_sanitizer.py` (V1 of the
// voice-quality-pass plan, 2026-04-16). The client-side copy runs on
// every incoming SSE delta BEFORE it reaches the sentence buffer so the
// chunker in [extractNextSentence] never sees markdown punctuation or
// emoji that would end up inside a TTS chunk anyway.
//
// Parity matters: if the two regex sets drift, the phone will strip a
// different set of tokens than the relay and debugging "why did voice
// read that aloud" becomes a two-sided investigation. If you change a
// pattern here, change it in the Python module — and vice versa.
// -------------------------------------------------------------------------

// Code fences first — their inner contents must not be interpreted by the
// bold / italic / inline-code rules below. DOTALL so `.` crosses newlines.
private val TTS_CODE_BLOCK = Regex("```[\\s\\S]*?```")
private val TTS_MD_LINK = Regex("\\[([^\\]]+)\\]\\([^)]+\\)")
private val TTS_MD_URL = Regex("https?://\\S+")
private val TTS_MD_BOLD = Regex("\\*\\*(.+?)\\*\\*")
private val TTS_MD_ITALIC = Regex("\\*(.+?)\\*")
private val TTS_MD_INLINE_CODE = Regex("`(.+?)`")
private val TTS_MD_HEADER = Regex("^#+\\s*", RegexOption.MULTILINE)
private val TTS_MD_LIST_ITEM = Regex("^\\s*[-*]\\s+", RegexOption.MULTILINE)
private val TTS_MD_HR = Regex("---+")
private val TTS_MD_EXCESS_NL = Regex("\\n{3,}")

/**
 * Conservative tool-annotation emoji alternation (mirrors
 * `_TOOL_ANNOTATION_EMOJI` in the Python module). Matches ChatHandler.kt's
 * annotation vocabulary. Kept deliberately small — broad Unicode-category
 * sweeps eat flag emoji and mixed-script punctuation and cause more
 * regressions than they prevent.
 *
 * NOTE: this is written as an alternation group rather than a Python-style
 * character class. java.util.regex operates on UTF-16 code units, so a
 * character class `[🔧💻]` would match an individual surrogate half
 * rather than a full code-point — stripping only `\uD83D` and leaving an
 * orphan `\uDD27` behind. Alternation keeps each emoji atomic.
 */
private const val TTS_TOOL_ANNOTATION_EMOJI_ALT =
    "\uD83D\uDD27" +          // 🔧 wrench
    "|\u2705" +               // ✅ white heavy check mark
    "|\u274C" +               // ❌ cross mark
    "|\uD83D\uDCBB" +         // 💻 laptop
    "|\uD83D\uDCF1" +         // 📱 mobile phone
    "|\uD83C\uDFA4" +         // 🎤 microphone
    "|\uD83D\uDD0A" +         // 🔊 speaker high volume
    "|\u26A0"                 // ⚠  warning sign (with or without U+FE0F)

/**
 * Backtick-wrapped token beginning with one of the tool-annotation
 * emojis. Strip the entire match, not just the backticks — otherwise the
 * inline-code rule would leave the label visible ("computer emoji
 * terminal" after the backticks go).
 */
private val TTS_TOOL_ANNOTATION = Regex(
    "`(?:$TTS_TOOL_ANNOTATION_EMOJI_ALT)\uFE0F?[^`]*`",
)

/**
 * Standalone (non-backticked) instances of the same conservative set.
 * Optional U+FE0F (variation selector) trails the warning sign in most
 * emoji keyboard outputs.
 */
private val TTS_STANDALONE_EMOJI = Regex(
    "(?:$TTS_TOOL_ANNOTATION_EMOJI_ALT)\uFE0F?",
)

/**
 * Strip markdown, tool annotations, URLs, and emoji from text destined
 * for the TTS backend. Pure function; safe to call from any thread.
 *
 * Order matters:
 *   1. Code fences — their contents must not be processed by later rules.
 *   2. Tool annotations — must run before inline-code, otherwise the
 *      inline-code rule would strip only the backticks and leave the
 *      emoji+label for TTS to read aloud.
 *   3. The standard markdown set (links, URLs, bold, italic, inline code,
 *      headers, list markers, horizontal rules).
 *   4. Standalone emoji from the conservative set.
 *   5. Whitespace normalization — collapse runs of 3+ newlines and trim.
 *
 * Returns an empty string for null-equivalent input (empty or
 * whitespace-only). Otherwise the cleaned text is returned trimmed.
 *
 * Exposed `internal` so the test source set can exercise it without
 * instantiating a full [VoiceViewModel] (which would require an
 * [android.app.Application] and wired dependencies).
 */
internal fun sanitizeForTts(text: String): String {
    if (text.isEmpty() || text.isBlank()) return ""

    var out = text
    // 1) Code fences before anything else.
    out = TTS_CODE_BLOCK.replace(out, " ")
    // 2) Tool annotations BEFORE inline code.
    out = TTS_TOOL_ANNOTATION.replace(out, "")
    // 3) Upstream markdown set.
    out = TTS_MD_LINK.replace(out, "$1")
    out = TTS_MD_URL.replace(out, "")
    out = TTS_MD_BOLD.replace(out, "$1")
    out = TTS_MD_ITALIC.replace(out, "$1")
    out = TTS_MD_INLINE_CODE.replace(out, "$1")
    out = TTS_MD_HEADER.replace(out, "")
    out = TTS_MD_LIST_ITEM.replace(out, "")
    out = TTS_MD_HR.replace(out, "")
    // 4) Standalone emoji — conservative set only.
    out = TTS_STANDALONE_EMOJI.replace(out, "")
    // 5) Whitespace normalization.
    out = TTS_MD_EXCESS_NL.replace(out, "\n\n")
    return out.trim()
}

/**
 * Count non-overlapping occurrences of [needle] in [haystack]. Used to
 * detect whether a streaming delta has an unclosed ``` fence (odd count
 * means odd number of fence markers → a fence is currently open).
 *
 * Internal so the test source set can verify the streaming-delta
 * deferral path without having to hand-roll a string scanner.
 */
internal fun countOccurrences(haystack: CharSequence, needle: String): Int {
    if (needle.isEmpty()) return 0
    var count = 0
    var idx = 0
    while (idx <= haystack.length - needle.length) {
        var match = true
        for (j in needle.indices) {
            if (haystack[idx + j] != needle[j]) {
                match = false
                break
            }
        }
        if (match) {
            count++
            idx += needle.length
        } else {
            idx++
        }
    }
    return count
}

// ─── V4 TTS prefetch pipeline — testable worker extractions ─────────────
//
// The synth + play coroutines live as top-level `internal` suspend funcs
// so `VoiceViewModelPipelineTest` can drive them against fakes in a
// `runTest`/`TestCoroutineScheduler` harness. The VM's private
// `runSynthWorker` / `runPlayWorker` members are thin binders that inject
// the real dependencies (`voiceClient`, `player`, UI-state updates) into
// these generic functions.

/**
 * Testable synth-worker body. Pulls sentences from [sentences], calls
 * [synthesize], and sends any successful [File] onto [output]. Files are
 * recorded in [pendingFiles] BEFORE the `send` so a cancellation while
 * suspended on backpressure can still reach the file and delete it.
 * Failures from [synthesize] are reported via [onSynthError] and
 * skipped — the pipeline must not stall on a single flaky call.
 *
 * Closes [output] on normal termination OR cancellation so the downstream
 * play worker's loop can exit cleanly.
 */
internal suspend fun runTtsSynthWorker(
    sentences: kotlinx.coroutines.channels.ReceiveChannel<String>,
    output: Channel<File>,
    synthesize: suspend (String) -> Result<File>,
    pendingFiles: MutableSet<File>,
    onSynthError: (Throwable?) -> Unit,
) {
    try {
        for (sentence in sentences) {
            val result = try {
                synthesize(sentence)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Result.failure<File>(e)
            }
            if (result.isFailure) {
                onSynthError(result.exceptionOrNull())
                continue
            }
            val file = result.getOrNull() ?: continue
            pendingFiles.add(file)
            try {
                output.send(file)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                pendingFiles.remove(file)
                try { file.delete() } catch (_: Exception) { /* ignore */ }
                break
            }
        }
    } finally {
        output.close()
    }
}

/**
 * Testable play-worker body. Drains [input], invoking [play] + awaiting
 * [awaitCompletion] per file in order. Between files, a non-blocking
 * `tryReceive` peek detects the "queue momentarily empty" instant at
 * which [onQueueDrained] fires — preserving the pre-V4 `maybeAutoResume`
 * checkpoint semantics.
 *
 * Removes each file from [pendingFiles] only AFTER [play] returns
 * successfully. Before that point the file's on-disk lifetime is owned
 * by the play worker alone, and a cancellation between `input.receive`
 * and `play(file)` would leak the file if we'd removed it too early.
 */
internal suspend fun runTtsPlayWorker(
    input: Channel<File>,
    play: suspend (File) -> Unit,
    awaitCompletion: suspend () -> Unit,
    onFileReady: (File) -> Unit,
    pendingFiles: MutableSet<File>,
    onQueueDrained: () -> Unit,
) {
    while (true) {
        val immediate = input.tryReceive()
        val file: File = when {
            immediate.isSuccess -> immediate.getOrNull() ?: continue
            immediate.isClosed -> break
            else -> {
                onQueueDrained()
                val received = input.receiveCatching()
                if (received.isClosed) break
                received.getOrNull() ?: continue
            }
        }

        onFileReady(file)
        try {
            play(file)
            pendingFiles.remove(file)
            awaitCompletion()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // play() threw — file never entered the player's queue. Leave
            // it in pendingFiles so the cancellation cleanup path deletes it.
        }
    }
}
