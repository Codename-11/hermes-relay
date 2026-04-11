# Hermes-Relay — Claude Code Context

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
1. **Runs API** (`/v1/runs`) — Best for tool display. Emits `tool.started`/`tool.completed` as real SSE events → rendered as ToolProgressCards in real-time.
2. **Sessions API** (`/api/sessions/{id}/chat/stream`) — Does NOT emit structured tool events during streaming. Tool calls are stored server-side as `tool_calls` JSON on each message. On stream complete, `ChatViewModel.onCompleteCb` reloads message history via `getMessages()` → `loadMessageHistory()` to get proper message boundaries + tool call cards. This is the "session_end reload" pattern.
3. **Annotation parser** (`ChatHandler.parseAnnotationLine` + `finalizeAnnotations`) — Fallback for servers that inject inline markdown annotations (`` `💻 terminal` ``). Parses during streaming + reconciliation pass on stream end. If your Hermes version uses a different format, check `adb logcat -s HermesApiClient` for raw SSE events and update the regex.

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
├── plugin/                      ← Hermes agent plugin (14 android_* tools + relay + pair CLI)
│   ├── android_tool.py
│   ├── android_relay.py
│   ├── pair.py                  # QR pairing implementation — `python -m plugin.pair`, wrapped by `hermes-pair` shim and the `hermes-relay-pair` skill
│   ├── cli.py                   # Registers plugin CLI sub-commands (note: top-level `hermes pair` blocked by upstream argparser gap — use slash command or shell shim)
│   ├── relay/                   # Canonical WSS relay (consolidated from relay_server/)
│   │   ├── server.py            # aiohttp WSS + HTTP routes (incl. /pairing/register)
│   │   ├── auth.py              # PairingManager, SessionManager, RateLimiter
│   │   ├── config.py            # RelayConfig, PAIRING_ALPHABET (full A-Z / 0-9)
│   │   └── channels/            # chat.py, terminal.py (PTY), bridge.py (stub)
│   ├── tools/                   # Standalone toolset
│   ├── skills/                  # Agent skills
│   └── tests/
├── relay_server/                ← Thin compat shim → plugin.relay (legacy entrypoint)
│   ├── __main__.py              # `python -m relay_server` still works
│   ├── Dockerfile
│   ├── hermes-relay.service     # Systemd unit
│   └── requirements.txt
├── skills/                      ← Installable Hermes skills (categorized per canonical layout)
│   └── devops/
│       └── hermes-relay-pair/  # /hermes-relay-pair slash command — QR pairing skill (category: devops)
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
| `app/src/main/kotlin/.../ui/screens/SettingsScreen.kt` | Settings — unified Connection section (Pair-with-your-server card with Scan QR primary action + unified API/Relay/Session status summary; collapsible Manual configuration card for API URL / key / Relay URL / Insecure toggle + Save & Test; collapsible Bridge-pairing-code card gated by `relayEnabled` feature flag, Phase 3 only), plus chat, appearance, analytics, about |
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
| `plugin/relay/server.py` | Canonical relay server — WSS + HTTP routes (health, /pairing, /pairing/register) |
| `plugin/relay/auth.py` | PairingManager (generate + register_code), SessionManager, RateLimiter |
| `plugin/relay/config.py` | RelayConfig + PAIRING_ALPHABET (full A-Z / 0-9 as of 2026-04-11) |
| `plugin/relay/channels/terminal.py` | Phase 2 PTY-backed terminal handler |
| `relay_server/__main__.py` | Thin shim → `plugin.relay.server.main()` — legacy `python -m relay_server` entrypoint |
| `relay_server/SKILL.md` | Hermes skill reference for relay self-setup |
| `relay_server/Dockerfile` | Container image for relay server |
| `relay_server/hermes-relay.service` | Systemd unit file for persistent deployment |
| `docs/relay-server.md` | Relay server setup, config, Docker, systemd, TLS reference |
| `app/src/main/kotlin/.../ui/components/QrPairingScanner.kt` | QR code scanner + `HermesPairingPayload` (incl. optional `relay` block with `url` + `code`) |
| `plugin/pair.py` | QR pairing implementation — probes local relay, pre-registers pairing code via `/pairing/register`, embeds relay URL + code in QR (pure-Python, uses segno). Invoked via `python -m plugin.pair`, wrapped by the `hermes-pair` shell shim and the `hermes-relay-pair` skill. |
| `plugin/cli.py` | Registers plugin CLI sub-commands via the v0.8.0 plugin CLI API. **Note:** the top-level `hermes pair` sub-command is not currently reachable — upstream `hermes_cli/main.py` only reads `plugins.memory.discover_plugin_cli_commands()` and never consults the generic plugin CLI dict. Use `/hermes-relay-pair` (skill) or `hermes-pair` (shell shim) instead. |
| `skills/devops/hermes-relay-pair/SKILL.md` | `/hermes-relay-pair` slash command — canonical category layout (`devops`), matches `metadata.hermes.category` frontmatter. Discovered via `skills.external_dirs` entry added by the installer. |
| `install.sh` | Canonical installer — clones to `~/.hermes/hermes-relay/`, `pip install -e` into the hermes-agent venv, appends `skills/` to `skills.external_dirs` in `~/.hermes/config.yaml`, symlinks `plugin/` into `~/.hermes/plugins/hermes-relay`, drops `hermes-pair` shell shim into `~/.local/bin/`. Updates via `git pull` in the clone. |
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

### Release Process

See [RELEASE.md](RELEASE.md) for the full release recipe — versioning conventions, keystore setup, Play Console upload (manual + automated via `gradle-play-publisher`), GitHub release workflow, and troubleshooting.

Quick reference:
- **Version source of truth**: `gradle/libs.versions.toml` (`appVersionName`, `appVersionCode`)
- **SemVer with optional prereleases**: `v0.1.0`, `v0.1.1`, `v0.2.0-beta.1`, `v1.0.0-rc.1`
- **`appVersionCode` is monotonic** — always increment, even across prereleases (Play Console rejects collisions)
- **Build AAB locally**: `scripts/dev.bat bundle` → `app/build/outputs/bundle/release/app-release.aab`
- **Verify signing**: `keytool -list -printcert -jarfile <aab>` — must NOT show `CN=Android Debug`
- **Cut a release**: bump version → commit → `git tag vMAJOR.MINOR.PATCH` → `git push origin <tag>` → CI builds APK + AAB and creates GitHub Release
- **Required GitHub Secrets** for signed CI builds: `HERMES_KEYSTORE_BASE64`, `HERMES_KEYSTORE_PASSWORD`, `HERMES_KEY_ALIAS`, `HERMES_KEY_PASSWORD` (without these the workflow still runs but produces debug-signed artifacts that Play Console will reject)
- **Optional automated upload**: `gradlew publishReleaseBundle --track=internal` (requires `play-service-account.json` at repo root, gitignored)

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
| Relay health | `GET /health` on `plugin/relay/server.py` (default `:8767`) | — |
| Relay pairing (QR flow) | `POST /pairing/register` (loopback only) — driven by `/hermes-relay-pair` skill or `hermes-pair` shell shim | — |
| QR payload schema | `HermesPairingPayload` (see `plugin/pair.py` + `QrPairingScanner.kt`) — top-level API fields + optional `relay: { url, code }` block | Old API-only QRs still parse (relay field is nullable + `ignoreUnknownKeys = true`) |

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
