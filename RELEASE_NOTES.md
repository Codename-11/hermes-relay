# Hermes-Relay-Android v1.1.0

**Release Date:** June 16, 2026
**Since v1.0.0:** A settings + chat-UX overhaul — quieter status surfaces, a single state-aware plugin badge, and chat-settings polish — plus a force-close fix and release-pipeline upgrades.

v1.1.0 is a refinement release on top of the 1.0 milestone. Settings is calmer and easier to read: status pills now appear only when a surface needs attention, the Power tools section shows one **Plugin active / required / offline** badge instead of an identical chip on every card, and the most-used controls sit where you reach for them. Chat settings render correctly, the system-prompt preview reflects your toggles, and a crash that could hit right after a successful pair is gone.

---

## Download

v1.1.0 ships in two Android build flavors. APK and AAB filenames are version-tagged:

| Flavor | File | Who it's for |
|---|---|---|
| Google Play | `hermes-relay-1.1.0-googlePlay-release.aab` | Upload this Android App Bundle to Play Console. It has no AccessibilityService, screen reading, screenshots, gestures, SMS/calls, contacts/location, overlays, or unattended phone control. |
| sideload | `hermes-relay-1.1.0-sideload-release.apk` | Direct-install APK for full Device Control. Installs as `com.axiomlabs.hermesrelay.sideload`. |
| googlePlay APK | `hermes-relay-1.1.0-googlePlay-release.apk` | Parity/testing artifact. |
| sideload AAB | `hermes-relay-1.1.0-sideload-release.aab` | Parity/testing artifact. |

Verify integrity with `SHA256SUMS.txt` from the same release. See the [Sideload guide](https://codename-11.github.io/hermes-relay/guide/getting-started.html#sideload-apk) for APK install steps.

---

## Highlights

### Settings screen overhaul

Settings was reorganized around what you actually touch and quieted down everywhere else:

- **Exception-only status pills.** Status pills now appear only when a surface needs attention and stay quiet when everything is healthy — no more a wall of green chips to read past.
- **One state-aware plugin badge.** The Power tools section shows a single **Plugin active / required / offline** badge instead of an identical "Relay paired" chip repeated on every card.
- **Layout that follows your reach.** Connections moved to the top (above the Hermes section), and Diagnostics + Developer options moved into the App section.
- **Restyled to match the app.** The status chips now use the app's translucent-bordered language, and the brand blue was deepened.

### Chat settings polish

- **Streaming-endpoint picker fixed.** The picker no longer wraps "Gateway" / "Sessions" onto a second line.
- **Live system-prompt preview.** The system-prompt preview now reflects the context toggles you've enabled (foreground app, battery, safety rails) with representative placeholder values, instead of looking inert.

### Force-close fix

A corrupt encrypted token store — which can happen after an app upgrade or a device restore — used to throw during construction and crash the app right after a successful pair, on both standard and relay connections. The token store now heals a corrupt keyset in place, and credential storage degrades to a re-pair instead of crashing if the device keystore is unusable.

### Release pipeline

- **Automated Play Console upload.** When a `PLAY_SERVICE_ACCOUNT_JSON` secret is configured, pushing a stable `android-v*` tag uploads the `googlePlay` App Bundle to the Production track as a draft (a human still starts the rollout). Prereleases are skipped, and the `sideload` flavor is structurally blocked from ever publishing to Play. Without the secret, releases publish to GitHub Releases exactly as before.
- **Desktop UI preview harness (`:ui-preview`).** A non-shipped Compose for Desktop module renders presentational composables in a window on the PC with Compose Hot Reload, for fast UI iteration without a device build/install loop. It reuses the shared sphere algorithm as its single source of truth.

---

## Upgrade notes

- The force-close fix means devices that previously crashed on connect after an upgrade or restore will heal their token store automatically on first launch of this build — no manual re-pair required in most cases.
- `appVersionCode` is **13**.
