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
  <a href="https://codename-11.github.io/hermes-relay/">Documentation</a> ·
  <a href="https://github.com/Codename-11/hermes-relay/releases">Releases</a> ·
  <a href="CHANGELOG.md">Changelog</a> ·
  <a href="https://hermes-agent.nousresearch.com">Hermes Agent</a>
</p>

---

## Quick Start

Two steps: install the Android app on your phone, then install the plugin on your Hermes server.

### 1. Install the Android app

<!-- TODO: Uncomment when Play Store listing is live
<a href="https://play.google.com/store/apps/details?id=com.hermesandroid.relay"><img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="80"></a>
-->

- **Google Play** — coming soon
- **APK** — download from [GitHub Releases](https://github.com/Codename-11/hermes-relay/releases)

### 2. Install the server plugin (one-liner)

On the machine running your Hermes agent:

```bash
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash
```

This installs the `hermes-android` plugin — 14 `android_*` device control tools plus the `hermes pair` CLI command. After restarting hermes, generate a pairing QR code:

```bash
hermes pair
```

Scan it from the Android app's onboarding screen and you're connected. The command also prints the server URL and API key as plain text, so you can pair manually if your terminal can't render QR blocks.

**Requirements:** Android 8.0+ (SDK 26), [hermes-agent](https://github.com/NousResearch/hermes-agent) v0.8.0+ (for the `hermes pair` CLI), Python 3.11+.

## What It Does

Talk to your Hermes agent from anywhere. Direct API streaming, session history, tool visualization — all native on Android.

| Channel | What | Status |
|---------|------|--------|
| **Chat** | Stream conversations to Hermes via HTTP/SSE | Available |
| **Terminal** | Secure remote shell via tmux | Phase 2 |
| **Bridge** | Agent controls the phone — taps, types, screenshots | Phase 3 |

## Features

- **Streaming chat** — Direct SSE to the Hermes API Server with real-time markdown rendering
- **Session management** — Create, switch, rename, delete chat sessions
- **Tool visualization** — See agent tool calls as they execute (compact or detailed cards)
- **Personalities** — Switch between agent personalities with a picker
- **Slash commands** — 29+ gateway commands, searchable command palette
- **File attachments** — Send images, documents, any file type
- **Message queuing** — Send messages while the agent is still streaming
- **Analytics** — Stats for Nerds with TTFT, token usage, stream health
- **Security** — Encrypted local storage (AES-256-GCM), HTTPS enforced
- **QR pairing** — Scan a QR code to auto-configure your server connection

## Getting Started

1. **Install the app** from the link above
2. **Enter your Hermes server URL** (e.g. `http://192.168.1.100:8642`) during onboarding
3. **Start chatting** — the app connects directly to the Hermes API Server

For detailed setup, server configuration, and feature guides, see the **[full documentation](https://codename-11.github.io/hermes-relay/)**.

## How It Works

```
Phone (HTTP/SSE) --> Hermes API Server (:8642)   [chat — direct]
Phone (WSS)      --> Relay Server (:8767)         [terminal, bridge — future]
```

Chat connects directly to the Hermes API Server — same pattern used by Open WebUI and other Hermes frontends. The relay server is a separate lightweight Python service for terminal and bridge channels (coming in Phase 2/3).

## Documentation

| | |
|---|---|
| **[User Guide](https://codename-11.github.io/hermes-relay/)** | **Getting started, features, configuration — start here** |
| [Architecture](https://codename-11.github.io/hermes-relay/architecture/) | How the app works under the hood |
| [API Reference](https://codename-11.github.io/hermes-relay/reference/api.html) | Hermes API endpoints used by the app |
| [Specification](docs/spec.md) | Full spec — protocol, UI, phases, dependencies |
| [Architecture Decisions](docs/decisions.md) | ADRs — framework, channels, auth, terminal |
| [Changelog](CHANGELOG.md) | Release history |

---

## Development

### Quick Start

1. **File > Open** the repo root in Android Studio
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
```

### Repository Structure

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

### Tech Stack

| Component | Stack |
|-----------|-------|
| **Android App** | Kotlin 2.0, Jetpack Compose, Material 3, OkHttp |
| **Relay Server** | Python 3.11+, aiohttp |
| **Serialization** | kotlinx.serialization |
| **Build** | AGP 9, Gradle 8.13, JVM toolchain 17 |
| **CI/CD** | GitHub Actions (lint, build, test, APK artifact) |
| **Min SDK** | 26 (Android 8.0) / Target SDK 35 |

### Relay Server (optional — terminal/bridge only)

```bash
pip install aiohttp pyyaml && python -m relay_server --no-ssl
```

Or with Docker:

```bash
docker build -t hermes-relay relay_server/ && docker run -d --network host --name hermes-relay hermes-relay
```

See [docs/relay-server.md](docs/relay-server.md) for TLS, systemd, and full setup.

### Hermes Plugin (for contributors)

End users should install via the [one-liner](#2-install-the-server-plugin-one-liner) at the top. For local development from a clone:

```bash
cp -r plugin ~/.hermes/plugins/hermes-android
# Or symlink for live edits:
ln -s "$PWD/plugin" ~/.hermes/plugins/hermes-android
```

Then restart hermes and run `hermes pair` to test the CLI command. The 14 `android_*` tools register regardless of hermes-agent version; the `hermes pair` CLI requires v0.8.0+.

## Hermes Agent

Hermes Relay is built for [Hermes Agent](https://github.com/NousResearch/hermes-agent) — an open-source AI agent platform by [Nous Research](https://nousresearch.com). See the [Hermes Agent docs](https://hermes-agent.nousresearch.com) for server setup, gateway configuration, and plugin development.

## License

[MIT](LICENSE) — Copyright (c) 2026 [Axiom-Labs](https://codename-11.dev)

---

<p align="center">
  Built with the help of Humans and AI Agents<br><br>
  <a href="https://ko-fi.com/L4L31Q8LJ1"><img src="https://ko-fi.com/img/githubbutton_sm.svg" alt="Support on Ko-fi"></a>
</p>
