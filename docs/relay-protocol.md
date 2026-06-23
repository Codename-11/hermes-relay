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
    "grants": {"chat": 2592000, "terminal": 604800, "bridge": 604800, "voice:stt": 2592000},
    "session_token": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
    "supports": {
      "typed_stream_events": true,
      "event_schema_version": 1
    }
  }
}
```

**Fields:**
- `pairing_code` — 6 chars A-Z/0-9, one-time, 10 min TTL, case-insensitive, consumed on use.
- `session_token` — UUID, used for reconnection after initial pairing. Exactly one of `pairing_code` or `session_token` must be present.
- `device_name` — display name on "Paired Devices" screen. Max 255 chars.
- `device_id` — unique persistent identifier.
- `ttl_seconds` — requested session lifetime; `0` means never expire. Ignored if pairing code carried pre-set metadata from host.
- `grants` — per-channel seconds-from-now. Keys include `chat`, `terminal`, `bridge`, `tui`, `voice:config`, `voice:stt`, `voice:tts`, and `voice:realtime`.
- `supports` — optional capability negotiation. `typed_stream_events: true` with `event_schema_version: 1` opts the client into first-class `chat`/`stream.event` envelopes (§3.3.2). Omit it or set it false for legacy text/final-response mode.

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
    "grants": {"chat": 1740000000, "terminal": 1739500000, "bridge": 1739200000, "voice:stt": 1740000000},
    "transport_hint": "wss",
    "profiles": [
      {"name": "default", "model": "gpt-4o", "description": "Default agent", "system_message": null},
      {"name": "mizu", "model": "claude-opus-4", "description": "Code assistant", "system_message": "You are a code expert..."}
    ]
  }
}
```

**Fields:**
- `session_token` — relay-minted UUID. Client stores for reconnection; bearer auth for Relay-protected HTTP routes (`/media/{token}`, `/sessions`, `/clipboard/inbox`, and `/voice/*`). Voice routes also accept a Hermes API bearer token as a narrow alternative; that API bearer is not a Relay session credential and does not authorize non-voice routes. Non-loopback API-bearer voice calls require HTTPS unless the operator enables `hermes relay insecure-api-key on` for local testing.
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

Bridge exposes 30+ HTTP routes on the relay itself (mirrored from legacy): `/ping`, `/screen`, `/screenshot`, `/tap`, `/tap_text`, `/long_press`, `/type`, `/swipe`, `/drag`, `/open_app`, `/return_to_hermes`, `/press_key`, `/scroll`, `/clipboard`, `/wait`, `/setup`, `/media`, `/find_nodes`, `/screen_hash`, `/diff_screen`, `/send_intent`, `/broadcast`, `/events`, `/location`, `/search_contacts`, `/call`, `/send_sms`, `/share_media`, `/send_mms`, …

Sources: `plugin/relay/channels/bridge.py`, `app/src/main/kotlin/.../network/handlers/BridgeCommandHandler.kt`.

### 3.3 Chat

**Purpose:** Native chat turn streaming and session listing.
**Direction:** Client → Server (`chat.send`, `chat.sessions.list`); Server → Client (legacy chat envelopes or typed stream events).
**Handler:** `plugin/relay/channels/chat.py`.

Modern Android/Desktop usually talk directly to Hermes dashboard/API-server for chat, but Relay also exposes a chat channel for paired native clients that need a single WSS route. Relay proxies `/api/sessions/{id}/chat/stream` SSE and preserves old text-first behavior unless the client explicitly advertises typed stream support in `system/auth.payload.supports`.

#### 3.3.1 Legacy chat envelopes

Clients that do not send `supports.typed_stream_events=true` receive the historical flattened messages:

| Type | Payload |
|------|---------|
| `chat.session` | `{session_id,title,model}` after Relay creates a session |
| `chat.delta` | `{session_id,message_id,delta}` assistant text only |
| `chat.progress` | `{session_id,message_id,delta}` subdued thinking/progress text |
| `chat.tool.started` | `{tool_name,tool_call_id?,preview,args}` |
| `chat.tool.completed` | `{tool_name,tool_call_id?,result_preview,success}` |
| `chat.tool.failed` | `{tool_name,tool_call_id?,error}` |
| `chat.turn.completed` | one assistant turn finished but run may continue |
| `chat.completed` | whole run/stream finished |
| `chat.error` | `{message}` |

This mode deliberately drops unknown/informational Hermes SSE events so older clients continue to work without UI changes.

#### 3.3.2 Typed stream.event mode

Capability negotiation:

```json
{
  "channel": "system",
  "type": "auth",
  "payload": {
    "session_token": "...",
    "supports": {
      "typed_stream_events": true,
      "event_schema_version": 1
    }
  }
}
```

When negotiated, each Hermes/API-server SSE event is forwarded on the chat channel as a Relay envelope whose payload is the versioned stream envelope:

```json
{
  "channel": "chat",
  "type": "stream.event",
  "id": "<uuid>",
  "payload": {
    "type": "stream.event",
    "schema_version": 1,
    "session_id": "sess_123",
    "run_id": "run_123",
    "seq": 42,
    "event": "tool.started",
    "ts": "2026-06-05T00:00:00Z",
    "payload": {
      "tool_name": "terminal",
      "call_id": "call_123",
      "preview": "npm test"
    }
  }
}
```

Stable top-level fields:

| Field | Stability | Notes |
|-------|-----------|-------|
| `type` | stable | always `stream.event` |
| `schema_version` | stable | v1 for this document. New incompatible shapes must increment. |
| `session_id` | stable | Hermes chat session id, copied from upstream or Relay-created session |
| `run_id` | stable nullable | upstream run id when present |
| `seq` | stable | monotonic per Relay stream (`session_id + run_id + request id`) when upstream does not supply one; clients use it for order/de-dupe |
| `event` | stable | event family below |
| `ts` | stable | ISO-8601 UTC string; Relay-generated when upstream omits timestamp |
| `payload` | event-specific | JSON object; unknown fields are preview-only unless documented by the upstream Hermes SSE contract |

Stable event families forwarded by Relay v1:

`session.created`, `run.started`, `message.started`, `assistant.delta`, `tool.progress`, `tool.pending`, `tool.started`, `tool.completed`, `tool.failed`, `memory.updated`, `skill.loaded`, `artifact.created`, `assistant.completed`, `run.completed`, `error`, `done`.

Relay-specific events must be namespaced: `relay.connection.*`, `relay.resume.*`, `relay.client_ack`.

Rendering guidance:

| Event | Native rendering |
|-------|------------------|
| `assistant.delta` | append to assistant bubble incrementally |
| `tool.progress` | subdued progress/thinking row, not assistant text |
| `tool.pending`/`tool.started`/`tool.completed`/`tool.failed` | collapsible tool card lifecycle |
| `artifact.created` | tappable/downloadable attachment row; payload may contain `url`, `path`, `title`, or a preview |
| `memory.updated`/`skill.loaded` | low-noise timeline chip/badge |
| `assistant.completed` | finish current assistant turn; run may continue |
| `run.completed`/`done` | explicit terminal completion state |
| `error` | explicit error/partial/interrupted affordance |

Reconnect/resume v1: Relay preserves in-order delivery on a live WebSocket and emits sequence numbers. Guaranteed replay/resume is not implemented for chat v1; clients should de-dupe by `(run_id || session_id, seq)` after reconnect and treat missing sequence gaps as best-effort live-stream loss. Future guaranteed resume belongs under `relay.resume.*`.

Payload safety: Relay redacts common secret-shaped keys (`token`, `api_key`, `authorization`, `password`, `secret`) and truncates large result-like fields to previews before sending typed events. Native clients must still treat payloads as previews, not as an authority for full tool results.

Golden fixture: `docs/fixtures/typed-stream-v1.jsonl` contains an ordered tool-using stream for native renderer tests and manual smoke.

### 3.4 Terminal

**Purpose:** PTY-backed interactive shell.
**Direction:** Bidirectional.
**Handler:** `TerminalHandler` (server); TUI client (incoming).
**Unix only** — returns `terminal.error` on Windows.

| Type | Direction | Payload |
|------|-----------|---------|
| `terminal.attach` | Client → Server | `{session_name?, cols, rows}` (`session_name` maps to server tmux session `hermes-<safe-name>`) |
| `terminal.attached` | Server → Client | `{session_name, pid, shell, cols, rows, tmux_available, reattach, replay?}` (`replay` is recent tmux scrollback captured before attach) |
| `terminal.input` | Client → Server | `{session_name?, data}` (UTF-8) |
| `terminal.output` | Server → Client | `{session_name, data}` (ANSI UTF-8) |
| `terminal.resize` | Client → Server | `{session_name?, cols, rows}` |
| `terminal.detach` | Client → Server | `{session_name?}` (preserves tmux) |
| `terminal.kill` | Client → Server | `{session_name?}` (destroys tmux, including background sessions not owned by the current WebSocket) |
| `terminal.list` | Client → Server | `{}` |
| `terminal.sessions` | Server → Client | `{sessions, tmux_available}` where `sessions[]` includes global `hermes-*` tmux sessions plus live per-WebSocket metadata |
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

Experimental computer-use tools use the same channel but are advertised by the desktop client only when the experimental computer-use feature flag is enabled (`--experimental-computer-use` or `HERMES_RELAY_EXPERIMENTAL_COMPUTER_USE=1`) after normal desktop-tool consent. The relay still treats `desktop_computer_*` names as strict-advertise tools so older or unflagged clients fail closed. Screenshots require an in-memory observe/assist/control grant. Host input currently uses a Windows-only CLI approval path: desktop-tool consent plus one visible local `yes` prompt for a task-scoped assist/control grant. Actions then run without per-action prompts until that grant expires or is canceled. Headless/non-interactive clients advertise blocked grant state and reject assist/control grant requests with structured failures.

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

### 3.9 Voice Output Route

Provider-neutral voice output uses dedicated HTTP/WebSocket routes separate
from the canonical `/ws` chat channel. Hermes still owns chat streaming, tool
execution, approvals, and final answers. Android sends already-decided Hermes
assistant text or brokered tool-status text to this route, and the relay streams
PCM from the selected TTS renderer back to the phone.

This route is experimental in the Android UI while provider choice, streaming
playback, barge-in tuning, and fallback behavior are being validated.

Setup:

```text
GET  /voice/output/config
PATCH /voice/output/config
GET  /voice/output/providers/{provider_id}/options
POST /voice/output/providers/{provider_id}/validate
POST /voice/output/session
GET  /voice/output/{session_id}  (websocket)
```

All routes require bearer auth. Relay session callers need an active
`voice:tts` grant; Hermes API bearer callers follow the same narrow voice
transport guard used by `/voice/config`, `/voice/transcribe`, and
`/voice/synthesize`.

Client websocket messages:

```json
{"type":"session.start"}
{"type":"session.resume","resume_token":"...","last_event_id":4,"last_audio_event_id":1,"last_played_audio_event_id":1}
{"type":"client.ack","event_id":5,"audio_event_id":2,"played_audio_event_id":2}
{"type":"response.create","text":"Final Hermes assistant sentence.","render_mode":"verbatim"}
{"type":"session.close"}
```

Server websocket messages:

```json
{"type":"voice.session.ready","event_id":1,"provider":"xai_tts","model":"xai-tts","voice":"eve","sample_rate":24000,"output_mode":"streaming_tts_renderer","resume_supported":true,"resume_ttl_ms":30000}
{"type":"voice.response.started","event_id":2,"output_mode":"streaming_tts_renderer","render_mode":"verbatim"}
{"type":"voice.audio.delta","event_id":3,"audio_event_id":1,"audio_base64":"...","sample_rate":24000,"channels":1,"sample_width":2,"rms_level":0.12}
{"type":"voice.audio.done","event_id":4}
{"type":"voice.response.done","event_id":5,"provider":"xai_tts","metrics":{"first_audio_ms":1764.25,"response_done_ms":3637.176},"event_log_path":"...","output_mode":"streaming_tts_renderer"}
```

Voice-output session creation also returns `resume_token`, `resume_supported`,
and `resume_ttl_ms`. If Android changes route while renderer PCM is in flight,
the relay keeps the render task alive for the resume TTL, buffers bounded
status/audio events, validates `session.resume`, and replays missed events after
the client's last acknowledged `event_id`/`audio_event_id`. This uses the same
Android `effectiveRelayUrl` route selection as fresh voice sessions. Android
may proactively resume when that route changes instead of waiting for the old
websocket to fail; provider renderers never see LAN, Tailscale, or cellular
details.

Config is relay-owned under `voice_output:` in `~/.hermes-relay/config.yaml`
or `RELAY_VOICE_OUTPUT_CONFIG`. The authenticated operator/app can patch safe
fields (`enabled`, `provider`, `model`, `voice`, `sample_rate`, `language`,
`codec`, `optimize_streaming_latency`, `text_normalization`, and
`fallback_enabled`) without exposing provider secrets to Android. Provider
secrets and xAI OAuth paths remain server-side.

Config responses include provider option metadata for dropdown-capable clients:
`providers[].models`, `providers[].voices`, `providers[].languages`, and
`providers[].sample_rates`. Provider-specific option responses use
`schema_version: 1` and can also include `voice_groups`, `voice_metadata`,
`recommended_voices`, and `model_voice_compatibility` so clients can show
searchable grouped voice pickers and catch model/voice mismatches. If a profile
is selected, Android sends `?profile=<name>` for config reads/writes and
`{"profile":"<name>"}` for session creation so voice output follows that
profile's experimental `voice_output:` section.

Before saving a changed provider, clients can call
`GET /voice/output/providers/{provider_id}/options?profile=<name>` to refresh a
single provider's current safe options. Dynamic provider discovery runs on the
relay and returns a `dynamic` status field; provider secrets are never sent to
Android, and manual IDs remain valid when discovery is unavailable. xAI dynamic
discovery uses `GET /v1/tts/voices` and paginated `GET /v1/custom-voices` when
xAI auth is present; ElevenLabs uses `/v1/voices` and `/v1/models`; OpenAI is
static from the documented built-in voice set because there is no general
OpenAI voice-list endpoint for this surface. Dynamic option fetches are cached
server-side by `RELAY_PROVIDER_OPTIONS_CACHE_SECONDS`.

Before saving, clients can call
`POST /voice/output/providers/{provider_id}/validate` with `model`, `voice`,
`sample_rate`, and optional `language`. The response includes `valid`,
`checks[]`, and `summary`; unknown manual IDs are warnings, while advertised
compatibility conflicts are errors.

`xai_tts` is the first-class Grok TTS renderer, `openai_tts` is the OpenAI
speech renderer, and `stub` is the no-quota route test provider. If the output
route fails before audio starts and fallback is enabled, Android falls back to
the legacy `/voice/synthesize` path.

Source: `plugin/relay/voice_output.py` and Android `RelayVoiceClient`.

### 3.10 Realtime Voice-Agent Route

Realtime provider voice uses dedicated HTTP/WebSocket routes separate from the
canonical `/ws` channel. The current Android dev path can use it for provider
PCM playback, latency metrics, and barge-in testing. The default deterministic
assistant speech path is `/voice/output/*`; this realtime route remains the
realtime-agent/playground path. The older `/voice/config`, `/voice/transcribe`,
and `/voice/synthesize` routes remain available for basic STT/TTS fallback and
utility clients. Operators can disable realtime voice with
`RELAY_REALTIME_VOICE_ENABLED=0`.

The experimental Realtime Agent engine is a separate brokered surface at
`/voice/realtime-agent/*`. It binds the selected provider to the active Hermes
profile/chat session, mirrors Hermes tool state into Android, and exposes only
`hermes_run_task`, `hermes_get_status`, `hermes_cancel`, and `hermes_confirm`
to provider-side tool loops.

Setup:

```text
GET  /voice/realtime/config
GET  /voice/realtime/providers/{provider_id}/options
POST /voice/realtime/providers/{provider_id}/validate
POST /voice/realtime/session
GET  /voice/realtime/{session_id}  (websocket)

GET   /voice/realtime-agent/config
PATCH /voice/realtime-agent/config
GET   /voice/realtime-agent/providers/{provider_id}/options
POST  /voice/realtime-agent/providers/{provider_id}/validate
POST  /voice/realtime-agent/session
GET   /voice/realtime-agent/{session_id}  (websocket)
```

All three routes require bearer auth. Relay session callers need an active
`voice:realtime` grant; Hermes API bearer callers follow the same narrow voice
transport guard used by `/voice/config`, `/voice/transcribe`, and
`/voice/synthesize`.

For paired Relay-session callers, the session token is not forwarded to the
Hermes WebAPI. Realtime Agent broker calls use the relay host's local Hermes
API credential from `config.yaml`, `.env`, or `API_SERVER_KEY`; if that
credential is missing or rejected, the websocket emits
`error_code=hermes_broker_auth_failed`.

Realtime Agent session creation returns resumable-session metadata so Android
can survive short route changes without starting a second Hermes turn:

```json
{
  "success": true,
  "session_id": "...",
  "websocket_path": "/voice/realtime-agent/{session_id}",
  "resume_token": "...",
  "resume_supported": true,
  "resume_ttl_ms": 30000,
  "context_message_count": 6
}
```

The resume token is relay-generated, scoped to that voice session, and accepted
only from the same voice-auth principal. Relay-session callers may resume from a
new bearer for the same paired device; Hermes API bearer callers must use the
same bearer.

Session creation accepts optional `context_messages`, a compact array of recent
`{"role":"user|assistant|system","content":"...","source":"hermes_chat|realtime_agent"}`
items from the Android timeline. The broker trims and seeds these into the
provider instructions so Realtime Agent can answer follow-ups from already
visible chat/voice context without first calling Hermes. If Android sends no
context but provides `chat_session_id`, the relay attempts a short
`GET /api/sessions/{chat_session_id}/messages` fetch and seeds recent Hermes
messages instead. Provider-native sessions are still ephemeral; Hermes remains
the durable context store.

Client websocket messages:

```json
{"type":"session.start"}
{"type":"session.resume","resume_token":"...","last_event_id":12,"last_audio_event_id":4,"last_played_audio_event_id":4,"last_input_chunk_id":8}
{"type":"input_audio.append","chunk_id":1,"sample_rate":16000,"audio_base64":"..."}
{"type":"client.ack","event_id":13,"audio_event_id":5,"input_chunk_id":8}
{"type":"response.create","text":"Test prompt","tool_scaffold":false,"render_mode":"verbatim"}
{"type":"session.close"}
```

Realtime Agent client websocket messages stream mic PCM to the broker. In the
provider-native path (`xai_realtime` or `openai_realtime`), `input_audio.commit`
finalizes the Android-captured utterance; the relay then asks the active
realtime provider to create a response. Android does not include a client
transcript because the provider owns speech recognition inside the
relay-brokered session. `client.ack` reports receipt cursors; Android only
reports heard/drained audio with `playback.drained.played_audio_event_id`.
Settings tests may instead send `response.create` with
`text`; the relay forwards that text as a provider-native realtime user message
and streams the provider's audio back without using legacy TTS.

```json
{"type":"session.start"}
{"type":"input_audio.append","chunk_id":1,"sample_rate":16000,"audio_base64":"..."}
{"type":"input_audio.commit"}
{"type":"response.create","text":"Say a short Realtime Agent test confirmation."}
{"type":"playback.drained","call_id":"...","played_audio_event_id":1}
{"type":"response.cancel"}
{"type":"hermes.confirm","confirmation_id":"...","answer":"yes"}
{"type":"session.close"}
```

Server websocket messages:

```json
{"type":"voice.session.ready","event_id":1,"provider":"xai_realtime","model":"grok-voice-latest","voice":"eve","sample_rate":24000,"resume_supported":true,"resume_ttl_ms":30000}
{"type":"voice.input_audio.received","event_id":2,"input_chunk_id":1,"byte_count":320,"total_bytes":320}
{"type":"voice.input_transcript.delta","event_id":3,"delta":"please check"}
{"type":"voice.input_transcript.final","event_id":4,"text":"Please check the relay status."}
{"type":"voice.response.started","event_id":5,"provider":"xai_realtime","response_id":"ack-1"}
{"type":"voice.response.delta","event_id":6,"source":"provider","delta":"I'll check Hermes.","response_id":"ack-1"}
{"type":"voice.output_audio.delta","event_id":7,"audio_event_id":1,"audio_base64":"...","sample_rate":24000,"channels":1,"sample_width":2,"rms_level":0.12,"response_id":"ack-1"}
{"type":"voice.output_audio.done","event_id":8,"response_id":"ack-1"}
{"type":"voice.playback_drain.requested","event_id":9,"call_id":"forced-preamble-1","reason":"pre_hermes_ack","timeout_ms":2500}
{"type":"hermes.run.started","event_id":10,"session_id":"...","run_id":"..."}
{"type":"hermes.tool.started","event_id":11,"source":"hermes","run_id":"...","tool_call_id":"...","tool_name":"terminal","message":"Running command."}
{"type":"hermes.run.progress","event_id":12,"source":"hermes","run_id":"...","status":"running","message":"Running command.","status_key":"tool:terminal:running","should_speak":false,"active_tool_name":"terminal","completed_tool_count":0,"elapsed_ms":12000}
{"type":"hermes.tool.completed","event_id":13,"source":"hermes","run_id":"...","tool_call_id":"...","tool_name":"terminal","result_preview":"...","success":true}
{"type":"voice.output_audio.delta","event_id":14,"audio_event_id":2,"audio_base64":"...","sample_rate":24000,"channels":1,"sample_width":2,"rms_level":0.12}
{"type":"voice.output_audio.done","event_id":15}
{"type":"voice.response.done","event_id":16,"event_log_path":"..."}
```

If Android loses the WebSocket while the provider/Hermes turn is still active,
or Android's route signal changes while the old WebSocket is still half-open,
the client reconnects with `session.resume`. The relay marks the session
detached when the old socket disappears, keeps the server-side provider socket
and Hermes state alive for `resume_ttl_ms`, and buffers bounded status/audio
events. On a valid `session.resume`, the relay emits:

```json
{"type":"voice.session.resumed","event_id":12,"session_id":"..."}
{"type":"voice.replay.started","event_id":13,"from_event_id":4,"from_audio_event_id":0,"replay_event_count":3}
{"type":"voice.output_audio.delta","event_id":8,"audio_event_id":1,"replayed":true,"audio_base64":"..."}
{"type":"voice.replay.done","event_id":14,"replay_event_count":3}
```

Audio deltas are mono 16-bit little-endian PCM base64 chunks. Android records a
single PCM/WAV utterance for the voice turn. In stable voice mode the WAV is
uploaded to `/voice/transcribe` for the Hermes STT leg. In provider-native
Realtime Agent mode, raw PCM is forwarded through `input_audio.append` messages,
resampled to the provider session rate when needed, and sent to the active
provider over the server-side WebSocket. Provider PCM output streams back to
Android for immediate `AudioTrack` playback. Tool execution remains owned by the
Hermes chat/relay loop; the provider only sees the approved `hermes_run_task`,
`hermes_get_status`, `hermes_cancel`, and `hermes_confirm` function schemas. The
relay sends compact function results back to the provider and waits for
Android's `playback.drained` acknowledgement or a short broker timeout before
requesting post-tool audio. For relay-forced Hermes turns, the relay must first
ask the active realtime provider to speak a short pre-Hermes acknowledgement,
wait for that provider audio to drain, and only then start the Hermes run. This
keeps the provider-native speech loop intact while Hermes remains the governed
tool authority. Local status speech is only a fallback or long-wait affordance
and must not create a competing `/voice/output` speech loop in healthy
provider-native turns. `hermes_run_task` function results include `text`/`answer`,
a shorter `summary`, `tool_count`, `last_tool_name`, and a provider instruction
telling the realtime provider to treat Hermes output as authoritative context
and not claim missing context after a result is present.
The broker also tracks active/latest Hermes tool state. It forwards
`hermes.tool.started`, `hermes.tool.delta`, `hermes.tool.completed`, and
`hermes.tool.failed` when Hermes exposes them, and synthesizes a
`hermes.tool.started` event if an upstream stream only reports a late completion.
During longer Hermes runs it emits `hermes.run.progress` with a stable
`status_key`, `active_tool_name`, `last_tool_name`, `completed_tool_count`, and
`should_speak`. Android should render these as compact timeline/tool rows. It
must not route those status lines through `/voice/output` while provider-native
Realtime Agent audio is active, because that creates competing speech streams.
Raw tool output should remain relay-log/debug detail.
Realtime Agent sessions also carry explicit interface context in provider
instructions and brokered Hermes system messages, including relay-local
date/time, so the agent can answer whether the active path is `realtime_agent`,
what provider is active, or what today's date is without falling back to model
training priors. For research, current facts, news, external data, live checks,
latest/recent/versioned data, device/desktop/app state, personal/session/project
context, side effects, precision-sensitive answers, explicit check/verify/look-up
requests, media/artifacts, or anything not present in the realtime prompt/session
context, provider-native adapters are instructed to call `hermes_run_task`
instead of answering from provider priors. Hermes response deltas sent during
tool execution are marked with `source:"hermes"`; clients should keep them out of
default spoken output and let the provider summarize the function result
naturally. Provider instructions also ask for speech-safe formatting: dates,
times, currency, percentages, versions, measurements, counts, paths, URLs, IDs,
JSON, logs, stack traces, tables, and dense numeric strings should be spoken as
human-readable summaries rather than raw character-by-character dumps.

#### Background Hermes runs (ADR 33)

Short Hermes runs answer in-line (the provider speaks the summary as soon as the
brokered result returns). A run that exceeds `promote_after_ms` is **promoted**
to a tracked background task so the provider event pump stays responsive instead
of blocking on the run:

```json
{"type":"hermes.run.promoted","event_id":20,"source":"hermes","run_id":"...","tier":"promoted","promote_after_ms":6000,"spoken_handoff":true,"result_delivery":"speak_when_idle","call_id":"call-1"}
{"type":"hermes.run.background_completed","event_id":41,"source":"hermes","run_id":"...","ok":true,"tool_count":2}
```

- On promotion the relay closes the pending provider function call with an
  interim `{"status":"running_in_background"}` output (so the provider socket is
  not left awaiting a tool result) and, when `spoken_handoff` is on, has the
  provider speak a brief "I'm on it" line.
- When the background run finishes, the relay emits
  `hermes.run.background_completed`, waits for the audio **floor** to be idle,
  then injects the result through the same forced-summary path so the provider
  speaks the answer exactly once. `result_delivery` selects `speak_when_idle`
  (default), `notify_then_speak`, or `visual_only`.
- `hermes_run_task(mode="background")` skips the grace window and detaches
  immediately (`tier:"durable"`), even when grace-period promotion is disabled.
- `hermes.run.progress` carries two extra fields while a run is in flight:
  `tier` (`foreground` | `promoted` | `durable`) and `floor`
  (`idle` | `provider_speaking` | `hermes_filler` | `result_pending`). The relay
  is the single floor owner — a completed background result never barges in, and
  Android local filler is suppressed while the provider holds the floor.
- A promoted run is cancellable via `response.cancel` (client) or the
  `hermes_cancel` provider tool; if the WebSocket detaches mid-run, the
  completion events replay through the resume event ring.

Promotion is configured per profile under `realtime_voice` (relay) and exposed
on `GET/PATCH /voice/realtime-agent/config` as a `promotion` block:
`enabled` (default on), `promote_after_ms`, `background_default_mode`,
`spoken_handoff`, `progress_spoken_after_ms`, `progress_repeat_ms`,
`result_delivery`, and `max_background_runs`. The default-on path is safe because
it closes the pending call rather than holding an open provider response; the
`scripts/realtime-provider-idle-probe.py` verdict (see `docs/realtime-voice-poc.md`)
confirms per-provider socket survival across the between-turns idle gap.

If a provider-native realtime turn answers directly without Hermes, Android
keeps the local user/assistant bubbles and marks that provider-only assistant
turn as unsynced. The next normal Hermes chat/run request includes those
provider-only turns as compact OpenAI-format user/assistant messages before the
live user message, then marks them synced. Hermes-backed realtime tool turns are
not duplicated; Hermes already owns their canonical session record.

The render-after-Hermes compatibility bridge remains available for non-native
providers, but Realtime Agent settings advertise the stricter
`supports_realtime_agent_native` flag so lab/render-only realtime providers are
not mistaken for native speech-to-speech agents. Provider-native adapters follow
the same broker contract:
Android PCM -> relay provider WebSocket -> normalized transcript/audio/tool
events -> Hermes brokered tool loop -> provider function output -> Android
timeline. The current first-class native targets are xAI Grok Voice Agent and
OpenAI Realtime; both keep the same Hermes-owned tool and confirmation boundary.

Realtime config responses use the same provider option metadata shape where
known. Profile-scoped reads/writes target the selected profile's experimental
`realtime_voice:` section; the route remains a lab/dev path rather than the
default Hermes chat voice path.

Realtime provider settings use the matching
`GET /voice/realtime/providers/{provider_id}/options?profile=<name>` refresh
route for realtime-capable providers and
`POST /voice/realtime/providers/{provider_id}/validate` before saving pending
provider/model/voice/sample-rate selections.

Source: `plugin/relay/realtime_voice.py` and Android `RelayVoiceClient`.

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
Top-level `key` is the Hermes API bearer used for direct chat/session HTTP; the relay pairing code is only `relay.code`.

```json
{
  "hermes": 3,
  "host": "192.168.1.100",
  "port": 8642,
  "key": "<api_key>",
  "tls": false,
  "relay": {
    "url": "ws://192.168.1.100:8767",
    "code": "ABC123",
    "ttl_seconds": 604800,
    "transport_hint": "ws"
  },
  "endpoints": [
    {
      "role": "lan",
      "priority": 0,
      "api": {"host": "192.168.1.100", "port": 8642, "tls": false},
      "relay": {"url": "ws://192.168.1.100:8767", "transport_hint": "ws"}
    },
    {
      "role": "tailscale",
      "priority": 1,
      "api": {"host": "hermes.fa0c2e.ts.net", "port": 8642, "tls": true},
      "relay": {"url": "wss://hermes.fa0c2e.ts.net:8767", "transport_hint": "wss"}
    },
    {
      "role": "public",
      "priority": 2,
      "api": {"host": "relay.example.com", "port": 443, "tls": true},
      "relay": {"url": "wss://relay.example.com:8767", "transport_hint": "wss"}
    }
  ]
}
```

See `docs/decisions.md` ADR 24 for full rationale.

Source: `plugin/pair.py`.

### 5.2 Tailscale Serve (ADR 25)

The Tailscale helper fronts both loopback services with `tailscale serve`: relay `:8767` and Hermes API `:8642`. Serving only the relay makes pairing and terminal/bridge reachable but leaves chat, API-key voice auth, and API health probes broken off-LAN. CLI: `hermes-relay-tailscale enable|disable|status`; use `--relay-only` only for legacy relay-only deployments.

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
| `tui` | min(session TTL, 30 days) |
| `voice:config` | Session TTL (inherits chat grant for legacy sessions) |
| `voice:stt` | Session TTL (inherits chat grant for legacy sessions) |
| `voice:tts` | Session TTL (inherits chat grant for legacy sessions) |
| `voice:realtime` | Session TTL (inherits chat grant for legacy sessions) |

Users can override per-channel via TTL picker (Android) or `/pairing/register` metadata (host).

Source: `plugin/relay/auth.py:70-113` (`_default_grants`).

### 7.2 Never-Expire Sessions

`ttl_seconds == 0` → `expires_at = math.inf` server-side, serialized as `null` in JSON.

Sources: `plugin/relay/auth.py:88-91`, `plugin/relay/server.py:2750-2751`.

---

## 8. Pairing Flow Summary

1. Host generates code (6 chars, 10 min TTL): `POST /pairing` → `{"code": "ABC123"}`.
2. Code embedded in QR with relay URL, API server info, Hermes API bearer in top-level `key` when configured, and optional endpoints array. Dashboard-minted QRs get that bearer from host-local Hermes config unless `api_key` is explicitly supplied.
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
