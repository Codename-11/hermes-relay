# Hermes-Relay-Server v__VERSION__

**Release Date:** August 11, 2026

This patch makes paired sessions easier to identify and their expiry easier to understand across Relay clients and the Dashboard.

Standard chat, session history, and Vanilla Hermes voice remain upstream-owned and do not require this plugin.

## What's changed

### Fixed

- **Recognizable device names.** Relay uses the client hostname as the primary paired-session name when available while preserving compatibility with existing clients.
- **Persisted device details.** Model and platform metadata survive refresh, reconnect, and Relay restart and appear in the authorized-session API and Dashboard detail view.
- **Reconnect enrichment without re-pairing.** A valid paired client can fill missing identity metadata during reconnect without changing its authentication state.
- **Human-readable expiry.** Long paired-session lifetimes use days, weeks, or a calendar date, with the exact local deadline retained for inspection.

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
