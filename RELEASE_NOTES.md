# Hermes-Relay Android v1.16.0

**Release Date:** September 9, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.16.0-sideload-release.apk` and tap it for the full feature set, or install the conservative build from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

This release makes startup and connection switching safer, prevents a network-change crash, and keeps Bot Mode and delegated work stable across multiple Hermes gateways. Chat feedback, attachment failures, and session preparation are also easier to understand and review.

## Changed

- Delegated-agent activity remains available after parent replies as compact, bounded, read-only history. The live strip appears only while work is active, and historical views cannot control a running process.
- Android feedback uses themed banners and action cards. A long-press on the agent header opens session diagnostics, and Developer settings can preview message surfaces locally.

## Fixed

- An authenticated Gateway chat opens on the first foreground launch instead of waiting for a background-and-resume cycle.
- Network changes can invalidate route probes without racing the endpoint cache or crashing Android.
- Saved Dashboard sign-ins stay bound to their owning connection when switching gateways; an outgoing route cannot invalidate another connection's session.
- Dashboard sign-in removes pasted line breaks from username and password fields while preserving every other credential character.
- Bot Mode and Active Now keep connection identity when different gateways expose the same profile name, and progress opens only on the selected bot.
- Missing attachments keep their error and Retry action in the attachment card without repeated global messages.
- Chat distinguishes session preparation from response streaming and retains initialization errors received before session acknowledgement.

## Install / Verify

- App version: **1.16.0** (versionCode **55**).
- Standard Chat, sessions, profiles, Manage, voice, and ordinary media use current upstream Hermes. Speech-to-text still requires a configured provider on the host.
- Hermes-Relay Plugin **1.11.2** is the matching optional release for Relay tools; the Android connection, Bot Mode, and delegated-activity fixes do not require the plugin.
- Already-erased or revoked Dashboard credentials still require a legitimate sign-in; this release prevents cross-connection invalidation going forward.
- Explicit Direct API/API-only connections remain supported and are not used as silent failover for Dashboard-owned chats.
- Granular Device Control and the system Voice Focus overlay remain sideload-only.
