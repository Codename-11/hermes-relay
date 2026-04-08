<p align="center">
  <img src="assets/logo.svg" alt="Hermes Relay" width="120">
</p>

<h1 align="center">Hermes Relay</h1>

<p align="center">
  Native Android client for the Hermes agent platform.<br>
  Chat, control, and connect — one app for your AI agent.
</p>

<p align="center">
  <a href="https://opensource.org/licenses/MIT"><img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="MIT"></a>
  <a href="https://developer.android.com"><img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Android"></a>
  <a href="https://github.com/Codename-11/hermes-relay/actions/workflows/ci.yml"><img src="https://github.com/Codename-11/hermes-relay/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://developer.android.com/about/versions/oreo"><img src="https://img.shields.io/badge/Min%20SDK-26-brightgreen.svg" alt="Min SDK 26"></a>
</p>

<p align="center">
  <a href="https://hermes-agent.nousresearch.com">Hermes Agent</a> ·
  <a href="docs/spec.md">Specification</a> ·
  <a href="docs/decisions.md">Architecture Decisions</a> ·
  <a href="CHANGELOG.md">Changelog</a>
</p>

---

## What is Hermes Relay?

A native Android app for [Hermes Agent](https://github.com/NousResearch/hermes-agent). Three channels in one app:

| Channel | Protocol | What |
|---------|----------|------|
| **Chat** | HTTP/SSE | Stream conversations directly to the Hermes API Server |
| **Terminal** | WSS | Secure remote shell access via tmux (Phase 2) |
| **Bridge** | WSS | Agent controls the phone — taps, types, screenshots (Phase 3) |

Chat connects directly to the Hermes API Server (`/api/sessions/{id}/chat/stream`). Terminal and bridge use a WebSocket relay with channel multiplexing.

## Server Components

The app talks to two server-side services. Only the first is required.

| Component | Required? | What |
|-----------|-----------|------|
| **Hermes API Server** (`:8642`) | Yes | Chat, sessions, profiles, skills. Part of `hermes gateway`. |
| **Relay Server** (`:8767`) | Only for terminal/bridge | WSS server for interactive terminal and device bridge. |

```
Phone (HTTP/SSE) --> Hermes API Server (:8642)   [chat — direct]
Phone (WSS)      --> Relay Server (:8767)         [terminal, bridge]
```

Chat connects directly to the Hermes API Server — same pattern used by Open WebUI, ClawPort, and other Hermes frontends. The relay server is a separate lightweight Python service for features that need persistent bidirectional communication. See [docs/relay-server.md](docs/relay-server.md) for details.

## Features

| Layer | Capabilities |
|-------|-------------|
| **Chat** | Direct API streaming (SSE), session management, auto-titles, message queuing, file attachments, personality picker, agent name on bubbles, slash command autocomplete, QR code pairing |
| **Slash Commands** | 29 gateway commands + dynamic personality commands + server skill discovery (`GET /api/skills`). Searchable command palette with category filtering. |
| **Personalities** | Dynamic from `GET /api/config` (`config.agent.personalities`). Picker shows server default + all configured. Agent name displayed on chat bubbles. `/personality <name>` slash commands. |
| **Rendering** | Full markdown, syntax-highlighted code blocks (Atom theme), reasoning display, animated streaming dots |
| **Tools** | Configurable display (Off/Compact/Detailed). Rich progress cards with type-specific icons, auto-expand/collapse, duration |
| **Analytics** | Stats for Nerds — TTFT, completion times, token usage, peak/slowest times, health latency, stream success rates. Canvas bar charts. Reset button. |
| **Tokens** | Per-message input/output counts and estimated cost |
| **Animation** | ASCII morphing sphere on empty chat, ambient fullscreen mode (toggle in header), 15% opacity behind messages (toggleable). Settings: sphere on/off, behind messages on/off. |
| **UX** | Animated splash screen, chat empty state with suggestion chips, haptic feedback, app context prompt, configurable limits (attachment size, message length) |
| **Security** | EncryptedSharedPreferences (AES-256-GCM), HTTPS enforced, cleartext only for localhost |
| **Connectivity** | Network monitoring, auto-reconnect, capability detection (enhanced/portable/disconnected) |

## Repository Structure

```
hermes-relay/
├── app/                       # Android app (Kotlin + Jetpack Compose)
├── relay_server/              # WSS relay server (Python + aiohttp)
├── plugin/                    # Hermes agent plugin (14 android_* tools)
├── skills/                    # Hermes agent skills (QR pairing)
├── user-docs/                 # VitePress documentation site
├── docs/                      # Spec, decisions, security
├── scripts/                   # Dev helper scripts
├── .github/workflows/         # CI + release pipelines
└── gradle/                    # Wrapper (8.13) + version catalog
```

## Quick Start

### Open in Android Studio

1. **File > Open** the repo root
2. Wait for Gradle sync
3. **Run** (Shift+F10) to deploy to emulator or device

### Dev Scripts

```bash
scripts/dev.bat build      # Build debug APK
scripts/dev.bat release    # Build signed release APK
scripts/dev.bat bundle     # Build release AAB for Google Play
scripts/dev.bat run        # Build + install + launch + logcat
scripts/dev.bat test       # Run unit tests
scripts/dev.bat version    # Show current version
scripts/dev.bat relay      # Start relay server (dev, no TLS)
scripts/dev.bat devices    # List connected devices
scripts/dev.bat wireless   # Pair for wireless debugging
```

### Android Studio Workflow

| Action | Shortcut | What it does | Wipes data? |
|--------|----------|-------------|-------------|
| **Gradle Sync** | Toolbar elephant icon | Reads `build.gradle.kts` + `libs.versions.toml`, resolves dependencies. No compilation. | No |
| **Assemble** | Build > Make Project | Compile → DEX → package APK. Does not deploy. | No |
| **Run** | Shift+F10 | Assemble + install + launch on device/emulator | No (upgrade install) |
| **Apply Changes** | Ctrl+F10 | Hot-patch changed code, restart Activity. ViewModels survive. | No |
| **Apply Code Changes** | Ctrl+Shift+F10 | Patch method bodies only, no restart | No |

To **wipe app data**: emulator app icon long-press > App Info > Storage > Clear Data, or `adb shell pm clear com.hermesandroid.relay`.

**Versioning** lives in `gradle/libs.versions.toml` (`appVersionName`, `appVersionCode`) — the single source of truth read by `build.gradle.kts`.

### Relay Server (optional — for terminal/bridge)

```bash
pip install aiohttp pyyaml && python -m relay_server --no-ssl
```

Or with Docker:

```bash
docker build -t hermes-relay relay_server/ && docker run -d --network host --name hermes-relay hermes-relay
```

Or as a systemd service:

```bash
sudo cp relay_server/hermes-relay.service /etc/systemd/system/
sudo systemctl enable --now hermes-relay
```

See [docs/relay-server.md](docs/relay-server.md) for TLS, configuration, and full setup.

### QR Code Pairing (optional)

Generate a QR code on your server that the app can scan to auto-configure:

```bash
# Install the skill + script
cp -r skills/hermes-pairing-qr ~/.hermes/skills/hermes-pairing-qr
cp skills/hermes-pairing-qr/hermes-pair ~/.local/bin/hermes-pair
chmod +x ~/.local/bin/hermes-pair
sudo apt install qrencode

# Generate QR
hermes-pair
```

Scan it in the app (Settings > Scan QR, or during onboarding). See [skills/hermes-pairing-qr/SKILL.md](skills/hermes-pairing-qr/SKILL.md) for details.

### Hermes Plugin (optional — for bridge/device control)

```bash
cp -r plugin ~/.hermes/plugins/hermes-android
# Restart hermes — /plugins should show: hermes-android (14 tools)
```

## Tech Stack

| Component | Stack |
|-----------|-------|
| **Android App** | Kotlin 2.0, Jetpack Compose, Material 3, OkHttp |
| **Relay Server** | Python 3.11+, aiohttp |
| **Serialization** | kotlinx.serialization |
| **Build** | AGP 8.13, Gradle 8.13, JVM toolchain 17 |
| **CI/CD** | GitHub Actions (lint, build, test, APK artifact) |
| **Min SDK** | 26 (Android 8.0) / Target SDK 35 |

## Current State — v0.1.0

| Phase | Status | Scope |
|-------|--------|-------|
| **Phase 0** | Complete | Compose scaffold, WSS connection, channel multiplexer, auth, splash screen |
| **Phase 1** | Complete | Direct API chat, sessions, markdown, tools, personalities, slash commands, command palette, analytics, QR pairing, tool display config |
| **Phase 2** | Next | Terminal channel (xterm.js + tmux) |
| **Phase 3** | Next | Bridge channel (AccessibilityService) |

See [docs/spec.md](docs/spec.md) for the full specification and [docs/decisions.md](docs/decisions.md) for architecture rationale.

## Documentation

| | |
|---|---|
| [Specification](docs/spec.md) | Full spec — protocol, UI, phases, dependencies |
| [Architecture Decisions](docs/decisions.md) | ADRs — framework, channels, auth, terminal |
| [Relay Server](docs/relay-server.md) | Setup, config, Docker, systemd — everything for the relay |
| [Upstream Contributions](docs/upstream-contributions.md) | Potential improvements to propose to hermes-agent |
| [Security](docs/security.md) | Auth flow, encryption, network security |
| [Privacy](docs/privacy.md) | Data handling, local storage, no telemetry |
| [Changelog](CHANGELOG.md) | Release history |
| [Dev Log](DEVLOG.md) | Session-by-session development notes |

## Hermes Agent

Hermes Relay is built for [Hermes Agent](https://github.com/NousResearch/hermes-agent) — an open-source AI agent platform by [Nous Research](https://nousresearch.com). See the [Hermes Agent docs](https://hermes-agent.nousresearch.com) for server setup, gateway configuration, and plugin development.

## License

[MIT](LICENSE) — Copyright (c) 2026 [Axiom-Labs](https://axiom-labs.cloud)

---

<p align="center">
  Built with the help of Humans and AI Agents<br><br>
  <a href="https://ko-fi.com/L4L31Q8LJ1"><img src="https://ko-fi.com/img/githubbutton_sm.svg" alt="Support on Ko-fi"></a>
</p>
