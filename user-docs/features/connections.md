# Connections

A **connection** in Hermes-Relay is a saved link to a Hermes server. Add multiple connections and switch between them with one tap — for example, a home server and a work server, or a dev instance and a production one.

## What a connection contains

Each connection has a stable ID for one Hermes install and can hold several
independent capabilities:

- Dashboard/Gateway URL (`http(s)://host:9119`) and dashboard session for primary Chat, sessions, Manage, and standard voice
- Optional API server URL (`http(s)://host:8642`) and API key for automatic chat fallback or advanced headless compatibility
- Optional Relay URL (`ws(s)://host:8767`) and pairing record for Terminal, Bridge, and relay-only power tools
- Connection-scoped Dashboard cookies or native sign-in tokens, API credentials,
  and Relay session credentials; authentication never carries into another
  saved connection
- Its own sessions, memory, personalities, and skill list (fetched from that server)
- Last-active session ID and explicit profile pick, so switching back takes you where you left off

Connections do **not** share sessions, memory, or personalities. Each Hermes server is a separate world. What *is* shared across connections: the app theme and settings, bridge safety preferences (blocklist, destructive-verb confirmation, auto-disable), and the device's TOFU cert-pin record for each host.

## Switching connection

Tap the agent's name or avatar in the chat top bar to open the agent info sheet. Its **Connection** section lists your connections as a radio list — each row shows the connection's label plus its hostname and pairing status. Tap another to switch. The list appears only when you have two or more connections (with a single connection there's nothing to switch between), and it's disabled while a reply is streaming.

On switch, the app:

1. Cancels any in-flight chat stream.
2. Disconnects from the current relay.
3. Rebinds to the selected connection's endpoints.
4. Reconnects and reloads sessions, personalities, and skills from the new server.
5. Restores the last-active session and profile pick on that connection, if there was one.

The whole thing takes under a second on a healthy connection.

## Managing connections

Open **Settings → Connections** for the connection **list**. Each card shows the
connection's label, an **Active** badge on the one in use, a one-line status, and
a compact capability summary (Chat · Manage · Voice · API fallback · Relay) so you can scan the
health of every server at a glance. Tap a card to open its **detail** screen.

With two or more saved connections, **On app start** defaults to **Last used**.
You can instead pin one connection for cold launches without changing which
connection is active during the current session.

The detail screen is organized into tabs:

- **Overview** — the live capability timeline (what this connection can do) plus
  quick **Reconnect** / **Re-pair** actions.
- **Routes** — shows the primary Dashboard route even when no API or Relay is
  configured. LAN, Tailscale (`100.x` or `.ts.net`), and public Dashboard routes
  can be added and tested without API configuration. Optional API routes are
  only for direct chat fallback on the same networks.
- **Advanced** — optional direct API fallback credentials, an explicit direct
  Relay endpoint override, and the development-only insecure-connection toggle.
  Dashboard addresses stay under **Routes**. **Pair Relay** opens the same shared
  QR / enter-code / show-code flow used everywhere else.
- **Security** — transport posture, Dashboard authentication, credential storage,
  and **Relay sessions**, where you can review and revoke phones paired with that
  server or sign out of the Dashboard session.

The `⋮` menu in the detail's top bar holds the per-connection actions:

- **Rename** — the default is the server's hostname; change it to whatever makes sense ("Home", "Work", "Lab NAS").
- **Re-pair** — if the session token expires or the server's pairing state was wiped, re-scan the QR code. This reuses the same onboarding flow but keeps the connection's ID and label.
- **Revoke** — server-side logout. The token is invalidated on the server; the connection stays in the app but is marked unpaired.
- **Remove** — deletes the connection and its stored auth material. The TOFU cert pin for the server's host survives, so if you re-add the same server later, it's still trusted without a re-verify.

A connection that isn't active opens to a preview with a **Switch to this
connection** action — the deep tabs (Routes / Advanced / Security) manage the
*active* connection, so switch to it first to manage its routes and relay sessions.

Tap **Add connection** to create a new one. The normal flow asks for one Hermes
Dashboard/Gateway address, discovers its capabilities, and signs in through the
dashboard when required. The API server and Relay are optional and can be added
later without recreating the connection. Existing API-first setup QRs remain
importable for compatibility.

## Private and user-installed certificate authorities

Both Google Play and sideload builds accept HTTPS/WSS certificates that chain
to either Android's system roots or a CA deliberately installed in the phone's
**user credential store**. This supports private, self-hosted PKI without an
"ignore TLS errors" mode. The server certificate must still be within its
validity period, chain to the installed CA, and contain the exact Dashboard/API/
Relay hostname or IP address in its Subject Alternative Name. Relay certificate
pins, when present, remain an additional check.

This is an app-wide Android trust policy because connections can use arbitrary
operator-supplied hostnames and the same policy must cover native HTTP, redirects,
Dashboard sign-in, Gateway WebSockets, API streaming, voice, and Relay. A
per-domain XML policy cannot name hosts that are not known at build time. Android
does not apply public Certificate Transparency verification to connections when
the app allows user-installed trust anchors; install only CAs you control and
remove them from Android Settings when they are no longer needed.

To validate a local CA on a device or emulator:

1. Issue the reverse proxy's leaf certificate from the local CA. Include the
   exact test DNS name or IP address in the leaf certificate's SAN extension.
2. Copy only the CA certificate (never its private key) to the device. For an
   emulator, `adb push local-ca.crt /sdcard/Download/` is convenient.
3. In Android Settings, open **Security & privacy → More security settings →
   Encryption & credentials → Install a certificate → CA certificate**. Menu
   wording varies slightly by Android version. Select `local-ca.crt` and confirm
   it appears under **Trusted credentials → User**.
4. Configure the Dashboard, optional API server, and optional Relay reverse
   proxy to present the leaf certificate plus any required intermediate chain.
5. Add the HTTPS Dashboard address in Hermes-Relay. Verify Dashboard discovery
   and sign-in, start a Chat reply to exercise the Gateway WSS route, open
   Manage, and run a Standard Voice preview. If API fallback or Relay uses the
   same private CA, test those capabilities from the connection detail screen.
6. Negative-check hostname verification by trying the same server through an
   address absent from the certificate SAN. It must still fail with a certificate
   hostname error.

## Live status and diagnostics

Pairing and live reachability are shown separately. A connection can still be
**paired** while the relay socket or HTTP health check is down; in that case the
Relay row shows **Relay unreachable - tap to reconnect** rather than treating
the saved session as proof of a live server.

Tap the Dashboard/Gateway, API fallback, Relay, or Session rows in the active connection's **Overview**
tab to open detail sheets with a compact **Recent activity** tail. The tail shows
sanitized API, route, relay, session, and voice events such as health timeouts,
selected routes, reconnect attempts, and voice relay checks. Raw payloads, query
strings, and token-like values are hidden. The same consolidated log is available
from **Settings -> Diagnostics**, where you can clear the in-app buffer.

Diagnostics also reports the optional Relay plugin separately from basic relay
reachability. When the plugin exposes version metadata, the row shows the
installed version and whether a newer plugin release is available. Older plugins
without the update-check route remain usable and appear as **version unavailable**
rather than being incorrectly marked current.

Connection feedback sits where it matters and never covers the nav or shifts the
screen. There are really two connections, shown in two places:

- **Your agent** (the Dashboard/Gateway chat connection, or API fallback when used) shows in the header **subtitle under the agent
  name** — the model line swaps to **Reconnecting…** / **Connecting…** /
  **Disconnected** (amber or red) and fades back to the model once it recovers, the
  same place messaging apps show "connecting…". This is the one that tells you whether
  you can send.
- **The relay link** (bridge, terminal, and relay voice) shows only as a small amber
  **Reconnecting…** cue in the bottom status strip — it doesn't block chat, so it stays
  quiet. The strip's route label also shows which route you're on (LAN / Tailscale / …).

A quick reconnect right after you return to the app from the background is silent — it
won't flash a misleading "connection changed" for the same connection re-handshaking.

## Multi-Endpoint Pairing: One QR for Every Network

A pairing QR can carry multiple endpoint candidates for the same server: LAN, Tailscale, public reverse proxy, or an operator-defined VPN route. The app stores the connection once, then chooses the highest-priority reachable route at runtime.

The split is intentional:

- Standard Chat, sessions, Manage, and voice use the Dashboard/Gateway route.
- Optional API fallback uses its own route and bearer only when configured.
- Terminal, bridge, TUI, media, clipboard, profile-file operations, Android
  control, and Relay voice extensions use the Relay route and require a paired
  Relay session.

For optional Relay and API routes over Tailscale, run this on the host before pairing:

```bash
hermes-relay-tailscale enable
hermes pair --mode auto --prefer tailscale
```

The helper publishes Relay `:8767` and the optional API fallback. Add the remote
Dashboard/Gateway independently — `http://100.x.y.z:9119` works over the
encrypted tailnet when the Dashboard is reachable there, and needs no API key.
The route menu in Settings
lets you prefer a route for the current session without changing the stored
connection.

See [Remote access](/guide/remote-access) for setup commands and troubleshooting.

## How this differs from personalities and profiles

- **Personality** = a system prompt preset *on* one agent. Switching a personality just changes what the agent behaves like on the next message. Memory, sessions, tools, and model are unchanged.
- **Profile** = an upstream-Hermes agent directory on the server (`~/.hermes/profiles/<name>/`). Selecting a profile overlays its model + `SOUL.md` for chat turns. See [Profiles](./profiles.md).
- **Connection** = a whole different Hermes server. Switching a connection changes *everything* — the conversation history, the agent's memory, the personalities and profiles that are even available to pick from.

The Connection chip lives on the left of the Chat top bar. Profile and Personality selection both live in the **agent sheet** — tap the agent name in the middle of the top bar to open it. That order reflects the hierarchy: pick the server first, then pick the profile and personality *on* that server. Choosing **Server default** clears the explicit profile pick for the active connection and lets that server decide its default.

## Things that stay the same across connections

- **Bridge safety preferences.** The blocklist, destructive-verb confirmation, and auto-disable timer apply to whichever server is active. This is intentional — the safety model is about your phone and your risk tolerance, not which server you're talking to. If this ever becomes a problem, let us know.
- **Theme + app settings** — your preferences aren't tied to a server.
- **TOFU cert pins** — pinned per `host:port`, so two connections on the same server share the pin.

## Migrating from single-connection

If you upgrade from an earlier version, your existing paired device becomes your first connection automatically. No re-pair, no re-scan, no interruption. The connection gets your server's hostname as its label — rename it in Settings → Connections whenever you like.
