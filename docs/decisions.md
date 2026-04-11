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

**Important upstream note:** The `/api/sessions` CRUD endpoints may not exist in all hermes-agent versions. The upstream codebase registers `/v1/chat/completions`, `/v1/responses`, and `/v1/runs` as the standard endpoints. Session management via `/api/sessions` may be version-specific (confirmed working in v0.7.0). The app's `detectChatMode()` probes the server and falls back gracefully.

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
