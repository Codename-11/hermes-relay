# Hermes-Relay CLI+UI v__VERSION__

**Release Date:** 2026-08-30

This beta preserves complete multi-route pairing while preventing Desktop from dialing Dashboard-ingress Relay routes before Dashboard WebSocket ticket support is available. (Related: #399)

**Beta phase.** Assets remain unsigned, so Windows SmartScreen and macOS Gatekeeper may warn on first launch. Standalone CLI binaries ship for Windows x64, Linux x64/arm64, and macOS x64/arm64; the management UI is Windows-only.

## What's changed

### Changed

- **Saved hosts retain the full route topology.** Dashboard, Relay, optional API, route priority, transport protection, certificate pins, and the selected host survive LAN, Tailscale, and public-route changes without creating duplicate hosts.
- **API-less pairing is first-class.** Dashboard plus direct Relay can pair without inventing an optional API server, while secure-first ranking retains plain LAN as the final fallback.

### Fixed

- **Dashboard-ingress Relay routes fail closed on Desktop.** The daemon, host selector, and Relay transport reject ingress that requires a Dashboard WebSocket ticket and choose a compatible direct Relay fallback instead of attempting an unauthenticated dial.
- **Pairing accepts the current v3 candidate shape.** Optional API records, same-origin Dashboard/Relay routes, and legacy top-level payloads remain compatible without collapsing route ownership.

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
