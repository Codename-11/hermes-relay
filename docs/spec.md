# Hermes-Relay — Android App

## Specification v1.3

**Status:** v0.3.0 shipped to Play Store internal testing + sideload track. Phase 0, Phase 1, Phase 2 (terminal preview), Phase 3 (bridge channel), Phase 4 (security hardening per ADR 15), and Phase 5 (polish + CI/CD) are partially-or-fully shipped. Phase V (voice mode) shipped 2026-04-12. v0.4 bridge feature expansion in progress on `feature/bridge-feature-expansion` — see `docs/plans/2026-04-13-bridge-feature-expansion.md`.  
**Repo:** [Codename-11/hermes-relay](https://github.com/Codename-11/hermes-relay)  
**Updated:** 2026-04-13

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
| `auth` (pairing mode) | App → Server | `{ pairing_code, ttl_seconds?, grants?, device_name, device_id }` — `ttl_seconds` / `grants` come from the phone's TTL picker dialog; host metadata wins over phone metadata when both are present |
| `auth` (session mode) | App → Server | `{ session_token, device_name, device_id }` — ttl/grants are not re-sent; server keeps the grant table keyed on the original pair |
| `auth.ok` | Server → App | `{ session_token, server_version, profiles[], expires_at, grants, transport_hint }` — see below |
| `auth.fail` | Server → App | `{ reason }` |
| `ping` | Both | `{ ts }` |
| `pong` | Both | `{ ts }` |

**`auth.ok` extended fields** (added in ADR 15 — see `docs/decisions.md`):

| Field | Type | Meaning |
|-------|------|---------|
| `expires_at` | epoch seconds or `null` | Session lifetime. `null` means never-expire (user explicitly picked "Never" in the TTL picker). Server-side `math.inf` serializes as `null`. |
| `grants` | `{ channel: epoch \| null }` | Per-channel expiries. Keys today: `chat`, `terminal`, `bridge`. Each grant is clamped to the session lifetime — a grant cannot outlive its session. `null` means the grant shares the session's never-expire. |
| `transport_hint` | `"wss"` / `"ws"` / `"unknown"` | What the server believes the phone is actually connected over. Drives the transport security badge and the TTL picker's default option on re-pair. |

#### Channel: `chat`
**Note:** Chat connects directly to the Hermes API Server via HTTP/SSE (see Section 6.2) — it does not traverse the relay. Voice, bridge, terminal, notifications, and inbound media DO go through the relay. The chat SSE event types are:

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

Pairing is QR-driven. The operator runs the pair command on the host — either `/hermes-relay-pair` from any Hermes chat surface (backed by the `devops/hermes-relay-pair` skill) or the `hermes-pair` shell shim (a thin wrapper around `python -m plugin.pair`). Both share the same implementation in `plugin/pair.py`. The command probes for a running relay, generates a fresh 6-char code, pre-registers it with the relay via the loopback-only `POST /pairing/register` endpoint, then embeds the relay URL + code + **chosen TTL + per-channel grants + HMAC signature** (and the API server credentials) in a single QR payload. The phone scans once, **confirms the TTL and grants via a picker dialog**, and is configured for both chat AND terminal/bridge.

```
1. Operator runs /hermes-relay-pair (or hermes-pair) on the Hermes host,
   optionally with --ttl <duration> and --grants terminal=7d,bridge=1d.
2. The pair command reads the API server config (host/port/key) from
   ~/.hermes/config.yaml or ~/.hermes/.env.
3. If a relay is reachable at localhost:RELAY_PORT (default 8767):
   a. Mint a fresh 6-char code from A-Z / 0-9
   b. Compute the transport hint (wss / ws) from the relay's TLS config
   c. POST /pairing/register { code, ttl_seconds, grants, transport_hint }
      (loopback only — the relay clears all rate-limit blocks on success
      so stale blocks don't prevent legitimate re-pair)
   d. Build the payload dict, HMAC-SHA256-sign it with the host-local
      secret at ~/.hermes/hermes-relay-qr-secret (auto-created, 32 bytes,
      mode 0o600), attach as `sig` field.
4. Render QR + plain-text block (includes "Pair: for 30 days" or
   "Pair: indefinitely" + per-channel grant labels).
5. Phone scans the QR → parses HermesPairingPayload (see §3.3.1).
6. Phone stores the API server URL + key and, if present, the relay URL.
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

**Old API-only QRs** (no `relay` block, no `hermes` field, or `hermes: 1`) still parse — the phone just skips the relay setup step and can be paired against a relay later via Settings. **v1 QRs with a relay block** (no TTL / grants / sig fields) still parse via `ignoreUnknownKeys`; the phone treats missing TTL as "prompt the user with defaults". Forward-compat: future v3+ QRs will ignore-unknown-keys in existing clients.

**Re-pair explicitly resets the TOFU pin** for the target host (`applyServerIssuedCodeAndReset(code, relayUrl)` wipes `CertPinStore[host:port]`) — a QR rescan is taken as consent to possibly-new certificate material. This is the documented recovery path when a relay restarts with a new self-signed cert.

**Phase 3 (bridge)** will introduce a symmetric phone-generates-code, host-approves flow. The `POST /pairing/approve` route is stubbed in this cycle — same wire shape as `/pairing/register`, same loopback gate — with a `# TODO(Phase 3)` pointing at the pending-codes store + operator approval UI that still needs to be built.

Biometric gate on the app side for terminal access (fingerprint/face) remains planned.

#### 3.3.1 QR Wire Format — `HermesPairingPayload` (v2)

```json
{
  "hermes": 2,
  "host": "172.16.24.250",
  "port": 8642,
  "key": "api-bearer-token",
  "tls": false,
  "relay": {
    "url": "ws://172.16.24.250:8767",
    "code": "ABCD12",
    "ttl_seconds": 2592000,
    "grants": {
      "terminal": 2592000,
      "bridge": 604800
    },
    "transport_hint": "ws"
  },
  "sig": "base64url-hmac-sha256"
}
```

- `hermes` — payload version. `1` is the legacy shape (no new fields); `2` is set when any v2-only field (`ttl_seconds`, `grants`, `transport_hint`) is present in the `relay` block. Both versions parse on phones with the v2 parser.
- Top-level fields (`host`/`port`/`key`/`tls`) configure the direct-chat Hermes API Server. Unchanged since v1.
- `relay` — **optional** and nullable. Present only when the pair command found a running relay and successfully pre-registered a pairing code with it.
- `relay.url` — full WebSocket URL (`ws://` for dev, `wss://` for production).
- `relay.code` — 6-char one-shot pairing code from `A-Z / 0-9`. Expires 10 minutes after registration.
- `relay.ttl_seconds` — **optional**. Operator-chosen session lifetime in seconds. `0` means never expire. When present, the phone's TTL picker preselects this value; when missing, the phone picks a default based on transport hint (wss → 30d, ws → 7d). The user always confirms via the picker dialog.
- `relay.grants` — **optional**. Per-channel expiries in seconds-from-now. Map keys: `"terminal"`, `"bridge"`. Each grant is clamped server-side to the overall session TTL — a grant cannot outlive its session. Default caps if unspecified: terminal 30 days, bridge 7 days.
- `relay.transport_hint` — **optional**. `"wss"` or `"ws"`. Used by the phone as the default for the transport security badge and to compute the TTL picker's default option.
- `sig` — **optional**. Base64 HMAC-SHA256 of the canonicalized payload (sort_keys=True, separators=(",", ":"), `sig` field excluded from canonical form). Computed with a host-local secret at `~/.hermes/hermes-relay-qr-secret`. Phones parse and store `sig` but **do not verify it yet** — full verification requires a secret distribution mechanism the protocol doesn't yet define.
- The Android parser uses `kotlinx.serialization` with `ignoreUnknownKeys = true`, so future fields can be added without breaking older app builds. `RelayPairing.ttlSeconds` / `grants` / `transportHint` are all nullable with defaults.

Implementation references:
- Server-side payload builder + CLI flags: `plugin/pair.py` → `build_payload(sign=True)` / `pair_command()` / `parse_duration()` / `parse_grants()`
- Server-side HMAC: `plugin/relay/qr_sign.py` → `canonicalize` / `sign_payload` / `verify_payload` / `load_or_create_secret`
- Phone-side parser: `app/src/main/kotlin/.../ui/components/QrPairingScanner.kt` → `HermesPairingPayload` / `RelayPairing`
- Phone-side TTL picker: `app/src/main/kotlin/.../ui/components/SessionTtlPickerDialog.kt`
- Relay registration endpoint: `plugin/relay/server.py` → `handle_pairing_register` (see §6 for details)

### 3.4 Security

| Layer | Implementation |
|-------|---------------|
| Transport (default) | WSS / TLS 1.3 (**preferred**) |
| Transport (opt-in) | Plain `ws://` — gated on `InsecureConnectionAckDialog` consent + reason picker (LAN-only / Tailscale or VPN / Local dev). Reason is displayed, not enforced — operator intent is the trust model. |
| Transport indicator | `TransportSecurityBadge` in Settings + Session sheet + Paired Devices card. Three states: 🔒 secure / 🔓 insecure with reason / 🔓 insecure unknown. |
| Pairing (host → phone) | `hermes-pair` / `/hermes-relay-pair` → `POST /pairing/register` (loopback-only) → QR embedded in operator's terminal or chat. |
| Pairing (phone → host, Phase 3) | Stubbed at `POST /pairing/approve` — same wire shape, same loopback gate. Real UX pending bridge work. |
| Session lifetime | User-selected at pair: 1d / 7d / 30d / 90d / 1y / **never**. Never is always selectable; operator intent is the trust model. |
| Per-channel grants | One session token carries `{chat, terminal, bridge}` per-channel expiries. Terminal default cap 30d, bridge default cap 7d, both clamped to session lifetime. |
| Auth envelope | `{pairing_code, ttl_seconds, grants, device_name, device_id}` for pairing mode; `{session_token, device_name, device_id}` for session-mode re-auth. Host metadata wins over phone metadata when both are present. |
| `auth.ok` response | `{session_token, expires_at, grants, transport_hint, profiles, server_version}`. `math.inf` expiries serialize as `null`. |
| Rate limiting | 5 auth attempts / 60s → 5-min block. **`/pairing/register` clears all blocks on success** so legitimate re-pair after a relay restart works immediately. |
| Token storage | `SessionTokenStore` — `KeystoreTokenStore` (StrongBox-preferred via `setRequestStrongBoxBacked`) with fallback to `LegacyEncryptedPrefsTokenStore` (TEE-backed `EncryptedSharedPreferences`). One-shot lossless migration on first launch post-upgrade. `hasHardwareBackedStorage` flag surfaced in UI. |
| Cert pinning | TOFU via `CertPinStore` — SHA-256 SPKI fingerprint recorded per `host:port` on first successful wss connect. Subsequent connects verify via OkHttp `CertificatePinner`. Pin wiped explicitly on QR re-pair (`applyServerIssuedCodeAndReset`). Plain ws:// short-circuits pinning entirely. |
| QR integrity | HMAC-SHA256 over canonicalized payload. Host-local secret at `~/.hermes/hermes-relay-qr-secret`. Phone parses + stores the signature but does NOT verify yet (secret distribution TBD). |
| Tailscale detection | Informational only — `tailscale0` interface + `100.64.0.0/10` CGNAT + `.ts.net` hostname checks. Displayed as a Connection-section chip. Does NOT auto-change TTL defaults. |
| Device revocation | Paired Devices screen → `GET /sessions` (tokens masked to 8-char prefix) / `DELETE /sessions/{token_prefix}` (self-revoke allowed, wipes local state + redirects to pair flow). Any paired device can revoke any other — trade-off documented in ADR 15. |
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
Shipped in v0.3.0; card hierarchy rewritten in v0.4.1. Rendered by `BridgeScreen.kt` + `BridgeViewModel` in this order:

1. **Master toggle card** (`BridgeMasterToggle`) — headline "Allow Agent Control" switch with a `MASTER` pill and leading "Master switch —" subtitle copy so the parent-gate role is legible at a glance. Gated on accessibility permission being granted; tapping the Switch when Accessibility is not granted surfaces a snackbar ("Accessibility Service must be enabled first.") with an "Open Settings" action that deep-links to `ACTION_ACCESSIBILITY_SETTINGS` rather than silent-dropping the tap. Inline device / battery / screen / current-app rows live in-card (the old standalone `BridgeStatusCard` was dropped from the layout in v0.4.1). Info icon opens a Play-review explanation dialog that also names the "Hermes has device control" persistent notification owned by the master switch.
2. **Permission checklist** (`BridgePermissionChecklist`) — tiered four-section layout shipped in v0.4.1 (Core bridge / Notification companion / Voice & camera / Sideload features). Tap-to-open Android Settings via `ACTION_ACCESSIBILITY_SETTINGS`, `ACTION_MANAGE_OVERLAY_PERMISSION`, `enabled_notification_listeners`, and per-row `RequestPermission` launchers for dangerous runtime perms. Rows fall back to `ACTION_APPLICATION_DETAILS_SETTINGS` when a runtime permission has been permanently denied. Optional rows render an "Optional" Material 3 pill in a `FlowRow` with `softWrap=false` so the pill never wraps internally on narrow titles. Re-probes on `Lifecycle.Event.ON_RESUME` so returning from Android Settings flips rows green without navigation churn.
3. **Advanced divider** — visual separator between "operate the bridge" and "expand what the bridge can do".
4. **Unattended Access card** (`UnattendedAccessRow`, sideload-only) — opt-in toggle gated on the master toggle (`enabled = masterEnabled`; subtitle reads "Requires Agent Control — enable the master switch above first." when master is off). First-enable shows the scary one-time dialog covering the security model + credential-lock limitation + how to disable. Credential-lock warning renders as an inline `KeyguardDetectedAlert` Surface band inside this card (was a standalone chip pre-v0.4.1, inlined so the warning lives next to the toggle that triggers it).
5. **Safety summary card** (`BridgeSafetySummaryCard`) — blocklist count / destructive-verb count / countdown timer (`in MM:SS` during an active idle window, else `N min idle`). Tap-through to `BridgeSafetySettingsScreen` for editing the blocklist / destructive verbs / auto-disable timer / status overlay / confirmation timeout.
6. **Activity log** (`BridgeActivityLog`) — scrollable `LazyColumn` capped at 320dp + `MAX_LOG_ENTRIES=100`. Tap-to-expand rows showing timestamp, status (Pending / Success / Failed / Blocked), result text, and optional screenshot token. DataStore-backed via `BridgePreferences`.

The bridge UI drives — and is driven by — Tier 5 safety-rails (`BridgeSafetyManager`, `BridgeForegroundService`, `BridgeStatusOverlay`, `AutoDisableWorker`). See `docs/decisions.md` and `CLAUDE.md`'s file table for the full wiring.

**Global unattended-access affordance (v0.4.1).** When master + unattended are both on (sideload only), `UnattendedGlobalBanner` renders as a 28dp amber strip at the top of `RelayApp`'s scaffold on every tab — pulsing dot + "Unattended access ON — agent can wake and drive this device" + chevron → tap navigates to Bridge. Theme-aware colours (amber-on-dark in dark mode, dark-amber-on-pale-amber in light). The banner handles visibility while the user is INSIDE Hermes-Relay; the existing WindowManager `BridgeStatusOverlayChip` handles visibility when the app is BACKGROUNDED. See `docs/decisions.md` §18 for the split rationale.

### Settings Tab
- **Connection** — unified "Pair with your server" card (primary action: Scan QR) with a single status summary covering API server, relay, and the active paired session. Collapsible "Manual configuration" card exposes API URL / API key / Relay URL / insecure-transport toggle + "Save & Test" (calls `RelayHttpClient.probeHealth`). Collapsible "Manual pairing code (fallback)" card for camera-less / SSH-only setups. Transport security badge (🔒 secure / 🔓 insecure-with-reason / 🔓 insecure-unknown) rendered inline. Paired Devices screen linked from here for the full device list + per-channel grant revoke.
- **Chat** — Show reasoning toggle, smooth auto-scroll toggle (live-follow streaming, default on), show token usage toggle, app context prompt toggle, tool call display (Off/Compact/Detailed), streaming endpoint selector (`auto` / `sessions` / `runs`), Stats for Nerds (analytics charts)
- **Voice** — interaction mode (tap / hold / continuous), silence threshold slider, Auto-TTS toggle, provider info read from `/voice/config`, language picker, Test Voice button
- **Notification companion** — opt-in status, "Open Android Settings" action, test notification dump
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

**Server:** three routes on `plugin/relay/server.py`:
- `POST /media/register` — **loopback-only**. Body `{"path", "content_type", "file_name"}`. Validates path is absolute, resolves (`os.path.realpath`) under an allowed root, exists, is a regular file, fits under `RELAY_MEDIA_MAX_SIZE_MB`. Generates `secrets.token_urlsafe(16)` (128 bits entropy), stores the token → entry mapping in an in-memory `OrderedDict` LRU (capped at `RELAY_MEDIA_LRU_CAP`, TTL `RELAY_MEDIA_TTL_SECONDS`). Returns `{ok, token, expires_at}`. Used when a host-local tool explicitly wants to publish a file.
- `GET /media/{token}` — requires `Authorization: Bearer <session_token>` against the existing `SessionManager` (same token WSS uses). Streams the file via `web.FileResponse` with the registered content type plus `Content-Disposition: inline; filename="..."` if the entry has a file name. 401 on missing/invalid bearer, 404 on unknown/expired token.
- `GET /media/by-path?path=<abs>&content_type=<optional>` — requires bearer auth. Shares the same sandbox validation as `/media/register` via a common `validate_media_path()` helper: absolute path, `realpath`-resolves under an allowed root, exists, is a regular file, fits under the size cap. Content-Type is the phone's hint if provided, otherwise guessed via `mimetypes.guess_type()`. This route exists specifically for **LLM-emitted bare-path markers** — upstream `agent/prompt_builder.py` instructs the model to include `MEDIA:/absolute/path/to/file` in its response text, so the bare-path form is the agent's native output, not just a fallback. 401 auth, 403 sandbox, 404 missing file.

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

Tools register against the Hermes plugin API in `plugin/tools/android_tool.py` (plus `plugin/tools/android_notifications.py`, `plugin/tools/android_navigate.py`). The Python-side tool issues an HTTP request to the relay on loopback; the relay forwards it to the phone over WSS; the phone executes it via the accessibility service and returns a structured response. Flavor gating: tools marked **sideload-only** are gated on `BuildFlavor.current == SIDELOAD` via `FeatureFlags.BuildFlavor` and/or require manifest permissions only declared in `app/src/sideload/AndroidManifest.xml`.

**Baseline (pre-v0.4 — shipped in Phase 3 Wave 1):**

| Tool | HTTP route | Purpose | Flavor |
|------|-----------|---------|--------|
| `android_ping` | `GET /ping` | Liveness check — does not require master enable | both |
| `android_screen` | `GET /screen` | Serialize the accessibility tree → `ScreenContent` | both |
| `android_screenshot` | `GET /screenshot` | `MediaProjection` PNG → `MEDIA:hermes-relay://<token>` | both |
| `android_current_app` | `GET /current_app` | Foregrounded package name | both |
| `android_get_apps` (`/apps` legacy) | `GET /get_apps` | Installed launcher apps | both |
| `android_tap` | `POST /tap` | Tap at `(x, y)` or on resolved `node_id` | both |
| `android_tap_text` | `POST /tap_text` | Find text via accessibility tree, tap it (see A9 cascade below) | both |
| `android_type` | `POST /type` | `ACTION_SET_TEXT` on focused input field | both |
| `android_swipe` | `POST /swipe` | Gesture swipe with direction + distance | both |
| `android_scroll` | `POST /scroll` | Scroll a specific container (resolves `node_id`) | both |
| `android_open_app` | `POST /open_app` | Launch an app by package name | both |
| `android_press_key` | `POST /press_key` | Curated global-action vocab (home/back/recents/notifications/quick_settings) — no raw `KeyEvent` injection | both |
| `android_wait` | `POST /wait` | Clamped idle — max 15s | both |
| `android_setup` | `POST /setup` | Permission bootstrap helper | both |
| `android_navigate` | (dispatches `/screenshot` + `/tap_text`/`/tap`/`/type`/`/swipe`/`/press_key`) | Tier 4 vision-driven close-the-loop navigation | both |
| `android_notifications_recent` | `GET /notifications/recent` | Poll the notif-listener ring buffer (loopback-only for Python tool callers) | both |

**v0.4 additions — Tier A (both flavors):**

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

**v0.4 additions — Tier B (both flavors):**

| Tool | HTTP route | Purpose |
|------|-----------|---------|
| `android_events(limit, since)` | `GET /events` | Poll the real-time `AccessibilityEvent` ring buffer. **Off by default** — a session must enable forwarding via `android_event_stream(enabled=true)` before events are recorded. Privacy-sensitive; keep off unless an agent flow needs it. |
| `android_event_stream(enabled)` | `POST /events/stream` | Opt in / out of event capture for the current session. |
| `android_send_intent(action, data, package, component, extras, category)` | `POST /send_intent` | Raw `Intent` escape hatch — `startActivity`. Safety-gated on the target package blocklist via `BridgeSafetyManager.checkPackageAllowed`. |
| `android_broadcast(action, data, package, extras)` | `POST /broadcast` | Raw `sendBroadcast`. Same blocklist gate as `/send_intent`. |

**v0.4 additions — Tier C (sideload-only):**

Tier C tools add runtime permissions that trigger Google Play policy review and are intentionally scoped to the sideload flavor only. The permissions are declared in `app/src/sideload/AndroidManifest.xml`; the googlePlay manifest does not declare them and the tools no-op via the `BuildFlavor.current == SIDELOAD` guard.

| Tool | HTTP route | Purpose | Permission |
|------|-----------|---------|------------|
| `android_location()` | `GET /location` | Last-known GPS fix via `LocationManager.getLastKnownLocation` | `ACCESS_FINE_LOCATION` |
| `android_search_contacts(query, limit)` | `POST /search_contacts` | `ContactsContract` name → phone number lookup, cap on result count | `READ_CONTACTS` |
| `android_call(number)` | `POST /call` | Auto-dial via `ACTION_CALL` on sideload; googlePlay stub falls back to `ACTION_DIAL` (user must confirm in the dialer). **Every call is gated on the destructive-verb confirmation modal**; see §6.4.2 safety notes. | `CALL_PHONE` |
| `android_send_sms(to, body)` | `POST /send_sms` | Direct `SmsManager.sendTextMessage` (or `sendMultipartTextMessage` for long bodies) with a `PendingIntent` result callback. **Every send is gated on the destructive-verb confirmation modal.** | `SEND_SMS` |

**Safety integration.** All HTTP routes except `/ping` and `/current_app` are gated in `BridgeCommandHandler` on the Bridge master toggle (`bridge_master_enabled` DataStore flag) and the Tier 5 three-stage safety check:
1. **Blocklist gate** — `BridgeSafetyManager.checkPackageAllowed(currentApp)` returns 403 `{"error": "blocked package <name>"}` when the foreground package is in the blocklist (~30 banking/payments/password-manager/2FA defaults seeded via `DEFAULT_BLOCKLIST`).
2. **Destructive-verb confirmation** — `/tap_text` and `/type` commands whose text matches the user's destructive-verb regex list (`send` / `pay` / `delete` / `transfer` / `confirm` / `submit` / ...) suspend on a `CompletableDeferred<Boolean>` under a `withTimeout`, waiting for the user to Allow / Deny via the `BridgeStatusOverlay` modal. **Tier C `android_call` and `android_send_sms` always go through this gate regardless of body content** — a phone call or SMS is definitionally destructive. Denied or timed-out commands return 403 `{"error": "user denied destructive action", "reason": "confirmation_denied_or_timeout"}`.
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
**Status: shipped in v0.3.0 (2026-04-13). v0.4 bridge feature expansion is in progress on `feature/bridge-feature-expansion` — adds long-press / drag / macro / clipboard / intent-send / location / contacts / call / SMS and multi-window screen reading.**

- [x] Migrate upstream bridge protocol into multiplexed WSS — Phase 3 Wave 1, 2026-04-12 (routes registered in `plugin/relay/server.py` delegating to `plugin/relay/channels/bridge.py`)
- [x] Update `plugin/tools/android_tool.py` to route through the unified relay on port 8767 (was the standalone `android_relay.py` on 8766)
- [x] App: Bridge status UI — see §5 Bridge Tab
- [x] App: Permission management (`BridgePermissionChecklist` — accessibility, screen capture, overlay, notification listener)
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
- [x] Per-channel grants on one session token (`Session.grants` — chat / terminal / bridge, clamped to session lifetime)
- [x] Paired Devices screen (`PairedDevicesScreen` + `GET /sessions` + `DELETE /sessions/{prefix}` + `PATCH /sessions/{prefix}` for extend)
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
- [x] GitHub Actions: release workflow — tag-triggered signed APK + AAB upload to GitHub Release (`.github/workflows/release.yml`)
- [x] Material You dynamic theming (Material 3 + dynamic color, user toggle in Appearance settings)
- [x] Proper error states and empty states (`RelayErrorClassifier` → `HumanError` → global `LocalSnackbarHost`; MorphingSphere-backed empty chat state)
- [x] App icon and branding (`ic_launcher*`, animated splash via `splash_icon_animated.xml`, MorphingSphere)
- [x] Two build flavors: `googlePlay` (Play Store track, conservative Accessibility use case) and `sideload` (`.sideload` applicationId suffix, full feature set)
- [ ] Notification channel for agent messages (not wired; Phase 6 territory)

### Phase V — Voice Mode
**Status: shipped 2026-04-12**

Real-time voice conversation via relay-hosted TTS/STT endpoints that wrap the hermes-agent venv's configured providers. Chat still goes directly to the API server — voice adds a modality on top, not a separate channel.

**Server-side (plugin/relay):**
- `POST /voice/transcribe` — multipart audio → `{text, provider}`. Wraps `tools.transcription_tools.transcribe_audio` in `asyncio.to_thread`.
- `POST /voice/synthesize` — JSON `{text}` → `audio/mpeg` file. Wraps `tools.tts_tool.text_to_speech_tool`.
- `GET /voice/config` — provider availability + current settings from `tts:` / `stt:` in `~/.hermes/config.yaml`.
- All three gated on the same bearer auth as `/media/*`.

**App-side:**
- `VoiceRecorder` (MediaRecorder / MPEG-4 AAC / m4a / 16 kHz mono) + `VoicePlayer` (MediaPlayer + Visualizer for amplitude) with `StateFlow<Float>` amplitude for the orb.
- `VoiceViewModel` state machine (`Idle / Listening / Transcribing / Thinking / Speaking / Error`) with sentence-boundary detection, `Channel<String>` TTS queue, and a consumer coroutine that synthesizes + plays sentences one at a time.
- Integrates with `ChatViewModel` by **observing** `messages: StateFlow` — no changes to chat code. Transcribed text goes through normal `chatVm.sendMessage(text)` so voice utterances appear as regular user messages in chat history.
- `VoiceModeOverlay` — full-screen UI with the MorphingSphere at 60% height in `voiceMode=true`, transcribed + response text, mic button supporting Tap / Hold / Continuous interaction modes.
- `MorphingSphere` gains `SphereState.Listening` (soft blue/purple, subtle wobble with user amplitude) and `SphereState.Speaking` (vivid green/teal, dramatic core-warmth pulse with agent amplitude). Additive changes — existing call sites unchanged via defaulted `voiceAmplitude` / `voiceMode` params.
- Voice Settings screen off the main Settings — interaction mode, silence threshold, TTS/STT provider labels, Test Voice button.

See `docs/decisions.md` → **Voice Mode — Architecture** for the four key decisions (relay-hosted endpoints, buffer-not-stream client chunking, m4a-not-webm recorder, ChatViewModel observation pattern).

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

As of v0.3.0, Phases 0–5 plus Phase V (voice) have shipped in some form. The current release cadence focuses on **v0.4 bridge feature expansion** — see `docs/plans/2026-04-13-bridge-feature-expansion.md`.

**Still non-goals for the current cadence:**
- Biometric session lock (fingerprint/face gate on terminal and/or chat resume). Tracked under Phase 4.
- Push notifications for agent messages (requires FCM + a notification channel on the relay side). Tracked under Phase 5.
- iOS client. Not on the roadmap.
- Reverse file transfer (phone → server direct upload). Inbound media (agent → phone) shipped in v0.2.0; outbound is attachments via the chat stream only.
- On-device model fallback (Phase 6).

See `Appendix A — Original Phase 0 Scope` at the end of this document for the historical "what we needed to build the first night" list, preserved for reference.

---

## 9. Key Dependencies

Current versions as of v0.3.0. Source of truth is `gradle/libs.versions.toml` — this table is a human-readable snapshot, not authoritative.

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
| aiohttp | 3.9+ | Server relay |
| libtmux | 0.37+ | tmux session management |
| gradle-play-publisher | 4.0.0 | Automated Play Console upload (optional) |

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
