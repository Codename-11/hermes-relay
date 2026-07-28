# Hermes-Relay-Android v1.5.2

**Release Date:** July 28, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.5.2-sideload-release.apk` and tap it for the full feature set, or install the conservative build from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

This patch restores reliable dashboard sign-in for self-hosted OIDC and Nous Portal connections, including private-LAN and Tailscale routes, and prevents replayed chat events from destabilizing the conversation list.

## Fixed

- Self-hosted OIDC returns through the dashboard cookie flow instead of a desktop-only loopback callback.
- Nous Portal authentication opens in the system browser so provider security challenges can complete.
- Native PKCE uses standards-compatible unpadded Base64URL and preserves the dashboard's canonical HTTPS callback origin while keeping tokens scoped to the active route.
- Full-screen in-app sign-in remains available for compatible dashboard providers.
- Replayed upstream chat events are coalesced before rendering, preventing duplicate message keys.

## Install / Verify

- App version: **1.5.2** (versionCode **35**).
- Standard Chat and Vanilla Hermes voice continue to work against unmodified upstream Hermes.
