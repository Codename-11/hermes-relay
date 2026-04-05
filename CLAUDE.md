# Hermes Companion — Claude Code Context

> Read this before touching code. Then read docs/spec.md and docs/decisions.md.

## What This Is

A native Android companion app for Hermes agent. Three multiplexed channels (chat, terminal, bridge) over a single WSS connection. The app is Kotlin + Jetpack Compose. The server relay is Python + aiohttp.

**Current state:** MVP Phase 0 + Phase 1 complete. The Android app has a working Compose scaffold with chat UI, WSS connection manager, channel multiplexer, and auth flow. The companion relay proxies chat to the Hermes WebAPI. Terminal and bridge channels are stubbed for Phase 2/3.

## Architecture

```
Phone (WSS) → Companion Relay (:8767) → Hermes WebAPI (:8642)
                                      → tmux/PTY (Phase 2)
                                      → Bridge relay (Phase 3)
```

Chat goes through WebAPI (not directly to gateway). Terminal goes through tmux. Bridge wraps existing relay protocol. See docs/decisions.md for why.

## Repository Layout

```
hermes-android/                  ← Android Studio opens this root
├── app/                         ← Android app module (Compose)
│   ├── src/main/kotlin/com/hermesandroid/companion/
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
├── companion_relay/             ← Python WSS relay server
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
- **OkHttp** for WebSocket — supports `wss://` natively
- **Single-activity** — Compose Navigation for all routing
- **Package:** `com.hermesandroid.companion`
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
| `app/src/main/kotlin/.../ui/CompanionApp.kt` | Main scaffold — bottom nav, navigation |
| `app/src/main/kotlin/.../network/ConnectionManager.kt` | WSS connection with auto-reconnect |
| `app/src/main/kotlin/.../network/ChannelMultiplexer.kt` | Envelope routing by channel |
| `app/src/main/kotlin/.../ui/screens/ChatScreen.kt` | Chat UI — streaming messages, tool cards |
| `companion_relay/relay.py` | Main relay server |
| `companion_relay/channels/chat.py` | Chat channel — SSE→WS proxy |
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
scripts/dev.bat relay      # Start companion relay (dev mode, no SSL)
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
