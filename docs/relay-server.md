# Relay Server

The relay server is a lightweight Python service that bridges the Hermes-Relay Android app to server-side features requiring persistent bidirectional communication.

## When You Need It

| Feature | Requires Relay Server? | Auth / protocol |
|---------|------------------------|-----------------|
| **Chat** | No | Hermes API key over HTTP/SSE direct to Hermes API Server (`:8642`) |
| **Voice** | Yes for `/voice/*` routes; relay pairing optional when an API key is present | Hermes API bearer or relay session over relay HTTP/WSS (`:8767`) |
| **Voice output broker** | Yes; default conversational voice renderer, disable with `RELAY_VOICE_OUTPUT_ENABLED=0` | Relay session or Hermes API bearer with `voice:tts` over relay HTTP/WSS (`:8767`) |
| **Realtime voice-agent lab** | Yes; provider-agent test path, disable with `RELAY_REALTIME_VOICE_ENABLED=0` | Relay session or Hermes API bearer with `voice:realtime` over relay HTTP/WSS (`:8767`) |
| **Realtime Agent voice engine** | Yes; experimental Hermes-brokered provider voice engine using `/voice/realtime-agent/*` | Relay session or Hermes API bearer with `voice:realtime` over relay HTTP/WSS (`:8767`) |
| **Terminal** | Yes | Relay session over WSS (`:8767`) |
| **Bridge** | Yes | Relay session over WSS/HTTP (`:8767`) |

If you only use chat, you do **not** need the relay server. The app rides the standard upstream Hermes surfaces — the dashboard `/api/ws` gateway (live thinking) preferred, the API server's SSE routes as fallback — for chat, sessions, profiles, and skills. Voice endpoints live on the relay but can authenticate with the same Hermes API server key used for chat; remote-control features such as terminal, bridge, TUI, media/session management, and Android control still require relay pairing.

When using the dashboard's pair/repair flow, the QR still needs both credential families: top-level `key` for direct Hermes API chat/sessions, and `relay.code` for the relay session token used by voice/bridge/terminal surfaces. The relay's loopback-only `/pairing/mint` endpoint reads `API_SERVER_KEY` from the same host-local config chain as `hermes pair` when the dashboard does not explicitly pass `api_key`.

## Architecture

```
Phone (HTTP/SSE) --> Hermes API Server (:8642)   [chat/default profile]
Phone (HTTP/SSE) --> Profile API Server (:8643+) [chat/selected Hermes profile, when advertised]
Phone (HTTP/WSS) --> Relay Server (:8767)         [voice routes, realtime voice, realtime-agent broker]
Phone (WSS/HTTP) --> Relay Server (:8767)         [terminal, bridge, media, sessions]
```

The relay runs alongside hermes-agent on the same machine. It reads `~/.hermes/config.yaml` and `~/.hermes/profiles/*/` for profile discovery metadata. Chat itself remains direct phone-to-Hermes-API traffic; the relay only advertises which base/profile API URL the phone should use.

Profile badge images can also live beside that metadata. Put `avatar.png`,
`avatar.jpg`, `profile.png`, or `profile.jpg` directly in a profile directory
(`~/.hermes/` for the root default, or `~/.hermes/profiles/<name>/`; the
synthetic default follows a valid sticky `active_profile` marker). A paired
Android client can import the image through the read-only profile avatar route;
the app stores a private copy on the phone rather than retaining a host path.

### Hermes Profile API Routing

For proper Hermes profile isolation in Android chat, each profile must run its own Hermes API server. The relay discovers profile directories and advertises safe routing metadata only; it does not start profile gateways or expose API keys.

Profile-local `.env` example:

```bash
API_SERVER_ENABLED=true
API_SERVER_HOST=0.0.0.0
API_SERVER_PORT=8645
API_SERVER_KEY=<same key as the paired Android connection>
```

Use a distinct port per running profile, then start that profile's Hermes gateway/API service with your normal Hermes service manager, for example `hermes -p mizu gateway start` or the equivalent container/supervisor entry. Set `RELAY_WEBAPI_URL` on the relay service to the phone-reachable base Hermes API URL, for example `http://192.168.1.100:8642`; this lets the relay rewrite local profile binds (`127.0.0.1`, `localhost`, `0.0.0.0`, `::1`) to that same host/scheme while preserving the profile API port. Android also defensively rewrites loopback profile URLs against the active Connection API URL so stale or host-local profile payloads do not make the phone dial its own `127.0.0.1`.

If a profile does not advertise a running API server, Android can still select it, but the behavior is compatibility fallback: the app sends that profile's `model` and `SOUL.md` as request overrides on the active Connection API server. That fallback does not isolate profile memory, sessions, tools, provider auth, or cron jobs.

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
  --allow-insecure-api-key
                     Allow API-key voice auth over plain LAN HTTP at startup
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
| `RELAY_TRUST_PROXY_HEADERS` | `0` | When `1`, trust `X-Forwarded-Proto: https` from your own reverse proxy for Hermes API bearer auth on `/voice/*`. Only enable when the proxy strips untrusted incoming forwarded headers. |
| `RELAY_ALLOW_INSECURE_API_BEARER` | `0` | Dev-only escape hatch. When `1`, allows Hermes API bearer auth on non-loopback plaintext `/voice/*` requests. Leave off for production. For a running relay, prefer `hermes relay insecure-api-key on` or the standalone `hermes-relay insecure-api-key on` shim so no restart is needed. |
| `RELAY_VOICE_OUTPUT_CONFIG` | `~/.hermes-relay/config.yaml` | Relay-owned config file for assistant speech rendering defaults under `voice_output:`. This is intentionally separate from upstream Hermes `~/.hermes/config.yaml`, which still owns legacy `stt:` and `tts:` fallback helpers. |
| `RELAY_VOICE_OUTPUT_ENABLED` | Relay config or `1` | Enables `/voice/output/*`, the default provider-neutral streaming TTS renderer used by Android voice mode. Set `0` to force the basic `/voice/synthesize` fallback path. |
| `RELAY_VOICE_OUTPUT_PROVIDER` | Relay config or `xai_tts` | Default voice renderer. Use `xai_tts` for Grok TTS, `openai_tts` for OpenAI speech, or `stub` for no-quota route testing. |
| `RELAY_VOICE_OUTPUT_MODEL` | Relay config or `xai-tts` | Renderer model label. OpenAI TTS commonly uses `gpt-4o-mini-tts`; xAI TTS does not require a model parameter but the relay reports `xai-tts` for stable UI/metrics. |
| `RELAY_VOICE_OUTPUT_VOICE` | Relay config or `eve` | Default renderer voice. xAI voices include `eve`, `ara`, `rex`, `sal`, and `leo`; OpenAI voice defaults to `coral`. |
| `RELAY_VOICE_OUTPUT_SAMPLE_RATE` | Relay config or `24000` | Output PCM sample rate sent to Android `AudioTrack`. |
| `RELAY_VOICE_OUTPUT_OPTIMIZE_LATENCY` | Relay config or `1` | Low-latency renderer toggle for providers that support it. |
| `RELAY_VOICE_OUTPUT_FALLBACK_ENABLED` | Relay config or `1` | Whether Android should fall back to legacy `/voice/synthesize` if streaming output fails before audio starts. |
| `RELAY_REALTIME_VOICE_CONFIG` | `~/.hermes-relay/config.yaml` | Relay-owned config file for realtime voice-agent defaults under `realtime_voice:`. |
| `RELAY_REALTIME_VOICE_ENABLED` | Relay config or `1` | Enables `/voice/realtime/*`, the Android realtime-agent test path. Set `0` to disable realtime sessions without affecting `/voice/output/*`. |
| `RELAY_REALTIME_VOICE_PROVIDER` | Relay config or `xai_realtime` | Default realtime provider. Realtime Agent native providers currently include `xai_realtime` and `openai_realtime`; use `stub` only for no-quota route testing. Launch env overrides the relay config file for temporary tests. |
| `RELAY_REALTIME_VOICE_MODEL` | Relay config or `grok-voice-latest` | Default realtime model advertised to Android. OpenAI Realtime commonly uses `gpt-realtime-2`; xAI uses `grok-voice-latest`. Launch env overrides the relay config file for temporary tests. |
| `RELAY_REALTIME_VOICE_VOICE` | Relay config or `eve` | Default realtime voice advertised to Android. xAI voices include `eve`, `ara`, `rex`, `sal`, and `leo`; OpenAI Realtime defaults commonly use `marin` or `cedar`. Launch env overrides the relay config file for temporary tests. |
| `RELAY_REALTIME_VOICE_RUN_DIR` | `~/.hermes-relay/realtime-voice-runs` | Server-side WAV and JSONL artifact directory for realtime lab and Realtime Agent runs. |
| `RELAY_REALTIME_VOICE_XAI_OAUTH_PATH` | `~/.hermes-relay/auth/xai-oauth.json` | Relay-owned xAI OAuth token path for SuperGrok/Premium+ realtime testing. |
| `RELAY_PROVIDER_OPTIONS_CACHE_SECONDS` | `600` | TTL for relay-owned provider option discovery. Applies to dynamic xAI/ElevenLabs option fetches so Android dropdown refreshes do not repeatedly hit provider APIs. Set `0` to disable caching during provider debugging. |
| `RELAY_MEDIA_MAX_SIZE_MB` | `100` | Per-file size cap on `POST /media/register`. Files larger than this are rejected. |
| `RELAY_MEDIA_TTL_SECONDS` | `86400` | How long a registered media entry stays valid before the registry evicts it. Matches the within-a-day scrollback use case — see ADR 14. |
| `RELAY_MEDIA_LRU_CAP` | `500` | Maximum in-memory entries in the media registry. Oldest is evicted on register-overflow. |
| `RELAY_MEDIA_ALLOWED_ROOTS` | — | Additional sandbox roots for `POST /media/register` (colon/`os.pathsep`-separated absolute paths). Extends the auto-derived defaults (`tempfile.gettempdir()` + `HERMES_WORKSPACE` or `~/.hermes/workspace/`), not replaces them. |
| `TS_AUTO` | `0` | **Install-time only** — read by `install.sh` step [7/7]. When set to `1`, the installer runs `hermes-relay-tailscale enable` non-interactively after detecting the `tailscale` binary. Useful for scripted installs. See `docs/remote-access.md`. |
| `TS_DECLINE` | `0` | **Install-time only** — read by `install.sh` step [7/7]. When set to `1`, the installer skips the Tailscale-enablement prompt even if the binary is present. Documented here for grep-ability; the relay process itself never reads this. |

## TLS / Production

For local development, `--no-ssl` is fine. For production (phone connecting over the internet):

```bash
# With Let's Encrypt
export RELAY_SSL_CERT=/etc/letsencrypt/live/yourdomain/fullchain.pem
export RELAY_SSL_KEY=/etc/letsencrypt/live/yourdomain/privkey.pem
python -m relay_server
```

Or use a reverse proxy (nginx/Caddy) to terminate TLS in front of the relay. Full decision matrix (Tailscale / Caddy / Cloudflare Tunnel / WireGuard / plaintext-over-VPN) with setup recipes lives in [`docs/remote-access.md`](remote-access.md).

## Tailscale helper

`hermes-relay-tailscale` is a thin CLI wrapper (installed at `~/.local/bin/` by `install.sh`) that fronts both loopback services with `tailscale serve`: relay `127.0.0.1:8767` and Hermes API `127.0.0.1:8642`. Both are needed for a full remote app session because chat/API-key voice need the API route, while terminal, bridge, TUI, media/session management, and relay-token fallback need the relay route. Subcommands: `hermes-relay-tailscale enable [--port N] [--api-port N] [--relay-only] [--no-https]` / `disable [--port N] [--api-port N] [--relay-only]` / `status`; each takes `--json` for scripting. All commands are safe to call when Tailscale is not installed — they return a structured failure instead of raising. The helper auto-retires once upstream PR [#9295](https://github.com/NousResearch/hermes-agent/pull/9295) merges (`canonical_upstream_present()` detects `hermes gateway run --tailscale`). See [`docs/remote-access.md`](remote-access.md) for the operator-facing flow and ADR 25 for the rationale.

## Authentication

The relay uses a QR-driven two-step auth flow:

1. **Pairing** — the pair command runs on the Hermes host (`hermes pair`, `/hermes-relay-pair`, or the compatibility `hermes-pair` shell shim), mints a fresh 6-char code (`A-Z / 0-9`), pre-registers it with the relay via the loopback-only `POST /pairing/register` endpoint, and embeds the relay URL + code in the scanned QR payload. The payload can also carry `dashboard_url` for custom Manage/dashboard routes. The same payload is printed as a paste-friendly `hermes-relay://pair?payload=...` invite URL for desktop GUI/CLI setup. The phone sends the code in its first `system/auth` envelope; the relay consumes it and issues a session token. Codes are one-shot and expire 10 minutes after registration. Android clears a failed scanned code after `auth.fail` so a stale QR cannot keep reconnecting into the rate limiter.
2. **Session token** — Stored in Android's EncryptedSharedPreferences. Used for subsequent relay connections and Relay-protected HTTP routes. Expires after 30 days by default and carries per-channel grants, including `voice:config`, `voice:stt`, `voice:tts`, and `voice:realtime`.

Voice endpoints also accept the existing Hermes API bearer token used by API-server clients such as the Obsidian Hermes Client. That API bearer path is limited to `/voice/config`, `/voice/transcribe`, `/voice/synthesize`, `/voice/output/*`, `/voice/realtime/*`, and `/voice/realtime-agent/*`; it is not accepted for sessions, media, clipboard, terminal, TUI, bridge, profile writes, or Android control routes. Android derives the conventional Relay URL from the configured API URL (`http(s)://host:8642` to `ws(s)://host:8767`) and probes the voice routes, with a manual Relay URL override for custom routing. For non-loopback callers, Hermes API bearer auth requires HTTPS by default, either direct TLS or trusted `X-Forwarded-Proto: https` from an explicitly trusted proxy.

Realtime voice defaults live in the relay-owned config file, not upstream
Hermes config:

```yaml
# ~/.hermes-relay/config.yaml
realtime_voice:
  enabled: true
  provider: xai_realtime
  model: grok-voice-latest
  voice: eve
  sample_rate: 24000
  xai_oauth_path: ~/.hermes/auth.json
```

Authenticated clients with voice grants can update safe voice defaults through
`PATCH /voice/output/config`, `PATCH /voice/realtime/config`, and
`PATCH /voice/realtime-agent/config`. Without a profile parameter those writes
go to the relay-owned config file. With `?profile=<name>` they update
experimental `voice_output:` or `realtime_voice:` sections in that profile's
`config.yaml`, so Android Voice Settings can follow the active Hermes profile.
Server-local paths such as `xai_oauth_path` remain operator-managed in the relay
config file or environment. Environment variables still win at process launch
for one-off tests. Legacy `tts:` and `stt:` remain owned by upstream Hermes
helpers; `/voice/config?profile=<name>` reads profile-local values where present
and reports `config_scope` / `fallback_to_global` for the app UI.

The voice config responses include provider option metadata for client
controls: each `providers[]` item may advertise `models`, `voices`,
`languages`, and `sample_rates`. Provider-specific option responses add
`schema_version`, grouped voice metadata (`voice_groups`), per-voice metadata
(`voice_metadata` with source/custom/recommended/manual flags), and
`model_voice_compatibility` where the provider has model-specific voice
constraints. Android uses that shape for searchable grouped dropdowns, but
still keeps an advanced manual entry path for provider IDs that are not yet
advertised by the relay.

For provider-specific refresh before saving, Android can query
`GET /voice/output/providers/{provider_id}/options?profile=<name>`,
`GET /voice/realtime/providers/{provider_id}/options?profile=<name>`, or
`GET /voice/realtime-agent/providers/{provider_id}/options?profile=<name>`.
These routes return the selected provider's safe option metadata plus current
profile/scope labels. Account-backed discovery stays server-side and is cached
by `RELAY_PROVIDER_OPTIONS_CACHE_SECONDS`; today the voice-output route can
refresh ElevenLabs voices/models/languages when the relay has an ElevenLabs API
key, and xAI output/realtime routes refresh built-in plus paginated account
custom voices from `GET /v1/tts/voices` and `GET /v1/custom-voices` when xAI
API/OAuth auth is available. OpenAI choices are advertised from the current
official voice set because OpenAI does not expose a general voice-list endpoint
for this use case. If dynamic discovery is unauthenticated or unavailable, the
relay falls back to static provider metadata and the Android UI keeps advanced
manual ID entry available.

Before writing a provider/model/voice selection, authenticated clients can call
`POST /voice/output/providers/{provider_id}/validate` or
`POST /voice/realtime/providers/{provider_id}/validate` or
`POST /voice/realtime-agent/providers/{provider_id}/validate` with `model`,
`voice`, `sample_rate`, and optional `language`. The relay validates the
selection against the same option schema and returns `valid`, `checks[]`, and
`summary`. Unknown manual IDs are warnings; advertised
model/voice/sample-rate conflicts are blocking errors.

For plain LAN phone testing against a running relay, toggle the dev escape hatch without restarting:

```bash
hermes relay insecure-api-key status
hermes relay insecure-api-key on
hermes relay insecure-api-key off

# Same operation on hosts whose Hermes CLI does not expose plugin commands:
hermes-relay insecure-api-key status
hermes-relay insecure-api-key on
hermes-relay insecure-api-key off
```

The command calls loopback-only `PATCH /relay/security` on the relay host and changes runtime state only. It does not persist across relay restarts.

Rate limiting: 5 failed auth attempts per 60 seconds triggers a 5-minute block per IP.

See [`docs/spec.md` §3.3](spec.md) for the full auth flow and the QR wire format.

## HTTP Routes

| Route | Method | Purpose |
|-------|--------|---------|
| `/ws`, `/` | GET (upgrade) | Main WebSocket endpoint. Phone connects, sends `system/auth`, then multiplexes `chat`/`terminal`/`bridge` envelopes. |
| `/health` | GET | Returns `{status, version, clients, sessions}` JSON. |
| `/pairing/register` | POST | **Loopback only.** Pre-register an externally-provided pairing code so it can appear in a QR payload before the phone scans it. Request body: `{"code": "ABCD12", "ttl_seconds": 2592000, "grants": {"terminal": 604800, "bridge": 86400}, "transport_hint": "wss"}` — `ttl_seconds` / `grants` / `transport_hint` are all optional; if omitted the SessionManager's bounded defaults are used. Client-supplied policy in the WebSocket auth envelope is never authoritative. Response: `{"ok": true, "code": "ABCD12"}`. Returns HTTP 403 for any `request.remote` other than `127.0.0.1` / `::1`. **As of ADR 15 this endpoint clears all rate-limit blocks on success** — the operator is explicitly re-pairing, stale blocks should not prevent the new code from being consumed. Used by `hermes pair` / `/hermes-relay-pair`; `hermes-pair` remains a compatibility shim. |
| `/pairing/mint` | POST | **Loopback only.** Mint a fresh pairing code and return the signed QR payload plus `pairing_url` (`hermes-relay://pair?payload=...`) used by dashboard and desktop pair/repair flows. Reads `API_SERVER_KEY` from the host-local config chain when the dashboard does not pass `api_key` explicitly. Optional request field `dashboard_url` is mirrored into the QR payload and response. |
| `/pairing/approve` | POST | **Loopback only, Phase 3 stub.** Same wire shape and loopback gate as `/pairing/register` — present so the Android client can target the route today. The semantic difference (operator reviewing a phone-initiated pending code before approval) still needs the pending-codes store + approval UX, marked `# TODO(Phase 3)` in the handler. |
| `/sessions` | GET | Bearer-auth'd. Returns `{"sessions": [ {token_prefix, device_name, device_id, created_at, last_seen, expires_at, grants, transport_hint, is_current}, ... ]}` for all currently-active paired devices. `token_prefix` is the first 8 characters of the session token — full tokens are NEVER included, so a caller holding one session token can't extract another. `expires_at` and grant values that are `math.inf` serialize as `null` (never expire). `is_current` is true for the session matching the caller's bearer. 401 on missing/invalid bearer. Used by the Android Paired Devices screen. **Loopback branch (2026-04-18):** callers on `127.0.0.1` / `::1` may skip the bearer and receive the same `{sessions: [...]}` payload without the `is_current` flag (no caller context). Added so the dashboard plugin proxy can list paired devices without needing to mint its own bearer. Non-loopback callers still require the bearer and retain `is_current`. |
| `/sessions/{token_prefix}` | DELETE | Bearer-auth'd. Revoke a paired device by first-N-char token prefix (N ≥ 4). Returns 200 `{"ok": true, "revoked_self": bool}` on exact match; 404 on zero matches; 409 on ambiguous (2+) matches with the count in the body. Self-revoke is allowed and flagged via `revoked_self: true` so the caller knows to wipe local state. Any paired device can revoke any other — see ADR 15 for the trade-off rationale. |
| `/sessions/{token_prefix}` | PATCH | Bearer-auth'd, self-targeted, and reduction-only. Body `{"ttl_seconds": 3600}`, `{"grants": {"terminal": 600}}`, or both may shorten the caller's current session policy. A bearer cannot target another session, extend its lifetime, add or lengthen grants, or change a finite expiry to never-expire; authority-increasing changes require a fresh operator-approved pairing flow. Omitted grants retain their existing absolute ceilings and are clamped if the parent session is shortened. Returns 200 with the reduced `{expires_at, grants}`; 400 on missing/invalid or unknown grants; 403 on cross-session targets or policy expansion; 404 on prefix miss; 409 on ambiguous prefix. |
| `/clipboard/inbox` | POST | Bearer-auth'd clipboard rendezvous used by remote clients before native platform clipboard fallback. |
| `/media/register` | POST | **Loopback only.** Register a file path with the in-memory `MediaRegistry` and receive an opaque token. Used by host-local tools (`android_screenshot` etc.) to make a file fetchable by the paired phone without leaking the filesystem path. Request body: `{"path": "/abs/path", "content_type": "image/jpeg", "file_name": "screenshot.jpg"}`. Response: `{"ok": true, "token": "<url-safe-16>", "expires_at": <unix>}`. Returns 403 for non-loopback callers, 400 on validation failure (relative path, missing file, oversized, outside allowed roots, etc). Path sandboxing is enforced server-side — see ADR 14. |
| `/media/upload` | POST | Bearer-auth'd small upload endpoint for phone-originated media. Accepts JSON `{file_name, content_type, content}` where `content` is base64 and registers the decoded bytes with the media registry. |
| `/media/{token}` | GET | Stream the bytes of a previously-registered file. Requires `Authorization: Bearer <session_token>` (same token the WSS channel uses; validated against `SessionManager`). Response has the registered `Content-Type` plus `Content-Disposition: inline; filename="..."` when a file name was provided. Returns 401 without auth or with an invalid bearer, 404 if the token is unknown or expired. The client never sees the underlying path — the token is the only handle. |
| `/media/by-path` | GET | Stream the bytes of a file **addressed by absolute path** rather than by registry token. Covers the case where an agent's LLM freeform-emits a `MEDIA:/abs/path.ext` marker in its response text (upstream `hermes-agent/agent/prompt_builder.py` explicitly instructs the model to do this) — no loopback register step is needed. Query parameters: `path` (required, absolute) and `content_type` (optional; otherwise guessed from extension via Python's `mimetypes`). Requires `Authorization: Bearer <session_token>`. Path sandboxing is identical to `/media/register`: must be absolute, must `realpath`-resolve under an allowed root (`tempfile.gettempdir()` + `HERMES_WORKSPACE` + `RELAY_MEDIA_ALLOWED_ROOTS`), must exist, must be a regular file, must fit under `RELAY_MEDIA_MAX_SIZE_MB`. Response carries `Content-Type` and `Content-Disposition: inline; filename="<basename>"`. Error shapes: 400 missing `path`; 401 missing/invalid bearer; 403 outside sandbox / not absolute / too large; 404 file not found or not a regular file. See ADR 14. |
| `/voice/transcribe` | POST | Bearer-auth'd via either a Relay session token with active `voice:stt` grant or a valid Hermes API bearer token. Non-loopback API-bearer calls require HTTPS unless `RELAY_ALLOW_INSECURE_API_BEARER=1`. `multipart/form-data` with an audio file field (any name — first field is used). Android may include `?profile=<name>` so the active profile context is recorded in the response/UI; execution still goes through the upstream STT helper. |
| `/voice/synthesize` | POST | Bearer-auth'd via either a Relay session token with active `voice:tts` grant or a valid Hermes API bearer token. Non-loopback API-bearer calls require HTTPS unless `RELAY_ALLOW_INSECURE_API_BEARER=1`. JSON body `{"text": "...", "profile": "mizu"}` (max 5000 chars) runs the basic fallback TTS helper and serves the resulting mp3. Normal assistant speech prefers `/voice/output/*`. |
| `/voice/config` | GET | Bearer-auth'd via either a Relay session token with active `voice:config` grant or a valid Hermes API bearer token. Optional `?profile=<name>` resolves `tts:` / `stt:` from `~/.hermes/profiles/<name>/config.yaml` where present, otherwise falls back. Returns provider info plus `profile`, `config_scope`, and `fallback_to_global`. |
| `/voice/output/config` | GET/PATCH | Default assistant speech renderer config route. Enabled by default; set `RELAY_VOICE_OUTPUT_ENABLED=0` to force legacy fallback. Requires bearer auth via `voice:tts` or valid Hermes API bearer. Optional `?profile=<name>` reads/writes experimental `voice_output:` defaults in that profile config; absent profile persists relay-owned `voice_output:` defaults. Responses include provider dropdown metadata (`models`, `voices`, `languages`, `sample_rates`) where known. |
| `/voice/output/providers/{provider_id}/options` | GET | Provider-specific option discovery for the Voice Output settings UI. Requires `voice:tts` or valid Hermes API bearer. Optional `?profile=<name>` is reflected in profile/scope metadata. Returns a single `provider` option object and `dynamic` status without exposing provider secrets. |
| `/voice/output/providers/{provider_id}/validate` | POST | Validates a pending Voice Output provider/model/voice/sample-rate/language selection before saving. Requires `voice:tts` or valid Hermes API bearer. Returns structured checks so the app can block incompatible choices while still allowing manual IDs with warnings. |
| `/voice/output/session` | POST | Default assistant speech renderer session route. Requires bearer auth via `voice:tts` or valid Hermes API bearer. Creates a short-lived server-side output session and returns `{session_id, websocket_path, provider, model, voice, sample_rate, event_log_path, profile, config_scope}`. JSON body may include `profile` plus provider/model/voice/sample-rate overrides. |
| `/voice/output/{session_id}` | GET websocket | Default voice output websocket. Requires the same auth and a session from `/voice/output/session`. Client sends `session.start`, `response.create` with final Hermes text, and `session.close`; server sends `voice.session.ready`, `voice.audio.delta`, `voice.audio.done`, `voice.response.done`, and `voice.error`. PCM deltas are mono 16-bit little-endian base64 chunks for direct Android `AudioTrack` playback. |
| `/voice/realtime/config` | GET/PATCH | Experimental realtime voice-agent config route. Enabled by default; set `RELAY_REALTIME_VOICE_ENABLED=0` to disable sessions while still allowing config reads/writes. Requires bearer auth via `voice:realtime` or valid Hermes API bearer. Optional `?profile=<name>` reads/writes experimental `realtime_voice:` defaults in that profile config; absent profile persists relay-owned defaults. Responses include provider dropdown metadata (`models`, `voices`, `sample_rates`) where known. |
| `/voice/realtime/providers/{provider_id}/options` | GET | Provider-specific option discovery for the dev-only Realtime Agent Lab settings UI. Requires `voice:realtime` or valid Hermes API bearer. Returns a single realtime-capable provider option object and current profile/scope metadata. |
| `/voice/realtime/providers/{provider_id}/validate` | POST | Validates a pending realtime provider/model/voice/sample-rate selection before saving. Requires `voice:realtime` or valid Hermes API bearer. |
| `/voice/realtime/session` | POST | Experimental realtime voice-agent session route. Requires bearer auth via `voice:realtime` or valid Hermes API bearer. Creates a short-lived server-side realtime session and returns `{session_id, websocket_path, provider, model, voice, sample_rate, event_log_path, profile, config_scope}`. JSON body may include `profile` plus provider/model/voice/sample-rate overrides. |
| `/voice/realtime/{session_id}` | GET websocket | Realtime voice-agent websocket. Requires the same auth and a session from `/voice/realtime/session`. Client sends JSON events such as `session.start`, `input_audio.append`, and `response.create`; server sends `voice.session.ready`, `voice.input_audio.received`, `voice.audio.delta`, `voice.audio.done`, `voice.response.done`, and `voice.error`. PCM deltas are mono 16-bit little-endian base64 chunks for direct Android `AudioTrack` playback in the dev/lab mode. |
| `/voice/realtime-agent/config` | GET/PATCH | Experimental Realtime Agent voice engine config route. Uses the same relay-owned/profile-scoped `realtime_voice:` settings as the lab route, but reports `mode: realtime_agent`, `stable_engine: hermes_voice_output`, `experimental: true`, the narrow Hermes tool surface, and current limits/fallback guidance. |
| `/voice/realtime-agent/providers/{provider_id}/options` | GET | Provider-specific option discovery for the Realtime Agent settings UI. Requires `voice:realtime` or valid Hermes API bearer and returns the same safe option metadata as the realtime lab route plus `tool_surface`. |
| `/voice/realtime-agent/providers/{provider_id}/validate` | POST | Validates a pending Realtime Agent provider/model/voice/sample-rate selection before save. |
| `/voice/realtime-agent/session` | POST | Creates a brokered Realtime Agent session bound to the active profile, optional Hermes chat session id, provider/model/voice/sample-rate, auth principal, and event log path. |
| `/voice/realtime-agent/{session_id}` | GET websocket | Experimental broker websocket. For provider-native xAI or OpenAI sessions, Android sends `session.start`, `input_audio.append`, `input_audio.commit` without transcript text, `playback.drained`, `response.cancel`, `hermes.confirm`, and `session.close`. The relay streams PCM to the provider, normalizes transcript/audio/function-call events, brokers only the approved Hermes functions, and sends input transcript events, Hermes session/tool/confirmation state, provider PCM as `voice.output_audio.delta`, and final `voice.response.done`. Hermes remains the owner of tools, memory, transcript persistence, Android bridge safety, confirmations, and cancellation. |
| `/bridge/activity` | GET | **Loopback only.** Returns the `BridgeHandler.recent_commands` ring buffer (max 100 entries) as `{"activity": [ {request_id, method, path, params, sent_at, response_status, result_summary, error, decision}, ... ]}` — newest first. Query param: `?limit=N` (1–500, default 100) caps the response size. `params` is redacted for any key in `{password, token, secret, otp, bearer}`; `decision` is one of `pending` / `executed` / `blocked` / `confirmed` / `timeout` / `error`. 403 for non-loopback callers. Consumed by the dashboard plugin's Bridge Activity tab. |
| Device Control routes (`/screen`, `/tap`, `/type`, and peers) | GET/POST | Require `Authorization: Bearer <session_token>` and an active `bridge` grant before any request data is forwarded to a connected Android client. Host tools supply the token through `ANDROID_BRIDGE_TOKEN`; loopback callers do not bypass this gate. |
| `/media/inspect` | GET | **Loopback only.** Returns `{"media": [ {token, file_name, content_type, size, created_at, expires_at, last_accessed, is_expired}, ... ]}` — `MediaRegistry.list_all()` snapshot, newest first. Absolute file paths are **never** included — only `file_name` (basename). Query param: `?include_expired=true` includes evicted entries (default false, hides them). 403 for non-loopback callers. Consumed by the dashboard plugin's Media Inspector tab. |
| `/relay/info` | GET | Aggregate status and capability contract. Loopback dashboard calls may omit auth; remote callers require a paired-device bearer. Returns backward-compatible `version` plus `plugin_version`, `protocol_version`, stable `capabilities`, per-profile `relay_state`, counters, and `health`. |
| `/relay/security` | GET/PATCH | **Loopback only.** Runtime security toggles for local operators, `hermes relay insecure-api-key`, and `hermes-relay insecure-api-key`. `GET` returns `{"allow_insecure_api_bearer": false, "trust_proxy_headers": false, "scope": "runtime"}`. `PATCH {"allow_insecure_api_bearer": true}` enables plain-LAN API-key voice auth immediately for the running relay; `false` disables it. This is not persisted across restarts. |

### Dashboard plugin proxy routes

The hermes-agent dashboard plugin at `plugin/dashboard/` exposes a FastAPI router mounted at `/api/plugins/hermes-relay/*` on the gateway's web server. Each route is a loopback-only pass-through to the corresponding relay HTTP route above, so the relay remains the source of truth. Implementation lives in `plugin/dashboard/plugin_api.py`.

| Dashboard route | Forwards to relay | Purpose |
|-----------------|-------------------|---------|
| `GET /api/plugins/hermes-relay/overview` | `GET /relay/info` | Aggregate status for the Relay Management tab |
| `GET /api/plugins/hermes-relay/sessions` | `GET /sessions` (loopback branch) | Paired-device list for the Paired Devices tab |
| `DELETE /api/plugins/hermes-relay/sessions/{token_prefix}` | `DELETE /sessions/{token_prefix}` | Revoke a paired device from the dashboard |
| `POST /api/plugins/hermes-relay/pairing` | `POST /pairing/mint` | Mint a fresh pairing code + return a signed QR payload |
| `GET /api/plugins/hermes-relay/bridge-activity` | `GET /bridge/activity?limit=N` | Recent bridge commands ring buffer for the Bridge Activity tab |
| `GET /api/plugins/hermes-relay/media` | `GET /media/inspect?include_expired=<bool>` | Active `MediaRegistry` tokens for the Media Inspector tab |
| `GET /api/plugins/hermes-relay/push` | — (static stub) | Push console placeholder until FCM is wired |

Errors: relay connect-error / timeout / 5xx → `502 Bad Gateway` with a human-readable `detail` pointing at `127.0.0.1:{RELAY_PORT}`. Relay 4xx passes through verbatim.

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

The relay reads the Hermes config/profile directories for agent metadata but does not modify them. Chat requests stay direct to the selected Hermes API server. Relay-owned routes handle voice, terminal, bridge, media, sessions, and profile inspection.

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
| `plugin/relay/channels/bridge.py` | Bridge WebSocket channel handler and command correlation |

Deployment assets (`relay_server/`, thin shim + ops files):

| File | Purpose |
|------|---------|
| `relay_server/__main__.py` | Legacy entrypoint — calls `load_hermes_env()` then delegates to `plugin.relay.server.main()` |
| `relay_server/Dockerfile` | Container image |
| `relay_server/hermes-relay.service` | Systemd **user** unit template — uses `%h` for home expansion so it's user-agnostic, no `EnvironmentFile=` (Python bootstrap handles env) |
| `relay_server/SKILL.md` | Hermes skill reference for self-setup |
| `relay_server/requirements.txt` | Python dependencies |
