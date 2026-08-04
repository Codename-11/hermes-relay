# Hermes-Relay-Android v1.6.1

**Release Date:** August 3, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.6.1-sideload-release.apk` and tap it for the full feature set, or install the conservative build from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

This patch clarifies optional Relay recovery, restores the full session drawer, and fixes several chat and Voice regressions.

## Fixed

- Optional Relay failures stay within Relay-only surfaces, use consistent status labels, and only request re-pairing when the stored Relay credential actually needs it.
- Foreground recovery reconnects immediately after ordinary backoff, while retained credentials are described as stored details rather than an active in-memory session.
- Session history loads its 200-row drawer window through upstream-compatible 100-row pages instead of failing with HTTP 422.
- Selecting text remains stable when a streamed response changes from live text to rendered Markdown.
- Manual Voice recording waits for barge-in microphone teardown and gives a useful recovery message when the microphone is unavailable.
- New-chat coaching yields while Voice owns the composer, so it no longer covers the expanding Voice drawer.

## Install / Verify

- App version: **1.6.1** (versionCode **38**).
- Standard Chat and Vanilla Hermes voice continue to work against unmodified upstream Hermes.
- Same-device Relay re-pair replacement requires the optional Server 1.5.1 plugin; Dashboard and `/relay revoke <token-prefix>` remain available for explicit cleanup.
