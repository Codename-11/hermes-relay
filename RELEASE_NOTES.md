# Hermes-Relay Android v1.15.1

**Release Date:** September 2, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.15.1-sideload-release.apk` and tap it for the full feature set, or install the conservative build from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

This patch improves chat, media, and voice reliability. It reduces memory-heavy work, keeps attachment previews stable through rotation, and makes follow-up message behavior and voice errors easier to understand.

## Changed

- Choose Correct now or Queue next from a slim tray behind the composer. Chat settings sets the default; the tray overrides one message. Stop pauses the queue, Resume continues it, and editing or removing an item preserves its successors.
- Chat and Voice use readable centered layouts on wider screens, including landscape Voice Focus.

## Fixed

- Correction and delivery labels remain visible inside user-message bubbles.
- Voice errors open in a scrollable dialog with separate Retry and Dismiss actions.
- Attachment previews remain open through rotation, and video previews preserve their proportions.
- Release optimization preserves the native speech configuration required for wake-word startup.
- Standard Hermes attachments download directly to disk with bounded size checks.
- Session refresh avoids repeated request loops; history loads, Markdown, image previews, and media exports keep memory use bounded.
- Image-generation progress stays visible through gaps between interim replies and returned media.
- The first prompt waits for Gateway session readiness. Ownership refusals retain the prompt for retry and show the original server error.

## Install / Verify

- App version: **1.15.1** (versionCode **54**).
- Standard Chat, sessions, profiles, Manage, voice, and ordinary media use current upstream Hermes. Speech-to-text still requires a configured provider on the host.
- Hermes-Relay Plugin **1.11.1** remains the current optional plugin release; this Android patch does not require a new plugin version.
- Paused text queues can be restored. Attachment bytes are not persisted in preferences; unrestorable attachment queues must be reviewed and sent again.
- Explicit Direct API/API-only connections remain supported and are not used as silent failover for Dashboard-owned chats.
- Granular Device Control and the system Voice Focus overlay remain sideload-only.
