# Hermes-Relay CLI+UI v__VERSION__

**Release Date:** 2026-09-02

This beta fixes Windows updates so the installed CLI and management UI advance together. Explicit CLI-only installations keep their standalone update path.

**Beta phase.** Assets remain unsigned, so Windows SmartScreen and macOS Gatekeeper may warn on first launch. Standalone CLI binaries ship for Windows x64, Linux x64/arm64, and macOS x64/arm64; the management UI is Windows-only.

## What's changed

### Fixed

- `hermes-relay update` detects an installed management UI beside the CLI and reports both installed versions.
- Bundle installations use the checksum-verified Windows installer to update and restart the affected CLI and UI together.
- Explicit CLI-only installations continue to use the standalone binary updater.

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
