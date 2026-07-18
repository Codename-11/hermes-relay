# Hermes-Relay-Android v1.4.6

**Release Date:** July 15, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.4.6-sideload-release.apk` and tap it for the full feature set, or install the conservative build from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

This patch keeps Server default aligned with Hermes' active profile, adds per-profile icon import and organization controls, and prevents the session drawer from mixing profile databases.

## Added

- Reorder or hide profiles per connection without changing server configuration.
- Choose a profile icon from the Android file picker or import `avatar.png`/`profile.jpg` from a paired Relay host.

## Fixed

- Server default resolves Hermes' sticky active profile before Gateway session create/resume and dashboard session operations, keeping the agent, drawer, transcript, and writes in one profile database.
- Host image import distinguishes an outdated Relay from a genuinely missing avatar and presents **Choose file** as a reliable fallback.

## Install / Verify

- App version: **1.4.6** (versionCode **29**).
- Standard Chat and Vanilla Hermes voice continue to work against unmodified upstream Hermes.
