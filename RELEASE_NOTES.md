# Hermes-Relay Android v1.16.1

**Release Date:** September 12, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.16.1-sideload-release.apk` and tap it for the full feature set, or install the conservative build from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

This patch fixes a remaining cold-start path that could leave a Dashboard-only connection waiting for Gateway readiness until the app resumed or its network route changed.

## Fixed

- Dashboard-only connections now start the exact profile-scoped session directory before Gateway readiness, so the directory and passive Gateway socket no longer wait on each other during a cold launch.

## Install / Verify

- App version: **1.16.1** (versionCode **56**).
- Standard Chat, sessions, profiles, Manage, voice, and ordinary media use current upstream Hermes. Speech-to-text still requires a configured provider on the host.
- Hermes-Relay Plugin **1.11.2** remains the matching optional release for Relay tools; this Gateway startup fix does not require it.
- Explicit Direct API/API-only connections remain supported and are not used as silent failover for Dashboard-owned chats.
- Granular Device Control and the system Voice Focus overlay remain sideload-only.
