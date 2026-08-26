# Hermes-Relay Android v1.13.2

**Release Date:** August 25, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.13.2-sideload-release.apk` and tap it for the full feature set, or install the conservative build from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

This release adds a parent-configured Supervised Mode and improves its return from full settings. It also keeps session rows neutral until live activity is confirmed.

## Added

- Use a profile-pinned Supervised Mode with parent-controlled attachments, Standard voice, generated media, history, actions, and technical details. Device authentication protects full settings; this remains a client-side restricted view rather than a server-enforced account boundary.

## Fixed

- Keep session rows neutral while optional live activity is unavailable or still loading, and reserve full-row activity borders for actual Starting or Working turns.
- Keep Supervised Chat rendered when parent access relocks after visiting full settings.

## Install / Verify

- App version: **1.13.2** (versionCode **51**).
- Standard Chat, sessions, Manage, sharing, profile switching, and Vanilla Hermes voice continue to work against unmodified upstream Hermes.
- Granular Device Control remains sideload-only; the Google Play build continues to ship Hermes Bridge Core without AccessibilityService Device Control.
- The optional Relay plugin remains unnecessary for standard Android chat, sessions, Manage, and Vanilla Hermes voice.
