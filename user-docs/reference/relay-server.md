# Relay Server

The relay server is a lightweight Python WSS service that enables **terminal** (remote shell) and **bridge** (agent-driven phone control) features in Hermes-Relay. Chat does not use the relay — it connects directly to the Hermes API Server.

## Do I Need It?

| Feature | Relay required? |
|---------|----------------|
| Chat | No |
| Voice Mode | Yes (for TTS/STT endpoints) |
| Inbound media (screenshots from tools) | Yes |
| Terminal | Yes |
| Bridge (sideload track only) | Yes |

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
| `RELAY_MEDIA_MAX_SIZE_MB` | `100` | Per-file size cap for `POST /media/register` (inbound media pipeline — see ADR 14) |
| `RELAY_MEDIA_TTL_SECONDS` | `86400` | How long a registered media entry stays fetchable |
| `RELAY_MEDIA_LRU_CAP` | `500` | Max entries in the in-memory media registry before LRU eviction |
| `RELAY_MEDIA_ALLOWED_ROOTS` | — | Extra absolute-path roots allowed on register (`os.pathsep`-separated). Extends defaults (`tempfile.gettempdir()` + `HERMES_WORKSPACE`). |

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
```

## HTTP Routes

| Route | Method | Purpose |
|-------|--------|---------|
| `/ws`, `/` | GET (upgrade) | WebSocket endpoint — phone connects here |
| `/health` | GET | `{status, version, clients, sessions}` JSON |
| `/pairing` | POST | Generate a new relay-side pairing code |
| `/pairing/register` | POST | **Loopback only.** Pre-register an externally-provided pairing code so it can be embedded in a QR payload. Optional body fields `ttl_seconds` / `grants` / `transport_hint` attach pairing metadata that applies to the session when the phone consumes the code — operator policy wins over phone-sent values. Also **clears all rate-limit blocks on success** so legitimate re-pair after a relay restart works immediately. Used by `/hermes-relay-pair` / `hermes-pair` on the same host. Rejects non-loopback peers with HTTP 403. |
| `/pairing/approve` | POST | **Loopback only, reserved for future use.** Same wire shape as `/pairing/register`. Placeholder for a future phone-generates-code / host-approves flow that would complement the existing QR pairing direction. |
| `/sessions` | GET | Bearer-auth'd (same token the WSS channel uses). Returns all active paired devices with metadata — device name, token prefix (first 8 chars, full token never exposed), created/last-seen timestamps, session expiry, per-channel grants, transport hint, and `is_current` for the device matching the bearer. `math.inf` expiries serialize as `null` (never expire). |
| `/sessions/{token_prefix}` | DELETE | Bearer-auth'd. Revoke a paired device by token-prefix (≥ 4 chars). 200 on exact match, 404 on zero, 409 on ambiguous matches. Self-revoke is allowed and flagged via `revoked_self: true`. |
| `/sessions/{token_prefix}` | PATCH | Bearer-auth'd. Update a paired device's session TTL and/or per-channel grants in place. Body `{ttl_seconds?, grants?}`. TTL restarts the clock from now; grants re-clamp automatically. Powers the Paired Devices "Extend" button. |
| `/media/register` | POST | **Loopback only.** Register a host-local file with the `MediaRegistry` and receive an opaque token. Body: `{"path": "/abs/path", "content_type": "image/jpeg", "file_name": "screenshot.jpg"}`. Used by tools like `android_screenshot` so the agent can emit `MEDIA:hermes-relay://<token>` in chat and have the phone fetch bytes out-of-band. Path is sandboxed to `tempfile.gettempdir()` + `HERMES_WORKSPACE` + any `RELAY_MEDIA_ALLOWED_ROOTS`; symlink escape is rejected via `realpath`. Returns 400 on validation failure. See ADR 14. |
| `/media/{token}` | GET | Stream the bytes of a previously-registered file. Requires `Authorization: Bearer <session_token>` — same token the WSS channel uses (validated against `SessionManager`). Response carries the registered `Content-Type` plus `Content-Disposition: inline; filename="..."` if a file name was provided at register time. The client only ever sees the opaque token — the path is never exposed. 401 without/with bad auth, 404 for unknown/expired tokens. |
| `/media/by-path` | GET | Fetch a file **by absolute path** rather than by registry token — covers the case where the agent's LLM freeform-emits `MEDIA:/abs/path.ext` in its response text (upstream `prompt_builder.py` tells it to). Query: `path` (required), `content_type` (optional hint). Bearer auth identical to `/media/{token}`. Path is sandbox-validated against the same allowed roots as `/media/register` (`tempfile.gettempdir()` + `HERMES_WORKSPACE` + `RELAY_MEDIA_ALLOWED_ROOTS`). 400 missing `path`; 401 auth; 403 sandbox violation; 404 file not found. See ADR 14 for the bare-path-LLM rationale. |
| `/voice/transcribe` | POST | Bearer-auth'd. `multipart/form-data` with an audio file → `{"text": "...", "provider": "openai", "success": true}`. Relay calls `tools.transcription_tools.transcribe_audio` from the hermes-agent venv. Provider is read from `stt:` in `~/.hermes/config.yaml` — the phone doesn't pass a provider name. See [Voice Mode](/features/voice) for the full story. |
| `/voice/synthesize` | POST | Bearer-auth'd. JSON body `{"text": "..."}` (max 5000 chars) → `audio/mpeg` file. Relay calls `tools.tts_tool.text_to_speech_tool` which writes to `~/voice-memos/tts_<ts>.mp3` and serves it via `web.FileResponse`. Provider is read from `tts:` in `~/.hermes/config.yaml`. Streaming is client-side — the phone detects sentence boundaries in the chat SSE stream and POSTs one sentence at a time so playback starts within a sentence of the agent replying. |
| `/voice/config` | GET | Bearer-auth'd. Returns `{"tts": {...}, "stt": {...}, "requirements": {...}}` describing what TTS/STT providers the relay's hermes-agent venv has configured. Used by the app's Voice Settings screen to show provider info and power the Test Voice button. |
| `/notifications/recent` | GET | Loopback callers skip bearer auth; remote callers need it. Returns the most recent entries from the bounded in-memory `NotificationsChannel` deque (default cap 100, wiped on relay restart). Backs the `android_notifications_recent(limit=20)` plugin tool. |
| `/bridge/status` | GET | **Loopback only.** Structured `{"device": {...}, "bridge": {...}, "safety": {...}}` phone-status view used by `android_phone_status()`, the `hermes-status` shell shim, and the `/hermes-relay-status` skill. |

## Bridge HTTP Routes

The bridge channel (v0.3+) publishes an HTTP surface on the unified relay for the Hermes `android_*` plugin tools. Every route is proxied over the phone's WSS connection to the in-app `BridgeCommandHandler` and runs through the Tier 5 safety pipeline (blocklist → destructive-verb confirmation → auto-disable reschedule) before any gesture fires. A 30-second per-command timeout and fail-fast-on-phone-disconnect semantics keep callers from wedging.

As of v0.4 the bridge surface is **27 routes** covering gestures, accessibility-tree reads, clipboard, media control, raw intents, an event stream, and a sideload-only phone-utility tier. See the v0.3 release notes and v0.4 changelog for the feature story behind each group.

| Route | Method | Group | Purpose |
|-------|--------|-------|---------|
| `/ping` | GET | core | Liveness — bypasses the master-enable gate |
| `/setup` | POST | core | One-shot welcome ping the agent can send before issuing real commands |
| `/current_app` | GET | core | Foregrounded package name (bypasses master-enable gate) |
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
| `/call` | POST | sideload-only | Place a call via `ACTION_CALL`, with an `ACTION_DIAL` fallback on Google Play |
| `/send_sms` | POST | sideload-only | Direct SMS send via `SmsManager` with send-result confirmation |

**Gating.** Every route except `/ping` and `/current_app` is refused with 403 when the in-app master toggle is off. The sideload-only routes are compiled out of the Google Play build. Blocklisted target packages return 403 `{"error": "blocked package <name>"}`; denied destructive-verb confirmations return 403 `{"error": "user denied destructive action", "reason": "confirmation_denied_or_timeout"}`.

## Pairing Model

The phone does **not** enter a pairing code by hand. Instead, the pair command (the `/hermes-relay-pair` slash command or the `hermes-pair` shell shim, both running on the Hermes host) drives the whole handshake:

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
- **Voice endpoints 500 with "no API key available"** — The relay process doesn't have the right keys. The Python bootstrap loads `~/.hermes/.env` automatically, so this almost always means the key just isn't in `.env` yet. Double-check with `grep VOICE_TOOLS_OPENAI_KEY ~/.hermes/.env` (for STT) or `grep ELEVENLABS_API_KEY ~/.hermes/.env` (for TTS). If you just edited `.env`, restart the service so Python re-imports: `systemctl --user restart hermes-relay`.
- **Service starts but port bind fails** — Check for an orphan manual launch: `pgrep -f "python -m plugin.relay"`. Kill it with `pkill -f "python -m plugin.relay"` then `systemctl --user restart hermes-relay`.
- **Auth failure** — Pairing codes expire 10 minutes after registration and are one-shot. Re-run `hermes-pair` (or `/hermes-relay-pair`) to mint a fresh code and get a new QR.
- **QR has no relay block** — the pair command only embeds relay details if it can reach `localhost:RELAY_PORT/health` when it runs. Start the relay first, then re-run `hermes-pair`.
- **TLS errors** — Use `--no-ssl` for local dev. Ensure cert paths are correct for production.
- **Phone can't reach relay** — Check firewall rules for port 8767. Verify with `curl http://server-ip:8767/health` from another machine.
- **Service stops when you log out of SSH** — That's systemd's default for user services. Run `loginctl enable-linger $USER` once to fix it.

## Further Reading

- [Full relay server docs](https://github.com/Codename-11/hermes-relay/blob/main/docs/relay-server.md)
- [Architecture decisions](https://github.com/Codename-11/hermes-relay/blob/main/docs/decisions.md)
- [Specification](https://github.com/Codename-11/hermes-relay/blob/main/docs/spec.md)
