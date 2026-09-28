# Hermes-Relay Android v1.18.0

**Release Date:** September 28, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.18.0-sideload-release.apk` and tap it for the full feature set, or install from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

Current Hermes Gateway prompts work again on Android. Setup, sign-in, voice handoff, media, and large-screen session navigation are more reliable, with optional Secure Link support for paired connections.

## Added

- Keep Sessions visible in an optional wide sidebar on larger screens.
- Show SuperGrok usage by default when no provider visibility choice is saved.

## Changed

- Gateway setup verifies Dashboard access and explains authentication failures. An HTTP address outside recognized private ranges requires explicit acceptance for that exact address and port; VPN protection remains the operator's responsibility.
- Secure Link shows the paired Dashboard route and applies its certificate pin to HTTP, Gateway, and voice traffic.
- Standard Voice reports when streaming output falls back to basic TTS, keeps queued speech in order, and exposes synthesis and playback timing in Stats for Nerds.

## Fixed

- Clarify, approval, sudo, and secret cards work with current upstream Hermes Gateway requests. Partial Clarify progress survives reconnects. (#631)
- Native Dashboard sign-in no longer lets an incomplete browser callback hold later attempts or their timeout and cancellation. (#632)
- The Gateway foreground service completes an accepted start before local retention stops, avoiding a startup/shutdown crash. (#603)
- Standard Voice speaks live background completions in the active conversation after the original turn finishes. (#545)
- Chat loads Gateway models when the picker first opens, including before the first message.
- Cold start applies the saved Appearance palette and light/dark choice before the first frame.
- Proactive phone Thread media appears as attachments without losing multiline text. (#485)

## Install / Verify

- App version: **1.18.0** (versionCode **58**).
- Standard Chat, sessions, profiles, Manage, and Standard Voice use current upstream Hermes. The optional Hermes-Relay Plugin **1.12.0** supplies Relay tools and Secure Link host support.
- Existing saved connections, profiles, sessions, history, and drafts remain in place.
- Physical paid-provider playback and speaker continuity were not independently tested for this release. The remaining audible pauses in legacy TTS stay open under #639; this release improves fallback handling and diagnostics without claiming those pauses are fixed.
