# Plan: Realtime Hermes Voice Agent Mode

> **Purpose.** Add an experimental realtime speech-to-speech voice mode that feels like a native realtime assistant while keeping Hermes as the authority for profiles, sessions, memory, tools, transcript history, Android bridge safety, and confirmations.
>
> **Decision.** Realtime providers are voice-session engines, not replacement agents. The relay brokers provider audio/tool events into the existing Hermes session/tool loop. Hermes remains the system of record.
>
> **Stable default.** `Hermes chat + voice output` remains the default and continues to use normal Hermes chat streaming plus relay-managed voice output rendering.
>
> **Experimental mode.** `Realtime Agent` is opt-in, visibly badged as experimental in Android Voice Settings, and can be switched off without changing existing voice behavior.

## Current State

- Android stable voice mode lives mostly in:
  - `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/VoiceViewModel.kt`
  - `app/src/main/kotlin/com/hermesandroid/relay/ui/components/VoiceModeOverlay.kt`
  - `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/VoiceSettingsScreen.kt`
  - `app/src/main/kotlin/com/hermesandroid/relay/data/VoicePreferences.kt`
  - `app/src/main/kotlin/com/hermesandroid/relay/network/RelayVoiceClient.kt`
- Chat/profile/session authority currently lives in:
  - `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ChatViewModel.kt`
  - `app/src/main/kotlin/com/hermesandroid/relay/network/HermesApiClient.kt`
  - `app/src/main/kotlin/com/hermesandroid/relay/data/ProfileSelectionStore.kt`
  - `app/src/main/kotlin/com/hermesandroid/relay/data/ProfileSessionStore.kt`
- Relay voice surfaces already exist:
  - `plugin/relay/voice.py` for STT/TTS fallback.
  - `plugin/relay/voice_output.py` for provider-neutral streaming speech rendering.
  - `plugin/relay/realtime_voice.py` for the current realtime provider playground route.
  - `plugin/relay/profile_voice.py`, `plugin/relay/provider_options.py`, and `plugin/relay/config.py` for relay-owned per-profile voice settings.
- The current `/voice/realtime/*` surface is a provider playground/test route. It can render a realtime provider response and has a scaffold tool event, but it does not yet own a Hermes session, stream Hermes tool events, or mirror transcripts into chat history.
- Provider lab adapters already exist under `plugin/voice_lab/providers/` for OpenAI/xAI realtime experiments. They are useful references, but the production broker should have its own provider adapter contract so Android and relay routes do not depend on lab-only assumptions.

## Target Architecture

Flow:

```text
Android Voice UI
  -> Relay RealtimeAgentBroker
  -> OpenAI/xAI realtime provider session
  -> provider tool call
  -> Relay HermesToolBroker
  -> Hermes API session/profile/tool loop
  -> Relay HermesToolBroker result/status
  -> provider tool result/context
  -> provider audio delta
  -> Android playback and mirrored chat/timeline events
```

### Core rule

Do not expose raw Hermes or Android tools directly to the realtime provider. Expose a narrow brokered tool surface:

- `hermes_run_task({ text, profile, session_id, mode })`
- `hermes_get_status({ run_id })`
- `hermes_cancel({ run_id })`
- `hermes_confirm({ confirmation_id, answer })`

The provider may ask for work, cancellation, status, or confirmation, but Hermes decides which real tools run and how safety prompts are handled.

## Non-Goals

- Do not remove or degrade the stable `Hermes chat + voice output` mode.
- Do not make OpenAI/xAI own Hermes memory, profile state, Android bridge permissions, or final transcript persistence.
- Do not route provider function calls straight to Android bridge tools.
- Do not require adb/device testing for implementation verification unless Bailey explicitly asks.
- Do not depend on Hermes upstream changes unless the executor first proves they are already available in the installed Hermes API server.

## Protocol Surfaces

### Relay HTTP

Add a new experimental surface instead of overloading the existing provider playground:

- `GET /voice/realtime-agent/config`
- `PATCH /voice/realtime-agent/config`
- `GET /voice/realtime-agent/providers/{provider_id}/options`
- `POST /voice/realtime-agent/session`
- `GET /voice/realtime-agent/{session_id}`

Keep `/voice/realtime/*` available for the current lab/testbench until the new mode proves itself.

### Android-to-relay websocket events

Client to relay:

- `session.start`
- `input_audio.append`
- `input_audio.commit`
- `response.cancel`
- `hermes.confirm`
- `session.close`

Relay to client:

- `voice.session.ready`
- `voice.input_transcript.delta`
- `voice.input_transcript.final`
- `voice.output_audio.delta`
- `voice.output_audio.done`
- `voice.response.started`
- `voice.response.delta`
- `voice.response.done`
- `hermes.run.started`
- `hermes.tool.started`
- `hermes.tool.delta`
- `hermes.tool.completed`
- `hermes.confirmation.requested`
- `hermes.run.completed`
- `voice.error`

Android should render these through the same chat/timeline components used by stable voice mode where possible.

## Session and Auth Binding

Each realtime-agent session must bind to:

- active connection/API base
- active Hermes profile or server default
- active Hermes chat session id
- voice mode engine
- provider/model/voice/sample-rate
- auth principal

Preferred auth:

1. If the client presents a Hermes API bearer token, validate it through the existing voice auth path and use it when proxying Hermes API session calls.
2. If the client presents only a Relay session token, the broker may run only when the relay has a configured server-side Hermes API credential for session writes. Otherwise the config endpoint should report `available=false` with a clear reason.

Do not log bearer tokens. Use short hashes only if diagnostics need identity.

## Provider Adapter Contract

Add a relay-side production interface, for example `plugin/relay/realtime_agent/providers/base.py`:

```python
class RealtimeAgentProvider:
    id: str
    capabilities: RealtimeAgentCapabilities

    async def connect(self, session_config: RealtimeAgentSessionConfig) -> RealtimeAgentConnection: ...

class RealtimeAgentConnection:
    async def send_audio(self, pcm: bytes, sample_rate: int) -> None: ...
    async def commit_audio(self) -> None: ...
    async def send_tool_result(self, call_id: str, payload: dict[str, Any]) -> None: ...
    async def cancel_response(self) -> None: ...
    async def close(self) -> None: ...
    async def events(self) -> AsyncIterator[RealtimeProviderEvent]: ...
```

Normalize provider event types before they reach broker logic. Provider-specific OpenAI/xAI differences must be isolated in adapters.

### OpenAI adapter

OpenAI is the first implementation target because the Realtime API supports speech-to-speech sessions and function calling. Prefer relay-owned server websocket for MVP. A later optimization can use Android WebRTC plus relay sideband control once the server broker is stable.

### xAI adapter

xAI is second. Keep it xAI-ready from day one by using the normalized adapter contract and by avoiding OpenAI-specific event names outside the adapter.

## Android UX

### Voice Settings

Add a clean `Voice engine` control near the top of `Settings -> Voice`:

- `Hermes chat + voice output` - stable default.
- `Realtime Agent` - experimental badge and short subtext.

Copy:

- Stable subtext: "Hermes handles chat, tools, memory, and voice rendering."
- Experimental subtext: "Provider-native realtime speech with Hermes-brokered tools."

When `Realtime Agent` is selected:

- Show provider/model/voice controls using the existing dynamic provider option pattern.
- Show an `Experimental` badge.
- Show a concise limitation note: "Hermes still owns tools and confirmations. Realtime mode may fall back to stable voice if the provider disconnects."
- Preserve per-profile settings so Victor/Mizuki/etc. can choose different realtime provider voices.

### Voice Overlay

The overlay should not fork into a separate UI. It should reuse existing voice state and timeline rendering, but add realtime-agent states:

- Listening
- Provider thinking
- Hermes using tools
- Speaking
- Waiting for confirmation
- Interrupted/cancelled

Tool calls must appear live without leaving voice mode.

## Implementation Waves

### Wave 0 - Local Audit

- Re-read current route wiring in `plugin/relay/server.py`.
- Re-read `plugin/relay/realtime_voice.py` and decide what can be reused versus what should remain lab-only.
- Re-read Android `VoiceSettingsScreen`, `VoiceSettingsViewModel`, `VoicePreferences`, `RelayVoiceClient`, `VoiceViewModel`, and `ChatViewModel`.
- Confirm how active profile and active chat session id are available at voice session start.
- Confirm whether the Android client has a Hermes API bearer token available for relay brokered session writes.

### Wave 1 - Relay broker foundation

- Add `plugin/relay/realtime_agent/` package.
- Add dataclasses for session config, capabilities, normalized events, tool calls, transcript events, and audio events.
- Add `RealtimeAgentBroker` session registry.
- Add route handler and route registration under `/voice/realtime-agent/*`.
- Reuse existing voice auth and profile voice config patterns.
- Add tests for config payloads, disabled state, session creation, auth failures, and route registration.

### Wave 2 - Hermes tool/session broker

- Add `HermesToolBroker` that can:
  - create or bind to a Hermes chat session
  - send user transcript text into that session
  - stream Hermes assistant/tool events back to `RealtimeAgentBroker`
  - cancel an active Hermes run
  - pass confirmation answers into the existing Android bridge safety flow where applicable
- Ensure all calls include active profile/session binding.
- Add tests for event mapping, cancellation, tool-call forwarding, profile propagation, and transcript mirroring.

### Wave 3 - OpenAI realtime provider adapter

- Implement OpenAI adapter behind the normalized provider contract.
- Register broker tool schemas for the small Hermes tool surface only.
- Convert OpenAI audio, transcript, response, and function-call events to normalized broker events.
- Send Hermes broker tool results back as provider tool results.
- Add stubbed provider tests that do not require live OpenAI credentials.

### Wave 4 - Android settings and client wiring

- Add `VoiceEngineMode` to `VoicePreferences`.
- Add `Realtime Agent` mode selector and experimental badge in `VoiceSettingsScreen`.
- Extend `RelayVoiceClient` with `/voice/realtime-agent/*` calls and websocket event parsing.
- Wire selected engine mode into `VoiceViewModel` without changing stable mode defaults.
- Preserve active profile/session id in session creation payload.
- Add focused Kotlin tests for preference serialization, config parsing, and event parsing.

### Wave 5 - Voice overlay and transcript/timeline parity

- Render realtime-agent provider/Hermes events in the existing voice overlay and chat/timeline surfaces.
- Ensure tool calls, confirmation requests, partial transcripts, and final Hermes results are visible without exiting voice mode.
- Ensure interrupt/cancel stops provider response and cancels/pauses the Hermes run according to the selected user action.
- Add UI/state tests where current test seams allow; otherwise keep compile as the gate and document manual validation steps.

### Wave 6 - xAI-ready boundary

- Add xAI adapter skeleton or full adapter if provider docs/runtime are clear enough during implementation.
- Keep xAI disabled unless auth/config is present.
- Add provider option handling and config validation parity with OpenAI.
- Add stub tests for event normalization and tool-call mapping.

### Wave 7 - Documentation and operator guidance

- Update `user-docs/features/voice.md`.
- Update `TODO.md` to mark the realtime-agent TODO as planned/linked.
- Add troubleshooting for auth/session binding, provider disconnect fallback, and why tools still run through Hermes.
- Add DEVLOG entry after implementation lands.

## Acceptance Checklist

- Stable voice mode remains the default and its current tests still pass.
- `Realtime Agent` appears in Android Voice Settings with an experimental badge and can be switched back to stable mode cleanly.
- Relay exposes `/voice/realtime-agent/*` without breaking `/voice/realtime/*` lab routes or `/voice/output/*`.
- Realtime-agent session creation requires valid voice/realtime auth and binds profile plus chat session id.
- Provider adapters cannot call raw Android/Hermes tools directly.
- Hermes broker tool surface is limited to `hermes_run_task`, `hermes_get_status`, `hermes_cancel`, and `hermes_confirm` unless the plan is explicitly amended.
- Hermes session transcript is maintained: user transcript, Hermes tool events, and final assistant result are represented in the same active session.
- Voice overlay shows live transcript, Hermes thinking/tool state, confirmation requests, and final response without exit/reload.
- Interrupt/cancel cancels provider speech and has deterministic behavior for the active Hermes run.
- Provider disconnect falls back or exits with a clear error without corrupting the Hermes session.
- Unit tests cover broker route/config/auth/session behavior, provider event normalization, Hermes tool broker event mapping, Android preference/config parsing, and stable mode regression surfaces.
- Verification runs focused Python tests, focused Android unit tests, and `:app:compileSideloadDebugKotlin`. No adb/device tests unless Bailey asks.

## References

- OpenAI Realtime API: speech-to-speech sessions, function calling, WebRTC, and server-side/sideband control patterns.
- xAI Voice Agent API: realtime websocket voice sessions, custom tools/function calls, and xAI-specific event behavior.
- Existing local provider lab: `plugin/voice_lab/providers/openai_realtime.py` and `plugin/voice_lab/providers/xai_realtime.py`.
- Existing stable relay voice output: `plugin/relay/voice_output.py`.
- Existing realtime lab relay route: `plugin/relay/realtime_voice.py`.
