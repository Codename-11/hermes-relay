# Hermes-Relay Android v1.18.1

**Release Date:** September 29, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.18.1-sideload-release.apk` and tap it for the full feature set, or install from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

Dashboard browser sign-in remains connected while a provider completes authentication and Hermes-Relay verifies the session. Callback pages identify the selected provider.

## Changed

- Browser callback success and error pages show the selected provider and use guidance that applies to self-hosted and hosted Hermes.

## Fixed

- A bounded sign-in foreground service keeps the browser callback and session verification connected while Hermes-Relay is in the background. It ends after completion, cancellation, or timeout.

## Install / Verify

- App version: **1.18.1** (versionCode **59**).
- Standard Chat, sessions, profiles, Manage, and Standard Voice use current upstream Hermes. The optional Hermes-Relay Plugin **1.12.0** supplies Relay tools and Secure Link host support.
- Existing saved connections, profiles, sessions, history, and drafts remain in place.
- Basic/password, self-hosted OIDC, and Nous sign-in passed on a physical Samsung device with Battery Saver off. Other OEMs, Battery Saver-on behavior, and process death during provider UI were not separately verified.
