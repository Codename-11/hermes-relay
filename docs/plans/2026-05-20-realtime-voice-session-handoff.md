# Plan: Realtime Voice Session Handoff

> **Purpose.** Make voice mode resilient when Android changes network transport mid-turn, especially Wi-Fi to cellular or LAN to Tailscale during Realtime Agent speech.
>
> **Goal.** Add resumable voice sessions above the WebSocket transport so Android can detach from an in-flight stable voice-output or Realtime Agent session, reconnect through the newly selected `effectiveRelayUrl`, replay missed state/audio safely, and continue the same Hermes-governed turn without provider-specific or LAN/Tailscale-specific client logic.
>
> **Core decision.** Do not try to migrate a live TCP/WebSocket connection. Treat network handoff as a short client detach from a relay-owned voice session, then resume that session through the same normalized voice broker contract.

## Current State

- Android uses one `RelayVoiceClient` wired to `ConnectionViewModel.effectiveRelayUrl`.
- `effectiveRelayUrl` is already driven by resolver-selected endpoints, so LAN, Tailscale, public, and manual relay routes share one transport selection path.
- Stable voice output and Realtime Agent both use `RelayVoiceClient.resolveHttpBase()` and `resolveWebSocketBase()`.
- `ConnectionManager` registers a `NetworkCallback`, marks failed endpoints unreachable, and re-resolves routes on network changes.
- Voice turns run a relay preflight before starting, so dead routes fail visibly.
- In-flight WebSocket sessions are not resumable. If the socket dies during listening, thinking, tool use, provider audio, or playback, the turn fails and the next user action starts a new turn.

Key files:

- `app/src/main/kotlin/com/hermesandroid/relay/network/RelayVoiceClient.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/VoiceViewModel.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ConnectionViewModel.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/network/ConnectionManager.kt`
- `plugin/relay/realtime_agent/broker.py`
- `plugin/relay/realtime_agent/models.py`
- `plugin/relay/realtime_agent/providers/base.py`
- `plugin/relay/voice_output.py`
- `docs/relay-protocol.md`

## Desired Behavior

If Android changes network transport during a voice turn:

```text
Wi-Fi voice websocket drops
  -> Android marks voice turn reconnecting
  -> ConnectionViewModel re-resolves effectiveRelayUrl
  -> Android reconnects to the same relay voice session with resume token
  -> Relay validates same device/session grant
  -> Relay replays missed transcript/status/audio events after last ack
  -> Android resumes the overlay and playback without starting a new Hermes turn
```

For Realtime Agent:

- The relay keeps the server-side xAI/OpenAI realtime provider socket alive while Android is briefly detached.
- Hermes broker state, pending tool run, pending confirmation, transcript text, and provider response state survive Android transport loss.
- Provider adapters remain transport-neutral. They do not know whether Android came through LAN, Tailscale, public, or cellular.

For stable `Hermes Chat + Voice Output`:

- Voice-output render sessions can detach/resume during provider PCM playback.
- Stable STT upload and normal chat streaming do not need full mid-upload migration in the first implementation; if the network drops before relay receives the utterance, Android should fail clearly and let the user retry.
- If Hermes already produced assistant text and `/voice/output/*` is rendering it, resume should replay missed audio/status events.

## Non-Goals

- Do not preserve the underlying Android WebSocket or TCP connection across network changes.
- Do not add provider-specific Android handoff code.
- Do not expose xAI/OpenAI provider secrets or provider session ids to Android.
- Do not make OpenAI/xAI the authority for Hermes tools, memory, confirmations, or durable transcript state.
- Do not guarantee lossless resume after long offline periods or app process death in the first pass.
- Do not redesign endpoint selection. Reuse `effectiveRelayUrl` and existing endpoint resolver behavior.

## Protocol Additions

### Session Creation

Add resume metadata to voice session creation responses for resumable surfaces:

```json
{
  "session_id": "...",
  "websocket_path": "/voice/realtime-agent/{session_id}",
  "resume_token": "...",
  "resume_supported": true,
  "resume_ttl_ms": 30000
}
```

The resume token is relay-generated, scoped to the voice session, and accepted only with the same authenticated principal or same paired device/session grant.

### Client Events

Add optional fields/events to `/voice/realtime-agent/*` first, then `/voice/output/*`:

- `session.start`
  - `resume_token`
  - `last_event_id`
  - `last_audio_event_id`
  - `last_input_chunk_id`
- `session.resume`
  - same fields as above, explicit resume intent
- `input_audio.append`
  - `chunk_id`
  - `sample_rate`
  - `audio_base64`
- `client.ack`
  - `event_id`
  - `audio_event_id`
  - `played_audio_event_id`
  - `input_chunk_id`
- `playback.drained`
  - keep existing behavior, include latest played audio id when available

### Server Events

Add normalized relay events:

- `voice.session.resumed`
- `voice.session.detached`
- `voice.session.resume_failed`
- `voice.replay.started`
- `voice.replay.done`

Add `event_id` to every server-to-Android event. Add `audio_event_id` to every audio delta.

## Relay Architecture

### 1. Resumable Session State

Add a shared relay-side session state helper for voice websocket surfaces:

- `event_seq`
- `audio_seq`
- `input_chunk_seq`
- `event_ring`
- `audio_ring`
- `acked_event_id_by_client`
- `acked_audio_event_id_by_client`
- `played_audio_event_id_by_client`
- `acked_input_chunk_id_by_client`
- `attached_ws`
- `detached_at`
- `resume_token_hash`
- `resume_deadline`

Start with Realtime Agent in `plugin/relay/realtime_agent/broker.py`. Extract a shared helper only after the Realtime Agent path is working, then apply it to `/voice/output/*`.

### 2. Detach Instead Of Close

When Android websocket closes unexpectedly:

- Mark session `detached`.
- Do not close provider connection immediately.
- Continue receiving provider/Hermes events.
- Buffer bounded event/audio history.
- Keep provider socket alive for `RELAY_VOICE_RESUME_TTL_MS` default 30 seconds.
- If buffer caps are exceeded, degrade deliberately:
  - keep transcript/status/result summaries,
  - keep the newest audio window,
  - emit a clear resume warning,
  - optionally ask provider to summarize after resume.

When Android sends `session.close` intentionally:

- Close provider socket and clean up immediately.

### 3. Resume Validation

On reconnect to an existing session:

- Require bearer auth as today.
- Require matching session id and resume token.
- Require same paired device/session grant when available.
- Reject expired or already-closed sessions.
- Emit `voice.session.resume_failed` with a non-secret reason when rejected.

### 4. Event Replay

On successful resume:

- Attach new websocket.
- Emit `voice.session.resumed`.
- Replay events with `event_id > last_event_id`.
- Replay audio with `audio_event_id > last_audio_event_id` unless Android reports it already played.
- Emit `voice.replay.done`.
- Continue live event streaming.

Audio replay must avoid obvious duplicate speech. Prefer "received/queued" ack for lossless continuity and "played" ack for preventing repeated audible chunks.

### 5. Provider Connection Contract

Extend `RealtimeAgentConnection` only if needed:

- `pause_output()` optional
- `resume_output()` optional
- `close_after_detach_timeout()` optional

Do not add LAN/Tailscale/provider branching. xAI/OpenAI adapters should keep their server-side provider sockets alive while the Android client is detached. The broker owns buffering and replay.

If provider socket dies while detached:

- Mark provider state failed.
- Preserve Hermes state and transcript.
- On resume, surface "Realtime provider disconnected during network change" and offer a clean retry/new turn.

## Android Architecture

### 1. Voice Session Handle

Add a local handle in `RelayVoiceClient` or `VoiceViewModel`:

- `sessionId`
- `resumeToken`
- `websocketPath`
- `lastEventId`
- `lastAudioEventId`
- `lastPlayedAudioEventId`
- `lastInputChunkId`
- current phase: listening/thinking/speaking/waiting_for_confirmation

Keep it in memory only for the first pass.

### 2. Ack Tracking

Android should:

- ack every non-audio event after applying it to UI/chat timeline,
- ack audio events once queued to `RealtimePcmPlayer`,
- ack played audio events when playback progresses or drains,
- ack input chunks after relay confirms receipt.

Keep ack messages small and coalesced to avoid flooding the websocket.

### 3. Reconnect State Machine

In `VoiceViewModel`:

- Watch the Android-driven `effectiveRelayUrl` signal during active resumable
  voice sessions, so `ConnectivityManager`/endpoint resolver changes can
  trigger resume before OkHttp reports a stale websocket failure.
- Detect realtime websocket failure during an active turn.
- Set overlay state to a recoverable reconnecting state instead of terminal error for resume-capable sessions.
- Record diagnostics: "Voice session detached" / "Reconnecting voice session".
- Wait briefly for `ConnectionViewModel` to revalidate and update `effectiveRelayUrl`.
- Reopen the websocket to the same `websocket_path` on the new base URL.
- Send `session.resume` with resume token and last acked ids.
- If resume succeeds, restore the prior voice phase.
- If resume fails, surface a clear error and return to idle.

Suggested user-facing copy:

- "Connection changed, reconnecting."
- "Voice reconnected."
- "Realtime session could not resume. Please try again."

### 4. Input Audio During Handoff

For Realtime Agent listening:

- Assign `chunk_id` to every `input_audio.append`.
- Keep a small ring of unacked PCM chunks.
- On resume, resend chunks after `last_input_chunk_id`.
- If the user finished speaking and `input_audio.commit` was already acked, do not resend audio.

For stable STT upload:

- Do not implement multipart upload resume in the first pass.
- If the relay did not receive the utterance, fail clearly and let the user retry.

## Implementation Phases

### Phase 1: Protocol And Tests

- Update `docs/relay-protocol.md` with detach/resume events, ids, acks, and TTL.
- Add unit tests for event id assignment, replay filtering, resume token validation, and detach cleanup.
- Add Android JVM tests for URL change + resume payload formation where feasible.

### Phase 2: Realtime Agent Relay Resume

- Add resume metadata to `/voice/realtime-agent/session`.
- Add event/audio ids to Realtime Agent server events.
- Add detach TTL and buffering.
- Add `session.resume`.
- Keep xAI/OpenAI provider sockets alive while Android is detached.
- Add route tests using fake providers and websocket reconnects.

### Phase 3: Android Realtime Agent Resume

- Store Realtime Agent session handle and ack ids.
- Send chunk ids and client acks.
- On websocket failure, reconnect through the current `effectiveRelayUrl`.
- Resume the same session and replay missed events/audio.
- Add diagnostics and restrained voice overlay status.

### Phase 4: Stable Voice Output Resume

- Add the same detach/resume helper to `/voice/output/*`.
- Resume streaming TTS playback when Android changes network while provider PCM is already being rendered.
- Keep stable STT upload retry-only for now.

### Phase 5: UX And Settings

- Add a Voice setting under diagnostics/developer controls:
  - "Resume voice sessions after connection changes"
  - default on for Realtime Agent once verified
  - detailed trace shows session id, detached/resumed, replayed event counts
- Keep default timeline clean:
  - show only "Connection changed, reconnecting" and "Voice reconnected"
  - raw event replay/debug details stay behind Detailed trace/Diagnostics.

## Testing Plan

Relay tests:

- Realtime Agent session returns resume metadata.
- Unexpected websocket close marks session detached and keeps provider connection alive.
- Reconnect with valid resume token succeeds.
- Reconnect with wrong token, wrong principal, closed session, or expired TTL fails.
- Server replays events after `last_event_id`.
- Server replays audio after `last_audio_event_id`.
- Server does not replay audio already marked played.
- Provider tool call pending during detach resumes and sends post-tool response after playback ack or timeout.
- Detached session cleans up after TTL.

Android tests:

- Stable Voice Output and Realtime Agent still follow shared `effectiveRelayUrl`.
- Realtime Agent stores session handle from create-session response.
- Realtime Agent sends chunk ids and coalesced acks.
- Websocket failure during active Realtime Agent turn enters reconnecting state.
- Resume uses current `effectiveRelayUrl`, not the old URL.
- Resume failure surfaces a clear recoverable error.

Manual device smoke:

- Start Realtime Agent over Wi-Fi and ask a short question.
- Disable Wi-Fi while provider is thinking.
- Confirm overlay shows reconnecting, then resumes on cellular/Tailscale.
- Repeat while provider is speaking.
- Repeat during a Hermes tool call and during a confirmation prompt.
- Confirm Diagnostics records detach/resume and route change.
- Capture the run with `.\scripts\voice-phone-smoke.ps1 -Handoff`; review
  `adb-logcat-voice.txt`, `relay-journal-voice.txt`, and the connectivity
  before/after captures in the generated `voice-lab-runs/phone-smoke/<timestamp>/`
  folder.
- From an agent/non-interactive shell, use
  `.\scripts\voice-phone-smoke.ps1 -Handoff -WaitForUnlockSeconds 180 -ManualSeconds 180`
  so the helper waits for unlock, gives a fixed manual action window, then
  captures artifacts without a console prompt.

## Acceptance Criteria

- Network handoff during Realtime Agent thinking or speaking does not start a new Hermes run when the resume window is met.
- Android uses the same `effectiveRelayUrl` route selection for resumed voice sessions as for fresh voice sessions.
- Relay provider adapters remain provider-specific only for provider wire protocol, not Android route selection.
- Default UI is not noisy; detailed replay/debug info is opt-in.
- If resume cannot happen, failure is explicit and recoverable.
- Existing stable voice, voice output tests, realtime-agent xAI/OpenAI tests, and endpoint route tests still pass.

## Implementation Notes

Initial implementation landed for both Realtime Agent and stable Voice Output:

- `/voice/realtime-agent/session` and `/voice/output/session` return `resume_token`, `resume_supported`, and `resume_ttl_ms`.
- Both websocket surfaces assign `event_id` to server events and `audio_event_id` to PCM deltas.
- Android sends `client.ack` and resumes with the latest event/audio/input ids through the current `effectiveRelayUrl`.
- Android now passes the `effectiveRelayUrl` flow into `RelayVoiceClient`; active Realtime Agent and stable Voice Output sessions proactively issue `session.resume` when that route changes instead of waiting only for the old websocket to fail.
- The relay validates resume tokens against the original voice-auth principal, keeps provider/render work alive during the resume TTL, and replays bounded missed events/audio.
- Realtime Agent preserves the server-side native xAI/OpenAI provider socket during short Android detach windows; stable Voice Output preserves the renderer task.
- Focused relay and Android tests cover resume metadata, replay, shared route selection, and route-change-driven proactive resume. Device handoff smoke remains the final manual gate.
- `scripts/voice-phone-smoke.ps1 -Handoff` captures resume-focused Android logs,
  relay journal lines, and connectivity before/after evidence for that manual
  gate.
- The helper also supports `-WaitForUnlockSeconds` and `-ManualSeconds` for
  non-interactive agent-driven smoke collection.

## Risks

- Provider sockets may close while Android is detached. Mitigation: preserve Hermes state and fail the provider layer clearly.
- Audio replay can duplicate speech. Mitigation: separate received, queued, and played audio acks.
- Buffering audio during detach can grow memory. Mitigation: bounded ring buffers and TTL.
- Android process death is not covered in phase 1. Mitigation: in-memory resume first, durable resume later if needed.
- Confirmation prompts during detach must not auto-allow. Mitigation: preserve pending confirmation state and require explicit user response after resume.

## Rollback

- Keep resume behind server capability metadata and an Android feature flag.
- If issues appear, Android can ignore `resume_supported` and fall back to current failure/retry behavior.
- Relay can close detached sessions immediately when `RELAY_VOICE_RESUME_ENABLED=0`.
