# Hermes Relay — Dev Log

## 2026-04-05 — Project Scaffolding

**Done:**
- Wrote spec (docs/spec.md) and decisions (docs/decisions.md)
- Created CLAUDE.md handoff for agent development
- Created DEVLOG.md

## 2026-04-05 — MVP Phase 0 + Phase 1 Implementation

**Done:**
- Created Android app — full Jetpack Compose project (30+ files, 2500+ lines)
  - Bottom nav scaffold with 4 tabs (Chat, Terminal, Bridge, Settings)
  - WSS connection manager (OkHttp, auto-reconnect with exponential backoff)
  - Channel multiplexer (typed envelope protocol)
  - Auth flow (6-char pairing code → session token in EncryptedSharedPreferences)
  - Chat UI: message bubbles, streaming text, tool progress cards, profile selector
  - Settings UI: server URL, connection status, theme selector
  - Material 3 + Material You dynamic theming
  - @Preview composables for MessageBubble and ToolProgressCard
  - Terminal and Bridge tabs stubbed for Phase 2/3
- Created relay server — Python aiohttp WSS server (10 files, 1500+ lines)
  - WSS server on port 8767 with health check
  - Auth: pairing codes (10min expiry), session tokens (30-day expiry), rate limiting
  - Chat channel: proxies to Hermes WebAPI SSE, re-emits as WS envelopes
  - Terminal/bridge channel stubs
  - Runnable via `python -m relay_server`
- Created CI/CD — GitHub Actions
  - CI: lint → build (debug APK) → test, relay syntax check
  - Release: tag-triggered, version validation, signed APK → GitHub Release

## 2026-04-05 — Repo Restructure + Build Fixes

**Done:**
- Promoted Android project to repo root (Android Studio opens root directly)
- Removed `hermes-android-bridge/` (upstream absorbed into Compose rewrite)
- Renamed `hermes-android-plugin/` → `plugin/`
- Moved root `tools/`, `skills/`, `tests/` into `plugin/`
- Resolved build issues: gradle.properties, launcher icons, SDK path, compileSdk 36
- Pinned AGP 8.13.2 + Gradle 8.13 + JVM toolchain 17
- Added dev scripts (`scripts/dev.sh` + `scripts/dev.bat`)
- Updated all docs (README, CLAUDE.md, AGENTS.md, DEVLOG.md, spec directory structure)
- Build verified: debug APK builds successfully

**Current state:**
- Phase 0 + Phase 1 complete
- Android Studio opens and syncs from repo root
- Debug APK builds and deploys to emulator/device
- Dev scripts ready for build/install/run/test/relay workflows

## 2026-04-05 — Direct API Chat Refactor

**Done:**
- Refactored chat to connect directly to Hermes API Server (`/v1/chat/completions`)
  - Chat no longer routes through relay server — bypasses it entirely
  - Uses OpenAI-compatible HTTP/SSE with `X-Hermes-Session-Id` for session continuity
  - Auth via `Authorization: Bearer <API_SERVER_KEY>` stored in EncryptedSharedPreferences
- New `HermesApiClient` — OkHttp-SSE client with health check and streaming chat
- New `ApiModels.kt` — OpenAI-format request/response models (ChatCompletionChunk, etc.)
- Refactored `ChatHandler` — removed envelope-based dispatch, added typed SSE entry points
- Refactored `ConnectionViewModel` — dual connection model:
  - API Server (HTTP) for chat — URL, key, health check, reachability state
  - Relay Server (WSS) for bridge/terminal — separate URL, connect/disconnect
- Refactored `ChatViewModel` — sends via `HermesApiClient.sendChatStream()` with cancel support
- Updated Onboarding — collects API Server URL + API Key (required) + Relay URL (optional)
- Updated Settings — split into "API Server" and "Relay Server" cards
- Updated ChatScreen — gates on API reachability, added stop button for streaming
- Updated DataManager — backup format v2 with separate apiServerUrl/relayUrl fields
- Updated docs/decisions.md with ADR for direct API chat
- Updated docs/spec.md with new chat architecture

**Architecture change:**
```
Before: Phone (WSS) → Relay (:8767) → WebAPI (:8642)  [everything]
After:  Phone (HTTP/SSE) → API Server (:8642)          [chat — direct]
        Phone (WSS) → Relay (:8767)                     [bridge, terminal]
```

## 2026-04-05 — Edge Case Fixes + CI/CD Hardening

**Done:**
- Fixed SSE thread safety — all callbacks dispatched to main thread via Handler
- Fixed overlapping streams — previous stream cancelled before new send
- Fixed tool call completion — now matches by toolCallId instead of first incomplete
- Fixed onboarding test connection — client properly cleaned up on exception via try/finally
- Fixed health check loop — only runs when API client is configured
- Added `network_security_config.xml` — cleartext restricted to localhost/127.0.0.1/emulator
- Added ProGuard rules for `okhttp-sse` (okhttp3.sse.**, okhttp3.internal.sse.**)
- Added `id` field to ToolCall data class for proper matching
- SSE read timeout set to 5 minutes (was 0/infinite — detects dead connections)
- OkHttpClient.shutdown() now uses awaitTermination for clean teardown
- Used AtomicBoolean for completeCalled flag (thread-safe)
- Created CHANGELOG.md (Keep a Changelog format)
- Created RELEASE_NOTES.md (used as GitHub Release body)
- Updated release.yml — uses RELEASE_NOTES.md body, SHA256 checksums, prerelease detection
- Created .github/dependabot.yml (Gradle + GitHub Actions, weekly, grouped)
- Created .github/PULL_REQUEST_TEMPLATE.md with checklist
- Added in-app "What's New" dialog in Settings (reads from bundled whats_new.txt asset)
- Bumped versionCode=2, versionName=0.2.0

## 2026-04-05 — Session Management + What's New Auto-Show

**Done:**
- Switched chat streaming from `/v1/chat/completions` to `/api/sessions/{id}/chat/stream`
  - Proper Hermes session API — not the undocumented X-Hermes-Session-Id header
  - Parses Hermes-native SSE events (assistant.delta, tool.started, tool.completed, assistant.completed)
- Full session CRUD in HermesApiClient:
  - `listSessions()`, `createSession()`, `deleteSession()`, `renameSession()`, `getMessages()`
- Session drawer UI (ModalNavigationDrawer):
  - List sessions with title, timestamp, message count
  - New Chat button, switch sessions, rename/delete with confirmation dialogs
  - Hamburger menu icon in ChatScreen top bar
- Session lifecycle:
  - Auto-creates session on first message if none active
  - Auto-titles session from first user message (truncated to 50 chars)
  - Message history loads when switching sessions
  - Last session ID persisted to DataStore — resumes on app restart
  - Optimistic deletes and renames
- What's New auto-show:
  - Tracks last seen version in DataStore (KEY_LAST_SEEN_VERSION)
  - Shows WhatsNewDialog automatically when version changes
  - Dismisses and records current version
- New models: SessionModels.kt (SessionItem, MessageItem, HermesSseEvent, etc.)
- Updated ChatHandler with session management methods (updateSessions, removeSession, etc.)
- Updated ChatMessage.ChatSession with messageCount and updatedAt fields
- Updated whats_new.txt with session management features

## 2026-04-05 — MVP Polish: Markdown, Reasoning, Tokens, Personalities, UX

**Done:**
- **Markdown rendering** — assistant messages render with full markdown (code blocks, bold, italic, links, lists) via mikepenz multiplatform-markdown-renderer-m3
- **Message copy** — long-press on any message bubble to copy text to clipboard
- **Reasoning/thinking display** — collapsible ThinkingBlock above assistant responses when agent uses extended thinking; toggle in Settings
- **Token & cost tracking** — per-message token count (↑input ↓output) and estimated cost displayed below timestamp
- **Personality picker** — dropdown in chat top bar with 8 built-in personalities (default, concise, creative, technical, teacher, formal, pirate, kawaii); injects system_message into chat stream
- **Error retry button** — "Retry" button in error banner re-sends last failed message
- **Offline detection** — ConnectivityObserver via ConnectivityManager; shows offline banner when network is lost
- **Streaming state fix** — onStreamError now clears isStreaming/isThinkingStreaming on all affected messages
- **Haptic feedback** — on send, stream complete, error, and message copy
- **Input character limit** — 4096 char limit with counter shown near the limit
- **Responsive layout** — bubble width adapts by screen width: 300dp (phone), 480dp (medium), 600dp (tablet)
- **Enriched tool cards** — tool-type-specific icons (terminal, search, file, tap, keyboard, etc.), duration tracking ("Completed in X.Xs")
- **Accessibility** — content descriptions on message bubbles, tool cards, thinking blocks, all interactive elements
- **Dead code cleanup** — deleted unused ApiModels.kt and ChatModels.kt
- **Settings: Chat section** — new "Show reasoning" toggle
- Added ACCESS_NETWORK_STATE permission to AndroidManifest

**New files:**
- `ui/components/MarkdownContent.kt` — Compose markdown renderer wrapper
- `ui/components/ThinkingBlock.kt` — collapsible reasoning/thinking display
- `ui/components/TokenDisplay.kt` — per-message token + cost display
- `ui/components/PersonalityPicker.kt` — personality selection dropdown
- `network/ConnectivityObserver.kt` — reactive network connectivity listener

**New dependencies:**
- `com.mikepenz:multiplatform-markdown-renderer-m3:0.30.0`
- `com.mikepenz:multiplatform-markdown-renderer-code:0.30.0`
- `material3-window-size-class` (responsive layout)

## 2026-04-05 — Full Audit + Bug Fixes + VitePress Docs Site

**Audit findings fixed (9 bugs):**
- CRITICAL: Added ProGuard rules for mikepenz markdown renderer + intellij-markdown parser
- CRITICAL: Cached fallback StateFlows in ChatViewModel (was creating new instances per access)
- MAJOR: Fixed MessageItem.id type from Int? to String? (server returns string IDs)
- HIGH: Added !isStreaming guard to send button (prevented double-send)
- HIGH: Added personality fallback when ID not found (prevents null system message)
- MEDIUM: Session rename dialog remember key now includes session (prevents stale state)
- MEDIUM: ToolProgressCard duration guard against negative values
- LOW: Error banner text limited to 2 lines with ellipsis
- BUILD: Unified AGP version to 8.13.2 in libs.versions.toml

**VitePress documentation site created:**
- 20 pages across 4 sections: Guide, Features, Architecture, Reference
- Landing page with hero section and 8 feature cards
- Full user guide: getting started, chat, sessions, troubleshooting
- Feature docs: direct API, markdown, reasoning, personalities, tokens, tools
- Architecture docs: overview diagram, ADRs adapted from docs/decisions.md, security model
- API reference with all endpoints and request/response examples
- Configuration reference with all DataStore keys and settings
- VitePress config with nav, sidebar, local search
- Run with: `npm install && npm run dev:docs`

**Next:**
- Build and test against running Hermes API server
- Test on emulator and physical device (S25 Ultra)
- Set up keystore/signing secrets for release CI
- Deploy docs site (GitHub Pages or similar)
- Phase 2: Terminal channel (xterm.js in WebView, tmux integration)
- Phase 3: Bridge channel migration
