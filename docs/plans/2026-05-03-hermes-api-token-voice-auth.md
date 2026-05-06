# Plan: Hermes API Token Auth for Relay Voice

> **Purpose.** Let clients that already authenticate to the Hermes API server, such as the Obsidian Hermes Client, use Hermes-Relay voice endpoints without going through Relay pairing. Keep Relay pairing/session tokens for phone, bridge, terminal, TUI, and other higher-risk remote-control capabilities.
>
> **Status.** Implemented in this repo on 2026-05-03. Kept for rationale and acceptance coverage.
>
> **Decision.** Relay should accept the existing Hermes API bearer token for safe chat/media-adjacent capabilities, starting with STT/TTS. This should be narrow: the API token must not become a universal Relay credential.

## Current State

- `plugin/relay/voice.py` exposes:
  - `POST /voice/transcribe`
  - `POST /voice/synthesize`
  - `GET /voice/config`
- Those routes call `require_voice_auth(request, ...)` and accept either Relay session tokens with explicit voice grants or valid Hermes API bearer tokens.
- `plugin/relay/server.py` registers the `/voice/*` routes directly against `VoiceHandler`.
- `plugin/relay/auth.py` models per-channel grants for session tokens, including explicit `voice:config`, `voice:stt`, and `voice:tts` grants with legacy-session backfill.
- `plugin/tests/test_voice_routes.py` covers Relay-session-token auth, Hermes API bearer auth, non-loopback transport guards, and negative non-voice route coverage.
- `RelayConfig.webapi_url` points at the Hermes API server, and Relay validates incoming Hermes API bearer tokens through a protected API probe before accepting them on voice routes.
- `hermes relay insecure-api-key status|on|off` toggles the running relay's loopback-only `/relay/security` runtime setting for temporary plain-LAN phone tests without restarting or exporting `RELAY_ALLOW_INSECURE_API_BEARER`.
- Android manual setup derives the Relay URL from the API URL (`http(s)://host:8642` to `ws(s)://host:8767`) and probes `/voice/config`. The manual Relay URL override is only needed when that conventional route fails.
- Android consumes failed server-issued QR codes on `auth.fail`, clears pending pair context, and lets the reconnect gate stop WSS retries. Chat+voice-only setup should use the Hermes API URL/key path; a reused relay QR still needs a fresh code.
- Android connection switching resolves auto-managed Relay URLs from the active API URL before reconnecting, so the default `http://localhost:8642`/`ws://localhost:8767` shape and LAN API-key voice setup stay aligned. Per-connection profile picks are resolved only from the active connection's advertised profile list; `null` remains server default.
- The Hermes dashboard Relay plugin ships `dist/style.css` through `manifest.css` so its management, QR, and table UI does not depend on host Tailwind discovery or inherit the dashboard shell's uppercase display typography.

## Target Contract

Support two auth paths for `/voice/*`:

1. **Relay session token**
   - Existing behavior remains valid.
   - Intended for paired phone, Quest, and other Relay-native clients.
   - Should gain explicit voice grants:
     - `voice:stt` for `/voice/transcribe`
     - `voice:tts` for `/voice/synthesize`
     - `/voice/config` may require either voice grant or a small `voice:config` grant if we decide one is worth adding.

2. **Hermes API bearer token**
   - Accepted only on safe voice routes:
     - `/voice/config`
     - `/voice/transcribe`
     - `/voice/synthesize`
   - Must not authorize:
     - bridge routes
     - terminal routes
     - TUI routes
     - session management routes
     - filesystem/media-by-path routes
     - clipboard inbox/routes
     - any remote-control or destructive capability

## Encryption Requirement

Because this sends microphone audio and can spend provider quota, API-token auth must be encrypted by default.

- Allow plaintext only for loopback callers: `127.0.0.1`, `::1`, and `localhost` equivalents where aiohttp exposes loopback as `request.remote`.
- For non-loopback callers, require an encrypted request:
  - direct TLS via `request.scheme == "https"` / secure transport, or
  - a trusted reverse-proxy signal such as `X-Forwarded-Proto: https` only when an explicit opt-in config exists, for example `RELAY_TRUST_PROXY_HEADERS=1`.
- Add a dev escape hatch only if needed, for example `RELAY_ALLOW_INSECURE_API_BEARER=1`, default `false`. Name it loudly and document that it is for local LAN testing only.
- Do not log bearer values. If diagnostics need identity, log a short hash or prefix only.

## Implementation Outline

### 1. Add a narrow voice auth helper

Prefer a new helper module over expanding ad hoc logic in `voice.py`.

Suggested shape:

```python
@dataclass(frozen=True)
class AuthPrincipal:
    kind: Literal["relay_session", "hermes_api"]
    session: Session | None = None

async def require_voice_auth(
    request: web.Request,
    capability: Literal["voice:config", "voice:stt", "voice:tts"],
) -> AuthPrincipal:
    ...
```

Flow:

1. Parse `Authorization: Bearer <token>`.
2. Try `server.sessions.get_session(token)` first.
3. If it is a Relay session, enforce the relevant voice grant once grants are added.
4. If it is not a Relay session, treat it as a possible Hermes API token.
5. Before validating an API token, enforce the encryption rule above.
6. Validate the token against `server.config.webapi_url`.
7. Return an `AuthPrincipal(kind="hermes_api")` on success.

Validation strategy:

- Best long-term option: add or use a cheap protected Hermes API endpoint such as `/api/auth/check`.
- If no such endpoint exists yet, temporarily validate by making a request to an existing protected API endpoint and discarding the body. Avoid logging response bodies because `/api/config` may include sensitive configuration.
- Cache positive validation briefly, for example 30-60 seconds keyed by `(webapi_url, token_hash)`, to avoid adding one Hermes API round trip per sentence-level TTS chunk.
- Cache negative validation cautiously or not at all. Keep existing auth-failure rate limiting for repeated bad tokens.

### 2. Wire `/voice/*` to the new helper

In `plugin/relay/voice.py`:

- Replace `_require_bearer_session(request)` calls with:
  - `await require_voice_auth(request, "voice:stt")` in `handle_transcribe`
  - `await require_voice_auth(request, "voice:tts")` in `handle_synthesize`
  - `await require_voice_auth(request, "voice:config")` in `handle_voice_config`
- Keep request validation, temp-file cleanup, TTS sanitizer behavior, and response shapes unchanged.
- Keep existing Relay-session-token tests passing.

### 3. Add explicit voice grants without breaking old sessions

In `plugin/relay/auth.py`:

- Add default grants for `voice:stt` and `voice:tts`.
- Update `Session` docs and any serialized/session-list documentation to include the new grants.
- Handle old persisted sessions that already have `grants` but lack voice keys:
  - safest compatibility path: merge missing voice grants on load using the session lifetime or the `chat` grant expiry.
  - do not require users to re-pair existing phones just because voice grants became explicit.
- If `/voice/config` gets its own `voice:config` grant, include the same migration/back-compat handling.

### 4. Keep dangerous capabilities on Relay auth only

Do not reuse the Hermes API bearer helper outside safe routes unless a later plan explicitly approves it.

Routes that must continue requiring Relay session auth, loopback auth, or existing bridge safety gates:

- `/bridge/*`
- terminal/TUI WebSocket paths
- `/sessions*`
- `/media/*` and `/media/by-path`
- `/clipboard/inbox`
- profile write endpoints
- any Android gesture/action route

## Tests

Extend or add tests around these files:

- `plugin/tests/test_voice_routes.py`
  - Relay session token still works for all `/voice/*` routes.
  - Missing bearer still returns 401.
  - Invalid Relay/API bearer still returns 401.
  - Valid Hermes API bearer can call `/voice/config`.
  - Valid Hermes API bearer can call `/voice/transcribe`.
  - Valid Hermes API bearer can call `/voice/synthesize`.
  - Non-loopback plaintext API-bearer request is rejected unless the explicit insecure-dev flag is set.
  - HTTPS or trusted-proxy API-bearer request is accepted.
  - Relay does not log token values in failure paths.

- `plugin/tests/test_session_grants.py`
  - New sessions include `voice:stt` and `voice:tts`.
  - Existing serialized sessions without voice grant keys are migrated or tolerated.
  - Expired voice grants block Relay-session-token voice access.

Suggested focused verification:

```bash
python -m unittest plugin.tests.test_voice_routes
python -m pytest plugin/tests/test_session_grants.py
```

Then run the broader Relay regression surface if the implementation touches shared auth/session code:

```bash
python -m pytest plugin/tests/test_tui_channel.py plugin/tests/test_session_grants.py plugin/tests/test_voice_routes.py
```

## Docs To Update

- `docs/relay-server.md`
- `user-docs/reference/relay-server.md`
- `docs/security.md` if it has the auth model overview
- `docs/relay-protocol.md` if it documents grants or route auth

Docs should say:

- `/voice/*` accepts either a Relay session token or a Hermes API bearer token.
- Hermes API bearer token support is limited to voice routes.
- Non-loopback API-bearer use requires TLS by default.
- Pairing remains required for phone/bridge/terminal/TUI and dangerous remote-control features.

## Acceptance Criteria

- Obsidian Hermes Client can point at Relay voice endpoints and use its existing Hermes API token for STT/TTS.
- Android/phone voice mode continues working with Relay session tokens.
- Existing paired sessions are not forced to re-pair.
- API bearer token is rejected for bridge, terminal, TUI, sessions, media, clipboard, and profile-write routes.
- Non-loopback plaintext API-bearer requests are rejected by default.
- Tests cover both auth paths and the transport guard.
- No bearer tokens are printed in logs, test output, docs examples, or error bodies.

## Agent Brief

> Implement `docs/plans/2026-05-03-hermes-api-token-voice-auth.md`. Add a narrow optional Hermes API bearer auth path for `/voice/config`, `/voice/transcribe`, and `/voice/synthesize` only. Keep Relay session auth working, add explicit voice grants with back-compat for existing sessions, enforce encrypted transport for non-loopback API-bearer use by default, and update tests/docs. Do not authorize bridge, terminal, TUI, session management, media, clipboard, profile-write, or Android control routes with the Hermes API token.
