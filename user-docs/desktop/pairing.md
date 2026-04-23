# Pairing <ExperimentalBadge />

Pairing exchanges a one-time 6-character code for a long-lived session token, stored at `~/.hermes/remote-sessions.json` (mode 0600). This is the same file the [Android client](../guide/getting-started.md) uses — **pair once from either, both work**.

## Step 1 — mint a code on the server

SSH into your Hermes host (or use any terminal already on it):

```bash
hermes-pair --ttl 600
```

Output:
```
  Code         : F3W7EY
  Relay        : ws://127.0.0.1:8767
  Session TTL  : 600 seconds
```

The code is valid for **10 minutes** (the default) and **single-use**. After first successful pair it's consumed. Adjust TTL (how long the minted session token stays valid) with `--ttl 86400` (1 day), `--ttl 2592000` (30 days), etc. — `0` means never expire (not recommended outside LAN).

If you don't have shell access to the host, run this from a Hermes chat session (any client, including Android): `/hermes-relay-pair`.

## Step 2 — pair on the client

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
Need a pairing code — run `/hermes-relay-pair` (or `hermes-pair`) on the relay host.
(Paste works; cleaned code shown before submit.)

Pairing code (6 chars): _
```

Type or paste `F3W7EY`. On success:

```
  → using code: F3W7EY
Pairing with ws://<host>:8767...
✓ Paired. Token stored in ~/.hermes/remote-sessions.json
  Server: 0.6.0
  Relay:  ws://<host>:8767
  Route:  lan
```

Subsequent `hermes-relay` commands reuse the stored token.

## Paste safety — what if the code comes out garbled?

Some terminals (Windows Terminal, WezTerm, older iTerm2) wrap pasted content in **bracketed paste** escape markers (`\x1b[200~...\x1b[201~`). The CLI disables bracketed paste before the prompt and defensively strips ANSI + control chars, but a few terminals ignore the disable flag. The `→ using code: F3W7EY` confirmation line is your sanity check — if the echoed code doesn't match what you pasted, type it manually instead.

You can also skip the prompt entirely by passing the code positionally:

```bash
hermes-relay pair F3W7EY --remote ws://<host>:8767
```

## Multi-endpoint pairing (ADR 24)

If your Hermes server is reachable from multiple routes — LAN + Tailscale + public URL — the host can mint a **single QR payload** containing all of them. The CLI probes endpoints in priority order, picks the first reachable one, and records which route it picked so the banner shows "Connected via LAN (plain)" or "Connected via Tailscale (secure)" on reconnect.

On the server:

```bash
# All three routes
hermes-pair --mode auto --public-url https://hermes.example.com

# Or specific:
hermes-pair --mode lan
hermes-pair --mode tailscale
hermes-pair --mode public --public-url https://hermes.example.com
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
