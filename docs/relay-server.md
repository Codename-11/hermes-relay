# Relay Server

The relay server is a lightweight Python service that bridges the Hermes-Relay Android app to server-side features requiring persistent bidirectional communication.

## When You Need It

| Feature | Requires Relay? | Protocol |
|---------|----------------|----------|
| **Chat** | No | HTTP/SSE direct to Hermes API Server (`:8642`) |
| **Terminal** | Yes (Phase 2) | WSS via relay (`:8767`) |
| **Bridge** | Yes (Phase 3) | WSS via relay (`:8767`) |

If you only use chat, you do **not** need the relay server. The app connects directly to the Hermes API Server for chat, sessions, profiles, and skills.

## Architecture

```
Phone (HTTP/SSE) --> Hermes API Server (:8642)   [chat]
Phone (WSS)      --> Relay Server (:8767)         [terminal, bridge]
```

The relay runs alongside hermes-agent on the same machine. It reads `~/.hermes/config.yaml` for agent profiles and proxies to the API server at `localhost:8642` by default.

## Quick Start

### Installed via `install.sh` (recommended)

The canonical installer — `install.sh` — drops a systemd **user** unit at `~/.config/systemd/user/hermes-relay.service` and enables it automatically on any host with a systemd user session (Linux desktops, most servers). After install, the relay is running and will auto-restart on failure and resume on login:

```bash
systemctl --user status hermes-relay
systemctl --user restart hermes-relay
journalctl --user -u hermes-relay -f
```

To keep the relay running after you log out of SSH:

```bash
loginctl enable-linger $USER
```

Want to skip the systemd step? `HERMES_RELAY_NO_SYSTEMD=1 ./install.sh` (or equivalent for the one-liner) leaves the unit off disk and you manage the process yourself.

### Running manually (dev / non-systemd hosts)

```bash
# Canonical entry point
python -m plugin.relay --no-ssl

# Legacy entrypoint — thin compat shim, still works
python -m relay_server --no-ssl
```

The canonical implementation lives at `plugin/relay/`. The top-level `relay_server/` package is a thin shim that delegates to `plugin.relay.server.main()` so existing docs, scripts, and systemd units keep working.

### `.env` auto-loading

Both entry points call `plugin/relay/_env_bootstrap.py::load_hermes_env()` **before** any import that reads `os.environ`, so API keys from `~/.hermes/.env` (`VOICE_TOOLS_OPENAI_KEY`, `ELEVENLABS_API_KEY`, `ANTHROPIC_API_KEY`, etc.) are loaded regardless of how the process was started. This mirrors how `hermes_cli/main.py` bootstraps env for the gateway — it's why neither the gateway unit nor `hermes-relay.service` carries an `EnvironmentFile=` directive. Any future `systemctl --user restart hermes-relay` (or `pkill` + manual relaunch) picks up fresh values from `.env` without needing the shell to `source` anything first.

Precedence (via `hermes_cli.env_loader.load_hermes_dotenv` when importable, falling back to `python-dotenv`):

1. `$HERMES_HOME/.env` (defaults to `~/.hermes/.env`) — `override=True`, so this beats any stale shell exports
2. No fallback beyond that — the relay is always installed alongside hermes-agent, so one env file is the source of truth

If neither helper is installed (stripped-down containers), the bootstrap no-ops silently — operators can still provide env via `docker run -e ...` or whatever their orchestrator uses.

### Docker

```bash
docker build -t hermes-relay relay_server/
docker run -d --name hermes-relay --network host \
  -v ~/.hermes:/home/relay/.hermes:ro \
  hermes-relay
```

The container mounts `~/.hermes` read-only so the in-process `.env` bootstrap can find `.env` at its canonical path inside the container.

### Systemd (manual install, non-`install.sh` hosts)

The `install.sh` step [6/6] handles this automatically, but if you're installing from a non-standard layout or want to skip the installer, the unit template is at `relay_server/hermes-relay.service`:

```bash
mkdir -p ~/.config/systemd/user
cp relay_server/hermes-relay.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now hermes-relay.service
loginctl enable-linger $USER   # survive logout (optional)
```

The template uses systemd's `%h` specifier for the user's home, so it's user-agnostic — no hand-editing required when the paths match the default install (`~/.hermes/hermes-relay`, `~/.hermes/hermes-agent/venv`). Edit only if your hermes-agent venv lives somewhere non-standard.

Check status:

```bash
systemctl --user status hermes-relay
journalctl --user -u hermes-relay -f
```

## CLI Options

```
python -m relay_server [OPTIONS]

  --port PORT        Listen port (default: 8767)
  --no-ssl           Disable TLS requirement (dev/localhost only)
  --log-level LEVEL  DEBUG, INFO, WARNING, ERROR (default: INFO)
  --config PATH      Path to hermes config.yaml
```

## Environment Variables

All settings can be configured via environment variables. These override CLI defaults.

| Variable | Default | Description |
|----------|---------|-------------|
| `RELAY_HOST` | `0.0.0.0` | Bind address |
| `RELAY_PORT` | `8767` | Listen port |
| `RELAY_SSL_CERT` | — | TLS certificate path |
| `RELAY_SSL_KEY` | — | TLS private key path |
| `RELAY_WEBAPI_URL` | `http://localhost:8642` | Hermes API Server base URL |
| `RELAY_HERMES_CONFIG` | `~/.hermes/config.yaml` | Hermes config path (for profile loading) |
| `RELAY_LOG_LEVEL` | `INFO` | Python logging level |
| `RELAY_MEDIA_MAX_SIZE_MB` | `100` | Per-file size cap on `POST /media/register`. Files larger than this are rejected. |
| `RELAY_MEDIA_TTL_SECONDS` | `86400` | How long a registered media entry stays valid before the registry evicts it. Matches the within-a-day scrollback use case — see ADR 14. |
| `RELAY_MEDIA_LRU_CAP` | `500` | Maximum in-memory entries in the media registry. Oldest is evicted on register-overflow. |
| `RELAY_MEDIA_ALLOWED_ROOTS` | — | Additional sandbox roots for `POST /media/register` (colon/`os.pathsep`-separated absolute paths). Extends the auto-derived defaults (`tempfile.gettempdir()` + `HERMES_WORKSPACE` or `~/.hermes/workspace/`), not replaces them. |

## TLS / Production

For local development, `--no-ssl` is fine. For production (phone connecting over the internet):

```bash
# With Let's Encrypt
export RELAY_SSL_CERT=/etc/letsencrypt/live/yourdomain/fullchain.pem
export RELAY_SSL_KEY=/etc/letsencrypt/live/yourdomain/privkey.pem
python -m relay_server
```

Or use a reverse proxy (nginx/Caddy) to terminate TLS in front of the relay.

## Authentication

The relay uses a QR-driven two-step auth flow:

1. **Pairing** — the pair command runs on the Hermes host (either the `/hermes-relay-pair` slash command invoked from any Hermes chat surface, or the `hermes-pair` shell shim), mints a fresh 6-char code (`A-Z / 0-9`), pre-registers it with the relay via the loopback-only `POST /pairing/register` endpoint, and embeds the relay URL + code in the scanned QR payload. The phone sends the code in its first `system/auth` envelope; the relay consumes it and issues a session token. Codes are one-shot and expire 10 minutes after registration.
2. **Session token** — Stored in Android's EncryptedSharedPreferences. Used for all subsequent connections. Expires after 30 days.

Rate limiting: 5 failed auth attempts per 60 seconds triggers a 5-minute block per IP.

See [`docs/spec.md` §3.3](spec.md) for the full auth flow and the QR wire format.

## HTTP Routes

| Route | Method | Purpose |
|-------|--------|---------|
| `/ws`, `/` | GET (upgrade) | Main WebSocket endpoint. Phone connects, sends `system/auth`, then multiplexes `chat`/`terminal`/`bridge` envelopes. |
| `/health` | GET | Returns `{status, version, clients, sessions}` JSON. |
| `/pairing` | POST | Generate a new relay-side pairing code. Returns `{"code": "ABC123"}`. Unrestricted (intended for host-local callers). |
| `/pairing/register` | POST | **Loopback only.** Pre-register an externally-provided pairing code so it can appear in a QR payload before the phone scans it. Request body: `{"code": "ABCD12", "ttl_seconds": 2592000, "grants": {"terminal": 604800, "bridge": 86400}, "transport_hint": "wss"}` — `ttl_seconds` / `grants` / `transport_hint` are all optional; if omitted the phone's chosen values (or the SessionManager defaults) are used. Response: `{"ok": true, "code": "ABCD12"}`. Returns HTTP 403 for any `request.remote` other than `127.0.0.1` / `::1`. **As of ADR 15 this endpoint clears all rate-limit blocks on success** — the operator is explicitly re-pairing, stale blocks should not prevent the new code from being consumed. Used by `/hermes-relay-pair` / `hermes-pair`. |
| `/pairing/approve` | POST | **Loopback only, Phase 3 stub.** Same wire shape and loopback gate as `/pairing/register` — present so the Android client can target the route today. The semantic difference (operator reviewing a phone-initiated pending code before approval) still needs the pending-codes store + approval UX, marked `# TODO(Phase 3)` in the handler. |
| `/sessions` | GET | Bearer-auth'd. Returns `{"sessions": [ {token_prefix, device_name, device_id, created_at, last_seen, expires_at, grants, transport_hint, is_current}, ... ]}` for all currently-active paired devices. `token_prefix` is the first 8 characters of the session token — full tokens are NEVER included, so a caller holding one session token can't extract another. `expires_at` and grant values that are `math.inf` serialize as `null` (never expire). `is_current` is true for the session matching the caller's bearer. 401 on missing/invalid bearer. Used by the Android Paired Devices screen. **Loopback branch (2026-04-18):** callers on `127.0.0.1` / `::1` may skip the bearer and receive the same `{sessions: [...]}` payload without the `is_current` flag (no caller context). Added so the dashboard plugin proxy can list paired devices without needing to mint its own bearer. Non-loopback callers still require the bearer and retain `is_current`. |
| `/sessions/{token_prefix}` | DELETE | Bearer-auth'd. Revoke a paired device by first-N-char token prefix (N ≥ 4). Returns 200 `{"ok": true, "revoked_self": bool}` on exact match; 404 on zero matches; 409 on ambiguous (2+) matches with the count in the body. Self-revoke is allowed and flagged via `revoked_self: true` so the caller knows to wipe local state. Any paired device can revoke any other — see ADR 15 for the trade-off rationale. |
| `/sessions/{token_prefix}` | PATCH | Bearer-auth'd. Update a paired device's session TTL and/or per-channel grants in place. Body: `{"ttl_seconds": 2592000}` (extend only) or `{"grants": {"terminal": 604800}}` (grants only) or both. `ttl_seconds = 0` means never-expire. **Semantics: TTL restarts the clock from now** — "extend by 30 days" = "30 days from now", not "old expiry + 30 days". If `grants` is omitted but `ttl_seconds` is provided and shorter than the existing expiry, existing grants are automatically clamped to the new session lifetime (no grant outlives its session). Returns 200 with the updated `{expires_at, grants}`; 400 on missing/invalid body; 404 on prefix miss; 409 on ambiguous prefix. Backs the Android Paired Devices "Extend" button. |
| `/media/register` | POST | **Loopback only.** Register a file path with the in-memory `MediaRegistry` and receive an opaque token. Used by host-local tools (`android_screenshot` etc.) to make a file fetchable by the paired phone without leaking the filesystem path. Request body: `{"path": "/abs/path", "content_type": "image/jpeg", "file_name": "screenshot.jpg"}`. Response: `{"ok": true, "token": "<url-safe-16>", "expires_at": <unix>}`. Returns 403 for non-loopback callers, 400 on validation failure (relative path, missing file, oversized, outside allowed roots, etc). Path sandboxing is enforced server-side — see ADR 14. |
| `/media/{token}` | GET | Stream the bytes of a previously-registered file. Requires `Authorization: Bearer <session_token>` (same token the WSS channel uses; validated against `SessionManager`). Response has the registered `Content-Type` plus `Content-Disposition: inline; filename="..."` when a file name was provided. Returns 401 without auth or with an invalid bearer, 404 if the token is unknown or expired. The client never sees the underlying path — the token is the only handle. |
| `/media/by-path` | GET | Stream the bytes of a file **addressed by absolute path** rather than by registry token. Covers the case where an agent's LLM freeform-emits a `MEDIA:/abs/path.ext` marker in its response text (upstream `hermes-agent/agent/prompt_builder.py` explicitly instructs the model to do this) — no loopback register step is needed. Query parameters: `path` (required, absolute) and `content_type` (optional; otherwise guessed from extension via Python's `mimetypes`). Requires `Authorization: Bearer <session_token>`. Path sandboxing is identical to `/media/register`: must be absolute, must `realpath`-resolve under an allowed root (`tempfile.gettempdir()` + `HERMES_WORKSPACE` + `RELAY_MEDIA_ALLOWED_ROOTS`), must exist, must be a regular file, must fit under `RELAY_MEDIA_MAX_SIZE_MB`. Response carries `Content-Type` and `Content-Disposition: inline; filename="<basename>"`. Error shapes: 400 missing `path`; 401 missing/invalid bearer; 403 outside sandbox / not absolute / too large; 404 file not found or not a regular file. See ADR 14. |
| `/voice/transcribe` | POST | Bearer-auth'd. `multipart/form-data` with an audio file field (any name — first field is used). Relay saves the upload to a `tempfile.NamedTemporaryFile`, calls `tools.transcription_tools.transcribe_audio(path)` (sync, wrapped in `asyncio.to_thread`), unlinks the temp file, and returns `{"text": "...", "provider": "openai", "success": true}`. 500 on STT failure with the provider's error in the body. Provider is read from `stt:` in `~/.hermes/config.yaml` — the phone doesn't pass a provider name. See the Voice Mode ADR for why this lives on the relay and not upstream. |
| `/voice/synthesize` | POST | Bearer-auth'd. JSON body `{"text": "..."}` (max 5000 chars — longer text gets a 400). Relay calls `tools.tts_tool.text_to_speech_tool(text)` (sync, wrapped in `asyncio.to_thread`), parses the JSON string it returns to get the file path, and serves the file via `web.FileResponse` with `Content-Type: audio/mpeg`. The TTS file is written by the upstream tool to `~/voice-memos/tts_<timestamp>.mp3` — cleanup of old files is the tool's concern (TODO: add LRU cap if this dir grows unbounded). Provider is read from `tts:` in `~/.hermes/config.yaml`. 500 on synthesis failure. **Streaming is client-side** — the phone detects sentence boundaries in the chat SSE stream and POSTs one sentence at a time so playback starts within a sentence of the agent replying. |
| `/voice/config` | GET | Bearer-auth'd. Returns `{"tts": {"provider": "...", "voice": "...", "model": "..."}, "stt": {"provider": "...", "enabled": true, "language": "..."}, "requirements": {...}}` describing what the relay's hermes-agent venv has configured. Reads the private upstream helpers `_load_tts_config()` / `_load_stt_config()` plus `tools.voice_mode.check_voice_requirements()` — marked as private/unstable in the handler. Used by the Android Voice Settings screen to show provider info. |
| `/bridge/activity` | GET | **Loopback only.** Returns the `BridgeHandler.recent_commands` ring buffer (max 100 entries) as `{"activity": [ {request_id, method, path, params, sent_at, response_status, result_summary, error, decision}, ... ]}` — newest first. Query param: `?limit=N` (1–500, default 100) caps the response size. `params` is redacted for any key in `{password, token, secret, otp, bearer}`; `decision` is one of `pending` / `executed` / `blocked` / `confirmed` / `timeout` / `error`. 403 for non-loopback callers. Consumed by the dashboard plugin's Bridge Activity tab. |
| `/media/inspect` | GET | **Loopback only.** Returns `{"media": [ {token, file_name, content_type, size, created_at, expires_at, last_accessed, is_expired}, ... ]}` — `MediaRegistry.list_all()` snapshot, newest first. Absolute file paths are **never** included — only `file_name` (basename). Query param: `?include_expired=true` includes evicted entries (default false, hides them). 403 for non-loopback callers. Consumed by the dashboard plugin's Media Inspector tab. |
| `/relay/info` | GET | **Loopback only.** Aggregate status for the dashboard plugin's Relay Management tab — one call instead of three. Returns `{"version": "0.5.0", "uptime_seconds": 12345, "session_count": 1, "paired_device_count": 1, "pending_commands": 0, "media_entry_count": 7, "health": "ok"}`. 403 for non-loopback callers. |

## Health Check

```bash
curl http://localhost:8767/health
```

Returns JSON with server status and version.

## Channel Protocol

All messages use typed envelopes over a single WebSocket connection at `/ws`:

```json
{
  "channel": "terminal" | "bridge" | "system",
  "type": "<event_type>",
  "id": "<uuid>",
  "payload": { ... }
}
```

### Terminal (Phase 2)

| Type | Direction | Payload |
|------|-----------|---------|
| `terminal.attach` | App --> Server | `{ session_name?, cols, rows }` |
| `terminal.attached` | Server --> App | `{ session_name, pid }` |
| `terminal.input` | App --> Server | `{ data }` |
| `terminal.output` | Server --> App | `{ data }` |
| `terminal.resize` | App --> Server | `{ cols, rows }` |
| `terminal.detach` | App --> Server | `{ session_name? }` — preserves tmux session |
| `terminal.kill` | App --> Server | `{ session_name? }` — destroys tmux session |

### Bridge (Phase 3)

| Type | Direction | Payload |
|------|-----------|---------|
| `bridge.command` | Server --> App | `{ request_id, method, path, params?, body? }` |
| `bridge.response` | App --> Server | `{ request_id, status, result }` |
| `bridge.status` | App --> Server | `{ accessibility_enabled, overlay_enabled, battery }` |

## Relationship to Hermes Agent

The relay server is **separate from hermes-agent**. The hermes-agent plugin system (`register_tool`, `register_hook`) cannot add HTTP/WebSocket endpoints to the gateway — it only registers agent tools and lifecycle hooks. The relay fills this gap by providing a dedicated WSS server for features that need persistent bidirectional communication.

The relay reads the hermes config file for agent profiles but does not modify it. It proxies chat requests to the API server but handles terminal and bridge sessions directly.

## Files

Canonical implementation (`plugin/relay/`):

| File | Purpose |
|------|---------|
| `plugin/relay/__main__.py` | Entry point (`python -m plugin.relay`) — calls `load_hermes_env()` before importing the server module |
| `plugin/relay/_env_bootstrap.py` | `load_hermes_env()` — prefers `hermes_cli.env_loader.load_hermes_dotenv`, falls back to `python-dotenv`, silent no-op in stripped containers |
| `plugin/relay/server.py` | Main WSS server, HTTP route registration, auth flow, `/pairing/register` handler |
| `plugin/relay/config.py` | `RelayConfig`, `PAIRING_ALPHABET` (full `A-Z / 0-9`), env var loading |
| `plugin/relay/auth.py` | `PairingManager`, `SessionManager`, `RateLimiter` |
| `plugin/relay/channels/chat.py` | Chat handler (proxies to API server) |
| `plugin/relay/channels/terminal.py` | Terminal handler (PTY-backed, Phase 2) |
| `plugin/relay/channels/bridge.py` | Bridge handler (Phase 3 — stub) |

Deployment assets (`relay_server/`, thin shim + ops files):

| File | Purpose |
|------|---------|
| `relay_server/__main__.py` | Legacy entrypoint — calls `load_hermes_env()` then delegates to `plugin.relay.server.main()` |
| `relay_server/Dockerfile` | Container image |
| `relay_server/hermes-relay.service` | Systemd **user** unit template — uses `%h` for home expansion so it's user-agnostic, no `EnvironmentFile=` (Python bootstrap handles env) |
| `relay_server/SKILL.md` | Hermes skill reference for self-setup |
| `relay_server/requirements.txt` | Python dependencies |
