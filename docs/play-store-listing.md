# Play Store Listing — Hermes-Relay

> Reference copy for the Google Play Console submission.

## Short Description (≤80 chars)

Self-hosted Hermes chat, voice, terminal, and notification companion

## Full Description

**Hermes-Relay** is a native Android client for the Hermes agent platform. Connect to your self-hosted Hermes AI agent and chat, speak, pair, and manage relay sessions directly from your phone.

Built for developers and AI enthusiasts who run their own Hermes agent instance. This is not a hosted AI service — it is a companion app for your own infrastructure.

━━━ HOW IT WORKS ━━━

Hermes-Relay connects directly to your Hermes API Server over HTTP/SSE for real-time streaming chat. If you also run the Hermes relay service, the app can pair by QR code and use Bridge Core features such as terminal access, voice routes, notification companion, media handoff, and relay-session management.

━━━ GOOGLE PLAY BUILD ━━━

The Google Play build ships **Hermes Bridge Core** only. It does not include AccessibilityService-based Device Control and cannot read your screen, tap, type, swipe, capture screenshots, send SMS, place calls, access contacts or location, or perform unattended phone control.

Device Control is reserved for sideload builds distributed outside Google Play.

━━━ FEATURES ━━━

◆ Direct API Streaming
Chat with your Hermes agent over SSE. Responses stream token-by-token with animated typing indicators.

◆ Session Management
Create, switch, rename, and delete chat sessions. Message history is loaded from your Hermes server on demand.

◆ Profiles and Personalities
Switch between configured Hermes profiles and personalities. The active profile context stays visible in chat and voice surfaces.

◆ Voice Mode
Use optional microphone capture for Hermes Chat + Voice Output, where Hermes owns the chat/tool turn and the relay renders assistant speech. An experimental Realtime Agent engine can use provider-native realtime speech while Hermes remains the tool, profile, confirmation, and memory authority.

◆ Bridge Core Pairing
Scan a relay QR code to configure API and relay endpoints, then manage relay sessions and per-feature grants from the app.

◆ Terminal and TUI Relay
Use paired relay sessions for terminal/TUI access to your Hermes host when your relay grants allow it.

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
• A running Hermes agent instance
• Optional Hermes relay service for Bridge Core features
• Network access to your server (local network, VPN, or internet)

━━━ OPEN SOURCE ━━━

Hermes-Relay is MIT licensed. Source code, documentation, and issue tracking are available on GitHub.

This app is a community project and is not affiliated with or endorsed by NousResearch.

## Release Notes

v0.8.0 improves the Google Play-safe Bridge Core build, voice settings, and realtime voice path. Play remains free of AccessibilityService Device Control while keeping chat, profiles, voice, terminal/TUI relay, media, notification companion, relay sessions, QR pairing, diagnostics, and connection health. Voice Settings now clearly separates Hermes Chat + Voice Output from the experimental Realtime Agent, keeps fallback TTS visible as a global safety net, and Realtime Agent supports provider-native Hermes-brokered speech for xAI and OpenAI.

## Category

Tools

## Content Rating

Target audience: 18+ (developer tool)
Not designed for children.

## Tags

ai, agent, hermes, developer tools, chat, voice, terminal, self-hosted, open source
