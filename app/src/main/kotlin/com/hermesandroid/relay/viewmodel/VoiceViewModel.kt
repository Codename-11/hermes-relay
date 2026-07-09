package com.hermesandroid.relay.viewmodel

import android.app.Application
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermesandroid.relay.audio.BargeInListener
import com.hermesandroid.relay.audio.RealtimePcmPlayer
import com.hermesandroid.relay.audio.VadEngine
import com.hermesandroid.relay.audio.VoicePlayer
import com.hermesandroid.relay.audio.VoiceRecorder
import com.hermesandroid.relay.audio.VoiceSfxPlayer
import com.hermesandroid.relay.data.BargeInPreferences
import com.hermesandroid.relay.data.BargeInPreferencesRepository
import com.hermesandroid.relay.data.BargeInSensitivity
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.data.RealtimeConversationContextMessage
import com.hermesandroid.relay.data.ToolCall
import com.hermesandroid.relay.data.VoiceEngineMode
import com.hermesandroid.relay.data.VoiceIntentTrace
import com.hermesandroid.relay.diagnostics.DiagnosticCategory
import com.hermesandroid.relay.diagnostics.DiagnosticSeverity
import com.hermesandroid.relay.diagnostics.DiagnosticsLog
import com.hermesandroid.relay.network.relay.ChannelMultiplexer
import com.hermesandroid.relay.network.relay.RelayVoiceClient
import com.hermesandroid.relay.network.relay.RelayVoiceAudioClientAdapter
import com.hermesandroid.relay.network.relay.RealtimeAgentSessionControl
import com.hermesandroid.relay.network.relay.RealtimeTurnInput
import com.hermesandroid.relay.network.relay.RealtimeVoiceSummary
import com.hermesandroid.relay.network.relay.RealtimeVoiceEvent
import com.hermesandroid.relay.network.relay.VoiceHandoffEvent
import com.hermesandroid.relay.network.shared.VoiceAudioClient
import com.hermesandroid.relay.network.shared.LocalDispatchResult
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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import com.hermesandroid.relay.data.VoicePreferencesRepository
import com.hermesandroid.relay.data.VoiceAudioRoute

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

internal fun InteractionMode.storageValue(): String = when (this) {
    InteractionMode.TapToTalk -> "tap"
    InteractionMode.HoldToTalk -> "hold"
    InteractionMode.Continuous -> "continuous"
}

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
    /**
     * True only after agent output has produced real playback audio for the
     * current Speaking turn. The voice UI uses this to keep the waveform in a
     * processing/spinner shape while TTS/realtime output is still preparing.
     */
    val outputAudioActive: Boolean = false,
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
    val hermesConfirmation: HermesConfirmationState? = null,
    /**
     * Short-lived network handoff surface. Populated only while a resumable
     * voice websocket is reconnecting or briefly after it succeeds/fails.
     */
    val handoffStatus: VoiceHandoffStatus? = null,
    /**
     * v0.4.1 JIT permission-denied chip. Non-null when the most recent voice
     * intent dispatch returned `errorCode == "permission_denied"`. Tap-target
     * deep-links to `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` for our
     * package so the user can grant the missing permission without leaving
     * voice mode by hand. Cleared when the user opens the chip OR enters a
     * fresh turn (mic tap), whichever comes first.
     */
    val permissionDeniedCallout: PermissionDeniedCallout? = null,
    /**
     * ADR 33: non-null while a Hermes run has been promoted to (or started as) a
     * background task. Drives a persistent "working on it" chip so the user
     * knows a long task is still running after the spoken handoff. Cleared when
     * the background run completes, is cancelled, or errors.
     */
    val backgroundRun: BackgroundRunState? = null,
)

/** ADR 33 background/promoted Hermes run surface for the voice overlay. */
data class BackgroundRunState(
    val runId: String? = null,
    /** "promoted" (auto-detached long run) or "durable" (explicit mode=background). */
    val tier: String = "promoted",
    val message: String = "Working on it in the background…",
    /**
     * Live secondary line from the run's progress/tool events (e.g. "Running
     * command."). With timer-driven spoken progress off by default, this chip
     * line is the primary in-between signal for a background run.
     */
    val statusLine: String? = null,
    /** Tools completed so far (from hermes.run.progress). */
    val completedToolCount: Int = 0,
    /** Tasks queued behind this run (background-run v2 queue) — "+N queued". */
    val queuedCount: Int = 0,
    /** Wall-clock at promotion — drives the chip's mm:ss elapsed ticker. */
    val startedAtMs: Long = System.currentTimeMillis(),
    val phase: BackgroundRunPhase = BackgroundRunPhase.RUNNING,
)

/** Connection-aware phase for the background-run chip. */
enum class BackgroundRunPhase {
    /** Run in flight; progress events are flowing. */
    RUNNING,

    /** The voice socket dropped mid-run; the relay keeps the run alive and the
     *  client is retrying the resume — the task is safe, not lost. */
    RECONNECTING,

    /** The run finished; the spoken summary is queued behind the floor. */
    DELIVERING,

    /**
     * The answer was delivered (summary audio started, or delivery settled
     * without audio). The chip lingers briefly showing the outcome instead of
     * vanishing the instant the waveform/spinner returns, then
     * auto-dismisses; its ✕ becomes a local dismiss.
     */
    DONE,
}

data class VoiceHandoffStatus(
    val title: String,
    val route: String? = null,
    val active: Boolean = true,
    val success: Boolean = false,
    val entries: List<VoiceHandoffTraceEntry> = emptyList(),
    val updatedAtMs: Long = System.currentTimeMillis(),
)

data class VoiceHandoffTraceEntry(
    val label: String,
    val detail: String? = null,
)

data class HermesConfirmationState(
    val confirmationId: String,
    val message: String,
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
 * Rolling telemetry snapshot for the voice pipeline. Exposed by
 * [VoiceViewModel.voiceStats] for the Stats-for-Nerds "Voice" section.
 *
 * All fields are session-scoped — they reset when the VM is recreated.
 * The VM updates this via [update]-style copies on significant events
 * (STT call, TTS call, barge-in, threshold change, state transition);
 * high-frequency amplitude ticks do NOT update this so the StateFlow
 * doesn't thrash recomposition.
 */
data class VoiceStats(
    /** Last-heard transcript text (unbounded; UI truncates). */
    val lastTranscript: String = "",
    /** Most recent STT round-trip in ms (upload + decode). */
    val lastSttLatencyMs: Long = 0L,
    /** Cumulative STT bytes uploaded this session. */
    val sttBytesUploaded: Long = 0L,
    /** Number of STT calls completed this session. */
    val sttCallCount: Int = 0,
    /** Last-synthesized sentence (unbounded; UI truncates). */
    val lastSynthesizedSentence: String = "",
    /** Rolling window of TTS per-sentence latency (last 5). */
    val recentTtsLatenciesMs: List<Long> = emptyList(),
    /** Number of TTS/realtime render chunks emitted for the current response. */
    val currentResponseTtsChunks: Int = 0,
    /** Gap between the previous render finishing and the latest render starting. */
    val lastTtsChunkGapMs: Long = 0L,
    /** Cumulative TTS bytes received this session. */
    val ttsBytesReceived: Long = 0L,
    /** Number of TTS calls completed this session. */
    val ttsCallCount: Int = 0,
    /** Count of barge-in hard-interrupts this session. */
    val bargeInCount: Int = 0,
    /** Currently configured silence-to-stop threshold in ms. */
    val vadThresholdMs: Long = 0L,
    /** Current interaction mode ("tap" / "hold" / "continuous"). */
    val interactionMode: String = "tap",
    /** Current voice engine ("hermes_voice_output" / "realtime_agent"). */
    val voiceEngineMode: String = VoiceEngineMode.HermesVoiceOutput.storageValue,
    /** Last player state tag ("idle" / "playing" / "paused"). */
    val playerState: String = "idle",
    /** Current TTS queue depth (pending + in-synth + in-play). */
    val ttsQueueDepth: Int = 0,
)

/**
 * Orchestrates the voice-mode turn cycle:
 *
 *   Idle → Listening (record mic) → Transcribing (upload) → Thinking
 *        → Speaking (sentence-buffered TTS) → Idle
 *
 * Requires [VoiceRecorder], [VoicePlayer], [VoiceAudioClient], and a
 * [ChatViewModel] for sending the transcribed text through the normal
 * chat pipeline. Realtime Agent still uses [RelayVoiceClient].
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

        /** How long the DONE background-run chip lingers before auto-dismiss. */
        private const val DONE_CHIP_LINGER_MS = 10_000L
        private const val MAX_BROKERED_TOOL_STATUS_PER_MESSAGE = 2
        private const val STABLE_VOICE_INTERFACE_CONTEXT =
            "Hermes Android voice interface context for this turn:\n" +
                "- Active voice engine: Hermes chat + voice output (hermes_voice_output).\n" +
                "- Active route: Android mic -> selected Hermes STT route -> " +
                "normal Hermes chat stream -> selected Hermes TTS route playback.\n" +
                "- This is not Realtime Agent mode. If the user asks which " +
                "interface, path, or mode is active, answer from this context.\n" +
                "- Your reply will be spoken aloud by text-to-speech, so format " +
                "for the ear, not the eye: write short, conversational sentences; " +
                "avoid markdown (headings, bullet lists, tables, code blocks), " +
                "emoji, and raw URLs; describe structure in prose instead of " +
                "lists; and keep answers concise unless the user asks for detail."

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
         * Initial VAD mute window for provider-streamed PCM. The first few
         * frames after AudioTrack start are most likely to contain TTS echo or
         * route-settling noise, not a deliberate user interrupt.
         */
        private const val REALTIME_BARGE_IN_STARTUP_GUARD_MS = 850L
        private const val REALTIME_WATCHDOG_INTERVAL_MS = 75L
        private const val REALTIME_WATCHDOG_MAX_TICKS = 60 // ~4.5s of watching
        private const val REALTIME_STUCK_CURSOR_MS = 1_200L

        /**
         * Provider-streamed PCM can still be draining through AudioTrack after
         * the final websocket audio delta arrives. [RealtimePcmPlayer] tracks
         * the estimated queued duration; this final-delta guard covers device
         * output latency that is not visible from the write calls themselves.
         */
        private const val REALTIME_OUTPUT_RESUME_TAIL_GUARD_MS = 650L
        private const val AUDIO_COMPLETION_RETRY_MS = 100L
        private const val AUDIO_COMPLETION_MAX_SLEEP_MS = 750L
        private const val OUTPUT_AUDIO_ACTIVE_THRESHOLD = 0.012f

        /**
         * W3 spoken-status throttle. On long / tool-heavy realtime runs the
         * agent emits many spoken status lines ("Searching.", "Still working.")
         * which becomes chatty. Independent of the per-key [spokenStatusKeys]
         * dedupe: this caps BOTH the spoken cadence (no two spoken status lines
         * within [MIN_SPOKEN_STATUS_GAP_MS]) and the per-turn spoken count
         * ([MAX_SPOKEN_STATUS_PER_TURN]). Suppressed lines still update the UI
         * + diagnostics — only the TTS enqueue is skipped.
         */
        private const val MIN_SPOKEN_STATUS_GAP_MS = 22_000L
        private const val MAX_SPOKEN_STATUS_PER_TURN = 3

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
         *
         * Reused by the Listening-turn silence auto-stop watchdog (2026-04-18)
         * since the same amplitude curve drives both decisions.
         */
        internal const val RESUME_SILENCE_THRESHOLD: Float = 0.08f

        /**
         * Poll interval for the Listening-turn silence watchdog. The
         * [VoiceRecorder.amplitude] poll itself runs at ~16 ms; checking
         * every 150 ms is coarse enough to be cheap (a couple of reads per
         * second) and fine enough that auto-stop lands within ~150 ms of
         * the configured threshold crossing.
         */
        private const val SILENCE_WATCHDOG_POLL_MS: Long = 150L

        /**
         * Idle/no-speech auto-close for a Listening turn that never hears
         * speech — parity with hermes-desktop voice_mode `idleSilenceMs`. The
         * turn is cancelled WITHOUT transcribing (nothing was said), then the
         * loop is free to re-arm.
         */
        private const val IDLE_NO_SPEECH_MS: Long = 12_000L

        /**
         * Hard ceiling on a single Listening turn — parity with hermes-desktop
         * voice_mode's 60 s turn timeout. Whatever was captured is sent to
         * transcription so a long monologue still completes.
         */
        private const val MAX_LISTEN_TURN_MS: Long = 60_000L

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
    private var voiceAudioClient: VoiceAudioClient? = null
    private var chatViewModel: ChatViewModel? = null
    private var recorder: VoiceRecorder? = null
    private var player: VoicePlayer? = null
    private var realtimePcmPlayer: RealtimePcmPlayer? = null
    private var sfxPlayer: VoiceSfxPlayer? = null
    // 2026-04-18: promoted to a field so the silence auto-stop watchdog can
    // read `silenceThresholdMs` at the start of each Listening turn. The
    // value is a live Flow, so the watchdog snapshots it on entry; changes
    // mid-turn take effect on the next turn.
    private var voicePreferences: VoicePreferencesRepository? = null
    private var voicePreferencesJob: Job? = null
    private var voiceEngineMode: VoiceEngineMode = VoiceEngineMode.HermesVoiceOutput
    private var realtimeTraceDetails: Boolean = false
    private var realtimePersistentSession: Boolean = true
    private var realtimeAgentControl: RealtimeAgentSessionControl? = null
    private var realtimeConfirmationControl: RealtimeAgentSessionControl? = null

    // === Persistent realtime-agent session (one socket across turns) ===
    // The long-lived call to RelayVoiceClient.runRealtimeAgent(persistent) runs
    // in realtimeSessionJob; further utterances are fed on realtimeTurnChannel.
    // The per-turn event holders below are hoisted to fields so the single
    // session-lived event callback can serve every turn; submitRealtimeTurn /
    // the open path reset them at each turn boundary.
    private var realtimeSessionJob: Job? = null
    private var realtimeTurnChannel: kotlinx.coroutines.channels.Channel<RealtimeTurnInput>? = null

    /** Watchdog that clears a DELIVERING background-run chip if no summary
     *  audio ever arrives (visual-only delivery, provider hiccup). */
    private var deliveringChipClearJob: Job? = null
    private var rtUserText: String = ""
    private var rtAssistantMessageId: String = ""
    private var rtConversationContext: List<RealtimeConversationContextMessage> = emptyList()
    private val audioSeen = AtomicBoolean(false)
    private val audioBytes = AtomicInteger(0)
    private val bargeInStarted = AtomicBoolean(false)
    private val lastRealtimeAudioEventId = AtomicLong(0L)
    private var responseText = StringBuilder()
    private var inputTranscript = StringBuilder()
    private val spokenStatusKeys = mutableSetOf<String>()
    // W3 spoken-status throttle (per turn). Reset alongside spokenStatusKeys at
    // every turn start/reset. See [shouldSpeakStatusNow] for the decision.
    private var lastSpokenStatusAtMs: Long = 0L
    private var spokenStatusCount: Int = 0
    private var voiceRelayPreflight: (suspend () -> Result<Unit>)? = null

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

    // Rolling voice pipeline telemetry for StatsForNerds → Voice section.
    // Updated on discrete lifecycle events (turn start/stop, STT call
    // complete, TTS call complete, barge-in fire, threshold read, state
    // transition) rather than on every amplitude tick so the StateFlow
    // stays recomposition-cheap.
    private val _voiceStats = MutableStateFlow(VoiceStats())
    val voiceStats: StateFlow<VoiceStats> = _voiceStats.asStateFlow()

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

    /** Preferred streaming voice-output queue. Falls back to [ttsQueue] when unavailable. */
    private val realtimeTtsQueue = Channel<String>(Channel.UNLIMITED)

    /**
     * True while the provider-native Realtime Agent owns speech output.
     *
     * During this window we must not route local status snippets through the
     * stable `/voice/output` renderer. Doing so opens a second websocket and
     * a second AudioTrack beside the provider stream, which causes competing
     * audio and jitter during long Hermes tool work.
     */
    private val providerRealtimeAgentTurnActive = AtomicBoolean(false)

    /**
     * Sentences that have been [Channel.trySend]-enqueued to [ttsQueue] but
     * whose synthesize round-trip has not yet completed. Incremented at each
     * successful trySend; decremented in the synth worker's synthesize
     * lambda's finally block (covering both success and failure paths, and
     * covering the full "queued → being synthesized" window).
     *
     * Combined with [pendingTtsFiles] (synthesized-but-not-yet-played), this
     * lets [maybeAutoResume] detect when the TTS pipeline is truly drained.
     *
     * **Why this gate exists.** The original v0.4.1 maybeAutoResume fired
     * whenever [runTtsPlayWorker]'s `tryReceive` returned empty on the
     * audioQueue — but the audioQueue goes transiently empty while a short
     * final sentence is mid-synthesis (flushed to ttsQueue right before the
     * observer cancels, synth takes ~200 ms on eleven_flash_v2_5). In
     * Continuous mode, `startListening()` calls `player.stop()`, which
     * clobbers the ExoPlayer queue before the freshly-synthesized final
     * chunk can be enqueued for playback — producing Bailey's "last
     * sentence not spoken" report on short emoji-ending replies
     * (2026-04-17 on-device testing).
     */
    private val pendingInTtsQueue = AtomicInteger(0)

    /** Tracks which assistant-message IDs have already been consumed so
     *  we don't re-process older turns when the history list updates. */
    private var lastObservedMessageId: String? = null
    private var lastObservedContentLength: Int = 0
    private var sentenceBuffer: StringBuilder = StringBuilder()
    private val realtimeSpeechCoalescer = BalancedRealtimeTtsCoalescer()
    private val brokeredToolSpeechKeys = mutableSetOf<String>()
    private val brokeredToolSpeechCounts = mutableMapOf<String, Int>()

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
    private var realtimeTtsConsumerJob: Job? = null
    private var amplitudeBridgeJob: Job? = null
    private var currentTurnJob: Job? = null
    // Post-response audio completion retry. Continuous mode uses the same
    // finalizer as tap/hold, then re-arms listening only when explicitly armed.
    private var continuousResumeJob: Job? = null
    private var realtimeAmplitudeDecayJob: Job? = null
    private var firstFrameWatchdogJob: Job? = null
    private var continuousLoopArmed: Boolean = false
    private var lastRealtimeAudioDeltaAtMs: Long = 0L

    /**
     * Set true when the user interrupts a realtime turn ([interruptSpeaking]).
     * The persistent realtime socket stays open by design, so audio deltas
     * already in flight can still arrive after we stop the player and would
     * re-create the AudioTrack — making "Stop" feel like it didn't work. While
     * suppressed, [handleRealtimeVoiceEvent] drops audio writes. Cleared when
     * the next turn is actually sent ([submitRealtimeTurn] / [runRealtimeAgentTurn]).
     */
    @Volatile
    private var realtimeAudioSuppressed: Boolean = false
    private var listeningStartedAtMs: Long = 0L
    // 2026-04-18: silence-based auto-stop watchdog. Runs for the duration
    // of a Listening turn in TapToTalk / Continuous modes when the user has
    // a non-zero `silenceThresholdMs` preference. HoldToTalk never starts
    // this — the physical release is the authoritative stop signal.
    private var silenceWatchdogJob: Job? = null

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

    /** Raw PCM captured for the current user turn, forwarded to /voice/realtime. */
    private var currentTurnPcm: ByteArray = ByteArray(0)
    private var currentTurnPcmSampleRate: Int = 16_000

    /** Null = not probed, true = prefer realtime, false = fallback to basic TTS. */
    private var voiceOutputAvailable: Boolean? = null
    private var voiceOutputProfileName: String? = null
    private var ttsChunksThisResponse: Int = 0
    private var lastTtsChunkFinishedAtMs: Long = 0L

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
    private var bargeInPreferencesJob: Job? = null

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

    /**
     * Un-played [spokenChunks] tail captured synchronously the instant a
     * barge-in interrupt fires, before [interruptSpeaking] runs. This MUST
     * be snapshotted here rather than re-read by the resume watchdog 600 ms
     * later: [interruptSpeaking] restarts the TTS consumer, whose play
     * worker immediately hits an empty audioQueue and fires
     * `onQueueDrained` → [clearSpokenChunksState] synchronously (on
     * `Dispatchers.Main.immediate`). That clears [spokenChunks] long before
     * the watchdog reads it, so reading live state would always yield an
     * empty tail and silently drop the resume (regression caught by
     * `VoiceViewModelBargeInTest`, GitHub issue #32).
     */
    private var pendingResumeTail: List<String> = emptyList()

    /** True while TTS volume is ducked from a `maybeSpeech` event. */
    @Volatile private var isDucked: Boolean = false

    @Volatile private var bargeInIgnoreUntilMs: Long = 0L
    @Volatile private var bargeInGuardLogged: Boolean = false

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
    private var handoffStatusClearJob: Job? = null
    private var voiceHandoffReporter: ((VoiceHandoffEvent) -> Unit)? = null

    // ---------------------------------------------------------------------
    // Initialization
    // ---------------------------------------------------------------------

    /**
     * Wire dependencies. Called once from `RelayApp` after the singletons
     * are constructed. Safe to call multiple times — later calls rewire.
     */
    fun initialize(
        voiceClient: RelayVoiceClient,
        voiceAudioClient: VoiceAudioClient? = null,
        chatViewModel: ChatViewModel,
        recorder: VoiceRecorder,
        player: VoicePlayer,
        realtimePcmPlayer: RealtimePcmPlayer? = null,
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
        // === 2026-04-17 fix: persist interactionMode across app restarts ===
        // Optional so pre-fix call sites keep compiling. When non-null, the
        // VM subscribes to the settings flow and mirrors `interactionMode`
        // into `uiState` on every emission — so the user's stored "continuous"
        // preference takes effect on the first voice-mode entry after a cold
        // start, not only after they revisit Voice Settings. Without this the
        // VM defaulted to [InteractionMode.TapToTalk] per [VoiceUiState] and
        // the Continuous auto-resume branch never fired for saved-pref users.
        voicePreferences: VoicePreferencesRepository? = null,
        voiceRelayPreflight: (suspend () -> Result<Unit>)? = null,
        voiceHandoffReporter: ((VoiceHandoffEvent) -> Unit)? = null,
    ) {
        this.voiceClient = voiceClient
        this.voiceAudioClient = voiceAudioClient ?: RelayVoiceAudioClientAdapter(voiceClient)
        this.chatViewModel = chatViewModel
        this.recorder = recorder
        this.player = player
        this.realtimePcmPlayer = realtimePcmPlayer
        this.sfxPlayer = sfxPlayer
        this.voiceRelayPreflight = voiceRelayPreflight
        this.voiceHandoffReporter = voiceHandoffReporter

        // B4 barge-in wiring. All-or-nothing: if any of the three is null
        // we disable barge-in entirely rather than half-wiring. This
        // matches the "default off at launch" posture in the plan.
        if (bargeInPreferences != null && vadEngineFactory != null && bargeInListenerFactory != null) {
            this.bargeInPreferences = bargeInPreferences
            this.vadEngineFactory = vadEngineFactory
            this.bargeInListenerFactory = bargeInListenerFactory
            bargeInPreferencesJob?.cancel()
            bargeInPreferencesJob = viewModelScope.launch {
                bargeInPreferences.flow.collect { prefs ->
                    _bargeInPrefs.value = prefs
                    Log.i(
                        TAG,
                        "Barge-in prefs updated enabled=${prefs.enabled} " +
                            "sensitivity=${prefs.sensitivity} resume=${prefs.resumeAfterInterruption}",
                    )
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
            this.bargeInPreferences = null
            this.vadEngineFactory = null
            this.bargeInListenerFactory = null
            bargeInPreferencesJob?.cancel()
            bargeInPreferencesJob = null
            Log.w(TAG, "barge-in collaborators only partially wired; disabling barge-in")
        } else {
            this.bargeInPreferences = null
            this.vadEngineFactory = null
            this.bargeInListenerFactory = null
            bargeInPreferencesJob?.cancel()
            bargeInPreferencesJob = null
        }

        // 2026-04-17 fix: mirror VoicePreferences.interactionMode into
        // uiState so the user's stored "continuous" / "hold" preference
        // survives app restarts. Previously only the VoiceSettingsScreen
        // push-path called setInteractionMode(), leaving fresh VMs stuck
        // on TapToTalk until the user revisited Settings.
        //
        // 2026-04-18: also store the repository on the VM so the silence
        // auto-stop watchdog in startListening() can read the current
        // `silenceThresholdMs` without re-plumbing it through the UI.
        if (voicePreferences != null) {
            this.voicePreferences = voicePreferences
            voicePreferencesJob?.cancel()
            voicePreferencesJob = viewModelScope.launch {
                // The repository's `settings` flow is now scope-aware (WP-V2):
                // it re-emits whenever the DataStore OR the active
                // (connection, profile) scope changes, so a profile switch
                // (see [onProfileChanged]) flows the new profile's
                // engine/route/enhanced picks through here automatically.
                voicePreferences.settings.collect { settings ->
                    applyVoiceSettingsSnapshot(settings)
                }
            }
        } else {
            this.voicePreferences = null
            voicePreferencesJob?.cancel()
            voicePreferencesJob = null
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
        startRealtimeTtsConsumer()
        bridgeAmplitudeFlows()
        startVoiceStatsStateMirror()
    }

    /**
     * Mirror the coarse player state + queue depth into [voiceStats].
     * Runs for the lifetime of the VM; cheap — maps over discrete
     * VoiceState enum changes, not the per-frame amplitude stream.
     */
    private fun startVoiceStatsStateMirror() {
        viewModelScope.launch {
            // Map VoiceUiState.state → playerState label. We only care
            // about Speaking (→ "playing"), Idle (→ "idle"), and anything
            // else ("paused" / "busy"). Bucketed coarsely so the stats
            // panel doesn't flicker.
            _uiState.collect { ui ->
                val label = when (ui.state) {
                    VoiceState.Speaking -> "playing"
                    VoiceState.Listening, VoiceState.Transcribing,
                    VoiceState.Thinking -> "busy"
                    VoiceState.Error -> "error"
                    VoiceState.Idle -> "idle"
                }
                val depth = pendingInTtsQueue.get().coerceAtLeast(0)
                _voiceStats.update {
                    if (it.playerState == label && it.ttsQueueDepth == depth) {
                        it
                    } else {
                        it.copy(playerState = label, ttsQueueDepth = depth)
                    }
                }
            }
        }
    }

    fun setInteractionMode(mode: InteractionMode) {
        val previous = _uiState.value
        if (previous.interactionMode == mode) {
            persistInteractionMode(mode)
            return
        }

        if (mode != InteractionMode.Continuous) {
            continuousLoopArmed = false
            continuousResumeJob?.cancel()
            continuousResumeJob = null
        }

        if (previous.state == VoiceState.Listening && mode != InteractionMode.Continuous) {
            cancelListeningWithoutProcessing(
                title = "Voice mode switched",
                detail = "Cancelled active listening before changing interaction mode",
            )
        }

        _uiState.update { it.copy(interactionMode = mode) }
        persistInteractionMode(mode)

        if (mode == InteractionMode.Continuous && _uiState.value.voiceMode) {
            continuousLoopArmed = true
            when (_uiState.value.state) {
                VoiceState.Idle, VoiceState.Error -> startListening()
                else -> Unit
            }
        }
    }

    private fun persistInteractionMode(mode: InteractionMode) {
        val prefs = voicePreferences ?: return
        viewModelScope.launch {
            prefs.setInteractionMode(mode.storageValue())
        }
    }

    fun onProfileChanged(profileName: String?) {
        val normalized = profileName?.trim()?.takeIf { it.isNotBlank() }
        if (voiceOutputProfileName == normalized) return
        voiceOutputProfileName = normalized
        voiceOutputAvailable = null
        resetRealtimeSpeechCoalescer()
        // WP-V2: re-point the voice prefs at this (connection, profile) scope
        // and re-seed the VM's engine/route from the per-profile values so the
        // *next* turn speaks with this profile's voice, not the prior one's.
        // The repository's scope-aware `settings` flow also re-emits to the
        // initialize() collector; the explicit re-read below guarantees the
        // engine mode is correct even before that collector is rescheduled.
        applyVoicePrefsScope(normalized)
        maybeNoteStandardVoiceProfileLimitation(normalized)
    }

    /**
     * WP-V2 seam: the active connection id used to namespace per-profile voice
     * prefs (`_<connectionId>_<profile>`, mirroring `ProfileSelectionStore`).
     *
     * Wired from `ConnectionViewModel.activeConnectionId` by RelayApp's
     * (connection, profile) voice effect, which calls [setVoicePrefsConnection]
     * BEFORE [onProfileChanged] so the profile re-seed reads the
     * correctly-scoped keys. While null (fresh VM before that effect fires),
     * per-profile keys degrade to profile-only namespacing — still enough to
     * isolate profiles within a single connection; it just can't disambiguate
     * two connections that both expose a same-named profile.
     */
    private var voicePrefsConnectionId: String? = null

    /**
     * WP-V2 seam: set the active connection id for per-profile voice-pref
     * namespacing and re-apply the current scope. Lets a connection switch
     * (with the profile unchanged) re-target the right namespaced keys. Safe
     * to call before [initialize]; a no-op when the value is unchanged.
     */
    fun setVoicePrefsConnection(connectionId: String?) {
        val normalized = connectionId?.trim()?.takeIf { it.isNotBlank() }
        if (voicePrefsConnectionId == normalized) return
        voicePrefsConnectionId = normalized
        applyVoicePrefsScope(voiceOutputProfileName)
    }

    /**
     * Push the current (connection, profile) scope to the voice-prefs
     * repository and re-seed the VM's cached engine/route from the resolved
     * per-profile values. The explicit [firstOrNull] read shares
     * [applyVoiceSettingsSnapshot] with the reactive collector in
     * [initialize], so running both converges on the same values with no
     * duplicated engine-transition side effects (the first to run handles a
     * realtime-session close; the second sees no transition).
     */
    private fun applyVoicePrefsScope(profileName: String?) {
        val prefs = voicePreferences ?: return
        prefs.setActiveScope(voicePrefsConnectionId, profileName)
        viewModelScope.launch {
            prefs.settings.firstOrNull()?.let { applyVoiceSettingsSnapshot(it) }
        }
    }

    /**
     * Apply a [VoiceSettings] snapshot to the VM's cached engine/route/diag
     * state and the [VoiceStats] mirror. Shared by the [initialize] settings
     * collector and the per-profile re-seed ([applyVoicePrefsScope]); idempotent
     * so it is safe to call repeatedly with the same snapshot.
     */
    private fun applyVoiceSettingsSnapshot(settings: com.hermesandroid.relay.data.VoiceSettings) {
        val nextEngineMode = VoiceEngineMode.fromStorage(settings.engineMode)
        if (
            voiceEngineMode != nextEngineMode ||
            realtimeTraceDetails != settings.realtimeTraceDetails
        ) {
            Log.i(
                TAG,
                "Voice prefs updated engine=${nextEngineMode.storageValue} " +
                    "interaction=${settings.interactionMode} " +
                    "realtimeTraceDetails=${settings.realtimeTraceDetails}",
            )
        }
        // Switching engine away from Realtime Agent (or disabling the
        // persistent toggle) must drop any open persistent session.
        if (
            (voiceEngineMode == VoiceEngineMode.RealtimeAgent &&
                nextEngineMode != VoiceEngineMode.RealtimeAgent) ||
            (realtimePersistentSession && !settings.realtimePersistentSession)
        ) {
            closeRealtimeSession()
        }
        voiceEngineMode = nextEngineMode
        realtimeTraceDetails = settings.realtimeTraceDetails
        realtimePersistentSession = settings.realtimePersistentSession
        val mode = when (settings.interactionMode.lowercase()) {
            "hold" -> InteractionMode.HoldToTalk
            "continuous" -> InteractionMode.Continuous
            else -> InteractionMode.TapToTalk
        }
        if (_uiState.value.interactionMode != mode) {
            _uiState.update { it.copy(interactionMode = mode) }
        }
        // Mirror the user-facing settings into the voiceStats snapshot so
        // StatsForNerds → Voice reflects live values.
        _voiceStats.update {
            it.copy(
                vadThresholdMs = settings.silenceThresholdMs,
                interactionMode = settings.interactionMode,
                voiceEngineMode = settings.engineMode,
            )
        }
    }

    /**
     * Standard (no-plugin dashboard) voice rides upstream `POST /api/audio/speak`,
     * which is text-only GLOBAL TTS: `TTSSpeakRequest` has no profile field and
     * `text_to_speech_tool` has no profile scope (web_server.py) — so switching
     * the chat profile does NOT change the spoken voice on standard-only installs.
     * The RELAY voice path IS profile-aware, so this notice is scoped strictly to
     * the EFFECTIVE Standard route + a non-default profile (the [profileName] arg
     * is already null for the default/launch profile). Quiet by design: a Voice
     * diagnostics line, not an interrupting toast.
     */
    private fun maybeNoteStandardVoiceProfileLimitation(profileName: String?) {
        val profile = profileName ?: return
        // Only when standard voice is what a call would actually use — never
        // claim the relay path has this limitation.
        if (voiceAudioClient?.effectiveRoute != VoiceAudioRoute.Standard) return
        DiagnosticsLog.record(
            category = DiagnosticCategory.Voice,
            severity = DiagnosticSeverity.Info,
            title = "Vanilla Hermes voice uses the host's global TTS",
            detail = "Profile \"$profile\" changes the chat agent, but standard " +
                "(no-plugin) voice speaks with the host's global TTS config, not " +
                "the profile's voice. Pair the Relay plugin for profile-aware voice.",
        )
    }

    // ---------------------------------------------------------------------
    // Voice-mode lifecycle
    // ---------------------------------------------------------------------

    fun enterVoiceMode() {
        // Re-probe on each overlay entry so a restarted relay/provider is picked up.
        voiceOutputAvailable = null
        resetBrokeredToolSpeechState()
        resetRealtimeSpeechCoalescer()
        continuousLoopArmed = false
        continuousResumeJob?.cancel()
        continuousResumeJob = null
        realtimeAmplitudeDecayJob?.cancel()
        realtimeAmplitudeDecayJob = null
        realtimeConfirmationControl = null
        clearVoiceHandoffStatus()
        // Fire the chime first so the sound lands with the overlay appearing.
        try { sfxPlayer?.playEnter() } catch (_: Exception) { /* ignore */ }
        _uiState.update {
            it.copy(voiceMode = true, state = VoiceState.Idle, outputAudioActive = false, error = null)
        }
        prewarmRealtimeSession()
    }

    /**
     * Open the persistent Realtime Agent session at voice-mode entry, before
     * the first utterance — pulling the session POST + relay websocket +
     * provider connect out of first-turn latency. Sends no input and touches
     * no turn state; the first utterance rides [submitRealtimeTurn] exactly
     * like a follow-up turn. No-op unless the engine is Realtime Agent with
     * the persistent-session toggle on, or when a session is already open.
     * A failed warm-up is silent — the first turn just opens fresh.
     */
    private fun prewarmRealtimeSession() {
        if (voiceEngineMode != VoiceEngineMode.RealtimeAgent) return
        if (!realtimePersistentSession) return
        val client = voiceClient ?: return
        val chatVm = chatViewModel ?: return
        if (realtimeSessionJob?.isActive == true && realtimeTurnChannel != null) return
        closeRealtimeSession()
        realtimeTurnChannel = kotlinx.coroutines.channels.Channel(
            kotlinx.coroutines.channels.Channel.UNLIMITED,
        )
        Log.i(TAG, "Prewarming persistent Realtime Agent session on voice-mode entry")
        realtimeSessionJob = viewModelScope.launch {
            runRealtimeAgentTurn(
                client = client,
                chatVm = chatVm,
                userText = "",
                inputPcm = ByteArray(0),
                inputSampleRate = 16_000,
                persistentOpen = true,
                prewarm = true,
            )
        }
    }

    private fun cancelRealtimeAgentTurn(reason: String) {
        val control = realtimeAgentControl ?: realtimeConfirmationControl
        if (control != null) {
            val sent = try {
                control.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "Realtime agent cancel failed during $reason: ${e.message}")
                false
            }
            Log.i(TAG, "Realtime agent cancel requested during $reason; sent=$sent")
        }
        realtimeAgentControl = null
        realtimeConfirmationControl = null
    }

    /** Apply [transform] to the background-run chip state, if one is showing. */
    private fun updateBackgroundRun(transform: (BackgroundRunState) -> BackgroundRunState) {
        _uiState.update { state ->
            val run = state.backgroundRun ?: return@update state
            state.copy(backgroundRun = transform(run))
        }
    }

    /**
     * Settle the background-run chip to [BackgroundRunPhase.DONE] and schedule
     * its auto-dismiss. Called when the spoken summary's first audio arrives,
     * or by the delivery watchdog when no audio ever does (visual-only
     * delivery / provider hiccup). Previously the chip was nulled outright at
     * first summary audio — it vanished the instant the waveform came back,
     * reading as the task being lost. Callers own cancelling any pending
     * [deliveringChipClearJob] (the watchdog IS that job and must not cancel
     * itself).
     */
    private fun settleBackgroundRunChip(reason: String) {
        Log.i(
            TAG,
            "Background-run chip settled to DONE ($reason) " +
                "run=${_uiState.value.backgroundRun?.runId ?: "?"}",
        )
        updateBackgroundRun { run ->
            run.copy(
                phase = BackgroundRunPhase.DONE,
                message = "Background task finished.",
                statusLine = null,
            )
        }
        deliveringChipClearJob = viewModelScope.launch {
            delay(DONE_CHIP_LINGER_MS)
            _uiState.update { state ->
                if (state.backgroundRun?.phase == BackgroundRunPhase.DONE) {
                    state.copy(backgroundRun = null)
                } else {
                    state
                }
            }
        }
    }

    /**
     * Cancel the promoted/durable background run from the overlay chip. The
     * relay confirms with `hermes.run.cancelled`, which clears the chip; the
     * message flips immediately so the tap feels acknowledged.
     */
    /**
     * DONE-chip tap: ask the relay to respeak the last delivered background
     * result over relay TTS. No-op unless the chip is settled (a tap on a
     * live chip must not re-trigger anything) or the control is gone.
     */
    fun respeakBackgroundResult() {
        val run = _uiState.value.backgroundRun ?: return
        if (run.phase != BackgroundRunPhase.DONE) return
        val control = realtimeAgentControl ?: return
        val sent = control.respeakLastResult()
        Log.i(TAG, "Background result respeak requested sent=$sent run=${run.runId ?: "?"}")
        if (sent) {
            // Keep the chip up while the respeak plays; its linger job will
            // re-dismiss afterwards.
            deliveringChipClearJob?.cancel()
            updateBackgroundRun { it.copy(message = "Repeating the answer…") }
            deliveringChipClearJob = viewModelScope.launch {
                delay(DONE_CHIP_LINGER_MS)
                _uiState.update { state ->
                    if (state.backgroundRun?.phase == BackgroundRunPhase.DONE) {
                        state.copy(backgroundRun = null)
                    } else {
                        state
                    }
                }
            }
        }
    }

    /**
     * Chat-side sink for voice lifecycle notices (wired from RelayApp to the
     * shared ChatHandler's system-notice bubble). Used when voice mode exits
     * with a background task still running, so the task doesn't silently
     * vanish from every surface.
     */
    var chatNoticeSink: ((String) -> Unit)? = null

    fun cancelBackgroundRun() {
        val run = _uiState.value.backgroundRun
        if (run?.phase == BackgroundRunPhase.DONE) {
            // The task already finished — ✕ on a settled chip is a local
            // dismiss, never a cancel (a late cancel used to overwrite a
            // delivered answer with "Cancelled.").
            Log.i(TAG, "Background run chip dismissed (DONE) run=${run.runId ?: "?"}")
            deliveringChipClearJob?.cancel()
            _uiState.update { it.copy(backgroundRun = null) }
            return
        }
        Log.i(
            TAG,
            "Background run cancel requested from chip " +
                "run=${run?.runId ?: "?"}",
        )
        updateBackgroundRun { it.copy(message = "Cancelling…", statusLine = null) }
        cancelRealtimeAgentTurn("background_run_chip")
    }

    fun exitVoiceMode() {
        // Idempotence guard — added 2026-04-21 after logcat showed the voice-
        // exit chime playing on every Add-connection tap.
        //
        // Why: `ConnectionSwitchCoordinator.switchConnection` fires the
        // voice-stop callback unconditionally on step 3, which is correct for
        // "user switches between two paired connections while voice is
        // active." But `beginAddConnection` also goes through `switchConnection`
        // (to bind the placeholder's auth store before the pair wizard runs),
        // and in that path voice mode is almost never active — the user just
        // tapped an FAB. Playing `playExit()` there is an audible bug.
        //
        // The early return is safe because every line of teardown below has
        // its own try/catch + null-guard; running it when voiceMode is false
        // would be a no-op anyway (recorder/player/timers are all already
        // null). The meaningful difference is that `sfxPlayer?.playExit()`
        // unconditionally enqueues a playback regardless of state.
        //
        // If a caller ever needs unconditional teardown (e.g. onCleared), they
        // can bypass this guard by calling the primitives directly — or we
        // add a separate `forceExitVoiceMode()` when that need materializes.
        if (!_uiState.value.voiceMode) return

        // Chime BEFORE teardown — AudioTrack release would cut it off otherwise.
        try { sfxPlayer?.playExit() } catch (_: Exception) { /* ignore */ }
        // Exit = detach, chip ✕ = cancel. A promoted/durable run stays alive
        // server-side; the relay delivers its result on the next voice session
        // or as a proactive notification. Cancelling here both killed the task
        // and let the relay's run-cancelled confirm overwrite an already-
        // delivered answer with "Cancelled." in the chat transcript.
        val detachedRun = _uiState.value.backgroundRun
        if (detachedRun != null) {
            Log.i(
                TAG,
                "Exiting voice mode with background run=${detachedRun.runId ?: "?"} " +
                    "active — detaching, not cancelling",
            )
            // C2: the chip dies with the overlay, but the task doesn't — leave
            // a breadcrumb in chat so the running work stays visible somewhere.
            // Only for runs that haven't already settled.
            if (detachedRun.phase != BackgroundRunPhase.DONE) {
                val queuedSuffix = if (detachedRun.queuedCount > 0) {
                    " (+${detachedRun.queuedCount} queued)"
                } else {
                    ""
                }
                chatNoticeSink?.invoke(
                    "🕐 Background voice task still running$queuedSuffix — " +
                        "Hermes will report back when it finishes.",
                )
            }
        } else {
            cancelRealtimeAgentTurn("exit voice mode")
        }
        closeRealtimeSession()
        // B4: tear down the barge-in listener + timers before we kill the
        // player so AEC doesn't try to track a released audio session.
        stopBargeInListener()
        duckingWatchdog?.cancel(); duckingWatchdog = null
        resumeWatchdog?.cancel(); resumeWatchdog = null
        handoffStatusClearJob?.cancel(); handoffStatusClearJob = null
        clearSpokenChunksState()
        // Tear down anything in flight.
        try {
            recorder?.cancel()
        } catch (_: Exception) { /* ignore */ }
        listeningStartedAtMs = 0L
        try {
            player?.stop()
        } catch (_: Exception) { /* ignore */ }
        try {
            realtimePcmPlayer?.stop()
        } catch (_: Exception) { /* ignore */ }
        currentTurnJob?.cancel()
        currentTurnJob = null
        streamObserverJob?.cancel()
        streamObserverJob = null
        continuousLoopArmed = false
        continuousResumeJob?.cancel()
        continuousResumeJob = null
        realtimeAmplitudeDecayJob?.cancel()
        realtimeAmplitudeDecayJob = null
        realtimeConfirmationControl = null
        idleFlushJob?.cancel()
        idleFlushJob = null
        // Drain any queued sentences so the consumer doesn't re-play the
        // interrupted turn when it's restarted below.
        while (true) {
            val r = ttsQueue.tryReceive()
            if (r.isFailure || r.isClosed) break
            pendingInTtsQueue.decrementAndGet()
        }
        drainRealtimeTtsQueue()
        // V4 pipeline teardown (see interruptSpeaking for rationale).
        ttsConsumerJob?.cancel()
        ttsConsumerJob = null
        realtimeTtsConsumerJob?.cancel()
        realtimeTtsConsumerJob = null
        deletePendingSynthFiles()
        startTtsConsumer()
        startRealtimeTtsConsumer()
        sentenceBuffer = StringBuilder()
        resetRealtimeSpeechCoalescer()
        pendingRawDelta = StringBuilder()
        lastObservedMessageId = null
        lastObservedContentLength = 0
        streamComplete = false
        currentTurnPcm = ByteArray(0)
        resetBrokeredToolSpeechState()
        resetTtsTurnStats()

        // Also abort any destructive countdown — leaving voice mode with a
        // queued SMS would execute the action after the overlay closed,
        // which is exactly the surprise we're trying to prevent.
        voiceBridgeIntentHandler?.cancelPending()
        _uiState.update {
            it.copy(
                voiceMode = false,
                state = VoiceState.Idle,
                amplitude = 0f,
                outputAudioActive = false,
                transcribedText = null,
                responseText = "",
                error = null,
                destructiveCountdown = null,
                handoffStatus = null,
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
        continuousResumeJob?.cancel()
        continuousResumeJob = null
        continuousLoopArmed = _uiState.value.interactionMode == InteractionMode.Continuous
        lastRealtimeAudioDeltaAtMs = 0L
        realtimeAmplitudeDecayJob?.cancel()
        realtimeAmplitudeDecayJob = null

        // B4: user manually started a turn → tear down the barge-in
        // listener and cancel any pending resume. Normal "tap mic to
        // talk" path also lands here, so we always leave Speaking cleanly.
        stopBargeInListener()
        resumeWatchdog?.cancel(); resumeWatchdog = null
        lastInterruptedAtChunkIndex = null
        clearSpokenChunksState()
        clearVoiceHandoffStatus()

        // Stop any TTS playback when the user starts talking.
        try { player?.stop() } catch (_: Exception) { /* ignore */ }
        try { realtimePcmPlayer?.stop() } catch (_: Exception) { /* ignore */ }

        try {
            rec.startRecording()
            listeningStartedAtMs = System.currentTimeMillis()
            _uiState.update {
                it.copy(
                    state = VoiceState.Listening,
                    outputAudioActive = false,
                    error = null,
                    responseText = "",
                    // v0.4.1 — fresh turn, drop any stale JIT permission chip
                    // from the previous dispatch.
                    permissionDeniedCallout = null,
                    handoffStatus = null,
                )
            }
            // 2026-04-18: arm silence-based auto-stop. HoldToTalk skips
            // this — the physical release is the authoritative stop.
            if (_uiState.value.interactionMode != InteractionMode.HoldToTalk) {
                startSilenceWatchdog()
            }
        } catch (e: Exception) {
            listeningStartedAtMs = 0L
            Log.e(TAG, "startListening failed: ${e.message}")
            // SecurityException path becomes "Permission needed" via the classifier.
            surfaceError(e, context = "record")
        }
    }

    fun stopListening() {
        val rec = recorder ?: return
        if (_uiState.value.state != VoiceState.Listening) return

        // 2026-04-18: cancel the silence watchdog first so it can't race
        // a concurrent stop from another path (barge-in, overlay X button).
        silenceWatchdogJob?.cancel()
        silenceWatchdogJob = null

        val captureDurationMs = listeningDurationMs()
        if (shouldDiscardVoiceCaptureBeforeStop(captureDurationMs)) {
            cancelListeningWithoutProcessing(
                title = "Voice capture ignored",
                detail = "Released before speech capture settled",
            )
            return
        }

        val file = try {
            rec.stopRecording()
        } catch (e: Exception) {
            listeningStartedAtMs = 0L
            Log.e(TAG, "stopListening failed: ${e.message}")
            surfaceError(e, context = "record")
            return
        }
        val inputPcm = rec.lastPcmBytes()
        val inputSampleRate = rec.sampleRate
        listeningStartedAtMs = 0L

        if (shouldDiscardVoiceCapture(captureDurationMs, inputPcm.size)) {
            try { file.delete() } catch (_: Exception) { /* ignore */ }
            DiagnosticsLog.record(
                category = DiagnosticCategory.Voice,
                severity = DiagnosticSeverity.Info,
                title = "Voice capture ignored",
                detail = "duration=${captureDurationMs}ms pcm=${inputPcm.size} bytes",
            )
            _uiState.update {
                it.copy(state = VoiceState.Idle, amplitude = 0f, outputAudioActive = false)
            }
            return
        }

        currentTurnJob?.cancel()
        currentTurnJob = viewModelScope.launch {
            processVoiceInput(file, inputPcm, inputSampleRate)
        }
    }

    private fun listeningDurationMs(): Long {
        val startedAt = listeningStartedAtMs
        return if (startedAt > 0L) {
            (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        } else {
            Long.MAX_VALUE
        }
    }

    private fun shouldDiscardVoiceCaptureBeforeStop(durationMs: Long): Boolean =
        durationMs < MIN_VOICE_CAPTURE_DURATION_MS

    private fun cancelListeningWithoutProcessing(title: String, detail: String? = null) {
        silenceWatchdogJob?.cancel()
        silenceWatchdogJob = null
        listeningStartedAtMs = 0L
        try { recorder?.cancel() } catch (_: Exception) { /* ignore */ }
        DiagnosticsLog.record(
            category = DiagnosticCategory.Voice,
            severity = DiagnosticSeverity.Info,
            title = title,
            detail = detail,
        )
        _uiState.update {
            it.copy(state = VoiceState.Idle, amplitude = 0f, outputAudioActive = false)
        }
    }

    /**
     * Pause the Continuous-mode loop without changing the selected mode or
     * leaving voice mode. The next mic tap re-arms Auto and starts a fresh
     * listening turn; until then, idle queue-drain callbacks are ignored.
     */
    fun pauseContinuousMode() {
        continuousLoopArmed = false
        continuousResumeJob?.cancel()
        continuousResumeJob = null
        realtimeAmplitudeDecayJob?.cancel()
        realtimeAmplitudeDecayJob = null
        silenceWatchdogJob?.cancel()
        silenceWatchdogJob = null

        when (_uiState.value.state) {
            VoiceState.Listening -> {
                try { recorder?.cancel() } catch (_: Exception) { /* ignore */ }
                listeningStartedAtMs = 0L
                _uiState.update { it.copy(state = VoiceState.Idle, amplitude = 0f, outputAudioActive = false) }
            }
            VoiceState.Speaking, VoiceState.Transcribing, VoiceState.Thinking -> {
                interruptSpeaking()
            }
            VoiceState.Idle, VoiceState.Error -> {
                _uiState.update { it.copy(state = VoiceState.Idle, amplitude = 0f, outputAudioActive = false) }
            }
        }
    }

    /**
     * Silence-based auto-stop watchdog for the current Listening turn.
     *
     * Reads the user's [VoiceSettings.silenceThresholdMs] snapshot at the
     * start of the turn (changes mid-turn take effect next turn) and polls
     * [VoiceRecorder.amplitude] every [SILENCE_WATCHDOG_POLL_MS]. A frame
     * is "silent" when the perceptual amplitude is below
     * [RESUME_SILENCE_THRESHOLD] — the same floor the post-barge-in resume
     * watchdog uses, already tuned to reject mic hiss and room tone on
     * Bailey's devices while catching whispered speech.
     *
     * Three timeouts, aligned to hermes-desktop's voice_mode defaults so the
     * standard-path loop feels like the official desktop:
     *  - **End-of-speech** ([VoiceSettings.silenceThresholdMs], default 1250 ms,
     *    desktop `silenceMs`): after speech is heard, this much silence
     *    auto-stops and transcribes the turn.
     *  - **Idle/no-speech** ([IDLE_NO_SPEECH_MS] = 12 s, desktop `idleSilenceMs`):
     *    a turn that never hears speech is cancelled WITHOUT transcribing.
     *  - **Hard cap** ([MAX_LISTEN_TURN_MS] = 60 s): any turn running this long
     *    is stopped and transcribed.
     *
     * Grace window: the end-of-speech timeout does NOT fire until we've seen at
     * least one above-floor frame. The amplitude floor [RESUME_SILENCE_THRESHOLD]
     * (0.08) is the analog of desktop's `silenceLevel` (0.075).
     *
     * No-op when [voicePreferences] is unwired (pre-fix test call sites)
     * or when the threshold is <= 0 (reserved for a future "Off" option).
     */
    private fun startSilenceWatchdog() {
        val prefs = voicePreferences ?: return
        silenceWatchdogJob?.cancel()
        silenceWatchdogJob = viewModelScope.launch {
            val thresholdMs = prefs.settings.firstOrNull()?.silenceThresholdMs
                ?: VoicePreferencesRepository.DEFAULT_SILENCE_THRESHOLD_MS
            if (thresholdMs <= 0L) return@launch

            val rec = recorder ?: return@launch
            var hasSpoken = false
            var lastVoiceMs = System.currentTimeMillis()
            val turnStartedMs = System.currentTimeMillis()

            while (isActive && _uiState.value.state == VoiceState.Listening) {
                val amp = rec.amplitude.value
                val now = System.currentTimeMillis()
                when {
                    amp > RESUME_SILENCE_THRESHOLD -> {
                        hasSpoken = true
                        lastVoiceMs = now
                    }
                    hasSpoken && (now - lastVoiceMs) >= thresholdMs -> {
                        Log.d(TAG, "silence watchdog: ${thresholdMs}ms of silence after speech — auto-stop")
                        // stopListening() is state-guarded (early-returns if
                        // already out of Listening), so a concurrent manual
                        // stop between here and dispatch is a safe no-op.
                        stopListening()
                        return@launch
                    }
                    !hasSpoken && (now - turnStartedMs) >= IDLE_NO_SPEECH_MS -> {
                        Log.d(TAG, "silence watchdog: ${IDLE_NO_SPEECH_MS}ms with no speech — closing idle turn")
                        cancelListeningWithoutProcessing(
                            title = "No speech detected",
                            detail = "No speech within ${IDLE_NO_SPEECH_MS / 1000}s",
                        )
                        return@launch
                    }
                }
                if ((System.currentTimeMillis() - turnStartedMs) >= MAX_LISTEN_TURN_MS) {
                    Log.d(TAG, "silence watchdog: ${MAX_LISTEN_TURN_MS}ms hard turn cap — auto-stop")
                    stopListening()
                    return@launch
                }
                delay(SILENCE_WATCHDOG_POLL_MS)
            }
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
        Log.i(
            TAG,
            "Interrupting speech pipeline",
        )
        // Stop = "stop talking", not "kill my task": with a background run
        // active, silence the audio pipeline below but leave the run alive —
        // the chip's ✕ is the explicit cancel affordance.
        if (_uiState.value.backgroundRun != null) {
            Log.i(TAG, "Interrupt with background run active — stopping audio only")
        } else {
            cancelRealtimeAgentTurn("interrupt")
        }
        // Drop realtime audio deltas still in flight on the open socket so a
        // stopped turn's tail can't re-create the player and resume playback.
        realtimeAudioSuppressed = true
        // B4: tear down the barge-in listener immediately so we don't
        // double-trigger on the ducking watchdog or emit another
        // bargeInDetected while the resume watchdog is deliberating.
        // stopBargeInListener is null-safe.
        stopBargeInListener()
        // 2026-04-18: the silence watchdog only runs during Listening, but
        // cancel defensively so a stale job from the prior turn can't
        // fire stopListening() after we've already returned to Idle.
        silenceWatchdogJob?.cancel()
        silenceWatchdogJob = null
        // Drain queued sentences without closing the channel.
        while (true) {
            val r = ttsQueue.tryReceive()
            if (r.isFailure || r.isClosed) break
            pendingInTtsQueue.decrementAndGet()
        }
        drainRealtimeTtsQueue()
        try { player?.stop() } catch (_: Exception) { /* ignore */ }
        try { realtimePcmPlayer?.stop() } catch (_: Exception) { /* ignore */ }
        // Stop the upstream SSE stream so the agent quits generating tokens.
        try { chatViewModel?.cancelStream() } catch (_: Exception) { /* ignore */ }
        streamObserverJob?.cancel()
        streamObserverJob = null
        currentTurnJob?.cancel()
        currentTurnJob = null
        continuousLoopArmed = false
        continuousResumeJob?.cancel()
        continuousResumeJob = null
        realtimeAmplitudeDecayJob?.cancel()
        realtimeAmplitudeDecayJob = null
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
        realtimeTtsConsumerJob?.cancel()
        realtimeTtsConsumerJob = null
        deletePendingSynthFiles()
        startTtsConsumer()
        startRealtimeTtsConsumer()
        // Reset per-turn buffering + tracking so the next turn starts clean.
        sentenceBuffer = StringBuilder()
        resetRealtimeSpeechCoalescer()
        pendingRawDelta = StringBuilder()
        lastObservedMessageId = null
        lastObservedContentLength = 0
        streamComplete = false
        currentTurnPcm = ByteArray(0)
        resetBrokeredToolSpeechState()
        resetTtsTurnStats()
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
            it.copy(
                state = VoiceState.Idle,
                amplitude = 0f,
                outputAudioActive = false,
                hermesConfirmation = null,
            )
        }
    }

    fun clearError() {
        // Clear the message and, if we were parked in Error, return to Idle so a
        // dismissed (or retried) failure lands in a usable state rather than a
        // banner-less Error limbo.
        _uiState.update {
            it.copy(
                error = null,
                state = if (it.state == VoiceState.Error) VoiceState.Idle else it.state,
            )
        }
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
            _uiState.update {
                it.copy(state = VoiceState.Idle, outputAudioActive = false, responseText = "Cancelled.")
            }
        }
    }
    // === END PHASE3-voice-intents ===

    fun answerHermesConfirmation(answer: String) {
        val confirmation = _uiState.value.hermesConfirmation ?: return
        val sent = realtimeConfirmationControl?.confirm(confirmation.confirmationId, answer) == true
        _uiState.update {
            it.copy(
                hermesConfirmation = null,
                responseText = if (sent) "Sent confirmation." else "Could not send confirmation.",
                state = if (sent) VoiceState.Thinking else VoiceState.Error,
                error = if (sent) null else "Realtime confirmation channel is not available",
            )
        }
    }

    // V2b EXTENSION --------------------------------------------------------
    // Fire a one-shot voice-output playback to verify the active profile's
    // voice pipeline from the settings screen without entering full voice
    // mode. Falls back to legacy synth+playback when the streaming output
    // route is unavailable. Runs outside the turn state machine so it
    // doesn't disturb uiState.
    //
    // Three toasts so the user knows what's happening: "Testing voice…" on
    // trigger, "Voice test successful" on completion, "Voice test failed" on
    // any error. The trigger toast is held in [triggerToast] so it can be
    // cancelled the moment the result toast fires — without that the two
    // would briefly overlap on screen. viewModelScope.launch defaults to
    // Main.immediate so Toast.show() is safe inline without a dispatcher
    // switch.
    fun testVoice(
        sample: String = "Hello, this is Hermes. Voice mode is working.",
        onResult: (Result<Unit>) -> Unit = {},
    ) {
        val app = getApplication<Application>()
        val audioClient = voiceAudioClient
        val relayClient = voiceClient
        val p = player
        if (audioClient == null || p == null) {
            onResult(Result.failure(IllegalStateException("Voice pipeline not initialized")))
            Toast.makeText(app, "Voice test failed: pipeline not initialized", Toast.LENGTH_SHORT).show()
            setError("Voice pipeline not initialized")
            return
        }
        val triggerToast = Toast.makeText(app, "Testing voice…", Toast.LENGTH_SHORT).also { it.show() }
        viewModelScope.launch {
            val profileAwareResult = if (audioClient.route == VoiceAudioRoute.Relay && relayClient != null) {
                testVoiceViaVoiceOutput(relayClient, sample)
            } else {
                null
            }
            val result = if (profileAwareResult != null) {
                if (profileAwareResult.isSuccess) {
                    triggerToast.cancel()
                    onResult(Result.success(Unit))
                    Toast.makeText(app, "Voice test successful", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                Log.w(TAG, "profile-aware voice test failed; falling back to legacy synthesize: ${profileAwareResult.exceptionOrNull()?.message}")
                audioClient.synthesize(sample)
            } else {
                audioClient.synthesize(sample)
            }
            if (result.isFailure) {
                triggerToast.cancel()
                val msg = result.exceptionOrNull()?.message ?: "synthesize failed"
                onResult(Result.failure(result.exceptionOrNull() ?: IllegalStateException(msg)))
                Toast.makeText(app, "Voice test failed: $msg", Toast.LENGTH_LONG).show()
                surfaceError(result.exceptionOrNull(), context = "synthesize")
                return@launch
            }
            val file = result.getOrNull()
            if (file == null) {
                triggerToast.cancel()
                onResult(Result.failure(IllegalStateException("No audio returned")))
                Toast.makeText(app, "Voice test failed: no audio returned", Toast.LENGTH_LONG).show()
                return@launch
            }
            trackTtsFile(file)
            try {
                p.play(file)
                p.awaitCompletion()
                triggerToast.cancel()
                onResult(Result.success(Unit))
                Toast.makeText(app, "Voice test successful", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.w(TAG, "test playback failed: ${e.message}")
                triggerToast.cancel()
                onResult(Result.failure(e))
                Toast.makeText(app, "Voice test failed: ${e.message ?: "playback error"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun testRealtimeAgent(
        sample: String = "Say a short confirmation that Hermes Realtime Agent is working.",
        onResult: (Result<Unit>) -> Unit = {},
    ) {
        val app = getApplication<Application>()
        val client = voiceClient
        val pcmPlayer = realtimePcmPlayer
        if (client == null || pcmPlayer == null) {
            onResult(Result.failure(IllegalStateException("Voice pipeline not initialized")))
            Toast.makeText(app, "Realtime test failed: pipeline not initialized", Toast.LENGTH_SHORT).show()
            setError("Voice pipeline not initialized")
            return
        }
        val triggerToast = Toast.makeText(app, "Testing Realtime Agent...", Toast.LENGTH_SHORT)
            .also { it.show() }
        viewModelScope.launch {
            DiagnosticsLog.record(
                category = DiagnosticCategory.Voice,
                severity = DiagnosticSeverity.Info,
                title = "Realtime Agent test started",
                detail = "Opening provider-native settings test session",
            )
            val audioBytes = AtomicInteger(0)
            pcmPlayer.stop()
            val result = client.runRealtimeAgent(
                prompt = sample,
                inputPcm = ByteArray(0),
                inputSampleRate = 16_000,
                chatSessionId = chatViewModel?.currentSessionId?.value,
            ) { event, _ ->
                if (!event.isAudioDelta) return@runRealtimeAgent
                val encoded = event.audioBase64 ?: return@runRealtimeAgent
                val audio = try {
                    Base64.getDecoder().decode(encoded)
                } catch (_: Exception) {
                    return@runRealtimeAgent
                }
                if (audio.isEmpty()) return@runRealtimeAgent
                val rate = event.sampleRate ?: 24_000
                audioBytes.addAndGet(audio.size)
                pcmPlayer.write(audio, rate)
            }
            triggerToast.cancel()
            if (result.isFailure) {
                pcmPlayer.stop()
                val msg = result.exceptionOrNull()?.message ?: "realtime agent failed"
                onResult(Result.failure(result.exceptionOrNull() ?: IllegalStateException(msg)))
                Toast.makeText(app, "Realtime test failed: $msg", Toast.LENGTH_LONG).show()
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Voice,
                    severity = DiagnosticSeverity.Error,
                    title = "Realtime Agent test failed",
                    detail = msg,
                )
                surfaceError(result.exceptionOrNull(), context = "voice_config")
                return@launch
            }
            if (audioBytes.get() <= 0) {
                pcmPlayer.stop()
                onResult(Result.failure(IllegalStateException("Provider returned no audio")))
                Toast.makeText(app, "Realtime test failed: no audio returned", Toast.LENGTH_LONG).show()
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Voice,
                    severity = DiagnosticSeverity.Error,
                    title = "Realtime Agent test failed",
                    detail = "Provider returned no audio",
                )
                return@launch
            }
            val drainMs = pcmPlayer.flushBufferedPlayback()
                .coerceIn(250L, 4_500L)
            delay(drainMs)
            pcmPlayer.stop()
            onResult(Result.success(Unit))
            Toast.makeText(app, "Realtime test successful", Toast.LENGTH_SHORT).show()
            DiagnosticsLog.record(
                category = DiagnosticCategory.Voice,
                severity = DiagnosticSeverity.Info,
                title = "Realtime Agent test complete",
                detail = "${audioBytes.get()} bytes streamed",
            )
        }
    }

    private suspend fun testVoiceViaVoiceOutput(
        client: RelayVoiceClient,
        sample: String,
    ): Result<Unit>? {
        val pcmPlayer = realtimePcmPlayer ?: return null
        val config = client.getVoiceOutputConfig().getOrNull()
        if (config?.enabled != true) return null

        val audioBytes = AtomicInteger(0)
        return try {
            val result = client.runVoiceOutput(
                text = sample,
                renderMode = "verbatim",
            ) { event ->
                if (!event.isAudioDelta) return@runVoiceOutput
                val encoded = event.audioBase64 ?: return@runVoiceOutput
                val audio = try {
                    Base64.getDecoder().decode(encoded)
                } catch (_: Exception) {
                    return@runVoiceOutput
                }
                if (audio.isEmpty()) return@runVoiceOutput
                val rate = event.sampleRate ?: 24_000
                audioBytes.addAndGet(audio.size)
                pcmPlayer.write(audio, rate)
            }
            result.fold(
                onSuccess = {
                    if (audioBytes.get() > 0) {
                        val drainMs = pcmPlayer.flushBufferedPlayback()
                            .coerceIn(250L, 4_500L)
                        delay(drainMs)
                        Result.success(Unit)
                    } else {
                        Result.failure(IllegalStateException("voice output returned no audio"))
                    }
                },
                onFailure = { Result.failure(it) },
            )
        } finally {
            pcmPlayer.stop()
        }
    }

    // ---------------------------------------------------------------------
    // Voice turn processing
    // ---------------------------------------------------------------------

    private suspend fun processVoiceInput(
        audioFile: File,
        inputPcm: ByteArray,
        inputSampleRate: Int,
    ) {
        val relayClient = voiceClient
        val audioClient = voiceAudioClient
        val chatVm = chatViewModel
        if (audioClient == null || chatVm == null) {
            setError("Voice pipeline not initialized")
            return
        }
        currentTurnPcm = inputPcm
        currentTurnPcmSampleRate = inputSampleRate
        resetBrokeredToolSpeechState()
        resetRealtimeSpeechCoalescer()
        resetTtsTurnStats()
        val engineModeForTurn = voiceEngineMode
        Log.i(
            TAG,
            "Processing voice input engine=${engineModeForTurn.storageValue} " +
                "pcmBytes=${inputPcm.size} sampleRate=$inputSampleRate",
        )

        if (engineModeForTurn == VoiceEngineMode.RealtimeAgent) {
            val client = relayClient
            if (client == null) {
                setError("Realtime Agent needs a Relay voice route")
                return
            }
            Log.i(TAG, "Voice input routed to Realtime Agent")
            DiagnosticsLog.record(
                category = DiagnosticCategory.Voice,
                severity = DiagnosticSeverity.Info,
                title = "Realtime voice turn started",
                detail = "Checking relay before opening provider session",
            )
            if (!runVoiceRelayPreflight("Realtime Agent")) return
            _uiState.update {
                it.copy(
                    state = VoiceState.Thinking,
                    outputAudioActive = false,
                    transcribedText = null,
                    responseText = "",
                )
            }
            if (realtimePersistentSession) {
                // Persistent conversation: one socket across turns. Open lazily on
                // the first turn (the long-lived call runs in realtimeSessionJob),
                // then feed further utterances on the channel. Fallback to one-shot
                // if the toggle is off (Voice Settings).
                if (realtimeSessionJob?.isActive == true && realtimeTurnChannel != null) {
                    submitRealtimeTurn(chatVm, inputPcm, inputSampleRate)
                } else {
                    closeRealtimeSession()
                    realtimeTurnChannel = kotlinx.coroutines.channels.Channel(
                        kotlinx.coroutines.channels.Channel.UNLIMITED,
                    )
                    realtimeSessionJob = viewModelScope.launch {
                        runRealtimeAgentTurn(
                            client = client,
                            chatVm = chatVm,
                            userText = "",
                            inputPcm = inputPcm,
                            inputSampleRate = inputSampleRate,
                            persistentOpen = true,
                        )
                    }
                }
                return
            }
            runRealtimeAgentTurn(
                client = client,
                chatVm = chatVm,
                userText = "",
                inputPcm = inputPcm,
                inputSampleRate = inputSampleRate,
            )
            return
        }

        Log.i(TAG, "Voice input routed to Hermes voice output")
        DiagnosticsLog.record(
            category = DiagnosticCategory.Voice,
            severity = DiagnosticSeverity.Info,
            title = "Voice turn started",
            detail = "Hermes voice output (${audioClient.route.storageValue})",
        )

        // Transcribe
        _uiState.update { it.copy(state = VoiceState.Transcribing, outputAudioActive = false) }
        val sttStartedAtMs = System.currentTimeMillis()
        val audioBytes = try { audioFile.length() } catch (_: Exception) { 0L }
        val transcribeResult = audioClient.transcribe(audioFile)
        val sttLatencyMs = System.currentTimeMillis() - sttStartedAtMs
        if (transcribeResult.isFailure) {
            val err = transcribeResult.exceptionOrNull()
            Log.w(TAG, "transcribe failed: ${err?.message}")
            DiagnosticsLog.record(
                category = DiagnosticCategory.Voice,
                severity = DiagnosticSeverity.Error,
                title = "Voice transcription failed",
                detail = err?.message ?: "Unknown error",
            )
            surfaceError(err, context = "transcribe")
            return
        }
        val userText = transcribeResult.getOrNull().orEmpty()
        if (userText.isBlank()) {
            DiagnosticsLog.record(
                category = DiagnosticCategory.Voice,
                severity = DiagnosticSeverity.Warning,
                title = "No speech detected",
            )
            setError("No speech detected")
            return
        }
        // Record successful STT in the voiceStats snapshot.
        _voiceStats.update { s ->
            s.copy(
                lastTranscript = userText,
                lastSttLatencyMs = sttLatencyMs,
                sttBytesUploaded = s.sttBytesUploaded + audioBytes,
                sttCallCount = s.sttCallCount + 1,
            )
        }

        _uiState.update {
            it.copy(
                state = VoiceState.Thinking,
                outputAudioActive = false,
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
            // Speak a short "Cancelled." and return to idle. Status lines
            // bypass normal assistant coalescing so the user hears them
            // immediately.
            _uiState.update {
                it.copy(
                    state = VoiceState.Speaking,
                    outputAudioActive = false,
                    responseText = "Cancelled.",
                    destructiveCountdown = null,
                )
            }
            enqueueSentenceForTts("Cancelled.", immediate = true)
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
                        // Voice-intent confirmations are status speech, not
                        // assistant prose, so they bypass balanced coalescing.
                        _uiState.update {
                            it.copy(
                                state = VoiceState.Speaking,
                                outputAudioActive = false,
                                responseText = confirmation,
                            )
                        }
                        enqueueSentenceForTts(confirmation, immediate = true)
                    } else {
                        _uiState.update {
                            it.copy(
                                state = VoiceState.Idle,
                                outputAudioActive = false,
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
                outputAudioActive = false,
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
        chatVm.sendVoiceMessage(userText, STABLE_VOICE_INTERFACE_CONTEXT)
    }

    private suspend fun runVoiceRelayPreflight(engineLabel: String): Boolean {
        val preflight = voiceRelayPreflight ?: return true
        val result = preflight()
        if (result.isSuccess) return true

        val raw = result.exceptionOrNull()?.message ?: "Relay is not responding"
        val message = when {
            raw.contains("timeout", ignoreCase = true) ||
                raw.contains("not responding", ignoreCase = true) ->
                "Relay is not responding. Check Connections."
            raw.contains("not configured", ignoreCase = true) ->
                "Relay is not configured. Check Connections."
            else -> raw
        }
        DiagnosticsLog.record(
            category = DiagnosticCategory.Voice,
            severity = DiagnosticSeverity.Error,
            title = "Voice relay check failed",
            detail = "$engineLabel: $message",
        )
        setError(message)
        return false
    }

    private suspend fun runRealtimeAgentTurn(
        client: RelayVoiceClient,
        chatVm: ChatViewModel,
        userText: String,
        inputPcm: ByteArray,
        inputSampleRate: Int,
        persistentOpen: Boolean = false,
        /**
         * Voice-mode-entry warm-up: open the persistent session/socket with no
         * first turn. All turn-scoped side effects (Thinking state, the chat
         * assistant placeholder, the turn-active flag) are skipped — the first
         * real utterance rides [submitRealtimeTurn] like any follow-up turn.
         */
        prewarm: Boolean = false,
    ) {
        if (!prewarm) providerRealtimeAgentTurnActive.set(true)
        // New turn requested → allow this response's audio through again.
        realtimeAudioSuppressed = false
        streamObserverJob?.cancel()
        streamObserverJob = null
        drainQueuedLocalTts()
        try { player?.stop() } catch (_: Exception) { /* ignore */ }

        sentenceBuffer = StringBuilder()
        pendingRawDelta = StringBuilder()
        lastObservedMessageId = null
        lastObservedContentLength = 0
        streamComplete = false
        idleFlushJob?.cancel()
        idleFlushJob = null
        resumeWatchdog?.cancel(); resumeWatchdog = null
        firstFrameWatchdogJob?.cancel(); firstFrameWatchdogJob = null
        clearSpokenChunksState()

        if (!prewarm) {
            _uiState.update {
                it.copy(
                    state = VoiceState.Thinking,
                    outputAudioActive = false,
                    transcribedText = userText,
                    responseText = "",
                )
            }
        }

        // Per-turn event state is hoisted to fields so one session-lived callback
        // serves every turn in persistent mode; reset them for this turn.
        audioSeen.set(false)
        audioBytes.set(0)
        bargeInStarted.set(false)
        lastRealtimeAudioEventId.set(0L)
        responseText = StringBuilder()
        inputTranscript = StringBuilder()
        spokenStatusKeys.clear()
        lastSpokenStatusAtMs = 0L
        spokenStatusCount = 0
        rtUserText = userText
        rtConversationContext = chatVm.realtimeAgentContextMessages()
        if (!prewarm) {
            rtAssistantMessageId = chatVm.startRealtimeAgentTurn(
                userText = userText,
                chatSessionId = chatVm.currentSessionId.value,
            )
        }
        val conversationContext = rtConversationContext
        val pcmPlayer = realtimePcmPlayer

        fun emitStatus(
            key: String,
            line: String,
            speak: Boolean = false,
            speakEvenAfterProviderAudio: Boolean = false,
        ) {
            if (!spokenStatusKeys.add(key)) return
            DiagnosticsLog.record(
                category = DiagnosticCategory.Voice,
                severity = DiagnosticSeverity.Info,
                title = line.trimEnd('.'),
                detail = "Realtime Agent",
            )
            // A promoted/durable background run owns the chip; the global
            // voice state must stay conversational (Idle/Listening) so the
            // mic keeps working — flipping Thinking on every status event is
            // what wedged the floor during background runs. Optional spoken
            // narration below is unaffected.
            if (_uiState.value.backgroundRun == null) {
                _uiState.update {
                    it.copy(
                        state = VoiceState.Thinking,
                        outputAudioActive = false,
                        responseText = line,
                    )
                }
            }
            if (speak && (!audioSeen.get() || speakEvenAfterProviderAudio)) {
                // W3: per-turn throttle independent of the per-key dedupe above.
                // Suppress the TTS enqueue (UI state + diagnostics already
                // applied) when spoken status is too frequent or has hit the
                // per-turn cap, so long / tool-heavy runs don't over-narrate.
                val now = System.currentTimeMillis()
                if (!shouldSpeakStatusNow(
                        now = now,
                        lastSpokenAtMs = lastSpokenStatusAtMs,
                        count = spokenStatusCount,
                        gapMs = MIN_SPOKEN_STATUS_GAP_MS,
                        maxCount = MAX_SPOKEN_STATUS_PER_TURN,
                    )
                ) {
                    Log.i(
                        TAG,
                        "Realtime status TTS suppressed (throttle) key=$key " +
                            "count=$spokenStatusCount sinceLastMs=${
                                if (lastSpokenStatusAtMs > 0L) now - lastSpokenStatusAtMs else -1L
                            } line=$line",
                    )
                    return
                }
                lastSpokenStatusAtMs = now
                spokenStatusCount += 1
                val remainingProviderAudioMs = if (speakEvenAfterProviderAudio && audioSeen.get()) {
                    300L
                } else {
                    0L
                }
                Log.i(
                    TAG,
                    "Realtime status TTS queued key=$key speak=$speak " +
                        "count=$spokenStatusCount " +
                        "afterProviderAudio=${audioSeen.get()} delayMs=$remainingProviderAudioMs line=$line",
                )
                if (remainingProviderAudioMs > 0L) {
                    viewModelScope.launch {
                        delay(remainingProviderAudioMs)
                        if (!providerRealtimeAgentTurnActive.get()) return@launch
                        Log.i(TAG, "Realtime status TTS dispatch key=$key delayed=true line=$line")
                        enqueueSentenceForTts(
                            sentence = line,
                            immediate = true,
                            allowDuringProviderRealtime = true,
                        )
                    }
                } else {
                    enqueueSentenceForTts(
                        sentence = line,
                        immediate = true,
                        allowDuringProviderRealtime = true,
                    )
                }
            }
        }

        val result = try {
            client.runRealtimeAgent(
                prompt = userText,
                inputPcm = inputPcm,
                inputSampleRate = inputSampleRate,
                chatSessionId = chatVm.currentSessionId.value,
                conversationContext = conversationContext,
                onHandoff = ::recordVoiceHandoff,
                turnInputs = if (persistentOpen) realtimeTurnChannel else null,
                onTurnComplete = { summary -> onRealtimeTurnComplete(summary) },
                prewarm = prewarm,
            ) { event, control ->
                realtimeAgentControl = control
                chatVm.applyRealtimeAgentEvent(
                    assistantMessageId = rtAssistantMessageId,
                    event = event,
                    showDetailedTrace = realtimeTraceDetails,
                )
                when (event.type) {
                "voice.input_transcript.delta" -> {
                    event.delta?.let { inputTranscript.append(it) }
                    _uiState.update {
                        it.copy(
                            state = VoiceState.Listening,
                            outputAudioActive = false,
                            transcribedText = inputTranscript.toString().ifBlank { rtUserText },
                        )
                    }
                }
                "voice.input_transcript.final" -> {
                    inputTranscript.clear()
                    inputTranscript.append(event.text ?: rtUserText)
                    _uiState.update {
                        it.copy(
                            state = VoiceState.Thinking,
                            outputAudioActive = false,
                            transcribedText = inputTranscript.toString(),
                        )
                    }
                }
                "voice.response.started", "hermes.run.started" -> {
                    if (event.type == "hermes.run.started") {
                        Log.i(
                            TAG,
                            "Realtime Hermes run started run=${event.runId ?: "?"} " +
                                "session=${event.chatSessionId ?: "?"}",
                        )
                    }
                    if (event.type == "voice.response.started" &&
                        _uiState.value.state == VoiceState.Listening
                    ) {
                        Log.i(TAG, "Ignoring realtime response start while mic capture is active")
                        return@runRealtimeAgent
                    }
                    _uiState.update {
                        it.copy(state = VoiceState.Thinking, outputAudioActive = false)
                    }
                }
                "voice.response.delta" -> {
                    // Hermes-sourced deltas are normally run chatter (the tool
                    // status flow owns that phase) — but DELIVERY responses
                    // (fallback TTS / respeak / visual-only emits) carry the
                    // actual answer and must render. Observed live: a fallback
                    // delivery played audibly while the overlay sat on
                    // "Thinking" with no text.
                    if (event.source == "hermes" && event.delivery == null) {
                        return@runRealtimeAgent
                    }
                    if (_uiState.value.state == VoiceState.Listening) {
                        Log.i(TAG, "Ignoring realtime response text while mic capture is active")
                        return@runRealtimeAgent
                    }
                    event.delta?.let { responseText.append(it) }
                    _uiState.update {
                        it.copy(
                            state = if (audioSeen.get()) VoiceState.Speaking else VoiceState.Thinking,
                            responseText = responseText.toString(),
                        )
                    }
                }
                "hermes.tool.started" -> {
                    emitStatus(
                        key = "tool-${event.toolName ?: event.toolCallId ?: "unknown"}",
                        line = realtimeToolStatusLine(event.toolName),
                        speakEvenAfterProviderAudio = true,
                    )
                    val tool = event.toolName?.replace('_', ' ') ?: "tool"
                    if (_uiState.value.backgroundRun == null) {
                        _uiState.update {
                            it.copy(
                                state = VoiceState.Thinking,
                                responseText = "Using $tool...",
                            )
                        }
                    }
                    // Live chip: tool starts are the fastest-updating signal for
                    // a background run (progress events only tick every ~5s).
                    // DELIVERING/DONE chips are settled — don't reanimate them.
                    updateBackgroundRun { run ->
                        if (run.phase == BackgroundRunPhase.DELIVERING ||
                            run.phase == BackgroundRunPhase.DONE
                        ) run
                        else run.copy(
                            statusLine = realtimeToolStatusLine(event.toolName),
                            phase = BackgroundRunPhase.RUNNING,
                        )
                    }
                }
                "hermes.tool.delta" -> {
                    realtimeToolProgressLine(event)?.let { line ->
                        if (_uiState.value.backgroundRun == null) {
                            _uiState.update {
                                it.copy(state = VoiceState.Thinking, responseText = line)
                            }
                        }
                    }
                    // The gateway streams drafting text as the `_thinking`
                    // pseudo-tool — the strongest "almost done" signal a
                    // background run emits. Surface it as the chip's status
                    // line (never as a tool pill; see ChatViewModel).
                    if (event.toolName == "_thinking") {
                        updateBackgroundRun { run ->
                            if (run.phase == BackgroundRunPhase.DELIVERING ||
                                run.phase == BackgroundRunPhase.DONE
                            ) run
                            else run.copy(statusLine = "Drafting the answer…")
                        }
                    }
                }
                "hermes.tool.completed", "hermes.tool.failed" -> {
                    // A tool finished. Without this, the "Using X…" status and the
                    // chip's tool status line stay pinned on the just-finished tool
                    // until the next started/progress event — which read as "stuck"
                    // in the gap before the next step or the spoken reply. The
                    // per-message ToolCall rows advance separately in ChatViewModel.
                    val completed = event.type == "hermes.tool.completed"
                    Log.i(
                        TAG,
                        "Realtime tool ${if (completed) "completed" else "failed"} " +
                            "tool=${event.toolName ?: event.toolCallId ?: "?"} " +
                            "backgroundRun=${_uiState.value.backgroundRun != null} " +
                            "run=${event.runId ?: "?"}",
                    )
                    if (_uiState.value.backgroundRun == null) {
                        _uiState.update {
                            it.copy(
                                state = VoiceState.Thinking,
                                // Drop the stale "Using X…" back to the real streamed
                                // answer so far (blank until the reply starts).
                                responseText = responseText.toString(),
                            )
                        }
                    }
                    updateBackgroundRun { run ->
                        if (run.phase == BackgroundRunPhase.DELIVERING ||
                            run.phase == BackgroundRunPhase.DONE
                        ) run
                        else run.copy(
                            // Clear the finished tool's line rather than leave
                            // RUNNING pinned on a tool that is no longer live.
                            statusLine = null,
                            completedToolCount = if (completed) {
                                event.completedToolCount ?: (run.completedToolCount + 1)
                            } else {
                                event.completedToolCount ?: run.completedToolCount
                            },
                            phase = BackgroundRunPhase.RUNNING,
                        )
                    }
                }
                "hermes.run.progress" -> {
                    val line = realtimeHermesProgressLine(event.message)
                    Log.i(
                        TAG,
                        "Realtime Hermes progress tier=${event.tier ?: "?"} " +
                            "floor=${event.floor ?: "?"} status=${event.statusKey ?: "?"} " +
                            "shouldSpeak=${event.shouldSpeak} run=${event.runId ?: "?"}",
                    )
                    if (event.shouldSpeak) {
                        emitStatus(
                            key = "progress-${event.runId ?: "run"}-${event.statusKey ?: line}",
                            line = line,
                            speak = true,
                            speakEvenAfterProviderAudio = true,
                        )
                    }
                    if (_uiState.value.backgroundRun == null) {
                        _uiState.update {
                            it.copy(
                                state = VoiceState.Thinking,
                                outputAudioActive = false,
                                responseText = line,
                            )
                        }
                    }
                    // Live chip: active tool + completed-step count. Progress
                    // arriving at all also means the socket is healthy, so a
                    // RECONNECTING chip can flip back to RUNNING here.
                    // DELIVERING/DONE chips are settled — don't reanimate them.
                    updateBackgroundRun { run ->
                        if (run.phase == BackgroundRunPhase.DELIVERING ||
                            run.phase == BackgroundRunPhase.DONE
                        ) run
                        else run.copy(
                            statusLine = event.activeToolName
                                ?.takeIf { name -> name.isNotBlank() }
                                ?.let { name -> realtimeToolStatusLine(name) }
                                ?: run.statusLine,
                            completedToolCount = event.completedToolCount
                                ?: run.completedToolCount,
                            phase = BackgroundRunPhase.RUNNING,
                        )
                    }
                }
                "hermes.run.promoted" -> {
                    // The run detached to the background; the provider speaks the
                    // handoff. Surface a persistent chip so the user knows a long
                    // task is still in flight (ADR 33 Tier B/C).
                    val tier = event.tier ?: "promoted"
                    Log.i(
                        TAG,
                        "Realtime run promoted to background tier=$tier " +
                            "run=${event.runId ?: "?"} session=${event.chatSessionId ?: "?"}",
                    )
                    // A fresh run replaces any lingering DONE chip — cancel its
                    // pending auto-dismiss so it can't clear the NEW chip's
                    // later DONE state early.
                    deliveringChipClearJob?.cancel()
                    _uiState.update {
                        it.copy(
                            backgroundRun = BackgroundRunState(
                                runId = event.runId,
                                tier = tier,
                                message = if (tier == "durable") {
                                    "Started a background task — I'll report back."
                                } else {
                                    "This is taking a moment — working on it in the background."
                                },
                                queuedCount = event.queuedCount ?: 0,
                            ),
                        )
                    }
                }
                "hermes.run.queued" -> {
                    // A second long ask was queued behind the running task
                    // (background-run v2). Reflect the depth on the chip; the
                    // model speaks the "queued" acknowledgement itself.
                    Log.i(
                        TAG,
                        "Realtime task queued count=${event.queuedCount ?: "?"} " +
                            "run=${event.runId ?: "?"}",
                    )
                    updateBackgroundRun { run ->
                        run.copy(queuedCount = event.queuedCount ?: run.queuedCount)
                    }
                }
                "hermes.run.background_completed" -> {
                    // Background run finished; the spoken summary follows via the
                    // provider's forced-summary turn once the floor is clear (up
                    // to ~12s later). Show a "delivering" chip for that gap; the
                    // first summary audio (or the watchdog) clears it.
                    Log.i(
                        TAG,
                        "Realtime background run completed run=${event.runId ?: "?"} " +
                            "ok=${event.success != false}",
                    )
                    if (event.success == false) {
                        _uiState.update { it.copy(backgroundRun = null) }
                    } else {
                        updateBackgroundRun { run ->
                            run.copy(
                                phase = BackgroundRunPhase.DELIVERING,
                                message = "Done — delivering the answer…",
                                statusLine = null,
                            )
                        }
                        // Watchdog: if no summary audio ever starts (visual-only
                        // delivery, provider hiccup), settle the chip to DONE
                        // instead of pinning DELIVERING forever — the run DID
                        // finish; its result is in the transcript either way.
                        deliveringChipClearJob?.cancel()
                        deliveringChipClearJob = viewModelScope.launch {
                            delay(20_000L)
                            if (_uiState.value.backgroundRun?.phase == BackgroundRunPhase.DELIVERING) {
                                // Re-assigns deliveringChipClearJob to the DONE
                                // linger job; this watchdog job is completing, so
                                // no self-cancel is needed.
                                settleBackgroundRunChip(reason = "delivery_watchdog")
                            }
                        }
                    }
                }
                "hermes.confirmation.requested" -> {
                    val confirmationId = event.confirmationId
                    if (!confirmationId.isNullOrBlank()) {
                        realtimeConfirmationControl = control
                        _uiState.update {
                            it.copy(
                                hermesConfirmation = HermesConfirmationState(
                                    confirmationId = confirmationId,
                                    message = event.message ?: "Hermes is waiting for confirmation.",
                                )
                            )
                        }
                    }
                    emitStatus("confirmation", "Waiting for confirmation.")
                    _uiState.update {
                        it.copy(
                            state = VoiceState.Thinking,
                            responseText = event.message ?: "Waiting for confirmation",
                        )
                    }
                }
                "voice.playback_drain.requested" -> {
                    val reason = event.reason.orEmpty()
                    val drainMs = pcmPlayer?.flushBufferedPlayback() ?: 0L
                    val timeoutMs = event.timeoutMs ?: 2_500L
                    val delayMs = drainMs
                        .coerceAtMost((timeoutMs - 100L).coerceAtLeast(0L))
                    val playedAudioEventId = lastRealtimeAudioEventId.get().takeIf { it > 0L }
                    Log.i(
                        TAG,
                        "Realtime playback drain requested reason=${reason.ifBlank { "unspecified" }} " +
                            "call=${event.toolCallId.orEmpty()} drainMs=$drainMs delayMs=$delayMs " +
                            "timeoutMs=$timeoutMs playedAudio=$playedAudioEventId",
                    )
                    viewModelScope.launch {
                        if (delayMs > 0L) delay(delayMs)
                        if (!providerRealtimeAgentTurnActive.get()) return@launch
                        val sent = control.sendPlaybackDrained(
                            callId = event.toolCallId,
                            playedAudioEventId = playedAudioEventId,
                        )
                        Log.i(
                            TAG,
                            "Realtime playback drained sent=$sent " +
                                "reason=${reason.ifBlank { "unspecified" }} " +
                                "call=${event.toolCallId.orEmpty()} playedAudio=$playedAudioEventId",
                        )
                    }
                    if (reason != "pre_hermes_ack") {
                        emitStatus("done-summarizing", "Done, summarizing.", speak = false)
                    }
                    _uiState.update {
                        it.copy(
                            state = if (audioSeen.get()) VoiceState.Speaking else VoiceState.Thinking,
                            responseText = responseText.toString().ifBlank {
                                if (reason == "pre_hermes_ack") "Checking Hermes..." else "Finishing tool response..."
                            },
                        )
                    }
                }
                "voice.output_audio.delta" -> {
                    if (_uiState.value.state == VoiceState.Listening) {
                        Log.i(TAG, "Dropping realtime output audio while mic capture is active")
                        return@runRealtimeAgent
                    }
                    event.audioEventId?.let {
                        lastRealtimeAudioEventId.updateAndGet { current -> maxOf(current, it) }
                    }
                    if (pcmPlayer != null) {
                        handleRealtimeVoiceEvent(
                            event = event,
                            pcmPlayer = pcmPlayer,
                            audioSeen = audioSeen,
                            audioBytes = audioBytes,
                            bargeInStarted = bargeInStarted,
                        )
                    }
                }
                "voice.output_audio.done" -> {
                    if (_uiState.value.state == VoiceState.Listening) {
                        Log.i(TAG, "Ignoring realtime output done while mic capture is active")
                        return@runRealtimeAgent
                    }
                    pcmPlayer?.flushBufferedPlayback()
                    _uiState.update {
                        it.copy(outputAudioActive = it.outputAudioActive && audioSeen.get())
                    }
                }
                "hermes.run.cancelled" -> {
                    Log.i(TAG, "Realtime Hermes run cancelled run=${event.runId ?: "?"}")
                    realtimeConfirmationControl = null
                    _uiState.update {
                        it.copy(
                            state = VoiceState.Idle,
                            outputAudioActive = false,
                            responseText = "Cancelled.",
                            hermesConfirmation = null,
                            backgroundRun = null,
                        )
                    }
                }
                "voice.response.done" -> {
                    if (_uiState.value.state == VoiceState.Listening) {
                        Log.i(TAG, "Ignoring realtime response done while mic capture is active")
                        return@runRealtimeAgent
                    }
                    realtimeConfirmationControl = null
                    event.text?.takeIf { it.isNotBlank() }?.let { finalText ->
                        if (responseText.isBlank()) responseText.append(finalText)
                    }
                    _uiState.update {
                        it.copy(
                            responseText = responseText.toString(),
                            outputAudioActive = it.outputAudioActive && audioSeen.get(),
                            hermesConfirmation = null,
                        )
                    }
                }
                "voice.error" -> {
                    realtimeConfirmationControl = null
                    val rawDetail = event.message ?: "Realtime agent failed"
                    DiagnosticsLog.record(
                        category = DiagnosticCategory.Voice,
                        severity = DiagnosticSeverity.Error,
                        title = "Realtime voice error",
                        detail = rawDetail,
                    )
                    _uiState.update { it.copy(hermesConfirmation = null) }
                    // Route the raw relay message through the classifier so
                    // provider-auth (and other) failures surface a clear,
                    // actionable message + a Voice-settings snackbar action
                    // instead of a raw "xAI Realtime auth ..." provider string.
                    surfaceError(
                        java.io.IOException(rawDetail),
                        context = "voice_config",
                    )
                }
                }
            }
        } finally {
            providerRealtimeAgentTurnActive.set(false)
            realtimeAgentControl = null
            realtimeConfirmationControl = null
        }

        if (result.isSuccess) {
            DiagnosticsLog.record(
                category = DiagnosticCategory.Voice,
                severity = DiagnosticSeverity.Info,
                title = "Realtime voice turn complete",
            )
            pendingInTtsQueue.set(0)
            maybeAutoResume()
            return
        }

        val err = result.exceptionOrNull()
        Log.w(TAG, "realtime agent failed: ${err?.message}")
        DiagnosticsLog.record(
            category = DiagnosticCategory.Voice,
            severity = DiagnosticSeverity.Error,
            title = "Realtime voice turn failed",
            detail = err?.message ?: "Unknown error",
        )
        // A persistent session ending in error must drop so the next turn opens
        // a fresh one rather than submitting into a dead channel.
        closeRealtimeSession()
        // A failed warm-up must stay silent: no user action happened, and the
        // first real utterance will simply open a fresh session.
        if (!prewarm) {
            surfaceError(err, context = "voice_config")
        }
    }

    /**
     * Per-turn completion in a persistent realtime session (ADR follow-up). The
     * socket stays open; this just finalizes the spoken turn and re-arms
     * continuous listening if enabled.
     */
    private fun onRealtimeTurnComplete(summary: RealtimeVoiceSummary) {
        DiagnosticsLog.record(
            category = DiagnosticCategory.Voice,
            severity = DiagnosticSeverity.Info,
            title = "Realtime voice turn complete",
        )
        Log.i(
            TAG,
            "Realtime persistent turn complete provider=${summary.provider} " +
                "audioChunks=${summary.audioChunks}",
        )
        pendingInTtsQueue.set(0)
        maybeAutoResume()
    }

    /**
     * Submit a follow-up utterance onto the already-open persistent session
     * (turns 2+). Mirrors the per-turn reset the open path does for turn 1.
     */
    private fun submitRealtimeTurn(chatVm: ChatViewModel, inputPcm: ByteArray, inputSampleRate: Int) {
        val channel = realtimeTurnChannel ?: return
        // A new turn while a background task runs: remind visually that the
        // earlier task is still going (the agent also says so if the user asks
        // for another task — the relay answers busy rather than orphaning it).
        updateBackgroundRun { run ->
            if (run.phase == BackgroundRunPhase.RUNNING) {
                run.copy(statusLine = "Still working on the earlier task…")
            } else {
                run
            }
        }
        // New turn requested → allow this response's audio through again.
        realtimeAudioSuppressed = false
        drainQueuedLocalTts()
        try { player?.stop() } catch (_: Exception) { /* ignore */ }
        firstFrameWatchdogJob?.cancel(); firstFrameWatchdogJob = null
        clearSpokenChunksState()
        audioSeen.set(false)
        audioBytes.set(0)
        bargeInStarted.set(false)
        lastRealtimeAudioEventId.set(0L)
        responseText = StringBuilder()
        inputTranscript = StringBuilder()
        spokenStatusKeys.clear()
        lastSpokenStatusAtMs = 0L
        spokenStatusCount = 0
        rtUserText = ""
        rtConversationContext = chatVm.realtimeAgentContextMessages()
        rtAssistantMessageId = chatVm.startRealtimeAgentTurn(
            userText = "",
            chatSessionId = chatVm.currentSessionId.value,
        )
        providerRealtimeAgentTurnActive.set(true)
        _uiState.update {
            it.copy(
                state = VoiceState.Thinking,
                outputAudioActive = false,
                transcribedText = null,
                responseText = "",
            )
        }
        val sent = channel.trySend(RealtimeTurnInput(inputPcm, inputSampleRate)).isSuccess
        Log.i(TAG, "Realtime persistent turn submitted sent=$sent pcmBytes=${inputPcm.size}")
    }

    /** Tear down the persistent realtime session (voice-mode exit / engine switch). */
    private fun closeRealtimeSession() {
        val hadSession = realtimeTurnChannel != null || realtimeSessionJob != null
        realtimeTurnChannel?.close()
        realtimeTurnChannel = null
        realtimeSessionJob?.cancel()
        realtimeSessionJob = null
        if (hadSession) Log.i(TAG, "Realtime persistent session closed")
    }

    private fun realtimeToolStatusLine(toolName: String?): String {
        val normalized = toolName.orEmpty().lowercase()
        return when {
            "desktop" in normalized -> "Searching desktop."
            "search" in normalized -> "Searching."
            "android" in normalized -> "Checking phone."
            "browser" in normalized || "web" in normalized -> "Searching web."
            "terminal" in normalized || "shell" in normalized -> "Running command."
            "skill" in normalized -> "Checking Hermes skill."
            "memory" in normalized -> "Checking memory."
            "file" in normalized || "read" in normalized || "write" in normalized -> "Checking files."
            else -> "Checking Hermes."
        }
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
                    resetBrokeredToolSpeechState()
                    lastObservedMessageId = msgId
                    lastObservedContentLength = 0
                }

                observeHermesToolLoopForSpeech(lastAssistant)

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
                    scheduleAgentAudioCompletionCheck()
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
        _uiState.update {
            it.copy(
                state = VoiceState.Speaking,
                outputAudioActive = it.outputAudioActive && it.state == VoiceState.Speaking,
                responseText = fullContent,
            )
        }
        drainSentences()
        rearmIdleFlush()
    }

    /**
     * Hermes remains the only authority for tool execution. This broker only
     * turns Hermes tool-start events into short spoken status lines so voice
     * mode does not sit silently while Hermes searches, reads, or controls a
     * device through the normal tool loop.
     */
    private fun observeHermesToolLoopForSpeech(message: ChatMessage) {
        if (!_uiState.value.voiceMode || message.toolCalls.isEmpty()) return

        var spokenForMessage = brokeredToolSpeechCounts[message.id] ?: 0
        message.toolCalls.forEach { tool ->
            if (tool.isComplete) return@forEach
            val key = brokeredToolSpeechKey(message, tool, phase = "start")
            if (!brokeredToolSpeechKeys.add(key)) return@forEach
            if (spokenForMessage >= MAX_BROKERED_TOOL_STATUS_PER_MESSAGE) return@forEach

            val status = brokeredToolStartStatus(tool.name, spokenForMessage)
            spokenForMessage += 1
            brokeredToolSpeechCounts[message.id] = spokenForMessage
            enqueueBrokeredToolStatus(status)
        }
    }

    private fun brokeredToolSpeechKey(message: ChatMessage, tool: ToolCall, phase: String): String {
        val stableToolId = tool.id ?: "${tool.name}:${tool.startedAt}"
        return "${message.id}:$stableToolId:$phase"
    }

    private fun brokeredToolStartStatus(toolName: String, indexForMessage: Int): String {
        return brokeredToolStartStatusForTts(toolName, indexForMessage)
    }

    private fun enqueueBrokeredToolStatus(status: String) {
        if (status.isBlank()) return
        if (enqueueSentenceForTts(status, immediate = true)) {
            _uiState.update { state ->
                state.copy(
                    state = VoiceState.Speaking,
                    outputAudioActive = state.outputAudioActive && state.state == VoiceState.Speaking,
                    responseText = state.responseText.ifBlank { status },
                )
            }
        }
    }

    private fun resetBrokeredToolSpeechState() {
        brokeredToolSpeechKeys.clear()
        brokeredToolSpeechCounts.clear()
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

    /**
     * Enqueue a sentence for the synth worker. Returns true on success.
     * Centralizes the [pendingInTtsQueue] increment so every producer
     * participates in the pipeline-drained gate — see the field KDoc for
     * the race this closes.
     */
    private fun enqueueSentenceForTts(
        sentence: String,
        immediate: Boolean = false,
        allowDuringProviderRealtime: Boolean = false,
    ): Boolean {
        if (providerRealtimeAgentTurnActive.get() && !allowDuringProviderRealtime) {
            Log.i(TAG, "Skipping local TTS during provider-native realtime turn")
            return true
        }
        if (shouldPreferRealtimeVoice()) {
            val realtimeChunks = if (immediate) {
                realtimeSpeechCoalescer.enqueueImmediate(sentence)
            } else {
                realtimeSpeechCoalescer.append(sentence)
            }
            if (realtimeChunks.isEmpty()) return true
            return enqueueRealtimeTtsChunks(realtimeChunks)
        }
        return enqueueSentenceForLegacyTts(sentence)
    }

    private fun enqueueRealtimeTtsChunks(chunks: List<String>): Boolean {
        var allQueued = true
        for (chunk in chunks) {
            val sent = realtimeTtsQueue.trySend(chunk)
            if (sent.isSuccess) {
                pendingInTtsQueue.incrementAndGet()
            } else {
                Log.w(TAG, "Realtime TTS queue refused chunk: ${sent.exceptionOrNull()?.message}")
                voiceOutputAvailable = false
                allQueued = false
                enqueueSentenceForLegacyTts(chunk)
            }
        }
        return allQueued
    }

    private fun enqueueSentenceForLegacyTts(sentence: String): Boolean {
        val sent = ttsQueue.trySend(sentence)
        if (sent.isSuccess) {
            pendingInTtsQueue.incrementAndGet()
            return true
        }
        Log.w(TAG, "TTS queue refused sentence: ${sent.exceptionOrNull()?.message}")
        return false
    }

    private fun shouldPreferRealtimeVoice(): Boolean =
        voiceOutputAvailable != false &&
            realtimePcmPlayer != null &&
            voiceClient != null &&
            // Use the RESOLVED route: AutoVoiceAudioClient.effectiveRoute maps
            // Auto -> Relay when relay is ready, so in `auto` mode with relay
            // paired the override-capable relay path engages. Reading the raw
            // `route` would stay "Auto" and silently drop the chosen override.
            voiceAudioClient?.effectiveRoute == VoiceAudioRoute.Relay

    private fun drainSentences() {
        while (true) {
            val sentence = extractNextSentence(sentenceBuffer, streamComplete) ?: return
            if (sentence.isBlank()) continue
            if (!enqueueSentenceForTts(sentence)) return
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
            val sentence = extractNextSentence(sentenceBuffer, streamComplete = true) ?: break
            if (sentence.isBlank()) continue
            if (!enqueueSentenceForTts(sentence)) return
        }
        flushRealtimeSpeechCoalescer()
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
            enqueueSentenceForTts(trailing)
        }
        flushRealtimeSpeechCoalescer()
    }

    private fun flushRealtimeSpeechCoalescer(): Boolean {
        if (!shouldPreferRealtimeVoice()) {
            resetRealtimeSpeechCoalescer()
            return true
        }
        val chunks = realtimeSpeechCoalescer.flush()
        if (chunks.isEmpty()) return true
        return enqueueRealtimeTtsChunks(chunks)
    }

    private fun resetRealtimeSpeechCoalescer() {
        realtimeSpeechCoalescer.clear()
    }

    // ---------------------------------------------------------------------
    // TTS consumer — two-coroutine pipeline: synth runs ahead of playback
    // ---------------------------------------------------------------------
    //
    // Relay-selected voice output can stream renderer PCM through
    // /voice/output/* and write chunks directly to AudioTrack. Standard
    // upstream audio and Relay fallback both use the synth/play workers.

    private fun startRealtimeTtsConsumer() {
        realtimeTtsConsumerJob?.cancel()
        realtimeTtsConsumerJob = viewModelScope.launch {
            for (sentence in realtimeTtsQueue) {
                speakSentenceViaRealtime(sentence)
            }
        }
    }

    private suspend fun speakSentenceViaRealtime(sentence: String) {
        val client = voiceClient
        val pcmPlayer = realtimePcmPlayer
        if (client == null || pcmPlayer == null) {
            pendingInTtsQueue.decrementAndGet()
            enqueueSentenceForLegacyTts(sentence)
            return
        }

        if (voiceOutputAvailable == null) {
            val config = client.getVoiceOutputConfig()
            val cfg = config.getOrNull()
            voiceOutputAvailable = cfg?.enabled == true
            if (voiceOutputAvailable != true) {
                Log.i(TAG, "Voice output unavailable; using basic /voice/synthesize fallback")
                // Surface the active render path for troubleshooting — otherwise
                // the streaming-vs-synthesize choice is invisible (e.g. why the
                // streaming "Expressive speech tags" toggle has no effect here).
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Voice,
                    severity = DiagnosticSeverity.Info,
                    title = "Voice render path: basic synthesize",
                    detail = "Streaming /voice/output is disabled or unavailable — rendering via " +
                        "the /voice/synthesize fallback. Per-request enhanced voice applies here; " +
                        "the streaming renderer's speech-tags setting does not.",
                )
                pendingInTtsQueue.decrementAndGet()
                enqueueSentenceForLegacyTts(sentence)
                return
            }
            DiagnosticsLog.record(
                category = DiagnosticCategory.Voice,
                severity = DiagnosticSeverity.Info,
                title = "Voice render path: streaming output",
                detail = "Streaming /voice/output renderer active" +
                    (cfg?.default_provider?.takeIf { it.isNotBlank() }?.let { " (provider $it)" }.orEmpty()) +
                    (if (cfg?.auto_speech_tags == true) "; expressive speech tags on" else "") + ".",
            )
        }

        val audioSeen = AtomicBoolean(false)
        val audioBytes = AtomicInteger(0)
        val bargeInStarted = AtomicBoolean(false)
        val startedAtMs = System.currentTimeMillis()
        _uiState.update { it.copy(state = VoiceState.Speaking, outputAudioActive = false) }
        _currentPlayingChunkIndex.value = _currentPlayingChunkIndex.value + 1

        val result = try {
            client.runVoiceOutput(
                text = sentence,
                renderMode = "verbatim",
                onHandoff = ::recordVoiceHandoff,
            ) { event ->
                handleRealtimeVoiceEvent(event, pcmPlayer, audioSeen, audioBytes, bargeInStarted)
            }
        } finally {
            if (_uiState.value.state == VoiceState.Speaking) {
                stopBargeInListener()
            }
        }

        pendingInTtsQueue.decrementAndGet()
        if (result.isSuccess) {
            spokenChunks.add(sentence)
            val latencyMs = System.currentTimeMillis() - startedAtMs
            recordTtsChunkFinished(
                sentence = sentence,
                latencyMs = latencyMs,
                bytesReceived = audioBytes.get().toLong(),
                startedAtMs = startedAtMs,
            )
            maybeAutoResume()
            return
        }

        val err = result.exceptionOrNull()
        Log.w(TAG, "Voice output failed; falling back to basic TTS: ${err?.message}")
        voiceOutputAvailable = false
        if (!audioSeen.get()) {
            enqueueSentenceForLegacyTts(sentence)
        } else {
            maybeAutoResume()
        }
    }

    /**
     * Timer-driven first-frame watchdog (#1). Polls [RealtimePcmPlayer.snapshot]
     * independently of websocket writes, so a parked AudioTrack cursor is caught
     * even when the provider front-loads all PCM and then goes quiet (the case
     * the write-sampled health log can miss). Emits a clean time-to-first-audio
     * metric and a one-shot in-app diagnostic if playback never starts.
     */
    private fun startRealtimePlaybackWatchdog() {
        val player = realtimePcmPlayer ?: return
        firstFrameWatchdogJob?.cancel()
        firstFrameWatchdogJob = viewModelScope.launch {
            var reportedStuck = false
            repeat(REALTIME_WATCHDOG_MAX_TICKS) {
                val snap = player.snapshot()
                val elapsed = if (snap.playbackStarted && snap.startedAtElapsedMs > 0L) {
                    SystemClock.elapsedRealtime() - snap.startedAtElapsedMs
                } else {
                    0L
                }
                val decision = evaluateFirstFrameWatchdog(
                    active = snap.active,
                    playbackStarted = snap.playbackStarted,
                    headFrames = snap.headFrames,
                    elapsedSinceStartMs = elapsed,
                    playStatePlaying = snap.playStatePlaying,
                    alreadyReportedStuck = reportedStuck,
                    stuckThresholdMs = REALTIME_STUCK_CURSOR_MS,
                )
                decision.firstAudioMs?.let { ms ->
                    Log.i(TAG, "Realtime watchdog: first audio reached speaker after ${ms}ms")
                    // Flip the waveform's output gate the instant playback truly
                    // starts, even if no further audio-delta byte event arrives
                    // to drive handleRealtimeVoiceEvent — the unfold then lands
                    // exactly at the first audible frame.
                    if (_uiState.value.state == VoiceState.Speaking) {
                        _uiState.update { st -> st.copy(outputAudioActive = true) }
                    }
                }
                if (decision.reportStuck) {
                    reportedStuck = true
                    Log.w(
                        TAG,
                        "Realtime watchdog: AudioTrack playing ${elapsed}ms but cursor still parked " +
                            "(headFrames=0) — no audio reaching the speaker",
                    )
                    DiagnosticsLog.record(
                        category = DiagnosticCategory.Voice,
                        severity = DiagnosticSeverity.Warning,
                        title = "Realtime audio not starting",
                        detail = "Playback has been running ${elapsed}ms with no audio output. " +
                            "If this persists, the audio output route may need a nudge (volume key).",
                    )
                }
                if (!decision.keepWatching) return@launch
                delay(REALTIME_WATCHDOG_INTERVAL_MS)
            }
        }
    }

    private fun handleRealtimeVoiceEvent(
        event: RealtimeVoiceEvent,
        pcmPlayer: RealtimePcmPlayer,
        audioSeen: AtomicBoolean,
        audioBytes: AtomicInteger,
        bargeInStarted: AtomicBoolean,
    ) {
        if (!event.isAudioDelta) return
        // After an interrupt, ignore the cancelled turn's in-flight audio tail
        // until the next turn is sent (which clears the flag). Otherwise these
        // late deltas re-create the player and playback resumes after "Stop".
        if (realtimeAudioSuppressed) return
        val encoded = event.audioBase64 ?: return
        val audio = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: Exception) {
            return
        }
        if (audio.isEmpty()) return
        audioSeen.set(true)
        audioBytes.addAndGet(audio.size)
        lastRealtimeAudioDeltaAtMs = System.currentTimeMillis()
        // The spoken summary started — settle the chip to DONE so the outcome
        // stays visible for a beat instead of vanishing the instant the
        // waveform returns (it auto-dismisses after DONE_CHIP_LINGER_MS).
        if (_uiState.value.backgroundRun?.phase == BackgroundRunPhase.DELIVERING) {
            deliveringChipClearJob?.cancel()
            settleBackgroundRunChip(reason = "summary_audio_started")
        }
        val sampleRate = event.sampleRate ?: 24_000
        Log.i(
            TAG,
            "Realtime audio delta event=${event.audioEventId ?: 0} bytes=${audio.size} " +
                "sampleRate=$sampleRate rms=${event.rmsLevel ?: -1f} peak=${event.peakLevel ?: -1f}",
        )
        val level = pcmPlayer.write(audio, sampleRate)
        scheduleRealtimeAmplitudeRelease(audio.size, sampleRate, lastRealtimeAudioDeltaAtMs)
        if (bargeInStarted.compareAndSet(false, true)) {
            startBargeInListenerIfEnabled(
                audioSessionIdProvider = { pcmPlayer.audioSessionId },
                startupGuardMs = REALTIME_BARGE_IN_STARTUP_GUARD_MS,
            )
            startRealtimePlaybackWatchdog()
        }
        // Audio arriving IS the speech signal, whatever produced it: a
        // delivery response (fallback TTS / respeak) starts PCM without any
        // provider text delta having flipped the state, and the envelope
        // below is gated on Speaking — without this flip the audio plays
        // while the overlay sits on "Thinking" with a dead waveform
        // (observed live on a fallback delivery).
        if (_uiState.value.state == VoiceState.Thinking) {
            _uiState.update { it.copy(state = VoiceState.Speaking) }
        }
        if (_uiState.value.state == VoiceState.Speaking) {
            // Keep feeding the visual envelope from the decoded level so the
            // waveform stays smooth, but gate `outputAudioActive` on REAL
            // playback start (head-move / head-synced amplitude) rather than on
            // the decoded RMS, which leads the audible frame by the player's
            // start prebuffer. Mirrors the basic-TTS Visualizer gating.
            speakEnvelope = applyEnvelope(speakEnvelope, level)
            val snap = pcmPlayer.snapshot()
            val playbackActive = shouldMarkRealtimeOutputActive(
                headFrames = snap.headFrames,
                playbackAmplitude = pcmPlayer.playbackAmplitude(),
            )
            _uiState.update {
                it.copy(
                    amplitude = speakEnvelope,
                    outputAudioActive = it.outputAudioActive || playbackActive,
                )
            }
        }
    }

    private fun scheduleRealtimeAmplitudeRelease(
        pcmBytes: Int,
        sampleRate: Int,
        writeTimestampMs: Long,
    ) {
        if (sampleRate <= 0 || pcmBytes <= 0) return
        realtimeAmplitudeDecayJob?.cancel()
        realtimeAmplitudeDecayJob = viewModelScope.launch {
            val chunkDurationMs = ((pcmBytes / 2.0) / sampleRate * 1000.0)
                .toLong()
                .coerceIn(25L, 240L)
            delay(chunkDurationMs + 80L)
            repeat(10) {
                if (lastRealtimeAudioDeltaAtMs != writeTimestampMs ||
                    _uiState.value.state != VoiceState.Speaking
                ) {
                    return@launch
                }
                speakEnvelope = applyEnvelope(speakEnvelope, 0f)
                _uiState.update { it.copy(amplitude = speakEnvelope) }
                if (speakEnvelope <= 0.02f) return@launch
                delay(45L)
            }
        }
    }

    private fun drainRealtimeTtsQueue() {
        while (true) {
            val r = realtimeTtsQueue.tryReceive()
            if (r.isFailure || r.isClosed) break
            pendingInTtsQueue.decrementAndGet()
        }
        resetRealtimeSpeechCoalescer()
    }

    private fun drainQueuedLocalTts() {
        while (true) {
            val r = ttsQueue.tryReceive()
            if (r.isFailure || r.isClosed) break
            pendingInTtsQueue.decrementAndGet()
        }
        drainRealtimeTtsQueue()
    }

    private fun resetTtsTurnStats() {
        ttsChunksThisResponse = 0
        lastTtsChunkFinishedAtMs = 0L
        _voiceStats.update {
            it.copy(
                currentResponseTtsChunks = 0,
                lastTtsChunkGapMs = 0L,
            )
        }
    }

    private fun recordTtsChunkFinished(
        sentence: String,
        latencyMs: Long,
        bytesReceived: Long,
        startedAtMs: Long,
    ) {
        val gapMs = if (lastTtsChunkFinishedAtMs > 0L) {
            (startedAtMs - lastTtsChunkFinishedAtMs).coerceAtLeast(0L)
        } else {
            0L
        }
        val finishedAtMs = System.currentTimeMillis()
        ttsChunksThisResponse += 1
        lastTtsChunkFinishedAtMs = finishedAtMs
        _voiceStats.update { s ->
            val nextLatencies = (s.recentTtsLatenciesMs + latencyMs).takeLast(5)
            s.copy(
                lastSynthesizedSentence = sentence,
                recentTtsLatenciesMs = nextLatencies,
                currentResponseTtsChunks = ttsChunksThisResponse,
                lastTtsChunkGapMs = gapMs,
                ttsBytesReceived = s.ttsBytesReceived + bytesReceived,
                ttsCallCount = s.ttsCallCount + 1,
            )
        }
    }

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
                val synthStartedAtMs = System.currentTimeMillis()
                try {
                    val client = voiceAudioClient
                    val result = if (client == null) {
                        Result.failure(IllegalStateException("voice audio client not initialized"))
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
                        // Record successful TTS in the voiceStats snapshot.
                        val latencyMs = System.currentTimeMillis() - synthStartedAtMs
                        val fileBytes = try {
                            result.getOrNull()?.length() ?: 0L
                        } catch (_: Exception) { 0L }
                        recordTtsChunkFinished(
                            sentence = sentence,
                            latencyMs = latencyMs,
                            bytesReceived = fileBytes,
                            startedAtMs = synthStartedAtMs,
                        )
                    }
                    result
                } finally {
                    // Covers both success and failure so the Continuous-mode
                    // auto-resume gate sees the pipeline as drained even when
                    // a synth call throws. See pendingInTtsQueue KDoc.
                    pendingInTtsQueue.decrementAndGet()
                }
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
                    _uiState.update {
                        it.copy(state = VoiceState.Speaking, outputAudioActive = false)
                    }
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

    // Called when a possible end-of-agent-output checkpoint is reached. The
    // same completion contract applies to tap, hold, and continuous: wait for
    // the assistant stream, queued TTS work, and provider-streamed PCM drain,
    // then end Speaking. Continuous only re-enters Listening after that shared
    // finalizer has run and only when the loop was explicitly armed.
    //
    // Key invariant: calling this is safe before the queue is drained. The
    // decision helper will schedule a retry instead of ending Speaking while
    // synth, playback, or realtime PCM still has work in flight.
    private fun maybeAutoResume() {
        val decision = agentAudioCompletionDecision()
        if (decision.finishNow) {
            finishAgentAudioOutput()
            return
        }
        decision.retryDelayMs?.let { delayMs ->
            scheduleAgentAudioCompletionCheck(delayMs)
        }
    }

    private fun realtimeHermesProgressLine(message: String?): String {
        val line = message
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "Waiting for Hermes response."
        return if (line.equals("Hermes is still working.", ignoreCase = true)) {
            "Waiting for Hermes response."
        } else {
            line
        }
    }

    private fun realtimeToolProgressLine(event: RealtimeVoiceEvent): String? {
        if (!realtimeTraceDetails && event.toolName.isNullOrBlank()) return null
        val line = (event.message ?: event.delta)
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        if (!realtimeTraceDetails && looksLikeRawRealtimeToolOutput(line)) return null
        return line.take(180)
    }

    private fun looksLikeRawRealtimeToolOutput(line: String): Boolean {
        if (line.length > 220) return true
        val rawMarkers = listOf("{", "}", "[", "]", "Traceback", "Exception:", "\\n", "```")
        return rawMarkers.count { marker -> line.contains(marker) } >= 2
    }

    /**
     * Re-check the end-of-audio handoff after the chat/realtime stream ends.
     * Realtime PCM playback can finish before ChatViewModel marks the assistant
     * response complete, or ChatViewModel can finish while AudioTrack still has
     * buffered PCM. This retry is mode-neutral so tap/hold also leave Speaking
     * once output audio has drained.
     */
    private fun scheduleAgentAudioCompletionCheck(initialDelayMs: Long = AUDIO_COMPLETION_RETRY_MS) {
        continuousResumeJob?.cancel()
        continuousResumeJob = viewModelScope.launch {
            delay(initialDelayMs.coerceIn(AUDIO_COMPLETION_RETRY_MS, AUDIO_COMPLETION_MAX_SLEEP_MS))
            repeat(120) {
                val state = _uiState.value
                if (!state.voiceMode ||
                    state.state == VoiceState.Listening
                ) {
                    return@launch
                }

                val decision = agentAudioCompletionDecision()
                if (decision.finishNow) {
                    finishAgentAudioOutput()
                    return@launch
                }
                val retryDelay = decision.retryDelayMs ?: return@launch
                delay(retryDelay.coerceIn(AUDIO_COMPLETION_RETRY_MS, AUDIO_COMPLETION_MAX_SLEEP_MS))
            }
        }
    }

    private fun agentAudioCompletionDecision(): AgentAudioCompletionDecision {
        val player = realtimePcmPlayer
        val estimate = player
            ?.estimatedRemainingPlaybackMs()
            ?.takeIf { lastRealtimeAudioDeltaAtMs > 0L }
            ?: 0L
        // Cross-check the byte-math estimate against the real hardware cursor (#3)
        // and trust whichever says "more left" so we never stop with frames still
        // queued (the "audio cut off early" failure mode).
        val effectiveRemaining = if (player != null && lastRealtimeAudioDeltaAtMs > 0L) {
            val snap = player.snapshot()
            if (snap.active && snap.playbackStarted) {
                val drift = playbackDrainDrift(
                    estimatedRemainingMs = estimate,
                    framesWritten = snap.framesWritten,
                    headFrames = snap.headFrames,
                    sampleRate = snap.sampleRate,
                )
                if (drift.earlyStopRisk) {
                    Log.w(
                        TAG,
                        "Realtime drain drift: estimate=${estimate}ms actual=${drift.actualRemainingMs}ms " +
                            "drift=${drift.driftMs}ms — holding completion to avoid early cutoff",
                    )
                }
                drift.effectiveRemainingMs
            } else {
                estimate
            }
        } else {
            estimate
        }
        return decideAgentAudioCompletion(
            voiceMode = _uiState.value.voiceMode,
            observerStopped = streamObserverJob?.isActive != true,
            pendingTtsWork = pendingInTtsQueue.get(),
            hasPendingSynthFiles = pendingTtsFiles.isNotEmpty(),
            realtimePlaybackRemainingMs = effectiveRemaining,
            realtimeTailGuardRemainingMs = continuousResumeTailGuardRemainingMs(),
        )
    }

    private fun finishAgentAudioOutput() {
        continuousResumeJob = null
        stopBargeInListener()
        realtimeAmplitudeDecayJob?.cancel()
        realtimeAmplitudeDecayJob = null
        firstFrameWatchdogJob?.cancel()
        firstFrameWatchdogJob = null
        if (lastRealtimeAudioDeltaAtMs > 0L || realtimePcmPlayer?.isActive == true) {
            try { realtimePcmPlayer?.stop() } catch (_: Exception) { /* ignore */ }
            lastRealtimeAudioDeltaAtMs = 0L
        }
        speakEnvelope = 0f

        if (_uiState.value.state == VoiceState.Speaking ||
            _uiState.value.state == VoiceState.Thinking ||
            _uiState.value.state == VoiceState.Transcribing
        ) {
            _uiState.update {
                it.copy(state = VoiceState.Idle, amplitude = 0f, outputAudioActive = false)
            }
        } else if (_uiState.value.state == VoiceState.Idle) {
            _uiState.update { it.copy(amplitude = 0f, outputAudioActive = false) }
        }

        if (_uiState.value.interactionMode == InteractionMode.Continuous &&
            continuousLoopArmed &&
            _uiState.value.state == VoiceState.Idle
        ) {
            startListening()
        }
    }

    private fun continuousResumeTailGuardRemainingMs(): Long {
        val lastAudioAt = lastRealtimeAudioDeltaAtMs
        if (lastAudioAt <= 0L) return 0L
        val elapsed = System.currentTimeMillis() - lastAudioAt
        return (REALTIME_OUTPUT_RESUME_TAIL_GUARD_MS - elapsed).coerceAtLeast(0L)
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
    private fun startBargeInListenerIfEnabled(
        audioSessionIdProvider: (() -> Int)? = null,
        startupGuardMs: Long = 0L,
    ) {
        // Already running → no-op. We bracket per Speaking turn, not per
        // chunk within a turn.
        if (bargeInListener != null) {
            Log.i(TAG, "Barge-in listener already running; leaving active")
            return
        }

        val factory = bargeInListenerFactory
        val vadFactory = vadEngineFactory
        if (factory == null || vadFactory == null) {
            Log.i(TAG, "Barge-in listener unavailable; collaborators not wired")
            return
        }
        val prefs = _bargeInPrefs.value
        if (!prefs.enabled || prefs.sensitivity == BargeInSensitivity.Off) {
            Log.i(
                TAG,
                "Barge-in listener skipped; enabled=${prefs.enabled} sensitivity=${prefs.sensitivity}",
            )
            return
        }

        val sessionProvider: () -> Int = if (audioSessionIdProvider != null) {
            audioSessionIdProvider
        } else {
            val p = player
            if (p == null) {
                Log.i(TAG, "Barge-in listener skipped; legacy player not ready")
                return
            }
            fun(): Int { return p.audioSessionId }
        }

        val vad = try {
            vadFactory().also { it.setSensitivity(prefs.sensitivity) }
        } catch (t: Throwable) {
            Log.w(TAG, "VadEngine construction failed; skipping barge-in: ${t.message}")
            return
        }
        bargeInVadEngine = vad

        val listener = try {
            factory(vad, sessionProvider)
        } catch (t: Throwable) {
            Log.w(TAG, "BargeInListener construction failed; skipping barge-in: ${t.message}")
            try { vad.close() } catch (_: Throwable) { /* ignore */ }
            bargeInVadEngine = null
            return
        }

        bargeInListener = listener
        bargeInIgnoreUntilMs = if (startupGuardMs > 0L) {
            System.currentTimeMillis() + startupGuardMs
        } else {
            0L
        }
        bargeInGuardLogged = false
        bargeInListenerJob = viewModelScope.launch {
            // Fan out the two event flows on child coroutines of this job.
            launch { listener.maybeSpeech.collect { onMaybeSpeech() } }
            launch { listener.bargeInDetected.collect { onBargeInDetected() } }
        }
        Log.i(
            TAG,
            "Starting barge-in listener; sensitivity=${prefs.sensitivity} " +
                "source=${if (audioSessionIdProvider != null) "realtime_pcm" else "legacy_player"} " +
                "session=${sessionProvider()}",
        )
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
        if (bargeInListener != null) {
            Log.i(TAG, "Stopping barge-in listener")
        }
        bargeInIgnoreUntilMs = 0L
        bargeInGuardLogged = false
        bargeInListenerJob?.cancel(); bargeInListenerJob = null
        val stoppedReaderJob = try { bargeInListener?.stop() } catch (_: Throwable) { null }
        bargeInListener = null
        val vadToClose = bargeInVadEngine
        bargeInVadEngine = null
        if (vadToClose != null) {
            if (stoppedReaderJob != null) {
                stoppedReaderJob.invokeOnCompletion {
                    try { vadToClose.close() } catch (_: Throwable) { /* ignore */ }
                }
            } else {
                try { vadToClose.close() } catch (_: Throwable) { /* ignore */ }
            }
        }
        duckingWatchdog?.cancel(); duckingWatchdog = null
        // Best-effort un-duck so the next playback starts at full volume.
        if (isDucked) {
            try { player?.unduck() } catch (_: Throwable) { /* ignore */ }
            try { realtimePcmPlayer?.unduck() } catch (_: Throwable) { /* ignore */ }
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
        if (isBargeInStartupGuardActive()) return
        if (!isDucked) {
            Log.i(TAG, "Barge-in VAD maybe speech; ducking output")
            try { player?.duck() } catch (_: Throwable) { /* ignore */ }
            try { realtimePcmPlayer?.duck() } catch (_: Throwable) { /* ignore */ }
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
        if (isBargeInStartupGuardActive()) return
        duckingWatchdog?.cancel(); duckingWatchdog = null
        lastInterruptedAtChunkIndex = _currentPlayingChunkIndex.value
        // Snapshot the un-played tail NOW, before interruptSpeaking restarts
        // the consumer and its play worker clears spokenChunks out from under
        // the resume watchdog. See [pendingResumeTail].
        val resumeStartIdx = (lastInterruptedAtChunkIndex ?: -1) + 1
        pendingResumeTail = synchronized(spokenChunks) {
            val snapshot = spokenChunks.toList()
            if (resumeStartIdx in snapshot.indices) snapshot.drop(resumeStartIdx) else emptyList()
        }
        _voiceStats.update { it.copy(bargeInCount = it.bargeInCount + 1) }
        Log.i(TAG, "Barge-in detected; interrupting speech at chunk=$lastInterruptedAtChunkIndex")

        // interruptSpeaking tears down the listener itself, so subsequent
        // bargeInDetected emissions are impossible until the next
        // Speaking entry. Belt-and-braces: we also cancel the subscriber
        // job as part of stopBargeInListener which interruptSpeaking calls.
        interruptSpeaking()

        // interruptSpeaking landed us in Idle — flip to Listening and
        // pre-warm the recorder so the first ~100 ms of user speech
        // isn't clipped by recorder cold-start.
        _uiState.update {
            it.copy(
                state = VoiceState.Listening,
                outputAudioActive = false,
                error = null,
                responseText = "",
            )
        }
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

    private fun isBargeInStartupGuardActive(): Boolean {
        val remainingMs = bargeInIgnoreUntilMs - System.currentTimeMillis()
        if (remainingMs <= 0L) return false
        if (!bargeInGuardLogged) {
            Log.i(TAG, "Barge-in VAD ignored during startup guard (${remainingMs}ms remaining)")
            bargeInGuardLogged = true
        }
        return true
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
                _uiState.update {
                    it.copy(state = VoiceState.Idle, amplitude = 0f, outputAudioActive = false)
                }
                return@launch
            }

            // Silence + resume enabled → re-enqueue un-played chunks. The
            // tail was snapshotted synchronously in [onBargeInDetected]; we
            // can't re-read [spokenChunks] here because interruptSpeaking's
            // consumer restart has already cleared it (see [pendingResumeTail]).
            val tail = pendingResumeTail

            // Cancel the recorder that was pre-warmed for a user turn
            // that didn't materialize.
            try { recorder?.cancel() } catch (_: Throwable) { /* ignore */ }

            if (tail.isEmpty()) {
                lastInterruptedAtChunkIndex = null
                _uiState.update {
                    it.copy(state = VoiceState.Idle, amplitude = 0f, outputAudioActive = false)
                }
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
            _uiState.update { it.copy(state = VoiceState.Speaking, outputAudioActive = false) }

            // Send each tail chunk back through the TTS queue. The synth
            // worker will re-synthesize (our cache isn't content-addressed)
            // and the play worker will pick them up in order.
            for (chunk in tail) {
                if (!enqueueSentenceForTts(chunk)) break
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

    private fun clearVoiceHandoffStatus() {
        handoffStatusClearJob?.cancel()
        handoffStatusClearJob = null
        _uiState.update { it.copy(handoffStatus = null) }
    }

    private fun recordVoiceHandoff(event: VoiceHandoffEvent) {
        voiceHandoffReporter?.invoke(event)
        // Background-run chip: reflect the voice socket's health so a mid-run
        // drop reads as "reconnecting — task still running", not silence. The
        // relay keeps the run alive across the retry window; progress events
        // (or the resumed signal) flip the chip back to RUNNING.
        _uiState.value.backgroundRun?.let { run ->
            if (run.phase != BackgroundRunPhase.DELIVERING &&
                run.phase != BackgroundRunPhase.DONE
            ) {
                when (event.label) {
                    "Connection changed", "Waiting for route", "Trying voice route",
                    "Resume sent", "Route changed",
                    -> updateBackgroundRun { it.copy(phase = BackgroundRunPhase.RECONNECTING) }
                    "Voice reconnected" -> updateBackgroundRun {
                        it.copy(
                            phase = BackgroundRunPhase.RUNNING,
                            statusLine = "Back online — still working…",
                        )
                    }
                }
            }
        }
        val detail = when {
            !event.previousRoute.isNullOrBlank() && !event.nextRoute.isNullOrBlank() ->
                "${event.previousRoute} -> ${event.nextRoute}"
            !event.route.isNullOrBlank() && !event.detail.isNullOrBlank() ->
                "${event.route} / ${event.detail}"
            !event.route.isNullOrBlank() -> event.route
            else -> event.detail?.take(96)
        }
        val entry = VoiceHandoffTraceEntry(
            label = event.label,
            detail = detail,
        )
        val now = System.currentTimeMillis()
        handoffStatusClearJob?.cancel()
        _uiState.update { state ->
            val previousEntries = state.handoffStatus?.entries.orEmpty()
            val nextEntries = if (previousEntries.lastOrNull() == entry) {
                previousEntries
            } else {
                (previousEntries + entry).takeLast(4)
            }
            state.copy(
                handoffStatus = VoiceHandoffStatus(
                    title = event.label,
                    route = event.nextRoute ?: event.route,
                    active = event.active,
                    success = event.success,
                    entries = nextEntries,
                    updatedAtMs = now,
                )
            )
        }
        if (!event.active) {
            handoffStatusClearJob = viewModelScope.launch {
                delay(12_000L)
                _uiState.update { state ->
                    if (state.handoffStatus?.updatedAtMs == now) {
                        state.copy(handoffStatus = null)
                    } else {
                        state
                    }
                }
            }
        }
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
        _uiState.update {
            it.copy(voiceMode = true, state = VoiceState.Speaking, outputAudioActive = false)
        }
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
                pendingInTtsQueue.decrementAndGet()
            } else {
                break
            }
        }
        return out
    }

    private fun trackTtsFile(file: File) {
        ttsFileHistory.addLast(file)
        while (ttsFileHistory.size > TTS_CACHE_CAP) {
            val old = ttsFileHistory.removeAt(0)
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
                        _uiState.update {
                            it.copy(amplitude = listenEnvelope, outputAudioActive = false)
                        }
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
                        _uiState.update {
                            it.copy(
                                amplitude = speakEnvelope,
                                outputAudioActive = it.outputAudioActive ||
                                    amp > OUTPUT_AUDIO_ACTIVE_THRESHOLD,
                            )
                        }
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
        _uiState.update {
            it.copy(state = VoiceState.Error, error = msg, amplitude = 0f, outputAudioActive = false)
        }
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
            it.copy(state = VoiceState.Error, error = err.body, amplitude = 0f, outputAudioActive = false)
        }
    }

    override fun onCleared() {
        super.onCleared()
        closeRealtimeSession()
        // B4: release the listener + VAD engine native resources before
        // anything else. stopBargeInListener is defensive / idempotent.
        stopBargeInListener()
        duckingWatchdog?.cancel()
        resumeWatchdog?.cancel()
        silenceWatchdogJob?.cancel()
        silenceWatchdogJob = null
        continuousLoopArmed = false
        continuousResumeJob?.cancel()
        continuousResumeJob = null
        realtimeAmplitudeDecayJob?.cancel()
        realtimeAmplitudeDecayJob = null
        try { recorder?.cancel() } catch (_: Exception) { /* ignore */ }
        try { player?.stop() } catch (_: Exception) { /* ignore */ }
        try { realtimePcmPlayer?.stop() } catch (_: Exception) { /* ignore */ }
        try { sfxPlayer?.release() } catch (_: Exception) { /* ignore */ }
        ttsQueue.close()
        realtimeTtsQueue.close()
        streamObserverJob?.cancel()
        ttsConsumerJob?.cancel()
        realtimeTtsConsumerJob?.cancel()
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
     * The message bypasses normal assistant coalescing so outcome/status
     * speech stays immediate, but still preserves queue order by flushing any
     * pending assistant speech first.
     */
    private fun speakDispatchResult(label: String, result: LocalDispatchResult) {
        val spoken = buildDispatchResultSpoken(label, result)
        if (spoken.isBlank()) return
        _uiState.update {
            it.copy(state = VoiceState.Speaking, outputAudioActive = false, responseText = spoken)
        }
        enqueueSentenceForTts(spoken, immediate = true)
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
                        append("Grant SMS permission in Settings › Apps › Hermes-Relay › Permissions.")
                    }
                    errorCode == "permission_missing_contacts" -> buildString {
                        append("**Send SMS — permission needed**")
                        append('\n')
                        append("Grant Contacts permission to look up '")
                        append(contact ?: "?")
                        append("' in Settings › Apps › Hermes-Relay › Permissions.")
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
// Voice capture guards and sentence-boundary detection (top-level so they're
// unit-testable without instantiating an AndroidViewModel + Application).
// -------------------------------------------------------------------------

private const val MIN_VOICE_CAPTURE_DURATION_MS = 250L
private const val MIN_VOICE_CAPTURE_PCM_BYTES = 1024

internal fun shouldDiscardVoiceCapture(durationMs: Long, pcmBytes: Int): Boolean =
    durationMs < MIN_VOICE_CAPTURE_DURATION_MS || pcmBytes < MIN_VOICE_CAPTURE_PCM_BYTES

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

private const val REALTIME_FIRST_CHUNK_TARGET_CHARS = 140
private const val REALTIME_FOLLOWUP_CHUNK_TARGET_CHARS = 260
private const val REALTIME_MAX_CHUNK_CHARS = 360

/**
 * Batches normal assistant speech before it reaches provider-streamed realtime
 * voice output. The sentence extractor still owns markdown cleanup,
 * abbreviation handling, and stream-complete edge cases; this layer only
 * decides how many extracted speech chunks should share one provider render.
 *
 * Explicit status speech uses [enqueueImmediate], which first flushes any
 * pending assistant prose to preserve order, then emits the status line as its
 * own low-latency render.
 */
internal class BalancedRealtimeTtsCoalescer(
    private val firstChunkTargetChars: Int = REALTIME_FIRST_CHUNK_TARGET_CHARS,
    private val followupChunkTargetChars: Int = REALTIME_FOLLOWUP_CHUNK_TARGET_CHARS,
    private val maxChunkChars: Int = REALTIME_MAX_CHUNK_CHARS,
) {
    private val buffer = StringBuilder()
    private var emittedChunks = 0

    fun append(text: String): List<String> {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return emptyList()
        appendWithSpacing(cleaned)
        return drain(force = false)
    }

    fun enqueueImmediate(text: String): List<String> {
        val out = flush().toMutableList()
        val cleaned = text.trim()
        if (cleaned.isNotEmpty()) {
            out += cleaned
            emittedChunks += 1
        }
        return out
    }

    fun flush(): List<String> = drain(force = true)

    fun clear() {
        buffer.clear()
        emittedChunks = 0
    }

    private fun appendWithSpacing(text: String) {
        if (buffer.isNotEmpty() && !buffer.last().isWhitespace()) {
            buffer.append(' ')
        }
        buffer.append(text)
    }

    private fun drain(force: Boolean): List<String> {
        if (buffer.isEmpty()) return emptyList()

        val out = mutableListOf<String>()
        while (buffer.isNotEmpty()) {
            val target = if (emittedChunks == 0) {
                firstChunkTargetChars
            } else {
                followupChunkTargetChars
            }

            val shouldEmit = force ||
                buffer.length >= target ||
                buffer.length > maxChunkChars
            if (!shouldEmit) break

            val end = if (buffer.length <= maxChunkChars) {
                buffer.length
            } else {
                findRealtimeCoalescedBoundary(buffer, maxChunkChars)
            }
            val chunk = consumeChunk(buffer, end)
            if (chunk.isNotBlank()) {
                out += chunk
                emittedChunks += 1
            }

            if (!force && buffer.length < target) break
        }
        return out
    }
}

internal fun findRealtimeCoalescedBoundary(buffer: CharSequence, maxChars: Int): Int {
    val end = minOf(buffer.length, maxChars).coerceAtLeast(1)
    for (i in end - 1 downTo 0) {
        val ch = buffer[i]
        if (ch in SENTENCE_TERMINATORS) {
            val next = if (i + 1 < buffer.length) buffer[i + 1] else null
            if (ch == '\n' || next == null || next.isWhitespace()) {
                return i + 1
            }
        }
    }
    val secondary = findLastSecondaryBreak(buffer, end)
    if (secondary >= 0) return secondary + 1
    for (i in end - 1 downTo 1) {
        if (buffer[i].isWhitespace()) return i
    }
    return end
}

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
 * Short deterministic status line spoken when Hermes starts a tool while
 * voice mode is active. Hermes still owns the tool loop; this only prevents
 * long-running tool phases from feeling silent in the voice overlay.
 */
internal fun brokeredToolStartStatusForTts(toolName: String, indexForMessage: Int): String {
    if (indexForMessage > 0) return "I'm checking one more thing."
    val normalized = toolName.lowercase()
    return when {
        normalized.startsWith("android_") -> "I'll check the phone."
        normalized.startsWith("desktop_") ||
            normalized.contains("computer") -> "I'll check the desktop."
        normalized.contains("search") ||
            normalized.contains("browser") ||
            normalized.contains("web") -> "I'll search that now."
        normalized.contains("file") ||
            normalized.contains("read") ||
            normalized.contains("grep") ||
            normalized.contains("list") -> "I'll check the relevant files."
        normalized.contains("shell") ||
            normalized.contains("terminal") ||
            normalized.contains("bash") ||
            normalized.contains("powershell") -> "I'll run a quick check."
        else -> "Let me check that."
    }
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

internal data class AgentAudioCompletionDecision(
    val finishNow: Boolean,
    val retryDelayMs: Long? = null,
)

internal fun decideAgentAudioCompletion(
    voiceMode: Boolean,
    observerStopped: Boolean,
    pendingTtsWork: Int,
    hasPendingSynthFiles: Boolean,
    realtimePlaybackRemainingMs: Long,
    realtimeTailGuardRemainingMs: Long,
): AgentAudioCompletionDecision {
    if (!voiceMode || !observerStopped) {
        return AgentAudioCompletionDecision(finishNow = false)
    }
    if (pendingTtsWork > 0 || hasPendingSynthFiles) {
        return AgentAudioCompletionDecision(finishNow = false, retryDelayMs = 100L)
    }
    val audioDelayMs = kotlin.math.max(
        realtimePlaybackRemainingMs.coerceAtLeast(0L),
        realtimeTailGuardRemainingMs.coerceAtLeast(0L),
    )
    return if (audioDelayMs > 0L) {
        AgentAudioCompletionDecision(finishNow = false, retryDelayMs = audioDelayMs)
    } else {
        AgentAudioCompletionDecision(finishNow = true)
    }
}

/** Result of one first-frame watchdog tick. */
internal data class FirstFrameWatchdogDecision(
    val firstAudioMs: Long?,
    val reportStuck: Boolean,
    val keepWatching: Boolean,
)

/**
 * Pure decision for the realtime playback first-frame watchdog (#1). Polled on a
 * timer independent of websocket writes so a parked AudioTrack cursor is caught
 * even when the provider front-loads PCM then goes quiet.
 *
 *  - [headFrames] > 0 once playback started → first audio reached the speaker;
 *    return the elapsed time-to-first-audio and stop watching.
 *  - still 0 after [stuckThresholdMs] while the track claims to be playing →
 *    report a one-shot stuck-cursor diagnostic.
 */
internal fun evaluateFirstFrameWatchdog(
    active: Boolean,
    playbackStarted: Boolean,
    headFrames: Int,
    elapsedSinceStartMs: Long,
    playStatePlaying: Boolean,
    alreadyReportedStuck: Boolean,
    stuckThresholdMs: Long,
): FirstFrameWatchdogDecision {
    if (!active) return FirstFrameWatchdogDecision(null, reportStuck = false, keepWatching = false)
    if (!playbackStarted) return FirstFrameWatchdogDecision(null, reportStuck = false, keepWatching = true)
    if (headFrames > 0) {
        return FirstFrameWatchdogDecision(
            firstAudioMs = elapsedSinceStartMs.coerceAtLeast(0L),
            reportStuck = false,
            keepWatching = false,
        )
    }
    val stuck = !alreadyReportedStuck && playStatePlaying && elapsedSinceStartMs >= stuckThresholdMs
    return FirstFrameWatchdogDecision(null, reportStuck = stuck, keepWatching = true)
}

/**
 * Pure gate for the realtime waveform's `outputAudioActive` flag (W3).
 *
 * The decoded-PCM RMS [level] arrives before the audio is actually audible —
 * [RealtimePcmPlayer] holds a start prebuffer, so the first few deltas decode
 * (level > 0) while the AudioTrack head is still parked at frame 0. Gating
 * `outputAudioActive` on [level] therefore unfolds the UI too early.
 *
 * Instead we gate on a playback-synced signal: the playback head has actually
 * moved ([headFrames] > 0) and/or the head-tracked [playbackAmplitude] is
 * non-zero. This mirrors the basic-TTS path, where the Visualizer only reports
 * amplitude once ExoPlayer is genuinely producing audio.
 *
 * Returns true once playback has really started so the caller may flip
 * `outputAudioActive` true (it is monotonic per turn — the caller ORs it).
 */
internal fun shouldMarkRealtimeOutputActive(
    headFrames: Int,
    playbackAmplitude: Float,
): Boolean = headFrames > 0 || playbackAmplitude > 0f

/**
 * Pure decision for the W3 spoken-status throttle. Returns true when a spoken
 * status line should actually be enqueued for TTS right now, given the time of
 * the last spoken status ([lastSpokenAtMs], 0 = none yet this turn), how many
 * have already been spoken this turn ([count]), the minimum inter-status gap
 * ([gapMs]), and the per-turn cap ([maxCount]).
 *
 * Independent of the per-key dedupe — this caps cadence + volume so long /
 * tool-heavy runs don't narrate every step.
 */
internal fun shouldSpeakStatusNow(
    now: Long,
    lastSpokenAtMs: Long,
    count: Int,
    gapMs: Long,
    maxCount: Int,
): Boolean {
    if (count >= maxCount) return false
    // First spoken status of the turn (lastSpokenAtMs == 0) is always allowed.
    if (lastSpokenAtMs > 0L && now - lastSpokenAtMs < gapMs) return false
    return true
}

/** Drain cross-check (#3): estimate vs. real hardware head position. */
internal data class DrainDrift(
    val actualRemainingMs: Long,
    val effectiveRemainingMs: Long,
    val driftMs: Long,
    val earlyStopRisk: Boolean,
)

/**
 * Compares the byte-math drain estimate against the true frames still queued
 * ([framesWritten] − [headFrames]). Using the max as the effective remaining
 * prevents the "audio cut off early" failure mode where the estimate underruns
 * the real cursor. A large positive [driftMs] (estimate says done, frames
 * remain) flags that risk.
 */
internal fun playbackDrainDrift(
    estimatedRemainingMs: Long,
    framesWritten: Long,
    headFrames: Int,
    sampleRate: Int,
    riskThresholdMs: Long = 250L,
): DrainDrift {
    val remainingFrames = (framesWritten - headFrames.toLong()).coerceAtLeast(0L)
    val actualRemainingMs = if (sampleRate > 0) remainingFrames * 1000L / sampleRate else 0L
    val estimate = estimatedRemainingMs.coerceAtLeast(0L)
    val effective = maxOf(estimate, actualRemainingMs)
    val drift = actualRemainingMs - estimate
    return DrainDrift(
        actualRemainingMs = actualRemainingMs,
        effectiveRemainingMs = effective,
        driftMs = drift,
        earlyStopRisk = drift > riskThresholdMs,
    )
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
