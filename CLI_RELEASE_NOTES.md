# Hermes-Relay CLI+UI v__VERSION__

**Release Date:** 2026-10-02

This patch beta fixes Windows CLI+UI installation failing while resolving the latest release. It also restores reusable management windows and image attachments for desktop screenshots.

**Beta phase.** Assets remain unsigned, so Windows SmartScreen and macOS Gatekeeper may warn on first launch. Standalone CLI binaries ship for Windows x64, Linux x64/arm64, and macOS x64/arm64; the management UI is Windows-only.

## What's changed

### Fixed

- The Windows bootstrap installer correctly selects CLI+UI releases from GitHub pages containing Android and Plugin releases and continues discovery across full pages.
- Tray notices, screenshot evidence, and grant prompts remain reusable after dismissal; screenshot evidence retains the selected image.
- Desktop computer screenshots attach validated image bytes to host tool results instead of returning base64 as plain text.

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
