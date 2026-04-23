# Hermes-Relay — Decisions & Implementation Guide

> Updated: 2026-04-06
>
> Read this before SPEC.md — it tells you what to build, what was deferred, and why.

---

## Framework Decision: Kotlin + Jetpack Compose

**Chosen over:** React Native, Flutter, Kotlin + XML (upstream)

**Why Compose:**
- 80% of the app is native Android services (AccessibilityService, PTY, foreground services, biometrics). Cross-platform frameworks would still need Kotlin native modules for all of that, plus a bridge layer.
- Compose is declarative like React — same mental model (state → UI), different syntax. Familiar to React developers.
- Material 3 / Material You theming is first-class. Dynamic color from wallpaper, proper motion system.
- OkHttp WebSocket supports `wss://` natively. No bridge layer to debug.
- Background foreground service keeps the WSS connection alive when the phone locks. React Native's background execution is fragile.
- Single language (Kotlin) for the entire app — services, UI, networking.

**Why not React Native:**
- Would need native modules for: AccessibilityService, foreground service, biometric auth, EncryptedSharedPreferences, MediaProjection. That's most of the app written in Kotlin anyway, plus JS bridge overhead.
- Background reliability issues on Android are well-documented.
- Only makes sense if the UI were 80%+ of the codebase. Here it's ~20%.

**Why not Flutter:**
- Same native bridge problem as RN, but with Dart (new language) instead of familiar React patterns.
- Platform channels for every native API.
- Smaller Android-specific ecosystem for security/biometric libraries.

**Why not staying with Kotlin + XML (upstream):**
- XML layouts are legacy. Compose is the modern Android UI toolkit.
- Upstream UI is functional but not polished. Full rewrite needed anyway.
- Compose gives us better animation, theming, and state management primitives.

---

## Architecture Decisions

### 1. Single WSS Connection with Channel Multiplexing

**Decision:** One WebSocket connection carries all three channels (chat, terminal, bridge) via typed message envelopes.

**Why:** Simpler connection management, single auth flow, single reconnect handler. Mobile networks are flaky — one connection is easier to keep alive than three.

**Trade-off:** If one channel floods (e.g., terminal output), it could delay others. Mitigated by: terminal output batching (16ms frames), priority queuing (system > chat > terminal > bridge).

### 2. Relay Server as Separate Service (Port 8767)

**Decision:** New Python relay service, separate from the existing bridge relay (8766) and the Hermes gateway.

**Why:** 
- The existing relay is single-purpose (bridge only). Extending it risks breaking upstream compatibility.
- Separate service means we can deploy/restart independently of the gateway.
- Future: can merge into gateway as a platform adapter if it stabilizes.

**Alternative considered:** Adding as a gateway platform adapter. Deferred — too coupled to gateway lifecycle for MVP.

### 3. Chat via Direct API, Not Relay Proxy

**Decision:** ~~Chat channel proxies through the relay to the WebAPI.~~ **Updated:** Chat now connects directly from the Android app to the Hermes API Server via HTTP/SSE. The relay server is only used for bridge and terminal channels.

**Why (original relay approach):**
- WebAPI already handles session management, agent creation, SSE streaming, tool progress events.
- Gateway integration would require implementing a new platform adapter. Much more work.
- WebAPI is stable, documented, and used by ClawPort.

**Why direct API (updated 2026-04-05):**
- The relay was an unnecessary middleman for chat — it just converted SSE to WebSocket envelopes.
- Every other Hermes frontend (Open WebUI, ClawPort, LobeChat, etc.) connects directly to the API server.
- Direct connection is simpler, removes the relay as a single point of failure for chat, and reduces latency.
- The relay remains for bridge (device control) and terminal (tmux/PTY) which require custom bidirectional protocols.

**Streaming endpoints (updated 2026-04-07):**

The app supports two streaming endpoints, selectable in Settings:

| Endpoint | Tool Calls | Event Format |
|----------|-----------|--------------|
| **Sessions** (`/api/sessions/{id}/chat/stream`) | Inline text annotations (`` `💻 terminal` ``) — client parses from markdown | Hermes-native SSE (assistant.delta, tool.progress, etc.) or OpenAI-format (delta.content) |
| **Runs** (`/v1/runs` + `/v1/runs/{run_id}/events`) | **Structured events** (tool.started, tool.completed) — real-time tool cards | Hermes lifecycle events (message.delta, tool.started, tool.completed, run.completed) |

**Important upstream note:** The `/api/sessions` CRUD endpoints are not in vanilla upstream hermes-agent. They are provided by one of three mechanisms:

1. The Codename-11 fork (`feat/session-api` branch — also carried on the `axiom` deploy branch). Submitted upstream as PR [#8556](https://github.com/NousResearch/hermes-agent/pull/8556) *"feat(api-server): add session management API for frontend clients"*. The PR's scope is broader than its title — it covers sessions CRUD, session chat/stream, memory, skills, config, and available-models, which is the full surface the bootstrap currently injects.
2. **Bootstrap injection** — `hermes_relay_bootstrap/` ships with the plugin and runs at Python interpreter startup (via a `.pth` file in the venv site-packages). It monkey-patches `aiohttp.web.Application` to add the management endpoints to upstream's `APIServerAdapter` at the moment it builds its app. See ADR 8 below.
3. Once PR #8556 merges, upstream-merged. The bootstrap detects this and no-ops.

The app's `probeCapabilities()` returns a per-endpoint snapshot, and `ConnectionViewModel.resolveStreamingEndpoint()` collapses `streamingEndpoint = "auto"` (the default for new installs) to a concrete `"sessions"` or `"runs"` choice based on what the server actually exposes.

**Tool call transparency:** In `/v1/chat/completions` streaming, tool calls are NOT emitted as separate SSE events. They are injected as inline markdown text (e.g., `` `💻 pwd` ``). The app's annotation parser (`ChatHandler.parseAnnotationLine`) detects these and renders them as tool progress cards. The `/v1/runs` endpoint is the only path that provides structured `tool.started`/`tool.completed` events.

**Architecture:**
```
Phone (HTTP/SSE) → Hermes API Server (:8642)   [chat — direct]
Phone (WSS)      → Relay Server (:8767)          [bridge, terminal]
```

**Auth:** Optional Bearer token (`API_SERVER_KEY`) stored in EncryptedSharedPreferences on device. Most local Hermes setups don't require a key.

### 4. xterm.js in WebView for Terminal

**Decision:** Use xterm.js running in a local WebView for the terminal emulator, not a native Compose canvas renderer.

**Why:**
- xterm.js is battle-tested — handles all ANSI escape sequences, Unicode, colors, scrollback.
- A native Compose terminal renderer would be weeks of work for inferior rendering.
- The WebView is a single composable in an otherwise fully native app — acceptable trade-off.
- Can replace with native renderer later if WebView performance is insufficient.

### 5. tmux for Terminal Session Management

**Decision:** Terminal channel attaches to tmux sessions, not raw PTY.

**Why:**
- tmux gives persistence — disconnect from the app, reconnect, session is still there.
- Named sessions let you manage multiple contexts (different projects, different servers).
- Shared sessions — agent and user can see the same terminal (future collaboration).

### 6. Auth: Pairing Code → Session Token

**Decision:** Initial pairing via 6-char code (upstream pattern), then long-lived session token for subsequent connections.

**Why:**
- Pairing codes are user-friendly and don't require pre-shared secrets.
- Session tokens avoid re-pairing on every app restart.
- Tokens stored in EncryptedSharedPreferences (Android Keystore-backed AES-256-GCM).

#### 6a. QR Carries Both API and Relay Credentials (updated 2026-04-11)

**Decision:** The Hermes pairing QR payload bundles the API server credentials AND the relay URL + pairing code into a single scan. The pair command (`/hermes-relay-pair` skill or `hermes-pair` shell shim, both backed by `plugin/pair.py`) runs on the Hermes host; if a relay is reachable at `localhost:RELAY_PORT`, the command mints a fresh 6-char code, pre-registers it with the relay via a new loopback-only `POST /pairing/register` endpoint, and embeds `{url, code}` under a nullable `relay` key alongside the existing `host`/`port`/`key`/`tls` fields.

**Trust anchor:** the operator with shell access on the host. Only a process running on the same machine as the relay can hit `/pairing/register` — the handler rejects any non-loopback `request.remote` with HTTP 403. A LAN attacker cannot inject codes. This matches the model we already rely on for reading `~/.hermes/.env` and `~/.hermes/config.yaml`: if you have shell access to the host, you have enough privilege to authorize a device.

**Why the change was necessary:**
- Previously the phone generated its own 6-char pairing code locally via `AuthManager.generatePairingCode()` and sent it to the relay on WSS connect. The relay had no way to know what code to accept, so relay pairing was effectively broken — only API-direct-chat pairing worked via the QR.
- Pushing the code flow through the host means the operator always has the source of truth, and a single scan configures both chat and terminal/bridge with no manual steps.

**Schema evolution:**
- Old API-only QRs (`{hermes, host, port, key, tls}`) still parse cleanly — the `relay` field is nullable and `kotlinx.serialization` runs with `ignoreUnknownKeys = true`.
- When `--no-relay` is passed to the pair command, or the relay isn't running, the QR omits the `relay` block and the command prints an `[info]` pointing at `hermes relay start`.

**Pairing alphabet change:** `PAIRING_ALPHABET` in `plugin/relay/config.py` was widened from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (32 chars, no ambiguous 0/O/1/I) to the full `A-Z / 0-9` (36 chars) to match the phone-side `AuthManager.PAIRING_CODE_CHARS`. The old restriction only mattered when a human had to retype a code from a display; now that the code flows phone ↔ server through a QR + HTTP, the restriction silently rejected ~12% of valid codes and had to go.

**Phase 3 symmetry:** `POST /pairing/register` is written generically enough that the bridge channel's phone-generates-code, host-approves flow (symmetric to the current server-generates, phone-scans flow) can reuse it from the opposite direction. Phone-side `generatePairingCode()` in `AuthManager.kt` is retained for that reason.

**References:**
- `plugin/pair.py` — `pair_command()`, `register_relay_code()`, `probe_relay()`
- `plugin/relay/server.py` — `handle_pairing_register`
- `plugin/relay/auth.py` — `PairingManager.register_code()`
- `app/.../ui/components/QrPairingScanner.kt` — `HermesPairingPayload` / `RelayPairing`

### 7. Biometric Gate for Terminal Only

**Decision:** Biometric/PIN required before terminal access. Chat and bridge don't require it.

**Why:**
- Terminal = shell access to your server. Highest privilege.
- Chat is conversational — no more dangerous than Discord.
- Bridge is controlled by the agent, not the user — gating it behind biometrics doesn't help.

### 8. Dynamic Personalities over Hardcoded Profiles

> **Terminology note (2026-04-18):** The word "Profiles" in this decision's title predates the multi-server / multi-agent work landed in §19 and §21. Today's vocabulary:
>
> - **Connection** (§19) — a paired Hermes *server*. What earlier drafts sometimes called a "profile".
> - **Profile** (§21) — an upstream-Hermes agent directory (`~/.hermes/profiles/<name>/`). Overlays model + SOUL on chat turns.
> - **Personality** (this decision) — a system-prompt preset *within* one agent's config.
>
> This decision is unchanged; only the surrounding vocabulary shifted.

**Decision:** Fetch personalities from `GET /api/config` → `config.agent.personalities` (name → system prompt map). Display active personality name on chat bubbles. Send selected personality's system prompt via `system_message` field.

**Why:**
- Hermes stores personalities as system prompt strings in `config.yaml` under `agent.personalities`. The server's active personality is in `config.display.personality` (set by the user during hermes setup).
- Previous approach sent a `profile` parameter that the server ignored — personality switching was non-functional.
- Now the app fetches the full personality map, shows the server default first in the picker, and sends the system prompt directly when a non-default personality is selected.
- Personalities are maintained in `~/.hermes/config.yaml` — changing them doesn't require an app update.
- Note: personalities only change the system prompt. Memory, sessions, tools, and model stay the same. For full agent identity separation, use hermes profiles (separate gateway instances).

**Trade-off:** Requires server connectivity to populate the picker. Fallback is "Default" with no personality override.

### 8b. Dynamic Command Palette over Hardcoded Slash Commands

**Decision:** Replace hardcoded slash command list with dynamic sources. Add a searchable command palette (bottom sheet) alongside inline autocomplete.

**Why:**
- Commands come from three sources: 29 gateway built-in commands (from `hermes_cli/commands.py` — `GATEWAY_KNOWN_COMMANDS`), dynamic personality commands (from `config.agent.personalities`), and server skills (from `GET /api/skills`).
- The built-in gateway commands are currently hardcoded because hermes-agent has no `/api/commands` endpoint. This is a potential upstream contribution (see below).
- The command palette provides browse-by-category, search, and multi-line descriptions — essential when there are 120+ commands.
- Inline autocomplete remains for fast typing: type `/` and it filters immediately.

**Upstream opportunity:** Propose a `GET /api/commands` endpoint for hermes-agent that exposes `GATEWAY_KNOWN_COMMANDS` from `hermes_cli/commands.py`. This would let the app fetch built-in commands dynamically instead of hardcoding 29 entries. Filed as a future contribution in docs/upstream-contributions.md.

### 9. In-App Analytics (Stats for Nerds)

**Decision:** Add an `AppAnalytics` singleton that collects performance metrics in-memory (no persistence, no network), displayed via canvas bar charts in Settings.

**Why:**
- Users debugging latency or token consumption need data, not guesses.
- Metrics tracked: TTFT (time to first token), completion time, token usage per message, health check latency, stream success/failure rates.
- Canvas-rendered bar charts with purple gradient — lightweight, no charting library dependency.
- In-memory only — data resets on app restart. No privacy concerns, no storage cost.

**Alternative considered:** Third-party analytics SDK (Firebase, Mixpanel). Rejected — too heavy for a developer-focused app, and users would rightfully object to telemetry.

### 10. Command Palette + Inline Autocomplete

**Decision:** Two complementary command discovery interfaces: inline autocomplete (type `/` to filter) and a full searchable command palette (bottom sheet via `/` button).

**Why:**
- 120+ commands across three sources (29 gateway built-ins, dynamic personalities, 90+ server skills) — too many for a simple dropdown.
- Inline autocomplete serves the "I know what I want" flow — type `/mod` and `/model` appears.
- Command palette serves the "what's available?" flow — browse by category, search, read descriptions.
- Commands are grouped by category: session, configuration, info, personality, then skill categories (creative, devops, etc.).
- All slash commands are sent to the server as regular messages. The server handles them as skills or built-in commands. Client stays thin.

**Sources:**
- **Gateway built-ins** (29): from `hermes_cli/commands.py` `GATEWAY_KNOWN_COMMANDS`. Currently hardcoded — no API endpoint exists. See `docs/upstream-contributions.md` for a proposed `GET /api/commands`.
- **Personalities**: generated dynamically from `GET /api/config` → `config.agent.personalities`.
- **Skills**: fetched from `GET /api/skills` with name, description, and category.

### 11. Animated Splash Screen

**Decision:** Custom `AnimatedVectorDrawable` splash with scale + overshoot + fade animation. Hold-while-loading pattern.

**Why:**
- Android 12+ requires a splash screen. Rather than the default static icon, we use a custom animated vector.
- The splash holds until DataStore preferences are loaded (determines whether to show onboarding or main app). This prevents a flash of wrong content.
- Separate `splash_icon.xml` at 0.9x scale of `ic_launcher_foreground.xml` — the splash icon spec uses a different safe zone than adaptive icons.
- Smooth fade-out exit animation via `SplashScreen.setOnExitAnimationListener`.

---

## Deferrals

| Feature | Reason | When |
|---------|--------|------|
| iOS support | Android-first, platform-specific APIs | v2+ |
| Multi-device | Single-device simplifies auth and state | Phase 6 |
| On-device model | Complexity, unclear value for relay use case | Phase 6 |
| Voice mode | Depends on Hermes TTS/STT maturity | Phase 6 |
| Notification listener | Not needed for app core | Phase 6 |
| File transfer | Can use agent tools (terminal) as workaround | Phase 6 |
| Android as Hermes platform/channel | See ADR 12 below — relay server as platform adapter | Phase 2+ (after terminal/bridge) |

### 12. Android as a Hermes Platform/Channel (Future)

**Decision:** Register the Android app as a Hermes platform adapter — like Discord, Telegram, or Slack — so the agent can proactively push messages TO the phone, not just respond to requests.

**Current architecture (request/response):**
```
Phone → POST /v1/runs → Agent processes → SSE stream → Phone
         (user initiates every exchange)
```

**Future architecture (bidirectional channel):**
```
Phone ↔ Relay Server ↔ Hermes Gateway (as "mobile" platform)
         (agent can initiate conversations, push notifications, send files)
```

**What this enables:**
- Agent-initiated messages — "Your build finished," "Reminder: meeting in 10 min"
- Cross-platform relay — "Send this to my phone" from Discord/Telegram
- Background task results — agent completes a long-running task and pushes the result
- Proactive notifications — agent monitors something and alerts the phone
- The phone appears in `channel_directory.py` as a reachable destination
- Cron job results delivered directly to phone
- `send_message` tool can target `mobile:<device_id>`

#### Upstream Platform Adapter Architecture (Researched 2026-04-07)

**Base class:** `BasePlatformAdapter` in `gateway/platforms/base.py`

Required abstract methods:
| Method | Purpose |
|--------|---------|
| `connect() → bool` | Start listeners, return True on success |
| `disconnect()` | Stop listeners, cleanup |
| `send(chat_id, content, reply_to?, metadata?) → SendResult` | Send a text message |
| `get_chat_info(chat_id) → Dict` | Return `{name, type, chat_id}` |

Optional methods (with default stubs): `send_typing`, `send_image`, `send_document`, `send_voice`, `send_video`, `edit_message`

Key data classes: `MessageEvent` (inbound), `SendResult` (outbound), `SessionSource` (origin tracking)

**Registration is NOT plugin-based** — requires changes to ~16 files in hermes-agent. There is an official guide: `gateway/platforms/ADDING_A_PLATFORM.md` (16-step checklist). Key files:

| File | Change |
|------|--------|
| `gateway/platforms/mobile.py` | CREATE — `MobileAdapter(BasePlatformAdapter)` |
| `gateway/config.py` | Add `MOBILE = "mobile"` to Platform enum + env overrides |
| `gateway/run.py` | Add to `_create_adapter()` factory + authorization maps |
| `gateway/channel_directory.py` | Add `"mobile"` to session-based discovery list |
| `tools/send_message_tool.py` | Add to `platform_map` + send routing |
| `cron/scheduler.py` | Add to `platform_map` for cron delivery |
| `agent/prompt_builder.py` | Add `PLATFORM_HINTS["mobile"]` |
| `toolsets.py` | Add `hermes-mobile` toolset |

**Inbound flow:** Adapter receives message via WSS → creates `MessageEvent` → calls `self.handle_message(event)` → gateway runs agent → response sent back via `adapter.send()`

**Outbound flow:** Agent uses `send_message` tool → `platform_map["mobile"]` → `adapter.send(chat_id, content)` → relay pushes to phone via WSS

#### Implementation Approaches

| Approach | Pros | Cons |
|----------|------|------|
| **A. Upstream PR** — Add `mobile.py` to hermes-agent | Full integration, maintained upstream, benefits community | Requires ~16 file changes, PR review process |
| **B. Fork hermes-agent** — Add locally | Quick iteration, full control | Maintenance burden, diverges from upstream |
| **C. Relay as bridge** — Relay server acts as adapter sidecar | No gateway changes, uses existing relay | Less integrated (no cron delivery, no channel_directory) |

**Recommended:** Approach A (upstream PR) for long-term. Start with Approach C (relay bridge) for prototyping. The relay server already has the persistent WSS connection — it just needs to expose a local HTTP API that the gateway's `send_message` tool can call.

**Session key pattern:** `agent:main:mobile:dm:<device_id>`

**Why deferred to Phase 2+:**
- Current chat works fine as request/response via HTTP/SSE
- Terminal and bridge channels are higher priority for MVP
- Upstream PR needs careful design and community alignment

### 13. Skill Distribution via `external_dirs`, not Hub Publish or Hand-Copy (2026-04-11)

**Decision:** The `hermes-relay-pair` skill is distributed by adding the Hermes-Relay clone's `skills/` directory to `skills.external_dirs` in `~/.hermes/config.yaml`, rather than publishing to the Hermes skill hub or hand-copying files into `~/.hermes/skills/`. The skill itself lives at `skills/devops/hermes-relay-pair/SKILL.md` in the repo (category subdirectory matching `metadata.hermes.category: devops`, per Hermes canonical layout).

**Why:**
- **Atomic updates** — a single `git pull` inside `~/.hermes/hermes-relay/` updates the skill, the plugin (via `pip install -e`), and the docs in lock-step. There is no drift between the skill and the plugin module it depends on (`plugin/pair.py`).
- **No hub round-trip** — the skill is an implementation detail of this project, not a generally-useful standalone skill. Publishing to the hub would be overkill and would make updates slower (hub publish step + `hermes skills update` on every change).
- **No hand-copy** — `cp -r skills/... ~/.hermes/skills/` was the old approach, but it means every update is a manual step and users can silently end up with stale skills. `external_dirs` is scanned fresh on every hermes-agent invocation, so there's nothing to forget.
- **Canonical category layout** — matching the `devops` subdirectory to the `metadata.hermes.category` frontmatter lets users browse `~/.hermes/skills/` (and external dirs) by category and mirrors how upstream organizes its own skills.

**Trade-off:** Users need to trust the installer's YAML edit to `~/.hermes/config.yaml`. The installer keeps the edit idempotent and only appends a single line under `skills.external_dirs`. If a user removes the line by hand, re-running the installer restores it.

**Why not `hermes skills update`:** That command targets skills installed via the hub (which writes to `~/.hermes/skills/`). Skills discovered via `external_dirs` aren't tracked by the hub at all — hermes-agent enumerates them on startup. Update flow for `external_dirs` skills is whatever the external dir's own update mechanism is. For us, that's `git pull`.

**Why the old `skills/hermes-pairing-qr/` was deleted:** It was the pre-plugin bash script era — `hermes-pair` as a shell script + a flat-file `SKILL.md`. The plugin now owns the QR generation (`plugin/pair.py`, pure Python, no `qrencode` dependency), the skill at `skills/devops/hermes-relay-pair/` owns the slash-command surface, and the shell shim at `~/.local/bin/hermes-pair` covers the script-friendly CLI entry point. Keeping the deprecated skill around would have been two sources of truth for the same operation.

**Upstream CLI gap (documented for posterity):** hermes-agent v0.8.0's `PluginContext.register_cli_command()` is wired up on the plugin side, and `plugin/cli.py` calls it correctly. However, `hermes_cli/main.py:5236` only reads `plugins.memory.discover_plugin_cli_commands()` (memory-plugin-specific) and never consults the generic `_cli_commands` dict. Third-party plugin CLI commands never reach the top-level argparser. Documented in DEVLOG as an upstream fix target. Until it lands, `hermes pair` (with a space) is **not** a working entry point — docs point users at `/hermes-relay-pair` (skill-driven slash command) and `hermes-pair` (dashed shell shim) instead.

**References:**
- `install.sh` — canonical installer
- `skills/devops/hermes-relay-pair/SKILL.md` — skill definition (canonical category layout)
- `plugin/pair.py` — shared implementation
- `~/.hermes/hermes-agent/website/docs/user-guide/features/skills.md` — upstream skill distribution spec

### 14. Inbound Media via Plugin-Owned Relay Endpoint, Not Upstream Gateway (2026-04-11)

**Decision:** Agent-initiated media (screenshots, and any future file-producing tool) is delivered to the phone via a new loopback-register + bearer-fetch pair of routes on our own relay server (`POST /media/register` → `GET /media/{token}`), not by relying on hermes-agent's upstream `extract_media()` / `send_document()` machinery. Tools emit a `MEDIA:hermes-relay://<token>` marker in their chat response text; the phone parses the marker out of the SSE stream and fetches bytes out-of-band over authenticated HTTPS.

**Why:**
- **Upstream doesn't solve this on the HTTP API surface.** Verification against `~/AppData/Local/Temp/hermes-agent/gateway/platforms/api_server.py` shows `APIServerAdapter.send()` is an explicit no-op with the comment `"API server uses HTTP request/response, not send()"`. `_write_sse_chat_completion` (api_server.py:651-757) streams raw `stream_q` deltas straight into SSE `content` chunks — it never invokes `extract_media()` and never routes deltas through `GatewayStreamConsumer` (which would at least strip `MEDIA:` tags via `_MEDIA_RE` at `stream_consumer.py:188`). The upstream `extract_media()` / `send_document()` calls at `gateway/run.py:4570`, `4747`, `4349` are only reachable from **non-streaming** paths (background tasks, cron, batch) and push-style platform adapters (Telegram, Feishu, WeChat, Slack), all of which override `send_document` with real platform APIs. The pull-based HTTP adapter inherits the base class default, which falls back to `self.send(chat_id, f"📎 File: {file_path}")` — which is the no-op. So `MEDIA:/tmp/...` has always passed through our chat stream as literal text.
- **Inline base64 in tool output was the obvious alternative but blows up LLM context.** A 1280×720 JPEG is ~135 KB base64, and every subsequent turn's context window has to re-ingest the bytes. Scales badly for video or multiple attachments per turn. Opaque tokens are ~25 chars and add essentially zero context cost.
- **No upstream PR in scope.** Fixing this properly upstream would mean implementing `send_document` on `APIServerAdapter` (likely via a side-channel SSE event or a new attachment field on the chat-completion chunk shape). That's a community-scoped API change, and user explicitly wanted an in-plugin workaround, not a fork.
- **Our relay is already the right place.** The plugin's relay server (`plugin/relay/server.py`) is a service we already own, already has HTTP routes (`/health`, `/pairing`, `/pairing/register`), already uses `SessionManager` for bearer-auth'd channels, and already lives on the phone's trust boundary (paired via the same QR). Adding file-serving doesn't create a new security surface or a new credential store — it reuses both.

**How it works:**

```
Agent tool              Hermes API Server       Relay (:8767)         Phone
──────────              ─────────────────       ─────────────         ─────
android_screenshot()
  │
  ├─ write bytes to /tmp/...
  ├─ POST /media/register  ─────────────────▶   MediaRegistry
  │  (loopback only)                            ◀── {"token": "xyz"}
  │
  └─ returns "MEDIA:hermes-relay://xyz" ──▶ stream_q
                              │
                              └─ SSE content chunk ────────────────▶ ChatHandler
                                                                     scanForMediaMarkers
                                                                     parseAnnotationLine
                                                                     strips line, fires
                                                                     onMediaAttachmentRequested

                                              GET /media/xyz  ◀───── RelayHttpClient
                                              Authorization:         (Bearer = existing
                                                Bearer <session>      session_token)
                                              ──── bytes + ─────▶   MediaCacheWriter
                                              Content-Type          → cacheDir/hermes-media/
                                              Content-Disposition   → FileProvider URI
                                                                    → InboundAttachmentCard
```

**Trust model:**
- `/media/register` is **loopback-only** — 403 for any `request.remote` other than `127.0.0.1` / `::1`. Only a process running on the same host as the relay can inject files. Same pattern as `/pairing/register`.
- `/media/{token}` requires `Authorization: Bearer <session_token>` — the same session token issued at pairing and stored in Android's `EncryptedSharedPreferences`. A LAN attacker who sniffed a token in cleartext insecure mode would also need a valid session token to use it, which they don't have unless they scanned the QR (same trust level as the WSS channel itself). Tokens are `secrets.token_urlsafe(16)` = 128 bits of entropy.
- **Path is never exposed to the client.** The register endpoint holds the token → path mapping server-side. Clients present only the token on GET, so the fetch endpoint has zero path-traversal surface. Path sandboxing lives entirely on the register side: `os.path.realpath()` must resolve under an allowed root (default: `tempfile.gettempdir()` + `HERMES_WORKSPACE` or `~/.hermes/workspace/` + any `RELAY_MEDIA_ALLOWED_ROOTS` entries), the file must exist, must be a regular file, and must fit under the configured size cap.

**Resource bounds:**
- **TTL: 24 hours** (default, `RELAY_MEDIA_TTL_SECONDS`) — chosen to match within-a-day session scrollback, the actual human use case. Going longer buys nothing since `SessionManager` is in-memory and any relay restart invalidates all tokens regardless. Going shorter breaks same-day scrollback.
- **LRU cap: 500 entries** (default, `RELAY_MEDIA_LRU_CAP`) — prevents runaway memory/disk under screenshot spam. Eviction is oldest-first via `OrderedDict.move_to_end` on every `get()`.
- **Per-file size cap: 100 MB** (default, `RELAY_MEDIA_MAX_SIZE_MB`) — guards `/media/register` against accidentally registering a 10 GB file.

**Fallback when the relay isn't running:**
The tool calls `register_media()` with a 5-second timeout. On any failure (connection refused, non-200, timeout) it logs a warning and returns the legacy bare-path form `MEDIA:/tmp/...`. The phone's `ChatHandler` parses this form via a second regex and, as of the bare-path-fetch update below, attempts a direct `/media/by-path` fetch — rendering the `⚠️ Image unavailable` placeholder only if that fetch ALSO fails (relay offline, sandbox violation, file missing). No regression versus the pre-fix behavior; the marker never renders as raw text.

**Addendum (2026-04-11, same day): LLM-emitted bare-path markers + `/media/by-path`.**
On-device testing exposed a gap: the above token-registration flow only covers markers **emitted by tools** (which we control and can wire through `register_media()`). But upstream `hermes-agent/agent/prompt_builder.py:266` explicitly instructs the LLM in its base system prompt: *"include MEDIA:/absolute/path/to/file in your response. The file..."* — so the agent's free-form completions contain `MEDIA:/abs/path` markers too, and those never go through any tool code. Our tool-only fix left them as raw text.

Rather than patch the system prompt (out of scope — upstream-owned), the fix is a second relay route — **`GET /media/by-path`** — that takes an absolute path + bearer auth and streams the file directly. Same path sandbox as `/media/register` (via shared `validate_media_path` helper), same trust model (bearer session token). The phone's bare-path marker handler now attempts this fetch first and only falls back to the "unavailable" placeholder on network/404/403 failure. Result: LLM free-form MEDIA markers render inline with zero prompt-hacking.

**Security implications of `/media/by-path`:** Adding a path-addressable fetch widens what a paired phone can request by one degree — it can now read any file in the allowed-roots whitelist without host-local process cooperation, where previously it needed a tool to pre-register via `/media/register`. This does NOT widen the trust boundary because:
 1. The allowed-roots whitelist is the same. Files outside `tempfile.gettempdir()` / `HERMES_WORKSPACE` / `RELAY_MEDIA_ALLOWED_ROOTS` are still unreachable.
 2. On Linux `/tmp` is already world-readable to every process running as the same user, so the relay exposing it over bearer-auth'd HTTP is no new disclosure to an attacker who could already connect a paired phone.
 3. Bearer auth still requires a valid session token issued at pair time. Unpaired peers get 401.
 4. The path never leaks out of the sandbox via symlinks — `os.path.realpath()` resolves symlinks before the whitelist check, so a symlink inside the whitelist pointing outside it is rejected.

The bare-path fetch is therefore safe as long as operators treat the allowed-roots whitelist as "directories the paired phone is allowed to read." If that's wrong for a given deployment, tighten `RELAY_MEDIA_ALLOWED_ROOTS` rather than disabling the endpoint.

**Trade-off: session replay across relay restarts doesn't work.** If the user scrolls back into a session from yesterday and the relay has restarted since, the tokens stored in the persisted message text are stale and `/media/{token}` returns 404. The phone renders a FAILED placeholder. Acceptable for MVP — the alternative (phone-side persistent cache indexed by token or content hash) is meaningful new plumbing and the right layer for durability, but out of scope. Filed as a follow-up in DEVLOG.

**Trade-off: auto-fetch-threshold slider is persisted but not enforced today.** The user-facing Settings → Inbound media section exposes a "auto-fetch threshold" knob (0–50 MB), but the actual fetch path only checks the cellular toggle + the max-size cap. Real threshold enforcement would need either a HEAD preflight (to reject before downloading) or accept the post-hoc waste. Kept the slider as a forward-compatibility placeholder; actual wiring is a follow-up.

**Alternative rejected: inline base64 in tool output.** Would be simpler (no new endpoint, no phone-side fetcher) but every attachment would bloat the LLM context window on every subsequent turn. For a single screenshot that's ~135 KB of base64; for any non-trivial use case the costs compound. User explicitly rejected this during the design discussion.

**Alternative rejected: patch upstream `api_server.py`.** Would be architecturally cleaner — route deltas through `GatewayStreamConsumer` so `_MEDIA_RE` strips the tags, then implement `send_document` via a side-channel SSE event. But it's a community-scoped API change, and user explicitly scoped us to "work with existing/documented methods ideally; if we have to work-around we need to follow our existing path within our plugin/etc." Recorded in `docs/upstream-contributions.md` as a possible future PR.

**References:**
- `plugin/relay/media.py` — `MediaRegistry`, `_MediaEntry`, `MediaRegistrationError`
- `plugin/relay/server.py` → `handle_media_register`, `handle_media_get`
- `plugin/relay/client.py` → `register_media()` (stdlib urllib, 5s timeout)
- `plugin/tools/android_tool.py::android_screenshot` — first consumer
- `app/src/main/kotlin/.../network/RelayHttpClient.kt` — phone fetcher
- `app/src/main/kotlin/.../network/handlers/ChatHandler.kt` → `scanForMediaMarkers`, `finalizeMediaMarkers`
- `app/src/main/kotlin/.../ui/components/InboundAttachmentCard.kt` — Discord-style rendering

### 15. Pairing + Security Architecture: grants, user-chosen TTL, Keystore, TOFU, Paired Devices (2026-04-11)

**Decision:** Replace the minimal pairing model (one-shot code → fixed-30-day session token → no channel separation → `EncryptedSharedPreferences` storage) with a layered architecture built around four ideas:

1. **User chooses session TTL at pair time** — 1 day / 7 days / 30 days / 90 days / 1 year / **never expire**. The Android TTL picker dialog always opens on QR scan so the user explicitly confirms. Defaults depend on transport: wss or Tailscale → 30d; plain ws → 7d. Never-expire is ALWAYS selectable with an inline warning — per operator direction, trust the user's intent rather than gating on secure-transport detection.
2. **Per-channel grants** — one session token, separate expiries for `chat` / `terminal` / `bridge`. Blast-radius-heavy channels have shorter default caps (`terminal ≤ 30d`, `bridge ≤ 7d`), clamped to the session lifetime. Chat runs through the hermes-agent API server rather than the relay, so the chat grant is informational only (used by the phone UI to show scope).
3. **Hardware-backed token storage with graceful fallback** — `KeystoreTokenStore` requests StrongBox-backed keys via `setRequestStrongBoxBacked(true)` on Android 9+ devices that advertise `FEATURE_STRONGBOX_KEYSTORE`. Falls back to the existing `LegacyEncryptedPrefsTokenStore` (TEE-backed `EncryptedSharedPreferences`) on older devices or when the Keystore path throws. Migration is one-shot and lossless — users never lose a session to an app upgrade.
4. **TOFU cert pinning with explicit reset on re-pair** — `CertPinStore` records SHA-256 SPKI fingerprints per `host:port` on the first successful wss connect. Subsequent connects build an OkHttp `CertificatePinner` from the stored pin. A user-initiated QR re-pair (`applyServerIssuedCodeAndReset(code, relayUrl)`) wipes the pin for the target host — re-pair is explicit consent to potentially-new cert material. Plaintext ws:// short-circuits pinning entirely.

**Supporting infrastructure:**

- **Device revocation UI** — new Paired Devices screen on the phone, backed by `GET /sessions` (list all paired devices, tokens masked to first 8 chars) and `DELETE /sessions/{token_prefix}` (revoke). Self-revoke is allowed and flagged via `revoked_self: true` so the phone can wipe local state and redirect to pairing.
- **QR payload v2 + HMAC signing** — payload version bumped from 1 to 2 when any new field is present (`ttl_seconds`, `grants`, `transport_hint`). Signed with HMAC-SHA256 using a host-local secret at `~/.hermes/hermes-relay-qr-secret` (32 bytes, `0o600`, auto-created). Phone parses and stores the `sig` field but does NOT verify it yet — full verification requires a secret-distribution mechanism we don't have defined. The server-side infrastructure is in place so phone-side verification can land in a follow-up.
- **Rate-limit clear on pair** — `/pairing/register` now calls `RateLimiter.clear_all_blocks()` on success. An operator explicitly re-pairing wants a clean slate; the stale rate-limit state otherwise blocks the legitimate re-pair attempt for 5 minutes. This was an actual bug biting the operator at the start of this session.
- **Transport security UI** — badge component with three states (secure green 🔒 / insecure amber with reason / insecure unknown red), three sizes (chip / row / large). Rendered in Settings Connection section, Session info sheet, and on each Paired Device card.
- **Insecure ack dialog** — first-time toggle-on shows a plain-language threat-model dialog with a reason picker (LAN only / Tailscale or VPN / Local dev only). Reason persists for display purposes; does NOT gate anything per the operator's trust-model direction.
- **Tailscale detection** — `TailscaleDetector` checks for `tailscale0` interface + `100.64.0.0/10` CGNAT addresses + `.ts.net` hostnames in the relay URL. Shown as a "Tailscale detected" green chip; **does NOT auto-change** any defaults — informational only.
- **Phase 3 bidirectional pairing stub** — new `POST /pairing/approve` route, loopback-only, same shape as `/pairing/register`. Marked with `# TODO(Phase 3):` — full flow needs a pending-codes store so operators review rather than rubber-stamp. The route and wire shape are committed now so the phone side has something to target when bridge lands.

**Why this split:**

- **Grants on a single token (not multiple tokens)** — one WSS connection, one auth envelope, one session lookup. Per-channel expiry is checked at channel message dispatch time via `Session.channel_is_expired(name)`. Simpler to reason about than multiple parallel tokens, and the phone only needs one storage slot.
- **`math.inf` for never-expire** — represents "truly unbounded" in code, serializes to `null` on the wire (JSON doesn't have an infinity literal, and null maps cleanly to Kotlin's nullable `Long?`). `canonicalize()` uses `allow_nan=False` so accidentally trying to sign a payload with a raw `math.inf` crashes loudly — callers must explicitly emit `None`/`0`. Prevents silent serialization bugs.
- **Metadata on pairing entries, host wins over phone** — when the host operator runs `hermes-pair --ttl 7d` and the phone sends `ttl_seconds=30d` in the auth envelope (because the user picked a different value on the TTL dialog), the host value wins. Operator policy is authoritative. If the host didn't specify anything, the phone's value applies.
- **Token prefix (not full token) in `/sessions` responses** — a caller already holds their own full token; they should never see another session's full token. First 8 chars are enough to identify devices in a practical deployment (one operator, 1-3 phones) and enough entropy to avoid collisions. Collisions return 409 with the match count.
- **Always open the TTL picker (no skip)** — even when the QR carries an operator-chosen TTL, the dialog opens with that value preselected. The user is always in the loop for the trust decision. A future "don't ask again if QR specifies a TTL" toggle is a plausible refinement but not in this cut.

**Trade-offs:**

- **Any paired phone can revoke any other paired device.** A compromised phone could lock out all other devices — DoS territory. Acceptable for the single-operator / 1-2 phones deployment model; multi-user deployments will need a per-device role model (admin vs user) with per-role grant caps.
- **QR signatures aren't verified on the phone.** Raises the bar for QR tampering on the server side (attacker can't inject a fake code via a modified photo if the server requires a valid sig), but the phone currently trusts any signature as long as the parse succeeds. Full verification is a follow-up.
- **TOFU cert pinning doesn't protect the first connect.** By definition, trust-on-first-use accepts whatever certificate is present on the initial handshake. Protects against MITM of subsequent connects only. Acceptable for LAN / Tailscale / VPN deployments where the first connect happens over a trusted path.
- **StrongBox is opportunistic.** Older Android devices or devices without StrongBox hardware fall back to TEE-backed EncryptedSharedPreferences. Still strong, but the attack surface is larger than hardware-backed.

**Alternatives rejected:**

- **Separate tokens per channel** — clean model but triples the storage + auth flow. One-token-with-grants is the right abstraction.
- **Gating never-expire on secure transport detection** — operator explicitly requested "don't force check, just allow based on user intent." User agency over policy.
- **Full QR signature verification on the phone in this cut** — requires a secret distribution mechanism (pre-shared key? enrollment token? OAuth?) that isn't yet designed. Server-side signing is the prerequisite and it's in place.

**References:**

- `plugin/relay/auth.py` — `Session`, `PairingMetadata`, `SessionManager`, `PairingManager`, `RateLimiter.clear_all_blocks`
- `plugin/relay/qr_sign.py` — `canonicalize`, `sign_payload`, `verify_payload`, `load_or_create_secret`
- `plugin/relay/server.py` — `handle_pairing_register`, `handle_pairing_approve`, `handle_sessions_list`, `handle_sessions_revoke`, `_detect_transport_hint`
- `plugin/pair.py` — `build_payload(sign=True)`, `parse_duration`, `parse_grants`, `--ttl` / `--grants` flags
- `app/src/main/kotlin/.../auth/SessionTokenStore.kt` — Keystore / legacy fallback
- `app/src/main/kotlin/.../auth/CertPinStore.kt` — TOFU pinning
- `app/src/main/kotlin/.../auth/PairedSession.kt` — phone-side session metadata
- `app/src/main/kotlin/.../ui/components/SessionTtlPickerDialog.kt` — TTL picker
- `app/src/main/kotlin/.../ui/components/TransportSecurityBadge.kt` — secure / insecure indicator
- `app/src/main/kotlin/.../ui/components/InsecureConnectionAckDialog.kt` — first-time insecure consent
- `app/src/main/kotlin/.../ui/screens/PairedDevicesScreen.kt` — list + revoke UI
- `app/src/main/kotlin/.../util/TailscaleDetector.kt` — informational detection

---

## CI/CD Patterns (from ARC)

Adopting from ARC's workflow patterns:

1. **CI workflow:** Lint (ktlint) → Build (debug + release matrix) → Test → Upload APK artifact
2. **Release workflow:** Tag `v*` → version validation (build.gradle.kts vs tag) → signed APK → GitHub Release
3. **Concurrency groups:** Cancel in-progress CI on new push to same branch
4. **Dependabot:** Auto-merge minor/patch dependency updates

### 16. Runtime API Server Patch via .pth Bootstrap (2026-04-12)

**Context:** The Codename-11 fork of hermes-agent (`feat/session-api` branch, submitted as PR #8556) adds ~14 management endpoints — `/api/sessions/*` CRUD, `/api/memory`, `/api/skills`, `/api/config`, `/api/available-models` — that the Android app depends on for its sessions browser, personality picker, command palette, and history-on-restart. These are submitted upstream as PR [#8556](https://github.com/NousResearch/hermes-agent/pull/8556) *"feat(api-server): add session management API for frontend clients"* but not yet merged. Until then, users running vanilla upstream hermes-agent + our plugin would lose these features and see a blank chat window when reopening the app to a previous session.

We considered four options:
- **A. Stay fork-only.** Reject vanilla upstream users until PR #8556 lands. Penalises onboarding.
- **B. Read-only sessions browser via plugin relay.** Add `GET /api/sessions/*` to the relay at port 8767. Forces the client to know which URL each operation goes to.
- **C. Full parity by porting all 800 lines onto the plugin relay.** Same architectural pollution as B, plus duplicates ~250 lines of chat-stream handler with cross-cutting `_create_agent` / `run_conversation` dependencies that the fork may have implicitly modified.
- **D. Runtime injection via Python interpreter startup hook.** Ship a `.pth` file in the venv site-packages that imports a bootstrap module, which installs a `sys.meta_path` finder for `aiohttp.web`. When the gateway eventually imports `aiohttp.web`, our finder wraps the loader and replaces `web.Application` with a thin subclass. The subclass overrides `__setitem__` to detect `app["api_server_adapter"] = self` (the line in upstream's `connect()` that gives us a reference to the adapter while the router is still mutable). At that point we feature-detect by route path and bind our extra handlers directly onto the same router the gateway is in the middle of populating.

**Decision: D, scoped to management endpoints only.** The chat-stream handler is intentionally NOT injected — chat goes through standard upstream `/v1/runs`, which already emits structured `tool.started`/`tool.completed` events. This avoids touching `_create_agent` / `run_conversation` (the fork's riskiest cross-cutting dependencies) and is arguably an upgrade — `/v1/runs` has live tool events whereas the sessions chat-stream path required a post-stream message-history reload to render tool cards.

**Why this is the right answer despite being a clever hack:**

1. **Zero modifications to hermes-agent's filesystem.** `git pull` / `hermes update` see no local changes, so they always work cleanly. The patch lives entirely in `hermes_relay_bootstrap/` inside our own repo.
2. **Single-file containment of all ported logic.** `_handlers.py` is 500 lines of straight-line aiohttp handler code with explicit `adapter` parameters (closures, not bound methods). Easy to audit, easy to delete.
3. **Feature detection by route path, not method name.** The bootstrap checks if `/api/sessions` is already in the router and no-ops if so. This means fork users, bootstrap-injected vanilla-upstream users, AND post-PR-#8556 upstream-merged users all run safely with the bootstrap installed. Three of the four valid combinations require zero changes; only the bootstrap-injected one actively patches.
4. **Trust model already established.** The user installed our plugin into their hermes-agent venv. They've already consented to having the plugin import hermes-agent internals (it does this for relay tools, voice endpoints, media registry). Monkey-patching `aiohttp.web.Application` is in the same trust bucket.
5. **Trivial removal.** When PR #8556 reaches a released hermes-agent version: delete `hermes_relay_bootstrap/`, delete the `.pth` from `install.sh` step 2, bump plugin version. The Android client's capability detection still works because it probes routes by path, not by source.
6. **`/v1/runs` is genuinely better for chat than `/api/sessions/{id}/chat/stream`.** It's standard upstream, supports `X-Hermes-Session-Id` for continuation, and emits live structured tool events. The fork's chat handler exists because upstream didn't HAVE this clean structured-event runs API at the time the fork was cut — but upstream does now.

**The Android client adapts via `streamingEndpoint = "auto"`.** New `ServerCapabilities` data class returned by `HermesApiClient.probeCapabilities()` captures per-endpoint presence (`sessionsApi`, `sessionsChatStream`, `runs`, `portable`, `healthy`). `ConnectionViewModel.resolveStreamingEndpoint()` collapses `"auto"` to `"sessions"` (when chat-stream handler is present, i.e. fork or upstream-merged) or `"runs"` (otherwise, i.e. bootstrap-injected vanilla upstream). The setting still supports manual `"sessions"` / `"runs"` overrides for debugging.

**Risks accepted:**
- **Plugin load order** — verified: `.pth` files are processed by Python's `site` module BEFORE any application code runs, so our import hook is in place before hermes-agent imports `aiohttp.web`.
- **Upstream refactor of the route-registration block in `connect()`** — handled by feature detection on route path. Worst case: bootstrap logs a warning and gateway runs without injected routes. The Android client falls back to `/v1/runs` automatically.
- **Upstream symbol removal in `tools/skills_tool.py`** — `skills_categories` was removed in upstream commit `8d023e43` as dead code. The bootstrap no longer imports or re-injects it. The app uses `/api/skills?category=` for category filtering; the standalone `/api/skills/categories` endpoint was never called by the app. The bootstrap stays in sync with upstream by not re-introducing removed symbols.
- **Editable pip install doesn't ship `.pth` files reliably** — verified empirically (test in `/tmp/pth-test` on the server during scoping). Solved by `install.sh` copying the `.pth` directly into the venv's `site-packages/` after `pip install -e`.

**File locations:**
- `hermes_relay_bootstrap/__init__.py` — installs the meta_path finder (~30 lines)
- `hermes_relay_bootstrap/_patch.py` — `_AioHttpWebFinder`, `_PatchingLoader`, `_PatchedApplication`, `_maybe_register_routes` (~170 lines)
- `hermes_relay_bootstrap/_handlers.py` — 14 ported handlers + helpers (~500 lines)
- `hermes_relay_bootstrap.pth` — single line: `import hermes_relay_bootstrap`
- `install.sh` step 2 — copies the `.pth` into the venv site-packages

**Removal path** (when PR #8556 lands and reaches a released hermes-agent):
1. Delete `hermes_relay_bootstrap/` directory
2. Delete `hermes_relay_bootstrap.pth` from repo root
3. Remove the `.pth` drop block from `install.sh` step 2
4. Update CLAUDE.md to drop the bootstrap reference
5. The Android client `probeCapabilities()` and `streamingEndpoint = "auto"` plumbing stays — it's permanent infrastructure that handles mixed-version deployments.

---

### 17. Wake-lock wrapping for gesture dispatch + multi-window ScreenReader + three-tier tapText cascade (2026-04-13)

**Context:** While scoping the v0.4 bridge feature expansion (see `docs/plans/2026-04-13-bridge-feature-expansion.md`), three reliability gaps in the existing Phase 3 `ActionExecutor` / `ScreenReader` surfaced as hard prerequisites before any of the ten Tier A tools were worth adding:

1. **Gestures silently fail when the phone screen is off.** `ActionExecutor.tap` / `swipe` / `typeText` all dispatch into `GestureDescription` via the accessibility service. When the screen is off the gesture completes cleanly at the framework level — `GestureResultCallback.onCompleted` fires — but nothing actually happens on-device. The agent thinks the tap landed and proceeds with the next step. Biting Bailey repeatedly during `android_navigate` loops that sat idle between iterations.
2. **`ScreenReader` misses every overlay, popup menu, notification shade, and split-screen secondary window.** The existing implementation calls `service.rootInActiveWindow` and walks a single tree. Android exposes all visible windows via `AccessibilityService.windows`; system overlays and popup menus live in separate windows from the foreground activity. Any agent flow that needed to see a popup dialog (date pickers, confirmation dialogs, context menus, notification shade content) hit a blank tree.
3. **`ActionExecutor.tapText` fails on clickable text inside non-clickable wrappers.** The single-shot implementation finds a text node via `findNodeBoundsByText` and calls `performAction(ACTION_CLICK)` on that node. Real-world Android apps (Uber, Spotify, Instagram, Tinder — verified by comparison-passing raulvidis/hermes-android against a matrix of target apps) wrap clickable content in non-clickable `TextView`/`ImageView` ancestors. `ACTION_CLICK` on the text node itself is a no-op. The fallback approach is to walk up the parent chain to the nearest clickable ancestor, and if nothing clickable is found, fall back to a coordinate tap at the node's bounds center.

**Decision:** Adopt all three patterns before shipping the v0.4 tool surface. They're applied to the existing Phase 3 code so every new Tier A/B/C tool inherits them for free.

**Pattern 1 — `WakeLockManager.wakeForAction` (A8).** New `object WakeLockManager` at `app/src/main/kotlin/com/hermesandroid/relay/power/WakeLockManager.kt`. Exposes `suspend fun <T> wakeForAction(block: suspend () -> T): T`. Uses `PowerManager.PARTIAL_WAKE_LOCK` (the non-deprecated modern successor to `SCREEN_BRIGHT_WAKE_LOCK`), with:
- **Ref counting.** A private `lockCount: Int` tracks concurrent scopes; the underlying `WakeLock.release()` only fires when the count hits zero. Nested calls don't release each other prematurely.
- **Hard 10-second timeout.** Passed to `newWakeLock().acquire(timeoutMillis = 10_000)` as a battery safety rail. Long-held wake locks are a notorious source of drain bugs; 10s is longer than any single gesture needs and far shorter than any plausible leak window.
- **Try/finally release.** The `wakeForAction` body is wrapped in `try { block() } finally { release() }` so exceptions thrown from inside the action still release the lock.

`ActionExecutor.tap` / `tapText` / `typeText` / `swipe` / `scroll` / `longPress` / `drag` (the last two new in v0.4) are wrapped in `WakeLockManager.wakeForAction { ... }`. **Read-only calls are deliberately NOT wrapped** — `readScreen`, `findNodes`, `describeNode`, `screenHash`, `diffScreen`, `currentApp`, `clipboardRead/Write`, `mediaControl`. These don't need the screen on; wrapping them would waste battery and add latency to polls. Requires `android.permission.WAKE_LOCK` in `app/src/main/AndroidManifest.xml`.

**Pattern 2 — Multi-window `ScreenReader` (P1).** Change `ScreenReader.readCurrentScreen` (and any helper using `rootInActiveWindow`) to iterate `service.windows.mapNotNull { it.root }` and walk each per-window tree. The node-ID scheme is updated to prefix every stable ID with `w<windowIndex>:` so IDs remain unique across the merged output (`w0:42` for the main activity, `w1:7` for a popup menu). Per-iteration `try/finally` blocks recycle each `AccessibilityNodeInfo` as the walker descends — `.parent` and `.getChild(i)` both return fresh instances that leak if not recycled. A single-window fallback kicks in when `service.windows` is empty, which happens on the googlePlay flavor without `flagRetrieveInteractiveWindows` (the conservative accessibility config required by Play Store policy review).

**Pattern 3 — Three-tier `tapText` cascade (A9).** Rewrite `ActionExecutor.tapText`:
1. Find node by text across all windows (benefits from P1). If `node.isClickable` → `performAction(ACTION_CLICK)`. Return `ActionResult(ok=true, data="direct")`.
2. Otherwise walk up the parent chain, capped at 8 levels, looking for a clickable ancestor. Each `.parent` call returns a fresh node that must be recycled before the loop reassigns. If any ancestor is clickable → `performAction(ACTION_CLICK)` on it. Return `ActionResult(ok=true, data="parent")`.
3. Otherwise capture the original node's `getBoundsInScreen()` center *before* recycling, and fall back to coordinate `tap(cx, cy)` via the existing gesture path (which itself runs under `WakeLockManager.wakeForAction`). Return `ActionResult(ok=true, data="coords")`.

The `data` field tells the activity log and agent trace which tier succeeded, which is actually useful debugging info when a tap lands unexpectedly.

**Consequences:**

- **Wake-lock reliability:** closes the idle-screen silent-failure class of bugs for every gesture-dispatching tool, present and future. Adds minimum complexity per action (one `wakeForAction { ... }` wrapper call). The battery cost is tightly bounded by the 10s timeout and the PARTIAL lock level.
- **Multi-window visibility:** accessibility tree size grows modestly (typical overhead: 1–3 extra windows at ~20 nodes each — the notification shade and system UI). `MAX_NODES=512` cap in `ScreenReader` absorbs it. The node-ID prefix change is a breaking change to the ID format, but IDs are opaque and session-local — agents pass them back within a single turn, never persist them, so the breakage is invisible to callers.
- **tapText success rate:** validated against raulvidis's target-app matrix (Uber, Spotify, Instagram, Tinder, Maps, Settings). Direct-click path still wins on well-structured accessibility trees (Google Maps, Android Settings). Parent-walk catches the ~60% of cases where the clickable wrapper is within 1–2 levels of the text node. Coordinate fallback is the last-resort and backs ~10% of cases on the hardest apps.
- **`android_navigate` throughput:** Pattern 1 + Pattern 3 together eliminate the two biggest "tap fails for no obvious reason" failure modes inside the vision-driven navigate loop. Combined with A5 `android_screen_hash` as a cheap change-detection primitive (covered in the tool surface but not a pattern ADR), navigate iterations get faster and more reliable without changing the vision-model integration.
- **Test coverage:** Pattern 1 is tested implicitly via the existing `ActionExecutor` tests — the wake-lock wrap is transparent to the gesture completion path. Pattern 2 adds multi-window fixtures to `ScreenReader` tests where available; the single-window fallback path ensures the unit tests that use the old fixture shape still pass. Pattern 3 is hard to unit-test cleanly (accessibility node traversal is not easy to mock) so it's primarily verified via instrumentation tests on a real device.

**Alternatives rejected:**

- **`SCREEN_BRIGHT_WAKE_LOCK` instead of `PARTIAL_WAKE_LOCK`.** Deprecated since API 17. The agent doesn't need the screen visible — it just needs the input path warm. `PARTIAL_WAKE_LOCK` is the modern, non-deprecated choice.
- **Single-root with window enumeration as a fallback.** Leaks overlays when the overlay is the primary point of interaction (e.g. a full-screen dialog). Iterating all windows up-front is simpler and the overhead is bounded.
- **Only walk the parent chain, no coordinate fallback.** Fails on the ~10% of apps where nothing in the chain is clickable but the bounds are valid. Coordinate tap is the last-ditch option and is cheap to try.
- **Wake-lock the entire bridge command handler.** Over-broad — every `android_screen` call would grab the lock. Scope it to the gesture path only.

**References:**

- `app/src/main/kotlin/com/hermesandroid/relay/power/WakeLockManager.kt` (new in v0.4)
- `app/src/main/kotlin/com/hermesandroid/relay/accessibility/ActionExecutor.kt` — `tap` / `tapText` / `typeText` / `swipe` / `scroll` / `longPress` / `drag` wrapped in `wakeForAction`, `tapText` cascade implementation
- `app/src/main/kotlin/com/hermesandroid/relay/accessibility/ScreenReader.kt` — `service.windows` iteration, `w<windowIndex>:<sequentialIndex>` node-ID scheme
- `app/src/main/kotlin/com/hermesandroid/relay/accessibility/ScreenHasher.kt` (new in v0.4 — SHA-256 content fingerprint primitive, covered in `docs/spec.md` §6.4.2 but not a separate ADR since it's additive rather than a cross-cutting pattern)
- `docs/plans/2026-04-13-bridge-feature-expansion.md` — A8, A9, P1 units
- `docs/spec.md` §6.4.2 — architectural-patterns subsection

---

### 18. Unattended-access visibility: in-app banner vs. system-overlay chip (2026-04-17)

**Context:** v0.4.1's unattended-access mode (sideload-only) lets the agent wake the screen and dismiss the keyguard while the user is physically away from the phone. That's a meaningful expansion of agent authority — the user should have a prominent, always-on affordance telling them the mode is live so they can disable it at a glance. The existing v0.4.1 unattended work shipped the WindowManager `BridgeStatusOverlayChip` in an "amber Unattended ON" variant to cover this, but that surface only renders when the Hermes-Relay app is backgrounded (the floating chip is drawn via `SYSTEM_ALERT_WINDOW` and the user doesn't see their own app's overlay on top of itself). While the user is IN Hermes-Relay — on any tab — there was no always-on affordance at all; they had to scroll the Bridge tab down to the Unattended Access card to check status.

**Decision:** Ship two complementary affordances with distinct visibility windows, kept deliberately separate rather than trying to unify them.

1. **`UnattendedGlobalBanner`** (in-app) — Compose-drawn 28dp amber strip at the top of `RelayApp`'s scaffold, rendered unconditionally on every tab when `masterEnabled && unattendedEnabled && BuildFlavor.isSideload`. Theme-aware colours (amber-on-dark / dark-amber-on-pale-amber), pulsing dot, chevron → navigates to Bridge. Handles the **app-foregrounded** case.
2. **`BridgeStatusOverlayChip`** (existing) — WindowManager overlay via `SYSTEM_ALERT_WINDOW`, renders the amber "Unattended ON" variant when unattended is on. Forced visible whenever unattended is on, independent of the user's regular "show status overlay" preference. Handles the **app-backgrounded** case.

**Why split, not unified:**

- **OS constraint.** WindowManager overlays don't render on top of the owning app's UI in the foregrounded state — Android deliberately hides a package's own overlays when that package is itself foreground. One surface cannot cover both states.
- **Different visual budgets.** The in-app banner sits inside `RelayApp`'s scaffold and can occupy a full-width strip with text + chevron. The system chip is a floating pill with strict size limits (~180dp wide) to avoid obscuring content in the host app. Text copy differs accordingly.
- **Different z-order semantics.** The in-app banner composes with the rest of the app's UI and respects tab navigation. The system chip is user-positionable via drag and persists across app switches. Unifying them would force the weaker of the two behaviours onto the stronger surface.
- **Different dismissibility.** The in-app banner is non-dismissible and always renders when the preconditions are met (it's a fixed affordance, not a notification). The system chip is user-draggable but non-tap-dismissable while unattended is on. Both fail-closed.

**What the user sees (sideload only, unattended on):**

- On any Hermes-Relay tab → amber banner pinned to the top of the scaffold. Tap the chevron to jump to the Bridge tab and disable.
- App in the background (any other app foregrounded, or home screen) → floating amber "Unattended ON" chip in the system overlay. Tap to return to Hermes-Relay.

**Consequences:**

- **Correct coverage.** There is now no app-state window in which the user can miss that unattended mode is on.
- **Two places to maintain.** Copy changes need to ripple to both surfaces. Mitigated by keeping the strings short and similar but not identical (the in-app banner has more room and uses "agent can wake and drive this device"; the system chip uses the tighter "Unattended ON"). Both surfaces are driven by the same `unattendedEnabled` StateFlow, so state can't drift.
- **Theme story differs.** The in-app banner honours the Material 3 theme (light / dark / dynamic colour). The system chip uses a fixed amber for visibility on arbitrary host apps. This is a feature, not a defect — the system chip has no knowledge of the underlying app's theme and amber on amber would vanish.

**Alternatives rejected:**

- **One unified overlay chip, rendered even while the app is foregrounded.** Requires a custom always-foreground overlay mode via `TYPE_APPLICATION_OVERLAY` + manual z-order tricks; Android actively fights this on modern targets. Cost / benefit not worth it when a Compose-drawn banner solves the foreground case trivially.
- **Persistent foreground notification as the only affordance.** We already have one (`BridgeForegroundService`), and it's the authoritative in-sight kill switch. But (a) it lives in the shade until the user pulls it down and (b) it's for the master-toggle-on state, not for unattended specifically. A per-mode notification channel was considered and rejected as notification shade clutter.
- **In-app banner only, drop the system chip.** Regresses the backgrounded-app case. The system chip is the only surface that can tell the user at a glance that unattended is on while their phone is running a different app — exactly the case unattended is designed for.

**References:**

- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/UnattendedGlobalBanner.kt` (new in v0.4.1 polish pass)
- `app/src/main/kotlin/com/hermesandroid/relay/ui/RelayApp.kt` — banner mount point at scaffold top
- `app/src/main/kotlin/com/hermesandroid/relay/bridge/BridgeStatusOverlay.kt` — `BridgeStatusOverlayChip` amber variant (shipped with the initial v0.4.1 unattended work)
- `docs/spec.md` §5 Bridge Tab — user-facing description

### 19. Multi-Connection Support — one app, many Hermes servers (2026-04-18)

> **Terminology note (2026-04-18):** earlier drafts of this design called these "profiles" — that's been renamed to "Connection" to avoid collision with Hermes's in-config agent profiles concept (agent.profiles in config.yaml defines name + model + description for each agent personality). A follow-up pass will introduce the agent-profile layer on top of the connection layer described here.

**Decision:** Formalize the implicit single-server connection as a first-class `Connection` entity, with a `ConnectionStore` that holds N connections in DataStore and persists the active one. Switching connection is a heavy context swap: cancel in-flight SSE, disconnect relay WSS, rebuild `HermesApiClient`, update URL `StateFlow`s (which `RelayHttpClient`/`RelayVoiceClient` providers already re-read lazily), rebuild `AuthManager` against a connection-scoped `EncryptedSharedPreferences` file, reconnect, reprobe capabilities, reload sessions/personalities, restore per-connection last-active session.

**Why:**
- Users with multiple Hermes installs (home + work, dev + prod, multiple NAS hosts) want to switch targets without wiping pairing state or re-running onboarding.
- A "connection" in this app's terminology is *a separate gateway instance* — there is no `/api/connections` endpoint to enumerate. The honest model is "connection = baseUrl + bearer + pairing record". This also matches how Discord achieves multi-agent-in-one-channel: each "agent" is a distinct bot backed by its own gateway.
- Personalities (Decision 8) are orthogonal and remain per-connection — each server exposes its own personality map via `/api/config`.

**How:**
- **Connection model:** UUID id, editable label (default = hostname), `apiServerUrl`, `relayUrl`, `tokenStoreKey` (EncryptedSharedPreferences file name), `pairedAt`, `lastActiveSessionId`, `transportHint`, `expiresAt`. Serialized as a JSON array in DataStore key `connections_v1`; active connection in `active_connection_id`.
- **Migration:** on first launch of the new version, `ConnectionStore.migrateLegacyConnectionIfNeeded()` seeds connection 0 with the existing `hermes_companion_auth_hw` file as its store — zero re-pair, zero token migration, fully transparent to the user. Additionally, the DataStore key rename (`profiles_v1` → `connections_v1`, `active_profile_id` → `active_connection_id`) migrates in-place on first launch after the rename pass.
- **Auth layer:** `SessionTokenStore.tryCreate()` accepts a `prefsName` parameter; `AuthManager` gains a `connectionId` constructor parameter and picks the store file via `Connection.buildTokenStoreKey(id)`. `CertPinStore` is unchanged — pins are already keyed by `host:port` and correctly shared across connections targeting the same server.
- **Network rebind:** `RelayHttpClient` and `RelayVoiceClient` take provider lambdas that read URL + token at call time, so updating the backing `MutableStateFlow`s is sufficient — no client recreation. `HermesApiClient` is recreated (private OkHttp pool + SSE factory), using the existing `rebuildApiClient()` path in `ConnectionViewModel`.
- **UI:** top-bar connection chip → `ConnectionSwitcherSheet` bottom sheet with radio list + "Manage connections…" link. `Screen.ConnectionsSettings` hosts card CRUD: rename (inline), re-pair (reuses `ConnectionWizard` with `connectionId` nav arg), revoke, remove.
- **Remove semantics:** `ConnectionStore.removeConnection()` calls `context.deleteSharedPreferences(tokenStoreKey)` to wipe the connection's auth material. Cert pin survives in the global TOFU map keyed by host:port so re-adding the same server is still trusted.

**Scope — what's per-connection vs. global:**

| Per-connection | Global |
|---|---|
| API baseUrl + bearer | Theme, dev-mode toggles |
| Sessions, messages, search | Bridge safety prefs (blocklist, destructive verbs, auto-disable) |
| Personalities (`/api/config`) | Feature flags / DataStore overrides |
| Skills, memory | Notification companion enabled/disabled |
| Relay WSS endpoint + cert pin (shared by host) | Keystore itself (one keystore, many entries) |
| Voice transcribe/synthesize endpoint | |
| Bridge command target | |
| Last-active session ID | |

**Trade-off — Bridge safety prefs are global:** a blocklist entry added for server A also applies when connected to server B. Accepted for v1 because the safety model is phone-wide (one user, one device, same risk appetite). If users report the shared blocklist biting, split per-connection later — the store shape allows it without breaking compatibility.

**Trade-off — "both agents in one channel" (Discord-style) deferred:** a unified chat view showing interleaved messages from two connections is possible but semantically fraught: sessions, memory, and tool calls don't merge on the server side, so the unified view would be purely client-side theater. Deferred until there's a concrete use case; v1 users switch contexts with one tap and carry on.

**Upstream opportunity:** a real `/api/profiles` endpoint that returns `[{id, name, endpoint, model}]` from a single config would let one hermes install expose multiple agent profiles without the user running multiple daemons. That's the upcoming Pass 2 work on top of this connection layer — note that upstream's concept ("agent profile") is different from the multi-server concept this decision records ("connection"). Filed alongside `/api/commands` as a future contribution in `docs/upstream-contributions.md`.

**References:**
- `app/src/main/kotlin/.../data/ConnectionData.kt` — `Connection` + helpers
- `app/src/main/kotlin/.../data/ConnectionStore.kt` — StateFlows + CRUD + migration
- `app/src/main/kotlin/.../auth/AuthManager.kt` — `connectionId` ctor. (The `sessionLabels` field mentioned in earlier drafts has been replaced by `agentProfiles: StateFlow<List<Profile>>` in §21.)
- `app/src/main/kotlin/.../viewmodel/ConnectionViewModel.kt` — `switchConnection()` orchestration
- `app/src/main/kotlin/.../ui/components/ConnectionSwitcherSheet.kt` — bottom-sheet switcher
- `app/src/main/kotlin/.../ui/screens/ConnectionsSettingsScreen.kt` — CRUD screen

---

### 20. Dashboard plugin: single plugin with internal tabs + pre-built IIFE bundle (2026-04-18)

**Context:** The upstream Dashboard Plugin System landed on hermes-agent's `axiom` branch in three commits (`01214a7f` plugin system, `3f6c4346` theme, `247929b0` OAuth providers). The gateway now scans `~/.hermes/plugins/<name>/dashboard/manifest.json` at startup and exposes registered plugins as tabs in the web UI, with a frontend SDK exposed at `window.__HERMES_PLUGIN_SDK__` (React + shadcn subset + hooks) and a plugin-registration global at `window.__HERMES_PLUGINS__.register(name, Component)`. Several deferred items from the MVP audit (cron manager, skills browser, memory viewer) previously blocked on "needs relay extension" flip with this system — most belong in the upstream dashboard directly. The relay plugin only needs to surface the four items that **only the relay knows about**: paired-device state, bridge command history, push delivery (future), and active `MediaRegistry` tokens. The question was how to ship those four surfaces cleanly.

**Decision:** Ship one plugin at `plugin/dashboard/` exposing a single tab `/relay` that hosts four internal sub-tabs (Relay Management, Bridge Activity, Push Console, Media Inspector), with the frontend authored in JSX under `src/`, bundled to a committed IIFE at `dist/index.js` via esbuild, and a thin FastAPI proxy at `plugin_api.py` forwarding to loopback-only relay HTTP routes.

Four sub-decisions captured together:

1. **Single plugin with internal `Tabs`, not four plugins.** The upstream manifest allows exactly one `tab.path` per plugin. Four plugins would fragment the dashboard nav (four "Relay — …" entries between Skills and whatever comes next), confuse operators ("where's paired-device state?"), and multiply the manifest / rescan surface for no UX gain. One plugin at `/relay` with an internal shadcn `Tabs` component keeps relay concerns grouped and gives us a single header (Auto-refresh toggle, health pill, version string) across all four sub-surfaces.

2. **Pre-built IIFE bundle, committed to git.** The upstream example plugin (`plugins/example-dashboard/dashboard/`) uses plain `React.createElement` with no build step. That's readable for a one-tab proof of concept; for four non-trivial tabs with shared `lib/` helpers (sentence formatters, relative-time, byte-size, API wrappers) it's actively painful to maintain. Decision: author JSX under `plugin/dashboard/src/`, bundle with `esbuild` (target `es2020`, format `iife`, React coming from the SDK global — NOT bundled), minify, and commit the ~16 KB `dist/index.js` alongside the source. Operators who `git pull` get a ready-to-serve bundle; the dashboard `<script src>` loads it verbatim and never runs a build. `esbuild` is the only dev dep, listed in `plugin/dashboard/package.json`.

3. **Loopback-only for the three new relay routes.** `/bridge/activity`, `/media/inspect`, `/relay/info` are gated with the same `_require_loopback()` helper pattern as `/bridge/status`, `/pairing/register`, and `/media/register` — any `request.remote` other than `127.0.0.1` / `::1` gets HTTP 403. The plugin backend runs inside the gateway process (itself bound to localhost) and calls the relay at `http://127.0.0.1:{HERMES_RELAY_PORT}/...` — no bearer minting, no new credentials, no new attack surface. We also added a loopback-exempt branch to the existing bearer-gated `/sessions` handler so the plugin can list paired devices without the relay needing to hand itself a token. Media paths are sanitized (basename-only) at the `MediaRegistry.list_all()` layer so a future decision to expose these routes externally wouldn't leak filesystem layout.

4. **Dashboard backend is a thin proxy; relay is source of truth.** `plugin_api.py` exposes five routes at `/api/plugins/hermes-relay/{overview,sessions,bridge-activity,media,push}` and forwards to the relay over `httpx.AsyncClient` with a 5-second timeout. No business logic — the plugin never maintains its own state, never caches, never retries. Relay connect-error / timeout / 5xx translate to `HTTPException(502, detail=…)` carrying the relay address, so the UI can render a "relay unreachable at 127.0.0.1:8767" banner; 4xx passes through verbatim. The one exception is `/push` — since FCM isn't wired, this route is a static stub returning `{configured: false, reason: "FCM not yet wired; …"}` with no network call. Keeps the four-tab nav layout correct for when FCM lands; swapping in real data only touches `PushConsole.jsx` + `plugin_api.py::get_push`.

**Consequences:**

- **Zero upstream dependency.** We ship against the already-published `axiom` plugin-system contract (scanner + manifest shape + SDK globals) without needing to patch hermes-agent. If upstream changes the icon whitelist or the SDK surface, we adjust the manifest / `src/` and rebuild — no relay changes.
- **Installer already covers discovery.** `install.sh` step 3 symlinks `~/.hermes/plugins/hermes-relay` → `<repo>/plugin`, so `~/.hermes/plugins/hermes-relay/dashboard/manifest.json` resolves automatically on any host that's already installed us. No new installer step; the plugin is live after the next `hermes-gateway` restart.
- **One extra dev dep (`esbuild`).** Listed in `plugin/dashboard/package.json` under `devDependencies`. Not a runtime dep — the gateway never runs `npm install`. Developers who want to modify the frontend run `cd plugin/dashboard && npm install && npm run build`, which regenerates `dist/index.js`; CI doesn't run the build because the committed bundle is the shipped artifact.
- **Committed build artifact policy.** `dist/index.js` is committed to git alongside the source. Unusual — most projects would `.gitignore` it — but required here because the dashboard scans live filesystem paths and has no build step of its own. The esbuild output is deterministic given the same source + minify flags, so PR review can diff the source without fighting the bundle; operators treat the bundle as read-only.
- **Loopback-branch divergence on `/sessions`.** The bearer-gated `/sessions` path still returns `is_current: bool` (true for the caller's own bearer); the loopback branch returns the same session rows without `is_current` (no caller context). Documented in `docs/relay-server.md` and in the handler's docstring so future maintainers don't lose the divergence.
- **Push Console UX before FCM.** The stub tab renders an "FCM not configured" banner + link to the deferred-items doc, which is honest but does occupy a nav slot for a non-working feature. Accepted trade-off: the alternative is shipping three tabs now and a four-tab reshuffle later, which churns muscle memory and breaks any `localStorage` tab-selection persistence.

**Alternatives rejected:**

- **Four separate plugins** (one per sub-tab). Rejected — fragments nav, multiplies manifest surface, no UX win.
- **No build step; plain `React.createElement` in `dist/index.js` directly.** Readable for one tab; actively hostile for four tabs + shared helpers. Source unreviewable once it hits ~500 LOC.
- **Bundle React into the IIFE.** Would bloat the bundle from ~16 KB to ~150 KB and introduce React-version drift risk vs. the dashboard shell's React. Using the SDK's `window.__HERMES_PLUGIN_SDK__.React` keeps us pinned to whatever the dashboard ships.
- **Plugin mints its own "dashboard key" bearer via a new relay config field.** Would work but adds a new credential to manage (rotation, storage, revocation) for no security benefit over the existing loopback gate — both layers already require localhost access.
- **Skills browser / cron manager / memory viewer as relay-plugin tabs.** Rejected — these belong in upstream dashboard plugins, not a relay plugin. The relay has no unique visibility into them; upstream knows everything we'd know.
- **Hot-reload the Python `plugin_api.py` without a gateway restart.** Upstream supports hot-reload for the frontend bundle (`POST /api/dashboard/plugins/rescan`) but Python modules still need a full gateway restart. Accepted — the backend is a thin proxy that rarely changes.

**References:**

- `plugin/dashboard/manifest.json` — plugin manifest (name, label, icon, tab config, entry bundle, api module)
- `plugin/dashboard/plugin_api.py` — FastAPI router; five proxy routes + structured 502 error translation
- `plugin/dashboard/src/index.jsx` — React root registering via `window.__HERMES_PLUGINS__.register("hermes-relay", …)`
- `plugin/dashboard/src/tabs/{RelayManagement,BridgeActivity,PushConsole,MediaInspector}.jsx` — four tab components
- `plugin/dashboard/src/lib/{api,formatters}.js` — SDK `fetchJSON` wrappers + time/byte formatters
- `plugin/dashboard/dist/index.js` — committed IIFE bundle (~16 KB minified), loaded verbatim by the dashboard shell
- `plugin/dashboard/test_plugin_api.py` — 10 backend proxy tests
- `plugin/relay/server.py` — `handle_bridge_activity`, `handle_media_inspect`, `handle_relay_info`, `_require_loopback()`, loopback branch on `handle_sessions_list`
- `plugin/relay/channels/bridge.py` — `BridgeCommandRecord` dataclass + `recent_commands` deque + `get_recent()` (commit `777a06a`)
- `plugin/relay/media.py` — `MediaRegistry.list_all()` (commit `2212fbc`)
- `docs/spec.md` §10.1 Dashboard plugin — user-facing overview + route table
- `docs/relay-server.md` HTTP Routes — wire-shape details for `/bridge/activity`, `/media/inspect`, `/relay/info`, `/sessions` loopback branch

### 21. Agent Profile picker — directory-discovered overlay of model + SOUL (2026-04-18)

**Decision:** The relay auto-discovers upstream Hermes profiles by scanning `~/.hermes/profiles/*/` (plus a synthetic "default" entry for the root `~/.hermes/config.yaml`). Each discovered profile is advertised in the `auth.ok` payload as `{name, model, description, system_message}`. On chat send with a profile selected, the phone overrides the request's `model` field with the profile's model AND uses the profile's `SOUL.md` content (carried in `system_message`) as the request's `system_message`. Selection is ephemeral and clears on Connection switch.

**Why this supersedes the original §21 design:**

The first pass read from a fictional top-level `profiles:` / `agents:` list in `~/.hermes/config.yaml`. Upstream Hermes has **never** used that schema — profiles upstream are isolated directory instances at `~/.hermes/profiles/<name>/`, each with its own `config.yaml`, `.env`, `SOUL.md`, memory, sessions, skills, and (optionally) its own gateway daemon. The old path always returned an empty list on real installs, which is why nothing ever populated in the picker.

Rather than invent our own schema, match upstream's layout. The relay scans the directory, reads each profile's config.yaml + SOUL.md, and reports what's really there.

**Three-layer model (unchanged at the UI level):**
- **Connection** (§19) — which Hermes server + gateway. One scan per server.
- **Profile** (this decision) — which upstream-layout agent directory on that server. Overlays model + SOUL.
- **Personality** (§8) — which system-prompt preset *within* the agent's config.

**How:**

Server side (`plugin/relay/config.py`, `plugin/relay/server.py`):
- `_load_profiles` rewritten to walk `~/.hermes/profiles/*/`. For each profile: `name = dir.name`; `model = config.yaml/model.default || "unknown"`; `description = config.yaml/description || first non-blank line of SOUL.md || ""`; `system_message = SOUL.md content || null`. Plus a synthetic `"default"` entry from the root config.
- New `RelayConfig.profile_discovery_enabled: bool = True`. Set to `false` in the relay's config to skip the scan and advertise an empty list — matches our configurability pattern of "opt-out defaults" for discovery features. Disabled state is logged at INFO on startup.
- `auth.ok` payload shape: each entry is `{"name": str, "model": str, "description": str, "system_message": str | null}` (snake_case on the wire).

Client side (`app/src/main/kotlin/.../data/ProfileData.kt`, `auth/AuthManager.kt`, `viewmodel/ChatViewModel.kt`):
- `Profile(name, model, description, systemMessage: String? = null)`, `@SerialName("system_message")` for the last field.
- `AuthManager.parseAgentProfiles` reads the new `system_message` field (null-safe, default null).
- `ChatViewModel` send path: if `selectedProfile != null`, pass `modelOverride = profile.model` AND, when `profile.systemMessage?.isNotBlank() == true`, use `profile.systemMessage` as the `system_message` override. Profile's `system_message` WINS over the personality's system message when both are selected — the profile is a richer/newer concept, and picking both implies the user wants the profile's full persona.
- `ConnectionViewModel.selectedProfile` and the reset-on-Connection-switch rule are unchanged from the earlier pass; `HermesApiClient.modelOverride` is unchanged.

**Trade-offs / v1 scope:**
- **Overlay, not isolation.** Selecting a profile on the phone routes through the active Connection's gateway with the profile's model + SOUL applied. The profile's `.env` (different API keys), memory, sessions, skills, and cron jobs stay with the Connection's default gateway. For fully isolated profile state, run the profile's own gateway (`hermes -p <name> platform start api --port <other>`) and pair it as a **separate Connection**. The server-side docs for this live in `plugin/relay/config.py` + `user-docs/features/profiles.md`.
- **SOUL.md size.** Some SOUL files are multi-KB (Mizu's is 8 KB). The full content ships as `system_message` on every chat turn. Upstream's personality path already ships a system prompt per turn, so this is consistent — but it's worth keeping SOUL.md concise.
- **Ephemeral only.** Selection doesn't persist across app restarts. Re-opening the app lands on server-default. Persisting per-Connection is a ~30-line extension (add `lastSelectedProfileName` to the Connection data class) — filed as a follow-up.
- **Chat-only.** Voice transcribe/synthesize and bridge commands don't honor the profile selection. Voice routes through `/voice/*` relay endpoints that don't carry `model` or `system_message`; bridge is unrelated to model choice.
- **Config toggle.** `profile_discovery_enabled = false` lets a server op keep the phone picker empty (e.g. if the operator wants Connections-only semantics). Per our configurability pattern of "enable useful defaults, let the operator opt out."

**Earlier (abandoned) design, for the record:**

First attempt parsed a top-level `profiles:` / `agents:` list from one YAML. That schema does not exist upstream and always returned empty on real installs. The data class + picker + `modelOverride` wiring from that attempt are preserved; only the data source + the addition of `system_message` changed. See commits `0303a4f` (initial parse), `b9d2914` (build fix), and the R1/R2 pass that followed this decision rewrite.

**References:**
- `app/src/main/kotlin/.../data/ProfileData.kt` — the data class
- `app/src/main/kotlin/.../auth/AuthManager.kt` — `parseAgentProfiles` companion
- `app/src/main/kotlin/.../viewmodel/ChatViewModel.kt` — precedence rule
- `app/src/main/kotlin/.../network/HermesApiClient.kt` — `modelOverride`
- `app/src/main/kotlin/.../ui/components/ProfilePicker.kt` — chip + dropdown
- `plugin/relay/config.py:_load_profiles` — directory-scan source of truth
- `plugin/relay/server.py` — auth.ok payload emission (enriched shape)
- Upstream doc: `~/.hermes/hermes-agent/website/docs/user-guide/profiles.md` (canonical profile-layout definition)

---

### 22. Profile-scoped read-only config + skills API (2026-04-18)

**Decision:** Add two relay-native HTTP routes — `GET /api/profiles/{name}/config` and `GET /api/profiles/{name}/skills` — that read `<profile_home>/config.yaml` and walk `<profile_home>/skills/<category>/<name>/SKILL.md` respectively. Both are read-only, use the existing loopback-or-bearer auth pattern (matching `/notifications/recent`), and resolve `name` via the same `default → ~/.hermes` / otherwise → `~/.hermes/profiles/<name>` helper used by `_load_profiles`. For `PUT /api/skills/toggle` (the upstream toggle shape the phone's capability probe checks), the bootstrap registers a stubbed handler that always returns **501 Not Implemented** with a structured `{"error": "skill_toggle_not_implemented", ...}` body.

**Why relay-native, not a dashboard proxy:**

The hermes-agent dashboard exposes `/api/config` and `/api/skills` via `hermes_cli/web_server.py` — a separate loopback-only web server from the chat API at `:8642`. Two problems if we proxied through the dashboard:
1. **No profile scoping.** The dashboard's `/api/config` operates on the active profile only; there's no way to read another profile's config without switching first. Our relay already has the layout knowledge (`_load_profiles` scans the tree); duplicating that as "switch profile, read, switch back" is fragile and racy.
2. **Secrets leakage risk.** `config.yaml` never holds credentials (those live in `~/.hermes/.env` + `~/.hermes/auth.json`), but proxying a general-purpose config endpoint invites future callers to pick up sensitive fields. A purpose-built read route keeps the attack surface small and the shape explicit — the response is `{profile, path, config, readonly: true}`, with the `readonly` flag part of the contract so clients can't silently assume write support.

Both endpoints trust the same boundary as every other phone-facing relay route: bearer-auth for remote callers, loopback for in-process dashboard proxy calls. `.env` and `auth.json` are **never** read or returned by these routes.

**Why read-only in v0.7:**

Write support would require an `active_profile` routing layer on the relay — picking which profile's config to mutate, flushing caches, notifying any running gateway daemon for that profile. hermes-desktop handles this by shelling out to `hermes profile use <name>` + `hermes config set ...`, which bakes the lifecycle into the CLI. Doing the equivalent from the relay means either (a) shelling out (new subprocess surface, env handling, error classification) or (b) reimplementing the write path in Python (duplicates upstream logic, risks drift). Neither belongs in this pass. The `readonly: true` field in the response is a deliberate contract — callers that probe it can hide edit UI cleanly when (eventually) a v0.8 server drops the flag.

**Why 501 on `PUT /api/skills/toggle` instead of omitting the route:**

`tools.skills_tool` (the upstream module the bootstrap already imports for `skills_list` / `skill_view`) has no clean enable/disable hook — that logic lives on `hermes_cli.web_server.py`, which the relay doesn't proxy. Three options:
1. Don't register the route → 404 → Android capability probe can't tell "toggle not implemented" from "wrong URL / server too old."
2. Register with full behavior → requires either shelling to the CLI or duplicating upstream persistence. Same objections as write-path above, worse because skill toggle state is per-profile and upstream persists it in a JSON sidecar whose shape we don't want to pin.
3. Register a 501 stub with a stable structured body → capability probe sees the route, reads `error: "skill_toggle_not_implemented"`, renders the toggle UI as disabled with a tooltip. Client logic stays a simple switch on error code; no magic version sniffing.

We pick (3). When upstream PR #8556 or a follow-up exposes a real toggle on `api_server.py`, we flip the stub to call through — the Kotlin client sees the 501 disappear and the toggle unlocks.

**Trade-offs:**
- **Profile scoping is a directory lookup, not an active-profile check.** The request says "read profile X"; we do not require X to be the currently-active profile for the dashboard or for any gateway daemon. This matches the read-only intent — the phone picker shows every profile's shape, even ones no gateway is running against.
- **Skill `enabled: true` is hardcoded.** We don't track disabled state server-side yet; the field is part of the response only so the shape aligns with upstream's eventual toggle response. Keep it stable so the Kotlin model doesn't need reshuffling when toggles land.
- **No pagination on skills.** A profile with thousands of skills would get a fat response, but that's well outside current usage (the populous profiles on our server carry 20-40 skills). Revisit if this stops being true.
- **Path-traversal guard is light.** Profile name is rejected if it contains `/`, `\\`, `.`, or `..`. That's enough for the `profiles/<name>/` join we do, and matches how `_load_profiles` already trusts `iterdir()` results — names that arrive over the wire get the extra check.

**Why not just surface `_load_profiles`'s new `gateway_running` / `has_soul` / `skill_count` everywhere?**

Those fields (added in the same commit series — see `auth.ok` profile shape) are intentionally summary-only. The picker uses them to render status; the detail screen pulls the full config + skill list via the new endpoints. Splitting summary (cheap, piggybacks on existing `auth.ok`) from detail (expensive enough to warrant on-demand HTTP) keeps pairing snappy even for servers with many skills per profile.

**References:**
- `plugin/relay/server.py` — `handle_profile_config`, `handle_profile_skills`, `_resolve_profile_home`
- `plugin/relay/config.py:_load_profiles` — summary-field source (§21)
- `hermes_relay_bootstrap/_handlers.py:toggle_skill` — 501 stub
- `docs/spec.md` §6.1 — HTTP routes table rows
- `TEMP-hermes-desktop-analysis.md` (session scratch, soon deleted) — `hermes-desktop` patterns we cross-referenced for the read/write split and credential-pool separation

**Scope addendum (2026-04-18):** Followed up with two more profile-scoped read routes — `GET /api/profiles/{name}/soul` and `GET /api/profiles/{name}/memory` — to feed the phone Profile Inspector's four-section layout (config / SOUL / memory / skills). Both reuse `_resolve_profile_home`, the loopback-or-bearer gate, and the `profile_not_found` 404 shape from the original two endpoints. Content is capped inline at **200KB for SOUL.md** and **50KB per memory file**; larger bodies flag `truncated: true` and clients see the on-disk `size_bytes` so they know real dimensions without fetching the full body. The caps exist because the Inspector is a viewer, not a diff or edit tool — phone-safe wire sizes matter more than lossless fidelity, and anyone who needs a full dump has the dashboard. Memory listing is intentionally non-recursive (one `iterdir` pass, no `rglob`) so subdirectories under `memories/` — used by some users for archival snapshots — don't spam the response. `MEMORY.md` and `USER.md` (per upstream `hermes_cli/profiles.py`) sort first; the rest are alphabetical, so the ordering is stable across filesystems. Absent SOUL.md returns 200 with `exists: false`; absent `memories/` returns 200 with an empty `entries` array — both let the Inspector render the section rather than mask "no content yet" as a transport failure.

---

## Voice Mode — Architecture

**Context:** We wanted real-time voice conversation with the Hermes agent — user speaks, agent listens, agent speaks back, orb reacts. Hermes-agent has six TTS providers and five STT providers fully implemented in `tools/tts_tool.py` and `tools/transcription_tools.py`, plus a CLI `voice_mode.py` that uses them for push-to-talk — but none of it is exposed via the WebAPI server at `:8642`. The phone has no way to call voice functions over HTTP.

**The four decisions:**

### 1. Voice endpoints live on the relay, not upstream hermes-agent

Three options were considered:

1. **Patch upstream `gateway/platforms/api_server.py`** — add `/api/voice/transcribe` / `/api/voice/synthesize` routes directly. Cleanest long-term, but requires upstream PRs, version pinning, and blocks shipping voice until upstream merges. Rejected for this cut.
2. **Relay plugin hosts the endpoints** — `plugin/relay/voice.py` imports `tools.tts_tool.text_to_speech_tool` and `tools.transcription_tools.transcribe_audio` directly from the hermes-agent venv (where the relay is editable-installed) and wraps them in async handlers. Chosen.
3. **Phone calls provider APIs directly** — ElevenLabs / OpenAI / Groq SDKs on Android. Rejected: would require API keys stored on the phone, different providers per-device, no unified config, and the phone would need to replicate the provider-selection logic that `tts_tool.py` / `transcription_tools.py` already implement.

**Why (2):**
- The relay already runs inside the hermes-agent venv (editable install per `install.sh` — `pip show hermes-relay` confirms). `from tools.tts_tool import text_to_speech_tool` just works.
- Provider config (`tts:` and `stt:` sections) is already in `~/.hermes/config.yaml` — the tools read it internally. The phone gets whatever provider the operator configured on the server, with zero per-device keys.
- No upstream dependency. Ships as part of hermes-relay; updates via `git pull` in the plugin clone.
- If upstream later adds native voice endpoints to `api_server.py`, the relay can switch to proxying without phone changes.

**Trade-offs accepted:**
- Uses private upstream helpers (`_load_tts_config`, `_load_stt_config`) for the `/voice/config` endpoint because they're the cleanest way to report what's configured. Marked clearly as private/unstable in the handler — if upstream refactors these, our `/voice/config` response shape is the only thing that needs a patch.
- Voice endpoints fail with 503 on servers where the hermes-agent venv doesn't have the providers' optional deps installed. Documented — operator's responsibility to run `pip install "hermes-relay[voice]"` or the equivalent.

### 2. Buffer-not-stream TTS responses (sentence-level client chunking)

The plan initially suggested streaming audio/opus over chunked HTTP — sentence-by-sentence during agent streaming. Two problems:

- `text_to_speech_tool` is **sync** and returns a file **path**, not a chunked byte generator. Streaming output would require rewriting the function or calling provider SDKs directly (MiniMax/ElevenLabs both support streaming, Edge TTS doesn't, NeuTTS doesn't).
- Android `MediaPlayer` prefers complete files — chunked Opus works but requires AudioTrack + manual Opus decode, which is a different layer of complexity.

**Decision:** The relay returns whole `.mp3` files per request. The client does sentence-boundary detection on the SSE chat stream (`VoiceViewModel.startStreamObserver` diffs `messages: StateFlow`), extracts complete sentences as they arrive, POSTs each sentence to `/voice/synthesize`, queues the resulting mp3 file into a `Channel<String>`, and a consumer coroutine plays them one after another with `awaitCompletion` between. First audio plays within one sentence of the agent starting to respond — effectively the same UX as true streaming.

**Why:**
- Zero upstream changes required.
- Works across all six TTS providers uniformly (not just the 3 with streaming SDKs).
- `MediaPlayer` handles mp3 natively — no AudioTrack + decoder ceremony.
- Bad sentences (failed TTS) don't corrupt the stream — the queue just skips.

**Trade-off:** If the agent response is one giant sentence, latency is dominated by that sentence's synthesis time. In practice responses have frequent punctuation so this is a non-issue.

### 3. `.m4a` (MPEG-4/AAC), not `.webm`, for recorder output

The plan suggested WebM/Opus from `MediaRecorder` (API 29+). Rejected:

- WebM support in Android's `MediaRecorder` arrived in API 29 but encoder availability is vendor-dependent on older builds. AAC in MPEG-4 is guaranteed since API 18 and universally supported.
- OpenAI's `whisper-1` and Groq's `whisper-large-v3-turbo` both accept `.m4a` / `.mp4` via the upstream `_validate_audio_file` path in `transcribe_audio`.
- Faster-whisper (local STT default) transparently handles m4a via ffmpeg.
- M4a at 16kHz/64kbps mono is ~8KB/second — small enough for LAN upload, indistinguishable from Opus at that sample rate.

Use `.m4a`.

### 4. ChatViewModel integration via observation, not callbacks

The plan called for adding a `// VOICE HOOK` callback to `ChatViewModel` so `VoiceViewModel` could see streaming deltas in real time and feed them to the sentence-detection buffer.

**Rejected.** Instead `VoiceViewModel.startStreamObserver` collects `chatVm.messages: StateFlow<List<ChatMessage>>` (already public on `ChatHandler`) and diffs the last assistant message's content length on each emission to extract streaming deltas. Zero changes to `ChatViewModel` or `ChatHandler`.

**Why:**
- Keeps chat code decoupled from voice. If voice mode is ripped out tomorrow, `ChatViewModel` is untouched.
- Observes the same state the chat UI observes — no divergence risk.
- Transcribed user text routes through the existing `chatVm.sendMessage(text)` path, so voice utterances appear as normal user messages in chat history. Load the session on another device and you see the transcript.

**Trade-off documented in a KDoc comment:** relies on the "last `isStreaming=true` message is the current turn" invariant. If `ChatViewModel` ever streams multiple assistant messages concurrently (multi-agent hand-off, for example) this needs a dedicated per-turn flow. Flagged for Phase 3+ review.

### Alternatives explicitly rejected

- **Android `SpeechRecognizer`** (on-device Google speech recognition) for STT — would bypass the relay entirely and lose the "server provider" consistency. Also Google-Play-Services-dependent, which contradicts the "works on degoogled Android" goal.
- **In-app TTS** (`android.speech.tts.TextToSpeech`) — same problem, plus vastly inferior voice quality compared to ElevenLabs / OpenAI. No synergy with the server's provider config.
- **Dedicated voice WebSocket channel on the relay** alongside chat/terminal/bridge — heavier than REST for a request-response modality with no true bidirectional streaming needs. REST + SSE (via the existing chat path) is sufficient.
- **Sphere `voiceMode` expansion to 1.0×** (plan target) — physically impossible with current `baseRadius`/data-ring geometry. Capped at 1.08×, which is still perceptually "bigger" and preserves the data ring's orbit math. Documented in the MorphingSphere KDoc.

**References:**

- `plugin/relay/voice.py` — `VoiceHandler`, `handle_transcribe`, `handle_synthesize`, `handle_voice_config`, `_require_bearer_session`
- `plugin/relay/server.py` — route registration alongside `/media/*`
- `plugin/tests/test_voice_routes.py` — 14 unit tests, `unittest`-based (pytest conftest issue documented in `CLAUDE.md`)
- `app/src/main/kotlin/.../audio/VoiceRecorder.kt` — MediaRecorder amplitude StateFlow
- `app/src/main/kotlin/.../audio/VoicePlayer.kt` — MediaPlayer + Visualizer amplitude StateFlow with OEM fallback
- `app/src/main/kotlin/.../network/RelayVoiceClient.kt` — OkHttp multipart + JSON clients
- `app/src/main/kotlin/.../viewmodel/VoiceViewModel.kt` — turn state machine, sentence detection, TTS queue consumer, `ChatViewModel` observation pattern
- `app/src/main/kotlin/.../ui/components/VoiceModeOverlay.kt` — full-screen overlay, VoiceState→SphereState mapping, three interaction modes
- `app/src/main/kotlin/.../ui/components/MorphingSphere.kt` — Listening/Speaking states, `voiceAmplitude`/`voiceMode` params, @Preview functions for iteration
- Obsidian plan: `3. System/Projects/Hermes-Relay/Plans/Voice Mode.md`

### 23. Branch policy: `main` + `dev` (2026-04-19)

**Evolution of the earlier "Feature branches" policy (2026-04-13):** the
original rule was "feature branches merge into `main` continuously, `main`
is always shippable." That worked while the project was small but produced
two operational frictions as it grew:

1. **Staging had no home.** The server pulled `main` for dogfood, so any
   feature that passed CI was immediately exercised against real pairing /
   bridge / voice state. When a merged-but-rough feature hit a bug on the
   server, rolling back meant reverting a merge commit on the shippable
   branch, which fought the "main is always shippable" invariant.
2. **Release cadence and merge cadence were entangled.** Cutting a release
   meant version-bumping on `main`, which required a branch-protection
   carve-out (`release: vX.Y.Z` direct pushes) because atomic bump + tag
   couldn't round-trip through a PR without racing another merge.

**New policy:**
- `main` = **released state only**. Every commit corresponds to a tag or
  a release-merge from `dev`.
- `dev` = **integration branch**. Feature branches target `dev`; the
  `[Unreleased]` CHANGELOG section lives there.
- Server pulls `dev` for staging. Users and `hermes-relay-update` track
  `main` and tags.
- Releases are opened as PRs from `dev` into `main`, merged `--no-ff`,
  then tagged from `main`. No direct-push carve-out is needed — the
  release PR is just a normal PR.

**Trade-offs accepted:**
- **Hotfix sync step.** When a hotfix lands on `main` from a tag
  (bypassing `dev`), you have to merge `main` back into `dev` so the next
  release doesn't collide on `appVersionCode`. Documented in RELEASE.md
  hotfix recipe.
- **Two branches to keep up to date.** Feature-branch authors need to
  remember that the base is `dev`, not `main`. Mitigated by branch
  protection on both and by CI running on both.
- **Server tracks pre-release state.** Post-policy the server pulls
  `dev`, so the real shift is that the Play Store / sideload-update
  audience now trails `dev` by a release cadence, not a merge cadence.

**CI impact:** the same workflow files trigger on both branches via
path-filtered triggers (`ci-android.yml` / `ci-relay.yml` after the
2026-04-19 split). `docs.yml` stays `main`-only — docs publish represents
shipped state, not integration state. `release.yml` is tag-triggered and
branch-agnostic, unchanged.

**References:**
- `CLAUDE.md` — "Git" section + "Testing / CI is split by path" note
- `RELEASE.md` — "Branching policy" + Release Process step 4 + Hotfix recipe
- `CONTRIBUTING.md` — "Commit Conventions"
- `.github/workflows/ci-android.yml`, `.github/workflows/ci-relay.yml`
- `scripts/bump-version.sh` — prints the dev → main release flow

---

### 24. Multi-endpoint pairing payload + network-aware switching (2026-04-19)

**Problem:** pairing QRs today carry a single `host:port` (API) plus a single
`relay.url`. That works for LAN-only operators, but the moment the phone
moves between LAN / Tailscale / a public reverse-proxy hostname, the
single URL is wrong half the time. Upstream hermes-agent's stance
(SECURITY.md) is "use VPN / Tailscale / firewall or don't expose" — they
don't plan to own a remote-access story. We do.

**Decision:** Extend the QR schema with an **optional ordered list of
endpoint candidates**. The phone picks the highest-priority reachable
candidate at connect time and re-evaluates on network change. Old
single-endpoint QRs remain valid — the phone synthesizes a single
priority-0 candidate from the top-level fields when `endpoints` is absent.

**Wire format (v3 — additive):**

```json
{
  "hermes": 3,
  "host": "192.168.1.100",
  "port": 8642,
  "key": "optional-api-key",
  "tls": false,
  "relay": { "url": "ws://192.168.1.100:8767", "code": "ABC123",
             "ttl_seconds": 2592000, "grants": {...},
             "transport_hint": "ws" },
  "endpoints": [
    { "role": "lan",       "priority": 0,
      "api":   { "host": "192.168.1.100", "port": 8642, "tls": false },
      "relay": { "url": "ws://192.168.1.100:8767",
                 "transport_hint": "ws" } },
    { "role": "tailscale", "priority": 1,
      "api":   { "host": "hermes.tail-scale.ts.net", "port": 8642, "tls": true },
      "relay": { "url": "wss://hermes.tail-scale.ts.net:8767",
                 "transport_hint": "wss" } },
    { "role": "public",    "priority": 2,
      "api":   { "host": "hermes.example.com", "port": 443, "tls": true },
      "relay": { "url": "wss://hermes.example.com/relay",
                 "transport_hint": "wss" } }
  ],
  "sig": "base64-hmac-sha256"
}
```

**Semantics (locked):**
- **`role` is an open string.** Known values `lan` / `tailscale` / `public`
  get styled chips + icons on the phone. Anything else (`wireguard`,
  `zerotier`, `netbird-eu`, whatever the operator wants) renders with a
  generic "Custom VPN" treatment and the raw role label. No enum, no
  validation, no normalization — `role` is preserved exactly as emitted
  so HMAC canonicalization round-trips.
- **`priority` is strict, 0 = highest.** If priority-0 is reachable the
  phone uses it; reachability never promotes a lower-priority candidate
  over a higher one. Reachability is only the tiebreaker for candidates
  that share the same priority. This is the DNS SRV priority/weight
  contract — known-good semantics, nothing new to debate.
- **Reachability probe**: `HEAD` against `${api.tls?https:http}://
  ${api.host}:${api.port}/health` with a 2-second timeout. The result is
  cached for 30 seconds per-endpoint so repeated `connect()` calls don't
  hammer the network. Relay-side reachability is implied — if the API
  server is reachable the relay usually is too, and the first WSS connect
  will loudly fail otherwise.
- **Network-change re-probe**: Android's `ConnectivityManager
  .NetworkCallback.onAvailable` / `onLost` triggers a re-probe. If a
  higher-priority candidate became reachable after a network transition,
  the phone reconnects to it; if the current one became unreachable, the
  phone falls through to the next candidate in priority order.
- **TTL defaults by role** (informational — operator can override at
  pair time): `lan` → 7 days, `tailscale` → 30 days, `public` → 30 days,
  unknown role → 7 days (conservative). Plaintext-`ws://` consent still
  gates any candidate with `transport_hint = "ws"`.

**Canonicalization for the HMAC signature:** `canonicalize()` in
`plugin/relay/qr_sign.py` uses `json.dumps(sort_keys=True,
separators=(",",":"), ensure_ascii=True, allow_nan=False)`. Nested dicts
are sort-keyed; **arrays preserve their emitted order** (priority is
meaningful, not alphabetic). Role strings are embedded verbatim — no
`.lower()`, no `.strip()`. A regression test in `plugin/tests/
test_qr_sign.py` exercises this for mixed-case and non-ASCII role
values so a future refactor can't silently divergence the canonical form.

**Pin store:** `CertPinStore` continues to key by `host:port`, unchanged.
Role doesn't participate in the pin key — if the operator points two
roles at the same hostname they'll share a pin (which is correct: same
cert, same pin). The role → hostname mapping lives in the per-device
endpoint record in DataStore, read by the Paired Devices screen to
render one row per `(device, endpoint)`.

**Backward compatibility:**
- `hermes: 1` and `hermes: 2` QRs continue to parse. When `endpoints` is
  absent, the phone synthesizes a single priority-0 candidate of
  `role: lan` (or `role: tailscale` if the top-level `host` matches the
  Tailscale CGNAT pattern `100.64.0.0/10` or a `.ts.net` suffix — same
  heuristics `TailscaleDetector` already uses).
- `hermes: 3` is the first version that *requires* `endpoints`. The
  bump is bookkeeping only; the parser is version-liberal
  (`ignoreUnknownKeys = true` already, plus a nullable `endpoints`
  field for the intermediate period when the server emits v3 but some
  phones haven't updated yet).

**Alternatives rejected:**
- **Layer Noise / libsignal over WSS.** The operator owns both endpoints
  and the transport is already TLS-terminated by the operator's chosen
  path (Tailscale / reverse-proxy / WireGuard). A second crypto layer
  adds complexity without defending against any threat in our model.
- **Auto-promote reachability over priority** ("LAN is flaky, switch to
  Tailscale even though priority-0 works"). Breaks operator intent; the
  operator might have a reason for preferring LAN (zero egress billing,
  lower latency, local-network-only policy). Strict priority keeps the
  operator in charge.
- **Close-vocabulary `role` enum.** Would force a release every time an
  operator spun up a new mesh VPN. Open-string + known-values-display
  map gives us the UI polish for common cases and zero friction for
  unusual ones.

**Key Files:**
- `plugin/pair.py` — `build_payload()` emits `endpoints`; `--mode` /
  `--public-url` flags; LAN-IP + Tailscale status auto-detection
- `plugin/relay/qr_sign.py` — canonical form docstring now covers arrays
- `plugin/relay/server.py` — `handle_pairing_mint` / `handle_pairing_register`
  accept optional `endpoints` in the body
- `plugin/tests/test_pairing_mint_schema.py` + `test_qr_sign.py`
- `app/src/main/kotlin/.../data/Endpoint.kt` — new `EndpointCandidate`
  data class
- `app/src/main/kotlin/.../ui/components/QrPairingScanner.kt` — payload
  extended; v1/v2 synthesizer
- `app/src/main/kotlin/.../data/PairingPreferences.kt` — per-device
  endpoint list store
- `app/src/main/kotlin/.../network/ConnectionManager.kt` —
  `resolveBestEndpoint()` + `NetworkCallback` plumbing
- `app/src/main/kotlin/.../viewmodel/RelayUiState.kt` —
  `activeEndpointRole` field
- `skills/devops/hermes-relay-pair/SKILL.md` — new flags documented

---

### 25. First-class Tailscale helper as optional hermes enhancement (2026-04-19)

**Problem:** the one piece of first-party upstream remote-access work
([PR #9295](https://github.com/NousResearch/hermes-agent/pull/9295) —
Tailscale Serve integration) is open but unmerged. Our current
`TailscaleDetector` is informational-only. We want Tailscale to be a
first-class supported mode today, without forking upstream.

**Decision:** Ship a thin Tailscale helper in `plugin/relay/tailscale.py`
that mirrors PR #9295's contract — keep the relay loopback-bound, let
`tailscale serve --bg --https=<port> http://127.0.0.1:<port>` terminate
TLS + identity. The helper is **optional** (no-op when the `tailscale`
binary is absent) and **auto-retires** when upstream lands the canonical
`hermes gateway run --tailscale` flag (detection via a one-shot capability
probe; helper prints an `[info]` pointing at the canonical path and
exits 0). Same pattern `hermes_relay_bootstrap/` uses for the
session-API endpoints.

**Surface:**
- `plugin/relay/tailscale.py` — thin module: `status()`,
  `enable(port=8767)`, `disable(port=8767)`, `canonical_upstream_present()`.
  All shell out to `tailscale` CLI and return structured dicts; no new
  daemon, no new state.
- `scripts/hermes-relay-tailscale` — shell shim mirroring `hermes-pair`
  pattern (see `project_hermes_plugin_cli_gap.md` memory — plugin
  `register_cli_command` doesn't reach `main.py` argparse on v0.8.0,
  so shell shim is the working path).
- `install.sh` gets an optional step [7/7]: detect `tailscale` binary;
  if present and the operator hasn't declined, offer to run
  `tailscale serve --bg --https=8767 http://127.0.0.1:8767`. Skipped
  silently if binary absent, `TS_DECLINE=1` set, or non-interactive
  shell without `TS_AUTO=1`.
- `plugin/pair.py --mode auto` calls `tailscale.status()`. If a `.ts.net`
  hostname is available, emit a `role: tailscale` endpoint at the next
  available priority after any `role: lan` entries.

**Why this is the right shape:**
- **Loopback-bound stays loopback-bound.** The relay keeps listening on
  `127.0.0.1:8767`; Tailscale handles the TLS + external reachability.
  No change to the relay's security posture.
- **`tailscale serve` already handles auth** via tailnet ACLs + device
  identity. We don't layer a second auth on top — the relay's existing
  bearer-token session auth covers the application layer.
- **Pins work unchanged.** The `.ts.net` hostname's cert is issued by
  Tailscale's internal CA; TOFU pin captures it on first connect, same
  mechanism as any other wss cert.
- **Graceful absence.** Operator without Tailscale sees zero noise. All
  failure modes exit non-fatal.

**Upstream-merge retirement:** when PR #9295 lands, `canonical_upstream_present()`
returns True (detected via `hermes gateway run --help | grep tailscale`
or a `hermes.version >= X.Y.Z` gate — the exact probe is TODO until
the PR lands and we see what its contract looks like). Helper then
no-ops with a log line pointing at `hermes gateway run --tailscale`.
`install.sh` gains an `# TODO(upstream-merge #9295):` comment marking
the exit criteria. Same removal pattern as `hermes_relay_bootstrap/`
after PR #8556.

**Alternatives rejected:**
- **Bundle our own `tailscaled` daemon.** Replaces Tailscale's existing
  user-facing install / login flow with a worse one. Operators already
  have Tailscale installed or don't — meeting them where they are is
  strictly better.
- **Wait for upstream PR #9295.** Unclear merge ETA; we'd ship v0.4.x
  with the first-class-Tailscale UX paper-thin while waiting.

**Key Files:**
- `plugin/relay/tailscale.py` (new)
- `scripts/hermes-relay-tailscale` (new) — shell shim
- `plugin/tests/test_tailscale_helper.py` (new)
- `install.sh` — optional step [7/7]
- `plugin/pair.py` — `--mode auto` integration
- `docs/remote-access.md` (new) — operator-facing setup guide

---

## ADR 26 — Rich cards via inline `CARD:{json}` line markers

**Status:** Accepted (v0.7.x) — phone-side Phase A. Upstream adapter parity is a separate follow-up (Phase B).

**Context.** The chat feed needed a way to surface structured content — skill results, approval prompts, link previews, calendar entries, weather — as discrete Material 3 cards instead of drowning them in markdown. Upstream `hermes-agent` has zero rich-content abstraction in its platform adapters (`gateway/platforms/base.py` exposes `send()` + `send_image()` + `edit_message()` with no blocks or embeds). Discord uses no `discord.Embed()` at all, Slack uses Block Kit only for the `exec-approval` dialog, and the base class only hints at rich surfaces via the `REQUIRES_EDIT_FINALIZE` attribute for DingTalk AI Cards.

We already had a working precedent: the `MEDIA:` marker in assistant text gives the LLM a lightweight way to attach files that works identically across every streaming endpoint we support (`/v1/runs` + `/api/sessions/{id}/chat/stream` + `/v1/chat/completions`). Cards follow the same recipe.

**Decision.**

1. **Wire format — `CARD:{json}` on its own line.** Parser in `network/handlers/ChatHandler.kt` mirrors the media-marker path: dedicated line buffer, dedupe set, single-line regex, finalize pass on turn/stream complete, reload pass in `loadMessageHistory`. Multi-line JSON is disallowed so the line-buffer strategy stays trivial; escape newlines in string fields as `\n`.

2. **Data model (`data/HermesCard.kt`).** `@Serializable` with `ignoreUnknownKeys = true` so newer agents emitting fields the phone build doesn't know about never crash the parser. Unknown `type` values render via a generic fallback (title + body + fields + actions), so the surface degrades gracefully when we add new built-ins server-side.

3. **Built-in types (Phase A).** `skill_result`, `approval_request`, `link_preview`, `calendar_event`, `weather`. `approval_request` intentionally mirrors the Slack `exec-approval` 4-button pattern (Allow / Allow Session / Always Allow / Deny → map to actions with `style: "primary"` / `"secondary"` / `"danger"`) so future upstream parity (Phase B) is just adapter-side translation, not a data-model rethink.

4. **Accent vocabulary.** `info` / `success` / `warning` / `danger` — semantic, not raw hex, so the renderer pulls from `colorScheme` and dark/light themes stay coherent.

5. **Action dispatch.** `mode` is one of `send_text` (default), `slash_command`, `open_url`. All three route through `ChatViewModel.dispatchCardAction`, which records a `HermesCardDispatch` stamp on the owning message before forwarding — so the card collapses into a "Chose: X" confirmation even if the side effect (browser launch, server round-trip) throws. `open_url` resolves at the UI layer (needs `Context`); the other two reuse `sendMessage` because slash commands are plain text to the server.

**Why markers and not structured SSE events.** A `card.available` event alongside `tool.completed` would be cleaner on the wire but would require a server patch upfront and would NOT work on `/v1/chat/completions` — which is the one endpoint every upstream deployment supports. The marker approach ships today across every endpoint with zero server dependency. If/when we decide structured events are worth the bootstrap cost, the parser can fan out; the `HermesCard` data model stays.

**Why `CARD:` not `<hermes-card>...</hermes-card>`.** Matches the existing `MEDIA:` shape exactly — LLMs have an easier time producing consistent markers when every rich-content construct uses the same grammar. A future delimiter swap (e.g. fenced code blocks) is easy if the flat marker turns out to be fragile in practice.

**Tradeoffs accepted.**
- Marker leakage: if the LLM gets confused mid-turn the raw `CARD:{...}` line can appear in a bubble. We mitigate by leaving unparseable lines in the content (visible artifact beats silent drop) and by including the marker contract in the same section of `prompt_builder.py` as `MEDIA:`.

**Server-side session sync (shipped alongside Phase A).**

Each [com.hermesandroid.relay.data.HermesCardDispatch] carries a `syncedToServer` flag. On the next chat send, `CardDispatchSyncBuilder.buildSyntheticMessages` materializes every unsynced dispatch into an OpenAI-format `assistant` (with `tool_calls`) + `tool` (with `tool_call_id`) pair under a synthetic tool name `hermes_card_action` — a namespaced name the upstream dispatcher will never try to execute, it's a historical audit record only. The arguments object carries `card_key` / `action_value` / `card_type` / `card_title` / `action_label` / `action_mode` / `action_style` so the LLM has enough context to describe the interaction even if the card itself gets trimmed from rolling window memory. Pairs are spliced into the same request-body slot as voice-intent synthetic messages (`voiceIntentMessages` param — name is historical, the param accepts any synthetic-message JsonArray); once the API client accepts the request, `ChatHandler.markCardDispatchesSynced` flips every dispatch's flag so subsequent turns don't re-emit. Commit-timing matches the voice-intent path exactly (post-handoff) so a thrown request-building exception leaves dispatches unsynced for the next try.

**Phase B (deferred — not v0.7.x).**

Contribute a `gateway/rich_cards.py` helper upstream + Discord/Slack adapter translations. Discord gains its first real embed usage; Slack reuses the existing Block Kit path. Plain-text platforms (Signal, SMS) fall back to a markdown render of the same card. Same playbook as the `hermes_relay_bootstrap/` → upstream PR #8556 pipeline. Held until real phone-side card usage surfaces concrete fidelity issues worth translating for.

**Key Files:**
- `app/src/main/kotlin/com/hermesandroid/relay/data/HermesCard.kt` (new)
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/HermesCardBubble.kt` (new)
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/CardDispatchSyncBuilder.kt` (new)
- `app/src/test/kotlin/com/hermesandroid/relay/viewmodel/CardDispatchSyncBuilderTest.kt` (new)
- `app/src/main/kotlin/com/hermesandroid/relay/network/handlers/ChatHandler.kt` (+`scanForCardMarkers` / `tryDispatchCardMarker` / `finalizeCardMarkers` / `extractCardsFromContent` / `recordCardDispatch` / `markCardDispatchesSynced`)
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/MessageBubble.kt` (+`onCardAction` plumb-through)
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ChatViewModel.kt` (+`dispatchCardAction`, sync splice in `startStream`)
