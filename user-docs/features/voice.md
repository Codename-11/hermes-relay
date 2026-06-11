# Voice Mode

Real-time voice conversation with your Hermes agent. Tap the mic in chat, speak,
and the agent speaks back. **No Relay required**: on a standard connection,
speech runs through your Hermes dashboard's audio routes — the same path the
official Hermes Desktop voice mode uses — with the server's configured STT/TTS
providers.

::: tip Two speech routes, picked automatically
- **Standard** — works on a vanilla Hermes install. The phone talks to your
  Hermes dashboard; if the dashboard requires sign-in, signing in once under
  **Manage** also unlocks voice for that connection.
- **Relay** — when the optional Relay is paired, voice prefers it: per-profile
  voice providers, streaming voice output, and the Realtime Agent engine.

You can pin either route under **Settings → Voice → Stable STT/TTS Route**;
the default *Auto* uses Relay when paired, otherwise Standard.
:::

The stable default engine is **Hermes Chat + Voice Output**: Hermes owns the
chat turn, tools, memory, approvals, and transcript, then the active speech
route renders the assistant response to audio. An opt-in **Realtime Agent**
engine is available for experimental provider-native speech work — it requires
a paired Relay, is visibly badged as Experimental in Voice Settings, and can be
switched off without changing the stable voice behavior.

## What It Is

Voice mode is a layer on top of chat. In the stable engine, your voice is
transcribed to text, sent through the normal chat flow, and the agent's response
is rendered back to speech as it streams in. In the experimental Realtime Agent
engine, Android streams mic PCM through the relay to a native realtime provider
such as xAI/Grok Voice Agent or OpenAI Realtime, and the relay mirrors the
provider's live transcript, audio, and Hermes tool state into the same chat
timeline. The MorphingSphere — the same orb you see in the chat empty state —
expands to fill the screen and reacts to both your voice and the agent's.

- **Your voice** drives a subtle blue-purple "listening" state. Gentle breathing, surface wobble with your amplitude.
- **The agent's voice** drives a dramatic green-teal "speaking" state. The core goes white-hot on peaks, the data ring spins up to 4× speed on loud consonants.

Transcribed messages appear in your chat history as normal messages. Load the
session on another device and you'll see the transcript. Realtime Agent mirrors
its transcript, Hermes tool state, confirmation prompts, and final response into
that same chat timeline so you do not have to exit voice mode to see what
happened.

## Requirements

**On your server:**

- **Standard route (no Relay):** a current hermes-agent whose dashboard
  exposes the audio endpoints, with `stt:` and `tts:` configured in
  `~/.hermes/config.yaml`. If the dashboard is auth-gated, sign in once under
  **Manage** on the phone. Older builds without dashboard audio routes show
  "Not available on this Hermes build" in Voice Settings — update
  hermes-agent or pair Relay.
- **Relay route:** Hermes `stt:` settings for transcription and the
  relay-managed `voice_output:` renderer for assistant speech, with the
  legacy Hermes `tts:` section as the fallback path. Voice output defaults
  live in `~/.hermes-relay/config.yaml` or in a selected profile's
  experimental `voice_output:` section; provider secrets stay server-side.

Common speech output choices:

| Provider | API key | Notes |
|---|---|---|
| **xAI Grok TTS** | xAI key or server-side xAI OAuth | First-class relay speech renderer. Built-ins include `eve`, `ara`, `rex`, `sal`, and `leo`; relay can also list account custom voices when auth allows it. |
| **OpenAI TTS** | `VOICE_TOOLS_OPENAI_KEY` or `OPENAI_API_KEY` | Relay speech renderer for OpenAI speech models. Uses OpenAI's documented built-in voice set. |
| **ElevenLabs** | `ELEVENLABS_API_KEY` | Cascaded streaming TTS comparison target. Relay can refresh account voices, models, and languages server-side. |
| **Hermes fallback TTS** | Depends on upstream provider | Used if relay voice output fails before audio starts. |

Realtime Agent native providers also run relay-side. `xai_realtime` uses the
relay-owned xAI API key or OAuth store. `openai_realtime` uses
`OPENAI_REALTIME_API_KEY`, `OPENAI_API_KEY`, or `VOICE_TOOLS_OPENAI_KEY` on the
relay host. These keys are never stored on Android.

Five STT providers are supported:

| Provider | API key | Notes |
|---|---|---|
| **Local (faster-whisper)** | None (free) | Default. Runs locally on your server. Models from `tiny` to `large-v3`. |
| **Local command** | — | Custom whisper binary via `HERMES_LOCAL_STT_COMMAND`. |
| **Groq** | `GROQ_API_KEY` | Free tier. `whisper-large-v3-turbo` — very fast. |
| **OpenAI Whisper** | `VOICE_TOOLS_OPENAI_KEY` | `whisper-1` or `gpt-4o-transcribe`. |
| **Mistral Voxtral** | `MISTRAL_API_KEY` | `voxtral-mini-latest`. |

**On your phone:**

- Microphone permission (requested the first time you tap the mic).
- A connected relay at `:8767` with the voice routes available (any hermes-relay build from 0.2.0 onwards).

Phone voice mode can authenticate two ways: a paired Relay session token with `voice:config`, `voice:stt`, and `voice:tts` grants, or the same saved Hermes API key used for chat. That means chat+voice-only phone setups can skip the full Relay pairing flow: enter the API URL and API key, and the app derives the Relay URL from the same host on port `8767`. If the `/voice/config` probe fails, the app reveals a manual Relay URL override. The API-key exception is limited to voice routes and requires HTTPS outside loopback. For a temporary plain-LAN phone test, run `hermes relay insecure-api-key on` on the relay host, then turn it back off with `hermes relay insecure-api-key off`.

Before a voice turn is submitted, Android now runs a fast relay health preflight.
If the host accepts TCP but does not answer HTTP, voice mode fails quickly with a
Connections-facing error instead of sitting in Thinking until the long realtime
turn timeout expires. The check is recorded in **Settings -> Diagnostics** and in
the Relay detail sheet's recent activity tail.

## Entering Voice Mode

Open a chat and tap the microphone FAB in the bottom-right corner. The first time, Android will ask for microphone permission. Granting it opens the voice mode overlay immediately. Denying it shows a banner at the top — tap Dismiss and you can try again later.

To exit voice mode, tap the X in the top-right of the overlay.

## Interaction Modes

Change in **Settings → Voice → Interaction mode**. All three share the same overlay — only the mic button's behavior changes.

### Tap-to-talk (default)

- Tap the mic to start recording.
- Tap again to stop manually, or stay quiet for ~3 seconds and it'll auto-stop (threshold configurable in Voice Settings).
- Tap while the agent is speaking to **interrupt** — the current TTS stops and a fresh recording starts. If **Barge-in** is enabled (see below), you can also just start speaking — no tap needed.

### Hold-to-talk

- Press and hold the mic while you speak.
- Release to stop recording and send.
- Good for noisy environments where the silence detector would trip early.

### Continuous

- After the agent finishes speaking, the mic automatically re-activates for your next turn.
- A back-and-forth conversation without ever touching the screen.
- Tap the X to exit when you're done.

## Streaming Voice Output — Why It Feels Fast

Most voice modes wait for the full response, then synthesize all of it, then play. That's a minimum ~3-second latency on top of the LLM.

Hermes-Relay does **balanced client-side voice chunking**: as the agent streams
its response over SSE, the phone detects complete speech boundaries, batches
normal assistant prose into larger natural chunks, and renders those chunks
through the relay's `/voice/output/*` websocket. PCM audio streams directly to
Android playback while newer text continues arriving. Short tool/status lines
such as "I'm checking that" bypass the batcher so they stay immediate. If
streaming voice output fails before audio starts, the app falls back to
`/voice/synthesize`.

Result: first audio starts shortly after the agent begins replying, but normal
answers use fewer provider renders so tone, prosody, and volume stay more
consistent across the response.

## Voice Settings

**Settings → Voice** has these core sections:

### Voice Engine

- **Voice engine** — Stable `Hermes Chat + Voice Output` or opt-in
  `Realtime Agent` with an Experimental badge.

The active engine controls which provider card appears below. Stable voice shows
the Hermes voice-output renderer settings. Realtime voice shows the Realtime
Agent provider settings.

### Global Voice Controls

These controls always show and apply to both engines:

- **Interaction mode** — Tap / Hold / Continuous
- **Silence threshold** — 1-10 seconds, default 3. Only applies in Tap-to-talk mode.
- **Auto-TTS** — reserved for future: speak all agent responses even when not in voice mode. Currently a placeholder.

### Barge-in

New as of 2026-04-17. Lets you interrupt the agent by speaking while it's replying — the same turn-taking pattern ChatGPT and Siri use. **Default off** because echo-cancellation quality varies widely across Android phones; opt in once and the setting persists.

- **Interrupt when I speak** — master toggle. Default off.
- **Sensitivity** — `Off / Low / Default / High`. Higher values fire on quieter / shorter speech. Start with Default; drop to Low if your phone false-triggers on its own TTS, raise to High if you find yourself having to speak up.
- **Resume after interruption** — default on. After you interrupt, if you then stay quiet for ~600 ms, the agent resumes reading from the next sentence of its response. A quick breath or "wait, actually…" that you decide not to finish won't throw away its answer. Turn off if you'd rather a hard cut every time.

**Device compatibility.** If your phone doesn't support hardware echo cancellation (`AcousticEchoCanceler`), you'll see a warning badge next to the master toggle: *"Your device may have limited echo cancellation. Barge-in quality will vary."* You can still enable barge-in, but expect more false triggers from the phone's own speaker feeding back into the mic. **Using headphones fixes this entirely** — the mic never hears the TTS output, so VAD has nothing to confuse.

**How it feels in practice.** As soon as you start speaking, the agent's voice briefly ducks in volume (about 30 %) — that's the app acknowledging "I think I heard something" before committing. If you keep speaking, it stops entirely within a fraction of a second and you're back in recording mode. If it was a false trigger (one stray frame of background noise), the volume pops back up to full after ~500 ms with no interruption.

### Hermes Chat + Voice Output

Visible when the `Hermes Chat + Voice Output` engine is selected. It edits
streaming voice output defaults for the active profile. Provider, model, voice,
language, and sample-rate dropdowns come from the relay's advertised provider
metadata. When you pick a provider, the app asks the relay for that provider's
latest safe options before saving; account-backed discovery, such as ElevenLabs
voice/model lists and paginated xAI custom voices, happens on the server and is
cached briefly to avoid repeated provider API calls.
OpenAI voice choices are static from the official API docs because OpenAI does
not provide a general voice-list endpoint for this surface. The picker groups
provider voices, supports search for long voice lists, marks recommended/custom
voices where the relay can tell, and validates model/voice/sample-rate
compatibility before saving. **Advanced manual entry** lets you type raw IDs for
providers or voices the relay does not advertise yet; those save as warnings
rather than hard failures unless the relay knows the combination is incompatible.
Saving while a profile is selected writes that profile's experimental
`voice_output:` section, so `mizuki` can use one voice and `victor` another.
**Save & test**
persists the choice and plays the sample through the active profile voice.

The older Hermes `tts:` config is still shown as fallback provider information
and is still used if streaming voice output fails before audio starts.

Stable streaming playback can resume during short connection changes. If the
phone moves from Wi-Fi to cellular or from LAN to Tailscale while audio is in
flight, Android reopens the same relay voice-output session through the current
route and the relay replays missed PCM chunks instead of forcing a new render.

### Speech-to-Text

Read-only display of the STT provider and model reported by `/voice/config`.
STT still follows the upstream Hermes `stt:` configuration for the stable
Hermes Chat + Voice Output engine, resolved through the active profile where
Hermes has one. Realtime Agent uses the selected provider's native realtime
transcription instead and does not upload the turn through `/voice/transcribe`.

### Realtime Agent

Visible when the `Realtime Agent` voice engine is selected. This experimental
section saves profile-scoped realtime provider defaults (`realtime_voice:`) for
provider-native testing. Native Realtime Agent providers currently include
`xai_realtime` with `grok-voice-latest` and `openai_realtime` with
`gpt-realtime-2`. Victor's profile can select `leo` for xAI, while OpenAI
profiles commonly use voices such as `marin` or `cedar`. Provider dropdowns use
the same relay-owned option refresh, search, grouping, and validation pattern as
Voice Output, plus a native-agent capability flag so lab/render-only providers
do not appear as full Realtime Agent choices.

Realtime Agent is still Hermes-first. The provider does not get raw Android
bridge tools, direct Hermes tool execution, or ownership of memory. The relay
broker exposes only a small tool surface:

- `hermes_run_task`
- `hermes_get_status`
- `hermes_cancel`
- `hermes_confirm`

When the phone is paired, its Relay session token only authenticates it to the
realtime route. Hermes tool calls from the realtime broker use the relay
server's local Hermes API credential from `config.yaml`, `.env`, or
`API_SERVER_KEY`; the provider never receives the phone's saved API key.

During Hermes tool work, the app shows clean status from the broker instead of
dumping raw tool output into voice. Normal timeline rows show the active Hermes
tool, completion state, and provider-spoken provenance. Android keeps those
status updates UI-only during provider-native realtime playback so a second
voice-output stream cannot compete with the realtime provider. The final spoken
answer is generated by the realtime provider after Hermes returns a compact
result, so tool output is summarized naturally instead of read aloud.

#### Background tasks

Some requests take a while — research, multi-step work, a long command. Instead
of freezing the conversation until they finish, Realtime Agent **promotes** a
slow run to the background: the agent says a short "I'm on it" and you can keep
talking, ask something else, or just wait. When the task finishes, the agent
speaks the answer. Asking for something explicitly long starts a background task
right away.

You control this under **Voice Settings → Realtime Agent → Background tasks**:

- **Promote long tasks** — turn the behavior on or off. With it off, the agent
  waits silently until the task finishes (the old behavior).
- **Spoken handoff** — whether the agent says a short acknowledgement when a task
  moves to the background, or just shows it on screen.
- **When the answer is ready** — *Speak* it as soon as you're not mid-sentence,
  *Notify* and speak when you re-engage, or *Show only* (no spoken answer).

While a background task is running, a small "working on it" chip stays visible in
the voice screen. You can cancel a background task at any time the same way you
cancel any turn. Hermes still owns the task end-to-end — promotion only changes
*when* the answer is spoken, never who runs the tools.

Provider-native Android paths stream mic PCM to a relay-owned realtime provider
WebSocket session. Android commits the captured utterance, the active provider
owns input transcription and speech generation, and Hermes still owns profile
binding, session history, memory, tools, confirmation prompts, cancellation
policy, and transcript persistence. The relay sends the provider only the
approved Hermes function schemas, returns compact tool results, and waits for
Android playback to drain before asking the provider for post-tool narration. If
the provider disconnects or quality is poor, switch Voice engine back to
`Hermes Chat + Voice Output`; existing voice routes and settings remain intact.

Realtime Agent sessions use the same resume window during short connection
changes. If the phone moves from Wi-Fi to cellular or from LAN to Tailscale
while a turn is thinking or speaking, Android follows the app's current relay
route signal and can reopen the same relay voice session before the old
websocket times out. It sends a session resume token and its last received
audio/event IDs, and the relay replays missed status or PCM chunks instead of
starting a second Hermes run. If the resume window expires, the app shows a
recoverable voice error and the next tap starts a fresh turn.

Voice turns include interface context. In stable voice mode the chat agent is
told the turn came through `Hermes Chat + Voice Output`; in Realtime Agent mode
the provider and Hermes broker are told the active path is `realtime_agent`,
including provider, model, voice, profile, and relay-local date/time. If you ask
which path is active or what today's date is, the agent should answer from that
context.

Realtime Agent also receives recent shared chat context from the current
timeline. That means switching from normal chat voice to Realtime Agent, then
back again, should feel like one conversation. If the realtime provider answers
a simple prompt-contained question directly, the app syncs that local
provider-only turn into the next Hermes chat turn. If the turn used Hermes tools,
Hermes already has the canonical record and the app does not duplicate the tool
output.

For research, news, current facts beyond the injected date/time, live checks, or
anything else the realtime provider cannot know from the active context, the
provider is instructed to call Hermes instead of guessing from model knowledge.
That also covers latest/versioned data, device or desktop state, personal or
project context, side effects, precision-sensitive answers, explicit
check/verify/look-up requests, media or artifact handling, and follow-up
references like "this", "that", "it", or "that integration" that need durable
chat/session context. Hermes performs the governed check, then the provider
speaks a concise summary from Hermes' returned answer.

Realtime Agent also asks the provider to format speech for listening: dates,
times, currency, percentages, versions, measurements, counts, paths, URLs, IDs,
JSON, logs, tables, and dense numeric strings should be summarized naturally
instead of read character by character. When raw values are not useful aloud, the
voice can say something like "plus a few IDs and raw values" and keep the meaning
front and center.

The default Realtime Agent timeline stays user-facing: live transcript,
assistant speech, path badges, confirmation state, and compact tool rows. Turn
on **Detailed trace** in Voice settings to show compact Hermes status and result
provenance for debugging. Full raw traces stay in the relay run logs.

**Reliable, low-latency playback.** Realtime audio plays from the first frame
with minimal latency. Earlier builds could drop or stutter the first turn because
the AudioTrack deep buffer cold-started with its playback head parked at zero;
the streaming buffer was reduced to a low-latency size, the prebuffer threshold
retuned, and the preroll force-start removed. Playback now records a
time-to-first-audio metric, logs the requested-vs-actual buffer size, runs a
first-frame watchdog, and cross-checks drain drift. When something goes wrong,
those signals land in **Settings -> Diagnostics** instead of presenting as silent
dead air.

### Test Current Engine

When `Hermes Chat + Voice Output` is selected, plays a sample sentence ("Hello,
this is Hermes. Voice mode is working.") through the currently saved profile
voice-output path. When `Realtime Agent` is selected, opens a provider-native
`/voice/realtime-agent/*` session and plays a short realtime sample through the
relay. The fallback TTS card is global and remains visible for both engines as
the stable speech safety net.

### Voice Lab

The realtime voice test screen (the "Voice Lab") is a developer-facing harness
for exercising the realtime path in isolation, with two clearly separated demos:

- **Text demo** — plays raw provider TTS directly, with no agent in the loop. Use
  it to confirm the provider, voice, and streaming playback are healthy.
- **Mic demo** — runs the full agent path: real speech recognition, Hermes
  brokering, and a spoken reply. Capture is tap-to-record / tap-to-stop, so you
  control exactly when the utterance is committed.

The Voice Lab waveform follows the playback cursor — it is driven by the player's
amplitude at the current playback position rather than by socket-arrival time, so
the visual matches what you actually hear. A `scripts/realtime-voice-lab-smoke.ps1`
script automates a quick end-to-end check of the lab routes.

## Troubleshooting

**"Microphone permission is required for voice mode"** — tap the mic FAB again. If Android doesn't show a permission prompt (you denied twice), go to Android Settings → Apps → Hermes Relay → Permissions → Microphone and enable it.

**No audio plays when the agent speaks** — check Settings → Voice → Hermes Chat + Voice Output. If the provider shows "Unknown" or the profile scope is not what you expected, the relay's `/voice/output/config` or `/voice/config` endpoint returned an error or fell back. Try Test Current Engine; the error message will tell you what's wrong.

**Transcript comes back empty or garbled** — check Settings → Voice → Speech-to-Text. Same diagnostic: Test Current Engine won't catch stable STT issues, but you can verify by speaking a slow, clear phrase and watching the transcribed text appear in the overlay. If it's wrong, your STT model or language setting is off.

**The sphere doesn't react during Speaking on my device** — some Android OEMs refuse to construct the `Visualizer` audio-effect object even with `MODIFY_AUDIO_SETTINGS` granted. Hermes-Relay catches this and falls back to a flat amplitude — voice mode still works, the orb just won't pulse with the agent's voice. Listening-mode amplitude (from your mic) is unaffected.

**"Relay returned 503"** — your server doesn't have the provider's optional dependencies installed. SSH into the server and `pip install` whichever provider you configured (e.g. `pip install edge-tts` for Edge, `pip install elevenlabs` for ElevenLabs, or `pip install faster-whisper` for local STT).

**"Relay returned 413" on synthesize** — you're trying to synthesize more than 5000 characters at once. This is a safety cap on the relay side to avoid runaway TTS costs. Client-side sentence chunking should normally keep individual requests well under this, so a 413 usually means the agent returned one enormous uninterrupted sentence.

**"That pairing code was already used"** — Relay pairing codes are one-shot. Generate a fresh QR from the dashboard Relay tab or `hermes pair` and scan again. If you only need chat plus voice, skip the Relay pairing path and save the Hermes API URL/key instead; the app will derive the conventional Relay voice URL and probe `/voice/config`.

## Privacy Note

Voice audio is uploaded to your relay server and from there to whichever provider you configured. If you're using a cloud provider (ElevenLabs, OpenAI, Groq, Mistral), your audio goes to them. If you're using local providers (faster-whisper, NeuTTS, Edge TTS), nothing leaves your network.

The mp3 files returned from `/voice/synthesize` are cached briefly in the app's cache directory and cleared automatically as new ones arrive (capped at 6 at a time). On the server side, `~/voice-memos/` accumulates TTS outputs — Hermes agent manages this directory, not the relay plugin.
