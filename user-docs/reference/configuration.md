# Configuration

Hermes-Relay stores its settings using Android's DataStore and EncryptedSharedPreferences. This page documents the available configuration options.

## Connection Settings

These are configured during onboarding or from the **Settings → Connection** screen. The Connection screen groups everything under a single section with three cards:

- **Pair with your server** — always visible. One-tap entry point: a **Scan Pairing QR** button plus a unified status summary (API Server reachable, Relay connected, Session paired). One scan of the QR printed by `/hermes-relay-pair` (or the `hermes-pair` shell shim) configures everything.
- **Manual configuration** — collapsible. Starts collapsed when you're already paired and reachable, expanded otherwise. Holds the manual-entry fields below and a **Save & Test** action. This is the power-user / troubleshooting path.
- **Bridge pairing code** — collapsible and only visible when the relay feature flag is on. Shows a locally-generated 6-char code with copy / regenerate icons. This code is **for the future Phase 3 bridge feature** — the host would approve it to let the agent control the phone. It is **not** used for initial pairing; that's driven entirely by the QR from `/hermes-relay-pair`.

| Setting | Storage | Description |
|---------|---------|-------------|
| API Server URL | EncryptedSharedPreferences | Base URL of the Hermes API Server (e.g., `http://192.168.1.100:8642`) |
| API Key (optional) | EncryptedSharedPreferences | Bearer token for API authentication — only needed if server has `API_SERVER_KEY` set |
| Relay URL | EncryptedSharedPreferences | WebSocket URL for the Relay Server (optional, for bridge/terminal) |
| Relay Session Token | EncryptedSharedPreferences | Persistent token from relay pairing flow |

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

**If the relay isn't running** when a tool emits a media marker, the tool falls back to the legacy bare-path form (`MEDIA:/tmp/...`). The app recognizes this form and renders an inline `⚠️ Image unavailable — relay offline` card instead of raw marker text.

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
