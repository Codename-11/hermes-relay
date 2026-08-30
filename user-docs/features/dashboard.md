# Dashboard and official Desktop plugins

Hermes-Relay surfaces its Relay-specific state in the web Dashboard and, when
explicitly enabled, in official Hermes Desktop. Both use the same existing
profile-scoped backend; neither creates a second Relay service or state store.

## What It Is

If your Hermes server runs the Dashboard Plugin System, the unified
Hermes-Relay plugin contributes a **Hermes-Relay** page alongside Chat, Skills, Memory,
and the other Dashboard pages. The same package also contributes the official
Hermes Desktop pane and the `hermes relay` / `hermes pair` CLI commands.

The plugin is the browser operator surface for Hermes-Relay. It reads health, sessions,
activity, media, and remote-access state, and performs explicit scoped actions:
minting invites, revoking sessions, changing Relay-owned settings, and managing
remote-access helpers. It never turns a viewed card into an implicit mutation;
pairing, revocation, and configuration remain labeled user actions.

## Requirements

**On your server:**

- hermes-agent with the Dashboard Plugin System. `hermes dashboard start` must already work for you; the Relay tab uses the dashboard plugin mount and does not depend on the legacy session API branch.
- The canonical plugin install:
  `hermes plugins install Codename-11/hermes-relay/plugin --enable`. The
  Dashboard scanner discovers `dashboard/manifest.json` from that unified
  package.
- A gateway restart after install: `systemctl --user restart hermes-gateway`.

**On your phone:**

Nothing. The dashboard plugin renders in your browser against the Hermes server — the phone is the subject of observation, not a participant.

**For official Hermes Desktop:** use a build with the runtime Plugin SDK and
unified-package discovery. The regular Hermes-Relay plugin install already
places `desktop/plugin.js` beside the Dashboard half. Open **Settings →
Plugins**, enable **Hermes Relay**, then use the labeled **Relay** sidebar or
status-bar item, or **Hermes Relay: Open** in the command palette.

Enabling or loading the Desktop plugin does not open its pane. App startup,
reconnect, profile changes, navigation restoration, updates, and background
events also leave it closed. After an explicit open, close and move/dock work
through the native Desktop pane controls. Desktop currently offers no supported
agent command for focusing contributed panes and no programmatic move-coordinate
API; Hermes-Relay does not use private hooks to imitate those features.

## Accessing the Dashboard

Open the hermes-agent dashboard in your browser (default: `http://localhost:<dashboard_port>`). The **Hermes-Relay** tab sits between Skills and whatever you have next in your nav order — click it and you land on the six-tab shell.

Use the real dashboard/Manage surface for this URL: start it with `hermes dashboard` and point Android's Dashboard URL at that service (default `:9119`). `hermes serve` is a headless backend/API command; it is useful for programmatic clients, but it does not serve the Manage UI that Android uses for Skills, Models, Keys, Profiles, voice auth, or dashboard plugins. `hermes relay doctor` warns when the Dashboard URL looks like an API-server/headless URL instead of the dashboard surface.

The Dashboard header names the page **Hermes-Relay** once. The plugin's Overview shows service health, version, uptime, paired-device count, remote-route summary, and recent Bridge activity. The **Live** switch persists to `localStorage`; turn it off when you want the current diagnostic view to stay still.

## Android Manage Surface

The Android app uses the Hermes Dashboard/Gateway as its standard connection:
primary chat, sessions, Manage, authentication, and standard voice all share
this upstream surface. New connections store the dashboard address directly;
legacy API-first records may still derive the conventional `:9119` address for
compatibility. The **Manage** tab reads Skills, Cron, MCP, MCP catalog, Profiles,
Models, Keys, and Config from dashboard endpoints when the server supports them.

What you can do from the phone, per section:

- **Skills** — toggle installed skills, plus full **skills-hub** access: browse the configured hub sources (featured skills shown before you search), search across them, read a skill's `SKILL.md` *before* installing, install/uninstall (these run asynchronously on the server), and update everything hub-installed.
- **Cron** — pause/resume/run/delete jobs and view recent runs.
- **MCP** — enable/disable, test, and remove servers; install catalog entries that don't require inline credentials.
- **Profiles** — create profiles (clone-from-default), activate, edit the role description, set a per-profile model, **edit SOUL.md** in a full-file editor, and delete.
- **Models** — change the main model from the full provider/model catalog, including the server's expensive-model confirmation step. Providers without keys appear greyed with a pointer to Keys. Use **Refresh** in the picker when you've just added provider credentials or changed a dynamic/custom-provider catalog and want the server to re-check available models immediately.
- **Keys** — view the curated env/key inventory (values redacted), set keys (write-only, masked), reveal one (server rate-limited and audit-logged), or clear them.
- **Config** — read the config schema.

A successful dashboard sign-in here also unlocks **standard voice** for the connection — speech uses the same dashboard session (see [Voice Mode](./voice)).

Dashboard sign-in is the upstream-preferred remote auth path. When Hermes advertises `native_pkce`, Android opens the selected provider in the system browser and uses the same brokered `/auth/native/*` flow as Hermes Desktop. Older gateways use the dashboard's cookie-based `/auth/login?provider=...` compatibility flow. Successful sign-in verifies `/api/auth/me` and can mint the short-lived `/api/ws` ticket used by Gateway chat.

The address saved in Android may be a LAN, Tailscale, or public Dashboard route. Redirect providers still need an externally reachable Dashboard callback registered as `<public-dashboard-origin>/auth/callback`. Hermes normally reconstructs that origin from trusted reverse-proxy headers. If the proxy does not forward them reliably, configure upstream `dashboard.public_url` (or `HERMES_DASHBOARD_PUBLIC_URL`) to the complete HTTPS Dashboard origin, including any path prefix. Native PKCE uses a different advertised origin only for its browser transaction; on the older cookie compatibility flow Android verifies the installation and asks before saving a different authenticated Dashboard origin. A second public sign-in field is not required during normal app setup.

For support, open **Settings → Diagnostics** and filter to **Auth**. Native sign-in records only a sanitized lifecycle—attempt number, provider class, route role, configured-versus-alternate authorization origin, browser launch, validated callback, Continue/cancel, elapsed time, completion, or typed failure stage. The reviewable support export includes these recent entries alongside persistent reliability reports and never uploads automatically.

This is separate from Relay pairing and from `API_SERVER_KEY`. A dashboard
session does not become an API bearer token: Android uses it for primary Gateway
chat and asks for an API bearer only when the optional API fallback is configured.
Relay-only capabilities — Terminal, Bridge, Relay sessions, Media inspector, and
profile memory file editing — stay under **Settings → Power tools** and show
**Requires pairing** until the phone has a paired Relay session. Profile
**SOUL.md editing is available without Relay** (Manage → Profiles → Edit SOUL,
via the dashboard); memory file editing remains in the paired profile inspector.

Server-side dashboard auth is owned by upstream Hermes. For current provider registration, Nous OAuth, username/password, and remote dashboard guidance, use the Hermes [Web Dashboard docs](https://hermes-agent.nousresearch.com/docs/user-guide/features/web-dashboard).

## Connect and pair clients

The Hermes-Relay Overview and Devices tabs expose two different setup actions. They intentionally do not
share credentials:

### Connect mobile app — standard upstream connection

Use this first for Android:

1. Open **Hermes-Relay → Devices** and click **Show setup QR** under **Connect mobile app**.
2. In Android **Connect**, choose **Scan Hermes setup QR**.
3. Scan the tokenless QR and sign in if prompted.

The QR contains only the canonical Dashboard address. Android verifies that
origin, then uses the upstream Dashboard/Gateway for Chat, sessions, Manage,
sign-in, and standard voice. It contains no token, cookie, API key, password, or
Relay pairing code.

### Pair new device — Relay session

Use this after the standard Android connection, or whenever pairing Android,
the Desktop CLI, or another Relay client:

1. Open **Hermes-Relay → Devices** and click **Pair new device**.
2. Keep **Auto** mode unless you specifically want LAN-only, Tailscale-only, or
   a pinned public route.
3. Android scans the QR from **Settings → Connections → Pair Hermes Relay**.
4. Desktop CLI users click **Copy invite**, then run:

```bash
hermes-relay pair --pair-qr "hermes-relay://pair?payload=…" --grant-tools
```

Official Hermes Desktop exposes the same backend in its **Hermes-Relay** pane. Its
**Pair new device** action shows the one-time code and copyable invite for a CLI
or UI client; it does not need to render a camera QR.

The invite is one-time and credential-bearing. Keep it private and mint a new
one when it expires or has already been consumed.

Auto prefers Tailscale, then public HTTPS, then LAN. Tailscale is first because
it keeps the server inside a private, authenticated tailnet with ACLs. A raw
tailnet `http://` or `ws://` address has no application TLS, but Tailscale's
WireGuard transport still encrypts traffic between tailnet devices. Public
routes must use HTTPS/WSS and remain the automatic fallback when Tailscale is
unavailable. Recommended Tailscale setup advertises the helper-reported
dedicated HTTPS listener (`:10443` by default), which proxies the local
Dashboard on `:9119`; the endpoint receipt shows that actual listener. This
avoids conflicts with Traefik, Caddy, or nginx on `:443`.

## The Six Tabs

### Overview

The landing tab is status-first. Its panels load independently, so an optional
remote-access or activity failure does not replace healthy service and device
state with a page-wide error. It shows:

- **Service status** — version, uptime, health, Live state, and update availability.
- **Paired devices** — the authoritative Hermes-Relay session count.
- **Remote access** — the primary configured route, such as Tailscale or Secure Link.
- **Last Bridge event** and a bounded recent-activity preview.
- **Quick actions** for standard Dashboard setup, Hermes-Relay pairing, and device management.

The old combined Pending/Media counter is intentionally absent. Bridge pending
is momentary activity, while the media registry size is not an active-delivery
count.

### Devices

Devices keeps the two connection contracts together without conflating them:

- **Connect mobile app** creates the tokenless standard Dashboard/Gateway connection.
- **Pair with Hermes-Relay** grants Terminal, Bridge, media, remote-access, and extended voice capabilities.
- **Paired devices** renders responsive cards with client type, last seen, expiry, transport, grants, copy-prefix, and host-confirmed revoke actions.

<!-- TODO: replace with real screenshots — Hermes-Relay Overview and Devices tabs -->

#### Pairing a new device

The **Pair new device** button on Devices uses the same signed pairing
contract as `/hermes-relay-pair` and `hermes pair`, driven from the browser
instead of a chat or shell.

**Click the button to open a QR-first PairDialog with:**

- **A freshly minted QR** — scan it from Android **Settings → Connections →
  Pair Hermes Relay**.
- **The six-character code and copyable invite** — use these for manual Android
  entry or Desktop CLI `--pair-qr` pairing.
- **Endpoint receipt, per-surface probes, and expiry** — each route shows its
  resolved Dashboard, Relay, and optional API URL, strict priority, and probe
  result. The invite is one-time and single-use; mint a fresh one after it
  expires or is consumed. Malformed or plaintext public candidates are blocked.
- **Connection summary** — defaults to **Auto**, which derives every configured
  reachable candidate. LAN-only, Tailscale-only, and public-only modes remain available.
- **Advanced connection options** — collapsed controls for role preference and
  the unusual API-host override.

Leave **Auto** and natural ordering selected for the common case. Configure
Tailscale and a pinned public Dashboard origin on the **Remote Access** tab;
PairDialog folds those server-owned values into the invite automatically. New
routes carry Relay under the Dashboard's same origin at
`/api/plugins/hermes-relay/transport` (with `/ws` and `/health` derived by the
client), so one external port serves both.

The advanced host, port, and TLS override is for a deliberately pinned API
fallback or unusual multi-homed/proxy topology. It is not the normal way to set
the Relay URL and should remain collapsed unless automatic server configuration
is wrong.

**What the minted QR contains.** The current Relay-pairing payload retains its
legacy top-level API fields for older phones and headless compatibility. New
connections should also carry an explicit Dashboard/Gateway URL; neither the API
endpoint nor its key is required for dashboard-primary chat. The nested `relay`
block carries the Relay WSS URL and pairing code. See `docs/spec.md` §3.3.1 for
the full backward-compatible wire format.

**If a manually overridden QR does not pair**, clear the advanced override and
mint again with **Auto**. Dashboard commonly listens locally on `:9119`; the
recommended Tailscale route is dedicated external HTTPS `:10443` → local
`:9119`. Old `:443`/`:9119` routes remain explicit migration compatibility.
Port `:8642` is the optional API fallback. The Relay process may still listen
internally on `:8767`, but that direct port is legacy pairing compatibility and
is not advertised in new QRs. Re-pair old clients before explicitly disabling
a served `:8767` route.

<!-- TODO: replace with real screenshot — PairDialog with QR and override fields expanded -->

### Activity — Bridge activity

Real-time feed of what the agent just did to the phone. Backed by an in-memory ring buffer on the relay (`BridgeHandler.recent_commands`, max 100 entries) that records every bridge command round-trip as it happens — no database, no replay across restarts.

Each row shows:

- **`sent_at`** — relative time, hover for absolute UTC.
- **`method` + `path`** — e.g. `POST /tap`, `POST /send_sms`.
- **`params`** — redacted for any key in `{password, token, secret, otp, bearer}`; everything else renders inline.
- **`decision`** — `executed` (ran normally), `blocked` (phone-side safety-rail denied it), `confirmed` (destructive-verb confirmation accepted), `timeout` (no response in 30s), `error` (exception on either end), or `pending` (in-flight right now).
- **`response_status`** + `result_summary` + `error` — HTTP status from the phone + the first line of the result + any error string.

A filter-chip row above the table lets you narrow to `All | Executed | Blocked | Confirmed | Timeout | Error` at a glance. Polls every 5 seconds (pausable via the Live switch).

<!-- TODO: replace with real screenshot — Bridge Activity tab mid-session, showing executed + one blocked row -->

### Activity — Media tokens

Media tokens is a diagnostic view nested under Activity. It lists active
`MediaRegistry` tokens — the handles Hermes-Relay mints when a host-local tool
(for example `android_screenshot`) registers a file for the paired phone to
download. Bare-path media deliveries do not create registry tokens and are
explicitly outside this view. Each row shows:

- **Token** — truncated display, hover to copy full.
- **`file_name`** — basename only. Absolute paths are never sent from the server; the inspector can't be used to enumerate your filesystem.
- **`content_type`** + **`size`**.
- **`created_at`** / **`last_accessed`**.
- **TTL countdown** — live `setInterval(1000)` ticking down to `expires_at`. Turns red when < 60s remaining.

By default, expired entries are hidden. Click the **Show expired** toggle at the top of the tab to include evicted rows (useful for debugging "did that screenshot actually register?" retroactively).

Polls every 15 seconds.

<!-- TODO: replace with real screenshot — Activity → Media tokens with a registered screenshot row -->

### Remote Access, Git, and Settings

- **Remote Access** retains the supported-first Tailscale, Secure Link, public
  Dashboard origin, per-surface probe, and endpoint-preview workflow. A pathless
  public URL means the Dashboard origin; an explicit Relay proxy path is labeled
  as legacy compatibility.
- **Git** retains the opt-in repository workspace and confirmed write operations.
- **Settings** follows the Dashboard Config layout with General, Agent Context, and Maintenance categories.

## How It's Wired (Brief)

The plugin has three layers:

1. **Frontend** — a pre-built React IIFE at `plugin/dashboard/dist/index.js` (about 110 KB minified), loaded verbatim by the dashboard shell. Source lives in `plugin/dashboard/src/` and is bundled with esbuild. Uses the dashboard's `window.__HERMES_PLUGIN_SDK__` global for React + Nous primitives — no bundled React, no external HTTP library.
2. **Backend proxy** — a FastAPI router at `plugin/dashboard/plugin_api.py` mounted at `/api/plugins/hermes-relay/*` inside the gateway process. Forwards five routes (`/overview`, `/sessions`, `/bridge-activity`, `/media`, `/push`) to the relay at `http://127.0.0.1:{HERMES_RELAY_PORT}` via `httpx.AsyncClient` with a 5-second timeout. Translates relay connect-errors / timeouts / 5xx into `HTTP 502` with a human-readable detail so the UI can show "relay unreachable".
3. **Relay** — three new loopback-gated HTTP routes (`/bridge/activity`, `/media/inspect`, `/relay/info`) plus a loopback-exempt branch on the existing `/sessions`. Both the plugin backend and the relay are localhost-bound, so no bearer is minted and no new credentials are introduced.

For the full wire-shape of each route (query params, response schemas, redaction rules, loopback guards), see the [Relay Server reference](https://github.com/Codename-11/hermes-relay/blob/main/docs/relay-server.md) and [ADR 19](https://github.com/Codename-11/hermes-relay/blob/main/docs/decisions.md) in the repo.

## Troubleshooting

**"Relay unreachable at 127.0.0.1:8767" on every tab.** The gateway can't see your relay process. Check `systemctl --user status hermes-relay` on the server; if the unit is inactive, `systemctl --user restart hermes-relay`. If you run the relay manually, confirm it's bound to `127.0.0.1:8767` and hasn't moved to a different port (override via `HERMES_RELAY_PORT` — the plugin reads this at import time).

**No "Hermes-Relay" tab appears after gateway restart.** Confirm the unified plugin is
enabled with `hermes plugins list`, then re-run
`hermes plugins install Codename-11/hermes-relay/plugin --enable` and refresh or
restart the Dashboard/Gateway plugin catalog. Check the gateway log for
plugin-load errors if the manifest is installed but the page is absent.

**The Hermes-Relay tab appears but text, colors, or cards are hard to read.** Update the Hermes-Relay plugin and restart or rescan the dashboard plugin list. The plugin stylesheet is loaded by the upstream dashboard and follows its active theme tokens; stale `dist/style.css` files from older installs can render poorly after Hermes dashboard theme changes.

**Bridge Activity tab is empty but the phone is issuing commands.** The ring buffer is in-memory and wipes on relay restart. If you just restarted the relay, you need the phone to issue at least one command before the tab has anything to show. If commands are going through but not appearing, confirm they're reaching the relay (`journalctl --user -u hermes-relay -f` should show the command round-trips).

**Media tokens shows entries but files won't download.** That's a separate path — the diagnostic view lists token-backed registry entries, while the actual download goes through `/media/{token}` (bearer-gated, via the phone). Bare-path deliveries are not listed. If the phone can't fetch a token, check the bearer's `media` grant and `RELAY_MEDIA_TTL_SECONDS` hasn't elapsed since registration.

**Revoke button fails silently.** Revoke is live as of the dashboard plugin release — `DELETE /api/plugins/hermes-relay/sessions/{prefix}` is proxied to the relay. If the click confirm fires but the list doesn't update, open the browser devtools network tab and re-click: a 502 means the relay itself is unreachable (see the "Relay unreachable" item above), a 404 means the token prefix is already gone (the list auto-reloaded between the button render and your click), and a 403 means the proxy is seeing a non-loopback caller (hermes-agent's dashboard shouldn't ever hit this — check `journalctl --user -u hermes-gateway -f` for the origin).

**Pair dialog mints a QR that won't pair.** Verify the host/port in the override panel: the top-level Host/Port are for the **Hermes API server** (default `:8642`), not the relay (`:8767`). If you entered the relay port in the override, the phone tries to reach the API at the relay's address and bails silently. Reset the overrides (clear the fields and click Pair new device again) and confirm the auto-detected defaults point at your actual API server.

## Security Notes

`/bridge/activity` and `/media/inspect` remain gated to `127.0.0.1` / `::1`.
`/relay/info` also accepts a remote request carrying a valid paired-device
bearer so the Android Diagnostics screen can read the sanitized version,
capability, and profile contract. It never returns tokens or configuration
paths.

The `MediaRegistry.list_all()` snapshot strips absolute paths server-side before the relay serializes its response, so even if you deliberately exposed these routes externally (by fronting the relay with a reverse proxy, for example), the inspector couldn't be used to enumerate your filesystem.

Bridge command `params` are redacted for any key matching `{password, token, secret, otp, bearer}` before they hit the ring buffer. This is best-effort — if you route a secret through a field named something else, it'll land in the activity feed verbatim. Audit your agent tools for non-obvious secret-carrying params if that matters for your threat model.
