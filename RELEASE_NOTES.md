# Hermes-Relay Android v1.13.1

**Release Date:** August 25, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.13.1-sideload-release.apk` and tap it for the full feature set, or install the conservative build from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

This patch makes Android session activity follow live Hermes runtime state instead of a five-minute recency estimate. It keeps Working, Starting, Needs input, Idle, Checking, Unavailable, and Background work accurate while preserving stale state until a complete, unambiguous snapshot can safely replace it.

## Fixed

- Derive session activity from the authoritative live runtime snapshot rather than Dashboard recency.
- Preserve prior activity when a refresh is incomplete, unsupported, or ambiguously scoped.
- Keep session drawer labels, timestamps, and active-turn ownership aligned with the exact profile and session.

## Install / Verify

- App version: **1.13.1** (versionCode **50**).
- Standard Chat, sessions, Manage, sharing, profile switching, and Vanilla Hermes voice continue to work against unmodified upstream Hermes.
- Granular Device Control remains sideload-only; the Google Play build continues to ship Hermes Bridge Core without AccessibilityService Device Control.
- The optional Relay plugin remains unnecessary for standard Android chat, sessions, Manage, and Vanilla Hermes voice.
