# Installation & Setup

## Prerequisites

- Android device or emulator (API 26+ / Android 8.0+)
- A running [Hermes Agent](https://hermes-agent.nousresearch.com) instance with the API server enabled

## Hermes Server Setup

Enable the API server in your Hermes configuration (`~/.hermes/.env`):

```bash
API_SERVER_ENABLED=true
API_SERVER_KEY=your-secret-key-here
API_SERVER_HOST=0.0.0.0  # Allow network access (default is localhost only)
API_SERVER_PORT=8642
```

::: tip API key is optional for local setups
If you're running Hermes on the same machine (or connecting via `localhost`), you can leave `API_SERVER_KEY` unset. The key is only needed when exposing the API server over the network. If you do set one, you'll enter the same value in the app during onboarding.
:::

## Install the App

### From Source

```bash
git clone https://github.com/Codename-11/hermes-relay.git
cd hermes-relay
scripts/dev.bat build    # Build debug APK
scripts/dev.bat run      # Build + install + launch
```

### From Release

Download the latest APK from [GitHub Releases](https://github.com/Codename-11/hermes-relay/releases).

## First Launch

1. The app opens with an onboarding flow
2. On the **Connect** page, either:
   - **Scan a QR code** — tap "Scan QR Code" and point at a Hermes pairing QR (see below)
   - **Enter manually** — type your API Server URL (e.g., `http://192.168.1.100:8642`) and API Key
3. Tap **Test Connection** to verify
4. Optionally enter a **Relay URL** for future Bridge/Terminal features
5. Tap **Get Started**

### QR Code Pairing (Recommended)

The fastest way to connect. On your Hermes server, run:

```bash
hermes-pair
```

This generates a QR code in your terminal containing your server URL and API key. Scan it with the app to auto-fill everything.

::: tip Install the pairing skill
If `hermes-pair` isn't available, install it from the hermes-relay repo:
```bash
cp skills/hermes-pairing-qr/hermes-pair ~/.local/bin/hermes-pair
chmod +x ~/.local/bin/hermes-pair
sudo apt install qrencode
```
Or install the full skill so your agent can generate QR codes on demand:
```bash
cp -r skills/hermes-pairing-qr ~/.hermes/skills/hermes-pairing-qr
```
:::

::: warning Security
The QR code contains your API key in plaintext. Don't screenshot or share it.
:::

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
