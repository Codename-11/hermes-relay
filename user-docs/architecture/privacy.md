# Privacy & Data Handling

Hermes-Relay connects only to your own machines — no cloud accounts, no hosted Hermes-Relay backend, no ads, and no third-party analytics.

## Track split

- **Google Play:** Bridge Core only — chat, voice, standard inbound files, terminal/TUI relay, notification companion, Relay media enhancements, relay sessions, and status. No AccessibilityService or MediaProjection Device Control, taps, typing, SMS, calls, contacts, location, or unattended phone control. Voice-only overlays and bounded user-invoked Android Assistant context are separate optional capabilities.
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
| Your relay server | WSS | Terminal/TUI relay, Bridge Core status, explicit Relay media, notifications, sessions |
| Your relay voice routes | HTTP(S)/WSS | Voice settings, STT, realtime voice, TTS |

HTTPS is enforced for non-localhost remote connections. No background pings or DNS prefetching to external services.

## Optional local permissions

The Google Play build can request camera for QR pairing, microphone for Voice
mode or opt-in local “Hey Hermes” detection, and Android Notification Access for
the notification companion. Pre-activation wake audio stays on the phone.
Notification Access forwards posted-notification package, title, text, subtext,
timestamp, and notification key to your paired relay only after you enable the
system permission.

## Data export and reset

From **Settings**, you can export a full connection backup, import a backup, or perform a full reset that wipes local data including encrypted credentials. Full backups include sensitive connection material such as API keys, relay session tokens, device IDs, and dashboard cookies so restored connections can work without manual re-entry. Keep exported backup files private.

## Open source

All code is [MIT licensed](https://github.com/Codename-11/hermes-relay) and publicly auditable. See the full [privacy policy](https://hermes-relay.dev/privacy.html).

## Voice Overlay

Voice Overlay is optional in both builds. Start it explicitly from Voice Focus while Hermes-Relay is visible and unlocked. It requires microphone access, display-over-other-apps access and an enabled microphone notification with Stop voice. Audio goes to the configured Hermes server; the overlay does not read or control other apps. Stop voice, closing the overlay, screen lock, task removal or loss of required access ends the overlay voice session. Returning to the app keeps foreground protection until the app is resumed. Granting permissions never starts a session.

If Hermes-Relay is selected as Android’s Digital Assistant, a compatible explicit unlocked assistant-button invocation may include bounded visible text and an available screenshot in one Standard voice turn sent to the configured Hermes server and AI provider. Ordinary wake and keyguard invocations do not request screen context.
