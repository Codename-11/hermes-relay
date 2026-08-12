# Hermes-Relay-Server v__VERSION__

**Release Date:** August 11, 2026

This patch improves gateway recovery diagnostics and prevents clients from reconnecting in lockstep after a shared restart.

Standard chat, session history, and Vanilla Hermes voice remain upstream-owned and do not require this plugin.

## What's changed

### Fixed

- **Bounded prior-exit diagnostics.** Relay Doctor and `/relay/info` distinguish a clean stop, an unclean exit, and unknown history, with an optional suspected out-of-memory hint. Raw logs are never returned through the API.
- **Desynchronized recovery.** Ordinary exponential reconnect delays use full jitter so multiple Relay clients do not retry in lockstep after the gateway restarts. Explicit reconnects and server-directed retry timing remain unchanged.

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
