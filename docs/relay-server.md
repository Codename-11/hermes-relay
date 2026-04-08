# Relay Server

The relay server is a lightweight Python service that bridges the Hermes Relay Android app to server-side features requiring persistent bidirectional communication.

## When You Need It

| Feature | Requires Relay? | Protocol |
|---------|----------------|----------|
| **Chat** | No | HTTP/SSE direct to Hermes API Server (`:8642`) |
| **Terminal** | Yes (Phase 2) | WSS via relay (`:8767`) |
| **Bridge** | Yes (Phase 3) | WSS via relay (`:8767`) |

If you only use chat, you do **not** need the relay server. The app connects directly to the Hermes API Server for chat, sessions, profiles, and skills.

## Architecture

```
Phone (HTTP/SSE) --> Hermes API Server (:8642)   [chat]
Phone (WSS)      --> Relay Server (:8767)         [terminal, bridge]
```

The relay runs alongside hermes-agent on the same machine. It reads `~/.hermes/config.yaml` for agent profiles and proxies to the API server at `localhost:8642` by default.

## Quick Start

### One-liner

```bash
pip install aiohttp pyyaml && python -m relay_server --no-ssl
```

Run from the repo root (where `relay_server/` is a Python package).

### Docker

```bash
docker build -t hermes-relay relay_server/
docker run -d --name hermes-relay --network host \
  -v ~/.hermes:/home/relay/.hermes:ro \
  hermes-relay
```

### Systemd (persistent service)

```bash
# Edit the service file to match your user/paths
sudo cp relay_server/hermes-relay.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now hermes-relay
```

Check status:

```bash
sudo systemctl status hermes-relay
journalctl -u hermes-relay -f
```

## CLI Options

```
python -m relay_server [OPTIONS]

  --port PORT        Listen port (default: 8767)
  --no-ssl           Disable TLS requirement (dev/localhost only)
  --log-level LEVEL  DEBUG, INFO, WARNING, ERROR (default: INFO)
  --config PATH      Path to hermes config.yaml
```

## Environment Variables

All settings can be configured via environment variables. These override CLI defaults.

| Variable | Default | Description |
|----------|---------|-------------|
| `RELAY_HOST` | `0.0.0.0` | Bind address |
| `RELAY_PORT` | `8767` | Listen port |
| `RELAY_SSL_CERT` | — | TLS certificate path |
| `RELAY_SSL_KEY` | — | TLS private key path |
| `RELAY_WEBAPI_URL` | `http://localhost:8642` | Hermes API Server base URL |
| `RELAY_HERMES_CONFIG` | `~/.hermes/config.yaml` | Hermes config path (for profile loading) |
| `RELAY_LOG_LEVEL` | `INFO` | Python logging level |

## TLS / Production

For local development, `--no-ssl` is fine. For production (phone connecting over the internet):

```bash
# With Let's Encrypt
export RELAY_SSL_CERT=/etc/letsencrypt/live/yourdomain/fullchain.pem
export RELAY_SSL_KEY=/etc/letsencrypt/live/yourdomain/privkey.pem
python -m relay_server
```

Or use a reverse proxy (nginx/Caddy) to terminate TLS in front of the relay.

## Authentication

The relay uses a two-step auth flow:

1. **Pairing** — The app displays a 6-character code. The user provides this to hermes-agent (or enters it in the relay's pairing endpoint). The relay validates and issues a session token.
2. **Session token** — Stored in Android's EncryptedSharedPreferences. Used for all subsequent connections. Expires after 30 days.

Rate limiting: 5 failed auth attempts per 60 seconds triggers a 5-minute block per IP.

## Health Check

```bash
curl http://localhost:8767/health
```

Returns JSON with server status and version.

## Channel Protocol

All messages use typed envelopes over a single WebSocket connection at `/ws`:

```json
{
  "channel": "terminal" | "bridge" | "system",
  "type": "<event_type>",
  "id": "<uuid>",
  "payload": { ... }
}
```

### Terminal (Phase 2)

| Type | Direction | Payload |
|------|-----------|---------|
| `terminal.attach` | App --> Server | `{ session_name?, cols, rows }` |
| `terminal.attached` | Server --> App | `{ session_name, pid }` |
| `terminal.input` | App --> Server | `{ data }` |
| `terminal.output` | Server --> App | `{ data }` |
| `terminal.resize` | App --> Server | `{ cols, rows }` |
| `terminal.detach` | App --> Server | `{}` |

### Bridge (Phase 3)

| Type | Direction | Payload |
|------|-----------|---------|
| `bridge.command` | Server --> App | `{ request_id, method, path, params?, body? }` |
| `bridge.response` | App --> Server | `{ request_id, status, result }` |
| `bridge.status` | App --> Server | `{ accessibility_enabled, overlay_enabled, battery }` |

## Relationship to Hermes Agent

The relay server is **separate from hermes-agent**. The hermes-agent plugin system (`register_tool`, `register_hook`) cannot add HTTP/WebSocket endpoints to the gateway — it only registers agent tools and lifecycle hooks. The relay fills this gap by providing a dedicated WSS server for features that need persistent bidirectional communication.

The relay reads the hermes config file for agent profiles but does not modify it. It proxies chat requests to the API server but handles terminal and bridge sessions directly.

## Files

| File | Purpose |
|------|---------|
| `relay_server/__main__.py` | Entry point (`python -m relay_server`) |
| `relay_server/relay.py` | Main WSS server, routing, auth flow |
| `relay_server/config.py` | Configuration from env vars + config file |
| `relay_server/auth.py` | Pairing codes, session tokens, rate limiting |
| `relay_server/channels/chat.py` | Chat handler (proxies to API server) |
| `relay_server/channels/terminal.py` | Terminal handler (Phase 2 — stub) |
| `relay_server/channels/bridge.py` | Bridge handler (Phase 3 — stub) |
| `relay_server/Dockerfile` | Container image |
| `relay_server/hermes-relay.service` | Systemd unit file |
| `relay_server/SKILL.md` | Hermes skill reference for self-setup |
| `relay_server/requirements.txt` | Python dependencies |
