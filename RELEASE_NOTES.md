# Hermes-Relay-Android v0.8.1

**Release Date:** May 26, 2026
**Since v0.8.0:** A focused patch fixing a voice-mode crash. No new features.

v0.8.1 is a patch release. If you don't use voice mode with barge-in enabled, v0.8.0 is unaffected — but updating is still recommended.

---

## Download

v0.8.1 ships in two Android build flavors. APK and AAB filenames are version-tagged:

| Flavor | File | Who it's for |
|---|---|---|
| Google Play | `hermes-relay-0.8.1-googlePlay-release.aab` | Upload this Android App Bundle to Play Console. It has no AccessibilityService, screen reading, screenshots, gestures, SMS/calls, contacts/location, overlays, wake locks, or unattended phone control. |
| sideload | `hermes-relay-0.8.1-sideload-release.apk` | Direct-install APK for full Device Control. Installs as `com.axiomlabs.hermesrelay.sideload`. |
| googlePlay APK | `hermes-relay-0.8.1-googlePlay-release.apk` | Parity/testing artifact. |
| sideload AAB | `hermes-relay-0.8.1-sideload-release.aab` | Parity/testing artifact. |

Verify integrity with `SHA256SUMS.txt` from the same release. See the [Sideload guide](https://codename-11.github.io/hermes-relay/guide/getting-started.html#sideload-apk) for APK install steps.

---

## Fixed

### Voice mode crash with barge-in on legacy TTS playback

Starting voice mode with **barge-in enabled** while the relay served audio over the legacy `/voice/synthesize` path crashed the app the instant the agent began speaking — the first word or two played, then the app died with `Player is accessed on the wrong thread`.

The barge-in listener reads the audio session id from a background thread to attach the echo canceller, but Media3's `ExoPlayer` is thread-confined and throws when its `audioSessionId` getter is read off the main thread. `VoicePlayer.audioSessionId` is now backed by a thread-safe cache populated from main-thread playback callbacks, so it's safe to read from any thread.

This only affected the **opt-in** barge-in feature on the legacy text-to-speech path; the provider-native Realtime Agent and Voice Output paths were never affected.
