# Play Store Listing — Hermes-Relay

> Reference copy for the Google Play Console submission.

## Short Description (≤80 chars)

Your Hermes AI agent, in your pocket — chat, voice, and control.

## Full Description (plain text, no markdown — paste as-is; ≤4000 chars)

Hermes-Relay is the native Android client for the Hermes agent platform. Point it at your own Hermes instance and chat with your agent, talk to it hands-free, and manage it — models, keys, skills, profiles — from anywhere.

It's not a hosted AI service. It's a companion app for the Hermes agent you run, and it talks only to the instances you configure.

QUICK START

1. Run hermes-agent with its API server enabled on your computer or home server.
2. Install Hermes-Relay and enter your server's address (for example [http://192.168.1.100:8642](http://192.168.1.100:8642)).
3. The setup wizard checks what your server supports and shows a readiness card — then you're talking.

A plain Hermes install is enough. Chat, management, and voice all work with no plugin or extra services.

HOW IT WORKS

Chat streams directly from your Hermes API Server in real time. Manage and voice use your Hermes dashboard with one sign-in. Run the optional relay service and the app can pair by QR code to add power tools: remote terminal, notification companion, media handoff, relay-session management, and more voice engines.

GOOGLE PLAY BUILD

The Google Play build ships Hermes Bridge Core only. It has no AccessibilityService Device Control: it cannot read your screen, tap, type, swipe, screenshot, send SMS, place calls, or access contacts or location. Device Control is reserved for sideload builds distributed outside Google Play.

FEATURES

◆ Streaming Chat — real-time and token-by-token, with live reasoning, markdown, tool-call visibility, image/PDF/file attachments, mid-turn steering, edit-and-resend, and a searchable command palette.

◆ Manage Your Agent — the Hermes dashboard on your phone: switch models from your provider catalog, manage provider keys (masked), edit profiles, and browse, install, and update skills.

◆ Voice Mode — talk hands-free using your server's speech providers, no plugin needed. Relay-paired setups add per-profile voices and an experimental realtime engine.

◆ Works Away From Home — add a Tailscale or public URL and the app switches routes automatically; when a server is unreachable it tells you what to fix instead of just going red.

◆ Sessions — create, switch, rename, and delete chats; message history loads on demand.

◆ Multiple Servers &amp; Profiles — connect to more than one server (home and work) and switch in a tap; overlay an agent profile or personality per conversation.

◆ Relay Power Tools (optional) — pair by QR code for a remote terminal, relay-session management, and per-feature grants.

◆ Notification Companion (optional) — forward notification metadata to your paired relay so your assistant can summarize it. Toggle it anytime in system settings.

◆ Stats for Nerds — local-only counters for response timing, token usage, cost, and stream health.

◆ Material You — Material 3 dynamic color, light/dark/system themes, and haptics.

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
v1.2.0 — Make it yours.

• Eight app themes, swappable sphere skins, and animated agent "pets" that react to what your agent is doing.
• See which streaming path you're on, plus a "What the agent sees" sheet showing the agent's exact context.
• ~3× faster cold start and honest loading states.
• In-app crash reporting with one-tap bug reports.
• Fixes: QR pairing on foldables, server-image & PDF crashes, in-chat model picks now apply.
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

### Foreground service permissions

The Play build declares `**FOREGROUND_SERVICE_SPECIAL_USE**` for `GatewayKeepAliveService`, backing the opt-in **Keep connected in background** feature (off by default). At submission, complete **App content → Foreground service permissions** for `specialUse`:

- **Use case:** maintains a persistent connection to the user's own Hermes agent server so the assistant stays responsive and delivers replies while the app is backgrounded.
- **Why a foreground service:** it's a real-time, user-initiated streaming connection that must survive Doze / background execution limits; `dataSync` is force-stopped after a 6-hour/day cap on Android 15, so `specialUse` is the only fit for "stay connected."
- **User control:** off by default; enabled only via *Settings → Chat → Keep connected in background*; shows an ongoing notification with a **Disconnect** action; ends when the app is swiped from recents.
- Google usually asks for a short screen recording of the toggle + notification.

The Play build does **not** declare `FOREGROUND_SERVICE_MEDIA_PROJECTION` or the Device Control accessibility/bridge services — those are sideload-only.

### Data safety

No data collection or sharing to declare: no telemetry, ads, or third-party analytics SDKs; all traffic goes only to user-configured servers; credentials are stored in encrypted on-device storage. Mirror the **Security &amp; Privacy** section above when filling the Data safety form.

### Sensitive / runtime permissions in the Play build

- `RECORD_AUDIO` — Voice mode, requested at use.
- `POST_NOTIFICATIONS` — turn-complete + keep-alive notifications, requested on API 33+.
- `CAMERA` — QR pairing / attachments, requested at use.
- Notification listener (companion) — user-enabled in system settings.

