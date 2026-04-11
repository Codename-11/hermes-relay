# hermes-relay

## Overview
This extension adds Android device control to hermes-agent via the `android` toolset.
It communicates with the Hermes-Relay app running on an Android device over WSS.

## Setup

### Quick start (canonical installer)

```bash
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash
```

This clones the repo to `~/.hermes/hermes-relay/`, `pip install -e`s the package into the hermes-agent venv, registers the clone's `skills/` directory in `~/.hermes/config.yaml` under `skills.external_dirs`, symlinks the plugin into `~/.hermes/plugins/hermes-relay`, and installs a `hermes-pair` shell shim into `~/.local/bin/`. Restart hermes-agent and everything is live. Updates are a `git pull` inside `~/.hermes/hermes-relay/`.

See [docs/relay-server.md](docs/relay-server.md) for Docker, systemd, TLS, and configuration options.

### Full setup
1. Install the Hermes-Relay APK on the Android device (build via `scripts/dev.bat build`)
2. Grant the app Accessibility Service permission in Settings > Accessibility
3. Grant SYSTEM_ALERT_WINDOW permission
4. Run the installer (above) and restart hermes-agent
5. Start the relay server if you need terminal/bridge: `python -m plugin.relay --no-ssl`
6. Pair the phone: type `/hermes-relay-pair` in any Hermes chat surface, or run `hermes-pair` from a shell. **Note:** the top-level `hermes pair` sub-command is not currently exposed — upstream argparser doesn't forward to plugin CLI dicts. Use the slash command or the dashed shim.

## Tool usage patterns

### Read before act
ALWAYS call android_read_screen before tapping. Never guess coordinates.

### Prefer text over coordinates
Use android_tap_text("Continue") over android_tap(x=540, y=1200).

### Wait after navigation
After opening an app or tapping a button that triggers loading,
always call android_wait with expected text before next action.

### Confirmation pattern for destructive actions
Before confirming a purchase, ride, or send action — always report
to the user what you're about to do and wait for approval.
Example: "I'm about to confirm an Uber ride to [destination] for [price].
Reply 'yes' to confirm."

## Common package names
- com.ubercab — Uber
- com.bolt.client — Bolt
- com.whatsapp — WhatsApp
- com.spotify.music — Spotify
- com.google.android.apps.maps — Google Maps
- com.android.chrome — Chrome
- com.google.android.gm — Gmail
- com.instagram.android — Instagram
- com.twitter.android — X/Twitter
