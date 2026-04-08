# Hermes Relay — Claude Code Context

> Read this before touching code. Then read docs/spec.md and docs/decisions.md.

## What This Is

A native Android app for Hermes agent. Chat connects directly to the Hermes API Server. Bridge and terminal channels use a relay server over WSS. The app is Kotlin + Jetpack Compose. The server relay is Python + aiohttp.

**Current state:** v0.1.0 (Google Play). Phase 0 + Phase 1 complete with direct API chat, session management, markdown rendering, messaging-style chat header (avatar + agent name + model subtitle), personality picker with agent name on bubbles, searchable command palette (29 gateway commands + dynamic personalities + server skills), QR code pairing, ConnectionStatusBadge (animated pulse ring), in-app analytics (Stats for Nerds with reset, peak times, tokens/msg), animated splash screen, tool display configuration, client-side message queuing (send while streaming), file attachments (images, documents, any file type via base64), configurable limits (attachment size, message length), feature gating with Developer Options, ASCII morphing sphere animation (empty chat state + ambient mode + behind-messages background), and animation settings in Settings. The relay server handles bridge (Phase 3) and terminal (Phase 2) via WSS. Auth uses optional Bearer token for API, pairing code for relay. Relay/pairing settings are hidden in production behind Developer Options (tap version 7x to unlock).

## Architecture

```
Phone (HTTP/SSE) → Hermes API Server (:8642)   [chat — direct]
Phone (WSS)      → Relay Server (:8767)          [bridge, terminal]
```

Chat goes directly to the API server via HTTP/SSE. The API key (Bearer token) is optional — most local setups run without one. Terminal will go through tmux via the relay. Bridge wraps existing relay protocol. See docs/decisions.md for why.

### Upstream Hermes API Reference

**IMPORTANT:** Always verify endpoints against the actual hermes-agent source (`gateway/platforms/api_server.py`). The upstream repo is the source of truth — not our docs, not our memory, not assumptions from other frontends.

**Standard endpoints (confirmed in hermes-agent source):**

| Endpoint | Purpose | Tool Call Format |
|----------|---------|-----------------|
| `POST /v1/chat/completions` | OpenAI-compatible chat (stream=true for SSE) | Inline markdown text (`` `💻 terminal` ``) — no separate tool events |
| `POST /v1/runs` | Start an agent run | Returns `run_id` |
| `GET /v1/runs/{run_id}/events` | SSE stream of run lifecycle events | **Structured events**: `tool.started`, `tool.completed`, `message.delta`, `reasoning.available`, `run.completed`, `run.failed` |
| `POST /v1/responses` | OpenAI Responses API format | Structured `function_call` objects (non-streaming only) |
| `GET /v1/models` | List available models | — |
| `GET /health` | Health check | — |
| `GET/POST/PATCH/DELETE /api/jobs/*` | Cron job management | — |

**Non-standard endpoints (may be version-specific):**

These endpoints work on our hermes-agent v0.7.0 but are **not in the upstream source**. They may be fork-specific, version-specific, or added by plugins. Always use `detectChatMode()` to probe availability.

| Endpoint | Purpose | Fallback |
|----------|---------|----------|
| `POST /api/sessions/{id}/chat/stream` | Session-based SSE chat | Use `/v1/runs` or `/v1/chat/completions` |
| `GET/POST/PATCH/DELETE /api/sessions` | Session CRUD | Use `X-Hermes-Session-Id` header with `/v1/chat/completions` |
| `GET /api/skills` | Skill discovery | Hardcoded command list |
| `GET /api/config` | Server config (personalities, model) | No fallback — personality picker empty |

**Tool call rendering paths:**
1. **Runs API** (`/v1/runs`) — Best for tool display. Emits `tool.started`/`tool.completed` as real SSE events → rendered as ToolProgressCards.
2. **Sessions/Chat Completions** — Tool progress injected as inline markdown (`` `💻 terminal` ``). The `ChatHandler` annotation parser detects these patterns and converts them to ToolCall objects client-side.
3. **Annotation parser** (`ChatHandler.parseAnnotationLine`) — Fallback for any endpoint. Matches backtick-wrapped emoji+tool_name patterns. If your Hermes version uses a different format, check `adb logcat -s HermesApiClient` for raw SSE events and update the regex.

## Key Instructions
- **Always verify upstream before assuming an endpoint exists.** Check `gateway/platforms/api_server.py` in hermes-agent. If an endpoint isn't there, document it as non-standard and implement a fallback.
- When building features that interface with hermes-agent, reference the upstream source — not just our spec docs. Our spec may be aspirational or based on a specific server version.
- If we use a non-standard endpoint, mark it clearly in code comments and ensure `detectChatMode()` handles its absence gracefully.

## Repository Layout

```
hermes-android/                  ← Android Studio opens this root
├── app/                         ← Android app module (Compose)
│   ├── src/main/kotlin/com/hermesandroid/relay/
│   │   ├── ui/                  # Screens, components, theme
│   │   ├── network/             # ConnectionManager, ChannelMultiplexer, handlers
│   │   ├── auth/                # AuthManager (pairing + tokens)
│   │   ├── viewmodel/           # ChatViewModel, ConnectionViewModel
│   │   └── data/                # ChatMessage, ToolCall models
│   └── build.gradle.kts
├── build.gradle.kts             ← Root Gradle (AGP, Kotlin plugins)
├── settings.gradle.kts
├── gradle/                      ← Wrapper (8.13) + version catalog
├── scripts/                     ← Dev scripts (build, install, run, test, relay)
├── relay_server/             ← Python WSS relay server
│   ├── relay.py                 # Main aiohttp WSS server
│   ├── auth.py                  # Pairing + session management
│   ├── channels/                # chat.py, terminal.py (stub), bridge.py (stub)
│   └── config.py
├── plugin/                      ← Hermes agent plugin (14 android_* tools)
│   ├── android_tool.py
│   ├── android_relay.py
│   ├── tools/                   # Standalone toolset
│   ├── skills/                  # Agent skills
│   └── tests/
├── skills/                      ← Installable Hermes skills
│   └── hermes-pairing-qr/      # QR code pairing (hermes-pair script + SKILL.md)
├── docs/                        ← spec, decisions, security
└── .github/workflows/           ← CI + release
```

## Project Conventions

### File Structure
- **Root-level:** README.md, CLAUDE.md, AGENTS.md, DEVLOG.md, .gitignore
- **docs/** — spec, decisions, security, and any other long-form documentation
- **DEVLOG.md** — update at end of each work session with what was done, what's next, blockers

### Code Style — Android (Kotlin)
- **Jetpack Compose** — no XML layouts. Material 3 / Material You.
- **kotlinx.serialization** — not Gson. Type-safe, faster.
- **OkHttp** for WebSocket + SSE — `okhttp` for WSS relay, `okhttp-sse` for API streaming
- **Single-activity** — Compose Navigation for all routing
- **Package:** `com.hermesandroid.relay`
- **Min SDK 26, Target SDK 35, Compile SDK 36**
- **Kotlin 2.0+**, JVM toolchain 17

### Code Style — Server (Python)
- **aiohttp** for the WSS relay — async, matches existing Hermes relay patterns
- **Type hints everywhere** — Python 3.11+ syntax
- **asyncio** for concurrency — no threading
- **Structured logging** — use `logging` module, not print()

### Git
- **Commit messages:** `type: description` — e.g. `feat: add chat channel UI`, `fix: WSS reconnect race condition`
- **Branch from main** — feature branches for anything non-trivial

### Testing
- **Android:** JUnit + Compose testing for UI, MockK for mocks
- **Python:** pytest for relay tests
- **CI runs on every push** — build must pass before merge

## Key Files

| File | Why |
|------|-----|
| `docs/spec.md` | Full specification — protocol, UI layouts, phases, dependencies |
| `docs/decisions.md` | Architecture decisions — framework choice, channel design, auth model |
| `app/src/main/kotlin/.../ui/RelayApp.kt` | Main scaffold — bottom nav, navigation |
| `app/src/main/kotlin/.../network/HermesApiClient.kt` | Direct HTTP/SSE client — `sendChatStream()` for sessions endpoint, `sendRunStream()` for runs endpoint, `detectChatMode()` for capability probing |
| `app/src/main/kotlin/.../network/ConnectionManager.kt` | WSS connection with auto-reconnect (relay) |
| `app/src/main/kotlin/.../network/ChannelMultiplexer.kt` | Envelope routing by channel (relay) |
| `app/src/main/kotlin/.../network/ConnectivityObserver.kt` | Reactive network connectivity listener |
| `app/src/main/kotlin/.../network/handlers/ChatHandler.kt` | Chat message state, streaming events, tool annotation parser (inline markdown → ToolCall) |
| `app/src/main/kotlin/.../network/models/SessionModels.kt` | Session, message, SSE event data models |
| `app/src/main/kotlin/.../data/FeatureFlags.kt` | Feature gating — compile-time defaults (DEV_MODE) + runtime DataStore overrides |
| `app/src/main/kotlin/.../data/AppAnalytics.kt` | In-app analytics singleton (TTFT, tokens, health, stream rates) |
| `app/src/main/kotlin/.../ui/screens/ChatScreen.kt` | Chat UI — streaming messages, slash commands, tool cards |
| `app/src/main/kotlin/.../ui/screens/SettingsScreen.kt` | Settings — connection, chat, appearance, analytics, about |
| `app/src/main/kotlin/.../ui/components/StatsForNerds.kt` | Canvas bar charts for analytics display |
| `app/src/main/kotlin/.../ui/components/CompactToolCall.kt` | Inline compact tool call display |
| `app/src/main/kotlin/.../ui/components/PersonalityPicker.kt` | Personality picker dropdown (from config.agent.personalities) |
| `app/src/main/kotlin/.../ui/components/CommandPalette.kt` | Searchable command palette (bottom sheet) + inline autocomplete |
| `app/src/main/kotlin/.../ui/components/ConnectionStatusBadge.kt` | Animated pulse ring status indicator (connected/connecting/disconnected) |
| `app/src/main/kotlin/.../ui/components/MorphingSphere.kt` | ASCII morphing sphere — 3D lit character sphere with color pulse, used in empty chat state, ambient mode, and behind-messages background |
| `app/src/main/kotlin/.../ui/components/MessageBubble.kt` | Message bubbles with markdown, tokens, tool cards |
| `app/src/main/kotlin/.../ui/components/ToolProgressCard.kt` | Expandable tool execution card (auto-expand/collapse) |
| `app/src/main/kotlin/.../viewmodel/ChatViewModel.kt` | Chat orchestration — send, stream, cancel, slash commands |
| `app/src/main/kotlin/.../viewmodel/ConnectionViewModel.kt` | Dual connection model (API + relay) |
| `app/src/main/res/drawable/splash_icon.xml` | Splash screen icon (0.9x scale) |
| `app/src/main/res/drawable/splash_icon_animated.xml` | Animated splash (scale + overshoot + fade) |
| `relay_server/relay.py` | Relay server — main WSS server (bridge/terminal only) |
| `relay_server/SKILL.md` | Hermes skill reference for relay self-setup |
| `relay_server/Dockerfile` | Container image for relay server |
| `relay_server/hermes-relay.service` | Systemd unit file for persistent deployment |
| `docs/relay-server.md` | Relay server setup, config, Docker, systemd, TLS reference |
| `app/src/main/kotlin/.../ui/components/QrPairingScanner.kt` | QR code scanner + Hermes pairing payload parser |
| `skills/hermes-pairing-qr/SKILL.md` | QR pairing skill for hermes-agent (install to ~/.hermes/skills/) |
| `skills/hermes-pairing-qr/hermes-pair` | QR code generator script (install to ~/.local/bin/) |
| `AGENTS.md` | Tool usage patterns for the `android_*` toolset |
| `docs/mcp-tooling.md` | MCP server setup — android-tools-mcp + mobile-mcp |

## What NOT to Do

- **Don't use XML layouts** — Compose only
- **Don't use Gson** — kotlinx.serialization
- **Don't use Ktor for networking** — OkHttp for WebSocket
- **Don't build terminal or bridge channels yet** — Phase 2 and 3. Stubbed with `TODO`.
- **Don't use plaintext WebSocket** — `wss://` only, even in development
- **Don't put documentation in root** — long-form docs go in `docs/`
- **Don't forget DEVLOG.md** — update it

## MCP Tooling

Two MCP servers are configured for AI-assisted development. See `docs/mcp-tooling.md` for full reference.

| Server | Layer | Requires |
|--------|-------|----------|
| `android-tools-mcp` | IDE/Build — Compose previews, Gradle, code search, Android docs | Android Studio running with project open |
| `mobile-mcp` | Device/Runtime — tap, swipe, screenshot, app management | ADB + connected device/emulator |

Together they cover the full loop: code → preview → build → deploy → interact → screenshot.

## Dev Workflow

```bash
scripts/dev.bat build      # Build debug APK (DEV_MODE=true)
scripts/dev.bat release    # Build signed release APK (DEV_MODE=false)
scripts/dev.bat bundle     # Build release AAB for Google Play upload
scripts/dev.bat run        # Build + install + launch + logcat
scripts/dev.bat test       # Run unit tests
scripts/dev.bat version    # Show current version from libs.versions.toml
scripts/dev.bat relay      # Start relay server (dev mode, no SSL)
```

Open repo root in Android Studio for Compose previews and device deployment.

## Integration Points

| Surface | Standard Endpoint | Non-Standard Fallback |
|---------|-------------------|----------------------|
| Chat streaming | `POST /v1/runs` → `GET /v1/runs/{id}/events` (structured tool events) | `POST /api/sessions/{id}/chat/stream` (inline tool text) |
| Chat (OpenAI compat) | `POST /v1/chat/completions` (stream=true) | — |
| Session CRUD | `X-Hermes-Session-Id` header on `/v1/chat/completions` | `GET/POST/PATCH/DELETE /api/sessions` (non-standard) |
| Personalities | Read from `~/.hermes/config.yaml` | `GET /api/config` (non-standard) |
| Server skills | — | `GET /api/skills` (non-standard) |
| Health check | `GET /health` or `GET /v1/health` | — |
| Models | `GET /v1/models` | — |
| Plugin tools | `android_*` via `plugin/` | — |

## Upstream References

When working on features that interface with hermes-agent, consult these source files directly:

| Topic | Upstream File |
|-------|--------------|
| API endpoints | `gateway/platforms/api_server.py` — all registered HTTP routes |
| Platform adapter interface | `gateway/platforms/base.py` — `BasePlatformAdapter` abstract class |
| Adding a platform | `gateway/platforms/ADDING_A_PLATFORM.md` — 16-step checklist |
| Platform registration | `gateway/run.py` → `_create_adapter()`, `gateway/config.py` → `Platform` enum |
| Channel directory | `gateway/channel_directory.py` — how platforms/channels are enumerated |
| Send message routing | `tools/send_message_tool.py` → `platform_map` dict |
| SSE streaming (runs) | `gateway/platforms/api_server.py` → runs endpoint, `_on_tool_progress` |

## Related Projects

- **[hermes-agent](https://github.com/NousResearch/hermes-agent)** — the agent platform (gateway, WebAPI, plugin system)
- **[android-tools-mcp](https://github.com/Codename-11/android-tools-mcp)** — our fork of Android Studio MCP bridge (Compose previews, Gradle, docs)
- **[mobile-mcp](https://github.com/mobile-next/mobile-mcp)** — device control MCP server (ADB, tap/swipe, screenshots)
