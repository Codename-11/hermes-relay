# Hermes-Relay — Claude Code Context

> Read this before touching code. Then read docs/spec.md and docs/decisions.md.

## What This Is

A native Android app for Hermes agent. Chat connects directly to the Hermes API Server. Bridge and terminal channels use a relay server over WSS. The app is Kotlin + Jetpack Compose. The server relay is Python + aiohttp.

**Current state:** v0.1.0 (Google Play). Phase 0 + Phase 1 complete with direct API chat, session management, markdown rendering, messaging-style chat header (avatar + agent name + model subtitle), personality picker with agent name on bubbles, searchable command palette (29 gateway commands + dynamic personalities + server skills), QR code pairing, ConnectionStatusBadge (animated pulse ring), in-app analytics (Stats for Nerds with reset, peak times, tokens/msg), animated splash screen, tool display configuration, client-side message queuing (send while streaming), file attachments (images, documents, any file type via base64), **inbound media pipeline** (agent-produced screenshots/files served via relay-hosted `MediaRegistry` + opaque tokens + `FileProvider`-backed cache, Discord-style rendering for image/video/audio/pdf/text/generic via `InboundAttachmentCard`, configurable max size / auto-fetch-on-cellular / cache cap / clear-cache), **pairing + security architecture** (user-chosen session TTL at pair time via `SessionTtlPickerDialog` with 1d/7d/30d/90d/1y/never options, per-channel grants on one session token via `Session.grants`, Android Keystore session storage with TEE fallback via `SessionTokenStore`, TOFU cert pinning via `CertPinStore`, transport security badge + first-time insecure ack dialog, Tailscale detection, full Paired Devices screen with list + revoke via `GET/DELETE /sessions`, HMAC-SHA256 QR signing via `plugin/relay/qr_sign.py`), configurable limits (attachment size, message length), feature gating with Developer Options, ASCII morphing sphere animation (empty chat state + ambient mode + behind-messages background), and animation settings in Settings. The relay server handles bridge (Phase 3) and terminal (Phase 2) via WSS. Auth uses optional Bearer token for API, pairing code for relay. Relay/pairing settings are hidden in production behind Developer Options (tap version 7x to unlock).

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

**Non-standard endpoints (provided by fork OR by plugin bootstrap):**

These endpoints are not in stock upstream `gateway/platforms/api_server.py`. There are three ways a hermes-agent install can serve them:

1. **Codename-11 fork** (`feat/api-server-enhancements` branch, currently merged into `axiom`) — adds them natively in `gateway/platforms/api_server.py`. Submitted upstream as PR [#8556](https://github.com/NousResearch/hermes-agent/pull/8556).
2. **Bootstrap injection** (`hermes_relay_bootstrap/`) — installed alongside the plugin via `install.sh`, runs at Python interpreter startup (via a `.pth` file in the venv's site-packages), monkey-patches `aiohttp.web.Application` so that when `APIServerAdapter.connect()` builds its app, our extra routes are added to the same router. **Vanilla upstream + plugin = these endpoints work too.** The bootstrap deliberately does NOT inject `/api/sessions/{id}/chat/stream` — chat goes through standard `/v1/runs` instead, which has live tool events and avoids touching `_create_agent` / `run_conversation` internals.
3. **Upstream-merged** (post PR #8556) — same paths, native upstream support. The bootstrap feature-detects on route paths and no-ops in this case.

| Endpoint | Purpose | Provided by |
|----------|---------|-------------|
| `GET /api/sessions` (CRUD) | Session list/create/rename/delete/fork | Fork OR bootstrap OR upstream-merged |
| `GET /api/sessions/{id}/messages` | Conversation history | Fork OR bootstrap OR upstream-merged |
| `GET /api/sessions/search` | Full-text message search | Fork OR bootstrap OR upstream-merged |
| `POST /api/sessions/{id}/chat/stream` | Session-based SSE chat | Fork OR upstream-merged ONLY (NOT bootstrap — use `/v1/runs`) |
| `GET /api/config`, `PATCH /api/config` | Personalities + model config | Fork OR bootstrap OR upstream-merged |
| `GET /api/skills`, `/categories`, `/{name}` | Skill discovery | Fork OR bootstrap OR upstream-merged |
| `GET/POST/PATCH/DELETE /api/memory` | Memory CRUD | Fork OR bootstrap OR upstream-merged |
| `GET /api/available-models` | Provider model list | Fork OR bootstrap OR upstream-merged |

The Android client probes per-endpoint capability via `HermesApiClient.probeCapabilities()` (returns `ServerCapabilities`). When `streamingEndpoint = "auto"` (the default for new installs), `ConnectionViewModel.resolveStreamingEndpoint()` reads the capability snapshot and picks `sessions` (when the chat-stream handler is present) or `runs` (otherwise). Users can still force `sessions` or `runs` manually in Settings → Chat → Streaming endpoint.

**Tool call rendering paths:**
1. **Runs API** (`/v1/runs`) — Best for tool display. Emits `tool.started`/`tool.completed` as real SSE events → rendered as ToolProgressCards in real-time.
2. **Sessions API** (`/api/sessions/{id}/chat/stream`) — Does NOT emit structured tool events during streaming. Tool calls are stored server-side as `tool_calls` JSON on each message. On stream complete, `ChatViewModel.onCompleteCb` reloads message history via `getMessages()` → `loadMessageHistory()` to get proper message boundaries + tool call cards. This is the "session_end reload" pattern.
3. **Annotation parser** (`ChatHandler.parseAnnotationLine` + `finalizeAnnotations`) — Fallback for servers that inject inline markdown annotations (`` `💻 terminal` ``). Parses during streaming + reconciliation pass on stream end. If your Hermes version uses a different format, check `adb logcat -s HermesApiClient` for raw SSE events and update the regex.

## Key Instructions
- **Always verify upstream before assuming an endpoint exists.** Check `gateway/platforms/api_server.py` in hermes-agent. If an endpoint isn't there, document whether the bootstrap injects it (`hermes_relay_bootstrap/_handlers.py`) or if it requires the fork.
- When building features that interface with hermes-agent, reference the upstream source — not just our spec docs. Our spec may be aspirational or based on a specific server version.
- If we use a non-standard endpoint, ensure `probeCapabilities()` covers it and the auto-resolver in `ConnectionViewModel.resolveStreamingEndpoint()` (or equivalent) degrades gracefully.
- **Bootstrap maintenance:** When upstream PR #8556 merges and reaches a released hermes-agent version, the entire `hermes_relay_bootstrap/` package and its `.pth` file in `install.sh` can be deleted. The bootstrap is no-op-compatible with both fork and upstream-merged installs (feature detection by route path), so leaving it in place during the rollout window is harmless.

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
│   ├── android_tool.py          # 14 android_* tool handlers; points BRIDGE_URL at the unified relay (localhost:8767) as of Phase 3 Wave 1
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
| `hermes_relay_bootstrap/` | Runtime patch package for vanilla upstream hermes-agent. Loaded via `hermes_relay_bootstrap.pth` in the venv site-packages (dropped by `install.sh` step 2). `__init__.py` installs a `sys.meta_path` finder; `_patch.py` swaps `aiohttp.web.Application` for a subclass that intercepts `app["api_server_adapter"] = self` and triggers `_handlers.register_routes()`; `_handlers.py` ports ~14 management handlers (sessions CRUD, memory, skills, config, available-models) from the fork. Feature-detects on route paths so it no-ops on fork or upstream-merged installs. Removable in one PR once PR #8556 lands. |
| `hermes_relay_bootstrap.pth` | Single-line `.pth` file at repo root: `import hermes_relay_bootstrap`. `install.sh` copies this into the hermes-agent venv's `site-packages/` so Python's `site` module loads the bootstrap at every interpreter startup. NOT installed automatically by `pip install -e` — setuptools' data-files doesn't ship to site-packages reliably for editable installs. |
| `uninstall.sh` | Canonical uninstaller — reverses every `install.sh` step in opposite order: stops + disables systemd unit, removes shim, removes skills external_dirs entry from config.yaml (preserves other entries), removes plugin symlink + legacy stales, removes bootstrap `.pth` from site-packages, `pip uninstall hermes-relay`, removes the clone (unless `--keep-clone`). Idempotent. Never touches `~/.hermes/.env`, `state.db`, or the hermes-agent venv core. Flags: `--dry-run`, `--keep-clone`, `--remove-secret` (the last preserves QR signing identity by default). |
| `app/src/main/kotlin/.../network/HermesApiClient.kt` (capability detection) | `data class ServerCapabilities(sessionsApi, sessionsChatStream, runs, portable, healthy)` + `suspend fun probeCapabilities()`. Uses **HEAD-method probes** against `/api/sessions`, `/api/sessions/probe/chat/stream`, `/v1/runs`, `/v1/models`. Treats any non-404 status as "route exists" (200/204/401/403/405 all count). HEAD avoids hermes-agent's CORS middleware which intercepts OPTIONS preflight and returns 403 for both existing AND missing paths — verified empirically against the production gateway 2026-04-12. The result drives `streamingEndpoint = "auto"` resolution. |
| `app/src/main/kotlin/.../viewmodel/ConnectionViewModel.kt` (auto-resolver) | `resolveStreamingEndpoint(preference)` collapses `"auto"` to a concrete `"sessions"` or `"runs"` based on the latest `serverCapabilities` snapshot. Manual `"sessions"` / `"runs"` settings pass through unchanged. |
| `plugin/relay/auth.py` | PairingManager (generate + register_code), SessionManager, RateLimiter |
| `plugin/relay/config.py` | RelayConfig + PAIRING_ALPHABET (full A-Z / 0-9 as of 2026-04-11) |
| `plugin/relay/channels/terminal.py` | Phase 2 PTY-backed terminal handler |
| `plugin/relay/channels/bridge.py` | Phase 3 bridge channel handler — routes agent tool calls to the connected phone over the unified WSS relay. `BridgeHandler.handle_command(method, path, params, body)` mints a `request_id`, sends a `bridge.command` envelope, and awaits a matching `bridge.response` with 30s timeout. `handle(ws, envelope)` opportunistically latches `phone_ws` and dispatches inbound `bridge.response`/`bridge.status`. `detach_ws(ws, reason)` fails all pending futures with `ConnectionError` on phone disconnect so HTTP callers fail fast. Migrated from the legacy standalone `plugin/tools/android_relay.py` (port 8766) into the unified relay on port 8767 in Phase 3 Wave 1 (Agent bridge-server, 2026-04-12). Wire protocol is frozen — envelope fields match the legacy relay byte-for-byte. 14 HTTP routes (`/ping`, `/screen`, `/screenshot`, `/get_apps`, `/apps` legacy, `/current_app`, `/tap`, `/tap_text`, `/type`, `/swipe`, `/open_app`, `/press_key`, `/scroll`, `/wait`, `/setup`) are registered in `plugin/relay/server.py` between `# === PHASE3-bridge-server ===` markers and delegate straight through to `handle_command`. |
| `relay_server/__main__.py` | Thin shim → `plugin.relay.server.main()` — legacy `python -m relay_server` entrypoint |
| `relay_server/SKILL.md` | Hermes skill reference for relay self-setup |
| `relay_server/Dockerfile` | Container image for relay server |
| `relay_server/hermes-relay.service` | Systemd unit file for persistent deployment |
| `docs/relay-server.md` | Relay server setup, config, Docker, systemd, TLS reference |
| `app/src/main/kotlin/.../ui/components/QrPairingScanner.kt` | QR code scanner + `HermesPairingPayload` (incl. optional `relay` block with `url` + `code`) |
| `plugin/relay/media.py` | `MediaRegistry` — in-memory LRU-capped token store for inbound media (opaque tokens, 24h TTL, 500-entry cap, path sandboxing via `os.path.realpath` against allowed roots); shared `validate_media_path()` helper used by both register and by-path fetch. **`allowed_roots=None` skips the root check** (permissive mode — default for `/media/by-path` since 2026-04-11); the token path always enforces. `strict_sandbox` flag on `MediaRegistry` / `RelayConfig.media_strict_sandbox` / `RELAY_MEDIA_STRICT_SANDBOX=1` re-enables the allowlist on by-path for operators who want defense-in-depth. |
| `plugin/relay/client.py` | `register_media()` — stdlib `urllib.request` helper for host-local tools to loopback-POST to `/media/register` and receive a token |
| `plugin/relay/qr_sign.py` | HMAC-SHA256 QR payload signing — `canonicalize()`, `sign_payload()`, `verify_payload()`, `load_or_create_secret()` (host-local secret at `~/.hermes/hermes-relay-qr-secret`, 32 bytes, 0o600). `canonicalize` explicitly excludes the `sig` field so signing is idempotent. `allow_nan=False` so accidentally signing `math.inf` crashes loudly. |
| `plugin/relay/auth.py` | Session/PairingMetadata/PairingManager/SessionManager/RateLimiter with full pairing architecture — per-channel grants on Session, `math.inf` for never-expire (serializes as `null`), `_materialize_grants` clamps grants to session lifetime, `RateLimiter.clear_all_blocks()` called on successful `/pairing/register` to fix "phone rate-limited after relay restart". `DEFAULT_TERMINAL_CAP=30d`, `DEFAULT_BRIDGE_CAP=7d`. |
| `plugin/relay/server.py` (additions) | `handle_media_register` + `handle_media_get` + `handle_media_by_path`; `handle_pairing_register` now accepts `ttl_seconds` / `grants` / `transport_hint` metadata and clears rate-limit blocks on success; `handle_pairing_approve` (Phase 3 bidirectional pairing stub); `handle_sessions_list` (GET /sessions — tokens masked to 8-char prefix); `handle_sessions_revoke` (DELETE /sessions/{prefix}, self-revoke flagged); `_detect_transport_hint` helper sniffs `request.transport.get_extra_info('ssl_object')`; `auth.ok` payload now includes `expires_at` / `grants` / `transport_hint` with `math.inf → null`. |
| `plugin/tools/android_tool.py::android_screenshot` | First consumer of `register_media()` — emits `MEDIA:hermes-relay://<token>` on success, falls back to bare `MEDIA:<path>` on relay unavailability |
| `plugin/tools/android_navigate.py` | Phase 3 Tier 4 `android_navigate(intent, max_iterations=5)` tool — vision-driven close-the-loop navigation. One screenshot per iteration via the bridge `/screenshot` endpoint (never continuous capture), vision model picks next action, tool dispatches to `/tap_text`/`/tap`/`/type`/`/swipe`/`/press_key` on the same Wave 1 bridge relay as `android_tool.py`. Returns a structured trace `[{step, action, params, screenshot_token, reasoning, result}, …]` on success + every failure envelope. Default cap 5, hard-clamped to `ABSOLUTE_MAX_ITERATIONS=20`. LLM integration uses a `call_vision_model` injection point (production swaps `_default_vision_model` for a real Anthropic/OpenAI vision client; tests patch it; smoke runs can set `HERMES_NAVIGATE_STUB_REPLY`). Until a real client is wired up, calling against a live phone returns `{"status": "error", "reason": "llm_gap", ...}` instead of crashing. |
| `plugin/tools/android_navigate_prompt.py` | Prompt template + response parser for `android_navigate`, kept separate so the parser is unit-testable without importing `requests`. Response format is `ACTION: <verb>\nPARAMS: <json>\nREASON: <sentence>`. Parser is case-insensitive, tolerates trailing punctuation on the verb, allows `done` to omit PARAMS, enforces per-verb shape (`tap` needs `(x,y)` ints or `node_id` string, `swipe` requires valid direction, etc.), and surfaces structural failures as `ParsedAction(action="error", reasoning=<why>)` so the loop can bail cleanly instead of calling the bridge with garbage. |
| `plugin/tests/test_android_navigate.py` | Stdlib `unittest` suite for `android_navigate` (35 tests) — every valid verb + malformed-input grid for the parser, plus loop tests for success/iteration-cap/screenshot-failure/parse-error/action-failure/llm-gap via `unittest.mock`. Uses no `responses`/`pytest` so it runs via `python -m unittest plugin.tests.test_android_navigate` without tripping the existing `conftest.py`. |
| `plugin/pair.py` | QR payload builder + CLI shim (`--ttl` / `--grants` flags, parses durations + grant specs). `build_payload(sign=True)` auto-bumps `hermes: 1 → 2` when any v2 field is present and attaches HMAC signature via `qr_sign`. `read_relay_config` detects TLS via `RELAY_SSL_CERT` for the transport hint. |
| `app/src/main/kotlin/.../auth/SessionTokenStore.kt` | Session token storage abstraction — `KeystoreTokenStore` (StrongBox-preferred via `setRequestStrongBoxBacked` on Android 9+, best-effort via `tryCreate`) + `LegacyEncryptedPrefsTokenStore` (TEE-backed `EncryptedSharedPreferences` fallback). One-shot lossless migration on first launch post-upgrade. |
| `app/src/main/kotlin/.../auth/CertPinStore.kt` | TOFU cert pinning — SHA-256 SPKI fingerprints per `host:port` in DataStore. `recordPinIfAbsent` on first successful wss handshake; `buildPinnerSnapshot` produces an OkHttp `CertificatePinner`. Wiped on explicit re-pair via `applyServerIssuedCodeAndReset`. Plain `ws://` short-circuits pinning. |
| `app/src/main/kotlin/.../auth/PairedSession.kt` | `PairedSession` state (token, expiresAt, grants, transportHint, firstSeen, hasHardwareStorage) + `PairedDeviceInfo` wire model for `GET /sessions` responses. |
| `app/src/main/kotlin/.../auth/AuthManager.kt` | Wires `SessionTokenStore` + `CertPinStore`, exposes `currentPairedSession: StateFlow<PairedSession?>`, parses new auth.ok fields (`expires_at` / `grants` / `transport_hint`), injects `ttl_seconds` + `grants` into pairing-mode auth envelope. `applyServerIssuedCodeAndReset(code, relayUrl?)` wipes the TOFU pin for the target host. |
| `app/src/main/kotlin/.../data/PairingPreferences.kt` | DataStore keys for the pairing UX: `pair_ttl_seconds`, `insecure_ack_seen`, `insecure_reason`, `tofu_pins`. |
| `app/src/main/kotlin/.../util/TailscaleDetector.kt` | Informational Tailscale detection via `NetworkInterface` scan (`tailscale0` + `100.64.0.0/10`) + relay-URL host check (`.ts.net` / CGNAT). Purely informational — does NOT auto-change defaults. |
| `app/src/main/kotlin/.../ui/components/SessionTtlPickerDialog.kt` | Compose TTL picker — 1d / 7d / 30d / 90d / 1y / Never radio list, inline warning under Never, `defaultTtlSeconds(qrTtl, transportHint, tailscale)` helper (QR operator value wins → wss/Tailscale = 30d → plain ws = 7d → unknown = 30d). Always opens on QR scan so user confirms the TTL. |
| `app/src/main/kotlin/.../ui/components/TransportSecurityBadge.kt` | Three-state badge (🔒 secure / 🔓 insecure-with-reason / 🔓 insecure-unknown), three sizes (Chip / Row / Large). Rendered in Settings Connection, Session info sheet, and Paired Devices cards. |
| `app/src/main/kotlin/.../ui/components/InsecureConnectionAckDialog.kt` | First-time insecure-mode consent dialog — plain-language threat model + reason picker (LAN only / Tailscale or VPN / Local dev only). Persists ack + reason but does NOT gate the toggle — operator intent is the trust model. |
| `app/src/main/kotlin/.../ui/screens/PairedDevicesScreen.kt` | Full-screen list of all paired devices with metadata (name + ID, transport badge, expiry, grant chips, current badge, revoke button). Pulls from `RelayHttpClient.listSessions()`. Revoke via `RelayHttpClient.revokeSession(tokenPrefix)`. Self-revoke wipes local state + redirects to pair. |
| `app/src/main/kotlin/.../network/ConnectionManager.kt` | Takes optional `CertPinStore` — rebuilds `OkHttpClient` on every connect with fresh `CertificatePinner` snapshot so re-pair pin wipes take effect immediately. Records peer cert fingerprint in `onOpen` on TLS handshake. |
| `app/src/main/kotlin/.../network/RelayHttpClient.kt` | OkHttp client for `GET /media/{token}`, `GET /media/by-path`, plus **`listSessions()`** (GET /sessions → `List<PairedDeviceInfo>`, 404 → empty list so UI degrades gracefully), **`revokeSession(tokenPrefix)`** (DELETE /sessions/{prefix}, 404 → success), **`extendSession(tokenPrefix, ttlSeconds?, grants?)`** (PATCH /sessions/{prefix} — restarts the clock from now), and **`probeHealth(relayUrl)`** (unauthenticated GET /health with 3s timeout, parses response body, validates it looks like a hermes-relay via `status == "ok"` + non-blank `version` — backs the Settings → Manual configuration → Save & Test button). |
| `plugin/relay/voice.py` | Voice endpoints on the relay — `VoiceHandler` with `handle_transcribe` (multipart audio → text), `handle_synthesize` (JSON text → audio/mpeg file), `handle_voice_config` (provider availability + current settings). Imports `tools.tts_tool.text_to_speech_tool` and `tools.transcription_tools.transcribe_audio` lazily inside each handler (both sync functions wrapped in `asyncio.to_thread`). Bearer auth via local `_require_bearer_session` helper, matching `/media/*` pattern. Reads provider config from `~/.hermes/config.yaml` internally via the upstream tools — relay doesn't need to pass provider/voice arguments. |
| `app/src/main/kotlin/.../audio/VoiceRecorder.kt` | `MediaRecorder` wrapper — MPEG_4/AAC output to `.m4a` at 16kHz/64kbps mono. Exposes `amplitude: StateFlow<Float>` polled at ~60fps from `mediaRecorder.maxAmplitude` with **perceptual curve** (noise-floor subtraction + speech-ceiling rescale + sqrt boost) so normal conversation (~raw 3000/32767) maps to a visually reactive ~0.48 rather than linear 0.09. API 31+ uses context-ctor, legacy path suppressed. |
| `app/src/main/kotlin/.../audio/VoicePlayer.kt` | `MediaPlayer` + `android.media.audiofx.Visualizer` wrapper for TTS playback. Exposes `amplitude: StateFlow<Float>` driven by 8-bit PCM RMS with NaN guard (`Float.coerceIn` silently passes NaN per IEEE 754). Visualizer construction is in try/catch — some OEM devices refuse even with `MODIFY_AUDIO_SETTINGS`, falls back to flat-zero amplitude without crashing. `suspend fun awaitCompletion()` via `suspendCancellableCoroutine`. |
| `app/src/main/kotlin/.../audio/VoiceSfxPlayer.kt` | Pre-synthesized 200 ms PCM chimes (ascending 440→660 Hz enter, descending mirror exit) played via `AudioTrack.MODE_STATIC` with `USAGE_ASSISTANT`. Phase-accumulated sweep (no chirp artifacts), 15 ms attack + 40 ms half-cosine decay, peak 0.35 of full-scale. Failed AudioTrack construction becomes a silent no-op — voice mode still works, just no chime. Wired into `VoiceViewModel.enterVoiceMode()` / `exitVoiceMode()` via a 5th `initialize()` parameter. |
| `app/src/main/kotlin/.../network/RelayVoiceClient.kt` | OkHttp client for `/voice/*` endpoints. `transcribe(File): Result<String>` (multipart POST), `synthesize(String): Result<File>` (JSON POST → audio bytes streamed to `cacheDir/voice_tts_<ts>.mp3`), `getVoiceConfig(): Result<VoiceConfig>`. Mirrors `RelayHttpClient`'s ctor shape — same bearer-auth pattern, same error handling. |
| `app/src/main/kotlin/.../voice/VoiceBridgeIntentHandler.kt` | **Phase 3 Wave 2 voice-intents**: interface + `sealed class IntentResult { Handled, NotApplicable }` for routing transcribed voice utterances to the bridge channel instead of chat. Flavor-specific impls live in `app/src/googlePlay/.../voice/VoiceBridgeIntentHandlerImpl.kt` (no-op) and `app/src/sideload/.../voice/VoiceBridgeIntentHandlerImpl.kt` (real keyword classifier). Both flavors export `createVoiceBridgeIntentHandler(multiplexer)` in `VoiceBridgeIntentFactory.kt`. `VoiceViewModel` only ever sees the interface — no reflection, flavor picked at build time. v1 confirmation model: destructive intents (SMS) speak a confirmation + start a 5s countdown coroutine, cancellable via `VoiceViewModel.cancelPendingBridgeIntent()`. Full conversational confirmation is a Wave 3 follow-up. |
| `app/src/sideload/kotlin/.../voice/VoiceIntentClassifier.kt` | **Phase 3 Wave 2 voice-intents** (sideload only): regex-based keyword classifier for phone-control intents. Six patterns: SendSms (`text <contact> saying <body>` / `text <contact>: <body>` / no-separator fallback), OpenApp (`open|launch|start <app>`), Tap (`tap|press|click(?: on)? <target>`), Scroll (`scroll up|down|top|bottom`), Back (`(go|navigate)? back`), Home (`(press|go)? home`). Filler words (`hey`, `okay`, `please`, `can you`, …) are stripped before matching. Design bias: false negatives > false positives — utterances that don't cleanly split into a structured action fall through to chat via `IntentResult.NotApplicable`. |
| `app/src/main/kotlin/.../viewmodel/VoiceViewModel.kt` | Voice turn state machine — `Idle / Listening / Transcribing / Thinking / Speaking / Error`. Sentence-boundary detection via top-level `extractNextSentence(StringBuilder)` helper (whitespace-lookahead for abbreviations like `e.g.`, `MIN_SENTENCE_LEN=6`). TTS queue as `Channel<String>` with dedicated consumer coroutine (while+tryReceive loop — `maybeAutoResume` only fires when queue is actually drained, NOT after every sentence, so waveform stays alive through multi-sentence playback). **`ignoreAssistantId`** captures the pre-send last-assistant-id so the stream observer doesn't replay the previous turn's response as TTS. **`errorEvents: SharedFlow<HumanError>`** one-shot events from `surfaceError(t, context)` → snackbar + overlay banner. **`applyEnvelope`** attack-fast/release-slow follower (0.75/0.10 at 60Hz) replaces the old Compose spring. **Integration pattern**: observes `chatVm.messages: StateFlow<List<ChatMessage>>` directly (diffs last streaming message's content length) rather than adding a callback to `ChatViewModel` — zero churn on chat code. Transcribed user text routes through `chatVm.sendMessage(text)` so voice utterances appear as regular user messages in chat history. |
| `app/src/main/kotlin/.../ui/components/VoiceModeOverlay.kt` | Full-screen voice-mode UI — top bar with interaction-mode dropdown + close X, MorphingSphere at 60% height (voiceMode=true), VoiceWaveform, scrollable response text, mic button with `pulseScale` from amplitude + hold-gesture `pointerInput` + haptics. Speaking + Listening states both use vivid red `Color(0xFFE53935)` stop button. Optional `errorEvents: SharedFlow<HumanError>?` parameter collects classified errors into `LocalSnackbarHost`. Maps `VoiceState` → `SphereState`. |
| `app/src/main/kotlin/.../ui/components/VoiceWaveform.kt` | Reactive layered-sine-wave visualizer mounted below the MorphingSphere. Three overlapping sine waves at co-prime frequencies (1.2/2.1/3.4) with amplitude-driven phase velocity via `rememberAmplitudeDrivenPhases` (`withFrameNanos` ticker, base durations 2000/1400/950 ms, speed up to 3.5× at peak amplitude via `PHASE_AMP_BOOST`). Pill-edge merge via dual technique: geometric `sin(π·t)` taper forces wave to centerY at both endpoints + `BlendMode.DstIn` horizontal gradient mask inside `saveLayer`. Color-keyed to `VoiceState` (blue/purple Listening, green/teal Speaking). No Compose spring — amplitude comes pre-smoothed from the ViewModel's envelope follower. |
| `app/src/main/kotlin/.../ui/screens/VoiceSettingsScreen.kt` | Voice settings sub-screen — interaction mode radio list, silence threshold slider (1–10s), Auto-TTS switch ("coming soon"), TTS/STT provider info from `/voice/config`, language picker (stored to `VoicePreferences` but relay auto-detects for now), Test Voice button calls `VoiceViewModel.testVoice(sample)`. |
| `app/src/main/kotlin/.../data/VoicePreferences.kt` | DataStore repo mirroring `MediaSettings.kt`. Keys: `voice_interaction_mode` (tap/hold/continuous), `voice_silence_threshold_ms` (default 3000), `voice_auto_tts` (default false, reserved), `voice_language` (default ""). |
| `app/src/main/kotlin/.../ui/screens/BridgeScreen.kt` | Phase 3 bridge-ui rewrite — four-card control surface (master toggle, status, permission checklist, activity log) + inert Safety placeholder. Owns a `BridgeViewModel` via default `viewModel()` param. Re-probes permission state on `Lifecycle.Event.ON_RESUME` so returning from Android Settings flips the a11y row from red to green without navigation churn. |
| `app/src/main/kotlin/.../viewmodel/BridgeViewModel.kt` | Phase 3 bridge-ui — AndroidViewModel for BridgeScreen. Exposes `masterToggle`/`bridgeStatus`/`permissionStatus`/`activityLog` StateFlows. Reads `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` + `Settings.canDrawOverlays` + `enabled_notification_listeners`. Seeds `BridgeStatus` from `PowerManager.isInteractive` + `BatteryManager.BATTERY_PROPERTY_CAPACITY`. **accessibility-handoff stubs** marked with `TODO(accessibility-handoff)`: replace `_bridgeStatus` MutableStateFlow with the real `HermesAccessibilityService` status flow; confirm `A11Y_SERVICE_CLASS` FQCN; wire accessibility's command dispatcher to call `recordActivity(entry)`. |
| `app/src/main/kotlin/.../data/BridgePreferences.kt` | Phase 3 bridge-ui — DataStore repo backing `BridgeViewModel`. Keys: `bridge_master_enabled` (Boolean) + `bridge_activity_log` (JSON-serialized `List<BridgeActivityEntry>` capped at `MAX_LOG_ENTRIES=100`). `appendEntry` dedupes by id so Pending → Success/Failed transitions replace in place. Lenient `Json` for forward-compat. |
| `app/src/main/kotlin/.../ui/components/BridgeMasterToggle.kt` | Phase 3 bridge-ui — headline "Allow Agent Control" Switch card. Inline device/battery/screen/current-app rows, Play-review-required explanation dialog behind the info icon, `accessibilityGranted` gate blocks enabling before a11y permission is granted. |
| `app/src/main/kotlin/.../ui/components/BridgeStatusCard.kt` | Phase 3 bridge-ui — standalone bridge status card. Reuses `ConnectionStatusBadge` for the pulsing connected/disconnected dot. Separate from `BridgeMasterToggle` so Agent safety-rails can rearrange cards without losing state. |
| `app/src/main/kotlin/.../ui/components/BridgePermissionChecklist.kt` | Phase 3 bridge-ui — four-row permission checklist (Accessibility / Screen Capture / Overlay / Notification Listener). Tap-to-open Android Settings via `ACTION_ACCESSIBILITY_SETTINGS`, `ACTION_MANAGE_OVERLAY_PERMISSION`, `enabled_notification_listeners`. Each launcher wrapped in `runCatching` for OEM-skin safety. Green check vs. red empty-circle status icons. |
| `app/src/main/kotlin/.../ui/components/BridgeActivityLog.kt` | Phase 3 bridge-ui — scrollable activity log. `LazyColumn` bounded at `heightIn(max = 320.dp)` with tap-to-expand rows showing full timestamp, status, result text, and optional screenshot token. `java.time.DateTimeFormatter` for `HH:mm:ss` / `yyyy-MM-dd HH:mm:ss`. Status icons: hourglass/check/error/block for Pending/Success/Failed/Blocked. |
| `app/src/main/kotlin/.../ui/components/MorphingSphere.kt` | ASCII morphing sphere — 3D lit character sphere. **New for voice mode**: `SphereState.Listening` (soft blue/purple, subtle wobble with `voiceAmplitude`) and `SphereState.Speaking` (vivid green/teal, dramatic core-warmth pulse with amplitude, data ring spin up to 4× on peak). New parameters `voiceAmplitude: Float = 0f` and `voiceMode: Boolean = false` (both defaulted — existing call sites unchanged). Radius scale capped at 1.08× in voiceMode — `baseRadius` is already 0.60× half-extent and the data ring at 1.55× would overflow past 1.1×. Three `@Preview` functions for Listening, Speaking-low, Speaking-peak. |
| `app/src/main/kotlin/.../util/RelayErrorClassifier.kt` | `classifyError(Throwable?, context: String?) → HumanError(title, body, retryable, actionLabel)`. Single when-branch classifier — UnknownHostException → ConnectException → SocketTimeoutException → SSLException → SecurityException → IllegalStateException → IOException (message-scan for HTTP status 401/403/404/413/500/503) → default. Branch order is load-bearing (SSLException extends IOException). Context tags (`transcribe`, `synthesize`, `voice_config`, `record`, `pair`, `save_and_test`, `media_fetch`, `send_message`) shape the title and 404 body. Used by VoiceViewModel, ChatViewModel, ConnectionSettingsScreen. |
| `app/src/main/kotlin/.../util/MediaCacheWriter.kt` | `cacheDir/hermes-media/` writer — LRU eviction by mtime, MIME→extension map, returns `FileProvider` `content://` URIs |
| `app/src/main/kotlin/.../accessibility/HermesAccessibilityService.kt` | Phase 3 accessibility — `AccessibilityService` subclass. `@Volatile instance` singleton set on `onServiceConnected` / cleared on unbind, so `BridgeCommandHandler` can reach the live service without DI. Caches foregrounded package from `TYPE_WINDOW_STATE_CHANGED`. Master enable lives in DataStore (`bridge_master_enabled`). `snapshotRoot()` wraps `rootInActiveWindow`. |
| `app/src/main/kotlin/.../accessibility/ScreenReader.kt` | Phase 3 accessibility — UI tree → `ScreenContent(rootBounds, nodes[], truncated)` via `@Serializable`. Walks `AccessibilityNodeInfo` tree capped at `MAX_NODES=512`, collects text/contentDescription/bounds/class/viewId + clickable/scrollable/editable flags. `findNodeBoundsByText(root, needle)` for `/tap_text` and `findFocusedInput(root)` for `/type`. Recycles child nodes in per-iteration `try/finally`. |
| `app/src/main/kotlin/.../accessibility/ActionExecutor.kt` | Phase 3 accessibility — `tap`/`tapText`/`swipe`/`scroll` via `GestureDescription` wrapped in `suspendCancellableCoroutine` so suspend form actually waits for `GestureResultCallback.onCompleted`. `typeText` uses `ACTION_SET_TEXT`. `pressKey` maps curated string vocab to `AccessibilityService.GLOBAL_ACTION_*` (no raw KeyEvent codes — no arbitrary injection). `wait(ms)` clamped to 15s. Returns `ActionResult(ok, data, error)`. |
| `app/src/main/kotlin/.../accessibility/ScreenCapture.kt` | Phase 3 accessibility — `MediaProjection` → `VirtualDisplay` → `ImageReader` → PNG bytes → multipart upload to `POST /media/upload` on the relay. Crops `rowStride` padding before `Bitmap.copyPixelsFromBuffer`. 2.5s capture timeout. Co-located `MediaProjectionHolder` singleton holds the per-session grant; Bridge UI (Agent bridge-ui) is responsible for the `ActivityResultLauncher` flow calling `MediaProjectionHolder.onGranted(resultCode, data)`. **Blocked on bridge-server: `POST /media/upload` endpoint doesn't exist yet** — current `/media/register` is loopback-only + path-based, phone has no shared filesystem. |
| `app/src/main/kotlin/.../accessibility/BridgeStatusReporter.kt` | Phase 3 accessibility — coroutine emitting `bridge.status` envelopes every 30s with `screen_on` (`PowerManager.isInteractive`), `battery` (`BatteryManager.BATTERY_PROPERTY_CAPACITY` + sticky-intent fallback), `current_app` (from service singleton), `accessibility_enabled` (true when service instance non-null). Owned + started by `ConnectionViewModel`. |
| `app/src/main/kotlin/.../network/handlers/BridgeCommandHandler.kt` | Phase 3 accessibility — routes inbound `bridge.command` envelopes to `ActionExecutor` and emits `bridge.response`. Paths: `/ping`, `/tap`, `/tap_text`, `/type`, `/swipe`, `/scroll`, `/press_key`, `/wait`, `/screen`, `/screenshot`, `/current_app`. Gates everything except `/ping` + `/current_app` on master-enable; returns 503 if service not connected, 403 if soft master off. `/screen` serializes `ScreenContent` via `Json.encodeToJsonElement`. **Phase 3 safety-rails additions:** optional `safetyManager: BridgeSafetyManager?` constructor arg runs the Tier 5 three-stage check on every command — blocklist (currentApp vs DataStore) → destructive-verb confirmation (for `/tap_text` + `/type`, suspend-awaits the overlay modal) → reschedule auto-disable timer. Blocked packages return 403 `{"error": "blocked package <name>"}`, denied confirmations return 403 `{"error": "user denied destructive action", "reason": "confirmation_denied_or_timeout"}`. |
| `app/src/main/kotlin/.../data/BridgeSafetyPreferences.kt` | Phase 3 safety-rails — DataStore-backed Tier 5 preferences. Keys: `bridge_blocklist` (JSON sorted list of package names, seeded with ~30 banking/payments/password-manager/2FA defaults via `DEFAULT_BLOCKLIST`), `bridge_destructive_verbs` (JSON sorted list, default `send`/`pay`/`delete`/`transfer`/`confirm`/`submit`/`post`/`publish`/`buy`/`purchase`/`charge`/`withdraw`), `bridge_auto_disable_minutes` (Int, default 30, clamped 5..120), `bridge_status_overlay_enabled` (Bool, default false), `bridge_confirmation_timeout_seconds` (Int, default 30, clamped 10..60). Sentinel `bridge_safety_initialized` distinguishes "user cleared blocklist" from "never touched" so defaults survive reinstall but respect user edits. |
| `app/src/main/kotlin/.../bridge/BridgeSafetyManager.kt` | Phase 3 safety-rails — central enforcement point for Tier 5 safety. Process-singleton installed at `ConnectionViewModel` init time. Methods: `checkPackageAllowed(pkg)` (blocklist gate), `requiresConfirmation(method, text)` + `awaitConfirmation(method, text)` (destructive verb gate — suspends on `CompletableDeferred<Boolean>` under `withTimeout`, fails-closed on missing overlay permission), `rescheduleAutoDisable()` + `cancelAutoDisable()` (coroutine-owned idle timer — `delay(minutes.toMillis())` rather than WorkManager since androidx.work is not in the classpath). Exposes `settings: StateFlow<BridgeSafetySettings>` + `autoDisableAtMs: StateFlow<Long?>` for the Bridge screen countdown. Word-boundary regex verb matching via `\\b${Regex.escape(verb)}\\b`. |
| `app/src/main/kotlin/.../bridge/BridgeForegroundService.kt` | Phase 3 safety-rails — persistent "Hermes agent has device control" foreground service. `foregroundServiceType=specialUse` on Android 14+ with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` justification in the manifest. Two notification actions: **Disable** (service re-entry → `HermesAccessibilityService.setMasterEnabled(ctx, false)`), **Settings** (opens `MainActivity`; TODO to wire a deep-link extra for `BridgeSafetySettingsScreen`). Lifecycle driven entirely from `BridgeViewModel.init` — a `masterToggle.distinctUntilChanged().collect {}` calls `start()`/`stop()` companion methods. |
| `app/src/main/kotlin/.../bridge/BridgeStatusOverlay.kt` | Phase 3 safety-rails — `WindowManager` overlay host. One singleton per process; implements `ConfirmationOverlayHost` so `BridgeSafetyManager` can reach both the destructive-verb modal and the optional floating status chip through the same slot. `TYPE_APPLICATION_OVERLAY` on API 26+, fallback to `TYPE_PHONE` below. Chip is `FLAG_NOT_FOCUSABLE` (taps fall through); modal is `FLAG_DIM_BEHIND` full-screen (intercepts touches). `ComposeView` attachments are wired to a synthetic `OverlayLifecycleOwner` that reports always-RESUMED + implements `ViewModelStoreOwner` — Compose's recomposer refuses to run without a `ViewTreeLifecycleOwner`. `SavedStateRegistryOwner` deliberately skipped (overlay content uses `remember`, not `rememberSaveable`). |
| `app/src/main/kotlin/.../bridge/AutoDisableWorker.kt` | Phase 3 safety-rails — "turn the bridge off after idle" unit of work. NOT a real `androidx.work.CoroutineWorker` (androidx.work isn't in the classpath) but shaped like one: single suspend `run()` method. Flips master toggle via `HermesAccessibilityService.setMasterEnabled(ctx, false)` and posts a one-shot "Bridge auto-disabled" notification through `NotificationManagerCompat` on its own `bridge_auto_disable` channel. Skips the notify() call when `POST_NOTIFICATIONS` isn't granted on Android 13+. Upgrade path to real WorkManager documented at the top of the file. |
| `app/src/main/kotlin/.../ui/screens/BridgeSafetySettingsScreen.kt` | Phase 3 safety-rails — Compose settings sub-screen for Tier 5. Five sections: blocklist (searchable LazyColumn of installed apps via `PackageManager.queryIntentActivities(CATEGORY_LAUNCHER)` unioned with `DEFAULT_BLOCKLIST` entries, checkboxes), destructive verbs (input field + chip list, word-boundary matching), auto-disable timer slider 5..120 min, status-overlay switch (walks the user through `ACTION_MANAGE_OVERLAY_PERMISSION` on first enable), confirmation timeout slider 10..60 s. Re-probes `Settings.canDrawOverlays` on `Lifecycle.Event.ON_RESUME`. |
| `app/src/main/kotlin/.../ui/components/DestructiveVerbConfirmDialog.kt` | Phase 3 safety-rails — confirmation modal content rendered inside the `BridgeStatusOverlay` ComposeView. NOT a Compose `Dialog` (those require an Activity window, we're in a `WindowManager` overlay). Shows the method name + flagged verb + full payload text so the user isn't guessing what they're allowing. Red "Allow" + outlined "Deny" — Deny is the safe default button placement (left). Same file also hosts `BridgeStatusOverlayChip` (the small floating "Hermes active" pill). Three `@Preview` functions for `/tap_text`, `/type`, and the chip. |
| `app/src/main/kotlin/.../ui/components/BridgeSafetySummaryCard.kt` | Phase 3 safety-rails — replaces the inert `SafetyPlaceholderCard` in `BridgeScreen`. Reads `BridgeSafetyManager.peek()?.settings` + `.autoDisableAtMs` and displays blocklist count / destructive-verb count / countdown timer (`in MM:SS` when active, else `N min idle`). `LaunchedEffect` ticker recomposes every 1s while the countdown is active. Tap → navigate to `BridgeSafetySettingsScreen`. |
| `app/src/main/kotlin/.../data/MediaSettings.kt` | DataStore-backed settings (max inbound size, auto-fetch threshold [persisted-not-enforced], auto-fetch on cellular, cached media cap) |
| `app/src/main/kotlin/.../ui/components/InboundAttachmentCard.kt` | Discord-style attachment card — dispatches by `(AttachmentState × AttachmentRenderMode)`; images inline, video/audio/pdf/text/generic as tap-to-open via `ACTION_VIEW` + `FLAG_GRANT_READ_URI_PERMISSION`. Handles outbound attachments too (default `state=LOADED`). |
| `app/src/main/res/xml/file_provider_paths.xml` | FileProvider path config — `<cache-path name="hermes-media" path="hermes-media/"/>` |
| `app/src/main/AndroidManifest.xml` | Declares `androidx.core.content.FileProvider` with authority `${applicationId}.fileprovider` |
| `plugin/pair.py` | QR pairing implementation — probes local relay, pre-registers pairing code via `/pairing/register`, embeds relay URL + code in QR (pure-Python, uses segno). Invoked via `python -m plugin.pair`, wrapped by the `hermes-pair` shell shim and the `hermes-relay-pair` skill. |
| `plugin/cli.py` | Registers plugin CLI sub-commands via the v0.8.0 plugin CLI API. **Note:** the top-level `hermes pair` sub-command is not currently reachable — upstream `hermes_cli/main.py` only reads `plugins.memory.discover_plugin_cli_commands()` and never consults the generic plugin CLI dict. Use `/hermes-relay-pair` (skill) or `hermes-pair` (shell shim) instead. |
| `skills/devops/hermes-relay-pair/SKILL.md` | `/hermes-relay-pair` slash command — canonical category layout (`devops`), matches `metadata.hermes.category` frontmatter. Discovered via `skills.external_dirs` entry added by the installer. |
| `plugin/relay/_env_bootstrap.py` | `load_hermes_env() → list[Path]` — loads `~/.hermes/.env` into `os.environ` before the relay server imports anything that reads env vars. Prefers `hermes_cli.env_loader.load_hermes_dotenv` when importable, falls back to direct `python-dotenv`, silent no-op in stripped containers. Called from both `plugin/relay/__main__.py` and `relay_server/__main__.py` so both entry points bootstrap identically. This is why neither `hermes-relay.service` nor `hermes-gateway.service` carries an `EnvironmentFile=` directive. |
| `install.sh` | Canonical installer — 6 steps + 1 sub-step. [1] clone repo, [2] `pip install -e`, [3] symlink plugin, [4] register skills in `config.yaml`, [5] write three shell shims (`hermes-pair`, `hermes-status`, `hermes-relay-update`), [6] systemd user unit (optional — skipped on macOS/WSL-without-systemd/containers, or via `$HERMES_RELAY_NO_SYSTEMD`), [6b] OFFER (don't force) hermes-gateway restart so re-imported plugin tools take effect. Idempotent: the relay-restart path uses explicit `systemctl --user restart` instead of `enable --now` (the latter is a no-op on already-active services and was the source of the 2026-04-12 "install ran clean but relay is still on stale code" debug rabbit hole). The canonical update flow is `hermes-relay-update` (a thin shim that re-runs the curl pipe) or the curl pipe itself — both are equivalent. |
| `plugin/pair.py::register_code_command` | Manual fallback for pre-registering an arbitrary 6-char pairing code with the relay without going through QR rendering. Invoked via `hermes-pair --register-code ABCD12 [--ttl 30d --grants chat:never,bridge:7d]`. Use case: phone is the only device with a camera, host is SSH-only. The phone's app-side "Manual pairing code (fallback)" card in Settings → Connection generates a code, the user types it into this CLI on the host, then taps Connect in the app. The fallback path in `AuthManager.authenticate()` sends `_pairingCode.value` as the `pairing_code` field when no `serverIssuedCode` is set. Tests: `plugin/tests/test_register_code.py` (25 stdlib unittest cases). |
| `~/.local/bin/hermes-relay-update` | Discoverable shell shim for "update Hermes-Relay". Two-line wrapper around the canonical curl pipe (`curl -fsSL .../install.sh \| bash -s -- "$@"`) — re-fetches the latest install.sh on every invocation, so improvements to install.sh itself take effect immediately. Forwards args via `bash -s --`. Honors `HERMES_RELAY_RESTART_GATEWAY=1` / `HERMES_RELAY_NO_RESTART_GATEWAY=1` env vars naturally. |
| `app/build.gradle.kts` (Phase 3) | `flavorDimensions += "track"` + `googlePlay` / `sideload` product flavors. `sideload` gets `applicationIdSuffix = ".sideload"` + `versionNameSuffix = "-sideload"` so both tracks coexist on the same device; `googlePlay` keeps the canonical applicationId for clean Play Store upgrades from v0.2.0. |
| `app/src/googlePlay/AndroidManifest.xml` | Google Play flavor manifest overlay — declares `BridgeAccessibilityService` with the conservative use-case description (`@string/a11y_description_googleplay`) and the conservative `@xml/accessibility_service_config`. Merged onto `app/src/main/AndroidManifest.xml` by AGP at build time. |
| `app/src/googlePlay/res/xml/accessibility_service_config.xml` | Conservative a11y config — `typeWindowStateChanged\|typeWindowContentChanged\|typeViewClicked` event subset, `flagDefault` only, no gestures. Targeted at Play Store policy review. |
| `app/src/googlePlay/res/values/strings.xml` | `a11y_description_googleplay` — "notifications / summarize / reply with confirmation" language. Reviewed by Play Store policy. |
| `app/src/sideload/AndroidManifest.xml` | Sideload flavor manifest overlay — same `BridgeAccessibilityService` reference, full-capability `@string/a11y_description_sideload` description. Not shipped through Google Play. |
| `app/src/sideload/res/xml/accessibility_service_config.xml` | Full a11y config — `typeAllMask`, `flagRetrieveInteractiveWindows\|flagReportViewIds\|flagRequestTouchExplorationMode`, `canPerformGestures="true"`. |
| `app/src/sideload/res/values/strings.xml` | `a11y_description_sideload` — full agent-control language (voice + vision + logging + confirmations). |
| `app/src/main/kotlin/.../data/FeatureFlags.kt::BuildFlavor` | Compile-time flavor gating. Exposes `current` (from `BuildConfig.FLAVOR`), `displayName` for the Settings → About badge, and six `bridgeTier1..6` flags. Tier 3/4/6 are `get() = current == SIDELOAD` so R8 can fold them in the Play release build. |
| `plugin/relay/channels/notifications.py` | **Phase 3 / Wave 1 / notif-listener** — `NotificationsChannel` holds a bounded `collections.deque` (maxlen 100) of recent notification metadata forwarded by the phone over the `notifications` WSS channel. Append on `notification.posted`, read via `get_recent(limit)` (newest-first, clamped to `[1, max_entries]`). In-memory only — wiped on relay restart by design (matches the smartwatch-companion semantics). |
| `plugin/tools/android_notifications.py` | **Phase 3 / Wave 1 / notif-listener** — Registers `android_notifications_recent(limit=20)` Hermes tool. Calls `http://127.0.0.1:8767/notifications/recent?limit=N` over loopback (no bearer needed for loopback callers — same trust model as `/media/register`), parses JSON, returns structured envelope. Stdlib `urllib.request` only — no requests/httpx dependency. Honours `RELAY_PORT` env. |
| `plugin/relay/server.py` (Phase3-notif-listener additions) | `handle_notifications_recent` HTTP route — loopback callers skip bearer, remote callers go through `_require_bearer_session`. Limit clamped via `NotificationsChannel.get_recent`. Channel dispatch in `_on_message` routes `channel == "notifications"` envelopes to `server.notifications.handle()`. Markers: `# === PHASE3-notif-listener: ... === / # === END PHASE3-notif-listener ===`. |
| `app/src/main/kotlin/.../notifications/HermesNotificationCompanion.kt` | **Phase 3 / Wave 1 / notif-listener** — `NotificationListenerService` subclass. Opt-in via `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` (the same Android API Wear OS / Android Auto / Tasker use). On `onNotificationPosted`, builds a `NotificationEntry`, serializes to a `notifications/notification.posted` envelope, and sends via the static `companion.multiplexer` slot. Cold-start buffer (`pendingEnvelopes`, capped at 50) preserves order before the multiplexer is wired. `isAccessGranted(context)` is a synchronous check against `Settings.Secure.enabled_notification_listeners`. |
| `app/src/main/kotlin/.../notifications/NotificationModels.kt` | **Phase 3 / Wave 1 / notif-listener** — `@Serializable data class NotificationEntry(packageName, title, text, subText, postedAt, key)` with `kotlinx.serialization` `@SerialName` mappings to the snake_case Python wire format. |
| `app/src/main/kotlin/.../ui/screens/NotificationCompanionSettingsScreen.kt` | **Phase 3 / Wave 1 / notif-listener** — Compose settings screen mirrored on `VoiceSettingsScreen`. Sections: About (plain-language explanation + revoke instructions), Status (live grant indicator via `LifecycleEventObserver` ON_RESUME re-check + "Open Android Settings" button), Test (pulls `service.activeNotifications` from the bound listener for end-to-end verification without requiring a relay round-trip). |
| `app/src/main/kotlin/.../network/ChannelMultiplexer.kt` (Phase3-notif-listener additions) | `sendNotification(envelope)` — thin wrapper over `send()` for the `HermesNotificationCompanion` outbound path. Drops on the floor when no `sendCallback` is wired (relay disconnected) — the service owns the cold-start buffer in its own `pendingEnvelopes` queue. Marked with `// === PHASE3-notif-listener: notification outbound routing ===`. |
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

### Typical Dev Loop (Claude-driven edits + Bailey's local testing)

The standard flow when Claude is editing code and Bailey is testing against the live hermes-agent install on the LAN server:

1. **Edit locally** in the Windows checkout (`C:\Users\Bailey\Desktop\Open-Projects\hermes-android\`). Both Python plugin (`plugin/`) and Android app (`app/`) live here. All code edits, tests, and doc updates happen in this one tree.
2. **Python changes** → run `python -m py_compile plugin/<file>.py` for quick syntax check. Full `pytest` / `unittest` runs happen on the server (the Windows env doesn't have the `aiohttp` + venv set up).
3. **Kotlin changes** → **do NOT run `gradle build`**. Bailey builds and runs the app from Android Studio's green ▶ button directly onto the physical device. This is a hard convention — never `adb install`, never `gradle assembleDebug` from Claude. Rely on type checks, `grep` for obvious structural bugs, and let Android Studio catch compile errors on Bailey's next build attempt. Errors surface in his IDE and he pastes them back for fast fixes.
4. **Commit + push to `origin/main`** via `git push origin main`. `main` is the deploy branch for this project. Feature branches are fine for longer work but the normal cycle is straight to main (SYSTEM.md convention: *"Git as handoff: Always commit + push before handing off between sessions"*).
5. **Pull on the Linux server** where the live relay + hermes-agent run. The hermes-relay plugin is **editable-installed** (`pip install -e`) against the hermes-agent venv, so `git pull` inside the server clone is all it takes for Python code changes to take effect on the **next import** per process. See "Server Deployment" below for the exact paths and commands.
6. **Restart running Python processes** if needed. Editable installs only take effect on fresh imports — any process that already imported the old code is holding it in memory. In practice this means:
   - `hermes-gateway.service` (user systemd) — restart via `systemctl --user restart hermes-gateway` when plugin *tool* code changes (e.g., `plugin/tools/android_tool.py`), because the gateway process imports and caches tools.
   - `hermes-relay.service` (user systemd, installed by `install.sh` step [6/6] as of 2026-04-12) — restart via `systemctl --user restart hermes-relay` when plugin *relay* code changes (files under `plugin/relay/`). The unit's Python entry point calls `plugin/relay/_env_bootstrap.py::load_hermes_env()` before importing the server module, so `~/.hermes/.env` values are re-read on every restart — no shell sourcing, no stale API keys. Logs: `journalctl --user -u hermes-relay -f`.
   - Plugin skill changes (files under `skills/`) — picked up **automatically** on every hermes-agent invocation via the `external_dirs` scan. No restart needed.
7. **Run tests on the server** (the venv there has all dependencies). Prefer `python -m unittest plugin.tests.test_<name>` — `python -m pytest` sometimes chokes on a pre-existing `conftest.py` that imports the `responses` module (not installed). `unittest` bypasses the conftest entirely.
8. **Test on the phone** — Bailey builds from Android Studio, installs to his Samsung device connected via USB (or the Studio device chooser), scans the pair QR from `/hermes-relay-pair` or `hermes-pair` on the server, and exercises the feature. He reports errors by pasting the Studio Logcat or the Kotlin compile error into the chat.

### Server Deployment (the "local instance" Bailey tests against)

The server is a Linux box on Bailey's LAN running hermes-agent with the hermes-relay plugin editable-installed. Canonical paths and commands (see `~/SYSTEM.md` on the server for the authoritative / sensitive details — host IP, user, services list):

| What | Where |
|---|---|
| hermes-agent repo (upstream fork) | `~/.hermes/hermes-agent/` |
| hermes-agent venv (python + all deps) | `~/.hermes/hermes-agent/venv/` |
| hermes-relay clone (this repo) | `~/.hermes/hermes-relay/` |
| Plugin symlink | `~/.hermes/plugins/hermes-relay` → `~/.hermes/hermes-relay/plugin` |
| Editable install verification | `~/.hermes/hermes-agent/venv/bin/pip show hermes-relay` → `Editable project location: ~/.hermes/hermes-relay` |
| Config (yaml + env) | `~/.hermes/config.yaml` + `~/.hermes/.env` |
| QR-secret (HMAC pair signing) | `~/.hermes/hermes-relay-qr-secret` (32 bytes, 0o600, auto-created by `hermes-pair` first run) |
| Relay log | `journalctl --user -u hermes-relay` (systemd user journal). Legacy `~/hermes-relay.log` is from the historical nohup era — no longer written to. |
| `hermes-gateway.service` | User systemd unit; runs `python -m hermes_cli.main gateway run --replace` on port 8642 |
| `hermes-relay.service` | User systemd unit (installed by `install.sh` step [6/6] as of 2026-04-12); runs `%h/.hermes/hermes-agent/venv/bin/python -m plugin.relay --no-ssl --log-level INFO` on port 8767. Template lives at `relay_server/hermes-relay.service` and uses `%h` for home-dir expansion so it's user-agnostic. **No `EnvironmentFile=`** — `plugin/relay/_env_bootstrap.py` loads `~/.hermes/.env` at Python import time, exactly like `hermes_cli/main.py` does for the gateway. |

**Standard update cycle on the server** (run manually via `ssh` — connection details in `~/SYSTEM.md` server-side):

```bash
# CANONICAL ONE-LINER (idempotent — covers everything below in one command):
hermes-relay-update                                 # if the shim is installed
HERMES_RELAY_RESTART_GATEWAY=1 hermes-relay-update  # opt into auto gateway restart

# Or via the curl pipe directly (same thing — the shim is just a wrapper):
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash

# IMPORTANT: when running over `ssh user@host '...'` non-interactively, use
# `bash -c` to wrap the env var so it actually inherits to the bash subshell:
ssh user@host 'bash -c "HERMES_RELAY_RESTART_GATEWAY=1 curl -fsSL .../install.sh | bash"'
# (Naked `HERMES_RELAY_RESTART_GATEWAY=1 curl ... | bash` only sets the var
# for curl, NOT for the bash subprocess that actually reads it. Confirmed
# the hard way on 2026-04-12.)

# Manual breakdown if you need to do it piecewise:
cd ~/.hermes/hermes-relay
git pull --ff-only origin main

# If plugin tools changed (android_tool.py etc.):
systemctl --user restart hermes-gateway

# If relay server code changed (plugin/relay/*.py):
systemctl --user restart hermes-relay

# Verify health
curl -s http://localhost:8767/health
hermes-status   # pretty version of the same + bridge state

# Verify the process has ~/.hermes/.env loaded (all expected keys as <set>):
PID=$(systemctl --user show -p MainPID --value hermes-relay)
cat /proc/$PID/environ | tr '\0' '\n' | grep -E 'VOICE_TOOLS_OPENAI_KEY|ELEVENLABS_API_KEY|ANTHROPIC_API_KEY' | sed 's/=.*/=<set>/'
```

If you ever see a stray `python -m plugin.relay` in `pgrep` output that doesn't belong to the service (orphan from a pre-systemd manual launch), kill it first: `pkill -f "python -m plugin.relay" && systemctl --user restart hermes-relay`. The nohup-era workflow (`nohup … & disown`) is no longer the canonical path as of 2026-04-12 — see the DEVLOG entry for that date.

**Running tests on the server:**

```bash
cd ~/.hermes/hermes-relay
# Specific modules to skip the conftest.py that imports `responses`:
~/.hermes/hermes-agent/venv/bin/python -m unittest \
    plugin.tests.test_qr_sign \
    plugin.tests.test_session_grants \
    plugin.tests.test_sessions_routes \
    plugin.tests.test_rate_limit_clear \
    plugin.tests.test_media_registry \
    plugin.tests.test_relay_media_routes
```

**Important conventions:**

- **Never install APKs from Claude.** Bailey deploys via Android Studio's run button. No `adb install`, no gradle builds from the tool side.
- **Never run `pytest` from Claude-side on the server** without the `unittest` escape above, unless you've first checked that `responses` is in the venv — the pre-existing conftest will fail the collector.
- **Use `systemctl --user restart hermes-relay` to restart the relay.** The nohup + disown dance is historical — as of 2026-04-12 the relay runs as a user systemd unit and `_env_bootstrap.py` loads `.env` at Python import time. If SSH is exiting before the command completes, wrap the restart with `nohup systemctl --user restart hermes-relay </dev/null &`.
- **Sensitive info lives in `~/SYSTEM.md` on the server and `~/.hermes/.env`**, NOT in this repo. Bailey's SSH user, IP, and secrets don't belong in `CLAUDE.md`. Claude reads the server's SYSTEM.md on first SSH if orientation is needed (`cat ~/SYSTEM.md`).
- **The phone re-pairs after each relay restart** — in-memory `SessionManager` state is wiped on restart, so the phone's stored session token becomes stale. `/pairing/register` clears rate-limit blocks automatically (per ADR 15) so re-pair via `/hermes-relay-pair` (in-chat) or `hermes-pair` (shell shim) works immediately without waiting for a block to expire.

### Where Python vs. Kotlin changes land

| Change type | Who rebuilds/restarts? | How? |
|---|---|---|
| Python plugin tool (e.g., `android_tool.py`) | `hermes-gateway.service` restart | `systemctl --user restart hermes-gateway` |
| Python plugin relay (`plugin/relay/*.py`) | `hermes-relay.service` restart | `systemctl --user restart hermes-relay` (loads `~/.hermes/.env` on every start via `_env_bootstrap.py`) |
| Python plugin pair CLI (`plugin/pair.py`, `plugin/cli.py`) | Next invocation picks up new code | No restart needed — fresh process per invocation |
| Skill files (`skills/**/SKILL.md`) | Next hermes-agent invocation scans `external_dirs` | No restart needed |
| Android app (`app/**`) | Bailey rebuilds in Android Studio | Studio run button → device |
| Docs (`docs/`, `user-docs/`, `DEVLOG.md`, `CLAUDE.md`, `README.md`) | — | No runtime effect |

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
| Inbound media register | `POST /media/register` on the relay (loopback only) — called by host-local tools via `plugin/relay/client.py::register_media()` | Tool falls back to bare `MEDIA:<path>` text + `⚠️ Image unavailable` placeholder if the relay isn't reachable |
| Inbound media fetch (token) | `GET /media/{token}` on the relay — `Authorization: Bearer <session_token>` (same token WSS uses) | — |
| Inbound media fetch (path) | `GET /media/by-path?path=<abs>` on the relay — same bearer auth. **Permissive by default since 2026-04-11**: serves any absolute readable file under the size cap, regardless of `allowed_roots`. Opt in to strict-mode allowlist enforcement via `RELAY_MEDIA_STRICT_SANDBOX=1`. Rationale: the LLM already has filesystem-reading tools, so the allowlist was defense-in-depth that mostly manifested as false positives ("Path not allowed" cards for legitimate `~/projects/foo/readme.png` fetches). Used for LLM-emitted `MEDIA:/abs/path` markers (upstream `agent/prompt_builder.py` instructs the LLM to emit this form). | — |
| Inbound media marker (tool) | `MEDIA:hermes-relay://<token>` — emitted by host-local tools that called `register_media()` via loopback | — |
| Inbound media marker (LLM) | `MEDIA:/abs/path.ext` — emitted by the LLM directly per the upstream system prompt. Phone fetches via `/media/by-path`, falls back to `⚠️ Image unavailable` on any failure | — |
| Paired devices list | `GET /sessions` on the relay — `Authorization: Bearer <session_token>`. Returns all active sessions with metadata (device name, token_prefix, expires_at, grants, transport_hint, is_current). Full tokens are NEVER included. Phone reads this for the Paired Devices screen. | — |
| Device revocation | `DELETE /sessions/{token_prefix}` on the relay — bearer-auth'd, matches on first-4+ chars. 200 on exact, 404 on zero, 409 on ambiguous. Self-revoke flagged via `revoked_self: true`. | — |
| Session TTL/grant update | `PATCH /sessions/{token_prefix}` on the relay — bearer-auth'd. Body `{ttl_seconds?, grants?}`. TTL restarts the clock from now; omitted grants re-clamp automatically to the new session lifetime. Backs the Android Paired Devices "Extend" button via `RelayHttpClient.extendSession` and `ConnectionViewModel.extendDevice`. | — |
| Pairing metadata | `POST /pairing/register` body now accepts optional `ttl_seconds` / `grants` / `transport_hint`. Host metadata wins over phone-sent values. Clears all rate-limit blocks on success so re-pair isn't blocked by stale failures. | — |
| Bidirectional pairing (Phase 3) | `POST /pairing/approve` — loopback-only stub, same wire shape as `/pairing/register`. Real UX tied to Phase 3 bridge implementation. | — |
| QR payload v2 | `HermesPairingPayload.hermes = 2` when any v2 field present. `relay.ttl_seconds` (0 = never), `relay.grants` (seconds-from-now, clamped to session), `relay.transport_hint` (`"wss"`/`"ws"`), top-level `sig` (HMAC-SHA256 over canonicalized payload via `plugin/relay/qr_sign.py`). Phone parses + stores `sig` but does NOT verify it yet (secret distribution TBD). | Old v1 QRs (no `hermes` field or `hermes: 1`) still parse via `ignoreUnknownKeys = true`. |
| Voice transcribe | `POST /voice/transcribe` on the relay — `multipart/form-data` with audio file, bearer-auth'd. Response: `{"text": "...", "provider": "openai", "success": true}`. Relay saves to temp file → calls `tools.transcription_tools.transcribe_audio` (sync, wrapped in `asyncio.to_thread`) → unlinks temp. Upstream reads `stt:` section from `~/.hermes/config.yaml` internally. | — |
| Voice synthesize | `POST /voice/synthesize` on the relay — JSON body `{"text": "..."}`, bearer-auth'd, max 5000 chars. Response: `audio/mpeg` file (full mp3, not chunked). Relay calls `tools.tts_tool.text_to_speech_tool` (sync, wrapped in `asyncio.to_thread`) which writes to `~/voice-memos/tts_<ts>.mp3` and returns JSON with the path → relay serves via `web.FileResponse`. Phone caches bytes to `cacheDir/voice_tts_<ts>.mp3`. Upstream reads `tts:` section from `~/.hermes/config.yaml` internally. Client-side sentence-boundary chunking is what makes playback feel streaming — server returns whole files per sentence. | — |
| Voice config | `GET /voice/config` on the relay — bearer-auth'd. Returns `{"tts": {provider, voice?, model?}, "stt": {provider, enabled, language?}}` so Voice Settings can show current provider + read-only voice/model. Reads `_load_tts_config()` / `_load_stt_config()` + `check_voice_requirements()` — private upstream helpers, clearly marked as such in the handler. | — |
| Auth envelope (pairing mode) | `{pairing_code, ttl_seconds?, grants?, device_name, device_id}` — phone adds ttl/grants from the TTL picker dialog. | — |
| Auth envelope (session mode) | `{session_token, device_name, device_id}` — ttl/grants NOT re-sent; server keeps the grant table keyed on the original pair. | — |
| `auth.ok` payload | `{session_token, server_version, profiles, expires_at, grants, transport_hint}` — new fields: expires_at as epoch (null = never), grants as `{channel: epoch\|null}`, transport_hint as `"wss"`/`"ws"`/`"unknown"`. | Old phones without v2 parser ignore the unknown fields. |

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
