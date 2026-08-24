# Hermes-Relay CLI+UI v__VERSION__

**Release Date:** 2026-08-15

This patch keeps the Windows management UI usable when the Relay daemon is stopped or its status cannot be read.

**Beta phase.** Assets remain unsigned, so Windows SmartScreen and macOS Gatekeeper may warn on first launch. Standalone CLI binaries ship for Windows x64, Linux x64, and macOS x64/arm64; the management UI is Windows-only.

## What's changed

### Fixed

- **Stopped daemons no longer block the management UI.** Missing, stale, malformed, or temporarily unavailable daemon status falls back to an explicit stopped state while hosts, settings, activity, CLI details, diagnostics, and daemon controls continue loading normally.
- **Starting the daemon restores live status without reopening the UI.** A valid running status continues through the same bounded, single-flight snapshot path introduced in beta.3.

## Install

**Windows CLI + management tray (PowerShell):**

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

Pin this release with `HERMES_RELAY_VERSION=__TAG__`.

## Verify

```text
hermes-relay --version
hermes-relay hosts list --json
hermes-relay daemon start
hermes-relay daemon status --json
```

On Windows, click the Hermes-Relay CLI UI notification-area icon to open the management popup directly above it.

See the [CLI and tray guide](https://hermes-relay.dev/docs/desktop/) for installation, access modes, grants, and troubleshooting.
