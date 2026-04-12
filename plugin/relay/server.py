"""Relay Server — Main WSS server.

Serves a single WebSocket endpoint at ``/ws`` and a health check at ``/health``.
Phone connects, authenticates with a pairing code or session token, then sends
typed envelope messages that get routed to the appropriate channel handler.

This is the canonical implementation since the plugin consolidation; the
top-level ``relay_server`` package is a thin shim that delegates here.

Usage:
    python -m plugin.relay
    python -m plugin.relay --port 8767 --no-ssl
    python -m plugin.relay --config /path/to/config.yaml

    # Legacy entrypoints still work via the shim:
    python -m relay_server
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import math
import mimetypes
import os
import signal
import ssl
import sys
import time
import uuid
from typing import Any

import aiohttp
from aiohttp import web

from . import __version__
from .auth import (
    PairingManager,
    RateLimiter,
    Session,
    SessionManager,
)
from .channels.bridge import BridgeHandler
from .channels.chat import ChatHandler
from .channels.terminal import TerminalHandler
from .config import RelayConfig
from .media import MediaRegistrationError, MediaRegistry, validate_media_path
from .voice import VoiceHandler

logger = logging.getLogger("hermes_relay")


# ── Server state ─────────────────────────────────────────────────────────────


class RelayServer:
    """Holds all mutable state for the running relay."""

    def __init__(self, config: RelayConfig) -> None:
        self.config = config

        # Auth
        self.pairing = PairingManager()
        self.sessions = SessionManager()
        self.rate_limiter = RateLimiter()

        # Media registry — inbound media from tools (screenshots, etc.)
        self.media = MediaRegistry(
            max_entries=config.media_lru_cap,
            ttl_seconds=config.media_ttl_seconds,
            max_size_bytes=config.media_max_size_mb * 1024 * 1024,
            allowed_roots=config.media_allowed_roots or None,
            strict_sandbox=config.media_strict_sandbox,
        )

        # Voice handler — TTS / STT bridge to the hermes-agent venv tools
        self.voice = VoiceHandler(config)

        # Channel handlers
        self.chat = ChatHandler(webapi_url=config.webapi_url)
        self.terminal = TerminalHandler(default_shell=config.terminal_shell)
        self.bridge = BridgeHandler()

        # Connected clients: ws → session token
        self._clients: dict[web.WebSocketResponse, str] = {}

        # In-flight tasks per client (for cancellation on disconnect)
        self._client_tasks: dict[web.WebSocketResponse, set[asyncio.Task[Any]]] = {}

    @property
    def client_count(self) -> int:
        return len(self._clients)

    async def close(self) -> None:
        """Shut down all channel handlers and close client connections."""
        # Cancel all in-flight tasks
        for ws, tasks in self._client_tasks.items():
            for task in tasks:
                task.cancel()

        # Close channel handlers
        await self.chat.close()
        await self.terminal.close()
        await self.bridge.close()

        # Close all WebSocket connections
        for ws in list(self._clients):
            if not ws.closed:
                await ws.close(
                    code=aiohttp.WSCloseCode.GOING_AWAY,
                    message=b"server shutting down",
                )

        self._clients.clear()
        self._client_tasks.clear()
        logger.info("Relay server shut down — all connections closed")


# ── HTTP handlers ────────────────────────────────────────────────────────────


async def handle_health(request: web.Request) -> web.Response:
    """Health check endpoint."""
    server: RelayServer = request.app["server"]
    return web.json_response(
        {
            "status": "ok",
            "version": __version__,
            "clients": server.client_count,
            "sessions": server.sessions.active_count(),
        }
    )


async def handle_pairing(request: web.Request) -> web.Response:
    """Generate a new pairing code (for use by the Hermes agent/CLI).

    POST /pairing → {"code": "ABC123"}
    """
    server: RelayServer = request.app["server"]
    code = server.pairing.generate_code()
    return web.json_response({"code": code})


async def handle_pairing_register(request: web.Request) -> web.Response:
    """Pre-register an externally-provided pairing code.

    Used by ``hermes pair`` to inject a code that will appear in a QR payload
    before the phone scans it. Gated to loopback callers only — only a process
    running on the same host can register codes, which matches the trust
    model: the operator has host access and is the source of truth for
    who gets to connect.

    POST /pairing/register {"code": "ABCD12"} → {"ok": true, "code": "ABCD12"}
    """
    remote = request.remote or ""
    # aiohttp gives us the peer IP as a string. The loopback check covers
    # both IPv4 and IPv6 loopback addresses.
    if remote not in ("127.0.0.1", "::1"):
        logger.warning(
            "Rejected /pairing/register from non-loopback peer %s", remote
        )
        raise web.HTTPForbidden(
            text="/pairing/register is restricted to localhost callers",
        )

    try:
        payload = await request.json()
    except (json.JSONDecodeError, ValueError):
        return web.json_response(
            {"ok": False, "error": "invalid JSON body"}, status=400
        )

    if not isinstance(payload, dict):
        return web.json_response(
            {"ok": False, "error": "body must be a JSON object"}, status=400
        )

    code = (payload.get("code") or "").strip()
    if not code:
        return web.json_response(
            {"ok": False, "error": "missing 'code' field"}, status=400
        )

    ttl_seconds, grants, transport_hint, err = _parse_pairing_metadata(payload)
    if err is not None:
        return web.json_response({"ok": False, "error": err}, status=400)

    server: RelayServer = request.app["server"]
    if not server.pairing.register_code(
        code,
        ttl_seconds=ttl_seconds,
        grants=grants,
        transport_hint=transport_hint,
    ):
        return web.json_response(
            {
                "ok": False,
                "error": "invalid code format (must be 6 chars from A-Z / 0-9)",
            },
            status=400,
        )

    # Successful loopback pair — clear any stale rate-limit state. The
    # operator is explicitly re-pairing, so a phone that tripped the
    # auth block against the prior token should be able to connect
    # immediately with the new code.
    server.rate_limiter.clear_all_blocks()

    logger.info("Pre-registered pairing code via /pairing/register: %s", code.upper())
    return web.json_response({"ok": True, "code": code.upper()})


def _parse_pairing_metadata(
    payload: dict[str, Any],
) -> tuple[float | None, dict[str, float] | None, str | None, str | None]:
    """Extract ``ttl_seconds`` / ``grants`` / ``transport_hint`` from a
    ``/pairing/register`` or ``/pairing/approve`` JSON body.

    Returns a 4-tuple ``(ttl_seconds, grants, transport_hint, error)`` where
    ``error`` is ``None`` on success or a human-readable string on
    validation failure. Missing fields are reported as ``None``, which
    the auth layer interprets as "use defaults".
    """
    ttl_seconds: float | None = None
    raw_ttl = payload.get("ttl_seconds")
    if raw_ttl is not None:
        if not isinstance(raw_ttl, (int, float)) or isinstance(raw_ttl, bool):
            return None, None, None, "ttl_seconds must be a number"
        if raw_ttl < 0:
            return None, None, None, "ttl_seconds must be >= 0 (0 = never)"
        ttl_seconds = float(raw_ttl)

    grants: dict[str, float] | None = None
    raw_grants = payload.get("grants")
    if raw_grants is not None:
        if not isinstance(raw_grants, dict):
            return None, None, None, "grants must be an object"
        cleaned: dict[str, float] = {}
        for channel, value in raw_grants.items():
            if not isinstance(channel, str):
                return None, None, None, "grants keys must be strings"
            if not isinstance(value, (int, float)) or isinstance(value, bool):
                return None, None, None, f"grants['{channel}'] must be a number"
            if value < 0:
                return None, None, None, f"grants['{channel}'] must be >= 0"
            cleaned[channel] = float(value)
        grants = cleaned

    transport_hint: str | None = None
    raw_hint = payload.get("transport_hint")
    if raw_hint is not None:
        if not isinstance(raw_hint, str):
            return None, None, None, "transport_hint must be a string"
        if raw_hint not in ("wss", "ws", "unknown"):
            return None, None, None, "transport_hint must be wss/ws/unknown"
        transport_hint = raw_hint

    return ttl_seconds, grants, transport_hint, None


async def handle_pairing_approve(request: web.Request) -> web.Response:
    """Approve a phone-initiated pairing code (Phase 3 bidirectional pairing).

    This is the inverse direction of ``/pairing/register``: instead of the
    host generating a code and the phone consuming it, the phone generates
    a code and the operator running on the host approves it. The full
    Phase 3 UX requires a "pending codes" store so operators can list and
    approve codes the phone has already broadcast; for now we just mint
    the code into the pairing manager and return ok — the phone can then
    send an auth envelope with the same code, exactly as the existing
    host-initiated flow.

    # TODO(Phase 3): swap the naive register-through with a real pending
    # queue so the operator reviews a human-readable request ("Galaxy S25
    # wants to bridge — approve?") rather than accepting any code that
    # comes in over loopback.

    POST /pairing/approve {"code": "ABC123", "ttl_seconds": ..., "grants": {...}}
      → 200 {"ok": true, "code": "ABC123"}
      → 400 invalid payload
      → 403 non-loopback caller
    """
    remote = request.remote or ""
    if remote not in ("127.0.0.1", "::1"):
        logger.warning(
            "Rejected /pairing/approve from non-loopback peer %s", remote
        )
        raise web.HTTPForbidden(
            text="/pairing/approve is restricted to localhost callers",
        )

    try:
        payload = await request.json()
    except (json.JSONDecodeError, ValueError):
        return web.json_response(
            {"ok": False, "error": "invalid JSON body"}, status=400
        )

    if not isinstance(payload, dict):
        return web.json_response(
            {"ok": False, "error": "body must be a JSON object"}, status=400
        )

    code = (payload.get("code") or "").strip()
    if not code:
        return web.json_response(
            {"ok": False, "error": "missing 'code' field"}, status=400
        )

    ttl_seconds, grants, transport_hint, err = _parse_pairing_metadata(payload)
    if err is not None:
        return web.json_response({"ok": False, "error": err}, status=400)

    server: RelayServer = request.app["server"]
    if not server.pairing.register_code(
        code,
        ttl_seconds=ttl_seconds,
        grants=grants,
        transport_hint=transport_hint,
    ):
        return web.json_response(
            {
                "ok": False,
                "error": "invalid code format (must be 6 chars from A-Z / 0-9)",
            },
            status=400,
        )

    server.rate_limiter.clear_all_blocks()
    logger.info(
        "Approved phone-initiated pairing code via /pairing/approve: %s",
        code.upper(),
    )
    return web.json_response({"ok": True, "code": code.upper()})


# ── Sessions handlers ───────────────────────────────────────────────────────


def _session_to_dict(session: Session, current_token: str | None) -> dict[str, Any]:
    """Serialize a :class:`Session` for the ``GET /sessions`` response.

    ``math.inf`` expiries serialize as JSON ``null`` (phones render null
    as "never expires"). The full token value is NEVER included — only
    ``token_prefix`` (first 8 chars) — so a phone holding auth for its
    own session cannot exfiltrate another phone's credentials.
    """
    def _norm(ts: float) -> float | None:
        return None if math.isinf(ts) else ts

    grants_out = {k: _norm(v) for k, v in session.grants.items()}
    return {
        "token_prefix": session.token[:8],
        "device_name": session.device_name,
        "device_id": session.device_id,
        "created_at": session.created_at,
        "first_seen": session.first_seen,
        "last_seen": session.last_seen,
        "expires_at": _norm(session.expires_at),
        "grants": grants_out,
        "transport_hint": session.transport_hint,
        "is_current": current_token is not None and session.token == current_token,
    }


def _require_bearer_session(
    request: web.Request,
) -> tuple[RelayServer, Session]:
    """Validate ``Authorization: Bearer <token>`` and return the session.

    Raises :class:`aiohttp.web.HTTPUnauthorized` on any failure. Callers
    get back the :class:`RelayServer` instance for convenience.
    """
    auth_header = request.headers.get("Authorization", "")
    if not auth_header.startswith("Bearer "):
        raise web.HTTPUnauthorized(
            text="Authorization: Bearer <session_token> required",
        )
    bearer = auth_header[len("Bearer ") :].strip()
    if not bearer:
        raise web.HTTPUnauthorized(text="empty bearer token")

    server: RelayServer = request.app["server"]
    session = server.sessions.get_session(bearer)
    if session is None:
        logger.info(
            "Sessions API: rejected invalid bearer from %s",
            request.remote or "unknown",
        )
        raise web.HTTPUnauthorized(text="invalid or expired session token")
    return server, session


async def handle_sessions_list(request: web.Request) -> web.Response:
    """List all paired devices.

    GET /sessions
      → 200 {"sessions": [...]}
      → 401 missing/invalid bearer
    """
    server, current_session = _require_bearer_session(request)
    entries = [
        _session_to_dict(s, current_session.token)
        for s in server.sessions.list_sessions()
    ]
    # Stable ordering: most-recent-first.
    entries.sort(key=lambda e: e["last_seen"], reverse=True)
    return web.json_response({"sessions": entries})


async def handle_sessions_revoke(request: web.Request) -> web.Response:
    """Revoke a paired device by token prefix.

    DELETE /sessions/{token_prefix}
      → 200 {"ok": true, "revoked": "abc12345"}
      → 401 missing/invalid bearer
      → 404 no match
      → 409 multiple matches — caller should retry with more chars
    """
    server, current_session = _require_bearer_session(request)
    prefix = request.match_info.get("token_prefix", "").strip()
    if not prefix:
        return web.json_response(
            {"ok": False, "error": "missing token_prefix"}, status=400
        )

    matches = server.sessions.find_by_prefix(prefix)
    if len(matches) == 0:
        raise web.HTTPNotFound(text=f"no session matches prefix {prefix!r}")
    if len(matches) > 1:
        return web.json_response(
            {
                "ok": False,
                "error": (
                    f"{len(matches)} sessions match prefix {prefix!r} — "
                    "retry with more characters"
                ),
            },
            status=409,
        )

    target = matches[0]
    revoked_self = target.token == current_session.token
    server.sessions.revoke_session(target.token)
    logger.info(
        "Revoked session %s... (%s)%s",
        target.token[:8],
        target.device_name,
        " [self]" if revoked_self else "",
    )
    return web.json_response(
        {
            "ok": True,
            "revoked": target.token[:8],
            "revoked_self": revoked_self,
        }
    )


async def handle_sessions_extend(request: web.Request) -> web.Response:
    """Update a paired device's session TTL and/or per-channel grants.

    This is the "extend" action exposed on the phone's Paired Devices
    screen, but also handles arbitrary TTL updates — passing
    ``ttl_seconds`` shorter than the current expiry clips the session
    (equivalent to shortening, though the button is labeled "Extend"
    for the common case). ``ttl_seconds == 0`` maps to never-expire.

    PATCH /sessions/{token_prefix}
      Body: {"ttl_seconds": 2592000}              # extend only
           | {"grants": {"terminal": 604800}}     # grants only
           | {"ttl_seconds": 0, "grants": {...}}  # both
      → 200 {"ok": true, "expires_at": ..., "grants": {...}}
      → 400 missing/invalid body or no fields provided
      → 401 missing/invalid bearer
      → 404 prefix doesn't match any active session
      → 409 prefix matches multiple sessions
    """
    server, current_session = _require_bearer_session(request)
    prefix = request.match_info.get("token_prefix", "").strip()
    if not prefix:
        return web.json_response(
            {"ok": False, "error": "missing token_prefix"}, status=400
        )

    try:
        body = await request.json()
    except (json.JSONDecodeError, ValueError):
        return web.json_response(
            {"ok": False, "error": "invalid JSON body"}, status=400
        )
    if not isinstance(body, dict):
        return web.json_response(
            {"ok": False, "error": "body must be a JSON object"}, status=400
        )

    ttl_seconds = body.get("ttl_seconds")
    grants = body.get("grants")

    if ttl_seconds is None and grants is None:
        return web.json_response(
            {
                "ok": False,
                "error": "must provide at least one of 'ttl_seconds' or 'grants'",
            },
            status=400,
        )

    if ttl_seconds is not None:
        if not isinstance(ttl_seconds, int) or ttl_seconds < 0:
            return web.json_response(
                {
                    "ok": False,
                    "error": "'ttl_seconds' must be a non-negative integer (0 = never expire)",
                },
                status=400,
            )
    if grants is not None:
        if not isinstance(grants, dict):
            return web.json_response(
                {"ok": False, "error": "'grants' must be an object"}, status=400
            )
        for k, v in grants.items():
            if not isinstance(k, str) or not isinstance(v, (int, float)) or v < 0:
                return web.json_response(
                    {
                        "ok": False,
                        "error": (
                            "'grants' must map string channel names to "
                            "non-negative numeric seconds-from-now values"
                        ),
                    },
                    status=400,
                )

    matches = server.sessions.find_by_prefix(prefix)
    if len(matches) == 0:
        raise web.HTTPNotFound(text=f"no session matches prefix {prefix!r}")
    if len(matches) > 1:
        return web.json_response(
            {
                "ok": False,
                "error": (
                    f"{len(matches)} sessions match prefix {prefix!r} — "
                    "retry with more characters"
                ),
            },
            status=409,
        )

    target = matches[0]
    updated = server.sessions.update_session(
        target.token,
        ttl_seconds=ttl_seconds,
        grants=grants,
    )
    if updated is None:
        # Raced with expiry or revocation between find_by_prefix and update.
        raise web.HTTPNotFound(text="session vanished mid-update, retry")

    logger.info(
        "Extended session %s... (%s)%s",
        target.token[:8],
        target.device_name,
        " [self]" if target.token == current_session.token else "",
    )
    return web.json_response(
        {
            "ok": True,
            "token_prefix": target.token[:8],
            "expires_at": (
                None if math.isinf(updated.expires_at) else updated.expires_at
            ),
            "grants": {
                k: (None if math.isinf(v) else v)
                for k, v in updated.grants.items()
            },
        }
    )


# ── Media handlers ───────────────────────────────────────────────────────────


async def handle_media_register(request: web.Request) -> web.Response:
    """Register a local file with the MediaRegistry and return an opaque token.

    Loopback-only — mirrors the ``/pairing/register`` trust model. Only a
    process running on the same host as the relay (e.g. a Hermes tool
    function) can mint media tokens.

    POST /media/register {"path": "/abs/path.png", "content_type": "image/png",
                          "file_name": "screenshot.png"}
      → 200 {"ok": true, "token": "...", "expires_at": <epoch seconds>}
      → 400 {"ok": false, "error": "<validation message>"}
      → 403 loopback gate
    """
    remote = request.remote or ""
    if remote not in ("127.0.0.1", "::1"):
        logger.warning(
            "Rejected /media/register from non-loopback peer %s", remote
        )
        raise web.HTTPForbidden(
            text="/media/register is restricted to localhost callers",
        )

    try:
        payload = await request.json()
    except (json.JSONDecodeError, ValueError):
        return web.json_response(
            {"ok": False, "error": "invalid JSON body"}, status=400
        )

    if not isinstance(payload, dict):
        return web.json_response(
            {"ok": False, "error": "body must be a JSON object"}, status=400
        )

    path = payload.get("path")
    content_type = payload.get("content_type")
    file_name = payload.get("file_name")

    server: RelayServer = request.app["server"]
    try:
        entry = await server.media.register(
            path=path,
            content_type=content_type,
            file_name=file_name,
        )
    except MediaRegistrationError as exc:
        logger.info("Media registration rejected: %s", exc)
        return web.json_response(
            {"ok": False, "error": str(exc)}, status=400
        )

    return web.json_response(
        {
            "ok": True,
            "token": entry.token,
            "expires_at": entry.expires_at,
        }
    )


async def handle_media_get(request: web.Request) -> web.StreamResponse:
    """Serve a previously-registered media file by opaque token.

    Authenticated via bearer token against the relay's SessionManager —
    every phone with a valid relay session token can fetch media,
    invalid/missing token is 401.

    GET /media/{token}
      → 200 file bytes
      → 401 missing/invalid bearer
      → 404 token not found / expired
    """
    auth_header = request.headers.get("Authorization", "")
    if not auth_header.startswith("Bearer "):
        raise web.HTTPUnauthorized(
            text="Authorization: Bearer <session_token> required",
        )

    bearer = auth_header[len("Bearer ") :].strip()
    if not bearer:
        raise web.HTTPUnauthorized(text="empty bearer token")

    server: RelayServer = request.app["server"]
    session = server.sessions.get_session(bearer)
    if session is None:
        logger.info(
            "Media fetch rejected: invalid bearer token from %s",
            request.remote or "unknown",
        )
        raise web.HTTPUnauthorized(text="invalid or expired session token")

    token = request.match_info["token"]
    entry = await server.media.get(token)
    if entry is None:
        raise web.HTTPNotFound(text="media token not found or expired")

    headers = {
        "Content-Type": entry.content_type,
        "Cache-Control": "private, max-age=3600",
    }
    if entry.file_name:
        # Quote the filename to survive weird characters. RFC 6266 inline
        # disposition with quoted filename is the broadest-compat form.
        safe_name = entry.file_name.replace('"', "")
        headers["Content-Disposition"] = f'inline; filename="{safe_name}"'
    else:
        headers["Content-Disposition"] = "inline"

    logger.debug(
        "Serving media token=%s... path=%s size=%d to session=%s...",
        token[:8],
        entry.path,
        entry.size,
        bearer[:8],
    )
    return web.FileResponse(entry.path, headers=headers)


async def handle_media_by_path(request: web.Request) -> web.StreamResponse:
    """Serve a media file by absolute path for LLM-emitted ``MEDIA:/abs/path`` markers.

    The LLM freeform-emits ``MEDIA:/abs/path.ext`` in its response text per
    upstream ``hermes-agent/agent/prompt_builder.py``. There is no token
    registration step — the phone sees the bare path in the chat stream and
    fetches it directly through this route.

    Security model:
      * **Bearer auth** — identical to ``/media/{token}``. Only a paired phone
        with a valid relay session token can fetch, so the trust boundary is
        the same as every other phone-facing relay channel.
      * **Path checks (always on)** — absolute path, ``realpath`` resolution,
        exists, is a regular file, under ``RELAY_MEDIA_MAX_SIZE_MB``.
      * **Allowed-roots sandbox (opt-in)** — off by default. Set
        ``RELAY_MEDIA_STRICT_SANDBOX=1`` to re-enable the allowlist
        enforcement. Rationale: if the LLM already has filesystem-reading
        tools (search_files, read_file, etc.), it can exfiltrate bytes via
        plain text responses, so the allowlist is defense-in-depth rather
        than a hard boundary. In practice the allowlist surfaced mostly as
        false positives — the LLM finds ``~/projects/foo/readme.png`` and
        the phone renders a "Path not allowed" card. Operators who want the
        tighter default back can opt in via env.

    GET /media/by-path?path=<urlencoded-abs-path>&content_type=<optional-type>
      → 200 file bytes
      → 400 missing path query param
      → 401 missing/invalid bearer
      → 403 path not absolute / other validation failure (or outside allowlist in strict mode)
      → 404 file does not exist or is not a regular file
      → 413 file exceeds RELAY_MEDIA_MAX_SIZE_MB
    """
    auth_header = request.headers.get("Authorization", "")
    if not auth_header.startswith("Bearer "):
        raise web.HTTPUnauthorized(
            text="Authorization: Bearer <session_token> required",
        )

    bearer = auth_header[len("Bearer ") :].strip()
    if not bearer:
        raise web.HTTPUnauthorized(text="empty bearer token")

    server: RelayServer = request.app["server"]
    session = server.sessions.get_session(bearer)
    if session is None:
        logger.info(
            "Media by-path fetch rejected: invalid bearer from %s",
            request.remote or "unknown",
        )
        raise web.HTTPUnauthorized(text="invalid or expired session token")

    path = request.query.get("path", "").strip()
    if not path:
        return web.json_response(
            {"ok": False, "error": "missing 'path' query parameter"},
            status=400,
        )

    # Hint: phone may pass content_type=; if not, guess from extension.
    content_type_hint = request.query.get("content_type", "").strip() or None

    # Strict mode → enforce allowlist. Default mode → None skips root check.
    roots_for_check = (
        server.media.allowed_roots if server.media.strict_sandbox else None
    )
    try:
        real_path, size = validate_media_path(
            path,
            roots_for_check,
            server.media.max_size_bytes,
        )
    except MediaRegistrationError as exc:
        msg = str(exc)
        # Differentiate file-not-found (phone should mark FAILED, stop
        # retrying) from sandbox violations (phone should mark FAILED with
        # a different error). Both are non-retryable.
        if "does not exist" in msg or "not a regular file" in msg:
            logger.info("Media by-path: not found — %s", msg)
            raise web.HTTPNotFound(text=msg)
        # Everything else — not absolute, outside allowed root, too large,
        # bad stat — is a sandbox or policy violation. 403 signals this
        # distinctly from 401 (bad auth) and 400 (malformed request).
        logger.info("Media by-path: sandbox violation — %s", msg)
        raise web.HTTPForbidden(text=msg)

    # Content type: honor phone-provided hint if given; otherwise guess.
    if content_type_hint:
        content_type = content_type_hint
    else:
        guessed, _ = mimetypes.guess_type(real_path)
        content_type = guessed or "application/octet-stream"

    # Extract a display file name from the path for Content-Disposition.
    file_name = os.path.basename(real_path)
    safe_name = file_name.replace('"', "")

    headers = {
        "Content-Type": content_type,
        "Cache-Control": "private, max-age=3600",
        "Content-Disposition": f'inline; filename="{safe_name}"',
    }

    logger.debug(
        "Serving media by-path %s size=%d type=%s to session=%s...",
        real_path,
        size,
        content_type,
        bearer[:8],
    )
    return web.FileResponse(real_path, headers=headers)


# ── WebSocket handler ────────────────────────────────────────────────────────


async def handle_ws(request: web.Request) -> web.WebSocketResponse:
    """Main WebSocket handler.

    Flow:
      1. Accept the WebSocket upgrade
      2. Wait for an ``auth`` message (system channel)
      3. Validate pairing code or session token
      4. On success: register client, route messages to channel handlers
      5. On disconnect: clean up
    """
    server: RelayServer = request.app["server"]
    remote_ip = request.remote or "unknown"

    # Rate-limit check
    if server.rate_limiter.is_blocked(remote_ip):
        logger.warning("WebSocket from blocked IP %s — rejecting", remote_ip)
        raise web.HTTPTooManyRequests(
            text="Too many failed authentication attempts. Try again later."
        )

    ws = web.WebSocketResponse(heartbeat=30.0)
    await ws.prepare(request)
    logger.info("WebSocket connection from %s — waiting for auth", remote_ip)

    # ── Phase 1: Authentication ──────────────────────────────────────────

    session_token: str | None = None
    try:
        session_token = await _authenticate(ws, server, remote_ip, request)
    except _AuthFailed as exc:
        logger.info("Auth failed from %s: %s", remote_ip, exc)
        # WebSocket was already sent an auth.fail message
        if not ws.closed:
            await ws.close()
        return ws

    if session_token is None:
        # Client disconnected during auth
        return ws

    # ── Phase 2: Authenticated message loop ──────────────────────────────

    server._clients[ws] = session_token
    server._client_tasks[ws] = set()
    logger.info("Client authenticated from %s (token=%s...)", remote_ip, session_token[:8])

    try:
        async for msg in ws:
            if msg.type == aiohttp.WSMsgType.TEXT:
                await _on_message(ws, server, msg.data)
            elif msg.type == aiohttp.WSMsgType.ERROR:
                logger.error(
                    "WebSocket error from %s: %s", remote_ip, ws.exception()
                )
                break
    except asyncio.CancelledError:
        logger.info("WebSocket handler cancelled for %s", remote_ip)
    finally:
        await _on_disconnect(ws, server, remote_ip)

    return ws


class _AuthFailed(Exception):
    """Raised when WebSocket authentication fails."""


def _detect_transport_hint(request: web.Request) -> str:
    """Infer whether the phone auth'd over ``wss`` or plain ``ws``.

    aiohttp doesn't expose a ready-made "is this request over TLS" flag,
    so we sniff the underlying transport's ``ssl_object`` (non-None when
    the SSL context wrapped the socket). Falls back to
    ``request.scheme`` + ``request.secure`` as a belt-and-braces check.
    Anything ambiguous returns ``"unknown"``.
    """
    try:
        transport = request.transport
        if transport is not None:
            ssl_obj = transport.get_extra_info("ssl_object")
            if ssl_obj is not None:
                return "wss"
        scheme = (request.scheme or "").lower()
        if scheme in ("https", "wss"):
            return "wss"
        if scheme in ("http", "ws"):
            return "ws"
    except Exception:  # pragma: no cover — defensive
        pass
    return "unknown"


def _build_auth_ok_payload(
    session: Session, server: RelayServer
) -> dict[str, Any]:
    """Construct the payload for a successful ``auth.ok`` envelope.

    Never-expire timestamps serialize as JSON ``null`` (``math.inf`` is
    not JSON-legal and would crash ``json.dumps`` with default settings).
    The phone treats ``null`` as "no expiry".
    """
    def _norm(ts: float) -> float | None:
        return None if math.isinf(ts) else ts

    return {
        "session_token": session.token,
        "server_version": __version__,
        "profiles": server.config.profiles,
        "expires_at": _norm(session.expires_at),
        "grants": {k: _norm(v) for k, v in session.grants.items()},
        "transport_hint": session.transport_hint,
    }


async def _authenticate(
    ws: web.WebSocketResponse,
    server: RelayServer,
    remote_ip: str,
    request: web.Request,
) -> str | None:
    """Wait for an auth message and validate it.

    Returns the session token on success, raises ``_AuthFailed`` on failure,
    or returns ``None`` if the client disconnects.
    """
    # Give the client 30 seconds to send an auth message
    try:
        msg = await asyncio.wait_for(_read_next_text(ws), timeout=30.0)
    except asyncio.TimeoutError:
        await _send_system(ws, "auth.fail", {"reason": "Authentication timeout"})
        raise _AuthFailed("timeout")

    if msg is None:
        # Client disconnected
        return None

    # Parse the envelope
    try:
        envelope = json.loads(msg)
    except json.JSONDecodeError:
        await _send_system(ws, "auth.fail", {"reason": "Invalid message format"})
        raise _AuthFailed("invalid JSON")

    if envelope.get("channel") != "system" or envelope.get("type") != "auth":
        await _send_system(
            ws, "auth.fail", {"reason": "First message must be system/auth"}
        )
        raise _AuthFailed("expected system/auth, got %s/%s" % (
            envelope.get("channel"), envelope.get("type")
        ))

    payload = envelope.get("payload", {})
    pairing_code = payload.get("pairing_code", "")
    session_token_attempt = payload.get("session_token", "")
    device_name = payload.get("device_name", "Unknown device")
    device_id = payload.get("device_id", "unknown")

    # The phone MAY send ttl_seconds / grants in its auth envelope, but
    # if the operator pre-registered the code with metadata on the host
    # side, those host-provided values win — the host has the authority
    # to decide "how long and for which channels". Only fall back to the
    # phone-sent fields when the code had no attached metadata (e.g. old
    # phones predating the v2 auth envelope).
    phone_ttl = payload.get("ttl_seconds")
    phone_grants = payload.get("grants")
    detected_transport = _detect_transport_hint(request)

    # Try session token first (reconnection)
    if session_token_attempt:
        session = server.sessions.get_session(session_token_attempt)
        if session is not None:
            server.rate_limiter.record_success(remote_ip)
            await _send_system(
                ws, "auth.ok", _build_auth_ok_payload(session, server)
            )
            return session.token

    # Try pairing code
    if pairing_code:
        metadata = server.pairing.consume_code(pairing_code)
        if metadata is not None:
            # Thread host-side metadata (from /pairing/register) through
            # to the session. Fall back to phone-sent values, then to
            # library defaults.
            ttl_seconds: float | None = metadata.ttl_seconds
            grants: dict[str, float] | None = metadata.grants
            transport_hint = metadata.transport_hint or detected_transport

            if ttl_seconds is None and isinstance(phone_ttl, (int, float)) and not isinstance(phone_ttl, bool):
                if phone_ttl >= 0:
                    ttl_seconds = float(phone_ttl)
            if grants is None and isinstance(phone_grants, dict):
                cleaned: dict[str, float] = {}
                for channel, value in phone_grants.items():
                    if isinstance(channel, str) and isinstance(value, (int, float)) and not isinstance(value, bool) and value >= 0:
                        cleaned[channel] = float(value)
                if cleaned:
                    grants = cleaned

            session = server.sessions.create_session(
                device_name,
                device_id,
                ttl_seconds=ttl_seconds,
                grants=grants,
                transport_hint=transport_hint,
            )
            server.rate_limiter.record_success(remote_ip)
            await _send_system(
                ws, "auth.ok", _build_auth_ok_payload(session, server)
            )
            return session.token

    # Both failed
    server.rate_limiter.record_failure(remote_ip)
    reason = "Invalid pairing code or session token"
    await _send_system(ws, "auth.fail", {"reason": reason})
    raise _AuthFailed(reason)


async def _read_next_text(ws: web.WebSocketResponse) -> str | None:
    """Read the next TEXT message from the WebSocket, or None if it closes."""
    async for msg in ws:
        if msg.type == aiohttp.WSMsgType.TEXT:
            return msg.data
        if msg.type in (aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.CLOSING, aiohttp.WSMsgType.CLOSED):
            return None
        if msg.type == aiohttp.WSMsgType.ERROR:
            return None
    return None


# ── Message routing ──────────────────────────────────────────────────────────


async def _on_message(
    ws: web.WebSocketResponse,
    server: RelayServer,
    raw: str,
) -> None:
    """Parse an envelope and route it to the appropriate channel handler."""
    try:
        envelope = json.loads(raw)
    except json.JSONDecodeError:
        logger.warning("Non-JSON message from client: %s", raw[:200])
        return

    channel = envelope.get("channel", "")
    msg_type = envelope.get("type", "")
    msg_id = envelope.get("id", "")

    logger.debug("Received: channel=%s type=%s id=%s", channel, msg_type, msg_id)

    if channel == "system":
        await _handle_system(ws, server, envelope)
    elif channel == "chat":
        # Run chat handling as a tracked task so we can cancel on disconnect
        task = asyncio.create_task(server.chat.handle(ws, envelope))
        _track_task(server, ws, task)
    elif channel == "terminal":
        task = asyncio.create_task(server.terminal.handle(ws, envelope))
        _track_task(server, ws, task)
    elif channel == "bridge":
        task = asyncio.create_task(server.bridge.handle(ws, envelope))
        _track_task(server, ws, task)
    else:
        logger.warning("Unknown channel: %s", channel)
        await _send_system(
            ws,
            "error",
            {"message": f"Unknown channel: {channel}"},
            msg_id=msg_id,
        )


def _track_task(
    server: RelayServer,
    ws: web.WebSocketResponse,
    task: asyncio.Task[Any],
) -> None:
    """Register a task so it can be cancelled on client disconnect."""
    tasks = server._client_tasks.get(ws)
    if tasks is None:
        return
    tasks.add(task)
    task.add_done_callback(lambda t: tasks.discard(t))


async def _handle_system(
    ws: web.WebSocketResponse,
    server: RelayServer,
    envelope: dict[str, Any],
) -> None:
    """Handle system-channel messages (ping/pong)."""
    msg_type = envelope.get("type", "")
    payload = envelope.get("payload", {})
    msg_id = envelope.get("id")

    if msg_type == "ping":
        await _send_system(ws, "pong", {"ts": payload.get("ts", time.time())}, msg_id)
    elif msg_type == "pong":
        # Client responding to our ping — nothing to do
        pass
    else:
        logger.debug("Unhandled system message type: %s", msg_type)


async def _send_system(
    ws: web.WebSocketResponse,
    msg_type: str,
    payload: dict[str, Any],
    msg_id: str | None = None,
) -> None:
    """Send a system-channel envelope to the client."""
    if ws.closed:
        return
    try:
        await ws.send_str(
            json.dumps(
                {
                    "channel": "system",
                    "type": msg_type,
                    "id": msg_id or str(uuid.uuid4()),
                    "payload": payload,
                }
            )
        )
    except ConnectionResetError:
        pass


# ── Disconnect cleanup ───────────────────────────────────────────────────────


async def _on_disconnect(
    ws: web.WebSocketResponse,
    server: RelayServer,
    remote_ip: str,
) -> None:
    """Clean up after a client disconnects."""
    token = server._clients.pop(ws, None)
    tasks = server._client_tasks.pop(ws, set())

    # Cancel all in-flight tasks for this client
    for task in tasks:
        task.cancel()

    if tasks:
        # Wait briefly for tasks to finish cancelling
        await asyncio.gather(*tasks, return_exceptions=True)
        logger.info(
            "Cancelled %d in-flight task(s) for %s", len(tasks), remote_ip
        )

    if not ws.closed:
        await ws.close()

    logger.info(
        "Client disconnected: %s (token=%s...)",
        remote_ip,
        token[:8] if token else "none",
    )


# ── Application factory ─────────────────────────────────────────────────────


def create_app(config: RelayConfig) -> web.Application:
    """Create and configure the aiohttp application."""
    app = web.Application()

    server = RelayServer(config)
    app["server"] = server

    # Routes — /ws is canonical but we also accept "/" as an alias so clients
    # that pass a bare ws://host:port URL (no path) still connect cleanly.
    app.router.add_get("/ws", handle_ws)
    app.router.add_get("/", handle_ws)
    app.router.add_get("/health", handle_health)
    app.router.add_post("/pairing", handle_pairing)
    app.router.add_post("/pairing/register", handle_pairing_register)
    app.router.add_post("/pairing/approve", handle_pairing_approve)
    # Sessions API — list + revoke paired devices. The fixed-path /sessions
    # route is distinct from /sessions/{token_prefix} so there's no wildcard
    # conflict; aiohttp picks GET vs DELETE on method before it looks at
    # path variability, so either ordering works. We declare the fixed
    # route first for clarity.
    app.router.add_get("/sessions", handle_sessions_list)
    app.router.add_delete("/sessions/{token_prefix}", handle_sessions_revoke)
    app.router.add_patch("/sessions/{token_prefix}", handle_sessions_extend)
    app.router.add_post("/media/register", handle_media_register)
    # Order matters: the fixed-path "/media/by-path" route must be declared
    # before the wildcard "/media/{token}" route or aiohttp will swallow
    # "by-path" as a token literal and handle_media_get will 404.
    app.router.add_get("/media/by-path", handle_media_by_path)
    app.router.add_get("/media/{token}", handle_media_get)

    # Voice endpoints — TTS / STT bridge, bearer-auth'd like /media/*.
    async def _voice_transcribe(request: web.Request) -> web.StreamResponse:
        s: RelayServer = request.app["server"]
        return await s.voice.handle_transcribe(request)

    async def _voice_synthesize(request: web.Request) -> web.StreamResponse:
        s: RelayServer = request.app["server"]
        return await s.voice.handle_synthesize(request)

    async def _voice_config(request: web.Request) -> web.StreamResponse:
        s: RelayServer = request.app["server"]
        return await s.voice.handle_voice_config(request)

    app.router.add_post("/voice/transcribe", _voice_transcribe)
    app.router.add_post("/voice/synthesize", _voice_synthesize)
    app.router.add_get("/voice/config", _voice_config)

    # Cleanup on shutdown
    app.on_shutdown.append(_on_app_shutdown)

    return app


async def _on_app_shutdown(app: web.Application) -> None:
    """Called when the aiohttp application is shutting down."""
    server: RelayServer = app["server"]
    await server.close()
    logger.info("Application shutdown complete")


# ── SSL context ──────────────────────────────────────────────────────────────


def _create_ssl_context(config: RelayConfig) -> ssl.SSLContext | None:
    """Create an SSL context if cert and key are configured."""
    if not config.ssl_cert or not config.ssl_key:
        return None

    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    try:
        ctx.load_cert_chain(config.ssl_cert, config.ssl_key)
    except (FileNotFoundError, ssl.SSLError) as exc:
        logger.error("Failed to load SSL cert/key: %s", exc)
        sys.exit(1)

    logger.info("SSL enabled: cert=%s key=%s", config.ssl_cert, config.ssl_key)
    return ctx


# ── CLI entry point ──────────────────────────────────────────────────────────


def main() -> None:
    """Parse CLI arguments and start the relay server."""
    parser = argparse.ArgumentParser(
        description="Hermes-Relay Server — WSS server for the Android app"
    )
    parser.add_argument(
        "--host",
        default=None,
        help="Bind address (default: 0.0.0.0, or RELAY_HOST env)",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=None,
        help="Listen port (default: 8767, or RELAY_PORT env)",
    )
    parser.add_argument(
        "--no-ssl",
        action="store_true",
        help="Disable SSL (development only — never use in production)",
    )
    parser.add_argument(
        "--config",
        default=None,
        help="Path to Hermes config YAML (default: ~/.hermes/config.yaml)",
    )
    parser.add_argument(
        "--webapi-url",
        default=None,
        help="Hermes WebAPI base URL (default: http://localhost:8642)",
    )
    parser.add_argument(
        "--log-level",
        default=None,
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
        help="Log level (default: INFO, or RELAY_LOG_LEVEL env)",
    )
    parser.add_argument(
        "--pairing-code",
        metavar="CODE",
        default=None,
        help=(
            "Pre-register a pairing code at startup (6 chars from A-Z / 0-9). "
            "Use this when the phone generates the code and displays it in "
            "the app — pass the same code here so the relay accepts it. "
            "Can also be set via RELAY_PAIRING_CODE."
        ),
    )
    parser.add_argument(
        "--version",
        action="version",
        version=f"hermes-relay {__version__}",
    )

    args = parser.parse_args()

    # Build config from env first, then override with CLI args
    config = RelayConfig.from_env()

    if args.host is not None:
        config.host = args.host
    if args.port is not None:
        config.port = args.port
    if args.config is not None:
        config.hermes_config_path = args.config
        # Reload profiles with the new path
        from .config import _load_profiles
        config.profiles = _load_profiles(config.hermes_config_path)
    if args.webapi_url is not None:
        config.webapi_url = args.webapi_url
    if args.log_level is not None:
        config.log_level = args.log_level
    if args.no_ssl:
        config.ssl_cert = None
        config.ssl_key = None

    # Configure logging
    logging.basicConfig(
        level=getattr(logging, config.log_level, logging.INFO),
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    # Build the app
    app = create_app(config)

    # Pre-register a pairing code from CLI/env so the relay will accept it
    # when the phone sends its locally-generated code during auth.
    import os as _os
    preset_code = args.pairing_code or _os.environ.get("RELAY_PAIRING_CODE")
    if preset_code:
        server: RelayServer = app["server"]
        if server.pairing.register_code(preset_code):
            logger.info("Pre-registered pairing code from CLI: %s", preset_code.upper())
        else:
            logger.error(
                "Failed to pre-register pairing code %r — must be %d chars from %s",
                preset_code,
                6,
                "A-Z2-9 (no ambiguous 0/1/I/O)",
            )
            sys.exit(2)

    # SSL context
    ssl_ctx = None if args.no_ssl else _create_ssl_context(config)

    if ssl_ctx is None and not args.no_ssl:
        logger.warning(
            "No SSL cert/key configured. Use --no-ssl for development, "
            "or set RELAY_SSL_CERT and RELAY_SSL_KEY for production."
        )

    # Log startup info
    scheme = "wss" if ssl_ctx else "ws"
    logger.info(
        "Starting Relay Server v%s on %s://%s:%d",
        __version__,
        scheme,
        config.host,
        config.port,
    )
    logger.info("WebAPI target: %s", config.webapi_url)
    logger.info(
        "Profiles loaded: %s",
        ", ".join(p["name"] for p in config.profiles) if config.profiles else "(none)",
    )

    # Run the server
    web.run_app(
        app,
        host=config.host,
        port=config.port,
        ssl_context=ssl_ctx,
        print=None,  # Suppress aiohttp's default startup banner
    )
