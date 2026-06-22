# Hermes-Relay-CLI v__VERSION__

**Release Date:** 2026-06-21
**Since the previous CLI release:** a first-class command surface — activity audit, relay inspection, a background daemon, a polished visual layer, and v1.2.0 server parity.

This is a broad CLI uplift: new commands for seeing what the agent did and inspecting the relay, a daemon you can run in the background, and a consistent themed interface with per-command help. Everything is additive — existing commands, flags, and scripts keep working.

**Experimental phase.** Assets are unsigned — Windows SmartScreen and macOS Gatekeeper will warn on first launch. Windows ships a tray installer as the primary desktop surface; CLI binaries remain available for terminal/headless use and for macOS/Linux.

## What's changed

### Added
- **`hermes-relay audit`** — see what the remote agent has run on this machine through the desktop tools (tool, status, detail), read from a local log. No network, no auth; works whether the relay is local or remote.
- **`hermes-relay relay`** — inspect the relay server: `relay context` audits the system-prompt context the relay injects into the agent (works from any paired machine), and `relay info` / `relay security` report server state for operators on the relay host.
- **Background daemon.** `hermes-relay daemon start` runs the headless tool router in the background — no console window, survives closing the terminal — with `daemon stop` and `daemon status` to manage it. Bare `daemon` still runs in the foreground. Logs go to `~/.hermes/daemon.log`.
- **Per-command help.** Every subcommand answers `--help`, and `devices` / `sessions` / `plugins` / `voice` / `relay` print their own usage (sub-commands, flags, examples) instead of a terse "unknown sub-verb".
- **Startup banner.** A slim "Hermes Relay" wordmark shows atop `--help`, the first-run welcome, and the chat REPL; `hermes-relay logo` prints it on demand. Suppressed for piped / `--json` / `--no-color` output.

### Changed
- **Visual + ergonomics refresh.** One consistent color theme across the CLI, aligned tables for `devices` / `sessions`, on/off status dots, and progress spinners for slow operations (the multi-endpoint pairing probe and the gateway connect) so nothing looks hung. Errors now suggest the fix (e.g. re-pair on auth failure).
- **Smoother pairing.** The multi-endpoint probe shows per-endpoint progress and latency; a near-expiry session warns before it fails and prints the exact re-pair command; and a bare `ws://host` (no port) defaults to `:8767`.
- **Voice + consent transparency.** `voice` now surfaces enhanced-voice capabilities (Gemini tone tags / persona, xAI speech tags); the desktop-tool consent prompt is clear that it persists per relay and points at `hermes-relay audit`; and computer-use's observe → grant → act flow is documented in `--help`.

## Install

**Windows tray app (PowerShell):**
```powershell
irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.ps1 | iex
```

**Windows CLI only:**
```powershell
$env:HERMES_RELAY_INSTALL_SURFACE='cli'; irm https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.ps1 | iex
```

**macOS / Linux CLI:**
```bash
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.sh | sh
```

Pin this specific release with `HERMES_RELAY_VERSION=__TAG__`.

## Verify

```text
hermes-relay --version
hermes-relay pair --remote ws://<host>:8767
hermes-relay shell
```

Open **Hermes Relay Desktop** from the Windows Start menu for tray pairing, devices, task log, settings, pause, and emergency stop.

See [Desktop docs](https://codename-11.github.io/hermes-relay/desktop/) for full usage.
