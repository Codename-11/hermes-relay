# Hermes-Relay Protocol Specification

**Status:** Draft v1.0 (2026-04-22) — extracted from existing server + Android client implementations.

**Server:** `plugin/relay/server.py` (aiohttp, port 8767 default)
**Clients:** Kotlin Android (`app/src/main/kotlin/.../network/`), desktop TUI (incoming)

This is a formal write-up of the protocol that was implicit in the Android/Python codebases. A second client (desktop TUI) should implement against this spec rather than reverse-engineer from Kotlin.

---

## 1. Handshake & Authentication

### 1.1 Connection Flow

1. Client initiates WSS to `wss://<host>:<port>/ws` (or bare `wss://<host>:<port>/` — both accepted).
2. Server accepts upgrade with 30s heartbeat.
3. Client sends `auth` envelope (within 30s) on the `system` channel — carries pairing code OR session token.
4. Server validates, mints session or looks up existing one.
5. Server responds `auth.ok` (success) or `auth.fail` (rate-limited / invalid code or token).
6. Message routing begins — client sends typed envelopes to channels (chat, bridge, terminal, notifications, …).

**Rate-limiting:**
- Pairing-code failures: 10 attempts / 60s → 120s block
- Session-token failures: 5 attempts / 60s → 300s block
- IP-based (both buckets share a per-IP block table)
- Cleared on loopback `/pairing/register` (operator re-pair)

Source: `plugin/relay/server.py:2649-2889` (`handle_ws`, `_authenticate`).

### 1.2 Auth Envelope (Client → Server)

```json
{
  "channel": "system",
  "type": "auth",
  "id": "<uuid>",
  "payload": {
    "pairing_code": "ABC123",
    "device_name": "Samsung Galaxy S25",
    "device_id": "android-device-uuid",
    "ttl_seconds": 2592000,
    "grants": {"chat": 2592000, "terminal": 604800, "bridge": 604800},
    "session_token": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
  }
}
```

**Fields:**
- `pairing_code` — 6 chars A-Z/0-9, one-time, 10 min TTL, case-insensitive, consumed on use.
- `session_token` — UUID, used for reconnection after initial pairing. Exactly one of `pairing_code` or `session_token` must be present.
- `device_name` — display name on "Paired Devices" screen. Max 255 chars.
- `device_id` — unique persistent identifier.
- `ttl_seconds` — requested session lifetime; `0` means never expire. Ignored if pairing code carried pre-set metadata from host.
- `grants` — per-channel seconds-from-now. Keys: `chat`, `terminal`, `bridge`, (soon) `tui`.

Source: `plugin/relay/server.py:2804-2850`.

### 1.3 Auth OK Envelope (Server → Client)

```json
{
  "channel": "system",
  "type": "auth.ok",
  "id": "<uuid>",
  "payload": {
    "session_token": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
    "server_version": "0.6.0",
    "expires_at": 1740000000,
    "grants": {"chat": 1740000000, "terminal": 1739500000, "bridge": 1739200000},
    "transport_hint": "wss",
    "profiles": [
      {"name": "default", "model": "gpt-4o", "description": "Default agent", "system_message": null},
      {"name": "mizu", "model": "claude-opus-4", "description": "Code assistant", "system_message": "You are a code expert..."}
    ]
  }
}
```

**Fields:**
- `session_token` — relay-minted UUID. Client stores for reconnection; bearer auth for all HTTP routes (`/media/{token}`, `/voice/transcribe`, etc.).
- `server_version` — relay version. Informational; clients may warn on major version mismatch.
- `expires_at` — epoch seconds or `null` (never expires; serialized from `math.inf`).
- `grants` — per-channel expiry timestamps (epoch seconds or `null`). Each grant is clamped to `expires_at` — a channel cannot outlive the session.
- `transport_hint` — `"wss"` / `"ws"` / `"unknown"`. Drives client transport-security badge and TTL picker default.
- `profiles` — Hermes profiles discovered at `~/.hermes/profiles/*/` plus synthetic `"default"`. May be empty if `RELAY_PROFILE_DISCOVERY_ENABLED=0`.

Source: `plugin/relay/server.py:2741-2764`.

### 1.4 TOFU Cert Pinning

Clients pin server certificates per `(host, port)` using SHA-256 SPKI. Opt-in and persistent — first cert seen is pinned; subsequent connections verify.

- **Android storage:** `CertPinStore.kt` — EncryptedSharedPrefs + DataStore. Key format: `cert_pin_{host}_{port}` → JSON `{pin_hash, last_updated}`.
- **Server side:** no explicit pin check — clients own TOFU logic. Server serves the cert configured at relay startup.

Sources: `plugin/relay/server.py:25-30`, `app/src/main/kotlin/.../auth/CertPinStore.kt`.

### 1.5 Bearer Token Format & Session Persistence

**Format:** UUID v4 (36 chars, hyphenated).

**Server persistence:**
- File: `~/.hermes/hermes-relay-sessions.json` (mode 0o600, atomic write via tempfile + `os.replace`).
- Layout: `{"version": 1, "sessions": [...]}`.
- `math.inf` (never-expire) serializes as string `"never"`.
- Survives relay restart — SessionManager reloads at startup; expired entries dropped silently.

Source: `plugin/relay/auth.py:340-836`.

**Client persistence (Android reference):**
- StrongBox Keystore (API 28+), fallback to EncryptedSharedPreferences.
- Lossless upgrade from plaintext → encrypted on first auth.

Source: `app/src/main/kotlin/.../auth/SessionTokenStore.kt`.

---

## 2. Envelope Format (Wire Protocol)

All messages are **text** WebSocket frames carrying JSON. Binary frames are not used.

### 2.1 Standard Envelope

```json
{
  "channel": "chat|terminal|bridge|notifications|system|pairing|tui",
  "type": "<event_type>",
  "id": "<uuid>",
  "payload": { ... }
}
```

All fields required except `payload` (defaults to `{}`). `channel` routes through `ChannelMultiplexer.route()`. `id` is a UUID used for tracing and (on some channels) request-response correlation. `type` is the event type within the channel.

**Special escape hatch:** the `pairing` channel hoists a top-level `profiles` array for `profiles.updated` pushes. Regular channels leave `profiles: null`. See `Envelope.kt`.

Source: `app/src/main/kotlin/.../network/models/Envelope.kt`.

### 2.2 Example: Bridge Command

```json
{
  "channel": "bridge",
  "type": "bridge.command",
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "payload": {
    "request_id": "550e8400-e29b-41d4-a716-446655440001",
    "method": "POST",
    "path": "/tap",
    "params": {"x": 512, "y": 1024},
    "body": {}
  }
}
```

Response:
```json
{
  "channel": "bridge",
  "type": "bridge.response",
  "id": "550e8400-e29b-41d4-a716-446655440002",
  "payload": {
    "request_id": "550e8400-e29b-41d4-a716-446655440001",
    "status": 200,
    "result": {"tapped": true}
  }
}
```

---

## 3. Channels

### 3.1 System

**Purpose:** Connection lifecycle, auth, keepalive.
**Direction:** Bidirectional.
**Handler:** `ChannelMultiplexer.handleSystem()` (client), `_handle_system()` (server).

| Type | Direction | Payload |
|------|-----------|---------|
| `auth` | Client → Server | See §1.2 |
| `auth.ok` | Server → Client | See §1.3 |
| `auth.fail` | Server → Client | `{"reason": "string"}` |
| `ping` | Both | `{"ts": <epoch_ms>}` |
| `pong` | Both | `{"ts": <epoch_ms>}` |

**Heartbeat:** Server pings every 30s. Client responds pong. Timeout triggers reconnect.

Source: `plugin/relay/server.py:2964-3005`.

### 3.2 Bridge

**Purpose:** Agent controls phone (taps, types, screenshots, …).
**Direction:** Server → Client (commands); Client → Server (responses + status pushes).
**Handler:** `BridgeHandler` (server), `BridgeCommandHandler` (client).
**Timeout:** 30 seconds per request.

Message types:

| Type | Direction | Purpose |
|------|-----------|---------|
| `bridge.command` | Server → Client | Tool dispatch — server mints `request_id`, awaits response |
| `bridge.response` | Client → Server | Phone replies, echoing `request_id` |
| `bridge.status` | Client → Server | Periodic device state (screen on, battery, current app, accessibility on) |

Bridge exposes 30+ HTTP routes on the relay itself (mirrored from legacy): `/ping`, `/screen`, `/screenshot`, `/tap`, `/tap_text`, `/long_press`, `/type`, `/swipe`, `/drag`, `/open_app`, `/press_key`, `/scroll`, `/clipboard`, `/wait`, `/setup`, `/media`, `/find_nodes`, `/screen_hash`, `/diff_screen`, `/send_intent`, `/broadcast`, `/events`, `/location`, `/search_contacts`, `/call`, `/send_sms`, …

Sources: `plugin/relay/channels/bridge.py`, `app/src/main/kotlin/.../network/handlers/BridgeCommandHandler.kt`.

### 3.3 Chat

**Note:** Chat does **not** traverse the relay. It connects directly to the Hermes API Server via HTTP/SSE.

Relay involvement is limited to session management routes (`/api/sessions/*`) for create/list/delete/extend. See hermes-relay CLAUDE.md §"Upstream Hermes API Reference" for the endpoint catalog.

### 3.4 Terminal

**Purpose:** PTY-backed interactive shell.
**Direction:** Bidirectional.
**Handler:** `TerminalHandler` (server); TUI client (incoming).
**Unix only** — returns `terminal.error` on Windows.

| Type | Direction | Payload |
|------|-----------|---------|
| `terminal.attach` | Client → Server | `{session_name?, cols, rows}` |
| `terminal.attached` | Server → Client | `{session_name, pid, shell, cols, rows, tmux_available}` |
| `terminal.input` | Client → Server | `{data}` (UTF-8) |
| `terminal.output` | Server → Client | `{data}` (ANSI UTF-8) |
| `terminal.resize` | Client → Server | `{cols, rows}` |
| `terminal.detach` | Client → Server | `{session_name?}` (preserves tmux) |
| `terminal.kill` | Client → Server | `{session_name?}` (destroys tmux) |
| `terminal.list` | Client → Server | `{}` |
| `terminal.sessions` | Server → Client | `{sessions, tmux_available}` |
| `terminal.error` | Server → Client | `{message}` |

**Output batching:** frames flushed every ~16ms or when buffer hits 4KB to avoid wire flooding.

Source: `plugin/relay/channels/terminal.py`.

### 3.5 Notifications

**Purpose:** Phone → Server notification forwarding (opt-in via Android Settings).
**Direction:** Client → Server only.
**Handler:** `NotificationsChannel` (server), `HermesNotificationCompanion` (client).
**Storage:** In-memory bounded deque (100 entries), lost on relay restart.

| Type | Direction | Payload |
|------|-----------|---------|
| `notification.posted` | Client → Server | `{app_package, title, text, timestamp, …}` |

HTTP route: `GET /notifications/recent?limit=N` — returns JSON array. Loopback callers skip bearer; bearer-authenticated callers also supported.

Sources: `plugin/relay/channels/notifications.py`, `app/src/main/kotlin/.../notifications/HermesNotificationCompanion.kt`.

### 3.6 Pairing

**Purpose:** Host-originated pushes about the session (e.g., profile list updates).
**Direction:** Server → Client only.

| Type | Direction | Notes |
|------|-----------|-------|
| `profiles.updated` | Server → Client | Hoists `profiles` array to top-level JSON (see §2.1 escape hatch) |

### 3.7 Desktop Tool Routing

**Purpose:** Route agent desktop tools to the connected desktop CLI/daemon.
**Direction:** Server → Client for `desktop.command`; Client → Server for status and responses.
**Handler:** `DesktopHandler` (server, `plugin/relay/channels/desktop.py`), `DesktopToolRouter` (client, `desktop/src/tools/router.ts`).

| Type | Direction | Payload |
|------|-----------|---------|
| `desktop.command` | Server → Client | `{request_id, tool, args}` |
| `desktop.response` | Client → Server | `{request_id, ok: true, result}` or `{request_id, ok: false, error}` |
| `desktop.status` | Client → Server | `{advertised_tools, host, platform, version, computer_use?}` |
| `desktop.workspace` | Client → Server | Workspace context snapshot |
| `desktop.active_editor` | Client → Server | Active editor hint |

Experimental computer-use tools use the same channel. The client only advertises `desktop_computer_*` names when explicitly enabled with `--experimental-computer-use` or `HERMES_RELAY_EXPERIMENTAL_COMPUTER_USE=1`, and the relay treats those names as strict-advertise tools so older clients fail closed. Screenshots require an in-memory observe/assist/control grant. Host input currently uses a Windows-only CLI approval path: durable per-URL computer-use consent, a task-scoped assist/control grant, and a local per-action prompt must all pass before input is sent. Daemon/non-interactive clients advertise blocked input state and reject actions with structured failures.

### 3.8 TUI *(new, being added — see `docs/plans/2026-04-22-desktop-tui-mvp.md`)*

**Purpose:** Remote desktop TUI — pipes JSON-RPC between the Node TUI client and a remote `tui_gateway` subprocess on the server.
**Direction:** Bidirectional.
**Handler:** `TuiHandler` (server, `plugin/relay/channels/tui.py`).

On `tui.attach`, the relay spawns a `tui_gateway` subprocess (same invocation as the local `hermes` CLI uses), and pumps stdio ↔ envelopes bidirectionally. The agent loop, tool execution, approval flows, session DB, and image attach all run server-side unchanged — the relay is a transparent transport.

| Type | Direction | Payload |
|------|-----------|---------|
| `tui.attach` | Client → Server | `{cols, rows, profile?, resume_session_id?}` |
| `tui.attached` | Server → Client | `{pid, server_version}` |
| `tui.rpc.request` | Client → Server | `{jsonrpc, id, method, params}` — forwarded verbatim to subprocess stdin |
| `tui.rpc.response` | Server → Client | `{jsonrpc, id, result | error}` — from subprocess stdout |
| `tui.rpc.event` | Server → Client | `{jsonrpc, method: "event", params: {...}}` — unsolicited events (tool.start, message.delta, …) |
| `tui.resize` | Client → Server | `{cols, rows}` |
| `tui.detach` | Client → Server | `{}` — kills subprocess |
| `tui.error` | Server → Client | `{message}` |

The TUI's 52 RPC methods and 50+ event types are defined by `tui_gateway/server.py` — see `~/.hermes/hermes-agent/ui-tui/src/gatewayTypes.ts` for the typed schema.

---

## 4. Request/Response Correlation

### 4.1 Request ID (Bridge and TUI)

Bridge and TUI use explicit `request_id` for RPC-style correlation:

1. Outbound (server for bridge; client for TUI): generates UUID, embeds in payload.
2. Inbound: echoes same `request_id` in response.
3. Timeout: 30 seconds (bridge `RESPONSE_TIMEOUT`).

**Concurrency:** Multiple commands may be in-flight. Server (or client, for TUI) maintains `{request_id → asyncio.Future}`. Response resolves the future; timeout cancels. On disconnect, all pending futures fail with `ConnectionError`.

Source: `plugin/relay/channels/bridge.py:193-272`.

### 4.2 Message ID (All Channels)

The top-level `id` field (UUID) on every envelope aids tracing/debugging. Not used for correlation in channels other than bridge and TUI.

### 4.3 Error Shape

```json
{
  "channel": "system|<channel>",
  "type": "error|<channel>.error",
  "id": "<uuid>",
  "payload": {
    "message": "human-readable error",
    "error": "optional structured code"
  }
}
```

**Canonical error codes (informal, not enforced):**
- `permission_denied` — session lacks grant for this channel
- `keyguard_blocked` — Android keyguard present (accessibility action blocked)
- `phone_not_connected` — no phone on bridge channel
- `timeout` — request didn't complete in time
- `invalid_request` — envelope malformed

Sources: `plugin/relay/server.py:2943-2948`, `plugin/relay/channels/bridge.py:64-83`.

---

## 5. Multi-Endpoint & Tailscale

### 5.1 Multi-Endpoint Pairing (ADR 24)

QR payloads may include an `endpoints` array listing alternate connection candidates (LAN, Tailscale, public, custom). Clients re-probe on network change, picking the best available endpoint.

```json
{
  "hermes": 3,
  "host": "172.16.24.250",
  "port": 8642,
  "key": "<api_key>",
  "tls": false,
  "relay": {
    "url": "ws://172.16.24.250:8767",
    "code": "ABC123",
    "ttl_seconds": 604800,
    "transport_hint": "ws",
    "endpoints": [
      {"type": "lan", "host": "192.168.1.100", "port": 8767, "tls": false},
      {"type": "tailscale", "host": "hermes.fa0c2e.ts.net", "port": 8767, "tls": true},
      {"type": "public", "host": "relay.example.com", "port": 443, "tls": true}
    ]
  }
}
```

See `docs/decisions.md` ADR 24 for full rationale.

Source: `plugin/relay/server.py:209-244`.

### 5.2 Tailscale Serve (ADR 25)

Relay fronts via `tailscale serve --bg --https=<port>`, exposing loopback `:8767` on Tailscale's HTTPS surface. CLI: `hermes-relay-tailscale enable|disable|status`.

Source: `plugin/relay/tailscale.py`.

---

## 6. Extension Points: Adding a New Channel

### 6.1 Server-Side Registration

1. Create handler in `plugin/relay/channels/<name>.py` with `async def handle(self, ws, envelope) -> None`.
2. Instantiate on `RelayServer` (`plugin/relay/server.py:97`): `self.<name> = <Name>Handler()`.
3. Register route in `ChannelMultiplexer` (`plugin/relay/server.py:2939`):
   ```python
   elif channel == "<name>":
       task = asyncio.create_task(server.<name>.handle(ws, envelope))
       _track_task(server, ws, task)
   ```
4. (Optional) Add HTTP side-channel routes for sync fallback.

### 6.2 Client-Side Routing

1. Register handler in `ChannelMultiplexer` (`app/src/main/kotlin/.../network/ChannelMultiplexer.kt`):
   ```kotlin
   "<name>" -> handlers["<name>"]?.onMessage(envelope)
   ```
2. Wire from ViewModel:
   ```kotlin
   multiplexer.registerHandler("<name>") { envelope -> /* dispatch */ }
   ```
3. Send via `multiplexer.send(envelope)`.

---

## 7. Session Grants & Expiry

### 7.1 Default Grants

| Channel | Default cap |
|---------|-------------|
| `chat` | Session TTL (no separate cap) |
| `terminal` | min(session TTL, 30 days) |
| `bridge` | min(session TTL, 7 days) |
| `tui` | min(session TTL, 30 days) *(proposed)* |

Users can override per-channel via TTL picker (Android) or `/pairing/register` metadata (host).

Source: `plugin/relay/auth.py:70-113` (`_default_grants`).

### 7.2 Never-Expire Sessions

`ttl_seconds == 0` → `expires_at = math.inf` server-side, serialized as `null` in JSON.

Sources: `plugin/relay/auth.py:88-91`, `plugin/relay/server.py:2750-2751`.

---

## 8. Pairing Flow Summary

1. Host generates code (6 chars, 10 min TTL): `POST /pairing` → `{"code": "ABC123"}`.
2. Code embedded in QR with relay URL, API server info, optional endpoints array.
3. Phone/desktop scans QR or accepts pasted code; extracts code + URL.
4. Client connects WSS, sends `auth` with `pairing_code` + device info.
5. Server consumes code (one-time), mints session, returns `auth.ok` with token + grants + profiles.
6. Client stores token for reconnection; TOFU-pins the certificate.
7. Subsequent connections use `session_token` in `auth` (no code needed).

Sources: `plugin/pair.py`, `plugin/relay/server.py` (pairing HTTP routes + WSS auth).

---

## 9. Quick Reference

| Layer | Responsibility | Primary files |
|-------|----------------|---------------|
| Protocol | Envelope format, channels, message types | `app/src/main/kotlin/.../models/Envelope.kt`, `plugin/relay/channels/*.py` |
| Auth | Pairing codes, sessions, rate limiting, cert pinning | `plugin/relay/auth.py`, `app/src/main/kotlin/.../auth/*` |
| Connection | WSS upgrade, multiplexing, lifecycle | `plugin/relay/server.py:2649-3046`, `app/.../ConnectionManager.kt` |
| Bridge RPC | `request_id` correlation, 30s timeout | `plugin/relay/channels/bridge.py` |
| Terminal PTY | Raw I/O batching, tmux integration | `plugin/relay/channels/terminal.py` |
| Notifications | In-memory cache, Android NotificationListenerService | `plugin/relay/channels/notifications.py` |
| TUI (new) | stdio ↔ envelope pump to `tui_gateway` subprocess | `plugin/relay/channels/tui.py` *(in progress)* |

---

**Versioning:** This spec tracks the implementation on the `dev` branch. Breaking wire changes bump a `protocol_version` field in `auth.ok` (not yet shipped — TODO once the desktop TUI client is live).
