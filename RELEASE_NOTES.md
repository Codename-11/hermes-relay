# Hermes-Relay-Android v1.6.0

**Release Date:** August 2, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.6.0-sideload-release.apk` and tap it for the full feature set, or install the conservative build from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

This minor release brings personality and extensibility to Android: floating Petdex companions can explore the interface, Hermes plugins can contribute safe native pages, and Hermes can become the optional Android Digital Assistant. Voice, route failover, live-list identity, and Russian localization are also substantially improved.

## Added

- Choose, preview, and install Petdex companions from Appearance, or import your own pet. Pets remain separate from agent avatars and the background Sphere.
- Hold and drag a pet anywhere, or enable optional UI-aware roaming across measured chat bubbles, the composer, settings cards, and other safe ledges.
- Open native Android pages contributed by installed Hermes plugins. Pages use a host-rendered declarative schema, and scoped writes remain disabled until explicitly granted.
- Use Relay 1.5.0 to review, keep, or remove approval-gated agent-created plugin-page drafts.
- Select Hermes as Android’s default Digital Assistant, with an optional local “Hey Hermes” listener that keeps pre-activation audio on the device.
- Use the complete AI-assisted Russian Android catalog from the in-app language picker.

## Improved

- Assistant and floating Voice controls start compact, expand for transcript and response detail, and hand off to full Voice without restarting the turn.
- Voice interruption now covers generation and playback with upstream-aligned calibration, stop phrases, and private next-turn interruption context.
- The Agent Passport presents profile configuration, skills, routing, reasoning, and scoped credentials more clearly.
- Pet roaming follows measured terrain, bubble edges, scroll movement, obstacle recovery, animation capabilities, temperament, and reduced-motion/accessibility pauses.

## Fixed

- Streaming voice output falls back when no first audio arrives, and long Standard Voice recordings upload without duplicate in-memory encoding.
- Relay failover no longer allows competing reconnect loops to bounce rapidly between LAN and remote routes.
- Streamed chat rows retain stable UI identity while server IDs and background-process state reconcile.
- Pets recover from occupied or scrolling terrain, avoid text and the jump-to-latest control, and preserve walk, jump, held, and drop animation states.
- OEM assistant picker activation, local wake completion, and empty-speech recovery are reliable across the supported lifecycle.

## Install / Verify

- App version: **1.6.0** (versionCode **37**).
- Standard Chat and Vanilla Hermes voice continue to work against unmodified upstream Hermes.
- Petdex installation is built into Android. Native pages from ordinary installed plugins use the authenticated Dashboard; agent-created page drafts require the optional Relay 1.5.0 plugin.
