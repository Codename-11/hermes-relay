# Hermes-Relay-CLI v__VERSION__

**Release Date:** 2026-07-13

This alpha makes the desktop direction explicit: Hermes-Relay is a real CLI/TUI with an optional Windows right-click systray—not a second desktop application. The old Tauri/WebView dashboard and its embedded windows are gone. The installed CLI remains the single source of behavior for pairing, TUI, daemon management, grants, audit, diagnostics, chat, voice, and tools.

**Experimental phase.** Assets are unsigned, so Windows SmartScreen and macOS Gatekeeper may warn on first launch. Standalone CLI binaries ship for Windows x64, Linux x64, and macOS x64/arm64; the optional native systray is Windows-only.

## What's changed

### Added

- **Persistent desktop-use control.** `hermes-relay computer-use status|enable|disable|cancel` stores one local preference, reports daemon privilege and active/pending grants, and can end an active task-scoped grant without relying on a GUI.
- **Headless grant review.** `hermes-relay grants` lists pending local computer-use requests and supports interactive review plus explicit `approve`, `reject`, and JSON forms for scripts.
- **Typed Relay chat option.** `chat --relay-chat` sends `chat.send` over WSS and renders typed `stream.event` v1 assistant, tool, artifact, memory, skill, and error lifecycles while preserving the existing gateway path as the default.
- **Release-parity verification.** One version contract now keeps the npm package, compiled CLI, Rust tray, lockfile, and installer metadata aligned. The Windows verification target covers TypeScript, compiled-binary smoke tests, Rust formatting/lint/check/tests, and installer packaging.

### Changed

- **Menu-only Windows systray.** The optional tray is a small native Rust process with no application window, WebView, overlay, embedded terminal, chat view, voice view, or settings dashboard. Interactive actions open the installed CLI in a normal terminal.
- **State- and privilege-aware daemon control.** The menu reports PID-backed daemon state and User/Administrator privilege, disables invalid lifecycle actions, and requests UAC only when **Start/Restart daemon as Administrator…** is explicitly chosen. The tray itself remains unprivileged.
- **Visible desktop-use safety.** The tray shows enablement, active grant mode and expiry, warns when an Administrator control grant is active, raises a native alert for pending approvals, opens CLI grant review, and provides immediate cancellation and emergency stop.
- **Per-user Windows installation.** The default PowerShell installer downloads the checksum-verified NSIS package, installs the CLI and optional tray under `~/.hermes/bin`, adds Start-menu shortcuts and user PATH, and can start the tray at sign-in. CLI-only installation remains available with `HERMES_RELAY_INSTALL_SURFACE=cli`.

### Fixed

- **Installed-binary diagnostics.** `hermes-relay doctor` reports the physical Bun-compiled executable instead of a virtual embedded-module path, so PATH and install-directory checks describe the binary that actually launched.
- **Release guardrails.** CLI tag automation rejects version drift, tags not contained in `main`, oversized tray binaries, or a tray process that creates an application window.

## Install

**Windows CLI + optional systray (PowerShell):**

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
hermes-relay pair --remote ws://<host>:8767 --grant-tools
hermes-relay daemon start
hermes-relay daemon status
```

On Windows, open **Hermes Relay Systray** from the Start menu and right-click its notification-area icon. No separate desktop window is installed.

See the [CLI and systray guide](https://codename-11.github.io/hermes-relay/desktop/) for installation, commands, desktop-use safety, and troubleshooting.
