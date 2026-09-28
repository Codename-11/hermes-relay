# OpenAI Realtime Agent contract

Checked against official OpenAI documentation on 2026-09-28 for issue #644.
This concerns the Plugin's provider-native Realtime Agent WebSocket adapter.
Standard Dashboard/Gateway voice has a separate upstream-owned transport.

## Session configuration

The adapter uses the GA `session.update` shape: `session.type = realtime`,
`output_modalities = [audio]`, and nested `audio.input` / `audio.output`.
Both format objects explicitly send `{"type": "audio/pcm", "rate": 24000}`.
The adapter accepts only 24 kHz session audio and advertises PCM16; it does not
offer a codec selector or negotiate G.711. Unsupported session rates fail before
opening a socket. Microphone PCM resampling remains separate from this session
format constraint.

The [conversation guide](https://developers.openai.com/api/docs/guides/realtime-conversations)
includes the rate on both PCM formats. The generated
[client-event reference](https://developers.openai.com/api/reference/resources/realtime/client-events)
currently labels the PCM rate optional but limits its value to `24000`.
Issue #644 reports the service rejecting an omitted output rate. Sending the
explicit validated rate satisfies both documented shapes and the reported
service requirement. These sources do not establish when enforcement changed.

The same reference lists `gpt-realtime-2`, `gpt-realtime-2.1`,
`gpt-realtime-2.1-mini`, `gpt-realtime`, `gpt-realtime-1.5`, and
`gpt-realtime-mini` under this shared session schema. Model availability remains
account-dependent. The Plugin's selectable defaults remain 2.1, 2.1-mini, and 2.
The official [2](https://developers.openai.com/api/docs/models/gpt-realtime-2),
[2.1](https://developers.openai.com/api/docs/models/gpt-realtime-2.1), and
[2.1 Mini](https://developers.openai.com/api/docs/models/gpt-realtime-2.1-mini)
pages describe reasoning and capability differences, without a separate PCM
session format. No model-specific rate fallback is applied.

## Sibling contract audit

- Voice remains under `audio.output.voice`; the selected voice is preserved.
- `audio.input.turn_detection = null` keeps manual turn ownership. Transcription
  remains `gpt-realtime-whisper` by default, with its unsupported prompt omitted;
  existing transcription overrides and disable behavior are preserved.
- Only the four brokered Hermes functions are exposed. Reasoning and parallel
  tool settings are optional, so this fix introduces no model-specific fields.
- GA `response.output_audio.delta`, transcript, function-call, and response events
  are already normalized. Legacy audio/text event aliases remain accepted.
  The [GA migration guide](https://developers.openai.com/api/docs/guides/realtime#beta-to-ga-migration)
  calls for the nested session fields and newer event names; the adapter does
  not add the old `OpenAI-Beta: realtime=v1` header.
- A rejected update arrives as an `error` event; the adapter preserves its
  human-readable message and the broker surfaces it as a voice error. A
  `session.created` event describes initial defaults; only `session.updated`
  acknowledges the configuration update. See the
  [server-event reference](https://developers.openai.com/api/reference/resources/realtime/server-events).
- xAI already includes both rates in its own session shape. The OpenAI
  render-only adapter already includes the output rate in both `session.update`
  and `response.create`. Neither implementation needs the missing-field fix.

No additional required-field mismatch was found in the native session payload.
This audit does not certify every optional model feature or live event sequence.

## Verification scope

`plugin/tests/test_realtime_agent_openai_provider.py` checks the serialized
session fields and uses an injected fake socket that rejects a missing rate
and acknowledges explicit 24 kHz PCM with `session.updated`. Removing the
production fix makes negotiation fail. Separate coverage verifies rejection
messages and refusal of unsupported session rates before connection.

These tests use dummy credentials and make no provider calls. They prove the
local payload and event handling, not account access, live negotiation, audio
quality, or physical-device interruption behavior. The reporter's successful
local patch remains the live-provider evidence attached to #644.
