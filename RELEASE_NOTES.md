# Hermes-Relay v0.1.0

First release — a native Android client for the Hermes agent platform with direct API chat, session management, and a full Material 3 Compose UI.

## Download

- **Most people**: grab **`app-release.apk`** below and sideload it. See the [sideload guide](https://codename-11.github.io/hermes-relay/guide/getting-started.html#sideload-apk) for step-by-step instructions.
- **Google Play users**: the app is currently on Internal testing — production rollout coming soon.
- **`app-release.aab`** is the Google Play format — *not* installable directly. It's here for auditors who want to cross-reference what's on Play Store.
- **Verify integrity** with `SHA256SUMS.txt` before installing.

## Highlights

- **Direct API chat** — connects to your Hermes API Server via SSE streaming
- **Session management** — create, switch, rename, delete sessions with full message history
- **Markdown rendering** — code blocks, bold, italic, links, lists in assistant messages
- **Reasoning display** — collapsible thinking blocks when the agent uses extended thinking
- **Token tracking** — per-message input/output token count and estimated cost
- **Personality picker** — dynamic personalities from server config, agent name on chat bubbles
- **Command palette** — searchable command browser with 29 gateway commands + server skills
- **QR code pairing** — scan `hermes-pair` QR to auto-configure connection
- **Material You theming** — dynamic colors with light/dark/auto support
- **File attachments** — attach images, documents, and other files to messages
- **Message queuing** — send follow-up messages while the agent is still responding
- **Offline detection** — graceful degradation when network connectivity is lost
- **Feature gating** — Developer Options (tap version 7x) for experimental features
- **Configurable limits** — adjustable attachment size and message length in Settings
- **In-app analytics** — Stats for Nerds with response times, token usage, peak times, reset

## Requirements

- Android 8.0+ (API 26)
- A running [Hermes agent](https://github.com/NousResearch/hermes-agent) instance

## What's Next

- Terminal channel via tmux (Phase 2)
- Bridge channel migration (Phase 3)
- Push notifications
- Agent-initiated image rendering (MEDIA: tags)

## Found a bug? Let us know!

This is an indie project and every report helps. If something feels off, broken, or just weird, [open an issue](https://github.com/Codename-11/hermes-relay/issues/new) — we read every one.
