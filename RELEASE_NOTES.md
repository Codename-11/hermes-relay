# Hermes-Relay v0.2.0

Voice mode, terminal, and a full security + pairing overhaul. 54 commits since v0.1.0.

## Download

- **Most people**: grab **`app-release.apk`** below and sideload it. See the [sideload guide](https://codename-11.github.io/hermes-relay/guide/getting-started.html#sideload-apk) for step-by-step instructions.
- **Google Play users**: the app is on Internal testing — production rollout coming soon.
- **`app-release.aab`** is the Google Play format — *not* installable directly.
- **Verify integrity** with `SHA256SUMS.txt` before installing.

## Highlights

### Voice Mode (new)

Talk to your Hermes agent with your voice. Tap the mic in the chat bar to enter voice mode — the sphere expands, listens while you speak, transcribes via your server's STT provider, streams the response through the normal chat pipeline, and speaks it back sentence-by-sentence via TTS. No API keys on the phone — everything routes through the relay plugin using whatever TTS/STT providers you've configured in `~/.hermes/config.yaml`.

- **Three interaction modes** — Tap-to-talk (default), Hold-to-talk, Continuous
- **Reactive layered-sine waveform** — three overlapping waves with amplitude-driven phase velocity, pill-shaped edge merge, color-keyed to voice state
- **Enter/exit chimes** — synthesized tonal sweeps
- **Streaming TTS** — sentence-boundary detection plays the first sentence while the rest is still generating
- **Interrupt** — tap stop while the agent is speaking to cancel TTS + SSE stream
- **Sphere voice states** — Listening (soft blue/purple) and Speaking (vivid green/teal)
- **Voice settings** — Settings > Voice for interaction mode, silence threshold, provider info, and Test Voice
- **6 TTS + 5 STT providers** supported via hermes-agent config

### Terminal (Phase 2)

- **tmux-backed persistent shells** — reconnecting reattaches to your existing session
- **Tabs** — multiple terminal sessions with tab bar
- **Scrollback search** — search through terminal history
- **Session info sheet** — tap for session metadata

### Pairing & Security

- **Session TTL picker** — choose 1d / 7d / 30d / 90d / 1y / Never when pairing
- **Per-channel grants** — control which channels each paired device can access
- **Android Keystore** — session tokens in hardware-backed encrypted storage
- **TOFU certificate pinning** — first-connect pins the relay's TLS cert
- **Paired Devices screen** — list, extend, revoke paired devices
- **Transport security badges** — visual connection security indicator
- **HMAC-SHA256 QR signing** — pairing QR codes are signed to prevent tampering

### Inbound Media

- Agent-produced screenshots and files via relay MediaRegistry with opaque tokens
- Discord-style rendering for image / video / audio / PDF / text attachments
- LLM-emitted `MEDIA:/path` markers fetched via `/media/by-path`

### Settings Refactor

- Category-list landing page replacing the mega-scroll
- Dedicated sub-screens: Connection, Chat, Voice, Media, Appearance, Paired Devices, Analytics, Developer

### Error Feedback

- **RelayErrorClassifier** — every failure now names what broke (not "error: unknown")
- **Global SnackbarHost** — transient error toasts from any screen
- **Mic permission banner** — "Open Settings" action instead of a confusing toast

### Relay Infrastructure

- **`.env` autoload** — relay loads `~/.hermes/.env` at Python import time
- **systemd user service** — `install.sh` installs and enables `hermes-relay.service` automatically
- No more `nohup` / `pkill` — just `systemctl --user restart hermes-relay`

### Other

- Global font-scale preference
- Save & Test health probe for relay connection verification
- App screenshots in `assets/screenshots/`
- Gradle task to suppress Android 15 logcat spam

## Requirements

- Android 8.0+ (API 26)
- [Hermes agent](https://github.com/NousResearch/hermes-agent) v0.8.0+
- Relay plugin installed via `install.sh` (for voice, terminal, media, and pairing features)

## Found a bug?

[Open an issue](https://github.com/Codename-11/hermes-relay/issues/new) — we read every one.
