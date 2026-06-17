# Relay Server

The relay server is a lightweight Python WSS/HTTP service that enables **terminal** (remote shell), **bridge** (agent-driven phone control), media, sessions, and voice routes in Hermes-Relay. Chat never touches the relay — it rides your standard Hermes surfaces, preferring the dashboard gateway (`/api/ws`, live thinking) when Manage auth is ready and falling back to the API server's SSE routes otherwise.

## Do I Need It?

| Feature | Relay required? | Auth path |
|---------|-----------------|-----------|
| Chat | No | Hermes API key direct to API server |
| Standard Voice Mode | No | Dashboard session from Manage |
| Relay voice extras | Yes for relay TTS/STT/realtime endpoints; pairing optional when the API key is present | Hermes API bearer or relay session |
| Realtime Agent voice engine | Yes; experimental `/voice/realtime-agent/*` broker | Hermes API bearer or relay session with `voice:realtime` |
| Inbound media (screenshots from tools) | Yes | Relay session |
| Terminal | Yes | Relay session |
| Bridge (sideload track only) | Yes | Relay session |

## Quick Start

**If you used `install.sh`** — nothing to do. Step [6/6] of the installer drops a systemd **user** unit at `~/.config/systemd/user/hermes-relay.service` and enables it. The relay is already running and will auto-restart on failure:

```bash
systemctl --user status hermes-relay
systemctl --user restart hermes-relay
journalctl --user -u hermes-relay -f
```

Want the service to survive logging out of SSH?

```bash
loginctl enable-linger $USER
```

To uninstall the relay (and the rest of the plugin) cleanly:

```bash
bash ~/.hermes/hermes-relay/uninstall.sh
```

The uninstaller stops the systemd unit, removes it, daemon-reloads, and reverses every other install step (pip package, plugin symlink, skills config entry, hermes-pair shim, git clone). It's idempotent and never touches `~/.hermes/.env`, the gateway's `state.db`, or the `hermes-agent` venv core. Use `--dry-run` to preview, `--keep-clone` to keep the git tree, `--remove-secret` to also wipe the QR signing identity.

**Manual run** (dev boxes, hosts without systemd-user):

```bash
# Canonical entry point
python -m plugin.relay --no-ssl

# Legacy entrypoint still works via a thin compat shim
python -m relay_server --no-ssl
```

Both entry points load `~/.hermes/.env` into the process environment **on import**, so API keys (`VOICE_TOOLS_OPENAI_KEY`, `ELEVENLABS_API_KEY`, etc.) are always present on start regardless of how the process was launched — no need to `source` anything first. This is the same pattern `hermes-gateway` uses, which is why neither unit needs an `EnvironmentFile=` directive. See [`docs/relay-server.md`](https://github.com/Codename-11/hermes-relay/blob/main/docs/relay-server.md#env-auto-loading) for the precedence rules.

### Plugin manager install

Current upstream Hermes can install the plugin tree directly:

```bash
hermes plugins install Codename-11/hermes-relay/plugin --enable
hermes relay doctor
```

That path manages plugin code, CLI command registration, dashboard metadata, and
agent tools. It does not install the systemd user service or shell shims. Use the
legacy `install.sh` only when you want those host-level artifacts.

The optional compatibility startup hook is managed by the plugin:

```bash
hermes relay compat status
hermes relay compat install   # older Hermes builds only
hermes relay compat remove
```

Standard chat, Manage, and dashboard voice do not require the compat hook.
New compat installs load the bootstrap implementation from the installed plugin
tree. Existing legacy hooks remain visible in `hermes relay compat status` and
can be removed with `hermes relay compat remove`.

Pairing/setup QRs can include top-level `dashboard_url`; Android uses it for
Manage and standard dashboard voice instead of deriving same-host `:9119`.

### Legacy cleanup ownership

| Artifact | Remove with |
|----------|-------------|
| Plugin-manager install | `hermes plugins remove hermes-relay` |
| Optional compat hook | `hermes relay compat remove --all` |
| Legacy systemd service | `bash ~/.hermes/hermes-relay/uninstall.sh` |
| Legacy shell shims | `bash ~/.hermes/hermes-relay/uninstall.sh` |
| Legacy editable Python package | `bash ~/.hermes/hermes-relay/uninstall.sh` |
| Legacy `skills.external_dirs` entry | `bash ~/.hermes/hermes-relay/uninstall.sh` |
| Legacy clone | `bash ~/.hermes/hermes-relay/uninstall.sh` unless `--keep-clone` is used |

The legacy uninstaller delegates compat hook removal to
`hermes relay compat remove` when that command is available, then falls back to
removing only the Relay `.pth` file. It preserves `~/.hermes/.env`,
`~/.hermes/state.db`, the Hermes agent install, and the QR signing secret unless
`--remove-secret` is passed.

For agent-assisted cleanup, use the bounded
[Agent Cleanup Prompt](/reference/agent-cleanup-prompt).

## Deployment Options

### Docker

```bash
docker build -t hermes-relay relay_server/
docker run -d --name hermes-relay --network host \
  -v ~/.hermes:/home/relay/.hermes:ro hermes-relay
```

Mount `~/.hermes` read-only into the container so the in-process `.env` bootstrap can find it at its canonical path.

### Systemd user service (manual install)

The installer does this for you, but if you're installing from a non-standard layout:

```bash
mkdir -p ~/.config/systemd/user
cp relay_server/hermes-relay.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now hermes-relay.service
loginctl enable-linger $USER   # survive logout (optional)
```

The unit template uses systemd's `%h` specifier for your home directory, so no hand-editing is required on a default install (`~/.hermes/hermes-relay`, `~/.hermes/hermes-agent/venv`).

### TLS (production)

```bash
export RELAY_SSL_CERT=/etc/letsencrypt/live/yourdomain/fullchain.pem
export RELAY_SSL_KEY=/etc/letsencrypt/live/yourdomain/privkey.pem
systemctl --user restart hermes-relay
```

Or terminate TLS at a reverse proxy (nginx/Caddy) in front of the relay. If you set these in `~/.hermes/.env`, the Python bootstrap picks them up on the next service restart — no need to edit the unit file.

## Configuration

All settings via environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `RELAY_HOST` | `0.0.0.0` | Bind address |
| `RELAY_PORT` | `8767` | Listen port |
| `RELAY_SSL_CERT` | — | TLS certificate path |
| `RELAY_SSL_KEY` | — | TLS private key path |
| `RELAY_WEBAPI_URL` | `http://localhost:8642` | Hermes API Server URL |
| `RELAY_HERMES_CONFIG` | `~/.hermes/config.yaml` | Hermes config path |
| `RELAY_LOG_LEVEL` | `INFO` | Logging level |
| `RELAY_TRUST_PROXY_HEADERS` | `0` | Trust `X-Forwarded-Proto: https` from your own reverse proxy for Hermes API bearer auth on `/voice/*`. |
| `RELAY_ALLOW_INSECURE_API_BEARER` | `0` | Dev-only escape hatch for non-loopback plaintext Hermes API bearer auth on `/voice/*`. Leave off for production. For a running relay, use `hermes relay insecure-api-key on` or the standalone `hermes-relay insecure-api-key on` shim instead of restarting with env. |
| `RELAY_MEDIA_MAX_SIZE_MB` | `100` | Per-file size cap for `POST /media/register` (inbound media pipeline — see ADR 14) |
| `RELAY_MEDIA_TTL_SECONDS` | `86400` | How long a registered media entry stays fetchable |
| `RELAY_MEDIA_LRU_CAP` | `500` | Max entries in the in-memory media registry before LRU eviction |
| `RELAY_MEDIA_ALLOWED_ROOTS` | — | Extra absolute-path roots allowed on register (`os.pathsep`-separated). Extends defaults (`tempfile.gettempdir()` + `HERMES_WORKSPACE`). |

Voice endpoints accept either a Relay session token or the existing Hermes API bearer token. The Hermes API bearer path is limited to `/voice/config`, `/voice/transcribe`, `/voice/synthesize`, `/voice/output/*`, `/voice/realtime/*`, and `/voice/realtime-agent/*`; pairing is still required for terminal, bridge, TUI, sessions, media, clipboard, profile writes, and Android control routes. Android derives a default Relay URL from the API URL by swapping `http(s)` to `ws(s)` and using port `8767`, then probes `/voice/config`; custom Relay URLs are still supported as an override. Non-loopback API-bearer voice calls require HTTPS by default. For temporary plain-LAN phone testing, run `hermes relay insecure-api-key on` or `hermes-relay insecure-api-key on` on the relay host; disable it with the matching `off` command.

## CLI Flags

```
hermes relay start [OPTIONS]          (or: python -m plugin.relay)

  --host HOST        Bind address (default: 0.0.0.0)
  --port PORT        Listen port (default: 8767)
  --no-ssl           Disable TLS (dev/localhost only)
  --shell PATH       Default shell for terminal sessions (absolute path; default: $SHELL)
  --webapi-url URL   Hermes WebAPI base URL (default: http://localhost:8642)
  --log-level LEVEL  DEBUG, INFO, WARNING, ERROR
  --config PATH      Hermes config.yaml path (python -m plugin.relay only)
  --allow-insecure-api-key
                     Allow API-key voice auth over plain LAN HTTP at startup

hermes relay insecure-api-key [status|on|off]
hermes-relay insecure-api-key [status|on|off]
                     Toggle plain-LAN API-key voice auth on the running relay

hermes relay compat [status|install|remove]
                     Manage the optional legacy API compatibility startup hook
```

## HTTP Routes

| Route | Method | Purpose |
|-------|--------|---------|
| `/ws`, `/` | GET (upgrade) | WebSocket endpoint — phone connects here |
| `/health` | GET | `{status, version, clients, sessions}` JSON |
| `/pairing` | POST | Generate a new relay-side pairing code |
| `/pairing/register` | POST | **Loopback only.** Pre-register an externally-provided pairing code so it can be embedded in a QR payload. Optional body fields `ttl_seconds` / `grants` / `transport_hint` attach pairing metadata that applies to the session when the phone consumes the code — operator policy wins over phone-sent values. Also **clears all rate-limit blocks on success** so legitimate re-pair after a relay restart works immediately. Used by `hermes pair` / `/hermes-relay-pair` on the same host; `hermes-pair` remains a compatibility shim. Rejects non-loopback peers with HTTP 403. |
| `/pairing/mint` | POST | **Loopback only.** Mint a fresh pairing code and return the signed QR payload plus `pairing_url` (`hermes-relay://pair?payload=...`) used by dashboard, desktop GUI, and CLI pair/repair flows. Reads the API key from the same host-local config chain as `hermes pair` when not supplied explicitly. Optional request field `dashboard_url` is mirrored into the QR payload and response. |
| `/pairing/approve` | POST | **Loopback only, reserved for future use.** Same wire shape as `/pairing/register`. Placeholder for a future phone-generates-code / host-approves flow that would complement the existing QR pairing direction. |
| `/sessions` | GET | Bearer-auth'd (same token the WSS channel uses). Returns all active paired devices with metadata — device name, token prefix (first 8 chars, full token never exposed), created/last-seen timestamps, session expiry, per-channel grants, transport hint, and `is_current` for the device matching the bearer. `math.inf` expiries serialize as `null` (never expire). |
| `/sessions/{token_prefix}` | DELETE | Bearer-auth'd. Revoke a paired device by token-prefix (≥ 4 chars). 200 on exact match, 404 on zero, 409 on ambiguous matches. Self-revoke is allowed and flagged via `revoked_self: true`. |
| `/sessions/{token_prefix}` | PATCH | Bearer-auth'd. Update a paired device's session TTL and/or per-channel grants in place. Body `{ttl_seconds?, grants?}`. TTL restarts the clock from now; grants re-clamp automatically. Powers the phone's Relay sessions "Extend" button. |
| `/clipboard/inbox` | POST | Bearer-auth'd clipboard rendezvous used by remote clients before native platform clipboard fallback. |
| `/media/register` | POST | **Loopback only.** Register a host-local file with the `MediaRegistry` and receive an opaque token. Body: `{"path": "/abs/path", "content_type": "image/jpeg", "file_name": "screenshot.jpg"}`. Used by tools like `android_screenshot` so the agent can emit `MEDIA:hermes-relay://<token>` in chat and have the phone fetch bytes out-of-band. Path is sandboxed to `tempfile.gettempdir()` + `HERMES_WORKSPACE` + any `RELAY_MEDIA_ALLOWED_ROOTS`; symlink escape is rejected via `realpath`. Returns 400 on validation failure. See ADR 14. |
| `/media/upload` | POST | Bearer-auth'd small upload endpoint for phone-originated media. Accepts JSON `{file_name, content_type, content}` where `content` is base64 and registers the decoded bytes with the media registry. |
| `/media/{token}` | GET | Stream the bytes of a previously-registered file. Requires `Authorization: Bearer <session_token>` — same token the WSS channel uses (validated against `SessionManager`). Response carries the registered `Content-Type` plus `Content-Disposition: inline; filename="..."` if a file name was provided at register time. The client only ever sees the opaque token — the path is never exposed. 401 without/with bad auth, 404 for unknown/expired tokens. |
| `/media/by-path` | GET | Fetch a file **by absolute path** rather than by registry token — covers the case where the agent's LLM freeform-emits `MEDIA:/abs/path.ext` in its response text (upstream `prompt_builder.py` tells it to). Query: `path` (required), `content_type` (optional hint). Bearer auth identical to `/media/{token}`. Path is sandbox-validated against the same allowed roots as `/media/register` (`tempfile.gettempdir()` + `HERMES_WORKSPACE` + `RELAY_MEDIA_ALLOWED_ROOTS`). 400 missing `path`; 401 auth; 403 sandbox violation; 404 file not found. See ADR 14 for the bare-path-LLM rationale. |
| `/voice/transcribe` | POST | Bearer-auth'd via either a Relay session token with active `voice:stt` grant or a valid Hermes API bearer token. Non-loopback API-bearer calls require HTTPS unless `RELAY_ALLOW_INSECURE_API_BEARER=1`. `multipart/form-data` with an audio file → `{"text": "...", "provider": "openai", "success": true}`. Android may include `?profile=<name>` so logs/UI retain the active profile context; execution still goes through the upstream STT helper. See [Voice Mode](/features/voice) for the full story. |
| `/voice/synthesize` | POST | Bearer-auth'd via either a Relay session token with active `voice:tts` grant or a valid Hermes API bearer token. Non-loopback API-bearer calls require HTTPS unless `RELAY_ALLOW_INSECURE_API_BEARER=1`. JSON body `{"text": "...", "profile": "mizu"}` (max 5000 chars) → `audio/mpeg` file. This is the basic fallback TTS route; normal Android voice playback prefers `/voice/output/*`. |
| `/voice/config` | GET | Bearer-auth'd via either a Relay session token with active `voice:config` grant or a valid Hermes API bearer token. Non-loopback API-bearer calls require HTTPS unless `RELAY_ALLOW_INSECURE_API_BEARER=1`. Optional `?profile=<name>` resolves `tts:` / `stt:` from `~/.hermes/profiles/<name>/config.yaml` when present, otherwise falls back to global config. Returns `config_scope`, `profile`, and `fallback_to_global` so the app can label Voice Settings accurately. |
| `/voice/output/config` | GET/PATCH | Relay-mediated streaming TTS renderer settings. Optional `?profile=<name>` reads or writes the experimental `voice_output:` section in that profile's `config.yaml`; absent profile writes relay-owned defaults. Responses include provider/model/voice, `config_scope`, fallback metadata, and known provider option lists (`models`, `voices`, `languages`, `sample_rates`) for app dropdowns. |
| `/voice/output/providers/{provider_id}/options` | GET | Provider-specific Voice Output option refresh. Optional `?profile=<name>` returns profile/scope metadata alongside the provider option object. Dynamic provider discovery stays server-side and cached: xAI can refresh built-in/paginated custom voices, ElevenLabs can refresh voices/models/languages, OpenAI uses static documented voices, and all providers fall back to static metadata when auth or remote discovery is unavailable. Responses include grouped voice metadata and compatibility hints when available. |
| `/voice/output/providers/{provider_id}/validate` | POST | Validates a pending Voice Output selection before save. Body accepts `model`, `voice`, `sample_rate`, optional `language`, and optional `profile`; response returns `valid`, `checks[]`, and `summary`. |
| `/voice/output/session` | POST | Creates a short-lived streaming TTS websocket session using profile-scoped voice output defaults when `{"profile": "<name>"}` is supplied. Hermes still owns chat/tool execution; this route renders final assistant text to audio. |
| `/voice/realtime/config` | GET/PATCH | Experimental realtime provider lab settings. Optional `?profile=<name>` reads or writes `realtime_voice:` in the selected profile config; absent profile uses relay-owned defaults. Responses include known provider option lists for app dropdowns while still allowing manual IDs. |
| `/voice/realtime/providers/{provider_id}/options` | GET | Provider-specific Realtime Agent Lab option refresh for realtime-capable providers. Optional `?profile=<name>` returns profile/scope metadata. xAI realtime shares the same built-in/paginated custom voice discovery path as xAI TTS; OpenAI realtime uses static documented voices. |
| `/voice/realtime/providers/{provider_id}/validate` | POST | Validates a pending realtime provider/model/voice/sample-rate selection before save. |
| `/voice/realtime/session` | POST | Creates an experimental realtime voice websocket session with profile-scoped defaults when `{"profile": "<name>"}` is supplied. Used for provider testing, speech-to-speech experiments, and tool-call scaffolding rather than the default deterministic assistant speech renderer. |
| `/voice/realtime-agent/config` | GET/PATCH | Experimental Realtime Agent voice engine config. Uses profile-scoped `realtime_voice:` settings but reports the broker mode, stable fallback engine, Experimental status, limits, and narrow Hermes tool surface. |
| `/voice/realtime-agent/providers/{provider_id}/options` | GET | Provider-specific option refresh for the Realtime Agent settings UI. Returns safe model/voice/sample-rate metadata and keeps provider account discovery server-side. |
| `/voice/realtime-agent/providers/{provider_id}/validate` | POST | Validates a pending Realtime Agent provider/model/voice/sample-rate selection before save. |
| `/voice/realtime-agent/session` | POST | Creates a brokered Realtime Agent websocket session bound to active profile, optional Hermes chat session id, provider defaults, auth principal, and event log path. |
| `/voice/realtime-agent/{session_id}` | GET websocket | Experimental broker websocket. It mirrors input transcript, Hermes session/tool/confirmation state, final response text, and provider PCM into the app timeline while Hermes remains the owner of tools, memory, safety, confirmations, and transcript persistence. |
| `/notifications/recent` | GET | Loopback callers skip bearer auth; remote callers need it. Returns the most recent entries from the bounded in-memory `NotificationsChannel` deque (default cap 100, wiped on relay restart). Backs the `android_notifications_recent(limit=20)` plugin tool. |
| `/bridge/status` | GET | **Loopback only.** Structured `{"device": {...}, "bridge": {...}, "safety": {...}}` phone-status view used by `android_phone_status()`, the `hermes-status` shell shim, and the `/hermes-relay-status` skill. |
| `/relay/security` | GET/PATCH | **Loopback only.** Runtime security toggles used by `hermes relay insecure-api-key` and `hermes-relay insecure-api-key`. `GET` reports the running relay's `allow_insecure_api_bearer` state; `PATCH {"allow_insecure_api_bearer": true}` enables plain-LAN API-key voice auth until the relay restarts. |

## Bridge HTTP Routes

The bridge channel has two scopes:

- **Bridge Core** is available on Google Play and sideload builds. It covers relay pairing/status plus non-device-control channels such as terminal/TUI relay, voice, media handoff, sessions, and notification companion.
- **Device Control** is sideload-only. It publishes the HTTP surface below for Hermes `android_*` plugin tools. Every route is proxied over the phone's WSS connection to the in-app `BridgeCommandHandler` and runs through the safety pipeline (blocklist → destructive-verb confirmation → auto-disable reschedule) before any gesture fires.

As of v0.4 the Device Control surface is **34 routes** (33 excluding the legacy `/apps` alias) covering gestures, accessibility-tree reads, clipboard, media control, raw intents, an event stream, a phone-utility tier (send_sms, call, search_contacts, location, share_media, send_mms), and a self-foreground route (`/return_to_hermes`). Google Play Bridge Core phones report `bridge.device_control_supported=false`; the plugin hides the `android_*` Device Control tools, and direct command probes fail closed with `403` / `error_code: device_control_sideload_only`.

| Route | Method | Group | Purpose |
|-------|--------|-------|---------|
| `/ping` | GET | core | Liveness — bypasses the master-enable gate |
| `/setup` | POST | core | One-shot welcome ping the agent can send before issuing real commands |
| `/current_app` | GET | core | Best-effort foregrounded package name (bypasses master-enable gate). Accessibility/SystemUI state can lag; use `/screen` for verification. |
| `/screen` | GET | read | Full accessibility tree → `ScreenContent(rootBounds, nodes[], truncated)`. Walks every accessibility window (system UI, popups, notification shade), not just the active app. |
| `/screen_hash` | GET | read | SHA-256 fingerprint of the current screen for cheap change detection |
| `/diff_screen` | POST | read | Compare current screen against a previous hash without re-downloading the full tree |
| `/find_nodes` | POST | read | Filtered accessibility-tree search (clickable, text match, resource-id, class, etc.) |
| `/describe_node` | POST | read | Full property bag for a stable node ID previously returned by `/screen` / `/find_nodes` |
| `/screenshot` | GET | read | PNG bytes via `MediaProjection` → `VirtualDisplay` → `ImageReader`, uploaded to the relay's media registry and returned as an opaque token |
| `/get_apps` | GET | read | Launchable app list from `PackageManager.queryIntentActivities(ACTION_MAIN + CATEGORY_LAUNCHER)`. Requires a matching `<queries>` element in the manifest on Android 11+. |
| `/apps` | GET | read | Legacy alias for `/get_apps` (pre-v0.4 tool name) |
| `/tap` | POST | act | Coordinate or `nodeId`-based tap. Runs the destructive-verb gate when a text hint is attached. |
| `/tap_text` | POST | act | Three-tier tapText cascade — exact match → clickable-ancestor walk → substring fallback. Destructive-verb gated. |
| `/long_press` | POST | act | Long-press gesture (context menus, drag initiation, widget rearranging) — by coordinate or node ID |
| `/drag` | POST | act | Drag gesture — point A → point B over a duration |
| `/type` | POST | act | Set text on the focused input via `ACTION_SET_TEXT`. Destructive-verb gated. |
| `/swipe` | POST | act | Directional swipe gesture |
| `/scroll` | POST | act | Scroll a container by direction or node ID |
| `/press_key` | POST | act | Global action vocab — `back`, `home`, `recents`, `notifications`, etc. No raw KeyEvent injection. |
| `/wait` | POST | act | Sleep in the command stream (capped at 15 s) |
| `/open_app` | POST | act | Launch a package by ID; safety-rails blocklist runs against the *target* package, not just the current foregrounded app |
| `/clipboard` | GET/POST | act | Read or write the system clipboard |
| `/media` | POST | act | System-wide playback control — play / pause / next / previous / volume |
| `/send_intent` | POST | act | Raw Android Intent escape hatch (startActivity) |
| `/broadcast` | POST | act | Raw Android broadcast escape hatch (sendBroadcast) |
| `/events` | GET | events | Poll the recent AccessibilityEvent buffer (structured UI events — window state changes, view clicks, scrolls, content changes) |
| `/events/stream` | POST | events | Toggle accessibility-event capture on / off on the phone side |
| `/location` | GET | sideload-only | GPS last-known-location read |
| `/search_contacts` | POST | sideload-only | Contact lookup by name → phone number |
| `/call` | POST | sideload-only | Place a call via `ACTION_CALL` |
| `/send_sms` | POST | sideload-only | Direct text-only SMS send via `SmsManager` with structured `sent`, `blocked`, `timeout`, or `failed` status details |
| `/share_media` | POST | sideload-only | Share text/files/relay media through Android's native share UI using `FileProvider` `content://` grants |
| `/send_mms` | POST | sideload-only | Open a user-mediated MMS compose/share handoff with recipient, text, and attachments |

**Gating.** Device Control routes require a sideload phone reporting `bridge.device_control_supported=true`. On the Google Play build, `/ping`, `/events`, and `/setup` can answer harmless probes, while Device Control commands fail closed before any AccessibilityService-dependent code runs. On sideload, every route except `/ping`, `/current_app`, and `/return_to_hermes` is refused with 403 when the in-app master toggle is off. Blocklisted target packages return 403 `{"error": "blocked package <name>"}`; denied destructive-verb confirmations return 403 `{"error": "user denied destructive action", "reason": "confirmation_denied_or_timeout"}`.

## Pairing Model

The phone does **not** enter a pairing code by hand. Instead, the pair command (`hermes pair`, the `/hermes-relay-pair` slash command, or the compatibility `hermes-pair` shell shim, all running on the Hermes host) drives the whole handshake:

1. The pair command mints a fresh 6-character code from `A-Z / 0-9`
2. It POSTs the code to `/pairing/register` on the local relay (blocked for any caller outside `127.0.0.1` / `::1`)
3. It embeds the relay URL and code in the same QR payload that carries the API server credentials
4. The phone scans once — the relay block auto-configures Settings > Connection
5. The phone's first WSS connect uses that code in its `system/auth` envelope; the relay consumes it and issues a 30-day session token

Pairing codes are one-shot and expire 10 minutes after registration. Session tokens (stored in EncryptedSharedPreferences on device) are used for all subsequent reconnects.

## Health Check

```bash
curl http://localhost:8767/health
```

## Troubleshooting

- **Connection refused** — Is the relay running? `systemctl --user status hermes-relay` (installed via `install.sh`) or `docker logs hermes-relay` (container) or `pgrep -af "python -m plugin.relay"` (manual launch).
- **Unsure what is installed** — Run `hermes relay doctor --json` for a full route/plugin/compat report, or `hermes relay compat status` for only the legacy hook.
- **Voice endpoints 500 with "no API key available"** — The relay process doesn't have the right keys. The Python bootstrap loads `~/.hermes/.env` automatically, so this almost always means the key just isn't in `.env` yet. Double-check with `grep VOICE_TOOLS_OPENAI_KEY ~/.hermes/.env` (for STT) or `grep ELEVENLABS_API_KEY ~/.hermes/.env` (for TTS). If you just edited `.env`, restart the service so Python re-imports: `systemctl --user restart hermes-relay`.
- **Service starts but port bind fails** — Check for an orphan manual launch: `pgrep -f "python -m plugin.relay"`. Kill it with `pkill -f "python -m plugin.relay"` then `systemctl --user restart hermes-relay`.
- **Auth failure** — Pairing codes expire 10 minutes after registration and are one-shot. Re-run `hermes pair` (or `/hermes-relay-pair`) to mint a fresh code and get a new QR.
- **QR has no relay block** — the pair command only embeds relay details if it can reach `localhost:RELAY_PORT/health` when it runs. Start the relay first, then re-run `hermes pair`.
- **TLS errors** — Use `--no-ssl` for local dev. Ensure cert paths are correct for production.
- **Phone can't reach relay** — Check firewall rules for port 8767. Verify with `curl http://server-ip:8767/health` from another machine.
- **Remote chat or API-key voice fails but relay pairs** — Verify the Hermes API route too: `curl http://server-ip:8642/health`, or `https://<tailnet-host>.ts.net:8642/health` when using Tailscale. Pairing can succeed through `:8767` while chat and API-key voice fail if `:8642` is not published.
- **Service stops when you log out of SSH** — That's systemd's default for user services. Run `loginctl enable-linger $USER` once to fix it.

## Further Reading

- [Full relay server docs](https://github.com/Codename-11/hermes-relay/blob/main/docs/relay-server.md)
- [Architecture decisions](https://github.com/Codename-11/hermes-relay/blob/main/docs/decisions.md)
- [Specification](https://github.com/Codename-11/hermes-relay/blob/main/docs/spec.md)
