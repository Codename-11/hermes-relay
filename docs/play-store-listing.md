# Play Store Listing — Hermes-Relay

> Reference copy for the Google Play Console submission.

## Short Description (≤80 chars)

Your self-hosted Hermes AI agent, in your pocket — chat, voice, and control.

## Full Description

**Hermes-Relay** is the native Android client for the Hermes agent platform. Point it at your own self-hosted Hermes server and chat with your agent, talk to it hands-free, and manage it — models, keys, skills, profiles — from anywhere.

Built for developers and AI enthusiasts who run their own Hermes agent instance. This is not a hosted AI service — it is a companion app for your own infrastructure, and it talks only to servers you configure.

━━━ QUICK START ━━━

1. Run hermes-agent with its API server enabled on your computer or home server.
2. Install Hermes-Relay and enter your server's address (for example http://192.168.1.100:8642).
3. The setup wizard probes what your server supports and shows you a readiness card — then you're talking.

A plain Hermes install is enough: chat, management, and voice all work without any plugin or extra services.

━━━ HOW IT WORKS ━━━

Hermes-Relay connects directly to your Hermes API Server for real-time streaming chat. The Manage tab and voice use your Hermes dashboard with one sign-in. If you also run the optional Hermes relay service, the app can pair by QR code and add power tools: remote terminal, notification companion, media handoff, relay-session management, and additional voice engines.

━━━ GOOGLE PLAY BUILD ━━━

The Google Play build ships **Hermes Bridge Core** only. It does not include AccessibilityService-based Device Control and cannot read your screen, tap, type, swipe, capture screenshots, send SMS, place calls, access contacts or location, or perform unattended phone control.

Device Control is reserved for sideload builds distributed outside Google Play.

━━━ FEATURES ━━━

◆ Streaming Chat
Chat with your Hermes agent in real time. Responses stream token-by-token with markdown rendering, tool-call visibility, file attachments, and a searchable command palette.

◆ Manage Your Agent
The full Hermes dashboard, native on your phone: switch models from your provider catalog, manage provider keys (write-only and masked), create and edit agent profiles, and browse, install, and update skills.

◆ Voice Mode
Talk to your agent hands-free. Voice works on a plain Hermes install using the speech providers your server is configured with — no plugin needed. Relay-paired setups add per-profile voice providers and an experimental realtime conversation engine.

◆ Works Away From Home
Add your server's Tailscale or public URL and the app switches routes automatically — local network at home, your secure fallback everywhere else. Routes are editable any time, and when a server is unreachable the status tells you what to fix instead of just going red.

◆ Sessions
Create, switch, rename, and delete chat sessions. Message history loads from your Hermes server on demand.

◆ Multiple Servers, Profiles, and Personalities
Connect to more than one Hermes server (home and work, dev and prod) and switch in one tap. Overlay an agent profile or personality per conversation.

◆ Relay Power Tools (optional)
Scan a relay QR code to pair, then open a remote terminal to your Hermes host, manage relay sessions, and control per-feature grants from the app.

◆ Notification Companion
Optional Android notification access lets Hermes-Relay forward posted-notification metadata to your paired relay so your assistant can summarize recent notifications. You enable or revoke this in Android system settings at any time.

◆ Media Handoff
Fetch relay-registered media into chat and share supported media through Android-native flows.

◆ Stats for Nerds
Built-in local analytics show response timing, token usage, cost estimates, and stream health. These counters stay on your phone.

◆ Material You Design
Full Material 3 with dynamic color theming, light/dark/system modes, animated splash screen, and haptic feedback.

━━━ SECURITY & PRIVACY ━━━

• API keys and relay tokens are stored in encrypted Android storage
• HTTPS is enforced for remote connections
• Cleartext is permitted only for localhost/LAN development servers
• No telemetry, no ads, no tracking, no third-party analytics SDKs
• Notification companion is optional and user-controlled
• Microphone is optional and requested only when you use Voice mode
• All app traffic goes only to servers you configure

━━━ REQUIREMENTS ━━━

• Android 8.0 or later (API 26+)
• A running Hermes agent instance (chat, management, and voice need nothing else)
• Optional Hermes relay service for power tools (terminal, notifications, media)
• Network access to your server (local network, VPN, or internet)

━━━ OPEN SOURCE ━━━

Hermes-Relay is MIT licensed. Source code, documentation, and issue tracking are available on GitHub.

This app is a community project and is not affiliated with or endorsed by NousResearch.

## Release Notes

v0.8.1 makes the standard no-plugin setup first-class. Voice now works on a plain Hermes install — one dashboard sign-in unlocks it. The Manage tab reaches parity with the desktop dashboard: models, provider keys, profiles, and a skills hub with install and update. Connection routes are editable on the phone, and remote access is built into the whole journey — a setup field, a readiness card, a smarter "unreachable" diagnosis, and a one-tap Tailscale shortcut.

## Category

Tools

## Content Rating

Target audience: 18+ (developer tool)
Not designed for children.

## Tags

ai, agent, hermes, developer tools, chat, voice, self-hosted, remote, open source
