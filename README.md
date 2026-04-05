# Hermes Companion

> Give your AI agent hands — and a first-party mobile client.

**Status:** Pre-MVP — scaffolding + upstream bridge  
**Repo:** [Codename-11/hermes-android](https://github.com/Codename-11/hermes-android) (private)  
**Upstream:** [raulvidis/hermes-android](https://github.com/raulvidis/hermes-android) (forked)

---

## What This Is

A native Android companion app for the [Hermes agent platform](https://github.com/NousResearch/hermes-agent). Three capabilities in one app:

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
├── hermes-android-bridge/     # Android app (Kotlin + Jetpack Compose)
│   ├── app/src/main/kotlin/   # Source — 12 Kotlin files
│   ├── build.gradle.kts       # App-level build config
│   └── settings.gradle.kts    # Project settings
├── companion-relay/           # Server-side relay (Python) — TBD
├── hermes-android-plugin/     # Hermes agent plugin (14 android_* tools)
├── tools/                     # Standalone Python toolset (dev/test)
├── tests/                     # Python tests
├── skills/                    # Agent skills for Android interaction
├── docs/                      # Project documentation
│   ├── spec.md                # Full specification (protocol, UI, phases)
│   ├── decisions.md           # Architecture decisions & rationale
│   ├── security.md            # Security model
│   └── plan.md                # Original build plan
├── CLAUDE.md                  # Agent handoff & conventions
├── AGENTS.md                  # Hermes agent context (tool patterns)
└── DEVLOG.md                  # Development log
```

## Quick Start

### Build the Android app

```bash
cd hermes-android-bridge
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Install as Hermes plugin

```bash
mkdir -p ~/.hermes/plugins
cp -r hermes-android-plugin ~/.hermes/plugins/hermes-android
# Restart hermes — /plugins should show: ✓ hermes-android v0.2.0 (14 tools)
```

### Connect

1. Open Hermes Bridge on phone → note the 6-char pairing code
2. Tell your agent: `Connect to my phone, code is <CODE>`

## Tech Stack

| Component | Stack |
|-----------|-------|
| **Android App** | Kotlin 2.0+, Jetpack Compose, Material 3, OkHttp WSS |
| **Companion Relay** | Python 3.11+, aiohttp, libtmux |
| **Serialization** | kotlinx.serialization (replacing Gson) |
| **Terminal** | xterm.js in WebView |
| **Min SDK** | 26 (Android 8.0) |

## Development

See [docs/spec.md](docs/spec.md) for the full specification — protocol details, UI layouts, implementation phases.

See [docs/decisions.md](docs/decisions.md) for architecture decisions and rationale.

See [CLAUDE.md](CLAUDE.md) for agent development conventions and handoff context.

## MVP Scope

**Phase 0** — Project setup: Compose scaffold, WSS connection manager, channel multiplexer, basic auth  
**Phase 1** — Chat channel: companion relay, WebAPI proxy, streaming chat UI, profile selector

See [docs/spec.md § 7-8](docs/spec.md) for the full phase breakdown.

## License

Private repository. See upstream for original license.
