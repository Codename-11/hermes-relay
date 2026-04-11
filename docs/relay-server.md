# Relay Server

The relay server is a lightweight Python service that bridges the Hermes-Relay Android app to server-side features requiring persistent bidirectional communication.

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
# If you installed the hermes-relay plugin:
hermes relay start --no-ssl

# Or directly from a repo checkout:
python -m plugin.relay --no-ssl

# Legacy entrypoint still works via a thin compat shim:
python -m relay_server --no-ssl
```

The canonical implementation lives at `plugin/relay/`. The top-level `relay_server/` package is a thin shim that delegates to `plugin.relay.server.main()` so existing docs, scripts, and systemd units keep working.

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

The relay uses a QR-driven two-step auth flow:

1. **Pairing** — `hermes pair` runs on the Hermes host, mints a fresh 6-char code (`A-Z / 0-9`), pre-registers it with the relay via the loopback-only `POST /pairing/register` endpoint, and embeds the relay URL + code in the scanned QR payload. The phone sends the code in its first `system/auth` envelope; the relay consumes it and issues a session token. Codes are one-shot and expire 10 minutes after registration.
2. **Session token** — Stored in Android's EncryptedSharedPreferences. Used for all subsequent connections. Expires after 30 days.

Rate limiting: 5 failed auth attempts per 60 seconds triggers a 5-minute block per IP.

See [`docs/spec.md` §3.3](spec.md) for the full auth flow and the QR wire format.

## HTTP Routes

| Route | Method | Purpose |
|-------|--------|---------|
| `/ws`, `/` | GET (upgrade) | Main WebSocket endpoint. Phone connects, sends `system/auth`, then multiplexes `chat`/`terminal`/`bridge` envelopes. |
| `/health` | GET | Returns `{status, version, clients, sessions}` JSON. |
| `/pairing` | POST | Generate a new relay-side pairing code. Returns `{"code": "ABC123"}`. Unrestricted (intended for host-local callers). |
| `/pairing/register` | POST | **Loopback only.** Pre-register an externally-provided pairing code so it can appear in a QR payload before the phone scans it. Request body: `{"code": "ABCD12"}`. Response: `{"ok": true, "code": "ABCD12"}`. Returns HTTP 403 for any `request.remote` other than `127.0.0.1` / `::1` — only a process running on the same host as the relay can inject codes. Used by `hermes pair`. |

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

Canonical implementation (`plugin/relay/`):

| File | Purpose |
|------|---------|
| `plugin/relay/__main__.py` | Entry point (`python -m plugin.relay`) |
| `plugin/relay/server.py` | Main WSS server, HTTP route registration, auth flow, `/pairing/register` handler |
| `plugin/relay/config.py` | `RelayConfig`, `PAIRING_ALPHABET` (full `A-Z / 0-9`), env var loading |
| `plugin/relay/auth.py` | `PairingManager`, `SessionManager`, `RateLimiter` |
| `plugin/relay/channels/chat.py` | Chat handler (proxies to API server) |
| `plugin/relay/channels/terminal.py` | Terminal handler (PTY-backed, Phase 2) |
| `plugin/relay/channels/bridge.py` | Bridge handler (Phase 3 — stub) |

Deployment assets (`relay_server/`, thin shim + ops files):

| File | Purpose |
|------|---------|
| `relay_server/__main__.py` | Legacy entrypoint — delegates to `plugin.relay.server.main()` |
| `relay_server/Dockerfile` | Container image |
| `relay_server/hermes-relay.service` | Systemd unit file |
| `relay_server/SKILL.md` | Hermes skill reference for self-setup |
| `relay_server/requirements.txt` | Python dependencies |
