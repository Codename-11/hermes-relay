# Hermes-Relay — Claude Code Adapter

> Read [AGENTS.md](AGENTS.md) first. It is the provider-neutral canonical agent
> context. Branch, release, staging, and hotfix rules live in `AGENTS.md` and
> [RELEASE.md](RELEASE.md); this file only adds Claude-specific project and tool
> guidance. Then read `docs/spec.md` and `docs/decisions.md`.

## What This Is

A native Android app (Kotlin + Jetpack Compose) paired with an optional Python relay plugin/server (aiohttp) for the Hermes agent platform. Vanilla Hermes chat, Manage, and dashboard voice work against unmodified upstream Hermes. The Relay plugin adds phone control, terminal, remote desktop tooling, extra voice engines, and dashboard Relay management via the official Hermes web dashboard.

**Current state:** Reference latest released version for stable state and current dev branch for working state. The default no-plugin path supports chat, Manage, and voice on vanilla upstream Hermes. Chat auto-prefers the dashboard `/api/ws` gateway transport when Manage auth is ready, then falls back to API-server SSE routes. Vanilla Hermes voice uses dashboard `/api/audio/*` with the Manage session. Relay remains an additive power path for terminal, bridge/device control, notification companion, extra/provider-native voice, remote access, and desktop tooling. Two Android product flavors ship: `googlePlay` (conservative, no unattended Device Control surface) and `sideload` (full-capability).

## Architecture

```
Phone (WS)       -> Hermes dashboard (:9119)     [vanilla Hermes gateway chat, live thinking]
Phone (HTTP/SSE) -> Hermes API Server (:8642)    [vanilla Hermes chat fallback, sessions, runs]
Phone (HTTP)     -> Hermes dashboard (:9119)     [vanilla Hermes Manage + voice]
Phone (WSS/HTTP) -> Relay plugin/server (:8767)  [optional bridge, terminal, relay voice, remote tools]
```

The Vanilla Hermes path must stay upstream-only. API-server bearer auth and dashboard cookie auth are separate. Terminal and bridge require Relay pairing; Vanilla Hermes chat, Manage, and dashboard voice must not.

### Upstream Hermes API Reference

**IMPORTANT:** Always verify endpoints against the actual hermes-agent source (`gateway/platforms/api_server.py`). The upstream repo is the source of truth — not our docs, not our memory, not assumptions from other frontends.

**Vanilla Hermes endpoints (confirmed in hermes-agent source):**


| Endpoint                                | Purpose                                                  | Tool Call Format                                                                                                               |
| --------------------------------------- | -------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| `POST /v1/chat/completions`             | OpenAI-compatible chat (stream=true for SSE)             | Inline markdown text (``💻 terminal``) — no separate tool events                                                               |
| `POST /v1/runs`                         | Start an agent run                                       | Returns `run_id`                                                                                                               |
| `GET /v1/runs/{run_id}/events`          | SSE stream of run lifecycle events                       | **Structured events**: `tool.started`, `tool.completed`, `message.delta`, `reasoning.available`, `run.completed`, `run.failed` |
| `POST /v1/responses`                    | OpenAI Responses API format                              | Structured `function_call` objects (non-streaming only)                                                                        |
| `GET /v1/capabilities`                  | Machine-readable feature + endpoint discovery            | Use before assuming optional surfaces exist                                                                                    |
| `GET /v1/models`                        | List available models                                    | —                                                                                                                              |
| `GET /v1/skills`                        | Read-only skill list for the API-server agent            | `{"object":"list","data":[...]}`                                                                                               |
| `GET /v1/toolsets`                      | Read-only API-server toolset inventory                   | `{"object":"list","platform":"api_server","data":[...]}`                                                                       |
| `GET/POST/PATCH/DELETE /api/sessions/*` | Native session CRUD, messages, fork, sync chat, SSE chat | Upstream merged via NousResearch/hermes-agent PR #33134                                                                        |
| `GET /health`                           | Health check                                             | —                                                                                                                              |
| `GET/POST/PATCH/DELETE /api/jobs/*`     | Cron job management (api_server surface)                 | —                                                                                                                              |


**Compatibility endpoints (not all native upstream API-server routes):**

Upstream main now contains the focused session-control API (`#33134`) and read-only skills/toolsets (`#33016`). The original broad PR [#8556](https://github.com/NousResearch/hermes-agent/pull/8556) was closed as superseded. Keep these distinctions straight:

1. **Native upstream** — `/api/sessions`, `/api/sessions/{id}/messages`, `/api/sessions/{id}/chat`, `/api/sessions/{id}/chat/stream`, `/v1/capabilities`, `/v1/skills`, and `/v1/toolsets` exist in current `gateway/platforms/api_server.py`.
2. **Bootstrap compatibility** (`plugin/hermes_relay_bootstrap/`) — monkey-patches aiohttp on startup via `.pth` file, injecting only compatibility-only surfaces (session search, memory, legacy skill detail/toggle, config, available-models, slash middleware). Sessions CRUD/messages/fork and the legacy skills list are **retired** — native upstream owns them (#33134/#33016) and the bootstrap carries no fallback for old builds. Native routes still win per method/path for the remaining set. The repo-root `hermes_relay_bootstrap/` package is a legacy import shim.
3. **Legacy fork branches** — useful as lineage only. Do not cite `feat/session-api` / `#8556` as the current upstream contract.


| Endpoint                               | Purpose                                | Provided by                                                                            |
| -------------------------------------- | -------------------------------------- | -------------------------------------------------------------------------------------- |
| `GET /api/sessions` (CRUD)             | Session list/create/rename/delete/fork | Native upstream (#33134); bootstrap injection retired                                  |
| `GET /api/sessions/{id}/messages`      | Conversation history                   | Native upstream (#33134); bootstrap injection retired                                  |
| `POST /api/sessions/{id}/chat`         | Synchronous session chat               | Native upstream (#33134)                                                               |
| `POST /api/sessions/{id}/chat/stream`  | Session-based SSE chat                 | Native upstream (#33134); bootstrap does NOT inject                                    |
| `GET /v1/skills`, `GET /v1/toolsets`   | Read-only skill/toolset discovery      | Native upstream (#33016)                                                               |
| `GET /api/sessions/search`             | Full-text message search               | Bootstrap/fork legacy; not in current upstream main                                    |
| `GET /api/config`, `PATCH /api/config` | Personalities + model config           | Bootstrap/fork legacy or dashboard web-server surface; not current API-server upstream |
| `GET /api/skills/{name}`               | Legacy skill detail                    | Bootstrap compat; list (`GET /api/skills`) retired — use native `/v1/skills`           |
| `PUT /api/skills/toggle`               | Enable/disable installed skill         | `hermes_cli/web_server.py` dashboard surface; bootstrap stub returns 501               |
| `GET/POST/PATCH/DELETE /api/memory`    | Memory CRUD                            | Bootstrap/fork legacy; not current API-server upstream                                 |
| `GET /api/available-models`            | Provider model list                    | Bootstrap/fork legacy; not current API-server upstream                                 |


The Android client probes per-endpoint capability via `HermesApiClient.probeCapabilities()` (returns `ServerCapabilities`). When `streamingEndpoint = "auto"`, `ConnectionViewModel.resolveStreamingEndpoint()` picks `sessions`, `completions`, or `runs` based on the capability snapshot.

**Dashboard web server (separate surface — standard Manage / Desktop remote gateway):**

hermes-agent ships a second web server at `hermes_cli/web_server.py` that hosts the React admin dashboard at `hermes_cli/web_dist/`. It has its **own** `/api/*` routes that **do not live on `api_server.py`** — notably: `GET/PUT /api/config` (full tree), `GET /api/config/schema`, `GET /api/config/defaults`, `GET/PUT /api/config/raw` (YAML text), `GET/PUT/DELETE /api/env` + `POST /api/env/reveal`, `PUT /api/skills/toggle`, `/api/cron/jobs/*` (different shape from `/api/jobs/*`), `/api/providers/oauth/*`, `/api/dashboard/themes`, `/api/dashboard/plugins`, `/api/model/info` + `/api/model/options` + `POST /api/model/set`, `/api/profiles/*` (CRUD, `POST /api/profiles/active`, per-profile soul/description/model), `/api/mcp/*`, `/api/logs`, `/api/analytics/usage`, and `**POST /api/audio/transcribe` + `POST /api/audio/speak`** (base64 data-url contract, built for hermes-desktop voice). The API server has **no audio routes** — its `/v1/capabilities` advertises `audio_api: false`; PR #8199 (`/v1/audio/*`) is the canonical future surface but is unmerged. Android's **Vanilla Hermes (no-plugin) voice** therefore rides this dashboard surface via `StandardHermesVoiceClient` with the per-connection dashboard cookie session (Manage sign-in unlocks voice); `AutoVoiceAudioClient` prefers Relay when paired and falls back to standard.

Current upstream supports two auth modes on this surface. Loopback dashboards still use the injected `window.__HERMES_SESSION_TOKEN__` path. Remote/non-loopback dashboards use the Desktop-style dashboard auth gate: `/api/status` advertises `auth_required` and providers, `/auth/password-login` handles password providers, `/auth/login?provider=...` handles Nous/OIDC redirects, `/api/auth/me` returns the verified session, and `/api/auth/ws-ticket` mints a short-lived ticket for `/api/ws` / `/api/pty`. This dashboard session is **not** an `API_SERVER_KEY`. Android uses it for Manage, Vanilla Hermes voice, and the gateway chat transport. `/api/ws` is backed by `tui_gateway/server.py` (what hermes-desktop + the Ink TUI speak) and is the only upstream surface with **live** `reasoning.delta`/`thinking.delta` streaming; the api_server SSE paths remain the SSE fallback. Relay-only capabilities remain behind Relay pairing. **Do not proxy dashboard auth or dashboard admin APIs over the relay.**

**Tool call rendering paths:**

1. **Runs API** — Emits `tool.started`/`tool.completed` as real SSE events → `ToolProgressCard` in real-time.
2. **Sessions API** — Native upstream emits structured SSE (`run.started`, `message.started`, `assistant.delta`, `tool.progress`, `tool.started/completed/failed`, `assistant.completed`, `run.completed`, `done`). `run.completed.messages` can reconcile authoritative per-turn transcript.
3. **Annotation parser** — Fallback for servers emitting inline markdown annotations (``💻 terminal``).

## Key Instructions

- **Vanilla Hermes path = upstream-only.** The default (no-plugin) connection path — gateway/API chat, Manage, and Vanilla Hermes voice via the dashboard surface — must work against **unmodified upstream hermes-agent**: no fork patches, no bespoke server config as a dependency. The app ships on Google Play to users whose servers we don't control. Features that need server-side changes go through upstream PRs (with graceful degradation until merged) or live behind the opt-in relay plugin.
- **Always verify upstream before assuming an endpoint exists.** Check `gateway/platforms/api_server.py` in hermes-agent. If an endpoint isn't there, document whether bootstrap injects it or it requires the fork.
- If we use a non-standard endpoint, ensure `probeCapabilities()` covers it and the auto-resolver degrades gracefully.
- **Bootstrap maintenance:** Retire `plugin/hermes_relay_bootstrap/` per surface. Done: sessions CRUD/messages/fork and the legacy skills list are retired from the bootstrap (native upstream #33134/#33016, no old-build fallback kept). Remaining: config, memory, legacy skill detail/toggle, available-models, session search, and slash middleware still need explicit replacement decisions before full removal.

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
├── relay-core/              ← [EXPERIMENTAL] Quest/XR shared core lib (com.axiomlabs.hermesrelay.core) — pairing, transport, terminal, voice, wire
├── relay-ui/                ← [EXPERIMENTAL] Quest/XR shared Compose UI lib — sphere, terminal WebView, QR scanner
├── quest/                   ← [EXPERIMENTAL] Meta Spatial SDK Quest/XR app (gradle includeBuild; in development, not shipped)
├── ui-preview/              ← Desktop Compose Hot Reload harness for PC UI iteration (NOT shipped; shares MorphingSphereCore)
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
├── website/                 ← Astro product/marketing site (static Coolify/Nixpacks deployment)
├── plugin/                  ← Hermes agent plugin
│   ├── android_tool.py      # 18 android_* tool handlers
│   ├── pair.py              # QR pairing implementation
│   ├── relay/               # Canonical WSS relay (server.py, auth.py, channels/, media.py, voice.py)
│   ├── tools/               # android_navigate.py, android_notifications.py
│   └── dashboard/           # hermes-agent dashboard plugin — manifest, React UI, FastAPI proxy
├── relay_server/            ← Thin compat shim → plugin.relay (legacy entrypoint)
├── hermes_relay_bootstrap/  ← Legacy import shim for older startup hooks
├── skills/devops/hermes-relay-pair/  ← /hermes-relay-pair slash command
├── scripts/                 ← dev.bat, bridge-smoke.sh, bump-version.sh
└── docs/                    ← spec, decisions, security, relay-server, mcp-tooling
```

## Project Conventions

### File Structure

- **Root-level:** README.md, CLAUDE.md, AGENTS.md, DEVLOG.md, TODO.md, .gitignore
- **docs/** — spec, decisions, security, and any other long-form documentation
- **DEVLOG.md** — update at end of each work session with what was done + verification (the factual record of *what happened*). It churns; do NOT park forward work here.
- **TODO.md** — the single home for follow-ups / deferred work / known gaps ("what's next"). Record them here — never buried in DEVLOG or scattered through code/doc comments where they get lost.
- **CLAUDE.md hygiene:** Key Files entries must stay one line — implementation detail belongs in the file or `docs/`. Run `/revise-claude-md` after feature-heavy sessions to trim drift.

### Public-repo writing hygiene

This is a **public, distributed repo** — every committed file (CHANGELOG, DEVLOG, README, docs, release notes) is public-facing. Write accordingly:

- **No personal names** in prose — attribute impersonally ("a user reported", "observed"). Author identity lives in git history + the signing cert, not the changelog.
- **No private infrastructure** — real server hostnames/IPs, internal deployment names, `~/SYSTEM.md` contents. (Generic example IPs like `192.168.1.100` in setup docs are fine.)
- **No AI/assistant process self-narration** — no "I should have…", no course-correction confessionals. State the technical conclusion, not the path to it.
- **No internal jargon / fork-branch plumbing** in user-facing notes — keep *what changed*, drop *where we staged it*.
- **CHANGELOG** uses Keep-a-Changelog grouping (Added / Changed / Fixed). Detail may accumulate during iteration, but at **release-prep the version block is condensed to crisp public bullets** (1–2 lines each) — deep "how we debugged it" stays in commits/DEVLOG. See [RELEASE.md](RELEASE.md) §2 "Scrub for public distribution".
- **DEVLOG.md** is a committed, factual engineering log — what changed, why, and verification — depersonalized and third-person, not a diary.

### Code Style — Android (Kotlin)

- **Jetpack Compose** — no XML layouts. Material 3 / Material You.
- **kotlinx.serialization** — not Gson. Type-safe, faster.
- **OkHttp** for WebSocket + SSE — `okhttp` for WSS relay, `okhttp-sse` for API streaming
- **Single-activity** — Compose Navigation for all routing
- **Namespace (Kotlin source tree):** `com.hermesandroid.relay` — stable, drives on-disk layout + class FQCNs
- **applicationId:** `com.axiomlabs.hermesrelay` (googlePlay), `com.axiomlabs.hermesrelay.sideload` (sideload)
- **Min SDK 26, Target SDK 35, Compile SDK 37** / **Kotlin 2.0+**, JVM toolchain 17

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
- **Branch/release policy:** follow the branch-contract table in `AGENTS.md` and
  the executable release and hotfix procedures in `RELEASE.md`. Do not maintain
  a Claude-specific parallel policy here.

### Testing

- **Android:** JUnit + Compose testing for UI, MockK for mocks
- **Python:** `python -m unittest plugin.tests.test_<name>` — avoid bare `pytest` (conftest imports `responses` which may not be installed in the venv)
- **CI and release gates:** follow the repository-wide requirements in
  `AGENTS.md` and `RELEASE.md`; Claude-specific guidance does not redefine them.

## Key Files


| File                                                        | Why                                                                                                                                                                                                                                                                                                 |
| ----------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `docs/spec.md`                                              | Full specification — protocol, UI layouts, phases, dependencies                                                                                                                                                                                                                                     |
| `docs/decisions.md`                                         | Architecture decisions — framework choice, channel design, auth model                                                                                                                                                                                                                               |
| `AGENTS.md`                                                 | Universal agent entry point — points here + the non-negotiables (standard-path, commits, writing hygiene)                                                                                                                                                                                           |
| `docs/mcp-tooling.md`                                       | MCP server setup — android-tools-mcp + mobile-mcp; `android_*` tool usage patterns                                                                                                                                                                                                                  |
| **App — Core**                                              |                                                                                                                                                                                                                                                                                                     |
| `ui/RelayApp.kt`                                            | Main scaffold (Scaffold + Compose nav); Chat is home — no mode strip, Manage/Bridge reached via Settings; `bottomBar` is a status pill, not a NavigationBar                                                                                                                                         |
| `viewmodel/ChatViewModel.kt`                                | Chat orchestration — send, stream, cancel, slash commands                                                                                                                                                                                                                                           |
| `viewmodel/ConnectionViewModel.kt`                          | Dual connection model (API + relay); `resolveStreamingEndpoint()`; derived `relayUiState` flow + `markPaired` hook stamp the active Connection                                                                                                                                                      |
| `viewmodel/RelayUiState.kt`                                 | Shared sealed state for the relay row — 5 cases + `asBadgeState()` / `statusText()` extensions; 5s grace window before Stale                                                                                                                                                                        |
| `network/HermesApiClient.kt`                                | Direct HTTP/SSE — `sendRunStream()`, `sendChatStream()`, `probeCapabilities()`                                                                                                                                                                                                                      |
| `network/GatewayChatClient.kt`                              | Gateway chat transport — JSON-RPC over dashboard `/api/ws` (tui_gateway); live `reasoning.delta`; fresh ws-ticket per connect; per-turn SSE fallback via `onPreflightFailure`; `prewarm()` (connect+resume off the send path); `setKeepAliveInBackground()` suppresses the 120s idle-close          |
| `network/GatewayKeepAliveService.kt`                        | Opt-in `specialUse` foreground service (BOTH flavors; declared in main manifest; Play needs a Console FGS declaration) holding the process up so the gateway socket survives background/Doze; driven by ConnectionViewModel from the `KEY_GATEWAY_KEEP_ALIVE` toggle; stops on task-removal         |
| `data/GatewayKeepAlivePrefs.kt`                             | Shared `KEY_GATEWAY_KEEP_ALIVE` pref key + `Context.setGatewayKeepAlive()` — used by ConnectionViewModel (StateFlow/setter) and the FGS Stop action                                                                                                                                                 |
| `network/GatewayEventMapper.kt`                             | Pure-JVM gateway event→callback mapping for one turn; unknown event types silently ignored; tui_gateway usage-key translation                                                                                                                                                                       |
| `network/GatewayModels.kt`                                  | `GatewayAvailability`, `ActiveTurnHandle`, `GatewayTurnCallbacks` (all members REQUIRED — forces dispatchOn main-thread wrap), `GatewayAsk`, `GatewaySubagentEvent`, `resolveStreamingEndpointPreference()`                                                                                         |
| `ui/components/ChatInputBar.kt`                             | Redesigned input bar — pill field, one trailing slot morphing Send/Voice/Stop/Steer/Queue, no slash button (long-press + opens palette)                                                                                                                                                             |
| `ui/components/SubagentLane.kt`                             | Per-taskIndex subagent progress lane — guide rail, compact tool rows, auto-collapse                                                                                                                                                                                                                 |
| `notifications/TurnCompleteNotifier.kt`                     | Turn-complete local notification when backgrounded — channel `chat_turn_complete`, cancel on resume, settings-gated                                                                                                                                                                                 |
| `network/ConnectionManager.kt`                              | WSS to relay with auto-reconnect; rebuilds OkHttpClient with fresh CertPinner on connect                                                                                                                                                                                                            |
| `network/ChannelMultiplexer.kt`                             | Envelope routing by channel; `sendNotification()` for notification outbound                                                                                                                                                                                                                         |
| `network/handlers/ChatHandler.kt`                           | Chat message state, streaming events, tool annotation parser                                                                                                                                                                                                                                        |
| `network/models/SessionModels.kt`                           | Session, message, SSE event data models                                                                                                                                                                                                                                                             |
| `data/FeatureFlags.kt`                                      | Feature gating — DEV_MODE + DataStore overrides; `BuildFlavor` (googlePlay/sideload Tier flags)                                                                                                                                                                                                     |
| **App — Auth**                                              |                                                                                                                                                                                                                                                                                                     |
| `auth/AuthManager.kt`                                       | Wires SessionTokenStore + CertPinStore; parses auth.ok; `applyServerIssuedCodeAndReset()`                                                                                                                                                                                                           |
| `auth/SessionTokenStore.kt`                                 | Keystore (StrongBox) + EncryptedSharedPrefs fallback; lossless migration on upgrade                                                                                                                                                                                                                 |
| `auth/CertPinStore.kt`                                      | TOFU cert pinning — SHA-256 SPKI per host:port in DataStore                                                                                                                                                                                                                                         |
| `auth/PairedSession.kt`                                     | PairedSession state + PairedDeviceInfo wire model                                                                                                                                                                                                                                                   |
| `data/Endpoint.kt`                                          | `EndpointCandidate` / `ApiEndpoint` / `RelayEndpoint` — multi-endpoint pairing (ADR 24); `displayLabel()` for LAN/Tailscale/Public/Custom chips                                                                                                                                                     |
| `network/RelayHttpClient.kt`                                | OkHttp for /media, /sessions (list/revoke/extend), /health                                                                                                                                                                                                                                          |
| **App — Bridge**                                            |                                                                                                                                                                                                                                                                                                     |
| `network/handlers/BridgeCommandHandler.kt`                  | Routes `bridge.command` → ActionExecutor; full path inventory + safety-rail integration                                                                                                                                                                                                             |
| `viewmodel/BridgeViewModel.kt`                              | BridgeScreen VM — masterToggle, bridgeStatus, permissionStatus, activityLog                                                                                                                                                                                                                         |
| `bridge/BridgeSafetyManager.kt`                             | Blocklist + destructive-verb confirmation + auto-disable timer; fails-closed on /call and /send_sms                                                                                                                                                                                                 |
| `data/BridgeSafetyPreferences.kt`                           | DataStore for blocklist, destructive verbs, auto-disable minutes, confirmation timeout                                                                                                                                                                                                              |
| `ui/screens/BridgeScreen.kt`                                | Bridge UI — master → permission checklist → [Advanced] → unattended → safety → activity log (v0.4.1 reorder)                                                                                                                                                                                        |
| `ui/components/UnattendedAccessRow.kt`                      | Unattended toggle card (sideload); `enabled=masterEnabled`; inline `KeyguardDetectedAlert`                                                                                                                                                                                                          |
| `ui/components/UnattendedGlobalBanner.kt`                   | 28dp amber strip at scaffold top when master+unattended on (sideload); tap → Bridge tab                                                                                                                                                                                                             |
| `bridge/BridgeStatusOverlay.kt`                             | WindowManager overlay; `ConfirmationOverlayHost`; requires `SavedStateRegistryOwner` init order (CREATED→restore→RESUMED)                                                                                                                                                                           |
| `accessibility/HermesAccessibilityService.kt`               | AccessibilityService subclass; `@Volatile instance` singleton for BridgeCommandHandler                                                                                                                                                                                                              |
| `accessibility/ScreenReader.kt`                             | UI tree → ScreenContent; `findNodeBoundsByText()`, `findFocusedInput()`                                                                                                                                                                                                                             |
| `accessibility/ActionExecutor.kt`                           | Gesture/text dispatch via GestureDescription + ACTION_SET_TEXT; pressKey maps vocab only                                                                                                                                                                                                            |
| **App — Voice**                                             |                                                                                                                                                                                                                                                                                                     |
| `voice/VoiceViewModel.kt`                                   | Voice turn state machine; TTS queue; `ignoreAssistantId`; `errorEvents: SharedFlow`                                                                                                                                                                                                                 |
| `audio/VoiceRecorder.kt`                                    | MediaRecorder wrapper; perceptual amplitude curve; `.m4a` at 16kHz/64kbps                                                                                                                                                                                                                           |
| `audio/VoicePlayer.kt`                                      | Media3 ExoPlayer (gapless TTS queue) + Visualizer; amplitude StateFlow; `awaitCompletion()` via coroutine; `audioSessionId` is a thread-safe `@Volatile` cache                                                                                                                                      |
| `network/RelayVoiceClient.kt`                               | OkHttp for `/voice/transcribe`, `/synthesize`, `/config`                                                                                                                                                                                                                                            |
| `voice/VoiceBridgeIntentHandler.kt`                         | Interface routing voice utterances to bridge; impls per flavor via factory                                                                                                                                                                                                                          |
| `voice/VoiceIntentClassifier.kt`                            | Regex phone-control classifier (sideload only); false-negatives preferred over false-positives                                                                                                                                                                                                      |
| `ui/components/VoiceModeOverlay.kt`                         | Full-screen voice UI — MorphingSphere + VoiceWaveform + mic button                                                                                                                                                                                                                                  |
| `ui/components/MorphingSphere.kt`                           | Compose renderer for the agent sphere — delegates math to `MorphingSphereCore`                                                                                                                                                                                                                      |
| `ui/components/MorphingSphereCore.kt`                       | Platform-agnostic sphere algorithm (`kotlin.math` only) — single source of truth; mirrored byte-for-byte in `preview/web/sphere.js`                                                                                                                                                                 |
| `preview/web/`                                              | Zero-dep browser harness — live `index.html` preview + `parity-check.mjs`; paired with `MorphingSphereCoreParityTest` (JVM) for struct/full checksum diffing                                                                                                                                        |
| `user-docs/.vitepress/theme/components/SphereMark.vue`      | Docs-site sphere embed — imports `preview/web/sphere.js` directly; autonomous fbm drift + pointer-proximity gaze/state blend; `<ClientOnly>` + `IntersectionObserver` + `prefers-reduced-motion` aware                                                                                              |
| **App — Media + Notifications**                             |                                                                                                                                                                                                                                                                                                     |
| `util/MediaCacheWriter.kt`                                  | `cacheDir/hermes-media/` LRU writer; returns FileProvider URIs                                                                                                                                                                                                                                      |
| `util/MediaSaver.kt`                                        | Save/share/open for chat media — MediaStore scoped-storage save (Pictures/Download `Hermes-Relay`, no perms on API 29+; pre-Q → share sheet); FileProvider share staging; remote-byte fetch; magic-byte image-MIME sniff for correct extensions                                                     |
| `ui/components/ChatImageViewer.kt`                          | Full-screen image viewer — pinch-zoom/pan (`detectTransformGestures`), double-tap 1×/2.5×, Share/Save/Close; `ChatImageViewerSource` decouples Coil-model/bitmap display from a suspend `bytesProvider` so Save keeps original bytes                                                                |
| `ui/components/InboundAttachmentCard.kt`                    | Discord-style attachment card for images/video/audio/pdf/text/generic; image tap → ChatImageViewer, file card long-press → Open/Share/Save menu                                                                                                                                                     |
| `ui/components/ChatImageContent.kt`                         | Parses `![alt](src)` out of assistant content; remote http(s) → Coil (tap → ChatImageViewer), server-local/failed → inline "can't render" notice with the path                                                                                                                                      |
| `data/HermesCard.kt`                                        | `CARD:{json}` envelope (ADR 26) — type/accent/fields/actions; kotlinx.serialization                                                                                                                                                                                                                 |
| `ui/components/HermesCardBubble.kt`                         | Rich-card renderer — accent stripe + FlowRow actions + dispatch stamp collapse                                                                                                                                                                                                                      |
| `viewmodel/CardDispatchSyncBuilder.kt`                      | Twin of VoiceIntentSyncBuilder — synthesizes card dispatches as `hermes_card_action` OpenAI pairs for session memory                                                                                                                                                                                |
| `notifications/HermesNotificationCompanion.kt`              | NotificationListenerService; cold-start buffer (50); forwards via ChannelMultiplexer                                                                                                                                                                                                                |
| `util/RelayErrorClassifier.kt`                              | `classifyError(Throwable, context) → HumanError`; used by Voice/Chat/Connection                                                                                                                                                                                                                     |
| `util/TurnLatencyTracer.kt`                                 | One `TurnLatency` INFO line per chat turn — `warm/cold` + `connect/session/submit/ttfe/ttft/done@…ms`; gateway + 3 SSE paths use it for desktop-comparable latency diagnosis; durations only                                                                                                        |
| **Relay — Server**                                          |                                                                                                                                                                                                                                                                                                     |
| `plugin/relay/server.py`                                    | Canonical relay — WSS + HTTP routes; bridge, media, voice, session, pairing handlers. `handle_pairing_mint` mirrors `pair.py:762` — top-level = API server, `relay.{url,code}` nested                                                                                                               |
| `plugin/relay/auth.py`                                      | PairingManager, SessionManager, RateLimiter; `math.inf` for never-expire                                                                                                                                                                                                                            |
| `plugin/relay/channels/bridge.py`                           | Bridge handler — `handle_command()` mints request_id, awaits response, 30s timeout                                                                                                                                                                                                                  |
| `plugin/relay/channels/notifications.py`                    | Bounded deque (100) of notification metadata; in-memory only                                                                                                                                                                                                                                        |
| `plugin/relay/media.py`                                     | MediaRegistry — LRU token store; `strict_sandbox` off by default for `/media/by-path`                                                                                                                                                                                                               |
| `plugin/relay/voice.py`                                     | Voice endpoints — transcribe, synthesize, voice_config; lazy tool imports                                                                                                                                                                                                                           |
| `plugin/relay/qr_sign.py`                                   | HMAC-SHA256 QR signing; secret at `~/.hermes/hermes-relay-qr-secret`; canonical form preserves `endpoints` array order + role strings verbatim (ADR 24)                                                                                                                                             |
| `plugin/relay/tailscale.py`                                 | First-class Tailscale helper (ADR 25) — `status()` / `enable(port)` / `disable(port)` / `canonical_upstream_present()`; safe-absent via shell-out to `tailscale` CLI                                                                                                                                |
| `plugin/relay/_env_bootstrap.py`                            | Loads `~/.hermes/.env` before relay imports; called from both entry points                                                                                                                                                                                                                          |
| **Plugin — Tools + Installer**                              |                                                                                                                                                                                                                                                                                                     |
| `plugin/tools/android_tool.py`                              | 18 `android_*` tool handlers (14 baseline + send_sms, call, search_contacts, return_to_hermes); `android_screenshot` first consumer of `register_media()`                                                                                                                                           |
| `plugin/tools/android_navigate.py`                          | Vision-driven navigation loop; up to 20 iterations; `llm_gap` error until vision client wired                                                                                                                                                                                                       |
| `plugin/pair.py`                                            | QR payload builder + CLI; `build_payload(sign=True)`; `--register-code` fallback                                                                                                                                                                                                                    |
| `plugin/doctor.py`                                          | `hermes relay doctor`; checks standard upstream API/dashboard reachability, Relay loopback state, plugin layout, and compat hook state                                                                                                                                                              |
| `plugin/compat.py`                                          | `hermes relay compat status/install/remove`; owns the optional `hermes_relay_bootstrap.pth` lifecycle                                                                                                                                                                                               |
| `plugin/hermes_relay_bootstrap/`                            | Plugin-owned runtime compatibility patch — compat-only surfaces (session search, memory, skill detail/toggle, config, available-models, slash middleware); sessions + skills-list injection retired (#33134/#33016)                                                                                 |
| `install.sh`                                                | Canonical installer — 6 steps; idempotent; drops `hermes-relay-update` shim                                                                                                                                                                                                                         |
| `uninstall.sh`                                              | Canonical uninstaller; reverses install.sh; never touches `.env` or `state.db`                                                                                                                                                                                                                      |
| `hermes_relay_bootstrap/`                                   | Legacy import shim for old `.pth` files and editable installs                                                                                                                                                                                                                                       |
| **Plugin — Dashboard**                                      |                                                                                                                                                                                                                                                                                                     |
| `plugin/dashboard/manifest.json`                            | Declares tab, entry bundle, and FastAPI module for hermes-agent discovery                                                                                                                                                                                                                           |
| `plugin/dashboard/plugin_api.py`                            | FastAPI router proxying 5 routes to relay over loopback; `/pairing` body = API-server overrides (host/port/tls/api_key), relay URL auto-derived                                                                                                                                                     |
| `plugin/dashboard/src/index.jsx`                            | React root registering `hermes-relay` plugin with 4-tab shell                                                                                                                                                                                                                                       |
| `plugin/dashboard/dist/index.js`                            | Committed IIFE bundle loaded verbatim by dashboard                                                                                                                                                                                                                                                  |
| **Desktop CLI**                                             |                                                                                                                                                                                                                                                                                                     |
| `desktop/package.json`                                      | `@hermes-relay/cli` package manifest — Node ≥21, one `hermes-relay` bin, pre-built dist                                                                                                                                                                                                             |
| `desktop/bin/hermes-relay.js`                               | Tiny shim: `import('../dist/cli.js').then(m => m.main())` + error surfacing                                                                                                                                                                                                                         |
| `desktop/src/chatAttach.ts`                                 | captureClipboardImage / captureScreenshot / readImageFile; ships base64 to server via `image.attach.bytes` RPC before next prompt.submit                                                                                                                                                            |
| `desktop/src/cli.ts`                                        | argv parser + subcommand dispatcher — bare → `shell` (PTY), positional-only → `chat`; command-scoped `--help` falls through to each command                                                                                                                                                         |
| `desktop/src/lib/theme.ts`                                  | Shared ANSI palette + `colorEnabled()` + `Theme` (semantic helpers, `statusDot`) — single visual language; `--no-color`/`NO_COLOR`/TTY aware                                                                                                                                                        |
| `desktop/src/lib/table.ts`                                  | Zero-dep column-aligned table renderer (ANSI-width aware, last column flexes to terminal width) — used by devices/sessions/audit                                                                                                                                                                    |
| `desktop/src/lib/spinner.ts`                                | Stderr braille spinner for slow ops (pair probe, gateway connect); no-op when piped/quiet/json                                                                                                                                                                                                      |
| `desktop/src/lib/usage.ts`                                  | `UsageSpec` + `renderUsage`/`printUsage`/`unknownSubcommand` — per-subcommand `--help` + self-documenting sub-verb fallback                                                                                                                                                                         |
| `desktop/src/lib/hints.ts`                                  | `suggestedFix(err, ctx)` → next-step command (re-pair on auth fail, etc.); `formatError` renders error + hint                                                                                                                                                                                       |
| `desktop/src/lib/logo.ts`                                   | Slim box-drawing "Hermes Relay" wordmark; shown atop `--help`, first-run welcome, REPL header, and `hermes-relay logo`; theme/no-color aware                                                                                                                                                        |
| `desktop/src/lib/auditLog.ts`                               | Local desktop-tool audit JSONL (`~/.hermes/desktop-audit.jsonl`); router appends per dispatch; backs `audit` command (relay's ring is loopback-only)                                                                                                                                                |
| `desktop/src/lib/daemonStatus.ts`                           | Daemon heartbeat file (`~/.hermes/daemon-status.json`) + `isPidAlive` liveness; backs `daemon --status`                                                                                                                                                                                             |
| `desktop/src/commands/audit.ts`                             | `hermes-relay audit` — tails the local audit log into a table (WHEN/TOOL/STATUS/DETAIL); `--limit`, `--json`                                                                                                                                                                                        |
| `desktop/src/commands/relay.ts`                             | `hermes-relay relay info/security/context/queue` — relay-server management surface; info/security/queue loopback-only, context works remote with bearer; `queue` lists/cancels the agent→phone outbound buffer (`--clear` / `--cancel <id>`)                                                        |
| `desktop/src/commands/chat.ts`                              | REPL + one-shot + piped-stdin; `runOneTurn` returns `{promise, cancel}` for safe SIGINT; auto-wires `DesktopToolRouter` when consented                                                                                                                                                              |
| `desktop/src/commands/shell.ts`                             | Pipes the `terminal` relay channel to raw-mode stdin/stdout; post-attach `exec hermes` 350ms after tmux settles; `Ctrl+A .` detach / `Ctrl+A k` kill / `Ctrl+A Ctrl+A` literal                                                                                                                      |
| `desktop/src/commands/pair.ts`                              | Either 6-char code + `--remote`, or full v3 QR via `--pair-qr` — probes + picks endpoint, records role; `--grant-tools` (TTY prompt) / `--auto-grant-tools` (silent) stamp `toolsConsented` so `daemon` works without a `shell` round-trip                                                          |
| `desktop/src/commands/tools.ts`                             | `tools.list` RPC → enabled/available toolsets; `--verbose` lists individual tools                                                                                                                                                                                                                   |
| `desktop/src/commands/status.ts`                            | Local read of `~/.hermes/remote-sessions.json`; renders `grants:` + `expires:` + `route:`; `--json` redacts tokens, `--reveal-tokens` opts in                                                                                                                                                       |
| `desktop/src/commands/devices.ts`                           | Server-side session management — `GET/DELETE/PATCH /sessions` via `fetch` over http(s)://host:port; `list` / `revoke <prefix>` / `extend <prefix> --ttl <s>`                                                                                                                                        |
| `desktop/src/banner.ts`                                     | `buildConnectBanner({url, meta, endpointRole})` → "Connected via LAN (plain) — server 0.6.0"; `humanExpiry()` for TTL formatting                                                                                                                                                                    |
| `desktop/src/endpoint.ts`                                   | `EndpointCandidate` / `EndpointRole` types + `displayLabel()` — mirrors Android `data/Endpoint.kt`                                                                                                                                                                                                  |
| `desktop/src/pairingQr.ts`                                  | `decodePairingPayload` (JSON or base64), `payloadToCandidates` (v3 verbatim / v1–v2 synthesized), `probeCandidatesByPriority` (`Promise.any` within tier, `AbortSignal.any`, 4s timeout, 60s cache)                                                                                                 |
| `desktop/src/certPin.ts`                                    | `extractSpkiSha256(der)` via `crypto.X509Certificate` + `publicKey.export({type:'spki'})`; `pinKey(url)`, `comparePins()`, `isSecureUrl()`                                                                                                                                                          |
| `desktop/src/tools/router.ts`                               | `DesktopToolRouter.attach(relay)` — `onChannel('desktop')` dispatch under 30s `AbortController`; heartbeat enriched with host/platform/version/uptime_ms + sticky `last_error` for `desktop_health`                                                                                                 |
| `desktop/src/tools/handlerSet.ts`                           | Single source of truth for the desktop tool map — `DESKTOP_HANDLERS` + `DESKTOP_ADVERTISED_TOOLS`; consumed by `chat.ts` / `shell.ts` / `daemon.ts` so adding a tool is a one-file change                                                                                                           |
| `desktop/src/tools/consent.ts`                              | `ensureToolsConsent(url)` — stored per-URL in `toolsConsented`; TTY prompt; non-TTY fails closed                                                                                                                                                                                                    |
| `desktop/src/tools/handlers/fs.ts`                          | `readFileHandler` / `writeFileHandler` / `patchHandler` — strict unified-diff applier, no fuzz                                                                                                                                                                                                      |
| `desktop/src/tools/handlers/terminal.ts`                    | `bash -lc` / `cmd /c`, SIGKILL on timeout or abort, returns `{stdout, stderr, exit_code, duration_ms}`                                                                                                                                                                                              |
| `desktop/src/tools/handlers/powershell.ts`                  | Spawns `pwsh`/`powershell` directly with `-Command -`, script piped via stdin — no cmd.exe quote-mangling; auto-picks pwsh &gt; powershell                                                                                                                                                          |
| `desktop/src/tools/handlers/process.ts`                     | `spawn_detached` (unref'd, returns pid+log_path), `list_processes` (tasklist /FO CSV — no /V to dodge window-title latency), `kill_process`, `find_pid_by_port` (netstat/lsof/ss)                                                                                                                   |
| `desktop/src/tools/handlers/jobs.ts`                        | Job API — `~/.hermes/desktop-jobs/<id>/{stdout.log, stderr.log, meta.json}` is source of truth across daemon restarts; `taskkill /T` on Windows so build trees die fully                                                                                                                            |
| `desktop/src/tools/handlers/transfer.ts`                    | `copy_directory` via `fs.cp`, `zip`/`unzip` via tar &gt; zip &gt; PowerShell probe, `checksum` streamed (sha256/sha1/md5)                                                                                                                                                                           |
| `desktop/src/tools/handlers/search.ts`                      | ripgrep with pure-Node fallback, skips `.git`/`node_modules`/`dist`/`.next`/`.cache`                                                                                                                                                                                                                |
| `desktop/src/renderer.ts`                                   | Streams `message.delta` → stdout, tool events → decorated lines; NO_COLOR / --json / --quiet aware                                                                                                                                                                                                  |
| `desktop/src/pairing.ts`                                    | readline-based 6-char prompt (`A-Z0-9`); headless mirror of TUI's Ink prompt; `validatePairingPayloadString` discriminated-union wrapper                                                                                                                                                            |
| `desktop/src/credentials.ts`                                | Precedence: `--token` → `--pair-qr` (probe+pair) → `--code` → stored → prompt; returns `Credentials{sessionToken?, pairingCode?, resolvedEndpoint?}`                                                                                                                                                |
| `desktop/src/transport/RelayTransport.ts`                   | Fork of ui-tui's transport + reconnect state machine (`idle/connecting/connected/reconnecting`, exp backoff 1→30s, 5min on 429, gate re-check post-sleep) + pre-WS TLS probe for TOFU                                                                                                               |
| `desktop/src/remoteSessions.ts`                             | Same file path as TUI (`~/.hermes/remote-sessions.json`, 0600); schema widened with `grants`, `ttlExpiresAt`, `endpointRole`, `toolsConsented`; `saveSession` back-compat overload                                                                                                                  |
| `desktop/src/commands/daemon.ts`                            | Headless WSS + tool router for always-on access; JSON-line logs; fails closed on missing consent unless `--allow-tools` with explicit `--token`                                                                                                                                                     |
| `desktop/src/commands/doctor.ts`                            | Local-only diagnostic report — version / binary path / PATH / sessions / daemon detection; `--json` for support-paste; omits tokens entirely                                                                                                                                                        |
| `desktop/src/relayUrlPrompt.ts`                             | First-run URL fallback — `resolveFirstRunUrl()` auto-picks single stored session, numbered picker for multiple, welcome banner for zero; throws on non-interactive + ambiguous                                                                                                                      |
| `desktop/src/version.ts`                                    | Build-time-generated constant (`npm run gen:version` before every build) — Bun compiled binaries can't read package.json via `__dirname` so version is embedded at build                                                                                                                            |
| `desktop/scripts/install.sh` / `install.ps1`                | curl/iwr one-liner installers — download prebuilt Bun binary (no Node required), SHA256-verified, API-resolver for `latest` that includes prereleases, version-aware pre/post-install readback                                                                                                      |
| `desktop/scripts/uninstall.sh` / `uninstall.ps1`            | 3-tier removal — default (binary + PATH), `--purge` (also wipes `~/.hermes/remote-sessions.json`), `--service` (stub for future service installers); Windows iex-safe env-var fallback                                                                                                              |
| `desktop/README.md`                                         | User-facing install + usage reference                                                                                                                                                                                                                                                               |
| **Desktop CLI — dev iteration**                             |                                                                                                                                                                                                                                                                                                     |
| `npm run smoke` (in `desktop/`)                             | Builds Windows binary + runs `--version` / `--help` / `doctor`, fails loud on zero-output. Local pre-flight before cutting any tag.                                                                                                                                                                 |
| `npm run gen:version`                                       | Regenerates `src/version.ts` from `package.json`. Runs automatically before every `build` / `build:bin:*`.                                                                                                                                                                                          |
| `release-cli.yml → Smoke-test Linux binary` step            | CI-side equivalent: runs compiled Linux binary through the same 3-command check before uploading assets. Catches silent-exit-0 + segfault classes.                                                                                                                                                  |
| **Server — Desktop tool routing (Phase B)**                 |                                                                                                                                                                                                                                                                                                     |
| `plugin/relay/channels/desktop.py`                          | Mirrors `bridge.py` — `desktop.command`/`desktop.response`/`desktop.status`, UUID-correlated futures, 30s timeout, single-client MVP, per-session advertised-tools set                                                                                                                              |
| `plugin/tools/desktop_tool.py`                              | 24 `desktop_*` tools (fs/shell/powershell/process/jobs/transfer/health) — registers with `tools.registry` under `desktop` toolset; per-tool `check_fn` pings `/desktop/_ping?tool=<name>`; `desktop_health` is `_RELAY_ONLY` and pings `/desktop/health` so it works even when the client is wedged |
| **Gradle modules — experimental Quest/XR (in development)** |                                                                                                                                                                                                                                                                                                     |
| `relay-core/`                                               | [EXPERIMENTAL] Android library (`com.axiomlabs.hermesrelay.core`) — shared pairing/transport/terminal/voice/wire for the Quest port; not yet wired into the shipped `:app`                                                                                                                          |
| `relay-ui/`                                                 | [EXPERIMENTAL] Android library (`com.axiomlabs.hermesrelay.ui`) — shared Compose UI (sphere, terminal WebView, QR scanner) for the Quest port; carries its own sphere copy                                                                                                                          |
| `quest/`                                                    | [EXPERIMENTAL] Meta Spatial SDK Quest/XR app — gradle `includeBuild("quest")`; needs further development, not shipped                                                                                                                                                                               |
| **Tooling — dev iteration (not shipped)**                   |                                                                                                                                                                                                                                                                                                     |
| `ui-preview/`                                               | Desktop Compose Hot Reload harness — JVM Compose for Desktop; source-shares `MorphingSphereCore` from `:relay-ui`; `Main.kt` gallery; see `ui-preview/README.md`                                                                                                                                    |
| `app/src/test/.../screenshots/StoreScreenshotTest.kt`       | Roborazzi host-side store/docs screenshot renderer — deterministic, no device, exact 1080×2160; reuses real components+chrome with mock data; `capture(name, themeId){…}` renders any view; see `docs/screenshot-automation.md` §Deterministic rendering (JDK-21 + no-plugin gotchas)               |


## What NOT to Do

- **Don't use XML layouts** — Compose only
- **Don't use Gson** — kotlinx.serialization
- **Don't use Ktor for networking** — OkHttp for WebSocket
- **Don't use plaintext WebSocket** — `wss://` only, even in development
- **Don't put documentation in root** — long-form docs go in `docs/`
- **Don't forget DEVLOG.md** — update it (record *what happened*)
- **Don't bury follow-ups** — deferred work / known gaps go in `TODO.md`, never in DEVLOG or one-off code/doc comments
- **Don't touch production / remote hosts** — automation and orchestrated agents must NEVER SSH into, deploy to, pull/restart/reconfigure, or push code to a live/remote Hermes host. Building, on-device testing, and server deployment are owner-driven (see Server Deployment). Stop at committing on your branch; surface "this needs a deploy/on-device check" rather than doing it.

## MCP Tooling

Two MCP servers are configured for AI-assisted development. See `docs/mcp-tooling.md` for full reference.


| Server              | Layer                                                           | Requires                                 |
| ------------------- | --------------------------------------------------------------- | ---------------------------------------- |
| `android-tools-mcp` | IDE/Build — Compose previews, Gradle, code search, Android docs | Android Studio running with project open |
| `mobile-mcp`        | Device/Runtime — tap, swipe, screenshot, app management         | ADB + connected device/emulator          |


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
4. **Before pushing Kotlin changes** — run `./gradlew lint` locally. It's the exact task CI runs and catches errors Android Studio's live inspections miss — e.g. `UnsafeOptInUsageError` with `kotlin.OptIn` vs `androidx.annotation.OptIn`, `FlowOperatorInvokedInComposition` (mapped flows inside Composables), Media3 `@UnstableApi` propagation. Android CI runs lint alongside build/test for faster feedback, but a local lint run still surfaces issues before the workflow spends runner time compiling and packaging.
5. **Commit + push** — follow `AGENTS.md` and `RELEASE.md`; normal work PRs to `dev`.
6. **Pull + restart on server** — see Server Deployment below.
7. **Test on phone** — Bailey builds from Studio, installs to Samsung device, pairs via `/hermes-relay-pair`.

### Server Deployment

Server is a Linux box running hermes-agent with hermes-relay editable-installed (`pip install -e`). Sensitive details (IP, user, secrets) in `~/SYSTEM.md` on the server — not in this repo.


| What               | Where                                                              |
| ------------------ | ------------------------------------------------------------------ |
| hermes-agent repo  | `~/.hermes/hermes-agent/`                                          |
| hermes-relay clone | `~/.hermes/hermes-relay/`                                          |
| Plugin symlink     | `~/.hermes/plugins/hermes-relay` → `~/.hermes/hermes-relay/plugin` |
| Config             | `~/.hermes/config.yaml` + `~/.hermes/.env`                         |
| Relay log          | `journalctl --user -u hermes-relay -f`                             |


**Update:** `hermes-relay-update` (idempotent, re-fetches install.sh). Or manually: `git pull --ff-only && systemctl --user restart hermes-relay`.

**Compat hook:** `hermes relay compat status/install/remove` manages only the

optional `hermes_relay_bootstrap.pth` startup hook. New installs load the

plugin-owned bootstrap from `plugin/hermes_relay_bootstrap/`; the repo-root

package is only a legacy import shim. Vanilla Hermes chat, Manage, and dashboard voice

must not depend on this hook.

**Key conventions:**

- Phone pairing **survives** relay restart — `SessionManager` persists sessions to `~/.hermes/hermes-relay-sessions.json` (`server.py:88-90`, `persistence_path` from `RelayConfig.from_env`); a trusted-device refresh token recovers a lost/revoked/reset session without a new QR scan. (Only the in-memory *live-connection presence* clears on restart; the phone reconnects automatically.)
- Use `python -m unittest` not `pytest` — conftest imports `responses` which may not be installed
- `_env_bootstrap.py` loads `~/.hermes/.env` on every relay start — no stale API keys

### Where Python vs. Kotlin changes land


| Change type                          | Who restarts?            | Command                                            |
| ------------------------------------ | ------------------------ | -------------------------------------------------- |
| Plugin tool (`android_tool.py` etc.) | `hermes-gateway.service` | `systemctl --user restart hermes-gateway`          |
| Relay code (`plugin/relay/*.py`)     | `hermes-relay.service`   | `systemctl --user restart hermes-relay`            |
| Pair CLI / skill files               | —                        | No restart — fresh process / scanned on invocation |
| Android app                          | Bailey (Studio)          | Studio run button                                  |


### Release Process

See [AGENTS.md](AGENTS.md) for the canonical branch contract and
[RELEASE.md](RELEASE.md) for version sources, release trains, surface tags,
hotfixes, secrets, publishing, and verification. Claude-specific automation
must not infer release authority from feature completion.

## Integration Points


| Surface                        | Endpoint                                                                                                            | Notes                                                                                                                                                                                                                                                                                       |
| ------------------------------ | ------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Chat (gateway)                 | Dashboard `POST /api/auth/ws-ticket` -&gt; WS `/api/ws`                                                             | Vanilla Hermes dashboard/tui_gateway path; live thinking/reasoning; requires dashboard auth                                                                                                                                                                                                 |
| Chat streaming                 | `POST /v1/runs` → `GET /v1/runs/{id}/events`                                                                        | Structured tool events; async run-control path                                                                                                                                                                                                                                              |
| Chat (sessions)                | `POST /api/sessions/{id}/chat/stream`                                                                               | Native upstream session-persisted SSE; preferred when capability probe finds it                                                                                                                                                                                                             |
| Chat (compat)                  | `POST /v1/chat/completions` (stream=true)                                                                           | Inline tool annotations only                                                                                                                                                                                                                                                                |
| Session CRUD                   | `GET/POST/PATCH/DELETE /api/sessions`                                                                               | Native upstream (#33134); bootstrap fallback retired                                                                                                                                                                                                                                        |
| Manage                         | Dashboard `/api/status`, `/api/auth/me`, `/api/config`, `/api/profiles/*`, `/api/env`, `/api/model/*`, `/api/mcp/*` | Vanilla Hermes dashboard surface; do not proxy through Relay                                                                                                                                                                                                                                |
| Vanilla Hermes voice           | Dashboard `POST /api/audio/transcribe`, `POST /api/audio/speak`                                                     | Vanilla Hermes no-plugin voice; uses dashboard session from Manage                                                                                                                                                                                                                          |
| Pairing (QR)                   | `POST /pairing/register` (loopback only)                                                                            | Via `/hermes-relay-pair` or `hermes-pair` shim; accepts optional `endpoints` for multi-endpoint QRs                                                                                                                                                                                         |
| Pairing (multi-endpoint)       | QR `endpoints` array (ADR 24)                                                                                       | `hermes: 3` schema; ordered `lan`/`tailscale`/`public`/... candidates; phone re-probes on network change                                                                                                                                                                                    |
| Pairing auth                   | WSS `auth.ok` payload                                                                                               | Includes `expires_at`, `grants`, `transport_hint`                                                                                                                                                                                                                                           |
| Tailscale Serve (ADR 25)       | `hermes-relay-tailscale enable|disable|status` CLI                                                                  | Fronts loopback `:8767` with `tailscale serve --bg --https=<port>`; auto-retires on upstream PR #9295                                                                                                                                                                                       |
| Inbound media (token)          | `GET /media/{token}`                                                                                                | Bearer auth; 24h TTL                                                                                                                                                                                                                                                                        |
| Inbound media (path)           | `GET /media/by-path?path=<abs>`                                                                                     | Permissive by default; `RELAY_MEDIA_STRICT_SANDBOX=1` to restrict                                                                                                                                                                                                                           |
| Session management             | `GET /sessions`, `DELETE /sessions/{prefix}`, `PATCH /sessions/{prefix}`                                            | List/revoke/extend; RelayHttpClient                                                                                                                                                                                                                                                         |
| Voice transcribe               | `POST /voice/transcribe`                                                                                            | multipart/form-data; bearer auth                                                                                                                                                                                                                                                            |
| Voice synthesize               | `POST /voice/synthesize`                                                                                            | JSON → audio/mpeg; max 5000 chars                                                                                                                                                                                                                                                           |
| Voice config                   | `GET /voice/config`                                                                                                 | Returns current tts/stt provider info                                                                                                                                                                                                                                                       |
| Plugin diagnostics             | `hermes relay doctor --json`                                                                                        | Reports upstream route reachability, Relay loopback state, plugin layout, and legacy bootstrap state                                                                                                                                                                                        |
| Compat hook lifecycle          | `hermes relay compat status/install/remove`                                                                         | Optional legacy API compatibility hook; not required for the standard path                                                                                                                                                                                                                  |
| Notifications                  | `GET /notifications/recent?limit=N`                                                                                 | Loopback callers skip bearer                                                                                                                                                                                                                                                                |
| Relay health                   | `GET /health` on `:8767`                                                                                            | Used by `RelayHttpClient.probeHealth()`                                                                                                                                                                                                                                                     |
| Capabilities                   | `GET /v1/capabilities` plus targeted `HEAD` probes                                                                  | Prefer capabilities when present; HEAD probes keep mixed-version fallback working                                                                                                                                                                                                           |
| Desktop CLI (tui channel)      | WSS `tui.attach` / `tui.rpc.request` / `tui.rpc.event`                                                              | Same channel + envelopes as the Ink TUI — the CLI just renders events as plain lines. Zero server changes.                                                                                                                                                                                  |
| Desktop CLI (terminal channel) | WSS `terminal.attach` / `terminal.input` / `terminal.output` / `terminal.resize` / `terminal.detached`              | Existing channel (shared with Android). CLI `shell` subcommand attaches, injects `clear; exec hermes\n` 350ms after ack, pipes raw bytes. `Ctrl+A .` detaches (tmux preserved), `Ctrl+A k` kills.                                                                                           |
| Desktop CLI tool visibility    | `tools.list` RPC on the shared tui channel                                                                          | Returns `{toolsets: [{name, description, tool_count, enabled, tools:[]}]}`; surfaced by `hermes-relay tools`                                                                                                                                                                                |
| Desktop CLI devices            | HTTP `GET/DELETE/PATCH /sessions` on the relay's same port                                                          | Wrapped by `hermes-relay devices list                                                                                                                                                                                                                                                       |
| Desktop tool routing (Phase B) | WSS `desktop.command` (s→c) + `desktop.response` (c→s) + `desktop.status` (c→s heartbeat)                           | New channel. Hermes calls `desktop_read_file(path)` → Python handler POSTs to `/desktop/desktop_read_file` → relay forwards over `desktop.command` → Node client's `DesktopToolRouter` runs the handler locally → response bubbles back. Mirror of Android's `bridge.command` pattern.      |
| Desktop tool check_fn          | HTTP `GET /desktop/_ping?tool=<name>`                                                                               | Returns 200 if a client is connected AND advertises this tool; 503 otherwise. Hermes uses this to fail the tool quickly when no desktop client is live, instead of waiting 30s for the dispatch timeout.                                                                                    |
| Desktop health                 | HTTP `GET /desktop/health`                                                                                          | Returns full status snapshot — connected/host/platform/version/pid/uptime/advertised_tools/last_error/recent_commands. Loopback-only. Backs the `desktop_health` agent tool, which intentionally does NOT round-trip through the client so it remains callable when other tools are wedged. |


## Upstream References


| Topic                      | Upstream File                                                                 |
| -------------------------- | ----------------------------------------------------------------------------- |
| API endpoints              | `gateway/platforms/api_server.py` — all registered HTTP routes                |
| Platform adapter interface | `gateway/platforms/base.py` — `BasePlatformAdapter` abstract class            |
| Adding a platform          | `gateway/platforms/ADDING_A_PLATFORM.md` — 16-step checklist                  |
| Platform registration      | `gateway/run.py` → `_create_adapter()`, `gateway/config.py` → `Platform` enum |
| Channel directory          | `gateway/channel_directory.py` — how platforms/channels are enumerated        |
| Send message routing       | `tools/send_message_tool.py` → `platform_map` dict                            |
| SSE streaming (runs)       | `gateway/platforms/api_server.py` → runs endpoint, `_on_tool_progress`        |


## Related Projects

- [**hermes-agent**](https://github.com/NousResearch/hermes-agent) — the agent platform (gateway, WebAPI, plugin system)
- [**android-tools-mcp**](https://github.com/Codename-11/android-tools-mcp) — our fork of Android Studio MCP bridge (Compose previews, Gradle, docs)
- [**mobile-mcp**](https://github.com/mobile-next/mobile-mcp) — device control MCP server (ADB, tap/swipe, screenshots)

