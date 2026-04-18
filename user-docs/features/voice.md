# Voice Mode

Real-time voice conversation with your Hermes agent. Tap the mic in chat, speak, and the agent speaks back — using whatever TTS and STT providers you've configured on your Hermes server.

## What It Is

Voice mode is a layer on top of chat. Your voice is transcribed to text, sent through the normal chat flow, and the agent's response is synthesized back to speech sentence-by-sentence as it streams in. The MorphingSphere — the same orb you see in the chat empty state — expands to fill the screen and reacts to both your voice and the agent's.

- **Your voice** drives a subtle blue-purple "listening" state. Gentle breathing, surface wobble with your amplitude.
- **The agent's voice** drives a dramatic green-teal "speaking" state. The core goes white-hot on peaks, the data ring spins up to 4× speed on loud consonants.

Transcribed messages appear in your chat history as normal messages. Load the session on another device and you'll see the transcript.

## Requirements

**On your server:**

Voice mode uses whatever TTS and STT providers you've configured in your `~/.hermes/config.yaml` under the `tts:` and `stt:` sections. The hermes-relay plugin imports those tools directly from the hermes-agent venv, so you don't need to do anything phone-side — if `hermes voice test` works on the server, voice mode works in the app.

Six TTS providers are supported upstream:

| Provider | API key | Notes |
|---|---|---|
| **Edge TTS** | None (free) | Default. 322 voices, 74 languages. Needs `ffmpeg` for the Opus conversion path (mp3 path doesn't). |
| **ElevenLabs** | `ELEVENLABS_API_KEY` | Premium. Fast, expressive. |
| **OpenAI TTS** | `VOICE_TOOLS_OPENAI_KEY` | `gpt-4o-mini-tts` with six voices. |
| **MiniMax** | `MINIMAX_API_KEY` | `speech-2.8-hd`, voice cloning. |
| **Mistral Voxtral** | `MISTRAL_API_KEY` | Multilingual, native Opus. |
| **NeuTTS** | None (local) | ~500 MB on-device model, reference voice cloning. |

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

## Streaming TTS — Why It Feels Fast

Most voice modes wait for the full response, then synthesize all of it, then play. That's a minimum ~3-second latency on top of the LLM.

Hermes-Relay does **client-side sentence chunking**: as the agent streams its response over SSE, the phone detects complete sentences (watching for `.`, `?`, `!`, and `\n\n` with whitespace lookahead for abbreviations like "e.g.") and immediately POSTs each sentence to the relay's `/voice/synthesize` endpoint. Each sentence comes back as an mp3, queues into a player channel, and a consumer coroutine plays them back-to-back while newer sentences synthesize in the background.

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

### Text-to-Speech

Read-only display of the TTS provider, voice, and model your server is configured with. Read live from `GET /voice/config` on every open — if you change the server config and hit Test Voice, the new settings come through immediately after a gateway restart. (Writing config from the phone is Phase M — for now, edit `~/.hermes/config.yaml` on the server and restart hermes-gateway.)

### Speech-to-Text

Read-only display of the STT provider + a language picker that stores your preference locally. The relay currently auto-detects language on most providers; the stored value is reserved for future use.

### Test Voice

Plays a sample sentence ("Hello, this is Hermes. Voice mode is working.") through the current TTS provider. Use this to confirm your server config is valid end-to-end without burning a chat turn.

## Troubleshooting

**"Microphone permission is required for voice mode"** — tap the mic FAB again. If Android doesn't show a permission prompt (you denied twice), go to Android Settings → Apps → Hermes Relay → Permissions → Microphone and enable it.

**No audio plays when the agent speaks** — check Settings → Voice → Text-to-Speech. If the provider shows "Unknown", the relay's `/voice/config` endpoint returned an error — your server's `tts:` section may be misconfigured or missing its API key. Try Test Voice; the error message will tell you what's wrong.

**Transcript comes back empty or garbled** — check Settings → Voice → Speech-to-Text. Same diagnostic: Test Voice won't catch STT issues, but you can verify by speaking a slow, clear phrase and watching the transcribed text appear in the overlay. If it's wrong, your STT model or language setting is off.

**The sphere doesn't react during Speaking on my device** — some Android OEMs refuse to construct the `Visualizer` audio-effect object even with `MODIFY_AUDIO_SETTINGS` granted. Hermes-Relay catches this and falls back to a flat amplitude — voice mode still works, the orb just won't pulse with the agent's voice. Listening-mode amplitude (from your mic) is unaffected.

**"Relay returned 503"** — your server doesn't have the provider's optional dependencies installed. SSH into the server and `pip install` whichever provider you configured (e.g. `pip install edge-tts` for Edge, `pip install elevenlabs` for ElevenLabs, or `pip install faster-whisper` for local STT).

**"Relay returned 413" on synthesize** — you're trying to synthesize more than 5000 characters at once. This is a safety cap on the relay side to avoid runaway TTS costs. Client-side sentence chunking should normally keep individual requests well under this, so a 413 usually means the agent returned one enormous uninterrupted sentence.

## Privacy Note

Voice audio is uploaded to your relay server and from there to whichever provider you configured. If you're using a cloud provider (ElevenLabs, OpenAI, Groq, Mistral), your audio goes to them. If you're using local providers (faster-whisper, NeuTTS, Edge TTS), nothing leaves your network.

The mp3 files returned from `/voice/synthesize` are cached briefly in the app's cache directory and cleared automatically as new ones arrive (capped at 6 at a time). On the server side, `~/voice-memos/` accumulates TTS outputs — Hermes agent manages this directory, not the relay plugin.
