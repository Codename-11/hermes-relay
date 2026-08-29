# Hermes-Relay — Android App

Android's declarative plugin surface is specified in
[Android Plugins](android-plugins.md).

## Specification v1.4

**Status:** v1.0.0 stable. The default path supports chat, Manage, and voice on vanilla upstream Hermes without installing the Relay plugin. Relay is additive: terminal, bridge/device control, notification companion, remote access, extra/provider-native voice, desktop tooling, and dashboard Relay management. Historical phase notes remain in this file for context; the current route ownership source of truth is [`docs/upstream-surface-matrix.md`](upstream-surface-matrix.md).
**Repo:** [Codename-11/hermes-relay](https://github.com/Codename-11/hermes-relay)  
**Updated:** 2026-08-24

---

## 1. What This Is

A **native Android app** for the Hermes agent platform. Not just remote phone control — a full bidirectional interface between you and your Hermes server from anywhere.

Current capabilities are split between vanilla upstream Hermes and optional Relay surfaces:

| Surface | Requires Relay | What |
|---------|----------------|------|
| **Chat** | No | Talk to any Hermes agent profile with dashboard `/api/ws` live thinking when signed in, or API-server SSE fallback |
| **Manage** | No | Dashboard-backed config, profiles, model/provider keys, skills, MCP, and diagnostics |
| **Vanilla Hermes voice** | No | Dashboard `/api/audio/transcribe`, streaming `/api/audio/speak-stream`, and compatible `/api/audio/speak` fallback with the Manage session |
| **Terminal** | Yes | Secure remote shell access to the Hermes server via tmux |
| **Bridge / Device Control** | Yes | Agent controls the sideload phone with explicit safety gates |
| **Relay power features** | Yes | Remote access, notification companion, provider-native voice, desktop tooling, media relay |

The standard Vanilla Hermes connection needs only the Dashboard/Gateway surface.
An API-server endpoint can be discovered or added as an automatic fallback for
chat and advanced headless compatibility. Pairing adds the Relay URL, session
token, terminal/bridge grants, and optional network candidates.

**What it is not:**
- Not a web wrapper — native Kotlin + Jetpack Compose
- Not phone-only — the bridge channel gives the agent hands on your device
- Not a replacement for Discord/Telegram — it's a first-party Hermes client with capabilities those platforms can't offer (terminal, bridge)

---

## 2. Design Principles

1. **Vanilla Hermes first** — chat, Manage, and voice must work against unmodified upstream Hermes before any Relay power path is considered.
2. **Secure by default** — WSS/HTTPS for remote paths; dashboard, API, and Relay auth stay on their native surfaces.
3. **Realtime where the surface supports it** — gateway chat can stream live thinking; API-server SSE remains the fallback; terminal and bridge stay realtime through Relay.
4. **Clean UX** — Material 3, minimal setup, and clear route identity for Vanilla Hermes vs Relay.
5. **Offline-aware** — graceful degradation when connection drops. Auto-reconnect with exponential backoff.
6. **Server-side state** — the app is a thin client. Sessions, history, memory, profiles, and dashboard state live on the Hermes server.
7. **Supervision is a client policy** — Android may offer a parent-controlled,
   profile-pinned restricted interface, but it does not claim to make the
   selected Hermes profile, server, or agent child-safe. See ADR 66 and the
   [Supervised Mode guide](../user-docs/guide/supervised-mode.md).

---

## 3. Architecture

### 3.1 High-Level

```
Android app
  |-- Vanilla Hermes chat   -> dashboard /api/ws, then API-server SSE fallback
  |-- Vanilla Hermes Manage -> dashboard /api/*
  |-- Vanilla Hermes voice  -> dashboard /api/audio/*
  |-- Relay terminal      -> Tailscale Serve WSS, or opt-in Hermes Secure Link :9443/relay/ws
  |-- Relay bridge/tools  -> the same authenticated Relay transport
  `-- Relay voice extras  -> relay /voice/*

Hermes upstream
  |-- API server          -> /v1/* and /api/sessions/*
  |-- Dashboard web       -> Manage, audio, auth, /api/ws tickets
  `-- tui_gateway         -> /api/ws live chat/reasoning transport

Hermes-Relay plugin
  |-- plugin manager root -> plugin/
  |-- relay server        -> plugin/relay/server.py
  |-- dashboard tab       -> plugin/dashboard/
  `-- diagnostics         -> hermes relay doctor
```

In multiplex gateways, every profile's plugin manager owns its own Relay tool,
command, hook, platform, and system-prompt registrations. Relay configuration
resolves through Hermes' context-local home override, not only the process
`HERMES_HOME`. Current Hermes hosts receive Relay prompt context through owned
`register_system_prompt_section` entries so reload, disable, re-enable, and
unload remain profile-isolated; the legacy prompt wrapper is used only when
that additive registration API is unavailable.

A saved **Connection** represents one Hermes installation, not one transport.
Its stable identity is independent of endpoint URLs. Dashboard/Gateway is the
standard upstream surface; API server and Relay endpoints are optional
capabilities that can be discovered, added, removed, and diagnosed separately.
The normal UI reports outcomes such as Chat, Manage, Voice, API fallback, and
Relay extensions instead of treating a missing optional endpoint as a broken
connection.

### 3.2 Protocol

Relay realtime communication flows over a single WebSocket connection. Vanilla
Hermes chat, Manage, and voice use upstream dashboard/API HTTP and WebSocket
surfaces directly. Relay messages use a typed envelope:

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
| `auth` (pairing mode) | App → Server | `{ pairing_code, ttl_seconds?, grants?, device_name?, device_hostname?, device_id, device_model?, device_platform? }` — `device_name` is the primary display identity and falls back to `device_hostname`; model/platform are informational detail only. `ttl_seconds` / `grants` remain in the wire shape for client compatibility, but only policy attached by a loopback-only host flow is authoritative; missing host metadata uses bounded server defaults |
| `auth` (session mode) | App → Server | `{ session_token, device_name?, device_hostname?, device_id, device_model?, device_platform? }` — identity metadata can enrich an existing pair on reconnect; ttl/grants are not re-sent and the server keeps the grant table keyed on the original pair |
| `auth.ok` | Server → App | `{ session_token, server_version, profiles[], expires_at, grants, transport_hint }` — see below |
| `auth.fail` | Server → App | `{ reason }` |
| `ping` | Both | `{ ts }` |
| `pong` | Both | `{ ts }` |

**`auth.ok` extended fields** (added in ADR 15 — see `docs/decisions.md`):

| Field | Type | Meaning |
|-------|------|---------|
| `expires_at` | epoch seconds or `null` | Session lifetime. `null` means never-expire (user explicitly picked "Never" in the TTL picker). Server-side `math.inf` serializes as `null`. |
| `grants` | `{ channel: epoch \| null }` | Per-channel expiries. Keys today: `chat`, `terminal`, `bridge`, `tui`, `voice:config`, `voice:stt`, `voice:tts`, and `voice:realtime`. Each grant is clamped to the session lifetime — a grant cannot outlive its session. `null` means the grant shares the session's never-expire. |
| `transport_hint` | `"wss"` / `"ws"` / `"unknown"` | What the server believes the phone is actually connected over. Drives the transport security badge and the TTL picker's default option on re-pair. |
| `profiles` | `[{name, model, description, system_message, api_server_*}]` | **Added v0.6.0; expanded 2026-05-18.** Relay-advertised list of upstream Hermes profiles discovered at `~/.hermes/profiles/*/`, plus a synthetic `"default"` entry describing Hermes' effective default profile. When the root `active_profile` marker names a valid profile, the synthetic row uses that profile's config/SOUL/API metadata; otherwise it uses the root profile. The named row remains available for explicit selection. `system_message` carries the profile's `SOUL.md` content and may be `null`. `api_server_enabled`, `api_server_url`, `api_server_host`, `api_server_port`, and `api_server_key_present` let Android route chat/session calls through a profile's own Hermes API server when it is running, without exposing the key. Empty list when `RELAY_PROFILE_DISCOVERY_ENABLED=0`. See `docs/decisions.md` §21. |

#### Channel: `chat`
**Note:** Vanilla Hermes chat prefers the upstream dashboard `/api/ws` gateway when
Manage auth is ready, then falls back to Hermes API Server HTTP/SSE paths (see
Section 6.2). It does not traverse the Relay server. Relay voice, bridge,
terminal, notifications, and inbound media do go through Relay. Relay voice
HTTP/WSS routes accept either a Relay session token with an active
`voice:config`, `voice:stt`, `voice:tts`, or `voice:realtime` grant, depending
on the route, or the Hermes API bearer token; that API bearer exception does not
apply to bridge, terminal, TUI, sessions, media, clipboard, profile writes, or
Android control routes. Non-loopback API-bearer voice calls require HTTPS unless
the local operator enables the runtime dev toggle with
`hermes relay insecure-api-key on`. The chat SSE event types are:

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
Newer Dashboard session rows may also supply `cwd`, `git_branch`, and
`git_repo_root`. Android reduces paths to a repository name for display and
uses the read-only `POST /api/profiles/sessions/pull-requests` transcript scan
to attach the PR a coding session created, then the repo-scoped read-only
`POST /api/git/review/pr-list` route for its current lifecycle state. All of
this metadata is optional; older Dashboard and API-server hosts retain the
ordinary session row.

Chat availability is derived only from the authenticated Gateway and supported
API-server fallback routes. A Send with no usable route remains fail-closed and
surfaces a retryable conversation failure plus secret-free Diagnostics evidence.
Profile-owned Gateway history is required to load through that exact profile;
an unavailable scoped reader surfaces a history failure instead of accepting an
empty or different profile's transcript as authoritative.

#### Channel: `terminal`
PTY streaming — raw terminal I/O.

| Type | Direction | Payload |
|------|-----------|---------|
| `terminal.attach` | App → Server | `{ session_name?, cols, rows }` |
| `terminal.attached` | Server → App | `{ session_name, pid }` |
| `terminal.input` | App → Server | `{ data }` (raw keystrokes) |
| `terminal.output` | Server → App | `{ data }` (raw ANSI output) |
| `terminal.resize` | App → Server | `{ cols, rows }` |
| `terminal.detach` | App → Server | `{ session_name? }` — preserves tmux session |
| `terminal.kill` | App → Server | `{ session_name? }` — destroys tmux session and kills the shell |

#### Channel: `bridge`
Phone control — mirrors upstream relay protocol.

| Type | Direction | Payload |
|------|-----------|---------|
| `bridge.command` | Server → App | `{ request_id, method, path, params?, body? }` |
| `bridge.response` | App → Server | `{ request_id, status, result }` |
| `bridge.status` | App → Server | `{ accessibility_enabled, overlay_enabled, battery }` |

### 3.3 Auth Flow

Dashboard/Gateway redirect authentication is provider-compatible and
capability-driven. When `/api/status.auth_flows` advertises `native_pkce`, every
interactive provider, including password-capable providers, uses the upstream
brokered system-browser flow so browser password managers/passkeys remain
available; provider display/configuration names never select the protocol. Android passes
the selected provider when upstream requires it, while retaining the hosted
Nous compatibility behavior where the gateway selects its single
native-eligible provider.
The app owns an ephemeral five-minute loopback callback and stores the resulting
bearer session only for that connection and exact dashboard origin. Callback,
code-exchange, hosted-gateway, transport, response-shape, and secure-storage
failures surface as distinct secret-free recovery guidance. Client-local native
failures automatically continue through the upstream cookie/WebView fallback;
explicit provider denial, server rejection, and rate limiting remain visible
instead of starting a second authorization attempt.
Unreadable secure stores may be cleared and rebuilt, with any Keystore fallback,
self-heal, or temporary in-memory degradation recorded in Diagnostics without
credential values, cookie contents, endpoint URLs, or storage identifiers.
Older gateways that do not advertise `native_pkce`, plus client-local native
fallback, use the dashboard cookie flow: Android opens `/auth/login` in a
full-screen embedded browser destination, lets the provider return through its
configured `/auth/callback`, imports only same-origin cookies, and verifies them
through `/api/auth/me`. When the selected LAN, private, or
Tailscale route advertises a different canonical callback, Android first
probes the redirect without cookies, starts the real transaction on that
canonical base, and retains it as the connection's authenticated
Dashboard/Gateway origin rather than as a network-route candidate
only after the user reviews the move and matching non-empty upstream
`install_id` values prove both addresses reach the same installation. A
mismatch is rejected; older gateways missing either ID require explicit
confirmation. Different public origins require HTTPS. Different cleartext
origins are accepted only between literal loopback/private-overlay hosts with
an HTTPS identity-provider hop. Secure
cookies are never copied back to cleartext LAN, while API and Relay routes stay
unchanged. The short-lived auth WebView permits third-party cookies for
compatible federated identity-provider pages. HTTPS is required on public routes;
explicit private-LAN and Tailscale-IP dashboards may use their existing HTTP
transport. When such a private route advertises a canonical HTTPS Nous callback,
Android starts the browser on that canonical origin so Hermes' temporary PKCE
cookie and the provider callback remain same-origin, then exchanges the
one-time code through the active private route. The verified session is shared
by Manage, Gateway tickets, and standard voice. Dashboard authentication and
Gateway transport readiness remain separate: an authenticated session stays
signed in when ticket minting is temporarily unavailable, while Gateway
reconnect continues independently. When a multi-provider Dashboard masks an
expired native bearer as a provider-unavailable ticket response, Android makes
one bounded native refresh and ticket retry; other requests are never replayed.
Dashboard ticket 5xx responses also receive one immediate fresh-ticket retry
for restart recovery; authentication rejection, rate limiting, and local HTTP
timeouts remain single-attempt failures.
Cookie sessions remain scoped to the exact browser host that issued them.
Android never copies a basic or OAuth cookie between LAN, Tailscale, public, or
derived Dashboard hosts; a different legacy cookie host requires sign-in there.

The Routes surface presents the Dashboard/Gateway origin separately from LAN,
Tailscale, direct API, Relay, and other network candidates. Editing the origin
revalidates it and clears origin-bound cookies or bearer credentials when its
base changes. A single HTTPS hostname can still use a local path through split
DNS; the hostname must remain identical so OIDC cookies and callbacks stay
same-origin.

Pairing is QR-driven. The operator runs the pair command on the host — `hermes pair`, `/hermes-relay-pair` from any Hermes chat surface, or the compatibility `hermes-pair` shell shim. All share the same implementation in `plugin/pair.py`. The command probes for a running relay, generates a fresh 6-char code, pre-registers it with the relay via the loopback-only `POST /pairing/register` endpoint, then embeds the relay URL + code + **chosen TTL + per-channel grants + HMAC signature** (plus the API server credentials and optional dashboard URL) in a single QR payload. The phone scans once, **confirms the TTL and grants via a picker dialog**, and is configured for both chat AND terminal/bridge.

Each Android Add Pair route owns its exact allocated target connection identity.
That target must be persisted and active before its wizard becomes ready; it is
never silently replaced by a differently identified placeholder. Duplicate
renewal performs one explicit validated handoff to the existing connection ID
before switching, which keeps the Compose-owned wizard alive while all
authentication stores follow that existing identity. The waiting surface is
bounded: a target that does not become ready exposes Retry and Cancel and
records only boolean, secret-free readiness evidence in Diagnostics.

The primary secure remote path today is Tailscale Serve, which exposes Relay as
WSS and the independently authenticated upstream API/Dashboard surfaces as
HTTPS. The optional Relay plugin **Hermes Secure Link** is a unified alternative: when
explicitly enabled it listens on `:9443`, advertises the operator-reviewed
paired endpoint's SPKI material,
and exposes fixed `/relay`, `/api`, and `/dashboard` namespaces. Each service
retains its native credential, and Dashboard forwarding fails closed unless
its upstream OAuth/password gate is active. Clients select secure candidates
first and may fall back to a separately configured LAN route; the existing
plain-route acknowledgement still applies. See
[`docs/security-native-proxy.md`](security-native-proxy.md).

Hermes Reach is the experimental `outbound_broker` candidate for hosts that cannot
accept inbound traffic. Host and client both connect outward to broker
`/v1/connect`; the broker only matches and forwards opaque records. QR-pinned
Secure Link TLS remains end-to-end inside that outer WSS connection, so broker
WSS is not itself the end-to-end security boundary. The broker may observe
routing identity, source, timing, and byte metadata and may disrupt delivery,
but cannot read authenticated inner paths, headers, credentials, or plaintext.
Direct/Tailscale/Secure Link routes remain independent fallbacks, with no silent
plaintext downgrade. Reach is disabled by default, marked experimental, and
selected only after supported routes are unavailable.
See [`docs/security-broker-transport.md`](security-broker-transport.md).

As of **v3 (ADR 24)**, the QR can also carry an ordered list of **endpoint candidates** (`lan` / `tailscale` / `public` / operator-defined roles). A single pairing covers every network the phone might be on — the phone picks the highest-priority reachable candidate at connect time and re-probes on network change. The single-URL top-level fields still appear in v3 QRs for backward compatibility; old phones ignore `endpoints` via `ignoreUnknownKeys = true`, new phones prefer `endpoints` and fall back to the top-level URL when the array is absent. See [`docs/remote-access.md`](remote-access.md) for the operator-facing setup per mode.

```
1. Operator runs `hermes pair` (or `/hermes-relay-pair`) on the Hermes host,
   optionally with --ttl <duration>, --grants terminal=7d,bridge=1d,
   --mode {auto,lan,tailscale,public} (default auto), --public-url <url>,
   and optionally --dashboard-url <url>.
2. The pair command reads the API server config (host/port/key) from
   ~/.hermes/config.yaml or ~/.hermes/.env, and auto-detects candidate
   endpoints: LAN IP via routing lookup; Tailscale hostname via
   tailscale.status() when the CLI is present; public URL from
   --public-url when provided. Generated defaults order secure candidates
   (Hermes Secure Link when enabled, then Tailscale/public TLS) before plain LAN,
   with 0 = highest. --mode lan/tailscale/public emits only that candidate.
3. If a relay is reachable at localhost:RELAY_PORT (default 8767):
   a. Mint a fresh 6-char code from A-Z / 0-9
   b. Compute the transport hint (wss / ws) from the relay's TLS config
   c. POST /pairing/register { code, ttl_seconds, grants, transport_hint,
      endpoints? } (loopback only — the relay clears all rate-limit
      blocks on success so stale blocks don't prevent legitimate re-pair)
   d. Build the payload dict (`hermes: 3` when endpoints present, else
      `hermes: 2`), HMAC-SHA256-sign it with the host-local secret at
      ~/.hermes/hermes-relay-qr-secret (auto-created, 32 bytes, mode
      0o600), attach as `sig` field. Canonicalization preserves array
      order — priority is meaningful, not alphabetic.
4. Render QR + plain-text block (includes "Pair: for 30 days" or
   "Pair: indefinitely" + per-channel grant labels + per-endpoint role
   chips when endpoints are present).
5. Phone scans the QR → parses HermesPairingPayload (see §3.3.1).
6. Phone stores the API server URL + key. When endpoints are present,
   stores the ordered candidate list in PairingPreferences; otherwise
   synthesizes a single priority-0 `role: lan` (or `role: tailscale`
   when the top-level host matches `100.64.0.0/10` / `.ts.net`) entry
   from the top-level fields for forward-compat.
7. SessionTtlPickerDialog opens with the QR's operator-chosen TTL
   preselected (or default 30d on wss/Tailscale, 7d on plain ws). User
   picks: 1d / 7d / 30d / 90d / 1y / Never. Never-expire warns inline
   but is always selectable — user intent is the trust model.
8. Phone opens WSS to the relay with the pairing code + confirmed
   ttl_seconds + grants in the first system/auth envelope.
9. Relay consumes the code (host-registered metadata wins over phone-sent
   metadata — operator policy is authoritative), creates a Session with
   the resolved TTL + grants + transport_hint, returns session token +
   expires_at + grants + transport_hint in auth.ok.
10. Phone stores the session token in the Android Keystore (StrongBox-
    preferred) with fallback to EncryptedSharedPreferences on older /
    unsupported devices. Ordinary WSS routes use the existing first-connect
    TOFU pin. Hermes Secure Link WSS instead requires the QR-carried SPKI pin before
    its first health or WebSocket request.
11. Future connections use the session token directly. Rate limiter,
    session expiry, and per-channel grants all enforced at the relay.
12. Session expires on ttl_seconds (or never); individual grants may
    expire sooner. Paired Devices screen lists all devices with per-row
    revoke.
```

**Old API-only QRs** (no `relay` block, no `hermes` field, or `hermes: 1`) still parse — the phone just skips the relay setup step and can be paired against a relay later via Settings. **v1 QRs with a relay block** (no TTL / grants / sig fields) still parse via `ignoreUnknownKeys`; the phone treats missing TTL as "prompt the user with defaults". **v3 QRs with an `endpoints` array** (ADR 24) also parse on v0.6.x and earlier clients — they ignore the array and keep using the top-level fields. New clients prefer `endpoints` and fall back to the top-level fields when absent.

**Re-pair explicitly resets transport trust** for the target host. Ordinary TLS
routes retain their existing TOFU behavior. A Hermes Secure Link route is stricter:
its SPKI pin must arrive in the operator-reviewed QR before the first request,
and a certificate or authority rotation requires another explicit re-pair. A
TLS or pin failure never silently downgrades that route to HTTP/WS.

**Phase 3 (bridge)** will introduce a symmetric phone-generates-code, host-approves flow. The `POST /pairing/approve` route is stubbed in this cycle — same wire shape as `/pairing/register`, same loopback gate — with a `# TODO(Phase 3)` pointing at the pending-codes store + operator approval UI that still needs to be built.

Biometric gate on the app side for terminal access (fingerprint/face) remains planned.

#### 3.3.1 QR Wire Format — `HermesPairingPayload` (v3)

```json
{
  "hermes": 3,
  "host": "hermes.tail-scale.ts.net",
  "port": 8642,
  "key": "api-bearer-token",
  "tls": true,
  "relay": {
    "url": "wss://hermes.tail-scale.ts.net:8767",
    "code": "ABCD12",
    "ttl_seconds": 2592000,
    "grants": { "terminal": 2592000, "bridge": 604800 },
    "transport_hint": "wss"
  },
  "endpoints": [
    { "role": "tailscale", "priority": 0,
      "api":   { "host": "hermes.tail-scale.ts.net", "port": 8642, "tls": true },
      "relay": { "url": "wss://hermes.tail-scale.ts.net:8767", "transport_hint": "wss" } },
    { "role": "public",    "priority": 1,
      "api":   { "host": "hermes.example.com", "port": 443, "tls": true },
      "relay": { "url": "wss://hermes.example.com/relay", "transport_hint": "wss" } },
    { "role": "lan",       "priority": 2,
      "api":   { "host": "192.168.1.100", "port": 8642, "tls": false },
      "relay": { "url": "ws://192.168.1.100:8767", "transport_hint": "ws" } }
  ],
  "sig": "base64url-hmac-sha256"
}
```

- `hermes` — payload version. `1` is the legacy shape (no new fields); `2` is set when any v2-only field (`ttl_seconds`, `grants`, `transport_hint`) is present in the `relay` block; `3` is set when `endpoints` is present (ADR 24). All three versions parse on the current Android client.
- `endpoints` — **optional** ordered list of endpoint candidates. When present, the phone uses these in strict-priority order (0 = highest) and re-probes reachability on network change. When absent, the phone synthesizes a single priority-0 candidate from the top-level `host`/`port`/`tls` + `relay.url`/`transport_hint` fields. `role` is an open string (known values `lan` / `tailscale` / `public` / `plugin_proxy` / `outbound_broker` get styled UI; `plugin_proxy` is the compatibility wire role for Hermes Secure Link and `outbound_broker` is the wire role for Hermes Reach, while anything else renders as "Custom VPN (<role>)"). Entries can carry independently optional `api`, `dashboard`, `relay`, Secure Link, and Reach routing metadata; pairing code, TTL, and grants stay at the top level because they are per-pair artifacts. Full schema in ADR 24, the Secure Link trust contract in [`security-native-proxy.md`](security-native-proxy.md), and the Reach broker contract in [`security-broker-transport.md`](security-broker-transport.md).
- Top-level fields (`host`/`port`/`key`/`tls`) configure the direct Hermes API Server. Unchanged since v1.
- `relay` — **optional** and nullable. Present only when the pair command found a running relay and successfully pre-registered a pairing code with it.
- `relay.url` — full WebSocket URL (`ws://` for dev, `wss://` for production).
- `relay.code` — 6-char one-shot pairing code from `A-Z / 0-9`. Expires 10 minutes after registration.
- `relay.ttl_seconds` — **optional**. Operator-chosen session lifetime in seconds. `0` means never expire. When present, the phone's TTL picker preselects this value; when missing, the phone picks a default based on transport hint (wss → 30d, ws → 7d). The user always confirms via the picker dialog.
- `relay.grants` — **optional**. Per-channel expiries in seconds-from-now. Map keys: `"terminal"`, `"bridge"`. Each grant is clamped server-side to the overall session TTL — a grant cannot outlive its session. Default caps if unspecified: terminal 30 days, bridge 7 days.
- `relay.transport_hint` — **optional**. `"wss"` or `"ws"`. Used by the phone as the default for the transport security badge and to compute the TTL picker's default option.
- `sig` — **optional**. Base64 HMAC-SHA256 of the canonicalized payload (sort_keys=True, separators=(",", ":"), `sig` field excluded from canonical form). Computed with a host-local secret at `~/.hermes/hermes-relay-qr-secret`. Phones parse and store `sig` but **do not verify it yet** — full verification requires a secret distribution mechanism the protocol doesn't yet define.
- The Android parser uses `kotlinx.serialization` with `ignoreUnknownKeys = true`, so future fields can be added without breaking older app builds. `RelayPairing.ttlSeconds` / `grants` / `transportHint` are all nullable with defaults.

Implementation references:
- Server-side payload builder + CLI flags: `plugin/pair.py` → `build_payload(sign=True, endpoints=..., dashboard_url=...)` / `pair_command()` / `parse_duration()` / `parse_grants()`; `--mode {auto,lan,tailscale,public}` + `--public-url <url>` + `--dashboard-url <url>`
- Server-side HMAC: `plugin/relay/qr_sign.py` → `canonicalize` / `sign_payload` / `verify_payload` / `load_or_create_secret` — canonical form preserves `endpoints` array order and role strings verbatim
- Phone-side endpoint model: `app/src/main/kotlin/.../data/Endpoint.kt` → `EndpointCandidate` / `ApiEndpoint` / `RelayEndpoint` / `displayLabel()`
- Phone-side parser: `app/src/main/kotlin/.../ui/components/QrPairingScanner.kt` → `HermesPairingPayload.endpoints` + v1/v2 synthesizer
- Phone-side endpoint store: `app/src/main/kotlin/.../data/PairingPreferences.kt` — per-device endpoint list
- Phone-side network-aware switching: `app/src/main/kotlin/.../network/ConnectionManager.kt` → `resolveBestEndpoint()` + `NetworkCallback`
- Phone-side TTL picker: `app/src/main/kotlin/.../ui/components/SessionTtlPickerDialog.kt`
- Relay registration endpoint: `plugin/relay/server.py` → `handle_pairing_register` (see §6 for details), accepts optional `endpoints` in body
- Dashboard pairing endpoint: `plugin/relay/server.py` → `handle_pairing_mint` mints a fresh code and returns a signed payload in this exact shape; regression-tested against the Android parser in `plugin/tests/test_pairing_mint_schema.py`. The endpoint is loopback-only and surfaced to the dashboard via `plugin/dashboard/plugin_api.py` at `POST /api/plugins/hermes-relay/pairing`.

### 3.4 Security

| Layer | Implementation |
|-------|---------------|
| Transport (default) | WSS / TLS 1.3 (**preferred**) |
| Transport (opt-in) | Plain `ws://` — gated on `InsecureConnectionAckDialog` consent + reason picker (LAN-only / Tailscale or VPN / Local dev). Reason is displayed, not enforced — operator intent is the trust model. |
| Transport indicator | `TransportSecurityBadge` in Settings + Session sheet + Paired Devices card. Three states: 🔒 secure / 🔓 insecure with reason / 🔓 insecure unknown. |
| Pairing (host → phone) | `hermes pair` / `/hermes-relay-pair` → `POST /pairing/register` (loopback-only) → QR embedded in operator's terminal or chat. |
| Pairing (phone → host, Phase 3) | Stubbed at `POST /pairing/approve` — same wire shape, same loopback gate. Real UX pending bridge work. |
| Session lifetime | User-selected at pair: 1d / 7d / 30d / 90d / 1y / **never**. Never is always selectable; operator intent is the trust model. |
| Per-channel grants | One session token carries per-channel expiries for `chat`, `terminal`, `bridge`, `tui`, and split voice grants (`voice:config`, `voice:stt`, `voice:tts`). Grants are clamped to session lifetime. |
| Auth envelope | `{pairing_code, ttl_seconds, grants, device_name, device_id}` for pairing mode; `{session_token, device_name, device_id}` for session-mode re-auth. Host metadata wins over phone metadata when both are present. |
| `auth.ok` response | `{session_token, expires_at, grants, transport_hint, profiles, server_version}`. `math.inf` expiries serialize as `null`. |
| Rate limiting | 5 auth attempts / 60s → 5-min block. **`/pairing/register` clears all blocks on success** so legitimate re-pair after a relay restart works immediately. |
| Token storage | `SessionTokenStore` — `KeystoreTokenStore` (StrongBox-preferred via `setRequestStrongBoxBacked`) with fallback to `LegacyEncryptedPrefsTokenStore` (TEE-backed `EncryptedSharedPreferences`). One-shot lossless migration on first launch post-upgrade. `hasHardwareBackedStorage` is surfaced in UI; fallback, self-heal, and temporary in-memory degradation produce secret-free Diagnostics events. |
| Cert pinning | TOFU via `CertPinStore` — SHA-256 SPKI fingerprint recorded per `host:port` on first successful wss connect. Subsequent connects verify via OkHttp `CertificatePinner`. Pin wiped explicitly on QR re-pair (`applyServerIssuedCodeAndReset`). Plain ws:// short-circuits pinning entirely. |
| QR integrity | HMAC-SHA256 over canonicalized payload. Host-local secret at `~/.hermes/hermes-relay-qr-secret`. Phone parses + stores the signature but does NOT verify yet (secret distribution TBD). |
| Tailscale detection | Informational only — `tailscale0` interface + `100.64.0.0/10` CGNAT + `.ts.net` hostname checks. Displayed as a Connection-section chip. Does NOT auto-change TTL defaults. |
| Tailscale helper (first-class) | `plugin/relay/tailscale.py` + `hermes-relay-tailscale` CLI (ADR 25). Publishes the loopback relay over the tailnet via `tailscale serve --bg --https=<port>`; managed TLS + tailnet ACL identity. Optional, graceful-absent when the binary isn't installed. Auto-retires when upstream PR #9295 lands. See [`docs/remote-access.md`](remote-access.md). |
| Multi-endpoint pairing | Single QR carries an ordered list of `role: lan/tailscale/public/...` candidates with strict-priority selection (ADR 24). Phone re-probes reachability on every network change. Per-candidate `transport_hint` drives the plaintext-`ws://` consent dialog. |
| Device revocation | Paired Devices screen → `GET /sessions` (tokens masked to 8-char prefix) / `DELETE /sessions/{token_prefix}` (self-revoke allowed, wipes local state + redirects to pair flow). Any paired device can revoke any other — trade-off documented in ADR 15. |
| Session policy updates | `PATCH /sessions/{token_prefix}` is self-targeted and reduction-only for normal Relay bearers. Extending a lifetime, adding or lengthening grants, or changing another session requires a fresh operator-approved pairing flow. |
| Terminal gate | Biometric/PIN required before terminal access (planned). |

---

## 4. Tech Stack

### Android App
- **Language:** Kotlin 2.0+
- **UI:** Jetpack Compose + Material 3 (Material You dynamic theming)
- **Navigation:** Compose Navigation (type-safe)
- **WebSocket:** OkHttp 4.x (already in upstream, supports `wss://`)
- **Terminal:** WebView + xterm.js (v1), consider native Compose terminal later
- **Serialization:** kotlinx.serialization (replace Gson — faster, type-safe)
- **Storage:** Android Keystore (StrongBox-preferred via `KeystoreTokenStore`) + `EncryptedSharedPreferences` legacy fallback via `LegacyEncryptedPrefsTokenStore`; DataStore (preferences + TOFU cert pins)
- **DI:** Manual dependency injection (no Hilt). Constructor-wired ViewModels, process-singletons where needed. Decided lean because the graph is small and dependencies are explicit.
- **Biometric:** AndroidX Biometric
- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 35
- **Compile SDK:** 37

### Server (Relay)
- **Language:** Python 3.11+
- **Framework:** aiohttp (matches existing relay)
- **Terminal:** `asyncio` + `pty` module for PTY, `libtmux` for session management
- **Chat proxy:** HTTP client to Hermes WebAPI (localhost:8642 or direct `run_agent`)
- **Port:** 8767 (WSS). The legacy standalone bridge relay on 8766 was retired in Phase 3 Wave 1 (2026-04-12) — the bridge channel is now multiplexed alongside chat, terminal, voice, and media on the unified relay.
- **TLS:** Let's Encrypt via certbot, or reverse proxy through Caddy/nginx

### CI/CD (GitHub Actions)
- **CI:** Lint (ktlint) → Build → Test → Upload APK artifact
- **Release:** Tag-triggered → version validation → signed APK → GitHub Release
- **Patterns from ARC:** Concurrency groups, matrix builds, version sync check

### Gateway contract certification (on demand)

Gateway/session/streaming/reconnect behavior uses the client-neutral scenario
fixture, current-upstream source conformance, Android instrumentation, and
optional physical ADB evidence described in
[`docs/gateway-contract-testing.md`](gateway-contract-testing.md). The layers
separate protocol, client state, rendered lifecycle, and device/runtime proof.
They are not scheduled automatically.

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
- **Top bar and Profile Shelf (three-layer agent model).** Layout from left to right:
  1. **Connection chip** — tap to open `ConnectionSwitcherSheet` (all paired servers + health indicator). Auto-hidden when you only have one Connection. See `docs/decisions.md` §19.
  2. **Agent avatar/name region** — tap to expand or collapse the Profile Shelf immediately below the app bar. With only one visible effective identity, the shelf stays hidden and the same tap opens Agent Passport.
  3. Remaining top-bar actions (session drawer hamburger, ambient toggle, etc.).
- **Profile Shelf** — the active avatar/name/chevron capsule opens Agent Passport; inactive profiles are avatar-only 48 dp switch targets; a fixed overflow opens the canonical full switcher also used by Passport. The shelf scrolls horizontally, honors `ProfilePresentationStore` ordering/hidden preferences, keeps a hidden selected profile disclosed, and disappears when only one visible identity remains. Hermes-owned avatars win by default, followed by device-local icons and display initials; an explicit per-connection/profile **This phone only** override lets the local icon win without mutating Hermes. Server default uses a home glyph and remains distinct from a profile literally named `default`.
- **Hermes-owned profile identity** — on a current Gateway, Android calls `profiles.list {include_sessions:false}` and consumes bounded `ui_meta` plus `has_avatar`. A true avatar flag triggers `profiles.get_asset`; validated server bytes are cached per connection/profile and win over the older device-local `ProfileIconStore`. A false flag or successful clear removes only the server cache. Refresh generations and exact connection identity prevent a late fetch from repainting another connection or resurrecting a cleared avatar.
- **Separated shared and phone avatar controls** — **Shared across Hermes** directly selects or removes the upstream `profiles.set_asset` avatar without changing local presentation. **This phone only** is a persisted per-connection/profile override populated from a phone image or Relay-host `GET /api/profiles/{name}/avatar`; selecting an image enables the override, while disabling it immediately returns to the shared avatar. Phone-local PNG/JPEG/WebP/GIF bytes are magic-checked and capped at 8 MB; Coil renders animated GIF/WebP consistently anywhere the profile icon appears. The shared picker accepts any image Android can decode, applies its display orientation, and downscales/re-encodes when necessary while retaining the exact upstream PNG/JPEG/WebP and 2,000,000-byte storage contract. Pet sheets and Sphere skins never enter `ui_meta` or profile assets.
- **Upstream animated pets** — the agent sheet consumes the profile-scoped Gateway `pet.info`, `pet.gallery`, `pet.select`, and `pet.disable` contracts. Android caches the bounded PNG/WebP sprite sheet by connection, effective profile, and `spritesheetRevision`; it sends `knownRevision` on refresh and reuses the existing bounded pet renderer for the returned geometry, row taxonomy, and activity states. The active upstream pet becomes the phone companion unless the user explicitly selected a phone-local floating pet. Selection and disable write Hermes `display.pet.*` state and therefore follow the profile across current Hermes surfaces; a method-not-found response leaves older hosts on the established local pet flow.
- **Profile creation** — Manage uses `profiles.create` on current Gateways and labels authentication as shared sign-in, copied credential snapshot, or isolated/no-copy. Android serializes `mirror_credentials` and `share_auth` explicitly, reports best-effort SOUL/model/credential results without claiming full success, and never receives or logs credentials. The user may explicitly enable the authenticated Dashboard create route as an older-host fallback only for the legacy shared/default choice; explicit isolation never degrades to an ambiguous older mutation.
- **Deletion boundary** — Hermes exposes no `profiles.delete` Gateway RPC. Android continues to delete profiles only through authenticated Dashboard `DELETE /api/profiles/{name}`.
- **Profile switch lifecycle** — selecting an inactive profile never changes Hermes' sticky server default and never hot-swaps a live session. Android switches connection/profile context, restores that profile's last session only from the compatible Gateway or SSE transport slot, or opens a fresh draft. Gateway turns detach and reconcile in their original durable session; live SSE switching is disabled. Model/provider, personality, reasoning, approval, Fast, and YOLO state are cleared before destination session truth re-seeds them.
- **Bot Mode workspace** — the session drawer exposes one entry into a separate full-screen messenger surface; it does not add Bot or group rows to the ordinary session taxonomy. Android refreshes every saved Dashboard/Gateway with bounded concurrency, preserves last-good rows as visibly offline, and collapses duplicate routes by upstream `install_id` before assigning source-qualified handles. Every Bot carries an immutable `(connectionId, profile)` owner; labels, installation metadata, and the currently resolved URL are presentation/routing data rather than identity. All gateways and one-gateway filters never mutate the foreground connection.
- **Canonical Bot Chat** — each individual row resolves the exact hidden session titled `Bot Chat` on its owning Gateway. Lookup failure is not absence, so Android creates and materializes the lazy row with `session.title` only after an authoritative empty exact-title result. The dedicated Bot Chat destination retains that route's pooled Gateway client, loads history through the same connection/profile Dashboard, sends only through Gateway, and returns directly to Bot Mode without rebinding Standard Chat or the global connection. `/new` or `/reset` compacts the canonical conversation instead of forking it. The route pool mints a fresh WebSocket ticket per dial, includes the immutable profile in the WebSocket URL, isolates credentials by exact trusted connection origin, and tears down only the removed connection's clients.
- **Bot group projection** — Android merges the bounded `ui_meta["hermes-bots-groups"]` v3 projection across gateways by durable room identity and newest revision. Rooms and recent messages are visibly read-only; Android does not create, rename, disband, join, send, coordinate member turns, or become a second room-log authority. Binary room images are ignored at this metadata boundary.
- **Session drawer** (swipe from left or hamburger icon) — session list with title, timestamp, message count. Create, switch, rename, delete, pin/unpin, and archive/restore. A profile switch marks the replacement list loading before clearing the previous profile's rows and keeps that state until the exact-profile fetch settles, so an empty-state claim never flashes before server truth arrives. The process-owned conversation binding is the single connection/profile/session identity for Chat; selecting an All Profiles row atomically makes its owner the selected agent and persists that profile/session, while merely browsing All Profiles changes no agent state. Lifecycle or locale-driven Activity recreation cannot replace an explicit binding with stale persisted state, and asynchronous list/history/mutation work is accepted only for the binding's exact namespace. A profile lock hides All Profiles and rejects stale/deep-linked cross-profile opens. The All Profiles browser mode otherwise survives Activity state restoration and refetches its rows after recreation. Pin and archive are durable upstream session fields loaded and patched through the owning connection/profile's Dashboard session API; Android does not keep a second local flag registry. Archived rows are requested explicitly so they remain restorable after recreation. Failed mutations roll back the optimistic row, while refresh and deletion reconcile from server truth. When a persisted title is absent, use upstream's first-user-message `preview`, matching the Hermes Desktop session picker; show "Untitled" only when neither value exists.
- **Authoritative session activity** — one composite registry keyed by connection, normalized profile, and durable session id drives the drawer, filters, grouping, animation, accessibility, and the visible composer. Exact pending approval/clarify/sudo/secret/MCP requests produce **Needs input**; the Gateway's process-wide `session.active_list` supplies **Starting**, **Working**, and **Idle**; exact terminal or `session.info {running:false}` can settle the matching generation. Because active-list rows normally have no profile metadata, Android assigns a row only through exact foreground/detached ownership already held by that client, or explicit profile metadata if a future upstream sends it. A bounded REST directory never proves global uniqueness. Unresolved rows create no status. Resolved rows from a partial snapshot may update their exact owners, but disappearance settles a scope only when the successful process-wide snapshot was completely and unambiguously resolved for it. Restart/checkpoint recovery is **Checking**; a failed or unsupported live refresh is **Unavailable**, never inferred Idle. REST `is_active` remains recency metadata only. `process.list` may add a separate **Background work** indicator and never keeps the parent conversation Working. Old socket generations, bare session ids from another profile, and delayed snapshots cannot revive newer settled state.
- **Concurrent Gateway chats** — switching sessions, profiles, drafts, or Threads detaches the visible turn without sending `session.interrupt`; each running chat keeps a connection/profile/session-scoped checkpoint and reattaches to its live Gateway session when reopened. Explicit Stop still interrupts. SSE fallback stays single-stream and cancels on navigation.
- **Queued Gateway follow-ups** — every local queued item is immutably scoped to its originating connection, profile, stored session, transport, and run generation; only that run's completion can make it eligible, and switching sessions shows only that session's queue. Restored text queues retain the same scope, while unavailable/deleted destinations and non-restorable attachment queues fail visibly instead of following the current composer. Drained messages add `queued: true` to `prompt.submit`; ordinary sends omit the field. Authoritative submit rejections (`4004`, `4018`, `4028`, `4029`, `4030`, `4090`, `5008`, `5070`, and `5071`) preserve the server message and never fall through to API-server SSE.
- **Durable composer drafts** — each connection/profile/session owns one app-private draft containing text, quote/edit context, and pending attachment bytes. Metadata and content-addressed blobs live under Android's no-backup directory, are capped at 64 drafts and 128 MB of retained blobs outside the active draft, flush when Chat backgrounds, and are removed after a successful send. Session/profile/connection navigation saves the previous owner before restoring the destination; an opened cross-profile session uses its actual owning profile rather than the global picker.
- **Large paste review** — a default-on Chat setting converts any single insertion of at least 5,000 characters into a visible `pasted-text.txt` attachment before the normal message-length limit rejects it. Gateway uses upstream `file.attach`; API-server SSE and proactive Thread paths materialize the same UTF-8 text into the outgoing prompt and remove only the synthetic attachment from that transport, so the behavior never requires Relay or silently drops content.
- **Complete transcript reads** — Android requests the API-server and profile-scoped Dashboard message routes with an explicit 500-row contract. User-visible history pages oldest-first until complete, preserves legacy unpaginated envelopes, and stops with a clear 50,000-message/32 MB client safety bound. Short dropped-stream recovery polls one explicit latest page while the known transcript fits that window; longer histories retain complete paging so positional edit/recovery anchors cannot shift. Edit-and-regenerate submits always send `confirm_truncate`; ordinal zero alone also sends `confirm_empty_truncate`. Gateway history `row_id` values are retained as durable rewind targets (never UI keys), sent alongside the visible-user ordinal, and rebound from `survivor_user_row_ids` after each truncating rewrite. Histories with no row IDs remain ordinal-compatible with older Hermes; a mixed history fails closed when the selected row lacks its durable ID instead of guessing by position.
- **Bounded Gateway resume** — an authoritative `session.resume` rejection such as `4130` remains visible with the server's export/config guidance. Android does not create a replacement session, retry, or fall through to API-server SSE, so an oversized lineage cannot silently become a context-free turn.
- **Gateway command privacy** — `command.dispatch` skill/send results render the server's bounded `display` text (or the literal command when absent). Expanded skill bodies remain transport-only and are not copied into bubbles, titles, retries, or recovery checkpoints.
- **Chat view** — assistant rows use the native incremental Markdown state from first token through completion. The renderer retains stable AST node identities while parsing the provisional tail with the same typography and components, so paragraphs, lists, links, fences, and tables do not hard-swap from a plain-text tree at completion; a non-append authoritative reconciliation starts one fresh renderer generation. Bottom-follow is user-owned: explicit sends, returning to the bottom, or the latest affordance arm it; a deliberate read-up cancels it across later stream, queue, refresh, and resume events. Live growth and Markdown settlement share one cancellable viewport-bounded follow owner rather than bubble-size or transcript-distance animation. Tool call cards retain Off/Compact/Detailed display modes.
- **Three independent visual roles** — the profile image or letter fallback identifies the agent and appears on the first assistant message in a group; the optional Sphere is an ambient background visualization; and an optional pet is an app-level floating companion. A pet never replaces profile identity or the Sphere.
- **Floating pet companion** — one root-level overlay host preserves the selected pet across in-app navigation without reserving transcript, message, or control-bar layout space. The 60 dp art (50 dp with the IME or on a short screen) has a persisted **60–120%** size control, default 100%; that default equals the previous 125% physical size, while legacy saved values are rebased to retain their rendered size. Keyboard compaction changes only the footprint: typing does not dim, pause, or disable pet playback, roaming, tapping, or dragging. One multiplier drives the art, complete touch target, collision footprint, perch eligibility, and route clearance; larger pets skip terrain that cannot safely fit them. Only the pet target intercepts input and the surrounding positioning layer is click-through. A tap waves and opens the pet menu. Long hold lifts into `held`; direct dragging follows the finger anywhere inside the visible overlay without terrain or obstacle projection. On release, the pet visibly falls to the nearest valid measured surface below, or the nearest remaining safe surface when none is below, while preserving the roaming preference. Edge plus normalized vertical position keeps the durable fallback home stable across rotation, resizing, and RTL. The menu and TalkBack custom actions can move, reset, configure, pause roaming, or hide it.
- **Opt-in, route-aware roaming** — off by default. Screens deliberately register curated live-measured ledges and obstacles; Android does not scan arbitrary elements or infer safe geometry from accessibility semantics. Chat registers the composer, newest settled bubble, and eligible older visible message rails, Terminal registers its extra-keys toolbar, root Settings registers its summary/category card tops, Appearance registers its section-card tops, and Settings/Appearance/About expose the persistent bottom status strip. When settled at Chat's bottom, the pet prefers a text-free side pocket beside the latest bubble, uses the raised bubble top when its scaled footprint cannot fit beside it, then falls back to the outer composer corner; every transition starts at the live coordinate instead of teleporting. Curated non-chat surfaces use the same bounded planner for a multi-level out-and-back tour; a pet that lands on an upper card may route downward, while its numbered debug loop shows the exact selected stops and reverse return. Settings terrain can expand dynamically through the same measured registry, but each screen/card/header remains an explicit safe opt-in with current scroll/modal state—every arbitrary element never becomes terrain automatically. Registered bubble interiors remain protected obstacles: autonomous movement may use only a derived outer edge, top rail, or touchdown with exact collision-validated endpoints. When no exact bounded route validates, the pet waits at its current safe point instead of projecting or snapping onto invented terrain. If layout has already placed it inside a newly measured obstacle, a separately labeled recovery may use only the shortest bounded straight egress to a clear edge, then stops without authorizing further travel. No route inserts a spacer or takes text layout space. Chat's scroll-to-bottom button and Terminal's jump-to-latest control trim or block only the rail segments they occupy. During active scrolling, autonomous travel pauses while the pet lifts slightly and follows its ledge's live measured position. If support leaves the safe viewport, the pet retains its last safe screen coordinate in the falling state and, after scrolling settles, lands on the nearest valid visible lower rail or jumps to the nearest remaining rail when an exact recovery route exists. Recovery cleanup is cancellation-safe, so transient controls or changing measurements cannot leave roaming gated. App-wide interaction-layer ownership suppresses the companion while any platform dialog/modal window or registered same-window overlay is active; returning to a supported route replans from the live position. Other routes keep it docked.
- **Response-bubble excursion** — after a response settles and the selected temperament delay elapses, the pet walks along the composer toward a proven-clear route. The journey planner may first walk along the current safe rail to the exact launch point used by the collision graph; it then backtracks past a dead-end foothold rather than letting one legal edge mask another complete chain. A nearby response uses the direct outer-gutter excursion. A farther visible response uses settled message-top stepping stones and temporary side-edge hop footholds; obstacle-adjacent transfers retain the 210 dp budget, while a proven-clear blank-space gap may use one distance-scaled ballistic transfer up to 360 dp instead of inventing a midair landing point. Eligible visible bubbles contribute edge footholds so several ordinary-height bubbles can bridge a larger cumulative gap. These use the visible sprite footprint when the larger accessibility target cannot fit the gutter. An exterior lane is preferred, while a viewport-filling bubble may expose one sprite-wide inset edge lane. Side footholds are traversal-only: the pet uses jump/fall motion without walking, idling, settling, or accepting input there, and the complete route back to persistent terrain must validate before departure. The newest response gets the full cross/pause/wave; the pet may then inspect one, two, or three successively older visible bubble rails for Calm, Balanced, or Playful temperament before retracing the same bounded route to the composer. A wide additional stop includes a short measured-rail walk. A narrow measured bubble that visibly supports at least 35% of the pet width may instead contribute one centered touchdown point: the pet lands briefly and continues, but never walks or idles there. Outside the selected transient edge lane, message content remains protected; registered controls retain full touch-target clearance, and a bubble is skipped when its visual lane or complete route cannot safely support the sequence.
- **Behavior and motion director** — direct interaction wins over agent activity, which wins over a pending response visit, autonomous roaming, and idle reactions. Agent activity therefore cannot be overwritten by locomotion. Horizontal travel uses directional walking clips with distance quantized to complete walk cycles; turns pause briefly. Vertical movement uses distance-scaled timing, anticipation, `jumping` to the apex, `falling` through descent, an altitude-responsive shadow, and a landing squash. Cross-level transfers are length-capped on every route, including Settings, so a screen with no explicitly registered safe intermediate level stays on its current rail rather than jumping through UI. Idle patrols alternate short hops, waves, and rests without turning response bubbles into arbitrary roaming terrain. **Calm**, **Balanced** (default), and **Playful** change response-visit, patrol, and idle-reaction cadence and cap older-bubble exploration at one, two, or three stops; safety, foreground, activity, scrolling, dialog, reduced-motion, and accessibility gates remain authoritative.
- **Activity versus locomotion** — idle/thinking/writing/error, tool-burst, and completion choose the agent-state clip. Directional `walking-*`/`running-*` clips describe only physical screen travel and are eligible only while the agent is idle; agent activity always wins. Microphone/TTS presentation remains Sphere/voice rather than a roaming-pet surface on Android.
- **Input bar** — text field with 4096 char limit, `/` palette button, send button, stop button during streaming. Inline autocomplete on `/` keystroke + full searchable command palette (bottom sheet). Commands sourced from: 29 gateway built-ins, dynamic personalities from `config.agent.personalities`, and server skills from native `GET /v1/skills`.
- **Outbound attachments** — Files, photos, camera captures, and clipboard images are read through a bounded encoder that enforces the configured limit before an oversized provider stream can fill memory. Gateway sends establish or resume the exact destination session first, then upload images through `image.attach_bytes` (with the legacy dotted-name fallback), PDFs through `pdf.attach`, and other files through `file.attach`; only after every upload succeeds does `prompt.submit` run. Generic files use the Gateway-returned `@file:` reference. An unsupported or interrupted attachment RPC fails the user turn visibly and never falls through to an SSE route that would omit the file. Queued follow-ups retain this same destination, upload, and `queued:true` contract.
- **Reasoning effort** — choices follow the selected upstream provider/model identity. When upstream or the optional Relay capability overlay reports an exact model-specific list, Android shows only those available levels. Otherwise it shows the standard advisory set (`none`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max`, `ultra`). An explicit upstream `reasoning: false` suppresses the control unless a higher-precedence exact list exists. Missing Relay configuration, pairing, route support, or network access never blocks model selection or chat. A server-confirmed current effort may remain visible as session truth even when it is not selectable for the next request. See ADR 45.
- **Model-selection consent** — Every Gateway model transition, including Server default, uses upstream `config.set` as a preflight. A fresh draft first creates a profile-bound default session without raw `model`/`provider` fields, preventing `session.create` from bypassing the guard and preventing sessionless global writes. A named-profile draft is usable only when the create result confirms the exact `info.profile_name`; missing or different ownership fails closed before `config.set`. When Hermes returns `confirm_required`, Android restores the previous selection, displays the exact server `confirm_message` (including cost and data-training policy warnings), and applies the model only through a second request carrying `confirm_expensive_model: true`. A profile or session change invalidates the pending confirmation. Older hosts that do not return the fields keep the existing one-step selection behavior.
- **Host resource pressure** — Android reads the optional upstream Dashboard `/api/status.memory` and `.disk` blocks during its existing health probe. `elevated` and `critical` values, plus a server-reported suspected out-of-memory restart, produce a persistent in-app warning about chat continuity and save risk. Android uses the upstream classifications as-is, adds no client thresholds or sampling, sends no telemetry, and stays quiet when older hosts omit the blocks.
- **Empty state** — Logo + "Start a conversation" + suggestion chips that populate input
- **Agent Passport — profile inspection/configuration** — upstream Hermes profiles are selected from the Profile Shelf or the Passport's shared full switcher. Passport retains identity customization, model/personality/reasoning/safety configuration, inspection, and session analytics; it is not a second profile-picker implementation. See `docs/decisions.md` §21 and ADR 48.
- **Agent sheet — Personality section** — personalities fetched from `GET /api/config` (`config.agent.personalities`). Shows server default (from `config.display.personality`) + all configured. Active personality name shown on assistant chat bubbles.
- **Agent sheet — Approval controls** — gateway contract v3 exposes the profile-persisted `approvals.mode` policy (`manual` / `smart` / `off`) separately from YOLO. The launch/default profile gets the three-way control; multiplexed non-launch profiles reconcile `session.info.approval_mode` read-only until upstream config RPCs honor profile scope. The existing YOLO switch remains an explicit per-session override and never silently writes profile configuration. Older gateways keep chat and YOLO available while the profile control explains that an upstream update is required.
- **Interactive clarify cards** — ordinary upstream choices retain one-tap submission; `multi_select:true` choices toggle independently and require explicit submission as one JSON-array answer. Open text remains available for an Other answer. Android never invents a clarify deadline when upstream omits timeout metadata: the correlated `clarify.expire` event or an expired response retires the card authoritatively.
- **Streaming dots** — animated pulsing 3-dot indicator replaces static "streaming..." text
- Displays: streaming delta text; quiet thinking/reasoning disclosures that open while live and collapse when settled; consecutive routine tool activity summarized as one live ticker or settled disclosure; standalone lifecycle surfaces for approvals, failures, generated media, file edits, and delegated work; per-message token counts + cost

### Terminal Tab
- **Full-screen terminal emulator** (xterm.js in WebView)
- **Session picker** — attach to existing tmux sessions or create new
- **Toolbar** — Ctrl, Tab, Esc, Arrow keys (soft keys for mobile)
- **Biometric gate** — fingerprint/face required before showing terminal
- Supports: full ANSI color, scrollback, text selection, copy/paste

### Bridge Tab
Shipped in v0.3.0; card hierarchy rewritten in v0.4.1. Rendered by `BridgeScreen.kt` + `BridgeViewModel` in this order:

1. **Master toggle card** (`BridgeMasterToggle`) — headline "Allow Agent Control" switch with a `MASTER` pill and leading "Master switch —" subtitle copy so the parent-gate role is legible at a glance. Gated on accessibility permission being granted; tapping the Switch when Accessibility is not granted surfaces a snackbar ("Accessibility Service must be enabled first.") with an "Open Settings" action that deep-links to `ACTION_ACCESSIBILITY_SETTINGS` rather than silent-dropping the tap. Inline device / battery / screen / current-app rows live in-card (the old standalone `BridgeStatusCard` was dropped from the layout in v0.4.1). Info icon opens a Play-review explanation dialog that also names the "Hermes has device control" persistent notification owned by the master switch.
2. **Agent access cockpit** (`BridgeAgentAccessCard`) — always visible directly below Master. It shows the current preset/custom posture, permanent-grant count, and screen lease/countdown or **Until off**. A first-use **Set up access** sheet offers Read only, Read + confirmed actions, or Custom; presets atomically replace permanent grants, while Custom opens the complete grouped editor. Inspection/control uses a separate sheet with renewable 5 min / 30 min / 2 hr idle limits or an explicitly warned **Until turned off** dedicated-device posture, prerequisite truth, and End now.
3. **Unattended Access card** (`UnattendedAccessRow`, sideload-only) — the single authoritative placement, directly below Agent access. Its opt-in toggle is gated on both Master and active Screen control. With an idle limit, active commands continually refresh the timer and Unattended ends only after inactivity. With **Until turned off**, it remains available through inactivity/reconnect for a dedicated device. First-enable shows the warning dialog covering the security model, selected lifetime, credential-lock limitation, persistent indicators, and revocation. Expiry, End now, or Master-off clears the unattended preference, so a later screen grant cannot silently revive it.
4. **Android access disclosure** (`BridgeAndroidAccessSummaryCard` + `BridgeSelectedAndroidAccessCard`) — summarizes readiness only for capabilities the user selected. Missing permissions for disabled capabilities never nag. Expanding preserves the complete existing `BridgePermissionChecklist` with every status, Settings link, and Test action (Core bridge / Notification companion / Voice & camera / Sideload features). The selected-access card leads with the still-missing requirements before the full matrix. Reads the same `AppPermissionStatusProbe` snapshot as Settings -> Permissions and re-probes on resume.
5. **Advanced divider** — visual separator between ordinary access/readiness and safety power controls.
6. **Safety & full capability editor** (`BridgeSafetySummaryCard` → `BridgeSafetySettingsScreen`) — grouped Read access, Actions, and Timed screen access retain all ten granular toggles. The same screen retains the global blocklist, destructive verbs, 5–120 minute timed-access window, status overlay, and confirmation timeout.
7. **Activity log** (`BridgeActivityLog`) — unchanged scrollable audit history capped at 100 entries with expandable result/status detail and optional screenshot token.

The bridge UI drives — and is driven by — Tier 5 safety-rails (`BridgeSafetyManager`, `BridgeForegroundService`, `BridgeStatusOverlay`, `AutoDisableWorker`). See `docs/decisions.md` and `CLAUDE.md`'s file table for the full wiring.

**Global unattended-access affordance (v0.4.1).** When master + unattended are both on (sideload only), `UnattendedGlobalBanner` renders as a 28dp amber strip at the top of `RelayApp`'s scaffold on every tab — pulsing dot + "Unattended access ON — agent can wake and drive this device" + chevron → tap navigates to Bridge. Theme-aware colours (amber-on-dark in dark mode, dark-amber-on-pale-amber in light). The banner handles visibility while the user is INSIDE Hermes-Relay; the existing WindowManager `BridgeStatusOverlayChip` handles visibility when the app is BACKGROUNDED. See `docs/decisions.md` §18 for the split rationale.

### Settings Tab
- **Active agent card (v0.6.0)** — top-of-screen summary card showing the current Connection / Profile / Personality. Tap navigates to Chat and auto-opens the agent sheet via the `openAgentSheet` nav arg, giving Settings-originating users a one-tap path to change agent context without leaving the flow.
- **Connections** (v0.6.0+) — lists every paired Hermes server with a per-card status chip. Actions: rename (inline), re-pair (reuses `ConnectionWizard` with `connectionId` nav arg), revoke, remove. Add-connection button launches the standard QR flow. Settings briefly treats a paired + disconnected relay as **Connecting** during the reconnect grace window, then promotes it to **Relay unreachable - tap to reconnect** if the live socket does not recover. API / Relay / Session detail sheets include compact sanitized recent-activity tails, and **Settings -> Diagnostics** shows the consolidated app-level API, relay, session, endpoint, voice, Pair-readiness, credential-store recovery, history-failure, and rejected-Send evidence without secrets. See `docs/decisions.md` §19.
- **Connection (single-server settings)** — summary-first detail for one Hermes installation. Dashboard/Gateway health drives standard Chat, Manage, Sessions, and Voice readiness. API fallback and Relay extensions appear as independently optional capabilities. Dashboard/Gateway address and network paths are edited under Routes. Advanced retains only the optional direct API credential, explicit direct Relay endpoint override, and insecure-development controls; missing API or Relay settings never make a healthy Dashboard/Gateway connection look broken. Every Relay QR, enter-code, and show-code method uses the shared connection-scoped Pair flow. Transport security posture and paired-device grants remain visible without leading the normal setup flow with ports or bearer keys.
- **Chat** — Show reasoning toggle, smooth auto-scroll toggle (live-follow streaming, default on), show token usage toggle, app context prompt toggle, tool call display (Off/Compact/Detailed), streaming endpoint selector (`auto` / `sessions` / `runs`), Stats for Nerds (analytics charts)
- **Voice** — route-aware voice engine selector (`Vanilla Hermes` via dashboard audio, `Relay Voice Output`, and experimental `Realtime Agent`), global interaction mode (tap / hold / continuous), silence threshold slider, a final-answer-only speech policy, Auto-TTS toggle, selected-engine cards for dashboard or relay-backed settings, language picker, and a Test Current Engine card. Final-answer-only keeps tool/service progress and intermediate commentary visual while both voice engines wait to speak the settled answer; approvals, confirmation questions, and blocking failures remain actionable. Vanilla Hermes voice depends on Manage/dashboard auth; Relay-backed engines run a fast relay health preflight before uploading audio or opening a realtime provider session so a hung relay surfaces as a connection error instead of an indefinite Thinking state.
- **Notification companion** — opt-in status, "Open Android Settings" action, test notification dump
- **Permissions** — central permission/capability review screen linked from Settings and onboarding. It makes the Vanilla Hermes path explicit ("Chat and Manage" need no Android runtime grant), lists optional camera/microphone/notification access with current status and Android Settings links, and shows sideload-only Device Control requirements only in the sideload flavor.
- **Appearance customization** — each authored preset has an expandable quick editor directly below mode. Accent and Soft/Balanced/Sharp shape changes apply immediately, persist locally in DataStore, and update both the live preview and real app surfaces. **Custom** is the first preset-gallery entry and opens a saved-preset workshop with an always-visible name field, real chat preview, editable Background/Surface/Accent/Text roles, selectable Light or Dark ownership, saved shape, and bounded rename/duplicate/delete actions. Up to 20 normalized presets are stored locally. Auto remains visibly disabled because one custom preset stores one palette rather than paired light/dark palettes. **Reset theme** restores the Relay preset, Auto mode, authored accent, and Soft shape without deleting saved custom presets or resetting independent font, visualization, or pet choices. Derived Material on-colors preserve readable contrast. Typography and density remain in their focused Font and Font size controls.
- **Appearance** — preset-first live preview, theme mode and typography controls, plus independent **Background visualization** (Off/Sphere with built-in or imported declarative JSON skins, or an imported validated pet-format animation) and **Floating pet** (None/installed pet) controls. A background pet-format asset reuses the safe renderer but never gains roaming, placement, or temperament behavior; its persisted selection is separate from the floating companion. Selected-pet controls include playback speed, 60–120% size, stabilization, temperament, opt-in **Walk around the interface**, and **Reset position**. Pets can be installed from a responsive, searchable Petdex thumbnail gallery, imported as a custom Relay `.zip`/single image, or prepared through a guided local-first creator that copies or prefills complete instructions into a fresh chat for user review before submission. Generated files are never auto-installed or shared. The gallery lazily requests upstream-cropped idle frames and animates only the selected installed pet; the global companion is hidden on that dense route so install/source controls remain clear. Petdex browsing prefers its v2 manifest with v1 fallback; full-atlas downloads to Android are user-initiated, exact-host and size constrained, attribution-preserving, validated, and installed atomically for offline use. Runtime decoding holds the previous complete clip during state swaps, caches a bounded set of clips/sheets, and rejects frame sequences or sheets beyond documented decoded-pixel ceilings. Settings cards and thinking controls remain registered obstacles beneath their walkable top rails, and the pet target yields pointer input during active scrolling. Existing combined selections migrate once: the previous main avatar becomes the background selection while any pet also remains available as the floating companion. Profile identity is unaffected.
- **Pet terrain diagnostics** — debug builds expose a default-off Developer Options toggle with a **Pet path inspector** anchored initially below the app header and its Android status-bar inset, leaving all header navigation and menu actions accessible. It starts as a narrow collapsed live-status bar, can be moved only from its grip, snaps to the nearest horizontal edge, retains normalized placement through navigation and rotation, and re-clamps when its size or viewport changes. **Reset position** returns it below the header. A recoverable **PASS** mode collapses the inspector and makes every region except its unlock button click-through, while the diagnostic Canvas remains visible. Expanding opens the default **Terrain** view; **Plan** reduces the canvas to the selected/active journey, while **Full** restores protected viewport, raw perch/rail labels, footprint, gate, and locomotion details. **Exit inspector** disables the persisted Developer Options overlay request. The inspector distinguishes measured perches, derived walk rails, narrow-bubble touchdown points, expanded collision regions, dashed collision-checked candidates, the selected out-and-back planner route with arrows and numbered stops, and the solid route active only while it is traversed. The planner maintains an event-driven lookahead while behavior pacing is idle, revalidates it when terrain or a supported waypoint changes, and keeps an in-flight transfer atomic instead of redirecting mid-jump. **Freeze** snapshots only the displayed diagnostics so a route can be inspected while the live planner continues unchanged. The selected route updates on each planner pass and is cleared when live terrain changes; its numbered loop describes the exact selected outbound legs and their reverse return. Active autonomous, recovery, and direct drag/drop paths are colored separately; recovery or direct manipulation never makes a path eligible for ambient travel. Candidate connectivity is diagnostic and never implies planner selection. The full-screen Canvas and non-control inspector regions remain pointer-transparent. Locking Developer Options clears it.
- **Data** — Android-local backup, restore, and reset with confirmation dialogs. Manage → Operations also uses the authenticated upstream dashboard to create and download server backups and to upload an explicitly confirmed zip to the guarded import staging route. Manage → Learning edits full node content and confirms deletion (Hermes archives skill nodes; memory-node deletion is permanent). Manage → Memory uses upstream discovery/config/setup and provider activation, scoped to the selected profile. Manage → Channels includes upstream WhatsApp QR onboarding, status polling, apply/cancel, and the resulting gateway restart.
- **Automation** — Manage → Cron lists and controls jobs through upstream Dashboard routes. A current authenticated Gateway also exposes **New schedule**, including an optional 1–999 run cap through native `cron.manage`; blank retains the schedule's upstream one-shot/forever default. Android never turns invalid finite counts into unlimited work and does not introduce a Relay scheduler.
- **Local reset evidence** — immediately before New chat or Thread entry replaces the visible context, Android stores a bounded app-private checkpoint containing only transport, structural counts, and lifecycle booleans. No conversation content, identifiers, profile names, URLs, paths, media, tool payloads, secrets, or telemetry leaves the device; the record is available only through the existing review-before-share Diagnostics bundle.
- **About** — logo on dark background, dynamic version from BuildConfig, Source + Docs link buttons, credits. Post-update What's New uses a non-blocking timed/swipeable toast with a compact secondary feature/fix digest, then expands into the centered highlight view; About and Settings retain intentional access to the full release history.

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
│   │   └── bridge.py          # WSS bridge command dispatch + response correlation
│   └── __main__.py            # `python -m plugin.relay`
└── relay_server/              # thin shim → plugin.relay (legacy entrypoint)
```

HTTP routes registered by `create_app()` in `plugin/relay/server.py`:

| Route | Method | Purpose |
|-------|--------|---------|
| `/ws`, `/` | GET (upgrade) | WebSocket handler — main multiplexed channel |
| `/health` | GET | Health check — returns `{status, version, clients, sessions}` |
| `/pairing/register` | POST | **Loopback only.** Pre-register an externally-provided pairing code. Used by the pair command (`hermes pair`, `/hermes-relay-pair`, or compatibility `hermes-pair`) to inject codes that will appear in QR payloads. Request: `{"code": "ABCD12"}`. Rejects non-loopback peers with HTTP 403. |
| `/pairing/mint` | POST | **Loopback only.** Mint a fresh pairing code and signed QR payload plus `pairing_url` (`hermes-relay://pair?payload=...`) for dashboard and CLI/tray pair/repair flows. Optional request field `dashboard_url` is copied into the QR payload for custom dashboard routes. |
| `/api/profiles/{name}/config` | GET | Profile-scoped read-only config. Returns `{profile, path, config, readonly: true}`. Loopback callers receive the parsed `config.yaml` and absolute path. Remote callers require a relay session bearer and receive only the explicitly public `description` and `model.default` fields with `path: "config.yaml"`; arbitrary provider, platform, integration, and extension sections never cross the remote boundary. 404 on missing profile / missing config.yaml; 500 on yaml parse error. See §22 in decisions.md. |
| `/api/profiles/{name}/avatar` | GET | Profile-scoped avatar discovery and image delivery. Searches direct children of the profile home for conventional names, preferring `avatar.*` then `profile.*` (`png`, `jpg`, `jpeg`, `webp`, `gif`; additional `profile-image`, `agent`, and `icon` stems are accepted). Synthetic `default` follows a valid sticky `active_profile` marker, matching its advertised identity. The resolved file must remain inside the profile home and satisfy the Relay media-size policy. Same loopback-or-session-bearer auth as the other profile reads. 404 when the profile or an image is absent. Android copies returned bytes into its existing device-local per-profile icon store. |
| `/api/profiles/{name}/skills` | GET | Profile-scoped skill enumeration. Walks `<profile>/skills/<category>/<skill>/SKILL.md` recursively; returns `{profile, skills: [{name, category, description, path, enabled: true}], total}`. Same auth model as `/config`. `name`/`description` come from YAML frontmatter when present, else directory basename. All skills report `enabled: true` today — see §22 for the toggle stub. |
| `/api/profiles/{name}/soul` | GET | Profile-scoped raw `SOUL.md` read. Returns `{profile, path, content, exists, size_bytes}` with optional `truncated: true` when content exceeds the 200KB inline cap. Absent SOUL.md returns 200 with `exists: false` and an empty content string so the Inspector can distinguish "no soul" from transport failure. Same auth model as `/config`. 404 on unknown profile; 500 `{error: "soul_read_failed"}` on decode error. See §22 in decisions.md. |
| `/api/profiles/{name}/memory` | GET | Profile-scoped memory listing. Returns `{profile, memories_dir, entries: [{name, filename, path, content, size_bytes, truncated}], total}` for `*.md` files directly under `<profile>/memories/` (non-recursive). Ordering: `MEMORY.md` first, `USER.md` second, remainder alphabetical. Each entry capped at 50KB inline with `truncated: true` when larger. Absent memories dir → 200 with empty `entries` array. Same auth model as `/config`. 404 on unknown profile. See §22 in decisions.md. |

### 6.2 Chat — Dashboard/Gateway Primary with Optional API Fallback

Chat bypasses the Relay server entirely. In `Auto`, Android uses the upstream
dashboard `/api/ws` gateway when dashboard auth is ready because that is the
vanilla upstream path with live thinking/reasoning events. When that gateway is
unavailable, Android falls back to API-server SSE routes. The native Sessions
API fallback looks like:

Model inventory also stays upstream-owned. Android may call the optional Relay
`POST /relay/model-capabilities` route to refine reasoning-effort choices for
the exact provider/model pairs returned by upstream, but that metadata call is
not a chat proxy and is never a prerequisite for sending a message.

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
8. GET /v1/skills → available skills (for command palette + autocomplete)
```

Key classes:
- **HermesApiClient** — OkHttp-based HTTP/SSE client for direct API communication (chat, sessions, skills, config)
- **ChatHandler** — processes streaming deltas and tool call events into ChatMessage state
- **ChatViewModel** — orchestrates send/stream/cancel lifecycle, slash command handling
- **AppAnalytics** — singleton tracking TTFT, completion times, token usage, health latency, stream success rates

Android also declares a system `ACTION_SEND` target for `text/*`. A share opens
the configured Chat surface in a new conversation and copies `EXTRA_TEXT` into
the composer. This is a draft-only handoff: it never calls the send path. The
app-root intent coordinator retains a cold-start request until the chat context
settles, while `ChatViewModel` owns the existing transport-aware new-chat
lifecycle and the one-shot composer prefill.

The relay server is **not involved** in chat streaming itself. It remains the home for bridge, terminal, and — as of 2026-04-11 — **inbound media delivery** (see 6.2a). As an optional compatibility enhancement, a paired phone may poll `GET /chat/image-activity?profile=<name>&session_id=<id>&since=<epoch>` during an active Standard Gateway turn. Relay reads the selected profile's Hermes `state.db` in read-only mode and reports persisted `image_generate` start/completion state. This fills only the animation lifecycle gap on upstream configurations that suppress tool progress; it does not proxy prompts, deltas, results, or chat control, and Android stops using it when the route is absent.

### 6.2a Inbound Media (Agent → Phone file delivery)

Tool-produced files (screenshots today, video/audio/PDF/other in the future) reach the phone via a plugin-owned file-serving surface on the relay, decoupled from the chat SSE stream itself. Only a short opaque token rides the chat stream; the bytes flow out-of-band over authenticated HTTPS.

**Why this lives in the plugin, not upstream hermes-agent:** `APIServerAdapter.send()` (in upstream `gateway/platforms/api_server.py`) is an explicit no-op — the HTTP API adapter does not implement `send_document`. Upstream's `extract_media()` / `send_document()` pipeline only fires for push platforms (Telegram, Feishu, WeChat) and non-streaming paths. On our streaming HTTP surface, `MEDIA:` tags in tool output have always passed through as literal text. Rather than patch upstream, we added our own endpoints and marker format. See [docs/decisions.md §14](decisions.md) for the full trust and resource model.

**Wire format:**
```
Screenshot captured (1280x720)
MEDIA:hermes-relay://<url-safe-16-byte-token>
```

**Server:** media routes on `plugin/relay/server.py`:
- `POST /media/register` — **loopback-only**. Body `{"path", "content_type", "file_name"}`. Validates path is absolute, resolves (`os.path.realpath`) under an allowed root, exists, is a regular file, fits under `RELAY_MEDIA_MAX_SIZE_MB`. Generates `secrets.token_urlsafe(16)` (128 bits entropy), stores the token → entry mapping in an in-memory `OrderedDict` LRU (capped at `RELAY_MEDIA_LRU_CAP`, TTL `RELAY_MEDIA_TTL_SECONDS`). Returns `{ok, token, expires_at}`. Used when a host-local tool explicitly wants to publish a file.
- `GET /api/plugins/hermes-relay/provider-usage?profile=<id>&session_id=<id>` — authenticated Dashboard-plugin surface that resolves the active Codex pool entry directly from the live Gateway session, without requiring another turn. Android prefers this route when Dashboard auth is available.
- `GET /usage/providers?profile=<id>&session_id=<id>` — bearer-authenticated standalone Relay surface for Android provider account limits. Disabled unless `RELAY_PROVIDER_USAGE_ENABLED=1`. The validated profile ID scopes every credential/account lookup through Hermes's context-local home override. It reuses Hermes account snapshots for Codex and Nous, adds OpenCode Go percentage/reset windows, and returns no provider secrets. For Codex it reports every bounded pool entry with a safe label, hashed opaque id, effective status, and usage windows; the optional Gateway session id correlates the active entry from a secret-free profile-local hook snapshot. If that exact evidence is absent, active state is explicitly unknown. Android falls back to this route, then additive upstream Gateway `account.usage` as a single-account fallback.
- `GET /media/{token}` — requires `Authorization: Bearer <session_token>` against the existing `SessionManager` (same token WSS uses). Streams the file via `web.FileResponse` with the registered content type plus `Content-Disposition: inline; filename="..."` if the entry has a file name. 401 on missing/invalid bearer, 404 on unknown/expired token.
- `GET /media/by-path?path=<abs>&content_type=<optional>` — requires bearer auth. Shares the same sandbox validation as `/media/register` via a common `validate_media_path()` helper: absolute path, `realpath`-resolves under an allowed root, exists, is a regular file, fits under the size cap. Content-Type is the phone's hint if provided, otherwise guessed via `mimetypes.guess_type()`. This route exists specifically for **LLM-emitted bare-path markers** — upstream `agent/prompt_builder.py` instructs the model to include `MEDIA:/absolute/path/to/file` in its response text, so the bare-path form is the agent's native output, not just a fallback. 401 auth, 403 sandbox, 404 missing file.
- `POST /media/upload` — bearer-auth'd small upload route for phone-originated media. Accepts base64 content, writes a temp file, and registers it into the same media registry.

**Phone:** parse → fetch → cache → render:
1. `ChatHandler.scanForMediaMarkers()` runs on every `onTextDelta`, unconditionally (not gated on `parseToolAnnotations`). Matches `MEDIA:hermes-relay://([A-Za-z0-9_-]+)` and fires `onMediaAttachmentRequested(messageId, token)`. A second regex matches the bare-path form `MEDIA:(/\S+)` and fires `onMediaBarePathRequested(messageId, path)` — the ViewModel then calls `RelayHttpClient.fetchMediaByPath()` to pull bytes via `GET /media/by-path`. A per-session `dispatchedMediaMarkers` set dedupes between real-time streaming scans and the post-stream `finalizeMediaMarkers` reconciliation pass. `loadMessageHistory` (invoked by the `session_end reload` pattern at every stream complete) re-runs the same parser on server-stored content so client-injected attachments survive the wholesale state replace. Both marker forms are stripped from the rendered message text.
2. `ChatViewModel` inserts a LOADING `Attachment` with `relayToken` set immediately (message updates via `ChatHandler.mutateMessage`).
3. On Wi-Fi, or on cellular when `autoFetchOnCellular` is true: `RelayHttpClient.fetchMedia(token)` issues `GET /media/{token}` with the bearer header. URL is derived by swapping `ws://`→`http://`, `wss://`→`https://` on the stored relay URL.
4. Bytes are checked against `maxInboundSizeMb`. If oversize → FAILED placeholder. Otherwise `MediaCacheWriter` writes them to `context.cacheDir/hermes-media/<sha1>.<ext>` with LRU eviction by mtime (capped at `cachedMediaCapMb`) and returns a `content://` URI via `FileProvider.getUriForFile(context, "${applicationId}.fileprovider", file)`.
5. The Attachment is flipped to LOADED with `cachedUri` set. `InboundAttachmentCard` dispatches by `(state × renderMode)`: `IMAGE` renders inline via `BitmapFactory.decodeByteArray` + `asImageBitmap`; `VIDEO`/`AUDIO`/`PDF`/`TEXT`/`GENERIC` render as tap-to-open file cards firing `ACTION_VIEW` with `FLAG_GRANT_READ_URI_PERMISSION` on the cached URI. Every message attachment group, including galleries and LOADING/FAILED cards, sits behind a compact collapse/expand header keyed by the message's stable UI identity. Collapsing changes presentation only: the header keeps the attachment count, first name/type, and restore affordance visible while existing retry, fetch, viewer, share, and save behavior remains mounted again after expansion.
6. On cellular with `autoFetchOnCellular` off: the initial LOADING placeholder settles to an actionable FAILED state with `errorMessage = "Tap to download"`, and `manualFetchAttachment()` re-runs the fetch ignoring the cellular gate. In-flight reads have a two-minute absolute timeout; every completed attempt publishes LOADED or an actionable FAILED state.

**Fallback when relay isn't running:** the tool's `register_media()` call fails (connection refused / timeout / non-200) → tool logs a warning and returns the legacy bare-path form (`MEDIA:/tmp/...`). The phone's `onUnavailableMediaMarker` handler inserts a FAILED Attachment with `errorMessage = "Image unavailable — relay offline"`. Matches current behavior; placeholder is tidier than raw marker text.

Persisted USER history may also contain upstream-owned `@image:<absolute-path>`
directive lines. Android recognizes only bounded, full-line image directives
(including upstream's backtick/single-quote/double-quote path wrapping), removes
recognized host paths from visible text, and reconstructs at most eight
attachments. A paired Relay may resolve those paths through its authenticated
media route; a vanilla or unavailable route renders a path-free failed
attachment. Inline, relative, malformed, non-image, and unknown directives stay
as text and never trigger a fetch. Client-local outbound attachments win during
the immediate post-send reload, preventing a duplicate fetch/gallery entry.

**Known gap — session replay across relay restarts:** the `MediaRegistry` is in-memory. Restarting the relay invalidates all tokens. A user scrolling back into a session from yesterday sees FAILED placeholders for any now-stale token. Phone-side persistent cache (indexed by token or content hash) is the planned fix; filed as a DEVLOG follow-up.

**Known gap — auto-fetch threshold slider isn't enforced today.** The Settings → Inbound media → auto-fetch threshold knob is persisted but the fetch path currently only checks the cellular toggle + the hard max cap. Forward-compatibility placeholder; real enforcement needs a HEAD preflight or post-hoc byte rejection.

**Key classes:**
- **`MediaRegistry`** (`plugin/relay/media.py`) — in-memory token store, thread-safe via `asyncio.Lock`
- **`register_media()`** (`plugin/relay/client.py`) — stdlib `urllib.request` helper for in-process tool callers
- **`RelayHttpClient`** (Android) — OkHttp GET with Bearer auth + URL rewriting
- **`MediaCacheWriter`** (Android) — FileProvider-backed LRU cache in `cacheDir/hermes-media/`
- **`InboundAttachmentCard`** (Android) — single Compose component dispatched on `(state × renderMode)`, handles both inbound and outbound attachments

### 6.2b Rich Cards (Agent → Phone structured UI, ADR 26)

Agents and skills can surface structured content — approval prompts, link previews, calendar entries, weather, generic skill output — as inline Compose cards in the chat feed, using the same "inline line marker" recipe as `MEDIA:`. No server patch is required; the marker rides the existing streaming text so it works unchanged on `/v1/runs`, `/api/sessions/{id}/chat/stream`, and `/v1/chat/completions`. See [docs/decisions.md ADR 26](decisions.md) for the full design + Phase B roadmap.

**Wire format:**
```
CARD:{"type":"approval_request","title":"Run shell command?","body":"`rm -rf /tmp/cache`","accent":"warning","actions":[{"label":"Allow","value":"/approve","style":"primary","mode":"slash_command"},{"label":"Deny","value":"/deny","style":"danger"}]}
```

Constraints:
- The marker MUST live on its own line.
- The JSON payload MUST be single-line — escape newlines in string fields as `\n`. Nested braces in `fields` / `actions` arrays are fine; the parser's `\{.*\}` body capture is greedy.
- Invalid JSON is logged and the line is left in the rendered content as a visible hint, not silently dropped.

**Envelope schema:**

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `type` | string | yes | Dispatcher key. Built-ins: `skill_result`, `approval_request`, `link_preview`, `calendar_event`, `weather`. Unknown values render via generic fallback. |
| `title` | string | no | Header line; rendered in `titleSmall` / SemiBold. |
| `subtitle` | string | no | Muted line under the title. |
| `body` | string | no | Markdown — same renderer as message bubbles. |
| `accent` | enum | no | `info` (default), `success`, `warning`, `danger`. Semantic → `colorScheme` token. |
| `fields` | `[{label, value}]` | no | Rendered as label/value rows. `value` is markdown; values that look like paths / commands / URLs auto-mono-font via a heuristic. |
| `actions` | `[{label, value, style?, mode?}]` | no | Tappable buttons. See below. |
| `footer` | string | no | Muted `labelSmall` text at bottom. |
| `id` | string | no | Stable id for dispatch tracking across session reload. Falls back to `idx:N` position in the message's `cards` list when absent. |

**Actions:**

| Field | Type | Notes |
|-------|------|-------|
| `label` | string | Button text. |
| `value` | string | Action payload — see `mode` for interpretation. |
| `style` | enum | `primary` (filled), `secondary` (outlined, default), `danger` (outlined, error color). |
| `mode` | enum | `send_text` (default — sends `value` as a new user message), `slash_command` (still routes through `sendMessage` — server interprets the leading `/`), `open_url` (launches `ACTION_VIEW` locally; `value` is the URL). |

**Phone:** parse → render → dispatch → sync:
1. `ChatHandler.scanForCardMarkers()` runs on every `onTextDelta`, unconditionally (not gated on `parseToolAnnotations`). Matches `^\s*CARD:(\{.*\})\s*$` per line, parses the JSON with `ignoreUnknownKeys = true`, and appends the decoded `HermesCard` to the message's `cards` list. A per-session `dispatchedCardMarkers` set dedupes real-time streaming scans against the post-stream `finalizeCardMarkers` reconciliation pass.
2. `loadMessageHistory` re-runs a mirror parser (`extractCardsFromContent`) on server-stored content so cards survive the wholesale state replace that fires at every `session_end reload`. The matched marker line is stripped from the rendered content so the user sees the card, never the literal JSON.
3. `HermesCardBubble` renders the card: accent stripe + type icon + title/subtitle + markdown body + fields table + `FlowRow` of action buttons + footer. Tapping an action fires `ChatViewModel.dispatchCardAction(messageId, cardKey, action)`, which stamps a `HermesCardDispatch` on the owning message **before** the side effect, so the card collapses into a "Chose: X" confirmation even if the side effect throws.
4. Dispatch side effect: `send_text` and `slash_command` both route through `sendMessage(action.value)` (slash is plain text server-side). `open_url` launches an `ACTION_VIEW` intent from the UI layer via `handleCardActionExternally`.
5. Server-side session sync runs on the next chat send: `CardDispatchSyncBuilder` materializes every unsynced `HermesCardDispatch` into an OpenAI-format `assistant`+`tool` pair under a namespaced synthetic tool name `hermes_card_action` (never dispatched — audit record only) and splices them into the request body. `ChatHandler.markCardDispatchesSynced` flips the `syncedToServer` flag post-handoff. Same idempotency pattern as `VoiceIntentSyncBuilder` (§6.2a).

**Key classes:**
- **`HermesCard`** / **`HermesCardField`** / **`HermesCardAction`** / **`HermesCardDispatch`** (`data/HermesCard.kt`) — `@Serializable`, `ignoreUnknownKeys = true` on the parser
- **`ChatHandler.scanForCardMarkers` / `tryDispatchCardMarker` / `finalizeCardMarkers` / `extractCardsFromContent` / `recordCardDispatch` / `markCardDispatchesSynced`** — line-oriented streaming parser + history extractor + idempotency flag flipper
- **`HermesCardBubble`** (`ui/components/HermesCardBubble.kt`) — Material 3 renderer; `handleCardActionExternally` top-level helper for URL launch
- **`CardDispatchSyncBuilder`** (`viewmodel/CardDispatchSyncBuilder.kt`) — pure function, JVM-testable; emits `hermes_card_action` synthetic messages for LLM session memory

**Known gap — multi-line JSON payloads.** Today the parser assumes single-line JSON so the line-buffer strategy stays simple. If a future built-in type needs very large payloads that stretch readability, a fenced `<hermes-card>...</hermes-card>` alternative syntax can be layered on without breaking the flat marker.

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

**Change from upstream:** The bridge channel is part of the multiplexed WSS connection instead of a separate `ws://` relay on port 8766. The legacy standalone `plugin/tools/android_relay.py` was retired in Phase 3 Wave 1 (2026-04-12) and its functionality migrated to two files in the unified relay: `plugin/tools/android_tool.py` (Hermes tools pointing at `http://localhost:8767` — baseline 14 plus v0.4 expansion) and `plugin/relay/channels/bridge.py` (the `BridgeHandler.handle_command(...)` dispatcher that mints request IDs, sends `bridge.command` envelopes over the shared WSS pipe, and awaits matching `bridge.response` envelopes with a 30s timeout). HTTP routes are registered on `plugin/relay/server.py` between `# === PHASE3-bridge-server ===` markers and delegate through the same handler. Wire protocol is frozen — envelopes match the legacy relay byte-for-byte.

#### 6.4.1 `android_*` tool surface

Tools register against the Hermes plugin API in `plugin/tools/android_tool.py` (plus `plugin/tools/android_notifications.py`, `plugin/tools/android_navigate.py`). The Python-side Device Control tools issue bearer-authenticated HTTP requests to the relay on loopback using `ANDROID_BRIDGE_TOKEN`; the relay requires that session's active `bridge` grant before forwarding to the phone over WSS. The sideload phone executes commands via the accessibility service and returns structured responses. Google Play phones report `bridge.device_control_supported=false` from `/bridge/status`, so these tools are hidden from the agent and direct command probes fail closed with `error_code: device_control_sideload_only`.

**Baseline (pre-v0.4 — shipped in Phase 3 Wave 1):**

| Tool | HTTP route | Purpose | Flavor |
|------|-----------|---------|--------|
| `android_ping` | `GET /ping` | Liveness check — does not require master enable | sideload Device Control |
| `android_screen` | `GET /screen` | Serialize the accessibility tree → `ScreenContent` | sideload Device Control |
| `android_screenshot` | `GET /screenshot` | `MediaProjection` PNG → `MEDIA:hermes-relay://<token>` | sideload Device Control |
| `android_current_app` | `GET /current_app` | Best-effort foregrounded package name; use `/screen` for verification | sideload Device Control |
| `android_get_apps` (`/apps` legacy) | `GET /get_apps` | Installed launcher apps | sideload Device Control |
| `android_tap` | `POST /tap` | Tap at `(x, y)` or on resolved `node_id` | sideload Device Control |
| `android_tap_text` | `POST /tap_text` | Find text via accessibility tree, tap it (see A9 cascade below) | sideload Device Control |
| `android_type` | `POST /type` | `ACTION_SET_TEXT` on focused input field | sideload Device Control |
| `android_swipe` | `POST /swipe` | Gesture swipe with direction + distance | sideload Device Control |
| `android_scroll` | `POST /scroll` | Scroll a specific container (resolves `node_id`) | sideload Device Control |
| `android_open_app` | `POST /open_app` | Launch an app by package name | sideload Device Control |
| `android_press_key` | `POST /press_key` | Curated global-action vocab (home/back/recents/notifications/quick_settings) — no raw `KeyEvent` injection | sideload Device Control |
| `android_wait` | `POST /wait` | Clamped idle — max 15s | sideload Device Control |
| `android_setup` | `POST /setup` | Permission bootstrap helper | both |
| `android_navigate` | (dispatches `/screenshot` + `/tap_text`/`/tap`/`/type`/`/swipe`/`/press_key`) | Tier 4 vision-driven close-the-loop navigation | sideload Device Control |
| `android_notifications_recent` | `GET /notifications/recent` | Poll the notif-listener ring buffer (loopback-only for Python tool callers) | both |

**v0.4 additions — Tier A (sideload Device Control):**

| Tool | HTTP route | Purpose |
|------|-----------|---------|
| `android_long_press(x, y, node_id, duration=500)` | `POST /long_press` | Long-press gesture at coords or on resolved node. Gesture path wrapped in `WakeLockManager.wakeForAction` (see §6.4.2). |
| `android_drag(start_x, start_y, end_x, end_y, duration)` | `POST /drag` | Single-stroke drag via `GestureDescription`. Wrapped in wake-lock. |
| `android_find_nodes(text?, class_name?, clickable?, limit)` | `POST /find_nodes` | Filtered accessibility-node search across **all** windows (see P1 in §6.4.2). Returns a list of `{node_id, text, bounds, class, clickable}` records. |
| `android_describe_node(node_id)` | `POST /describe_node` | Full property bag for a single node resolved by stable `node_id`. Round-trips the same ID scheme emitted by `android_screen` / `android_find_nodes`. A4 also completes the `node_id` resolution path in the existing `/tap` and `/scroll` routes — the IDs were previously emitted but not accepted as input. |
| `android_screen_hash()` | `GET /screen_hash` | Returns `{hash, node_count}`. SHA-256 over a canonical per-node fingerprint (`className + text + bounds + viewId`) across the full accessibility tree. See `ScreenHasher` in §6.4.2. |
| `android_diff_screen(previous_hash)` | `POST /diff_screen` | Returns `{changed, hash, node_count}` in a single call. Used as a cheap "did anything change?" check to skip full screen re-reads inside agent loops. |
| `android_clipboard_read()` | `GET /clipboard` | Read primary clip via `ClipboardManager.primaryClip`. |
| `android_clipboard_write(text)` | `POST /clipboard` | Set primary clip. |
| `android_media(action)` | `POST /media` | System-wide media control via `AudioManager.dispatchMediaKeyEvent` + `ACTION_MEDIA_BUTTON` broadcast. Actions: `play` / `pause` / `toggle` / `next` / `previous`. |
| `android_macro(steps, name, pace_ms)` | (Python-side only) | Pure-Python batched workflow dispatcher. Iterates `steps` (each `{tool, args}`), stops on first failure, returns the full trace. No new HTTP route — dispatches to the existing tool handlers in-process. |

**v0.4 additions — Tier B (sideload Device Control):**

| Tool | HTTP route | Purpose |
|------|-----------|---------|
| `android_events(limit, since)` | `GET /events` | Poll the real-time `AccessibilityEvent` ring buffer. **Off by default** — a session must enable forwarding via `android_event_stream(enabled=true)` before events are recorded. Privacy-sensitive; keep off unless an agent flow needs it. |
| `android_event_stream(enabled)` | `POST /events/stream` | Opt in / out of event capture for the current session. |
| `android_send_intent(action, data, package, component, extras, category)` | `POST /send_intent` | Raw `Intent` escape hatch — `startActivity`. Safety-gated on the target package blocklist via `BridgeSafetyManager.checkPackageAllowed`. |
| `android_broadcast(action, data, package, extras)` | `POST /broadcast` | Raw `sendBroadcast`. Same blocklist gate as `/send_intent`. |

**v0.4 additions — Tier C (sideload-only):**

Tier C tools add runtime permissions or user-mediated system share/compose handoffs that are intentionally scoped to the sideload flavor only. The permissions are declared in `app/src/sideload/AndroidManifest.xml`; the googlePlay manifest does not declare them, and phone-side route gates return structured `403` / `error_code: sideload_only` (the broader Device Control command gate uses `device_control_sideload_only`).

| Tool | HTTP route | Purpose | Permission |
|------|-----------|---------|------------|
| `android_location()` | `GET /location` | Last-known GPS fix via `LocationManager.getLastKnownLocation` | `ACCESS_FINE_LOCATION` |
| `android_search_contacts(query, limit)` | `POST /search_contacts` | `ContactsContract` name → phone number lookup, cap on result count | `READ_CONTACTS` |
| `android_call(number)` | `POST /call` | Auto-dial via `ACTION_CALL`. **Every call is gated on the destructive-verb confirmation modal**; see §6.4.2 safety notes. | `CALL_PHONE` |
| `android_send_sms(to, body)` | `POST /send_sms` | Text-only `SmsManager.sendTextMessage` (or `sendMultipartTextMessage` for long bodies) with a `PendingIntent` result callback. Returns structured `sent`, `blocked`, `timeout`, or `failed` status details. **Every send is gated on the destructive-verb confirmation modal.** | `SEND_SMS` |
| `android_share_media(...)` | `POST /share_media` | Share text, host-local files, relay `MEDIA:` markers, or raw media tokens through Android's native share UI with `FileProvider` `content://` grants. | n/a |
| `android_send_mms(to, body?, attachments...)` | `POST /send_mms` | Open a user-mediated MMS compose/share handoff with recipient, optional body, and attachments. Hermes Relay does not silently send MMS because Android reserves background MMS delivery for the default SMS app. | n/a |

**Safety integration.** `BridgeCommandHandler` resolves every request through the closed `(method, path)` registry before side effects. Unknown paths, wrong methods, missing policy, missing active Connection identity, absent grants, and expired timed grants fail with structured 403 responses. Only `/ping`, host-only `/setup`, and bounded `/wait` are grant-exempt operational primitives; the master switch still overrides `/wait` and every capability route. The remaining gates compose rather than replace one another:
1. **Connection-scoped capability** — the active Android Connection selects the persisted policy. Durable capabilities are Always/Never. Screen/UI inspection and screen/device control store absolute expiries and are Timed-only. Only accepted timed commands extend their shared idle window; harmless permanent reads do not.
2. **Master + Android authority** — the v2 master key gates every capability. AccessibilityService, runtime permissions, Notification Listener, overlay access, and current-session MediaProjection remain separately enforced by Android and the executor. A UI grant never substitutes for an OS grant.
3. **Blocklist gate** — `BridgeSafetyManager.checkPackageAllowed(currentApp)` returns 403 when the foreground package is blocklisted. Intent/broadcast/open targets are checked separately.
4. **Confirmation gate** — destructive text actions suspend for the overlay decision. `android_call`, `android_send_sms`, `android_share_media`, and `android_send_mms` always confirm even when their capability is Always. Silence, missing overlay, and timeout deny.

Aliases share one capability (`/apps` and `/get_apps`), while `GET /clipboard` and `POST /clipboard` intentionally resolve to separate read/write capabilities. `android_navigate` and `android_macro` have no aggregate bypass: every primitive call crosses the registry. Policy is not exported in Android backups, removal of a Connection deletes it, and the legacy master key is pinned false so downgrade fails closed. See ADR 63.

#### 6.4.2 Architectural patterns adopted in v0.4

The v0.4 wave includes three reliability patterns applied to existing code and one new primitive. They're listed here because they cut across every tool added above and anchor the tool surface to a more predictable baseline.

**WakeLockManager — wake-scope wrapping for gesture dispatch.** New `object WakeLockManager` at `app/src/main/kotlin/com/hermesandroid/relay/power/WakeLockManager.kt` exposes `suspend fun <T> wakeForAction(block: suspend () -> T): T`. Uses `PowerManager.PARTIAL_WAKE_LOCK`, ref-counted so nested calls don't release each other prematurely, with a hard 10-second timeout as a battery safety rail. `ActionExecutor` wraps every gesture-dispatching function (`tap`, `tapText`, `typeText`, `swipe`, `scroll`, `longPress`, `drag`) in `wakeForAction { ... }`. Read-only accessibility calls (`readScreen`, `findNodes`, `describeNode`, `screenHash`, `diffScreen`, `currentApp`, `clipboardRead/Write`, `mediaControl`) are not wrapped — they don't need the screen on. Closes the "gesture fires into the void when the screen is off" failure mode that silently broke `android_tap` / `android_swipe` whenever Bailey's phone hit idle between commands. Requires `android.permission.WAKE_LOCK` in the main manifest.

**Multi-window ScreenReader (P1).** `ScreenReader.readCurrentScreen` now iterates `service.windows.mapNotNull { it.root }` instead of the single `rootInActiveWindow`. Returns a merged tree where each `AccessibilityNodeInfo` is walked per-window and recycled in the per-iteration `try/finally`. Catches system overlays, popup menus, notification shade, and split-screen secondary windows — the previous single-root path silently ignored them. **Node-ID scheme update:** stable IDs are now prefixed `w<windowIndex>:<sequentialIndex>` (e.g. `w0:42`, `w1:7`) so IDs are disambiguated across windows. A single-window fallback kicks in when `service.windows` is empty, which happens on the googlePlay flavor without `flagRetrieveInteractiveWindows` (the conservative a11y config that survives Play Store policy review). Node IDs are end-to-end resolvable after A4 wired parsing into `/tap` and `/scroll` — `android_find_nodes` and `android_describe_node` emit them, and `android_tap` / `android_scroll` accept them as input, so an agent can search → describe → act without re-reading the tree.

**A9 three-tier `tapText` cascade.** `ActionExecutor.tapText` replaces the single-shot `findNodeBoundsByText → performAction(ACTION_CLICK)` path with a 3-tier fallback:
1. Find node by text across all windows. If `node.isClickable` → `performAction(ACTION_CLICK)`.
2. Otherwise walk up the parent chain (capped at 8 levels) looking for a clickable ancestor. If found → `performAction(ACTION_CLICK)` on it.
3. Otherwise capture the node's `getBoundsInScreen()` center and fall back to a coordinate `tap(cx, cy)`.

The `ActionResult.data` field indicates which tier succeeded (`"direct"` / `"parent"` / `"coords"`) so the activity log and agent trace show how the click was resolved. Fixes a whole class of failures in real-world apps (Uber, Spotify, Instagram, Tinder) that wrap clickable content in non-clickable text or image views. Parent-chain traversal is bounded to avoid leaks — every `AccessibilityNodeInfo` returned by `.parent` is explicitly recycled before the loop reassigns.

**ScreenHasher — content fingerprint for change detection.** New primitive backing A5 `android_screen_hash` / `android_diff_screen`. Walks the full (multi-window) accessibility tree and computes SHA-256 over a canonical joined fingerprint of per-node triples (`className + text + bounds + viewId`). Returns `{hash, node_count}`. The hash is deliberately **not** stable across animation frames or live-updating text — documented limitation. Rationale: `android_navigate` previously re-read the full tree on every loop iteration to decide whether the last action did anything; a hash comparison is ~100× cheaper in both compute and token cost, and an agent polling for "has the page loaded yet?" can do so without dragging a full `ScreenContent` JSON back across the WSS each time. Phone-side: new `ScreenHasher.kt` alongside `ScreenReader.kt`. Exposed via a `computeHash()` extension on the serialized node model so the server can also hash a prior `ScreenContent` snapshot for free.

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
**Status: preview shipped in v0.2.0 (2026-04-12). Biometric gate is the one open item.**

- [x] Server: PTY/tmux integration (`plugin/relay/channels/terminal.py`)
- [x] Server: Terminal channel handler (attach, input, output, resize)
- [x] App: WebView + xterm.js terminal emulator (`TerminalWebView.kt`)
- [x] App: Soft keyboard toolbar — Ctrl / Tab / Esc / arrows (`ExtraKeysToolbar.kt`)
- [x] App: tmux session picker with tabs (`TerminalTabBar.kt`, `TerminalSessionInfoSheet.kt`), scrollback search (`TerminalSearchBar.kt`)
- [ ] App: Biometric gate before terminal access (planned — see Phase 4)
- [x] App: Terminal resize on orientation change

### Phase 3 — Bridge Channel
**Status: shipped and expanded.** The original bridge channel shipped in v0.3.0 (2026-04-13); the later bridge expansion added long-press / drag / macro / clipboard / intent-send / location / contacts / call / SMS and multi-window screen reading. Bridge remains a Relay-required sideload power surface, not part of the Vanilla Hermes no-plugin path.

- [x] Migrate upstream bridge protocol into multiplexed WSS — Phase 3 Wave 1, 2026-04-12 (routes registered in `plugin/relay/server.py` delegating to `plugin/relay/channels/bridge.py`)
- [x] Update `plugin/tools/android_tool.py` to route through the unified relay on port 8767 (was the standalone `android_relay.py` on 8766)
- [x] App: Bridge status UI — see §5 Bridge Tab
- [x] App: Permission management (`BridgePermissionChecklist` plus Settings -> Permissions — shared accessibility, screen capture, overlay, notification listener, runtime-grant status)
- [x] App: Activity log (`BridgeActivityLog` + `BridgePreferences`, capped at 100 entries)
- [x] App: Accessibility service (`HermesAccessibilityService` + `ScreenReader` + `ActionExecutor` + `BridgeCommandHandler`)
- [x] App: Tier 5 safety rails — `BridgeSafetyManager` (connection-scoped capability grants + timed screen-access expiry + global blocklist + destructive confirmation), `BridgeForegroundService` (persistent "Hermes has device control" notification), `BridgeStatusOverlay` (confirmation modal + optional floating chip)
- [x] App: Flavor split — googlePlay (conservative a11y config) and sideload (full capabilities)
- [x] Plugin: notification-listener companion channel (`android_notifications_recent`) + `android_navigate` vision loop
- [x] **v0.4 bridge feature expansion** — 10 Tier A tools (long_press, drag, find_nodes, describe_node, screen_hash + diff_screen, clipboard r/w, media, macro) + 2 Tier B tools (events/event_stream, send_intent + broadcast) + 4 Tier C sideload-only tools (location, search_contacts, call, send_sms); architectural patterns — `WakeLockManager` wake-scope wrapping, multi-window `ScreenReader`, A9 three-tier `tapText` cascade, `ScreenHasher` content fingerprinting. See §6.4.1 for the tool surface table and §6.4.2 for the patterns.

### Phase 4 — Security Hardening
**Status: ADR 15 landed in v0.2.0 (2026-04-11/12). Biometric gate is the one remaining item.**

- [x] TLS support + TOFU certificate pinning (`CertPinStore` — SHA-256 SPKI fingerprints per `host:port`, wiped explicitly on re-pair via `applyServerIssuedCodeAndReset`; plain `ws://` short-circuits pinning)
- [x] Android Keystore session token storage (`SessionTokenStore` — `KeystoreTokenStore` with StrongBox-preferred via `setRequestStrongBoxBacked`, `LegacyEncryptedPrefsTokenStore` TEE-backed fallback, one-shot lossless migration on first launch)
- [x] User-chosen session TTL at pair time (`SessionTtlPickerDialog` — 1d / 7d / 30d / 90d / 1y / Never)
- [x] Per-channel grants on one session token (`Session.grants` — chat / terminal / bridge / TUI / split voice grants (`voice:config`, `voice:stt`, `voice:tts`), clamped to session lifetime)
- [x] Paired Devices screen (`PairedDevicesScreen` + `GET /sessions` + `DELETE /sessions/{prefix}`; bearer-authenticated `PATCH /sessions/{prefix}` is self-targeted and reduction-only)
- [x] Transport security badge (`TransportSecurityBadge` — three states: secure / insecure-with-reason / insecure-unknown)
- [x] First-time insecure-mode ack dialog with reason picker (`InsecureConnectionAckDialog`)
- [x] Tailscale detection (`TailscaleDetector` — informational only)
- [x] HMAC-SHA256 QR signing (`plugin/relay/qr_sign.py` with host-local secret at `~/.hermes/hermes-relay-qr-secret`; phone parses + stores `sig` but does not verify yet — secret distribution is a follow-up)
- [x] Rate limiting on auth endpoint (`RateLimiter` — 5 attempts / 60s → 5-min block; `/pairing/register` clears all blocks on success so legitimate re-pair after relay restart works immediately)
- [x] Session expiry + rotation (`expires_at` in `auth.ok`, server-side `SessionManager` enforcement)
- [ ] Biometric gate for terminal access (AndroidX Biometric — not wired yet)

### Phase 5 — Polish & CI/CD
**Status: largely shipped. v0.1.0 shipped to the Play Store under Axiom-Labs, LLC. Notification-channel-for-agent-messages is the one open item.**

- [x] GitHub Actions: lint + build + test on every push (`.github/workflows/ci.yml`)
- [x] GitHub Actions: release workflow — `android-v*` tag-triggered signed APK + AAB upload to GitHub Release (`.github/workflows/release-android.yml`)
- [x] Material You dynamic theming (Material 3 + dynamic color, user toggle in Appearance settings)
- [x] Proper error states and empty states (`RelayErrorClassifier` → `HumanError` → global `LocalSnackbarHost`; MorphingSphere-backed empty chat state)
- [x] App icon and branding (`ic_launcher*`, animated splash via `splash_icon_animated.xml`, MorphingSphere)
- [x] Two build flavors: `googlePlay` (Play Store track, conservative Accessibility use case) and `sideload` (`.sideload` applicationId suffix, full feature set)
- [ ] Notification channel for agent messages (not wired; Phase 6 territory)

### Phase V — Voice Mode
**Status: shipped 2026-04-12**

Voice conversation uses upstream dashboard audio by default, with Relay-hosted
voice endpoints available as optional enhanced engines. Chat uses the upstream
Dashboard/Gateway first and can fall back to the API server; voice adds a
modality on top, not a separate chat channel. The Relay exact-assistant
narration path is `/voice/output/*`, a
first-class streaming TTS renderer. The older realtime lab path remains
available at `/voice/realtime/*` for provider-agent experiments, while the
experimental Realtime Agent engine uses `/voice/realtime-agent/*` to bind
provider audio rendering to the Hermes session/tool loop.
The basic `/voice/transcribe` and `/voice/synthesize` endpoints remain fallback
utilities.

**Server-side (plugin/relay):**
- `POST /voice/transcribe` — multipart audio → `{text, provider}`. Wraps `tools.transcription_tools.transcribe_audio` in `asyncio.to_thread`.
- `POST /voice/synthesize` — JSON `{text}` → `audio/mpeg` file. Wraps `tools.tts_tool.text_to_speech_tool`; used as the basic fallback when streaming/realtime provider playback is unavailable. Accepts optional per-request enhanced-voice overrides (`voice`, `model`, `audio_tags`, `persona_prompt`, `language`) mapped onto the active provider — Gemini and xAI today — by crafting a per-call `tts_config` and invoking the provider generator (`_generate_gemini_tts` / `_generate_xai_tts`) directly, since `text_to_speech_tool` has no per-call override surface (no fork; upstream imports isolated in `plugin/relay/upstream_voice.py`). The relay owns the output temp file and deletes it after streaming.
- `GET /voice/config` — provider availability + current settings from `tts:` / `stt:` in `~/.hermes/config.yaml`. When the basic TTS provider is Gemini or xAI, the response includes a `tts.enhanced` capability block (voices/models/audio-tag support + `supports_persona`/`supports_language` flags) so the app renders a per-request enhanced-voice picker. The Vanilla Hermes dashboard `/api/audio/speak` has no per-request surface — enhanced voice there stays config-only via Manage `PUT /api/config`.
- `GET/PATCH /voice/output/config`, `POST /voice/output/session`, and `GET /voice/output/{session_id}` — relay-mediated streaming TTS renderer sessions. Android sends final Hermes text or brokered tool-status text and receives mono PCM deltas for direct `AudioTrack` playback. Session creation accepts optional provider/model/voice/sample-rate/language overrides for ephemeral draft previews; omitted values continue to resolve from the saved profile/relay defaults. Session responses include resumable-session metadata and PCM events carry `event_id`/`audio_event_id`, so short route changes during stable speech playback can resume and replay missed audio without re-rendering. Config responses include provider option metadata (`providers[].models`, `providers[].voices`, `providers[].languages`, `providers[].sample_rates`) for first-class dropdowns.
- `GET/PATCH /voice/realtime/config`, `POST /voice/realtime/session`, and `GET /voice/realtime/{session_id}` — relay-mediated realtime provider-agent sessions for lab/dev experiments. Android can send PCM input events and receives mono PCM provider deltas for direct `AudioTrack` playback. Realtime config responses expose the same provider option shape where known.
- `GET/PATCH /voice/realtime-agent/config`, `POST /voice/realtime-agent/session`, and `GET /voice/realtime-agent/{session_id}` — experimental Hermes-brokered Realtime Agent engine. The broker binds active profile/chat session/auth, streams Android mic PCM to a native realtime provider such as `xai_realtime` or `openai_realtime`, normalizes provider transcript/audio/function-call events, mirrors Hermes session/tool/confirmation events into Android, and returns compact Hermes tool results to the provider for concise spoken follow-up. Session creation accepts an ephemeral `final_answer_only` boolean; when enabled, the broker disables routine spoken handoffs and progress while preserving approval, confirmation, and blocking-failure prompts. Session responses include resumable-session metadata (`resume_token`, `resume_supported`, `resume_ttl_ms`); server events carry `event_id`, audio deltas carry `audio_event_id`, and Android can resume a detached session through the current `effectiveRelayUrl` after short Wi-Fi/cellular/LAN/Tailscale changes without starting a second Hermes run. A replacement route is usable only after relay `voice.session.resumed` confirmation; socket generation + resume-episode claims reject stale failure/close/fatal callbacks, unacknowledged input is replayed atomically, and each route-loss episode owns a bounded retry budget that starts at loss rather than session prewarm. Terminal exhaustion detaches session-owned reconnect UI so a stopped retry loop cannot leave an active task pill behind. The only provider-facing tool surface is `hermes_run_task`, `hermes_get_status`, `hermes_cancel`, and `hermes_confirm`.
- `GET /voice/output/providers/{provider_id}/options`, `GET /voice/realtime/providers/{provider_id}/options`, and `GET /voice/realtime-agent/providers/{provider_id}/options` — provider-specific option refresh before saving. Android calls these when a provider is selected so dynamic account-backed choices can be fetched by the relay without exposing provider secrets. xAI refreshes built-in/paginated custom voices when API/OAuth auth is available; ElevenLabs refreshes voices/models/languages with its API key; OpenAI uses static documented voice choices. Realtime Agent provider payloads include `supports_realtime_agent_native` so render/lab-only realtime support is not confused with native speech-to-speech Hermes tooling. Responses include `schema_version`, grouped voice metadata, recommended/custom flags, and model/voice compatibility hints when known. Unknown or unauthenticated discovery falls back to static provider metadata plus manual entry.
- `POST /voice/output/providers/{provider_id}/validate`, `POST /voice/realtime/providers/{provider_id}/validate`, and `POST /voice/realtime-agent/providers/{provider_id}/validate` — pre-save validation for provider/model/voice/sample-rate selections. Unknown manual IDs return warnings; explicit incompatibilities return blocking errors.
- Voice-output provider defaults are relay-owned under `voice_output:` in `~/.hermes-relay/config.yaml` (or `RELAY_VOICE_OUTPUT_CONFIG`), then overridden by `RELAY_VOICE_OUTPUT_*` env vars for temporary tests. Authenticated operator clients may patch safe defaults (`enabled`, `provider`, `model`, `voice`, `sample_rate`, `language`, `codec`, `optimize_streaming_latency`, `text_normalization`, `auto_speech_tags`, `fallback_enabled`) through the relay. With `?profile=<name>`, the patch writes that profile's `voice_output:` section. Provider secrets and local auth paths stay server-side. `auto_speech_tags` is an xAI enhanced-voice control: when the renderer is `xai_tts` the relay applies `upstream_voice.apply_xai_speech_tags()` (upstream's inline/wrapping tone markers) to each chunk before rendering, so the streaming path matches the basic `/voice/synthesize` tone behavior. The `voice_lab` renderer set is xai/openai/elevenlabs — there is no Gemini streaming provider, so Gemini enhanced voice is `/voice/synthesize`-only.
- Realtime provider defaults are relay-owned under `realtime_voice:` in `~/.hermes-relay/config.yaml` (or `RELAY_REALTIME_VOICE_CONFIG`), then overridden by `RELAY_REALTIME_VOICE_*` env vars for temporary tests. Authenticated operator clients may patch safe defaults (`enabled`, `provider`, `model`, `voice`, `sample_rate`) through the relay. With `?profile=<name>`, the patch writes that profile's `realtime_voice:` section. Provider secrets and local auth paths stay server-side.
- Voice routes are gated by narrow voice bearer auth (`voice:config`, `voice:stt`, `voice:tts`, or `voice:realtime`) or a valid Hermes API bearer under the transport guard.

**App-side:**
- `VoiceRecorder` (`AudioRecord` / WAV / 16 kHz mono PCM) exposes both a STT upload file and raw PCM bytes for the realtime websocket input events.
- `VoicePlayer` (Media3 ExoPlayer + Visualizer) remains the fallback `/voice/synthesize` playback surface.
- `RealtimePcmPlayer` streams `/voice/output/*`, `/voice/realtime/*`, and `/voice/realtime-agent/*` PCM deltas directly to `AudioTrack`.
- `VoiceViewModel` state machine (`Idle / Listening / Transcribing / Thinking / Speaking / Error`). Assistant text is sanitized (markdown / tool-annotations / URLs / emoji-set stripped) on each delta before a coalescing chunker (`MIN_COALESCE_LEN=40`, `MAX_BUFFER_LEN=400` secondary-break escape, 800 ms timer flush) emits sentence-scale chunks. The observer aggregates every assistant bubble created by one Hermes run, including interim tool handoffs and the final answer, and finishes speech only when the run-level stream ends. Stable bubble identity and submitted-turn/session fences prevent StateFlow/history reconciliation or a pending-new-chat session switch from speaking stale or duplicate text. Bubble boundaries flush incomplete prior text so adjacent narration cannot run together. The default queue calls `/voice/output/*` for exact renderer PCM playback; failed output turns fall back to the existing `/voice/synthesize` synth/play workers. The same stream observer watches Hermes-owned `ToolCall` state and speaks bounded status lines for running tools; execution, approval, and tool results remain in the Hermes chat/relay loop.
- Server-side, `/voice/synthesize` runs a matching sanitizer (`plugin/relay/tts_sanitizer.py`) before handing text to the upstream `text_to_speech_tool` — defense-in-depth for any client that doesn't pre-sanitize.
- **Full-turn barge-in** (default on, user-configurable). One turn-scoped `BargeInListener` starts when the submitted voice turn enters `Thinking` and remains the sole listener through generation, `Speaking`, and audio drain on both Standard and Realtime paths. Its duplex `AudioRecord` (16 kHz mono PCM, `VOICE_COMMUNICATION` source) feeds 32 ms frames through Silero VAD and an upstream-compatible RMS gate: roughly 450 ms of non-triggering pre-playback calibration, a 90th-percentile quiet floor of at least 200 RMS, a default 3× multiplier, generation/playback floors of 400/1,500 RMS, a 4,000 RMS ceiling, 500 ms playback grace, and an 80%-majority 300 ms decision window. The ambient floor may drift only while playback is inactive and the room remains below threshold. Playback phase follows the renderer, returning to generation thresholds between output spans and rearming grace only after a gap of at least one second. Raw probable speech ducks playback; only model-confirmed speech above the RMS gate interrupts. `AcousticEchoCanceler` + `NoiseSuppressor` attach to that `AudioRecord` capture session and release with it. Detection uses the existing gateway/provider interrupt seam, fences stale callbacks and late audio/text deltas, waits for microphone release, then captures the replacement utterance. A 600 ms watchdog preserves the existing resume-after-interruption behavior for playback. Configurable stop phrases default to exact bare `stop`; an empty list disables them. A stop phrase ends the active voice chat during generation or playback, while ordinary requests such as “stop the container” remain agent input outside that exact match. Playback interruption sets a one-shot, API-local 120-second latch that adds `[Note: the user interrupted your previous spoken reply before it finished.]` to the next model-bound turn without changing visible or persisted user text; generation or pre-audio synthesis interruption does not set it. Silencing a promoted background run leaves the Hermes task alive unless the user explicitly requests background-task cancellation.
- **Experimental local wake word** (opt-in, default off). Android runs sherpa-onnx keyword spotting for the single validated phrase “Hey Hermes” inside a user-started microphone foreground service. Pre-activation PCM never leaves the phone. Voice settings expose strictness (higher is harder to trigger), decoder confirmation, start-new-session behavior, and a ten-second test that exercises the real microphone and model without opening voice; the stored routing shape reserves future profile-specific selection while this release deliberately preserves the currently selected profile. The first enable downloads and SHA-256 verifies the approximately 6 MB English KWS model rather than bundling it in the APK. The detector reuses its PCM normalization buffer across equal-sized frames. A completed sherpa result is consumed once and its stream is reset immediately. Detection releases the wake microphone before entering the existing voice flow, pauses wake listening while voice owns the microphone, and resumes only after voice exits. Android’s ongoing microphone notification provides the persistent privacy status and Stop action; there is no boot or background auto-start. Opening visible Voice settings reconciles an enabled listener after app replacement or process death. In foreground-service mode, a background detection remains pending behind its notification until Hermes is visible; system-assistant integration is a separate opt-in mode.
- **System voice overlay background capture.** Opening the app-owned voice overlay while Hermes is visible starts a dedicated microphone foreground service before the user backgrounds the app. The service owns no `AudioRecord`; `VoiceViewModel`, `VoiceRecorder`, and the process-wide microphone lease remain the only capture path. Its ongoing notification exposes a terminal **Stop voice** action. Hide, Exit, Open Hermes, voice-mode shutdown, overlay creation failure, and task removal release the service so foreground-only microphone access cannot outlive the visible overlay session.
- **Android Digital Assistant mode** (opt-in, default off). A declared
  `VoiceInteractionService` becomes active only after the user selects Hermes
  for Android's Assistant role. When its separate background-wake switch is
  enabled, the system-kept service reuses the Android-local sherpa-onnx detector
  and opens a `VoiceInteractionSession` for background or locked-screen
  activation. Its separate-process UI defaults to a compact bottom bar that can
  expand in place for transcript and response detail. Expanding and collapsing
  are presentation-only; **Open full voice** disables the system surface and
  foregrounds the already-running `VoiceModeOverlay` without starting a second
  session or changing microphone ownership. Initial activation is delivered to
  the app-owned voice state machine by an explicit package-scoped message, so it
  does not bring Hermes' Activity over the app currently on screen. Ordinary
  assistant dismissal still cancels the voice turn. Connection, chat, and voice
  state machines and audio resources have one main-process,
  application-lifetime owner, allowing assistant activation to start cold
  without constructing or foregrounding `MainActivity`; full Voice later binds
  that same runtime. Cancel, error, app/process recreation, and session finish
  use the same scoped protocol. The
  wake recorder is released before the established voice recorder opens, and
  assistant listening resumes only after the session exits. This mode is
  mutually exclusive with the experimental notification-based foreground
  listener. Third-party assistants do not receive Google's dedicated low-power
  hotword hardware, so continuous local detection has a material battery cost.
- Compatible firmware may dispatch an assistant control as
  `android.speech.action.WEB_SEARCH`. Hermes accepts only that action through a
  transparent system-assistant trampoline, requires the protected platform caller
  permission and active Assistant role, ignores caller query data, and fails closed
  if the real `VoiceInteractionSession` cannot be shown. An accepted invocation
  starts listening from the same button press without replacing the foreground app.
- Only unlocked WEB_SEARCH sessions request `SHOW_WITH_ASSIST` and
  `SHOW_WITH_SCREENSHOT`. Android-provided context is bounded, excludes hidden,
  assist-blocked, and password fields, treats secure or missing screenshots as
  normal, and never logs captured content. Wake-word, power-button, ordinary
  assistant, and keyguard paths request no screen context.
- Screen context is activation-scoped, staged in app-private cache, and framed as
  untrusted user content. It belongs only to the first Standard voice turn, never
  borrows composer drafts, and is consumed only after the selected Gateway or API
  transport accepts the turn. Preflight failure retains the exact context for Try
  again; cancellation, expiry, and later turns cannot reuse it. Routes that cannot
  transport screen context reject that isolated submission instead of dropping the
  attachment silently.
- The assistant session surface remains a transparent system overlay and uses a
  bounded bottom-end Hermes card on large screens. It shows a thumbnail only when
  a screenshot exists, otherwise a semantic-context indicator only when context
  exists and the selected Standard voice path can transport it; experimental
  Realtime Agent sessions do not claim inclusion. The mic control follows the
  active voice state, close remains separate, and **Open full voice** explicitly
  transfers ownership so assistant-process cleanup cannot cancel the main-app flow.
- Stable voice integrates with `ChatViewModel` by **observing** `messages: StateFlow`; transcribed text goes through normal `chatVm.sendMessage(text)` so voice utterances appear as regular user messages in chat history. Experimental Realtime Agent creates a mirrored chat turn and applies broker events directly so tool state, transcript text, assistant deltas, and final responses appear without leaving voice mode.
- `VoiceModeOverlay` — full-screen UI with the MorphingSphere at 60% height in `voiceMode=true`, transcribed + response text, mic button supporting Tap / Hold / Continuous interaction modes.
- The optional `SYSTEM_ALERT_WINDOW` Voice control is user-invoked from an
  active in-app turn. It starts as a wide compact bar, expands for transcript,
  response, route metadata, and turn controls, and can still minimize to the
  existing bubble. It is distinct from the Assistant-role session, which does
  not require display-over-other-apps permission.
- `MorphingSphere` gains `SphereState.Listening` (soft blue/purple, subtle wobble with user amplitude) and `SphereState.Speaking` (vivid green/teal, dramatic core-warmth pulse with agent amplitude). Additive changes — existing call sites unchanged via defaulted `voiceAmplitude` / `voiceMode` params.
- Voice Settings screen off the main Settings — Output / Listening / Advanced tabs split engine/provider selection from turn-taking controls and diagnostics. Output includes the final-answer-only delivery policy shared by Standard and Realtime voice, groups the provider summary and model/voice catalog, exposes inline no-save play/stop previews for the draft model and individual voices, shows the speaking waveform on the active row, and keeps Discard separate from Save. Dropdowns come from relay-advertised provider metadata, refresh through provider-specific options routes when the selected provider changes, become searchable/grouped for large voice catalogs, and validate compatibility before saving, with advanced manual entry for raw provider/model/voice IDs. Voice routes receive the selected Hermes profile; the relay reports whether values came from profile config, relay config, or global fallback. Test Current Engine remains under Advanced and uses `/voice/output/*` playback for stable mode and `/voice/realtime-agent/*` provider-native session playback for realtime mode; normal assistant speech uses the same streaming renderer PCM path when available.

See `docs/decisions.md` → **Voice Mode — Architecture** for the historical baseline decisions. Current voice mode records PCM/WAV for STT, routes stable assistant speech through `/voice/output/*`, keeps `/voice/realtime/*` as a provider-agent lab path, and exposes `/voice/realtime-agent/*` as an experimental Hermes-brokered engine.

### Phase 6 — Future
**Priority: P3 — not for MVP**

- [x] Notification listener — shipped v0.3.0 via `HermesNotificationCompanion` (opt-in `NotificationListenerService`), exposed to the agent via `android_notifications_recent(limit=20)` over a bounded relay-side deque in `plugin/relay/channels/notifications.py`.
- [x] Clipboard bridge — shipped on the v0.4 bridge-expansion branch (`feature/A6-clipboard`): `android_clipboard_read` / `android_clipboard_write`.
- [ ] Reverse file transfer (phone → server direct upload; inbound agent → phone already shipped in v0.2.0)
- [ ] Multi-device session routing (per-device tool-call routing with an explicit "add another device" flow)
- [ ] On-device model fallback (Gemma / Qwen via MediaPipe or llama.cpp, for offline + hybrid routing)
- [ ] iOS client (evaluate Shortcuts + accessibility + App Intents feasibility first)

---

## 8. Current Scope

As of v1.0.0, the current scope is maintaining the vanilla-Hermes-first contract while keeping Relay power features additive and cleanly manageable. Vanilla Hermes Dashboard/Gateway chat, Manage, sessions, and dashboard voice must continue to work against unmodified upstream Hermes without an API-server or Relay requirement. API fallback remains optional and Relay work should be plugin-owned, diagnosable through `hermes relay doctor`, and removable without becoming a hidden requirement for the vanilla Hermes app path.

**Still non-goals for the current cadence:**
- Biometric session lock (fingerprint/face gate on terminal and/or chat resume). Tracked under Phase 4.
- Push notifications for agent messages (requires FCM + a notification channel on the relay side). Tracked under Phase 5.
- iOS client. Not on the roadmap.
- Reverse file transfer (phone → server direct upload). Inbound media (agent → phone) shipped in v0.2.0; outbound is attachments via the chat stream only.
- On-device model fallback (Phase 6).

See `Appendix A — Original Phase 0 Scope` at the end of this document for the historical "what we needed to build the first night" list, preserved for reference.

---

## 9. Key Dependencies

Current Android dependency versions. Source of truth is `gradle/libs.versions.toml` — this table is a human-readable snapshot, not authoritative.

| Dependency | Version | Purpose |
|------------|---------|---------|
| Android Gradle Plugin | 8.13.2 | Build toolchain |
| Kotlin | 2.3.20 | Language + Compose compiler plugin |
| Jetpack Compose BOM | 2026.03.01 | UI framework |
| Material 3 (via BOM) | — | Design system |
| Navigation Compose | 2.9.7 | Type-safe navigation |
| Lifecycle | 2.10.0 | ViewModel + state |
| OkHttp | 5.3.2 | WebSocket + SSE + HTTP |
| kotlinx.serialization | 1.11.0 | JSON handling |
| kotlinx.coroutines | 1.10.2 | Structured concurrency |
| DataStore Preferences | 1.1.1 | Key-value settings |
| Security Crypto | 1.1.0 | `EncryptedSharedPreferences` legacy token fallback |
| markdown-renderer (mikepenz) | 0.30.0 | Chat message rendering |
| Haze | 1.7.2 | Glassmorphism blur |
| ML Kit Barcode Scanning | 17.3.0 | QR pairing scan |
| CameraX | 1.6.0 | QR camera preview |
| ONNX Runtime Android | 1.27.0 | Shared Silero VAD and sherpa KWS runtime |
| sherpa-onnx | 1.13.4 | Experimental on-device keyword spotting |
| xterm.js | 5.x | Terminal emulator (WebView) |
| aiohttp | 3.14.1+ | Server relay |
| libtmux | 0.37+ | tmux session management |
| gradle-play-publisher | 4.0.0 | Automated Play Console upload (optional) |

---

## 10. Hermes Integration Points

| Surface | How We Connect |
|---------|---------------|
| **Gateway chat** | Dashboard `/api/auth/ws-ticket` + `/api/ws` for live thinking/reasoning and session-scoped `image.attach_bytes` / `pdf.attach` / `file.attach` uploads when Manage auth is ready |
| **API-server chat fallback** | `/api/sessions/*/chat/stream`, `/v1/chat/completions`, or `/v1/runs` based on capability probes; a known selected multiplex profile uses the shared listener's `/p/<profile>` prefix and its own encrypted profile credential |
| **API-server sessions** | `GET/POST/PATCH/DELETE /api/sessions` for CRUD |
| **Manage** | Dashboard `/api/status`, `/api/auth/me`, `/api/config`, `/api/profiles/*`, `/api/env`, `/api/model/*`, `/api/mcp/*` |
| **Vanilla Hermes voice** | Dashboard `POST /api/audio/transcribe`, WebSocket `/api/audio/speak-stream`, and `POST /api/audio/speak` compatibility fallback, all scoped by the selected profile when present |
| **Plugin system** | `register_tool()` via `ctx` for `android_*` and `desktop_*` tools |
| **Relay plugin** | `hermes pair`, `hermes relay start`, `hermes relay doctor`, `hermes relay compat`, dashboard `/relay` plugin tab |
| **Dashboard plugin** | Lives at `plugin/dashboard/`; see §10.1 below |
| **Official Desktop plugin** | Unified-package `plugin/desktop/plugin.js`; official `@hermes/plugin-sdk` only; see §10.2 below |

### 10.1 Dashboard plugin

Hermes-Relay ships a hermes-agent Dashboard Plugin that surfaces relay-specific state in the gateway's web UI. The plugin subtree at `plugin/dashboard/` is discovered when `~/.hermes/plugins/hermes-relay` points at `<repo>/plugin` or when the upstream plugin manager installs `Codename-11/hermes-relay/plugin`. The gateway scans `~/.hermes/plugins/<name>/dashboard/manifest.json` at startup. Manifest fields (`name: "hermes-relay"`, `label: "Relay"`, `icon: "Activity"`, `tab.path: "/relay"`, `tab.position: "after:skills"`) place the tab after Skills in the dashboard nav.

**Four internal tabs** render inside the single `/relay` route via a shadcn `Tabs` component:

| Tab | Data source | What it shows |
|-----|-------------|---------------|
| **Relay Management** | `/api/plugins/hermes-relay/overview` + `/sessions` | Relay version + uptime + health, paired-device list (token prefix, device name, last-seen, expires-at, per-channel grants), per-row Revoke button (placeholder pending proxy route). |
| **Bridge Activity** | `/api/plugins/hermes-relay/bridge-activity` | Ring buffer of the most recent 100 bridge commands (`method`, `path`, redacted `params`, `decision`, `sent_at`, `response_status`, `error`). Filter chips: All / Executed / Blocked / Confirmed / Timeout / Error. Polls every 5s; pausable via header Auto-refresh toggle (persisted to `localStorage`). |
| **Push Console** | `/api/plugins/hermes-relay/push` | Stub — returns `{configured: false, reason: "FCM not yet wired; …"}`. Renders an FCM-not-configured banner + link to the deferred-items doc. Real data ships when FCM is wired. |
| **Media Inspector** | `/api/plugins/hermes-relay/media` | Active `MediaRegistry` tokens (basename-only file name — absolute paths never leave the server — plus `content_type`, `size`, `created_at`, `expires_at`, `last_accessed`). TTL countdown decrements in real time (`setInterval(1000)`, cleaned up on unmount). Polls every 15s. |

**Three new loopback-gated relay routes** feed the plugin backend (plus a loopback-exempt branch on the existing `GET /sessions`). All are gated by a tiny `_require_loopback()` helper that rejects any `request.remote` other than `127.0.0.1` / `::1` with HTTP 403. Full wire-shape details in [`docs/relay-server.md`](relay-server.md#http-routes).

| Route | Method | Purpose |
|-------|--------|---------|
| `/bridge/activity` | GET | Ring buffer of recent bridge commands; `?limit=N` (max 500, default 100). |
| `/media/inspect` | GET | Active media tokens; `?include_expired=true` to include evicted entries (default false). |
| `/relay/info` | GET | Authenticated Relay contract and aggregate status: plugin/protocol versions, capabilities, per-profile enablement, counters, and health. Loopback dashboard requests may omit bearer auth. |
| `/sessions` | GET | Loopback branch now returns the full session list without a bearer (for the dashboard proxy). Non-loopback callers still require the bearer and retain the `is_current` flag. |

**Auth model.** The dashboard plugin's FastAPI router mounts under `/api/plugins/hermes-relay/*` inside the gateway process (itself bound to localhost). It forwards to the relay at `http://127.0.0.1:{HERMES_RELAY_PORT}` (default 8767). Both hops are loopback-only — no bearer is minted and no new credentials are introduced. Media paths are sanitized to basename-only in `MediaRegistry.list_all()` so even a future decision to expose these routes externally wouldn't leak filesystem layout.

**Frontend.** Source under `plugin/dashboard/src/` (JSX + esbuild), committed pre-built IIFE at `plugin/dashboard/dist/index.js` (~16 KB minified). Uses the dashboard's `window.__HERMES_PLUGIN_SDK__` global for React + shadcn primitives + `fetchJSON()` — no external HTTP library, no bundled React. See ADR 19 in [`docs/decisions.md`](decisions.md) for the architectural rationale.

### 10.2 Official Desktop plugin

The same installable `hermes-relay` plugin folder includes
`plugin/desktop/plugin.js`, discovered through the upstream unified-package path
`$HERMES_HOME/plugins/hermes-relay/desktop/plugin.js`. It imports only
`@hermes/plugin-sdk`, React, and the React JSX runtime. Backend access uses the
SDK's profile-aware `ctx.rest()` door, so Desktop and the web Dashboard share
the existing `/api/plugins/hermes-relay/*` backend without copying state or
introducing a second control plane.

The Desktop half is opt-in. Enabling or loading it registers only labeled
sidebar, status-bar, and command-palette entry points. The management pane is
registered lazily after one of those explicit actions, then restored and
focused with `ctx.panes.reveal()`. Startup, reconnect, profile change, hot
reload, update, navigation restoration, and background events never reveal or
focus it. Closing uses the official dismissible-pane lifecycle; moving and
docking use the host's native drag targets. The current SDK does not expose
programmatic move coordinates or agent-driven focus for contributed pane IDs,
so Hermes-Relay does not emulate either with private layout or Electron hooks.

The pane provides four manually refreshed views: Relay management and pairing,
bridge activity, sanitized media metadata, and remote access. Mutations require
explicit labeled actions; session revocation and remote-access changes add an
in-pane confirmation. The plugin has no timers, notifications, background
polling, arbitrary renderer code generation, telemetry, or direct external
networking. Query keys include the active profile and `ctx.rest()` supplies the
matching authenticated backend scope.

---

## Related

- **ARC** — CI/CD patterns, project structure conventions
- **Hermes Agent** — Gateway, WebAPI, plugin system, SSE streaming

---

## Appendix A — Original Phase 0 Scope

Preserved verbatim from the original scoping session. This is a historical snapshot, not a current MVP definition. See §8 for the current scope.

> **MVP Scope (Tonight)**
>
> Focus: **Phase 0 + start of Phase 1**
>
> Deliverables:
> 1. Compose project with bottom nav scaffold
> 2. WSS connection manager with channel multiplexing
> 3. Basic pairing/auth flow
> 4. Chat tab: send message → get streaming response
> 5. Server: relay with chat channel routing
> 6. GitHub Actions: build APK
>
> Non-goals for tonight:
> - Terminal (Phase 2)
> - Bridge (Phase 3)
> - Biometrics (Phase 4)
> - Release workflow (Phase 5)

All six deliverables shipped in v0.1.0. Four of the five "non-goals for tonight" have since shipped in v0.2.0 / v0.3.0; biometrics is the one remaining open item.
- **ClawPort** — Web dashboard (parallel effort, different interface surface)
