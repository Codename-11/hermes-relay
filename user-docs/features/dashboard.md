# Dashboard Plugin

A hermes-agent dashboard plugin that surfaces relay-specific state in the gateway's web UI. Paired devices, bridge command history, push delivery (future), and active inbound-media tokens — all in one "Relay" tab, no SSH required.

## What It Is

If your Hermes server runs the Dashboard Plugin System (upstream `axiom` branch), Hermes-Relay ships a plugin that auto-registers through the same `~/.hermes/plugins/hermes-relay` symlink created by `install.sh`. Restart the gateway and a new **Relay** tab appears alongside Chat, Skills, Memory, and the other dashboard tabs. Inside that tab you get four sub-tabs grouping the four things only the relay knows about.

The plugin is a thin observer — it never modifies state, never writes to your config, and can't do anything a phone-side operator can't already do. Its job is to save you from SSHing into the server to check "is my phone still paired?" or "what did the agent just do?".

## Requirements

**On your server:**

- hermes-agent with the Dashboard Plugin System (upstream commit `01214a7f` on `axiom`, or any later `main` once [PR #8556](https://github.com/NousResearch/hermes-agent/pull/8556) and its dashboard followups merge). `hermes dashboard start` must already work for you.
- The canonical Hermes-Relay install — if you ran the one-liner on the [Quick Start](/guide/getting-started), you're done. The installer symlinks `~/.hermes/plugins/hermes-relay` → the plugin subtree and the dashboard scanner picks up `plugin/dashboard/manifest.json` automatically.
- A gateway restart after install: `systemctl --user restart hermes-gateway`.

**On your phone:**

Nothing. The dashboard plugin renders in your browser against the Hermes server — the phone is the subject of observation, not a participant.

## Accessing the Dashboard

Open the hermes-agent dashboard in your browser (default: `http://localhost:<dashboard_port>`). The **Relay** tab sits between Skills and whatever you have next in your nav order — click it and you land on the four-tab shell.

The plugin's header shows the relay version, overall health (green / red dot), and an **Auto-refresh** toggle that persists to `localStorage`. Turn auto-refresh off if you're reading a specific activity row and don't want it to scroll out from under you.

## The Four Tabs

### Relay Management

The landing tab. Shows:

- **Relay version + uptime + health** — served by the relay's `/relay/info` endpoint. Green dot = reachable, red = `relay unreachable at 127.0.0.1:8767` (the gateway can't see your relay process; check `systemctl --user status hermes-relay`).
- **Paired devices list** — one row per active session. Columns: device name (from the phone's `PairedDeviceInfo`), token prefix (first 8 chars — full tokens are never sent), created-at, last-seen, expires-at, per-channel grants (bridge / terminal / chat), transport hint (`wss` / `ws`).
- **Revoke button** per row — live. Click to pop a native browser confirm; on OK the button calls `DELETE /api/plugins/hermes-relay/sessions/{prefix}` which the plugin proxy forwards to the relay, and the list auto-reloads on success. Same effect as revoking from the Android app's Settings → Paired Devices or running `hermes-pair --revoke <prefix>` on the server.
- **Pair new device** — button in the card header opens the [PairDialog](#pairing-a-new-device) described below.

<!-- TODO: replace with real screenshot — dashboard Relay Management tab with a paired device row -->

#### Pairing a new device

The **Pair new device** button on the Relay Management tab is an alternative to `/hermes-relay-pair` and the `hermes-pair` CLI — same underlying pairing flow, just driven from a browser on your laptop instead of a chat or shell. Useful when you're already in the dashboard reviewing session state and want to onboard a phone without bouncing out to a terminal.

**Click the button to open a PairDialog with:**

- **The QR code** — freshly minted, signed, ready to scan from the Android app's onboarding or Settings → Connections → Add connection flow.
- **The 6-character pairing code** — shown plain-text above the QR. Type this into the app's manual-entry path if your phone can't scan, or read it aloud if someone else is holding the phone.
- **A 10-minute expiry countdown** — the code is one-shot and single-use. When it expires or after the phone claims it, close the dialog and click Pair new device again for a fresh code.
- **A "reveal/hide" toggle on the QR** — defaults to hidden so bystanders in a shared screen can't silently scan it behind your back. Reveal explicitly when you're ready to scan.

**The "Override host / port / TLS" section** is what most non-default deploys need. By default the relay fills the QR with its own LAN-visible address (`http://<LAN-IP>:8642` for the API server + `ws://<LAN-IP>:8767` for the relay), which is correct for a straight home-LAN install. You need to override when:

- **Reverse proxy in front of the relay** — e.g. a Traefik-fronted `wss://relay.example.com:443`, where the dashboard itself sees `127.0.0.1:8767` but the phone needs to reach the public hostname. Set Host to `relay.example.com`, Port to `443`, TLS to `on`, and the minted QR carries those coordinates while the relay still registers the pairing code locally.
- **Tailscale / Wireguard VPN** — phone and server are both on the tailnet but the dashboard is rendering on a different network interface. Override Host to the tailnet IP / MagicDNS name so the phone connects over the tunnel.
- **Multi-homed server** — the relay auto-detection picks one IP but you want the phone on a different interface (e.g. a separate VLAN for IoT devices). Override to pin the address.

**Override persistence.** The dashboard stores the last-used host/port/TLS values in `localStorage` per-browser, so returning to the Pair dialog in the same browser session pre-fills your overrides. Different browsers (or cleared storage) start with the relay's auto-detected defaults. The overrides are never persisted server-side — they only shape the next QR's payload.

**What the minted QR contains.** Top-level `host`/`port`/`tls`/`key` describe the **Hermes API server** the phone hits for chat (defaults `:8642`, override fields labelled "API server"). The nested `relay` block carries the relay's WSS URL and the pairing code — always auto-derived from the relay's own bind config, never operator-editable, since the relay knows where phones need to connect. See `docs/spec.md` §3.3.1 for the full wire-format spec and `plugin/tests/test_pairing_mint_schema.py` for the regression guard that keeps the payload in sync with the Android parser.

**If the minted QR "doesn't do anything" when scanned**, the most common cause is that the host-side API port in the override section points at the wrong service — e.g. you accidentally entered `8767` (the relay's port) in the API Host/Port fields, so the phone tries to reach the API at the relay's address. The relay validates that the URL parses but can't verify the port is actually an API gateway, so this mistake surfaces as a silent pair failure. Double-check that Host points at something serving `/v1/runs` / `/v1/chat/completions`, not your relay.

<!-- TODO: replace with real screenshot — PairDialog with QR and override fields expanded -->

### Bridge Activity

Real-time feed of what the agent just did to the phone. Backed by an in-memory ring buffer on the relay (`BridgeHandler.recent_commands`, max 100 entries) that records every bridge command round-trip as it happens — no database, no replay across restarts.

Each row shows:

- **`sent_at`** — relative time, hover for absolute UTC.
- **`method` + `path`** — e.g. `POST /tap`, `POST /send_sms`.
- **`params`** — redacted for any key in `{password, token, secret, otp, bearer}`; everything else renders inline.
- **`decision`** — `executed` (ran normally), `blocked` (phone-side safety-rail denied it), `confirmed` (destructive-verb confirmation accepted), `timeout` (no response in 30s), `error` (exception on either end), or `pending` (in-flight right now).
- **`response_status`** + `result_summary` + `error` — HTTP status from the phone + the first line of the result + any error string.

A filter-chip row above the table lets you narrow to `All | Executed | Blocked | Confirmed | Timeout | Error` at a glance. Polls every 5 seconds (pausable via the header Auto-refresh toggle).

<!-- TODO: replace with real screenshot — Bridge Activity tab mid-session, showing executed + one blocked row -->

### Push Console

**Stub for now.** Renders an "FCM integration not configured" banner with a link to the deferred-items doc. The plugin backend returns `{configured: false, reason: "FCM not yet wired; …"}` without hitting the network.

When FCM lands, this tab will show outbound push delivery: target device, payload, delivery status, timestamps. The nav slot is reserved deliberately so the four-tab layout doesn't reshuffle when the feature ships — only `PushConsole.jsx` + the plugin's `/push` route change.

<!-- TODO: replace with real screenshot — Push Console stub banner -->

### Media Inspector

Lists active `MediaRegistry` tokens — the handles the relay mints when a host-local tool (e.g. `android_screenshot`) registers a file for the paired phone to download. Each row shows:

- **Token** — truncated display, hover to copy full.
- **`file_name`** — basename only. Absolute paths are never sent from the server; the inspector can't be used to enumerate your filesystem.
- **`content_type`** + **`size`**.
- **`created_at`** / **`last_accessed`**.
- **TTL countdown** — live `setInterval(1000)` ticking down to `expires_at`. Turns red when < 60s remaining.

By default, expired entries are hidden. Click the **Show expired** toggle at the top of the tab to include evicted rows (useful for debugging "did that screenshot actually register?" retroactively).

Polls every 15 seconds.

<!-- TODO: replace with real screenshot — Media Inspector with a registered screenshot row, TTL counting down -->

## How It's Wired (Brief)

The plugin has three layers:

1. **Frontend** — a pre-built React IIFE at `plugin/dashboard/dist/index.js` (~16 KB minified), loaded verbatim by the dashboard shell. Source lives in `plugin/dashboard/src/` and is bundled with esbuild. Uses the dashboard's `window.__HERMES_PLUGIN_SDK__` global for React + shadcn primitives — no bundled React, no external HTTP library.
2. **Backend proxy** — a FastAPI router at `plugin/dashboard/plugin_api.py` mounted at `/api/plugins/hermes-relay/*` inside the gateway process. Forwards five routes (`/overview`, `/sessions`, `/bridge-activity`, `/media`, `/push`) to the relay at `http://127.0.0.1:{HERMES_RELAY_PORT}` via `httpx.AsyncClient` with a 5-second timeout. Translates relay connect-errors / timeouts / 5xx into `HTTP 502` with a human-readable detail so the UI can show "relay unreachable".
3. **Relay** — three new loopback-gated HTTP routes (`/bridge/activity`, `/media/inspect`, `/relay/info`) plus a loopback-exempt branch on the existing `/sessions`. Both the plugin backend and the relay are localhost-bound, so no bearer is minted and no new credentials are introduced.

For the full wire-shape of each route (query params, response schemas, redaction rules, loopback guards), see the [Relay Server reference](https://github.com/Codename-11/hermes-relay/blob/main/docs/relay-server.md) and [ADR 19](https://github.com/Codename-11/hermes-relay/blob/main/docs/decisions.md) in the repo.

## Troubleshooting

**"Relay unreachable at 127.0.0.1:8767" on every tab.** The gateway can't see your relay process. Check `systemctl --user status hermes-relay` on the server; if the unit is inactive, `systemctl --user restart hermes-relay`. If you run the relay manually, confirm it's bound to `127.0.0.1:8767` and hasn't moved to a different port (override via `HERMES_RELAY_PORT` — the plugin reads this at import time).

**No "Relay" tab appears after gateway restart.** Confirm the symlink resolves: `ls -lL ~/.hermes/plugins/hermes-relay/dashboard/manifest.json` should show the file. If it doesn't, the installer symlink was broken — re-run `hermes-relay-update` or the `install.sh` one-liner. Check the gateway log (`journalctl --user -u hermes-gateway -f`) for plugin-load errors during startup.

**Bridge Activity tab is empty but the phone is issuing commands.** The ring buffer is in-memory and wipes on relay restart. If you just restarted the relay, you need the phone to issue at least one command before the tab has anything to show. If commands are going through but not appearing, confirm they're reaching the relay (`journalctl --user -u hermes-relay -f` should show the command round-trips).

**Media Inspector shows tokens but files won't download.** That's a separate path — the inspector lists registered tokens but the actual download goes through `/media/{token}` (bearer-gated, via the phone). If the phone can't fetch a token, check the bearer's `media` grant and `RELAY_MEDIA_TTL_SECONDS` hasn't elapsed since registration.

**Revoke button fails silently.** Revoke is live as of the dashboard plugin release — `DELETE /api/plugins/hermes-relay/sessions/{prefix}` is proxied to the relay. If the click confirm fires but the list doesn't update, open the browser devtools network tab and re-click: a 502 means the relay itself is unreachable (see the "Relay unreachable" item above), a 404 means the token prefix is already gone (the list auto-reloaded between the button render and your click), and a 403 means the proxy is seeing a non-loopback caller (hermes-agent's dashboard shouldn't ever hit this — check `journalctl --user -u hermes-gateway -f` for the origin).

**Pair dialog mints a QR that won't pair.** Verify the host/port in the override panel: the top-level Host/Port are for the **Hermes API server** (default `:8642`), not the relay (`:8767`). If you entered the relay port in the override, the phone tries to reach the API at the relay's address and bails silently. Reset the overrides (clear the fields and click Pair new device again) and confirm the auto-detected defaults point at your actual API server.

## Security Notes

All three new relay routes (`/bridge/activity`, `/media/inspect`, `/relay/info`) are gated to `127.0.0.1` / `::1` — any remote request returns HTTP 403. The plugin backend itself runs inside the gateway process, which binds to localhost by default; neither layer introduces a new remote attack surface.

The `MediaRegistry.list_all()` snapshot strips absolute paths server-side before the relay serializes its response, so even if you deliberately exposed these routes externally (by fronting the relay with a reverse proxy, for example), the inspector couldn't be used to enumerate your filesystem.

Bridge command `params` are redacted for any key matching `{password, token, secret, otp, bearer}` before they hit the ring buffer. This is best-effort — if you route a secret through a field named something else, it'll land in the activity feed verbatim. Audit your agent tools for non-obvious secret-carrying params if that matters for your threat model.
