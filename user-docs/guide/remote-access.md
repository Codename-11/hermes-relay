# Remote Access

Hermes-Relay can keep one paired phone connected as it moves between LAN, Tailscale, a VPN, and a public reverse proxy. The recommended path is Tailscale because it works behind CGNAT, encrypts traffic end-to-end (WireGuard), keeps access inside your tailnet ACLs, and can *optionally* front TLS for you. (Note: the WireGuard encryption is what makes a tailnet link secure — TLS via `tailscale serve --https` is a separate, optional layer on top. See [Is my connection secure?](../architecture/connection-security.md).)

## What Uses Which Connection

Vanilla Hermes setup saves the Dashboard/Gateway address as the standard route.
Remote LAN, Tailscale, VPN, or public routes can be added to the same connection
and Android uses the highest-priority reachable one. API fallback and Relay
routes remain independently optional:

- **Chat, sessions, Manage, and standard voice** use the Dashboard/Gateway route and its dashboard session.
- **API fallback/headless compatibility** uses the API server URL and bearer only when configured.
- **Terminal, bridge, TUI, media/session management, clipboard, profile writes, Android control, and relay-token voice fallback** use the relay URL and require a paired relay session token.

The app stores these capabilities under one stable connection identity. One
Hermes connection can therefore use LAN at home and Tailscale away from home
without making API or Relay availability define whether standard chat is ready.

## Recommended: Tailscale

On the Hermes host:

```bash
hermes-relay-tailscale enable
hermes pair --mode auto --prefer tailscale
```

The Tailscale helper can publish the optional Relay and API services with TLS:

```bash
tailscale serve --bg --https=8767 http://127.0.0.1:8767
tailscale serve --bg --https=8642 http://127.0.0.1:8642
```

Port `8767` carries Relay WSS and HTTP routes. Port `8642` carries the optional
API fallback and API-bearer Relay voice compatibility. Publish the primary
Dashboard/Gateway on `:9119` separately; missing optional API does not break a
healthy dashboard route.

You can add and test a Tailscale Dashboard address without configuring either
optional service. Use `http://100.x.y.z:9119` for a directly reachable dashboard,
or the `https://host.ts.net` URL and port/path from your own Dashboard proxy.
Android probes the Dashboard itself and handles Dashboard sign-in; it does not
look for `API_SERVER_KEY` on this path.

::: tip Two layers, both optional-to-stack
Your tailnet is already encrypted by WireGuard, so even a plain `http://100.x.y.z` route is
secure over Tailscale. `tailscale serve --https` adds a *separate* TLS layer on top, giving
you a `wss://`/`https://` route fronted by a real certificate (the dashboard on `:9119` is
not fronted by the helper — front it yourself if you want TLS there). See
[Is my connection secure?](../architecture/connection-security.md) for which the app reports
as 🔒 TLS vs 🛡️ Tailscale (both secure).
:::

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

## Which URL Do I Enter?

Normal connection and route fields use the **Dashboard/Gateway** address (port
`9119` by convention). Advanced endpoint settings expose API fallback (`8642`)
and Relay (`8767`) independently; do not substitute one service's port for
another. The editor previews the exact URL before saving.

Pick the scheme by how the server is reached:

- **Raw Tailscale IP (`100.x.y.z`)** → normally
  `http://100.x.y.z:9119` for the Dashboard/Gateway. The dashboard must listen
  on an interface reachable through Tailscale. This route needs no API server
  or API key. An `http://` route over a raw Tailscale IP is **not
  plaintext on the wire** — WireGuard encrypts it end-to-end. It's secure
  transport, just not TLS (the app reports it as 🛡️ Tailscale, not ⚠️ Not
  encrypted).
- **`*.ts.net` hostname** → use the exact `http://` or `https://` Dashboard URL
  your operator published. `hermes-relay-tailscale enable` only fronts the
  optional Relay and API ports; it does not automatically publish Dashboard
  `:9119`. If you separately front the Dashboard with Tailscale HTTPS, its
  certificate is valid for the `.ts.net` name, not the raw `100.x` IP.
- **LAN IP** → normally `http://host:9119`. Unlike a raw Tailscale route, plain
  LAN HTTP has no WireGuard transport layer.
- **Public reverse proxy** → `https://` with whatever host/port the proxy
  exposes.

After saving, the Routes card probes immediately and each row shows its
verdict — "Reachable", or "Unreachable" with the reason (TLS failure,
connection refused, timeout, HTTP status). A route that never shows
"Reachable" is misconfigured, not just unlucky.

## Add or Edit Routes on the Phone

You don't need to re-run setup (or use a QR) to add remote access later.
Open **Settings -> Connections -> active connection -> Show routes**:

- **Add route** opens an editor with Tailscale / Public / Custom presets for the
  Dashboard/Gateway. Entering a `100.x` or `.ts.net` Dashboard address is enough
  to save and test that route. Advanced settings can add matching API and Relay
  endpoints, but they are not prerequisites.
- Each fallback route's menu has **Edit route** and **Remove route**. The
  primary route mirrors the connection's Dashboard/Gateway URL and is edited there instead.
- When the phone is on Tailscale but the connection has no Tailscale route,
  the Connections card shows an **Add Tailscale route** shortcut.

Saved routes take effect immediately — the app re-probes and switches without
a reconnect. The setup result card and the status pill both call out when a
connection is LAN-only so you know remote access isn't configured yet.

::: tip One sign-in per route
Dashboard sessions are per-host. The first time Manage or voice runs over a
new route (for example the Tailscale URL), sign in to Manage once on that
route; the app keeps both sessions afterwards.
:::

## Other Remote Paths

Reverse proxies should expose the standard Dashboard/Gateway and whichever
optional capabilities you use:

- Dashboard/Gateway: `https://...` to local `127.0.0.1:9119`
- Relay: `wss://...` to local `127.0.0.1:8767`
- Optional API fallback: `https://...` to local `127.0.0.1:8642`

Plain `ws://` and `http://` are acceptable only on a LAN or VPN you trust. The app requires explicit plain-transport consent before it uses those routes. Do not expose plain relay or API ports to the open internet.

## Troubleshooting

From the phone browser, verify the exact Dashboard/Gateway URL first, then any
optional endpoints you configured. The three URLs below are examples only; the
Dashboard URL depends on how you published it:

```text
https://<tailnet-host>.ts.net:9119
https://<tailnet-host>.ts.net:8767/health
https://<tailnet-host>.ts.net:8642/health
```

If the optional API health check fails while Dashboard/Gateway works, standard
chat remains available and only API fallback is unavailable. If Relay health
fails, terminal/bridge and Relay voice extensions are unavailable without
affecting the standard upstream path.

For the full operator matrix and reverse-proxy examples, see the [repository remote access guide](https://github.com/Codename-11/hermes-relay/blob/main/docs/remote-access.md).
