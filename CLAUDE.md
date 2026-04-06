# Hermes Relay — Claude Code Context

> Read this before touching code. Then read docs/spec.md and docs/decisions.md.

## What This Is

A native Android app for Hermes agent. Chat connects directly to the Hermes API Server. Bridge and terminal channels use a relay server over WSS. The app is Kotlin + Jetpack Compose. The server relay is Python + aiohttp.

**Current state:** MVP Phase 0 + Phase 1 complete with direct API chat. The Android app connects to the Hermes API Server (`/api/sessions/{id}/chat/stream`) for chat via HTTP/SSE. The relay server handles bridge (Phase 3) and terminal (Phase 2) via WSS. Auth uses optional Bearer token for API, pairing code for relay.

## Architecture

```
Phone (HTTP/SSE) → Hermes API Server (:8642)   [chat — direct, OpenAI-compatible]
Phone (WSS)      → Relay Server (:8767)          [bridge, terminal]
```

Chat goes directly to the API server using the Hermes Sessions API (`/api/sessions/{id}/chat/stream`) with SSE streaming. The API key (Bearer token) is optional — most local setups run without one. Terminal will go through tmux via the relay. Bridge wraps existing relay protocol. See docs/decisions.md for why.

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
| `app/src/main/kotlin/.../network/HermesApiClient.kt` | Direct HTTP/SSE client for Hermes API Server |
| `app/src/main/kotlin/.../network/ConnectionManager.kt` | WSS connection with auto-reconnect (relay) |
| `app/src/main/kotlin/.../network/ChannelMultiplexer.kt` | Envelope routing by channel (relay) |
| `app/src/main/kotlin/.../network/handlers/ChatHandler.kt` | Chat message state + streaming event processing |
| `app/src/main/kotlin/.../ui/screens/ChatScreen.kt` | Chat UI — streaming messages, tool cards |
| `relay_server/relay.py` | Relay server (bridge/terminal only) |
| `AGENTS.md` | Tool usage patterns for the `android_*` toolset |

## What NOT to Do

- **Don't use XML layouts** — Compose only
- **Don't use Gson** — kotlinx.serialization
- **Don't use Ktor for networking** — OkHttp for WebSocket
- **Don't build terminal or bridge channels yet** — Phase 2 and 3. Stubbed with `TODO`.
- **Don't use plaintext WebSocket** — `wss://` only, even in development
- **Don't put documentation in root** — long-form docs go in `docs/`
- **Don't forget DEVLOG.md** — update it

## Dev Workflow

```bash
scripts/dev.bat build      # Build debug APK
scripts/dev.bat run        # Build + install + launch + logcat
scripts/dev.bat test       # Run unit tests
scripts/dev.bat relay      # Start relay server (dev mode, no SSL)
```

Open repo root in Android Studio for Compose previews and device deployment.

## Integration Points

| Surface | Endpoint |
|---------|----------|
| WebAPI chat | `POST localhost:8642/api/sessions/{id}/chat/stream` (SSE) |
| WebAPI sessions | `GET/POST localhost:8642/api/sessions` |
| Agent profiles | Read from `~/.hermes/config.yaml` |
| Plugin tools | `android_*` via `plugin/` |

## Related Projects

- **[hermes-agent](https://github.com/NousResearch/hermes-agent)** — the agent platform (gateway, WebAPI, plugin system)
- **[ARC](https://github.com/Codename-11/ARC)** — CI/CD patterns, project conventions reference
- **[ClawPort](https://github.com/Codename-11/clawport-ui)** — web dashboard (parallel project, uses same WebAPI)
