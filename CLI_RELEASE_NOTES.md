# Hermes-Relay CLI v__VERSION__

**Release Date:** 2026-08-14

This beta makes connection recovery and Activity evidence inspectable in the compact management UI, and keeps the preferred CUA control engine usable when its upstream whole-desktop accessibility probe times out.

**Beta phase.** Assets remain unsigned, so Windows SmartScreen and macOS Gatekeeper may warn on first launch. Standalone CLI binaries ship for Windows x64, Linux x64, and macOS x64/arm64; the management UI is Windows-only.

## What's changed

### Added

- **Inspectable Activity evidence.** Commands, files, device work, connection lifecycle, and computer control share a consistent event stepper with dedicated failure details.
- **Optional screenshot retention.** Screenshot events can keep bounded local PNG evidence for Off, 1 day, 7 days, or 30 days and open it in a larger borderless viewer. Evidence stays outside the JSON activity log.

### Changed

- **Connection state is live and actionable.** The UI distinguishes connected, reconnecting, and stopped states, shows retry timing, and offers Retry now without freezing the popup.
- **Connection notices stay out of the way.** Compact connect, disconnect, and reconnect cards appear only while the main management UI is hidden.

### Fixed

- **CUA readiness no longer depends on the flaky global accessibility scan.** Hermes verifies the canonical runtime, required tools, daemon, and safe permission mode before structured control; explicit accessibility health remains available for diagnosis and individual actions still fail closed.
- **Connection errors retain useful context.** Activity records bounded retry and recovery evidence without flooding one event per backoff attempt.

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
