# Is my connection secure?

Short answer: **probably yes** — and Hermes-Relay now tells you at a glance, without
overstating or understating it.

This page explains what "encrypted" actually means for your connection, why a Tailscale
link is genuinely secure even when it looks like plain `http://`, and how to read the
in-app security indicator. If you just want the quick version, jump to
[How to read the indicator](#how-to-read-the-in-app-indicator).

## Plain vs. encrypted: `ws://` vs `wss://`, `http://` vs `https://`

Every connection uses a URL scheme, and the scheme tells you whether the link is
encrypted by **TLS** (the same technology the lock icon in your browser refers to):

| Scheme | What it is | Privacy |
|---|---|---|
| `http://` / `ws://` | **Plaintext.** No TLS. | Anyone on the network path can read the traffic. |
| `https://` / `wss://` | **TLS-encrypted.** | The traffic is encrypted to the server's certificate. |

On its own, a plain `http://` or `ws://` link is readable by anyone between your phone
and the server — your home router, the coffee-shop Wi-Fi, an upstream ISP. That's why
plaintext is only safe on a network you fully trust.

**But scheme isn't the whole story.** A plain `http://` link can still be fully encrypted
if it rides inside an encrypted overlay network like Tailscale. That's the part people
get wrong — including, until now, our own docs.

## What Tailscale actually is

[Tailscale](https://tailscale.com/) is a **WireGuard-based VPN**. When your phone and your
Hermes host are both on your tailnet, every byte between them is **encrypted and
authenticated end-to-end by WireGuard** — before it ever touches the URL scheme. This is
genuinely secure transport: strong modern encryption plus device identity (only enrolled
devices on your tailnet can talk to each other).

So a connection to `http://100.x.y.z:8642` **over your tailnet is encrypted** — by
WireGuard, not by TLS. It is *not* plaintext-on-the-wire even though the scheme says
`http`. **Tailscale plaintext is secure; it's just not TLS.** Hermes-Relay treats it as a
green/secure route, and never labels a Tailscale route "insecure."

Tailscale can *also*, separately, terminate TLS for you. Running
`tailscale serve --https=<port>` puts a real TLS certificate in front of a service, so the
same connection becomes `https://<host>.ts.net:<port>` — now you have **both** WireGuard
encryption *and* TLS. You don't need the TLS layer for the link to be secure over a
tailnet, but it's there if you want a `wss://`/`https://` route (some tools and proxies
expect one).

::: tip The one thing to remember
WireGuard encryption ≠ TLS, but **both are secure transports.** A Tailscale route is
encrypted whether or not TLS is also in play. The app's 🛡️ shield means "encrypted by your
private network" — it is a green/secure state, not a warning.
:::

## TLS + certificate pinning (TOFU)

Ordinary TLS routes (`wss://`/`https://`) use **trust-on-first-use (TOFU)
pinning**: on the first connection Hermes-Relay records the certificate's
SHA-256 SPKI, and every later connection to that host must present the same
certificate. Hermes Secure Link is stricter: its SPKI pin comes from the
operator-reviewed pairing QR and is required before the first request. The pin
verifies continuity with that paired endpoint; it does not independently prove
the identity of the physical host.

What this buys you:

- After the first connect, a man-in-the-middle can't swap in a different certificate to
  intercept your traffic — the pin won't match.
- The pin is per `host:port`, stored on-device.
- Re-pairing the device (scanning a fresh QR) intentionally resets the pin for that host,
  because re-pairing is explicit consent to potentially new certificate material.

Two honest caveats:

- **Pinning only applies to TLS routes.** A Tailscale-over-`http` route has no TLS
  certificate to pin — its security comes from WireGuard instead, which provides its own
  device identity.
- **TOFU can't protect the very first connect.** By definition it trusts whatever
  certificate is present on the initial handshake, so do your first connection over a path
  you trust (LAN, Tailscale, or VPN). It protects every connection after that. This caveat
  does not apply to QR-pinned Hermes Secure Link.

## Why one connection has several security states

A single paired connection isn't one pipe — it fans out to several **surfaces**, and each
one can independently be TLS, overlay-encrypted, or plain:

| Surface | What it carries | Typical port |
|---|---|---|
| **Chat (gateway)** | Live chat, thinking/reasoning | dashboard `:9119` |
| **API / sessions** | Chat fallback, session history | API `:8642` |
| **Dashboard (Manage + voice)** | Settings, model config, vanilla voice | dashboard `:9119` |
| **Relay tools** | Terminal, bridge, device control | relay `:8767` |

Because each surface has its own URL, **a connection can be partly encrypted and partly
plain at the same time** — for example chat and Manage on `https://`, but relay tools on
plain `ws://`. There's no single true/false answer to "is it secure," so a single binary
badge would lie.

Hermes-Relay handles this with a **rollup at a glance, the full truth on tap**:

- The **glance badge** reflects the worst case across the surfaces actually in use.
- **Tapping it** opens a per-surface breakdown so you can see exactly which routes are
  encrypted and how.

## How to read the in-app indicator

The security glyph appears next to the route on the chat status chip and the connection
card. There are four outcomes:

| Indicator | Meaning | Tone |
|---|---|---|
| 🔒 `Encrypted · TLS` | Every in-use surface is `wss`/`https`, pinned on first connect. | **Secure (green)** |
| 🛡️ `Encrypted · Tailscale` | Plain scheme, but the route is Tailscale or WireGuard — encrypted by the overlay. | **Secure (green)** |
| 🛡️ `Mixed routes` | Some surfaces are encrypted, some are plain (a secure fallback exists). | Amber — review the breakdown |
| ⚠️ `Not encrypted` | Plain `ws`/`http` with no overlay. | Warning — only safe on a network you fully trust |

The key idea: **both 🔒 and 🛡️ are green/secure.** Only true plaintext with no overlay is a
warning. Tap the indicator for the per-surface detail, where each route is spelled out in
one line:

- *TLS — encrypted to this server's certificate (pinned on first connect).*
- *Tailscale — encrypted by your tailnet (WireGuard), not TLS.*
- *Not encrypted — only safe on a network you fully trust.*

If you see ⚠️ **Not encrypted**, you're on a plain `ws://`/`http://` route with nothing
wrapping it. That's fine on a home LAN or a trusted VPN, but you should add an encrypted
route before using it over public Wi-Fi or the open internet.

## How to get a TLS (or otherwise encrypted) route

You have a few ways to make a connection secure. Pick whichever fits your setup:

### 1. Tailscale (recommended)

Putting both devices on a tailnet gives you WireGuard encryption immediately — you're
secure (🛡️) with no certificates to manage. The recommended helper also creates
the TLS-fronted Dashboard origin:

```bash
tailscale serve --bg --https=10443 http://127.0.0.1:9119
```

The `hermes-relay-tailscale` helper configures a dedicated HTTPS listener
(`:10443` by default) for local Dashboard `:9119`, including the plugin's
same-origin Relay transport. This avoids conflicts when Traefik, Caddy, nginx,
or another service already owns `:443`.
Direct API on `:8642` remains optional:

```bash
hermes-relay-tailscale enable
```

The phone uses the advertised `https://host.ts.net:10443` listener, not local target
port `:9119`. A manually exposed `http://100.x.y.z:9119` route is still
WireGuard-encrypted and secure over the tailnet, but it has no application TLS.
Old `:443` and `:9119` listeners remain migration/explicit routes; direct Relay
`:8767` remains only for older paired clients until re-pairing.

### 2. A public reverse proxy

A proxy like **Caddy**, **nginx**, or **Cloudflare** can terminate TLS in front of your
services and expose `https://`/`wss://` routes to the open internet. The proxy holds the
certificate; your phone pins it on first connect. This is the path to use when you're
exposing Hermes beyond a private network — never expose plain `ws://`/`http://` ports
directly.

### 3. Optional Hermes Secure Link

The Relay plugin can expose Hermes Secure Link, an opt-in pinned-TLS listener on port `9443`. It has fixed
`/relay`, `/api`, and `/dashboard` namespaces and is not an arbitrary reverse proxy.
Relay sessions, API keys, and Dashboard login remain independent; Dashboard forwarding
requires its upstream authentication gate. Its SPKI pin comes from the pairing QR,
so the client has trust material before its first request. That pin proves
continuity with the endpoint reviewed during pairing, not the identity of the
underlying physical host. Changing the certificate or advertised authority
requires explicit re-pairing.

Secure Link protects transport but does not create reachability: LAN routing,
Tailscale or another VPN, or a public route must still reach the listener. One
origin carries the fixed namespaces, while Relay pairing/session auth, API bearer
auth, and Dashboard cookie/native bearer auth remain separate. Enable it with
`--secure-link` or `RELAY_SECURE_LINK_ENABLED=1`, then re-pair to import its
authority and pin. Tailscale Serve remains the primary recommendation today;
direct and Tailscale routes remain available as independent fallbacks.

### 4. Hermes Reach (experimental outbound rendezvous)

Hermes Reach solves reachability, not transport trust. A host and client each
open an outbound WSS connection to an optional hosted or self-hosted broker.
The broker matches the two streams, but the Hermes session remains inside
QR-pinned Secure Link TLS end-to-end.

That distinction matters: outer WSS protects each hop to the broker, while the
inner pinned TLS session keeps Hermes paths, headers, credentials, and plaintext
opaque to the broker. The broker can still observe routing and source metadata,
timing, and byte volume; it can deny, delay, drop, or misroute traffic. Reach is
therefore not anonymous or “zero knowledge.”

The initial QR carries a raw one-use bootstrap token whose SHA-256 hash is
atomically consumed by the broker. Durable reconnect credentials are scoped to
an opaque session identifier and returned only after inner Relay authentication.
Reach remains disabled by default and ordered after Tailscale, public TLS, and
Direct Secure Link. A broker failure never authorizes plaintext downgrade.

## See also

- [Security](./security.md) — full security model: key storage, auth flow, the bridge safety gate.
- [Remote access](../guide/remote-access.md) — step-by-step Tailscale and reverse-proxy setup.
- [Privacy](./privacy.md) — what data the app stores and where.
