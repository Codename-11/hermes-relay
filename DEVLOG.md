# Hermes Relay — Dev Log

## 2026-04-07 — ASCII Morphing Sphere, Ambient Mode, Animation Settings, Polish Fixes

**Done:**
- **ASCII morphing sphere** — animated visualization on the empty chat screen, inspired by AMP Code CLI. Pure Compose Canvas rendering (no OpenGL). Characters `. : - = + * # % @` form a sphere shape with 3D lighting. Color pulses green to purple. Contained in square aspect ratio box above "Start a conversation" text.
- **Ambient mode** — toggle button (AutoAwesome icon) in chat header bar hides messages and shows the sphere fullscreen. Tap the ChatBubble icon to return to chat.
- **Animation behind messages** — sphere renders at 15% opacity behind the chat message list as a subtle ambient background. Toggleable in Settings.
- **Animation settings** — new section in Settings under Appearance: "ASCII sphere" toggle (on by default), "Behind messages" toggle (on by default, disabled when animation is off).
- **Parse tool annotations** — marked as "Experimental" badge, disabled/dimmed when streaming endpoint is "Runs" mode (only relevant for Sessions mode).
- **Empty bubble fix** — messages with blank content and no tool calls are now hidden from chat.
- **App icon fix** — adaptive icon foreground scaled to 75% via `<group>` transform for proper safe zone padding.
- **Dev scripts** — added `release`, `bundle`, `version` commands to `scripts/dev.bat`.
- **MCP tooling** — android-tools-mcp v0.1.1 (IDE/build layer) + mobile-mcp (device/runtime layer) configured as companion MCP servers. Full reference in `docs/mcp-tooling.md`.
- **Audit fixes** — MIT LICENSE added, orphaned `companion/` and `companion_relay/` removed, `FOREGROUND_SERVICE` permission removed, CHANGELOG URLs fixed, version refs updated to 0.1.0, .gitignore updated, plugin refs updated from raulvidis to Codename-11.

**New files:**
- `ui/components/MorphingSphere.kt` — ASCII morphing sphere composable (Canvas-based, 3D lighting, color pulse)

**Files changed:**
- `ui/screens/ChatScreen.kt` — Empty state sphere, ambient mode toggle, behind-messages background layer
- `ui/screens/SettingsScreen.kt` — Animation settings section (ASCII sphere toggle, behind messages toggle), parse tool annotations experimental badge
- `viewmodel/ConnectionViewModel.kt` — Animation preference DataStore keys/flows
- `app/build.gradle.kts` — Version and build config updates
- `res/mipmap-anydpi-v26/ic_launcher.xml` — 75% scale group transform on foreground
- `scripts/dev.bat` — Added release, bundle, version commands

---

## 2026-04-07 — Token Tracking Fix, Stats Enhancements, Keyboard Gap, Configurable Limits

**Done:**
- **Token tracking fix** — Root cause: OpenAI-format SSE events (e.g. `chat.completion.chunk`) have no `type`/`event` field, so the `val eventType = type ?: event.resolvedType ?: return` line exited before usage was ever checked. Moved usage extraction **before** the type resolution in both `sendChatStream()` and `sendRunStream()`. Also added `prompt_tokens`/`completion_tokens` (OpenAI naming) support in `UsageInfo` via `resolvedInputTokens`/`resolvedOutputTokens` helper properties.
- **Stats for Nerds enhancements** — Reset button with confirmation dialog, tokens per message average in summary line (`~Xk/msg`), peak TTFT and slowest completion times (tertiary color), `formatMsWithSeconds()` helper shows `1234ms (1.2s)` for all time displays >= 1s.
- **Configurable limits** — Expandable "Limits" section in Chat settings with segmented button rows for max attachment size (1/5/10/25/50 MB, default 10) and max message length (1K/2K/4K/8K/16K chars, default 4K). Persisted to DataStore, read reactively in ChatScreen.
- **Keyboard gap fix** — Set `contentWindowInsets = WindowInsets(0)` on the Scaffold in RelayApp.kt. The Scaffold was adding system bar padding to `innerPadding` that stacked with ChatScreen's `imePadding()`, causing a visible gap between input bar and keyboard.

**Files changed:**
- `network/HermesApiClient.kt` — Usage check moved before eventType resolution in both streaming methods
- `network/models/SessionModels.kt` — `UsageInfo` now accepts `prompt_tokens`/`completion_tokens`, added `resolvedInputTokens`/`resolvedOutputTokens`/`resolvedTotalTokens`
- `viewmodel/ChatViewModel.kt` — Uses `usage.resolvedInputTokens` etc
- `viewmodel/ConnectionViewModel.kt` — `maxAttachmentMb` + `maxMessageLength` DataStore keys/flows/setters
- `ui/components/StatsForNerds.kt` — Reset button+dialog, tokens/msg, peak/slowest times, `formatMsWithSeconds()`
- `ui/screens/SettingsScreen.kt` — Expandable "Limits" section with segmented buttons
- `ui/screens/ChatScreen.kt` — Reads `charLimit`/`maxAttachmentMb` from settings
- `ui/RelayApp.kt` — `contentWindowInsets = WindowInsets(0)` on Scaffold

---

## 2026-04-07 — File Attachments

**Done:**
- **Generic file attachments** — users can attach any file type via `+` button in the input bar. Uses Android `OpenMultipleDocuments` picker (accepts `*/*`). Files base64-encoded and sent in the Hermes API `attachments` array (`{contentType, content}`).
- **Attachment preview strip** — horizontal scrollable row above input bar showing pending attachments. Image attachments show decoded thumbnails, other files show document icon + filename + size. Each attachment has a remove (X) button.
- **Attachment rendering in bubbles** — user messages display attached images inline (decoded from base64), non-image attachments show as file badge with name. Forward-compatible with agent-sent images.
- **10 MB file size limit** — enforced client-side with toast warning.
- **Send with attachments only** — send button enabled when attachments are present even without text. Sends `[attachment]` as placeholder text.
- **API integration** — `attachments` parameter added to both `sendChatStream()` and `sendRunStream()` in HermesApiClient. Serialized as JSON array matching Hermes WebAPI spec.
- **Message history support** — `MessageItem.imageUrls` extracts `image_url` content blocks from OpenAI-format content arrays for future server-side image rendering.

**Files changed:**
- `data/ChatMessage.kt` — Added `Attachment` data class, `attachments` field on `ChatMessage`
- `network/models/SessionModels.kt` — Added `imageUrls` property to `MessageItem`
- `network/HermesApiClient.kt` — `attachments` param on `sendChatStream()` + `sendRunStream()`, JSON array serialization
- `viewmodel/ChatViewModel.kt` — `_pendingAttachments` StateFlow, add/remove/clear, snapshot-and-clear on send, pass through to API
- `ui/screens/ChatScreen.kt` — `+` button, `OpenMultipleDocuments` picker, attachment preview strip, `formatFileSize()` helper
- `ui/components/MessageBubble.kt` — Inline image rendering (base64 decode), file badge for non-images

---

## 2026-04-07 — Client-Side Message Queuing

**Done:**
- **Message queuing** — Users can now send messages while the agent is streaming. Messages are queued locally and auto-sent when the current stream completes. Queue drains one at a time, maintaining proper ordering.
- **Input bar redesign** — During streaming, both Stop and Send buttons are visible side by side. Send button uses `tertiary` color during streaming to indicate "queue" mode. Placeholder changes to "Queue a message..." when streaming.
- **Queue indicator** — Animated bar above the input field shows queued message count ("1 message queued" / "3 messages queued") with a Clear button to discard the queue. Uses `AnimatedVisibility` for smooth entrance/exit.
- **Queue lifecycle** — Queue is cleared on stream cancellation (Stop button) and on stream error, preventing stale messages from auto-sending after failures.

**Design decisions:**
- Client-side queuing (not server-side `/queue` command) because the Hermes HTTP API doesn't support concurrent SSE streams to the same session. The gateway's `/queue` is a CLI-level feature, not an HTTP endpoint.
- Queue drains automatically — no manual "send next" required. Provides a seamless conversation flow.
- No purple glow on Send button during streaming — visual distinction between "send now" and "queue for later".

**Files changed:**
- `viewmodel/ChatViewModel.kt` — `_queuedMessages` StateFlow, `sendMessage()` queues during streaming, `sendMessageInternal()` extracted, `drainQueue()` on complete, `clearQueue()`, queue cleared on error/cancel
- `ui/screens/ChatScreen.kt` — Queue indicator row, input bar with both Stop+Send buttons, tertiary send tint during streaming, "Queue a message..." placeholder

---

## 2026-04-07 — Feature Gating, MCP Tooling, v0.1.0 Release Prep

**Done:**
- **Feature gating system** — `FeatureFlags.kt` singleton with compile-time defaults (`BuildConfig.DEV_MODE`) and runtime DataStore overrides. Debug builds have all features unlocked; release builds gate experimental features behind Developer Options.
- **Developer Options** — Hidden settings section activated by tapping version number 7 times (same UX as Android system Developer Options). Contains relay features toggle and lock button. Uses `tertiary` color scheme for visual distinction.
- **Gated relay/pairing settings** — Relay Server and Pairing sections in Settings hidden by default in release builds. Only visible when relay feature flag is enabled via Developer Options.
- **Gated onboarding pages** — Terminal, Bridge, and Relay pages dynamically excluded from onboarding flow when relay feature is disabled. Page count and indices adjust automatically.
- **Version bump** — `0.1.0-beta` → `0.1.0` for Google Play submission.
- **BuildConfig.DEV_MODE** — `true` for debug, `false` for release. Used by FeatureFlags as compile-time default.
- **android-tools-mcp v0.1.1** — Fixed MCP server path, built plugin from fork, committed wrapper jar fix, repo cleanup (fork attribution, VM option name fix, cross-platform release script), released to GitHub.
- **mobile-mcp added** — Added `mobile-next/mobile-mcp` as companion MCP server for device/runtime testing (tap, swipe, screenshot, app management). Configured with telemetry disabled.
- **MCP tooling docs** — Created `docs/mcp-tooling.md` with full reference for both MCP servers (setup, prerequisites, 40 tools listed, when-to-use guide, overlap analysis).

**New files:**
- `data/FeatureFlags.kt` — Feature flag singleton
- `docs/mcp-tooling.md` — MCP tooling reference

**Files changed:**
- `app/build.gradle.kts` — Added `DEV_MODE` BuildConfig field, `buildConfig = true`
- `gradle/libs.versions.toml` — Version `0.1.0`
- `ui/screens/SettingsScreen.kt` — Feature-gated relay/pairing, added Developer Options section with tap-to-unlock
- `ui/onboarding/OnboardingScreen.kt` — Dynamic page list based on feature flags
- `CLAUDE.md` — Updated current state, added FeatureFlags to key files, MCP tooling section, related projects

**Next:**
- Build release APK and submit to Google Play (closed testing track)
- Test feature gating on release build (relay settings hidden, dev options tap unlock)
- Phase 2: Terminal channel
- Phase 3: Bridge channel

---

## 2026-04-07 — Session Management Audit, Play Store Release Prep

**Done:**
- **Session management audit** — Full review of session CRUD, persistence, capability detection, error handling. Implementation is complete and solid against upstream Hermes API (both `/api/sessions` non-standard and `/v1/runs` standard endpoints).
- **Fixed SessionDrawer highlight bug** — `backgroundColor` variable was computed but never applied to the Row modifier. Active sessions now properly highlighted with `secondaryContainer`.
- **Added Privacy Policy link** — New "Privacy Policy" button in Settings → About, linking to GitHub-hosted `docs/privacy.md`. Required for Google Play Store submission.
- **Fixed privacy.md inaccuracies** — Added CAMERA permission to the permissions table (used for QR scanning, declared `required="false"`). Corrected network security description to accurately reflect cleartext policy.
- **Fixed RELEASE_NOTES.md URL** — Changed generic `user/hermes-android` to actual `Codename-11/hermes-android`.
- **Improved network_security_config.xml docs** — Expanded comment explaining why cleartext is globally permitted (Android doesn't support IP range restrictions, users connect to arbitrary LAN IPs) and how security is enforced at the application layer (insecure mode toggle + warning badge).

**Session management features confirmed working:**
- List/create/switch/rename/delete sessions with optimistic updates + rollback
- Message history loading with tool call reconstruction
- Auto-session creation on first message send with auto-title
- Session ID persistence via DataStore across app restarts
- Capability detection (`detectChatMode()`) with graceful degradation
- Both Sessions and Runs streaming endpoints

**Play Store readiness:**
- Signing config loads from env vars / local.properties ✅
- ProGuard rules comprehensive ✅
- Release workflow with version validation ✅
- Privacy policy link in app ✅
- Network security documented ✅
- No hardcoded debug flags in release ✅
- Version: `0.1.0-beta` (versionCode 1) — ready for open testing track

**Files changed:**
- `ui/components/SessionDrawer.kt` — Added `background` import + applied `backgroundColor` to Row
- `ui/screens/SettingsScreen.kt` — Added Shield icon import + Privacy Policy button in About card
- `docs/privacy.md` — Added CAMERA permission, fixed network security description
- `RELEASE_NOTES.md` — Fixed issues URL
- `res/xml/network_security_config.xml` — Expanded documentation comment

---

## 2026-04-07 — Chat UI Polish, Annotation Stripping, Reasoning Extraction

**Done:**
- **Scroll-to-bottom FAB** — SmallFloatingActionButton appears when scrolled up from bottom. Animated fade + slide. Haptic on click. Positioned bottom-end of message area.
- **Message entrance animations** — `animateItem()` on all LazyColumn items (messages, spacers, streaming dots). Smooth fade + slide when items appear/reorder.
- **Date separators** — "Today", "Yesterday", or "EEE, MMM d" chips between messages from different calendar days. Subtle surfaceVariant pill style.
- **Message grouping** — Consecutive same-sender messages have tighter spacing (2dp base + 1dp vs 6dp padding), suppressed agent name on non-first messages, grouped bubble corner shapes (flat edges where messages meet).
- **Pre-first-token indicator** — Placeholder assistant message with streaming dots appears immediately after send, before any SSE delta. Fills naturally when first delta arrives.
- **Copy feedback toast** — Snackbar "Copied to clipboard" on long-press copy. Previously only haptic with no visual confirmation.
- **Annotation stripping** — When the tool annotation parser matches inline text (`` `💻 terminal` ``), it now strips that text from the message content. Previously the raw annotation text remained visible alongside the ToolCall card.
- **Inline reasoning extraction** — `<think>`/`<thinking>` tags in assistant text are detected and redirected to `thinkingContent` for the ThinkingBlock. Handles tags split across streaming deltas. Resets on stream complete.

**Files changed:**
- `ui/screens/ChatScreen.kt` — FAB, date separators, grouping, snackbar, animation modifiers, Box wrapper for message area
- `ui/components/MessageBubble.kt` — `isFirstInGroup`/`isLastInGroup` params, grouped bubble shapes, conditional agent name
- `network/handlers/ChatHandler.kt` — `addPlaceholderMessage()`, `stripLineFromContent()`, `processInlineReasoning()`, thinking tag parser, `parseAnnotationLine` returns Boolean
- `viewmodel/ChatViewModel.kt` — placeholder message before stream start

**Note:** Code block copy button already existed (`MarkdownContent.kt` → `CodeBlockWithCopyButton`).

---

## 2026-04-07 — Tool Call Rendering Fix, Runs API, SSE Architecture Correction

**Done:**
- **Fixed premature stream completion** — `assistant.completed` was calling `onComplete()`, terminating the stream before tool events arrived in multi-turn agent loops. Now only `run.completed`/`done` end the stream. `assistant.completed` calls new `onTurnComplete()` which marks one message done without stopping the stream.
- **Added `message.started` handling** — Server-assigned message IDs now tracked via `onMessageStarted` callback. Enables proper multi-turn message tracking (each assistant turn gets its own message).
- **Dynamic message ID tracking** — `ChatViewModel.startStream()` uses `currentMessageId` variable that updates when the server sends new message IDs, instead of hardcoding one UUID for the whole stream.
- **Rewrote tool annotation parser** — Regex patterns now match actual Hermes format: `` `💻 terminal` `` (any emoji + tool name in backticks). Uses state tracking: first occurrence = start, second = complete. Also handles explicit completion/failure emojis (✅/❌) and verbose format (`🔧 Running: tool_name`).
- **Fixed message history tool calls** — `loadMessageHistory()` now reconstructs `ToolCall` objects from assistant messages' `tool_calls` field and matches tool results from `role:"tool"` messages. Previously skipped all tool data.
- **Runs API event coverage** — Added `message.delta`, `reasoning.available`, `run.failed` event handling. Updated `HermesSseEvent` model with `event` field (alias for `type`), `tool` field (Runs API format), `duration`, `output`, `text`, `timestamp`. Added `resolvedType` and `resolvedToolName` helpers.
- **SSE debug logging** — All events logged with `HermesApiClient` tag. Filter with `adb logcat -s HermesApiClient` to see what the server actually sends.
- **Updated decisions.md** — Documented the two streaming endpoints (Sessions vs Runs), tool call transparency differences, upstream API notes.
- **Updated settings description** — Streaming endpoint toggle now explains the difference.

**Architecture correction (from upstream research):**
- `/api/sessions` CRUD endpoints are NOT in upstream hermes-agent source. They may be version-specific (v0.7.0). Standard endpoints are `/v1/chat/completions`, `/v1/responses`, `/v1/runs`.
- `/v1/chat/completions` streaming embeds tool calls as **inline markdown text** (`` `💻 terminal` ``), NOT as separate SSE events. The annotation parser is the primary detection path.
- `/v1/runs` + `/v1/runs/{run_id}/events` provides **structured lifecycle events** with real `tool.started`/`tool.completed` — this is the correct endpoint for rich tool display.
- Hermes has no "channels" API (Discord/Telegram-style). The `channel_directory.py` is for cross-platform message routing, not a chat API.

**Files changed:**
- `network/HermesApiClient.kt` — new callbacks, fixed completion flow, debug logging
- `network/handlers/ChatHandler.kt` — `onTurnComplete()`, annotation rewrite, history tool calls
- `network/models/SessionModels.kt` — new fields for Runs API compatibility
- `viewmodel/ChatViewModel.kt` — dynamic message ID tracking, new callback wiring
- `ui/screens/SettingsScreen.kt` — updated endpoint toggle description
- `docs/decisions.md` — corrected API architecture documentation

**Next:**
- Deploy to device and test tool call rendering with `adb logcat -s HermesApiClient`
- Test with both "Sessions" and "Runs" endpoint modes
- Verify annotation parser matches actual Hermes verbose output
- If Runs API works well, consider making it the default endpoint

**Blockers:**
- Need a running hermes-agent server with tools configured to validate tool event flow end-to-end

---

## 2026-04-06 — Personality System, Command Palette, QR Pairing, Chat Header

**Done:**
- **Personality system fix** — `getProfiles()` was reading wrong JSON path and returning empty list. Replaced with `getPersonalities()` reading `config.agent.personalities` + `config.display.personality`. Server default personality shown first in picker. Switching sends personality's system prompt via `system_message` (previous `profile` field was ignored by server).
- **Agent name on chat bubbles** — Added `agentName` field to `ChatMessage`. Active personality name displayed above assistant messages.
- **Chat header redesign** — Messaging-app style: avatar circle with initial letter + `ConnectionStatusBadge` pulse overlay, agent name (`titleMedium`), model name subtitle from `/api/config`.
- **Command palette** — Searchable bottom sheet with category filter chips (2-row limit, expandable), 29 gateway built-in commands + dynamic personality commands + 90+ server skills from `GET /api/skills`. `/` button on input bar opens palette.
- **Inline autocomplete improved** — Extracted to `InlineAutocomplete` component with `LazyColumn`, 2-line descriptions, up to 8 results.
- **QR code pairing** — ML Kit barcode scanner + CameraX. Detects `{"hermes":1,...}` payload, auto-fills server URL + API key, triggers connection test. Available in Settings and Onboarding.
- **`hermes-pair` skill** — Added to `skills/hermes-pairing-qr/` for users to install on their server. Generator script + SKILL.md.
- **ConnectionStatusBadge** — Reusable animated status indicator with pulse ring (green connected, amber connecting, red disconnected). Wired into Settings, Onboarding, and chat header.
- **Relay server docs** — `docs/relay-server.md`, `relay_server/Dockerfile`, `relay_server/hermes-relay.service`, `relay_server/SKILL.md`.
- **Upstream contributions doc** — `docs/upstream-contributions.md` — proposed `GET /api/commands`, `personality` parameter, terminal HTTP API.

**Corrections to previous session:**
- "Server profile picker" was actually fetching from wrong path — now correctly reads `config.agent.personalities`
- "Sends `profile` field" — server ignores this; now sends `system_message` with personality prompt
- "13 personality commands" were hardcoded — now generated dynamically from server config
- ProfilePicker renamed to PersonalityPicker

---

## 2026-04-06 — v0.1.0-beta Polish, Profiles, Analytics, Splash

**Done:**
- **Package rename** — `com.hermesandroid.companion` → `com.hermesandroid.relay`. All files moved, manifest updated, app name changed to "Hermes Relay".
- **Server profile picker** — Replaced hardcoded 8-personality system with dynamic server profiles fetched from `GET /api/config`. ProfilePicker in top bar shows Default + server-configured profiles. Sends `profile` field in chat requests.
- **Personality switching** — 13 built-in Hermes personalities available via `/personality <name>` slash commands (server-side, session-level switching).
- **Slash command autocomplete** — Type `/` in chat input to see built-in commands (`/help`, `/verbose`, `/clear`, `/status`) + 13 personality commands + dynamically fetched server skills via `GET /api/skills`. Filterable dropdown overlay.
- **In-app analytics (Stats for Nerds)** — `AppAnalytics` singleton tracking response times (TTFT, completion), token usage, health check latency, stream success rates. Canvas bar charts in Settings with purple gradient. Accessible via Settings > Chat > Stats for Nerds.
- **Tool call display config** — Off/Compact/Detailed modes in Settings. `CompactToolCall` inline component for compact mode. `ToolProgressCard` auto-expands while tool is running, auto-collapses on complete.
- **App context prompt** — Toggleable system message telling the agent the user is on mobile. Enabled by default in Settings > Chat.
- **Animated splash screen** — `AnimatedVectorDrawable` with scale + overshoot + fade animation. Icon background color matches theme. Hold-while-loading (stays until DataStore ready). Smooth fade-out exit transition. Separate `splash_icon.xml` at 0.9x scale.
- **Chat empty state** — Logo + "Start a conversation" + suggestion chips that populate input.
- **Animated streaming dots** — Replaces static "streaming..." text with pulsing 3-dot animation.
- **Haptic feedback** — On send, copy, stream complete, error.
- **About section redesign** — Logo on dark background, dynamic version from `BuildConfig`, Source + Docs link buttons, credits line.
- **Hermes docs links** — In onboarding welcome page, API key help dialog, and Settings About section.
- **Release signing config** — Environment variables + `local.properties` fallback with graceful debug-signing fallback.
- **Centralized versioning** — `libs.versions.toml` as single source of truth (`appVersionName`, `appVersionCode`).
- **Logo fix** — Removed vertical H bars from ghost layer, now matches actual SVG (V-crossbar + diagonal feathers only).
- **SSE debug logging** — Unhandled event types now logged for diagnostics.
- **Release infrastructure (from ARC patterns)** — 3-job release workflow (validate → CI → release) reading from `libs.versions.toml`. Claude automation workflows (issue triage, fix, code review). Dependabot auto-merge. CHANGELOG.md + RELEASE_NOTES.md for v0.1.0-beta. Updated PR template with Android checklist.

**New files:**
- `data/AppAnalytics.kt` — In-app analytics singleton
- `ui/components/StatsForNerds.kt` — Canvas bar charts for analytics
- `ui/components/CompactToolCall.kt` — Inline compact tool call display
- `network/models/SessionModels.kt` — Session, message, SSE event models
- `res/drawable/splash_icon.xml` — Static splash icon (0.9x scale)
- `res/drawable/splash_icon_animated.xml` — Animated splash vector
- `res/animator/` — Splash animation resources
- `.github/workflows/claude.yml` — Claude automation
- `.github/workflows/claude-code-review.yml` — Claude code review
- `.github/workflows/dependabot-auto-merge.yml` — Dependabot auto-merge

**Next:**
- Build and test against running Hermes API server
- Test on emulator and physical device (S25 Ultra)
- Set up keystore/signing secrets for release CI
- Deploy docs site (GitHub Pages or similar)
- Phase 2: Terminal channel (xterm.js in WebView, tmux integration)
- Phase 3: Bridge channel migration

**Blockers:**
- None — ready for on-device testing

---

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
