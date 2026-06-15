# Privacy & Data Handling

Hermes-Relay is a companion app for the Hermes agent. It connects only to servers you configure — there are no cloud accounts, hosted backends, ads, or third-party analytics.

## No External Data Transmission

- The app makes **no connections** to Anthropic, Google, or any third party by default
- **No telemetry**, analytics, crash reports, or tracking data are sent externally
- **No advertising SDKs** or third-party SDKs that phone home are included
- Your Hermes server may connect to AI providers such as OpenAI or Anthropic; that is server-side and outside this app's scope

## Build Tracks

Hermes-Relay has two Android tracks:

| Track | Bridge scope | Sensitive Android APIs |
|-------|--------------|------------------------|
| Google Play | **Bridge Core**: chat, voice, terminal/TUI relay, notification companion, media handoff, relay sessions, status | No AccessibilityService, no overlay permission, no MediaProjection screenshots, no wake-lock device-control service, no contacts/location/SMS/call permissions |
| Sideload | **Device Control**: the full agent-driven phone-control bridge | AccessibilityService, foreground service, overlay chip, optional screenshots, and phone-utility permissions when enabled |

The Google Play build cannot read your screen, tap/type/swipe, capture screenshots, send SMS, place calls, access contacts or location, or perform unattended phone control.

## Local Storage

All app data is stored on-device in the app's private sandbox:

| Data | Storage | Notes |
|------|---------|-------|
| API server URL, relay URL | DataStore preferences | Plaintext, app-private |
| API key | EncryptedSharedPreferences | AES-256-GCM via Android Keystore |
| Relay session token | EncryptedSharedPreferences | Same encryption as API key |
| Theme and display preferences | DataStore preferences | Tool display mode, reasoning toggle, voice preferences |
| Stats for Nerds counters | DataStore preferences | Response times, token counts, health stats — local only |

Chat messages are **not cached locally**. They are loaded from the Hermes API server on demand and exist only in memory while the app is running.

## Network Communication

The app connects only to user-configured endpoints:

- **HTTP/SSE** to your Hermes API server for chat streaming
- **WSS** to your relay server for terminal/TUI relay, Bridge Core status, media handoff, notification companion, and paired-session management
- **HTTP(S)/WSS** to your relay server's `/voice/*` routes for voice settings, speech-to-text uploads, realtime voice websocket sessions, and text-to-speech audio when you use Voice mode
- Cleartext HTTP is permitted for local/private network connections to user-configured servers; the app warns when using insecure remote connections
- No DNS prefetching and no background pings to external services

## Permissions

Google Play build:

| Permission or access | Purpose |
|----------------------|---------|
| `INTERNET` | Connect to your Hermes servers |
| `ACCESS_NETWORK_STATE` | Detect connectivity changes for reconnect behavior |
| `CAMERA` | Optional QR code scanning for server pairing (`required="false"`) |
| `RECORD_AUDIO` | Optional Voice mode microphone capture, requested when you use the mic |
| `MODIFY_AUDIO_SETTINGS` | Voice playback and audio-session behavior |
| Android Notification Access | Optional system setting for the notification companion; forwards posted-notification package, title, text, subtext, timestamp, and notification key to your paired relay |

Sideload builds may additionally request permissions needed for Device Control, including overlay, foreground-service, wake-lock, screenshot, contacts, location, SMS, and call capabilities. Those permissions are not present in the Google Play manifest.

## Notification Companion

Notification companion is opt-in. The app only forwards notification metadata after you grant Android's system Notification Access permission. You can revoke it any time from Android Settings. Notification entries are sent to your paired relay over your configured WSS connection and are not sent to any hosted Hermes-Relay service.

## Data Export & Reset

From Settings, users can:

- **Export** a full connection backup. The file includes server URLs,
  preferences, API keys, relay session tokens, device IDs, and dashboard
  cookies so restored connections can work without manual re-entry. Keep it
  private.
- **Import** a previously exported backup
- **Full reset** to wipe local data including encrypted credentials

## Stats for Nerds

Stats for Nerds tracks performance metrics such as time to first token, completion time, token usage, cost estimates, and health-check latency. These counters are stored locally in DataStore and never leave the device.

## Open Source

Hermes-Relay is MIT licensed. All source code is publicly available and auditable at [GitHub](https://github.com/Codename-11/hermes-relay).
