<p align="center">
  <img src="assets/logo.svg" alt="Hermes-Relay" width="120">
</p>

<h1 align="center">Hermes-Relay</h1>

<p align="center">
  <strong>One Hermes agent. Two ways to use it.</strong><br>
  A native Android remote-control app for your phone, plus a desktop CLI that lets you<br>
  use a server-deployed Hermes from your laptop as if it were running locally.
</p>

<p align="center">
  <a href="https://opensource.org/licenses/MIT"><img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="MIT"></a>
  <a href="https://developer.android.com"><img src="https://img.shields.io/badge/Surface%201-Android-green.svg" alt="Android"></a>
  <a href="https://github.com/Codename-11/hermes-relay/tree/main/desktop"><img src="https://img.shields.io/badge/Surface%202-Desktop%20CLI-orange.svg" alt="Desktop CLI"></a>
  <a href="https://github.com/Codename-11/hermes-relay/actions/workflows/ci.yml"><img src="https://github.com/Codename-11/hermes-relay/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://developer.android.com/about/versions/oreo"><img src="https://img.shields.io/badge/Min%20SDK-26-brightgreen.svg" alt="Min SDK 26"></a>
</p>

<p align="center">
  <a href="https://codename-11.github.io/hermes-relay/">Documentation</a> ·
  <a href="https://github.com/Codename-11/hermes-relay/releases">Releases</a> ·
  <a href="CHANGELOG.md">Changelog</a> ·
  <a href="https://hermes-agent.nousresearch.com">Hermes Agent</a>
</p>

<p align="center">
  <video src="https://github.com/Codename-11/hermes-relay/raw/main/assets/chat_demo.mp4" poster="https://github.com/Codename-11/hermes-relay/raw/main/assets/chat_demo_poster.jpg" autoplay loop muted playsinline width="280"></video>
</p>

---

## Two surfaces, one pair

| Surface | What | Status |
|---------|------|--------|
| **[Android app](#1a-android-app)** | Native phone control — chat, voice, the agent reads your screen and acts on it (tap, type, swipe), notification companion, multi-Connection. | Available — Google Play (Internal testing) + sideload APK |
| **[Desktop CLI](#1b-desktop-cli-experimental)** | Use a server-deployed Hermes from your laptop **like it's local** — same shell, same TUI, same `Win+Shift+S` → `Ctrl+A v` paste flow, same conversation continuity. The remote agent can also reach back through the relay and run tools on YOUR machine. | **Experimental** — `desktop-v0.3.0-alpha.14` (one-binary install, no Node required) |

Both share `~/.hermes/remote-sessions.json` and the same WSS relay. **Pair once from either, both work.**

---

## Quick Start

Three steps: pick your surface (or install both), then install the relay plugin on your Hermes server.

### 1a. Android app

<!-- TODO: Uncomment when Play Store listing is live
<a href="https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay"><img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="80"></a>
-->

- **Google Play** — coming soon (currently on Internal testing)
- **APK** — download from [GitHub Releases](https://github.com/Codename-11/hermes-relay/releases/latest)

#### Sideload APK (GitHub Releases)

Prefer not to wait for Google Play? Grab the signed APK directly:

1. Download the file ending in **`-sideload-release.apk`** from [the latest release](https://github.com/Codename-11/hermes-relay/releases/latest) — that's the full-featured "Hermes Dev" build. (Skip any `.aab` file — those are the Google Play bundle format and won't install directly.)
2. On your phone: **Settings → Apps → Special app access → Install unknown apps** and allow your browser (first time only).
3. Open the APK from your downloads and tap **Install**.
4. Optionally verify integrity against `SHA256SUMS.txt` from the same release (`sha256sum` on macOS/Linux, `Get-FileHash -Algorithm SHA256` on Windows).

Full walkthrough, including signing-certificate fingerprint: [Sideload guide](https://codename-11.github.io/hermes-relay/guide/getting-started.html#sideload-apk).

**Staying up to date (sideload):** the app checks GitHub for a newer release on cold start (at most once every 6 hours) and shows a dismissable banner when you're behind. Tapping **Update** opens the next APK in your browser so Android's Downloads notification hands it to the system installer — no second app required. You can also trigger a check manually under **Settings → About → Updates**. Google Play installs get auto-updates through the Play Store and don't show this banner.

### 1b. Desktop CLI (experimental)

A single-binary thin client (`hermes-relay`) that talks to a server-deployed Hermes over WSS — same shell, same TUI, same `/paste` flow as a local install. The remote agent can also reach back through the relay and run `desktop_read_file`, `desktop_terminal`, `desktop_search_files`, `desktop_screenshot`, `desktop_clipboard_*`, `desktop_open_in_editor`, etc. **on your machine** while its brain stays on the host. One pair, two surfaces (with the Android app), no `ssh`.

**Install** (Windows PowerShell):

```powershell
irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.ps1 | iex
```

**Install** (macOS / Linux):

```bash
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.sh | sh
```

```bash
hermes-relay pair --remote ws://<host>:8767   # once
hermes-relay                                   # interactive Hermes TUI in tmux
hermes-relay "summarize the last commit"       # one-shot
hermes-relay --json "..." | jq                 # structured events for scripting
hermes-relay daemon                            # headless tool router (agent reaches you anytime)
hermes-relay update                            # self-update via GitHub Releases
```

**Native paste workflow** (the killer demo): inside `hermes-relay shell`, hit `Win+Shift+S` to screenshot, then `Ctrl+A v` — the client reads your clipboard, ships the image to the server's inbox, and types `/paste` into the TUI for you. Identical UX to native local-Hermes paste. The same chord set works on macOS (`Cmd+Shift+4` → `Ctrl+A v`) and Linux (Wayland/X11 detected automatically).

**No Node required** — Bun-compiled native binaries (~60–110 MB per platform) installed via curl/irm. Version-aware install (`upgrading X → Y`), collision-safe `hermes` short alias, self-update via `hermes-relay update`. Binaries are **unsigned** during the experimental phase — SmartScreen/Gatekeeper warnings are expected; the install scripts show the one-line escape hatches. Code signing, multi-client server-side routing, and service installers (sc.exe / systemd / launchd) land with v1.0.

- **Docs**: [Desktop CLI guide](https://codename-11.github.io/hermes-relay/desktop/) · [`desktop/README.md`](desktop/README.md)
- **Release track**: tagged `desktop-v*`, [separate from Android](https://github.com/Codename-11/hermes-relay/releases?q=desktop)
- **AI-agent setup recipe**: `/hermes-relay-desktop-setup` (the agent can run `desktop_terminal` on your machine to diagnose install/pair issues live)

### 2. Install the server plugin (one-liner)

On the machine running your Hermes agent:

```bash
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash
```

The installer clones Hermes-Relay to `~/.hermes/hermes-relay/` (override with `$HERMES_RELAY_HOME`), `pip install -e`s the package into the hermes-agent venv, registers the `skills/` directory in your `~/.hermes/config.yaml` under `skills.external_dirs` (so updates flow through `git pull`), symlinks the plugin into `~/.hermes/plugins/hermes-relay`, drops a thin `hermes-pair` shim into `~/.local/bin/`, and (optionally) installs a systemd user service for the WSS relay. After restart, pair your client via either of these equivalent entry points:

- **From any Hermes chat surface** (CLI, Discord, Telegram, etc.): type `/hermes-relay-pair` and the `hermes-relay-pair` skill renders the QR + 6-char code inline. Shortest path if you're already chatting with the agent.
- **From a shell**: `hermes-pair` (dashed) — a thin wrapper around `python -m plugin.pair` in the hermes-agent venv. Use this in scripts or when you want the raw output.
- **No camera?** `hermes-pair --register-code ABCD12` — manual fallback for SSH-only / camera-less setups. For Android: read the 6-char code from the app's **Settings → Connection → Manual pairing code (fallback)** card, pre-register it on the host with this command, then tap **Connect** in the app. For the desktop CLI: just pass it as `hermes-relay pair ABCD12 --remote ws://<host>:8767`. Composes with `--ttl` / `--grants`.

Scan the QR from the Android app's onboarding screen, OR paste the 6-char code into `hermes-relay pair --remote ws://<host>:8767` on your laptop, and you're connected. One pair configures **both** the direct-chat API server **and** the WSS relay (for terminal / bridge / desktop tools) — if a local relay is running at `localhost:8767`, the pair command pre-registers a fresh 6-char pairing code with it and embeds the relay URL + code in the same QR. If you only want direct chat from the Android app, pass `--no-relay` (or just don't start the relay). Plain-text connection details are always printed alongside the QR so you can copy values by hand if your terminal can't render QR blocks.

**Dashboard plugin.** If your hermes-agent install has the Dashboard Plugin System (upstream `axiom` branch), Hermes-Relay ships a plugin at `plugin/dashboard/` that surfaces paired devices, bridge command activity, and active inbound-media tokens in the gateway's web UI. It auto-registers through the same `~/.hermes/plugins/hermes-relay` symlink created by `install.sh` — restart the gateway and a "Relay" tab appears. See [docs/relay-server.md](docs/relay-server.md) and `user-docs/features/dashboard.md` for details.

**Updating:** `hermes-relay-update` (shortest path — installed as part of the one-liner) or re-run the same `curl … | bash` from above. Both are equivalent and fully idempotent: pulls latest main, refreshes the editable install, recreates all three shims, restarts `hermes-relay`, and prompts before restarting `hermes-gateway`. Set `HERMES_RELAY_RESTART_GATEWAY=1` to opt into the gateway restart non-interactively. For routine plugin/skill updates without restarting anything, a plain `cd ~/.hermes/hermes-relay && git pull` is enough — the editable install picks up the new code on next process start.

**Uninstalling:** `bash ~/.hermes/hermes-relay/uninstall.sh` reverses every install step in the opposite order. Idempotent, never touches state shared with other Hermes tools (`.env`, sessions DB, hermes-agent venv core). Flags: `--dry-run`, `--keep-clone`, `--remove-secret`. Or pull the script via curl if you've already removed the clone.

**Requirements:** Android 8.0+ (SDK 26) for the Android app · macOS / Linux / Windows for the desktop CLI · [hermes-agent](https://github.com/NousResearch/hermes-agent) v0.8.0+, Python 3.11+ on the server.

### For AI Agents

If you have an AI assistant (Claude, GPT, etc.) and want it to install or maintain Hermes-Relay for you, paste the block below into the chat. The agent will fetch the canonical setup recipe from this repo and walk you through it — verification, pairing, troubleshooting included.

```text
You are helping me install and maintain Hermes-Relay (https://github.com/Codename-11/hermes-relay) — a native Android client + a desktop CLI + a Python plugin for the Hermes AI agent platform.

Read the canonical setup recipe before acting:
  https://raw.githubusercontent.com/Codename-11/hermes-relay/main/skills/devops/hermes-relay-self-setup/SKILL.md

Then guide me through:
- Verifying hermes-agent is already installed (it's a prerequisite — Hermes-Relay is a plugin, not standalone)
- Running the server-plugin install one-liner: `curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash`
- Pairing my phone via `hermes-pair` or `/hermes-relay-pair` (Android), OR pairing my laptop via the `hermes-relay` desktop CLI (binary one-liner: `curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.sh | sh` or `irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.ps1 | iex` on Windows, then `hermes-relay pair --remote ws://<host>:8767`)
- Verifying with `hermes-status` (server) or `hermes-relay doctor` (desktop CLI)

Always confirm before running shell commands. Never restart hermes-gateway without asking. If any step fails, consult the Troubleshooting section in the SKILL.md and ask me for the exact error.
```

Already have Hermes-Relay installed? The same recipe is auto-loaded as a Hermes skill — invoke it from any chat with `/hermes-relay-self-setup` for re-setup, troubleshooting, or "is everything wired correctly?" checks. Single source, two delivery modes (raw URL pre-install + Hermes skill post-install), no drift.

## What It Does

Talk to your Hermes agent from anywhere. Direct API streaming, session history, tool visualization — native on Android, native in the terminal, with the agent able to reach back through the relay and act on either surface.

| Surface | Channel | What | Status |
|---------|---------|------|--------|
| Android | **Chat** | Stream conversations to Hermes via HTTP/SSE | Available |
| Android | **Voice** | Real-time voice conversation via relay TTS/STT | Available |
| Android | **Bridge** | Agent reads the screen and performs UI actions (tap, long-press, drag, type, clipboard, media, macros, events) | Available |
| Android | **Terminal** | Secure remote shell via tmux | Phase 2 |
| Desktop CLI | **Shell** | Full Hermes Ink TUI piped over PTY in tmux on the host. Bare `hermes-relay` drops you in. | Available (experimental) |
| Desktop CLI | **Chat** | Structured-event REPL / one-shot / piped stdin. `--json` for scripting. REPL supports `/paste`, `/screenshot`, `/image <path>`. | Available (experimental) |
| Desktop CLI | **In-shell paste / screenshot** | `Ctrl+A v` (clipboard image → server inbox → `/paste` auto-typed). `/screenshot` is multi-monitor by default. | Available (experimental) |
| Desktop CLI | **Local tool routing** | Agent calls `desktop_read_file` / `_write_file` / `_terminal` / `_search_files` / `_patch` / `_clipboard_*` / `_screenshot` / `_open_in_editor` — runs on YOUR machine over the same relay | Available (experimental) |
| Desktop CLI | **Daemon** | Headless tool router — keeps tools advertised even when no shell is open | Available (experimental) |
| Desktop CLI | **Self-update** | `hermes-relay update` polls GitHub Releases, atomic-swaps the binary | Available (experimental) |

## What's new in v0.6.0

- **Connect from anywhere** — multi-endpoint pairing with first-class Tailscale support; plug in any VPN or reverse proxy mode. See [`docs/remote-access.md`](docs/remote-access.md).
- **Multi-Connection support** — pair with multiple Hermes servers (home + work, dev + prod, etc.) and switch in one tap from the Chat top bar. Each Connection keeps its own sessions, personalities, profiles, and relay state; theme and safety preferences stay global. Existing installs migrate transparently.
- **Agent Profiles** — the relay auto-discovers upstream Hermes profiles at `~/.hermes/profiles/*/` and the phone overlays the selected profile's model + `SOUL.md` on chat turns. Ephemeral, chat-only, clears on Connection switch. Gated by `RELAY_PROFILE_DISCOVERY_ENABLED` (default on).
- **Consolidated agent sheet** — Profile + Personality selection and per-session analytics now live in one scrollable bottom sheet opened from the Chat top-bar agent name.

See the [changelog](CHANGELOG.md) for the full list.

## Features

### Android

- **Streaming chat** — Direct SSE to the Hermes API Server with real-time markdown rendering, session history, tool-call visualization, personality picker, searchable command palette (29+ gateway commands), file attachments, and send-while-streaming message queuing
- **Multi-Connection + agent profiles** — Pair with multiple Hermes servers and switch targets from the top bar; select an upstream-discovered agent profile to overlay model + `SOUL.md` on chat turns. Three-layer model: Connection (server) → Profile (agent directory) → Personality (prompt preset)
- **Voice mode** — Real-time voice conversation via the relay; the sphere listens with you and performs the agent's reply as it speaks. Uses your server's configured TTS/STT providers (Edge TTS, ElevenLabs, OpenAI, MiniMax, Mistral, NeuTTS / faster-whisper, Groq, OpenAI Whisper)
- **Phone control (bridge)** — The agent can read what's on screen and act on it — tap, long-press, drag, swipe, scroll, type, and press system keys — plus take screenshots, read/write the clipboard, and control system-wide media playback. Gesture reliability is hardened for dim/idle screens, and a smarter tap-fallback cascade handles apps where labels sit inside non-clickable wrappers
- **Screen understanding** — Filtered accessibility-tree search, per-node property lookups with stable IDs, cheap screen-hash change detection, and multi-window reads (system overlays, popups, notification shade) so the agent can reason about UI without guessing
- **Workflow automation** — Batched macro execution for multi-step flows, real-time accessibility event streaming for "wait until something happens" waits, and a raw-Intent escape hatch for apps that expose deep-link actions
- **Notification companion** — Opt-in notification access so the agent can triage, summarize, and route incoming notifications
- **Bridge safety rails** — Per-app blocklist (banking, payments, 2FA default-blocked), destructive-verb confirmation modal (send, pay, delete, transfer…), idle auto-disable timer, optional persistent-status overlay, full activity log
- **Security & pairing** — QR-code pairing, Android Keystore session storage (StrongBox-preferred), TOFU cert pinning, per-channel time-bound grants, user-chosen session TTL
- **Analytics** — Stats for Nerds with TTFT, token usage, stream health, and peak-time charts

> Sideload builds add direct SMS, contact search, one-tap dialing, and location awareness — handy for fully hands-free voice intents like "text Sam I'll be 10 minutes late". See [Release tracks](https://codename-11.github.io/hermes-relay/guide/release-tracks) for the full sideload capability matrix.

### Desktop CLI

- **Shell mode (default)** — bare `hermes-relay` pipes the host's actual `hermes` Ink TUI through a PTY in tmux. Same banner, same skin, same slash commands as a local install. `Ctrl+A .` detaches (preserves tmux), `Ctrl+A k` kills, `Ctrl+A v` pastes a clipboard image, `Ctrl+A ?` re-prints chord help, `Ctrl+A Ctrl+A` literal.
- **Chat mode** — REPL or one-shot or piped stdin. `--json` emits `GatewayEvent`s per line for `jq` / automation. REPL slash commands `/paste` (clipboard), `/screenshot` (multi-monitor by default; `primary` / `1` / `2` to narrow), `/image <path>` attach the next message.
- **Local tool routing** — agent calls `desktop_read_file`, `desktop_write_file`, `desktop_terminal`, `desktop_search_files`, `desktop_patch`, `desktop_clipboard_read/write`, `desktop_screenshot`, `desktop_open_in_editor` — all run on YOUR machine over the same WSS relay. One-time per-URL consent gate; `--no-tools` kill-switch; non-TTY stdin fails closed; agent-proposed patches render as colored diffs with `y/n/e/r` interactive approval.
- **Daemon mode** — `hermes-relay daemon` runs the tool router headless so the agent can reach you even when no shell is open. JSON-line lifecycle logs by default, auto-human on TTY. Fails closed on missing consent.
- **Self-update** — `hermes-relay update` polls GitHub Releases (SemVer-max picker, prerelease-aware), verifies SHA256, atomic-swaps the binary on POSIX (running daemon keeps inode), cooperative `.new.exe` swap on Windows.
- **Multi-endpoint pairing + reconnect-on-drop + TOFU cert pinning** — same as the Android app. One QR carries LAN + Tailscale + public; client races candidates in priority order, re-probes on every network change.
- **Workspace awareness** — on connect, client advertises `cwd`, `git_root`, `git_branch`, `repo_name`, `hostname`, `platform`, `active_shell` to the relay (server-side prompt-context consumption coming).
- **Conversation picker on attach** — without `--conversation` / `--new`, you get a numbered list of recent server-side hermes sessions to resume.
- **One install, one binary, no Node required** — Bun-compiled native binaries via curl/irm one-liners; collision-safe `hermes` short alias auto-installed.

## Getting Started

**Android:**

1. **Install the app** from the [link above](#1a-android-app)
2. **Enter your Hermes server URL** (e.g. `http://192.168.1.100:8642`) during onboarding, or scan a QR via `/hermes-relay-pair`
3. **Start chatting** — the app connects directly to the Hermes API Server

**Desktop CLI:**

1. **Install the binary** — [PowerShell `irm`](#1b-desktop-cli-experimental) (Windows) / curl (macOS / Linux) one-liner
2. **Pair once** — `hermes-relay pair --remote ws://<host>:8767` (mint code via `hermes-pair` or `/hermes-relay-pair` on the server first)
3. **Drop into the shell** — bare `hermes-relay` opens the full Hermes TUI in tmux on the host

For detailed setup, server configuration, and feature guides, see the **[full documentation](https://codename-11.github.io/hermes-relay/)**.

## How It Works

```
Phone        (HTTP/SSE) --> Hermes API Server (:8642)   [chat — direct]
Phone        (WSS)      --> Relay Server (:8767)         [voice, bridge, notifications]
Desktop CLI  (WSS)      --> Relay Server (:8767)         [tui, terminal, desktop tools]
```

Chat from the Android app connects directly to the Hermes API Server — same pattern used by Open WebUI and other Hermes frontends. Voice and bridge use a separate WSS relay (`:8767`). The desktop CLI connects exclusively to that same relay — `tui` channel for chat / shell, `desktop` channel for client-side tool routing — so one relay process serves both surfaces, and one pair grants access to either.

## Documentation

| | |
|---|---|
| **[User Guide](https://codename-11.github.io/hermes-relay/)** | **Getting started, both surfaces, features, configuration — start here** |
| [Android](https://codename-11.github.io/hermes-relay/guide/) | Android-specific install + setup + features |
| [Desktop CLI](https://codename-11.github.io/hermes-relay/desktop/) | Desktop CLI guide — shell/chat, pairing, subcommands, local tool routing |
| [Architecture](https://codename-11.github.io/hermes-relay/architecture/) | How the system works under the hood |
| [API Reference](https://codename-11.github.io/hermes-relay/reference/api.html) | Hermes API endpoints used by both surfaces |
| [Specification](docs/spec.md) | Full spec — protocol, UI, phases, dependencies |
| [Architecture Decisions](docs/decisions.md) | ADRs — framework, channels, auth, terminal |
| [Changelog](CHANGELOG.md) | Release history (Android `v*` and desktop `desktop-v*`) |

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
├── desktop/                   # Desktop CLI thin-client (@hermes-relay/cli — TS + Bun-compiled binary)
├── relay_server/              # WSS relay server (Python + aiohttp; thin shim → plugin/relay)
├── plugin/                    # Hermes agent plugin
│   ├── relay/                 #   - canonical relay (server.py, channels/, media, voice, desktop tools)
│   ├── tools/                 #   - 18 android_* + 9 desktop_* tool handlers
│   └── pair.py                #   - QR pairing CLI + multi-endpoint payload builder
├── skills/                    # Hermes agent skills
│   └── devops/
│       ├── hermes-relay-pair/         # /hermes-relay-pair slash-command skill
│       ├── hermes-relay-self-setup/   # AI-agent setup recipe (Android + desktop)
│       └── hermes-relay-desktop-setup/ # AI-agent recipe specifically for the desktop CLI
├── user-docs/                 # VitePress documentation site (Android + desktop sections)
├── docs/                      # Spec, decisions, security
├── scripts/                   # Dev helper scripts
├── .github/workflows/         # CI + release pipelines (ci-android / ci-relay / ci-desktop)
└── gradle/                    # Wrapper (8.13) + version catalog
```

### Tech Stack

| Component | Stack |
|-----------|-------|
| **Android App** | Kotlin 2.0, Jetpack Compose, Material 3, OkHttp |
| **Desktop CLI** | TypeScript, Bun-compiled native binary, Node ≥21 (source/dev), zero runtime deps |
| **Relay Server** | Python 3.11+, aiohttp |
| **Serialization** | kotlinx.serialization (Android) |
| **Build** | AGP 9, Gradle 8.13, JVM toolchain 17 (Android); `tsc` + `bun build --compile` (desktop) |
| **CI/CD** | GitHub Actions (lint, build, test, APK artifact, desktop binaries per platform) |
| **Min SDK** | 26 (Android 8.0) / Target SDK 35 |

### Relay Server (optional — terminal/bridge only)

```bash
hermes relay start --no-ssl          # if you installed the plugin
# or from a repo checkout:
python -m plugin.relay --no-ssl
```

Or with Docker:

```bash
docker build -t hermes-relay relay_server/ && docker run -d --network host --name hermes-relay hermes-relay
```

See [docs/relay-server.md](docs/relay-server.md) for TLS, systemd, and full setup.

### Hermes Plugin (for contributors)

End users should install via the [one-liner](#2-install-the-server-plugin-one-liner) at the top. For local development from a clone:

```bash
cp -r plugin ~/.hermes/plugins/hermes-relay
# Or symlink for live edits:
ln -s "$PWD/plugin" ~/.hermes/plugins/hermes-relay
```

Then restart hermes and run `hermes-pair` (dashed shell shim) or type `/hermes-relay-pair` in any Hermes chat surface to verify pairing. The 18 `android_*` and 9 `desktop_*` tools register regardless of hermes-agent version. **Note:** a top-level `hermes pair` CLI sub-command is *not* currently exposed — hermes-agent v0.8.0's top-level argparser doesn't yet forward to third-party plugins' `register_cli_command()` dict. Use the slash command or the dashed shim instead.

## Hermes Agent

Hermes-Relay is built for [Hermes Agent](https://github.com/NousResearch/hermes-agent) — an open-source AI agent platform by [Nous Research](https://nousresearch.com). See the [Hermes Agent docs](https://hermes-agent.nousresearch.com) for server setup, gateway configuration, and plugin development.

## Found a bug? Let us know!

This is an indie project and every report helps shape where it goes next. If something feels off, broken, or just weird — [open an issue](https://github.com/Codename-11/hermes-relay/issues/new). We read every one, and even a one-line "this didn't work on my Pixel 7" / "the alpha.14 Windows binary segfaults on my Surface" is genuinely useful.

## Star History

<a href="https://www.star-history.com/?repos=Codename-11%2Fhermes-relay&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=Codename-11/hermes-relay&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=Codename-11/hermes-relay&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=Codename-11/hermes-relay&type=date&legend=top-left" />
 </picture>
</a>

## License

[MIT](LICENSE) — Copyright (c) 2026 [Axiom-Labs](https://codename-11.dev)

---

<p align="center">
  Built with the help of Humans and AI Agents<br><br>
  <a href="https://ko-fi.com/L4L31Q8LJ1"><img src="https://ko-fi.com/img/githubbutton_sm.svg" alt="Support on Ko-fi"></a>
</p>
