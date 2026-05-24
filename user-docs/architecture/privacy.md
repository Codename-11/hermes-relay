# Privacy & Data Handling

Hermes-Relay is a self-hosted app. It connects only to your own servers — no cloud accounts, no hosted Hermes-Relay backend, no ads, and no third-party analytics.

## Track split

- **Google Play:** Bridge Core only — chat, voice, terminal/TUI relay, notification companion, media handoff, relay sessions, and status. No AccessibilityService, screen reading, taps, typing, screenshots, SMS, calls, contacts, location, overlay, or unattended phone control.
- **Sideload:** Device Control — the separate sideload track can include AccessibilityService-backed phone control and the extra Android permissions needed for that surface.

## What stays on your phone

- **Server URLs and preferences** — stored in Android DataStore (app-private)
- **API key and session tokens** — encrypted with AES-256-GCM via EncryptedSharedPreferences
- **Stats for Nerds counters** — response times, token counts, health stats; stored locally, never sent externally

Chat messages are **not cached** on device. They load from your Hermes server on demand.

## What the app does NOT do

- Send telemetry, analytics, crash reports, or tracking data to any external service
- Include tracking, advertising, or third-party analytics SDKs
- Connect to Anthropic, Google, or any service beyond your configured servers
- Use AccessibilityService in the Google Play build

## Network connections

| Destination | Protocol | Purpose |
|-------------|----------|---------|
| Your Hermes API server | HTTP/SSE | Chat streaming |
| Your relay server | WSS | Terminal/TUI relay, Bridge Core status, media, notifications, sessions |
| Your relay voice routes | HTTP(S)/WSS | Voice settings, STT, realtime voice, TTS |

HTTPS is enforced for non-localhost remote connections. No background pings or DNS prefetching to external services.

## Optional local permissions

The Google Play build can request camera for QR pairing, microphone for Voice mode, and Android Notification Access for the notification companion. Notification Access forwards posted-notification package, title, text, subtext, timestamp, and notification key to your paired relay only after you enable the system permission.

## Data export and reset

From **Settings**, you can export your configuration (secrets excluded), import a backup, or perform a full reset that wipes local data including encrypted credentials.

## Open source

All code is [MIT licensed](https://github.com/Codename-11/hermes-relay) and publicly auditable. See the full privacy document at [`docs/privacy.md`](https://github.com/Codename-11/hermes-relay/blob/main/docs/privacy.md).
