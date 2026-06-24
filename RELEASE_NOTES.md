# Hermes-Relay-Android v1.2.3

**Release Date:** June 23, 2026
**Since v1.2.2:** A connection-stability hotfix. Connecting to a server over an **encrypted link** (Tailscale Serve or public HTTPS) could hard-close the app the moment the connection came up; that crash is fixed, so securing your connection no longer force-closes Hermes-Relay.

v1.2.3 is a focused fix for anyone connecting over Tailscale or public TLS. Plain-LAN connections were never affected.

---

## Download

v1.2.3 ships in two Android build flavors. APK and AAB filenames are version-tagged:

| Flavor | File | Who it's for |
|---|---|---|
| Google Play | `hermes-relay-1.2.3-googlePlay-release.aab` | Upload this Android App Bundle to Play Console. It has no AccessibilityService, screen reading, screenshots, gestures, SMS/calls, contacts/location, overlays, or unattended phone control. |
| sideload | `hermes-relay-1.2.3-sideload-release.apk` | Direct-install APK for full Device Control. Installs as `com.axiomlabs.hermesrelay.sideload`. |
| googlePlay APK | `hermes-relay-1.2.3-googlePlay-release.apk` | Parity/testing artifact. |
| sideload AAB | `hermes-relay-1.2.3-sideload-release.aab` | Parity/testing artifact. |

Verify integrity with `SHA256SUMS.txt` from the same release. See the [Sideload guide](https://codename-11.github.io/hermes-relay/guide/getting-started.html#sideload-apk) for APK install steps.

---

## Highlights

### Fixed
- **No more crash on connect over TLS / Tailscale.** Connecting over an encrypted link (Tailscale Serve or public HTTPS) could force-close the app with a `NetworkOnMainThreadException` as the connection came up — a live SSL socket was being closed on the main thread during client teardown, and a TLS close performs a network write. Socket teardown now always runs off the main thread, so connecting over a secured link is stable. Plain-LAN connections were never affected.

---

## Upgrade notes
- This is an app-side fix on **both** flavors — no Device Control or server changes needed.
- If you were crashing on connect over Tailscale or HTTPS, update and reconnect.
- `appVersionCode` is **17**.
