# Play Store Listing — Hermes-Relay v0.1.0

> Temporary reference for Google Play Console submission. Delete after listing is live.

## Short Description (≤80 chars)

Native Android client for your self-hosted Hermes AI agent

## Full Description

**Hermes-Relay** is a native Android client for the Hermes agent platform. Connect to your self-hosted Hermes AI agent and chat in real time — directly from your phone.

Built for developers and AI enthusiasts who run their own Hermes agent instance. This is not a hosted AI service — it's a companion app for your own infrastructure.

━━━ HOW IT WORKS ━━━

Hermes-Relay connects directly to your Hermes API Server over HTTP/SSE for real-time streaming chat. Point it at your server, optionally scan a QR code to auto-configure, and start talking to your agent.

━━━ FEATURES ━━━

◆ Direct API Streaming
Chat with your Hermes agent over SSE. Responses stream in token-by-token with animated typing indicators. Send follow-up messages while the agent is still responding.

◆ Session Management
Create, switch, rename, and delete chat sessions. Full message history is preserved per session with auto-generated titles.

◆ Rich Rendering
Full markdown support — syntax-highlighted code blocks, bold, italic, links, and lists. Collapsible reasoning blocks when your agent uses extended thinking.

◆ Personality Picker
Switch between agent personalities configured on your server. The active personality name appears on chat bubbles so you always know who you're talking to.

◆ Command Palette
Searchable command browser with 29 gateway commands, dynamic personality commands, and server skill discovery. Type "/" in chat for inline autocomplete.

◆ Tool Progress Display
See what your agent is doing in real time. Configurable tool display (Off / Compact / Detailed) with type-specific icons, duration tracking, and auto-expand/collapse.

◆ File Attachments
Attach images, documents, and other files to your messages. Configurable size limits in Settings.

◆ QR Code Pairing
Run hermes-pair on your server to generate a QR code. Scan it from the app to auto-configure your connection — no manual URL entry needed.

◆ Stats for Nerds
Built-in analytics: time-to-first-token, completion times, token usage per message with cost estimates, peak activity times, and stream health. Bar charts rendered on Canvas.

◆ Material You Design
Full Material 3 with dynamic color theming. Light, dark, and system-auto modes. Animated splash screen and haptic feedback throughout.

◆ ASCII Morphing Sphere
An animated 3D character sphere greets you on the empty chat screen. Toggle ambient fullscreen mode or display it behind messages as a subtle background effect.

━━━ SECURITY ━━━

• API keys stored in AES-256-GCM encrypted storage
• HTTPS enforced for remote connections
• Cleartext permitted only for localhost/LAN development servers
• No telemetry, no analytics sent externally
• All data stays between your phone and your server

━━━ REQUIREMENTS ━━━

• Android 8.0 or later (API 26+)
• A running Hermes agent instance (https://github.com/NousResearch/hermes-agent)
• Network access to your server (local network or internet)

━━━ OPEN SOURCE ━━━

Hermes-Relay is MIT licensed. Source code, documentation, and issue tracking available on GitHub.

This app is a community project and is not affiliated with or endorsed by NousResearch.

## Release Notes (v0.1.0)

First release — native Android client for the Hermes agent platform.

• Direct API chat with SSE streaming
• Session management with full message history
• Markdown rendering with syntax-highlighted code
• Personality picker and command palette
• QR code pairing for quick setup
• File attachments and message queuing
• Material You theming (light/dark/auto)
• In-app analytics (Stats for Nerds)

## Category

Tools

## Content Rating

Target audience: 18+ (developer tool)
Not designed for children.

## Tags

ai, agent, hermes, developer tools, chat, self-hosted, open source
