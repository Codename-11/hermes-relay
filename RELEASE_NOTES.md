# Hermes-Relay-Android v1.12.0

**Release Date:** August 21, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.12.0-sideload-release.apk` and tap it for the full feature set, or install the conservative build from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

This release adds saved custom themes and makes appearance shape consistent throughout Android. It also keeps profile and session identity intact across All Profiles navigation and language changes, recovers Gateway chats when a completion frame is missed, and accepts common Relay endpoint forms without producing invalid routes.

## Added

- Create up to 20 local custom themes with editable palette roles, Light or Dark ownership, saved shape, live chat preview, rename, duplicate, and delete controls.

## Changed

- Apply Soft, Balanced, or Sharp styling consistently across chat, settings, sheets, dialogs, terminal, voice, Bridge, and other shared surfaces.
- Activate a session's owning agent when selecting it from All Profiles; profile locks hide that browser and reject cross-profile opens.

## Fixed

- Preserve the exact connection, agent, session, transcript, draft, and All Profiles state through an app-language change.
- Relocalize the persistent connection notification without restarting the active connection.
- Settle and reconcile active Gateway turns when the terminal completion frame was missed.
- Normalize Relay base, `/ws`, and `/health` endpoint forms without producing duplicate route segments.

## Install / Verify

- App version: **1.12.0** (versionCode **47**).
- Standard Chat, sessions, Manage, profile switching, custom themes, and Vanilla Hermes voice continue to work against unmodified upstream Hermes.
- Granular Device Control remains sideload-only; the Google Play build continues to ship Hermes Bridge Core without AccessibilityService Device Control.
- The optional Relay plugin is not required for standard Android chat, session continuity, themes, or Gateway recovery.
