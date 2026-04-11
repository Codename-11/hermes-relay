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
# If you installed the hermes-relay plugin (recommended):
hermes relay start --no-ssl

# Or directly from a repo checkout:
python -m plugin.relay --no-ssl
```

Run on the same machine as hermes-agent. The canonical implementation lives at `plugin/relay/`; the top-level `relay_server/` package is a thin compat shim that delegates to it, so legacy entrypoints (`python -m relay_server`) still work.

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
hermes relay start [OPTIONS]          (or: python -m plugin.relay)

  --host HOST        Bind address (default: 0.0.0.0)
  --port PORT        Listen port (default: 8767)
  --no-ssl           Disable TLS (dev/localhost only)
  --shell PATH       Default shell for terminal sessions (absolute path; default: $SHELL)
  --webapi-url URL   Hermes WebAPI base URL (default: http://localhost:8642)
  --log-level LEVEL  DEBUG, INFO, WARNING, ERROR
  --config PATH      Hermes config.yaml path (python -m plugin.relay only)
```

## HTTP Routes

| Route | Method | Purpose |
|-------|--------|---------|
| `/ws`, `/` | GET (upgrade) | WebSocket endpoint — phone connects here |
| `/health` | GET | `{status, version, clients, sessions}` JSON |
| `/pairing` | POST | Generate a new relay-side pairing code |
| `/pairing/register` | POST | **Loopback only.** Pre-register an externally-provided pairing code so it can be embedded in a QR payload. Used by `/hermes-relay-pair` / `hermes-pair` on the same host. Rejects non-loopback peers with HTTP 403. |

## Pairing Model

The phone does **not** enter a pairing code by hand. Instead, the pair command (the `/hermes-relay-pair` slash command or the `hermes-pair` shell shim, both running on the Hermes host) drives the whole handshake:

1. The pair command mints a fresh 6-character code from `A-Z / 0-9`
2. It POSTs the code to `/pairing/register` on the local relay (blocked for any caller outside `127.0.0.1` / `::1`)
3. It embeds the relay URL and code in the same QR payload that carries the API server credentials
4. The phone scans once — the relay block auto-configures Settings > Connection
5. The phone's first WSS connect uses that code in its `system/auth` envelope; the relay consumes it and issues a 30-day session token

Pairing codes are one-shot and expire 10 minutes after registration. Session tokens (stored in EncryptedSharedPreferences on device) are used for all subsequent reconnects.

## Health Check

```bash
curl http://localhost:8767/health
```

## Troubleshooting

- **Connection refused** — Is the relay running? `systemctl status hermes-relay` or `docker logs hermes-relay`
- **Auth failure** — Pairing codes expire 10 minutes after registration and are one-shot. Re-run `hermes-pair` (or `/hermes-relay-pair`) to mint a fresh code and get a new QR.
- **QR has no relay block** — the pair command only embeds relay details if it can reach `localhost:RELAY_PORT/health` when it runs. Start the relay first, then re-run `hermes-pair`.
- **TLS errors** — Use `--no-ssl` for local dev. Ensure cert paths are correct for production.
- **Phone can't reach relay** — Check firewall rules for port 8767. Verify with `curl http://server-ip:8767/health` from another machine.

## Further Reading

- [Full relay server docs](https://github.com/Codename-11/hermes-relay/blob/main/docs/relay-server.md)
- [Architecture decisions](https://github.com/Codename-11/hermes-relay/blob/main/docs/decisions.md)
- [Specification](https://github.com/Codename-11/hermes-relay/blob/main/docs/spec.md)
