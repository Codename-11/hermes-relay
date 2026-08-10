# Hermes-Relay-Android v1.8.1

**Release Date:** August 9, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.8.1-sideload-release.apk` and tap it for the full feature set, or install the conservative build from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

This patch keeps long Hermes conversations complete and aligns Android's
Gateway behavior with current upstream turn contracts.

## Fixed

- Complete transcript reads page explicitly across both API-server and
  profile-scoped Dashboard routes, so sessions beyond Hermes' latest-500
  default retain stable history, sharing, retry, edit, and recovery anchors.
- Gateway submit rejections preserve the authoritative server message without
  silently falling through to SSE.
- Gateway event envelopes reconcile consistently, and edit-and-regenerate
  requests send the required truncation confirmation.

## Install / Verify

- App version: **1.8.1** (versionCode **42**).
- Standard Chat, sessions, Manage, and Vanilla Hermes voice continue to work
  against unmodified upstream Hermes.
- The optional Relay plugin is not required for standard Android chat or hosted
  Dashboard authentication.
