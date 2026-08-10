# Hermes-Relay-Android v1.8.0

**Release Date:** August 9, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.8.0-sideload-release.apk` and tap it for the full feature set, or install the conservative build from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

This release makes mobile conversations calmer and more capable: richer message
actions and attachments, concise reasoning and tool activity, immediate profile
switching, and deeper appearance customization without mixing session identity.

## Added

- Quote or edit a message, attach and reorder files with previews, search the
  conversation, and jump between prompt turns without losing session context.
- Switch agents from the compact Profile Shelf while preserving each profile's
  last session and the server-default identity.
- Share text from another Android app into a fresh reviewed Chat draft.
- Customize theme accents and shapes, import Sphere skins, and create or install
  pets from one live-preview Appearance workflow.

## Changed

- Live reasoning opens inline and settles to a compact Thought disclosure.
  Routine tool work groups into a concise activity surface, while approvals,
  failures, generated media, edits, risks, and delegated work stay distinct.
- Agent Passport controls remain session-scoped: model and reasoning choices no
  longer overwrite server defaults merely by inspecting or switching profiles.

## Fixed

- Configured voice can speak any completed assistant message without requiring
  Voice Mode, and the same message menu exposes Stop during playback.
- Quotes remain readable inside bubbles, portrait images honor EXIF rotation,
  and keyboard controls follow standard capitalization and Enter behavior.
- Persisted Gateway history recovers missing structured tool activity without
  republishing healthy transcript state or duplicating cards.
- Floating pets avoid chat identity rows and controls while scrolling, remain
  touchable for their menu, and Appearance stays clear of system status bars.

## Install / Verify

- App version: **1.8.0** (versionCode **41**).
- Standard Chat, sessions, Manage, and Vanilla Hermes voice continue to work
  against unmodified upstream Hermes.
- The optional Relay plugin is not required for standard Android chat or hosted
  Dashboard authentication.
