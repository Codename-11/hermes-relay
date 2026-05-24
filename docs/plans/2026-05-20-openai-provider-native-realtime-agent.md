# Plan: OpenAI Provider-Native Realtime Agent

> **Purpose.** Add first-class OpenAI Realtime support to Hermes Relay's Realtime Agent mode, matching the xAI provider-native path while preserving Hermes as the governed tool, memory, profile, confirmation, and persistence authority.
>
> **Goal.** Implement OpenAI Realtime as a true provider-native Realtime Agent provider, not a render-after-Hermes fallback: Android streams mic PCM to the relay, the relay opens a server-side OpenAI Realtime session, OpenAI handles realtime speech recognition and speech generation, Hermes remains the only path for tools, memory, research, current data, side effects, confirmations, and durable context, and the provider speaks concise post-Hermes summaries through the same normalized broker contract used by xAI.
>
> **Boundary.** This plan is for `/voice/realtime-agent/*`. The existing `/voice/realtime/*` lab route, `/voice/output/*` stable renderer, and render-only OpenAI voice-lab adapter should keep working unchanged.

## Implementation Status

- Implemented locally on 2026-05-20.
- `openai_realtime` now has a provider-native Realtime Agent adapter registered alongside `xai_realtime`.
- Android continues to send mic PCM plus `input_audio.commit` without transcript text.
- The relay now reports `supports_realtime_agent_native` so lab/render-only realtime providers are not treated as full Realtime Agent providers.
- Focused adapter, broker route, xAI regression, realtime voice route, and voice output route tests passed locally.

## Starting State

- `openai_realtime` is present in the voice-lab registry and can render text to realtime audio.
- Realtime Agent provider options can list and validate `openai_realtime` because the voice-lab provider reports `supports_realtime=True`.
- The Realtime Agent native broker only registers xAI in `RealtimeAgentHandler.native_providers`.
- `plugin/relay/realtime_agent/providers/openai.py` currently contains only a thin render adapter over `RealtimeAgentProviderAdapter`.
- Android's Realtime Agent client sends mic PCM plus `input_audio.commit` and does not send a transcript.
- Non-native render-after-Hermes compatibility mode requires transcript text, so selecting `openai_realtime` for Realtime Agent can look selectable but cannot provide the intended native speech-to-speech Hermes tool loop.

## Desired Behavior

OpenAI Realtime should behave like xAI's provider-native path from the relay broker's point of view:

```text
Android mic PCM
  -> Relay /voice/realtime-agent websocket
  -> OpenAIRealtimeAgentConnection
  -> OpenAI Realtime session
  -> normalized transcript/audio/function-call events
  -> Relay RealtimeAgentHandler
  -> HermesToolBroker for approved Hermes tools
  -> compact function result back to OpenAI
  -> provider follow-up audio/text
  -> Android playback, status, trace, and chat timeline
```

The provider-specific wire protocol should be hidden inside the OpenAI adapter. Broker logic should continue to consume only `ProviderEvent` and `ToolCallEvent`.

## Non-Goals

- Do not expose raw Android, desktop, shell, web, or Hermes internals directly to OpenAI.
- Do not store OpenAI provider secrets on Android.
- Do not replace stable `Hermes chat + voice output`.
- Do not make OpenAI the default provider until the native path is verified.
- Do not rework Android voice UX unless capability metadata requires small labels or guardrails.

## Implementation Plan

### 1. Split Capability Semantics

Current `supports_realtime` means "can be used by the realtime lab/render surfaces." Realtime Agent needs a stricter concept.

- Add a relay-side capability such as `supports_realtime_agent_native`.
- Use it for `/voice/realtime-agent/config`, provider options, validation, and Android selection labels.
- Prevent `openai_realtime` from being presented as a fully native Realtime Agent provider until the OpenAI native adapter is registered.
- Keep `supports_realtime` for `/voice/realtime/*` lab compatibility.

Files:

- `plugin/relay/realtime_agent/broker.py`
- `plugin/relay/realtime_agent/providers/base.py`
- `plugin/relay/provider_options.py`
- `app/src/main/kotlin/com/hermesandroid/relay/network/RelayVoiceClient.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/VoiceSettingsScreen.kt`

### 2. Build `OpenAIRealtimeAgentProvider`

Extend `plugin/relay/realtime_agent/providers/openai.py` with a native provider implementation:

- `OpenAIRealtimeAgentProvider.provider_id = "openai_realtime"`
- `connect(config: RealtimeAgentSessionConfig) -> OpenAIRealtimeAgentConnection`
- server-side OpenAI auth resolution from provider options, env, or existing relay config patterns
- injectable async WebSocket factory for tests
- `configure()` sends OpenAI `session.update` with:
  - selected model
  - selected voice
  - Hermes-aware realtime instructions
  - PCM input and output format at the session sample rate
  - manual turn control compatible with Android `input_audio.commit`
  - only the approved Hermes tool schemas

The adapter must implement the shared connection contract:

- `send_audio(pcm, sample_rate)`
- `commit_audio()`
- `clear_audio()`
- `cancel_response()`
- `send_tool_result(call_id, output)`
- `request_response()`
- `close()`
- `events()`

### 2a. Future: Pluggable WebRTC Transport for OpenAI

The implemented OpenAI native path uses relay-owned WebSocket transport because
it fits the existing xAI-normalized broker and keeps provider secrets
server-side. Add a follow-up transport abstraction so OpenAI Realtime can use
WebRTC from Android when mobile audio quality needs provider-native jitter
buffering, interruption, and media handling.

Design target:

- keep the existing normalized Realtime Agent broker/tool contract
- let a provider advertise supported transports such as `websocket`, `webrtc`,
  and future bridge transports
- use OpenAI WebRTC as the first implementation target
- keep relay-side session minting, instructions, profile context, and Hermes
  tool brokering
- keep Hermes as the only authority for tools, memory, confirmations, current
  data, side effects, and durable transcript state
- avoid hard-coding OpenAI WebRTC details into Android voice UX or broker core

This is tracked in `TODO.md` under **Pluggable Realtime Agent media
transports**.

### 3. Map OpenAI Wire Events to Normalized Events

Normalize provider events to the existing broker contract:

- input transcript delta/final -> `ProviderEventKind.INPUT_TRANSCRIPT_DELTA/FINAL`
- response start -> `ProviderEventKind.RESPONSE_STARTED`
- output text deltas -> `ProviderEventKind.OUTPUT_TEXT_DELTA`
- audio deltas/done -> `ProviderEventKind.AUDIO_DELTA/AUDIO_DONE`
- function call completed -> `ProviderEventKind.FUNCTION_CALL_COMPLETED`
- response done -> `ProviderEventKind.RESPONSE_DONE`
- provider error -> `ProviderEventKind.ERROR`

The adapter should tolerate the OpenAI event variants already proven in `plugin/voice_lab/providers/openai_realtime.py`, but should not leak OpenAI event names into the broker.

### 4. Convert Hermes Tools for OpenAI

OpenAI tool schema formatting may differ from xAI. Add OpenAI-specific conversion inside `providers/openai.py`.

Requirements:

- expose only `hermes_run_task`, `hermes_get_status`, `hermes_cancel`, and `hermes_confirm`
- preserve existing tool descriptions and speech-safe/routing policy language
- pass compact JSON function results back to OpenAI
- request the provider follow-up response only after Android playback has drained or the broker timeout expires
- keep intermediate `response.done` suppression behavior working for OpenAI tool-call responses

### 5. Register Native OpenAI Provider

Wire OpenAI into the native path after the adapter has tests:

- import `OpenAIRealtimeAgentProvider`
- add it to `RealtimeAgentHandler.native_providers`
- ensure `adapter_for("openai_realtime")` still works for render-after-Hermes compatibility where needed
- make provider options report native-agent capability only once registration succeeds

### 6. Auth and Configuration

Keep provider credentials relay-side.

Auth resolution should support, in order:

- explicit provider options passed by relay config/profile config
- existing OpenAI env names already used by voice-lab, such as `OPENAI_API_KEY` or `VOICE_TOOLS_OPENAI_KEY`
- optional provider-specific websocket URL override for testing

Errors must be clear and user-facing:

- missing key
- rejected key
- unsupported model/voice
- websocket connect failure
- provider event parse failure

### 7. Android and UI Guardrails

Android should not need a protocol change if OpenAI uses the same normalized server events.

Small UI/data changes may be needed:

- display whether a realtime provider is native-agent capable or render/lab-only
- prevent saving a non-native provider for Realtime Agent unless an explicit advanced override is allowed
- keep path badges provider-neutral: `Realtime Agent`, `Hermes`, `Tool`, and provider badge such as `OpenAI marin`

### 8. Documentation

Update docs after implementation, not before:

- `docs/relay-protocol.md`
- `docs/relay-server.md`
- `docs/spec.md`
- `user-docs/features/voice.md`
- `docs/plans/2026-05-19-xai-provider-native-realtime-agent.md` if it still says OpenAI is only future work

Docs should distinguish:

- OpenAI Realtime lab/render support
- OpenAI Realtime Agent native Hermes-brokered support
- stable Hermes chat + voice output

## Test Plan

Add `plugin/tests/test_realtime_agent_openai_provider.py`.

Adapter tests:

- `connect` sends `session.update` with model, voice, PCM format, instructions, and only Hermes tools.
- audio append resamples/sends PCM in OpenAI's required event shape.
- `commit_audio` sends the correct commit and response request events.
- provider transcript/audio events normalize to shared `ProviderEvent` kinds.
- OpenAI function-call events normalize to `ToolCallEvent`.
- `send_tool_result` sends compact function output in OpenAI's expected shape.
- `request_response` sends the provider follow-up event.
- `cancel_response` and `clear_audio` map to OpenAI events.
- provider errors become `ProviderEventKind.ERROR`.

Route/broker tests:

- `openai_realtime` native session accepts Android PCM without transcript text.
- OpenAI function call runs fake Hermes broker through `hermes_run_task`.
- post-tool response waits for playback drain.
- intermediate tool-call `response.done` does not close Android before provider follow-up.
- cancellation cancels provider and active Hermes run.
- missing OpenAI auth returns a clear `voice.error`.

Regression tests:

- existing xAI realtime-agent tests still pass.
- `/voice/realtime/*` OpenAI lab tests still pass.
- stable `/voice/output/*` tests still pass.
- Android compile passes if metadata/UI changes are needed.

Commands:

```powershell
python -m unittest plugin.tests.test_realtime_agent_routes plugin.tests.test_realtime_agent_xai_provider plugin.tests.test_realtime_agent_openai_provider
python -m unittest plugin.tests.test_realtime_voice_routes plugin.tests.test_voice_output_routes
.\gradlew.bat :app:compileSideloadDebugKotlin
```

## Rollout Plan

1. Land capability guard first if OpenAI remains selectable before native support is ready.
2. Implement the OpenAI provider behind tests.
3. Register OpenAI as native only after adapter and broker tests pass.
4. Deploy relay-only files to `bailey@docker-server.local`.
5. Restart `hermes-relay.service`.
6. Verify `/health`.
7. Test Android Realtime Agent with `openai_realtime`, including:
   - simple direct answer
   - "what path are we using?"
   - current date/time
   - Hermes query/tool request
   - cancellation
   - long/dense output speech summary

## Acceptance Criteria

- `openai_realtime` can be selected for Realtime Agent without hitting render-after-Hermes transcript errors.
- Android sends mic PCM only; the relay/OpenAI path handles transcription.
- OpenAI can call the same brokered Hermes tool surface as xAI.
- Hermes remains the only path for tools, current data, memory, side effects, and confirmations.
- Provider output uses the existing timeline, status, badges, trace, playback drain, and confirmation UX.
- xAI behavior remains unchanged.
- OpenAI provider secrets remain server-side.

## Risks and Open Questions

- OpenAI's current Realtime event names and function-call event shape may differ from the voice-lab text-render adapter. The native adapter must be tested against captured fake events and then a live smoke test.
- OpenAI may require a different session field shape for input audio transcription, output audio format, or tool schemas than xAI. Keep those differences adapter-local.
- Follow-up audio after function output may have different `response.done` timing than xAI. Reuse the response-id suppression pattern but verify with OpenAI events.
- If OpenAI supports provider-native web/search tools in the selected model, ensure instructions and exposed tool schemas prevent bypassing Hermes governance.
- If provider-side transcription is unavailable or disabled for a model, the UI must show a clear validation failure rather than falling back silently.

## References

- `docs/plans/2026-05-19-realtime-hermes-voice-agent.md`
- `docs/plans/2026-05-19-xai-provider-native-realtime-agent.md`
- `plugin/relay/realtime_agent/providers/base.py`
- `plugin/relay/realtime_agent/providers/xai.py`
- `plugin/relay/realtime_agent/providers/openai.py`
- `plugin/voice_lab/providers/openai_realtime.py`
- `plugin/tests/test_realtime_agent_xai_provider.py`
