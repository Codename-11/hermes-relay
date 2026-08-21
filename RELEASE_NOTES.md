# Hermes-Relay-Android v1.11.0

**Release Date:** August 20, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.11.0-sideload-release.apk` and tap it for the full feature set, or install the conservative build from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

This release makes Android control more explicit and chat recovery more honest. Sideload users can grant only the Bridge capabilities they intend, including bounded or unlimited screen access, while every build gains clearer stored-session failures, complete multiline keyboard behavior, persistent cancellation status, and lower idle power use.

## Added

- Choose read-only, read-and-confirm, or custom Bridge capability presets for the active connection in sideload builds.
- Grant screen inspection and control for a bounded duration or explicitly keep them unlimited, with the active policy reflected in Relay status.

## Changed

- Use a summary-first Bridge screen that separates Agent access, unattended mode, Android readiness, and advanced safety without removing the full permission matrix.

## Fixed

- Keep stored-session resume failures in the conversation with route-aware details and explicit retry or dismiss actions instead of silently switching context.
- Insert newlines from both direct-text and synthesized-Enter software keyboards while preserving physical Enter, Shift+Enter, and Ctrl/Cmd+Enter behavior.
- Retain the Stopped status when a blank recovery placeholder is cancelled.
- Stop idle Sphere, waveform, and closed-drawer redraw loops when no motion is visible.
- Attach screen-capture surfaces only for requested frames, release unattended wake locks at command completion, and bind audio effects to the capture session.
- Reuse wake-word normalization buffers during continuous opt-in listening.

## Install / Verify

- App version: **1.11.0** (versionCode **46**).
- Standard Chat, sessions, Manage, stored-session recovery, software-keyboard multiline input, and Vanilla Hermes voice continue to work against unmodified upstream Hermes.
- Granular Device Control grants and screen-access durations are sideload-only; the Google Play build continues to ship Hermes Bridge Core without AccessibilityService Device Control.
- The optional Relay plugin is not required for standard Android chat, session recovery, or multiline composition.