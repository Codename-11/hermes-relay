# Hermes-Relay-Android v1.4.5

**Release Date:** July 15, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.4.5-sideload-release.apk` and tap it for the full feature set, or install the conservative build from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://codename-11.github.io/hermes-relay/guide/sideload) for installation help.

## Summary

This patch keeps Gateway chats running when you switch sessions, clears expired approval prompts, and keeps provider wait messages out of the conversation transcript.

## Fixed

- Switching to another chat, profile, draft, or Thread no longer interrupts a running Gateway reply. Returning to the session restores its live checkpoint and reattaches to Hermes.
- Expired secret and sudo prompts collapse when Hermes reports their expiry, so stale actions no longer look usable.
- Provider wait, reconnect, and continuation notices stay in Chat's live status line instead of accumulating as assistant reasoning.

## Install / Verify

- App version: **1.4.5** (versionCode **27**).
- Standard Chat and Vanilla Hermes voice continue to work against unmodified upstream Hermes.
