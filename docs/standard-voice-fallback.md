# Standard Voice fallback and pause diagnosis

Standard Voice sends conversation work to Hermes. On a Standard upstream audio
route it uses the Dashboard speech stream and upstream synthesis fallback. On an
explicitly resolved Hermes-Relay audio route it can use `/voice/output/*`; a
renderer failure before audio starts sends that chunk to legacy file synthesis.
The latter is the path reported in [#639](https://github.com/Codename-11/hermes-relay/issues/639).
It is separate from OpenAI Realtime Agent session negotiation in #644.

## Recovery behavior

When streaming voice fails, Android warns that basic speech is active and may
pause between sentences. Check the configured voice provider settings. Provider
credits, permissions, or availability can prevent streaming even while Hermes
chat continues working. A Voice diagnostic records `legacy_hermes_tts`, whether
audio had started, and whether the failed chunk is replayed. It does not copy the
provider's raw error, account details, or credentials into the notice.

Queued chunks and the coalescer's short tail move to legacy TTS in order. Later
chunks use legacy directly. A partially spoken chunk is not replayed, avoiding
duplicate speech. Buffered PCM from a failed stream is stopped before queued
fallback files start, so the two players cannot overlap during that transition.
Cancellation and superseded voice owners cannot publish the
failure notice or select fallback for a new owner. Entering voice mode again
re-probes streaming availability; fallback does not change server settings.

The server advertises `fallback_enabled` and `fallback_provider`, but Android's
existing playback fallback decision is local. This change preserves that policy.
Disabled/unavailable streaming configuration keeps its existing informational
diagnostic; a failed active attempt gets the warning.

## Reading Stats for Nerds

The Voice section separates these measurements:

| Row | Meaning |
| --- | --- |
| Avg latency | Last five synthesis/render durations, not player silence |
| Response chunks | Completed synthesis/render chunks in the current response |
| Synthesis request gap | Previous render completion to next render request start; formerly labeled Last chunk gap |
| Audio queue wait | Previous file drain to receipt of the next synthesized file; absent for the first chunk |
| Player start delay | File submission to Media3 reporting `isPlaying`; updated after that file drains |

All legacy playback durations use monotonic clocks. These are application events,
not an acoustic recording: codec padding, silence inside a synthesized file,
output-device buffering, and Bluetooth latency need device evidence. Queue wait
also includes time waiting for more model text; it alone cannot prove a slow TTS
provider. Correlate it with synthesis latency and response chunk sizes.

## Reproducible separation of the two costs

`LegacyTtsTimingTest` drives the production synth/play workers with virtual time:

- 100 ms synthesis, 1000 ms speech, and an injected 50 ms player startup: the
  next file is ready, queue wait is 0 ms, and the simulated seam is 50 ms.
- 500 ms synthesis, 100 ms speech, and the same 50 ms startup: queue wait is
  350 ms and the simulated seam is 400 ms.

Those numbers describe controlled fakes, not the reporter's device. VoicePlayer
tests separately verify submission-to-playing measurement and cancellation reset.
Pipeline, sentence extraction, barge-in, session fences, and capture tests retain
their independent responsibilities.

The file queue remains bounded at two waiting files. The current play worker
waits for each file to drain before handing over another; a ready file therefore
still requires a player restart. Raising capacity cannot remove that seam, nor
can it overcome sustained synthesis starvation. Larger chunks can reduce request
frequency but can also delay first speech and change interruption/resume cursors.
Neither change is justified as a complete audible fix by the supplied traces.

For the remaining device comparison, use replies with many short sentences and
with a few long sentences, record these rows, and compare decoded-file boundaries
with an audio recording. See the tracked follow-up in [TODO](project/TODO.md).
