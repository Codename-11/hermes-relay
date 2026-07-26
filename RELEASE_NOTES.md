# Hermes-Relay-Android v1.5.1

**Release Date:** July 26, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.5.1-sideload-release.apk` and tap it for the full feature set, or install the conservative build from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

This patch restores reliable narration and background behavior in Voice, adds focused and full-conversation voice layouts, and keeps richly formatted streamed answers anchored at their completed end.

## Added

- Voice Focus keeps narration, Markdown, tools, media, and actionable cards in a compact voice-first view.
- Voice Conversation exposes the complete Chat renderer while preserving the active voice session.
- A final-answer speech preference keeps intermediate progress visual while supported voice paths wait for the settled response.

## Fixed

- Standard Voice narrates valid completed assistant responses instead of losing them during the generation-to-speech handoff.
- Realtime tasks promoted to background release the foreground spinner and microphone while progress and results remain reachable.
- Completed streamed answers switch to full Markdown and preserve the measured trailing edge instead of jumping to the start of the response.
- Assistant text uses the theme's full-contrast foreground with a more readable chat type scale.

## Install / Verify

- App version: **1.5.1** (versionCode **34**).
- Standard Chat and Vanilla Hermes voice continue to work against unmodified upstream Hermes.
