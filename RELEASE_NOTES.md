# Hermes-Relay Android v1.15.0

**Release Date:** August 31, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.15.0-sideload-release.apk` and tap it for the full feature set, or install the conservative build from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

This release makes the everyday Android experience work through current upstream Hermes without treating the optional Hermes-Relay Plugin as a prerequisite. It also strengthens parent access, cross-client activity, transport ownership, recovery, and release visibility.

## Changed

- **Upstream-first standard surfaces.** Chat attachments, inbound host files, current-session Git reads, Nous usage bars, and keyed Hermes notices use authenticated Dashboard/Gateway contracts first. Relay remains an additive compatibility and enhancement layer.
- **Clear Settings ownership.** Media sits with Chat and Voice under Hermes. Proactive Threads, Terminal, Notification Companion, Relay sessions, enhanced voice, and Device Control stay grouped under Relay tools.
- **App-specific supervised parent access.** Parents choose a PIN or password and receive a six-word recovery phrase; Android device credentials and biometrics no longer grant parent authority.
- **Complete What's New history.** Release highlights, the remaining improvements and fixes, compatibility notes, toast counts, and history now come from one structured inventory.

## Fixed

- Host-local images, audio, video, and files download through the authenticated Dashboard and remain loaded across history reconciliation instead of flashing `Relay URL not configured` or returning to Loading.
- Standard voice remains usable after explicit Relay removal, while temporary Relay outages preserve configured choices and default-profile preferences stay isolated across connections.
- Android can display uniquely matched Desktop/TUI Working and Waiting activity without resuming, activating, or interrupting the external turn.
- Dashboard/Gateway chats preserve their transcript, draft, profile, and session through sign-out or outages instead of silently changing to Direct API.
- Completed text survives Dashboard-history authentication expiry and returns the existing sign-in recovery path.
- Long-running context compaction refreshes the turn watchdog instead of being interrupted as idle.
- Bot Chat history renders immediately after its route-owned handler binds.
- An exact idle live-session snapshot settles an Android-owned turn whose terminal frame was lost, reconciles history, and drains its queued follow-up.
- Supervised Add Gateway remains parent-owned across relock, back navigation, and pending setup cancellation.
- Completed generated images stay visible during marker persistence lag and retain the intended image-generation presentation.

## Install / Verify

- App version: **1.15.0** (versionCode **53**).
- Standard Chat, sessions, profiles, Manage, standard voice, outbound attachments, and ordinary inbound files work against current unmodified upstream Hermes without the optional Hermes-Relay Plugin.
- Install Hermes-Relay Plugin v1.11.1 for Terminal, proactive Threads/offline delivery, Notification Companion, Relay sessions, provider-native/realtime voice, compatibility media metadata, Secure Link, and phone/device control.
- Explicit Direct API/API-only connections remain supported and are never used as a silent failover for a Dashboard-owned chat.
- Granular Device Control and the system Voice Focus overlay remain sideload-only; the Google Play build does not declare their restricted permissions.
