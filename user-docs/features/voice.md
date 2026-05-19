# Voice Mode

Real-time voice conversation with your Hermes agent. Tap the mic in chat, speak,
and the agent speaks back through the relay-managed voice output provider while
STT still follows your Hermes server configuration.

## What It Is

Voice mode is a layer on top of chat. Your voice is transcribed to text, sent
through the normal chat flow, and the agent's response is rendered back to speech
as it streams in. The MorphingSphere — the same orb you see in the chat empty
state — expands to fill the screen and reacts to both your voice and the
agent's.

- **Your voice** drives a subtle blue-purple "listening" state. Gentle breathing, surface wobble with your amplitude.
- **The agent's voice** drives a dramatic green-teal "speaking" state. The core goes white-hot on peaks, the data ring spins up to 4× speed on loud consonants.

Transcribed messages appear in your chat history as normal messages. Load the session on another device and you'll see the transcript.

## Requirements

**On your server:**

Voice mode uses Hermes `stt:` settings for transcription and the relay-managed
`voice_output:` renderer for normal assistant speech. The legacy Hermes `tts:`
section remains the fallback path. Voice output defaults live in
`~/.hermes-relay/config.yaml` or in a selected profile's experimental
`voice_output:` section; provider secrets stay server-side.

Common speech output choices:

| Provider | API key | Notes |
|---|---|---|
| **xAI Grok TTS** | xAI key or server-side xAI OAuth | First-class relay speech renderer. Built-ins include `eve`, `ara`, `rex`, `sal`, and `leo`; relay can also list account custom voices when auth allows it. |
| **OpenAI TTS** | `VOICE_TOOLS_OPENAI_KEY` or `OPENAI_API_KEY` | Relay speech renderer for OpenAI speech models. Uses OpenAI's documented built-in voice set. |
| **ElevenLabs** | `ELEVENLABS_API_KEY` | Cascaded streaming TTS comparison target. Relay can refresh account voices, models, and languages server-side. |
| **Hermes fallback TTS** | Depends on upstream provider | Used if relay voice output fails before audio starts. |

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

Hermes-Relay does **client-side sentence chunking**: as the agent streams its
response over SSE, the phone detects complete sentences and immediately renders
them through the relay's `/voice/output/*` websocket. PCM audio streams directly
to Android playback while newer sentences render in the background. If streaming
voice output fails before audio starts, the app falls back to `/voice/synthesize`.

Result: first audio starts within ~1 sentence of the agent starting to reply. You hear the agent before it finishes thinking.

## Voice Settings

**Settings → Voice** has four sections:

### Voice Mode

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

### Voice Output

Editable streaming voice output defaults for the active profile. Provider, model,
voice, language, and sample-rate dropdowns come from the relay's advertised
provider metadata. When you pick a provider, the app asks the relay for that
provider's latest safe options before saving; account-backed discovery, such as
ElevenLabs voice/model lists and paginated xAI custom voices, happens on the server
and is cached briefly to avoid repeated provider API calls.
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

### Speech-to-Text

Read-only display of the STT provider and model reported by `/voice/config`.
STT still follows the upstream Hermes `stt:` configuration, resolved through the
active profile where Hermes has one.

### Realtime Agent Lab

Visible only when Developer options are unlocked. This experimental section lets
you save profile-scoped realtime provider defaults (`realtime_voice:`) for
speech-to-speech/provider testing. Provider dropdowns use the same relay-owned
option refresh, search, grouping, and validation pattern. Normal assistant speech
still uses Voice Output so Hermes remains the owner of chat context, tool
execution, and approval.

### Test Voice

Plays a sample sentence ("Hello, this is Hermes. Voice mode is working.") through
the currently saved profile voice output path. Use this to confirm provider,
model, voice, auth, and playback without burning a chat turn.

## Troubleshooting

**"Microphone permission is required for voice mode"** — tap the mic FAB again. If Android doesn't show a permission prompt (you denied twice), go to Android Settings → Apps → Hermes Relay → Permissions → Microphone and enable it.

**No audio plays when the agent speaks** — check Settings → Voice → Voice Output. If the provider shows "Unknown" or the profile scope is not what you expected, the relay's `/voice/output/config` or `/voice/config` endpoint returned an error or fell back. Try Test Voice; the error message will tell you what's wrong.

**Transcript comes back empty or garbled** — check Settings → Voice → Speech-to-Text. Same diagnostic: Test Voice won't catch STT issues, but you can verify by speaking a slow, clear phrase and watching the transcribed text appear in the overlay. If it's wrong, your STT model or language setting is off.

**The sphere doesn't react during Speaking on my device** — some Android OEMs refuse to construct the `Visualizer` audio-effect object even with `MODIFY_AUDIO_SETTINGS` granted. Hermes-Relay catches this and falls back to a flat amplitude — voice mode still works, the orb just won't pulse with the agent's voice. Listening-mode amplitude (from your mic) is unaffected.

**"Relay returned 503"** — your server doesn't have the provider's optional dependencies installed. SSH into the server and `pip install` whichever provider you configured (e.g. `pip install edge-tts` for Edge, `pip install elevenlabs` for ElevenLabs, or `pip install faster-whisper` for local STT).

**"Relay returned 413" on synthesize** — you're trying to synthesize more than 5000 characters at once. This is a safety cap on the relay side to avoid runaway TTS costs. Client-side sentence chunking should normally keep individual requests well under this, so a 413 usually means the agent returned one enormous uninterrupted sentence.

**"That pairing code was already used"** — Relay pairing codes are one-shot. Generate a fresh QR from the dashboard Relay tab or `hermes-pair` and scan again. If you only need chat plus voice, skip the Relay pairing path and save the Hermes API URL/key instead; the app will derive the conventional Relay voice URL and probe `/voice/config`.

## Privacy Note

Voice audio is uploaded to your relay server and from there to whichever provider you configured. If you're using a cloud provider (ElevenLabs, OpenAI, Groq, Mistral), your audio goes to them. If you're using local providers (faster-whisper, NeuTTS, Edge TTS), nothing leaves your network.

The mp3 files returned from `/voice/synthesize` are cached briefly in the app's cache directory and cleared automatically as new ones arrive (capped at 6 at a time). On the server side, `~/voice-memos/` accumulates TTS outputs — Hermes agent manages this directory, not the relay plugin.
