# Remote Access

Hermes-Relay can keep one paired phone connected as it moves between LAN, Tailscale, a VPN, and a public reverse proxy. The recommended path is Tailscale because it works behind CGNAT, gives you managed TLS, and keeps access inside your tailnet ACLs.

## What Uses Which Connection

Standard setup saves the API server URL and API key directly. If you also enter
the host's Tailscale API URL in Standard setup, Android stores both routes and
uses the highest-priority reachable one. A Relay pairing QR can also carry both
parts of the app when you enable the optional relay:

- **Chat and API-backed voice** use the Hermes API server URL and the Hermes API bearer key when one is configured.
- **Terminal, bridge, TUI, media/session management, clipboard, profile writes, Android control, and relay-token voice fallback** use the relay URL and require a paired relay session token.

The app stores your base API URL, optional Tailscale API URL, and relay URL on the connection, then uses the active route selected from saved route candidates at runtime. That means one saved connection can use LAN at home and Tailscale away from home.

## Recommended: Tailscale

On the Hermes host:

```bash
hermes-relay-tailscale enable
hermes pair --mode auto --prefer tailscale
```

The Tailscale helper publishes both required loopback services:

```bash
tailscale serve --bg --https=8767 http://127.0.0.1:8767
tailscale serve --bg --https=8642 http://127.0.0.1:8642
```

Port `8767` carries relay WSS and relay HTTP routes. Port `8642` carries the Hermes API server for chat, API-key voice auth, and endpoint health probes. If only `8767` is served, terminal/bridge may work while chat and API-key voice still fail remotely.

Check the served ports with:

```bash
hermes-relay-tailscale status
```

## One QR, Multiple Routes

Use `--mode auto` for the normal multi-endpoint QR:

```bash
hermes pair --mode auto
```

It emits LAN when available, adds Tailscale when the helper detects a tailnet hostname, and adds a public route when you pass `--public-url`:

```bash
hermes pair --mode auto --public-url https://hermes.example.com/relay
```

Use `--prefer tailscale` when you want the phone to try Tailscale first but still keep LAN as a fallback:

```bash
hermes pair --mode auto --prefer tailscale
```

You can also override from the phone: **Settings -> Connections -> active connection -> Routes -> Prefer this route**.

## Other Remote Paths

Reverse proxies work if they expose both services:

- Relay: `wss://...` to local `127.0.0.1:8767`
- API: `https://...` to local `127.0.0.1:8642`

Plain `ws://` and `http://` are acceptable only on a LAN or VPN you trust. The app requires explicit plain-transport consent before it uses those routes. Do not expose plain relay or API ports to the open internet.

## Troubleshooting

From the phone browser, verify both:

```text
https://<tailnet-host>.ts.net:8767/health
https://<tailnet-host>.ts.net:8642/health
```

If relay health works but API health fails, terminal/bridge can pair while chat, API-key voice, and route probes still fail. Re-run `hermes-relay-tailscale enable`, verify `API_SERVER_ENABLED=true`, and make sure the Hermes API server is listening on `127.0.0.1:8642` or `0.0.0.0:8642` on the host.

For the full operator matrix and reverse-proxy examples, see the [repository remote access guide](https://github.com/Codename-11/hermes-relay/blob/main/docs/remote-access.md).
