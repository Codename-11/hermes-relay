# Hermes-Relay-Android v1.7.0

**Release Date:** August 6, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.7.0-sideload-release.apk` and tap it for the full feature set, or install the conservative build from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

This patch makes model reasoning controls provider-aware, keeps active and
restored conversations stable, and adds private, actionable support information.

## Added

- Reasoning effort choices now follow the selected provider and model when an
  exact list is available. Standard choices remain available as an advisory
  fallback on unmodified Hermes or without the optional Relay capability overlay.
- The session drawer can search sessions and shows which conversations are
  working or waiting for input.
- Diagnostics now provides a locally redacted support report that can be
  reviewed before copy, share, or opening GitHub. Nothing uploads automatically.

## Fixed

- Restored and completed conversations remain at the exact bottom through late
  message, composer, keyboard, and status-row layout changes without overriding
  someone who intentionally scrolls up.
- Chat recovery keeps one stable rendered row through checkpoint restore,
  streaming callbacks, server reconciliation, and replay, preventing recurring
  duplicate-row crashes in Chat and Voice.
- Focus Voice controls receive taps again while the modal background continues
  to block interaction with the chat behind it.
- Connection diagnostics identify the attempted route and operation, redact host
  details, and give targeted guidance for DNS, timeout, TLS, authentication,
  rate-limit, missing-route, and server failures.
- The session drawer dismisses the composer keyboard when opening, preserves the
  newest row during refresh, and keeps floating pets on measured safe terrain.

## Install / Verify

- App version: **1.7.0** (versionCode **39**).
- Standard Chat, model selection, and Vanilla Hermes voice continue to work
  against unmodified upstream Hermes.
- Relay capability discovery is optional and fail-soft; unavailable or older
  Relay installations retain the standard reasoning choices.
