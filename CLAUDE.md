# Hermes Companion — Claude Code Context

> Read this before touching code. Then read docs/spec.md and docs/decisions.md.

## What You're Building

A native Android companion app for Hermes agent. Three multiplexed channels (chat, terminal, bridge) over a single WSS connection. The app is Kotlin + Jetpack Compose. The server relay is Python + aiohttp.

**Current state:** The repo has a working upstream bridge app (Ktor HTTP server, AccessibilityService) and a Python plugin (14 `android_*` tools). The MVP rewrites the Android app with Compose and adds a new companion relay server.

## MVP Deliverables

Build Phase 0 + Phase 1 from docs/spec.md:

1. **Compose scaffold** — bottom nav with 4 tabs (Chat, Terminal, Bridge, Settings), placeholder screens
2. **WSS connection manager** — OkHttp WebSocket, `wss://` only, auto-reconnect with exponential backoff
3. **Channel multiplexer** — typed envelope format: `{ channel, type, id, payload }` (see docs/spec.md § 3.2)
4. **Auth flow** — 6-char pairing code → session token, stored in EncryptedSharedPreferences
5. **Companion relay** — Python aiohttp WSS server on port 8767 (new dir: `companion-relay/`)
6. **Chat channel** — relay proxies to Hermes WebAPI SSE (`/api/sessions/{id}/chat/stream`), app shows streaming messages
7. **Chat UI** — message bubbles, streaming text, tool progress cards (collapsible), profile selector
8. **GitHub Actions** — CI workflow: lint (ktlint) → build → test → upload APK artifact

## Architecture

```
Phone (WSS) → Companion Relay (:8767) → Hermes WebAPI (:8642)
                                      → tmux/PTY (Phase 2)
                                      → Bridge relay (Phase 3)
```

Chat goes through WebAPI (not directly to gateway). Terminal goes through tmux. Bridge wraps existing relay protocol. See docs/decisions.md for why.

## Project Conventions

### File Structure
- **Root-level:** README.md, CLAUDE.md, AGENTS.md, DEVLOG.md, .gitignore
- **docs/** — spec, decisions, security, and any other long-form documentation
- **No SPEC.md or DECISIONS.md in root** — they live in docs/
- **DEVLOG.md** — update at end of each work session with what was done, what's next, blockers

### Code Style — Android (Kotlin)
- **Jetpack Compose** — no XML layouts. Material 3 / Material You.
- **kotlinx.serialization** — not Gson. Type-safe, faster.
- **OkHttp** for WebSocket — already in the project, supports `wss://` natively
- **Single-activity** — Compose Navigation for all routing
- **Package structure:** `com.hermesandroid.companion` (new namespace, distinct from upstream `com.hermesandroid.bridge`)
- **Min SDK 26, Target SDK 35**
- **Kotlin 2.0+**, JVM target 17

### Code Style — Server (Python)
- **aiohttp** for the WSS relay — async, matches existing Hermes relay patterns
- **Type hints everywhere** — Python 3.11+ syntax
- **asyncio** for concurrency — no threading
- **Structured logging** — use `logging` module, not print()

### Git
- **Commit messages:** `type: description` — e.g. `feat: add chat channel UI`, `fix: WSS reconnect race condition`
- **Branch from main** — feature branches for anything non-trivial
- **Don't modify upstream files** in `hermes-android-bridge/` unless migrating to Compose — the existing bridge code will be replaced, not patched

### Testing
- **Android:** JUnit + Compose testing for UI, MockK for mocks
- **Python:** pytest for relay tests
- **CI runs on every push** — build must pass before merge

## Key Files to Read

| File | Why |
|------|-----|
| `docs/spec.md` | Full specification — protocol, UI layouts, phases, dependencies |
| `docs/decisions.md` | Architecture decisions — framework choice, channel design, auth model |
| `docs/security.md` | Security considerations |
| `AGENTS.md` | Tool usage patterns for the `android_*` toolset |
| `hermes-android-bridge/` | Existing upstream code — reference for bridge protocol, AccessibilityService |

## What NOT to Do

- **Don't use XML layouts** — Compose only
- **Don't use Gson** — kotlinx.serialization
- **Don't use Ktor for the Android app's networking** — OkHttp for WebSocket. Ktor is in upstream code being replaced.
- **Don't build terminal or bridge channels yet** — Phase 2 and 3. Stub them in the multiplexer with `TODO`.
- **Don't use plaintext WebSocket** — `wss://` only, even in development
- **Don't put documentation in root** — long-form docs go in `docs/`
- **Don't forget DEVLOG.md** — update it

## Team Workflow (omx / Agent Team)

This project uses `ralplan` → `team` → `ralph` workflow:
1. **ralplan** — read CLAUDE.md + docs/spec.md, produce an implementation plan
2. **team** — distribute tasks across agents (one per major component)
3. **ralph** — each agent executes independently, commits to a branch

Suggested task split for MVP:
- **Agent 1:** Compose scaffold + navigation + WSS connection manager + channel multiplexer
- **Agent 2:** Companion relay (Python) — WSS server, auth, chat channel proxy
- **Agent 3:** Chat UI (Compose) — message list, streaming, tool cards, profile selector

## Integration Points

| Surface | Endpoint |
|---------|----------|
| WebAPI chat | `POST localhost:8642/api/sessions/{id}/chat/stream` (SSE) |
| WebAPI sessions | `GET/POST localhost:8642/api/sessions` |
| Agent profiles | Read from `~/.hermes/config.yaml` |
| Plugin tools | `android_*` via `hermes-android-plugin/` |

## Related Projects

- **[hermes-agent](https://github.com/NousResearch/hermes-agent)** — the agent platform (gateway, WebAPI, plugin system)
- **[ARC](https://github.com/Codename-11/ARC)** — CI/CD patterns, project conventions reference
- **[ClawPort](https://github.com/Codename-11/clawport-ui)** — web dashboard (parallel project, uses same WebAPI)
