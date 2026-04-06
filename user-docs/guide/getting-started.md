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
git clone https://github.com/Codename-11/hermes-android.git
cd hermes-android
scripts/dev.bat build    # Build debug APK
scripts/dev.bat run      # Build + install + launch
```

### From Release

Download the latest APK from [GitHub Releases](https://github.com/Codename-11/hermes-android/releases).

## First Launch

1. The app opens with an onboarding flow
2. Enter your **API Server URL** (e.g., `http://192.168.1.100:8642`)
3. Enter your **API Key** if the server has one set (optional for local setups)
4. Tap **Test Connection** to verify
5. Optionally enter a **Relay URL** for future Bridge/Terminal features
6. Tap **Get Started**

## Verify Connection

After onboarding, the chat screen shows a green dot next to "Hermes Chat" when the API server is reachable. If you see a red dot, check:

- Is the Hermes agent running? (`hermes gateway`)
- Is `API_SERVER_ENABLED=true`?
- Can your phone reach the server? (same network, firewall rules)
- Is the URL correct? (include port, e.g., `:8642`)
