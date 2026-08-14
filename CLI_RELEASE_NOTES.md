# Hermes-Relay CLI v__VERSION__

**Release Date:** 2026-08-14

This patch prevents the Windows management tray from accumulating Hermes-Relay, registry, and ADB helper processes when a refresh is slow or fails, and adds bounded diagnostics for future recovery.

**Beta phase.** Assets remain unsigned, so Windows SmartScreen and macOS Gatekeeper may warn on first launch. Standalone CLI binaries ship for Windows x64, Linux x64, and macOS x64/arm64; the management UI is Windows-only.

## What's changed

### Changed

- **Grant discovery stays lightweight.** The approval window reads local bridge state instead of rebuilding the complete management snapshot, schedules each refresh only after the previous one finishes, and pauses idle polling while hidden.
- **Management refreshes are visibility-aware and resilient.** Concurrent snapshot requests share one bounded result, optional static checks are cached, and repeated failures back off instead of creating more work.

### Fixed

- **Windows helper processes are contained.** Tray-launched commands have hard deadlines, bounded output capture, descendant cleanup, and suppressed loader-error dialogs, preventing stalled probes from growing into a process storm.
- **Daemon startup is serialized across launchers.** Cross-process lifecycle and runtime ownership locks prevent concurrent start or restart requests from leaving duplicate daemons behind.
- **Tray failures are diagnosable without exposing command data.** A rotated, sanitized `tray.log` records bounded probe and lifecycle outcomes separately from `daemon.log`.

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
