# Hermes-Relay-Server v__VERSION__

**Release Date:** August 14, 2026

This release adds an official, opt-in Relay pane for Hermes Desktop through the supported runtime Plugin SDK. It keeps Relay management profile-scoped and user-invoked without opening a pane during startup, reconnects, profile changes, or plugin updates.

Standard chat, session history, and Vanilla Hermes voice remain upstream-owned and do not require this plugin.

## What's changed

### Added

- **Official Hermes Desktop pane.** The unified plugin package registers a movable native pane for Relay status, paired devices, bridge activity, media, pairing, revocation, and remote-access management.
- **Explicit entry points.** Labeled sidebar, status-bar, and command-palette actions register and reveal the pane lazily; repeated opens reuse the same surface.
- **Profile-scoped state.** Cached Relay state follows the active Hermes profile and is disposed cleanly when the plugin unloads.

### Changed

- **Plugin loading stays passive.** Loading, startup, reconnects, profile changes, and updates never reveal the pane or perform pane-owned network work.

## Install / update

    # Native upstream plugin path:
    hermes plugins install Codename-11/hermes-relay/plugin --enable

    # Classic install / update on a systemd host:
    curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/server-v__VERSION__/install.sh | bash
    # or, if already installed:
    hermes-relay-update

## Verify

    hermes relay doctor
    # Agent/tool callers can use desktop_health to list desktop targets.
    python scripts/check-plugin-version-sync.py --expect __VERSION__

---

Tag prefixes: Android releases use android-v*, Server releases use server-v*, and Desktop releases use desktop-v*.
