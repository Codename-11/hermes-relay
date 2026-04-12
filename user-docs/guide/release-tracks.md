# Release tracks: Google Play vs Sideload

Hermes-Relay ships in **two flavors** built from the same codebase. Most users want the Google Play one. Power users who want the agent to drive their phone hands-free want the sideload one. This page explains what's different, why, and how to choose.

## TL;DR

- **Google Play** — easy install, automatic updates, every chat and voice feature, plus the agent can *read* your phone (notifications, calendar, what's on screen). Cannot tap, type, or swipe on your behalf.
- **Sideload** — manual install from GitHub Releases, all of the above plus the agent can *control* your phone — voice-routed bridge intents ("text Sam I'll be late"), vision-driven navigation, the works.
- They coexist on a device — you can install both side-by-side and try them both.

## Why two tracks?

Hermes-Relay's bridge channel uses Android's **Accessibility Service** — a powerful API designed for screen readers and assistive tech that, by extension, lets one app see and control another. It's exactly the right tool for letting an AI assistant act on your behalf, but Google Play scrutinizes accessibility-service apps very carefully because the same API is also a popular vector for malware.

To stay inside Google Play's policy without giving up the cool features, the Hermes-Relay APK on Google Play is built from a smaller subset of the source. The accessibility service in that build can read what's on screen and read your notifications, but the part that synthesizes taps and swipes is *not compiled into the APK at all*. It's not "disabled with a flag" — the code is physically absent from the binary Google reviews and you install. That keeps the Play version honest about what it can do.

If you want the full feature set — including the parts where the agent actually performs actions on the phone for you — that's the **sideload** build, distributed straight from GitHub Releases as a signed APK.

## What's in each track

<FeatureMatrix />

## How to choose

- **Want the easiest install and automatic updates?** → Google Play.
- **Want the agent to be a "second pair of hands" — read notifications and summarize them, check your calendar, answer questions about what's on screen?** → Either track. Both can do this.
- **Want hands-free voice control of your phone — "text Sam I'll be 10 minutes late" without touching your screen?** → Sideload.
- **Want the agent to look at your screen and figure out how to drive an unfamiliar app on your behalf?** → Sideload.
- **Don't know yet?** → Start with Google Play. You can install sideload later — they live side-by-side as separate apps.

## Can I switch later?

Yes — and you don't even have to choose just one. The two builds use different application IDs (`com.hermesandroid.relay` for Google Play and `com.hermesandroid.relay.sideload` for sideload), which means Android treats them as two completely separate apps. Each gets its own launcher icon, its own settings, and its own paired-device entry on the relay.

If you want to migrate fully from one to the other, just uninstall the one you no longer use. Sessions, attachments, and pairings are stored per-app, so wiping the old one only affects that one.

## How to install each

### Google Play

One tap from the Play Store listing. Updates arrive automatically.

[Open the Play Store listing →](https://play.google.com/store/apps/details?id=com.hermesandroid.relay)

### Sideload

Download `app-release.apk` from the latest GitHub Release, allow installs from your browser, and tap the file. Full step-by-step instructions (including how to verify the APK signature) live in [Installation & Setup → Sideload APK](/guide/getting-started#sideload-apk).

[Latest GitHub Release →](https://github.com/Codename-11/hermes-relay/releases/latest)

## Updates

- **Google Play** updates automatically through the Play Store — you'll get new versions in the background like any other Play app.
- **Sideload** updates require re-downloading the APK from GitHub Releases. You can subscribe to new releases on GitHub by clicking *Watch → Custom → Releases* on the [repo](https://github.com/Codename-11/hermes-relay) so you get an email when a new version ships.

In both cases, the server-side plugin is updated independently with `git pull && bash install.sh` on whatever machine runs your Hermes agent — see the [installation guide](/guide/getting-started#install-the-server-plugin) for that flow.

## Safety rails — always on, in both tracks

Whichever track you pick, Hermes-Relay's bridge channel runs the same set of guardrails:

- **Per-app blocklist** — banking apps, password managers, and your work email are blocked from any bridge action by default. You can add or remove entries from Settings.
- **Confirmation on destructive verbs** — words like *send*, *pay*, *delete*, *transfer*, *post*, *publish*, and *buy* always trigger a confirmation prompt before the agent acts.
- **Auto-disable** — bridge mode turns itself off after a configurable idle period and re-enabling it requires biometric.
- **Activity log** — every command the agent runs is logged with a timestamp, the result, and a screenshot thumbnail.
- **Persistent notification** — when bridge is on, you always have a system-tray notification with a one-tap kill switch.

These exist regardless of which track you chose — they're the floor, not a sideload feature.
