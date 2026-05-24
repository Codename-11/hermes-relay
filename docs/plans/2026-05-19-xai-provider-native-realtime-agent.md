# Plan: xAI-First Provider-Native Realtime Agent

> **Purpose.** Complete the experimental Realtime Agent voice mode by making xAI/Grok Voice Agent the first provider-native speech-to-speech loop for Hermes Relay, using Victor's current `leo` voice path as the primary test target.
>
> **Decision.** Try xAI first because the current operator setup already uses xAI/SuperGrok auth and Victor is configured around the `leo` voice. OpenAI remains the second adapter target after the broker contract proves out.
>
> **Boundary.** Keep Hermes first-class for profile binding, session history, memory, tool execution, confirmations, Android bridge safety, cancellation, and transcript persistence. xAI owns realtime turn detection, speech recognition, speech generation, and provider-native function-call timing only inside a relay-brokered session.

## Research Notes

- xAI Voice Agent is a realtime WebSocket API at `wss://api.x.ai/v1/realtime`, with `model=grok-voice-latest` recommended for the best current voice experience.
- The voice session is configured with `session.update`; relevant fields include `voice`, `instructions`, `tools`, `turn_detection`, and 24 kHz PCM input/output audio format.
- Built-in xAI voices are `eve`, `ara`, `rex`, `sal`, and `leo`; custom voice IDs can also be passed as the `voice` value.
- xAI supports server VAD. With `turn_detection.type = "server_vad"`, the client can stream `input_audio_buffer.append` events and let the provider decide turn end; manual commit/clear should remain available as a fallback.
- xAI supports custom function tools. The provider emits `response.function_call_arguments.done`; the client executes the function, sends `conversation.item.create` with a `function_call_output`, then sends `response.create` to continue the response.
- xAI warns that tool-call follow-up audio can overlap previous audio if the client immediately sends `response.create`; our broker should wait until Android playback is complete or nearly complete before asking xAI for the post-tool spoken continuation.
- xAI auth supports server-side Bearer auth with an API key or OAuth bearer token, and ephemeral client secrets for browser/client-side WebSocket connections. For Android Relay, keep provider auth relay-side for now.

## Initial Local State

- The merged experimental route already exists at `/voice/realtime-agent/*`.
- Android already has a switchable `VoiceEngineMode.RealtimeAgent`.
- Before the provider-native pass, `RealtimeAgentHandler` was still a render-after-Hermes compatibility path:
  - Android streams PCM to relay, but `input_audio.commit` still requires client transcript text.
  - Relay sends that text to Hermes first through `HermesToolBroker`.
  - Relay then renders the final Hermes text through the selected provider adapter.
  - The current xAI relay adapter is only a skeleton over the render-text contract.
- `plugin/voice_lab/providers/xai_realtime.py` already proves the xAI WebSocket basics for text-to-voice lab runs, including auth resolution, `session.update`, `conversation.item.create`, `response.create`, and audio/transcript event parsing.
- `plugin/relay/realtime_agent/models.py` contains normalized event and tool dataclasses but is currently untracked in the working tree. Reconcile it into the implementation deliberately rather than deleting or duplicating those types.

## Implemented Status

- xAI/Grok Voice Agent is now the first provider-native Realtime Agent path.
- Android streams mic PCM to the relay; xAI owns realtime transcription and speech generation.
- Hermes remains the governed authority for profile/session state, tools, memory, confirmations, cancellation, and persistence through the brokered `hermes_*` tool surface.
- Hermes tool results are returned to xAI as compact function outputs so xAI can speak a natural summary instead of Android reading raw tool output aloud.
- Android shows clean default status, path badges, and confirmation controls; detailed raw Hermes deltas/tool previews are opt-in through Voice settings.

## Target Architecture

```text
Android mic PCM
  -> Relay /voice/realtime-agent websocket
  -> XAIRealtimeAgentConnection
  -> xAI Grok Voice Agent session
  -> provider transcript/audio/function-call events
  -> Relay RealtimeAgentBroker
  -> HermesToolBroker for approved Hermes tools
  -> Hermes API profile/session/tool loop
  -> xAI function_call_output and response.create
  -> Android live transcript, tool state, waveform, playback, chat timeline
```

The important change is that xAI should hear the user's audio and drive the realtime turn, not receive only final text after Android or Hermes already decided the turn.

## Provider Contract

Replace or extend the current render-only adapter with a connection-oriented contract:

```python
class RealtimeAgentProvider:
    id: str
    capabilities: RealtimeAgentCapabilities

    async def connect(config: RealtimeAgentSessionConfig) -> RealtimeAgentConnection: ...

class RealtimeAgentConnection:
    async def send_audio(pcm: bytes, sample_rate: int) -> None: ...
    async def commit_audio() -> None: ...
    async def clear_audio() -> None: ...
    async def cancel_response() -> None: ...
    async def send_tool_result(call_id: str, output: dict[str, Any]) -> None: ...
    async def request_response() -> None: ...
    async def close() -> None: ...
    async def events() -> AsyncIterator[ProviderEvent]: ...
```

Provider-specific event names stay inside adapters. Broker logic should only see normalized events such as:

- `provider.input_transcript.delta`
- `provider.input_transcript.final`
- `provider.response.started`
- `provider.response.text_delta`
- `provider.output_audio.delta`
- `provider.output_audio.done`
- `provider.function_call.completed`
- `provider.response.done`
- `provider.error`

## xAI Adapter Scope

Implement `XAIRealtimeAgentConnection` first.

- Connect to `wss://api.x.ai/v1/realtime?model=<model>`.
- Authenticate with relay-owned xAI OAuth/API key resolution already used by the lab and relay provider options.
- Send `session.update` with:
  - `voice`: profile-selected value, `leo` for Victor when configured.
  - `instructions`: Hermes-aware realtime instructions for the active profile, short enough to avoid replacing Hermes as the agent.
  - `audio.input.format`: `audio/pcm`, `rate: 24000`.
  - `audio.output.format`: `audio/pcm`, `rate: 24000`.
  - `turn_detection`: server VAD by default, with manual commit fallback.
  - `tools`: only the brokered Hermes tool schemas.
- Forward Android PCM as `input_audio_buffer.append`.
- Map Android stop/interruption to `response.cancel` and `input_audio_buffer.clear` where appropriate.
- Map xAI transcript events to `voice.input_transcript.delta/final`.
- Map xAI audio deltas to `voice.output_audio.delta` with peak/RMS levels for the waveform.
- Map xAI function calls to `HermesToolBroker`; do not execute raw Android or Hermes internals directly from provider arguments.
- For Hermes-required turns, keep the provider-native loop intact: the relay
  should request a normal provider response, the provider may speak one short
  pre-Hermes acknowledgement, and the provider should then call
  `hermes_run_task`. Do not replace this healthy path with relay-injected text
  prompts or local `/voice/output` TTS status speech.
- After a Hermes tool result, send `function_call_output`, then wait for Android playback-drained or broker-safe timing before `response.create`.

## Hermes Tool Loop

Keep the provider tool surface narrow:

- `hermes_run_task({ text, profile, session_id, mode })`
- `hermes_get_status({ run_id })`
- `hermes_cancel({ run_id })`
- `hermes_confirm({ confirmation_id, answer })`

Execution behavior:

- `hermes_run_task` creates or binds to the active Hermes chat session.
- The broker forwards the provider's final user transcript into Hermes with active profile and auth.
- Hermes tool/thinking/progress events mirror immediately to Android.
- The broker sends a compact tool result back to xAI, not the full raw internal event stream.
- Confirmations remain Android/Hermes owned; provider can narrate that a confirmation is needed, but the app UI must collect the answer.
- Cancellation must cancel xAI response generation and the active Hermes run when one exists.

## Android UX

Voice Settings should expose this as a mode under the existing experimental Realtime Agent area:

- Engine: `Hermes chat + voice output` or `Realtime Agent`.
- Realtime Agent provider loop: `xAI Grok Voice Agent` first; OpenAI Realtime is tracked in `docs/plans/2026-05-20-openai-provider-native-realtime-agent.md`.
- Badge: `Experimental` only on Realtime Agent and especially provider-native/barge-in controls.
- Profile detail: show selected profile, provider, model, voice, and config scope in compact subtext.
- For Victor, the settings should show `xai_realtime`, `grok-voice-latest`, `leo`, and the source of that setting when available.
- Provide an advanced manual entry area for model/voice IDs, but prefer dynamic provider options when the relay can query them.

Voice mode and overlay should:

- Render live input transcript deltas from xAI.
- Render provider speaking, Hermes thinking, Hermes tool-running, waiting-for-confirmation, and cancelled states without leaving voice mode.
- Keep waveform tied to actual input/output PCM levels.
- Allow stop/cancel while keeping auto/continuous mode enabled.
- Keep stable voice mode unchanged and easy to switch back to.

## Relay Work Items

1. Reconcile `plugin/relay/realtime_agent/models.py` into the tracked implementation or fold it into existing modules.
2. Add a connection-oriented provider interface beside the existing render-only adapter, preserving render-only as fallback if needed.
3. Implement `XAIRealtimeAgentConnection` with injectable WebSocket factory for tests.
4. Update `RealtimeAgentHandler.handle_ws` so it can run provider-native sessions without requiring commit text.
5. Add a broker event pump:
   - one task receives Android websocket messages and forwards audio/control to provider;
   - one task receives provider events and forwards normalized timeline/audio events to Android;
   - one task handles Hermes tool calls and sends function outputs back to provider.
6. Add playback-drain acknowledgement from Android to avoid xAI post-tool audio overlap.
7. Persist sanitized JSONL event logs without bearer tokens or raw audio payloads.
8. Preserve the current stable `/voice/output/*`, `/voice/realtime/*`, and render-after-Hermes paths.

## Android Work Items

1. Extend `RelayVoiceClient.runRealtimeAgent` with a provider-native mode that sends `input_audio_buffer` style audio events and does not require local transcript text.
2. Add parser support for new realtime-agent events, including input transcript deltas, provider response deltas, provider audio done, Hermes tool state, confirmation requests, playback-drain requests, and cancellation.
3. Send playback-drained acknowledgement when the queued xAI audio for a response has finished or is nearly finished.
4. Ensure profile and chat session ID are included when creating realtime-agent sessions.
5. Show the active profile/provider/model/voice in Voice Settings and voice overlay subtext.
6. Keep stable voice mode tests passing and do not require adb/device verification unless explicitly requested.

## Verification

Relay tests:

- Fake xAI websocket verifies `session.update` includes `voice=leo`, 24 kHz PCM audio config, server VAD, and only Hermes broker tool schemas.
- Audio append from Android is forwarded to xAI without requiring text.
- xAI transcript/audio events normalize to Android wire events.
- `response.function_call_arguments.done` executes the fake Hermes broker and returns `function_call_output`.
- Post-tool `response.create` waits for playback-drain or broker timeout.
- `response.cancel` cancels provider and active Hermes run.
- Auth missing/provider unavailable returns clear relay errors.

Android tests:

- Realtime Agent config parses provider/model/voice/profile metadata.
- Provider-native realtime events update voice state and chat/timeline state live.
- Playback-drain acknowledgement is sent after audio completion.
- Stop/cancel preserves auto mode selection while ending the current active session.

Commands:

- `python -m unittest plugin.tests.test_realtime_agent_routes`
- new focused Python realtime-agent xAI adapter tests
- `.\gradlew.bat :app:testSideloadDebugUnitTest --tests "*Voice*"`
- `.\gradlew.bat :app:compileSideloadDebugKotlin`

No adb/device tests unless Bailey asks.

## Risks and Open Questions

- xAI's provider-native loop can answer directly before Hermes has run. The broker instructions and tool schema must make Hermes the default route for work that needs memory, tools, bridge actions, or persistent agent context.
- xAI server-side web search/X search/MCP should remain disabled for this mode unless explicitly exposed through Hermes policy; otherwise it bypasses Hermes tool governance.
- We need to verify whether Hermes API can accept provider-generated transcript turns cleanly. If it cannot, the relay should persist a mirrored transcript and submit a final turn/summary to Hermes after each provider-native turn.
- Server-mediated WebSocket protects auth and tool governance but may add latency compared with a future direct Android ephemeral-token path.
- Barge-in and acoustic echo cancellation remain experimental until real device testing proves the phone is not feeding speaker output back into the microphone.

## References

- xAI Voice Agent guide: `https://docs.x.ai/developers/model-capabilities/audio/voice-agent`
- xAI Voice Agent model/pricing page: `https://docs.x.ai/developers/models/voice-agent-api`
- xAI Voice REST/WebSocket reference: `https://docs.x.ai/developers/rest-api-reference/inference/voice`
- Existing local plan: `docs/plans/2026-05-19-realtime-hermes-voice-agent.md`
- Existing lab adapter: `plugin/voice_lab/providers/xai_realtime.py`
