# Hermes Companion

> Native Android client for the Hermes agent platform.

**Status:** MVP — Phase 0 + Phase 1 complete  
**Repo:** [Codename-11/hermes-android](https://github.com/Codename-11/hermes-android) (private)

---

## What This Is

A native Android companion app for [Hermes agent](https://github.com/NousResearch/hermes-agent). Three capabilities in one app:

| Channel | Direction | What |
|---------|-----------|------|
| **Chat** | Phone ↔ Agent | Talk to any Hermes agent profile with full streaming |
| **Terminal** | Phone ↔ Server | Secure remote shell access via tmux |
| **Bridge** | Agent → Phone | Agent controls the phone (taps, types, screenshots) |

One persistent WSS connection. One pairing flow. Three multiplexed channels.

## Architecture

```
┌──────────────────────────────┐
│   Android App (Compose)      │
│   Chat │ Terminal │ Bridge   │
│         ↕ WSS (TLS)         │
└─────────┬────────────────────┘
          │
┌─────────┴────────────────────┐
│   Companion Relay (Python)   │
│   Port 8767                  │
│                              │
│   Chat → WebAPI proxy        │
│   Terminal → tmux/PTY        │
│   Bridge → AccessibilityServ │
└──────────────────────────────┘
```

## Repository Structure

```
hermes-android/
├── app/                       # Android app module (Kotlin + Jetpack Compose)
├── build.gradle.kts           # Root Gradle config
├── settings.gradle.kts
├── gradle/                    # Wrapper + version catalog
├── gradlew / gradlew.bat
├── scripts/                   # Dev helper scripts
├── companion_relay/           # WSS relay server (Python + aiohttp)
├── plugin/                    # Hermes agent plugin (14 android_* tools)
├── docs/                      # Spec, decisions, security
├── .github/workflows/         # CI + release pipelines
├── CLAUDE.md                  # Agent development context
├── AGENTS.md                  # Tool usage patterns
└── DEVLOG.md                  # Development log
```

## Quick Start

### Open in Android Studio

1. **File > Open** → select the repo root (`hermes-android/`)
2. Wait for Gradle sync
3. Click **Run** (Shift+F10) to deploy to emulator or connected device

### Dev Scripts

```bash
scripts/dev.bat build      # Build debug APK
scripts/dev.bat install    # Build + install to connected device
scripts/dev.bat run        # Build + install + launch + logcat
scripts/dev.bat test       # Run unit tests
scripts/dev.bat lint       # Run lint checks
scripts/dev.bat clean      # Clean build outputs
scripts/dev.bat devices    # List connected devices
scripts/dev.bat relay      # Start companion relay (dev mode)
```

### Wireless Debugging (Android)

1. **Settings > Developer Options > Wireless debugging** → enable
2. Tap **Pair device with pairing code**
3. Run: `scripts/dev.bat wireless <ip:port> <pairing-code>`
4. Then: `adb connect <ip:port>` (main wireless debugging port)

### Start Companion Relay

```bash
pip install -r companion_relay/requirements.txt
python -m companion_relay --no-ssl --log-level DEBUG
```

### Install as Hermes Plugin

```bash
cp -r plugin ~/.hermes/plugins/hermes-android
# Restart hermes — /plugins should show: ✓ hermes-android (14 tools)
```

## Tech Stack

| Component | Stack |
|-----------|-------|
| **Android App** | Kotlin 2.0, Jetpack Compose, Material 3, OkHttp WSS |
| **Companion Relay** | Python 3.11+, aiohttp |
| **Serialization** | kotlinx.serialization |
| **Build** | AGP 8.13, Gradle 8.13, JVM toolchain 17 |
| **CI/CD** | GitHub Actions (lint → build → test → APK artifact) |
| **Min SDK** | 26 (Android 8.0) |

## Current State

- **Phase 0** — Complete: Compose scaffold, WSS connection manager, channel multiplexer, auth flow
- **Phase 1** — Complete: companion relay, chat channel proxy, streaming chat UI, profile selector
- **Phase 2** — Next: terminal channel (xterm.js + tmux)
- **Phase 3** — Next: bridge channel migration

See [docs/spec.md](docs/spec.md) for the full specification and [docs/decisions.md](docs/decisions.md) for architecture rationale.

## Related Projects

- [hermes-agent](https://github.com/NousResearch/hermes-agent) — the agent platform
- [ARC](https://github.com/Codename-11/ARC) — CI/CD patterns reference
- [ClawPort](https://github.com/Codename-11/clawport-ui) — web dashboard

---

*Originally forked from [raulvidis/hermes-android](https://github.com/raulvidis/hermes-android).*
