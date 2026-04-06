# Configuration

Hermes Relay stores its settings using Android's DataStore and EncryptedSharedPreferences. This page documents the available configuration options.

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
| Personality | `default` | Active personality for new messages |

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

## Server-Side Configuration

The Hermes API Server is configured via `~/.hermes/.env`:

```bash
# Required for Hermes Relay
API_SERVER_ENABLED=true
# API_SERVER_KEY=your-secret-key  # Optional — only set if exposing to network
API_SERVER_HOST=0.0.0.0
API_SERVER_PORT=8642

# Optional: Relay Server (for bridge/terminal)
# RELAY_SERVER_PORT=8767
# RELAY_SERVER_SSL_CERT=/path/to/cert.pem
# RELAY_SERVER_SSL_KEY=/path/to/key.pem
```

## Network Security Config

The app's `network_security_config.xml` controls which domains allow cleartext (HTTP) traffic:

- **Cleartext allowed:** `localhost`, `127.0.0.1`, `10.0.2.2` (emulator)
- **All other domains:** HTTPS required

To connect to a server without HTTPS on a local network, you have two options:
1. Set up a reverse proxy (nginx/Caddy) with TLS on the server
2. Use an SSH tunnel or VPN to the server, then connect via `localhost`
