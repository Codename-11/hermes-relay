# Hermes-Relay-Android v1.2.2

**Release Date:** June 22, 2026
**Since v1.2.1:** A multi-profile reliability pass. Switching agent profiles now keeps your chats straight — **deleting a session on a non-default profile sticks**, and a **cold start opens the session list on the right profile** instead of flashing the default one — plus a **full-screen Diagnostics** view that leads with subsystem health checks, simpler connection wording, and a roomier distraction-free chat mode.

v1.2.2 is a focused follow-up to 1.2.1, sharpened by real multi-profile use. The two profile fixes remove the most confusing rough edges when you run more than one agent profile, Diagnostics becomes a place you can actually read at a glance, and a couple of small touches make the default path clearer.

---

## Download

v1.2.2 ships in two Android build flavors. APK and AAB filenames are version-tagged:

| Flavor | File | Who it's for |
|---|---|---|
| Google Play | `hermes-relay-1.2.2-googlePlay-release.aab` | Upload this Android App Bundle to Play Console. It has no AccessibilityService, screen reading, screenshots, gestures, SMS/calls, contacts/location, overlays, or unattended phone control. |
| sideload | `hermes-relay-1.2.2-sideload-release.apk` | Direct-install APK for full Device Control. Installs as `com.axiomlabs.hermesrelay.sideload`. |
| googlePlay APK | `hermes-relay-1.2.2-googlePlay-release.apk` | Parity/testing artifact. |
| sideload AAB | `hermes-relay-1.2.2-sideload-release.aab` | Parity/testing artifact. |

Verify integrity with `SHA256SUMS.txt` from the same release. See the [Sideload guide](https://codename-11.github.io/hermes-relay/guide/getting-started.html#sideload-apk) for APK install steps.

---

## Highlights

### Profiles that behave
- **Deleting a session on a non-default profile now sticks.** A non-default profile keeps its sessions in its own store; the delete now scopes to the active profile, so a removed chat no longer reappears after the list refreshes.
- **Cold start opens on the right profile.** Launching with a non-default profile selected used to briefly show the default profile's chats before snapping to the correct ones. The session list (and the restored session context) now wait for the profile to resolve and load the right list directly.

### Clearer diagnostics
- **Diagnostics status timeline.** Diagnostics is now a full screen that leads with a top-to-bottom list of subsystem health checks — network, API server, chat transport, pairing, relay, and voice — each with a pass / warning / fail state and the reason when something's wrong. Tap a failing check for full detail; the recent-activity log stays below.

### Small touches
- **Simpler connection wording.** The default connection is now just **"Hermes"** (previously "Vanilla" / "Standard Hermes"), and the optional power features are labelled **"Relay"** / "Relay plugin", across setup, the switcher, voice, and permissions.
- **Roomier clean chat.** Distraction-free chat mode gives its text a taller, scrollable area instead of capping it near a third of the screen.

---

## Upgrade notes
- All changes are client-side and available on **both** flavors (no Device Control needed).
- `appVersionCode` is **16**.
