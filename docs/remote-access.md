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

**First-class Tailscale** (ADR 25) is the primary supported remote path
today. Its helper publishes the upstream Dashboard (`127.0.0.1:9119`) and
optional Hermes API server (`127.0.0.1:8642`) to the tailnet. The Dashboard
origin owns Chat, Manage, standard voice, and the plugin's same-origin Relay
transport. The Relay process may still listen internally on `127.0.0.1:8767`,
but new pairings do not advertise that direct port. A served `:8767` remains
only for older paired clients until they are re-paired.

Tailscale is preferred over a public HTTPS route because it keeps reachability
inside an authenticated, ACL-controlled private network. Public HTTPS is still
secure and remains the next remote fallback. Raw `http://` / `ws://` addresses
on a tailnet have no application TLS, but Tailscale's WireGuard data plane still
encrypts traffic between tailnet devices. Use Tailscale Serve HTTPS where it is
available; do not describe raw tailnet HTTP as public plaintext transport.

Optional **Hermes Secure Link**
is also available for Android traffic.
It listens on `:9443`, uses the SPKI pin carried by the pairing QR, and exposes
fixed `/relay`, `/api`, and `/dashboard` namespaces. Each service retains its
own authentication. See [`docs/security-native-proxy.md`](security-native-proxy.md) for the
security contract.

These are different layers, not competing names for the same tunnel. Tailscale
provides reachability across NAT plus tailnet ACL identity and can terminate
publicly trusted TLS. Secure Link provides a pairing-pinned Hermes ingress after
the host is reachable; it does not run a rendezvous service, traverse NAT, or
replace the network path. They can coexist, and pairing may advertise both.

**Hermes Reach** is a third, experimental layer retained for advanced testing. It
supplies outbound-only rendezvous when neither side can accept a direct inbound
connection. The host and client each open WSS to a Reach broker; the broker
matches opaque streams while the actual Hermes session remains inside
QR-pinned Secure Link TLS. Reach can be hosted or self-hosted. It does not
replace Relay/API/Dashboard authentication, identify the physical host, or
provide anonymity.

## Decision matrix

| Mode | Recommended for | Setup complexity | Notes |
|------|----------------|------------------|-------|
| **Tailscale (built-in)** | 95% of operators | One command | **Default recommendation.** `hermes-relay-tailscale enable`. Private WireGuard transport, tailnet ACLs, works behind CGNAT, no public DNS to own. Tailscale Serve may add HTTPS; raw tailnet HTTP is still encrypted by Tailscale but has no application TLS. |
| **Hermes Secure Link** | Operators who need a pairing-pinned Hermes ingress | Low | Opt-in `:9443` listener; QR-pinned endpoint SPKI; fixed Relay, API, and authenticated Dashboard namespaces. Requires an independently reachable host via LAN, VPN/Tailscale, or public routing. |
| **Hermes Reach** *(experimental)* | Advanced evaluation of outbound rendezvous | High | Disabled by default and always ordered last. Both sides connect outbound to a self-hosted broker while inner Secure Link TLS protects Hermes traffic. Not recommended for normal remote access. |
| **Caddy + Let's Encrypt** | Operators with a public domain | Moderate | Real public URL, real CA-signed cert, any browser can reach the dashboard. Requires a domain + port 80/443 reachable from the internet. |
| **Cloudflare Tunnel** | Residential / CGNAT setups | Moderate | No inbound ports, no domain required (free tryCloudflare subdomains work). Cloudflare is in the path — acceptable for the operator-owned trust model, but note the HTTP-level intercept. |
| **Self-hosted WireGuard** | Advanced operators | High | No external dependency. You own the crypto + peer config. We don't ship a WireGuard helper — use the upstream WireGuard docs. |
| **Plaintext `ws://` over VPN** | Dev / trusted network | None | Fine over a VPN or LAN you trust. Phone surfaces the `InsecureConnectionAckDialog` the first time and requires explicit opt-in. **Do not** expose plain `ws://` to the open internet. |

When in doubt, start with Tailscale. It meets the `tailscale serve`
contract PR #9295 will eventually land upstream — when that happens, our
helper detects the canonical flag and no-ops with a log line, same
auto-retire pattern `hermes_relay_bootstrap/` uses for its remaining
compatibility endpoints (its session-API endpoints have since been fully
retired in favor of native upstream).

## Setup

### Tailscale

Prerequisite: the `tailscale` CLI is installed and the daemon is logged
into your tailnet. `tailscale status` should print a non-error summary.

```bash
hermes-relay-tailscale enable
```

That's the whole thing. The recommended stack publishes:

- Dashboard/Gateway and same-origin Relay ingress: `:9119` (required)
- API-server fallback: `:8642` (optional)
- direct Relay: `:8767` (legacy compatibility only)

The helper uses `tailscale serve` when the installed Tailscale version supports
the requested stack. Tailscale may expose a managed HTTPS hostname or a raw
tailnet address; both travel through the encrypted tailnet, while only the
HTTPS form has application-layer TLS.

Re-run `hermes pair --mode auto --dashboard-url <dashboard-origin>` after
enabling. The QR includes a `role: tailscale` candidate whose Dashboard URL and
Relay URL share the `:9119` origin; Relay uses
`/api/plugins/hermes-relay/transport`; the client derives its `/ws` and
`/health` endpoints from that base. The optional API fallback remains on
`:8642`. Scan once and the phone gets Tailscale, public HTTPS when configured,
and LAN fallbacks in the signed order. The Dashboard Pair dialog and CLI/TUI QR
surface emit the same route shape.

Disable later with `hermes-relay-tailscale disable`. After all old clients are
re-paired, remove a legacy direct route explicitly with
`hermes-relay-tailscale disable --port 8767`. The Dashboard never silently
removes it. `hermes-relay-tailscale status` prints the current served ports.

### Caddy + Let's Encrypt

Caddy's autoprovisioning TLS flow is the shortest path to a real public
URL. Minimal `Caddyfile`:

```caddyfile
hermes.example.com {
    # One public origin for Dashboard, Gateway, and plugin Relay ingress.
    reverse_proxy http://127.0.0.1:9119
}
```

DNS `hermes.example.com` to the box running the relay, open 80/443 in
the firewall, start Caddy, and the first request provisions the cert
from Let's Encrypt. Pair with:

```bash
hermes pair --mode auto \
  --dashboard-url https://hermes.example.com \
  --public-url https://hermes.example.com
```

The QR carries a `role: public` endpoint with
`dashboard.url = https://hermes.example.com` and
`relay.url = wss://hermes.example.com/api/plugins/hermes-relay/transport`.
An API candidate is included only when the optional API fallback is configured.
An explicit Relay proxy path remains accepted for legacy deployments, but a
pathless public HTTPS value means a Dashboard origin and never synthesizes
public `:8767`.

### Hermes Secure Link

Enable Secure Link when a pairing-pinned unified route is desired. Its
default listener is `https://<host>:9443`; Relay health is
`GET /relay/health`, the authenticated Relay WebSocket is
`wss://<host>:9443/relay/ws`. The operator-reviewed pairing QR carries the
pin. API-server traffic uses `/api/*`; Dashboard traffic uses `/dashboard/*`
and is enabled only while Dashboard's real authentication gate is active.
Android and the desktop CLI validate the advertised authority and SPKI SHA-256
pin before making a proxy request.

Secure Link does not itself make the host reachable from outside the LAN. Use
Tailscale, another VPN, port forwarding, or a public tunnel for that layer. With
Tailscale enabled, Secure Link may travel over the tailnet while preserving its
separate pairing pin; direct Tailscale Serve WSS/HTTPS remains a valid sibling
candidate and fallback.

Secure Link is intentionally not a general reverse proxy. Its fixed API and
Dashboard namespaces forward only to their configured loopback services, and
its Relay namespace exposes only health and the authenticated WebSocket.
Pairing, session-management, bridge HTTP, media-inspection, and other
loopback-trusted Relay routes remain absent. Relay sessions, API bearer
credentials, and Dashboard cookies or native bearer credentials remain
isolated even though all three services share one pinned TLS origin.
Certificate or hostname rotation requires explicit re-pairing; clients do not
learn replacement trust from a health response or `auth.ok`.

Secure Link is opt-in. Start the Relay with `--secure-link` or set
`RELAY_SECURE_LINK_ENABLED=1`; the default bind is `0.0.0.0:9443`. The Relay
health payload reports `secure_link.status` as `disabled`, `available`, or
`unavailable`, and `GET https://<host>:9443/relay/health` reports the enabled
capabilities. Pair again after enabling it so the signed QR contains the exact
authority and SPKI pin. Legacy `RELAY_SECURE_PROXY_*` environment names remain
accepted for compatibility, but new configuration should use
`RELAY_SECURE_LINK_*`.

### Hermes Reach (experimental)

Hermes Reach is the public name for the experimental `outbound_broker` route. It is a
reachability service, not another name for Secure Link or Tailscale:

- **Reach** gets two outbound connections to meet when the host cannot accept an
  inbound connection.
- **Secure Link** supplies the QR-pinned inner TLS session and fixed
  Relay/API/Dashboard namespaces.
- **Tailscale** supplies a private overlay network and device identity.

The standalone broker runs as `python -m plugin.rendezvous`, exposes
`GET /health`, and accepts public WSS streams at `/v1/connect`. A host maintains
one outbound control connection; client streams are matched through opaque
identifiers. After matching, the broker forwards framed ciphertext without
terminating the inner Secure Link TLS session.

The broker can observe the broker account or host identity used for routing,
source network information, connection timing, and byte counts. It can deny,
delay, drop, or misroute traffic. It cannot read inner paths, headers,
application credentials, or plaintext that successfully authenticates through
the QR-pinned inner TLS session. Do not describe Reach as anonymous,
zero-knowledge, or secure merely because the outer hop uses WSS.

The QR bootstrap credential is raw, one-use, and expiring. The broker stores
and atomically consumes only its SHA-256 hash. A durable route token is scoped
to an opaque credential/session identifier and is returned only inside the
inner Relay `auth.ok` after successful pairing. Durable reconnect is implemented,
but Reach remains experimental while deployment, recovery, and operator UX are
still being evaluated.

Direct, Tailscale, public TLS, and Secure Link routes remain independent
candidates. A Reach failure may select another explicitly configured secure
route; it never authorizes a silent plaintext downgrade. Exact connector flags,
status fields, and production hosting instructions are intentionally explicit:

1. The host connector persists an opaque host ID (by default under
   `~/.hermes/relay-secure-link/host-id`). Provision one raw host-registration
   token to the host, then register that host ID and only the token's base64url
   SHA-256 digest in the broker's private `hosts` credential file.
2. Run the broker with `python -m plugin.rendezvous --credentials <file>
   --state /var/lib/hermes-reach/routes.json --listen 0.0.0.0 --port 9444
   --tls-cert <cert> --tls-key <key>`. The state file stores only credential
   hashes and bounded expiry/revocation metadata; preserve it across restarts.
3. On the Hermes host, explicitly enable experimental Reach with
   `RELAY_EXPERIMENTAL_REACH_ENABLED=1`, enable Secure Link, and set
   `RELAY_SECURE_LINK_BROKER_URL=wss://<broker>` plus
   `RELAY_SECURE_LINK_BROKER_HOST_TOKEN=<raw-host-token>`, then restart Relay.
4. Check Relay `/health` → `secure_link.reach.state`. Expected connector states
   are `connecting`, `connected`, or `backoff`; `unavailable` indicates partial
   configuration or a missing local Secure Link listener.

The broker itself reports only `ok`, `service: "hermes_reach"`, protocol
version, online-host count, and active-stream count from `GET /health`. The host
token is never placed in a QR. End-user pairing remains gated on the pairing
publisher and durable-token acceptance tests described above.

### Cloudflare Tunnel

Works without a domain and without opening any inbound ports. Install
`cloudflared`, then:

```bash
# Publish Dashboard, Gateway, and same-origin Relay ingress together.
cloudflared tunnel --url http://localhost:9119
# Outputs something like: https://random-words.trycloudflare.com
```

For a stable URL, create a named tunnel in the Cloudflare dashboard,
point a hostname at it, and run `cloudflared tunnel run <name>`. Point the
public hostname at Dashboard `:9119`; the plugin transport carries Relay under
that same origin. Add a separate `:8642` API route only if the optional API
fallback is required.

Pair with `hermes pair --mode auto --dashboard-url https://<your-trycloudflare-url>
--public-url https://<your-trycloudflare-url>`. An explicit Relay-only proxy
path is accepted only for legacy compatibility and is labeled that way in the
Dashboard.

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

`hermes-relay-pair --mode auto` emits every available candidate. Clients honor
the signed ordering secure-first and retain LAN as a separately configured
fallback:

1. If Hermes Secure Link is enabled, emit its pinned `plugin_proxy`
   candidate.
2. If Tailscale is available, emit its private Dashboard `:9119` candidate and
   same-origin Relay path as the normal primary remote path.
3. Preserve public TLS candidates when configured.
4. Preserve LAN as the fallback; plain LAN still requires explicit consent.

Resulting QR (three endpoints, strict-priority):

```json
{
  "hermes": 3,
  "endpoints": [
    { "role": "tailscale", "priority": 0, "dashboard": {"url": "http://100.64.0.5:9119"}, "relay": {"url": "ws://100.64.0.5:9119/api/plugins/hermes-relay/transport"}, "api": {...} },
    { "role": "public",    "priority": 1, "dashboard": {"url": "https://hermes.example.com"}, "relay": {"url": "wss://hermes.example.com/api/plugins/hermes-relay/transport"}, "api": {...} },
    { "role": "lan",       "priority": 2, "dashboard": {"url": "http://192.168.1.100:9119"}, "relay": {"url": "ws://192.168.1.100:9119/api/plugins/hermes-relay/transport"}, "api": {...} }
  ]
}
```

**Strict priority** — priority 0 wins whenever it is reachable. Reachability
only breaks ties between candidates that share a priority. Operator overrides
can still promote a role deliberately, but generated defaults prefer secure
routes and use LAN as fallback. Supported priorities probe speculatively in
parallel and are consumed in strict order, so a dead public route does not add
its full timeout before Tailscale/LAN probing begins.

The phone re-probes on every `ConnectivityManager.onAvailable` /
`onLost`, with a 60s cache per candidate so rapid network flaps don't
hammer the network with `GET` health probes.

Force-override from the pair command: `--mode lan` (LAN only),
`--mode tailscale` (Tailscale only), `--mode public` (requires
`--public-url`; emits only that). Useful when you explicitly want one
candidate in the QR — e.g. pairing a phone that should never fall back
to LAN because it's not on your home network.

### Promoting a role to priority 0 — `--prefer`

Added 2026-04-19. `--prefer <role>` (open vocab — commonly `lan` /
`tailscale` / `public`, but any role string works) promotes the named
role to priority 0 with the rest renumbered in their natural order. The
QR still embeds all detected candidates; only the probe order changes.

```bash
# All three modes detected, but Tailscale probed first
hermes pair --mode auto --dashboard-url https://hermes.example.com \
  --public-url https://hermes.example.com --prefer tailscale
```

Result: `[(0, tailscale), (1, public), (2, lan)]` — phone tries the
tailnet first, keeps the public TLS route as its next secure option, and
uses acknowledged LAN only as the final fallback.

**Matching is case-insensitive** but the emitted `role` string is
preserved verbatim (HMAC canonicalization requires the wire form to
round-trip unchanged). **Unknown role** → stderr warning + natural
order. **Role already at priority 0** → no-op.

Works identically from three surfaces:

- **CLI:** `hermes pair --prefer tailscale`
- **Skill:** `/hermes-relay-pair` documented in
  [`skills/devops/hermes-relay-pair/SKILL.md`](../skills/devops/hermes-relay-pair/SKILL.md)
- **Dashboard:** Remote Access tab → Endpoint preview card →
  **Prefer role** dropdown → Regenerate QR

On the phone side, the per-session equivalent is Settings → Connections
→ [active card] → Routes expander → row menu → "Prefer this route."
Server-side `--prefer` sets the *baseline* order in the QR; phone-side
override is *per-session* and survives network changes as long as the
pinned role stays reachable.

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
- Direct `:8767` candidates remain valid for compatibility. Re-pair current
  clients before disabling that served port; fresh Dashboard, CLI, and TUI QRs
  use Dashboard `:9119` plus the same-origin Relay path.

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

**Tailscale route not reaching phone.** Check tailnet ACLs. Verify the
Dashboard at `http://<tailscale-ip>:9119/api/health` or its Tailscale Serve
HTTPS equivalent, then verify Relay ingress at
`<dashboard-origin>/api/plugins/hermes-relay/transport/health`. The API
fallback may also expose `:8642/health`, but it is not required for normal
Gateway chat. `hermes-relay-tailscale status` prints served ports; `9119` is
the recommended ingress, `8642` is optional, and `8767` is legacy only.

**Public URL reachable from the dashboard but not from the phone.**
Usually IPv6 or an egress firewall on the phone's network. Mobile
carriers and captive portals sometimes block outbound HTTPS traffic. Test
the public Dashboard `/api/health` on port 443 from the phone's browser first
— the same origin is in the QR
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
`hermes_relay_bootstrap/`: retire compatibility per surface once the
supported upstream baseline covers it.

### Forward-auth gateways (Authelia, Cloudflare Access) in front of the API server

A surprising failure mode: the relay's WSS endpoint pairs fine (the phone
presents the one-shot pairing code, which the relay knows about), but
every follow-up API call from the phone returns `401`/`403` because the
API server is behind a forward-auth gateway that expects a browser-issued
SSO cookie the phone doesn't have. Result: the relay creates a session
(so the paired device shows up in the Management tab), but the phone's
wizard times out waiting for `AuthState.Paired` and cleans up the config
locally — looks like "pair succeeded, then silently dropped."

**Diagnosing the shape:**

- Server-side: `journalctl --user -u hermes-relay -f` shows
  `Client authenticated from <LAN IP> (token=...)` — proof WSS pairing
  worked.
- Server-side: the same log shows no corresponding `/v1/models` or
  `/v1/runs` 200s from the phone — just silence or 4xx.
- Phone-side: ConnectionViewModel logs `probeCapabilities()` failures
  or HTTP 401 on initial model listing.

**Fix options, in order of preference:**

1. **Don't put the Hermes API behind forward-auth.** Keep the API
   server on `127.0.0.1:8642` and front it via Tailscale Serve
   (identity at the network edge, no HTTP-layer challenge). Forward-auth
   gateways are the wrong tool for machine-to-machine traffic.
2. **Use the canonical remote path:** Tailscale
   (`hermes-relay-tailscale enable`) publishes Dashboard `:9119` and its
   same-origin Relay ingress; optional API fallback remains on `:8642`.
3. **Bypass-auth rule for the phone's IP range.** Some forward-auth
   stacks (Traefik + Authelia `bypass` rules, Caddy
   `reverse_proxy` + access control) can whitelist the phone's
   Tailscale IP or a well-known source CIDR. Fragile — prefer #1 or #2.

**UI guardrail:** the dashboard's `PairDialog` warns when the operator
types an FQDN into the "Advanced · API-server override" field, because
that path has been the trap — the input pins the API host in the QR,
and when the typed host is forward-auth-gated the phone hits the
failure above. Leave the override blank and let `mode=auto` pick.

## See also

- `docs/decisions.md` §24 — multi-endpoint pairing wire format +
  priority semantics.
- `docs/decisions.md` §25 — Tailscale helper scope, upstream-retire
  criteria, alternatives rejected.
- `docs/security.md` — remote-connectivity section + overall trust
  model.
- `docs/security-native-proxy.md` — Hermes Secure Link trust boundary, route
  allowlist, pinning, and rotation contract.
- `docs/relay-server.md` — `hermes-relay-tailscale` CLI reference +
  environment variables (`TS_AUTO`, `TS_DECLINE`).
