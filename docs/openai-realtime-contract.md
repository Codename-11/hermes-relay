# OpenAI Realtime Agent audio contract

The Plugin OpenAI provider uses the GA WebSocket session shape: `type: realtime`,
`output_modalities: [audio]`, nested `audio.input` and `audio.output`, and manual
turn detection. Both PCM formats explicitly carry `type: audio/pcm` and
`rate: 24000`. Other sample rates fail locally before a socket opens. The selected
model and voice pass through unchanged; the default model is `gpt-realtime-2.1`.

## Official documentation checked on 2026-09-28

- [Realtime conversations](https://developers.openai.com/api/docs/guides/realtime-conversations)
  explicitly supplies 24000 for both PCM directions and documents the GA audio,
  transcript, tool, cancellation, and response events used by this adapter.
- [Client event reference](https://developers.openai.com/api/reference/resources/realtime/client-events)
  limits PCM to 24 kHz, but its generated PCM schema still labels `rate` optional.
  That differs from the missing-required-parameter rejection reported in #644.
  Sending the explicit supported rate follows the guide and avoids depending on
  omission semantics; no API rollout date or universal new requirement is assumed.
- [Deprecations](https://developers.openai.com/api/docs/deprecations#2025-09-15-realtime-api-beta)
  records removal of `OpenAI-Beta: realtime=v1` on May 12, 2026. This adapter does
  not select a beta schema by model name. Legacy event aliases remain accepted.

Fake-WebSocket tests cover configured `gpt-realtime-2`, `gpt-realtime-2.1`,
`gpt-realtime`, and the default model, exact PCM payloads, acknowledgement,
schema errors, unsupported rates, voice selection, manual turns, cancellation,
transcripts and tool results. These tests establish local wire behavior, not
model availability or paid-account access. No provider credentials are needed.

The fix addresses session negotiation only. Standard Voice renderer failure and
legacy TTS playback gaps (#639) use a separate path and require separate evidence.
