# Hermes-Relay CLI v__VERSION__

**Release Date:** 2026-08-11

This alpha replaces the right-click-only Windows tray with the compact **Hermes-Relay CLI UI** popup while keeping Hermes-Relay's desktop boundary narrow. Chat, the remote TUI, plugins, voice, and agent sessions remain CLI or upstream desktop concerns.

**Experimental phase.** Assets remain unsigned, so Windows SmartScreen and macOS Gatekeeper may warn on first launch. Standalone CLI binaries ship for Windows x64, Linux x64, and macOS x64/arm64; the management tray is Windows-only.

## What's changed

### Added

- **Compact Windows management tray.** The popup provides connection status, host selection, per-host access, pending approval dialogs, recent activity, daemon controls, startup settings, and authorized-client revocation.
- **Host-aware desktop access.** `hermes-relay hosts` lists and selects paired Hermes instances and stores independent Ask, Trusted, or Full Access policy for each canonical relay URL.
- **In-window grant decisions.** New computer-use requests bring the tray forward and show the requesting host, scope, reason, and duration with explicit Approve and Reject actions.
- **Supported UI lifecycle from the CLI.** `hermes-relay ui install|open|status` lets a CLI-only Windows installation add, reveal, or inspect the optional management UI without rerunning setup by hand.

### Changed

- **Daemon startup is connectivity-first.** Ask mode can keep an authenticated daemon connected with zero desktop tools attached, so starting the daemon does not itself grant authority.
- **Full Access is explicit and host-scoped.** Trusted hosts may use command and file tools while screen/input remains task-granted. Full Access also removes task prompts for screen, input, and file patches for that host, while authentication, audit, revocation, emergency stop, and UAC boundaries remain enforced.
- **Host changes apply immediately.** Selecting a different host or changing its access mode restarts an already-running daemon and the UI verifies that the daemon URL matches the selected host before showing it as connected.
- **PowerShell remains first-class.** Agents should prefer the dedicated `desktop_powershell` RPC for native Windows work; `desktop_terminal` remains cmd-compatible for existing callers.
- **CLI and UI updates share one verified installer.** Bundle updates coordinate shutdown and restart, allow same-version UI repair, and refuse accidental downgrade unless explicitly forced.

### Fixed

- **Detached daemon start reports real readiness.** `daemon start` waits for the spawned PID to authenticate and connect, and reports configuration, authentication, early-exit, and timeout diagnostics instead of returning a false success.
- **Normal tray operation no longer requires opening a CLI for grants.** Pending requests are resolved directly in the focused management dialog.
- **Release checks match the management tray.** CI builds the React assets, validates Tauri metadata, and smoke-tests the packaged tray without obsolete menu-only size or window assertions.
- **Installed tray builds no longer depend on a localhost development server.** Local and packaged builds embed their UI assets, eliminating the `127.0.0.1 refused to connect` failure.

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
