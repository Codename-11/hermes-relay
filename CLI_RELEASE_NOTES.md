# Hermes-Relay CLI+UI v__VERSION__

**Release Date:** 2026-10-08

## Summary

Structured computer control accepts current CUA Driver element handles, and Windows Activity shows the backend actually recorded for each operation.

**Beta phase.** Assets remain unsigned. Standalone CLI binaries ship for Windows x64, Linux x64/arm64, and macOS x64/arm64; the management UI is Windows-only.

## Fixed

- CUA clicks, value changes, and element scrolling accept snapshot handles, including hexadecimal generations after the first nine snapshots. Legacy handles remain supported. (#680)
- Activity correctly identifies CUA snapshots and system captures. Entries without backend or dispatch metadata no longer appear as compatibility input in the background. (#682)

## Known issue

The startup crash reported on Windows Insider build 26200.8875 remains under investigation in #496. This release does not change the pinned Bun runtime.

## Compatibility

Dashboard CLI+UI pairing requires Plugin 1.13.0 or newer. Existing direct URL-and-code pairing and saved hosts remain supported.

## Install

**Windows CLI + management UI:** download [hermes-relay-windows-x64-setup.exe](https://github.com/Codename-11/hermes-relay/releases/download/__TAG__/hermes-relay-windows-x64-setup.exe). Release assets are covered by `SHA256SUMS.txt`.

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
