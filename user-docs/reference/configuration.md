# Configuration

Hermes-Relay stores its settings using Android's DataStore, Android Keystore (for session tokens when available), and EncryptedSharedPreferences (fallback). This page documents the available configuration options.

## Connection Settings

These are configured during onboarding or from the **Settings → Connections** screen. That screen is the single authoritative home for everything connection-related (as of the 2026-04-21 unification — the legacy singular *Settings → Connection* subpage was folded in here). Each paired server appears as its own card in the list; the currently-active card expands inline to surface all deep-configuration UI.

**On any card (active or not) — the per-connection action row:**
- **Reconnect** (only on Stale state)
- **Rename**, **Re-pair**, **Revoke**, **Remove**

**On the active card — a deep body below the action row.** Each section is headed by a labelMedium header and a one-line caption so the card self-narrates:

- **Connection health** (always visible) — *"Tap any row for details."* Three tappable rows (API / Relay / Session) open detail sheets with token, endpoint, health, and session info.
- **Routes (N)** (when the pairing carries multi-endpoint candidates) — *"The app picks the fastest reachable network automatically and switches when you change networks."* Expander reveals one row per route with role chip (LAN / Tailscale / Public / Custom VPN), per-row Secure/Plain security chip, state chip (Active / Fallback), probe-now button, per-candidate *Prefer this route* override, and per-candidate TOFU pin inspection.
- **Advanced** (collapsible) — *"Manual setup — most people don't need this after QR pairing."* Holds:
  - **Manual URL config** — API Server URL, API Key, Relay URL, each with **Save & Test**.
  - **Allow plain (unencrypted) connections** toggle — first enable opens a consent dialog with a reason picker (LAN only / Tailscale or VPN / Local dev only). Reason is stored for later but the Transport Security badge usually derives a more accurate label from the live active-route role. Operator intent is the trust model — the toggle gates the UI's ability to save `ws://` / `http://` URLs, nothing server-side.
  - **Disconnect** button — drops the active WSS without clearing the session token.
  - **Manual pairing code (fallback)** — the 3-step flow for when you can't use QR scanning. (1) Copy the locally-generated 6-char code; (2) on the host, run `hermes-pair --register-code <code>`; (3) tap **Connect** here. Canonical flow is still the QR from `/hermes-relay-pair` — use this only when QR scanning is physically impossible. Bridge control is gated by the master toggle on the Bridge tab, NOT by this code.
- **Security** (always visible) — Transport Security badge (🔒 secure / 🔓 plain with reason / 🔓 unknown), Tailscale-detected chip, Hardware-keystore badge, and a **Relay sessions** row that navigates to the full list of phones paired with this server.

| Setting | Storage | Description |
|---------|---------|-------------|
| API Server URL | EncryptedSharedPreferences | Base URL of the Hermes API Server (e.g., `http://192.168.1.100:8642`) |
| API Key (optional) | EncryptedSharedPreferences | Bearer token for API authentication — only needed if server has `API_SERVER_KEY` set |
| Relay URL | EncryptedSharedPreferences | WebSocket URL for the Relay Server (optional, for bridge/terminal) |
| Relay Session Token | **Keystore** (StrongBox when available), with fallback to EncryptedSharedPreferences | Persistent token from relay pairing flow. Migrated automatically from the legacy EncryptedSharedPreferences file on first launch post-upgrade. |
| TOFU Cert Pins | DataStore (`tofu_pins`) | SHA-256 SPKI fingerprints per `host:port`. Recorded on the first successful `wss://` connect, verified on subsequent connects via OkHttp `CertificatePinner`. Wiped explicitly when the user re-pairs via QR (taken as consent to new cert material). |
| Pair TTL Preference | DataStore (`pair_ttl_seconds`) | User's last-selected session TTL on the pair flow. Preselected next time. |
| Plain toggle ack seen | DataStore (`insecure_ack_seen`) | Whether the user has acknowledged the Allow-plain-connections toggle threat model. Per-install; revoke via **Clear data** to reshow. (Key name retains the legacy `insecure_` prefix for migration compatibility.) |
| Plain toggle reason | DataStore (`insecure_reason`) | Reason selected on the plain-toggle ack dialog — `lan_only` / `tailscale_vpn` / `local_dev` / empty. Auto-stamped at pair time when the resolved endpoint's role is `lan` or `tailscale` (cleared on upgrade to a secure endpoint). The Transport Security badge prefers the live active-route role over this stored value. |
| All-plain pairing ack | DataStore (`all_insecure_pair_ack_seen`) | Whether the user has acknowledged the one-time pairing-consent checkbox that appears on step 2 when every route in the scanned QR is plain `ws://` / `http://` (no secure sibling). Per-install. Mixed QRs (LAN + Tailscale) are ungated because the secure route is a safety net. |
| Trusted bridge actions | DataStore (`bridge_trusted_destructive_verbs`) | Set of destructive bridge verbs (e.g. `send_sms`, `call`) that bypass the confirmation overlay because the user ticked "Don't ask again" in a prior confirm. The master-disable toggle and the blocklist still override — trust is for eliminating confirmation fatigue on approved verbs, not a kill-switch bypass. Reset from Bridge → Trusted actions → **Reset**. |

### Pair Flow — TTL Picker

When you scan a pairing QR (or enter a code manually), a **Session TTL Picker** dialog opens before the phone connects to the relay. Options:

- **1 day** — one-shot development sessions. Expires fast, forces frequent re-pair.
- **7 days** — the default for plain `ws://` without Tailscale.
- **30 days** — the default for `wss://` or when Tailscale is detected. Also matches the legacy hardcoded TTL.
- **90 days** / **1 year** — longer-lived operator devices.
- **Never expire** — the device stays paired until you revoke it manually from Relay sessions. Always selectable — the phone treats user intent as the trust model and doesn't gate on transport security. A warning is shown inline.

The default pre-selection depends on the QR's operator-chosen TTL (if any, via `hermes-pair --ttl <duration>`), falling back to 30d on secure/Tailscale transports or 7d on plain ws. Your last pick persists as the new default for future pairs.

Per-channel grants (`terminal`, `bridge`) can be pre-set by the operator via `hermes-pair --grants terminal=7d,bridge=1d`. The phone displays them on the Relay sessions card as chips with a tap-for-info icon explaining that each grant is a per-feature permission (chat / bridge / voice) with an independent expiry. Grants cannot outlive the session — they're clamped to the session TTL server-side.

### Relay sessions

**Settings → Connections → [active card] → Security → Relay sessions** (or **Settings → Relay sessions**) opens a full-screen list of every phone currently paired with the relay. The screen leads with a short intro paragraph explaining that each row is a server-side session (not a Bluetooth pairing, not an account), then renders one card per session:

- Device name + device ID
- **Current device** badge if this is the device you're looking at the list on
- Transport security badge (Secure (TLS) / Plain (on `<role>`) / Plain (no TLS))
- Session expiry (a date or "Never")
- Per-channel grant chips (`chat · terminal · bridge`)
- **Extend** button — opens the same TTL picker dialog used during initial pair, preselected with the current remaining lifetime (or "Never" if already never-expiring). Confirming calls `PATCH /sessions/{token_prefix}` with the new TTL; the server restarts the clock from now and auto-clamps any existing grants to the (possibly new) session lifetime. Also works to **shorten** sessions — pick a shorter duration or "Never" to change the policy without re-pairing.
- **Revoke** button — confirmation dialog, then `DELETE /sessions/{token_prefix}`. Revoking the current device wipes local session token and redirects to pairing flow.

Any paired device can revoke any other. For single-operator deployments (1-2 phones, one host) this is fine; for multi-user deployments a per-device role model is a future refactor (see ADR 15).

## Chat Settings

Available in **Settings > Chat**.

| Setting | Default | Description |
|---------|---------|-------------|
| Show reasoning | `true` | Display thinking/reasoning blocks above responses |
| Show token usage | `true` | Display input/output token counts and estimated cost |
| App context prompt | `true` | Send system message telling agent user is on mobile |
| Tool call display | `Detailed` | How tool calls appear: Off, Compact, or Detailed |
| Personality | Server default | Active personality from `config.agent.personalities` via `GET /api/config` |

## Inbound Media Settings

Available in **Settings > Inbound media**. Controls how the app fetches and caches files the agent sends back through chat (screenshots from `android_screenshot`, and any future media-producing tool). Requires a running relay — files are served out-of-band by `plugin/relay/` via the new `POST /media/register` + `GET /media/{token}` routes, so the agent only needs to emit a `MEDIA:hermes-relay://<token>` marker in its chat response and the app handles the fetch + render. Bytes land in the app's cache directory and are shared with external viewers via `FileProvider` (authority `${applicationId}.fileprovider`).

| Setting | Default | Description |
|---------|---------|-------------|
| Max inbound attachment size | `25 MB` | Hard cap on fetches. The app downloads the body, and if it exceeds this cap the attachment flips to FAILED with a "File too large" message. Range: 5–100 MB. |
| Auto-fetch threshold | `2 MB` | *Persisted but not enforced today — forward-compatibility placeholder.* Intended to be a soft ceiling above which the app shows "Tap to download" instead of auto-fetching. Currently only the cellular toggle + the hard max cap are enforced. Range: 0–50 MB. |
| Auto-fetch on cellular | `off` | When off + the device is on a cellular network, attachments stay in LOADING state with a "Tap to download" affordance rather than auto-downloading. When on, cellular is treated the same as Wi-Fi. |
| Cached media cap | `200 MB` | Maximum total size of `cacheDir/hermes-media/`. Oldest files (by mtime) are evicted when the cache would exceed this. Range: 50–500 MB. |
| Clear cached media | — | Button. Deletes every file in `cacheDir/hermes-media/` and shows a toast with the freed byte count. |

**What works:** Images render inline (same path as outbound attachments — `BitmapFactory.decodeByteArray` + `asImageBitmap`, no Coil/Glide added). Video / audio / PDF / text / generic files render as tap-to-open file cards that fire `ACTION_VIEW` with `FLAG_GRANT_READ_URI_PERMISSION` against the `FileProvider` URI.

**What doesn't work yet:**
- Session replay across relay restarts. The `MediaRegistry` is in-memory on the relay side, so tokens stored in persisted message history become stale when the relay restarts. Scrolling back into a prior session renders a `⚠️ Image unavailable` placeholder for any stale token. Phone-side persistent caching (indexed by token or content hash) is the planned fix; filed as a follow-up.
- Auto-fetch threshold enforcement (see table above).

**Bare-path markers (`MEDIA:/abs/path.ext`) — the LLM's native format.** Upstream `hermes-agent/agent/prompt_builder.py` instructs the LLM to emit markers in this form directly in its response text. The app parses bare-path markers and fetches bytes via `GET /media/by-path` on the relay (same bearer auth, same path sandbox as `/media/register`). The tool-side `MEDIA:hermes-relay://<token>` form remains available for tools that want to pre-register explicitly.

**If the relay isn't reachable** or the file isn't in the allowed roots, the app shows an inline `⚠️ Image unavailable` card with the specific reason (relay offline / sandbox violation / file not found) instead of raw marker text.

## Appearance Settings

Available in **Settings > Appearance**.

| Setting | Default | Description |
|---------|---------|-------------|
| Theme | `system` | Light, dark, or follow system setting |
| Dynamic colors | `true` | Use Material You wallpaper-based colors (Android 12+) |

## Session State

These are managed automatically by the app.

| Key | Description |
|-----|-------------|
| Last active session | Session ID to resume on app restart |
| Onboarding complete | Whether the user has completed initial setup |
| Last seen version | Version string for What's New auto-show |

## Analytics (In-Memory)

The Stats for Nerds section in Settings shows performance data collected in-memory. This data is **not persisted** and resets on app restart. No data is sent off-device.

| Metric | Description |
|--------|-------------|
| TTFT | Time to first token (ms) |
| Completion time | Total response time (ms) |
| Token usage | Input/output tokens per message |
| Health latency | API health check round-trip time (ms) |
| Stream success rate | Percentage of streams that completed without error |

## Server-Side Configuration

### Hermes API Server

The API server is part of `hermes gateway` and configured via `~/.hermes/.env`:

```bash
# Required for Hermes-Relay
API_SERVER_ENABLED=true
# API_SERVER_KEY=your-secret-key  # Optional — only set if exposing to network
API_SERVER_HOST=0.0.0.0
API_SERVER_PORT=8642
```

### Relay Server

The relay server is a **separate service** (canonically at `plugin/relay/` with a thin `relay_server/` compat shim) that handles terminal and bridge channels over WSS. Only needed if you use those features.

**Quick start:**

```bash
# If you installed the hermes-relay plugin:
hermes relay start --no-ssl

# Or directly from a repo checkout:
python -m plugin.relay --no-ssl
```

`RELAY_HOST` and `RELAY_PORT` are read by **both** the relay server itself and the pair command (`hermes-pair` / `/hermes-relay-pair`) — the pair command uses them to locate the local relay when pre-registering a pairing code, so if you run the relay on a non-default port, make sure the same values are in the environment when you invoke pairing.

**Environment variables:**

| Variable | Default | Description |
|----------|---------|-------------|
| `RELAY_HOST` | `0.0.0.0` | Bind address (relay) / relay host used by the pair command |
| `RELAY_PORT` | `8767` | Listen port (relay) / relay port probed by the pair command |
| `RELAY_SSL_CERT` | — | TLS certificate path |
| `RELAY_SSL_KEY` | — | TLS private key path |
| `RELAY_WEBAPI_URL` | `http://localhost:8642` | Hermes API Server URL |
| `RELAY_HERMES_CONFIG` | `~/.hermes/config.yaml` | Hermes config (for profile loading) |
| `RELAY_LOG_LEVEL` | `INFO` | Logging level |
| `RELAY_TERMINAL_SHELL` | _auto (`$SHELL`)_ | Absolute path to the shell spawned for terminal sessions |
| `RELAY_PAIRING_CODE` | — | Pre-register a pairing code at startup (same effect as `--pairing-code`) |
| `RELAY_MEDIA_MAX_SIZE_MB` | `100` | Per-file size cap on `POST /media/register` (MediaRegistry, used for inbound media delivery — see ADR 14) |
| `RELAY_MEDIA_TTL_SECONDS` | `86400` | How long a registered media entry stays valid before the registry evicts it |
| `RELAY_MEDIA_LRU_CAP` | `500` | Max entries in the media registry before oldest-eviction kicks in |
| `RELAY_MEDIA_ALLOWED_ROOTS` | — | Additional absolute directory roots allowed on `/media/register` (colon-separated on Unix, `os.pathsep` on other platforms). Extends the auto-derived defaults (`tempfile.gettempdir()` + `HERMES_WORKSPACE` or `~/.hermes/workspace/`). |

**Pairing alphabet:** As of 2026-04-11, the relay accepts any 6-character code from `A-Z / 0-9` (36 chars). The earlier "no ambiguous 0/O/1/I" 32-char restriction was dropped once the pairing flow became QR + HTTP — the phone-side generator in `AuthManager.kt` uses the full alphabet, and the restriction silently rejected roughly one in eight valid codes.

For Docker, systemd, and TLS setup, see [docs/relay-server.md](https://github.com/Codename-11/hermes-relay/blob/main/docs/relay-server.md).

### Skills (`external_dirs`)

Hermes-Relay's `/hermes-relay-pair` slash command is implemented as a skill at `~/.hermes/hermes-relay/skills/devops/hermes-relay-pair/SKILL.md`. Rather than hand-copying it into `~/.hermes/skills/`, the installer registers the clone's `skills/` directory in your `~/.hermes/config.yaml`:

```yaml
skills:
  external_dirs:
    - ~/.hermes/hermes-relay/skills
```

This is the canonical Hermes distribution pattern for plugin-bundled skills — hermes-agent scans `external_dirs` on every invocation, so a `git pull` inside `~/.hermes/hermes-relay/` immediately updates the skill with no extra steps. (There is no `hermes skills update` flow for `external_dirs`-based skills; update = `git pull`.)

If you already have an `external_dirs` list, the installer appends to it idempotently. If you removed the entry by hand and want it back, re-run the one-liner or add the line manually and restart hermes-agent.

## Network Security Config

The app's `network_security_config.xml` controls which domains allow cleartext (HTTP) traffic:

- **Cleartext allowed:** `localhost`, `127.0.0.1`, `10.0.2.2` (emulator)
- **All other domains:** HTTPS required

To connect to a server without HTTPS on a local network, you have two options:
1. Set up a reverse proxy (nginx/Caddy) with TLS on the server
2. Use an SSH tunnel or VPN to the server, then connect via `localhost`
