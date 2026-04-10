---
layout: page
title: Privacy Policy
description: Privacy policy for the Hermes-Relay Android app
---

# Privacy Policy

**Hermes-Relay** · Effective date: April 8, 2026

Hermes-Relay is a native Android app that connects to your self-hosted [Hermes Agent](https://github.com/NousResearch/hermes-agent) server. This policy describes how the app handles your data.

## Summary

Hermes-Relay does not collect, transmit, or share any personal data with third parties. The app connects only to servers that you configure. There are no accounts, no cloud services, and no analytics sent externally.

## Data Storage

All data is stored locally on your device in the app's private sandbox:

| Data | Storage Method |
|------|---------------|
| Server URLs, preferences | Android DataStore (app-private) |
| API key, session tokens | AES-256-GCM encryption via Android Keystore |
| Performance counters | Android DataStore (local only) |

Chat messages are **not cached** on your device. They are loaded from your Hermes server on demand and exist only in memory while the app is running.

## Network Connections

The app connects only to endpoints you configure:

- **Your Hermes API server** — via HTTP/SSE for chat streaming
- **Your relay server** — via WSS for terminal and bridge channels (optional)

No connections are made to Google, Anthropic, or any other third-party service. There is no telemetry, no crash reporting, no DNS prefetching, and no background network activity.

## Permissions

| Permission | Purpose | Required |
|------------|---------|----------|
| Internet | Connect to your Hermes servers | Yes |
| Network State | Detect connectivity for auto-reconnect | Yes |
| Camera | QR code scanning for server pairing | No |

No access to contacts, location, microphone, storage, or other sensitive data.

## Third-Party Services

Hermes-Relay includes **no** third-party SDKs, advertising, tracking, or analytics services. The app is built entirely with first-party Android components (Jetpack Compose, OkHttp, AndroidX).

::: info Note
Your Hermes server may connect to AI providers (OpenAI, Anthropic, etc.) server-side. That network activity is outside the scope of this app and governed by your server's configuration.
:::

## Data Export & Deletion

From the app's Settings screen, you can:

- **Export** your configuration (server URLs, preferences — secrets excluded)
- **Import** a saved configuration
- **Full reset** — permanently deletes all local data including encrypted credentials

Uninstalling the app removes all stored data from your device.

## Children's Privacy

Hermes-Relay is not directed at children under 13. We do not knowingly collect information from children.

## Changes to This Policy

Updates will be posted on this page with a revised effective date. Significant changes will be noted in the app's release notes.

## Contact

Questions about this policy: [GitHub Issues](https://github.com/Codename-11/hermes-relay/issues)

## Open Source

Hermes-Relay is [MIT licensed](https://github.com/Codename-11/hermes-relay). All source code is publicly auditable.
