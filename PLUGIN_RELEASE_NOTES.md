# Hermes-Relay-Server v__VERSION__

**Release Date:** August 12, 2026

This patch adds safe simultaneous routing for multiple connected desktop PCs and makes host selection explicit across command, file, screen, USB, and ADB operations.

Standard chat, session history, and Vanilla Hermes voice remain upstream-owned and do not require this plugin.

## What's changed

### Fixed

- **No more latest-client-wins routing.** Connecting a second desktop no longer evicts the first. Requests are bound to the selected desktop WebSocket, and a response from another PC cannot satisfy them.
- **Ambiguous calls fail closed.** With more than one desktop online, client-routed tools require a stable device ID or unambiguous computer name instead of silently choosing the latest heartbeat.
- **Pairing preserves existing PCs.** Placeholder legacy device identifiers no longer collide and revoke another desktop's session.

### Added

- **Target discovery.** `desktop_health` lists every connected desktop with its stable ID, name, and advertised tools.
- **Two-level USB targeting.** USB and ADB tools use `device` for the host PC; ADB operations retain `serial` for the attached Android device.

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
