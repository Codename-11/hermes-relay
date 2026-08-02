# Hermes-Relay-Android v1.5.3

**Release Date:** July 31, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.5.3-sideload-release.apk` and tap it for the full feature set, or install the conservative build from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

This patch prevents Voice Focus from closing when live chat rows receive their persisted server identities during history reconciliation.

## Fixed

- Voice Focus now uses the same stable row identity as the main conversation, preventing duplicate-key crashes while live messages reconcile with persisted history.

## Install / Verify

- App version: **1.5.3** (versionCode **36**).
- Standard Chat and Vanilla Hermes voice continue to work against unmodified upstream Hermes.
