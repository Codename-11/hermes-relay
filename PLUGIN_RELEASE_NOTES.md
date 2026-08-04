# Hermes-Relay-Server v__VERSION__

**Release Date:** August 3, 2026

This patch makes intentional re-pairing repair the existing device record instead of accumulating duplicate Relay sessions.

Standard chat, session history, and Vanilla Hermes voice remain upstream-owned and do not require this plugin.

## What's changed

### Fixed

- **Re-pairing replaces stale credentials for the same device.** After the host approves a new pair, Relay revokes older sessions and refresh credentials that belong to that device before issuing the replacement.
- **Existing and unrelated sessions remain operator-controlled.** The Dashboard and `/relay revoke <token-prefix>` continue to provide explicit cleanup without treating optional Relay pairing as a requirement.

## Install / update

    # Native upstream plugin path:
    hermes plugins install Codename-11/hermes-relay/plugin --enable

    # Classic install / update on a systemd host:
    curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/server-v__VERSION__/install.sh | bash
    # or, if already installed:
    hermes-relay-update

## Verify

    hermes relay doctor
    python scripts/check-plugin-version-sync.py --expect __VERSION__

---

Tag prefixes: Android releases use android-v*, Server releases use server-v*, and Desktop releases use desktop-v*.
