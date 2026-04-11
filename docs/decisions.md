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

**Decision:** The Hermes pairing QR payload bundles the API server credentials AND the relay URL + pairing code into a single scan. `hermes pair` runs on the Hermes host; if a relay is reachable at `localhost:RELAY_PORT`, the command mints a fresh 6-char code, pre-registers it with the relay via a new loopback-only `POST /pairing/register` endpoint, and embeds `{url, code}` under a nullable `relay` key alongside the existing `host`/`port`/`key`/`tls` fields.

**Trust anchor:** the operator with shell access on the host. Only a process running on the same machine as the relay can hit `/pairing/register` — the handler rejects any non-loopback `request.remote` with HTTP 403. A LAN attacker cannot inject codes. This matches the model we already rely on for reading `~/.hermes/.env` and `~/.hermes/config.yaml`: if you have shell access to the host, you have enough privilege to authorize a device.

**Why the change was necessary:**
- Previously the phone generated its own 6-char pairing code locally via `AuthManager.generatePairingCode()` and sent it to the relay on WSS connect. The relay had no way to know what code to accept, so relay pairing was effectively broken — only API-direct-chat pairing worked via the QR.
- Pushing the code flow through the host means the operator always has the source of truth, and a single scan configures both chat and terminal/bridge with no manual steps.

**Schema evolution:**
- Old API-only QRs (`{hermes, host, port, key, tls}`) still parse cleanly — the `relay` field is nullable and `kotlinx.serialization` runs with `ignoreUnknownKeys = true`.
- When `--no-relay` is passed to `hermes pair`, or the relay isn't running, the QR omits the `relay` block and the command prints an `[info]` pointing at `hermes relay start`.

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

---

## CI/CD Patterns (from ARC)

Adopting from ARC's workflow patterns:

1. **CI workflow:** Lint (ktlint) → Build (debug + release matrix) → Test → Upload APK artifact
2. **Release workflow:** Tag `v*` → version validation (build.gradle.kts vs tag) → signed APK → GitHub Release
3. **Concurrency groups:** Cancel in-progress CI on new push to same branch
4. **Dependabot:** Auto-merge minor/patch dependency updates
