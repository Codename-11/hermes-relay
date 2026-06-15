# Plan: Persistent realtime-agent session (one socket across turns)

> **Purpose.** Make Realtime Agent voice a *persistent conversation* instead of a
> new session per utterance. Today each turn calls
> `RelayVoiceClient.runRealtimeAgent()` which `createRealtimeAgentSession()`s a
> fresh relay session + provider socket and closes it on `voice.response.done`.
> The provider therefore has no live memory of prior turns (only relay-seeded
> context snippets), and every turn pays session-setup latency.
>
> **Key finding.** This is a **client-only** change. The relay already supports
> many turns on one socket — `_handle_provider_native_ws` loops over
> `input_audio.append` / `input_audio.commit` / `response.create` and only tears
> the session down when the WebSocket disconnects. `voice.response.done` is a
> *turn* boundary, not a *session* boundary. The client is what closes the socket
> on `voice.response.done` (`RelayVoiceClient.kt:1410`).

## Design

Keep one relay session + provider socket open for the lifetime of a Realtime
Agent voice-mode session; feed each utterance as a new turn on that socket.

### RelayVoiceClient
- Add an opt-in **persistent mode** to `runRealtimeAgent` via two optional params
  (one-shot path is byte-for-byte unchanged when they're null):
  - `turnInputs: ReceiveChannel<RealtimeTurnInput>?` — when non-null, the call is
    a long-lived session: after the first turn it reads further turns off the
    channel and sends their `input_audio.append`+`commit` on the open socket.
  - `onTurnComplete: (RealtimeVoiceSummary) -> Unit` — invoked at each
    `voice.response.done` instead of completing+closing.
- In persistent mode:
  - `voice.response.done` → call `onTurnComplete`, **do not** close the socket or
    complete `finished`.
  - A reader coroutine drains `turnInputs` and sends each turn's PCM chunks.
  - `finished` completes only when the channel closes (voice-mode exit) or on a
    fatal socket/provider error.
  - The per-turn idle/turn-limit guards in `awaitRealtimeAgentCompletion` are
    scoped to an *active* turn only — between-turn idle is expected and must not
    trip `REALTIME_AGENT_IDLE_TIMEOUT_MS`.
- `RealtimeTurnInput(inputPcm, sampleRate, prompt)` data class.

### VoiceViewModel
- Hold a `realtimeLiveSession` (the persistent call's `Job` + the
  `SendChannel<RealtimeTurnInput>`), opened lazily on the first Realtime Agent
  turn and reused for subsequent turns.
- Per utterance: instead of a fresh `runRealtimeAgentTurn`, do the per-turn UI
  setup (state reset, watchdog, `chatVm.startRealtimeAgentTurn`) and
  `realtimeLiveSession.submit(RealtimeTurnInput(...))`.
- Close the session (`channel.close()` + cancel job) on `exitVoiceMode`, engine
  switch away from Realtime Agent, and `onCleared`.
- The continuous event callback (the `when(event.type)` block) is registered once
  for the session and handles every turn's events.

### Fallback flag (risk control)
- `VoicePreferences.realtimePersistentSession` (default **true**). When false,
  fall back to the current one-shot `runRealtimeAgentTurn` path. Lets the user
  flip back on-device without a rebuild if the persistent path misbehaves.

## Non-Goals
- No relay changes (the relay already supports multi-turn sockets).
- No change to the one-shot path used by the Voice Lab / stable engine.
- Not changing barge-in semantics beyond keeping them working per turn.

## Risks / must-validate-on-device
- Feeding new input while a prior turn's audio is still draining (barge-in vs.
  new turn). Mitigation: reuse existing barge-in/cancel before submitting a turn.
- Per-turn UI state resets must not tear down the shared session.
- Between-turn idle must not trip the stall timeout.
- Provider/relay session longevity across long pauses (the socket stays open, so
  no resume-TTL dependency — but confirm providers don't idle-close; see ADR 33
  Phase 0 `hold-floor-ok`).

## Test strategy
- Unit-test the pure pieces: `RealtimeTurnInput` chunking, the persistent-vs-
  one-shot branch decision, idle-guard gating by active-turn.
- On-device (post-merge, by Bailey): multi-turn conversation with follow-up
  references ("what did you just say?"), background promotion mid-conversation,
  barge-in, exit/re-enter voice mode, engine switch.

## Key Files
- `app/src/main/kotlin/com/hermesandroid/relay/network/RelayVoiceClient.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/VoiceViewModel.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/data/VoicePreferences.kt`
- `docs/relay-protocol.md` (note: turn vs. session boundary)
