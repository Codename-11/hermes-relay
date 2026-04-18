# Plan: Voice Barge-In

> **Purpose.** Let the user interrupt TTS by speaking. Build on top of the voice-quality-pass cancellation infrastructure (V4's `interruptSpeaking()` and V5's ExoPlayer audio-session-id exposure) to add duplex audio capture, VAD-triggered cutoff, volume ducking, and optional resume-from-next-sentence.
>
> **Scope model.** Stacked feature branch (`feature/voice-barge-in`) rooted at `feature/voice-quality-pass`, in a new worktree at `../hermes-android-barge-in`. Single session, agent team. Merges *after* voice-quality-pass lands.
>
> **Origin.** Follow-on from 2026-04-17 voice-quality-pass session. Bailey flagged that current conversation UX lags ChatGPT/Claude app because we can't interrupt TTS — user must wait for the agent to finish. Classic industry pattern: AEC + VAD + hysteresis + ducking + optional resume.
>
> **Branching.** From `feature/voice-quality-pass` (not `origin/main`) because this work architecturally depends on: (a) V4's supervisor-scoped cancellation + `pendingTtsFiles` cleanup, (b) V5's ExoPlayer `audioSessionId` exposure (needed for `AcousticEchoCanceler.create(sessionId)`), (c) V3's sentence-chunk tracking (needed for resume-from-next-sentence). If voice-quality-pass changes significantly in review, rebase this branch.
>
> **Flavor.** Both (`googlePlay` + `sideload`). Default-off at launch on both.

## Scope legend

| Tier | Meaning |
|------|---------|
| **B** | Barge-in feature work — primary focus of this plan. |
| **Doc** | Documentation surface change. |

**Effort sizing.** S / M / L as usual.

## Sequencing & parallelism

| Wave | Units | Mode | Why |
|------|-------|------|-----|
| **Wave 1** | B1, B2, B6 | **Parallel** | Disjoint files — DataStore module, new VAD engine module, single-method add to VoicePlayer. |
| **Wave 2** | B3, B5 | **Parallel** | B3 depends on B2 (committed in Wave 1). B5 depends on B1 (committed in Wave 1). They touch disjoint files. |
| **Wave 3** | B4 | Serial | Integration across `VoiceViewModel` — depends on all of B1, B2, B3, B6. The shared-file hot-spot. |
| **Wave 4** | Doc2 | Serial | Everything else stabilizes first. |

**Shared-file hot-spot.** `VoiceViewModel.kt` — only B4 touches it. Other units touch disjoint files.

## External dependency

**`com.github.gkonovalov:android-vad:silero:2.0.8`** (or latest) — bundled Silero VAD model for Android. Adds ~2.2 MB to the APK. Alternative: `vad-webrtc` variant (~120 KB) if APK size becomes a Play Store issue — same API, swap artifact only.

---

# Wave 1 — Parallel: prefs, VAD, ducking

## B1. `BargeInPreferences` DataStore

- **Status:** `[ ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** S · **Effort:** Kotlin

**Summary.** New preferences surface for barge-in settings, mirroring the existing voice-preferences pattern.

**Scope / Acceptance criteria.**
- New file `app/src/main/kotlin/com/hermesandroid/relay/data/BargeInPreferences.kt`.
- Data class `BargeInPreferences(enabled: Boolean = false, sensitivity: BargeInSensitivity = BargeInSensitivity.Default, resumeAfterInterruption: Boolean = true)`.
- `enum class BargeInSensitivity { Off, Low, Default, High }` — maps to VAD threshold + hysteresis tuning. `Off` means the whole feature is disabled even if `enabled = true` (UI convenience).
- DataStore-backed repository following the existing pattern (search for `BridgeSafetyPreferences.kt` as a structural reference).
- `StateFlow<BargeInPreferences>` exposed via a repository / provider module.
- Unit tests in `app/src/test/kotlin/com/hermesandroid/relay/data/BargeInPreferencesTest.kt` covering defaults, set/get round-trip per field, and enum serialization.

**Files to touch.**
- `app/src/main/kotlin/com/hermesandroid/relay/data/BargeInPreferences.kt` (new)
- `app/src/test/kotlin/com/hermesandroid/relay/data/BargeInPreferencesTest.kt` (new)

**Agent brief.**
> Implement B1 per `docs/plans/2026-04-17-voice-barge-in.md`. Read `CLAUDE.md` first and `app/src/main/kotlin/com/hermesandroid/relay/data/BridgeSafetyPreferences.kt` as a structural reference for DataStore patterns. Create `BargeInPreferences.kt` + DataStore repository. Write unit tests. Commit on `feature/voice-barge-in` as `feat(voice): add BargeInPreferences DataStore`.

**Dependencies.** None.

---

## B2. Silero VAD engine

- **Status:** `[ ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** M · **Effort:** Kotlin + Gradle

**Summary.** Lightweight VAD engine that eats 30 ms audio frames and emits a "speech probability" Flow. Wraps the android-vad Silero artifact but hides it behind our own interface so we can swap implementations.

**Scope / Acceptance criteria.**
- Add `com.github.gkonovalov:android-vad:silero:2.0.8` (or latest stable) to `gradle/libs.versions.toml` + `app/build.gradle.kts`.
- New `app/src/main/kotlin/com/hermesandroid/relay/audio/VadEngine.kt`:
  - `class VadEngine(context: Context, sampleRate: Int = 16000)` — owns the Silero `VadSilero` instance.
  - `fun analyze(frame: ShortArray): VadResult` — synchronous single-frame call. Returns `VadResult(isSpeech: Boolean, probability: Float)`.
  - `fun setSensitivity(sensitivity: BargeInSensitivity)` — maps enum to `(threshold, attack_ms, release_ms, consecutive_frames)` tuple. Sensible defaults:
    - `Off` → no-op (always returns isSpeech=false).
    - `Low` → `threshold=0.85, attack=80ms, release=300ms, consecutive=3`.
    - `Default` → `threshold=0.7, attack=50ms, release=250ms, consecutive=2`.
    - `High` → `threshold=0.5, attack=30ms, release=200ms, consecutive=1`.
  - `fun close()` — release native resources.
- Internal hysteresis state: track consecutive speech/silence frame counts and only flip `isSpeech=true` after N consecutive speech frames (the "debounce" from the pattern discussion). This is NOT done by the upstream library — we layer it on top.
- Unit tests: `app/src/test/kotlin/com/hermesandroid/relay/audio/VadEngineTest.kt`:
  - Feeding silence (zeros) → isSpeech=false.
  - Synthetic tone burst → isSpeech=true after debounce frames pass.
  - Sensitivity=Off always returns false regardless of input.
  - Hysteresis: isolated speech frame ≤ debounce threshold doesn't trigger.

**Files to touch.**
- `gradle/libs.versions.toml` (add `androidVad` version key)
- `app/build.gradle.kts` (add dep)
- `app/src/main/kotlin/com/hermesandroid/relay/audio/VadEngine.kt` (new)
- `app/src/test/kotlin/com/hermesandroid/relay/audio/VadEngineTest.kt` (new)

**Implementation notes.**
- android-vad's Silero model is bundled in the AAR — no runtime download or external model file.
- Do NOT expose the android-vad `VadSilero` type in our public interface. Wrap entirely so we can swap to `vad-webrtc` without touching callers.
- The library's frame size constraint: Silero requires frames of specific sizes (`80`, `160`, `320` samples at 8kHz; `160`, `320`, `640`, `1024` at 16kHz). We target 16kHz/32ms frames = 512 samples — the closest supported is 640 samples (40ms). Use 640.
- Callers will feed 20–40 ms frames in a tight loop from B3. The engine must be *cheap* per call — no allocations inside `analyze()` beyond the VadResult.

**Agent brief.**
> Implement B2 per `docs/plans/2026-04-17-voice-barge-in.md`. Read `CLAUDE.md`. Add android-vad Silero dep. Implement `VadEngine` wrapping `VadSilero` with hysteresis debouncer applied on top of the library's raw output. Map sensitivity enum values to `(threshold, attack, release, consecutive)` tuples per the plan. Synchronous `analyze(ShortArray)` API. Write unit tests. Commit on `feature/voice-barge-in` as `feat(voice): add Silero VAD engine for barge-in`.

**Dependencies.** None (doesn't use B1 prefs directly — B4 wires them together).

---

## B6. `VoicePlayer.setVolume()` + duck helpers

- **Status:** `[ ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** S · **Effort:** Kotlin

**Summary.** Expose ExoPlayer's `setVolume(Float)` through VoicePlayer, plus `duck()` / `unduck()` convenience helpers. Barge-in uses soft ducking when VAD is "maybe" speech (1 frame, below debounce threshold) before hard-stopping.

**Scope / Acceptance criteria.**
- Add three methods to `VoicePlayer.kt`:
  - `fun setVolume(volume: Float)` — 0f..1f, clamp, forward to `exoPlayer.volume`.
  - `fun duck()` — `setVolume(0.3f)`.
  - `fun unduck()` — `setVolume(1.0f)`.
- Add a `@Volatile private var` tracking current volume so state survives across internal ExoPlayer reconfig calls (there aren't any today, but future-proof).
- Add KDoc notes explaining the ducking use case.
- Add one unit test in the existing `VoicePlayerTest.kt` that verifies `duck()` sets volume to 0.3 and `unduck()` restores to 1.0 on the mocked ExoPlayer.

**Files to touch.**
- `app/src/main/kotlin/com/hermesandroid/relay/audio/VoicePlayer.kt`
- `app/src/test/kotlin/com/hermesandroid/relay/audio/VoicePlayerTest.kt`

**Agent brief.**
> Implement B6 per `docs/plans/2026-04-17-voice-barge-in.md`. Read `CLAUDE.md`. Add `setVolume`, `duck`, `unduck` methods to `VoicePlayer`. Add one unit test in the existing `VoicePlayerTest.kt`. Commit on `feature/voice-barge-in` as `feat(voice): add VoicePlayer ducking helpers`.

**Dependencies.** None.

---

# Wave 2 — Parallel: duplex audio + settings UI

## B3. `BargeInListener` — duplex audio capture

- **Status:** `[ ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** M · **Effort:** Kotlin

**Summary.** Continuously capture mic audio while TTS plays. Run each frame through B2's `VadEngine`. Emit `bargeInDetected` events. Handles AEC + NoiseSuppressor wiring.

**Scope / Acceptance criteria.**
- New file `app/src/main/kotlin/com/hermesandroid/relay/audio/BargeInListener.kt`.
- Uses `AudioRecord` (NOT `MediaRecorder` — need raw PCM for VAD). Config: 16kHz mono 16-bit PCM.
- Constructor takes `context: Context`, `vadEngine: VadEngine`, `audioSessionId: Int` (from VoicePlayer, so AEC can cancel TTS audio).
- Lifecycle:
  - `suspend fun start()` — allocates `AudioRecord`, attaches `AcousticEchoCanceler.create(audioSessionId)` and `NoiseSuppressor.create(audioSessionId)` if available, starts reading frames in a coroutine loop, feeds each 640-sample frame to `vadEngine.analyze()`.
  - `fun stop()` — cancels loop, releases AudioRecord + AEC + NoiseSuppressor.
- Emits via two Flows:
  - `val bargeInDetected: SharedFlow<Unit>` — fires when VAD says speech (post-hysteresis).
  - `val maybeSpeech: SharedFlow<Unit>` — fires on a single speech frame BEFORE hysteresis passes (for ducking).
- Graceful degradation:
  - If `AcousticEchoCanceler.isAvailable() == false` — log at INFO, continue without AEC, bump hysteresis thresholds via VadEngine sensitivity if possible (or just accept more false positives).
  - If `AudioRecord.getState() != STATE_INITIALIZED` — log error, emit nothing, don't crash.
- Unit tests: `app/src/test/kotlin/com/hermesandroid/relay/audio/BargeInListenerTest.kt`:
  - Mock `VadEngine` returning a scripted sequence of VadResults.
  - Mock `AudioRecord` via a test-only constructor path that accepts a pre-recorded byte stream (or use an interface seam — your call).
  - Verify `bargeInDetected` fires only when VAD positive post-hysteresis.
  - Verify `maybeSpeech` fires on first positive frame.
  - Verify `stop()` cancels the loop within a bounded time.

**Files to touch.**
- `app/src/main/kotlin/com/hermesandroid/relay/audio/BargeInListener.kt` (new)
- `app/src/test/kotlin/com/hermesandroid/relay/audio/BargeInListenerTest.kt` (new)

**Implementation notes.**
- `AudioRecord` initialization on newer Android (≥ Q) needs `MediaRecorder.AudioSource.VOICE_COMMUNICATION` for AEC to apply. `MIC` source bypasses system AEC.
- The coroutine reading loop should be cancellable. Use `isActive` checks and `withContext(Dispatchers.IO)` wrapper for the blocking `AudioRecord.read()` call.
- Frame size: 640 samples = 40 ms at 16kHz. That's our VAD frame rate.
- The `audioSessionId` from VoicePlayer may be 0 until ExoPlayer allocates its audio track (V5 noted this gotcha and deferred Visualizer attach to `onIsPlayingChanged(true)`). If `sessionId == 0` at start(), wait on a StateFlow for it to become non-zero before creating the AEC.
- Do NOT directly observe `VoicePlayer` from here — B4 will coordinate. Just accept the sessionId as a parameter.

**Agent brief.**
> Implement B3 per `docs/plans/2026-04-17-voice-barge-in.md`. Read `CLAUDE.md`. B2 is committed — read `app/src/main/kotlin/com/hermesandroid/relay/audio/VadEngine.kt` to understand the interface you're consuming. Build `BargeInListener` as specified: 16kHz mono AudioRecord with `VOICE_COMMUNICATION` source, AEC + NoiseSuppressor attached when available, feeds 640-sample frames to VadEngine, emits two SharedFlows. Handle the case where audioSessionId starts at 0. Write unit tests. Commit on `feature/voice-barge-in` as `feat(voice): add duplex audio listener with AEC for barge-in`.

**Dependencies.** B2 (needs `VadEngine`).

---

## B5. Voice Settings UI section

- **Status:** `[ ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** S · **Effort:** Compose

**Summary.** New section in the Voice Settings screen: enable toggle, sensitivity picker, resume sub-toggle, compatibility hint.

**Scope / Acceptance criteria.**
- Find the existing Voice Settings screen (likely `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/VoiceSettingsScreen.kt` or similar — search if not).
- Add a new section titled "Interruption" or "Barge-in" with the following controls:
  - `Switch` — "Interrupt when I speak" bound to `BargeInPreferences.enabled`.
  - If enabled: show below it
    - `DropdownMenu` — "Sensitivity" with options matching `BargeInSensitivity` enum (`Off`, `Low`, `Default`, `High`).
    - `Switch` — "Resume after interruption" bound to `BargeInPreferences.resumeAfterInterruption`.
    - Informational hint text: "Works best with headphones. On some phones the speaker may false-trigger."
  - Compatibility hint: probe `AcousticEchoCanceler.isAvailable()` at screen init. If false, show a warning-styled badge next to the toggle: "Your device may have limited echo cancellation. Barge-in quality will vary." Don't prevent enabling — just set expectations.
- Wire to the `BargeInPreferences` repository from B1 via a ViewModel (create a dedicated `VoiceSettingsViewModel` if the existing screen lacks one, or extend what's there).
- Don't write a UI test — our convention skips on-screen tests.

**Files to touch.**
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/VoiceSettingsScreen.kt` (existing — extend)
- Possibly a dedicated `VoiceSettingsViewModel` if one doesn't exist yet.

**Agent brief.**
> Implement B5 per `docs/plans/2026-04-17-voice-barge-in.md`. Read `CLAUDE.md`. B1 is committed — read `BargeInPreferences.kt` to understand the repo interface. Find the Voice Settings screen (search for `VoiceSettings` under `ui/screens/`) and add the Interruption section with toggle, sensitivity picker, resume sub-toggle, compatibility hint based on `AcousticEchoCanceler.isAvailable()`. Material 3 / Compose idioms as the rest of the file. Commit on `feature/voice-barge-in` as `feat(voice): add barge-in Voice Settings section`.

**Dependencies.** B1 (needs `BargeInPreferences`).

---

# Wave 3 — Serial: integration

## B4. `VoiceViewModel` integration + resume logic

- **Status:** `[ ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** M · **Effort:** Kotlin

**Summary.** Wire `BargeInListener` lifecycle to `VoiceState.Speaking`. On `bargeInDetected` → call `interruptSpeaking()`. On `maybeSpeech` → call `voicePlayer.duck()`. Implement resume-from-next-sentence.

**Scope / Acceptance criteria.**
- Inject `BargeInPreferences` repository + `VadEngine` + `BargeInListener` (DI or factory — match what the rest of VoiceViewModel uses for collaborators).
- **Lifecycle wiring:**
  - When `VoiceState` transitions INTO `Speaking` AND `BargeInPreferences.enabled == true` AND `sensitivity != Off` → start `BargeInListener`.
  - When `VoiceState` leaves `Speaking` (any reason) → stop listener.
- **Trigger handling:**
  - Subscribe to `listener.maybeSpeech` → `voicePlayer.duck()` (but only if ducking isn't already active).
  - Subscribe to `listener.bargeInDetected` → 
    1. Capture the currently-playing chunk index into a `lastInterruptedAtChunkIndex` field.
    2. Call existing `interruptSpeaking()` (already handles queue cancel + cache cleanup).
    3. Flip state to `Listening`.
    4. Pre-warm `VoiceRecorder` — start it immediately so the first ~100 ms of user speech isn't clipped by cold-start.
- **Resume logic:** 
  - Track the *list* of sentence chunks the consumer has received for this response turn (new `spokenChunks: MutableList<String>` indexed by synth-worker order).
  - On barge-in, record `lastInterruptedAtChunkIndex = currentlyPlayingIndex`.
  - Start a 600 ms timer: if the user continues speaking (next VAD trigger or VoiceRecorder receives audio frames within 600 ms) → clear `lastInterruptedAtChunkIndex`, let the new turn proceed normally.
  - If 600 ms passes without further user speech AND `BargeInPreferences.resumeAfterInterruption == true` AND `lastInterruptedAtChunkIndex != null`:
    - Re-enqueue the un-played chunks: `spokenChunks.drop(lastInterruptedAtChunkIndex + 1).forEach { ttsQueue.send(it) }`.
    - Flip state back to `Speaking`.
    - Un-duck (in case it was ducked).
  - On fresh user speech (new turn begins properly) → clear `spokenChunks` + `lastInterruptedAtChunkIndex` via the normal turn-reset path.
- **Chunk index tracking:** V3's chunker already coalesces into sentence-scale chunks. You need to track which chunk is *currently playing* at any moment — this means the play worker (from V4) needs to emit a `currentChunkIndex: StateFlow<Int>` or similar. Touch V4's code as needed to add that, but keep the scope tight.
- **Ducking / unduck coordination:** When `maybeSpeech` ducks, schedule a watchdog that un-ducks after 500 ms if no `bargeInDetected` follows. This prevents stuck ducking if VAD gives a single false-positive frame that never passes hysteresis.
- Tests: `app/src/test/kotlin/com/hermesandroid/relay/voice/VoiceViewModelBargeInTest.kt`:
  - `bargeInDetected` while Speaking → interruptSpeaking called, state → Listening.
  - Resume with `resumeAfterInterruption=true` and no further speech → chunks re-enqueued.
  - Resume with `resumeAfterInterruption=true` and continued speech → NO re-enqueue.
  - Resume with `resumeAfterInterruption=false` → NO re-enqueue even on silence.
  - `maybeSpeech` calls `voicePlayer.duck()`.
  - Ducking watchdog un-ducks after timeout if no follow-up detection.

**Files to touch.**
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/VoiceViewModel.kt`
- `app/src/test/kotlin/com/hermesandroid/relay/voice/VoiceViewModelBargeInTest.kt` (new)

**Implementation notes.**
- Don't break V4's supervisor-scoped worker lifecycle. The BargeInListener runs in its own coroutine lifecycle (parallel to the synth/play workers), but cleanup on interrupt still goes through `interruptSpeaking()`.
- `VoicePlayer.audioSessionId` — if it's still 0 when `BargeInListener.start()` is called (ExoPlayer lazy), pass a Flow/function so the listener can read it dynamically. Alternatively: delay listener start by 100 ms and re-check. Whichever is cleaner.
- Don't instantiate `BargeInListener` eagerly in VoiceViewModel's init — create it lazily on first `VoiceState.Speaking` transition. Saves cold-start work and mic permission surface area when barge-in is off.

**Agent brief.**
> Implement B4 per `docs/plans/2026-04-17-voice-barge-in.md`. Read `CLAUDE.md`. B1, B2, B3, B6 are all committed — read each before starting so you know the interfaces. Wire `BargeInListener` into `VoiceViewModel`'s `VoiceState.Speaking` lifecycle. Duck on `maybeSpeech`, interrupt + pre-warm recorder on `bargeInDetected`. Implement resume-from-next-sentence by tracking `spokenChunks` + `lastInterruptedAtChunkIndex`. Add a ducking watchdog to un-duck after 500ms if no trigger follows. Touch V4's play worker as needed to emit `currentChunkIndex`. Write `VoiceViewModelBargeInTest.kt` with the 6 cases listed in the plan. Commit on `feature/voice-barge-in` as `feat(voice): wire barge-in to VoiceState lifecycle with resume support`.

**Dependencies.** B1, B2, B3, B6 (all Wave 1 / Wave 2 units).

---

# Wave 4 — Docs

## Doc2. DEVLOG + CHANGELOG + spec + voice.md

- **Status:** `[ ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** S · **Effort:** Markdown

**Summary.** Capture the wave. Unlike the voice-quality-pass docs pass, this one DOES update `user-docs/features/voice.md` because barge-in is a user-facing feature with a settings UI they need to discover.

**Scope.**
- `DEVLOG.md`: append 2026-04-17 entry (second of the day) summarizing the barge-in stack.
- `CHANGELOG.md`: add to `[Unreleased]` under an `### Added — Barge-in` section.
- `docs/spec.md` Phase V section: reference the new barge-in architecture (duplex audio, VAD, AEC, resume).
- `user-docs/features/voice.md`: add a new "## Barge-in (interrupt the agent)" section with screenshot placeholder, settings description, device compatibility note.

**Files to touch.**
- `DEVLOG.md`, `CHANGELOG.md`, `docs/spec.md`, `user-docs/features/voice.md`.

**Dependencies.** All B units committed.

---

# Definition of done

- All 6 B units + Doc2 committed on `feature/voice-barge-in`.
- Android build succeeds in Studio (Bailey verifies).
- Barge-in toggle visible in Voice Settings. Default off. AEC-missing badge shows on devices without `AcousticEchoCanceler`.
- With toggle on + sensitivity=Default + headphones, speaking during TTS stops playback within ~100 ms. With silence after, TTS resumes from the next sentence if "Resume" enabled.
- A single PR is open from `feature/voice-barge-in` → `main`. PR body notes the stacked-on-voice-quality-pass dependency.
