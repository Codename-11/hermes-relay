# Hermes-Relay Realtime Voice Provider Lab

## Purpose

This document tracks the realtime voice provider lab, the server-mediated
Hermes-Relay voice output path, and the experimental relay-brokered Realtime
Agent engine. The standalone lab still exists for provider experimentation,
streaming TTS testing, expression testing, latency measurement, and provider
comparison. The stable runtime uses the relay `/voice/output/*` route for
deterministic Android assistant speech. Realtime Agent is an opt-in Voice
Settings engine that keeps Hermes as the owner of chat, memory, tools,
confirmations, and transcript persistence.

Current status: standalone CLI lab, stable relay-owned Voice Output, legacy
realtime provider lab routes, and experimental `/voice/realtime-agent/*` broker
routes. Android Voice Settings labels only Realtime Agent and Barge-in as
Experimental; the stable voice engine remains `Hermes Chat + Voice Output`.

- Existing `/voice/config`, `/voice/transcribe`, and `/voice/synthesize` remain
  available as the basic fallback and utility surface.
- Android primary voice mode records one 16 kHz mono PCM/WAV utterance. The WAV
  goes to `/voice/transcribe` for the STT leg. Realtime-agent test screens can
  still forward raw PCM through `/voice/realtime/{session_id}` input events for
  provider testing and event artifacts.
- Current assistant speech output prefers `/voice/output/config`,
  `/voice/output/session`, and `/voice/output/{session_id}`. Provider PCM
  deltas stream back through the relay and play directly through Android
  `AudioTrack`; failed output runs fall back to `/voice/synthesize`.
- Grok streaming TTS is available as `xai_tts`, followed by OpenAI TTS via
  `openai_tts` and existing Hermes-compatible fallback providers.
- Tool execution remains in the Hermes chat/relay loop. Android voice mode
  observes Hermes tool-start events and brokers compact status lines while
  Hermes runs the actual tool and approval flow. In provider-native Realtime
  Agent mode those status lines stay UI-only so they do not create a competing
  `/voice/output` stream beside provider audio.
- The experimental `/voice/realtime-agent/*` surface binds a provider render
  session to a Hermes chat session/profile. It mirrors input transcripts,
  Hermes tool state, confirmation prompts, assistant deltas, final responses,
  and provider PCM into the existing Android chat/voice timeline.
- The brokered tool surface is intentionally small:
  `hermes_run_task`, `hermes_get_status`, `hermes_cancel`, and
  `hermes_confirm`.
- The first live realtime adapters are `openai_realtime` and `xai_realtime`,
  using provider WebSocket APIs for CLI testing.
- `elevenlabs_tts` is available as a streaming TTS comparison target for the
  cascaded STT -> LLM -> TTS approach.
- `eval-approach` runs a fixed E2E prompt matrix and writes WAV, JSONL,
  `summary.json`, `runs.jsonl`, and `report.md` artifacts.
- The Grok adapter supports raw xAI API credentials and a lab-owned xAI OAuth
  PKCE flow for SuperGrok/Premium+ testing.
- Relay runtime Grok auth uses `~/.hermes-relay/auth/xai-oauth.json` or xAI
  environment credentials. The dev launcher can copy the lab OAuth store into
  that relay-owned path for testing.
- The built-in `stub` provider is only a deterministic local WAV generator for
  validating the CLI, event logs, metrics, and output paths.

## Current Provider References

As of 2026-05-18, current provider references are:

- xAI Text to Speech:
  <https://docs.x.ai/developers/model-capabilities/audio/text-to-speech>
- xAI Voice Agent:
  <https://docs.x.ai/developers/model-capabilities/audio/voice-agent>
- xAI Voice REST reference:
  <https://docs.x.ai/developers/rest-api-reference/inference/voice>
- OpenAI Text to Speech:
  <https://developers.openai.com/api/docs/guides/text-to-speech>
- OpenAI Realtime conversations:
  <https://developers.openai.com/api/docs/guides/realtime-conversations>
- xAI OAuth discovery:
  <https://auth.x.ai/.well-known/openid-configuration>
- xAI announcement for the advertised SuperGrok surfaces:
  <https://x.ai/news/grok-hermes>

The lab implements its own browser-based OAuth 2.0 PKCE loopback flow and stores
tokens in `voice-lab-runs/auth/xai-oauth.json` by default. It does not require
the `hermes` command, does not read `~/.hermes/auth.json`, and does not scrape
Grok.com or X browser/session cookies.

## Output Architecture Decision

Use separate output modes instead of overloading realtime voice agents as TTS.

```text
streaming_tts_renderer
  Primary target for assistant speech and brokered tool-status lines.
  Input is final Hermes text. Output should be exact/verbatim audio.
  First target: xai_tts over xAI streaming TTS.
  Next targets: openai_tts, elevenlabs_tts, hermes_compat, stub.

realtime_agent
  Experimental target for speech-to-speech/provider turn-taking work. In the
  Android MVP, the client sends the transcript text to the relay broker, Hermes
  performs the chat/tool run, and the selected realtime provider renders the
  approved Hermes response as PCM. Future provider-native turns must still use
  the brokered Hermes tool surface.

legacy_hermes_tts
  Compatibility fallback through `/voice/synthesize` and upstream Hermes TTS
  provider config.
```

Shared voice-output behavior belongs in a broker above individual providers:
verbatim rendering, waveform/meter data from actual PCM, first-audio latency,
chunk-gap metrics, barge-in cancellation, fallback selection, short
pre-tool-call speech, and long-task filler speech. Hermes remains the owner of
chat, tools, approvals, and final response text.

## Realtime Agent Broker

Realtime Agent is deliberately separate from the older provider lab:

```text
Android Voice UI
  -> /voice/realtime-agent/session + websocket
  -> RealtimeAgentBroker
  -> HermesToolBroker
  -> Hermes API session/profile/tool loop
  -> Realtime provider adapter for PCM output
  -> Android playback + mirrored chat/voice timeline
```

Provider adapters live under `plugin/relay/realtime_agent/providers/` and hide
OpenAI/xAI-specific details behind the broker. They receive only broker-approved
text/audio rendering context. Hermes remains first-class for profile/session
binding, memory, transcript writes, tools, Android bridge safety, confirmations,
and cancellation.

Realtime Agent sessions now advertise a relay-generated `resume_token`,
`resume_supported=true`, and a 30 second default resume TTL from
`/voice/realtime-agent/session`. Every relay-to-Android event carries
`event_id`; PCM deltas also carry `audio_event_id`; Android sends compact
`client.ack` messages and can reconnect with `session.resume` after Wi-Fi,
cellular, LAN, or Tailscale route changes. Android watches the same
`effectiveRelayUrl` flow used by chat/relay HTTP, so a `ConnectivityManager` or
endpoint resolver route change can proactively open the resumed voice websocket
before the stale OkHttp websocket reports failure. During the resume window the
relay keeps the server-side provider socket and Hermes broker state alive,
buffers a bounded event/audio ring, then replays missed events without changing
provider adapters or endpoint-selection logic.

The stable `/voice/output/*` renderer session now uses the same detach/resume
contract for PCM playback. Session creation returns `resume_token`,
`resume_supported`, and `resume_ttl_ms`; renderer events carry `event_id`, audio
deltas carry `audio_event_id`, and Android acknowledges/reconnects through the
current `effectiveRelayUrl`. The Android client watches that route flow during
active renderer sessions and can resume immediately on route change. If the
phone drops Wi-Fi mid-speech, the relay continues the render task for the resume
TTL and replays missed audio instead of forcing a new TTS render or falling back
to legacy synth.

Realtime Agent tool status is brokered from Hermes, not invented by the
provider. The relay forwards Hermes tool-start/delta/completed/failed events
when the upstream stream exposes them and synthesizes a compact
`hermes.tool.started` if a stream only reports late completion metadata. While a
Hermes run is active, the relay emits periodic `hermes.run.progress` events with
actual active/latest tool fields plus a stable `status_key`. After a longer
pause, `should_speak=true` lets Android surface a restrained local status line
such as "Running command." or "Searching desktop." as UI state. Android does not
play that line through `/voice/output` during a provider-native realtime turn.
Raw tool output stays in relay run logs; the realtime provider receives the
compact Hermes function result and speaks the natural post-tool summary.

Provider-native Realtime Agent turns must preserve the live speech loop around
Hermes, not collapse back into local TTS. The correct forced-Hermes sequence is:

1. The realtime provider owns speech recognition and produces the final user
   transcript.
2. If the transcript requires Hermes for tools, memory, current data,
   confirmations, side effects, or durable context, the relay first asks the
   active realtime provider to speak one short acknowledgement such as "I'll
   check Hermes." The provider must not call tools or add content during this
   preamble.
3. After that provider audio drains, the relay starts the Hermes tool/session
   run and mirrors clean Hermes status events into the voice UI.
4. Hermes returns a compact authoritative function result to the provider.
5. The same realtime provider speaks the final natural summary. Android must not
   read raw Hermes tool output aloud.

Local spoken status lines are fallback and long-wait affordances only. They are
valid when the provider preamble fails, when Hermes has been running long enough
to need restrained progress feedback, or when stable `Hermes Chat + Voice
Output` is the selected engine. They should not replace provider-native
pre-Hermes acknowledgement in a healthy Realtime Agent turn.

Provider-native instructions treat Hermes as durable memory. Follow-up
references such as "this", "that", "it", or "that integration" should first use
the seeded recent chat/voice context when it is sufficient. If that context is
missing, stale, tool-derived, or needs verification, the provider calls
`hermes_run_task` before answering. `hermes_run_task` results include an
authoritative `answer`/`summary` payload so the provider has context for the
spoken reply and should not say it has no context after Hermes returns.

Android opens each Realtime Agent session with compact recent timeline context
from the same chat view used by normal Hermes Chat + Voice Output. If the phone
does not have local context but passes a `chat_session_id`, the relay attempts a
short Hermes messages fetch and seeds that history instead. Provider-only
realtime answers are marked as unsynced local turns; the next normal Hermes
chat/run request splices them in as user/assistant messages before the live user
message, then marks them synced. Hermes-backed tool turns are not duplicated
because Hermes already owns those canonical turns.

Limitations in the first Android-integrated slice:

- Client STT still produces the committed transcript before the broker runs the
  Hermes turn.
- Provider audio starts after Hermes has enough final assistant text to render.
- If provider audio fails or disconnects, switch Voice engine back to stable
  `Hermes Chat + Voice Output`.
- Provider-native function-call loops are allowed only through the brokered
  Hermes tool surface and remain experimental.

## Non-Negotiables

1. Hermes remains first-class.
2. Existing Hermes voice endpoints continue working unchanged.
3. Realtime provider experiments remain reproducible outside Android.
4. Provider code should be reusable between the CLI harness, future relay
   integration, and future mobile integrations.
5. Metrics come before optimization.
6. Emotional and conversational expression are first-class test inputs.
7. Provider adapters should be easy to add and remove as provider APIs evolve.

## Current Code Layout

```text
plugin/
  voice_lab/
    __main__.py
    auth.py
    cli.py
    expressions.py
    metrics.py
    eval_approach.py
    registry.py
    terminal_ui.py
    textual_tui.py
    tui_session.py
    providers/
      base.py
      elevenlabs_tts.py
      openai_realtime.py
      stub.py
      xai_realtime.py
scripts/
  voice-lab.ps1
  voice-relay-dev.ps1
```

The package is separate from `plugin.relay.voice` because `plugin/relay/voice.py`
is the existing production relay HTTP voice handler. Do not convert that file
into a package or add experimental provider code there during the standalone
POC phase.

## CLI Usage

Run the lab directly from the repo:

```powershell
python -m plugin.voice_lab doctor
python -m plugin.voice_lab providers
```

`doctor` reports missing live provider credentials. That is expected on
machines that can only run the local `stub` provider.

For day-to-day local testing on Windows, use the convenience launcher:

```powershell
.\scripts\voice-lab.ps1 -Mode doctor
.\scripts\voice-lab.ps1 -Mode providers
.\scripts\voice-lab.ps1 -Provider stub
.\scripts\voice-lab.ps1 -Provider openai -OpenAIKey "<your key>"
.\scripts\voice-lab.ps1 -Mode auth -Provider grok
.\scripts\voice-lab.ps1 -Provider grok -XAIKey "<your xAI API key>"
.\scripts\voice-lab.ps1 -Provider elevenlabs -ElevenLabsKey "<your key>"
.\scripts\voice-lab.ps1 -Mode eval -Providers "xai_realtime,openai_realtime,elevenlabs_tts,stub"
```

For the SuperGrok/Premium+ path, run the lab-owned OAuth flow first:

```powershell
python -m plugin.voice_lab auth --provider grok
# or
.\scripts\voice-lab.ps1 -Mode auth -Provider grok
```

`-Provider auto` is the launcher default. It uses `xai_realtime` when
the lab-owned xAI OAuth store, `XAI_API_KEY`, or `VOICE_TOOLS_XAI_KEY` is
available, then falls back to `openai_realtime` when `OPENAI_API_KEY` or
`VOICE_TOOLS_OPENAI_KEY` is available, then falls back to `stub`. Keys can live
in the current environment or `VOICE_LAB_HOME/.env`; xAI OAuth tokens live in
`VOICE_LAB_HOME/auth/xai-oauth.json`. Generated WAV files and JSONL event logs
are written to `voice-lab-runs/`, which is ignored by git.

Run the default quick test. This uses `openai_realtime` unless you name another
provider:

```powershell
python -m plugin.voice_lab test
python -m plugin.voice_lab test stub
python -m plugin.voice_lab test openai_realtime --text "Testing one two three."
```

Run the persistent PulseForge-inspired TUI for iterative provider testing:

```powershell
.\scripts\voice-lab.ps1 -Mode tui -Provider grok

# Direct Python form:
python -m plugin.voice_lab tui --provider xai_realtime
```

Interactive terminals launch the Textual-backed PulseForge-style TUI when
`textual` is installed. It provides keyboard-operable dropdowns for provider,
model, voice, emotion, tone, pace, style, and command execution; replay/rerun
buttons; a live waveform from generated PCM RMS levels; latency metrics; and
artifact paths. Piped sessions, `--json`, and `--visual off` use the simpler
console loop so automation stays parseable.

Run the relay with realtime voice defaults for Android Studio testing:

```powershell
.\scripts\voice-relay-dev.ps1 -Provider grok

# Local provider/no-quota route smoke:
.\scripts\voice-relay-dev.ps1 -Stub
```

Then pair the Android dev build with the relay and open:

```text
Settings > Developer options > Realtime voice lab
```

The primary Android voice overlay uses stable Voice Output by default. Select
`Settings > Voice > Voice engine > Realtime Agent` to test the experimental
brokered route. The Developer options screen remains available for isolated
provider smoke tests; it fetches realtime provider config, captures a short
mono 16-bit PCM mic sample for the input event, opens the realtime websocket,
streams provider PCM deltas to `AudioTrack`, shows waveform/latency/event
state, and keeps relay-generated WAV/JSONL artifacts on the server.

Install the full keyboard UI dependencies with:

```powershell
python -m pip install -e ".[voice-lab]"
```

For non-TUI one-shot commands, the waveform writes to stderr so JSON and run
summaries on stdout stay parseable. Control it with:

```powershell
--visual auto   # default; render only in an interactive terminal
--visual on     # force waveform output
--visual off    # disable waveform output
```

Play generated audio from the terminal after a run:

```powershell
python -m plugin.voice_lab realtime-text `
  --provider openai_realtime `
  --text "Play this after rendering." `
  --play

.\scripts\voice-lab.ps1 -Provider openai -Text "Play this after rendering." -Play
```

Run one text-to-audio experiment with the local stub provider:

```powershell
python -m plugin.voice_lab realtime-text `
  --provider stub `
  --emotion warm `
  --tone encouraging `
  --text "Everything is under control." `
  --output voice-lab-runs/sample.wav `
  --event-log voice-lab-runs/sample.jsonl
```

Run one text-to-audio experiment with OpenAI Realtime:

```powershell
$env:OPENAI_API_KEY = "<your key>"

python -m plugin.voice_lab realtime-text `
  --provider openai_realtime `
  --emotion warm `
  --tone encouraging `
  --visual on `
  --text "Everything is under control." `
  --output voice-lab-runs/openai-warm.wav `
  --event-log voice-lab-runs/openai-warm.jsonl
```

The OpenAI adapter defaults to:

```text
model=gpt-realtime-2
voice=marin
output_format=audio/pcm
sample_rate=24000
timeout=60
```

Override provider options with repeated `--provider-option KEY=VALUE` flags:

```powershell
python -m plugin.voice_lab realtime-text `
  --provider openai_realtime `
  --provider-option model=gpt-realtime-2 `
  --provider-option voice=cedar `
  --provider-option safety_identifier=hashed-user-id `
  --text "Read this in a calm, precise tone."
```

`websocket-client` is required for the OpenAI adapter. This environment already
has it installed; on a new environment, install it with:

```powershell
pip install websocket-client
```

Run one text-to-audio experiment with Grok Voice Agent:

```powershell
# SuperGrok/Premium+ subscription path:
python -m plugin.voice_lab auth --provider grok

# API-key path, if not using lab OAuth:
# $env:XAI_API_KEY = "<your xAI API key>"

python -m plugin.voice_lab realtime-text `
  --provider xai_realtime `
  --emotion warm `
  --tone encouraging `
  --visual on `
  --text "Everything is under control." `
  --output voice-lab-runs/grok-warm.wav `
  --event-log voice-lab-runs/grok-warm.jsonl
```

The xAI adapter defaults to:

```text
model=grok-voice-latest
voice=eve
sample_rate=24000
timeout=60
```

Grok provider auth:

- Supported: lab-owned xAI OAuth credentials in
  `VOICE_LAB_HOME/auth/xai-oauth.json`, created by
  `python -m plugin.voice_lab auth --provider grok` or
  `.\scripts\voice-lab.ps1 -Mode auth -Provider grok`.
- Supported: `XAI_API_KEY`, `VOICE_TOOLS_XAI_KEY`, or an ephemeral xAI realtime
  client secret passed as `XAI_REALTIME_CLIENT_SECRET` / `XAI_EPHEMERAL_TOKEN`.
- Convenience alias: `GROK_API_KEY` is accepted locally, but `XAI_API_KEY` is
  the official environment name.
- Not supported: scraping Grok.com or X session cookies. Use the lab-owned
  OAuth flow for subscription auth, or xAI API credentials for direct API
  testing.

Enable the non-executing tool-call scaffold for provider event testing:

```powershell
python -m plugin.voice_lab realtime-text `
  --provider xai_realtime `
  --provider-option tool_scaffold=true `
  --text "Run a voice tool-call test without touching relay routes."
```

Run one text-to-audio experiment with ElevenLabs streaming TTS:

```powershell
$env:ELEVENLABS_API_KEY = "<your key>"

python -m plugin.voice_lab realtime-text `
  --provider elevenlabs_tts `
  --emotion warm `
  --tone precise `
  --visual on `
  --text "Everything is under control." `
  --output voice-lab-runs/elevenlabs-warm.wav `
  --event-log voice-lab-runs/elevenlabs-warm.jsonl
```

The ElevenLabs adapter defaults to:

```text
model=eleven_flash_v2_5
voice=JBFqnCBsd6RMkjVDRZzb
output_format=pcm_24000
timeout=60
```

It is intentionally marked as a cascaded TTS target, not a native realtime voice
agent. Use it to compare crispness, pronunciation, volume consistency, and
streaming TTS latency against native voice-agent providers.

Run the fixed E2E approach evaluation:

```powershell
python -m plugin.voice_lab eval-approach `
  --providers xai_realtime,openai_realtime,elevenlabs_tts,stub `
  --output-dir voice-lab-runs/e2e `
  --visual off `
  --json

.\scripts\voice-lab.ps1 `
  -Mode eval `
  -Providers "xai_realtime,openai_realtime,elevenlabs_tts,stub" `
  -OutputDir voice-lab-runs\e2e `
  -Visual off `
  -Json
```

The eval matrix covers:

- `ack`: first audible "let me check that" acknowledgement;
- `tool_final`: a simulated tool wait followed by a final spoken answer;
- `pronunciation`: Hermes, Grok, OpenAI Realtime, ElevenLabs, Obsidian, MCP,
  WebSocket, QR pairing, SuperGrok, and `gpt-realtime-2`;
- `volume`: a steady-level phrase used for RMS variation and clipping checks.

Artifacts are written under the requested output directory:

```text
summary.json   provider rollups, scores, and recommendation
runs.jsonl     one JSON object per scenario run
report.md      human-readable table
events/*.jsonl raw per-run event summaries
*.wav          generated audio
```

Run a prompt script:

```powershell
python -m plugin.voice_lab bench `
  --providers stub `
  --script .\scripts\voice-lab-phrases.txt `
  --output-dir voice-lab-runs
```

Run expression benchmarking:

```powershell
python -m plugin.voice_lab bench-expression `
  --providers stub `
  --script .\scripts\voice-lab-phrases.txt `
  --emotions calm,warm,serious,energetic `
  --output-dir voice-lab-runs `
  --event-dir voice-lab-runs\events
```

Run STT scaffolding. The `stub` provider inspects the WAV and returns a
deterministic transcript so automation can validate the STT surface before a
live streaming adapter is implemented:

```powershell
python -m plugin.voice_lab stt `
  --provider stub `
  --input voice-lab-runs/sample.wav `
  --expected-text "expected transcript" `
  --event-log voice-lab-runs/stt-sample.jsonl

.\scripts\voice-lab.ps1 `
  -Mode stt `
  -Provider stub `
  -InputAudio voice-lab-runs\sample.wav `
  -ExpectedText "expected transcript"
```

Run speech-to-speech scaffolding. The `stub` provider inspects the input WAV,
logs a synthetic transcript, and renders a response WAV using the same
waveform, event-log, expression, JSON, and playback path as text-to-speech:

```powershell
python -m plugin.voice_lab speech-to-speech `
  --provider stub `
  --input voice-lab-runs/sample.wav `
  --text "I heard the sample and am responding." `
  --output voice-lab-runs/s2s-sample.wav `
  --event-log voice-lab-runs/s2s-sample.jsonl

.\scripts\voice-lab.ps1 `
  -Mode s2s `
  -Provider stub `
  -InputAudio voice-lab-runs\sample.wav `
  -Text "I heard the sample and am responding."
```

## Expression Model

The lab uses `VoiceExpression` as the provider-neutral input model:

```python
VoiceExpression(
    emotion="warm",
    tone="encouraging",
    intensity=0.5,
    pace="normal",
    style="natural",
    pronunciation_hints=[],
    interruption_behavior="allow",
    persona_instructions=None,
    provider_overrides={},
)
```

Provider adapters should translate this object into provider-specific session
or turn controls. Prompt prefixes such as "say this happily" are acceptable for
early experiments, but they should not become the long-term abstraction.

## Metrics

Every run should collect the common comparison fields below when a provider can
measure them:

```text
first_audio_ms
response_done_ms
audio_chunk_gap_ms
audio_underruns
speech_pacing
tone_consistency
emotion_consistency
voice_drift
interrupt_latency
pronunciation_quality
context_retention
provider
model
voice
```

The current providers record timing and chunk events. Subjective fields such as
tone consistency and pronunciation quality are left unset until a review
workflow exists.

## Provider Adapter Contract

Provider adapters implement `VoiceProvider.run_text(request, recorder)` and
return `VoiceResponse`. STT and speech-to-speech scaffolds use
`run_transcription(...)` and `run_speech_to_speech(...)`; providers that have
not implemented those methods fail cleanly with `provider unavailable` instead
of falling through to relay or Android code.

Adapters should:

- keep provider-specific imports inside the adapter module;
- accept `VoiceExpression` without requiring relay or Android state;
- write audio output to the requested path;
- emit raw events through `MetricsRecorder.event`;
- call `MetricsRecorder.audio_chunk` when audio bytes become available;
- return provider, model, voice, output path, metrics, and metadata;
- fail before spending quota if required credentials are missing.

## Providers

Implemented:

```text
stub              tts, stt scaffold, speech-to-speech scaffold, expression
xai_tts           primary Grok streaming TTS renderer target
openai_tts        OpenAI streaming TTS renderer target
openai_realtime   live realtime voice-agent render, expression, verbatim prompt mode
xai_realtime      live realtime voice-agent render, expression, tool-use scaffold, verbatim prompt mode
elevenlabs_tts    live streaming TTS renderer comparison, expression
```

Reserved for future adapters:


```text
hermes_compat     upstream Hermes `/voice/synthesize` compatibility renderer
```

Before adding the next real provider, document its credentials, transport,
output format, rate-limit risks, and minimum acceptance criteria.

## POC Sequence

### Phase 0 - Repo Recon

Completed enough for this standalone slice:

- basic fallback routes are `/voice/config`, `/voice/transcribe`, and
  `/voice/synthesize`;
- production voice auth is isolated in `plugin.relay.voice_auth`;
- production upstream Hermes voice imports are isolated in
  `plugin.relay.upstream_voice`;
- `plugin.relay.voice` should remain the stable basic STT/TTS surface while
  realtime provider code lives in `plugin.relay.realtime_voice` and
  `plugin.voice_lab`.

### Phase 1 - Shared Provider Layer

Current implementation:

- `plugin.voice_lab.providers.base`
- `plugin.voice_lab.metrics`
- `plugin.voice_lab.expressions`
- `plugin.voice_lab.registry`

### Phase 2 - CLI Harness

Current implementation:

- `python -m plugin.voice_lab list-providers`
- `python -m plugin.voice_lab providers`
- `python -m plugin.voice_lab doctor`
- `python -m plugin.voice_lab test`
- `python -m plugin.voice_lab realtime-text`
- `python -m plugin.voice_lab tui`
- `python -m plugin.voice_lab stt`
- `python -m plugin.voice_lab speech-to-speech`
- `python -m plugin.voice_lab bench`
- `python -m plugin.voice_lab bench-expression`
- `python -m plugin.voice_lab eval-approach`

### Phase 3 - First Real Provider

Current implementation:

- `plugin.voice_lab.providers.openai_realtime`
- `plugin.voice_lab.providers.xai_realtime`
- `plugin.voice_lab.providers.elevenlabs_tts`
- server-side WebSocket transport
- default model `gpt-realtime-2`
- default voice `marin`
- raw event summaries in JSONL logs
- WAV output from PCM audio delta events

The OpenAI adapter requires `OPENAI_API_KEY` or `VOICE_TOOLS_OPENAI_KEY` at
runtime. The xAI adapter accepts lab-owned OAuth credentials from
`VOICE_LAB_HOME/auth/xai-oauth.json`, `XAI_API_KEY` / `VOICE_TOOLS_XAI_KEY`, or
an ephemeral xAI realtime token. Secrets are not included in event logs or
response metadata.

The ElevenLabs adapter requires `ELEVENLABS_API_KEY` or
`VOICE_TOOLS_ELEVENLABS_KEY`. It uses the streaming text-to-speech endpoint and
requires a PCM output format so the lab can write WAV and inspect RMS levels.

### Phase 3.5 - PulseForge-Style Keyboard TUI

Current implementation. The lab now includes a Textual-backed keyboard TUI that
uses PulseForge's terminal structure as the reference: header, signal panel,
voice visualizer, voice settings panel, bottom run strip, and keybinding footer.
The original line-oriented prompt loop remains as the fallback for non-TTY,
`--json`, and `--visual off` runs.

Target command:

```powershell
.\scripts\voice-lab.ps1 -Mode tui -Provider grok
# or
python -m plugin.voice_lab tui --provider xai_realtime
```

Expected behavior:

- stay inside one terminal session for repeated prompts;
- reuse the selected provider and auth without re-running the launcher;
- support provider switching between `stub`, `openai_realtime`, and
  `xai_realtime`;
- show generated PCM waveform, first-audio latency, completion latency, and
  provider status after every turn;
- support keyboard and Tab navigation through dropdowns for provider, model,
  voice, emotion, tone, pace, style, and command execution;
- expose all common actions through keyboard shortcuts or the command dropdown:
  run, replay, rerun, save, log, open log, provider option, play, tool, JSON,
  help, focus prompt, and quit;
- support quick expression changes for emotion, tone, and intensity from the
  TUI, with pace/style/persona still available from launch/provider options;
- support `:replay`, `:rerun`, `:save`, `:log`, `:open-log`, `:provider`,
  `:voice`, `:model`, `:option`, `:tool`, `:json`, and `:quit` style commands;
- keep automation clean by leaving existing `--json` / `--visual off` behavior
  on the console fallback and non-TUI commands.

Acceptance criteria:

- the TUI can run at least three prompts in one process without losing provider
  state;
- every turn writes a WAV and optional JSONL event log under `voice-lab-runs/`;
- terminal playback works per turn without blocking the next prompt after
  playback completes;
- `F2`, `F5`, `F6`, `F7`, `F8`, `F9`, `F10`, `Esc`, and `Ctrl+Q` operate
  without mouse input in the Textual TUI;
- failed provider turns stay inside the session and show a concise error;
- existing unit tests and non-interactive commands keep passing.

### Phase 4 - Relay Realtime Voice Integration

Current implementation uses `/voice/output/*` as the Android conversational
voice renderer while preserving the existing HTTP STT/TTS endpoints as the
basic fallback and utility APIs. The relay realtime route remains available as
a provider-agent test path. The realtime pass made live mobile PCM playback,
provider metrics, and barge-in testable, but it also proved that realtime
voice-agent providers should not be treated as deterministic TTS.

Tool calls still run through the existing Hermes chat/session stream. Voice mode
adds a brokered feedback layer only: when Hermes emits a running tool card, the
Android stream observer queues a short status sentence through the selected
speech renderer so the conversation does not go silent during searches, desktop
checks, phone actions, or file reads.

```text
/voice/output/config
/voice/output/session
/voice/output/{session_id}
/voice/realtime/config
/voice/realtime/session
/voice/realtime/{session_id}
```

Server-side owner files:

```text
plugin/relay/voice_output.py
plugin/relay/realtime_voice.py
plugin/relay/server.py
plugin/relay/config.py
plugin/relay/auth.py
plugin/relay/voice_auth.py
```

Android dev-build owner files:

```text
app/src/main/kotlin/com/hermesandroid/relay/network/RelayVoiceClient.kt
app/src/main/kotlin/com/hermesandroid/relay/audio/VoiceRecorder.kt
app/src/main/kotlin/com/hermesandroid/relay/audio/RealtimePcmPlayer.kt
app/src/main/kotlin/com/hermesandroid/relay/audio/RealtimePcmRecorder.kt
app/src/main/kotlin/com/hermesandroid/relay/viewmodel/VoiceViewModel.kt
app/src/main/kotlin/com/hermesandroid/relay/ui/screens/RealtimeVoiceTestScreen.kt
app/src/main/kotlin/com/hermesandroid/relay/ui/screens/DeveloperSettingsScreen.kt
app/src/main/kotlin/com/hermesandroid/relay/ui/RelayApp.kt
```

Voice output and realtime voice are enabled by default in `RelayConfig`, but
their host configuration is relay-owned and intentionally separate from
upstream Hermes `~/.hermes/config.yaml`:

```yaml
# ~/.hermes-relay/config.yaml
voice_output:
  enabled: true
  provider: xai_tts
  model: xai-tts
  voice: eve
  sample_rate: 24000
  language: en
  codec: pcm
  optimize_streaming_latency: 1
  fallback_enabled: true
realtime_voice:
  enabled: true
  provider: xai_realtime
  model: grok-voice-latest
  voice: eve
  sample_rate: 24000
  xai_oauth_path: ~/.hermes/auth.json
```

`GET /voice/output/config` returns renderer defaults plus provider/auth status
and provider option metadata for UI controls: `providers[].models`,
`providers[].voices`, `providers[].languages`, and
`providers[].sample_rates`.
`PATCH /voice/output/config` lets an authenticated operator client persist safe
runtime choices: `enabled`, `provider`, `model`, `voice`, `sample_rate`,
`language`, `codec`, `optimize_streaming_latency`, `text_normalization`, and
`fallback_enabled`. Server-local auth paths such as `xai_oauth_path` stay in
the relay config file or process environment, not in Android storage. Override
or disable with environment variables when needed for a temporary test:

```powershell
$env:RELAY_VOICE_OUTPUT_PROVIDER = "xai_tts"
$env:RELAY_VOICE_OUTPUT_MODEL = "xai-tts"
$env:RELAY_VOICE_OUTPUT_VOICE = "eve"
python -m plugin.relay --no-ssl
```

Android Voice Settings uses the advertised provider metadata for dropdowns while
keeping an advanced manual entry expander for raw provider/model/voice IDs. The
provider-specific option schema includes grouped voices, source/custom/
recommended metadata, model/voice compatibility hints, and a schema version so
the Android picker can search large catalogs and block known-incompatible
choices before saving. When the active profile is selected, PATCH requests
include `?profile=<name>` and write that profile's experimental `voice_output:`
/ `realtime_voice:` sections, so named profiles such as `mizuki` and `victor`
can keep separate voices.
When the selected provider changes, Android refreshes that provider through
`/voice/output/providers/{provider_id}/options` or
`/voice/realtime/providers/{provider_id}/options` before saving, then calls the
matching `/validate` endpoint before writing config. Dynamic account-backed
discovery stays on the relay; the response reports whether it used static
metadata, succeeded dynamically, hit the cache, or could not fetch remote
options. xAI custom voice discovery is paginated; ElevenLabs voice/model
discovery uses provider API keys; OpenAI uses static documented voices.

### Phase 4.5 - First-Class Streaming TTS Renderer

Implemented baseline:

- `plugin/relay/voice_output.py` adds a swappable voice output strategy
  boundary separate from realtime-agent providers.
- `xai_tts` and `openai_tts` stream PCM through the relay as deterministic
  speech renderers with server-side auth only.
- Final Hermes assistant text and brokered status lines are sent as renderer
  input with `render_mode=verbatim`; the provider is not asked to answer
  conversationally.
- Waveform, PCM playback, first-audio latency, chunk-gap metrics, barge-in, and
  fallback handling are shared with the realtime route.
- Keep `/voice/realtime/*` available for the lab, provider comparison, and
  speech-to-speech/agent experiments.
- Verify whether the existing xAI OAuth/SuperGrok token works for `/v1/tts`.
  If it does not, support xAI API key auth for TTS while leaving OAuth for the
  realtime-agent path.

The Windows helper wraps those settings and can copy the lab OAuth token into
the relay-owned runtime auth path when needed:

```powershell
.\scripts\voice-relay-dev.ps1 -Provider grok
```

Android `Settings > Voice` displays global controls for both engines, then shows
the selected engine's card: editable voice output from `/voice/output/config` or
editable experimental Realtime Agent defaults from `/voice/realtime-agent/config`.
Global fallback TTS stays visible with the global controls, and STT from
`/voice/config` remains visible below the selected engine. Android can update
safe profile-scoped or relay-owned voice defaults, but it does not own provider
secrets. **Test Current Engine** plays the currently saved profile voice-output
path for stable mode and opens a provider-native `/voice/realtime-agent/*` text
test session for Realtime Agent mode, while normal assistant speech prefers
`/voice/output/*` streaming PCM when available.

## Definition Of Done For The Standalone POC

- CLI can run without relay or Android.
- Provider registry can add real adapters without touching relay routes.
- Expression inputs are captured consistently.
- Raw events can be logged as JSONL.
- Audio output is saved per run.
- Metrics are emitted as JSON-friendly data.
- TTS, STT scaffold, speech-to-speech scaffold, expression runs, tool-call
  scaffold, persistent TUI iteration, and provider comparison hooks are exposed
  as standalone lab commands or provider options.
- Terminal waveform reflects generated PCM audio levels, not only connection
  progress.
- Optional `--play` / `-Play` can play rendered WAV output directly from the
  terminal.
- Unit tests cover the stub provider, registry, CLI, console TUI fallback, and
  Textual TUI smoke path.
- E2E approach evaluation can compare native realtime providers against
  streaming TTS using the same prompt matrix and artifact format.

## Verification

```powershell
python -m unittest plugin.tests.test_voice_lab
python -m unittest plugin.tests.test_realtime_voice_routes plugin.tests.test_voice_routes plugin.tests.test_session_grants
python -m compileall plugin\relay plugin\voice_lab
.\gradlew.bat :app:compileSideloadDebugKotlin
python -m plugin.voice_lab doctor
python -m plugin.voice_lab providers
python -m plugin.voice_lab test stub
python -m plugin.voice_lab tui --provider stub --visual off
python -m plugin.voice_lab realtime-text --provider stub --text "Testing one two three."
python -m plugin.voice_lab realtime-text --provider openai_realtime --text "Testing one two three."
python -m plugin.voice_lab realtime-text --provider xai_realtime --text "Testing one two three."
python -m plugin.voice_lab realtime-text --provider elevenlabs_tts --text "Testing one two three."
python -m plugin.voice_lab eval-approach --providers stub --output-dir voice-lab-runs/e2e-stub --visual off --json
```

Only run the live OpenAI, xAI/Grok, or ElevenLabs commands when the relevant
auth source is configured and you are ready to spend a small amount of provider
quota.

## Tested Relay Command Flow

These commands were used on 2026-05-18 to verify the relay-owned output path
against the hermes-host deployment target.

```powershell
# Local repo checks
python -m unittest plugin.tests.test_voice_output_routes plugin.tests.test_voice_lab -v
python -m unittest plugin.tests.test_voice_routes plugin.tests.test_realtime_voice_routes plugin.tests.test_voice_output_routes plugin.tests.test_voice_lab -v
python -m compileall plugin\relay plugin\voice_lab
.\gradlew.bat :app:compileSideloadDebugKotlin
.\gradlew.bat :app:installSideloadDebug

# Remote relay checks
ssh you@hermes-host "cd ~/.hermes/hermes-relay && ~/.hermes/hermes-agent/venv/bin/python -m compileall plugin/relay plugin/voice_lab"
ssh you@hermes-host "cd ~/.hermes/hermes-relay && ~/.hermes/hermes-agent/venv/bin/python -m unittest plugin.tests.test_voice_output_routes plugin.tests.test_voice_lab -v"
ssh you@hermes-host "systemctl --user restart hermes-relay.service"
ssh you@hermes-host "curl -fsS http://127.0.0.1:8767/health"
```

Live relay smoke used the configured server token without printing secrets:

```bash
set -a
. $HOME/.hermes/.env >/dev/null 2>&1 || true
set +a
cd $HOME/.hermes/hermes-relay
$HOME/.hermes/hermes-agent/venv/bin/python - <<'PY'
import asyncio, base64, json, os, time, aiohttp

BASE = "http://127.0.0.1:8767"
HEADERS = {"Authorization": f"Bearer {os.environ['API_SERVER_KEY']}"}

async def main():
    async with aiohttp.ClientSession(headers=HEADERS) as session:
        async with session.post(f"{BASE}/voice/output/session", json={}) as resp:
            body = await resp.json(content_type=None)
            assert resp.status < 400, body
        ws_url = f"{BASE.replace('http', 'ws')}{body['websocket_path']}"
        started = time.perf_counter()
        first_audio_ms = None
        audio_bytes = 0
        async with session.ws_connect(ws_url, headers=HEADERS, heartbeat=20) as ws:
            ready = await ws.receive_json(timeout=10)
            await ws.send_json({
                "type": "response.create",
                "text": "I'll check that now.",
                "render_mode": "verbatim",
            })
            while True:
                msg = await ws.receive(timeout=45)
                event = json.loads(msg.data)
                if event.get("type") == "voice.audio.delta":
                    first_audio_ms = first_audio_ms or round((time.perf_counter() - started) * 1000, 1)
                    audio_bytes += len(base64.b64decode(event["audio_base64"]))
                if event.get("type") == "voice.response.done":
                    print(json.dumps({
                        "ready": ready.get("type"),
                        "done": event.get("type"),
                        "provider": event.get("provider"),
                        "model": event.get("model"),
                        "voice": event.get("voice"),
                        "audio_bytes": audio_bytes,
                        "first_audio_ms_client": first_audio_ms,
                        "first_audio_ms_server": event.get("metrics", {}).get("first_audio_ms"),
                    }, sort_keys=True))
                    return

asyncio.run(main())
PY
```

Expected shape for the successful xAI/Grok TTS smoke is
`provider=xai_tts`, `model=xai-tts`, `voice=eve`, nonzero `audio_bytes`, and
`voice.response.done`. The 2026-05-18 hermes-host smoke produced first audio
in roughly 330 ms and completed a short tool-status phrase in roughly 605 ms.

### Live Phone Smoke

The final end-to-end gate must be run with the Android device unlocked because
ADB cannot drive the voice overlay through the password keyguard.

```powershell
# Confirm the installed sideload build and unlock state.
adb devices -l
adb shell dumpsys package com.axiomlabs.hermesrelay.sideload |
  Select-String -Pattern "versionName|lastUpdateTime"
adb shell dumpsys window |
  Select-String -Pattern "mDreamingLockscreen|mCurrentFocus|mFocusedApp|mKeyguard"

# Start with a clean log window, then run the manual voice turn on the phone.
adb logcat -c
```

On the phone:

1. Unlock the device.
2. Open Hermes Relay sideload build `0.8.0-sideload`.
3. Confirm it reconnects to the active relay route, for example
   `ws://192.168.1.100:8767/ws` or the configured Tailscale route.
4. Start tap-to-talk and say: `check the relay status`.
5. Wait for the spoken status/tool narration and assistant reply.
6. Interrupt while it is speaking to verify barge-in cancellation/resume.

Capture the evidence:

```powershell
# Preferred: use the checked-in helper. It writes artifacts under the ignored
# voice-lab-runs/phone-smoke/<timestamp>/ directory.
.\scripts\voice-phone-smoke.ps1

# For Wi-Fi/cellular, LAN, or Tailscale route handoff validation:
.\scripts\voice-phone-smoke.ps1 -Handoff

# Non-interactive capture from an agent session: waits for unlock, leaves a
# fixed manual window for the phone actions, then captures artifacts.
.\scripts\voice-phone-smoke.ps1 -Handoff -WaitForUnlockSeconds 180 -ManualSeconds 180

# Or capture the same evidence manually:
adb logcat -d -v time |
  Select-String -Pattern "VoiceViewModel|RelayVoiceClient|voice/output|voice.response|voice.audio|voice.session|voice.replay|session.resume|replayed|RealtimePcmPlayer|BargeIn|transcribe|synthesize|auth.ok|sendMessage|tool|ConnectionManager|endpoint fallback|probeAndReconnect" |
  Select-Object -Last 260

ssh you@hermes-host 'journalctl --user -u hermes-relay.service --since "10 minutes ago" --no-pager | grep -E "Client connected|Client disconnected|voice.output|voice/output|voice/realtime|voice.session|voice.replay|session.resume|detached|resumed|resume_failed|voice/config|voice/transcribe|voice/synthesize|ERROR|Traceback" | tail -160'
```

Pass criteria:

- Android logs show `auth.ok` and a live relay connection.
- STT route is called and produces a non-empty transcript.
- Hermes chat/tool loop receives the transcript and returns the assistant turn.
- If a tool starts, the app shows a compact status line before the longer final
  answer without opening a second speech stream.
- Assistant speech uses `/voice/output/*` and receives `voice.audio.delta`
  followed by `voice.response.done`.
- Barge-in logs show user speech interrupting active playback, without losing
  the new turn.
- If `/voice/output/*` fails before audio starts, Android falls back to
  `/voice/synthesize` and logs the fallback path.
- Handoff logs show the current voice websocket fails or closes, Android sends
  `session.resume` through the current relay route, and the relay records
  `voice.*.session.detached` followed by `voice.session.resumed`.
- Replayed events are marked `replayed=true`, and the session does not start a
  duplicate Hermes run inside the resume TTL.

## Idle tolerance (ADR 33 Phase 0)

Background-Hermes-run promotion (ADR 33, `docs/plans/2026-05-24-realtime-background-hermes-runs.md`)
detaches a long Hermes run from the provider event pump and keeps the provider
session open while the run completes. Whether a provider can hold the floor while
**quiescent** (no `response.create`, no input audio) for tens of seconds is the
factual unknown that gates default-on promotion.

Run the probe on the relay host (provider credentials configured) with the repo
root on `PYTHONPATH`:

```bash
python scripts/realtime-provider-idle-probe.py --provider xai
python scripts/realtime-provider-idle-probe.py --provider openai --windows 30,60,120
```

The probe holds each socket idle across the windows, then issues one short
post-idle turn to check for VAD/turn artifacts, and prints a verdict.

Record the verdict per provider. The verdict selects that provider's Tier B
strategy:

| Verdict | Meaning | Tier B strategy |
|---|---|---|
| `hold-floor-ok` | Socket survives idle; post-idle turn clean | Hold the provider session open during the background run (default) |
| `needs-keepalive` | Survives but post-idle turn degraded | Hold open + send a minimal keep-alive; revalidate the first post-idle turn |
| `must-reopen` | Socket closes/errors while idle | Detach the run but close+reopen (or resume) the provider socket on completion |

### Findings

Both verdicts are **`hold-floor-ok`** and Phase 0 is closed. The probe's original
worst case — a provider holding an *open response* idle for the whole run — does
not occur: the promotion path closes the pending call with an interim ack
(`broker.py:_begin_background_delivery`), so the socket only experiences the
normal between-turns idle gap. That gap is already exercised in production by
every Realtime Agent turn (the session stays open between the user finishing
speaking and the next `response.create`, across the resume TTL).

| Provider | Date | Basis | Windows | Post-idle turn | Verdict |
|---|---|---|---|---|---|
| OpenAI (`gpt-realtime-2`) | 2026-05-24 | empirical (probe) | 10s, 20s, 30s | audio returned, no error | **`hold-floor-ok`** |
| xAI (`grok-voice-latest`) | 2026-05-24 | existing production behavior + protocol parity | between-turn idle in daily use | clean (no idle-close reports) | ~~`hold-floor-ok`~~ (revised below) |
| xAI (`grok-voice-latest`) | 2026-07-08 | empirical (live relay event log) | 900s continuous silence | conversation closed server-side: "timed out after 900.0 seconds due to inactivity" | **`needs-keepalive`** |

**OpenAI — empirical.** Ran `realtime-provider-idle-probe.py --provider openai
--windows 10,20,30` against the live API. The session stayed open across all
three quiescent windows and produced clean audio on every post-idle
`response.create`. (Incidental observation, not an idle finding: the live API
emitted one `Missing required parameter: 'session.audio.output.format.rate'`
error at `session.update` time — a minor schema drift in
`providers/openai.py:_session_update` worth a follow-up; the session still
functioned and returned audio.)

**xAI — production behavior + parity.** No xAI key/OAuth store is present on the
dev box, so a fresh probe run is deferred to the relay host. The verdict is not
conditional, however: the *shipping* Realtime Agent already holds `xai_realtime`
sessions open across between-turn idle gaps with `turn_detection: None`
(relay-driven turns) and a resume TTL, and there are no reports of xAI closing or
degrading on those idle gaps. Background promotion does not lengthen the
*open-response* duration (the call is closed with an interim ack), so it does not
introduce a new idle condition beyond what xAI already tolerates today. Running
the probe on the relay host is retained as a **regression check**, not a
precondition. If it ever returns `needs-keepalive`/`must-reopen`, set that
provider's `realtime_voice` override accordingly; the per-provider setting
surface already supports it.

**Conclusion.** Both verdicts are `hold-floor-ok`, so `promotion_enabled`
defaults **on**. Phase 0's gate is satisfied; default-on is no longer blocked.

### Revision (2026-07-08) — xAI is `needs-keepalive` beyond ~900s

The regression case the 2026-05-24 xAI verdict reserved for has occurred. A
live relay event log showed a session dying while quiet: after a background-run
summary finished speaking, a complete gap of zero events for ~896s ended in
`voice.error` — "xAI Realtime error: Conversation timed out after 900.0
seconds due to inactivity". The 2026-05-24 verdict was scoped to *between-turn*
idle in normal use (tens of seconds); it never probed the 15-minute scale,
because "daily use" never leaves a session silent that long. The client's
manual turn-taking (`turn_detection: None`, mic streamed only while the user
talks) means nothing on the provider socket resets xAI's inactivity timer
during a background wait or an open-but-silent session.

**Fix (shipped in the broker).** `_provider_keepalive_loop` — a per-connection
task that appends ~100ms of silent PCM (`input_audio_buffer.append`, never
committed, never cleared) whenever the provider connection has seen no sends
or events for `RELAY_VOICE_PROVIDER_KEEPALIVE_MS` (default 240s ⇒ three pings
per 900s window; `0` disables). Append-only by design: a buffer `clear` could
race a user utterance that starts streaming between the check and the send,
while uncommitted silence merely prefixes the next real utterance by a sliver.
Belt-and-braces: an idle-close that still slips through is classified by
`_is_provider_idle_timeout` and surfaced as a human-readable "voice session
expired" instead of the raw provider error.

**Verification.** The probe now takes `--keepalive-ms`; on the relay host run
the repro (`--provider xai --windows 960`, expect idle-close at ~900s) and the
fix (`--provider xai --windows 960 --keepalive-ms 240000`, expect survival +
clean post-idle turn). Whether an uncommitted buffer append actually resets
xAI's inactivity timer is the one remaining empirical unknown — the probe run
confirms or refutes it before the keepalive is trusted; if appends do NOT
count as activity, the fallback is a periodic no-op protocol message or a
scheduled reopen, and this section gets a further revision.

OpenAI's tolerance at the 900s scale remains unprobed (2026-05-24 ran only
10/20/30s); the keepalive runs for both providers, so the question is moot
unless OpenAI objects to silent appends — no such behavior observed.
