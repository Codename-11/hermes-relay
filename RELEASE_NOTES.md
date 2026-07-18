# Hermes-Relay-Android v1.4.7

**Release Date:** July 18, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.4.7-sideload-release.apk` and tap it for the full feature set, or install the conservative build from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

This patch adds German, Brazilian Portuguese, and Japanese and makes long streamed replies grow smoothly while staying anchored at the latest text.

## Added

- Use German, Brazilian Portuguese, or Japanese throughout both Android product flavors.
- Language-picker and catalog freshness checks keep every shipped locale aligned with the canonical English resources.

## Fixed

- Long streamed replies insert text at a display-paced cadence instead of visibly rebuilding the conversation.
- The active response remains anchored at the latest text through growth and completion while readers who scroll into history remain undisturbed.

## Install / Verify

- App version: **1.4.7** (versionCode **30**).
- Standard Chat and Vanilla Hermes voice continue to work against unmodified upstream Hermes.
