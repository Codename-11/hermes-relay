# Hermes-Relay-Android v1.7.1

**Release Date:** August 8, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.7.1-sideload-release.apk` and tap it for the full feature set, or install the conservative build from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

This patch tightens Android chat ownership, interaction safety, session
durability, and hosted-Hermes onboarding after the 1.7.0 release.

## Fixed

- Growing streamed replies stay visible while bottom-follow is owned, and
  intentional scrollback still releases that ownership.
- Completed replies transition immediately from stable live text to rendered
  Markdown without reopening the session.
- Queued follow-ups retain their originating connection, profile, session,
  route, attachments, and voice context through concurrent session switches.
- Approval cards remain pending until an explicit labeled response or an
  authoritative upstream expiry; scrolling, navigation, and later activity
  cannot silently decide them.
- Session pins and archives persist through the owning Hermes profile, with
  rollback when an update fails.
- Live tool cards retain stable identity and details while a run is active, and
  duplicate model inventory rows are reconciled before rendering.
- Hosted Hermes addresses complete through the official Dashboard system-browser
  sign-in flow and resume the verified connection after callback.
- Agent Passport safety controls use accessible full-width choices and a
  reliable close or downward-swipe path.

## Install / Verify

- App version: **1.7.1** (versionCode **40**).
- Standard Chat, sessions, Manage, and Vanilla Hermes voice continue to work
  against unmodified upstream Hermes.
- The optional Relay plugin is not required for standard Android chat or hosted
  Dashboard authentication.
