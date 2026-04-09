# Installation & Setup

## Prerequisites

- Android device or emulator (API 26+ / Android 8.0+)
- A running [Hermes Agent](https://hermes-agent.nousresearch.com) instance (v0.8.0+ recommended) with the API server enabled
- Python 3.11+ on the server (for the pairing plugin)

## Quick Start

Three commands get you from zero to connected:

### 1. Install the Android app

Download the latest APK from [GitHub Releases](https://github.com/Codename-11/hermes-relay/releases), or wait for the Play Store listing.

### 2. Install the server plugin

On the machine running your Hermes agent:

```bash
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash
```

This installs the `hermes-android` plugin into `~/.hermes/plugins/hermes-android` and pulls in its Python dependencies (`requests`, `aiohttp`, `segno`). Restart hermes to load it.

::: tip What you get
The plugin registers **14 `android_*` device control tools** (tap, type, read screen, screenshot, open apps, etc.) plus the **`hermes pair` CLI command** for generating pairing QR codes. No separate skill install, no `qrencode` binary needed.
:::

### 3. Pair your phone

On the server, run:

```bash
hermes pair
```

This prints a QR code **and** the plain-text connection details (server URL, API key). Scan the QR from the app's onboarding screen — or type the URL and key in manually if your terminal can't render QR blocks. The text fallback is always shown, so this works even inside Hermes's Rich TUI panel and over SSH sessions with limited charsets.

::: warning Security
The QR code contains your API key in plaintext. Don't screenshot or share it. The terminal output is equally sensitive.
:::

## Hermes Server Setup

Enable the API server in your Hermes configuration (`~/.hermes/.env`):

```bash
API_SERVER_ENABLED=true
API_SERVER_KEY=your-secret-key-here
API_SERVER_HOST=0.0.0.0  # Allow network access (default is localhost only)
API_SERVER_PORT=8642
```

::: tip API key is optional for local setups
If you're running Hermes on the same machine (or connecting via `localhost`), you can leave `API_SERVER_KEY` unset. The key is only needed when exposing the API server over the network. If you do set one, `hermes pair` reads it automatically.
:::

## Manual Install (from source)

If you prefer to build the app yourself:

```bash
git clone https://github.com/Codename-11/hermes-relay.git
cd hermes-relay
scripts/dev.bat build    # Build debug APK
scripts/dev.bat run      # Build + install + launch (requires connected device)
```

## Manual Pairing

If you don't want to use QR pairing, you can enter connection details by hand during the app's onboarding flow:

1. The app opens with an onboarding flow
2. On the **Connect** page, tap **Enter manually**
3. Type your API Server URL (e.g., `http://192.168.1.100:8642`) and API Key
4. Tap **Test Connection** to verify
5. Optionally enter a **Relay URL** for future Bridge/Terminal features
6. Tap **Get Started**

The `hermes pair` command always prints these same values as plain text alongside the QR code, so you can copy them directly.

## Relay Server (Optional)

The relay server is only needed for **Terminal** (remote shell) and **Bridge** (agent-driven phone control). Chat works without it.

::: tip One-liner install
```bash
pip install aiohttp pyyaml && python -m relay_server --no-ssl
```
Run this on the same machine as hermes-agent, from the hermes-relay repo root.
:::

For persistent deployment, Docker, systemd, and TLS options, see the [Relay Server docs](/reference/relay-server).

After starting the relay, enter the **Relay URL** in the app's onboarding or Settings > Connection (e.g., `ws://192.168.1.100:8767`).

## Verify Connection

After onboarding, the chat header shows the agent name with an animated green pulse on the avatar when the API server is reachable. If the dot is red (no pulse), check:

- Is the Hermes agent running? (`hermes gateway`)
- Is `API_SERVER_ENABLED=true`?
- Can your phone reach the server? (same network, firewall rules)
- Is the URL correct? (include port, e.g., `:8642`)
