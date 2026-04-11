# Configuration

Hermes-Relay stores its settings using Android's DataStore and EncryptedSharedPreferences. This page documents the available configuration options.

## Connection Settings

These are configured during onboarding or from the **Settings → Connection** screen. The Connection screen groups everything under a single section with three cards:

- **Pair with your server** — always visible. One-tap entry point: a **Scan Pairing QR** button plus a unified status summary (API Server reachable, Relay connected, Session paired). One scan of the QR printed by `hermes pair` configures everything.
- **Manual configuration** — collapsible. Starts collapsed when you're already paired and reachable, expanded otherwise. Holds the manual-entry fields below and a **Save & Test** action. This is the power-user / troubleshooting path.
- **Bridge pairing code** — collapsible and only visible when the relay feature flag is on. Shows a locally-generated 6-char code with copy / regenerate icons. This code is **for the future Phase 3 bridge feature** — the host would approve it to let the agent control the phone. It is **not** used for initial pairing; that's driven entirely by the QR from `hermes pair`.

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

`RELAY_HOST` and `RELAY_PORT` are read by **both** the relay server itself and `hermes pair` — the pair command uses them to locate the local relay when pre-registering a pairing code, so if you run the relay on a non-default port, make sure the same values are in the environment when you run `hermes pair`.

**Environment variables:**

| Variable | Default | Description |
|----------|---------|-------------|
| `RELAY_HOST` | `0.0.0.0` | Bind address (relay) / relay host used by `hermes pair` |
| `RELAY_PORT` | `8767` | Listen port (relay) / relay port probed by `hermes pair` |
| `RELAY_SSL_CERT` | — | TLS certificate path |
| `RELAY_SSL_KEY` | — | TLS private key path |
| `RELAY_WEBAPI_URL` | `http://localhost:8642` | Hermes API Server URL |
| `RELAY_HERMES_CONFIG` | `~/.hermes/config.yaml` | Hermes config (for profile loading) |
| `RELAY_LOG_LEVEL` | `INFO` | Logging level |
| `RELAY_TERMINAL_SHELL` | _auto (`$SHELL`)_ | Absolute path to the shell spawned for terminal sessions |
| `RELAY_PAIRING_CODE` | — | Pre-register a pairing code at startup (same effect as `--pairing-code`) |

**Pairing alphabet:** As of 2026-04-11, the relay accepts any 6-character code from `A-Z / 0-9` (36 chars). The earlier "no ambiguous 0/O/1/I" 32-char restriction was dropped once the pairing flow became QR + HTTP — the phone-side generator in `AuthManager.kt` uses the full alphabet, and the restriction silently rejected roughly one in eight valid codes.

For Docker, systemd, and TLS setup, see [docs/relay-server.md](https://github.com/Codename-11/hermes-relay/blob/main/docs/relay-server.md).

## Network Security Config

The app's `network_security_config.xml` controls which domains allow cleartext (HTTP) traffic:

- **Cleartext allowed:** `localhost`, `127.0.0.1`, `10.0.2.2` (emulator)
- **All other domains:** HTTPS required

To connect to a server without HTTPS on a local network, you have two options:
1. Set up a reverse proxy (nginx/Caddy) with TLS on the server
2. Use an SSH tunnel or VPN to the server, then connect via `localhost`
