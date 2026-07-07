---
layout: doc
title: Privacy Policy
description: Privacy policy for the Hermes-Relay Android app
---

# Privacy Policy

**Hermes-Relay** · Effective date: May 19, 2026

Hermes-Relay is a native Android app that connects to your own [Hermes Agent](https://github.com/NousResearch/hermes-agent) host. This policy describes how the app handles your data.

## Summary

Hermes-Relay does not collect, transmit, or share personal data with third parties. The app connects only to servers that you configure. There are no accounts, no hosted Hermes-Relay cloud service, and no analytics sent externally.

## Google Play Track

The Google Play build ships Hermes Bridge Core: chat, voice, terminal/TUI relay, notification companion, media handoff, relay sessions, and status. It does **not** include AccessibilityService-based Device Control and cannot read your screen, tap/type/swipe, capture screenshots, send SMS, place calls, access contacts or location, or perform unattended phone control.

The sideload build is a separate distribution track for users who intentionally install Device Control outside Google Play.

## Data Storage

All data is stored locally on your device in the app's private sandbox:

| Data | Storage Method |
|------|---------------|
| Server URLs, preferences | Android DataStore (app-private) |
| API key, relay session tokens | AES-256-GCM encryption via Android Keystore |
| Performance counters | Android DataStore (local only) |
| Notification trigger rules and activity log | Android DataStore (local only) |

Chat messages are **not cached** on your device. They are loaded from your Hermes server on demand and exist only in memory while the app is running.

## Network Connections

The app connects only to endpoints you configure:

- **Your Hermes API server** — HTTP/SSE for chat streaming
- **Your relay server** — WSS for terminal/TUI relay, Bridge Core status, media handoff, notification companion, and session management
- **Your relay voice routes** — HTTP(S)/WSS for speech-to-text, voice settings, realtime voice sessions, and text-to-speech audio when you use Voice mode

No connections are made to Google, Anthropic, or any other third-party service by the app. There is no telemetry, no crash reporting, no DNS prefetching, and no background network activity to hosted services.

## Permissions

Google Play build:

| Permission or access | Purpose | Required |
|----------------------|---------|----------|
| Internet | Connect to your Hermes servers | Yes |
| Network State | Detect connectivity for reconnect behavior | Yes |
| Camera | QR code scanning for server pairing | No |
| Microphone | Voice mode speech-to-text | No |
| Notification Access | Optional notification companion metadata forwarding to your paired relay | No |

Notification Access is granted and revoked from Android system settings. When enabled, Hermes-Relay forwards posted-notification package, title, text, subtext, timestamp, and notification key to your paired relay. It does not forward notifications to a Hermes-Relay cloud service. If you separately enable Notification triggers, matching happens locally on the phone; the MVP action writes a local activity-log entry and posts a local “Ask Hermes?” prompt. It does not send a new AI request or reply in another app automatically.

Sideload Device Control builds may request additional permissions for overlay, foreground service, wake lock, screenshots, contacts, location, SMS, and calls. Those permissions are not present in the Google Play build.

## Third-Party Services

Hermes-Relay includes no advertising, tracking, or analytics services. The app is built with Android platform components and open-source libraries.

::: info Note
Your Hermes server may connect to AI providers such as OpenAI or Anthropic server-side. That network activity is outside the scope of this app and governed by your server's configuration.
:::

## Data Export & Deletion

From the app's Settings screen, you can:

- **Export** a full connection backup. The file includes server URLs, preferences,
  API keys, relay session tokens, device IDs, and dashboard cookies so restored
  connections can work without manual re-entry. Keep it private.
- **Import** a saved configuration
- **Full reset** to permanently delete local data including encrypted credentials

Uninstalling the app removes all stored data from your device.

## Children's Privacy

Hermes-Relay is not directed at children under 13. We do not knowingly collect information from children.

## Changes to This Policy

Updates will be posted on this page with a revised effective date. Significant changes will be noted in the app's release notes.

## Contact

Questions about this policy: [GitHub Issues](https://github.com/Codename-11/hermes-relay/issues)

## Open Source

Hermes-Relay is [MIT licensed](https://github.com/Codename-11/hermes-relay). All source code is publicly auditable.
