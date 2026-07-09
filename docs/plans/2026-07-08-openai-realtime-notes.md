# OpenAI Realtime / live voice — research notes (2026-07-08)

Research snapshot mapping OpenAI's realtime voice offering (as of July 2026)
onto the hermes-relay realtime agent, to scope next-release-candidate work.
Companion TODO items live under "OpenAI realtime provider — next-RC roadmap"
in `TODO.md`.

## Repo starting point

`plugin/relay/realtime_agent/providers/openai.py` is a complete,
connection-oriented `RealtimeAgentProvider` implementing the full
`RealtimeAgentConnection` interface (send_audio / commit_audio / send_text /
clear_audio / cancel_response / send_tool_result / request_response / events)
and is wired into the broker alongside xAI. It already uses the GA session
shape (`session.type: "realtime"`, `output_modalities`,
`audio.input/output.format`), per-response `instructions` overrides,
`gpt-realtime-whisper` transcription, and 24kHz PCM16.

Gaps: the default model constant is `gpt-realtime-2` (superseded 2026-07-06
by `gpt-realtime-2.1` / `gpt-realtime-2.1-mini`, drop-in protocol), and no
recorded live voice round has exercised the OpenAI path — every forensics
session in `realtime-agent-runs/` is grok-voice/xAI.

## Findings

**Model lineup** (speech-to-speech, single-model, not cascaded):

- `gpt-realtime` — GA snapshot `gpt-realtime-2025-08-28`, 32k context,
  WebRTC/WebSocket/SIP.
  <https://developers.openai.com/api/docs/models/gpt-realtime>
- `gpt-realtime-2` (GPT-5-class reasoning), `gpt-realtime-translate`,
  `gpt-realtime-whisper`.
  <https://openai.com/index/advancing-voice-intelligence-with-new-models-in-the-api/>
- `gpt-realtime-2.1` + `gpt-realtime-2.1-mini` (2026-07-06): configurable
  reasoning effort, better interruption/noise/alphanumeric handling,
  p95 latency −25%.

**Transports / audio:** WebRTC, WebSocket, SIP; PCM16 @ 24kHz (g711 for
telephony). The relay uses WebSocket + PCM16 @ 24kHz — aligned.

**Session lifecycle:** hard **60-minute wall-clock cap** regardless of
activity (raised from 30). `turn_detection.idle_timeout_ms` exists but only
under `server_vad` — N/A to the relay, which runs `turn_detection: null` and
owns turn-taking. No resume token: an in-flight response survives a socket
drop, but a capped/dropped session must be rebuilt via
`conversation.item.create` / `response.create.input` / `item_reference`.
<https://developers.openai.com/api/docs/guides/conversation-state>

**Turn-taking:** `server_vad` / `semantic_vad` (content-based end-of-turn) /
`none`. Barge-in = `input_audio_buffer.speech_started` →
`conversation.item.truncate` when provider VAD is on. The relay uses `none`
and owns the floor (`RealtimeFloor`) — deliberate; provider VAD/truncate
unused. <https://platform.openai.com/docs/guides/realtime-vad>

**Per-response instructions + out-of-band responses:**
`response.create.instructions` override (already used by the broker), plus
out-of-band responses — `"conversation": "none"` with a custom `"input"`
array (`item_reference`, new messages, or `[]`) and `"metadata"`. Strictly
more control than xAI exposes; directly relevant to exact-answer delivery.
<https://developers.openai.com/api/docs/guides/realtime-conversations>

**Function calling:** standard `function_call` → `function_call_output`; GA
allows the session to continue while a function call is pending (async,
unlike the Responses API). Hosted MCP and image input supported (the relay
keeps non-Hermes tools off).
<https://developers.openai.com/blog/realtime-api>

**Voices / pricing:** `alloy/ash/ballad/coral/echo/sage/shimmer/verse` +
`marin` + `cedar` (Realtime-exclusive). Per 1M tokens — 2.1: audio in $32 /
out $64 / cached $0.40; text $4/$16. 2.1-mini: audio in $10 / out $20 /
cached $0.30. <https://developers.openai.com/api/docs/pricing>

## OpenAI Realtime vs xAI Grok Voice (as the relay uses them)

| Dimension | OpenAI (gpt-realtime-2.1) | xAI (grok-voice-latest) |
|---|---|---|
| Session close | Hard 60-min wall-clock cap, activity-independent | 900s conversation-inactivity close (verified live 4×; active turns stay alive) + ~30-min hard cap per docs |
| Idle knob | `idle_timeout_ms` (server_vad only — N/A) | None; no message resets the 900s timer (verified live) |
| Reconnect/resume | No token; rebuild conversation items on reconnect | Session ends → reseed from the durable Hermes session (current handling) |
| Turn detection | server_vad / semantic_vad / none | none (relay-driven) |
| Duplex/barge-in | `speech_started` + `item.truncate` when VAD on | Provider events; relay owns the floor either way |
| Per-response instructions | Yes, plus out-of-band `conversation:"none"` + `input` | Yes (`instructions`); xAI-only `force_message` provides exact TTS without model inference |
| Async function calls | Yes — session continues while a call is pending | Unverified |
| Pricing | Per-token (see above) | Flat $0.05/min + tool/text tokens separate |
| Reasoning control | 2.1 configurable effort; mini reasons before speaking | No exposed knob |

## Capabilities that could obsolete current workarounds

1. **Forced-summary validation fragility** — grok-voice spoke deferral filler
   in most live rounds. xAI Exact mode now uses its provider-native
   `force_message` event and bypasses model compliance entirely; OpenAI still
   needs the out-of-band response experiment carrying the Hermes answer as
   explicit `input` context. The blocklist/validator remains a safety net.
2. **Delivery-note hack** — async function calling makes it possible to leave
   a promoted `hermes_run_task` pending and complete it with a real late
   `function_call_output`, so the provider's own history reads "done" and
   `native_pending_delivery_note` becomes unnecessary (on OpenAI).
3. **Keepalive reasoning doesn't transfer** — OpenAI's failure mode is a
   wall-clock cap that can cut an ACTIVE session, unlike xAI's inactivity
   timer; the current idle-close handling is xAI-shaped
   (`_PROVIDER_IDLE_CLOSE_WS_REASON`) and a focused pass over the broker's
   close handling should confirm how a cap-close surfaces before scheduling
   the reconnect work.
