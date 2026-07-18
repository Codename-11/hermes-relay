# Hermes-Relay-Android v1.4.8

**Release Date:** July 18, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.4.8-sideload-release.apk` and tap it for the full feature set, or install the conservative build from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

This patch restores the public privacy-policy URL required by Google Play and moves the canonical policy to hermes-relay.dev.

## Fixed

- The historical GitHub Pages privacy URL now serves the complete policy instead of returning 404.
- The About screen opens the canonical policy on hermes-relay.dev.
- Android release automation verifies both public policy URLs before uploading or publishing to Google Play.

## Install / Verify

- App version: **1.4.8** (versionCode **31**).
- Standard Chat and Vanilla Hermes voice continue to work against unmodified upstream Hermes.
