# Hermes-Relay-Server v__VERSION__

**Release Date:** August 8, 2026

This patch makes the optional Dashboard plugin's Android setup handoff reliable for hosted Hermes connections.

Standard chat, session history, and Vanilla Hermes voice remain upstream-owned and do not require this plugin.

## What's changed

### Fixed

- **Canonical hosted-Hermes setup handoff.** The Dashboard plugin supplies the verified Dashboard address Android needs to continue through the official system-browser authentication flow.
- **Contained dialog focus behavior.** Mobile setup dialogs retain their own focus and keyboard handling without disrupting the surrounding Dashboard.

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
