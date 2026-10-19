# Standard Voice fallback and pause diagnosis

Standard Voice sends conversation work to Hermes. On a Standard upstream audio
route it uses the Dashboard speech stream and upstream synthesis fallback. On an
explicitly resolved Hermes-Relay audio route it can use `/voice/output/*`; a
renderer failure before audio starts sends that chunk to legacy file synthesis.
The latter is the path reported in [#639](https://github.com/Codename-11/hermes-relay/issues/639).
It is separate from OpenAI Realtime Agent session negotiation in #644.

## Recovery behavior

### Relay profile renderer defaults

For Relay streaming output, a profile's `voice_output` section takes precedence
over its `tts` section. When that profile selects a different provider from the
Relay baseline, omitted model and voice values use the selected streaming
adapter's defaults. ElevenLabs therefore uses `eleven_flash_v2_5` when its model
is omitted; an explicit `model` or `model_id` remains authoritative. This does
not change the upstream/basic synthesis provider's defaults.

Same-provider overrides retain the Relay baseline's model and voice unless the
profile explicitly replaces them. A request without a profile keeps using the
Relay configuration; selecting `default` resolves the root Hermes profile, and
a missing named profile retains the existing explicit global-fallback behavior.
Resolution does not rewrite any saved configuration.

Offline tests cover configuration, Relay session creation, the real ElevenLabs
request builder, and synthetic PCM delivery through a fake HTTP transport. Live
ElevenLabs synthesis and heard playback still need reporter confirmation in
[#750](https://github.com/Codename-11/hermes-relay/issues/750).

### Streaming failure fallback

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

## Missing speech and playback completion

Final answer only waits for the chat run to settle before submitting the last
non-empty assistant bubble. Message, run-state, and session notifications are
delivered independently; the speech observer uses them as wakeups and reads
their current values on Main before checking session ownership and completion.
This prevents a cached idle value from retiring the observer at an
empty placeholder, or delayed message delivery from selecting old commentary.
Sanitization may still leave no speakable text, such as a code-only answer.

Offline observer/client tests cover delayed notifications, Standard streaming
and file fallback, and silent intermediate tool activity. These reproduce a
missing-request race, but do not establish the cause of the reporter's original
capture in [#751](https://github.com/Codename-11/hermes-relay/issues/751) or certify
live Edge synthesis and audible playback.

Missing words or sentences in [#704](https://github.com/Codename-11/hermes-relay/issues/704)
are distinct from the inter-sentence pauses above. Android must retain ownership
until the chat observer, synthesis queues, Media3 playlist, pending PCM writes,
and hardware PCM cursor have drained. A tool-call gap is not turn completion.

File playback includes items already handed to Media3, including buffering
items. Streaming playback accepts whole-sentence PCM frames as well as small
provider chunks. The writer advances by the actual accepted byte count, starts
before its stopped buffer fills, and retries unwritten suffixes. Standard speech
uses one ordered IO writer and waits for it before consuming socket completion.
Successful Relay renderer chunks also force-start any short prebuffered tail.
Stop invalidates queued writes and pauses the native track without waiting for
a blocked write to finish. A replacement stream cannot reuse old audio work.

On Android 12 and newer, the application prebuffer controls startup and the
native refill threshold is one frame, allowing short speech after a tool gap.
Older Android versions receive bounded silent tail padding at stream completion
to satisfy their native refill threshold. These mechanisms do not synthesize
replacement words or change the provider's PCM samples.

Current upstream's `hermes_cli/web_routers/audio.py` owns `/api/audio/speak-stream`.
At upstream commit `046094df28b712f62a5e3c728a9e59da05dcbf1e`,
`_SyncSentencePCMStreamer.stream` yields a complete decoded sentence, and the
socket producer waits for further text across idle tool intervals. Android must
not depend on server-side frame splitting or silence keepalives.

`RealtimePcmPlayerWriteTest` exercises the production writer with a finite fake
AudioTrack and synthetic PCM, including 90 KB frames, short writes, underruns,
drain accounting, and concurrent Stop. `StandardVoiceTurnPlaybackTest` exercises
the production observer and speech queue through tool gaps, final-only text,
delayed IO, socket completion, and replacement-stream fencing.
`VoiceFilePlaybackCompletionTest` covers the Media3 completion handoff.
These tests prove software ownership and sample preservation, not heard playback.

For a coordinated physical check, repeat a three-sentence plain reply and a
reply with a tool call on both basic file synthesis and streaming PCM. Compare
the opening and final words against the visible reply, test Hold and Continuous
with phone-local interruption off, then test explicit Stop and a new turn.
Include a short final utterance after a gap and a non-chunked provider. Record
the exact APK, Android version, resolved audio route, and content-free byte/frame
counts. Do not include conversation text, credentials, or raw provider payloads.
