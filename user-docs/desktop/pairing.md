# Pairing <ExperimentalBadge />

Pairing exchanges a one-time 6-character code for a long-lived session token, stored at `~/.hermes/remote-sessions.json` (mode 0600). This is the same file the [Android client](../guide/getting-started.md) uses — **pair once from either, both work**. The token survives reboots, picks up automatic reconnects with TOFU cert pinning on `wss://`, and is revocable from any other paired client (see [`hermes-relay devices`](./subcommands.md#hermes-relay-devices)).

::: warning The Hermes host needs the Relay plugin
The desktop CLI runs on Windows, macOS, and Linux, but the Hermes machine you
pair with must have the Relay plugin installed, enabled, and running. On the
Hermes host, complete this once before minting a pairing code:

```bash
hermes plugins install Codename-11/hermes-relay/plugin --enable
hermes relay doctor
hermes relay start --no-ssl
```

`--no-ssl` is for a trusted LAN or VPN path. For access outside that network,
use the [recommended Tailscale or TLS setup](../guide/remote-access.md).
:::

## Recommended — paste the complete invite

Create the invite from whichever Hermes surface is already open:

1. **Web Dashboard:** open **Relay → Pair new device → Copy invite**.
2. **Official Hermes Desktop:** open the **Relay** pane, click **Pair new
   device**, then **Copy**.
3. **Host terminal:** run `hermes pair` and copy the printed
   `hermes-relay://pair?...` invite URL.

On the computer you are pairing:

```bash
hermes-relay pair --pair-qr "hermes-relay://pair?payload=…" --grant-tools
```

Despite the flag name, `--pair-qr` accepts the pasted invite URL or the raw QR
payload; the desktop client does not need a camera. The full invite is preferred
because it carries the operator-reviewed certificate pin and ordered endpoint
candidates. The client probes secure routes first, stores the selected route,
and retains the remaining candidates for reconnect.

The invite and six-character code are single-use. Generate a fresh invite when
one expires or has already been consumed.

## Manual fallback — Relay URL + code

Use this only when the full invite cannot be copied.

### 1. Mint a code on the server

SSH into your Hermes host (or use any terminal already on it):

```bash
hermes pair --ttl 600
```

Output:
```
  Code         : F3W7EY
  Relay        : ws://127.0.0.1:8767
  Session TTL  : 600 seconds
```

The code is valid for **10 minutes** (the default) and **single-use**. After first successful pair it's consumed. Adjust TTL (how long the minted session token stays valid) with `--ttl 86400` (1 day), `--ttl 2592000` (30 days), etc. — `0` means never expire (not recommended outside LAN).

If you don't have shell access to the host, run this from a Hermes chat session (any client, including Android): `/hermes-relay-pair`.

### 2. Pair on the client

On your laptop/workstation:

```bash
hermes-relay pair --remote ws://<host>:8767
```

Replace `<host>` with:
- A LAN IP (`192.168.1.100`)
- A Tailscale tailnet hostname (`hermes.tail1234.ts.net`) — use `wss://` if tailscale serve is on
- A public URL (`wss://hermes.example.com`) — Cloudflare Tunnel, Caddy, nginx, etc.

The CLI prompts:

```
Relay: ws://<host>:8767
Need a pairing code — run `hermes pair` (or `/hermes-relay-pair`) on the relay host.
(Paste works; cleaned code shown before submit.)

Pairing code (6 chars): _
```

Type or paste `F3W7EY`. On success:

```
  → using code: F3W7EY
Pairing with ws://<host>:8767…
✓ Paired. Token stored in ~/.hermes/remote-sessions.json
  server: 1.2.0
  relay:  ws://<host>:8767
  route:  lan
  tip: add --grant-tools to also enable desktop tools (needed for `daemon`).
```

Subsequent `hermes-relay` commands reuse the stored token. When that token nears its expiry, the CLI warns you (on a TTY) before the next command would fail and prints the exact re-pair command — so an expired session never just silently breaks.

::: tip Port default
A bare `ws://<host>` with no port defaults to `:8767` (the relay's default), and the CLI tells you it did. A `wss://<host>` is left untouched — it's usually a reverse-proxy / Tailscale Serve front on `:443` — so include the port explicitly if your secure relay listens elsewhere.
:::

## First use

Pairing is complete when the CLI reports that its token was stored. Pick the
first result you want:

```bash
# Open the paired Hermes TUI now
hermes-relay

# Confirm the selected host and connection state
hermes-relay status

# Windows: open the management UI from the same installation
hermes-relay ui
```

To keep approved desktop tools available in the background, include
`--grant-tools` when pairing and then start the daemon:

```bash
hermes-relay daemon start
```

The grant is host-scoped and remains subject to the access policy you select
locally. Start with the TUI if you only want to confirm that pairing works.

## Paste safety — what if the code comes out garbled?

Some terminals (Windows Terminal, WezTerm, older iTerm2) wrap pasted content in **bracketed paste** escape markers (`\x1b[200~...\x1b[201~`). The CLI disables bracketed paste before the prompt and defensively strips ANSI + control chars, but a few terminals ignore the disable flag. The `→ using code: F3W7EY` confirmation line is your sanity check — if the echoed code doesn't match what you pasted, type it manually instead.

You can also skip the prompt entirely by passing the code positionally:

```bash
hermes-relay pair F3W7EY --remote ws://<host>:8767
```

## Multi-endpoint pairing (ADR 24)

If your Hermes server is reachable from multiple routes — optional Hermes Secure Link, Tailscale, a public TLS URL, and LAN — the host can mint a **single QR payload** containing all of them. Generated defaults put Secure Link first when it is enabled, then other secure candidates, with plain LAN retained as the last fallback. The CLI probes endpoints in strict priority order, printing each result and latency, picks the first reachable route, and records what it used so reconnect banners remain clear. Network changes trigger another probe, allowing a move between trusted LAN and remote routes without re-pairing.

Hermes Secure Link is one QR-pinned TLS origin for Relay, API, and authenticated
Dashboard namespaces. Their credentials remain separate, and Secure Link does
not make the host reachable; LAN, VPN/Tailscale, or public routing must already
reach port `9443`. A certificate, hostname, or port change requires re-pairing.

Hermes Reach is an experimental outbound-only broker route.
It lets the host and CLI connect outward when the host cannot accept an inbound
connection. The Reach broker forwards opaque records; QR-pinned Secure Link TLS
remains end-to-end inside that route. The broker sees routing/source metadata,
timing, and byte counts, not inner Hermes credentials or plaintext. It is
disabled by default and attempted only after Tailscale and supported direct TLS
routes. Broker failure never enables plain transport.

```
Probing 3 endpoint(s)…
  ✓ [1/3] lan ws://192.168.1.50:8767 145ms
  · [2/3] tailscale ws://hermes.tail1234.ts.net:8767 — timeout
  → picked lan endpoint ws://192.168.1.50:8767
```

On the server:

```bash
# All three routes
hermes pair --mode auto --public-url https://hermes.example.com

# Or specific:
hermes pair --mode lan
hermes pair --mode tailscale
hermes pair --mode public --public-url https://hermes.example.com
```

The output is a JSON blob (printed alongside the QR). Copy it verbatim and paste to the CLI:

```bash
hermes-relay pair --pair-qr '{"hermes":3,"host":"192.168.1.10","port":8642,"key":"ABC123","endpoints":[...]}'
```

Or via env:

```bash
HERMES_RELAY_PAIR_QR='<payload>' hermes-relay shell
```

The CLI races candidates within the same priority tier (`Promise.any` with 4s per-candidate timeout, 60s reachability cache) and picks the winner. Priority is strict — reachability only breaks ties *within* a tier, never promotes a lower-priority candidate.

> **HMAC signature.** The QR payload carries an optional HMAC-SHA256 signature (`sig` field). The current CLI parses it but doesn't verify — the server's HMAC secret isn't client-accessible yet. Matches the Android app's current behavior. Verification lands with v1.0.

## Re-pair (reset)

If your stored token expires, was revoked, or you want a fresh start:

```bash
# purge stored session for this URL
rm ~/.hermes/remote-sessions.json  # or delete just this URL's entry

# mint a fresh code on the server, then:
hermes-relay pair --remote ws://<host>:8767
```

## Inspect stored sessions

```bash
hermes-relay status
```

Shows per-URL: server version, pair age, token prefix, TTL expiry, grants (per-channel access), endpoint role, cert pin (wss only), tool consent state. Pass `--json` for a machine-readable redacted dump, or `--json --reveal-tokens` to include full tokens (for scripted re-auth — never paste into a shared terminal).

## Paired devices on the server (revoke remotely)

See what the server thinks is paired — and revoke / extend:

```bash
hermes-relay devices              # list all paired devices on this server
hermes-relay devices revoke abc12345   # revoke by token prefix
hermes-relay devices extend abc12345 --ttl 604800   # extend to 7 days
```

Talks to the relay's `GET /sessions` HTTP endpoint using your stored bearer. The current device is marked with `●`.

## Related

- [Installation](./installation.md) — get the binary on your machine first.
- [Subcommands](./subcommands.md) — full reference for `pair`, `status`, `devices`, `shell`, `chat`, `tools`.
- [Troubleshooting](./troubleshooting.md) — `auth timed out`, `relay rejected`, `disconnected before auth`, etc.
