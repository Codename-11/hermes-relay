# Hermes-Relay Plugin v__VERSION__

**Release Date:** August 31, 2026

## Summary

This patch restores native installation compatibility on affected Hermes versions and makes Relay prompt context advertise only capabilities the selected session can actually call. Standard Chat, Manage, standard voice, and ordinary inbound files remain upstream-owned.

## Fixed

- **Native installer compatibility.** The plugin keeps its complete current manifest while avoiding the installer/runtime schema mismatch that caused `manifest_version 2` installs to fail after an apparent Hermes update.
- **Capability-gated phone context.** Phone-control and cross-platform delivery guidance now follows the selected session/profile tool catalog instead of implying unavailable `android_*` or `send_message` callables.

## Install / update

    # Native upstream plugin path:
    hermes plugins install Codename-11/hermes-relay/plugin --enable

    # Classic install / update on a systemd host:
    curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/server-v__VERSION__/install.sh | bash
    # or, if already installed:
    hermes-relay-update

Restart or reload the Hermes Dashboard and Relay after updating so the new manifest and prompt context are active.

## Verify

    hermes relay doctor
    python scripts/check-plugin-version-sync.py --expect __VERSION__

---

Tag prefixes: Android releases use android-v*, Plugin releases use server-v*, and CLI+UI releases use desktop-v*.
