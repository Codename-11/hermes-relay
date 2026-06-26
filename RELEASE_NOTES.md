# Hermes-Relay-Android v1.2.4

**Release Date:** June 25, 2026
**Since v1.2.3:** A second connection-stability fix plus a new way to see whether your connection is encrypted. A transient blip on the dashboard session check — a pooled connection aborting or timing out over Tailscale — could still hard-close the app even after the v1.2.3 fix; that path is now handled cleanly. And the app now shows, at a glance, whether each transport is encrypted.

v1.2.4 is recommended for anyone connecting over Tailscale or public TLS. Plain-LAN connections were never affected by the crash.

---

## Download

v1.2.4 ships in two Android build flavors. APK and AAB filenames are version-tagged:

| Flavor | File | Who it's for |
|---|---|---|
| Google Play | `hermes-relay-1.2.4-googlePlay-release.aab` | Upload this Android App Bundle to Play Console. It has no AccessibilityService, screen reading, screenshots, gestures, SMS/calls, contacts/location, overlays, or unattended phone control. |
| sideload | `hermes-relay-1.2.4-sideload-release.apk` | Direct-install APK for full Device Control. Installs as `com.axiomlabs.hermesrelay.sideload`. |
| googlePlay APK | `hermes-relay-1.2.4-googlePlay-release.apk` | Parity/testing artifact. |
| sideload AAB | `hermes-relay-1.2.4-sideload-release.aab` | Parity/testing artifact. |

Verify integrity with `SHA256SUMS.txt` from the same release. See the [Sideload guide](https://codename-11.github.io/hermes-relay/guide/getting-started.html#sideload-apk) for APK install steps.

---

## Highlights

### Fixed
- **No more crash when the dashboard connection drops mid-check.** A transient network failure on the dashboard session check — for example a pooled connection aborting or timing out over Tailscale — could still force-close the app: the check returned a result type but re-threw the network error instead of reporting it, and it surfaced on the main thread. The check now reports the failure cleanly and the connection probe degrades gracefully, so a flaky link can no longer crash the app. (#129)

### Added
- **See whether your connection is encrypted.** The chat status chip, the connection card, and the route picker now show your encryption state at a glance — 🔒 **Encrypted · TLS**, 🛡️ **Encrypted · Tailscale** (both secure), 🛡️ **Mixed routes**, or ⚠️ **Not encrypted** — and tapping it opens a per-transport breakdown (chat, API, relay tools). A Tailscale or WireGuard route is now correctly shown as encrypted rather than implied insecure. A new ["Is my connection secure?"](https://codename-11.github.io/hermes-relay/architecture/connection-security.html) docs page explains the difference between TLS and overlay (WireGuard) encryption.

---

## Upgrade notes
- This is an app-side release on **both** flavors — no Device Control or server changes needed.
- If you connect over Tailscale or HTTPS, update and reconnect.
- `appVersionCode` is **18**.
