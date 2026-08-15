# Hermes-Relay-Android v1.9.0

**Release Date:** August 14, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.9.0-sideload-release.apk` and tap it for the full feature set, or install the conservative build from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

This release makes multi-profile session browsing feel native, keeps reactions
and voice attached to the correct conversation, and expands standard Gateway
management without requiring the optional Relay plugin.

## Added

- Browse one profile or all profiles, customize sorting and filters, and
  optionally group sessions by project, recency, status, or profile.
- See profile identity, repository, branch, and pull-request context directly
  in session rows when Hermes supplies it.
- Edit current Hermes profiles and complete more Manage workflows through the
  authenticated standard Gateway.

## Fixed

- Cross-profile sessions hydrate and resume with their owning agent while All
  Profiles remains selected; New Chat from that view respects the default
  profile.
- Reactions pin to both user and assistant messages using durable message rows.
- Vanilla Hermes voice stays on the Gateway instead of depending on the
  optional API fallback.
- Session navigation defaults to an ungrouped list, keeps project grouping
  opt-in, restores secondary actions, and closes on outside taps.
- Route changes shut down network clients off the main thread, and Gateway
  outcomes, uploads, clarify cards, automation state, and media recovery follow
  authoritative upstream behavior.

## Install / Verify

- App version: **1.9.0** (versionCode **43**).
- Standard Chat, sessions, Manage, and Vanilla Hermes voice continue to work
  against unmodified upstream Hermes.
- The optional Relay plugin is not required for standard Android chat or hosted
  Dashboard authentication.
