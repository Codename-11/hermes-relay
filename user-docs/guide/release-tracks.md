# Release tracks: Google Play vs Sideload

Hermes-Relay ships in **two flavors** built from the same codebase. Most users want the Google Play one. Power users who want the agent to drive their phone hands-free want the sideload one. This page explains what's different, why, and how to choose.

## TL;DR

- **Google Play** — easy install, automatic updates, every chat/profile/voice feature, terminal/TUI relay, media handoff, notification companion, relay sessions, and diagnostics. The relay-backed ones (terminal, media, notifications) need the [Relay plugin](#how-the-pieces-combine) paired to your Hermes host. It has **no AccessibilityService** and cannot read the screen, tap, type, swipe, capture screenshots, send SMS, make calls, access contacts/location, or perform unattended phone control.
- **Sideload** — manual install from GitHub Releases, all of the above plus AccessibilityService-backed Device Control: screen reading, taps, typing, gestures, screenshots, voice-routed bridge intents ("text Sam I'll be late", "open Chrome"), direct SMS/call dispatch, file sharing/MMS attachment handoff, vision-driven navigation, and the full `android_*` bridge toolset. The toggle is labeled "Agent Control."
- They coexist on a device — you can install both side-by-side and try them both.

## How the pieces combine

It's easy to read this page as "Google Play vs Sideload" and miss that two
*independent* things decide what your agent can do:

- **The Relay plugin** (server-side) — an optional plugin on the machine running
  Hermes. Pairing it unlocks terminal/TUI, relay & enhanced voice, notification
  forwarding, media handoff, desktop tools, **and** the device-control channel.
- **The build flavor** (app-side) — Google Play or Sideload. This only decides
  whether AccessibilityService **Device Control** is compiled into the APK.

Chat, Manage, and Vanilla Hermes voice need neither — they run on unmodified upstream
Hermes. Everything else is additive:

<CombineModel />

So **Device Control needs both**: the Relay plugin paired *and* the sideload
build. A sideload app with no paired relay still can't drive your phone, and a
paired relay on the Google Play build still has no phone-control surface. The
flavor table below assumes a paired relay — it isolates the one axis this page
is about (which build), not whether the plugin is installed.

## Why two tracks?

Hermes-Relay's sideload Device Control channel uses Android's **Accessibility Service** — a powerful API designed for screen readers and assistive tech that, by extension, lets one app see and control another. It's exactly the right tool for letting an AI assistant act on your behalf, but Google Play scrutinizes accessibility-service apps very carefully because the same API is also a popular vector for malware.

To stay inside Google Play's policy without giving up the rest of the app, the Hermes-Relay APK on Google Play is built from a smaller subset of the source. The accessibility service, screen-reading routes, gesture dispatch, screenshots, SMS/call/contact/location helpers, overlays, wake-lock Device Control, and unattended-control code are *not compiled into the APK at all*. It's not "disabled with a flag" — the code is physically absent from the binary Google reviews and you install. Notification companion stays available through Android's separate Notification Access permission.

If you want the full feature set — including screen reading or any part where the agent performs actions on the phone for you — that's the **sideload** build, distributed straight from GitHub Releases as a signed APK.

## What's in each track

<FeatureMatrix />

## How to choose

- **Want the easiest install and automatic updates?** → Google Play.
- **Want notification summaries, chat, profiles, terminal/TUI, media, and voice without sideloading?** → Google Play.
- **Want the agent to answer questions about what's on screen?** → Sideload.
- **Want hands-free voice control of your phone — "text Sam I'll be 10 minutes late" without touching your screen?** → Sideload.
- **Want the agent to look at your screen and figure out how to drive an unfamiliar app on your behalf?** → Sideload.
- **Don't know yet?** → Start with Google Play. You can install sideload later — they live side-by-side as separate apps.

## Can I switch later?

Yes — and you don't even have to choose just one. The two builds use different application IDs (`com.axiomlabs.hermesrelay` for Google Play and `com.axiomlabs.hermesrelay.sideload` for sideload), which means Android treats them as two completely separate apps. Each gets its own launcher icon, its own settings, and its own paired-device entry on the relay.

If you want to migrate fully from one to the other, just uninstall the one you no longer use. Sessions, attachments, and pairings are stored per-app, so wiping the old one only affects that one.

## How to install each

### Google Play

One tap from the Play Store listing. Updates arrive automatically.

[Open the Play Store listing →](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay)

### Sideload

Download the file ending in `-sideload-release.apk` from the latest GitHub Release (for example, `hermes-relay-1.0.0-sideload-release.apk`), allow installs from your browser, and tap the file. Full step-by-step instructions (including how to verify the APK signature) live in [Installation & Setup → Sideload APK](/guide/getting-started#sideload-apk).

[GitHub Android Releases →](https://github.com/Codename-11/hermes-relay/releases)

## Updates

- **Google Play** updates automatically through the Play Store — you'll get new versions in the background like any other Play app.
- **Sideload** checks GitHub for a newer release on app cold start (at most once every 6 hours) and shows a dismissable banner at the top of the app when you're behind. Tap **Update** → the sideload APK opens in your browser, Android's Downloads notification hands it to the system installer, done. No second app required, no new permissions. You can also trigger a manual check at **Settings → About → Updates**. If you prefer to subscribe to release notifications directly, click *Watch → Custom → Releases* on the [repo](https://github.com/Codename-11/hermes-relay).

In both cases, the server-side plugin is updated independently with `git pull && bash install.sh` on whatever machine runs your Hermes agent — see the [installation guide](/guide/getting-started#install-the-server-plugin) for that flow.

## Safety rails

The safety rail system is designed for the sideload track where the agent can control the phone. On the Google Play track, action routes are blocked at the code level (not "disabled by a flag" — the routes return 403 and the safety settings UI is hidden entirely), so the safety rails don't fire because there's nothing to gate.

On the **sideload** track, the full safety rail stack runs:

- **Per-app blocklist** — banking apps, password managers, and your work email are blocked from any bridge action by default. You can add or remove entries from Settings.
- **Confirmation on destructive verbs** — words like *send*, *pay*, *delete*, *transfer*, *post*, *publish*, and *buy* trigger a system-overlay confirmation prompt before the agent acts. This fires on `/tap_text`, `/type`, AND on `/tap` + `/long_press` when the tapped button's text contains a destructive verb (so tapping a "Send" button by node ID is also gated).
- **Auto-disable** — bridge mode turns itself off after a configurable idle period.
- **Activity log** — every command the agent runs is logged with a timestamp and the result.
- **Persistent notification** — when bridge is on, you always have a system-tray notification with a one-tap kill switch.
- **Denial is final** — if you tap Deny on the confirmation prompt, the agent receives a structured `error_code: user_denied` response with an explicit "do not retry via UI automation" instruction. The agent cannot work around a denial by driving the Messages app UI instead.

On the **Google Play** track, none of the above fires because Device Control is absent. The Bridge tab shows Bridge Core status and links for Connections, Terminal, Voice, Notification Companion, Media, and Relay Sessions; it does not show Accessibility, screen reading, gesture, screenshot, or safety-rail controls.
