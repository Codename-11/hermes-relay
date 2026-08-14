# Hermes-Relay CLI v__VERSION__

**Release Date:** 2026-08-14

This first beta makes structured Windows computer control safer and more capable through an optional CUA Driver integration, while hardening in-place CLI and management UI updates.

**Beta phase.** Assets remain unsigned, so Windows SmartScreen and macOS Gatekeeper may warn on first launch. Standalone CLI binaries ship for Windows x64, Linux x64, and macOS x64/arm64; the management UI is Windows-only.

## What's changed

### Added

- **CUA Driver computer control.** Windows sessions prefer the verified CUA runtime for window-targeted background actions, fresh pre/post snapshots, one-use element tokens, and optional per-session animated cursors that do not move the physical pointer.
- **Explicit engine management.** The CLI and management UI show CUA installation, compatibility, health, active backend, and session state, with guarded Install, Check, and Update actions.
- **Bounded control activity.** Computer-control events show backend, dispatch, target, action, and verification phases without retaining screenshots, UI trees, entered values, or raw session identifiers.

### Changed

- **Windows Input is the compatibility backend.** Engine selection is fixed for each authenticated control session; CUA never silently falls back to foreground input after a session starts.
- **CUA remains optional.** Hermes verifies the canonical upstream package and supported version range. The driver is not bundled or silently updated, and Hermes sessions force driver telemetry off.

### Fixed

- **Bundle updates handle locked processes safely.** The installer waits for the invoking process, quiesces tray children, retries checked payload extraction, preserves custom install directories, and fails before release metadata changes if replacement cannot complete.
- **CUA readiness uses the documented health schema.** A healthy `ok` result is accepted, installed-but-degraded UI Automation is explained accurately, and trusted Windows installer paths validate consistently.

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
