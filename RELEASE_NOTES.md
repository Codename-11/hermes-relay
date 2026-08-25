# Hermes-Relay Android v1.12.1

**Release Date:** August 22, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.12.1-sideload-release.apk` and tap it for the full feature set, or install the conservative build from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

This patch makes Android sharing and recovery dependable. Shared links, text, images, and files open as complete reviewable drafts; connection renewal no longer stalls; offline and history failures are visible; and secure-storage recovery appears in Diagnostics.

## Fixed

- Open shared links, text, images, files, and mixed or multi-item shares as a fresh reviewable draft without sending automatically.
- Keep Add and Renew connection setup on the correct connection-scoped authentication store, with bounded Retry or Cancel recovery instead of an indefinite preparation screen.
- Surface unavailable chat routes and profile-history failures clearly instead of silently dropping Send or presenting missing history as an empty conversation.
- Report Android Keystore fallback, encrypted-store recovery, and temporary credential storage in Diagnostics without exposing credentials.

## Install / Verify

- App version: **1.12.1** (versionCode **48**).
- Standard Chat, sessions, Manage, sharing, profile switching, and Vanilla Hermes voice continue to work against unmodified upstream Hermes.
- Granular Device Control remains sideload-only; the Google Play build continues to ship Hermes Bridge Core without AccessibilityService Device Control.
- The optional Relay plugin is not required for standard Android chat, sharing, session continuity, or Gateway recovery.
