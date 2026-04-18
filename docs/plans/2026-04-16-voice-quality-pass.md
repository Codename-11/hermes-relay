# Plan: Voice Quality Pass

> **Purpose.** A single-session, agent-team implementation plan for the voice-mode quality improvements diagnosed on 2026-04-16. Addresses the four symptom classes Bailey reported — **clear↔muffled switching, volume drift, mid-response pauses, jumbled letters** — by closing the gaps between our chunked-per-sentence TTS pipeline and how ChatGPT/Claude-class voice experiences are built.
>
> **Scope model.** One feature branch (`feature/voice-quality-pass`) in a git worktree. One session. Multiple agents coordinating via commits on the shared branch — not per-unit sub-branches like the bridge-expansion plan. Agents working on the same file must sequence; work on disjoint files can run in parallel. One PR at session end.
>
> **Origin.** Diagnosis session 2026-04-16 that traced the voice pipeline end-to-end: Android `VoiceViewModel` chunking → `RelayVoiceClient.synthesize` → relay `/voice/synthesize` → upstream `tools/tts_tool.py::text_to_speech_tool` → ElevenLabs. Key findings: (1) the upstream `_strip_markdown_for_tts()` exists but is only called from the CLI `stream_tts_to_speaker()` path — our relay bypasses it; (2) server config uses `eleven_multilingual_v2` which has high per-call prosody variance — wrong model for a chunked pipeline; (3) `_generate_elevenlabs()` ignores `VoiceSettings` (no `stability`, `similarity_boost`, `use_speaker_boost`); (4) client re-creates `MediaPlayer` per sentence — hard audio seam + codec re-init; (5) no synthesize↔playback pipelining, so every sentence boundary eats a network RTT.
>
> **Related files.**
> - [`CLAUDE.md`](../../CLAUDE.md) — project conventions every implementing agent reads first
> - [`docs/spec.md`](../spec.md) — protocol / voice surface reference
> - [`ROADMAP.md`](../../ROADMAP.md) — where this pass sits in the broader arc
> - [`docs/plans/2026-04-13-bridge-feature-expansion.md`](./2026-04-13-bridge-feature-expansion.md) — precedent for plan-file format
> - [`DEVLOG.md`](../../DEVLOG.md) — append session entry on completion
> - Upstream reference: `~/.hermes/hermes-agent/tools/tts_tool.py` on hermes-host (`bailey@172.16.24.250`)

## How to use this file

1. **Bailey:** walk each work unit, mark status checkbox (`[x] Now` / `[x] Next` / `[x] Later` / `[x] Skip`). Leave inline notes to override scope.
2. **Orchestrator agent:** creates the worktree + branch per [Worktree setup](#worktree-setup), then dispatches work units per the [Sequencing](#sequencing--parallelism) plan. The orchestrator owns the working tree; subagents are given narrow, self-contained tasks and asked to return diffs or land commits on the shared branch.
3. **Session end:** run acceptance checklist, update `DEVLOG.md` + `CHANGELOG.md`, open one PR for the whole branch with all unit IDs in the body.

## Worktree setup

```bash
# From the main checkout on Windows (C:\Users\Bailey\Desktop\Open-Projects\hermes-android)
git fetch origin
git worktree add ../hermes-android-voice feature/voice-quality-pass origin/main
cd ../hermes-android-voice
```

- **Branch:** `feature/voice-quality-pass` — one branch, the whole session lands on it.
- **Worktree path:** `../hermes-android-voice` (sibling of main checkout, outside the tracked tree).
- **Cleanup:** after the PR merges, `git worktree remove ../hermes-android-voice && git branch -d feature/voice-quality-pass`.
- **Why a worktree, not just a branch:** keeps Bailey's Android Studio project (pointed at the main checkout) pristine while agents churn. Studio opens cleanly from either path if needed for verification.

## Scope legend

| Tier | Meaning |
|------|---------|
| **V** | Voice-pipeline improvement — primary focus of this plan. |
| **Cfg** | Runtime configuration change only. No code in this repo; documented for operator execution. |
| **Up** | Upstream change in `hermes-agent` repo. Separate PR against `NousResearch/hermes-agent`. Lands out-of-band of this worktree's PR but runs in the same session. |
| **Doc** | Documentation surface change. |

**Effort sizing.** **S** = single-file change, **M** = multi-file / multi-layer change, **L** = new subsystem or cross-cutting.

**Flavor impact.** All units here are **both** (googlePlay + sideload) — voice mode is available on both flavors.

## Sequencing & parallelism

| Wave | Units | Mode | Why |
|------|-------|------|-----|
| **Wave 0** | Cfg1 | Manual, serial | Operator runs this on hermes-host before the code work starts so A/B baseline is cleaner. |
| **Wave 1** | V1, V5, Up1 | **Parallel** | Disjoint files: V1 touches only `plugin/relay/voice.py`, V5 touches only `VoicePlayer.kt` + Gradle, Up1 is a different repo entirely. |
| **Wave 2** | V2 | Serial | Extends `VoiceViewModel.stripMarkdown()` → must land before V3 because V3's coalesce logic runs against sanitized text. |
| **Wave 3** | V3 | Serial | Chunking changes to `VoiceViewModel.kt` — conflicts with V4. |
| **Wave 4** | V4 | Serial | Pipelining rewrite of `VoiceViewModel.startTtsConsumer()` — takes the output of V3's chunker as input. |
| **Wave 5** | Doc1 | Serial | `DEVLOG.md` + `CHANGELOG.md` + `user-docs/` updates after code stabilizes. |

**Shared-file hot-spot.** `VoiceViewModel.kt` is touched by V2, V3, and V4. These **must** serialize — do not parallelize them. The orchestrator should handle them in sequence (V2→V3→V4) as three commits from one agent session, not three concurrent subagents.

---

# Wave 0 — Baseline

## Cfg1. Switch ElevenLabs model to `eleven_flash_v2_5`

- **Status:** `[ ] Now` `[ ] Next` `[ ] Later` `[ ] Skip` `[ X ] Completed`
- **Size:** S · **Where:** hermes-host (not in this repo)

**Summary.** Flip `tts.elevenlabs.model_id` in `~/.hermes/config.yaml` from `eleven_multilingual_v2` to `eleven_flash_v2_5`.

**Why.** `eleven_multilingual_v2` is ElevenLabs' expressive long-form model — designed for whole-paragraph generation where prosody can breathe. In a chunked pipeline it *re-interprets* each sentence independently, producing audible tone/volume/clarity variance between calls. `eleven_flash_v2_5` is the streaming-optimized model — ~75ms per-request latency, lower variance across calls, designed exactly for sentence-by-sentence pipelines like ours. This single flip likely accounts for 60–70% of the perceived "clear↔muffled switching" symptom.

**Scope / Acceptance criteria.**
- Edit `~/.hermes/config.yaml` on hermes-host: `tts.elevenlabs.model_id: eleven_flash_v2_5`.
- Restart `hermes-gateway.service` (`systemctl --user restart hermes-gateway`).
- Verify via relay log that next `/voice/synthesize` request uses flash.
- Voice test: send 3-sentence assistant response, confirm reduced inter-chunk variance by ear.

**Files to touch.** `~/.hermes/config.yaml` (server-side only — not in this repo).

**Implementation notes.**
- `eleven_flash_v2_5` costs fewer characters per dollar than `multilingual_v2` — net win on billing too.
- Voice ID stays at `XZEfcFyBnzsNJrdvkWdI` (Bailey's current custom voice).
- Rollback: revert the config line, restart gateway. Zero risk.

**Agent brief.**
> This is an operator-executed step, not an agent task. The orchestrator should SSH to `bailey@172.16.24.250` (key auth), edit `~/.hermes/config.yaml`, restart `hermes-gateway.service`, and verify with a single voice request. Document the before/after impression in the session commit message of Wave 1.

**Dependencies.** None.

---

# Wave 1 — Parallel: relay sanitizer, playback engine, upstream voice settings

## V1. Relay TTS text sanitizer

- **Status:** `[ x ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** S · **Effort:** Python

**Summary.** Apply a text sanitizer inside `plugin/relay/voice.py::handle_synthesize()` before handing text to upstream `text_to_speech_tool()`. Strip markdown, code fences, URLs, markdown links, Hermes tool annotations (`` `💻 terminal` ``, emojis like 🔧 ✅), list markers, and headers.

**Why.** The jumbled-letters symptom. ElevenLabs today reads `` `💻 terminal` `` as "backtick computer emoji terminal backtick". It reads `https://github.com/foo/bar` as a character-by-character URL spell-out. The upstream `_strip_markdown_for_tts()` at `tools/tts_tool.py:1053` already does most of this — but only the CLI's `stream_tts_to_speaker()` calls it. Our relay calls `text_to_speech_tool()` which does **not** run the stripper. We don't want to depend on an upstream refactor landing first; we sanitize at our layer.

**Scope / Acceptance criteria.**
- New module `plugin/relay/tts_sanitizer.py` — pure-function `sanitize_for_tts(text: str) -> str`.
- Regex suite (mirror upstream `_strip_markdown_for_tts`, plus Hermes-specific additions):
  - ` ```…``` ` → ` ` (space)
  - `[label](url)` → `label`
  - `https?://\S+` → `` (remove)
  - `**bold**` / `*italic*` → plain
  - `` `inline` `` → plain
  - `^#+\s*` (headers) → `` (remove)
  - `^\s*[-*]\s+` (list markers) → `` (remove)
  - `---+` → `` (remove)
  - `\n{3,}` → `\n\n`
  - **Hermes-specific:** strip the tool-annotation pattern used in `ChatHandler.kt:50-74` — backtick-wrapped emoji+label tokens like `` `💻 terminal` `` `` `🔧 some_tool` `` `` `✅ done` ``.
  - **Emoji pass:** strip all emoji (Unicode `So`/`Sk` categories) that aren't in a word context. Don't strip plain text that happens to contain a non-BMP char inside a word.
- Wire into `plugin/relay/voice.py::handle_synthesize()` before the `asyncio.to_thread(text_to_speech_tool, text)` call.
- Log the delta: if sanitized length differs from input length by more than a few chars, log at DEBUG with a short preview of removed tokens (helps diagnose over-stripping in prod).
- Unit tests: `plugin/tests/test_tts_sanitizer.py`. Cover each regex class with a positive + negative case, plus a fixture response that exercises them all at once (a realistic Hermes assistant message with a code block + tool annotation + URL + bullet list).

**Files to touch.**
- `plugin/relay/tts_sanitizer.py` (new)
- `plugin/relay/voice.py` (call site — one line near the synthesize handler)
- `plugin/tests/test_tts_sanitizer.py` (new)

**Implementation notes.**
- Don't import from the hermes-agent venv — we want the sanitizer to work even if upstream renames the function. Copy the regex set into our tree, credit it in a comment, and keep it independently testable.
- Be conservative with emoji stripping. False-positive risk: "🇺🇸" or language-specific punctuation. Start with the obvious tool-annotation set (🔧 ✅ ❌ 💻 📱 🎤 🔊 ⚠️) and only expand if a regression shows up.
- Run tests with `python -m unittest plugin.tests.test_tts_sanitizer` — do NOT use `pytest` (conftest imports `responses`).

**Agent brief.**
> Implement the relay TTS sanitizer per unit V1 in `docs/plans/2026-04-16-voice-quality-pass.md`. Read `CLAUDE.md` first for project conventions. Write a new pure-function module `plugin/relay/tts_sanitizer.py` that mirrors the regex set in upstream `~/.hermes/hermes-agent/tools/tts_tool.py::_strip_markdown_for_tts` (use the agent's diagnosis report — or re-read the upstream file via SSH — to get the exact patterns) plus Hermes-specific additions for tool-annotation backtick tokens and emoji. Wire it into `plugin/relay/voice.py::handle_synthesize` immediately before the upstream `text_to_speech_tool` call. Add `plugin/tests/test_tts_sanitizer.py` with per-regex coverage and a combined realistic-message fixture. Run `python -m unittest plugin.tests.test_tts_sanitizer`. Commit on `feature/voice-quality-pass`. Commit style: Conventional Commits (`feat(relay): strip markdown/tool-annotations/URLs before TTS`).

**Dependencies.** None. Can land immediately.

---

## V5. Switch `VoicePlayer` to ExoPlayer (Media3) with gapless concatenation

- **Status:** `[ x ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** M · **Effort:** Kotlin

**Summary.** Replace the single-`MediaPlayer`-reset-per-file pattern in `VoicePlayer.kt` with Media3 ExoPlayer + `ConcatenatingMediaSource`. Keep the public API (`play`, `stop`, `awaitCompletion`, amplitude `StateFlow`) identical so `VoiceViewModel` is unchanged.

**Why.** Addresses the "audible seam between sentences" portion of the muffled-switching symptom. `MediaPlayer.stop() → new MediaPlayer() → prepare() → start()` per sentence resets the audio track, forces codec context re-init, and causes a hard pop/click plus ~50–100ms gap. ExoPlayer's `ConcatenatingMediaSource` supports true gapless playback — you append `MediaItem`s as they arrive and the pipeline stays alive. This is also the foundation that unblocks V4's prefetch — you can enqueue sentence N+1 before sentence N finishes without recreating the player.

**Scope / Acceptance criteria.**
- Add Media3 dependency to `gradle/libs.versions.toml` + `app/build.gradle.kts`. Pin to current Media3 release (check `media3-exoplayer`, `media3-exoplayer-dash` as needed — we only need progressive-source support for MP3, so `media3-exoplayer` alone should suffice).
- Rewrite `VoicePlayer.kt` internals around `ExoPlayer` + `ConcatenatingMediaSource`:
  - `play(audioFile)` → `exoPlayer.addMediaItem(MediaItem.fromUri(audioFile.toUri()))`. If the player is idle, call `prepare()` + `play()`.
  - Maintain a `StateFlow<Boolean>` for `isPlaying` from `Player.Listener::onIsPlayingChanged`.
  - Amplitude: keep the `Visualizer` integration — ExoPlayer exposes `audioSessionId` via `Player.getAudioSessionId()`.
  - `awaitCompletion()` → suspend until the queue is empty AND `isPlaying == false`. Semantic: completes when all currently-queued items have finished, not just the first one. Document this — it's a subtle behavior change.
  - `stop()` → `exoPlayer.stop() + exoPlayer.clearMediaItems()`.
- Unit test: add `app/src/test/kotlin/.../audio/VoicePlayerTest.kt` — mock `ExoPlayer` with MockK, verify queue behavior (append, clear, completion listener wiring). Don't try to instrument real audio — UI-layer test, not integration.
- **Verify on device**: play a 3-sentence response and listen for the seam. Bailey verifies via Studio run button — *do not* `adb install`.

**Files to touch.**
- `gradle/libs.versions.toml` (add media3 version)
- `app/build.gradle.kts` (add dependency)
- `app/src/main/kotlin/com/hermesandroid/relay/audio/VoicePlayer.kt` (internal rewrite, public API preserved)
- `app/src/test/kotlin/com/hermesandroid/relay/audio/VoicePlayerTest.kt` (new)

**Implementation notes.**
- Preserve the public API exactly. `VoiceViewModel.kt:776-836` treats `VoicePlayer` as a black box — don't break that contract or V2/V3/V4 get harder.
- `Visualizer` attaches to an `audioSessionId`. ExoPlayer exposes this via `player.audioSessionId` (default impl delegates to the audio track). Test that amplitude StateFlow continues to emit across track transitions — there's a known gotcha where Visualizer's session can invalidate on track change; mitigation is to create the Visualizer once against the session ID and not re-attach.
- Set `ExoPlayer.Builder(context).setHandleAudioBecomingNoisy(true).build()` — matches the current MediaPlayer default behavior of stopping on headphone unplug.
- `awaitCompletion()` semantic change: old = "current file done", new = "queue drained". This is actually what V4 wants. If V5 lands but V4 slips, verify this doesn't regress the V3-chunked-alone path (it shouldn't — V3 still enqueues one-at-a-time, so queue-drained ≡ file-done).
- Consider keeping a 1-line feature flag (`FeatureFlags.useExoPlayerVoice: Boolean = true`) so Bailey can fall back to `MediaPlayer` if something misbehaves in the field.

**Agent brief.**
> Implement unit V5 in `docs/plans/2026-04-16-voice-quality-pass.md`. Read `CLAUDE.md` first. Swap the internals of `app/src/main/kotlin/com/hermesandroid/relay/audio/VoicePlayer.kt` from a re-created `MediaPlayer` pattern to a single Media3 ExoPlayer + ConcatenatingMediaSource. Preserve the public API exactly — `play(file)`, `stop()`, `awaitCompletion()`, and the amplitude `StateFlow` — because `VoiceViewModel` treats it as a black box. Change the semantic of `awaitCompletion()` to "queue drained" and document it. Add the Media3 dependency to `gradle/libs.versions.toml` + `app/build.gradle.kts`. Write a mockk-based unit test for queue semantics. Do NOT `adb install` or run `gradle build` — Bailey verifies via Android Studio run button. Commit on `feature/voice-quality-pass`. Conventional commit: `refactor(voice): swap MediaPlayer for Media3 ExoPlayer for gapless playback`.

**Dependencies.** None. Runs in parallel with V1 and Up1.

---

## Up1. Upstream PR — expose `VoiceSettings` in `_generate_elevenlabs`

- **Status:** `[ ] Now` `[ ] Next` `[ x ] Later` `[ ] Skip`
- **Size:** S · **Where:** `Codename-11/hermes-agent` fork → upstream PR

**Summary.** Patch `tools/tts_tool.py::_generate_elevenlabs` to accept and pass through `VoiceSettings` from `tts.elevenlabs.voice_settings` in `config.yaml`. Today the function calls `client.text_to_speech.convert(text, voice_id, model_id, output_format)` with no voice settings — so all ElevenLabs's default values apply, including a `stability` that's fine for long-form but too low for our chunked use.

**Why.** Addresses the volume-drift symptom specifically. `use_speaker_boost: true` auto-normalizes output level across calls. Higher `stability` (~0.6–0.7) reduces prosody/EQ drift between calls. These aren't exposable today — the fix is a one-function patch upstream. We also want this to land in upstream rather than fork-only because it's a cheap quality-of-life change for any ElevenLabs user of hermes-agent.

**Scope / Acceptance criteria.**
- In `~/.hermes/hermes-agent/tools/tts_tool.py::_generate_elevenlabs` (server-side path; work happens in the `Codename-11/hermes-agent` fork's working tree, not in this repo):
  - Import `VoiceSettings` from `elevenlabs`.
  - Read `el_config.get("voice_settings", {})` with sensible defaults: `stability=0.65`, `similarity_boost=0.8`, `style=0.0`, `use_speaker_boost=True`.
  - Pass `voice_settings=VoiceSettings(**merged)` to `client.text_to_speech.convert(...)`.
  - Guard: if the installed `elevenlabs` package is too old to support `VoiceSettings`, fall back to the current no-settings call with a one-line warning log.
- Update upstream docs: `docs/configuration/tts.md` (or wherever elevenlabs config is documented) to show the new `voice_settings` sub-keys.
- Submit as PR against `NousResearch/hermes-agent` from `Codename-11/hermes-agent` fork. Branch name: `feat/elevenlabs-voice-settings`. Cross-reference PR #8556 for format / tone.
- Pending merge, apply the same patch to our running instance (`axiom` branch on hermes-host) so V2/V3/V4 testing can actually benefit from the settings in this session.

**Files to touch.**
- `tools/tts_tool.py` (in `hermes-agent` repo, not here)
- `docs/configuration/tts.md` (in `hermes-agent` repo)
- `~/.hermes/config.yaml` (on hermes-host) — add the `voice_settings` subkey
- `~/.hermes/hermes-agent/` on hermes-host — apply the patch locally until upstream PR merges

**Implementation notes.**
- This is the **only unit in a different repository**. The worktree for this unit is `~/.hermes/hermes-agent/` on hermes-host, not `../hermes-android-voice` on Bailey's Windows box.
- Run upstream's test suite before opening the PR (there are probably TTS unit tests under `tests/tools/`).
- Keep the PR scope narrow — one function + one config key + one docs page. Don't bundle other voice improvements.

**Agent brief.**
> SSH to `bailey@172.16.24.250`. Work in `~/.hermes/hermes-agent/` on the `Codename-11/hermes-agent` fork. Create branch `feat/elevenlabs-voice-settings`. Patch `tools/tts_tool.py::_generate_elevenlabs` to accept `voice_settings` from the elevenlabs config block and pass through a `VoiceSettings(...)` object (defaults: stability=0.65, similarity_boost=0.8, style=0.0, use_speaker_boost=True). Add a graceful degradation for older elevenlabs SDKs. Update the upstream `docs/configuration/tts.md` reference. Run the project's test suite. Push the branch, open a PR against `NousResearch/hermes-agent` — cross-reference PR #8556 for style. Then apply the patch locally on the running `axiom` branch and restart `hermes-gateway` so V2/V3/V4 can benefit during testing. Report back the PR URL and the locally-applied confirmation.

**Dependencies.** Benefits from Cfg1 being done first (both ship settings for ElevenLabs — makes config review cleaner).

---

# Wave 2 — Serial: client sanitizer upgrade

## V2. Extend client-side `stripMarkdown()` → `sanitizeForTts()`

- **Status:** `[ x ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** S · **Effort:** Kotlin

**Summary.** Replace `VoiceViewModel.kt:1080-1084 stripMarkdown()` with a full `sanitizeForTts(String): String` that mirrors V1's relay-side sanitizer. Apply it at the buffer-accumulation boundary, not at the extraction boundary — so the chunker in V3 operates on already-clean text.

**Why.** Defense in depth. Even with V1 landed, the relay sanitizer runs *per synthesize call* — but our client-side sentence buffer contains raw text including tool annotations, which means the sentence-boundary regex in `extractNextSentence()` can split on punctuation inside a code block or URL path. Sanitizing client-side before chunking prevents chunk boundaries from landing inside structure we're about to strip anyway.

**Scope / Acceptance criteria.**
- Rename `stripMarkdown` → `sanitizeForTts` (private fun in `VoiceViewModel.kt`), keep existing call sites.
- Extend the regex set to match V1's `sanitize_for_tts()`:
  - Code fences, markdown links, URLs, bold/italic/inline-code, headers, list markers, horizontal rules, emoji in tool-annotation positions.
- Apply inside `onStreamDelta()` before the delta is appended to `sentenceBuffer` — so all downstream logic (boundary detection, length checks) runs on sanitized text.
- Unit test: `app/src/test/kotlin/.../voice/VoiceViewModelSanitizerTest.kt`. Mirror V1's combined-fixture test — a realistic Hermes assistant message with a code block, tool annotation, URL, bullet list. Assert the sanitized output is what we'd expect a voice to read aloud.

**Files to touch.**
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/VoiceViewModel.kt` (rename + extend fn; call site change in `onStreamDelta`)
- `app/src/test/kotlin/com/hermesandroid/relay/voice/VoiceViewModelSanitizerTest.kt` (new)

**Implementation notes.**
- Keep the function private to `VoiceViewModel`. Don't extract to a util yet — YAGNI. If a second caller appears later, extract then.
- Watch for streaming-delta edge cases: a code fence may span multiple deltas (```<delta1>```<delta2>). Stripping per-delta means you might leave orphaned ``` in the buffer. Mitigation: only strip code fences *after* the full pattern is present. If you see an unclosed ``` in the delta, buffer it unsanitized and re-apply the sanitizer when the closing fence arrives.
- That streaming-code-fence wrinkle is the only real implementation gotcha. Everything else (URLs, bold, inline code) is self-contained per delta.

**Agent brief.**
> Implement V2 in `docs/plans/2026-04-16-voice-quality-pass.md`. Read `CLAUDE.md` first. Work in the `../hermes-android-voice` worktree on the `feature/voice-quality-pass` branch. Rename `VoiceViewModel.stripMarkdown` → `sanitizeForTts` and extend its regex set to match the V1 sanitizer in `plugin/relay/tts_sanitizer.py` (read that file to get exact patterns). Apply sanitization inside `onStreamDelta` before text is appended to `sentenceBuffer`. Handle the streaming-delta code-fence edge case (buffer unclosed fences). Write `VoiceViewModelSanitizerTest.kt`. Commit on `feature/voice-quality-pass`. Conventional commit: `feat(voice): sanitize assistant text client-side before TTS chunking`.

**Dependencies.** V1 (same regex set should land in both places for parity; if V1 diverges later, diff the two modules).

---

# Wave 3 — Serial: sentence coalescing

## V3. Coalesce short sentences + better chunking

- **Status:** `[ x ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** M · **Effort:** Kotlin

**Summary.** Rework `VoiceViewModel::extractNextSentence()` (lines ~1228–1267) so tiny one-word sentences ("Sure.", "Okay.") coalesce with their neighbors instead of each becoming its own TTS request. Also support optional soft-flush on commas/semicolons for very long run-on sentences.

**Why.** The "muffled switch" symptom is worst on response patterns like `Okay. Let me check that for you. Here's what I found.` — three API calls, three re-inits, three chunks, three chances for audible variance. Coalescing these into one synthesize call both reduces inter-chunk variance *and* cuts pipeline latency (one network RTT instead of three). Separately, very long sentences (>200 chars) should optionally split on commas so we don't block playback for 8 seconds waiting for a full paragraph to synthesize.

**Scope / Acceptance criteria.**
- Raise `MIN_SENTENCE_LEN` from 6 → ~40 (make it a named constant, `MIN_COALESCE_LEN`).
- Rework `extractNextSentence()`:
  - If next complete sentence ≥ `MIN_COALESCE_LEN` → emit as today.
  - If next complete sentence < `MIN_COALESCE_LEN` AND more buffer is available → keep buffering, try again.
  - If next complete sentence < `MIN_COALESCE_LEN` AND the stream is complete (end-of-response signal) → emit the short sentence anyway. Don't starve the queue.
  - Add a `MAX_BUFFER_LEN` escape hatch (~400 chars) — if the buffer exceeds this without a terminator, flush at the nearest secondary break (`,` `;` `—`) to avoid a pathological "never flushes" case on run-on text.
- Preserve the abbreviation lookahead (`e.g.`, `U.S.`) — don't regress that.
- Add a timer-based flush: if the buffer has content and no delta has arrived in >800ms, flush what's there. Covers the case where the stream ends without a trailing terminator.
- Unit tests in `VoiceViewModelChunkingTest.kt`:
  - Short-sentence coalescing (`"Sure. Let me check." → one chunk`)
  - Long-sentence secondary-break split (at 400-char-with-no-terminator, splits at comma)
  - Abbreviation preservation (`"e.g. a thing. Next sentence."` doesn't split on `e.g.`)
  - End-of-stream flush (incomplete trailing sentence gets emitted on stream-complete signal)
  - Timer flush (buffer has text, no delta for 800ms → emit).

**Files to touch.**
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/VoiceViewModel.kt`
- `app/src/test/kotlin/com/hermesandroid/relay/voice/VoiceViewModelChunkingTest.kt` (new)

**Implementation notes.**
- The timer flush is easy to get wrong — it must cancel if a new delta arrives mid-wait. Use a `Job` you cancel in `onStreamDelta`.
- Consider using ICU4J's `BreakIterator.getSentenceInstance()` instead of hand-rolled regex. It's Android-built-in (no dep), handles Unicode punctuation, and does abbreviation awareness for free. Worth spiking — if it behaves well on Hermes output styles, use it. Otherwise stick with the extended hand-rolled approach.
- **Don't** raise `MIN_COALESCE_LEN` past ~50 — the latency-to-first-voice grows linearly with it. 40 is a good balance.

**Agent brief.**
> Implement V3 in `docs/plans/2026-04-16-voice-quality-pass.md`. Read `CLAUDE.md` first. Work in the `../hermes-android-voice` worktree. V2 must be committed first — confirm. Rework `VoiceViewModel::extractNextSentence` per the acceptance criteria: coalesce short sentences, add a secondary-break escape at ~400 chars, add an 800ms timer flush for stream-end edge cases. Preserve abbreviation lookahead. Consider ICU4J `BreakIterator` as an alternative implementation — if it's cleaner and handles Hermes-style output well, use it. Write `VoiceViewModelChunkingTest.kt` covering all five cases in the scope. Commit on `feature/voice-quality-pass`. Conventional commit: `feat(voice): coalesce short sentences and add secondary-break chunking`.

**Dependencies.** V2 (sanitized text is the input to the chunker).

---

# Wave 4 — Serial: pipelining

## V4. TTS prefetch pipelining

- **Status:** `[ x ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** M · **Effort:** Kotlin

**Summary.** Rewrite `VoiceViewModel.startTtsConsumer()` (lines ~776–836) from a strictly serial `synthesize→play→awaitCompletion→synthesize…` loop into a 2-slot prefetcher: while sentence N is playing, synthesize N+1 in parallel. The next `MediaItem` is already queued and ready when N finishes.

**Why.** Addresses the "pause then switch" symptom. Today, the gap between sentence N's audio ending and sentence N+1's audio starting is ≈ network RTT (100–400ms) + ElevenLabs synth time (~75ms on flash) + file-write + MediaPlayer prepare (~50ms). With prefetching, the gap collapses to only the ExoPlayer append latency (single-digit ms). Combined with V5's gapless playback, inter-sentence gaps should become imperceptible.

**Scope / Acceptance criteria.**
- Split `startTtsConsumer` into two coroutine workers joined by a bounded `Channel<File>(capacity=2)`:
  - **Synth worker:** pulls sentences from `ttsQueue`, calls `RelayVoiceClient.synthesize()`, pushes resulting `File` onto `audioQueue`. Runs eagerly — keeps the audioQueue full.
  - **Play worker:** pulls `File` from `audioQueue`, calls `player.play(file)`, calls `player.awaitCompletion()`, loops. Runs behind the synth worker naturally due to playback duration.
- Capacity of 2 prevents unbounded memory growth and respects any per-request cost to the ElevenLabs API — we prefetch at most one sentence ahead.
- Cancellation: when the user interrupts voice mode, cancel both workers, drain + delete any pending audio files on disk. Don't leak `voice_tts_<ts>.mp3` cache entries.
- Error handling: if synth fails for sentence N+1, don't stall sentence N's playback. Log, skip, continue.
- Preserve `maybeAutoResume()` behavior (line ~796) — when the queue drains naturally (end of response), the voice state should flip back to "listening" as today.
- Unit test: stub `RelayVoiceClient` with artificial delays; verify that total response time for 3 sentences is `≈ max(total_synth, total_playback)` not `sum`. Also verify cancellation cleans up.

**Files to touch.**
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/VoiceViewModel.kt`
- `app/src/test/kotlin/com/hermesandroid/relay/voice/VoiceViewModelPipelineTest.kt` (new)

**Implementation notes.**
- This is the unit most likely to introduce regressions — the current serial loop is straightforward, the two-worker version has more edge cases. Invest in the test.
- The cancellation path is the gnarliest. When `stopVoice()` fires, both workers need to tear down deterministically without deadlock. Prefer structured concurrency — make them children of a single supervisor scope, cancel the scope.
- If V5 has landed, audio files are appended to ExoPlayer's queue and the play worker's `awaitCompletion` semantics match V5's "queue drained" definition — so this composes naturally. If V5 slipped, this still works against `MediaPlayer` but the seam stays audible until V5 lands.
- Be careful with `ttsQueue.close()` — the synth worker needs to emit any in-flight result before exiting, so the play worker doesn't lose a final file. Standard producer-consumer shutdown dance.

**Agent brief.**
> Implement V4 in `docs/plans/2026-04-16-voice-quality-pass.md`. Read `CLAUDE.md` first. Work in the `../hermes-android-voice` worktree. V2 and V3 must be committed first — confirm. Split `VoiceViewModel.startTtsConsumer` into two coroutine workers (synth + play) joined by a bounded `Channel<File>(capacity=2)`. Use structured concurrency for cancellation. Preserve `maybeAutoResume()` semantics. Handle synth errors without stalling playback. Clean up cache files on cancellation. Write `VoiceViewModelPipelineTest.kt` — stub `RelayVoiceClient` with delays and assert parallelism. Commit on `feature/voice-quality-pass`. Conventional commit: `feat(voice): prefetch next sentence synthesis while current plays`.

**Dependencies.** V2, V3 (needs sanitized + coalesced chunks as input). Composes well with V5 but doesn't require it.

---

# Wave 5 — Docs

## Doc1. Update DEVLOG, CHANGELOG, user-docs, spec

- **Status:** `[ x ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** S · **Effort:** Markdown

**Summary.** Capture the wave in the append-only log, bump CHANGELOG with an `[Unreleased]` entry, refresh user-docs voice-mode troubleshooting, update spec if the TTS pipeline section exists.

**Scope / Acceptance criteria.**
- `DEVLOG.md`: append a 2026-04-16 session entry summarizing what shipped and the diagnosis chain that motivated it.
- `CHANGELOG.md`: `[Unreleased] — Changed` entry: "Voice output quality — switched to ElevenLabs flash streaming model, added client+relay text sanitization, coalesced short sentences, prefetched next sentence during playback, and swapped to ExoPlayer for gapless concatenation." Use conventional-changelog tone.
- `user-docs/`: if there's a voice-mode page, add a "What changed in voice mode" note. If not, don't create one just for this.
- `docs/spec.md`: if there's a voice-pipeline section, update the flow diagram or prose to reflect the prefetch + gapless architecture.
- No entry in `ROADMAP.md` — the wave is complete, not milestoned.

**Files to touch.**
- `DEVLOG.md`
- `CHANGELOG.md`
- `user-docs/*` (conditional)
- `docs/spec.md` (conditional)

**Agent brief.**
> Implement Doc1 in `docs/plans/2026-04-16-voice-quality-pass.md`. Read `CLAUDE.md` first. Append a DEVLOG entry for 2026-04-16 session. Add an `[Unreleased] — Changed` entry to CHANGELOG.md. If `user-docs/` has a voice-mode page, add a "what changed" note. If `docs/spec.md` describes the TTS pipeline, reflect the new prefetch + ExoPlayer flow. Don't create new docs just to have something to write. Commit on `feature/voice-quality-pass`. Conventional commit: `docs: record voice quality pass in DEVLOG and CHANGELOG`.

**Dependencies.** Everything else in this plan — this is the last commit of the session.

---

# Definition of done

A session is complete when all of the following hold:

- [ ] All work units with `[x] Now` have landed on `feature/voice-quality-pass`.
- [ ] CI is green on the branch (Android build + Python unit tests both pass).
- [ ] Bailey has done a voice-mode smoke test on device: 3-sentence response, 1 code-block response, 1 multi-paragraph response. Subjective judgment on whether the symptoms improved is captured in the DEVLOG entry.
- [ ] A single PR is open against `main`. PR body lists every unit ID shipped (`V1, V2, V3, V4, V5, Doc1, Cfg1 (operator), Up1 (upstream)`) with a one-line note per unit.
- [ ] The upstream PR for Up1 is open and linked from this PR's body (even if not yet merged).
- [ ] The worktree is clean (no uncommitted changes) before Bailey reviews the PR.

# Post-merge

After this PR merges to `main`:

1. Delete the worktree: `git worktree remove ../hermes-android-voice`.
2. Delete the local branch: `git branch -d feature/voice-quality-pass`.
3. Archive this plan file: rename to `docs/plans/archive/2026-04-16-voice-quality-pass.md` (create `archive/` if needed) so the `docs/plans/` directory shows only active plans.
4. If Up1 has not yet merged upstream, leave the local patch on the `axiom` branch. When it does merge, rebase `axiom` onto the new upstream and drop the local patch in the same operation.
