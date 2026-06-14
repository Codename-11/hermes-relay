<p align="center">
  <img src="assets/logo.svg" alt="Hermes-Relay" width="120">
</p>

<h1 align="center">Hermes-Relay</h1>

<p align="center">
  <strong>Your Hermes agent, native on your phone.</strong><br>
  Chat, voice, and full agent management — it runs on your machine and lives on your devices —<br>
  plus an experimental CLI that gives the agent hands on any machine you pair.
</p>

<p align="center">
  <a href="https://opensource.org/licenses/MIT"><img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="MIT"></a>
  <a href="https://developer.android.com"><img src="https://img.shields.io/badge/Surface%201-Android-green.svg" alt="Android"></a>
  <a href="https://github.com/Codename-11/hermes-relay/tree/main/desktop"><img src="https://img.shields.io/badge/Surface%202-Desktop%20CLI%20%28alpha%29-orange.svg" alt="Desktop CLI (alpha)"></a>
  <a href="https://github.com/Codename-11/hermes-relay/actions/workflows/ci-android.yml"><img src="https://github.com/Codename-11/hermes-relay/actions/workflows/ci-android.yml/badge.svg" alt="Android CI"></a>
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
| **[Android app](#quick-start-android)** | Native phone client — streaming chat, hands-free voice, full agent management (models, keys, skills, profiles), and on sideload builds the agent can read your screen and act on it. | Available — Google Play (Internal testing) + sideload APK |
| **[Desktop CLI](#desktop-cli-alpha)** | The agent reaching back to **your machine** — local tool routing (files, terminal, screenshots, clipboard) plus a remote shell to the host. | **Alpha** — `desktop-v*` releases, expect heavy changes |

Both share the same WSS relay and credentials store. **Pair once from either, both work.**

---

## Quick Start (Android)

Install → connect → talk, in about two minutes. A vanilla [hermes-agent](https://github.com/NousResearch/hermes-agent) install is enough — chat, management, and voice need **no plugin**.

### 1. Install the app

- **Google Play** — coming soon (currently on Internal testing)
- **APK** — download the file ending in **`-sideload-release.apk`** from the newest `android-v*` release on [GitHub Releases](https://github.com/Codename-11/hermes-relay/releases) and open it (allow your browser to install unknown apps the first time). Full walkthrough — integrity verification, signing fingerprint, what's in each build — in the [Sideload guide](https://codename-11.github.io/hermes-relay/guide/getting-started.html#sideload-apk).

Sideload builds check GitHub for new releases and show a one-tap update banner when you're behind; Play builds update through the Play Store. See [Release tracks](https://codename-11.github.io/hermes-relay/guide/release-tracks) for the capability matrix.

### 2. Have Hermes running

Run upstream Hermes with its API server and dashboard enabled:

```bash
hermes setup --portal

mkdir -p ~/.hermes
API_SERVER_KEY="$(openssl rand -hex 32)"
cat >> ~/.hermes/.env <<EOF
API_SERVER_ENABLED=true
API_SERVER_HOST=0.0.0.0
API_SERVER_PORT=8642
API_SERVER_KEY=$API_SERVER_KEY
EOF

echo "Android API URL: http://<this-computer-ip>:8642"
echo "Android API key: $API_SERVER_KEY"
hermes gateway
```

Windows commands, dashboard auth notes, and upstream links: [Getting Started](https://codename-11.github.io/hermes-relay/guide/getting-started).

### 3. Connect and talk

Open the app, choose **Standard Hermes**, and enter your server's address and API key. The wizard probes everything and finishes with a capability card:

| Line | What it means |
|---|---|
| **Chat** | API server reachable — you can talk |
| **Manage** | Dashboard found — models, keys, skills, profiles from the phone |
| **Voice** | Speech ready via your server (or one Manage sign-in away) |
| **Remote** | Fallback route configured — keeps working away from home |
| **Relay** | Optional power tools — fine to leave unpaired |

If your dashboard requires sign-in, do it once under the **Manage** tab — the same session also unlocks voice. That's the whole standard setup.

**Going places?** Put your server's Tailscale URL in the setup form's "Remote access" field (or add a route any time under **Settings → Connections → Routes**). The app uses LAN at home and switches routes automatically when you leave. See [Remote access](https://codename-11.github.io/hermes-relay/guide/remote-access).

### 4. Optional: install Relay for power tools

Install the Relay plugin on the server only when you want Terminal, Bridge phone control, relay sessions, media routes, or the realtime voice engine:

```bash
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash
hermes relay start --no-ssl
hermes pair
```

The installer clones to `~/.hermes/hermes-relay/`, registers the plugin/skill paths, and can install a systemd user service. Scan the QR from the phone's Connections screen; if you can't scan, use `hermes pair --register-code ABCD12` with the manual code from Android **Settings → Connections → Advanced**. (`/hermes-relay-pair` and the dashed `hermes-pair` shim remain for chat-surface and older builds.)

- **Updating:** `hermes-relay-update` — idempotent; or re-run the install one-liner.
- **Uninstalling:** `bash ~/.hermes/hermes-relay/uninstall.sh` — reverses every step, never touches shared Hermes state. Flags: `--dry-run`, `--keep-clone`, `--remove-secret`.
- **Dashboard plugin:** installs with the same symlink — restart the gateway and a "Relay" tab (paired devices, bridge activity, media tokens) appears in the web UI.

Full server setup, TLS, and systemd details: [docs/relay-server.md](docs/relay-server.md).

**Requirements:** Android 8.0+ (SDK 26) · [hermes-agent](https://github.com/NousResearch/hermes-agent) v0.8.0+, Python 3.11+ on the server · macOS / Linux / Windows for the desktop CLI.

## Desktop CLI (alpha)

> **Alpha — expect heavy changes.** With [hermes-desktop](https://hermes-agent.nousresearch.com) now covering chat and management on the desktop, this surface is being refocused into a pure remote **"hands" connector**: the agent reaching back through the relay to run tools on your machine (files, terminal, screenshots, clipboard, editor). The chat and shell features that overlap hermes-desktop will be removed in a future release. Binaries are unsigned during the experimental phase — SmartScreen/Gatekeeper warnings are expected.

The agent's brain stays on the host; the CLI lets it call `desktop_read_file`, `desktop_terminal`, `desktop_search_files`, `desktop_screenshot`, `desktop_clipboard_*`, `desktop_open_in_editor`, and more **on your machine** over the same WSS relay — with a one-time consent gate, interactive diff approval for patches, and a `--no-tools` kill-switch. No Node required; installs are self-contained native binaries.

**Install** (Windows PowerShell / macOS / Linux):

```powershell
irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.ps1 | iex
```

```bash
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.sh | sh
```

```bash
hermes-relay pair --remote ws://<host>:8767   # once
hermes-relay daemon                            # headless tool router — agent reaches you anytime
hermes-relay                                   # interactive Hermes TUI in tmux (legacy, being refocused)
hermes-relay update                            # self-update via GitHub Releases
```

- **Docs:** [Desktop guide](https://codename-11.github.io/hermes-relay/desktop/) · [`desktop/README.md`](desktop/README.md)
- **Release track:** tagged `desktop-v*`, [separate from Android](https://github.com/Codename-11/hermes-relay/releases?q=desktop)
- **AI-agent setup recipe:** `/hermes-relay-desktop-setup`

## Features

### Android

- **Streaming chat** — direct SSE to the Hermes API Server with real-time markdown rendering, session history, tool-call visualization, searchable command palette, file attachments, quote-in-reply, conversation share, and send-while-streaming queuing
- **Manage your agent** — the full Hermes dashboard, native: switch models from your provider catalog, manage provider keys (write-only, masked, server-rate-limited reveal), create and edit agent profiles including `SOUL.md`, and browse, install, and update skills from the hub. One dashboard sign-in covers it all
- **Voice mode** — talk hands-free on a vanilla install: speech rides your server's configured providers, unlocked by the same Manage sign-in. Relay-paired setups add per-profile voice providers and an opt-in provider-native Realtime Agent with background task handoff
- **Works away from home** — add your server's Tailscale or public URL and the app roams automatically: LAN at home, fallback elsewhere. Routes are editable per connection, and an unreachable server gets a diagnosis ("away from the server's network? add a route"), not just a red dot
- **Multi-Connection + profiles** — pair with multiple Hermes servers (home + work, dev + prod) and switch in one tap; overlay an agent profile's model + `SOUL.md` per chat
- **Phone control (bridge)** — with the Relay plugin paired, the agent reads the screen and acts on it: tap, type, swipe, scroll, screenshots, clipboard, media keys, batched macros, and event-driven waits. Guarded by safety rails: per-app blocklist (banking/payments/2FA default-blocked), destructive-verb confirmation, idle auto-disable, full activity log
- **Notification companion** — opt-in notification access so the agent can triage, summarize, and route incoming notifications
- **Security & pairing** — QR pairing, Android Keystore session storage (StrongBox-preferred), TOFU cert pinning, per-channel time-bound grants, user-chosen session TTL
- **Stats for Nerds** — local-only analytics: TTFT, token usage, stream health, peak-time charts

> Sideload builds add direct SMS, contact search, one-tap dialing, and location awareness — handy for fully hands-free voice intents like "text Sam I'll be 10 minutes late". See [Release tracks](https://codename-11.github.io/hermes-relay/guide/release-tracks).

### Desktop CLI

- **Local tool routing** — `desktop_read_file` / `_write_file` / `_terminal` / `_search_files` / `_patch` / `_clipboard_*` / `_screenshot` / `_open_in_editor` run on your machine; agent-proposed patches render as colored diffs with interactive approval
- **Daemon mode** — headless tool router; the agent can reach you with no shell open
- **Multi-endpoint pairing, reconnect-on-drop, TOFU cert pinning** — same model as the Android app
- **Self-update** — `hermes-relay update` verifies SHA256 and atomic-swaps the binary

## Install with an AI agent

If an AI assistant (Claude, GPT, etc.) manages your server, paste this block into its chat and it will fetch the canonical setup recipe and walk you through install, pairing, and troubleshooting:

```text
You are helping me install and maintain Hermes-Relay (https://github.com/Codename-11/hermes-relay) — a native Android client + a desktop CLI + a Python plugin for the Hermes AI agent platform.

Read the canonical setup recipe before acting:
  https://raw.githubusercontent.com/Codename-11/hermes-relay/main/skills/devops/hermes-relay-self-setup/SKILL.md

Then guide me through:
- Verifying hermes-agent is already installed (it's a prerequisite — Hermes-Relay is a plugin, not standalone)
- Running the server-plugin install one-liner: `curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash`
- Connecting my phone by Standard Hermes API URL/key first, then optionally pairing Relay via the plugin-provided `hermes pair` or `/hermes-relay-pair` for power tools; OR pairing my laptop via the `hermes-relay` desktop CLI (binary one-liner: `curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.sh | sh` or `irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.ps1 | iex` on Windows, then `hermes-relay pair --remote ws://<host>:8767`)
- Verifying with `hermes-status` (server) or `hermes-relay doctor` (desktop CLI)

Always confirm before running shell commands. Never restart hermes-gateway without asking. If any step fails, consult the Troubleshooting section in the SKILL.md and ask me for the exact error.
```

Already installed? The same recipe is auto-loaded as a Hermes skill — invoke `/hermes-relay-self-setup` from any chat for re-setup or "is everything wired correctly?" checks.

## How It Works

```
Phone        (HTTP/SSE) --> Hermes API Server (:8642)   [chat — direct]
Phone        (HTTP)     --> Hermes Dashboard  (:9119)   [manage + standard voice — cookie sign-in]
Phone        (WSS/HTTP) --> Relay             (:8767)   [terminal, bridge, media, relay voice, sessions]
Desktop CLI  (WSS)      --> Relay             (:8767)   [desktop tools, tui, terminal]
```

Chat connects directly to the Hermes API Server with the API key — the same pattern used by Open WebUI and other Hermes frontends. The Manage tab and standard voice ride the Hermes dashboard with its own one-time sign-in, so a vanilla install needs no plugin for either. The optional relay on `:8767` adds the power surfaces — terminal, bridge phone control, media handoff, desktop tools, and relay-side voice providers (preferred automatically when paired). One QR can configure API, dashboard, and relay routes without merging their auth models.

## Documentation

| | |
|---|---|
| **[User Guide](https://codename-11.github.io/hermes-relay/)** | **Quick start, both surfaces, features, configuration — start here** |
| [Android](https://codename-11.github.io/hermes-relay/guide/) | Android install + setup + features |
| [Desktop CLI](https://codename-11.github.io/hermes-relay/desktop/) | Desktop CLI guide — pairing, subcommands, local tool routing |
| [Architecture](https://codename-11.github.io/hermes-relay/architecture/) | How the system works under the hood |
| [API Reference](https://codename-11.github.io/hermes-relay/reference/api.html) | Hermes API endpoints used by both surfaces |
| [Specification](docs/spec.md) | Full spec — protocol, UI, phases, dependencies |
| [Architecture Decisions](docs/decisions.md) | ADRs — framework, channels, auth, terminal |
| [Upstream Integration Sync](docs/upstream-integration-sync.md) | Supported Hermes extension points vs server-owned compatibility layers |
| [Changelog](CHANGELOG.md) | Release history (Android `android-v*`, Server `server-v*`, Desktop `desktop-v*`) |

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
scripts/dev.bat relay      # Start Server (dev, no TLS)
```

### Repository Structure

```
hermes-relay/
├── app/                       # Android app (Kotlin + Jetpack Compose)
├── desktop/                   # Desktop CLI thin-client (@hermes-relay/cli — TS + Bun-compiled binary)
├── relay_server/              # WSS Server (Python + aiohttp; thin shim → plugin/relay)
├── plugin/                    # Hermes agent plugin
│   ├── relay/                 #   - canonical relay (server.py, channels/, media, voice, desktop tools)
│   ├── tools/                 #   - android_* bridge + desktop_* tool handlers
│   └── pair.py                #   - QR pairing CLI + multi-endpoint payload builder
├── skills/                    # Hermes agent skills
│   └── devops/
│       ├── hermes-relay-pair/         # /hermes-relay-pair slash-command skill
│       ├── hermes-relay-self-setup/   # AI-agent setup recipe (Android + desktop)
│       └── hermes-relay-desktop-setup/ # AI-agent recipe specifically for the desktop CLI
├── user-docs/                 # VitePress documentation site (Android + desktop sections)
├── docs/                      # Spec, decisions, security
├── scripts/                   # Dev helper scripts
├── .github/workflows/         # CI + release pipelines (ci-android / ci-server / ci-desktop)
└── gradle/                    # Wrapper (8.13) + version catalog
```

### Tech Stack

| Component | Stack |
|-----------|-------|
| **Android App** | Kotlin 2.0, Jetpack Compose, Material 3, OkHttp |
| **Desktop CLI** | TypeScript, Bun-compiled native binary, Node ≥21 (source/dev), zero runtime deps |
| **Server** | Python 3.11+, aiohttp |
| **Serialization** | kotlinx.serialization (Android) |
| **Build** | AGP 9, Gradle 8.13, JVM toolchain 17 (Android); `tsc` + `bun build --compile` (desktop) |
| **CI/CD** | GitHub Actions (lint, build, test, APK artifact, desktop binaries per platform) |
| **Min SDK** | 26 (Android 8.0) / Target SDK 35 |

### Server (optional — bridge, terminal, TUI, media, and relay voice routes)

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

End users should install via the [one-liner](#4-optional-install-relay-for-power-tools) above. For local development from a clone:

```bash
cp -r plugin ~/.hermes/plugins/hermes-relay
# Or symlink for live edits:
ln -s "$PWD/plugin" ~/.hermes/plugins/hermes-relay
```

Then restart hermes and run the plugin-provided `hermes pair` to verify pairing. The 18 `android_*` and 9 `desktop_*` tools register regardless of hermes-agent version. `/hermes-relay-pair` and the dashed `hermes-pair` shim remain available for chat-surface and older-build compatibility.

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
