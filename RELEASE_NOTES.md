# Hermes-Relay Android v1.19.0

**Release Date:** October 20, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.19.0-sideload-release.apk` and tap it for the full feature set, or install from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

Larger conversations open more reliably, quiet turns recover without interruption, and unread replies are easier to find. Voice playback, microphone handoff, sign-in, and startup appearance receive reliability fixes.

## Added

- Unread conversation markers and counts in Sessions, profiles, and the chat header.
- Optional muting of built-in English tool announcements independently of assistant readback.

## Changed

- Reply notifications identify the agent and conversation.
- Pairing controls distinguish shortening from operator-approved renewal and retain actionable errors.
- Voice settings distinguish phone interruption from host barge-in and explain Hold mode without changing defaults.

## Fixed

- Oversized history pages retry in smaller windows while preserving the recent 500-message window, order, compacted scrollback, profile scope, and existing memory guards. Complete reads retain their aggregate safety limits; individually oversized messages fail explicitly.
- Gateway replies avoid duplicate history transfers. Automatic conversation restoration pauses after a recorded out-of-memory crash while saved conversations and drafts remain intact.
- Quiet tools and missing completion events recover from authoritative Gateway state without sending Stop.
- Temporary reverse-proxy failures preserve native Dashboard sign-in.
- Background alerts retain conversation ownership, refresh onboarding permission defaults, and keep separate notification slots. Chat progress remains above the current composer.
- Wake-word listeners hand the microphone to voice capture; assistant activation waits for microphone startup.
- Streaming speech preserves whole sentences and short replies after tool gaps. File playback drains before Continuous mode listens again, and Final answer only speech survives chat-state races.
- Native voice runtime compatibility, profile-state startup ordering, and the saved Appearance palette remain consistent after startup.

## Install / Verify

- App version: **1.19.0** (versionCode **60**).
- Standard Chat, history, Manage, and Standard Voice use current upstream Hermes. The optional Hermes-Relay Plugin **1.13.1** supplies Relay tools and Secure Link host fixes.
- Existing connections, profiles, sessions, history, drafts, and composer layout are preserved.
- History recovery is covered by production HTTP fixtures and a Standard Phone API 36 rendered failure/reopen test. These checks do not certify behavior on every physical device.
- Recent voice changes have source and fixture coverage; physical listening verification remains incomplete.
- Screenshot upload authentication through Dashboard ingress remains tracked separately in [#745](https://github.com/Codename-11/hermes-relay/issues/745). This release does not claim to fix all screenshot behavior.
