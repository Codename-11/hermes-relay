# Remote Access

Operator-facing setup guide for connecting a paired phone to a Hermes-Relay install that lives anywhere other than the same LAN as the phone.

## Overview

Upstream hermes-agent ships a loopback-by-default relay and takes the
"use a VPN, reverse proxy, or firewall — or don't expose" stance in
`SECURITY.md`. They don't plan to own a remote-access story. Hermes-Relay
does.

**Multi-endpoint pairing** (ADR 24) solves the "my phone moves between
LAN / Tailscale / a public hostname" problem: a single QR carries an
ordered list of endpoint candidates and the phone picks the
highest-priority reachable one at connect time. Re-probing happens
automatically on network change (`ConnectivityManager.NetworkCallback`)
so walking out of the house onto LTE seamlessly hops from the LAN
candidate to the Tailscale (or public) one. See `docs/decisions.md` §24
for the wire format and priority semantics.

**First-class Tailscale** (ADR 25) ships a thin helper that fronts
`127.0.0.1:8767` with `tailscale serve` for managed TLS + tailnet-ACL
identity — optional, auto-retires when upstream PR #9295 lands.

## Decision matrix

| Mode | Recommended for | Setup complexity | Notes |
|------|----------------|------------------|-------|
| **Tailscale (built-in)** | 95% of operators | One command | **Default recommendation.** `hermes-relay-tailscale enable`. Managed TLS, tailnet ACLs, works behind CGNAT, no DNS or certs to own. |
| **Caddy + Let's Encrypt** | Operators with a public domain | Moderate | Real public URL, real CA-signed cert, any browser can reach the dashboard. Requires a domain + port 80/443 reachable from the internet. |
| **Cloudflare Tunnel** | Residential / CGNAT setups | Moderate | No inbound ports, no domain required (free tryCloudflare subdomains work). Cloudflare is in the path — acceptable for the operator-owned trust model, but note the HTTP-level intercept. |
| **Self-hosted WireGuard** | Advanced operators | High | No external dependency. You own the crypto + peer config. We don't ship a WireGuard helper — use the upstream WireGuard docs. |
| **Plaintext `ws://` over VPN** | Dev / trusted network | None | Fine over a VPN or LAN you trust. Phone surfaces the `InsecureConnectionAckDialog` the first time and requires explicit opt-in. **Do not** expose plain `ws://` to the open internet. |

When in doubt, start with Tailscale. It meets the `tailscale serve`
contract PR #9295 will eventually land upstream — when that happens, our
helper detects the canonical flag and no-ops with a log line, same
auto-retire pattern `hermes_relay_bootstrap/` uses for session-API
endpoints.

## Setup

### Tailscale

Prerequisite: the `tailscale` CLI is installed and the daemon is logged
into your tailnet. `tailscale status` should print a non-error summary.

```bash
hermes-relay-tailscale enable
```

That's the whole thing. Under the hood it shells out to:

```bash
tailscale serve --bg --https=8767 http://127.0.0.1:8767
```

which publishes the loopback-bound relay port on the tailnet with a
Tailscale-managed TLS cert (issued by Tailscale's internal CA —
captured by the phone's TOFU pin on first connect, same mechanism as
any other wss cert).

Re-run `hermes-relay-pair` after enabling and the QR will include a
`role: tailscale` endpoint candidate pointing at
`wss://<your-tailnet-hostname>.ts.net:8767`, with auto-detection
driven by `tailscale.status()` from the same module. Scan once and
the phone gets both LAN and Tailscale targets.

Disable later with `hermes-relay-tailscale disable` (takes a `--port N`
if you used a non-default port). `hermes-relay-tailscale status`
prints the current Tailscale state + served ports.

### Caddy + Let's Encrypt

Caddy's autoprovisioning TLS flow is the shortest path to a real public
URL. Minimal `Caddyfile`:

```caddyfile
hermes.example.com {
    # Relay WSS — matches ACK 25's loopback-bound relay.
    reverse_proxy /relay ws://127.0.0.1:8767
    # API server — hermes-agent's direct HTTP/SSE surface.
    reverse_proxy /api/* http://127.0.0.1:8642
    reverse_proxy /v1/* http://127.0.0.1:8642
}
```

DNS `hermes.example.com` to the box running the relay, open 80/443 in
the firewall, start Caddy, and the first request provisions the cert
from Let's Encrypt. Pair with:

```bash
hermes-relay-pair --mode auto --public-url https://hermes.example.com
```

The QR will carry a `role: public` endpoint with
`relay.url = wss://hermes.example.com/relay` and
`api = { host: hermes.example.com, port: 443, tls: true }`.

### Cloudflare Tunnel

Works without a domain and without opening any inbound ports. Install
`cloudflared`, then:

```bash
# Quick-and-dirty free subdomain (good for testing).
cloudflared tunnel --url http://localhost:8767
# Outputs something like: https://random-words.trycloudflare.com
```

For a stable URL, create a named tunnel in the Cloudflare dashboard,
point a hostname at it, and run `cloudflared tunnel run <name>`. You
can front both ports — one tunnel per hostname, or one tunnel with
ingress rules mapping paths to the relay (`:8767`) vs. the API
server (`:8642`).

Pair with `hermes-relay-pair --mode auto --public-url https://<your-trycloudflare-url>`.

### Self-hosted WireGuard

High-level: stand up WireGuard on the relay host, add your phone as a
peer, route the phone's WireGuard IP to the relay. Once the phone is
on the tunnel, the relay's loopback/LAN IP is reachable directly — no
reverse proxy or TLS fronting needed, since the tunnel is E2E
encrypted.

We don't ship a WireGuard helper and don't plan to — the upstream
[WireGuard Quick Start](https://www.wireguard.com/quickstart/) is the
canonical guide. Once the phone is on the tunnel, pair with
`--mode auto` (which picks up the LAN IP and the tailscale status if
present) and nothing else is required.

### Plaintext `ws://` over VPN

If you're on a LAN you trust or a VPN you own end-to-end (Tailscale,
WireGuard, a commercial provider with an app-layer killswitch), plain
`ws://` to the relay's loopback-or-LAN IP is fine. The phone surfaces
the `InsecureConnectionAckDialog` with a reason picker (LAN-only /
Tailscale or VPN / Local dev) the first time it sees a `ws://` URL;
picking a reason is operator consent to the unencrypted transport
for that candidate. The reason is displayed in the Settings
Transport Security badge, not enforced — operator intent is the trust
model.

**Do not** use plaintext `ws://` over the open internet. A reverse
proxy or Tailscale costs nothing in setup time compared to the
interception risk.

## Combining modes

`hermes-relay-pair --mode auto` is the intended default. It does three
things in order:

1. Always emits a `role: lan` endpoint using the detected LAN IP.
2. Probes `tailscale.status()`; if a `.ts.net` hostname is present,
   emits a `role: tailscale` endpoint at the next priority slot.
3. If `--public-url <url>` is passed, emits a `role: public` endpoint
   at the final slot.

Resulting QR (three endpoints, strict-priority):

```json
{
  "hermes": 3,
  "endpoints": [
    { "role": "lan",       "priority": 0, "api": {...}, "relay": {"url": "ws://192.168.1.100:8767",  "transport_hint": "ws"}  },
    { "role": "tailscale", "priority": 1, "api": {...}, "relay": {"url": "wss://host.ts.net:8767",   "transport_hint": "wss"} },
    { "role": "public",    "priority": 2, "api": {...}, "relay": {"url": "wss://hermes.example.com/relay", "transport_hint": "wss"} }
  ]
}
```

**Strict priority** — priority 0 wins whenever it's reachable, even if
priority 1 is "better" by some other metric (lower latency, no billing
egress). If the operator put LAN at priority 0 they have a reason.
Reachability only breaks ties between candidates that share a priority.

The phone re-probes on every `ConnectivityManager.onAvailable` /
`onLost`, with a 30s cache per candidate so rapid network flaps don't
hammer the network with `HEAD /health` probes.

Force-override from the pair command: `--mode lan` (LAN only),
`--mode tailscale` (Tailscale only), `--mode public` (requires
`--public-url`; emits only that). Useful when you explicitly want one
candidate in the QR — e.g. pairing a phone that should never fall back
to LAN because it's not on your home network.

## Migrating from single-URL pairing

Operators with phones already paired on v0.6.x or earlier: **nothing
breaks, no re-pair required.**

- Old phones (v0.6.x and earlier) ignore the new `endpoints` field
  because the Android parser has `ignoreUnknownKeys = true`. They keep
  using the top-level `host`/`port`/`relay.url` values exactly as
  before.
- Old QRs (`hermes: 1` or `hermes: 2` payloads with no `endpoints`
  field) keep parsing on new phones. The phone synthesizes a single
  priority-0 candidate of `role: lan` (or `role: tailscale` if the
  top-level `host` matches the `100.64.0.0/10` CGNAT range or a
  `.ts.net` suffix — same heuristics `TailscaleDetector` already uses).
- Fresh pairings from v0.7+ pair commands emit `hermes: 3` and the
  `endpoints` array when any candidate is present.

Re-pair only when you want the multi-endpoint UX — e.g. you just
enabled Tailscale and want the phone to fall through to it when LAN is
unreachable. The Paired Devices screen in the app shows one row per
`(device, endpoint)`, so you can see at a glance which candidates a
given phone has.

## Troubleshooting

**Phone stuck on wrong endpoint.** Probably a stale reachability cache
or a NetworkCallback that didn't fire. Toggle airplane mode once;
that's the heaviest network-change signal Android will emit. If the
problem persists, disable + re-enable the offending mode
(`hermes-relay-tailscale disable && hermes-relay-tailscale enable`,
or toggle the Caddy site) and re-pair. Reachability cache TTL is 30s
per candidate.

**Tailscale serve not reaching phone.** Check tailnet ACLs —
`tailscale serve` publishes to the tailnet, and by default tailnets
allow all peers, but a locked-down ACL might block the phone from
reaching the relay host. Verify from the phone by opening
`https://<your-tailnet-hostname>.ts.net:8767/health` in its browser;
200 `{"status": "ok"}` means the tunnel is fine and the problem is
the pairing payload. `hermes-relay-tailscale status` also prints the
currently-served ports — if 8767 is missing, `enable` didn't succeed.

**Public URL reachable from the dashboard but not from the phone.**
Usually IPv6 or an egress firewall on the phone's network. Mobile
carriers and captive portals sometimes block outbound 8767/443 traffic
selectively. Test from the phone's browser first — same URL as the QR
embedded. If the browser can't reach it, neither can the pairing. For
carrier-grade firewalls, Cloudflare Tunnel (which fronts everything on
443 via Cloudflare's edge) routes around the problem.

**"Plaintext connection blocked" dialog won't dismiss.** That's
intentional — the phone requires explicit operator consent before
touching a `ws://` endpoint. Pick a reason in the dialog to consent;
the transport-security badge in Settings then shows the reason. To
reset, revoke the paired session from Settings → Paired Devices and
re-pair.

**Canonical upstream flag landed — is the helper still needed?**
`hermes-relay-tailscale status` calls `canonical_upstream_present()`
which probes `hermes gateway run --help | grep tailscale`. When
that returns true (PR #9295 has landed in your hermes-agent install),
the helper still works but the canonical path
(`hermes gateway run --tailscale`) is preferred and the helper will
be removed in a future release. Same retirement pattern as
`hermes_relay_bootstrap/` after PR #8556.

## See also

- `docs/decisions.md` §24 — multi-endpoint pairing wire format +
  priority semantics.
- `docs/decisions.md` §25 — Tailscale helper scope, upstream-retire
  criteria, alternatives rejected.
- `docs/security.md` — remote-connectivity section + overall trust
  model.
- `docs/relay-server.md` — `hermes-relay-tailscale` CLI reference +
  environment variables (`TS_AUTO`, `TS_DECLINE`).
