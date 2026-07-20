# Hermes-Relay — Android App

## Specification v1.4

**Status:** v1.0.0 stable. The default path supports chat, Manage, and voice on vanilla upstream Hermes without installing the Relay plugin. Relay is additive: terminal, bridge/device control, notification companion, remote access, extra/provider-native voice, desktop tooling, and dashboard Relay management. Historical phase notes remain in this file for context; the current route ownership source of truth is [`docs/upstream-surface-matrix.md`](upstream-surface-matrix.md).
**Repo:** [Codename-11/hermes-relay](https://github.com/Codename-11/hermes-relay)  
**Updated:** 2026-07-18

---

## 1. What This Is

A **native Android app** for the Hermes agent platform. Not just remote phone control — a full bidirectional interface between you and your Hermes server from anywhere.

Current capabilities are split between vanilla upstream Hermes and optional Relay surfaces:

| Surface | Requires Relay | What |
|---------|----------------|------|
| **Chat** | No | Talk to any Hermes agent profile with dashboard `/api/ws` live thinking when signed in, or API-server SSE fallback |
| **Manage** | No | Dashboard-backed config, profiles, model/provider keys, skills, MCP, and diagnostics |
| **Vanilla Hermes voice** | No | Dashboard `/api/audio/transcribe` + `/api/audio/speak` with the Manage session |
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

---

## 3. Architecture

### 3.1 High-Level

```
Android app
  |-- Vanilla Hermes chat   -> dashboard /api/ws, then API-server SSE fallback
  |-- Vanilla Hermes Manage -> dashboard /api/*
  |-- Vanilla Hermes voice  -> dashboard /api/audio/*
  |-- Relay terminal      -> relay WSS :8767
  |-- Relay bridge/tools  -> relay WSS/HTTP :8767
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
| `auth` (pairing mode) | App → Server | `{ pairing_code, ttl_seconds?, grants?, device_name, device_id }` — `ttl_seconds` / `grants` remain in the wire shape for client compatibility, but only policy attached by a loopback-only host flow is authoritative; missing host metadata uses bounded server defaults |
| `auth` (session mode) | App → Server | `{ session_token, device_name, device_id }` — ttl/grants are not re-sent; server keeps the grant table keyed on the original pair |
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

Pairing is QR-driven. The operator runs the pair command on the host — `hermes pair`, `/hermes-relay-pair` from any Hermes chat surface, or the compatibility `hermes-pair` shell shim. All share the same implementation in `plugin/pair.py`. The command probes for a running relay, generates a fresh 6-char code, pre-registers it with the relay via the loopback-only `POST /pairing/register` endpoint, then embeds the relay URL + code + **chosen TTL + per-channel grants + HMAC signature** (plus the API server credentials and optional dashboard URL) in a single QR payload. The phone scans once, **confirms the TTL and grants via a picker dialog**, and is configured for both chat AND terminal/bridge.

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
   --public-url when provided. Strict-priority ordering (lan → tailscale
   → public) with 0 = highest. --mode lan/tailscale/public emits only
   that candidate.
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
    unsupported devices. On the first wss handshake, records the cert
    SHA-256 fingerprint in CertPinStore (TOFU). Subsequent connects
    verify against the stored pin via OkHttp's CertificatePinner.
11. Future connections use the session token directly. Rate limiter,
    session expiry, and per-channel grants all enforced at the relay.
12. Session expires on ttl_seconds (or never); individual grants may
    expire sooner. Paired Devices screen lists all devices with per-row
    revoke.
```

**Old API-only QRs** (no `relay` block, no `hermes` field, or `hermes: 1`) still parse — the phone just skips the relay setup step and can be paired against a relay later via Settings. **v1 QRs with a relay block** (no TTL / grants / sig fields) still parse via `ignoreUnknownKeys`; the phone treats missing TTL as "prompt the user with defaults". **v3 QRs with an `endpoints` array** (ADR 24) also parse on v0.6.x and earlier clients — they ignore the array and keep using the top-level fields. New clients prefer `endpoints` and fall back to the top-level fields when absent.

**Re-pair explicitly resets the TOFU pin** for the target host (`applyServerIssuedCodeAndReset(code, relayUrl)` wipes `CertPinStore[host:port]`) — a QR rescan is taken as consent to possibly-new certificate material. This is the documented recovery path when a relay restarts with a new self-signed cert.

**Phase 3 (bridge)** will introduce a symmetric phone-generates-code, host-approves flow. The `POST /pairing/approve` route is stubbed in this cycle — same wire shape as `/pairing/register`, same loopback gate — with a `# TODO(Phase 3)` pointing at the pending-codes store + operator approval UI that still needs to be built.

Biometric gate on the app side for terminal access (fingerprint/face) remains planned.

#### 3.3.1 QR Wire Format — `HermesPairingPayload` (v3)

```json
{
  "hermes": 3,
  "host": "192.168.1.100",
  "port": 8642,
  "key": "api-bearer-token",
  "tls": false,
  "relay": {
    "url": "ws://192.168.1.100:8767",
    "code": "ABCD12",
    "ttl_seconds": 2592000,
    "grants": { "terminal": 2592000, "bridge": 604800 },
    "transport_hint": "ws"
  },
  "endpoints": [
    { "role": "lan",       "priority": 0,
      "api":   { "host": "192.168.1.100", "port": 8642, "tls": false },
      "relay": { "url": "ws://192.168.1.100:8767", "transport_hint": "ws" } },
    { "role": "tailscale", "priority": 1,
      "api":   { "host": "hermes.tail-scale.ts.net", "port": 8642, "tls": true },
      "relay": { "url": "wss://hermes.tail-scale.ts.net:8767", "transport_hint": "wss" } },
    { "role": "public",    "priority": 2,
      "api":   { "host": "hermes.example.com", "port": 443, "tls": true },
      "relay": { "url": "wss://hermes.example.com/relay", "transport_hint": "wss" } }
  ],
  "sig": "base64url-hmac-sha256"
}
```

- `hermes` — payload version. `1` is the legacy shape (no new fields); `2` is set when any v2-only field (`ttl_seconds`, `grants`, `transport_hint`) is present in the `relay` block; `3` is set when `endpoints` is present (ADR 24). All three versions parse on the current Android client.
- `endpoints` — **optional** ordered list of endpoint candidates. When present, the phone uses these in strict-priority order (0 = highest) and re-probes reachability on network change. When absent, the phone synthesizes a single priority-0 candidate from the top-level `host`/`port`/`tls` + `relay.url`/`transport_hint` fields. `role` is an open string (known values `lan` / `tailscale` / `public` get styled UI; anything else renders as "Custom VPN (<role>)"). Per-endpoint entries intentionally carry **only** `api` + `relay` — the pairing code, TTL, and grants stay at the top level because they're per-pair artifacts, not per-endpoint. Full schema in ADR 24.
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
| Token storage | `SessionTokenStore` — `KeystoreTokenStore` (StrongBox-preferred via `setRequestStrongBoxBacked`) with fallback to `LegacyEncryptedPrefsTokenStore` (TEE-backed `EncryptedSharedPreferences`). One-shot lossless migration on first launch post-upgrade. `hasHardwareBackedStorage` flag surfaced in UI. |
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
- **Top bar (three-layer agent model, v0.6.0).** Layout from left to right:
  1. **Connection chip** — tap to open `ConnectionSwitcherSheet` (all paired servers + health indicator). Auto-hidden when you only have one Connection. See `docs/decisions.md` §19.
  2. **Agent name + tappable region** — tap to open the consolidated **agent sheet** (bottom sheet) holding Profile + Personality selection and per-session info/analytics (message count, tokens in/out, avg TTFT). Sheet is scrollable. Toast confirmations fire on Profile/Personality switch. Replaces the separate `ProfilePicker` and `PersonalityPicker` top-bar chips that shipped in intermediate v0.5.x builds.
  3. Remaining top-bar actions (session drawer hamburger, ambient toggle, etc.).
- **Session drawer** (swipe from left or hamburger icon) — session list with title, timestamp, message count. Create, switch, rename, delete. When a persisted title is absent, use upstream's first-user-message `preview`, matching the Hermes Desktop session picker; show "Untitled" only when neither value exists.
- **Concurrent Gateway chats** — switching sessions, profiles, drafts, or Threads detaches the visible turn without sending `session.interrupt`; each running chat keeps a connection/profile/session-scoped checkpoint and reattaches to its live Gateway session when reopened. Explicit Stop still interrupts. SSE fallback stays single-stream and cancels on navigation.
- **Chat view** — message bubbles with markdown rendering, streaming text, tool call cards (Off/Compact/Detailed display modes)
- **Input bar** — text field with 4096 char limit, `/` palette button, send button, stop button during streaming. Inline autocomplete on `/` keystroke + full searchable command palette (bottom sheet). Commands sourced from: 29 gateway built-ins, dynamic personalities from `config.agent.personalities`, and server skills from native `GET /v1/skills`.
- **Empty state** — Logo + "Start a conversation" + suggestion chips that populate input
- **Agent sheet — Profile section (v0.6.0, updated 2026-05-18)** — upstream Hermes profiles auto-discovered by the relay at `~/.hermes/profiles/*/`. Selecting one routes chat/session calls to that profile's advertised `api_server_url` when present, giving proper Hermes isolation for sessions, memory, tools, provider auth, and SOUL/default model. If no profile API route is advertised, the app falls back to overlaying `model` + `SOUL.md` (as `system_message`) on the active Connection's API server. Selection is persisted per Connection/profile context. Hidden when the server advertises no profiles. See `docs/decisions.md` §21.
- **Agent sheet — Personality section** — personalities fetched from `GET /api/config` (`config.agent.personalities`). Shows server default (from `config.display.personality`) + all configured. Active personality name shown on assistant chat bubbles.
- **Streaming dots** — animated pulsing 3-dot indicator replaces static "streaming..." text
- Displays: streaming delta text, tool progress cards (auto-expand while running, auto-collapse on complete), thinking/reasoning blocks (collapsible), per-message token counts + cost

### Terminal Tab
- **Full-screen terminal emulator** (xterm.js in WebView)
- **Session picker** — attach to existing tmux sessions or create new
- **Toolbar** — Ctrl, Tab, Esc, Arrow keys (soft keys for mobile)
- **Biometric gate** — fingerprint/face required before showing terminal
- Supports: full ANSI color, scrollback, text selection, copy/paste

### Bridge Tab
Shipped in v0.3.0; card hierarchy rewritten in v0.4.1. Rendered by `BridgeScreen.kt` + `BridgeViewModel` in this order:

1. **Master toggle card** (`BridgeMasterToggle`) — headline "Allow Agent Control" switch with a `MASTER` pill and leading "Master switch —" subtitle copy so the parent-gate role is legible at a glance. Gated on accessibility permission being granted; tapping the Switch when Accessibility is not granted surfaces a snackbar ("Accessibility Service must be enabled first.") with an "Open Settings" action that deep-links to `ACTION_ACCESSIBILITY_SETTINGS` rather than silent-dropping the tap. Inline device / battery / screen / current-app rows live in-card (the old standalone `BridgeStatusCard` was dropped from the layout in v0.4.1). Info icon opens a Play-review explanation dialog that also names the "Hermes has device control" persistent notification owned by the master switch.
2. **Permission checklist** (`BridgePermissionChecklist`) — tiered four-section layout shipped in v0.4.1 (Core bridge / Notification companion / Voice & camera / Sideload features). Reads the same `AppPermissionStatusProbe` snapshot as Settings -> Permissions so Bridge and the central review surface agree on Android grants, special-access toggles, and screen-capture session state. Tap-to-open Android Settings via `ACTION_ACCESSIBILITY_SETTINGS`, `ACTION_MANAGE_OVERLAY_PERMISSION`, `enabled_notification_listeners`, and per-row runtime-permission affordances; rows fall back to `ACTION_APPLICATION_DETAILS_SETTINGS` when a runtime permission has been permanently denied. Optional rows render an "Optional" Material 3 pill in a `FlowRow` with `softWrap=false` so the pill never wraps internally on narrow titles. Re-probes on `Lifecycle.Event.ON_RESUME` so returning from Android Settings flips rows green without navigation churn.
3. **Advanced divider** — visual separator between "operate the bridge" and "expand what the bridge can do".
4. **Unattended Access card** (`UnattendedAccessRow`, sideload-only) — opt-in toggle gated on the master toggle (`enabled = masterEnabled`; subtitle reads "Requires Agent Control — enable the master switch above first." when master is off). First-enable shows the scary one-time dialog covering the security model + credential-lock limitation + how to disable. Credential-lock warning renders as an inline `KeyguardDetectedAlert` Surface band inside this card (was a standalone chip pre-v0.4.1, inlined so the warning lives next to the toggle that triggers it).
5. **Safety summary card** (`BridgeSafetySummaryCard`) — blocklist count / destructive-verb count / countdown timer (`in MM:SS` during an active idle window, else `N min idle`). Tap-through to `BridgeSafetySettingsScreen` for editing the blocklist / destructive verbs / auto-disable timer / status overlay / confirmation timeout.
6. **Activity log** (`BridgeActivityLog`) — scrollable `LazyColumn` capped at 320dp + `MAX_LOG_ENTRIES=100`. Tap-to-expand rows showing timestamp, status (Pending / Success / Failed / Blocked), result text, and optional screenshot token. DataStore-backed via `BridgePreferences`.

The bridge UI drives — and is driven by — Tier 5 safety-rails (`BridgeSafetyManager`, `BridgeForegroundService`, `BridgeStatusOverlay`, `AutoDisableWorker`). See `docs/decisions.md` and `CLAUDE.md`'s file table for the full wiring.

**Global unattended-access affordance (v0.4.1).** When master + unattended are both on (sideload only), `UnattendedGlobalBanner` renders as a 28dp amber strip at the top of `RelayApp`'s scaffold on every tab — pulsing dot + "Unattended access ON — agent can wake and drive this device" + chevron → tap navigates to Bridge. Theme-aware colours (amber-on-dark in dark mode, dark-amber-on-pale-amber in light). The banner handles visibility while the user is INSIDE Hermes-Relay; the existing WindowManager `BridgeStatusOverlayChip` handles visibility when the app is BACKGROUNDED. See `docs/decisions.md` §18 for the split rationale.

### Settings Tab
- **Active agent card (v0.6.0)** — top-of-screen summary card showing the current Connection / Profile / Personality. Tap navigates to Chat and auto-opens the agent sheet via the `openAgentSheet` nav arg, giving Settings-originating users a one-tap path to change agent context without leaving the flow.
- **Connections** (v0.6.0+) — lists every paired Hermes server with a per-card status chip. Actions: rename (inline), re-pair (reuses `ConnectionWizard` with `connectionId` nav arg), revoke, remove. Add-connection button launches the standard QR flow. Settings briefly treats a paired + disconnected relay as **Connecting** during the reconnect grace window, then promotes it to **Relay unreachable - tap to reconnect** if the live socket does not recover. API / Relay / Session detail sheets include compact sanitized recent-activity tails, and **Settings -> Diagnostics** shows the consolidated app-level API, relay, session, endpoint, and voice activity buffer. See `docs/decisions.md` §19.
- **Connection (single-server settings)** — summary-first detail for one Hermes installation. Dashboard/Gateway health drives standard Chat, Manage, Sessions, and Voice readiness. API fallback and Relay extensions appear as independently optional capabilities. Advanced configuration exposes manual Dashboard, API, and Relay endpoints plus their native credentials; missing API or Relay settings never make a healthy Dashboard/Gateway connection look broken. Pairing-code and QR fallbacks remain available for Relay and compatibility setups. Transport security posture and paired-device grants remain visible without leading the normal setup flow with ports or bearer keys.
- **Chat** — Show reasoning toggle, smooth auto-scroll toggle (live-follow streaming, default on), show token usage toggle, app context prompt toggle, tool call display (Off/Compact/Detailed), streaming endpoint selector (`auto` / `sessions` / `runs`), Stats for Nerds (analytics charts)
- **Voice** — route-aware voice engine selector (`Vanilla Hermes` via dashboard audio, `Relay Voice Output`, and experimental `Realtime Agent`), global interaction mode (tap / hold / continuous), silence threshold slider, Auto-TTS toggle, selected-engine cards for dashboard or relay-backed settings, language picker, and a Test Current Engine card. Vanilla Hermes voice depends on Manage/dashboard auth; Relay-backed engines run a fast relay health preflight before uploading audio or opening a realtime provider session so a hung relay surfaces as a connection error instead of an indefinite Thinking state.
- **Notification companion** — opt-in status, "Open Android Settings" action, test notification dump
- **Permissions** — central permission/capability review screen linked from Settings and onboarding. It makes the Vanilla Hermes path explicit ("Chat and Manage" need no Android runtime grant), lists optional camera/microphone/notification access with current status and Android Settings links, and shows sideload-only Device Control requirements only in the sideload flavor.
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

The relay server is **not involved** in chat streaming itself. It remains the home for bridge, terminal, and — as of 2026-04-11 — **inbound media delivery** (see 6.2a).

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
- `GET /media/{token}` — requires `Authorization: Bearer <session_token>` against the existing `SessionManager` (same token WSS uses). Streams the file via `web.FileResponse` with the registered content type plus `Content-Disposition: inline; filename="..."` if the entry has a file name. 401 on missing/invalid bearer, 404 on unknown/expired token.
- `GET /media/by-path?path=<abs>&content_type=<optional>` — requires bearer auth. Shares the same sandbox validation as `/media/register` via a common `validate_media_path()` helper: absolute path, `realpath`-resolves under an allowed root, exists, is a regular file, fits under the size cap. Content-Type is the phone's hint if provided, otherwise guessed via `mimetypes.guess_type()`. This route exists specifically for **LLM-emitted bare-path markers** — upstream `agent/prompt_builder.py` instructs the model to include `MEDIA:/absolute/path/to/file` in its response text, so the bare-path form is the agent's native output, not just a fallback. 401 auth, 403 sandbox, 404 missing file.
- `POST /media/upload` — bearer-auth'd small upload route for phone-originated media. Accepts base64 content, writes a temp file, and registers it into the same media registry.

**Phone:** parse → fetch → cache → render:
1. `ChatHandler.scanForMediaMarkers()` runs on every `onTextDelta`, unconditionally (not gated on `parseToolAnnotations`). Matches `MEDIA:hermes-relay://([A-Za-z0-9_-]+)` and fires `onMediaAttachmentRequested(messageId, token)`. A second regex matches the bare-path form `MEDIA:(/\S+)` and fires `onMediaBarePathRequested(messageId, path)` — the ViewModel then calls `RelayHttpClient.fetchMediaByPath()` to pull bytes via `GET /media/by-path`. A per-session `dispatchedMediaMarkers` set dedupes between real-time streaming scans and the post-stream `finalizeMediaMarkers` reconciliation pass. `loadMessageHistory` (invoked by the `session_end reload` pattern at every stream complete) re-runs the same parser on server-stored content so client-injected attachments survive the wholesale state replace. Both marker forms are stripped from the rendered message text.
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

**Safety integration.** All HTTP routes except `/ping`, `/current_app`, and `/return_to_hermes` are gated in `BridgeCommandHandler` on the Bridge master toggle (`bridge_master_enabled` DataStore flag) and the Tier 5 three-stage safety check:
1. **Blocklist gate** — `BridgeSafetyManager.checkPackageAllowed(currentApp)` returns 403 `{"error": "blocked package <name>"}` when the foreground package is in the blocklist (~30 banking/payments/password-manager/2FA defaults seeded via `DEFAULT_BLOCKLIST`).
2. **Destructive-verb confirmation** — `/tap_text` and `/type` commands whose text matches the user's destructive-verb regex list (`send` / `pay` / `delete` / `transfer` / `confirm` / `submit` / ...) suspend on a `CompletableDeferred<Boolean>` under a `withTimeout`, waiting for the user to Allow / Deny via the `BridgeStatusOverlay` modal. **Tier C `android_call`, `android_send_sms`, `android_share_media`, and `android_send_mms` always go through this gate regardless of body content** — these actions leave the phone or hand user data to another app. Denied or timed-out commands return 403 `{"error": "user denied destructive action", "reason": "confirmation_denied_or_timeout"}`.
3. **Auto-disable reschedule** — every successful command resets the idle countdown on `BridgeSafetyManager.rescheduleAutoDisable`, which flips master off after the configured idle window (default 30 min, clamped 5..120).

The newly added Tier A/B tools all flow through the same `BridgeCommandHandler` dispatch and are covered by the existing gates without additional wiring. Tier A tools that only *read* (e.g. `android_screen_hash`, `android_clipboard_read`, `android_describe_node`) skip the destructive-verb check but still hit the blocklist and master-enable gates. `android_send_intent` and `android_broadcast` hit the blocklist gate keyed on the target `package` (not just the foreground app) so an agent can't bypass the blocklist by firing an Intent at a blocked target from an allowed foreground.

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
- [x] App: Tier 5 safety rails — `BridgeSafetyManager` (blocklist + destructive-verb confirmation + auto-disable timer), `BridgeForegroundService` (persistent "Hermes has device control" notification), `BridgeStatusOverlay` (confirmation modal + optional floating chip)
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
- `GET/PATCH /voice/output/config`, `POST /voice/output/session`, and `GET /voice/output/{session_id}` — relay-mediated streaming TTS renderer sessions. Android sends final Hermes text or brokered tool-status text and receives mono PCM deltas for direct `AudioTrack` playback. Session responses include resumable-session metadata and PCM events carry `event_id`/`audio_event_id`, so short route changes during stable speech playback can resume and replay missed audio without re-rendering. Config responses include provider option metadata (`providers[].models`, `providers[].voices`, `providers[].languages`, `providers[].sample_rates`) for first-class dropdowns.
- `GET/PATCH /voice/realtime/config`, `POST /voice/realtime/session`, and `GET /voice/realtime/{session_id}` — relay-mediated realtime provider-agent sessions for lab/dev experiments. Android can send PCM input events and receives mono PCM provider deltas for direct `AudioTrack` playback. Realtime config responses expose the same provider option shape where known.
- `GET/PATCH /voice/realtime-agent/config`, `POST /voice/realtime-agent/session`, and `GET /voice/realtime-agent/{session_id}` — experimental Hermes-brokered Realtime Agent engine. The broker binds active profile/chat session/auth, streams Android mic PCM to a native realtime provider such as `xai_realtime` or `openai_realtime`, normalizes provider transcript/audio/function-call events, mirrors Hermes session/tool/confirmation events into Android, and returns compact Hermes tool results to the provider for concise spoken follow-up. Session responses include resumable-session metadata (`resume_token`, `resume_supported`, `resume_ttl_ms`); server events carry `event_id`, audio deltas carry `audio_event_id`, and Android can resume a detached session through the current `effectiveRelayUrl` after short Wi-Fi/cellular/LAN/Tailscale changes without starting a second Hermes run. A replacement route is usable only after relay `voice.session.resumed` confirmation; socket generation + resume-episode claims reject stale failure/close/fatal callbacks, unacknowledged input is replayed atomically, and each route-loss episode owns a bounded retry budget that starts at loss rather than session prewarm. Terminal exhaustion detaches session-owned reconnect UI so a stopped retry loop cannot leave an active task pill behind. The only provider-facing tool surface is `hermes_run_task`, `hermes_get_status`, `hermes_cancel`, and `hermes_confirm`.
- `GET /voice/output/providers/{provider_id}/options`, `GET /voice/realtime/providers/{provider_id}/options`, and `GET /voice/realtime-agent/providers/{provider_id}/options` — provider-specific option refresh before saving. Android calls these when a provider is selected so dynamic account-backed choices can be fetched by the relay without exposing provider secrets. xAI refreshes built-in/paginated custom voices when API/OAuth auth is available; ElevenLabs refreshes voices/models/languages with its API key; OpenAI uses static documented voice choices. Realtime Agent provider payloads include `supports_realtime_agent_native` so render/lab-only realtime support is not confused with native speech-to-speech Hermes tooling. Responses include `schema_version`, grouped voice metadata, recommended/custom flags, and model/voice compatibility hints when known. Unknown or unauthenticated discovery falls back to static provider metadata plus manual entry.
- `POST /voice/output/providers/{provider_id}/validate`, `POST /voice/realtime/providers/{provider_id}/validate`, and `POST /voice/realtime-agent/providers/{provider_id}/validate` — pre-save validation for provider/model/voice/sample-rate selections. Unknown manual IDs return warnings; explicit incompatibilities return blocking errors.
- Voice-output provider defaults are relay-owned under `voice_output:` in `~/.hermes-relay/config.yaml` (or `RELAY_VOICE_OUTPUT_CONFIG`), then overridden by `RELAY_VOICE_OUTPUT_*` env vars for temporary tests. Authenticated operator clients may patch safe defaults (`enabled`, `provider`, `model`, `voice`, `sample_rate`, `language`, `codec`, `optimize_streaming_latency`, `text_normalization`, `auto_speech_tags`, `fallback_enabled`) through the relay. With `?profile=<name>`, the patch writes that profile's `voice_output:` section. Provider secrets and local auth paths stay server-side. `auto_speech_tags` is an xAI enhanced-voice control: when the renderer is `xai_tts` the relay applies `upstream_voice.apply_xai_speech_tags()` (upstream's inline/wrapping tone markers) to each chunk before rendering, so the streaming path matches the basic `/voice/synthesize` tone behavior. The `voice_lab` renderer set is xai/openai/elevenlabs — there is no Gemini streaming provider, so Gemini enhanced voice is `/voice/synthesize`-only.
- Realtime provider defaults are relay-owned under `realtime_voice:` in `~/.hermes-relay/config.yaml` (or `RELAY_REALTIME_VOICE_CONFIG`), then overridden by `RELAY_REALTIME_VOICE_*` env vars for temporary tests. Authenticated operator clients may patch safe defaults (`enabled`, `provider`, `model`, `voice`, `sample_rate`) through the relay. With `?profile=<name>`, the patch writes that profile's `realtime_voice:` section. Provider secrets and local auth paths stay server-side.
- Voice routes are gated by narrow voice bearer auth (`voice:config`, `voice:stt`, `voice:tts`, or `voice:realtime`) or a valid Hermes API bearer under the transport guard.

**App-side:**
- `VoiceRecorder` (`AudioRecord` / WAV / 16 kHz mono PCM) exposes both a STT upload file and raw PCM bytes for the realtime websocket input events.
- `VoicePlayer` (Media3 ExoPlayer + Visualizer) remains the fallback `/voice/synthesize` playback surface.
- `RealtimePcmPlayer` streams `/voice/output/*`, `/voice/realtime/*`, and `/voice/realtime-agent/*` PCM deltas directly to `AudioTrack`.
- `VoiceViewModel` state machine (`Idle / Listening / Transcribing / Thinking / Speaking / Error`). Assistant text is sanitized (markdown / tool-annotations / URLs / emoji-set stripped) on each delta before a coalescing chunker (`MIN_COALESCE_LEN=40`, `MAX_BUFFER_LEN=400` secondary-break escape, 800 ms timer flush) emits sentence-scale chunks. The default queue calls `/voice/output/*` for exact renderer PCM playback; failed output turns fall back to the existing `/voice/synthesize` synth/play workers. The same stream observer watches Hermes-owned `ToolCall` state and speaks bounded status lines for running tools; execution, approval, and tool results remain in the Hermes chat/relay loop.
- Server-side, `/voice/synthesize` runs a matching sanitizer (`plugin/relay/tts_sanitizer.py`) before handing text to the upstream `text_to_speech_tool` — defense-in-depth for any client that doesn't pre-sanitize.
- **Barge-in** (opt-in, default off). While in `Speaking`, a `BargeInListener` runs a duplex `AudioRecord` (16 kHz mono PCM, `VOICE_COMMUNICATION` source) feeding 32 ms frames through a Silero VAD (`com.github.gkonovalov:android-vad:silero`). `AcousticEchoCanceler` + `NoiseSuppressor` attach to the ExoPlayer audio session so TTS output doesn't retrigger VAD. A single raw speech frame → `VoicePlayer.duck()` (volume 0.3f) with a 500 ms un-duck watchdog. `N` consecutive frames (2–3, sensitivity-tuned) → `interruptSpeaking()` (same cancellation path V4 wired for user taps). A 600 ms watchdog on `VoiceRecorder.amplitude` then decides: if the user keeps talking, new turn proceeds normally; if silence wins AND `resumeAfterInterruption=true`, `VoiceViewModel` re-enqueues the unplayed chunks from `spokenChunks[lastInterruptedAtChunkIndex+1..]` and flips back to `Speaking`. Settings UI exposes `BargeInPreferences` (enabled / sensitivity ∈ `Off/Low/Default/High` / resume) with an `AcousticEchoCanceler.isAvailable()`-driven compatibility badge.
- Stable voice integrates with `ChatViewModel` by **observing** `messages: StateFlow`; transcribed text goes through normal `chatVm.sendMessage(text)` so voice utterances appear as regular user messages in chat history. Experimental Realtime Agent creates a mirrored chat turn and applies broker events directly so tool state, transcript text, assistant deltas, and final responses appear without leaving voice mode.
- `VoiceModeOverlay` — full-screen UI with the MorphingSphere at 60% height in `voiceMode=true`, transcribed + response text, mic button supporting Tap / Hold / Continuous interaction modes.
- `MorphingSphere` gains `SphereState.Listening` (soft blue/purple, subtle wobble with user amplitude) and `SphereState.Speaking` (vivid green/teal, dramatic core-warmth pulse with agent amplitude). Additive changes — existing call sites unchanged via defaulted `voiceAmplitude` / `voiceMode` params.
- Voice Settings screen off the main Settings — voice engine selector, global interaction mode, silence threshold, selected-engine cards for editable voice-output provider/model/voice/language/latency/fallback settings or editable experimental Realtime Agent defaults, a global fallback TTS card, STT provider labels, active profile/scope labels, and Test Current Engine. Dropdowns come from relay-advertised provider metadata, refresh through provider-specific options routes when the selected provider changes, become searchable/grouped for large voice catalogs, and validate compatibility before saving, with advanced manual entry for raw provider/model/voice IDs. Voice routes receive the selected Hermes profile; the relay reports whether values came from profile config, relay config, or global fallback. Test Current Engine uses `/voice/output/*` playback for stable mode and `/voice/realtime-agent/*` provider-native session playback for realtime mode; normal assistant speech uses the same streaming renderer PCM path when available.

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
| xterm.js | 5.x | Terminal emulator (WebView) |
| aiohttp | 3.14.1+ | Server relay |
| libtmux | 0.37+ | tmux session management |
| gradle-play-publisher | 4.0.0 | Automated Play Console upload (optional) |

---

## 10. Hermes Integration Points

| Surface | How We Connect |
|---------|---------------|
| **Gateway chat** | Dashboard `/api/auth/ws-ticket` + `/api/ws` for live thinking/reasoning when Manage auth is ready |
| **API-server chat fallback** | `/api/sessions/*/chat/stream`, `/v1/chat/completions`, or `/v1/runs` based on capability probes; a known selected multiplex profile uses the shared listener's `/p/<profile>` prefix |
| **API-server sessions** | `GET/POST/PATCH/DELETE /api/sessions` for CRUD |
| **Manage** | Dashboard `/api/status`, `/api/auth/me`, `/api/config`, `/api/profiles/*`, `/api/env`, `/api/model/*`, `/api/mcp/*` |
| **Vanilla Hermes voice** | Dashboard `POST /api/audio/transcribe` and `POST /api/audio/speak` |
| **Plugin system** | `register_tool()` via `ctx` for `android_*` and `desktop_*` tools |
| **Relay plugin** | `hermes pair`, `hermes relay start`, `hermes relay doctor`, `hermes relay compat`, dashboard `/relay` plugin tab |
| **Dashboard plugin** | Lives at `plugin/dashboard/`; see §10.1 below |

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
