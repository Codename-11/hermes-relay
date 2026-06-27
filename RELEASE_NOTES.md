# Hermes-Relay-Android v1.2.5

**Release Date:** June 27, 2026
**Since v1.2.4:** A crash fix and a new way to explore the app before connecting. A non-URL value entered in a server address field — a UI label, or a line copied from the docs — could force-close the app on the Manage / sign-in screen; that's now caught with an inline error. And a new offline **Try the demo** mode lets anyone preview the chat experience with no server, account, or network.

v1.2.5 is recommended for everyone.

---

## Download

v1.2.5 ships in two Android build flavors. APK and AAB filenames are version-tagged:

| Flavor | File | Who it's for |
|---|---|---|
| Google Play | `hermes-relay-1.2.5-googlePlay-release.aab` | Upload this Android App Bundle to Play Console. It has no AccessibilityService, screen reading, screenshots, gestures, SMS/calls, contacts/location, overlays, or unattended phone control. |
| sideload | `hermes-relay-1.2.5-sideload-release.apk` | Direct-install APK for full Device Control. Installs as `com.axiomlabs.hermesrelay.sideload`. |
| googlePlay APK | `hermes-relay-1.2.5-googlePlay-release.apk` | Parity/testing artifact. |
| sideload AAB | `hermes-relay-1.2.5-sideload-release.aab` | Parity/testing artifact. |

Verify integrity with `SHA256SUMS.txt` from the same release. See the [Sideload guide](https://codename-11.github.io/hermes-relay/guide/getting-started.html#sideload-apk) for APK install steps.

---

## Highlights

### Fixed
- **No more crash when a non-address is entered as a server URL.** Typing or pasting non-URL text — for example a UI label, or a line copied from the docs — into the API server or Dashboard URL field could force-close the app on the Manage / sign-in screen: the value was handed to the networking layer as a host, which rejected it with an uncaught error on the main thread. The setup fields now reject anything that isn't a valid host or `http(s)://` URL with an inline error, and the dashboard and voice request paths treat a malformed address as "unreachable" instead of ever crashing. (#131, #132)

### Added
- **Try the demo.** A new "Try the demo" option on the setup / Connect screen — and on the empty chat screen if you skip setup — opens an offline preview of the real Chat UI: a sample conversation with Markdown, a tool-progress card, and a rich card, with zero setup and zero network (it works in airplane mode). A "Demo mode — sample data, not connected" banner offers a one-tap Connect into the real setup wizard. Lets a first-run user — or anyone curious — see what the app does before connecting a server.

---

## Upgrade notes
- This is an app-side release on **both** flavors — no Device Control or server changes needed.
- `appVersionCode` is **19**.
