# Hermes-Relay-Android v0.8.0

**Release Date:** May 23, 2026
**Since v0.7.0:** Google Play-safe Bridge Core hardening, provider-native Realtime Agent voice with reliable low-latency playback, a text + mic Voice Lab, connection diagnostics, and clearer Voice Settings.

v0.8.0 is the Android release-prep build for resubmitting the enhanced Google Play track. The Play artifact keeps chat, profiles, voice, terminal/TUI relay, media, notification companion, relay sessions, QR pairing, and diagnostics, while leaving AccessibilityService-backed Device Control only in the sideload flavor.

---

## Download

v0.8.0 ships in two Android build flavors. APK and AAB filenames are version-tagged:

| Flavor | File | Who it's for |
|---|---|---|
| Google Play | `hermes-relay-0.8.0-googlePlay-release.aab` | Upload this Android App Bundle to Play Console. It has no AccessibilityService, screen reading, screenshots, gestures, SMS/calls, contacts/location, overlays, wake locks, or unattended phone control. |
| sideload | `hermes-relay-0.8.0-sideload-release.apk` | Direct-install APK for full Device Control testing. Installs as `com.axiomlabs.hermesrelay.sideload`. |
| googlePlay APK | `hermes-relay-0.8.0-googlePlay-release.apk` | Parity/testing artifact. |
| sideload AAB | `hermes-relay-0.8.0-sideload-release.aab` | Parity/testing artifact. |

Verify integrity with `SHA256SUMS.txt` from the same release. See the [Sideload guide](https://codename-11.github.io/hermes-relay/guide/getting-started.html#sideload-apk) for APK install steps.

---

## Highlights

### Google Play Bridge Core

- Google Play now ships Bridge Core without AccessibilityService-backed Device Control.
- The Play artifact keeps pairing, profiles, chat, voice, terminal/TUI relay, media, notification companion, relay sessions, connection status, and diagnostics.
- Sideload remains the only track for screen reading, gestures, screenshots, SMS/call/contact/location helpers, overlays, wake locks, and unattended control.
- User docs, feature matrix, privacy/security copy, and Play listing notes now match that artifact boundary.

### Provider-native Realtime Agent

- Realtime Agent is now a true provider-native voice engine rather than a render-after-Hermes fallback.
- Android streams mic PCM to the relay; the relay opens a server-side xAI or OpenAI realtime session.
- Hermes remains the only path for tools, memory, research/current-data checks, confirmations, side effects, profile context, and durable transcript state.
- The provider receives compact Hermes function results and speaks natural post-tool summaries instead of reading raw tool output aloud.

### Reliable, low-latency realtime playback

- Fixed silent / choppy first-turn realtime audio: the AudioTrack deep-buffer cold-start was parking the playback head at zero. The streaming buffer was shrunk (4000ms → 700ms), the low-latency prebuffer retuned, and a preroll force-start removed for reliable playback from the first frame.
- Added playback diagnostics — time-to-first-audio, requested-vs-actual buffer logging, a first-frame watchdog, and a drain cross-check — so cold-start and underrun issues surface in the Diagnostics log.

### Voice Lab (text + mic demos)

- The realtime voice test screen offers a **Text demo** (raw provider TTS) and a **Mic demo** (full agent path: real speech recognition, Hermes brokering, spoken reply) with tap-to-record / tap-to-stop capture.
- The Voice Lab waveform now follows the playback cursor (driven by the player's amplitude at the playback position) instead of socket-arrival time, so the visual matches what is heard.

### Voice Settings and diagnostics

- Voice Settings now separates **Voice Engine** from global controls.
- The active engine controls the visible card: **Hermes Chat + Voice Output** or **Realtime Agent**.
- The fallback TTS card is global and remains visible for both engines.
- **Test Current Engine** plays the stable voice-output sample in stable mode and opens a provider-native Realtime Agent test session in Realtime Agent mode.
- Settings now has app-level Diagnostics, and API / Relay / Session detail drawers include sanitized recent activity tails.
- Voice turns preflight relay health and use shorter timeouts so a hung relay surfaces as a connection error instead of an indefinite Thinking state.

---

## Google Play Resubmission Notes

- Upload `hermes-relay-0.8.0-googlePlay-release.aab`.
- Play Console release notes can use the `docs/play-store-listing.md` **Release Notes** section.
- The merged Play manifest should contain no `AccessibilityService`, `BIND_ACCESSIBILITY_SERVICE`, `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`, `SEND_SMS`, `CALL_PHONE`, contacts, or location permissions.
- The Play listing should not claim screen reading, screenshots, phone control, or accessibility automation.

## Verification

- Android version metadata: `0.8.0` / `versionCode 10`.
- Google Play release Kotlin compile passed.
- Google Play release AAB build passed.
- Google Play merged manifest has no forbidden Device Control entries.
- Google Play AAB is release-signed with the production upload keystore (not debug-signed).
- VitePress user-docs build passed.
- Focused voice engine, realtime playback (buffer policy / amplitude / watchdog), and diagnostics unit tests passed.

## Post-install Smoke

- Install/update the sideload APK over the existing sideload app with `adb install -r`.
- Existing pairing should survive a same-flavor update. Re-pair only if you uninstall app data, switch flavor/applicationId, revoke the device, or intentionally clear the server session store.
- Confirm Settings -> Connections shows API, Relay, Session, and Diagnostics activity.
- Confirm Settings -> Voice shows the selected engine card and Test Current Engine follows the selected path.
- In Voice mode, test Hermes Chat + Voice Output first, then opt into Realtime Agent and ask a current-data question to confirm Hermes is queried instead of the provider guessing.
