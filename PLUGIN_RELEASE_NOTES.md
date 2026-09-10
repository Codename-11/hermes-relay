# Hermes-Relay Plugin v__VERSION__

**Release Date:** September 10, 2026

## Summary

This patch makes Android and Desktop tool availability fast and reliable when Relay is unavailable, starts late-created Android bridge sessions without restarting Hermes, and restores compatibility with both current and legacy `android_setup` arguments. Standard Chat, Manage, standard voice, and ordinary inbound files remain upstream-owned.

## Fixed

- **Fast, accurate tool availability.** Android and Desktop tool checks use explicit IPv4 loopback and one bounded health snapshot instead of repeated per-tool connection attempts. Multi-PC capability advertisements remain isolated, and unavailable Relay clients continue to fail closed.
- **Late Android bridge recovery.** `android_*` calls retry profile-scoped and active bridge-session credentials after a stale token is rejected, so a phone connected after Hermes startup becomes usable without restarting the host.
- **Compatible Android setup arguments.** `android_setup` accepts the canonical `bridge_session_token` and `pairing_code` fields as well as their legacy aliases, with structured errors when no usable credential is supplied.
- **Isolated setup tests.** Android tool setup tests use a temporary Hermes home instead of writing bridge settings into the operator environment.

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
