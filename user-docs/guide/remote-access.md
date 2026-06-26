# Remote Access

Hermes-Relay can keep one paired phone connected as it moves between LAN, Tailscale, a VPN, and a public reverse proxy. The recommended path is Tailscale because it works behind CGNAT, encrypts traffic end-to-end (WireGuard), keeps access inside your tailnet ACLs, and can *optionally* front TLS for you. (Note: the WireGuard encryption is what makes a tailnet link secure — TLS via `tailscale serve --https` is a separate, optional layer on top. See [Is my connection secure?](../architecture/connection-security.md).)

## What Uses Which Connection

Vanilla Hermes setup saves the API server URL and API key directly. The setup form
also has a **Remote access — Tailscale URL** field; fill it in and Android
stores both routes and uses the highest-priority reachable one. A Relay
pairing QR can also carry both parts of the app when you enable the optional
relay:

- **Chat and API-backed voice** use the Hermes API server URL and the Hermes API bearer key when one is configured.
- **Terminal, bridge, TUI, media/session management, clipboard, profile writes, Android control, and relay-token voice fallback** use the relay URL and require a paired relay session token.

The app stores your base API URL, optional Tailscale API URL, and relay URL on the connection, then uses the active route selected from saved route candidates at runtime. That means one saved connection can use LAN at home and Tailscale away from home.

## Recommended: Tailscale

On the Hermes host:

```bash
hermes-relay-tailscale enable
hermes pair --mode auto --prefer tailscale
```

The Tailscale helper publishes both required loopback services, fronting each with TLS:

```bash
tailscale serve --bg --https=8767 http://127.0.0.1:8767
tailscale serve --bg --https=8642 http://127.0.0.1:8642
```

Port `8767` carries relay WSS and relay HTTP routes. Port `8642` carries the Hermes API server for chat, API-key voice auth, and endpoint health probes. If only `8767` is served, terminal/bridge may work while chat and API-key voice still fail remotely.

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

Route fields want the **API server** (port `8642` by default) — never the
dashboard (`9119`) or relay (`8767`); those are derived from the host
automatically. You can type just a host or IP: `100.64.0.1` is saved as
`http://100.64.0.1:8642`, and the editor previews the exact URL before you
save.

Pick the scheme by how the server is reached:

- **Raw Tailscale IP (`100.x.y.z`) or LAN IP** → `http://` (the default).
  The Hermes API server speaks plain HTTP; an `https://` route against it
  fails its TLS handshake on every probe and never wins. This also requires
  the API server to listen beyond loopback (`0.0.0.0:8642` or the tailnet
  interface). Note that an `http://` route over a raw Tailscale IP is **not
  plaintext on the wire** — WireGuard encrypts it end-to-end. It's secure
  transport, just not TLS (the app reports it as 🛡️ Tailscale, not ⚠️ Not
  encrypted). A plain LAN IP, by contrast, has no such wrapping.
- **`*.ts.net` hostname fronted by `hermes-relay-tailscale enable`** →
  `https://` — Tailscale terminates TLS for the MagicDNS hostname (the cert
  is only valid for that name, not for the raw `100.x` IP). This adds TLS
  *on top of* the WireGuard encryption you already had over the tailnet.
- **Public reverse proxy** → `https://` with whatever host/port the proxy
  exposes.

After saving, the Routes card probes immediately and each row shows its
verdict — "Reachable", or "Unreachable" with the reason (TLS failure,
connection refused, timeout, HTTP status). A route that never shows
"Reachable" is misconfigured, not just unlucky.

## Add or Edit Routes on the Phone

You don't need to re-run setup (or use a QR) to add remote access later.
Open **Settings -> Connections -> active connection -> Show routes**:

- **Add route** opens an editor with Tailscale / Public / Custom presets and
  an API URL field. The relay and dashboard URLs are derived from the host
  automatically.
- Each fallback route's menu has **Edit route** and **Remove route**. The
  primary route mirrors the connection's API URL and is edited there instead.
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
