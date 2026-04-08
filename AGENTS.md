# hermes-android

## Overview
This extension adds Android device control to hermes-agent via the `android` toolset.
It communicates with the Hermes Relay app running on an Android device over WSS.

## Setup

### Quick start (relay + plugin)

```bash
pip install aiohttp pyyaml && python -m relay_server --no-ssl   # start relay
cp -r plugin ~/.hermes/plugins/hermes-android                    # install plugin
```

Then restart hermes-agent. See [docs/relay-server.md](docs/relay-server.md) for Docker, systemd, TLS, and configuration options.

### Full setup
1. Install the Hermes Relay APK on the Android device (build via `scripts/dev.bat build`)
2. Grant the app Accessibility Service permission in Settings > Accessibility
3. Grant SYSTEM_ALERT_WINDOW permission
4. Start the relay server: `pip install aiohttp pyyaml && python -m relay_server --no-ssl`
5. Install the plugin: `cp -r plugin ~/.hermes/plugins/hermes-android`
6. Restart hermes-agent

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
