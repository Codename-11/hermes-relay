# Hermes-Relay — TODO

Open items that don't fit a formal Phase plan but shouldn't be lost. Items move from here into a Plan in `docs/spec.md` or an Obsidian Phase plan once they're ready to schedule.

For shipped work, see `DEVLOG.md`. For architectural decisions, see `docs/decisions.md`.

---

## Active — next up (2026-07-07)

Compaction-safe snapshot of where we are; details in the linked sections below.

- **HOLD — android-v1.4.0 + plugin-v1.4.0 release cut is PAUSED (owner direction 2026-07-07): do not merge/tag until voice testing is finished.** Version bumps + CHANGELOG rename are already committed on `dev` (Android **1.4.0 / versionCode 22**, plugin **1.4.0**); do not touch them further. What's blocking: the "Voice — on-device findings" bugs below must be fixed and re-verified on-device first. Once testing signs off, resume the release act:
  1. On-device sign-off: the Tink `EncryptedSharedPreferences` check (pair → force-stop → relaunch → session persists, no startup crash — see Crash-class follow-ups) + a full voice manual re-test (including the fixes below + the new screen-wake-lock behavior).
  2. Write the release notes the bump script listed: `RELEASE_NOTES.md`, `app/src/main/assets/whats_new.txt`, `app/src/main/assets/changelog.json`, `app/src/googlePlay/play/release-notes/en-US/default.txt`.
  3. Merge `dev`→`main` `--no-ff` + tag `android-v1.4.0` and `plugin-v1.4.0`. Plugin is a **MINOR** (it carries #165 native-loader + installer-venv, #170 doctor guardrails, #171 multi-device bridge, #178 dedup guard — not the 1.3.1 patch originally queued).
  4. Discard the 1.3.0 Play Console draft and upload the 1.4.0 AAB.
- **Voice bugs being worked now** — see "Voice — on-device findings" below for full detail:
  1. Tool-call status pills/ordering + stuck "Thinking" — **in progress** (root cause confirmed, fix underway 2026-07-07).
  2. Tap/static click between sentences (`RealtimePcmPlayer` boundary) — still needs an on-device audio repro before fixing.
- **Screen-wake-lock — SHIPPED (2026-07-07).** See "Voice — on-device findings" below.
- **Owner / Mizu — GitHub triage.** Close #64 as superseded, plus the queued open-issue comment/close/label batch (see "Open-issue resolution batch" below).

---

## Voice — on-device findings (2026-07-07 realtime test)

Surfaced during a live realtime-voice test with a long, many-tool-call background run. (The duplicate-error-toast + no-dismiss issue from the same test shipped this session — see DEVLOG 2026-07-07.)

- **Tool-call status pills stuck / ordering wrong — FIXED, needs on-device re-verify (2026-07-07).** After the recent background-run-chip work (`8dc874c`/`9554c7c`), the owner found on-device that the "Thinking" indicator can get stuck and that the relative order of tool-call pills vs. the agent's reply doesn't cleanly track what actually happened. Root cause was narrower than first suspected — `VoiceUiState.responseText` is write-only for the realtime path (nothing renders it), so the actual stuck surface was the `BackgroundRunChip`: no `hermes.tool.completed`/`hermes.tool.failed` branch in `VoiceViewModel`'s event handler meant a finished tool's `statusLine` stayed pinned at `phase=RUNNING` until the next unrelated event overwrote it. Fixed (`VoiceViewModel.kt:2619`): clears the finished tool's status line, advances `completedToolCount`, leaves `DELIVERING` alone. The ordering half was `CompactTranscriptRow` (`VoiceModeOverlay.kt`) rendering reply text above the tool rows that produced it — reordered to tool-rows-first (chronological). The per-message `ToolCall` transcript rows were already correct (untouched). `:app:compileSideloadDebugKotlin` green. **Needs on-device re-verify** (long multi-tool background run: chip never shows a stale finished-tool name; reply reads below its tool calls, not above) before the release resumes.
- **Tap/static click between sentences (realtime PCM playback) — NEEDS on-device audio investigation.** Suspected discontinuity at TTS chunk/sentence boundaries in `RealtimePcmPlayer` (a buffer underrun between segments, or a pop when a new segment's `AudioTrack` write starts). Capture head-position / underrun logs during a multi-sentence reply to confirm before touching the buffer sizing or adding a boundary crossfade/fade. Related to the existing "Realtime-PCM waveform output gating" note.
- **Screen-wake-lock for chat/voice — SHIPPED (2026-07-07).** The app previously relied entirely on the OS screen-timeout during both chat and voice mode. Added `KeepScreenOnWhile(enabled)` (`ui/components/OrientationOverride.kt`, `Window.FLAG_KEEP_SCREEN_ON` via `DisposableEffect` — the same Android-recommended visible-surface mechanism `power/WakeLockManager.kt`'s doc comment already pointed at for a background/no-window case), wired at the `ChatScreen` root as a single call site: `enabled = voiceUiState.voiceMode || isStreaming`. Rationale (matches other apps): voice mode is a call-like continuous session (Assistant/phone-call convention) so it holds the flag for the whole time the overlay is open, regardless of Idle/Listening/Thinking/Speaking sub-state; chat only holds it while a reply is actively streaming (video-playback convention) — idle reading/scrolling falls back to the OS default, matching WhatsApp/Telegram/Signal norms rather than pinning the screen on for a static transcript. Deliberately a single owner of the window flag (not ref-counted) — see the function's doc comment before adding a second caller. **Needs on-device confirmation**: screen stays on for the whole voice session incl. silent gaps, screen stays on only during active streaming in chat (not while idle), and the flag is correctly released on exiting voice mode / when a stream ends.

## Voice background-run v2 (2026-07-06 roadmap — post plugin-v1.3.0)

The v1 shape shipped in plugin-v1.3.0 (single durable run, free floor during
background work, busy answer, deliver-on-reattach, exit-detaches / chip-✕-
cancels). Ranked next increments, in value-per-complexity order:

1. **Fast lane — SHIPPED in code (2026-07-08; needs relay deploy + live voice
   verify).** `_run_fast_lane_task` in `broker.py`: while a detached
   (promoted/durable) run holds the background slot, a second
   `hermes_run_task` first runs INLINE on a separate ephemeral Hermes session
   (`session_id=None`) within the normal grace window; grace-elapse, a
   known-long tool start (`_long_tool_hints`), explicit `mode=background`, or
   promotion-off all abandon it and fall through to the (reworded) busy
   answer. Touches NONE of the session's `hermes_*` run state — run_id/
   status/progress/chip stay owned by the in-flight run — and emits no client
   events of its own (bounded by grace; a chip would fight the detached
   run's). Events: `voice.hermes_fast_lane.completed/abandoned/error` in the
   session log. Tests: `plugin/tests/test_realtime_fast_lane.py` (7) +
   updated `test_second_run_task_answers_busy_without_orphaning_first`
   (per-stream cancellation tracking). **Residuals:** (a) context injection —
   the ephemeral session gets only the task text + interface context, not
   rolling conversation context (broker keeps no per-turn transcript; the
   model is instructed to pass self-contained task text); (b) an abandoned
   attempt may still finish server-side into the ephemeral session
   (at-least-once, unread) — same property as promotion; (c) live verify:
   during a long background run, ask a quick second question → answered
   inline; ask a second long thing → busy answer unchanged.
2. **Task queue** — upgrade the busy answer from refusal to offer ("want me
   to queue it?"): small FIFO in the broker session, start-next-on-completion
   with a spoken handoff, chip shows "+1 queued". Pairs with (1).
3. **Chip tap-through to the transcript** — the run executes on a real
   gateway session, so full tool calls/outputs already live in that session's
   history; make the chip (or the finished turn) open it. Cheapest "see tool
   output" step.
4. **Live tool-output sheet** — chip expands to a run timeline (tool name,
   status, capped ~500-char output snippet). Relay adds a truncated output
   field to `hermes.tool.*` events; client renders a lane (reuse the
   `SubagentLane` pattern).
5. **Injection framing (recorded earlier, still open)** — on providers with
   native async function calling, leave the tool call pending and deliver the
   real `function_call_output` late instead of interim-ack + synthetic
   instruction text. Needs a live xAI parity check first.
6. **Pending-result FIFO** — `pending_background_result` is a single slot
   (correct for one run); generalize to an ordered list the day (1)/(2) land
   so two results delivered during a detach don't race.
7. **Full N-way concurrent background runs — deliberately deferred.** Needs
   session-per-run topology (a gateway session serializes turns), which
   fragments conversation context, multiplies delivery/floor/failure modes,
   and needs run-id-targeted cancel + a multi-run chip. Only worth it when
   two *long* tasks genuinely need parallel wall-clock; revisit if the queue
   feels slow in practice.

## Open-issue resolution batch (2026-07-06) — owner GitHub actions + deferrals

Plan: `docs/plans/2026-07-06-open-issue-resolution.md` (13 open issues triaged;
fix-state claims verified against tags with `git merge-base --is-ancestor`).
**Automation never posts to GitHub** — every comment/close/label below is an
owner action, deliberately queued here:

- [ ] **#131** — close: fixed by `3573ba8` (PR #136), shipped android-v1.2.5
      (reporter was on 1.2.3). Optionally re-check Play vitals for the
      "Invalid URL host" signature on ≥1.2.5 first.
- [ ] **#129** — close: fixed by `99b9cf1` (PR #128), shipped android-v1.2.4
      (owner already promised v1.2.4 in-thread).
- [ ] **#124** — post the promised follow-up + close: fixed by `802385c`
      (PR #125), first shipped android-v1.2.3.
- [ ] **#70** — close both prongs: original keyset force-close fixed `48ddba5`
      (android-v1.1.0); the in-thread TLS/Tailscale crash is #124's bug, fixed
      android-v1.2.3. Invite reopening if it recurs on ≥1.2.3.
- [ ] **#94** — pull Play Console vitals for the versionCode-13 / Z Fold7
      cluster; hardening shipped `a455e46` (android-v1.2.0). Confirm no
      recurrence on v1.2.x, then close.
- [ ] **#155 / #154** — support comments + close as user-config: `localhost`
      on the phone points at the phone itself (#154 is the downstream probe
      failure of the same misconfig). Link the new troubleshooting entry once
      it deploys. Relabel away from `bug`/`area:plugin`.
- [ ] **#146** — needs-info comment (Tailscale up on the phone? follow-up
      Error entry? agent bound on the tailnet address?); close as support if
      no response.
- [ ] **#166** — relabel `area:plugin` → `area:android`; reply with the root
      cause (phone drops the SSE socket on long local-model turns; upstream
      finishes + persists the answer; app now recovers it) and credit the
      reporter's `supports_async_delivery` instinct. Ask: screen off during
      the hang? does reopening the session later show the answer?
- [ ] **#165** — reply: both failure modes confirmed (absolute `plugin.`
      imports under the native loader; install.sh layout assumptions); fix
      ships as plugin-v1.3.1. The uv-pip gap they mention was already fixed in
      plugin-v1.1.0+. Owner must e2e the fix on the official Docker image.
- [ ] **#145** — confirm-triage reply; on-device check after fix (max font +
      display size, all 5 slides); close after the next android-v* release.
- [ ] **#144** — close after the next android-v* release demonstrates the
      2-asset layout + new Download block; optionally edit the published
      android-v1.2.6 release body to drop the "Parity/testing artifact" wording.
- [ ] **#121** — label (`enhancement` + area) and milestone onto the next
      `cli-v*` release; it's scheduled feature work, not part of this batch.

Deferred from the batch (coordination / decisions):

- **Localhost-advisory UI wiring** (`ConnectionWizard` / `ConnectionDetailScreen`
  `supportingText`) — the util (`ServerAddress.loopbackHostWarning`) + tests land
  in WS-C, but the wizard wiring waits on the parallel connections-UI workstream
  to avoid colliding in those files.
- **#166 optional hardening** — extend the opt-in keep-alive foreground service
  to cover an in-flight SSE turn (reduces disconnect incidence; googlePlay-flavor
  FGS declaration implications). Recovery poller ships without it.
- **Upstream PR candidates from #166** — intentional detached-run semantics on
  client disconnect in `_handle_session_chat_stream`; pollable/resumable
  session-turn status. Decide whether to file against hermes-agent.
- **"Vanilla Hermes" docs naming** — app dropped the label in v1.2.2; docs still
  use it as a concept term. Owner decision whether to retire it docs-wide
  (WS-F only fixes verbatim UI-label quotes).
- **Docker venv pivot for install.sh** — beyond steer-to-native: optionally
  create a dedicated relay venv under a writable path so the full installer
  works in-container.

Implementation-batch follow-ups (from the per-branch reviews):

- **#166 recovery: empty-session fail-fast.** `HermesApiClient.getMessages()`
  maps fetch failures to `emptyList()`, so the recovery poller can't distinguish
  "server unreachable" from "session genuinely empty" — a `Result`-returning
  history read would let the never-landed-send fail-fast also cover a dropped
  FIRST message of a fresh session (today that case polls to the cap).
- **#166 recovery cap.** Recovery gives up after 30 minutes; longer turns still
  land in session history but only surface after a manual reload. Consider a
  "keep waiting" affordance if real turns exceed the cap.
- **CI android slice.** `ServerAddressTest` + `IssueReportAndDiagnosticsTest`
  added to the focused `--tests` slice; the Robolectric/MockWebServer recovery
  tests and the compact-onboarding Roborazzi test stay local-only (same
  precedent as `StoreScreenshotTest`) until the broad-suite hang (#32) is fixed.
- **Skills docs still cite editable-only fixes.** `skills/devops/hermes-relay-pair/SKILL.md`
  and `skills/android/SKILL.md` document `python -m plugin.pair` + `pip install -e`
  as the ModuleNotFoundError fix — add the native-layout equivalent when the
  #165 branch ships.
- **Dashboard API tests not CI-visible.** `plugin/dashboard/test_plugin_api.py`
  isn't discovered by `unittest discover -s plugin/tests` and needs
  fastapi/httpx — wire into a CI runner or move under plugin/tests with skips.
- **Desktop tool-count drift.** `user-docs/desktop/index.md` counts client-side
  handlers (clipboard/screenshot/open_in_editor) that have no server-side
  `desktop_*` registration in `plugin/tools/desktop_tool.py` — reconcile the
  advertised set; also `user-docs/desktop/pairing.md` wrongly says Android uses
  `~/.hermes/remote-sessions.json` (it's Keystore/EncryptedSharedPrefs; the file
  is shared with the Ink TUI). CLAUDE.md Key Files also still says 18/24 tools.
- **Info-report button label.** The diagnostics Report button reads "Report"
  even when the first tap only reveals the expectation field — a "Continue"
  label would make the two-step flow clearer.

## Connections UI / status banner (2026-06-30 restructure follow-ups)

The Connections screen was split into a scannable list + a tabbed detail screen
(Overview / Routes / Advanced / Security). Connection-status presentation went
through a few iterations (persistence-tiered top strip → no-float → …) and **landed
on a two-connection model (2026-07-01):**
- **Chat/agent** (gateway/API) → the chat header **subtitle** swaps model ⇄
  "Reconnecting…"/"Connecting…"/"Disconnected" (WhatsApp-style; `ChatScreen`).
- **Relay socket** (bridge/terminal/relay-voice) → the **bottom `RelayStatusStrip`**
  amber "Reconnecting…" cue only.
- **No top-of-screen surface** for connection status at all (no strip, banner, or
  float). Route changes are **ambient only** (the bottom strip's route label updates;
  no explicit "switched to Tailscale" notification — decided 2026-07-01).

Deferred:

- ~~**Dead connection-status-surface code.**~~ *(Cleaned up 2026-07-01.)* Removed the
  now-unused top-strip machinery: `ConnectionHandoffBanner` + `ConnectionStatusBanner`
  (+ `PulsingSyncIcon`), `ConnectionStatusSurface` + `presentationSurface()` +
  `ConnectionStatusSurfaceTest`. **`ConnectionStatusToast` retained** as a parked
  general-purpose toast primitive (the only surface with a live multi-step stepper;
  decouple from `ConnectionStatusSnapshot` + rename to `StatusToast` on first reuse).
- **Bottom-strip route-change flash (optional).** Route change is ambient-only for
  now. If a "switched to Tailscale" confirmation is wanted, surface it briefly in the
  **bottom strip** (where the route label already lives), not the top — keeps the
  no-top-chrome principle. The VM still detects + logs the change (`lastConnectedRole`).
- ~~**Resume suppression covers only the handoff path.**~~ *(Fixed 2026-07-01.)* Added
  `postResumeQuiet`: a benign background→foreground re-handshake no longer flashes the
  bottom-strip "Reconnecting…" cue (the health "Connecting" path used to leak it).
- **Stuck "Reconnecting" cue during a sustained/flapping outage (backoff gaps).**
  Confirmed in a both-sides trace (DEVLOG 2026-07-01): when a reconnect attempt fails
  (`Reconnecting→Disconnected`) **no handoff branch matches**, so the active
  "Reconnecting" handoff persists on its 30s backstop — including during the backoff
  *gap* where the socket is idle (`Disconnected`, not actually trying) and during the
  20s connect timeout. The cue then clears on the timer with no resolution ("no toast
  after"). The post-resume case is fixed (`postResumeQuiet`); this sustained/non-resume
  case is not. Fix idea: drive the bottom-strip cue off the LIVE
  `relayConnectionState`/`relayUiState` (show only while actually Connecting/
  Reconnecting), and clear the active handoff when `relayUiState` goes `Stale`/`Expired`
  so the live "Relay unreachable" state surfaces instead of a stuck cue. Deferred:
  hard to repro on a stable network; also risks surfacing the take-space "unreachable"
  banner more often on a chronically-flappy link (decide the escalation threshold).
- **Rapid real flaps still churn the cue/subtitle.** On a genuinely flapping network
  (DEVLOG 2026-07-01 — Samsung adaptive Wi-Fi cycling the radio), each real drop→recover
  toggles the bottom-strip cue (relay) and, if chat drops too, the header subtitle. Now
  unobtrusive (no top surface), but a short coalescing/debounce would quiet a
  chronically-flappy link further. Deferred — the flap is environmental, not an app bug.
- **Non-active connection detail is Overview-only.** Routes/Advanced/Security tabs
  appear only for the active connection (they read the single active-connection VM
  state); a non-active connection shows a "Switch to this connection" CTA. A future
  read-only preview of a non-active connection's saved routes could be nice.
- **Store screenshot regeneration.** The `07_connections` scene mock was updated to
  the new list design; confirm the regenerated PNG + Play-graphics export at
  release-prep (only auto-publishes on a `main` release merge).

## Chat UI/UX polish (2026-07-01 readability pass)

A 5-agent audit compared the chat surface to Discord/Telegram/Messenger/iMessage/
GitHub-mobile. **Shipped this pass (pending on-device verification):** a chat-tuned
`markdownTypography()` ramp (headings were falling through to M3 display roles —
h1=`displayLarge` 57sp in this app's scale — so a `#` was a billboard; now h1≈20sp
scaling down, list/paragraph unified to 14sp, inline+fenced code 13sp, `textLink`
accent+underline) in `MarkdownContent.kt`; timestamp gated to `isLastInGroup` (was on
every bubble) + grouping breaks on a >5min gap (`GROUP_GAP_MS`) so a resumed
conversation gets its own beat; long-press haptic on the action menu; streaming dots
gated to pre-first-token. Deferred:

- **Streaming↔final render parity (kill the reflow).** `StreamingMarkdownContent`
  renders raw markdown source (`## `, `**bold**`, `- item`) as plain 14sp text for the
  whole turn, then swaps to the full renderer at completion — headings still pop
  14sp→20sp on finalize (much reduced now that settled headings are small and lists no
  longer resize, but not zero). Run the real renderer on the settled prefix and keep
  only the trailing unterminated block raw. Riskier (partial-fence flicker) — needs
  on-device testing. Highest-effort audit item.
- **Bubble body 14sp → 15sp/21.** 14sp is the smallest body of the five reference
  apps. Bump markdown paragraph/text/list + the two plain `Text` sites
  (`MessageBubble.kt` user/system) together; keep ~1.4 leading so the ~272dp measure
  stays ~36–38 chars/line. Debatable/broad — left out of the certain heading win.
- **Tail-corner on last-in-group only (design decision).** The audit flagged the
  per-bubble bottom tail as "half-implemented," but it's a deliberate aesthetic
  (every bubble tails). Switching to iMessage-style "tail on the last bubble only"
  changes the look — get design intent before flipping. `isLastInGroup` is now
  meaningful (grouping breaks on gaps) so it's ready if wanted.
- **Wide tables.** GFM tables use the default renderer on ~272dp (columns crush);
  code fences already horizontal-scroll. Add a custom `table` component in
  `markdownComponents` with `horizontalScroll` + ~110dp min column + right-edge fade.
- **Assistant bubble width decoupled from user.** Both cap at 300dp though only the
  assistant carries markdown/code; let the assistant run wider (~92% of available /
  340–360dp cap) so fences wrap/scroll later. Keep user ~300dp.
- **Token counts out of the bubble; delivery → glyph.** Move `TokenDisplay` to a
  long-press "message info" sheet; collapse `Sending…/Delivered/Not sent` to a single
  trailing check/clock/! glyph on the last bubble (declutters every message).
- **SelectionContainer vs long-press conflict.** Long-pressing the words can start
  text selection instead of opening Copy/Quote. Pick one owner (drop
  `SelectionContainer`, expose Copy via the menu — chat-app norm — or move actions to a
  kebab). Needs on-device confirmation of the current conflict first.
- **Jump-to-bottom FAB unread badge** + drop the no-op tap ripple on bubbles
  (`combinedClickable onClick={}` still ripples). Telegram pattern.
- **Sessions-transport `animateItem` flash.** Stream-complete rebuilds the list with
  new ids → every visible bubble replays its enter animation (gateway transport,
  stable id, is unaffected). Reuse the streaming bubble's id for the final message.
- **Viewport re-pin on the `isStreaming` true→false height growth** (gateway
  transport): `ChatScreen` early-returns on `onlyStreamingFlagChanged`; issue one
  `withFrameNanos{}` + instant `scrollToItem(last)` when the flag flips and the user
  isn't scrolled away. Largely neutralized once render parity removes the height delta.
- **Full 15-role `Typography` + metadata contrast.** Type.kt declares only 7 roles at
  0 tracking; the rest inherit M3 defaults with 0.1–0.5sp tracking (ChatScreen uses
  several) — declare all 15 for one coherent scale. Separately, floor muted-metadata
  alpha at ≥0.6 and verify ≥4.5:1 per theme (11sp timestamps were alpha 0.5 over
  `onSurfaceVariant` ≈ 2–2.5:1; the surviving timestamp is now 0.6).

## Realtime voice (ADR 33) follow-ups — 2026-07-01 robustness batch

The deliver-on-reattach / adaptive-promotion / milestone-speech / resume-retry /
prewarm batch shipped (see DEVLOG 2026-07-01). Deferred:

- **Result injection framing — FIXED in code, deployed, needs live e2e voice verify (2026-07-07).** The completed background summary, the background-handoff acknowledgement, and the forced-Hermes preamble were all injected as a synthetic *user* message (`send_text` → `conversation.item.create` role=user) — the model saw a fake turn where "the user" said things like "Hermes has already handled the user's previous voice request..." Research turned up a cleaner mechanism than the one originally guessed at: `response.create` supports a per-response `instructions` field that overrides the session system prompt for one response only, **without creating any conversation item at all** — confirmed supported by both providers (OpenAI's own docs; xAI's Voice Agent API docs explicitly show the same `response.create.response.instructions` shape). `conversation: "none"` (true out-of-band, not in history) is OpenAI-only and was deliberately NOT used — we want the spoken summary to land in real conversation history so follow-ups like "what was that again" still work; only the injection *transport* changed, not where the turn ends up. Implementation: `RealtimeAgentConnection.request_response()` (`providers/base.py`) gained an optional `instructions: str | None` kwarg; both `providers/openai.py` and `providers/xai.py` implement it identically (`{"type": "response.create", "response": {"instructions": ...}}` only when instructions are given, else the original bare `response.create`); all 4 broker-authored injection call sites (`broker.py:1244, 2113, 2352, 2560`) switched from `send_text(prompt)` to `request_response(instructions=prompt)`. The one genuine passthrough site (`broker.py:699`, real client-supplied text) is untouched. `python -m unittest discover -s plugin/tests` — 1073/1074 green (the one failure is the pre-existing, already-documented `test_reads_hermes_xai_oauth_credential_pool` fixture gap, unrelated). **Deployed to the relay (2026-07-07) — still needs a real on-device voice session** confirming the model still speaks a natural summary when driven by `instructions` alone (no preceding fake user turn); watch for a background-task delivery in particular since that's the highest-traffic call site. **Confirmed live-verified (2026-07-08)** via the raw event log on the relay: a background run (~4min, terminal tool ×9-10) delivered its spoken summary correctly through the new `request_response(instructions=...)` path (`voice.response.started` → `voice.output_audio.delta` ×N → `voice.response.done`, clean).
- **NEW (2026-07-08) — xAI closes the realtime session after 900s of true silence; no provider-side keepalive exists.** Same live event log: after the background-task summary above finished speaking (`voice.response.done` at `at_ms=242226`), there is a **complete gap of zero events** until `voice.error` at `at_ms=1138277` — "xAI Realtime error: Conversation timed out after 900.0 seconds due to inactivity" (896s of nothing). This is **not a regression from the injection-framing fix** — the background task itself ran well within our own `_BACKGROUND_RUN_MAX_SECONDS` (300s) ceiling and delivered cleanly; the session simply sat idle afterward (no further speech, no client interaction) until xAI's own server-side inactivity timer fired. Root cause: the existing heartbeat (`_send_hermes_run_progress` / `_should_continue_heartbeat`) only protects the **client-facing** `ws` connection from the phone's own ~90s silence watchdog — nothing pings or otherwise generates activity on the **provider-facing** `session.native_connection` (the actual xAI/OpenAI socket) during a quiet stretch. **FIXED in code (2026-07-08) — needs relay deploy + live probe verify.** The client-side question is answered: mic capture is turn-bracketed by design (`startListening()`/`stopListening()` bracket one utterance shipped as `append×N + commit`; `turn_detection: None` means no continuous stream ever exists), so *any* quiet stretch ≥900s starves the provider socket — background waits are just the most common way to get there. It's not a client bug; the fix is relay-side. Implemented: `_provider_keepalive_loop` in `broker.py` — a per-connection task (created with the provider connection, cancelled in `_close_native_session`, runs through detached periods) that appends ~100ms of silent PCM whenever the provider socket has seen no sends/events for `RELAY_VOICE_PROVIDER_KEEPALIVE_MS` (default 240s ⇒ 3 pings per 900s window; `0` disables). Append-only deliberately — no `input_audio_buffer.clear` (could race a user utterance starting between check and send); uncommitted silence just prefixes the next utterance by a sliver. Activity is stamped in two places only: the client `input_audio.append` path and once per provider event in `_pump_provider_events`. Belt-and-braces: `_is_provider_idle_timeout` classifies a residual idle-close and surfaces "Voice session expired after a long silence" instead of the raw provider error. 11 new tests (`plugin/tests/test_realtime_keepalive.py`) green; existing 54 realtime tests green. Phase 0 docs corrected (xAI = `needs-keepalive` ≥900s; `docs/realtime-voice-poc.md` revision + ADR 33 note). **Remaining:** (1) deploy to relay, then on the relay host run the probe repro + fix check — `scripts/realtime-provider-idle-probe.py --provider xai --windows 960` (expect idle-close) and `--windows 960 --keepalive-ms 240000` (expect survival). This also answers the one open empirical unknown: whether an uncommitted buffer append resets xAI's inactivity timer — if it does NOT, fallback is a periodic no-op protocol message or scheduled reopen. (2) A live voice session left silent >15 min should stay usable.
- **Realtime voice: provider-answered turn durability — gateway drain + provenance badge SHIPPED (2026-07-08); app-restart persistence still open.** Shipped in code (needs on-device verify with the rest of the voice batch): (a) **gateway trace drain** — a gateway-configured turn with unsynced synthetic sync messages (voice intents / card dispatches / provider-answered realtime turns) now forces itself onto the sessions SSE route so the traces actually reach the server (previously "leave them for the next SSE turn" meant *never* on a gateway-primary phone). Deliberately narrow: only with an existing session id + the sessions fallback route (a stateless completions/runs detour would drop the turn itself from the transcript) and only on the default profile (a non-default profile's gateway session lives in its own state.db — the shared api_server POST would 404 and fail the user's turn; that residual defer case is accepted). The synced-mark guard now checks the route the turn actually *dispatched* on (`effectiveEndpoint`), also fixing a latent duplicate-resend for forced-SSE voice turns. (b) **provenance badge on reload** — `RealtimeTurnSyncBuilder.stripProvenanceMarker()` recognizes the synced `[Realtime Agent provider-native voice turn: …]` marker in loaded history, strips the bracket noise, restores the quiet "Realtime Agent" badge (same chip live turns get), and drops the superseded local clientOnly bubble so the exchange doesn't render twice. **Still open — app-restart loss:** unsynced traces are in-memory only; a restart before the next Hermes turn loses them. A fix needs a client-side pending-trace store (DataStore) plus answers to: which session should late traces sync into (voice binds per-session; the next turn may be a different session/profile), and restore-as-bubbles vs builder-side-only. A true flush-on-voice-exit is NOT implementable without an upstream append-messages API (every chat POST runs the agent); the drain above narrows the exposure window to "restart before the very next turn." Deliberately NOT a separate relay transcript store (forks the conversation).
- ~~**Realtime voice: subtle "Voice" provenance chip (2026-07-08).**~~ **Done via the durability item above** — turned out message-level "Realtime Agent"/"Voice" badges already rendered for live turns (`MessageBubble.kt` VolumeUp chips); the actual gap was reloaded history showing raw bracket provenance instead of the badge, now fixed by the marker → badge restore.
- **Pre-existing test failure:** `test_realtime_voice_routes.py::
  test_reads_hermes_xai_oauth_credential_pool` fails at HEAD too (`token is None`) —
  looks like an environment/fixture dependency on a local xai oauth pool, not a code
  regression. Diagnose or gate on the fixture.
- **Standard voice `delegate_task(background=true)` nudge — SHIPPED then
  REVERTED same-day (2026-07-08); premise disproven by the VERIFY-FIRST
  check.** The nudge (a `STABLE_VOICE_INTERFACE_CONTEXT` line telling the
  model to background long voice asks) was implemented, then the companion
  verify-first item below was actually checked against upstream source and
  killed it: **`delegate_task(background=true)` never dispatches async on the
  api_server surface at all.** Upstream downgrades it to synchronous
  execution (issue #10760): every api_server route binds
  `async_delivery=False` (`gateway/platforms/api_server.py` ~4000), and
  `tools/delegate_tool.py` (~2775) checks
  `gateway.session_context.async_delivery_supported()` and runs the batch
  inline with a "ran SYNCHRONOUSLY" note — "the adapter's send() is a no-op,
  so a background dispatch would silently never re-enter the conversation."
  Since ALL standard voice turns are forced onto SSE (ephemeral prompt slot),
  the nudge would have made the model block just as long (plus subagent
  overhead) while claiming it backgrounded. Reverted in `45c7ef4`. If a
  "don't hold the voice floor" behavior is ever wanted on the standard path,
  it needs the upstream async-delivery gap fixed first (a poll/webhook
  delivery channel for stateless sessions — upstream contribution), or the
  Relay realtime engine, which already has real background runs (ADR 33).
- ~~**Standard voice: speak a delegated result if the overlay is still open when
  it lands.**~~ **CLOSED 2026-07-08 — premise gone.** There is no delayed
  `delegate_task` completion turn on the standard voice path: the api_server
  surface downgrades `background=true` to synchronous execution (see the
  reverted-nudge entry above), so the "delegated result landing later" case
  this wanted to speak cannot occur on SSE. On the gateway transport a
  background completion does re-enter as a new turn — whether the phone's
  gateway client renders an unsolicited idle-time turn is a separate
  (text-chat) question, tracked nowhere yet; add it if gateway background
  delegation becomes a used flow on phone text chat.
- **VERIFIED 2026-07-08 — a `delegate_task` completion turn can NEVER reach an
  api_server-sourced session, because upstream never dispatches one there.**
  Answered by reading current upstream source (clone @ `5057f03bf`): the
  question is moot one layer earlier than expected. Every api_server route
  binds the session context with `async_delivery=False`
  (`gateway/platforms/api_server.py` ~4000, "the stateless HTTP path");
  `tools/delegate_tool.py` (~2775) consults
  `gateway.session_context.async_delivery_supported()` and, when false, runs
  the whole batch SYNCHRONOUSLY with an explanatory note (issue #10760) —
  there is no detached child, no completion event, no forged turn. The
  `_async_delegation_watcher` → `_inject_watch_notification` →
  `adapter.handle_message()` path only ever fires for sessions whose origin
  routes to a real push-capable platform adapter (gateway chats, Discord,
  etc.). Consequences applied same-day: the voice delegate nudge was reverted
  and the speak-on-overlay item closed (entries above).

## Relay-enhanced standard voice for background tasks — research (2026-07-08)

**Verdict: NO — don't build it.** Full owner ask + Fable 5 agent research (cross-
checked against hermes-desktop's actual source, found in the local upstream
monorepo clone). Three lanes already cover "a long voice request survives and
reports back": (1) standard voice isn't a blocking call — a long turn just keeps
streaming, and the #166 SSE-recovery poller + `TurnCompleteNotifier` already
recover + notify on a dropped socket, zero relay involvement; (2) upstream's own
`delegate_task(background=true)` is the standard-path equivalent of the realtime
broker's `hermes_run_task` promotion — the model can detach a long task itself;
(3) hermes-desktop's own voice hook (`apps/desktop/src/app/chat/composer/hooks/
use-voice-conversation.ts` in the upstream monorepo — verified, zero mentions of
background/promotion) is the same thin synchronous record→transcribe→submit→speak
loop with NO background awareness; their background-task UX lives entirely in the
chat/composer surface (a status stack + native OS notification, never spoken) —
convergent with Android's existing background-run chip / `SubagentLane` /
`TurnCompleteNotifier`, not a gap to fill. Building a relay-side background layer
for standard voice would mean proxying an upstream-only surface through the relay
or monkey-patching deeper than the accepted `plugin/enhancements/` seam — against
the standard-path rule — to duplicate machinery ADR 33 itself calls the most
fragile code in `broker.py`, for an audience realtime already serves better.
Action items from this research are above (prompt nudge, speak-on-overlay-open
polish, the api_server-routing verify-first gate).
- **Prewarm cost watch.** Voice-mode entry now opens the provider session before the
  first utterance. If users habitually open+close voice mode without speaking, idle
  provider sessions cost connect/teardown churn — consider a short "no utterance in
  N min → close" reaper if it shows up in practice.
- **E2E verification pending** for the new paths on-device: deferred result spoken on
  resume after a mid-run drop; proactive notification when the session dies for good;
  busy answer on a second task; adaptive promotion timing; first-turn latency with
  prewarm; the live background-run chip (progress line/steps/timer, RECONNECTING and
  DELIVERING phases, ✕-to-cancel).
- **Ambient background-run visibility OUTSIDE voice mode.** Exiting the voice overlay
  mid-run leaves no on-screen indication a task is still going (the run survives and
  the result arrives as a notification via the proactive fallback). Surface a small
  indicator on the chat screen — natural home is the bottom `RelayStatusStrip`, which
  the connection-management work owns → **coordinate before implementing**.
- **Dev-env note:** the local hermes-agent app venv (`AppData/Local/hermes/...`) can
  prune `aiohttp`/`segno` (uv sync), breaking `python -m unittest plugin.tests.*` with
  ModuleNotFoundError — restore with
  `uv pip install --python <venv>/Scripts/python.exe aiohttp segno`.

## Phone as a Hermes platform (proactive agent → phone)

Phase 1 (end-to-end spine) shipped on `Codename-11/phone-platform` — `send_message target=phone` → loopback `/phone/message` → relay `ProactiveChannel` → phone WSS → system notification, gated off by default (`PHONE_ENABLED` server-side + "Let Hermes message me" app-side + pairing). Remaining:

- **Phase 2a — dedicated "Hermes" inbox surface.** An always-present inbound conversation/section for agent-initiated messages (reuse chat *rendering* components, do NOT restyle — chat-ux worktree owns visuals). Land proactive messages there in addition to the notification. `ProactiveMessageHandler.onReceived` + `dispatch()` are the seams already in place; key the surfacing on `ProactiveMessage.surfacing` (notification / inbox / session / default = notification + inbox). Needs a small persistence store + a nav entry.
- **Phase 2b — session injection.** Deliver a proactive message into the relevant/active chat session (continue that conversation) when `surfacing == "session"`. Keep the `ChatViewModel` change SMALL/localized (one injection entry point) to avoid conflicting with the chat-ux branch.
- **Phase 2c — two-way reply. SHIPPED + DEVICE-VERIFIED (2026-06-29).** All three legs landed: (1) inline-reply notification (`RemoteInput` + `ProactiveReplyReceiver`) + a reply box in the Hermes inbox; (2) `proactive.reply` envelope (app→relay) buffered by `ProactiveChannel` + a loopback `GET /phone/replies` long-poll; (3) `PhoneAdapter.connect()` inbound loop drains `/phone/replies` → `handle_message()` so the reply continues the originating conversation (keyed by `chat_id`/`reply_to`), and the agent's answer rides the existing `send()` back. Inbound source is `role_authorized=True` (the relay pairing layer is the auth boundary), so replies work without `PHONE_ALLOW_ALL_USERS`. Verified via `python -m unittest` (proactive + phone tests) and `./gradlew :app:lint`, then **end-to-end on-device (2026-06-29)** after two faults the device test surfaced (see DEVLOG): `PhoneAdapter.connect()` was missing the `is_reconnect` kwarg the gateway passes (→ `TypeError`, adapter never connected, reply loop never polled); and a stale duplicate plugin copy in the user-plugins dir was winning the loader's name-dedup, so the gateway loaded old code and ignored every deploy. Residual deferred (below): outbound buffering / a persistent send-when-reconnected queue (currently the agent's answer `503`s and is **lost** if the phone's subscription dropped); inbound media in replies (text-first in v1).
- **Phase 3 — full controls.** DataStore-backed `ProactivePreferences` expanding `data/ProactivePrefs.kt`: quiet hours / DND (suppress or defer), per-profile push scoping, rate limiting (debounce/cap), and TTS-on-voice (route to the existing voice player API when a voice turn is active — call, don't modify, the voice path). Surface on the existing `ProactiveSettingsScreen`. Also: a persistent outbound-reply queue so a reply typed while the relay is disconnected (notification inline-reply in a killed process, or a dropped WS) is sent on the next connect instead of dropped.

**Maintainer verification (live box + device — can't be done off-device):**
- Live gateway must discover the plugin (`~/.hermes/plugins/hermes-relay` → `plugin/`) and `plugins.enabled` must include `hermes-relay` for the `phone` platform to register. Confirm `phone` appears in `hermes gateway status` with `PHONE_ENABLED=1`.
- End-to-end: with the app paired + "Let Hermes message me" on, run `send_message target=phone text=...` (and a cron `deliver=phone`) and confirm a notification on the device. Verify 503 (no phone) and the off-by-default gates.
- **Phase 2c reply round-trip — ✅ DONE (verified on-device 2026-06-29).** Confirmed: agent → phone notification → inline reply → drained through the relay's loopback `GET /phone/replies` (different process) → `handle_message` (`role_authorized=True`, no `PHONE_ALLOW_ALL_USERS`) → agent answer back in the *same* thread. Both fixes required (see DEVLOG / the Phase 2c bullet above).
- **FIX: cron `deliver=phone` / standalone send is broken.** Live testing: `hermes send --to phone` returns `{"error": "Unknown platform: phone"}`. The standalone (non-gateway) send path doesn't run a `kind=standalone` plugin's programmatic `ctx.register_platform`, so it never learns `phone` — only the running gateway (which loads `register()` at startup) does. The agent path (`send_message target=phone` in the gateway) works and was verified end-to-end on-device; the standalone/cron path needs the platform discoverable there too (declare it so the standalone loader picks it up, or route cron through the gateway). Until then `cron deliver=phone` won't work.
- **FIX SHIPPED (2026-07-07) — installer + doctor guard against stale duplicate plugin copies; live-host verify pending.** Root cause of the 2026-06-29 round-trip failure: the gateway loader dedups discovered plugins by manifest `name`, so a second directory declaring `name: hermes-relay` (an old-installer backup copy, or a stray native install) could win the dedup and make the gateway load stale code — silently ignoring every later deploy. `plugin/doctor.py` now emits a `plugin-name-unique` warning when more than one directory under `~/.hermes/plugins/` declares the same plugin name (distinct real targets only — two links to the same target are deduped), and `install.sh` sweeps any such duplicate so only the canonical `hermes-relay` symlink survives. (Current `install.sh` already `rm -rf`s the old link rather than backing it up inside the plugins dir, so the original "back up outside the plugins dir" half is moot.) **Verify on the live host:** `hermes relay doctor` reports the `plugin-name-unique` check, and a reinstall leaves exactly one `hermes-relay` entry under `~/.hermes/plugins/`.

## Phone platform — usability roadmap (post device-verification, 2026-06-29)

**North-star (2026-06-29): the phone should replace Discord-on-the-phone for agent contact.** The agent lane is meant to be a place you live in — proactive messages land, you reply inline or open a real thread, you multitask in and out of it like a chat app. That framing (not "an inbox of notifications") drives every item below: it must feel like a first-class messaging surface, attributed as its own gateway lane, with the conversation persisted and continuable.

**Decision (2026-06-29): "separate lanes, unified surface."** The phone/agent conversation stays its own **gateway-platform lane** — distinct from the Standard Chat tab, which must keep working on vanilla upstream Hermes with no plugin — but is surfaced as a **first-class chat-style thread** that reuses the chat UI and sits alongside Chat. NOT a Chat "transport": a transport is an interchangeable pipe for the *same* user-chat conversation; the phone platform is a *different* conversation (agent-initiated, own session store/attribution, relay auth), so treating it as a transport miscategorizes it and couples a standard surface to a relay-only capability.

**Refinement (2026-06-29) — unified-session model: "Threads."** Going further on "unified surface": the agent conversation is **not a separate tab/segment** at all — it is a **source-tagged session inside the one Chat surface**, a **Thread** (`source=phone`). What makes a Thread special vs. a normal gateway chat are *session properties*, not a separate UI: (a) the agent can initiate, (b) relay `proactive` transport + relay-gated, (c) standing/named DM. **Scrollback = the gateway session store** (same read path Chat uses); **live receive = relay `proactive` push** (→ notification); **send = `proactive.reply`**. `ProactiveInboxStore` is demoted to a live-push cache + outbox (no parallel history). The Thread capability shows in the **best-path/capability UI** (relay tier, like terminal/bridge/voice) and as a clean **Threads** entry — thread-spool icon, NOT a phone glyph — pinned atop the session drawer when active; never a connection-wizard step. Degrades cleanly (no plugin → no `source=phone` sessions → Chat unchanged). **Supersedes the "separate Agent lane / 4th nav segment" sketch** and merges with the "source attribution in Chat" goal below. Keep the two "gateway" senses straight: *platform layer* (the Thread's `source`) ≠ *dashboard `/api/ws` transport* (how live bytes flow). Full re-cut: docs/decisions.md ADR 12.

- **Outbound buffering — ✅ relay-side DONE (2026-06-29).** `ProactiveChannel.push()` now queues agent→phone messages in a bounded deque (drop-oldest, 24 h TTL) when no phone is subscribed and returns `{queued: true}` (not 503); `_flush_outbound` delivers FIFO on the next subscribe (stale pruned). Inspect/cancel via `peek_outbound`/`cancel_outbound` + loopback `GET`/`DELETE /phone/outbound`. **UI surfacing of the queued state** (host-side, since the queue exists while the phone is OFFLINE): (a) ✅ **desktop CLI `relay queue` / `relay queue --clear` / `--cancel <id>` DONE (2026-06-29)** over the new endpoints (loopback-only — run on the relay host); a dashboard Relay-tab view is the optional GUI equivalent; (b) **remaining** — in the threaded agent surface, mark messages that arrived-while-away, and show the user's OWN pending replies (the Phase 3 reply queue) with a sending/Cancel affordance — that's where phone-side "queued + cancel" belongs.
- **Threads surface (unified-session model — see ADR 12 + the Refinement above).** Build order, each shippable: **(1)** source tags in the session drawer (`source=phone` → clean **Threads** chip + thread-spool icon, NOT a phone glyph) — also delivers the "source attribution in Chat" goal; **(2)** open a Thread in Chat from its session-store history (reuse the existing message-history path); **(3)** route the live `proactive` push into the session view + notification + unread, demoting `ProactiveInboxStore` to cache/outbox; **(4)** reply from the Chat composer via `proactive.reply` + persist the user turn + local `Sending/Queued/Failed` status — **MVP**; **(5)** a **Threads capability row** in the best-path UI + a pinned **Threads** entry atop the drawer (thread-spool icon, shown only when relay-paired + opted-in) + retire `HermesInboxScreen`, re-point the notification deep-link + Settings "View messages"; **(6)** outbox/retry on reconnect; **(7)** relay `proactive.reply.ack` (honest Delivered) + `proactive.cancel`; **(8)** multi-thread `chat_id` (named/project Threads). **Verify gate before (1):** confirm the app's session-list/history path surfaces a `source=phone` session cleanly (upstream `session.list` returns all sources flat, so it should — but check whether the drawer currently filters it out). Honesty call: do NOT show "Delivered" until (7) lands (can't confirm it client-side before the ack).
  - **Status (2026-06-29, implemented UNBUILT — verify in Studio):** **CODE-COMPLETE on `dev`:** slice **1** (drawer source tags + `ThreadSpoolGlyph` + Threads filter), **2** (open a Thread from history — free via the existing `loadSessionHistory` path), **3-parse** (carry `reply_to` on `ProactiveMessage`), **4** (composer reply in a `source=phone` session routes over `proactive.reply`; `MessageDeliveryStatus` SENDING→DELIVERED/FAILED on the bubble), **5** (Threads capability row in `SessionPathCard` + `threadsCapabilityActive` drawer wiring), **7** (relay `proactive.reply.ack` + `proactive.cancel` — 25/25 `unittest` green — and client ack handling). **DONE since (2026-06-29, built + on phone):** live **in-thread reply rendering** (an agent reply lands in the open Thread as an ASSISTANT bubble, suppressing the notification/inbox — `injectIntoThread`); **user-created named Threads** ("+ New Thread"); **retire `HermesInboxScreen`** (deleted; route + nav removed; notification tap + Settings "View messages" re-pointed to Chat; surface renamed "Hermes messages" → **"Threads"**); relay slice-7 ack/cancel **DEPLOYED** to the host so **"Delivered" is live**. **DEFERRED (reasons):** per-session **unread badge**; **outbox/retry** (needs multiplexer connection-state); **exact-Thread deep-link** from the notification (opens Chat today, not the specific thread — needs select-session-on-entry); **remove the now-orphaned `ProactiveInboxStore`** (viewer-less write-only log); **agent-initiated** named Threads (upstream `send_message` thread param). On-device verifies for the create-flow: fresh-`chat_id` auto-create, the `…:dm:<chat_id>` id form, `renameSession` on a phone session.
  - **User-created Threads (slice 8, Discord-style) — CODE-COMPLETE on `dev` (built + installed 2026-06-29; on-device behavior pending).** "+ New Thread" in the drawer's Threads view → name dialog → `ChatViewModel.startNewThread` mints a fresh `chat_id`; the first composer message opens it over `proactive.reply` (gateway auto-creates the `source=phone` session) → `switchToCreatedThread` polls + switches to the real session + applies the name. Existing-thread replies route by the `chat_id` parsed from the session id (`…:dm:<chat_id>`; opaque id → home fallback). **On-device verifies:** (1) a fresh-`chat_id` no-`reply_to` inbound creates a new `source=phone` session; (2) the phone session id carries the `…:dm:<chat_id>` form the client parses; (3) `renameSession` titles a phone session. **Remaining slice-8:** AGENT-initiated named Threads (the upstream `send_message` thread/chat_id param so the agent can open its own named Threads).
  - **`chat_id` not exposed by `/api/sessions` (root cause of the 2026-06-29 on-device create-flow bugs — fixed client-side).** Confirmed on the host: a phone session's `id` is a timestamp (e.g. `20260629_204755_94f391d6`); the real `chat_id` lives in the `session_key` (`agent:main:phone:dm:<chat_id>`) and a `chat_id` column — but `/api/sessions` returns **neither `chat_id` nor `session_key`**, only `source` + the timestamp `id`. So the client could not map a session ↔ its `chat_id`, which broke create-thread switch/rename + reply routing + in-thread injection. **Client workaround shipped:** find a created thread by session-list **diff** (the new `source=phone` session), keep an in-memory `sessionId → chat_id` map (learned at creation + from incoming `phone.message`s) for reply routing, and inject by source (+ learned chat_id) rather than a parsed id. **Limitation:** for a thread the app didn't create *this* session (agent-created, another device, or after an app restart) `chat_id` is unknown until a message arrives while viewing it → its replies fall back to the home channel until then. **RESOLVED via the plugin (2026-06-29, per upstream-or-plugin policy):** the relay now exposes `GET /phone/threads` (`plugin/relay/session_store.py` reads the gateway store read-only → `[{session_id, chat_id, title}]`; `server.py` `handle_phone_threads`, bearer for the app / loopback for diag; 5 unit tests). The app (`RelayHttpClient.fetchPhoneThreads` → `ConnectionViewModel.phoneThreadChatIds` on every `auth.ok` → `ChatViewModel.seedThreadChatIds`, authoritative over the learned map) now routes replies correctly for **any** Thread — incl. ones it didn't create + after restart. Deployed + verified live. **Still-nice-to-have (lower priority): the upstream PR** to add `chat_id`/`session_key` to `/api/sessions` (the standard-path proper fix; the relay route then becomes redundant + the client prefers upstream when present).
- **Threads as named/project conversations (Discord-parity — folds into multi-thread #8).** A stable *named* `chat_id` per project = a persistent, agent-reachable project Thread (Discord named-thread parity for "persist a session for a project"). Enables: the agent **opening** a new named Thread for a background job/topic (a relay/gateway "open thread" affordance + a `send_message`-adjacent tool); cron/job updates landing in their own Thread; and replying to a Thread from any surface (desktop CLI / dashboard) since it is just a gateway session. Also evaluate per-Thread profile binding (a project Thread uses the "work" profile — ties to profile=contact).
- **Thread vs. normal gateway chat — keep complementary, don't force one.** A Thread is a gateway chat + proactive delivery + `source` attribution. Use a *normal* gateway chat for live foreground interactive work (live `reasoning.delta` over `/api/ws`); use a *Thread* for persistent/named/agent-reachable/background-delivered conversations. Possible future enhancement (verify first): when a Thread is open in the foreground, allow a live `/api/ws prompt.submit` turn into that `source=phone` session for live reasoning — but confirm it does NOT break platform attribution or the proactive reply loop before relying on it; the proven send path stays `proactive.reply`.
  - **LOOK INTO (own item, owner-requested 2026-06-29): live `/api/ws` transport for a foregrounded Thread.** Goal: when a Thread is open in the app foreground, give it the *same* live experience as Chat (live `reasoning.delta` + tool-progress) by running the turn over the `/api/ws` dashboard-gateway transport into that `source=phone` session, instead of the notification-grade `proactive.reply` path. Spec the experiment: (1) does `session.resume` + `prompt.submit` on a `source=phone` session over `/api/ws` keep `source=phone` (not silently re-tag `tui`)? (2) does it bypass `PhoneAdapter` / the role_authorized reply loop, and does that matter when the user is the one typing? (3) reconcile the two send paths (foreground→`/api/ws`, background/notification→`proactive.reply`) without double-sends. If it holds, a Thread becomes "background-delivered like a DM, but live like Chat when you open it" — the best of both. Until verified, `proactive.reply` stays the only send path.
- **Docs/user-docs for Threads (lockstep — author with the user-facing slices 4–5).** Dev refs are done (ADR 12 carries the unified-session decision + the two-"gateway" split). Still to write when the surface ships: a plain-language `user-docs/features/threads.md` — what a Thread *is*, **Chat vs Threads** (live foreground work vs. persistent, agent-reachable conversations), the two opt-in gates, that it's relay-only — plus a **brief in-app explainer** (e.g. a one-line hint on the Threads filter empty state or a small info affordance, not a wall of text), and `docs/relay-protocol.md` + relay-server route docs for the wire. Replace the stale user-docs "Coming Soon → Push Notifications" row; keep it distinct from the clipboard inbox and the inbound Notification Companion.
- **More Threads fold-ins (capture now, build with the relevant slice).** (a) **Read-state back to the agent** — tell the gateway you saw a proactive message (Discord-style read receipt) so the agent knows; fold into the `proactive.reply.ack` design (#7). (b) **Cross-surface reply** — because a Thread is just a gateway session, a reply could come from the desktop CLI / dashboard too, not only the phone; near-free once unified, verify the reply routing. (c) **Priority/importance on a proactive message** — let the agent mark urgent vs FYI → notification importance / quiet-hours bypass; small payload field + maps to the notifier channel.
- **Per-thread `chat_id`.** Everything is hardcoded `chat_id="phone"` (one thread) today; the adapter already plumbs `chat_id`, so varying it yields multiple threads (per topic, or the agent opening distinct conversations). Ties into the threaded surface.
- **Message status + delivery state.** Surface sent / delivered / queued / failed per message in the thread (depends on outbound buffering's queued state) so the user knows whether the agent actually reached them.
- **Auto-title the phone thread** like other sessions (first confirm whether the gateway already auto-titles platform sessions; wire it through if so).
### Discord/Telegram replacement — capability gaps (to fully retire reaching for them)

The gateway-platform model is the *correct + sufficient architecture* (the phone is a registered platform peer, so anything that routes to a platform — `send_message`, cron `deliver=`, channel directory, background jobs — can reach the phone). These are the concrete gaps between "architecturally a peer" and "I never open Discord":

- **Guaranteed background delivery (the biggest gap; no push today).** Delivery is **live-WSS-only** + a 24 h relay buffer; there is **no FCM/UnifiedPush** wake-up. If the app process is dead AND not holding a socket, a message waits for the next reconnect, and the relay buffer is ephemeral (lost on relay restart). Discord/Telegram feel instant because they wake the device via push even when the app is dead. Decide a **push transport**: **UnifiedPush/ntfy** (recommended — self-hostable, no Google dependency, upstream *already* ships an `ntfy` platform, on-brand for self-hosted) vs **FCM** (simplest UX but adds Play Services + a push relay; clashes with self-hosted ethos — at most the `googlePlay` flavor) vs **persistent foreground keep-alive service** holding the relay WSS (zero new infra, like `GatewayKeepAliveService`, but battery cost + Doze-fragile). Likely: UnifiedPush primary + foreground-keepalive fallback.
- **Cron / background-job delivery is BROKEN** (already tracked above): `deliver=phone` standalone path → `Unknown platform: phone`. This is load-bearing for "receiver of crons/background jobs" — fix is required, not optional, for the replacement goal.
- **Multi-thread is wired-for but never varied** (already tracked: per-thread `chat_id`). For real DM/channel parity the agent must *open distinct threads* (vary `chat_id` per topic/job), the app must render a **thread list** (N conversations, not one), and replies route back by `chat_id`+`reply_to` (already plumbed).
- **Durable history / scrollback.** The relay buffer is ephemeral; a real messaging surface needs persisted scrollback. Read the gateway **session store** for the `phone` platform's history (relay-exposed read path) so reopening a thread shows the full conversation, not just buffered-while-away.
- **Profile = contact mapping (new idea, fold in).** Multiple Hermes **profiles** (distinct agent personas/configs) could each be a distinct thread *source*/"contact" — DMing different agents. Maps cleanly onto the per-thread `chat_id` + source-attribution work; lets the app feel like a contact list of agents.
- **Per-thread notification controls + deep-link (Discord-parity affordances).** Per-thread notification channels, mute/DND/quiet-hours (Phase 3 partially), and a notification that **deep-links into the exact thread** (tap → land in that conversation) so dipping in/out while multitasking is frictionless.
- **Agent-initiated rich content.** Agent → phone thread with **images/cards** (relay media infra + `InboundAttachmentCard`/`HermesCardBubble` already exist on the chat side — reuse). Inbound (phone → agent) reply media stays deferred (text-first), but outbound rich content is low-cost parity.
- **In-thread "agent is working" indicator.** A typing/working state in the thread while the agent thinks/runs tools (Discord typing-dots parity) — the chat surface already has thinking indicators to reuse.

- **Source/platform attribution + filtering in the drawer (NOW READY — owner-requested 2026-06-29; the gateway/Threads surface has shipped).** `/api/sessions` DOES expose `source` (confirmed live: `tui`, `cli`, `api_server`, `web`, `discord`, `telegram`, `cron`, `webhook`, `phone`). Build: **(a)** a clean **source badge** per session in the drawer — phone → the thread-spool (done); discord / telegram / cron / webhook / web → a small per-platform chip/icon (match hermes-desktop's convention); the app's own `tui`/`api_server` chats get no badge (or a subtle one). **(b)** a **filter** (drawer dropdown) to show/hide sources. **(c)** a **setting** (Chat settings) for the default — **hide the agent's other-gateway/automation sessions (cron / webhook / discord / telegram) by default** so the drawer shows just your chats + Threads, with a toggle to reveal them (the live default `state.db` is full of cron/discord/webhook noise). Persist the visibility prefs. Can't see the official desktop (no clone) — infer its chip styling; match exactly if specifics surface. Standard-path: read-only display of the upstream `source` field. Fold cross-restart **Thread-name persistence** (currently in-memory) into this drawer pass.
- **Beta-gate the Threads featureset (owner direction 2026-06-29).** Mark Threads **Beta** with a clean badge in the UI (the Threads filter chip + the best-path "Threads" capability row) until the enhancements land. Full (non-beta) release is gated on: **live `/api/ws` transport for a foregrounded Thread** (an open Thread streams like Chat — the headline), per-session **unread**, the **`chat_id`-on-`/api/sessions` upstream fix** (so threads route after restart / cross-device), and **outbox/retry**.

## Voice — Standard-path parity follow-ups

- **On-device verification of the Server voice config card.** Static read + unit tests cover the client/merge logic, but the card's render, provider-scoped field switching, the ElevenLabs picker (key-present and `available:false`), and a live save round-trip against a real dashboard still need an on-device pass. Confirm a save reaches `config.yaml` and the next voice turn reflects it.
- **Generic Manage "Config" tab is still read-only.** This work added a *voice-scoped* editor; the Manage Config tab still renders `/api/config/schema` as two non-editable rows (`fields`, `category_order`). A full schema-driven editor for all categories (general/agent/terminal/…) grouped by `category_order`, GET-merge-PUT-whole, is a separate, larger task if we want full desktop Config parity.
- **`silenceThresholdMs` default change (3000 → 1250 ms) is user-facing.** Confirm on-device that 1.25 s end-of-speech doesn't clip slow speakers in real use, and that the new 12 s idle/no-speech auto-close (which now also applies to Tap-to-talk — previously "wait forever") feels right. Easy to revert the default if too aggressive.
- **Standard voice config targets the launch-profile config (`profile = null`).** Standard voice is host-global, so the editor writes the base `config.yaml`. If a user runs a non-default *launch* profile, revisit whether to scope the config write to the active profile (the dashboard `/api/config?profile=` supports it).
- **CLI `voice.*` config not surfaced.** The editor covers `tts.*`/`stt.*`; the separate `voice.*` block (record_key, beep_enabled, the CLI's own silence_threshold/duration) is intentionally out of scope — surface it only if a phone use-case appears.
## Chat UI refresh — follow-ups

- **`/font <name>` slash command (optional, deferred).** The chat-UX brief floated a chat slash command mirroring the Appearance Font picker. Deferred to keep the work inside the chat-UI/theme lane: it needs the command intercepted in the chat send path (`ChatViewModel`) before it forwards to the server, plus a `SlashCommand` palette entry. The settings picker is the primary, shipped surface. Add `/font` later as a thin wrapper over `ConnectionViewModel.setAppFont`, discoverable via the slash palette.
- **On-device verification of the chat refresh.** The Roborazzi harness proves layout + that Inter/Nunito load as distinct faces host-side, but the final typeface crispness and feel (avatar size, bubble width, density on a real Samsung) are a maintainer on-device gate. Confirm the variable-font weights (400/500/600/700) resolve on-device and Inter reads clean at body sizes.
- **Growing the font set.** The `AppFont` registry is open — add more OFL/Apache faces (e.g. a serif or a display mono) by dropping a TTF into `app/src/main/res/font` and adding one enum entry; keep the license text in `licenses/`.

## Crash-class follow-ups

- **Verify the Tink pin didn't break EncryptedSharedPreferences (owner, on-device).** The Android-15 `removeFirst`/`removeLast` crash lint flagged `com.google.crypto.tink.hybrid.HybridConfig.<clinit>` in the Tink dependency. Our app pulls Tink transitively via `androidx.security:security-crypto` for `SessionTokenStore`'s `EncryptedSharedPreferences`, which uses the AEAD path (not Hybrid), so the flagged `<clinit>` is very likely never reached at runtime — but we pinned `com.google.crypto.tink:tink-android:1.16.0` (ahead of security-crypto's transitive Tink) to clear the Play warning. **This is untestable without a build:** a too-new Tink can break `EncryptedSharedPreferences` at *runtime* (a `NoSuchMethodError`, not a compile error, so `./gradlew build` won't catch it). On-device smoke: launch the app, pair/sign in, force-stop + relaunch, and confirm the stored session survives (no re-pair prompt) and no startup crash. If it breaks, the blast radius is one line — revert the `tink-android` pin (catalog + `app/build.gradle.kts`) and the token store falls back to security-crypto's transitive Tink; then either try a lower Tink (1.15.0) or leave the (unreached) warning.
- **Bridge screenshots: regrant UX.** Multi-device live smoke found that a device can report `screen_capture_granted=false` because the MediaProjection grant was revoked and needs an in-app/user-consent regrant. The e-ink timeout path has been hardened with a longer configurable wait and one capture-pipeline rebuild retry; remaining polish is to surface the regrant action more prominently in Bridge status.

- **Audit remaining throwing URL-build sites for the "Invalid URL host" class (#131).** The #131 fix guarded the two clients that take a user-entered base URL on the Manage/voice path (`DashboardApiClient`, `StandardHermesVoiceClient`) and validates input at entry. Remaining site groups:
  - **`HermesApiClient` streaming methods — DONE 2026-07-08.** `sendChatStream` / `sendCompletionsStream` / `sendRunStream` now build via the non-throwing `authRequestOrNull()` chokepoint (backed by top-level `buildApiRequestOrNull`, unit-tested like `buildRelayRequestOrNull`); a malformed base URL fails the turn through the normal `onError` channel ("Invalid server address …") and returns an inert EventSource instead of throwing out of the ViewModel. The whole #131 audit list is now closed.
  - **`ConnectionManager` WSS connect — FIXED 2026-07-07** (this was the confirmed crasher: Play 1.2.6 on a Galaxy S25 Ultra / Android 16, `IllegalArgumentException` from `HttpUrl$Builder.parse` via `doConnectInternal` → `Request.Builder.url()` on the IO coroutine). Now routed through `buildRelayRequestOrNull()` → graceful Disconnected + diagnostic instead of a throw. `ConnectionManagerUrlGuardTest` covers it.
  - **Remaining relay HTTP clients — DONE 2026-07-07 (defense-in-depth).** `RelayVoiceClient` now validates its base in `resolveHttpBase()` (returns null on a malformed URL → the existing `Result.failure` guards fire), and `RelayHttpClient`'s two string-URL sites (`fetchMedia`, `listSessions`) use `toHttpUrlOrNull()` → `Result.failure`. `RelayProfileInspectorClient` was already fully guarded (every `.toHttpUrl()` wrapped in `catch (IllegalArgumentException)`). The whole #131 relay class is now covered; `HermesApiClient` streaming (the other lower-risk group above) remains the only open item.

## Session titles (#133) — follow-ups beyond the client fixes

The client-side mitigations shipped (see DEVLOG 2026-06-27): the `updateSessions` clobber guard, the post-turn title reconcile (gateway), and the subtle "not auto-named here" drawer note on SSE. These two are the larger follow-ups:

- **Upstream PR: auto-title on the api_server surface.** `APIServerAdapter._run_agent` (`gateway/platforms/api_server.py:3492`) calls `agent.run_conversation(...)` and returns without ever invoking `agent.title_generator.maybe_auto_title` — so `/api/sessions/*/chat[/stream]`, `/v1/runs`, and `/v1/chat/completions` never auto-name sessions (only the gateway/tui_gateway → cli.py path does). Mirror the gateway call site (`gateway/run.py:15493`): after a successful first exchange, fire `maybe_auto_title(self._ensure_session_db(), session_id, user_message, final_response, history, main_runtime={...})` in the existing thread-executor return path. Standard-path rule applies — it's an upstream contribution; our client degrades gracefully until it merges. This is the proper fix for the SSE-surface half of #133.

- **Relay-side patch (interim, until the upstream PR lands).** Because the phone's SSE chat hits the upstream api_server **directly** on `:8642` (not through the relay on `:8767`), the relay can't intercept the turn to title it inline. Options to evaluate:
  - A relay background reconciler that periodically scans the shared `state.db` for untitled sessions with ≥1 exchange and titles them via the same auxiliary-LLM logic (`agent.title_generator.generate_title`) — essentially running upstream's titler out-of-band. Lowest client impact, but couples the relay to the session DB schema.
  - A relay `/sessions/{id}/title` helper the client can POST after an SSE turn to request server-side generation, keeping the LLM call (and key) server-side. More explicit, needs a client call.
  - Decision gate: prefer the upstream PR; only ship a relay patch if upstream review stalls. Keep it behind the relay (never the Vanilla Hermes path).

- **(Separate feature — DROPPED 2026-06-27) Client-side title generation via the main LLM.** Idea: when a session still lacks a server title after its first turn, have the app ask the main model for a 3–7-word title and persist it via `renameSession`. **Dropped because there is no client-reachable LLM endpoint that doesn't persist a session** — which would put phantom title-generation sessions in the drawer/history (the explicit no-go):
  - `/v1/chat/completions`: when no `X-Hermes-Session-Id` is sent, the server *derives* a session_id from the prompt fingerprint (`_derive_chat_session_id`) and `_create_agent` runs with `session_db=_ensure_session_db()` → the turn persists. 1 user + 1 assistant msg passes the drawer's `min_messages=1` filter → phantom row.
  - `/v1/responses` with `store:false`: `store` only governs the in-memory response-chaining store; `session_id = stored_session_id or uuid4()` is still passed to `_run_agent`, so it *also* persists a session row.
  - Reusing the chat's own session id would append the title prompt/response to the real conversation history — worse.
  - Why upstream is clean: `agent/title_generator.py` calls `auxiliary_client.call_llm` directly (raw provider call with the server's keys, no session machinery). The phone has neither provider keys nor a non-persisting endpoint, so it can't replicate that.
  - **Correct home = server-side** (the upstream api_server titler PR above, or the relay-side titler). A create-then-delete hack on the client (read `X-Hermes-Session-Id`, then `DELETE`) is fragile/racy and still flashes a row — not worth it. Revisit only if upstream ever exposes a non-persisting utility-completion endpoint.

- [x] **Session rename now profile-scoped on the gateway (fixed 2026-06-27).** Added `DashboardApiClient.renameSession`/`patchJsonObject` + `ConnectionViewModel.renameProfileScopedSession` + `ChatViewModel.profileSessionRenamer` (wired in `RelayApp`); `renameSession` routes through it when `streamingEndpoint == "gateway"`, falling back to the unscoped api_server PATCH otherwise. Verify on-device: rename a session on a non-default profile and confirm the title survives a drawer refresh / app restart.
  - **Profile-scoping audit result (2026-06-27):** rename was the *only* remaining gap. List (`profileSessionLister`), messages (`profileMessageLoader`), and delete (`profileSessionDeleter`) are already scoped; create on the gateway goes through `session.create` over `/api/ws` (inherently profile-correct); the SSE create-path auto-title PATCH (`ChatViewModel:2692`) targets the shared api_server DB where there are no profiles, so unscoped is correct; `/branch` is a server-side slash command. No further client-side session ops bypass profile scoping.

## User-Added:

- [ ] Verify profile selection retains voice config selections in all voice modes/configuration combinations - enhance UI/configurability/management for this.

### Thinking indicator — post-v1.3.0 follow-ups

The animated dot-matrix "thinking" indicator shipped in **android-v1.3.0** (Wave/Pulse/Bounce/Sparkle motions + Auto/accent colors, live preview in Chat settings; static when animations are off). Remaining:

- **OS-level reduce-motion / TalkBack** — currently gates only on the app's `animationEnabled` pref. Also honor OS reduce-motion + touch-exploration like `CleanChatMode` does (`rememberCleanMotionState().osAnimations`).
- **Optional: promote to a full avatar style** — the alternative scope (a `DotMatrixAvatar` `AgentAvatar` shown everywhere via `LocalAvailableAvatars`, selected in Appearance). Deferred in favor of the narrower in-bubble indicator.

## Demo mode (2026-06-27) — deferred polish

Shipped offline Demo / Explore mode (see DEVLOG 2026-06-27). Core is in; these are non-blocking polish items, none required for the Play "App access" fix:

- **On-device verify (Studio).** Confirm: "Try the demo" on the onboarding Connect page and the standalone Connect screen lands on Chat showing the canned transcript (Markdown, tool-progress card, weather card, code block); the persistent banner shows and its Connect exits demo into the real wizard; demo runs in airplane mode with no network; Manage/Voice show the demo empty state; Bridge/Terminal show their pair-gate; backing out of demo Chat clears the flag so a real connection still works.
- **Demo composer is a silent no-op — DONE 2026-07-08.** `sendMessage` now intercepts while `isDemoMode`: echoes the user bubble and appends `DemoContent.composerReply` ("offline demo, can't answer for real — tap Connect in the banner"), both clientOnly so demo-exit's `clearMessages()` wipes them. Wired via `setDemoModeWiring` (unconditional in RelayApp — the client-gated chat init never runs in demo, so ChatViewModel's own handler is null there). On-device check rides the existing demo verify item above.
- **Live voice mode in demo.** The voice-mode overlay (mic) launched from Chat isn't demo-gated — a tap would attempt a transcribe (fails gracefully, no crash). Add a demo notice / disable the mic in demo. (Voice settings screen already shows the demo empty state.)
- **Light typewriter/stream simulation.** The transcript is statically populated; an optional per-token reveal on first entry would better convey the "streaming" feel. Acceptable as static for v1.
- **Optional richer demo.** Could add a second tool type or an image attachment to the transcript to showcase more surfaces; kept minimal/one-file for now.

## Orchestration batch (2026-06-22) — deferred follow-ups

Four User-Added items resolved via a 4-worker orchestration pass (disjoint file ownership, coordinator-serialized commits): clean-chat viewport (`1dca285`), connections reframe (`c9fa8f7`), diagnostics/analytics (`c3098a9`), session-delete fix (`6552566`). Plus a follow-on profile-isolation fix raised mid-session: cold-start session-drawer hydration (`889273a`). **Committed to `dev`, NOT built/linted/verified.** Remaining:

- **Build + lint + on-device verify all five (Studio).** Run `./gradlew lint` and a Studio build before pushing `dev` (workers couldn't run gradle). Then confirm on device: clean-chat shows a noticeably taller text area that scrolls; deleting a session on a *non-default* profile sticks (no resurrection after the drawer re-fetches); the Diagnostics screen renders honest per-check status + failure reasons and opens detail on a failing tappable row; connections/voice/permissions copy reads "Hermes"/"Relay"; **and on a cold start while a non-default profile is selected, the session drawer loads that profile's sessions directly with no flash of the server-default list.**
- **Profile isolation — broader sweep (cold-start race).** The session drawer + restored session context are now gated on `ProfileController.selectionSettled` (`889273a`), so they no longer load the server-default profile before the persisted profile resolves. Other profile-scoped surfaces read the *live* `selectedProfile.value` and self-correct when it resolves but aren't gated: voice prefs (`VoiceViewModel.onProfileChanged` at the `RelayApp` voice effect), `profileDisplayAlias`, `profileIcon`. They re-seed on resolution (no visible content-flash like the drawer), but if any shows a wrong-profile beat on cold start, gate its first use on `profileSelectionSettled` the same way. Also: `selectionSettled`'s decision logic is unit-testable (pure over connId/selected/pending/profiles) — add a `ProfileControllerSettledTest` when convenient.
- **Diagnostics: no live re-probe trigger.** The status checks reflect the *last* probe state (read-only snapshot). A "Re-run checks" button would need `ConnectionViewModel` to expose probe methods — deferred so the diagnostics work didn't have to edit a concurrently-owned VM.
- **Diagnostics: Pass checks lack a last-checked timestamp/duration.** `StatusCheck` carries `timestampMs`/`durationMs`, but the VM doesn't expose probe timing, so passing rows show no "checked Ns ago". Wire when/if the VM surfaces probe timestamps.
- **Connections reframe — out-of-scope occurrences left intentionally.** `ConnectionViewModel.kt`, `VoiceAudioClient.kt`, `VoiceViewModel.kt`, `BridgeCoreScreen.kt`, and `RelayApp.kt` still contain "Standard"/"Vanilla" in code identifiers/log strings; only user-facing display copy was reframed. Revisit if any of those surface to users.

## Orchestration batch (2026-06-21) — deferred follow-ups

Client-side profile-lock + voice fixes (the items marked above) landed via a planning→implementation orchestration pass, **built + deployed to device as 1.2.1 (versionCode 15)**; new unit suite green (36 Kotlin + 11 Python). On-device behaviour verification still pending. Remaining from that batch:

- **Realtime voice: server-side half (Python) — DONE + DEPLOYED 2026-06-21.** `plugin/relay/realtime_agent/broker.py`: `_send_hermes_run_progress` now heartbeats while `session.hermes_task` is unfinished (helper `_should_continue_heartbeat`), closing the 90s stall at the source; spoken-status repeat raised 30s→90s and gated on a *coarse* status change (`_coarse_spoken_status_key` / `_should_repeat_spoken_status`) so tool-message churn no longer re-narrates. `plugin/tests/test_realtime_heartbeat.py` 11/11; `test_realtime_promotion` regression 5/5. Deployed: committed `d1820fb` → pushed to `origin/dev` → server `~/.hermes/hermes-relay` fast-forwarded + `hermes-relay` restarted (active, clean startup) — both client + server halves now live end-to-end (re-pair the phone after the relay restart). Optional follow-up: flip `promotion_enabled` default to True so long runs detach.
- **Voice override on the streaming path (open question).** The `.route`→`.effectiveRoute` fix makes 'auto'+relay engage the override-capable path, but the streaming `/voice/output` renderer reads the relay's server-saved `voice_output:` config, not the UI `enhancedVoice` override. Decide whether the override card should also push to `updateVoiceOutputConfig`, or whether an override should force the basic `/voice/synthesize` path.
- **Per-profile voice on Standard (upstream).** `/api/audio/*` is host-global/text-only; the Standard surface still can't carry a per-request voice. Needs the upstream profile-voice / `/v1/audio/*` PR. Until then the client prefers the relay path; consider surfacing an honest "override needs Relay" state when Standard is the effective surface.
- **Profile lock: ChatScreen glyph + export.** The optional lock glyph on the chat-header avatar was skipped (`ChatScreen.kt` is owned by a concurrent session). Decide whether the per-connection lock belongs in settings export/import (it rides the `profile_selections` DataStore).
- **Unit tests — DONE 2026-06-21 (36/36 pass via `:app:testSideloadDebugUnitTest`).** `ProfileLockStoreTest` (9 — uses an in-memory `DataStore` harness; the file-backed factory hits a Windows write-rename/instance race), `ProfileControllerLockTest` (8, Robolectric), `CoerceAudioRouteTest` (7), `VoiceStatusGatesTest` (12).
- **CHANGELOG.** Add `[Unreleased]` entries (Profile lock → Added; voice override + realtime → Fixed) at build-verify/PR time.
- **On-device verification.** Override applies in 'auto'+relay; realtime survives a &gt;90s background task without stalling and stops over-narrating; Speaking waveform unfolds at first audible frame; profile lock hides pickers + holds on a missing profile; overlay shows the profile icon.

## Hands-free agentic voice backlog

Goal: make Hermes usable for hands-free work without leaving the operator blind

to tool state, safety prompts, or the current task.

- **Waveform output-start sync** — current input waveform timing feels good, but

the agent-output waveform can unfold and begin movement before audible speech

starts. Split "preparing audio" from "speaking audio" in the visual layer, or

gate the unfolded Speaking waveform on the first real playback frame/audio

amplitude. Processing can stay as the folded circular spinner until output is

actually audible.

- **Voice command layer** — reserve local commands that bypass normal agent

routing: "pause", "resume", "stop talking", "cancel", "repeat that", "open

overlay", "return to Hermes", and "new chat". These should work while the

agent is thinking, speaking, or using tools.

- **Spoken tool progress** — when Hermes uses tools, voice mode should speak

short status updates such as "I'm checking the relay logs" or "I found an

error" without waiting for final assistant text. Long tool calls should emit

periodic, low-noise progress updates.

- **Realtime tool timeline parity** — the voice overlay should render the same

live thinking blocks, streaming assistant text, and tool call progress as the

normal chat surface without requiring exit/reload.

- **Hands-free confirmation flow** — risky actions need first-class spoken and

visual confirmation: "yes", "no", "cancel", "confirm", plus a visible and

audible countdown for destructive actions.

- **Voice session memory/status** — add a compact "where are we?" summary for

the current voice task: active objective, last tool result, pending next step,

and whether the agent is waiting on the user.

- **Mode presets** — add presets such as Hands-free, Low latency, Careful tool

mode, and Quiet/visual-only. Hands-free should favor Continuous listening,

spoken tool progress, confirmations, and overlay availability.

- **Barge-in hardening** — keep barge-in experimental until echo/self-recording

is solved. The target path is proper AEC, playback-ducking, and a rule that

output audio can never become a user turn.

- **Audio quality guardrails** — normalize output volume across realtime and

fallback TTS providers, keep pronunciation hints/profile voice tuning, and

measure provider-specific delay, chunk gaps, and tail clipping.

- **Pluggable Realtime Agent media transports** — add an OpenAI-first WebRTC

transport option for Realtime Agent so mobile audio can use provider-native

jitter buffering, interruption, and media handling instead of only relay

WebSocket PCM. Design this as a provider transport interface

(`websocket`, `webrtc`, future `livekit`/SIP-style bridges) so other

realtime providers can opt in without forking the Hermes broker/tool

contract. Hermes must still own tools, memory, confirmations, current data,

and durable transcript state.

- **Voice engine selector** — implemented as an opt-in experimental Realtime

Agent engine in `docs/plans/2026-05-19-realtime-hermes-voice-agent.md`.

Follow-up work is provider-native turn-taking, richer confirmation handling,

and quality/latency evaluation before promotion beyond Experimental.

- **Realtime-native Hermes bridge prototype** — first relay-brokered slice

implemented in `docs/plans/2026-05-19-realtime-hermes-voice-agent.md`.

Remaining work: let OpenAI/xAI realtime sessions own more of the live speech

turn while still proxying every tool, confirmation, memory, and Android bridge

action through Hermes/relay safety.

---

## Research / open questions

### Proper Hermes plugin / skill / tool distribution

**Status:** open question, no plan yet.

We currently distribute Hermes-Relay via a one-shot `install.sh` that clones the repo, `pip install -e`s the package into the user's hermes-agent venv, and registers `skills/` via the `external_dirs` config knob. This works but it's a custom protocol — every project that wants to ship a Hermes plugin reinvents it.

Things to look into:

- **Does upstream hermes-agent have or plan a canonical plugin registry / package format?** If yes, we should migrate to it. If no, we may want to propose one upstream so third-party plugins (ours and others) get a standard install path.
- **Skill distribution as separate from plugin distribution** — right now skills ride along with the plugin install via `external_dirs`. Should skills be installable independently (e.g. `hermes skill install <git-url>`)? Would that fragment maintenance or improve reuse?
- **Tool registration discoverability** — `android_*` tools register at gateway import time. There's no canonical "list installed plugin tools" API. Would adding one to upstream make sense, or is `gateway tool list` already enough?
- **Versioning + compatibility ranges** — `pip install -e` doesn't enforce version pins between hermes-agent and our plugin. A breaking change in upstream's plugin loader could silently break us. Do we need a `hermes_compat: ">=0.8.0,<1.0.0"` field somewhere?
- **Update discovery (shipped 2026-06-30 — CLI + dashboard + app).** `hermes relay update-check`, a dashboard "Plugin version" card, and an app **About → "Relay"** row all compare the installed plugin against the latest `plugin-v*` release and surface the right update command (`hermes plugins update hermes-relay` vs `hermes-relay-update`). The app polls the relay's `GET /relay/update-check` (`:8767`, bearer) on each `auth.ok`; the relay is the single source of truth (the app never hits GitHub). Possible polish (deferred): a more prominent dismissible "relay is behind" banner outside About (today it's capability-first + the About row), and showing the app's own version alongside the relay's in the same readout (the app-Version row already exists separately just above it).
- **Per-profile enablement (shipped 2026-06-30).** `hermes relay profiles list|enable [--all|NAME]` + `plugin/profiles.py` resolve the install-once/enable-per-profile papercut; docs now cover the pair-once/one-relay model. Possible follow-up: an `install.sh` / `hermes plugins install` prompt offering "enable for all existing profiles" so new installs don't need the manual `profiles enable --all`.
- `**hermes-relay-self-setup` SKILL.md as a precedent** — we just shipped a self-installing skill that an LLM can fetch from a raw GitHub URL and execute. Does this pattern generalize? Could it become a recommended way for any third-party Hermes project to ship setup automation?
- **Bootstrap injection** — `hermes_relay_bootstrap/` monkey-patches `aiohttp.web.Application` to inject endpoints into vanilla/partial upstream. This is intentional but feels like a hack. The original broad PR #8556 was **closed as superseded**; native upstream now covers sessions/chat/fork via [#33134](https://github.com/NousResearch/hermes-agent/pull/33134) and skill/toolset discovery via `/v1/skills` + `/v1/toolsets` (#33016). The bootstrap therefore shrinks **per surface** as the remaining compatibility routes (config, memory, legacy `/api/skills` detail, available-models, search) gain native replacements — not one big delete. Track upstream per surface.
- **Gateway slash-command preprocessor — upstream Stage 1 PR.** Sibling follow-up to the native session-control baseline (#33134). Intercepts known gateway commands on `/v1/runs` + `/v1/chat/completions`, dispatches the stateless ones (`/help`, `/commands`) via `gateway_help_lines()`, returns a deterministic "use a channel with session state" notice for the stateful majority. Currently being prepared in `C:/Users/Bailey/Desktop/Open-Projects/hermes-agent-pr-prep/` on branch `feat/api-server-gateway-commands`; awaiting subagent's code + draft PR body before pushing. See `docs/upstream-contributions.md` §5.
- **Gateway slash-command preprocessor — bootstrap middleware (Stage 1 equivalent).** Sibling shim in `hermes_relay_bootstrap/_command_middleware.py` that mirrors the upstream Stage 1 PR as an aiohttp middleware injected at bootstrap time. Ships the hallucination fix to vanilla-upstream installs before the upstream PR lands. Planned for v0.4.1, after the current bridge feature branch wraps. See `ROADMAP.md` v0.4.1 entry.
- **Stage 2 — stateful slash-command dispatch on `/api/sessions/{id}/chat/stream`.** Unblocked now that session primitives shipped upstream (#33134 / `f7527b0`). Add a preprocessor scoped to the session chat stream endpoint only, using `session_id` as the persistence handle. Separate upstream PR + matching bootstrap middleware. See `docs/upstream-contributions.md` §5 ("Stage 2").

When the answer becomes clearer, this section becomes either an ADR in `docs/decisions.md` or a Plan under `Plans/`.

---

## Smaller deferred items

- **MediaProjection consent flow** — wired in MainActivity (2026-04-12), needs end-to-end test on a real device
- **WorkManager upgrade for auto-disable timer** — currently a coroutine `Job + delay()` in `AutoDisableWorker.kt`; documented at top of file. Upgrade when androidx.work joins the classpath
- **Wave 3 voice-bridge multi-turn confirmation** — currently a 5s TTS countdown with cancel; conversational confirmation is the follow-up
- **LLM client wiring for `android_navigate`** — `_default_vision_model` is stubbed; production swap to a real Anthropic/OpenAI vision client
- **Real screenshots of each flavor's a11y permission dialog** — for `user-docs/guide/release-tracks.md`
- `**llms.txt` standard** — explicitly skipped in favor of the `hermes-relay-self-setup` SKILL.md path; revisit if the standard gains traction in the agent ecosystem
- `**markdown-renderer`/`lifecycle` compileSdk ceiling — RESOLVED via compileSdk 37 (2026-06-22).** `MarkdownContent.kt` is on the 0.4x API, and `markdown-renderer 0.42.0` / `lifecycle 2.11.0` (the Dependabot bumps) require `compileSdk 37`. The project moved to **compileSdk 37** (`206d182`, across app/quest/relay-core/relay-ui; `targetSdk` stays 35), which satisfies them — so the temporary 1.2.2-prep pins (0.41.0 / 2.10.0 on compileSdk 36) were dropped when integrating `origin/dev`. Docs/refs reconciled to 37 (2026-06-23): CLAUDE.md, `docs/spec.md`, and the `android.suppressUnsupportedCompileSdk` flags in `gradle.properties` + `quest/gradle.properties`. A Dependabot ignore rule is still worth adding so a future bump that raises the compileSdk floor again fails loudly rather than silently (see next item).
- **Dependabot auto-merge guardrails** — Dependabot merged breaking bumps despite CI failing. Investigate why `.github/workflows/dependabot-auto-merge.yml` isn't gating on CI status, and consider adding an ignore rule for packages we know need manual attention on major bumps (`markdown-renderer`, compose BOM, activity-compose).

---

## Crash reporting + foldable hardening (shipped 2026-06-20)

Triggered by a Play Store review: app "keeps crashing" during setup on a Samsung Galaxy Z Fold7 (Android 16 / SDK 36, version code 13). Shipped: in-app crash capture (`util/CrashReporter.kt` — uncaught handler that persists a report then re-raises so Play vitals still collects; `ui/components/CrashReportDialog.kt` — show-once dialog with Copy + pre-filled GitHub-issue "Report"); QR camera-init hardening (`QrPairingScanner.kt` — try/catch around `ProcessCameraProvider.get()` and `InputImage.fromMediaImage()`, graceful `CameraUnavailableCard` → manual pairing instead of force-close).

Follow-ups:

- **Confirm the actual crash from Play vitals.** Pull the top crash cluster for Galaxy Z Fold7 / version code 13 (Quality → Android vitals → Crashes &amp; ANRs) to verify the camera path is the real cause vs. another setup-path throw. The hardening is correct regardless, but the trace closes the loop.
- **Portrait lock is moot on large screens under SDK 36.** `android:screenOrientation="portrait"` is largely ignored by Android 16's mandatory large-screen orientation override on foldables/tablets. Decide whether to keep the lock (it still applies on phones) or make it conditional; either way it does not *cause* the crash.
- **Foldable camera lifecycle races (from the 2026-06-20 audit, not yet fixed).** `QrPairingScanner` can still hit bind/unbind races on rapid fold/unfold recomposition (the `DisposableEffect` `unbindAll()` vs. an in-flight `addListener` bind), and `mapBoxToViewport` runs on possibly-stale `viewportSizePx` during a fold transition. Not crash-fatal after the try/catch hardening (logged + skipped), but worth a fold-aware guard if foldable adoption grows.
- **Optional: surface crash history in Settings.** The reporter keeps only the most recent crash (`files/crash/last-crash.json`, consumed on view). If repeat-crash diagnosis becomes common, keep a small ring of recent reports + a Settings entry to view/copy them.

---

## Relay enhancement layer + agent-context injection (shipped 2026-06-20 — `docs/plans/2026-06-20-relay-enhancement-layer.md`)

Shipped: `plugin/enhancements/` (registry + fail-open `context_injection` wrap of `AIAgent._build_system_prompt`), the `media-sensitivity` block, `GET /context/injected` audit route, dashboard toggles, client sensitivity re-thread + "Relay context (server-side)" audit section, and the transport-path UI (`ChatTransportStatusBadge` / `RelayStatusStrip` + tier ladder). OFF by default, removable, vanilla-safe.

Follow-ups:

- **Confirm the `AIAgent` seam on the live host before relying on it.** `context_injection._resolve_ai_agent_class()` tries `agent.system_prompt` / `run_agent`. When you flip `RELAY_AGENT_CONTEXT_ENABLED=1`, verify `GET /context/injected` shows the block AND that it actually lands in the prompt (the wrap is fail-open, so a wrong module = inert, not broken). If the class lives elsewhere, widen the module list.
- **Retire the monkey-patch when upstream adds a plugin context hook.** Drop `context_injection` (and migrate to the native hook) the moment hermes-agent ships a first-class system-prompt contributor — same as we retire bootstrap routes for native upstream routes.
- **Incremental bootstrap migration.** Fold the existing `hermes_relay_bootstrap` route-patches into `plugin/enhancements/` per-surface (startup phase) so patching is one surface; don't big-bang the working compat.
- **Structured media channel** — `docs/plans/2026-06-20-structured-media-channel.md` (design only). Replace fragile `MEDIA:`/markdown text markers with a structured channel carrying `sensitive` natively; lead with a relay `relay_send_media(path, sensitive, …)` tool.
- **Gateway voice-ephemeral via the same slot.** The enhancement layer's server-side injection can carry per-turn voice instructions on the gateway (which has no ephemeral `system_message`), letting voice stay on the gateway instead of being forced to SSE. Wire when the voice path is revisited.

---

## Attachments (shipped 2026-06-18 — `docs/plans/2026-06-18-attachment-experience.md`)

- **B3 — download progress + cancel.** Inbound fetch is un-cancelable; the previews work scaffolded an indeterminate bar + nullable `onCancel`. Live wiring needs the fetch-path owner (`ChatViewModel`/`Attachment`) to expose determinate progress (Content-Length) + a cancel hook.
- **A6 — multi-image gallery.** N images in one message → grid + swipe-across viewer (Telegram media-group parity).
- **C5 — agent-side sensitivity config gate.** `RELAY_MEDIA_SENSITIVITY_HINTS` (env or per-profile) instructing the agent to annotate sensitive media via the prompt-builder. Transport (relay `X-Media-Sensitive` header + client blur) already ships; the agent isn't asked to set the bit yet.
- **Relay thumbnails (D6).** Server-side thumbnail generation to avoid full-size download for cards/galleries. Needs an image lib (Pillow not currently a dep) — evaluate before adding.
- **D5 — outbound upload progress.** No per-attachment progress during the 60s gateway PDF-render window.

## Voice overhaul (shipped 2026-06-18 — `docs/plans/2026-06-18-voice-overhaul.md`)

- **Per-profile voice on Standard (upstream PR).** Upstream `/api/profiles/*` has no voice field and `/api/audio/*` is host-global. Long-term: PR a voice section to the profile config + make `/api/audio/*` honor the active/`?profile=` profile. The relay path already carries per-profile voice; ship that first.
- ~~**Wire connectionId for per-profile voice namespacing.**~~ **Already shipped — stale entry (verified 2026-07-08).** The wiring landed in `0aa1b38` (2026-06-21, the same batch this list belongs to): `RelayApp` has a `LaunchedEffect(activeConnectionId, selectedProfile?.name)` calling `voiceViewModel.setVoicePrefsConnection(activeConnectionId)` *before* `onProfileChanged(...)`, and `applyVoicePrefsScope` pushes `(connectionId, profile)` into `VoicePreferencesRepository.setActiveScope`. Two connections with same-named profiles namespace separately.
- **Realtime-PCM waveform output gating.** The basic-TTS output waveform is now Visualizer-accurate (gated on real playback amplitude), but the realtime path gates `outputAudioActive` on `audioSeen` (first decoded PCM bytes) in `VoiceViewModel.handleRealtimeVoiceEvent`, which can still lead audible output by the `RealtimePcmPlayer` start prebuffer. Gate realtime on actual playback-start (head moved) to match the basic-TTS path.

## Chat clean-mode + pets (shipped 2026-06-18 — `docs/plans/2026-06-18-chat-clean-mode-and-pets.md`)

- **Part-A chat polish (optional bundle).** Per-code-block copy + horizontal scroll, visible copy affordance, mid-stream stall feedback, profile/skill-aware empty-state chips, the ~40-flow recomposition hotspot at the top of `ChatScreen`. (Sphere `contentDescription`/reduced-motion was handled by the clean-mode a11y work.)
- **Pet hot-load + in-app add/remove (shipped 2026-06-20).** Pets now live-refresh: an `avatarsRefreshTick` keys the avatar `produceState` in `RelayApp`, and Appearance re-scans `pets/` on open and after in-app import/delete — no app restart. Appearance gained "Add a pet" (SAF `.zip` import via `PetImporter`, zip-slip/zip-bomb guarded + validated through `toAvatar`) and an "Installed pets" list with per-pet remove (`PetLoader.deletePet`, confirm dialog, Sphere fallback). Remaining:
  - **Sphere-skin parity.** Skins are still process-scoped + `adb push` only — the live tick and the importer cover pets, not skins. Extend the tick to `loadUserSkins` and add a `.json` skin import if hot-loading/adding skins in-app is wanted.
  - `**adb push` into `Android/data` hangs on Samsung scoped storage.** Confirmed: pushing a pet pack to `/sdcard/Android/data/<pkg>/files/pets/` stalls (no bytes written) although `adb shell ls` of the dir works. In-app `.zip` import is the supported path; `/sdcard/Download` pushes fine. Consider softening `docs/pet-spec.md` + user-docs to lead with in-app import over adb.
  - **On-device import/delete smoke.** Import `/sdcard/Download/lucy.zip` via Add a pet → confirm Lucy appears, selects, and animates all states; then remove it and confirm the avatar falls back to the Sphere.
- **Pet state-change re-decode can flash one blank frame.** When the agent state switches clips, the first frame of the new clip may briefly be blank during decode; prewarm/hold-last-frame to smooth it. Root cause is the same as the next item: `PetAvatar.Render` re-decodes from disk on every clip change.
- **Pet frame-sequence memory: no cap or downsample (audit 2026-06-19).** `decodeClip` decodes every frame of the selected clip into `List<ImageBitmap>` at full resolution with no `inSampleSize` downscale to the display size and no frame-count/dimension ceiling — a long sequence of large PNGs can use a lot of RAM and a single very large image can OOM `BitmapFactory`. Add `inSampleSize` downsampling to the avatar's draw size and/or a documented hard cap. Spec now warns authors (prefer sprite sheets), but the renderer doesn't enforce it.
- **Pet decoded-clip cache (audit 2026-06-19).** `PetAvatar.Render` keys `produceState` on `clip`, so idle→thinking→speaking→idle within one turn re-runs `BitmapFactory.decodeFile` from disk each transition (repeated I/O + GC churn, and the blank-frame flash above). Add a small per-avatar `Map<SphereState, PetFrames>` decode cache.
- **Pet behavior model — richer state association (spec'd 2026-06-19, `docs/pet-spec.md` "Agent states &amp; pet behavior").** Shipped: the honesty clamp (declared reactivity ∩ `PET_RENDERER_CAPABILITIES`), the friendly `writing` alias, the `**working`/tool-use overlay** (pet-local sub-state from `toolCallBurst`; opt-in `working` clip drives both the swap and the Tools badge), the **one-shot reaction layer** (`greet`/`wake` on appear, `done`/`celebrate` on turn-finish — opt-in, play-once-then-revert, transition-derived; `ONE_SHOT_MAX_MS` backstop), and `**intensity` modulation** (opt-in `reactive.intensity` → live playback speedup ≤1.6× via `rememberUpdatedState`; un-clamps the Activity badge). Voice · Tools · Activity reactivity is now complete. Remaining:
  - `**attention` one-shot (only deferred behavior).** A reaction on notification arrival — needs a host event the avatar doesn't yet receive (unlike `greet`/`done`, which ride state transitions). Would plumb a notification edge into `AvatarRenderState` (or a side channel) + a `PetOneShot.Attention`. Low priority: the avatar is rarely on-screen when notifications land (backgrounded) — see the value analysis; revisit only if the avatar becomes an always-on surface (persistent overlay / Quest port).
  - **On-device verification (working + one-shots + intensity).** Best seen in clean mode (`AgentTextFlow` feeds `toolCallBurst` + `streamingIntensity` + state transitions). Confirm: a `working` clip swaps in during a tool run and releases ~600ms after (`WORKING_BURST_THRESHOLD` 0.5); a `done` clip plays once on reply completion then returns to idle; a `greet` clip plays once when the avatar appears; with `intensity:true`, a writing/working loop visibly quickens while streaming. Watch for the known clip re-decode flash on each swap (separate TODO — decoded-clip cache).
- **Undecodable-but-present image appears valid (audit 2026-06-19).** A file that exists but isn't a decodable image passes the loader's `isFile` check, so the pet shows in the picker but renders blank. Documented as a caveat; consider a cheap header sniff at load time if false-valid pets become a support issue.

