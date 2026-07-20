# Hermes-Relay-Android v1.4.9

**Release Date:** July 19, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.4.9-sideload-release.apk` and tap it for the full feature set, or install the conservative build from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

This patch makes the Hermes dashboard the standard connection path and refreshes setup, connection management, and profile identity throughout Android.

## Changed

- Connect through the Hermes dashboard for Chat, sessions, Manage, and voice with one sign-in. The API server remains an automatic fallback or headless compatibility path, and Relay remains optional for power features.
- Onboarding and connection management now explain nearby, remote, Tailscale, custom-port, and Relay paths with clearer status, route, startup, Advanced, and Security controls.

## Fixed

- Server default now shows Hermes' pinned active profile consistently across Chat, sessions, agent details, settings, voice, diagnostics, and profile inspection.
- Successful local discovery adds useful hostname identity without replacing a custom connection label.

## Install / Verify

- App version: **1.4.9** (versionCode **32**).
- Standard Chat and Vanilla Hermes voice continue to work against unmodified upstream Hermes.
