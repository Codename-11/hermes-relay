# Hermes-Relay Plugin v__VERSION__

**Release Date:** August 25, 2026

## Summary

This release adds a provider-neutral account-usage surface for Android and Dashboard clients. Relay resolves Codex credential pools, structured Nous balances, and OpenCode Go windows on the Hermes host without returning provider credentials.

Standard chat, session history, and Vanilla Hermes voice remain upstream-owned and do not require this plugin.

## Added

- **Provider-neutral usage snapshots.** Authenticated Dashboard clients can resolve the exact active Codex pool entry, Nous balances, and OpenCode Go account windows through one normalized schema.
- **Bounded paired-client fallback.** Operators may explicitly enable the Relay usage route for paired standalone clients while credentials remain host-side.

## Changed

- **Usage capabilities are explicit.** Responses identify Relay-enhanced credential pools, structured balances, and provider adapters instead of implying unsupported upstream data.
- **Public product naming is aligned.** Releases use `Hermes-Relay Plugin` while retaining the `server-v*` tag and installation contract.

## Fixed

- **Custom Hermes homes resolve correctly.** Relay profile discovery and session persistence follow `HERMES_HOME` by default while preserving the explicit `RELAY_HERMES_CONFIG` override.

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

Tag prefixes: Android releases use android-v*, Plugin releases use server-v*, and CLI+UI releases use desktop-v*.
