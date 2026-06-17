# Hermes-Relay-CLI v__VERSION__

**Release Date:** <!-- YYYY-MM-DD -->
**Since the previous CLI release:** <!-- one line: the theme of this release -->

<!-- One short paragraph: what this desktop/CLI release is about and who should care. -->

<!--
═══ RELEASE-PREP CHECKLIST (delete this comment block when done) ═══
• This file is the GitHub Release body for `cli-v*` tags. The release workflow
  substitutes __VERSION__ (bare, e.g. 0.3.0) and __TAG__ (full, e.g. cli-v0.3.0) —
  leave those tokens in the Install section; do NOT hardcode versions there.
• Rewrite the Summary + the Added/Changed/Fixed groups from the CLI/desktop-relevant
  bullets in CHANGELOG.md's promoted version block.
• Keep-a-Changelog rules: include only the groups that have entries; delete empty ones.
• Keep the "Experimental phase" notice until the CLI reaches GA.
• Scrub for public distribution (RELEASE.md §2): no personal names, no private infra,
  no fork-branch plumbing, no AI self-narration.
═══════════════════════════════════════════════════════════════════
-->

**Experimental phase.** Assets are unsigned — Windows SmartScreen and macOS Gatekeeper will warn on first launch. Windows ships a tray installer as the primary desktop surface; CLI binaries remain available for terminal/headless use and for macOS/Linux.

## What's changed

### Added
-

### Changed
-

### Fixed
-

## Install

**Windows tray app (PowerShell):**
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

Pin this specific release with `HERMES_RELAY_VERSION=__TAG__`.

## Verify

```text
hermes-relay --version
hermes-relay pair --remote ws://<host>:8767
hermes-relay shell
```

Open **Hermes Relay Desktop** from the Windows Start menu for tray pairing, devices, task log, settings, pause, and emergency stop.

See [Desktop docs](https://codename-11.github.io/hermes-relay/desktop/) for full usage.
