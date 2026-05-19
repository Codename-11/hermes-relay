# Hermes-Relay Realtime Voice Provider Lab

## Purpose

This document tracks the realtime voice provider lab and the server-mediated
Hermes-Relay voice output path. The standalone lab still exists for provider
experimentation, realtime agent testing, streaming TTS testing, expression
testing, latency measurement, and provider comparison. The runtime now uses the
relay `/voice/output/*` route for deterministic Android assistant speech, with
realtime voice-agent providers kept as a separate test mode.

Current status: experimental standalone CLI plus experimental relay-owned voice
output and realtime voice routes used by Android primary voice mode, Voice
Settings, and the Developer options testbench. Android Voice Settings labels
the streaming Voice Output, Realtime Agent Lab, and Barge-in controls as
Experimental while provider quality, echo handling, latency, and barge-in
tuning are still being evaluated.

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
  observes Hermes tool-start events and brokers short spoken status lines such
  as "I'll search that now" while Hermes runs the actual tool and approval
  flow. Those status lines should go through the selected speech renderer, not
  through provider-owned tool calls. Provider adapters expose a non-executing
  tool-call scaffold for testing provider events only; Android does not execute
  provider-emitted tools locally.
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
  Target for speech-to-speech, provider turn-taking, provider tool-call event
  experiments, and comparison testing. This mode may reason conversationally.
  It must not execute Android or Hermes tools directly.

legacy_hermes_tts
  Compatibility fallback through `/voice/synthesize` and upstream Hermes TTS
  provider config.
```

Shared voice-output behavior belongs in a broker above individual providers:
verbatim rendering, waveform/meter data from actual PCM, first-audio latency,
chunk-gap metrics, barge-in cancellation, fallback selection, short
pre-tool-call speech, and long-task filler speech. Hermes remains the owner of
chat, tools, approvals, and final response text.

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

The primary Android voice overlay now uses the realtime route for assistant
speech by default. The Developer options screen remains available for isolated
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

Android `Settings > Voice` displays all three config surfaces: editable voice
output from `/voice/output/config`, fallback STT/TTS from `/voice/config`, and
editable experimental realtime-agent defaults from `/voice/realtime/config`
behind Developer options. Android can update safe profile-scoped or relay-owned
voice defaults, but it does not own provider secrets. The **Test Voice** button
plays the currently saved profile voice-output path, while normal assistant
speech prefers `/voice/output/*` streaming PCM when available.

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
against the Docker-server deployment target.

```powershell
# Local repo checks
python -m unittest plugin.tests.test_voice_output_routes plugin.tests.test_voice_lab -v
python -m unittest plugin.tests.test_voice_routes plugin.tests.test_realtime_voice_routes plugin.tests.test_voice_output_routes plugin.tests.test_voice_lab -v
python -m compileall plugin\relay plugin\voice_lab
.\gradlew.bat :app:compileSideloadDebugKotlin
.\gradlew.bat :app:installSideloadDebug

# Remote relay checks
ssh bailey@172.16.24.250 "cd ~/.hermes/hermes-relay && ~/.hermes/hermes-agent/venv/bin/python -m compileall plugin/relay plugin/voice_lab"
ssh bailey@172.16.24.250 "cd ~/.hermes/hermes-relay && ~/.hermes/hermes-agent/venv/bin/python -m unittest plugin.tests.test_voice_output_routes plugin.tests.test_voice_lab -v"
ssh bailey@172.16.24.250 "systemctl --user restart hermes-relay.service"
ssh bailey@172.16.24.250 "curl -fsS http://127.0.0.1:8767/health"
```

Live relay smoke used the configured server token without printing secrets:

```bash
set -a
. /home/bailey/.hermes/.env >/dev/null 2>&1 || true
set +a
cd /home/bailey/.hermes/hermes-relay
/home/bailey/.hermes/hermes-agent/venv/bin/python - <<'PY'
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
`voice.response.done`. The 2026-05-18 Docker-server smoke produced first audio
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
2. Open Hermes Relay sideload build `0.6.1-sideload`.
3. Confirm it reconnects to `ws://172.16.24.250:8767/ws`.
4. Start tap-to-talk and say: `check the relay status`.
5. Wait for the spoken status/tool narration and assistant reply.
6. Interrupt while it is speaking to verify barge-in cancellation/resume.

Capture the evidence:

```powershell
# Preferred: use the checked-in helper. It writes artifacts under the ignored
# voice-lab-runs/phone-smoke/<timestamp>/ directory.
.\scripts\voice-phone-smoke.ps1

# Or capture the same evidence manually:
adb logcat -d -v time |
  Select-String -Pattern "VoiceViewModel|RelayVoiceClient|voice/output|voice.response|voice.audio|RealtimePcmPlayer|BargeIn|transcribe|synthesize|auth.ok|sendMessage|tool|ConnectionManager" |
  Select-Object -Last 220

ssh bailey@172.16.24.250 'journalctl --user -u hermes-relay.service --since "10 minutes ago" --no-pager | grep -E "Client connected|Client disconnected|voice.output|voice/output|voice/realtime|voice/config|voice/transcribe|voice/synthesize|ERROR|Traceback" | tail -120'
```

Pass criteria:

- Android logs show `auth.ok` and a live relay connection.
- STT route is called and produces a non-empty transcript.
- Hermes chat/tool loop receives the transcript and returns the assistant turn.
- If a tool starts, the app enqueues a short spoken status line before the
  longer final answer.
- Assistant speech uses `/voice/output/*` and receives `voice.audio.delta`
  followed by `voice.response.done`.
- Barge-in logs show user speech interrupting active playback, without losing
  the new turn.
- If `/voice/output/*` fails before audio starts, Android falls back to
  `/voice/synthesize` and logs the fallback path.
