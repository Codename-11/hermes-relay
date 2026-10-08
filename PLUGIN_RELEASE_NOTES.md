# Hermes-Relay Plugin v__VERSION__

**Release Date:** October 7, 2026

## Summary

Dashboard pairing provides separate Android and CLI+UI choices, clearer connection addresses, and more reliable QR invites.

## Added

- CLI+UI pairing invites include route candidates and Secure Link certificate trust.

## Fixed

- Connection guidance shows protocols and ports, includes direct Tailscale Relay routes, and avoids assuming a public Relay listener from a Dashboard address.
- Expired and oversized invites show actionable errors. QR codes remain fully visible, and setup distinguishes Plugin activation from discovery.

## Compatibility

Full-invite CLI import requires a compatible CLI build newer than the currently published 0.4.0-beta.8. This Plugin release does not publish that companion. Existing Android pairing and legacy CLI connection settings remain supported.

## Install / update

    # Native upstream plugin path:
    hermes plugins install Codename-11/hermes-relay/plugin --enable

    # Classic install / update on a systemd host:
    curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/server-v__VERSION__/install.sh | bash
    # or, if already installed:
    hermes-relay-update

Restart or reload the Hermes Dashboard and Relay after updating so the new manifest and provider context are active.

## Verify

    hermes relay doctor
    python scripts/check-plugin-version-sync.py --expect __VERSION__

---

Tag prefixes: Android releases use android-v*, Plugin releases use server-v*, and CLI+UI releases use desktop-v*.
