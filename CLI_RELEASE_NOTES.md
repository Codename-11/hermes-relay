# Hermes-Relay CLI+UI v__VERSION__

**Release Date:** 2026-10-07

## Summary

Pair the CLI or Windows management UI with a complete Dashboard invite, retaining its connection routes and Secure Link certificate trust.

**Beta phase.** Assets remain unsigned. Standalone CLI binaries ship for Windows x64, Linux x64/arm64, and macOS x64/arm64; the management UI is Windows-only.

## Added

- Paste and import a full Dashboard pairing invite in the Windows management UI. The existing `hermes-relay pair --pair-qr` command remains available.
- Native import preserves route candidates and Secure Link certificate trust and keeps one-use invite contents out of command arguments and activity logs.

## Changed

- CLI pairing displays advertised routes with protocols and ports and gives actionable direct-route guidance when Dashboard authentication is unsupported.

## Compatibility

Dashboard CLI+UI pairing requires Plugin 1.13.0 or newer. Existing direct URL-and-code pairing and saved hosts remain supported.

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
