# Configuration

Hermes-Relay stores its settings using Android's DataStore and EncryptedSharedPreferences. This page documents the available configuration options.

## Onboarding Settings

These are set during the initial onboarding flow and can be updated in **Settings > Connection**.

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

The relay server is a **separate service** (not part of hermes-agent) that handles terminal and bridge channels over WSS. Only needed if you use those features.

**Quick start:**

```bash
pip install aiohttp pyyaml && python -m relay_server --no-ssl
```

**Environment variables:**

| Variable | Default | Description |
|----------|---------|-------------|
| `RELAY_HOST` | `0.0.0.0` | Bind address |
| `RELAY_PORT` | `8767` | Listen port |
| `RELAY_SSL_CERT` | — | TLS certificate path |
| `RELAY_SSL_KEY` | — | TLS private key path |
| `RELAY_WEBAPI_URL` | `http://localhost:8642` | Hermes API Server URL |
| `RELAY_HERMES_CONFIG` | `~/.hermes/config.yaml` | Hermes config (for profile loading) |
| `RELAY_LOG_LEVEL` | `INFO` | Logging level |

For Docker, systemd, and TLS setup, see [docs/relay-server.md](https://github.com/Codename-11/hermes-relay/blob/main/docs/relay-server.md).

## Network Security Config

The app's `network_security_config.xml` controls which domains allow cleartext (HTTP) traffic:

- **Cleartext allowed:** `localhost`, `127.0.0.1`, `10.0.2.2` (emulator)
- **All other domains:** HTTPS required

To connect to a server without HTTPS on a local network, you have two options:
1. Set up a reverse proxy (nginx/Caddy) with TLS on the server
2. Use an SSH tunnel or VPN to the server, then connect via `localhost`
