# Relay Server

The relay server is a lightweight Python WSS service that enables **terminal** (remote shell) and **bridge** (agent-driven phone control) features in Hermes-Relay. Chat does not use the relay — it connects directly to the Hermes API Server.

## Do I Need It?

| Feature | Relay required? |
|---------|----------------|
| Chat | No |
| Terminal (Phase 2) | Yes |
| Bridge (Phase 3) | Yes |

## Quick Start

```bash
pip install aiohttp pyyaml && python -m relay_server --no-ssl
```

Run from the `hermes-relay` repo root on the same machine as hermes-agent.

## Deployment Options

### Docker

```bash
docker build -t hermes-relay relay_server/
docker run -d --name hermes-relay --network host \
  -v ~/.hermes:/home/relay/.hermes:ro hermes-relay
```

### Systemd

```bash
sudo cp relay_server/hermes-relay.service /etc/systemd/system/
# Edit the file: set User= and WorkingDirectory= for your setup
sudo systemctl daemon-reload
sudo systemctl enable --now hermes-relay
```

### TLS (production)

```bash
export RELAY_SSL_CERT=/etc/letsencrypt/live/yourdomain/fullchain.pem
export RELAY_SSL_KEY=/etc/letsencrypt/live/yourdomain/privkey.pem
python -m relay_server
```

Or terminate TLS at a reverse proxy (nginx/Caddy) in front of the relay.

## Configuration

All settings via environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `RELAY_HOST` | `0.0.0.0` | Bind address |
| `RELAY_PORT` | `8767` | Listen port |
| `RELAY_SSL_CERT` | — | TLS certificate path |
| `RELAY_SSL_KEY` | — | TLS private key path |
| `RELAY_WEBAPI_URL` | `http://localhost:8642` | Hermes API Server URL |
| `RELAY_HERMES_CONFIG` | `~/.hermes/config.yaml` | Hermes config path |
| `RELAY_LOG_LEVEL` | `INFO` | Logging level |

## CLI Flags

```
python -m relay_server [OPTIONS]

  --port PORT        Listen port (default: 8767)
  --no-ssl           Disable TLS (dev/localhost only)
  --log-level LEVEL  DEBUG, INFO, WARNING, ERROR
  --config PATH      Hermes config.yaml path
```

## App Setup

After starting the relay, configure the app to connect:

1. Open **Settings > Connection**
2. Enter **Relay URL**: `ws://your-server-ip:8767` (or `wss://` with TLS)
3. The app will pair via a 6-character code on first connection

## Health Check

```bash
curl http://localhost:8767/health
```

## Troubleshooting

- **Connection refused** — Is the relay running? `systemctl status hermes-relay` or `docker logs hermes-relay`
- **Auth failure** — Pairing codes expire after 10 minutes. Generate a new one in the app.
- **TLS errors** — Use `--no-ssl` for local dev. Ensure cert paths are correct for production.
- **Phone can't reach relay** — Check firewall rules for port 8767. Verify with `curl http://server-ip:8767/health` from another machine.

## Further Reading

- [Full relay server docs](https://github.com/Codename-11/hermes-relay/blob/main/docs/relay-server.md)
- [Architecture decisions](https://github.com/Codename-11/hermes-relay/blob/main/docs/decisions.md)
- [Specification](https://github.com/Codename-11/hermes-relay/blob/main/docs/spec.md)
