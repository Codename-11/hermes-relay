# Hermes-Relay Plugin v__VERSION__

**Release Date:** September 28, 2026

## Summary

Secure Link gains guided host checks and pairing, Android screenshot tools deliver captured images to host vision, and OpenAI Realtime sessions use the required output audio rate.

## Added

- Guided Secure Link setup in Dashboard and the Desktop Relay pane uses read-only host checks, restart instructions, and a signed pairing handoff.
- Provider usage reports SuperGrok subscription windows, product usage, and on-demand credit state for hosts signed in with `xai-oauth`.

## Fixed

- OpenAI Realtime Agent sessions include the output PCM sample rate required by the current API. (#644)
- Android screenshot and navigation tools resolve authenticated media tokens into bounded images for host vision while retaining legacy inline-image compatibility. (#593)
- Relay-owned media uploads are removed on token expiry, eviction, and shutdown, and media logs omit sensitive tokens and file paths.
- Secure Link preserves Gateway ticket authentication and Dashboard sign-in, bounds proxied responses, and leaves ordinary Relay available when optional Secure Link configuration fails.
- Dashboard pairing QR codes support certificate-bearing invites, and health/status checks avoid extra loopback probes.

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
