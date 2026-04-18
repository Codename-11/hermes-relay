# Hermes-Relay v0.5.1

**Release Date:** April 18, 2026
**Since v0.5.0:** Voice-focused patch release — TTS quality pass, conversational barge-in, silence-based auto-stop, plus a bootstrap crash fix

> **The voice release.** v0.5.0 shipped the bridge polish; v0.5.1 makes voice mode feel like a real conversation. Gapless ExoPlayer playback, client + relay sanitizers so the agent stops reading emoji and markdown fences aloud, sentence-prefetch so there's no dead air between chunks, barge-in so you can interrupt by just speaking, and silence-based auto-stop so Continuous mode actually ends your turn when you stop talking.

---

## 📥 Download

v0.5.1 ships in **two build flavors**. APK filenames are version-tagged:

| Flavor | File | Who it's for |
|---|---|---|
| **sideload** (recommended) | `hermes-relay-0.5.1-sideload-release.apk` | Full feature set — bridge channel, voice intents, unattended access, vision-driven `android_navigate`. Installs alongside the Play build with a `.sideload` applicationId. |
| **Google Play** | `hermes-relay-0.5.1-googlePlay-release.aab` | Conservative feature set (chat, voice, safety rails — no agent device control) to match Play Store's Accessibility policy. |
| googlePlay APK | `hermes-relay-0.5.1-googlePlay-release.apk` | Parity + diff tooling — not the primary download. |
| sideload AAB | `hermes-relay-0.5.1-sideload-release.aab` | Parity + diff tooling — not the primary download. |

**Verify integrity** with `SHA256SUMS.txt` from the same release. See the [Sideload guide](https://codename-11.github.io/hermes-relay/guide/getting-started.html#sideload-apk) for install steps.

---

## ✨ Highlights

### Voice quality pass

- **Gapless TTS playback.** Swapped `MediaPlayer` for Media3 `ExoPlayer` with a persistent instance and `addMediaItem` queuing. No more 200–400 ms silence between sentence chunks, no more pop / click on chunk boundaries.
- **Client + relay text sanitizers.** Assistant output is stripped of markdown fences, tool-call annotations (`` `💻 terminal` ``), URLs, and emoji *before* hitting ElevenLabs — on the relay (`plugin/relay/tts_sanitizer.py`) and on the phone (`VoiceViewModel.sanitizeForTts`). The chat UI still shows emoji; only the voice path is cleaned. Solves "agent reads `colon rocket` out loud" and "agent reads `https colon slash slash github dot com`."
- **Sentence coalescing + secondary-break chunking.** Minimum 40-char chunks with 800 ms idle flush; ellipses / dashes treated as soft breaks when the primary sentence end is far away. Keeps prosody natural without waiting for a full paragraph.
- **Prefetch synth-while-playing pipeline.** Two coroutines in a `supervisorScope` — one synthesizing the next sentence while the previous one plays. Channel-backed with capacity 2 so the player never starves.

### Voice barge-in (off by default, opt-in via Voice Settings)

- **Silero VAD + AcousticEchoCanceler + hysteresis.** Duplex AudioRecord (`VOICE_COMMUNICATION` source) monitored by a Silero VAD engine with 2–3 consecutive-frame hysteresis. AEC binds to the ExoPlayer audio session id so the VAD sees your voice, not the agent's playback echo.
- **Soft-duck → hard-cut interrupt.** Single VAD positive triggers a 30 % volume duck; confirmed hysteresis pass hard-cuts playback and starts a new listening turn. 500 ms duck-watchdog un-ducks on a single-frame false positive so stray clicks only briefly dip the volume.
- **Optional resume-from-next-sentence.** After a barge-in interrupt, a 600 ms silence watchdog checks whether the user actually continued speaking. If not (cough, false positive, stray laugh), the remaining un-played sentences are re-queued. Toggle in Voice Settings.
- **Sensitivity picker (Off / Low / Default / High)** with an always-visible AEC compatibility badge so users know when their device's echo canceler isn't loaded (affects false-positive rate on some Samsung / Motorola / older Pixel builds).

### Silence-based auto-stop for listening turns

- **The `silenceThresholdMs` preference is finally wired.** Previously the Settings slider persisted a value nothing ever read — Continuous mode would re-arm the mic after TTS drained and then wait forever for a manual tap to send. Now `VoiceViewModel.startListening()` arms a watchdog that polls amplitude every 150 ms and auto-calls `stopListening()` after the configured silence window (default 3 s) following at least one above-floor frame.
- **Grace window** — auto-stop never fires before the user's first above-floor frame, so "tap mic, take a beat" doesn't insta-close the turn.
- **Skipped in Hold-to-Talk.** The physical release is the authoritative stop there; auto-stopping mid-hold would be surprising.

---

## 🔧 Fixes

- **"Final short sentence with emoji not spoken in Continuous mode."** Race in `maybeAutoResume` where Continuous mode's `startListening()` → `player.stop()` clobbered the still-in-flight final chunk's playback pipeline. Fixed with an `AtomicInteger` gate on the synth queue; auto-resume now defers until the TTS pipeline actually drains.
- **Continuous mode didn't persist across app restarts.** `VoiceViewModel` never subscribed to `VoicePreferencesRepository.settings.interactionMode`, so cold starts always defaulted to Tap-to-Talk regardless of the saved pref. Now subscribes on `initialize` and mirrors the saved value into `uiState`.
- **Bootstrap gateway crash: `'tuple' object has no attribute 'freeze'`.** `hermes_relay_bootstrap/_command_middleware.py::maybe_install_middleware` was replacing aiohttp's `FrozenList` with a plain tuple, which broke when `AppRunner.setup()` later called `.freeze()`. Switched to in-place `app._middlewares.append(middleware)`. 31/31 middleware tests pass.

---

## 🧪 Verification checklist (post-install)

- Voice mode → ask the agent a multi-sentence question. No gap / click between sentences; no emoji or markdown spoken aloud.
- Speak over the agent mid-response with barge-in ON → playback cuts within ~100 ms, a new listening turn starts.
- Briefly cough during playback with barge-in ON and "Resume after interruption" ON → playback ducks briefly but resumes from the next unplayed sentence.
- Voice Settings → Interaction Mode → **Continuous** → force-stop the app → relaunch → Voice mode still comes up in Continuous.
- Voice Settings → Silence Threshold slider at 3 s → start a Tap-to-Talk turn → speak one sentence → stop talking → within ~3 s the turn auto-submits.
- Device without AEC → Voice Settings shows the compatibility badge next to the Barge-in section.

## 🧩 Known — test suite deferred

8 new voice/audio unit tests added in this release are `@Ignore`'d pending a test-infra follow-up. See [issue #32](https://github.com/Codename-11/hermes-relay/issues/32) for the root-cause breakdown (coroutine `.cancel()` without `.join()` + Media3 static init on pure JVM + Robolectric classloader leakage). No app-behavior impact — the tests describe intent + assertions for the new voice code and will be un-`@Ignore`'d once the separate-source-set split lands. On-device smoke testing (by Bailey, Samsung) validated the feature behavior.

See `CHANGELOG.md` for the full file-level diff and `DEVLOG.md` for the per-feature session narrative.

---

🤖 Generated with [Claude Code](https://claude.com/claude-code)
