# Privacy & Data Handling

Hermes-Relay is a companion app for the Hermes agent. It connects only to servers you configure — there are no cloud accounts, hosted backends, ads, or third-party analytics.

## No Hermes-Relay Hosted Data Collection

- The app makes **no connections** to Anthropic, Google, or any third party by default
- **No telemetry**, analytics, crash reports, or tracking data are sent externally
- **No advertising SDKs** or third-party SDKs that phone home are included
- Your Hermes server may connect to configured AI providers for inference and
  bounded capability discovery; that traffic is server-side and uses the
  credentials already owned by the selected Hermes profile

## Build Tracks

Hermes-Relay has two Android tracks:

| Track | Bridge scope | Sensitive Android APIs |
|-------|--------------|------------------------|
| Google Play | **Bridge Core**: chat, voice, terminal/TUI relay, notification companion, media handoff, relay sessions, status | No AccessibilityService, no overlay permission, no MediaProjection screenshots, no wake-lock device-control service, no contacts/location/SMS/call permissions. Optional Android Assistant screen context is described below. |
| Sideload | **Device Control**: the full agent-driven phone-control bridge | AccessibilityService, foreground service, overlay chip, optional screenshots, and phone-utility permissions when enabled |

The Google Play build cannot use Accessibility or MediaProjection to inspect or
control the screen, and it cannot tap/type/swipe, send SMS, place calls, access
contacts or location, or perform unattended phone control. If you explicitly
select Hermes as Android's Digital Assistant and invoke it through compatible
firmware's manual assistant control while unlocked, Android may provide bounded
visible screen text and a screenshot to the assistant session. Hermes labels that
data untrusted and sends it only with the first Standard voice turn to the Hermes
server you configured; that server may forward it to its configured AI provider.
Wake-word, power-button, keyguard, and ordinary assistant invocations do not request
screen context.

## Local Storage

All app data is stored on-device in the app's private sandbox:

| Data | Storage | Notes |
|------|---------|-------|
| API server URL, relay URL | DataStore preferences | Plaintext, app-private |
| API key | EncryptedSharedPreferences | AES-256-GCM via Android Keystore |
| Relay session token | EncryptedSharedPreferences | Same encryption as API key |
| Theme and display preferences | DataStore preferences | Tool display mode, reasoning toggle, voice preferences |
| Stats for Nerds counters | DataStore preferences | Response times, token counts, health stats — local only |
| Reliability reports | App-private JSON | Up to 20 locally redacted crash/handled-error records, retained for 14 days; no prompts, messages, profile names, hosts, tokens, or media |
| Pending Android Assistant context | App-private cache | Bounded visible text and optional JPEG keyed to one activation; consumed only after transport accepts the first Standard voice turn, retained across preflight failure, discarded on exit/cancel, and stale-cleaned after one hour |

Chat messages are **not cached locally**. They are loaded from the Hermes API server on demand and exist only in memory while the app is running.

## Network Communication

The app connects only to user-configured endpoints:

- **HTTP/SSE** to your Hermes API server for chat streaming
- **WSS** to your relay server for terminal/TUI relay, Bridge Core status, media handoff, notification companion, and paired-session management
- **HTTP(S)/WSS** to your relay server's `/voice/*` routes for voice settings, speech-to-text uploads, realtime voice websocket sessions, and text-to-speech audio when you use Voice mode
- **HTTP(S)/WSS** to the configured Hermes Dashboard/Gateway or API route for an
  explicitly invoked Android Assistant turn. When compatible firmware supplies
  screen context, bounded text and an optional screenshot accompany that one
  Standard voice turn and can reach the AI provider configured on the Hermes host.
- **HTTP(S)** to your optional Relay server's `/relay/model-capabilities` route
  when Android refines reasoning-effort choices for models already reported by
  upstream Hermes. The phone sends provider/model identifiers and the selected
  profile name, not provider credentials.
- Cleartext HTTP is permitted for local/private network connections to user-configured servers; the app warns when using insecure remote connections
- No DNS prefetching and no background pings to external services

The Relay capability resolver may make short, bounded requests from the Hermes
host to a configured provider endpoint. Results are cached for five minutes by
profile, endpoint, model, and a one-way credential fingerprint to limit repeated
provider traffic. Credentials and fingerprints remain on the host; Android
receives only reasoning support, ordered effort values, whether the values are
exact, and diagnostic provenance. A manual refresh requests a new server-side
resolution but does not expose or copy the selected profile's secrets.

## Permissions

Google Play build:

| Permission or access | Purpose |
|----------------------|---------|
| `INTERNET` | Connect to your Hermes servers |
| `ACCESS_NETWORK_STATE` | Detect connectivity changes for reconnect behavior |
| `CAMERA` | Optional QR code scanning for server pairing (`required="false"`) |
| `RECORD_AUDIO` | Optional Voice mode capture and opt-in local “Hey Hermes” detection. Pre-activation wake audio stays on the phone. |
| `MODIFY_AUDIO_SETTINGS` | Voice playback and audio-session behavior |
| Android Notification Access | Optional system setting for the notification companion; forwards posted-notification package, title, text, subtext, timestamp, and notification key to your paired relay |
| Android Digital Assistant role | Optional system setting. Compatible unlocked manual assistant invocations may let Android provide bounded visible screen text and a screenshot for one Standard voice turn. |

Sideload builds may additionally request permissions needed for Device Control, including overlay, foreground-service, wake-lock, screenshot, contacts, location, SMS, and call capabilities. Those permissions are not present in the Google Play manifest.

If you select Hermes as Android's default Digital Assistant and separately
enable background “Hey Hermes,” Android keeps the assistant service available
and local sherpa-onnx keyword spotting uses the microphone while the mode is
enabled. Wake audio is not sent to Hermes or Relay before activation. The
Digital Assistant listener and the notification-based experimental listener
cannot be enabled at the same time.

## Notification Companion

Notification companion is opt-in. The app only forwards notification metadata after you grant Android's system Notification Access permission. You can revoke it any time from Android Settings. Notification entries are sent to your paired relay over your configured WSS connection and are not sent to any hosted Hermes-Relay service.

## Data Export & Reset

From Settings, users can:

- **Review support information** in Diagnostics, then explicitly copy or share
  the exact redacted local text. Nothing is uploaded automatically.

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
