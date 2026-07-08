# Plan: Realtime Agent background Hermes runs (foreground -> promote -> durable)

> **Purpose.** Stop the Realtime Agent's provider event pump from blocking on
> the full Hermes run. Keep short turns synchronous and low-latency, but let
> genuinely long Hermes runs detach to a tracked background task so the provider
> session stays responsive and the run can be monitored, summarized on
> completion, and cancelled.
>
> **Decision of record.** ADR 33 (`docs/decisions.md`). This plan is the
> phased, file-level execution of that ADR. Read ADR 33 first — it defines the
> three tiers, the relay-as-single-floor-owner rule, the settings surface, and
> the Phase 0 prerequisite spike.
>
> **Non-negotiable.** Hermes remains the only authority for tools, memory,
> current data, research, side effects, durable context, and confirmations
> (ADR 29/32). This plan changes *when and how* a Hermes run is awaited and
> spoken — never whether Hermes governs it.

## Current State (grounded in code)

The realtime-agent broker already runs a persistent provider event pump; the
gap is that tool execution blocks it.

- `plugin/relay/realtime_agent/broker.py`
  - `_pump_provider_events` (~`broker.py:1124`) is the long-lived
    `native_provider_task` — the persistent loop. It survives WS detach/resume.
  - `FUNCTION_CALL_COMPLETED` -> `_handle_provider_tool_call` (~`broker.py:1628`)
    -> `_run_brokered_tool` (~`broker.py:1930`, `return await task`) ->
    `_execute_brokered_tool` (~`broker.py:1956`) `async for`s the entire Hermes
    SSE stream before the pump consumes the next provider event. **This is the
    blocking await to remove for long runs.**
  - `_send_hermes_run_progress` (~`broker.py:2245`) already runs concurrently as
    `progress_task`, emitting `hermes.run.progress` every
    `_HERMES_PROGRESS_INTERVAL_SECONDS` and spoken filler after
    `_HERMES_SPOKEN_PROGRESS_AFTER_SECONDS`. Reuse this for background progress.
  - `_cancel_active_hermes` (~`broker.py:2295`) already cancels `hermes_task`
    cross-coroutine. Promotion must keep this working.
  - `RealtimeAgentSession` (~`broker.py:114`) holds `hermes_task`,
    `hermes_run_status`, and the `native_forced_*` suppression flags. Floor state
    will live here.
  - `_render_provider_audio` (~`broker.py:2509`) is the relay TTS render mouth
    (forced-summary fallback). It must obey the floor owner.
  - `_request_playback_drain` / `_send_provider_tool_result` /
    `_request_provider_response` (~`broker.py:1700`-`1810`) are the existing
    result-injection primitives the background-completion path will reuse.
- `plugin/relay/realtime_agent/hermes_tool_broker.py`
  - `_TOOL_SURFACE = ("hermes_run_task", "hermes_get_status", "hermes_cancel",
    "hermes_confirm")` (~`:189`) — background vocabulary already advertised.
  - `stream_task` yields the run; unchanged by this plan.
- `plugin/relay/realtime_agent/models.py` — `SERVER_EVT_*`, `CLIENT_MSG_*`,
  `HERMES_TOOL_SCHEMAS`. New events/fields added here.
- Providers (`providers/xai.py`, `providers/openai.py`) — both run
  `turn_detection: None` (relay owns `response.create`) and emit audio. This is
  what makes "withhold the summary until the floor is idle" possible.
- Settings: `plugin/relay/profile_voice.py` (`realtime_voice_settings`,
  `save_profile_voice_section`) + `plugin/relay/config.py`.
- Android: `viewmodel/VoiceViewModel.kt`, `network/RelayVoiceClient.kt`,
  `data/VoicePreferences.kt`, `data/RealtimeConversationContext.kt`,
  `voice/RealtimeTurnSyncBuilder.kt`, `ui/screens/VoiceSettingsScreen.kt`.
- Tests: `plugin/tests/test_realtime_agent_routes.py`,
  `test_realtime_agent_xai_provider.py`, `test_realtime_agent_openai_provider.py`.

### Three audio sources ("who speaks")

All three converge on Android's single `AudioTrack` via `voice.output_audio.delta`:

1. Realtime provider (xAI/OpenAI) — primary.
2. Relay TTS render (`_render_provider_audio`) — fallback; same wire event.
3. Android local TTS — `should_speak` filler on `hermes.run.progress`.

Today the blocking await serializes them. This plan replaces that implicit mutex
with an **explicit relay-side floor owner**.

## Target Architecture

```text
provider event pump (never blocks on a long run)
  -> short run: await inline (Tier A, unchanged ADR 32 shape)
  -> long run: detach to tracked task, resume pump, speak handoff (Tier B)
  -> explicit durable: return handle immediately (Tier C)
                         |
                         v
        FloorOwner(idle | provider_speaking | hermes_filler | result_pending)
                         |
   background run completes -> queue result_pending -> speak when floor idle
```

**FloorOwner contract (relay-side, per session):**
- Exactly one mouth holds the floor; provider holds it by default.
- A completed background result is queued `result_pending`, never barges in. It
  is spoken (provider summary, or relay-TTS fallback) only on transition to
  `idle`.
- Android `should_speak` filler is suppressed while `provider_speaking`.
- `_render_provider_audio` may only fire when it owns the floor and the provider
  has drained.

## Non-Goals

- Do not change Tier A latency or the ADR 32 preamble -> forced-Hermes ->
  provider-summary shape for short turns.
- Do not give the provider authority over tools/memory/confirmations.
- Do not support `max_background_runs > 1` in this plan (single `hermes_task`).
- Do not require adb/device testing for verification unless Bailey asks. JVM/py
  unit tests plus the lab smoke gate cover acceptance.
- Do not depend on Hermes upstream changes; the broker already owns the loop.

## Protocol Additions (additive only)

Relay -> client (add to `models.py`):
- `hermes.run.promoted` — `{run_id, promote_after_ms, spoken_handoff, tier}`.
- `hermes.run.background_completed` — `{run_id, ok, tool_count}` (precedes summary).
- Extend `hermes.run.progress` with `tier` (`foreground|promoted|durable`) and
  `floor` (`idle|provider_speaking|hermes_filler|result_pending`).

No new client->relay messages required — `response.cancel`, `hermes_cancel`, and
`hermes_get_status` already exist; the latter two become reachable mid-run once
the pump no longer blocks.

## Settings Surface (per ADR 33)

Relay-side `realtime_voice` config (source of truth, per-profile override via
`profile_voice.py`), mirrored into Android `VoicePreferences`:

| Key | Default | Meaning |
|---|---|---|
| `promotion_enabled` | `true` (Phase 3) | Master switch; `false` pins Tier A blocking |
| `promote_after_ms` | `6000` | Grace window before Tier B handoff |
| `background_default_mode` | `promote` | Ambiguous long turn: `promote` vs `foreground` |
| `spoken_handoff` | `true` | Speak "I've started that" on promotion |
| `progress_spoken_after_ms` | `15000` | Reuse `_HERMES_SPOKEN_PROGRESS_AFTER_SECONDS` |
| `progress_repeat_ms` | `30000` | Reuse `_HERMES_SPOKEN_PROGRESS_REPEAT_SECONDS` |
| `result_delivery` | `speak_verbatim` | vs `speak_when_idle` / `notify_then_speak` / `visual_only` |
| `max_background_runs` | `1` | Fixed at 1 this plan |

---

## Phase 0 — Provider idle-tolerance spike (GATES default-on)

**Goal.** Determine empirically whether xAI and OpenAI realtime sessions tolerate
being held open and quiescent (no `response.create`, no input audio) for
30–120s, since Tier B holds the floor while a background run completes.

**Tasks.**
1. Add a throwaway script under `plugin/tools/` or a `scripts/` probe (not
   shipped) that opens each provider realtime socket via the existing adapters,
   sends `session.update`, then idles 30/60/120s and logs: socket survival, any
   server-side timeout/close codes, VAD/turn artifacts on the first post-idle
   `response.create`, and any keep-alive requirement.
2. Record findings in `docs/realtime-voice-poc.md` under a new "Idle tolerance"
   section, per provider.

**Acceptance.**
- A documented per-provider verdict: `hold-floor-ok` | `needs-keepalive` |
  `must-reopen`. This verdict selects the Tier B fallback strategy per provider.

**Note.** If a provider is `must-reopen`, Tier B for that provider detaches the
run but closes/reopens the provider socket (or keeps a minimal keep-alive)
rather than holding it conversational. Capture that in the ADR's Phase 0 line.

**Status: DONE (2026-05-24).** Verdicts recorded in
`docs/realtime-voice-poc.md` → Idle tolerance:
- **OpenAI `gpt-realtime-2` — `hold-floor-ok` (empirical).** Probe ran live
  (10s/20s/30s idle windows survived; clean post-idle audio each time).
- **xAI `grok-voice-latest` — `hold-floor-ok`.** No xAI creds on the dev box, but
  the verdict is not conditional: the shipping Realtime Agent already holds
  `xai_realtime` sessions open across between-turn idle gaps
  (`turn_detection: None` + resume TTL) with no idle-close reports. A relay-host
  probe run is retained as a regression check, not a precondition.

**Premise superseded.** Phase 2/3 implemented Tier B by *closing the pending
provider call with an interim ack* rather than holding an open response, so the
"hold the floor conversational while a run completes" worst case this spike
guarded against does not occur — the socket only sees the normal between-turns
idle gap. Both providers were `hold-floor-ok` for the short-window Phase 0 gate,
so default-on was satisfied. Later 2026-07-08 xAI probes revised the long-idle
behavior to `must-reopen` after the provider's 900s conversation-inactivity
expiry.

---

## Phase 1 — Introduce FloorOwner under current blocking behavior (no functional change)

**Goal.** Add the explicit floor state machine and route all three mouths through
it, while preserving today's exact audible behavior. This is the de-risking step.

**Tasks.**
1. Add `RealtimeFloor` (new dataclass/enum) to `broker.py` or a new
   `realtime_agent/floor.py`: state `idle|provider_speaking|hermes_filler|
   result_pending`, with `acquire(mouth)`, `release(mouth)`, and
   `can_speak(mouth) -> bool`. Pure, unit-testable, no I/O.
2. Hold a `floor: RealtimeFloor` on `RealtimeAgentSession`.
3. Gate the three emit paths through `floor.can_speak(...)`:
   - provider audio in `_pump_provider_events` (`AUDIO_DELTA` -> `provider`),
   - `_render_provider_audio` (-> `relay_tts`),
   - `should_speak` progress events (-> `android_filler`; suppress while
     `provider_speaking`).
4. Transition floor on `RESPONSE_STARTED`/`AUDIO_DONE`/`RESPONSE_DONE` and on
   playback-drain completion.
5. Stamp `floor` onto `hermes.run.progress` (additive field).

**Acceptance.**
- All existing `plugin/tests/test_realtime_agent_*` pass unchanged.
- New `test_realtime_floor.py` proves single-mouth invariants:
  background-result-never-barges, filler-suppressed-while-provider-speaks,
  relay-TTS-only-when-owned.
- Manually/log-verified: a short turn sounds identical to pre-change.

**Test gate.** `python -m unittest plugin.tests.test_realtime_floor` +
existing realtime agent tests green.

---

## Phase 2 — Tier B grace-period promotion (default OFF)

**Goal.** Detach a long Hermes run from the pump after `promote_after_ms`; resume
the pump; speak a handoff; deliver the result on completion via the floor owner.

**Tasks.**
1. `models.py`: add `hermes.run.promoted`, `hermes.run.background_completed`,
   `tier` field; add settings keys to the config schema.
2. `profile_voice.py` / `config.py`: read/write the new `realtime_voice` keys
   with defaults above (`promotion_enabled=false` this phase).
3. `broker.py` — split `_run_brokered_tool` for `hermes_run_task`:
   - Start the run task as today, but `await asyncio.wait({task}, timeout=
     promote_after_ms)`.
   - If done in time: Tier A path, unchanged.
   - If not: emit `hermes.run.promoted`, optionally speak handoff (gated by
     `spoken_handoff` + floor), set `tier="promoted"`, **return control to the
     pump** without awaiting the task. Keep `session.hermes_task` set.
4. Add a pump-side drain point: between provider events (and on a small timer),
   check for a finished background task; when finished, emit
   `hermes.run.background_completed`, then inject via the existing
   `_send_provider_tool_result` -> `_request_provider_response` path **only when
   `floor` is `idle`** (else mark `result_pending` and inject on next `idle`).
5. Preserve cancellation: `hermes_cancel` (now reachable) and `response.cancel`
   both route to `_cancel_active_hermes`.
6. Resume safety: if WS detaches mid-background-run, the completion event must
   replay through the existing event-ring/resume path on reattach.

**Acceptance.**
- With `promotion_enabled=true` in test config, a run that exceeds
  `promote_after_ms` emits `hermes.run.promoted`, the pump processes a subsequent
  provider event before the run finishes (proves non-blocking), and the result is
  spoken exactly once after completion.
- A run under the window behaves as Tier A (no `promoted` event).
- Cancel during a promoted run stops it and emits `hermes.run.cancelled`.
- Detach+resume during a promoted run replays `background_completed`.

**Test gate.** New `test_realtime_promotion.py` (fake provider connection + fake
Hermes broker with controllable latency) covering: under-window, over-window,
cancel-promoted, detach-resume-completes, result-waits-for-idle-floor.

---

## Phase 3 — Default-on + Tier C durable + Android settings UI

**Goal.** Flip `promotion_enabled` default to `true` (gated on Phase 0 verdict),
add explicit `mode="background"`, and expose settings on Android.

**Tasks.**
1. `models.py` `HERMES_TOOL_SCHEMAS`: document/validate `hermes_run_task.mode`
   (`run|background`); `background` returns a handle immediately (skip grace
   window, go straight to Tier B detach).
2. Default `promotion_enabled=true`; per-provider Tier B strategy from Phase 0.
3. Android `VoicePreferences.kt` + `RelayVoiceClient.kt`: read/write the new
   settings; surface in `VoiceSettingsScreen.kt` under Realtime Agent (promotion
   toggle, grace slider, handoff toggle, result-delivery picker).
4. Android `VoiceViewModel.kt` + `RealtimeTurnSyncBuilder.kt`: handle
   `hermes.run.promoted` / `hermes.run.background_completed`; render a persistent
   "working on: …" chip while `tier=promoted/durable`; implement
   `notify_then_speak` affordance (chime + tap-to-hear) if that mode is selected.
5. Docs: `docs/relay-protocol.md` (new events/fields), `user-docs/features/
   voice.md` (settings + behavior), `CHANGELOG.md` `[Unreleased]`.

**Acceptance.**
- Default config promotes long runs without user action; short runs unaffected.
- `hermes_run_task(mode="background")` returns immediately and completes via the
  same path.
- Android shows promoted state and speaks the result per `result_delivery`.
- `./gradlew lint` clean; py tests green; lab smoke
  (`scripts/realtime-voice-lab-smoke.ps1`) still under threshold for short turns.

**Test gate.** Full `plugin.tests.test_realtime_agent_*` + new floor/promotion
suites; Android unit tests for the new `VoicePreferences`/sync mapping;
`./gradlew lint`.

---

## Risks & Mitigations

- **Floor logic stacking on `native_forced_*` suppression.** The forced-summary
  state machine is the most fragile code in `broker.py`. Mitigation: Phase 1
  introduces FloorOwner *as the serializer*; migrate suppression decisions to ask
  the floor rather than adding parallel flags.
- **Double-speak (provider + relay TTS).** Mitigation: both gated by
  `floor.can_speak`; result injection only on `idle`.
- **Provider idle close.** Mitigation: Phase 0 verdict picks per-provider Tier B
  strategy; `must-reopen` providers don't hold the floor.
- **Stranded background result on disconnect.** Mitigation: reuse event-ring +
  resume replay; covered by Phase 2 acceptance.

## Test Strategy Summary

- Pure unit: `RealtimeFloor` invariants (Phase 1).
- Broker integration with fakes: promotion timing, cancel, resume, idle-gated
  delivery (Phase 2).
- Android: settings round-trip + event handling (Phase 3).
- Regression: existing realtime agent suites unchanged throughout; lab smoke for
  short-turn latency.

## Key Files

- `docs/decisions.md` (ADR 33)
- `plugin/relay/realtime_agent/broker.py`
- `plugin/relay/realtime_agent/floor.py` (new)
- `plugin/relay/realtime_agent/hermes_tool_broker.py`
- `plugin/relay/realtime_agent/models.py`
- `plugin/relay/realtime_agent/providers/xai.py`
- `plugin/relay/realtime_agent/providers/openai.py`
- `plugin/relay/profile_voice.py`
- `plugin/relay/config.py`
- `plugin/tests/test_realtime_floor.py` (new)
- `plugin/tests/test_realtime_promotion.py` (new)
- `docs/relay-protocol.md`
- `docs/realtime-voice-poc.md` (Phase 0 findings)
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/VoiceViewModel.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/voice/RealtimeTurnSyncBuilder.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/data/VoicePreferences.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/network/RelayVoiceClient.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/VoiceSettingsScreen.kt`
- `user-docs/features/voice.md`
- `CHANGELOG.md`
