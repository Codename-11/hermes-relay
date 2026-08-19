# Hermes-Relay-Android v1.10.0

**Release Date:** August 18, 2026

## Download

> Installing on your phone? Download `hermes-relay-1.10.0-sideload-release.apk` and tap it for the full feature set, or install the conservative build from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The `.aab` file is a Play Console upload bundle and cannot be installed by tapping it on a phone.

Verify the download against `SHA256SUMS.txt`. See the [sideload guide](https://hermes-relay.dev/docs/guide/sideload) for installation help.

## Summary

This release makes Android chat more continuous: drafts survive restarts, large
pastes become reviewable attachments, live replies render with incremental
Markdown, and foreground reconnect or completion no longer disrupts the open
conversation.

## Added

- Preserve text, quote/edit context, and pending attachments in the exact
  connection, profile, and session draft across app restarts.
- Convert large pastes into reviewable text attachments before sending while
  retaining compatible text delivery on fallback transports.
- Render paragraphs, lists, links, fenced code, and tables incrementally from
  the first streamed token without replacing the message at completion.

## Fixed

- Reattach the visible Gateway session after background/foreground reconnect
  and reconcile missed work without leaving the conversation.
- Expose Return on the software keyboard while keeping the dedicated Send
  action and physical-keyboard behavior distinct.
- Reject malformed imported credentials before network-header construction or
  encrypted-state replacement.
- Keep intentional scrollback fixed and bottom-follow stable while Markdown,
  voice actions, timestamps, and token metadata settle.

## Install / Verify

- App version: **1.10.0** (versionCode **45**).
- Standard Chat, sessions, Manage, profile identity, streaming Markdown, and
  Vanilla Hermes voice continue to work against unmodified upstream Hermes.
- The optional Relay plugin is not required for standard Android chat,
  foreground session reattachment, or streaming Markdown.
