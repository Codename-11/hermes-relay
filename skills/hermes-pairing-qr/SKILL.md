---
name: hermes-pairing-qr
description: Generate QR codes for pairing mobile devices with the Hermes API server. Reads connection details (host, port, API key) from Hermes config and renders a scannable QR code in the terminal. Use when the user wants to connect hermes-android or any mobile client to their gateway.
version: 1.0.0
author: Axiom-Labs
deprecated: true
deprecated_message: "Use the hermes-android plugin instead: install the plugin and run `hermes pair`"
tags: [hermes, pairing, qr, mobile, api-server, gateway, hermes-android, deprecated]
triggers:
  - user wants to pair a mobile device
  - user asks about QR code for Hermes
  - user wants to connect hermes-android
  - user mentions mobile pairing or device setup
---

> **DEPRECATED:** This standalone skill is superseded by the `hermes-android` plugin (v0.3.0+).
> Install the plugin and run `hermes pair` instead — no separate script or `qrencode` binary needed.
>
> ```bash
> cp -r plugin ~/.hermes/plugins/hermes-android
> hermes pair
> ```
>
> The plugin bundles the pairing CLI command using pure-Python QR rendering (via `segno`),
> so one install gives you both the 14 `android_*` device control tools and the QR pairing flow.
> This skill is kept for backward compatibility with hermes-agent v0.7.0 and earlier.

# Hermes Pairing QR Code

Generate a QR code containing API server connection details so a mobile device can scan and auto-configure its connection to the Hermes gateway.

## What To Do When This Skill Is Loaded

**Immediately run `hermes-pair` via the `terminal` tool.** No further user input needed — it reads config automatically and renders the QR. If the user provided arguments (e.g. a custom host or port), pass them as flags: `hermes-pair --host X --port Y`.

## Installation

### Install the skill

```bash
cp -r skills/hermes-pairing-qr ~/.hermes/skills/hermes-pairing-qr
```

### Install the script

```bash
cp skills/hermes-pairing-qr/hermes-pair ~/.local/bin/hermes-pair
chmod +x ~/.local/bin/hermes-pair
```

### Install qrencode (required)

```bash
sudo apt install qrencode    # Debian/Ubuntu
brew install qrencode         # macOS
```

### One-liner (everything)

```bash
cp -r skills/hermes-pairing-qr ~/.hermes/skills/hermes-pairing-qr && \
cp skills/hermes-pairing-qr/hermes-pair ~/.local/bin/hermes-pair && \
chmod +x ~/.local/bin/hermes-pair && \
sudo apt install -y qrencode 2>/dev/null || brew install qrencode 2>/dev/null
```

## Usage

```bash
hermes-pair              # QR in terminal + PNG
hermes-pair --png        # PNG only (no terminal QR)
hermes-pair --host IP    # Override host
hermes-pair --port PORT  # Override port
hermes-pair --help       # Usage info
```

## QR Payload Format

The QR code contains a compact JSON object:

```json
{"hermes":1,"host":"172.16.24.250","port":8642,"key":"your-api-key","tls":false}
```

| Field | Type | Description |
|-------|------|-------------|
| `hermes` | int | Protocol version marker (always `1`) |
| `host` | string | API server's reachable IP/hostname |
| `port` | int | API server port (default: `8642`) |
| `key` | string | Bearer token value (empty string if no auth) |
| `tls` | bool | Whether to use HTTPS (`false` for LAN) |

## Config Source Priority

**API Key:** `platforms.api_server.key` in config.yaml > `API_SERVER_KEY` in .env > empty

**Host:** `API_SERVER_HOST` env > config.yaml > `127.0.0.1` (auto-resolved to LAN IP)

**Port:** `API_SERVER_PORT` env > config.yaml > `8642`

If the host is `0.0.0.0`, `127.0.0.1`, or `localhost`, the script auto-detects the LAN IP.

## How the Android App Consumes It

The Hermes Relay Android app detects a Hermes QR by checking for `"hermes":1` in the scanned JSON. On detection:

1. Builds the server URL: `http(s)://host:port` based on the `tls` flag
2. Populates the API Server URL and API Key fields
3. Auto-triggers a connection test
4. Shows success/failure toast

Available in both the **Onboarding** flow (Connect page) and **Settings > API Server**.

## Security Notes

- The QR contains the **plaintext API key**. Warn users not to screenshot or share.
- If `API_SERVER_KEY` is empty and host is `0.0.0.0`, anyone on the network has full agent access. Always recommend setting a key.
- For internet-exposed setups, put the API server behind a reverse proxy with TLS and set `tls: true`.

## Troubleshooting

- **qrencode not installed** — `sudo apt install qrencode`
- **QR too dense to scan** — very long API keys produce dense QR codes. Try scanning from closer or use the PNG (`/tmp/hermes-pairing-qr.png`)
- **Host shows 127.0.0.1** — set `API_SERVER_HOST=0.0.0.0` in `~/.hermes/.env` for network access
- **TUI rendering broken** — run `hermes-pair` directly in a terminal, not through the agent's Rich response panel
