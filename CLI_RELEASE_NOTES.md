# Hermes-Relay CLI v__VERSION__

**Release Date:** 2026-08-13

This alpha makes the compact **Hermes-Relay CLI UI** responsive during connection changes, expands host and capability management, and presents live route security and diagnostics without turning the tray into a full desktop client.

**Experimental phase.** Assets remain unsigned, so Windows SmartScreen and macOS Gatekeeper may warn on first launch. Standalone CLI binaries ship for Windows x64, Linux x64, and macOS x64/arm64; the management tray is Windows-only.

## What's changed

### Added

- **Host management hub.** Each paired Hermes host has local identity, pairing facts, access and capability controls, authorized-client revocation, re-pairing, and guarded removal.
- **Evidence-first activity.** Overview shows the latest three events and detailed views retain bounded command, output, exit, duration, and truncation evidence.
- **Live route details.** The Agent-to-PC path shows route type, encryption state, endpoint, packet motion, and a connection test with reachability and latency.

### Changed

- **Connection lifecycle stays responsive.** Connect, disconnect, and snapshot work run outside the UI thread with immediate transition feedback and single-flight live polling.
- **Access presets are explicit.** Restricted, Ask Every Time, Standard, Full Access, and Custom map visibly onto individual command, file, screen/input, and hardware capabilities.
- **Tailscale is the recommended remote route.** Direct TLS and Hermes Secure Link remain supported; Hermes Reach is marked experimental and stays below supported routes.

### Fixed

- **Legacy LAN and Tailscale routes no longer appear as Custom VPN.** Route testing infers generic saved roles from the endpoint and reports the correct network path.
- **PowerShell output remains complete.** Scalar, pipeline, JSON, native output, errors, exit status, and truncation metadata return reliably through desktop RPC.
- **Tray placement follows the real notification area.** Responsive geometry uses the tray monitor and DPI and remains anchored above the icon.

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
