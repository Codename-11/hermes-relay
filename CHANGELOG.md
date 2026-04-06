# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.2.0] - 2026-04-05

### Added
- **Direct API chat** — chat connects directly to Hermes API Server via `/api/sessions` SSE
- **Session management** — create, switch, rename, delete chat sessions
- **Session drawer** — slide-out panel listing all sessions with title, timestamp, message count
- **Message history** — loads when switching sessions from the server
- **Auto-session titles** — first user message auto-titles the session (truncated to 50 chars)
- **Session persistence** — last session ID saved to DataStore, resumes on app restart
- **What's New auto-show** — dialog shown automatically when app version changes
- **HermesApiClient** — full session CRUD + SSE streaming via `/api/sessions/{id}/chat/stream`
- **API key storage** — securely stored in EncryptedSharedPreferences
- **Dual connection model** — API Server for chat, Relay Server for bridge/terminal
- **Test Connection** — verify API server reachability from onboarding and settings
- **Cancel streaming** — stop button to cancel in-flight chat responses
- **Network security config** — cleartext restricted to localhost only
- **Markdown rendering** — assistant messages render code blocks, bold, italic, links, lists
- **Message copy** — long-press any message to copy text to clipboard
- **Reasoning display** — collapsible thinking block above assistant responses (toggle in Settings)
- **Token tracking** — per-message input/output token count and estimated cost
- **Personality picker** — 8 built-in personalities (concise, creative, technical, pirate, etc.)
- **Error retry** — retry button in error banner re-sends last failed message
- **Offline detection** — banner shown when network connectivity is lost
- **Haptic feedback** — on send, stream complete, error, and message copy
- **Input character limit** — 4096 character limit with counter
- **Responsive layout** — bubble widths adapt to phone, tablet, and landscape
- **Enriched tool cards** — tool-type icons, completion duration tracking

### Changed
- Chat no longer routes through relay server — direct to API server
- Onboarding collects API Server URL + API Key (required) + Relay URL (optional)
- Settings split into separate API Server and Relay Server cards
- ChatHandler refactored from envelope-based to typed SSE entry points
- Backup format bumped to v2 with separate apiServerUrl/relayUrl fields
- App version bumped to 0.2.0

### Fixed
- SSE callbacks now dispatched to main thread for safe StateFlow updates
- Overlapping streams prevented — previous stream cancelled before new send
- Tool call completion now matches by tool call ID (not first incomplete)
- Onboarding test connection properly cleans up client on failure
- Health check loop only runs when API client is configured

## [0.1.0] - 2026-04-05

### Added
- **Android app** — Jetpack Compose scaffold with chat, terminal (stub), bridge (stub), settings
- **WSS connection manager** — OkHttp WebSocket with auto-reconnect and exponential backoff
- **Channel multiplexer** — typed envelope protocol for chat/terminal/bridge/system
- **Auth flow** — 6-character pairing code with session token persistence
- **Chat UI** — message bubbles, streaming text, tool progress cards, profile selector
- **Relay server** — Python aiohttp WSS server proxying to Hermes WebAPI
- **Material 3 + Material You** — dynamic theming with light/dark/auto
- **Onboarding** — 5-page pager with feature overview and connection setup
- **Settings** — connection management, theme, data export/import/reset
- **CI/CD** — GitHub Actions for lint, build, test, and tag-driven releases
- **Dev scripts** — build, install, run, test, relay via scripts/dev.bat

[Unreleased]: https://github.com/user/hermes-android/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/user/hermes-android/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/user/hermes-android/releases/tag/v0.1.0
