# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.1.0] - 2026-04-07

### Added
- **ASCII morphing sphere** — animated 3D character sphere on empty chat screen (pure Compose Canvas, `. : - = + * # % @` characters, green-purple color pulse, 3D lighting)
- **Ambient mode** — toggle in chat header hides messages and shows sphere fullscreen; tap to return to chat
- **Animation behind messages** — sphere renders at 15% opacity behind chat message list as subtle background (toggleable)
- **Animation settings** — Settings > Appearance section with "ASCII sphere" and "Behind messages" toggles
- **File attachments** — attach files via `+` button; images, documents, PDFs sent as base64 in the Hermes API `attachments` format
- **Attachment preview** — horizontal strip above input shows thumbnails (images) or file badges (other types) with remove button
- **Message queuing** — send messages while the agent is streaming; queued messages auto-send when the current response completes
- **Queue indicator** — animated bar above input shows queued count with clear button
- **Configurable limits** — expandable Limits section in Chat settings for max attachment size (1–50 MB) and message length (1K–16K chars)
- **Stats for Nerds enhancements** — reset button, tokens per message average, peak TTFT, slowest completion, seconds subtext on all ms values
- **Feature gating** — `FeatureFlags` singleton with compile-time defaults (`BuildConfig.DEV_MODE`) and runtime DataStore overrides
- **Developer Options** — hidden settings section, tap version 7 times to unlock (same pattern as Android system Developer Options)
- **Relay feature toggle** — relay server settings and pairing sections gated behind developer options in release builds
- **Dynamic onboarding** — terminal, bridge, and relay pages excluded from onboarding when relay feature disabled
- **Parse tool annotations** — experimental annotation parsing for Sessions mode (marked with badge, disabled for Runs mode)
- **Privacy policy link** — accessible from Settings → About
- **MCP tooling docs** — `docs/mcp-tooling.md` reference for android-tools-mcp + mobile-mcp development setup
- **Dev scripts** — added `release`, `bundle`, `version` commands to `scripts/dev.bat`
- **MIT LICENSE** — added project license file

### Fixed
- **Empty bubbles** — messages with blank content and no tool calls are now hidden from chat
- **App icon** — adaptive icon foreground scaled to 75% via `<group>` transform for proper safe zone padding
- **Token tracking** — usage data now extracted before SSE event type resolution, fixing 0 token counts when server sends OpenAI-format events
- **Token field compatibility** — `UsageInfo` accepts both `input_tokens`/`output_tokens` (Hermes) and `prompt_tokens`/`completion_tokens` (OpenAI)
- **Keyboard gap** — removed Scaffold content window insets that stacked with ChatScreen's IME padding
- **Session drawer highlight** — active session now properly highlighted (background color was computed but not applied)
- **Privacy doc** — added CAMERA permission, corrected network security description
- **CHANGELOG URLs** — fixed comparison links to use correct GitHub repository
- **FOREGROUND_SERVICE** — removed unused permission from AndroidManifest
- **Plugin refs** — updated from raulvidis to Codename-11

### Changed
- Version bumped from `0.1.0-beta` to `0.1.0` for Google Play release
- Input bar shows both Stop and Send buttons during streaming (previously only Stop)
- Onboarding page flow now uses enum-based dynamic list instead of hardcoded indices

## [0.1.0-beta] - 2026-04-06

MVP release — native Android companion app for Hermes agent with direct API chat, session management, and full Compose UI.

### Added

#### Core Chat
- **Direct API chat** — connects to Hermes API Server via `/api/sessions/{id}/chat/stream` with SSE streaming
- **HermesApiClient** — full session CRUD + SSE streaming, health checks, cancel support
- **Dual connection model** — API Server (HTTP) for chat, Relay Server (WSS) for bridge/terminal
- **API key auth** — optional Bearer token stored in EncryptedSharedPreferences
- **Cancel streaming** — stop button to cancel in-flight chat responses
- **Error retry** — retry button in error banner re-sends last failed message

#### Session Management
- **Session CRUD** — create, switch, rename, delete chat sessions via Sessions API
- **Session drawer** — slide-out panel listing all sessions with title, timestamp, message count
- **Message history** — loads from server when switching sessions
- **Auto-session titles** — first user message auto-titles the session (truncated to 50 chars)
- **Session persistence** — last session ID saved to DataStore, resumes on app restart

#### Chat UI
- **Markdown rendering** — assistant messages render code blocks, bold, italic, links, lists (mikepenz multiplatform-markdown-renderer)
- **Reasoning display** — collapsible thinking block above assistant responses (toggle in Settings)
- **Token tracking** — per-message input/output token count and estimated cost
- **Personality picker** — dynamic personalities from server config (`config.agent.personalities`), agent name on chat bubbles
- **Message copy** — long-press any message to copy text to clipboard
- **Enriched tool cards** — tool-type icons, completion duration tracking
- **Responsive layout** — bubble widths adapt to phone, tablet, and landscape
- **Input character limit** — 4096 character limit with counter
- **Haptic feedback** — on send, stream complete, error, and message copy

#### App Foundation
- **Jetpack Compose scaffold** — bottom nav with Chat, Terminal (stub), Bridge (stub), Settings
- **WSS connection manager** — OkHttp WebSocket with auto-reconnect and exponential backoff
- **Channel multiplexer** — typed envelope protocol for chat/terminal/bridge/system
- **Auth flow** — 6-character pairing code with session token persistence
- **Material 3 + Material You** — dynamic theming with light/dark/auto
- **Onboarding** — multi-page pager with feature overview and connection setup
- **Settings** — API Server + Relay Server config, theme, reasoning toggle, data export/import/reset
- **Offline detection** — banner shown when network connectivity is lost
- **What's New dialog** — shown automatically when app version changes
- **Splash screen** — branded splash via core-splashscreen API
- **Network security** — cleartext restricted to localhost only

#### Infrastructure
- **Relay server** — Python aiohttp WSS server for bridge/terminal channels
- **CI/CD** — GitHub Actions for lint, build, test, and tag-driven releases
- **Claude Code automation** — issue triage, PR fix, chat, and code review workflows
- **Dependabot** — weekly Gradle + GitHub Actions dependency updates with auto-merge
- **Dev scripts** — build, install, run, test, relay via scripts/dev.bat
- **ProGuard rules** — okhttp-sse, markdown renderer, intellij-markdown parser

[Unreleased]: https://github.com/Codename-11/hermes-relay/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/Codename-11/hermes-relay/compare/v0.1.0-beta...v0.1.0
[0.1.0-beta]: https://github.com/Codename-11/hermes-relay/releases/tag/v0.1.0-beta
