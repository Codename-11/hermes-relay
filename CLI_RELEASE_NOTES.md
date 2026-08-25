# Hermes-Relay CLI+UI v__VERSION__

**Release Date:** 2026-08-25

This beta makes the Desktop connector resilient through Relay interruptions,
aligns Windows computer control with current CUA Driver releases, adds a native
Linux ARM64 build, and hardens installation and update discovery.

**Beta phase.** Assets remain unsigned, so Windows SmartScreen and macOS Gatekeeper may warn on first launch. Standalone CLI binaries ship for Windows x64, Linux x64/arm64, and macOS x64/arm64; the management UI is Windows-only.

## What's changed

### Added

- **Linux ARM64 is a first-class release target.** The one-line installer,
  updater, checksums, and release artifacts now cover both Linux x64 and arm64.
- **The public site shows the real Windows CLI UI.** Deterministic screenshots
  cover connections, host access, activity, computer control, and updates.

### Changed

- **Public naming is aligned.** Releases use `Hermes-Relay CLI+UI` while the
  beta keeps its existing `desktop-v*` tag and updater contract.

### Fixed

- **The daemon reconnects instead of exiting after an interrupted Relay socket.** Relay restarts and repeated transient replacement failures stay on bounded automatic backoff, and terminal failures persist an accurate stopped reason for the UI.
- **Oversized desktop-tool output no longer closes the shared connection.** PowerShell output and every serialized desktop response stay inside the Relay WebSocket budget.
- **Current CUA Driver releases remain compatible by contract.** Driver 0.20 and newer are accepted when their manifest and required tools match Hermes, and Windows uses the manifest-declared direct standard-mode runtime instead of a stale machine-wide daemon.
- **Install and update discovery paginates the multi-surface release history.** Desktop releases remain discoverable after more Android and Server releases, Windows cooperative updates clean their released backup, and unsigned installers retain the normal SmartScreen warning.

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
hermes-relay computer-use status --json
```

On Windows, click the Hermes-Relay CLI UI notification-area icon to open the management popup directly above it.

See the [CLI and tray guide](https://hermes-relay.dev/docs/desktop/) for installation, access modes, grants, and troubleshooting.
