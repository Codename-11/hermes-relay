# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.2.0] - 2026-04-12

### Added
- **Voice mode** — real-time voice conversation via relay TTS/STT endpoints. Tap the mic in the chat bar to enter voice mode with the sphere, waveform visualizer, and streaming sentence-level TTS playback
  - Three interaction modes (Tap-to-talk, Hold-to-talk, Continuous)
  - Streaming TTS with sentence-boundary detection
  - Interrupt: tap stop during Speaking to cancel TTS + SSE stream
  - Sphere voice states: Listening (blue/purple), Speaking (green/teal)
  - Voice settings screen (interaction mode, silence threshold, provider info, Test Voice)
  - Relay endpoints: `POST /voice/transcribe`, `POST /voice/synthesize`, `GET /voice/config`
  - 6 TTS + 5 STT providers via hermes-agent config
  - Voice messages appear as normal chat messages in session history
- **Reactive layered-sine waveform** — three overlapping waves with amplitude-driven phase velocity (`withFrameNanos` ticker), pill-shaped edge merge (geometric `sin(πt)` taper + `BlendMode.DstIn` gradient mask), color-keyed to voice state
- **Enter/exit voice chimes** — synthesized 200ms PCM sweeps via AudioTrack (440→660 Hz enter, mirror exit)
- **Terminal (Phase 2)** — tmux-backed persistent shells with tabs, scrollback search, and session info sheet
- **Session TTL picker** — choose 1d / 7d / 30d / 90d / 1y / Never at pair time
- **Per-channel grants** — control terminal/bridge access per paired device
- **Android Keystore token storage** — StrongBox-preferred hardware-backed encrypted storage with TEE fallback
- **TOFU certificate pinning** — SHA-256 SPKI fingerprints per host:port, wiped on re-pair
- **Paired Devices screen** — list all paired devices with metadata, extend sessions, revoke access
- **Transport security badges** — three-state visual indicator (secure / insecure-with-reason / insecure-unknown)
- **HMAC-SHA256 QR signing** — pairing QR codes signed via host-local secret
- **Insecure connection acknowledgment dialog** — first-time consent with threat model explanation + reason picker
- **Inbound media pipeline** — agent-produced files via relay `MediaRegistry` with opaque tokens, Discord-style rendering for image/video/audio/PDF/text/generic attachments
- **`/media/by-path` endpoint** — LLM-emitted `MEDIA:/path` markers fetched directly by the phone
- **Settings refactor** — category-list landing page with dedicated sub-screens (Connection, Chat, Voice, Media, Appearance, Paired Devices, Analytics, Developer)
- **Global font-scale preference** — applies to both chat and terminal
- **RelayErrorClassifier** — converts any `Throwable` into a user-facing `HumanError(title, body, retryable, actionLabel)` with context-aware titles
- **Global SnackbarHost** — `LocalSnackbarHost` CompositionLocal at RelayApp scope so any screen can surface classified errors
- **Mic permission banner** — rebuilt with "Open Settings" action button instead of a toast
- **Relay `.env` autoload** — `plugin/relay/_env_bootstrap.py` loads `~/.hermes/.env` at Python import time, matching the gateway pattern
- **systemd user service** — `install.sh` step [6/6] installs and enables `hermes-relay.service` automatically
- **Save & Test health probe** — relay connection verification with classified error feedback
- **Gradle logcat task** — `silenceAndroidViewLogs` auto-runs `adb shell setprop log.tag.View SILENT` after every install to suppress Compose Android 15 VRR spam
- **App screenshots** in `assets/screenshots/`

### Fixed
- **Voice replying to wrong turn** — `ignoreAssistantId` baseline prevents the stream observer from replaying the previous turn's response as TTS for the new question
- **Waveform flatline between sentences** — TTS consumer restructured from `for` loop to `while` + `tryReceive` so `maybeAutoResume` only fires when the queue is actually drained, not after every sentence
- **Stop button during Speaking** — `interruptSpeaking()` now cancels the SSE stream via `chatViewModel.cancelStream()`, drains TTS queue, and returns to Idle (previously only paused playback)
- **Waveform unresponsive to speech** — perceptual amplitude curve at the source (noise-floor subtraction + speech-ceiling rescale + sqrt boost), attack/release envelope follower (0.75/0.10 at 60Hz), killed Compose spring double-smoothing
- **Stop button color** — hardcoded vivid red `Color(0xFFE53935)` for Listening + Speaking (Material 3 dark `colorScheme.error` resolved to pale pink)
- **NaN amplitude propagation** — guards in VoicePlayer.computeRms and VoiceViewModel.sanitizeAmplitude (IEEE 754: `Float.coerceIn` silently passes NaN)
- **Relay voice 500 on restart** — `.env` not loaded into relay process when started via nohup/systemd without shell sourcing
- **Rate-limit block on re-pair** — `/pairing/register` clears all rate-limit blocks on success
- **Paired devices JSON unwrap** — permissive `/media/by-path` sandbox
- **Settings status flicker** — unified relay status as "Reconnecting..." on Settings entry

### Changed
- Smart-swap trailing input button (empty → Mic, text → Send) replacing the floating Mic FAB
- Voice overlay is fully opaque surface (was 0.95 alpha — "phantom pencil" bleed-through from chat)
- Bottom nav hidden during voice mode
- Scrollable response text in voice overlay (long responses no longer clip)
- `install.sh` is now 6 steps (was 5) — new step [6/6] for systemd user service
- Relay restart is `systemctl --user restart hermes-relay` (nohup era ended)

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
