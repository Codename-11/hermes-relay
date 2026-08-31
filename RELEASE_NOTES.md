# Hermes-Relay Android v1.14.0

**Release Date:** August 30, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.14.0-sideload-release.apk` and tap it for the full feature set, or install the conservative build from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

This release makes saved Hermes connections reliable across LAN, Tailscale, and public HTTPS while keeping Dashboard authentication bound to its exact trusted origin. It also adds delegated-agent previews and an optional native Git workspace, and improves Voice, Assistant, Threads, profile drafts, and Clarify interactions.

## Added

- **Delegated-agent previews.** Follow bounded lifecycle, progress, tool previews, and available read-only child history without leaving the parent chat. Partial history and reconnect gaps remain explicit. (#447)
- **Native Git workspace.** Review repository state, diffs, branches, staging, commits, and remotes from Chat or Settings. Git operations require Hermes-Relay Plugin v1.11.0 and retain confirmation and grant boundaries.

## Changed

- **Route-aware connections.** Dashboard, Relay, and optional API health are evaluated independently across LAN, Tailscale, and public HTTPS. Same-origin Relay ingress stays on the Dashboard origin that owns authentication, while direct compatibility routes keep separate credentials. (Related: #399)
- **Voice Focus controls.** Stop and immediate spoken steering remain accessible while Hermes is Thinking, Transcribing, or Speaking, including TalkBack, Switch Access, keyboard, and sideload overlay surfaces.

## Fixed

- Wake-word detection packages one compatible ONNX Runtime for sherpa and Java JNI on every supported ABI. (#444)
- Continuous voice waits for barge-in microphone teardown before listening again. (#464)
- Fresh chats retain their selected profile without reopening a previous session or carrying a proactive Thread route across profiles. (#436)
- Provisional Threads can be removed locally and reconcile with promoted sessions without deleting server history. (#461)
- Clarify cards expose a reachable Other answer, keyboard Send, and authoritative expiry behavior. (#446)
- Passive Android browsing no longer claims or interrupts a turn owned by another client. (Related: #365)
- Assistant sessions show retryable no-speech feedback, recover their active state after recreation, and redact conversation details behind the keyguard. (Related: #424)
- Protected Relay ingress `401/403` responses are recognized as authentication boundaries rather than outages; malformed, different-origin, and direct unauthorized routes still fail closed.

## Install / Verify

- App version: **1.14.0** (versionCode **52**).
- Standard Chat, sessions, profiles, Manage, and standard voice continue to work against unmodified upstream Hermes without the optional Relay plugin.
- Install Hermes-Relay Plugin v1.11.0 for same-origin Relay extensions, Git workspace actions, Bridge, media, proactive features, and enhanced voice.
- Granular Device Control and the system Voice Focus overlay remain sideload-only; the Google Play build does not declare their restricted permissions.
- Existing connections, drafts, sessions, profile ownership, and legacy direct Relay routes remain data-preserving compatibility paths.
