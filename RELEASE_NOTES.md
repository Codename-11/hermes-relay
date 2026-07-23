# Hermes-Relay-Android v1.5.0

**Release Date:** July 22, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.5.0-sideload-release.apk` and tap it for the full feature set, or install the conservative build from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

This feature release overhauls voice setup and playback, expands upstream Gateway-aware controls, and makes active Hermes work easier to understand.

## Added

- Standard and Realtime voice settings now have distinct, organized cards for provider, model, and voice selection, with upstream-aware discovery, descriptions, inline previews, waveform feedback, loading skeletons, and an expandable scrolling voice browser.
- Standard Hermes speech now streams completed reply segments as they arrive. Starting another preview or reply stops the prior audio, and leaving voice mode stops playback.
- Manage and diagnostics consume upstream health hints and compatibility details, follow canonical Gateway redirects, compress larger RPC payloads, and preserve profile-scoped behavior.
- Chat surfaces one-turn model selection, queued recovery, project labels, interim Gateway events, and image-generation progress.

## Fixed

- Voice settings and active-turn correction copy remain complete across supported languages.
- Chat reactivates the original live Gateway session after a connection loss, avoids duplicate prompt submission when an acknowledgement is lost, and prevents duplicate session rows from crashing the drawer.
- Hosted Manage OAuth remains bound to the selected dashboard, direct-chat image memory is bounded, and session reset and recovery behavior follow upstream contracts.

## Install / Verify

- App version: **1.5.0** (versionCode **33**).
- Standard Chat and Vanilla Hermes voice continue to work against unmodified upstream Hermes.
