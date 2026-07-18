# Hermes-Relay-Plugin v__VERSION__

**Release Date:** July 15, 2026

This patch aligns Server default with Hermes' sticky active profile and lets paired clients import conventional profile avatar files without exposing host paths.

Pairs with Hermes-Relay-Android v1.4.6 for profile image import. Standard chat and Vanilla Hermes voice remain upstream-owned and do not require this plugin.

## What's changed

### Added

- **Paired clients can import profile avatars.** Relay discovers conventional direct-child images such as `avatar.png` and `profile.jpg`, validates their media type, size, and profile boundary, and serves the bytes through an authenticated route.

### Fixed

- **Server default follows Hermes' active profile.** Advertised identity, model, SOUL, profile metadata, and avatar resolve through the sticky `active_profile` marker instead of always using the root profile.

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
