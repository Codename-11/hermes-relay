# Hermes-Relay-Plugin v__VERSION__

**Release Date:** July 22, 2026

This patch hardens Relay authorization, adds upstream-aware diagnostics, and keeps plugin bootstrap work off the Gateway event loop.

It can accompany Hermes-Relay-Android v1.5.0 for optional Relay diagnostics and power features. Standard chat and Vanilla Hermes voice remain upstream-owned and do not require this plugin.

## What's changed

### Added

- **Upstream-aware Gateway diagnostics.** Doctor and `/relay/info` expose optional health, configuration-route, and capability signals so clients can explain compatibility gaps without treating an older upstream install as a broken Relay.

### Fixed

- **Privileged Relay paths enforce host authorization and active grants.** Pairing, Android bridge, terminal, session policy, remote profile configuration, and voice provider origins retain their intended trust boundaries.
- **Plugin bootstrap remains responsive.** Database initialization and compatibility inspection run outside the Gateway event loop while preserving compatibility with older upstream bootstrap contracts.
- **Windows Gateway detection is non-signalling.** Starting Relay and periodic profile rescans no longer risk terminating an existing Gateway process.

## Install / update

    # Native upstream plugin path:
    hermes plugins install Codename-11/hermes-relay/plugin --enable

    # Classic install / update on a systemd host:
    curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash
    # or, if already installed:
    hermes-relay-update

## Verify

    hermes relay doctor
    python scripts/check-plugin-version-sync.py --expect __VERSION__

---

Tag prefixes: Android releases use android-v*, Server releases use server-v*, and Desktop releases use desktop-v*.
