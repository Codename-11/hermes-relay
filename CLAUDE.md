# Hermes-Relay — Claude Code Context

> Read this before touching code. Then read docs/spec.md and docs/decisions.md.

## What This Is

A native Android app (Kotlin + Jetpack Compose) paired with a Python relay server (aiohttp) for the Hermes agent platform. Chat connects directly to the Hermes API Server via HTTP/SSE; bridge and terminal use a relay over WSS.

**Current state:** v0.7.x (unreleased on `dev`) — Phase 0–3 complete. Direct API chat, session management, pairing + security (now multi-endpoint, ADR 24), inbound media, voice mode, bridge/accessibility control, notification companion, safety rails, multi-Connection, agent profiles + inspector, and first-class Tailscale (ADR 25). Two product flavors: `googlePlay` (conservative) and `sideload` (full-capability).

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
| `GET/POST/PATCH/DELETE /api/jobs/*` | Cron job management (api_server surface) | — |

**Non-standard endpoints (provided by fork OR by plugin bootstrap):**

These endpoints are not in stock upstream `gateway/platforms/api_server.py`. There are three ways a hermes-agent install can serve them:

1. **Codename-11 fork** (`feat/session-api` branch, deployed on the `axiom` branch) — adds them natively. Submitted upstream as PR [#8556](https://github.com/NousResearch/hermes-agent/pull/8556) *"feat(api-server): add session management API for frontend clients"* — scope is broader than the title: sessions CRUD + session chat/stream + memory + skills + config + available-models.
2. **Bootstrap injection** (`hermes_relay_bootstrap/`) — monkey-patches aiohttp on startup via `.pth` file. Does NOT inject `/api/sessions/{id}/chat/stream` — use `/v1/runs` for chat.
3. **Upstream-merged** (post PR #8556) — bootstrap auto-detects and no-ops.

| Endpoint | Purpose | Provided by |
|----------|---------|-------------|
| `GET /api/sessions` (CRUD) | Session list/create/rename/delete/fork | Fork OR bootstrap OR upstream-merged |
| `GET /api/sessions/{id}/messages` | Conversation history | Fork OR bootstrap OR upstream-merged |
| `GET /api/sessions/search` | Full-text message search | Fork OR bootstrap OR upstream-merged |
| `POST /api/sessions/{id}/chat/stream` | Session-based SSE chat | Fork OR upstream-merged ONLY (NOT bootstrap) |
| `GET /api/config`, `PATCH /api/config` | Personalities + model config | Fork OR bootstrap OR upstream-merged |
| `GET /api/skills`, `/{name}` | Skill discovery (list + detail) | Fork OR bootstrap OR upstream-merged |
| `PUT /api/skills/toggle` | Enable/disable installed skill | `hermes_cli/web_server.py` dashboard surface; mirrored into bootstrap |
| `GET/POST/PATCH/DELETE /api/memory` | Memory CRUD | Fork OR bootstrap OR upstream-merged |
| `GET /api/available-models` | Provider model list | Fork OR bootstrap OR upstream-merged |

The Android client probes per-endpoint capability via `HermesApiClient.probeCapabilities()` (returns `ServerCapabilities`). When `streamingEndpoint = "auto"`, `ConnectionViewModel.resolveStreamingEndpoint()` picks `sessions` or `runs` based on the capability snapshot.

**Dashboard web server (separate surface — loopback-only):**

hermes-agent ships a second web server at `hermes_cli/web_server.py` that hosts the React admin dashboard at `hermes_cli/web_dist/`. It has its **own** `/api/*` routes that **do not live on `api_server.py`** — notably: `GET/PUT /api/config` (full tree), `GET /api/config/schema`, `GET /api/config/defaults`, `GET/PUT /api/config/raw` (YAML text), `GET/PUT/DELETE /api/env` + `POST /api/env/reveal`, `PUT /api/skills/toggle`, `/api/cron/jobs/*` (different shape from `/api/jobs/*`), `/api/providers/oauth/*`, `/api/dashboard/themes`, `/api/dashboard/plugins`, `/api/model/info`, `/api/logs`, `/api/analytics/usage`. Auth is a page-injected `window.__HERMES_SESSION_TOKEN__` — loopback-only, no external issuance. **Do not proxy this surface over the relay.** Phone consumes the narrower, fork/bootstrap `api_server.py` surface or relay-native profile-scoped endpoints.

**Tool call rendering paths:**
1. **Runs API** — Emits `tool.started`/`tool.completed` as real SSE events → `ToolProgressCard` in real-time.
2. **Sessions API** — No structured tool events during streaming; reloads message history on stream complete ("session_end reload" pattern).
3. **Annotation parser** — Fallback for servers emitting inline markdown annotations (`` `💻 terminal` ``).

## Key Instructions
- **Always verify upstream before assuming an endpoint exists.** Check `gateway/platforms/api_server.py` in hermes-agent. If an endpoint isn't there, document whether bootstrap injects it or it requires the fork.
- If we use a non-standard endpoint, ensure `probeCapabilities()` covers it and the auto-resolver degrades gracefully.
- **Bootstrap maintenance:** Remove `hermes_relay_bootstrap/` in one PR once PR #8556 merges. It's no-op-compatible, so leaving it in place during rollout is harmless.

## Repository Layout

```
hermes-android/
├── app/src/main/kotlin/com/hermesandroid/relay/
│   ├── ui/                  # Screens, components, theme
│   ├── network/             # ConnectionManager, ChannelMultiplexer, handlers
│   ├── auth/                # AuthManager (pairing + tokens)
│   ├── viewmodel/           # ChatViewModel, ConnectionViewModel
│   ├── data/                # ChatMessage, ToolCall models, FeatureFlags
│   ├── audio/               # VoiceRecorder, VoicePlayer, VoiceSfxPlayer
│   ├── voice/               # VoiceViewModel, VoiceBridgeIntentHandler
│   ├── accessibility/       # HermesAccessibilityService, ScreenReader, ActionExecutor
│   ├── bridge/              # BridgeSafetyManager, BridgeForegroundService, BridgeStatusOverlay
│   └── notifications/       # HermesNotificationCompanion
├── desktop/                 ← Node thin-client CLI (`@hermes-relay/cli`)
│   ├── bin/hermes-relay.js  # #!/usr/bin/env node shim → dist/cli.js
│   ├── src/
│   │   ├── cli.ts           # argv parser + subcommand dispatcher (bare → shell)
│   │   ├── commands/        # chat, shell, pair, status, tools, devices
│   │   ├── banner.ts        # contextual connect line (LAN / Tailscale / Plain / Secure)
│   │   ├── renderer.ts      # GatewayEvent → plain-line stdout formatter (chat only)
│   │   ├── endpoint.ts      # ADR 24 EndpointCandidate + role helpers
│   │   ├── pairingQr.ts     # v3 QR decode + priority-raced reachability probe
│   │   ├── pairing.ts       # readline 6-char prompt + payload validator
│   │   ├── credentials.ts   # token → pair-qr → code → stored → prompt precedence
│   │   ├── certPin.ts       # TOFU SPKI sha256 extract / pinKey / compare
│   │   ├── tools/           # desktop.command router + fs/terminal/search handlers + consent
│   │   ├── transport/       # RelayTransport (reconnect state machine + TLS probe TOFU)
│   │   └── lib/             # gracefulExit, rpc, circularBuffer (vendored)
│   └── scripts/             # install.sh + install.ps1 curl/iwr one-liners
├── plugin/                  ← Hermes agent plugin
│   ├── android_tool.py      # 18 android_* tool handlers
│   ├── pair.py              # QR pairing implementation
│   ├── relay/               # Canonical WSS relay (server.py, auth.py, channels/, media.py, voice.py)
│   ├── tools/               # android_navigate.py, android_notifications.py
│   └── dashboard/           # hermes-agent dashboard plugin — manifest, React UI, FastAPI proxy
├── relay_server/            ← Thin compat shim → plugin.relay (legacy entrypoint)
├── hermes_relay_bootstrap/  ← Runtime patch for vanilla upstream; removable after PR #8556
├── skills/devops/hermes-relay-pair/  ← /hermes-relay-pair slash command
├── scripts/                 ← dev.bat, bridge-smoke.sh, bump-version.sh
└── docs/                    ← spec, decisions, security, relay-server, mcp-tooling
```

## Project Conventions

### File Structure
- **Root-level:** README.md, CLAUDE.md, AGENTS.md, DEVLOG.md, .gitignore
- **docs/** — spec, decisions, security, and any other long-form documentation
- **DEVLOG.md** — update at end of each work session with what was done, what's next, blockers
- **CLAUDE.md hygiene:** Key Files entries must stay one line — implementation detail belongs in the file or `docs/`. Run `/revise-claude-md` after feature-heavy sessions to trim drift.

### Code Style — Android (Kotlin)
- **Jetpack Compose** — no XML layouts. Material 3 / Material You.
- **kotlinx.serialization** — not Gson. Type-safe, faster.
- **OkHttp** for WebSocket + SSE — `okhttp` for WSS relay, `okhttp-sse` for API streaming
- **Single-activity** — Compose Navigation for all routing
- **Namespace (Kotlin source tree):** `com.hermesandroid.relay` — stable, drives on-disk layout + class FQCNs
- **applicationId:** `com.axiomlabs.hermesrelay` (googlePlay), `com.axiomlabs.hermesrelay.sideload` (sideload)
- **Min SDK 26, Target SDK 35, Compile SDK 36** / **Kotlin 2.0+**, JVM toolchain 17

### Code Style — Desktop CLI (Node/TypeScript)
- **Node ≥21** — uses built-in global `WebSocket` (no `ws`/`undici` dep). Strict TS, ES modules, `NodeNext` resolution.
- **Zero runtime deps** — `@types/node` + `tsx`/`rimraf`/`typescript` are devDeps only. Ship compiled `dist/`, not tsx.
- **One binary, subcommands** — idiomatic for Node CLIs (codex, continue, vite pattern). Bare invocation is `chat`.
- **Vendor-for-now** — transport/gateway/types are copied verbatim from `hermes-agent-tui-smoke/ui-tui/src/` with a header note. Extract to a shared package when the TUI and CLI stabilize.
- **Dev loop:** `npx tsx src/cli.ts <args>` (no rebuild). `npm run build` + `npm link` before pushing to verify the bin shim. Never ship tsx in the published tarball — pre-build with `tsc` so Windows `npm install -g` can cmd-shim the JS directly.

### Code Style — Server (Python)
- **aiohttp** — async, matches existing Hermes relay patterns
- **Type hints everywhere** — Python 3.11+ syntax
- **asyncio** — no threading; **structured logging** — use `logging`, not print()

### Git
- **Conventional Commits:** `feat`, `fix`, `docs`, `refactor`, `test`, `chore`
- **Branching model (as of 2026-04-19):** `main` + `dev`. Feature branches target `dev`, not `main`. `main` receives only release merges (and tags). No straight-to-main exemption — even single-file typos go through `dev`.
- **Merge style:** `git merge --no-ff` — no squash. Preserves per-commit trail for agent-team branches on every merge in the chain (feature → dev → main).
- **Merging ≠ releasing.** Feature branches land on `dev` continuously as CI goes green; each PR appends to `[Unreleased]` in `CHANGELOG.md` on `dev`. Releases are a separate act — cut when accumulated state is worth shipping, not per-feature. See `RELEASE.md` "When to cut a release."
- **Version bumps happen on `dev`, then release-merge to `main`.** Use `bash scripts/bump-version.sh <new-version>` to bump all three sources atomically (`gradle/libs.versions.toml`, `pyproject.toml`, `plugin/relay/__init__.py`). The `release: vX.Y.Z` commit lives on `dev`, then a release PR merges `dev` → `main` with `--no-ff`, then the tag is cut from `main`.
- **Server tracks `dev` for staging.** The hermes-host deployment pulls `dev` so merged features are exercised before they reach a tag. Released state lives on tags cut from `main`.
- **Branch protection** on `main` — direct push blocked; only release-merge PRs from `dev` land here. `dev` also requires CI to pass on PRs but accepts feature-branch merges freely.

### Testing
- **Android:** JUnit + Compose testing for UI, MockK for mocks
- **Python:** `python -m unittest plugin.tests.test_<name>` — avoid bare `pytest` (conftest imports `responses` which may not be installed in the venv)
- **CI is split by path:** `.github/workflows/ci-android.yml` runs on app/Gradle changes; `.github/workflows/ci-relay.yml` runs on plugin/Python changes. Both trigger on pushes to `main` and `dev` and on PRs targeting either. Build + tests must pass before merge to `dev`; release-merge to `main` requires the same.

## Key Files

| File | Why |
|------|-----|
| `docs/spec.md` | Full specification — protocol, UI layouts, phases, dependencies |
| `docs/decisions.md` | Architecture decisions — framework choice, channel design, auth model |
| `AGENTS.md` | Tool usage patterns for the `android_*` toolset |
| `docs/mcp-tooling.md` | MCP server setup — android-tools-mcp + mobile-mcp |
| **App — Core** | |
| `ui/RelayApp.kt` | Main scaffold — bottom nav, Compose navigation |
| `viewmodel/ChatViewModel.kt` | Chat orchestration — send, stream, cancel, slash commands |
| `viewmodel/ConnectionViewModel.kt` | Dual connection model (API + relay); `resolveStreamingEndpoint()`; derived `relayUiState` flow + `markPaired` hook stamp the active Connection |
| `viewmodel/RelayUiState.kt` | Shared sealed state for the relay row — 5 cases + `asBadgeState()` / `statusText()` extensions; 5s grace window before Stale |
| `network/HermesApiClient.kt` | Direct HTTP/SSE — `sendRunStream()`, `sendChatStream()`, `probeCapabilities()` |
| `network/ConnectionManager.kt` | WSS to relay with auto-reconnect; rebuilds OkHttpClient with fresh CertPinner on connect |
| `network/ChannelMultiplexer.kt` | Envelope routing by channel; `sendNotification()` for notification outbound |
| `network/handlers/ChatHandler.kt` | Chat message state, streaming events, tool annotation parser |
| `network/models/SessionModels.kt` | Session, message, SSE event data models |
| `data/FeatureFlags.kt` | Feature gating — DEV_MODE + DataStore overrides; `BuildFlavor` (googlePlay/sideload Tier flags) |
| **App — Auth** | |
| `auth/AuthManager.kt` | Wires SessionTokenStore + CertPinStore; parses auth.ok; `applyServerIssuedCodeAndReset()` |
| `auth/SessionTokenStore.kt` | Keystore (StrongBox) + EncryptedSharedPrefs fallback; lossless migration on upgrade |
| `auth/CertPinStore.kt` | TOFU cert pinning — SHA-256 SPKI per host:port in DataStore |
| `auth/PairedSession.kt` | PairedSession state + PairedDeviceInfo wire model |
| `data/Endpoint.kt` | `EndpointCandidate` / `ApiEndpoint` / `RelayEndpoint` — multi-endpoint pairing (ADR 24); `displayLabel()` for LAN/Tailscale/Public/Custom chips |
| `network/RelayHttpClient.kt` | OkHttp for /media, /sessions (list/revoke/extend), /health |
| **App — Bridge** | |
| `network/handlers/BridgeCommandHandler.kt` | Routes `bridge.command` → ActionExecutor; full path inventory + safety-rail integration |
| `viewmodel/BridgeViewModel.kt` | BridgeScreen VM — masterToggle, bridgeStatus, permissionStatus, activityLog |
| `bridge/BridgeSafetyManager.kt` | Blocklist + destructive-verb confirmation + auto-disable timer; fails-closed on /call and /send_sms |
| `data/BridgeSafetyPreferences.kt` | DataStore for blocklist, destructive verbs, auto-disable minutes, confirmation timeout |
| `ui/screens/BridgeScreen.kt` | Bridge UI — master → permission checklist → [Advanced] → unattended → safety → activity log (v0.4.1 reorder) |
| `ui/components/UnattendedAccessRow.kt` | Unattended toggle card (sideload); `enabled=masterEnabled`; inline `KeyguardDetectedAlert` |
| `ui/components/UnattendedGlobalBanner.kt` | 28dp amber strip at scaffold top when master+unattended on (sideload); tap → Bridge tab |
| `bridge/BridgeStatusOverlay.kt` | WindowManager overlay; `ConfirmationOverlayHost`; requires `SavedStateRegistryOwner` init order (CREATED→restore→RESUMED) |
| `accessibility/HermesAccessibilityService.kt` | AccessibilityService subclass; `@Volatile instance` singleton for BridgeCommandHandler |
| `accessibility/ScreenReader.kt` | UI tree → ScreenContent; `findNodeBoundsByText()`, `findFocusedInput()` |
| `accessibility/ActionExecutor.kt` | Gesture/text dispatch via GestureDescription + ACTION_SET_TEXT; pressKey maps vocab only |
| **App — Voice** | |
| `voice/VoiceViewModel.kt` | Voice turn state machine; TTS queue; `ignoreAssistantId`; `errorEvents: SharedFlow` |
| `audio/VoiceRecorder.kt` | MediaRecorder wrapper; perceptual amplitude curve; `.m4a` at 16kHz/64kbps |
| `audio/VoicePlayer.kt` | MediaPlayer + Visualizer; amplitude StateFlow; `awaitCompletion()` via coroutine |
| `network/RelayVoiceClient.kt` | OkHttp for `/voice/transcribe`, `/synthesize`, `/config` |
| `voice/VoiceBridgeIntentHandler.kt` | Interface routing voice utterances to bridge; impls per flavor via factory |
| `voice/VoiceIntentClassifier.kt` | Regex phone-control classifier (sideload only); false-negatives preferred over false-positives |
| `ui/components/VoiceModeOverlay.kt` | Full-screen voice UI — MorphingSphere + VoiceWaveform + mic button |
| `ui/components/MorphingSphere.kt` | Compose renderer for the agent sphere — delegates math to `MorphingSphereCore` |
| `ui/components/MorphingSphereCore.kt` | Platform-agnostic sphere algorithm (`kotlin.math` only) — single source of truth; mirrored byte-for-byte in `preview/web/sphere.js` |
| `preview/web/` | Zero-dep browser harness — live `index.html` preview + `parity-check.mjs`; paired with `MorphingSphereCoreParityTest` (JVM) for struct/full checksum diffing |
| `user-docs/.vitepress/theme/components/SphereMark.vue` | Docs-site sphere embed — imports `preview/web/sphere.js` directly; autonomous fbm drift + pointer-proximity gaze/state blend; `<ClientOnly>` + `IntersectionObserver` + `prefers-reduced-motion` aware |
| **App — Media + Notifications** | |
| `util/MediaCacheWriter.kt` | `cacheDir/hermes-media/` LRU writer; returns FileProvider URIs |
| `ui/components/InboundAttachmentCard.kt` | Discord-style attachment card for images/video/audio/pdf/text/generic |
| `data/HermesCard.kt` | `CARD:{json}` envelope (ADR 26) — type/accent/fields/actions; kotlinx.serialization |
| `ui/components/HermesCardBubble.kt` | Rich-card renderer — accent stripe + FlowRow actions + dispatch stamp collapse |
| `viewmodel/CardDispatchSyncBuilder.kt` | Twin of VoiceIntentSyncBuilder — synthesizes card dispatches as `hermes_card_action` OpenAI pairs for session memory |
| `notifications/HermesNotificationCompanion.kt` | NotificationListenerService; cold-start buffer (50); forwards via ChannelMultiplexer |
| `util/RelayErrorClassifier.kt` | `classifyError(Throwable, context) → HumanError`; used by Voice/Chat/Connection |
| **Relay — Server** | |
| `plugin/relay/server.py` | Canonical relay — WSS + HTTP routes; bridge, media, voice, session, pairing handlers. `handle_pairing_mint` mirrors `pair.py:762` — top-level = API server, `relay.{url,code}` nested |
| `plugin/relay/auth.py` | PairingManager, SessionManager, RateLimiter; `math.inf` for never-expire |
| `plugin/relay/channels/bridge.py` | Bridge handler — `handle_command()` mints request_id, awaits response, 30s timeout |
| `plugin/relay/channels/notifications.py` | Bounded deque (100) of notification metadata; in-memory only |
| `plugin/relay/media.py` | MediaRegistry — LRU token store; `strict_sandbox` off by default for `/media/by-path` |
| `plugin/relay/voice.py` | Voice endpoints — transcribe, synthesize, voice_config; lazy tool imports |
| `plugin/relay/qr_sign.py` | HMAC-SHA256 QR signing; secret at `~/.hermes/hermes-relay-qr-secret`; canonical form preserves `endpoints` array order + role strings verbatim (ADR 24) |
| `plugin/relay/tailscale.py` | First-class Tailscale helper (ADR 25) — `status()` / `enable(port)` / `disable(port)` / `canonical_upstream_present()`; safe-absent via shell-out to `tailscale` CLI |
| `plugin/relay/_env_bootstrap.py` | Loads `~/.hermes/.env` before relay imports; called from both entry points |
| **Plugin — Tools + Installer** | |
| `plugin/tools/android_tool.py` | 18 `android_*` tool handlers (14 baseline + send_sms, call, search_contacts, return_to_hermes); `android_screenshot` first consumer of `register_media()` |
| `plugin/tools/android_navigate.py` | Vision-driven navigation loop; up to 20 iterations; `llm_gap` error until vision client wired |
| `plugin/pair.py` | QR payload builder + CLI; `build_payload(sign=True)`; `--register-code` fallback |
| `install.sh` | Canonical installer — 6 steps; idempotent; drops `hermes-relay-update` shim |
| `uninstall.sh` | Canonical uninstaller; reverses install.sh; never touches `.env` or `state.db` |
| `hermes_relay_bootstrap/` | Runtime patch for vanilla upstream; no-op on fork/upstream-merged; remove after PR #8556 |
| **Plugin — Dashboard** | |
| `plugin/dashboard/manifest.json` | Declares tab, entry bundle, and FastAPI module for hermes-agent discovery |
| `plugin/dashboard/plugin_api.py` | FastAPI router proxying 5 routes to relay over loopback; `/pairing` body = API-server overrides (host/port/tls/api_key), relay URL auto-derived |
| `plugin/dashboard/src/index.jsx` | React root registering `hermes-relay` plugin with 4-tab shell |
| `plugin/dashboard/dist/index.js` | Committed IIFE bundle loaded verbatim by dashboard |
| **Desktop CLI** | |
| `desktop/package.json` | `@hermes-relay/cli` package manifest — Node ≥21, one `hermes-relay` bin, pre-built dist |
| `desktop/bin/hermes-relay.js` | Tiny shim: `import('../dist/cli.js').then(m => m.main())` + error surfacing |
| `desktop/src/cli.ts` | argv parser + subcommand dispatcher — bare → `shell` (PTY), positional-only → `chat` |
| `desktop/src/commands/chat.ts` | REPL + one-shot + piped-stdin; `runOneTurn` returns `{promise, cancel}` for safe SIGINT; auto-wires `DesktopToolRouter` when consented |
| `desktop/src/commands/shell.ts` | Pipes the `terminal` relay channel to raw-mode stdin/stdout; post-attach `exec hermes` 350ms after tmux settles; `Ctrl+A .` detach / `Ctrl+A k` kill / `Ctrl+A Ctrl+A` literal |
| `desktop/src/commands/pair.ts` | Either 6-char code + `--remote`, or full v3 QR via `--pair-qr` — probes + picks endpoint, records role |
| `desktop/src/commands/tools.ts` | `tools.list` RPC → enabled/available toolsets; `--verbose` lists individual tools |
| `desktop/src/commands/status.ts` | Local read of `~/.hermes/remote-sessions.json`; renders `grants:` + `expires:` + `route:`; `--json` redacts tokens, `--reveal-tokens` opts in |
| `desktop/src/commands/devices.ts` | Server-side session management — `GET/DELETE/PATCH /sessions` via `fetch` over http(s)://host:port; `list` / `revoke <prefix>` / `extend <prefix> --ttl <s>` |
| `desktop/src/banner.ts` | `buildConnectBanner({url, meta, endpointRole})` → "Connected via LAN (plain) — server 0.6.0"; `humanExpiry()` for TTL formatting |
| `desktop/src/endpoint.ts` | `EndpointCandidate` / `EndpointRole` types + `displayLabel()` — mirrors Android `data/Endpoint.kt` |
| `desktop/src/pairingQr.ts` | `decodePairingPayload` (JSON or base64), `payloadToCandidates` (v3 verbatim / v1–v2 synthesized), `probeCandidatesByPriority` (`Promise.any` within tier, `AbortSignal.any`, 4s timeout, 60s cache) |
| `desktop/src/certPin.ts` | `extractSpkiSha256(der)` via `crypto.X509Certificate` + `publicKey.export({type:'spki'})`; `pinKey(url)`, `comparePins()`, `isSecureUrl()` |
| `desktop/src/tools/router.ts` | `DesktopToolRouter.attach(relay)` — installs `onChannel('desktop')`, dispatches `desktop.command` under 30s `AbortController`, 30s heartbeat via `desktop.status` advertising handler names |
| `desktop/src/tools/consent.ts` | `ensureToolsConsent(url)` — stored per-URL in `toolsConsented`; TTY prompt; non-TTY fails closed |
| `desktop/src/tools/handlers/fs.ts` | `readFileHandler` / `writeFileHandler` / `patchHandler` — strict unified-diff applier, no fuzz |
| `desktop/src/tools/handlers/terminal.ts` | `bash -lc` / `cmd /c`, SIGKILL on timeout or abort, returns `{stdout, stderr, exit_code, duration_ms}` |
| `desktop/src/tools/handlers/search.ts` | ripgrep with pure-Node fallback, skips `.git`/`node_modules`/`dist`/`.next`/`.cache` |
| `desktop/src/renderer.ts` | Streams `message.delta` → stdout, tool events → decorated lines; NO_COLOR / --json / --quiet aware |
| `desktop/src/pairing.ts` | readline-based 6-char prompt (`A-Z0-9`); headless mirror of TUI's Ink prompt; `validatePairingPayloadString` discriminated-union wrapper |
| `desktop/src/credentials.ts` | Precedence: `--token` → `--pair-qr` (probe+pair) → `--code` → stored → prompt; returns `Credentials{sessionToken?, pairingCode?, resolvedEndpoint?}` |
| `desktop/src/transport/RelayTransport.ts` | Fork of ui-tui's transport + reconnect state machine (`idle/connecting/connected/reconnecting`, exp backoff 1→30s, 5min on 429, gate re-check post-sleep) + pre-WS TLS probe for TOFU |
| `desktop/src/remoteSessions.ts` | Same file path as TUI (`~/.hermes/remote-sessions.json`, 0600); schema widened with `grants`, `ttlExpiresAt`, `endpointRole`, `toolsConsented`; `saveSession` back-compat overload |
| `desktop/src/commands/daemon.ts` | Headless WSS + tool router for always-on access; JSON-line logs; fails closed on missing consent unless `--allow-tools` with explicit `--token` |
| `desktop/src/commands/doctor.ts` | Local-only diagnostic report — version / binary path / PATH / sessions / daemon detection; `--json` for support-paste; omits tokens entirely |
| `desktop/src/relayUrlPrompt.ts` | First-run URL fallback — `resolveFirstRunUrl()` auto-picks single stored session, numbered picker for multiple, welcome banner for zero; throws on non-interactive + ambiguous |
| `desktop/src/version.ts` | Build-time-generated constant (`npm run gen:version` before every build) — Bun compiled binaries can't read package.json via `__dirname` so version is embedded at build |
| `desktop/scripts/install.sh` / `install.ps1` | curl/iwr one-liner installers — download prebuilt Bun binary (no Node required), SHA256-verified, API-resolver for `latest` that includes prereleases, version-aware pre/post-install readback |
| `desktop/scripts/uninstall.sh` / `uninstall.ps1` | 3-tier removal — default (binary + PATH), `--purge` (also wipes `~/.hermes/remote-sessions.json`), `--service` (stub for future service installers); Windows iex-safe env-var fallback |
| `desktop/README.md` | User-facing install + usage reference |
| **Desktop CLI — dev iteration** | |
| `npm run smoke` (in `desktop/`) | Builds Windows binary + runs `--version` / `--help` / `doctor`, fails loud on zero-output. Local pre-flight before cutting any tag. |
| `npm run gen:version` | Regenerates `src/version.ts` from `package.json`. Runs automatically before every `build` / `build:bin:*`. |
| `release-desktop.yml → Smoke-test Linux binary` step | CI-side equivalent: runs compiled Linux binary through the same 3-command check before uploading assets. Catches silent-exit-0 + segfault classes. |
| **Server — Desktop tool routing (Phase B)** | |
| `plugin/relay/channels/desktop.py` | Mirrors `bridge.py` — `desktop.command`/`desktop.response`/`desktop.status`, UUID-correlated futures, 30s timeout, single-client MVP, per-session advertised-tools set |
| `plugin/tools/desktop_tool.py` | `desktop_read_file` / `_write_file` / `_terminal` / `_search_files` / `_patch` — registers with `tools.registry` under `desktop` toolset; `_check_requirements` pings `/desktop/_ping` for "is a client connected AND does it advertise this tool?" |

## What NOT to Do

- **Don't use XML layouts** — Compose only
- **Don't use Gson** — kotlinx.serialization
- **Don't use Ktor for networking** — OkHttp for WebSocket
- **Don't use plaintext WebSocket** — `wss://` only, even in development
- **Don't put documentation in root** — long-form docs go in `docs/`
- **Don't forget DEVLOG.md** — update it

## MCP Tooling

Two MCP servers are configured for AI-assisted development. See `docs/mcp-tooling.md` for full reference.

| Server | Layer | Requires |
|--------|-------|----------|
| `android-tools-mcp` | IDE/Build — Compose previews, Gradle, code search, Android docs | Android Studio running with project open |
| `mobile-mcp` | Device/Runtime — tap, swipe, screenshot, app management | ADB + connected device/emulator |

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

### Bridge smoke test (run on hermes-host, not local PC)

```bash
scripts/bridge-smoke.sh                   # full suite, destructive ON
scripts/bridge-smoke.sh --no-destructive  # read-only paths only
scripts/bridge-smoke.sh --filter open_app # re-run a single test
scripts/bridge-smoke.sh --pair ABCDEF     # register pairing code first
```

Curls every bridge HTTP route via `localhost:8767`. Catches the silent-drop regression class (Python relay registers a route but Kotlin dispatcher's `when (path)` has no matching branch). Run after every relay restart.

### Typical Dev Loop

1. **Edit locally** — Windows checkout. Both plugin (`plugin/`) and app (`app/`) live here.
2. **Python syntax check** — `python -m py_compile plugin/<file>.py`. Full tests run on the server.
3. **Kotlin changes** — do NOT run `gradle build`. Bailey builds via Android Studio's ▶ button. Never `adb install` from Claude.
4. **Before pushing Kotlin changes** — run `./gradlew lint` locally. It's the exact task CI runs (see `.github/workflows/ci.yml` → `gradlew lint` fallback) and catches errors Android Studio's live inspections miss — e.g. `UnsafeOptInUsageError` with `kotlin.OptIn` vs `androidx.annotation.OptIn`, `FlowOperatorInvokedInComposition` (mapped flows inside Composables), Media3 `@UnstableApi` propagation. Lint is a hard blocker in CI: Build + Test show "skipping" until lint passes, and lint prints only the **first failure** before aborting — so CI iterations reveal errors one at a time while a single local lint run surfaces all of them.
5. **Commit + push** — feature branch off `dev`, merged back to `dev` via PR. `main` is reserved for release merges.
6. **Pull + restart on server** — see Server Deployment below.
7. **Test on phone** — Bailey builds from Studio, installs to Samsung device, pairs via `/hermes-relay-pair`.

### Server Deployment

Server is a Linux box running hermes-agent with hermes-relay editable-installed (`pip install -e`). Sensitive details (IP, user, secrets) in `~/SYSTEM.md` on the server — not in this repo.

| What | Where |
|---|---|
| hermes-agent repo | `~/.hermes/hermes-agent/` |
| hermes-relay clone | `~/.hermes/hermes-relay/` |
| Plugin symlink | `~/.hermes/plugins/hermes-relay` → `~/.hermes/hermes-relay/plugin` |
| Config | `~/.hermes/config.yaml` + `~/.hermes/.env` |
| Relay log | `journalctl --user -u hermes-relay -f` |

**Update:** `hermes-relay-update` (idempotent, re-fetches install.sh). Or manually: `git pull --ff-only && systemctl --user restart hermes-relay`.

**Key conventions:**
- Phone re-pairs after each relay restart (SessionManager is in-memory; wiped on restart)
- Use `python -m unittest` not `pytest` — conftest imports `responses` which may not be installed
- `_env_bootstrap.py` loads `~/.hermes/.env` on every relay start — no stale API keys

### Where Python vs. Kotlin changes land

| Change type | Who restarts? | Command |
|---|---|---|
| Plugin tool (`android_tool.py` etc.) | `hermes-gateway.service` | `systemctl --user restart hermes-gateway` |
| Relay code (`plugin/relay/*.py`) | `hermes-relay.service` | `systemctl --user restart hermes-relay` |
| Pair CLI / skill files | — | No restart — fresh process / scanned on invocation |
| Android app | Bailey (Studio) | Studio run button |

### Release Process

See [RELEASE.md](RELEASE.md) for the full recipe.

- **Version source:** `gradle/libs.versions.toml` (`appVersionName`, `appVersionCode`)
- **Bump atomically:** `bash scripts/bump-version.sh <new-version>` — updates all three sources
- **`appVersionCode` is monotonic** — always increment across prereleases
- **Cut a release:** bump → commit → `git tag vMAJOR.MINOR.PATCH` → push tag → CI builds + GitHub Release
- **Required secrets:** `HERMES_KEYSTORE_BASE64`, `HERMES_KEYSTORE_PASSWORD`, `HERMES_KEY_ALIAS`, `HERMES_KEY_PASSWORD`

## Integration Points

| Surface | Endpoint | Notes |
|---------|----------|-------|
| Chat streaming | `POST /v1/runs` → `GET /v1/runs/{id}/events` | Structured tool events; preferred |
| Chat (sessions) | `POST /api/sessions/{id}/chat/stream` | No live tool events; reloads history on stream complete |
| Chat (compat) | `POST /v1/chat/completions` (stream=true) | Inline tool annotations only |
| Session CRUD | `GET/POST/PATCH/DELETE /api/sessions` | Non-standard; bootstrap or fork |
| Pairing (QR) | `POST /pairing/register` (loopback only) | Via `/hermes-relay-pair` or `hermes-pair` shim; accepts optional `endpoints` for multi-endpoint QRs |
| Pairing (multi-endpoint) | QR `endpoints` array (ADR 24) | `hermes: 3` schema; ordered `lan`/`tailscale`/`public`/... candidates; phone re-probes on network change |
| Pairing auth | WSS `auth.ok` payload | Includes `expires_at`, `grants`, `transport_hint` |
| Tailscale Serve (ADR 25) | `hermes-relay-tailscale enable\|disable\|status` CLI | Fronts loopback `:8767` with `tailscale serve --bg --https=<port>`; auto-retires on upstream PR #9295 |
| Inbound media (token) | `GET /media/{token}` | Bearer auth; 24h TTL |
| Inbound media (path) | `GET /media/by-path?path=<abs>` | Permissive by default; `RELAY_MEDIA_STRICT_SANDBOX=1` to restrict |
| Session management | `GET /sessions`, `DELETE /sessions/{prefix}`, `PATCH /sessions/{prefix}` | List/revoke/extend; RelayHttpClient |
| Voice transcribe | `POST /voice/transcribe` | multipart/form-data; bearer auth |
| Voice synthesize | `POST /voice/synthesize` | JSON → audio/mpeg; max 5000 chars |
| Voice config | `GET /voice/config` | Returns current tts/stt provider info |
| Notifications | `GET /notifications/recent?limit=N` | Loopback callers skip bearer |
| Relay health | `GET /health` on `:8767` | Used by `RelayHttpClient.probeHealth()` |
| Capabilities | `HEAD /api/sessions`, `HEAD /v1/runs`, etc. | HEAD avoids CORS 403 on OPTIONS preflight |
| Desktop CLI (tui channel) | WSS `tui.attach` / `tui.rpc.request` / `tui.rpc.event` | Same channel + envelopes as the Ink TUI — the CLI just renders events as plain lines. Zero server changes. |
| Desktop CLI (terminal channel) | WSS `terminal.attach` / `terminal.input` / `terminal.output` / `terminal.resize` / `terminal.detached` | Existing channel (shared with Android). CLI `shell` subcommand attaches, injects `clear; exec hermes\n` 350ms after ack, pipes raw bytes. `Ctrl+A .` detaches (tmux preserved), `Ctrl+A k` kills. |
| Desktop CLI tool visibility | `tools.list` RPC on the shared tui channel | Returns `{toolsets: [{name, description, tool_count, enabled, tools:[]}]}`; surfaced by `hermes-relay tools` |
| Desktop CLI devices | HTTP `GET/DELETE/PATCH /sessions` on the relay's same port | Wrapped by `hermes-relay devices list | revoke <prefix> | extend <prefix> --ttl <s>`; bearer token from stored session; token prefix only (never full token) |
| Desktop tool routing (Phase B) | WSS `desktop.command` (s→c) + `desktop.response` (c→s) + `desktop.status` (c→s heartbeat) | New channel. Hermes calls `desktop_read_file(path)` → Python handler POSTs to `/desktop/desktop_read_file` → relay forwards over `desktop.command` → Node client's `DesktopToolRouter` runs the handler locally → response bubbles back. Mirror of Android's `bridge.command` pattern. |
| Desktop tool check_fn | HTTP `GET /desktop/_ping?tool=<name>` | Returns 200 if a client is connected AND advertises this tool; 503 otherwise. Hermes uses this to fail the tool quickly when no desktop client is live, instead of waiting 30s for the dispatch timeout. |

## Upstream References

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
