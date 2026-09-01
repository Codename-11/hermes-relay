# Remote Access

Hermes-Relay can keep one paired phone connected as it moves between LAN, Tailscale, a VPN, and a public reverse proxy. The primary recommended path today is Tailscale because it works behind CGNAT, encrypts traffic between tailnet devices, and keeps access inside your tailnet ACLs. Recommended setup exposes a dedicated HTTPS listener (`:10443` by default) and proxies local Dashboard `:9119`, avoiding conflicts with Traefik, Caddy, or nginx on `:443`. See [Is my connection secure?](../architecture/connection-security.md).

## What Uses Which Connection

Vanilla Hermes setup saves the Dashboard/Gateway address as the standard route.
Remote LAN, Tailscale, VPN, or public routes can be added to the same connection
and Android uses the highest-priority reachable one. API fallback and Relay
routes remain independently optional:

- **Chat, sessions, Manage, and standard voice** use the Dashboard/Gateway route and its dashboard session.
- **API fallback/headless compatibility** uses the API server URL and bearer only when configured.
- **Terminal, bridge, TUI, Relay session management, clipboard, profile writes, Android control, explicit Relay media tokens, and relay-token voice fallback** use the relay URL and require a paired relay session token. Ordinary inbound files use the authenticated Dashboard route.

The app stores these capabilities under one stable connection identity. One
Hermes connection can therefore use LAN at home and Tailscale away from home
without making API or Relay availability define whether standard chat is ready.

## Recommended: Tailscale

On the Hermes host:

```bash
hermes-relay-tailscale enable
hermes pair --mode auto --prefer tailscale
```

The recommended Tailscale stack listens on dedicated HTTPS `:10443` and proxies the local
Dashboard/Gateway on `:9119`, including the plugin's same-origin Relay
transport. Port `8642` remains an optional API fallback. The Relay process
still listens internally on `:8767`, but direct serving of that port is legacy
compatibility for already-paired clients and is not part of new QRs.

The helper advertises the detected `https://host.ts.net:10443` origin without local `:9119`;
the phone must use the actual tailnet listener, not the proxy's local target.
You can still manually add `http://100.x.y.z:9119` when Dashboard itself is
deliberately reachable on the raw tailnet IP, but that is not the helper's
recommended HTTPS mapping.
Android probes the Dashboard itself and handles Dashboard sign-in; it does not
look for `API_SERVER_KEY` on this path.

::: tip Two layers, both optional-to-stack
Your tailnet is already encrypted by WireGuard, so even a plain `http://100.x.y.z` route is
secure over Tailscale. `tailscale serve --https` adds a *separate* TLS layer on top, giving
  you a `wss://`/`https://` route fronted by a real certificate. Recommended
  setup uses dedicated HTTPS `:10443` → local Dashboard `:9119`. See
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
hermes pair --mode auto --dashboard-url https://hermes.example.com \
  --public-url https://hermes.example.com
```

Use `--prefer tailscale` when you want the phone to try Tailscale first but still keep LAN as a fallback:

```bash
hermes pair --mode auto --prefer tailscale
```

You can also override from the phone: **Settings -> Connections -> active connection -> Routes -> Prefer this route**.

Generated route lists prefer available secure candidates and retain LAN as a
fallback. A plain LAN fallback still requires its explicit acknowledgement; a
TLS or pin failure never silently converts a secure route into a plain one.

## Optional Hermes Secure Link

Hermes Secure Link is the Relay plugin's optional pinned-TLS ingress on port
`9443`.
Fixed `/relay`, `/api`, and `/dashboard` namespaces cover the Android surfaces
without mixing their credentials. Dashboard forwarding is available only when
its upstream OAuth/password gate is active. Pairing supplies the authority and SPKI
pin before the app connects. Certificate or hostname rotation therefore
requires explicit re-pairing.

The QR pin verifies continuity with the paired endpoint; it does not
independently prove the physical Hermes host's identity. Secure Link and
Tailscale solve different problems. Tailscale, another VPN,
LAN routing, or a public route makes the host reachable. Secure Link adds the
pairing-pinned Hermes TLS boundary after the host is reachable; it is not a
hosted rendezvous service and does not traverse NAT by itself. You can advertise
both Secure Link and Tailscale Serve in one pairing invite for failover.

One Secure Link origin carries fixed Relay, API, and authenticated Dashboard
namespaces, but it does not merge their trust domains. Relay pairing/session
auth, API bearer auth, and Dashboard cookie/native bearer auth remain separate.
Chat, Manage, voice, and API fallback may therefore use the Secure Link
namespaces when their own credentials are present, or use independent direct or
Tailscale HTTPS fallback routes. Tailscale Serve remains the normal recommended
setup; Secure Link is opt-in.

Enable it with `--secure-link` or `RELAY_SECURE_LINK_ENABLED=1`, then re-pair so
the new QR carries the exact origin and pin. The Relay `/health` response
reports `secure_link.status`; the Secure Link endpoint at
`https://<host>:9443/relay/health` reports its namespaces. A certificate,
hostname, or port change requires another explicit re-pair.

## Hermes Reach (experimental)

Hermes Reach is an experimental outbound-only path for hosts behind NAT or a
firewall. Both your Hermes host and phone connect outward to a hosted or
self-hosted Reach broker, so the host does not need an inbound port.

Reach provides **reachability**, while Secure Link provides **end-to-end
transport protection**. The broker matches and forwards opaque records; the
actual Hermes stream remains inside QR-pinned Secure Link TLS. Relay sessions,
API bearers, and Dashboard sign-in remain separate inside that protected
session.

The broker still sees connection metadata: the routing identity, source network
information, timing, and byte counts. It can block, delay, drop, or misroute a
connection. It cannot read inner paths, headers, credentials, or plaintext that
passes the pinned inner authentication. Reach is not an anonymity service.

Tailscale is the recommended remote-access path. Direct, public TLS, and Secure
Link routes are also attempted before Reach. Reach is disabled by default,
appears only in advanced/experimental UI, and never silently enables plaintext.

For operator testing, a self-hosted broker runs with `python -m
plugin.rendezvous --credentials <private-json> --listen 0.0.0.0 --port 9444
--tls-cert <cert> --tls-key <key>`. Configure the host with Secure Link plus
`RELAY_EXPERIMENTAL_REACH_ENABLED=1`,
`RELAY_SECURE_LINK_BROKER_URL=wss://<broker>` and
`RELAY_SECURE_LINK_BROKER_HOST_TOKEN=<raw-host-token>`. Relay `/health` reports
the connector under `secure_link.reach`; the broker's own `/health` returns only
aggregate service, protocol, online-host, and active-stream status. Public
listeners require TLS. Plain development mode is loopback-only.

## Which URL Do I Enter?

Normal connection and route fields use the **Dashboard/Gateway** address. On
LAN that is commonly local `:9119`; recommended Tailscale uses the external
dedicated HTTPS `:10443` listener that proxies local `:9119`. Relay rides the selected
Dashboard origin under the plugin transport path. Advanced endpoint settings
expose optional API fallback (`8642`). Direct Relay (`8767`) is legacy-only; do
not substitute it for a Dashboard or API address. The editor previews every
resolved surface before saving.

Pick the scheme by how the server is reached:

- **Raw Tailscale IP (`100.x.y.z`)** → normally
  `http://100.x.y.z:9119` for the Dashboard/Gateway. The dashboard must listen
  on an interface reachable through Tailscale. This route needs no API server
  or API key. An `http://` route over a raw Tailscale IP is **not
  plaintext on the wire** — WireGuard encrypts it end-to-end. It's secure
  transport, just not TLS (the app reports it as 🛡️ Tailscale, not ⚠️ Not
  encrypted).
- **`*.ts.net` hostname** → use the exact HTTPS Dashboard URL the helper
  published. Recommended setup listens on tailnet `:10443` and proxies local
  Dashboard `:9119`; use the advertised dedicated port, not the local target. Its
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

::: tip OIDC callback behind a reverse proxy
Register `<external Hermes Dashboard base>/auth/callback` as the allowed redirect
in Authelia, Authentik, or another identity provider. This is the browser-facing
Hermes Dashboard base, not the identity provider's issuer URL. Hermes normally
reconstructs it from trusted `X-Forwarded-*` headers. Set upstream
`dashboard.public_url` / `HERMES_DASHBOARD_PUBLIC_URL` only when that
reconstruction is unreliable, and include any path prefix. Android may still
connect over LAN or Tailscale and does not require a second sign-in URL field.
:::

Reverse proxies should expose the standard Dashboard/Gateway and whichever
optional capabilities you use:

- Dashboard/Gateway: `https://...` to local `127.0.0.1:9119`
- Relay: the Dashboard origin's `/api/plugins/hermes-relay/transport` base,
  which derives `/ws` and `/health` and proxies internally to `127.0.0.1:8767`
- Optional API fallback: `https://...` to local `127.0.0.1:8642`

Plain `ws://` and `http://` are acceptable only on a LAN or VPN you trust. The app requires explicit plain-transport consent before it uses those routes. Do not expose plain relay or API ports to the open internet.

## Troubleshooting

From the phone browser, verify the exact Dashboard/Gateway URL first, then any
optional endpoints you configured. The three URLs below are examples only; the
Dashboard URL depends on how you published it:

```text
https://<tailnet-host>.ts.net:10443/api/health
https://<tailnet-host>.ts.net:10443/api/plugins/hermes-relay/transport/health
https://<tailnet-host>.ts.net:8642/health
```

If the optional API health check fails while Dashboard/Gateway works, standard
chat remains available and only API fallback is unavailable. If Relay health
fails, terminal/bridge and Relay voice extensions are unavailable without
affecting the standard upstream path.

For the full operator matrix and reverse-proxy examples, see the [repository remote access guide](https://github.com/Codename-11/hermes-relay/blob/main/docs/remote-access.md).
