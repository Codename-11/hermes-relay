# Hermes-Relay Android v1.13.0

**Release Date:** August 25, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.13.0-sideload-release.apk` and tap it for the full feature set, or install the conservative build from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

This feature release adds Bot Mode across saved Hermes gateways, provider usage and limits, and bounded Assistant screen context. It also settles stale Gateway composer state, improves onboarding, and keeps idle Sphere motion efficient.

## Added

- Use Bot Mode as one messenger-style workspace across saved Hermes gateways, with exact gateway/profile ownership and read-only group rooms.
- Review Codex credential pools, Nous balances, and OpenCode Go windows from one provider-neutral Usage & limits screen.
- Start a compatible unlocked Assistant invocation with bounded visible text and an available screenshot in the first Standard voice turn.

## Changed

- Follow the Dashboard-first setup path with current screenshots and clearer separation between standard Hermes and optional Relay extensions.
- Use clear `Hermes-Relay Android` and isolated `HR Candidate` product names without changing package identities or update behavior.

## Fixed

- Settle orphaned Gateway busy state automatically while preserving active or detached turns owned by another session.
- Keep the visible idle Sphere gently animated without running hidden, backgrounded, or motion-disabled loops.
- Retry Windows-hosted `MEDIA:` attachments through the Relay by-path route instead of treating drive-letter paths as expired tokens.

## Install / Verify

- App version: **1.13.0** (versionCode **49**).
- Standard Chat, sessions, Manage, sharing, profile switching, and Vanilla Hermes voice continue to work against unmodified upstream Hermes.
- Granular Device Control remains sideload-only; the Google Play build continues to ship Hermes Bridge Core without AccessibilityService Device Control.
- The optional Relay plugin enhances provider usage, media retry, and device surfaces but remains unnecessary for standard Android chat, sessions, Manage, and Vanilla Hermes voice.
