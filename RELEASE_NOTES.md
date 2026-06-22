# Hermes-Relay-Android v1.2.1

**Release Date:** June 21, 2026
**Since v1.2.0:** A focused follow-up to the big personalization release — add a **profile lock**, an in-app **changelog**, a clean **diagnostics → report** flow, and a non-nagging **update nudge**, plus a round of voice and realtime reliability fixes.

v1.2.1 builds on 1.2.0's personalization and transparency themes with quality-of-life and reliability work. Pin the app to one agent profile, review past release notes any time, turn a logged error into a one-tap GitHub issue, and get a tasteful in-app prompt when a newer build is live. Voice mode is calmer and more correct — Stop actually stops, hold-to-talk is steadier, the overlay is readable — and realtime turns that reach back to Hermes no longer fail with a session error.

---

## Download

v1.2.1 ships in two Android build flavors. APK and AAB filenames are version-tagged:

| Flavor | File | Who it's for |
|---|---|---|
| Google Play | `hermes-relay-1.2.1-googlePlay-release.aab` | Upload this Android App Bundle to Play Console. It has no AccessibilityService, screen reading, screenshots, gestures, SMS/calls, contacts/location, overlays, or unattended phone control. |
| sideload | `hermes-relay-1.2.1-sideload-release.apk` | Direct-install APK for full Device Control. Installs as `com.axiomlabs.hermesrelay.sideload`. |
| googlePlay APK | `hermes-relay-1.2.1-googlePlay-release.apk` | Parity/testing artifact. |
| sideload AAB | `hermes-relay-1.2.1-sideload-release.aab` | Parity/testing artifact. |

Verify integrity with `SHA256SUMS.txt` from the same release. See the [Sideload guide](https://codename-11.github.io/hermes-relay/guide/getting-started.html#sideload-apk) for APK install steps.

---

## Highlights

### Make it yours
- **Profile lock.** Pin the app to a single agent profile from Settings → Profile lock. Every other profile picker collapses to a locked state, and the lock screen stays the one place that lists every profile — with a clear notice if the locked profile isn't on the current server.

### Find your way back
- **In-app What's New & changelog.** A new Settings entry shows the current and past release notes any time, not just the post-update popup.

### When something breaks
- **Diagnostics → tap for detail + report.** Logged errors now carry clean titles and open a detail view with **Copy / Share / Create-GitHub-issue** — the same flow as crash reports. Classified errors across voice, chat, and connection are captured centrally.
- **Update-available nudge.** A dismissable in-app banner when a newer version is live — Google Play In-App Update on Play installs, GitHub Releases on sideload. Per-version dismissal, throttled, never nags.

### Voice & realtime
- **Voice override applies in Auto mode.** A chosen per-profile/enhanced voice now takes effect on Auto with the relay paired (previously only "Relay" mode applied it); voice settings are also namespaced per connection.
- **Realtime "Stop" stops immediately**, over-chatty spoken status is throttled, and long background tasks no longer time out the turn.
- **Steadier voice controls.** Hold-to-talk holds until you genuinely lift your finger, and the voice overlay is readable — opaque panel and status bubbles, non-wrapping labels, and invalid engine/route combinations disabled.
- **Connection status overlay** clears faster — resolved (error/warning) toasts auto-dismiss within ~5s instead of lingering.

---

## Upgrade notes
- All new app features are available on **both** flavors (client-side; no Device Control needed).
- **Relay-side fix (ships in the plugin, not the APK):** brokered Realtime Agent turns that reach back to Hermes no longer fail with `session_not_found` — the relay now mints/reuses a valid API-server session and reads the current nested create-session response. Relay operators pick this up via `hermes-relay-update`.
- `appVersionCode` is **15**.
