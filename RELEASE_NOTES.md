# Hermes-Relay-Android v1.9.1

**Release Date:** August 16, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.9.1-sideload-release.apk` and tap it for the full feature set, or install the conservative build from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

This patch aligns Agent Passport with current Hermes profile identity, makes
shared avatar changes reliable from the phone, and hardens several Gateway
operations around profile ownership, attachments, recovery, and model consent.

## Added

- Create Hermes profiles with explicit shared, copied, or isolated
  authentication choices and inspect partial setup outcomes.
- Select upstream animated pets that follow the active Hermes profile across
  supported clients, while keeping phone-only animated icons separate.
- Create finite recurring schedules, review bounded reset evidence, and see
  host resource or model-consent warnings before taking action.

## Fixed

- Shared avatar images selected from Android now persist. The app accepts any
  decodable image and safely converts it to Hermes' supported static format and
  size contract when needed.
- Named-profile sessions, draft writes, rewinds, and recovery fail closed when
  Hermes cannot confirm the owning profile or durable history.
- Attachment sends remain bounded and no longer fall through to text-only
  delivery after an unsupported or interrupted upload.
- Nous hosted sign-in follows the official native callback and provider
  contract with clearer, non-sensitive failure guidance.

## Install / Verify

- App version: **1.9.1** (versionCode **44**).
- Standard Chat, sessions, Manage, profile identity, and Vanilla Hermes voice
  continue to work against unmodified upstream Hermes.
- The optional Relay plugin is not required for standard Android chat, shared
  profile avatars, upstream pets, or hosted Dashboard authentication.
