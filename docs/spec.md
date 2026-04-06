# Hermes Relay — Android App

## Specification v1.0

**Status:** Draft — ready for MVP  
**Repo:** [Codename-11/hermes-android](https://github.com/Codename-11/hermes-android) (private)  
**Upstream:** [raulvidis/hermes-android](https://github.com/raulvidis/hermes-android) (forked)  
**Updated:** 2026-04-05

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
│  │ (FastAPI) │  │      │  │                  │      │
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

```
1. App displays 6-char pairing code (generated locally)
2. User tells Hermes: "connect to my phone, code is ABC123"
3. Hermes relay generates a server-side session token
4. Server sends WSS URL to user via chat
5. App connects to WSS URL with pairing code
6. Server validates code, returns session token
7. App stores token in EncryptedSharedPreferences
8. Future connections use token directly (no re-pairing)
9. Token expires after 30 days or manual revoke
```

Biometric gate on the app side for terminal access (fingerprint/face).

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
- **Session list** (left drawer or top sheet on mobile) — grouped by agent profile
- **Chat view** — message bubbles, streaming text, collapsible tool call cards
- **Input bar** — text field, attachment button (images), send
- **Profile selector** — switch between Victor, Mizu, etc.
- Displays: streaming delta text, tool progress cards (expandable), thinking indicators

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
- **Server connection** — URL, pairing code, reconnect
- **Security** — biometric toggle, token management, certificate info
- **Appearance** — theme (auto/light/dark), dynamic colors toggle
- **Profiles** — manage agent profiles visible in chat
- **About** — version, upstream info, licenses

---

## 6. Server: Relay

The relay is a new Python service that runs alongside the Hermes gateway. It owns the WSS connection to the phone and routes messages to the appropriate backend.

### 6.1 Structure

```
hermes-android/
├── relay_server/
│   ├── relay.py           # Main WSS server (aiohttp)
│   ├── auth.py            # Pairing, token management
│   ├── channels/
│   │   ├── chat.py        # Proxies to Hermes WebAPI
│   │   ├── terminal.py    # PTY/tmux management (stub)
│   │   └── bridge.py      # Existing bridge protocol (stub)
│   ├── config.py          # Relay configuration
│   └── requirements.txt
```

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

Key classes:
- **HermesApiClient** — OkHttp-based HTTP/SSE client for direct API communication
- **ChatHandler** — processes streaming deltas and tool call events into ChatMessage state
- **ChatViewModel** — orchestrates send/stream/cancel lifecycle

The relay server is **not involved** in chat. It remains for bridge and terminal channels only.

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

- [ ] Create private GitHub repo `Codename-11/hermes-android` (or rename fork)
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
| **WebAPI** | HTTP to `localhost:8642/api/sessions/*/chat/stream` (SSE) |
| **Plugin system** | `register_tool()` via `ctx` for `android_*` tools |
| **Gateway** | Chat channel proxies through WebAPI, not directly to gateway |
| **Agent profiles** | Read from `config.yaml` to populate profile selector |
| **Sessions** | Use WebAPI `/api/sessions` for CRUD |
| **Memory/Skills** | Accessible through agent chat (no direct API needed for MVP) |

---

## Related

- **ARC** — CI/CD patterns, project structure conventions
- **Hermes Agent** — Gateway, WebAPI, plugin system, SSE streaming
- **ClawPort** — Web dashboard (parallel effort, different interface surface)
