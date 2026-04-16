# Hermes-Relay Server

Setup and run the Hermes-Relay Server for the Hermes-Relay Android app.

## What It Is

A lightweight Python WSS server that bridges the Hermes-Relay Android app to server-side features that need persistent bidirectional communication: **terminal** (remote shell via tmux) and **bridge** (agent-driven phone control). Chat does not use the relay — it connects directly to the Hermes API Server.

## When You Need It

- **Chat only?** You do NOT need the relay server. The app talks directly to the Hermes API Server (`:8642`).
- **Terminal or Bridge?** You need the relay server running on the same machine as hermes-agent.

## Quick Setup

### One-liner (pip)

```bash
pip install aiohttp pyyaml && python -m relay_server --no-ssl
```

Run this from the `hermes-android/` repo root, or wherever `relay_server/` is located.

### As a Hermes plugin + relay

```bash
# 1. Install the Android plugin (18 android_* tools)
cp -r plugin ~/.hermes/plugins/hermes-relay

# 2. Install relay dependencies
pip install -r relay_server/requirements.txt

# 3. Start the relay server
python -m relay_server --no-ssl --log-level INFO
```

### Docker

```bash
docker run -d --name hermes-relay \
  --network host \
  -v ~/.hermes:/home/relay/.hermes:ro \
  ghcr.io/codename-11/hermes-relay:latest
```

Or build locally:

```bash
docker build -t hermes-relay relay_server/
docker run -d --name hermes-relay --network host -v ~/.hermes:/home/relay/.hermes:ro hermes-relay
```

### Systemd service (persistent)

```bash
sudo cp relay_server/hermes-relay.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now hermes-relay
```

Edit the service file first to set `User=` and `WorkingDirectory=` to match your setup.

## Configuration

All settings via environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `RELAY_HOST` | `0.0.0.0` | Bind address |
| `RELAY_PORT` | `8767` | Listen port |
| `RELAY_SSL_CERT` | (none) | Path to TLS certificate |
| `RELAY_SSL_KEY` | (none) | Path to TLS private key |
| `RELAY_WEBAPI_URL` | `http://localhost:8642` | Hermes API Server URL |
| `RELAY_HERMES_CONFIG` | `~/.hermes/config.yaml` | Path to hermes config (for profile loading) |
| `RELAY_LOG_LEVEL` | `INFO` | Logging level |

## CLI Options

```
python -m relay_server [OPTIONS]

  --port PORT        Override listen port (default: 8767)
  --no-ssl           Disable TLS (dev only — use for localhost)
  --log-level LEVEL  Set logging level (DEBUG, INFO, WARNING, ERROR)
  --config PATH      Path to hermes config.yaml
```

## Architecture

```
Phone (HTTP/SSE) --> Hermes API Server (:8642)   [chat — direct, no relay]
Phone (WSS)      --> Relay Server (:8767)         [terminal, bridge]
```

The relay authenticates phones via a 6-character pairing code, then issues a 30-day session token. All communication uses typed envelope messages multiplexed over a single WebSocket connection.

## Verify It Works

```bash
curl http://localhost:8767/health
# {"status": "ok", "version": "0.4.0", "channels": ["chat", "terminal", "bridge"]}
```

## Troubleshooting

- **Connection refused**: Is the relay running? Check `systemctl status hermes-relay` or `docker logs hermes-relay`
- **Auth failures**: Pairing codes expire after 10 minutes. Generate a new one.
- **TLS errors**: Use `--no-ssl` for local dev. For production, set `RELAY_SSL_CERT` and `RELAY_SSL_KEY`.
- **Can't reach from phone**: Ensure port 8767 is open in firewall. Use `ss -tlnp | grep 8767` to verify listening.
