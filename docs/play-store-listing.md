# Play Store Listing — Hermes-Relay

> Reference copy for the Google Play Console submission.

## Short Description (≤80 chars)

Your Hermes AI agent, in your pocket — chat, voice, and control.

## Full Description (plain text, no markdown — paste as-is; ≤4000 chars)

Hermes-Relay is the native Android client for the Hermes agent platform. Point it at your own Hermes instance and chat with your agent, talk to it hands-free, and manage it — models, keys, skills, profiles — from anywhere.

It's not a hosted AI service. It's a companion app for the Hermes agent you run, and it talks only to the instances you configure.

QUICK START

1. Run hermes-agent with its Dashboard/Gateway enabled on your computer or home server.
2. Install Hermes-Relay and select the nearby Dashboard, or enter its address and custom port.
3. Sign in through the Dashboard when prompted. The setup wizard verifies Chat, sessions, Manage, and voice before finishing.

No server yet? Tap "Try the demo" on the setup screen to explore the app offline — a sample conversation, no login or server required.

A plain Hermes install is enough. Chat, management, and voice all work with no plugin or extra services.

HOW IT WORKS

Chat, sessions, Manage, and voice use the Hermes Dashboard/Gateway with one sign-in. A headless API server remains an automatic compatibility fallback. Run the optional relay service and the app can pair by QR code to add power tools: remote terminal, notification companion, media handoff, relay-session management, and more voice engines.

GOOGLE PLAY BUILD

The Google Play build ships Hermes Bridge Core only. It has no AccessibilityService Device Control: it cannot read your screen, tap, type, swipe, screenshot, send SMS, place calls, or access contacts or location. Device Control is reserved for sideload builds distributed outside Google Play.

FEATURES

◆ Streaming Chat — real-time and token-by-token, with live reasoning, markdown, tool-call visibility, image/PDF/file attachments, mid-turn steering, edit-and-resend, and a searchable command palette.

◆ Manage Your Agent — the Hermes dashboard on your phone: switch models from your provider catalog, manage provider keys (masked), edit profiles, and browse, install, and update skills.

◆ Voice Mode — talk hands-free using your server's speech providers, no plugin needed. Optionally choose Hermes as Android's Digital Assistant or enable local "Hey Hermes" detection; Relay-paired setups add per-profile voices and an experimental realtime engine.

◆ Floating Pets — browse and install Petdex companions or import your own. Keep the pet separate from your agent identity, drag it anywhere, or let it roam across UI-aware ledges.

◆ Native Plugin Pages — installed Hermes plugins can add safe, host-rendered Android pages without loading executable plugin code on your phone. Write actions require an explicit per-plugin grant.

◆ Works Away From Home — add a Tailscale or public URL and the app switches routes automatically; when a server is unreachable it tells you what to fix instead of just going red.

◆ Sessions — create, switch, rename, and delete chats; message history loads on demand.

◆ Multiple Servers &amp; Profiles — connect to more than one server (home and work) and switch in a tap; overlay an agent profile or personality per conversation.

◆ Relay Power Tools (optional) — pair by QR code for a remote terminal, relay-session management, and per-feature grants.

◆ Notification Companion (optional) — forward notification metadata to your paired relay so your assistant can summarize it. Toggle it anytime in system settings.

◆ Stats for Nerds — local-only counters for response timing, token usage, cost, and stream health.

◆ Material You — Material 3 dynamic color, light/dark/system themes, haptics, and complete Android catalogs for English, German, Spanish, Brazilian Portuguese, Japanese, Russian, and Simplified Chinese.

SECURITY &amp; PRIVACY

• API keys and relay tokens are stored in encrypted Android storage

• HTTPS is enforced for remote connections; cleartext only for localhost/LAN

• No telemetry, ads, tracking, or third-party analytics SDKs

• Notification access and the microphone are optional and user-controlled

• All app traffic goes only to servers you configure

REQUIREMENTS

• Android 8.0 or later (API 26+)

• A running Hermes agent (chat, management, and voice need nothing else)

• Optional Hermes relay service for power tools (terminal, notifications, media)

• Network access to your server (local network, VPN, or internet)

OPEN SOURCE

Hermes-Relay is MIT licensed. Source, docs, and issue tracking are on GitHub.

This app is a community project and is not affiliated with or endorsed by NousResearch.

## Release Notes

Paste into Play Console → **What's new** (≤500 characters):

```
v1.6.1 - Clearer recovery, steadier chat

* Clear optional Relay status and recovery guidance.
* Full session history through compatible paging.
* Stable streamed text selection and Voice microphone handoff.
* New-chat coaching stays clear of Voice controls.
```

## Category

Tools

## Content Rating

Target audience: 18+ (developer tool)

Not designed for children.

## Tags

ai, agent, hermes, developer tools, chat, voice, self-hosted, remote, open source

## Graphics and screenshots

Store graphics are versioned in the repo and exported to the Gradle Play
Publisher metadata tree with:

```bash
python scripts/screenshots.py export --target play
python scripts/screenshots.py validate
```

The capture/export plan is `docs/media/screenshots.json`. The exported Play
assets live under `app/src/googlePlay/play/listings/en-US/graphics/`.
Android tag releases intentionally run the bundle-only
`publishGooglePlayReleaseBundle` task so static listing graphics are not
republished on every release. When listing copy or screenshots change, run the
path-filtered Play Store Listing workflow or publish locally with:

```bash
./gradlew publishGooglePlayReleaseListing
```

## Play Console Declarations

Submission-time declarations the Play Console requires — keep in sync with the merged `googlePlay` manifest.

### Privacy policy

Use `https://hermes-relay.dev/privacy.html` as the Play Console privacy-policy
URL. The Android preflight and release workflows require both that canonical
page and the historical GitHub Pages compatibility URL to return the complete
policy before they can publish. The standard Android Publisher listing API does
not expose this Play policy-declaration field, so the legacy URL remains a
permanent compatibility page for existing Console metadata.

### App access

Hermes-Relay is a client for a **user-run Hermes server**. A fresh install with no server configured has no content of its own — which is what a reviewer hits first, and what triggered the v1.2.4 *App access* rejection. The core experience is reviewable **offline via Demo mode**, with **no test server, account, or credentials required**.

In **App content → App access**, choose **"All or some functionality is restricted"** — full chat, Manage, and voice require the user to connect their own Hermes server, and choosing "restricted" is what exposes the instructions field that tells the reviewer how to get in. Add **one** access entry with **no username/password**, just these instructions (Play Console caps this field at **500 characters** — the text below is 423):

```
This app is a client for a Hermes server the user runs themselves, so a fresh install has no content until one is connected. To review it with no server or account: launch the app, then tap "Try the demo" on the first/Connect screen (it's also on the empty Chat screen if you tap Skip). That opens an offline demo of the real chat UI - a sample conversation, no login, account, or network needed. It works in airplane mode.
```

**Reviewer note** — paste into the resubmission / appeal message to pre-empt the same rejection:

> Hermes-Relay is a client for a self-hosted Hermes agent server (like an SSH or self-hosted-app client), so it has no content until the user connects their own. We added an offline **"Try the demo"** mode — tap it on the first screen — so the full chat experience is reviewable with no server, account, or network.

### Foreground service permissions

Before promoting any Play release, compare this section with the merged
`googlePlayRelease` manifest and complete **App content → Foreground service
permissions** for every declared type. Google blocks the Android Publisher API
edit at commit time when a declaration is missing, even if the Production draft
upload itself succeeded.

The Play build declares `**FOREGROUND_SERVICE_SPECIAL_USE**` for
`GatewayKeepAliveService`. It protects user-started active chat turns
automatically and also backs the opt-in **Persistent connection** feature for
idle connectivity. Declare `specialUse` with:

- **Use case:** keeps a user-started Hermes turn connected until it finishes or pauses for user input, including multiple concurrent sessions; optionally maintains the idle connection when the user enables Persistent connection.
- **Why a foreground service:** it's a real-time, user-initiated streaming connection that must survive Doze / background execution limits; `dataSync` is force-stopped after a 6-hour/day cap on Android 15, so `specialUse` is the only fit for "stay connected."
- **User control:** the service starts when the user sends a chat message and stops after all active turns settle. Continuous idle retention is off by default and enabled only via *Settings → Quick Controls → Persistent connection*. The ongoing notification shows the active/waiting session count; its **Turn off always-on** action disables idle retention without interrupting active work. Swiping the app from recents ends it.
- Google usually asks for a short screen recording of a backgrounded active turn, the ongoing notification, and the optional persistent toggle.

The Play build also declares `**FOREGROUND_SERVICE_MICROPHONE**` for two
explicitly user-started voice features. Declare `microphone` and cover both
entry points in its description and demonstration:

- **Local wake word:** when the user explicitly enables *Hey Hermes* in Voice
  settings, `WakeWordForegroundService` listens on-device for the configured
  wake phrase. Audio before activation remains on the device, the ongoing
  microphone notification has a Stop action, and the service is never started
  at boot.
- **Voice overlay:** when the user opens the app-owned voice overlay while
  Hermes is visible, `VoiceOverlayForegroundService` preserves microphone
  access for that active voice session while the overlay is shown over another
  app. Hiding or exiting the overlay, stopping voice, or removing the task ends
  the service.
- **Why a foreground service:** Android requires an active microphone
  foreground service for user-visible capture that continues while the app is
  backgrounded. Both paths are opt-in, show Android's persistent microphone
  indicator and ongoing notification, and expose an immediate Stop action.
- Record a short video that enables the wake listener and shows its notification
  and Stop action, then opens the voice overlay, backgrounds Hermes, and ends
  the session from the overlay or notification.

The Play build does **not** declare `FOREGROUND_SERVICE_MEDIA_PROJECTION` or the Device Control accessibility/bridge services — those are sideload-only.

### Data safety

No data collection or sharing to declare: no telemetry, ads, or third-party analytics SDKs; all traffic goes only to user-configured servers; credentials are stored in encrypted on-device storage. Mirror the **Security &amp; Privacy** section above when filling the Data safety form.

### Sensitive / runtime permissions in the Play build

- `RECORD_AUDIO` — Voice mode and opt-in local wake detection, requested at use.
- `POST_NOTIFICATIONS` — chat input, turn-complete, and keep-alive notifications, requested on API 33+.
- `CAMERA` — QR pairing / attachments, requested at use.
- Notification listener (companion) — user-enabled in system settings.

