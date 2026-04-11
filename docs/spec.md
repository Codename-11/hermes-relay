# Hermes-Relay — Android App

## Specification v1.1

**Status:** v0.1.0 — Phase 0 + Phase 1 complete  
**Repo:** [Codename-11/hermes-relay](https://github.com/Codename-11/hermes-relay)  
**Updated:** 2026-04-07

---

## 1. What This Is

A **native Android app** for the Hermes agent platform. Not just remote phone control — a full bidirectional interface between you and your Hermes server from anywhere.

Three capabilities in one app:

| Channel | Direction | What |
|---------|-----------|------|
| **Chat** | Phone ↔ Agent | Talk to any Hermes agent profile (Victor, Mizu, etc.) with full streaming |
| **Terminal** | Phone ↔ Server | Secure remote shell access to the Hermes server via tmux |
| **Bridge** | Agent → Phone | Agent controls the phone (taps, types, screenshots — upstream functionality) |

One persistent WSS connection. One pairing flow. Three multiplexed channels.

**What it is not:**
- Not a web wrapper — native Kotlin + Jetpack Compose
- Not phone-only — the bridge channel gives the agent hands on your device
- Not a replacement for Discord/Telegram — it's a first-party Hermes client with capabilities those platforms can't offer (terminal, bridge)

---

## 2. Design Principles

1. **Secure by default** — WSS only (no plaintext WebSocket option). TLS certificate pinning for production.
2. **Realtime everything** — streaming chat responses, live terminal output, instant bridge feedback. No polling.
3. **Clean UX** — Material 3, minimal setup, one pairing flow for all channels.
4. **Offline-aware** — graceful degradation when connection drops. Auto-reconnect with exponential backoff.
5. **Single connection** — one WSS pipe multiplexes all three channels. Efficient, simple to reason about.
6. **Server-side state** — the app is a thin client. Sessions, history, memory all live on the Hermes server.

---

## 3. Architecture

### 3.1 High-Level

```
┌─────────────────────────────────────────────────────┐
│                 Android App (Compose)                 │
│                                                       │
│  ┌─────────┐  ┌──────────┐  ┌────────┐  ┌────────┐ │
│  │  Chat   │  │ Terminal  │  │ Bridge │  │Settings│ │
│  │  Tab    │  │  Tab      │  │  Tab   │  │  Tab   │ │
│  └────┬────┘  └────┬─────┘  └───┬────┘  └────────┘ │
│       │            │             │                    │
│  ┌────┴────────────┴─────────────┴────┐              │
│  │     Connection Manager (WSS)        │              │
│  │     Channel Multiplexer             │              │
│  │     Auth + Session Management       │              │
│  └────────────────┬───────────────────┘              │
└───────────────────┼──────────────────────────────────┘
                    │ WSS (TLS 1.3)
                    │
┌───────────────────┼──────────────────────────────────┐
│            Hermes Server (Docker-Server)               │
│                   │                                    │
│  ┌────────────────┴───────────────────┐               │
│  │      Relay Server (Python)          │               │
│  │      Port 8767 (WSS)               │               │
│  │                                     │               │
│  │  ┌─────────┐ ┌────────┐ ┌───────┐ │               │
│  │  │  Chat   │ │Terminal│ │Bridge │ │               │
│  │  │ Router  │ │ PTY    │ │Router │ │               │
│  │  └────┬────┘ └───┬───┘ └───┬───┘ │               │
│  └───────┼──────────┼─────────┼─────┘               │
│          │          │         │                       │
│  ┌───────┴───┐  ┌───┴──┐  ┌──┴──────────────┐      │
│  │ WebAPI    │  │ tmux │  │AccessibilityServ.│      │
│  │ /api/...  │  │ PTY  │  │(on phone)        │      │
│  │ (aiohttp) │  │      │  │                  │      │
│  └───────────┘  └──────┘  └──────────────────┘      │
└──────────────────────────────────────────────────────┘
```

### 3.2 Protocol

All communication flows over a single WebSocket connection. Messages use a typed envelope:

```json
{
  "channel": "chat" | "terminal" | "bridge" | "system",
  "type": "<event_type>",
  "id": "<message_uuid>",
  "payload": { ... }
}
```

#### Channel: `system`
Connection lifecycle, auth, keepalive.

| Type | Direction | Payload |
|------|-----------|---------|
| `auth` | App → Server | `{ pairing_code, device_name, device_id }` |
| `auth.ok` | Server → App | `{ session_token, server_version, profiles[] }` |
| `auth.fail` | Server → App | `{ reason }` |
| `ping` | Both | `{ ts }` |
| `pong` | Both | `{ ts }` |

#### Channel: `chat`
**Note:** Chat now connects directly to the Hermes API Server via HTTP/SSE (see Section 6.2). The relay server is not involved in chat. The SSE event types from the Hermes API are:

| Event | Direction | Payload |
|-------|-----------|---------|
| `session.created` | Server → App | `{ session_id, run_id, title? }` |
| `run.started` | Server → App | `{ session_id, run_id, user_message: { id, role, content } }` |
| `message.started` | Server → App | `{ session_id, run_id, message: { id, role } }` |
| `assistant.delta` | Server → App | `{ session_id, run_id, message_id, delta }` |
| `tool.progress` | Server → App | `{ session_id, run_id, message_id, delta }` |
| `tool.pending` | Server → App | `{ session_id, run_id, tool_name, call_id }` |
| `tool.started` | Server → App | `{ session_id, run_id, tool_name, call_id, preview?, args }` |
| `tool.completed` | Server → App | `{ session_id, run_id, tool_call_id, tool_name, args, result_preview }` |
| `tool.failed` | Server → App | `{ session_id, run_id, call_id, tool_name, error }` |
| `assistant.completed` | Server → App | `{ session_id, run_id, message_id, content, completed, partial, interrupted }` |
| `run.completed` | Server → App | `{ session_id, run_id, message_id, completed, partial, interrupted, api_calls? }` |
| `error` | Server → App | `{ message, error }` |
| `done` | Server → App | `{ session_id, run_id, state: "final" }` |

Session management uses the REST API (`GET/POST /api/sessions`, `PATCH/DELETE /api/sessions/{id}`).

#### Channel: `terminal`
PTY streaming — raw terminal I/O.

| Type | Direction | Payload |
|------|-----------|---------|
| `terminal.attach` | App → Server | `{ session_name?, cols, rows }` |
| `terminal.attached` | Server → App | `{ session_name, pid }` |
| `terminal.input` | App → Server | `{ data }` (raw keystrokes) |
| `terminal.output` | Server → App | `{ data }` (raw ANSI output) |
| `terminal.resize` | App → Server | `{ cols, rows }` |
| `terminal.detach` | App → Server | `{}` |

#### Channel: `bridge`
Phone control — mirrors upstream relay protocol.

| Type | Direction | Payload |
|------|-----------|---------|
| `bridge.command` | Server → App | `{ request_id, method, path, params?, body? }` |
| `bridge.response` | App → Server | `{ request_id, status, result }` |
| `bridge.status` | App → Server | `{ accessibility_enabled, overlay_enabled, battery }` |

### 3.3 Auth Flow

Pairing is QR-driven. The operator runs the pair command on the host — either `/hermes-relay-pair` from any Hermes chat surface (backed by the `devops/hermes-relay-pair` skill) or the `hermes-pair` shell shim (a thin wrapper around `python -m plugin.pair`). Both share the same implementation in `plugin/pair.py`. The command probes for a running relay, generates a fresh 6-char code, pre-registers it with the relay via the loopback-only `POST /pairing/register` endpoint, then embeds the relay URL + code (and the API server credentials) in a single QR payload. The phone scans once and is configured for both chat AND terminal/bridge.

```
1. Operator runs /hermes-relay-pair (or hermes-pair) on the Hermes host
2. The pair command reads the API server config (host/port/key) from
   ~/.hermes/config.yaml or ~/.hermes/.env
3. If a relay is reachable at localhost:RELAY_PORT (default 8767):
   a. Mint a fresh 6-char code from A-Z / 0-9
   b. POST /pairing/register { "code": "ABCD12" }  (loopback only)
   c. Embed { url: "ws://host:port", code: "ABCD12" } in the QR payload
4. Render QR + plain-text block
5. Phone scans the QR → parses HermesPairingPayload (see §3.3.1)
6. Phone stores the API server URL + key and, if present, the relay URL
7. Phone opens WSS to the relay with the pairing code in the first
   system/auth envelope; relay consumes the code, returns a session token
8. Phone stores the session token in EncryptedSharedPreferences
9. Future connections use the session token directly (no re-pairing)
10. Session tokens expire after 30 days or on manual revoke
```

Old API-only QRs (no `relay` block) still parse — the phone just skips the relay setup step and can be paired against a relay later via Settings. Phase 3 (bridge) will introduce a symmetric phone-generates-code, host-approves flow that reuses the same `POST /pairing/register` endpoint from the opposite direction.

Biometric gate on the app side for terminal access (fingerprint/face).

#### 3.3.1 QR Wire Format — `HermesPairingPayload`

```json
{
  "hermes": 1,
  "host": "172.16.24.250",
  "port": 8642,
  "key": "api-bearer-token",
  "tls": false,
  "relay": {
    "url": "ws://172.16.24.250:8767",
    "code": "ABCD12"
  }
}
```

- Top-level fields (`host`/`port`/`key`/`tls`) configure the direct-chat Hermes API Server. This is the legacy shape.
- The `relay` object is **optional** and nullable. Present only when the pair command found a running relay and successfully pre-registered a pairing code with it.
- `relay.url` is the full WebSocket URL (`ws://` for dev, `wss://` for production).
- `relay.code` is a 6-char one-shot pairing code from `A-Z / 0-9`. Expires 10 minutes after registration (same lifecycle as relay-generated codes).
- The Android parser uses `kotlinx.serialization` with `ignoreUnknownKeys = true`, so future fields can be added without breaking older app builds.

Implementation references:
- Server-side payload builder: `plugin/pair.py` → `build_payload()` / `pair_command()`
- Phone-side parser: `app/src/main/kotlin/.../ui/components/QrPairingScanner.kt` → `HermesPairingPayload` / `RelayPairing`
- Relay registration endpoint: `plugin/relay/server.py` → `handle_pairing_register` (see §6 for details)

### 3.4 Security

| Layer | Implementation |
|-------|---------------|
| Transport | WSS (TLS 1.3) — no plaintext option |
| Auth | Pairing code → session token (stored encrypted on device) |
| Rate limiting | 5 auth attempts / 60s, then 5min block (existing) |
| Terminal gate | Biometric/PIN required before terminal access |
| Token storage | Android EncryptedSharedPreferences (AES-256-GCM) |
| Certificate | Pin server cert or use Let's Encrypt with ACME |

---

## 4. Tech Stack

### Android App
- **Language:** Kotlin 2.0+
- **UI:** Jetpack Compose + Material 3 (Material You dynamic theming)
- **Navigation:** Compose Navigation (type-safe)
- **WebSocket:** OkHttp 4.x (already in upstream, supports `wss://`)
- **Terminal:** WebView + xterm.js (v1), consider native Compose terminal later
- **Serialization:** kotlinx.serialization (replace Gson — faster, type-safe)
- **Storage:** EncryptedSharedPreferences (tokens), DataStore (preferences)
- **DI:** Hilt or manual (lean for MVP)
- **Biometric:** AndroidX Biometric
- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 35

### Server (Relay)
- **Language:** Python 3.11+
- **Framework:** aiohttp (matches existing relay)
- **Terminal:** `asyncio` + `pty` module for PTY, `libtmux` for session management
- **Chat proxy:** HTTP client to Hermes WebAPI (localhost:8642 or direct `run_agent`)
- **Port:** 8767 (WSS) — separate from existing bridge relay (8766)
- **TLS:** Let's Encrypt via certbot, or reverse proxy through Caddy/nginx

### CI/CD (GitHub Actions)
- **CI:** Lint (ktlint) → Build → Test → Upload APK artifact
- **Release:** Tag-triggered → version validation → signed APK → GitHub Release
- **Patterns from ARC:** Concurrency groups, matrix builds, version sync check

---

## 5. App Layout

### Navigation

Bottom navigation bar with 4 tabs:

```
┌───────────────────────────────────────────────┐
│                                               │
│              [Active Tab Content]              │
│                                               │
│                                               │
│                                               │
├───────┬───────────┬──────────┬────────────────┤
│ 💬    │ >_        │ 📱       │ ⚙️             │
│ Chat  │ Terminal  │ Bridge   │ Settings       │
└───────┴───────────┴──────────┴────────────────┘
```

### Chat Tab
- **Session drawer** (swipe from left or hamburger icon) — session list with title, timestamp, message count. Create, switch, rename, delete.
- **Chat view** — message bubbles with markdown rendering, streaming text, tool call cards (Off/Compact/Detailed display modes)
- **Input bar** — text field with 4096 char limit, `/` palette button, send button, stop button during streaming. Inline autocomplete on `/` keystroke + full searchable command palette (bottom sheet). Commands sourced from: 29 gateway built-ins, dynamic personalities from `config.agent.personalities`, and server skills from `GET /api/skills`.
- **Empty state** — Logo + "Start a conversation" + suggestion chips that populate input
- **Personality picker** — personalities fetched from `GET /api/config` (`config.agent.personalities`). Shows server default (from `config.display.personality`) + all configured. Active personality name shown on assistant chat bubbles.
- **Streaming dots** — animated pulsing 3-dot indicator replaces static "streaming..." text
- Displays: streaming delta text, tool progress cards (auto-expand while running, auto-collapse on complete), thinking/reasoning blocks (collapsible), per-message token counts + cost

### Terminal Tab
- **Full-screen terminal emulator** (xterm.js in WebView)
- **Session picker** — attach to existing tmux sessions or create new
- **Toolbar** — Ctrl, Tab, Esc, Arrow keys (soft keys for mobile)
- **Biometric gate** — fingerprint/face required before showing terminal
- Supports: full ANSI color, scrollback, text selection, copy/paste

### Bridge Tab
- **Connection status** — connected/disconnected, latency, battery
- **Permission checklist** — accessibility service, overlay, etc.
- **Activity log** — recent commands executed by the agent on the phone
- **Quick actions** — screenshot, screen read (manual triggers for testing)

### Settings Tab
- **API Server** — URL, API key, health check indicator
- **Relay Server** — URL, pairing code, connection status
- **Chat** — Show reasoning toggle, smooth auto-scroll toggle (live-follow streaming, default on), show token usage toggle, app context prompt toggle, tool call display (Off/Compact/Detailed), Stats for Nerds (analytics charts)
- **Appearance** — theme (auto/light/dark), dynamic colors toggle
- **Data** — Backup, restore, reset with confirmation dialogs
- **About** — logo on dark background, dynamic version from BuildConfig, Source + Docs link buttons, credits. What's New dialog.

---

## 6. Server: Relay

The relay is a new Python service that runs alongside the Hermes gateway. It owns the WSS connection to the phone and routes messages to the appropriate backend.

### 6.1 Structure

The canonical relay implementation lives at `plugin/relay/` (consolidated into the plugin as of Phase 2). A thin compat shim at the top-level `relay_server/` package delegates to it so legacy entrypoints (`python -m relay_server`) still work.

```
hermes-android/
├── plugin/relay/              # canonical implementation
│   ├── server.py              # main aiohttp WSS server + HTTP routes
│   ├── auth.py                # PairingManager, SessionManager, RateLimiter
│   ├── config.py              # RelayConfig, PAIRING_ALPHABET
│   ├── channels/
│   │   ├── chat.py            # proxies to Hermes WebAPI
│   │   ├── terminal.py        # PTY-backed shell handler (Phase 2)
│   │   └── bridge.py          # existing bridge protocol (stub)
│   └── __main__.py            # `python -m plugin.relay`
└── relay_server/              # thin shim → plugin.relay (legacy entrypoint)
```

HTTP routes registered by `create_app()` in `plugin/relay/server.py`:

| Route | Method | Purpose |
|-------|--------|---------|
| `/ws`, `/` | GET (upgrade) | WebSocket handler — main multiplexed channel |
| `/health` | GET | Health check — returns `{status, version, clients, sessions}` |
| `/pairing` | POST | Generate a new relay-side pairing code |
| `/pairing/register` | POST | **Loopback only.** Pre-register an externally-provided pairing code. Used by the pair command (`/hermes-relay-pair` skill or `hermes-pair` shim) to inject codes that will appear in QR payloads. Request: `{"code": "ABCD12"}`. Rejects non-loopback peers with HTTP 403. |

### 6.2 Chat — Direct API Connection

Chat connects directly from the Android app to the Hermes API Server, bypassing the relay server entirely. This uses the Hermes Sessions API:

```
1. POST /api/sessions → create session → get session_id
2. POST /api/sessions/{session_id}/chat/stream → send message, get SSE stream
         Authorization: Bearer <API_SERVER_KEY>   (optional)
         Accept: text/event-stream
         Content-Type: application/json
         
         { "message": "Hello", "system_message": "..." }

Response: SSE stream with typed events:
         event: session.created
         data: {"session_id":"...","run_id":"...","title":"..."}

         event: run.started
         data: {"session_id":"...","run_id":"...","user_message":{"id":"...","role":"user","content":"Hello"}}

         event: message.started
         data: {"session_id":"...","run_id":"...","message":{"id":"...","role":"assistant"}}

         event: assistant.delta
         data: {"session_id":"...","run_id":"...","message_id":"...","delta":"Hello"}

         event: tool.progress
         data: {"session_id":"...","run_id":"...","message_id":"...","delta":"thinking..."}

         event: tool.pending
         data: {"session_id":"...","run_id":"...","tool_name":"terminal","call_id":"..."}

         event: tool.started
         data: {"session_id":"...","run_id":"...","tool_name":"terminal","call_id":"...","preview":"...","args":{...}}

         event: tool.completed
         data: {"session_id":"...","run_id":"...","tool_call_id":"...","tool_name":"terminal","args":{...},"result_preview":"..."}

         event: tool.failed
         data: {"session_id":"...","run_id":"...","call_id":"...","tool_name":"terminal","error":"..."}

         event: assistant.completed
         data: {"session_id":"...","run_id":"...","message_id":"...","content":"...","completed":true,"partial":false,"interrupted":false}

         event: run.completed
         data: {"session_id":"...","run_id":"...","message_id":"...","completed":true,"partial":false,"interrupted":false,"api_calls":3}

         event: error
         data: {"message":"error description","error":"..."}

         event: done
         data: {"session_id":"...","run_id":"...","state":"final"}
```

Additional API endpoints used:
```
3. GET /api/sessions → list all sessions
4. PATCH /api/sessions/{session_id} → rename session
5. DELETE /api/sessions/{session_id} → delete session
6. GET /api/sessions/{session_id}/messages → fetch message history
7. GET /api/config → personalities (for personality picker, `config.agent.personalities`)
8. GET /api/skills → available skills (for command palette + autocomplete)
```

Key classes:
- **HermesApiClient** — OkHttp-based HTTP/SSE client for direct API communication (chat, sessions, skills, config)
- **ChatHandler** — processes streaming deltas and tool call events into ChatMessage state
- **ChatViewModel** — orchestrates send/stream/cancel lifecycle, slash command handling
- **AppAnalytics** — singleton tracking TTFT, completion times, token usage, health latency, stream success rates

The relay server is **not involved** in chat streaming itself. It remains the home for bridge, terminal, and — as of 2026-04-11 — **inbound media delivery** (see 6.2a).

### 6.2a Inbound Media (Agent → Phone file delivery)

Tool-produced files (screenshots today, video/audio/PDF/other in the future) reach the phone via a plugin-owned file-serving surface on the relay, decoupled from the chat SSE stream itself. Only a short opaque token rides the chat stream; the bytes flow out-of-band over authenticated HTTPS.

**Why this lives in the plugin, not upstream hermes-agent:** `APIServerAdapter.send()` (in upstream `gateway/platforms/api_server.py`) is an explicit no-op — the HTTP API adapter does not implement `send_document`. Upstream's `extract_media()` / `send_document()` pipeline only fires for push platforms (Telegram, Feishu, WeChat) and non-streaming paths. On our streaming HTTP surface, `MEDIA:` tags in tool output have always passed through as literal text. Rather than patch upstream, we added our own endpoints and marker format. See [docs/decisions.md §14](decisions.md) for the full trust and resource model.

**Wire format:**
```
Screenshot captured (1280x720)
MEDIA:hermes-relay://<url-safe-16-byte-token>
```

**Server:** two routes on `plugin/relay/server.py`:
- `POST /media/register` — **loopback-only**. Body `{"path", "content_type", "file_name"}`. Validates path is absolute, resolves (`os.path.realpath`) under an allowed root, exists, is a regular file, fits under `RELAY_MEDIA_MAX_SIZE_MB`. Generates `secrets.token_urlsafe(16)` (128 bits entropy), stores the token → entry mapping in an in-memory `OrderedDict` LRU (capped at `RELAY_MEDIA_LRU_CAP`, TTL `RELAY_MEDIA_TTL_SECONDS`). Returns `{ok, token, expires_at}`.
- `GET /media/{token}` — requires `Authorization: Bearer <session_token>` against the existing `SessionManager` (same token WSS uses). Streams the file via `web.FileResponse` with the registered content type plus `Content-Disposition: inline; filename="..."` if the entry has a file name. 401 on missing/invalid bearer, 404 on unknown/expired token.

**Phone:** parse → fetch → cache → render:
1. `ChatHandler.scanForMediaMarkers()` runs on every `onTextDelta`, unconditionally (not gated on `parseToolAnnotations`). Matches `MEDIA:hermes-relay://([A-Za-z0-9_-]+)` and fires `onMediaAttachmentRequested(messageId, token)`. A second regex matches the bare-path form `MEDIA:(/\S+)` and fires `onUnavailableMediaMarker`. A per-session `dispatchedMediaMarkers` set dedupes between real-time streaming scans and the post-stream `finalizeMediaMarkers` reconciliation pass. Both markers are stripped from the rendered message text.
2. `ChatViewModel` inserts a LOADING `Attachment` with `relayToken` set immediately (message updates via `ChatHandler.mutateMessage`).
3. On Wi-Fi, or on cellular when `autoFetchOnCellular` is true: `RelayHttpClient.fetchMedia(token)` issues `GET /media/{token}` with the bearer header. URL is derived by swapping `ws://`→`http://`, `wss://`→`https://` on the stored relay URL.
4. Bytes are checked against `maxInboundSizeMb`. If oversize → FAILED placeholder. Otherwise `MediaCacheWriter` writes them to `context.cacheDir/hermes-media/<sha1>.<ext>` with LRU eviction by mtime (capped at `cachedMediaCapMb`) and returns a `content://` URI via `FileProvider.getUriForFile(context, "${applicationId}.fileprovider", file)`.
5. The Attachment is flipped to LOADED with `cachedUri` set. `InboundAttachmentCard` dispatches by `(state × renderMode)`: `IMAGE` renders inline via `BitmapFactory.decodeByteArray` + `asImageBitmap`; `VIDEO`/`AUDIO`/`PDF`/`TEXT`/`GENERIC` render as tap-to-open file cards firing `ACTION_VIEW` with `FLAG_GRANT_READ_URI_PERMISSION` on the cached URI.
6. On cellular with `autoFetchOnCellular` off: the attachment stays in LOADING state with `errorMessage = "Tap to download"`, and `manualFetchAttachment()` re-runs the fetch ignoring the cellular gate.

**Fallback when relay isn't running:** the tool's `register_media()` call fails (connection refused / timeout / non-200) → tool logs a warning and returns the legacy bare-path form (`MEDIA:/tmp/...`). The phone's `onUnavailableMediaMarker` handler inserts a FAILED Attachment with `errorMessage = "Image unavailable — relay offline"`. Matches current behavior; placeholder is tidier than raw marker text.

**Known gap — session replay across relay restarts:** the `MediaRegistry` is in-memory. Restarting the relay invalidates all tokens. A user scrolling back into a session from yesterday sees FAILED placeholders for any now-stale token. Phone-side persistent cache (indexed by token or content hash) is the planned fix; filed as a DEVLOG follow-up.

**Known gap — auto-fetch threshold slider isn't enforced today.** The Settings → Inbound media → auto-fetch threshold knob is persisted but the fetch path currently only checks the cellular toggle + the hard max cap. Forward-compatibility placeholder; real enforcement needs a HEAD preflight or post-hoc byte rejection.

**Key classes:**
- **`MediaRegistry`** (`plugin/relay/media.py`) — in-memory token store, thread-safe via `asyncio.Lock`
- **`register_media()`** (`plugin/relay/client.py`) — stdlib `urllib.request` helper for in-process tool callers
- **`RelayHttpClient`** (Android) — OkHttp GET with Bearer auth + URL rewriting
- **`MediaCacheWriter`** (Android) — FileProvider-backed LRU cache in `cacheDir/hermes-media/`
- **`InboundAttachmentCard`** (Android) — single Compose component dispatched on `(state × renderMode)`, handles both inbound and outbound attachments

### 6.3 Terminal Channel

```python
# App sends: { channel: "terminal", type: "terminal.attach", payload: { cols: 80, rows: 24 } }
# Relay:
#   1. Find or create tmux session
#   2. Open PTY attached to tmux
#   3. Stream PTY output → WebSocket
#   4. WebSocket input → PTY stdin
```

Uses `asyncio.create_subprocess_exec` with PTY for non-blocking I/O. tmux gives us named sessions, detach/reattach, and persistence across disconnects.

### 6.4 Bridge Channel

Wraps the existing relay protocol. When the agent calls `android_*` tools, the tool handler routes through the relay server's bridge channel to the phone.

**Change from upstream:** The bridge channel is now part of the multiplexed WSS connection instead of a separate `ws://` relay on port 8766. The plugin's `android_relay.py` gets updated to route through the relay server.

---

## 7. Implementation Phases

### Phase 0 — Project Setup (MVP Night 1)
**Priority: P0 — do first**

- [ ] Create private GitHub repo `Codename-11/hermes-relay` (or rename fork)
- [ ] Set up Kotlin + Jetpack Compose project (replace upstream XML layout)
- [ ] Gradle config: Kotlin 2.0+, Compose BOM, Material 3, OkHttp, kotlinx.serialization
- [ ] Basic Compose scaffold: bottom nav, 4 tabs, placeholder screens
- [ ] GitHub Actions: build APK on push
- [ ] WSS connection manager (OkHttp WebSocket with `wss://`)
- [ ] Channel multiplexer (envelope format, routing)
- [ ] Basic auth flow (pairing code → token)

### Phase 1 — Chat Channel (MVP)
**Priority: P0**

- [ ] Server: Relay with chat channel router
- [ ] Server: Proxy to Hermes WebAPI `/api/sessions/{id}/chat/stream`
- [ ] Server: SSE → WebSocket bridge
- [ ] App: Chat UI (message list, input bar, streaming text)
- [ ] App: Tool progress cards (collapsible)
- [ ] App: Profile selector (list available agent profiles)
- [ ] App: Session management (create, list, switch)
- [ ] App: Auto-reconnect with exponential backoff

### Phase 2 — Terminal Channel
**Priority: P1**

- [ ] Server: PTY/tmux integration
- [ ] Server: Terminal channel handler (attach, input, output, resize)
- [ ] App: WebView + xterm.js terminal emulator
- [ ] App: Soft keyboard toolbar (Ctrl, Tab, Esc, arrows)
- [ ] App: tmux session picker
- [ ] App: Biometric gate before terminal access
- [ ] App: Terminal resize on orientation change

### Phase 3 — Bridge Channel
**Priority: P1**

- [ ] Migrate upstream bridge protocol into multiplexed WSS
- [ ] Update `android_relay.py` to route through relay server
- [ ] App: Bridge status UI
- [ ] App: Permission management (accessibility, overlay)
- [ ] App: Activity log (recent agent commands)
- [ ] AccessibilityService integration (carry forward from upstream)

### Phase 4 — Security Hardening
**Priority: P1**

- [ ] TLS certificate setup (Let's Encrypt or self-signed with pinning)
- [ ] EncryptedSharedPreferences for token storage
- [ ] Biometric authentication (AndroidX Biometric)
- [ ] Token expiry and rotation
- [ ] Rate limiting on auth endpoint

### Phase 5 — Polish & CI/CD
**Priority: P2**

- [ ] GitHub Actions: lint, build, test matrix
- [ ] GitHub Actions: release workflow (tag → signed APK → GitHub Release)
- [ ] Material You dynamic theming
- [ ] Proper error states and empty states
- [ ] Notification channel for agent messages
- [ ] App icon and branding

### Phase 6 — Future
**Priority: P3 — not for MVP**

- [ ] Notification listener (agent reads phone notifications)
- [ ] Clipboard bridge
- [ ] File transfer (phone ↔ server)
- [ ] Multi-device support
- [ ] On-device model fallback
- [ ] Voice mode (TTS/STT)

---

## 8. MVP Scope (Tonight)

Focus: **Phase 0 + start of Phase 1**

Deliverables:
1. Compose project with bottom nav scaffold
2. WSS connection manager with channel multiplexing
3. Basic pairing/auth flow
4. Chat tab: send message → get streaming response
5. Server: relay with chat channel routing
6. GitHub Actions: build APK

Non-goals for tonight:
- Terminal (Phase 2)
- Bridge (Phase 3)
- Biometrics (Phase 4)
- Release workflow (Phase 5)

---

## 9. Key Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| Jetpack Compose BOM | 2024.12+ | UI framework |
| Material 3 | 1.3+ | Design system |
| OkHttp | 4.12+ | WebSocket + HTTP |
| kotlinx.serialization | 1.7+ | JSON handling |
| xterm.js | 5.x | Terminal emulator (WebView) |
| aiohttp | 3.9+ | Server relay |
| libtmux | 0.37+ | tmux management |

---

## 10. Hermes Integration Points

| Surface | How We Connect |
|---------|---------------|
| **WebAPI chat** | HTTP to `localhost:8642/api/sessions/*/chat/stream` (SSE) |
| **WebAPI sessions** | `GET/POST/PATCH/DELETE /api/sessions` for CRUD |
| **Personalities** | `GET /api/config` → `config.agent.personalities` for picker + command palette |
| **Server skills** | `GET /api/skills` — dynamic skill discovery for command palette + autocomplete |
| **Plugin system** | `register_tool()` via `ctx` for `android_*` tools |
| **Gateway** | Chat channel goes through WebAPI, not directly to gateway |
| **Memory/Skills** | Accessible through agent chat (no direct API needed for MVP) |

---

## Related

- **ARC** — CI/CD patterns, project structure conventions
- **Hermes Agent** — Gateway, WebAPI, plugin system, SSE streaming
- **ClawPort** — Web dashboard (parallel effort, different interface surface)
