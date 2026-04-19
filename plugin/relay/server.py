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
import tempfile
import time
import uuid
from typing import Any
from urllib.parse import urlparse

import aiohttp
import yaml
from aiohttp import web
from pathlib import Path

from . import __version__
from .auth import (
    PairingManager,
    RateLimiter,
    Session,
    SessionManager,
)
from .channels.bridge import BridgeError, BridgeHandler
from .channels.chat import ChatHandler
from .channels.notifications import NotificationsChannel
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
        self.start_time: float = time.monotonic()

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
        # === PHASE3-notif-listener: notifications channel ===
        self.notifications = NotificationsChannel()
        # === END PHASE3-notif-listener ===

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

    # Optional v3 multi-endpoint payload (ADR 24). Accept the array as-is
    # and mirror it back in the response; ``pair.py`` built it and it's
    # about to be HMAC-signed, so the server doesn't re-validate shape.
    # The phone validates structure on parse.
    endpoints = payload.get("endpoints")
    if endpoints is not None and not isinstance(endpoints, list):
        return web.json_response(
            {"ok": False, "error": "endpoints must be an array"}, status=400
        )

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
    response: dict[str, Any] = {"ok": True, "code": code.upper()}
    if endpoints is not None:
        response["endpoints"] = endpoints
    return web.json_response(response)


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


async def handle_pairing_mint(request: web.Request) -> web.Response:
    """Mint a fresh pairing code and return a signed QR payload.

    Loopback-only. Used by the dashboard plugin's "pair new device" flow.
    Generates a random 6-char A-Z/0-9 code, registers it via the
    PairingManager, then hands back the same signed JSON blob the CLI
    (`hermes-pair`) would print so the caller can render it as a QR.

    The payload shape matches what the Android app parses in
    ``QrPairingScanner.kt``: top-level ``host/port/key/tls`` describe the
    direct-chat Hermes **API** server the phone will hit for chat; the
    nested ``relay`` block describes the WSS relay connection:

    ```json
    {
      "hermes": 2,
      "host": "172.16.24.250", "port": 8642, "key": "<api-key>", "tls": false,
      "relay": {"url": "ws://172.16.24.250:8767", "code": "ABC123",
                "ttl_seconds": 604800, "transport_hint": "ws"}
    }
    ```

    This mirrors ``pair.py``'s CLI ``build_payload(...)`` call. Before
    2026-04-18 the endpoint put the minted code in top-level ``key`` and
    emitted top-level ``port`` as the relay port, which broke scanning —
    the phone treated the relay port as the API server address and the
    relay block had no url/code for it to open a WSS against.

    POST /pairing/mint
      body (all optional — fall back to RelayConfig defaults):
        - host: "172.16.24.250"        API server host override (LAN IP)
        - port: 8642                    API server port override
        - tls: false                    API server TLS override
        - api_key: "<token>"            API bearer token (goes in top-level
                                        "key"; empty = open access)
        - ttl_seconds: <int>            Session TTL
        - transport_hint: "wss"|"ws"    Forwarded verbatim
        - grants: {...}                 Channel TTL map
      → 200 {ok, code, qr_payload, expires_at, host, port, tls, relay_url}
      → 400 invalid JSON or API host can't be resolved
      → 403 non-loopback caller
    """
    _require_loopback(request)

    import secrets
    import string

    try:
        payload = await request.json() if request.body_exists else {}
    except (json.JSONDecodeError, ValueError):
        return web.json_response({"ok": False, "error": "invalid JSON"}, status=400)
    if not isinstance(payload, dict):
        payload = {}

    server: RelayServer = request.app["server"]

    from ..pair import build_payload, _relay_lan_base_url, _resolve_lan_ip

    # ── API server info (top-level of the QR payload) ────────────────────
    # Defaults come from ``RelayConfig.webapi_url`` (the Hermes API gateway
    # URL the relay is fronting). The dashboard's "editable pair URL" UI
    # overrides any of host/port/tls via the request body.
    default_api_url = urlparse(server.config.webapi_url or "http://localhost:8642")
    api_host_override = payload.get("host")
    api_port_override = payload.get("port")
    api_tls_override = payload.get("tls")

    raw_api_host = str(
        api_host_override
        if api_host_override
        else (default_api_url.hostname or "")
    ).strip()
    if not raw_api_host or raw_api_host == "0.0.0.0":
        return web.json_response(
            {
                "ok": False,
                "error": "missing 'host' — API server address could not be resolved",
            },
            status=400,
        )
    api_host = _resolve_lan_ip(raw_api_host)

    if api_port_override is not None:
        try:
            api_port = int(api_port_override)
        except (TypeError, ValueError):
            return web.json_response(
                {"ok": False, "error": "'port' must be an integer"}, status=400
            )
    elif default_api_url.port is not None:
        api_port = default_api_url.port
    else:
        api_port = 443 if default_api_url.scheme == "https" else 8642

    if api_tls_override is not None:
        api_tls = bool(api_tls_override)
    else:
        api_tls = default_api_url.scheme == "https"

    api_key_raw = payload.get("api_key")
    api_key = str(api_key_raw) if api_key_raw is not None else ""

    # ── Pairing metadata ─────────────────────────────────────────────────
    ttl_seconds, grants, transport_hint, err = _parse_pairing_metadata(payload)
    if err is not None:
        return web.json_response({"ok": False, "error": err}, status=400)

    # Optional v3 multi-endpoint array (ADR 24). The caller (dashboard
    # "pair new device" UI) composes this; the server just stores and
    # mirrors it. Shape is validated on the phone — any server-side
    # normalization would break HMAC round-tripping.
    endpoints_raw = payload.get("endpoints")
    if endpoints_raw is not None and not isinstance(endpoints_raw, list):
        return web.json_response(
            {"ok": False, "error": "endpoints must be an array"}, status=400
        )
    endpoints_list: list[Any] | None = (
        list(endpoints_raw) if endpoints_raw is not None else None
    )

    # ── Mint the relay pairing code ──────────────────────────────────────
    alphabet = string.ascii_uppercase + string.digits
    for _ in range(5):
        code = "".join(secrets.choice(alphabet) for _ in range(6))
        if server.pairing.register_code(
            code,
            ttl_seconds=ttl_seconds,
            grants=grants,
            transport_hint=transport_hint,
        ):
            break
    else:
        return web.json_response(
            {"ok": False, "error": "could not mint a unique code"}, status=503
        )

    # ── Relay block (nested in the QR payload) ───────────────────────────
    # URL derives from the relay's own bind config — the relay knows where
    # the phone should connect. Matches pair.py:746 exactly.
    relay_tls = bool(server.config.ssl_cert)
    relay_url = _relay_lan_base_url(
        server.config.host, server.config.port, tls=relay_tls
    )
    relay_block: dict[str, Any] = {
        "url": relay_url,
        "code": code,
    }
    if ttl_seconds is not None:
        relay_block["ttl_seconds"] = ttl_seconds
    if grants is not None:
        relay_block["grants"] = grants
    if transport_hint is not None:
        relay_block["transport_hint"] = transport_hint

    qr_payload = build_payload(
        host=api_host,
        port=api_port,
        key=api_key,
        tls=api_tls,
        relay=relay_block,
        sign=True,
        endpoints=endpoints_list or None,
    )

    expires_at = int(time.time()) + (
        int(ttl_seconds) if ttl_seconds and ttl_seconds > 0 else 60
    )

    # Same rationale as /pairing/register and /pairing/approve — a phone
    # self-banned on the rate limiter (e.g. reconnect-loop after a relay
    # restart invalidated its session token) must not be left unpairable
    # just because the minting path is different. Any loopback-originated
    # mint implies operator intent to pair, so wiping the block table is
    # safe and matches the other two pairing entry points.
    server.rate_limiter.clear_all_blocks()

    logger.info(
        "Minted pairing code via /pairing/mint: %s "
        "(api=%s://%s:%d relay=%s)",
        code,
        "https" if api_tls else "http",
        api_host,
        api_port,
        relay_url,
    )
    mint_response: dict[str, Any] = {
        "ok": True,
        "code": code,
        "qr_payload": qr_payload,
        "expires_at": expires_at,
        "host": api_host,
        "port": api_port,
        "tls": api_tls,
        "relay_url": relay_url,
    }
    if endpoints_list is not None:
        # Mirror the endpoints back verbatim so the dashboard can render
        # the same list it sent (useful for "edit URL" round-trips) and
        # persist it alongside the pairing code metadata.
        mint_response["endpoints"] = endpoints_list
    return web.json_response(mint_response)


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


def _require_loopback(request: web.Request) -> None:
    if request.remote not in ("127.0.0.1", "::1"):
        raise web.HTTPForbidden(reason="loopback only")


async def handle_sessions_list(request: web.Request) -> web.Response:
    """List all paired devices.

    GET /sessions
      → 200 {"sessions": [...]}
      → 401 missing/invalid bearer
    """
    # Loopback-without-bearer branch — used by the co-hosted dashboard
    # plugin to enumerate paired devices without minting a bearer. The
    # response omits the is_current highlight (there is no calling
    # session). Bearer-authenticated callers fall through to the
    # existing code path below.
    is_loopback = request.remote in ("127.0.0.1", "::1")
    if is_loopback and not request.headers.get("Authorization"):
        server: RelayServer = request.app["server"]
        entries = [
            _session_to_dict(s, None) for s in server.sessions.list_sessions()
        ]
        entries.sort(key=lambda e: e["last_seen"], reverse=True)
        return web.json_response({"sessions": entries})

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
    # Loopback-without-bearer branch — co-hosted dashboard plugin revokes
    # sessions on behalf of the operator without minting its own bearer.
    is_loopback = request.remote in ("127.0.0.1", "::1")
    if is_loopback and not request.headers.get("Authorization"):
        server: RelayServer = request.app["server"]
        current_token: str | None = None
    else:
        server, current_session = _require_bearer_session(request)
        current_token = current_session.token

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
    revoked_self = current_token is not None and target.token == current_token
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


# === PHASE3-bridge-server-followup: /media/upload ===
#
# accessibility's ScreenCapture (the phone-side accessibility runtime) needs to push
# screenshot bytes to the relay over the network. The pre-existing
# /media/register endpoint is loopback + path-based — it assumes the
# caller already has the bytes on the relay's filesystem, which is true
# for host-local Hermes tools but false for the phone. This endpoint
# bridges that gap: accept multipart bytes from a paired phone, write to
# a sandboxed tempfile under tempfile.gettempdir() (which is in the
# default MediaRegistry allowed_roots), then hand the path to
# MediaRegistry.register() so the rest of the pipeline (token issuance,
# expiry, LRU eviction, GET /media/{token}) is identical.

_MEDIA_UPLOAD_CHUNK_SIZE = 64 * 1024


async def handle_media_upload(request: web.Request) -> web.Response:
    """Accept a phone-uploaded file and register it with MediaRegistry.

    Bearer-auth'd (every paired phone has a session token). Streams the
    multipart `file` field to a NamedTemporaryFile under
    ``tempfile.gettempdir()``, enforces ``MediaRegistry.max_size_bytes``
    while reading, and on success returns the same JSON shape as
    ``/media/register``.

    POST /media/upload (multipart/form-data, field name "file")
      → 200 {"ok": true, "token": "...", "expires_at": <epoch>}
      → 400 invalid request shape (no multipart, no "file" field)
      → 401 missing/invalid bearer
      → 413 file too large (over MediaRegistry.max_size_bytes)
    """
    server, _session = _require_bearer_session(request)

    content_type = request.content_type or ""
    if not content_type.startswith("multipart/"):
        return web.json_response(
            {"ok": False, "error": "expected multipart/form-data"},
            status=400,
        )

    try:
        reader = await request.multipart()
    except (aiohttp.ClientPayloadError, ValueError) as exc:
        return web.json_response(
            {"ok": False, "error": f"invalid multipart body: {exc}"},
            status=400,
        )

    field = await reader.next()
    while field is not None and field.name != "file":
        field = await reader.next()
    if field is None:
        return web.json_response(
            {"ok": False, "error": "missing 'file' multipart field"},
            status=400,
        )

    file_name = field.filename or "upload.bin"
    file_content_type = (
        field.headers.get("Content-Type") or "application/octet-stream"
    )

    # Pick a suffix from the filename so MIME-by-extension consumers
    # downstream still work. Strip path components defensively in case
    # a client sends something exotic.
    base_name = os.path.basename(file_name) or "upload.bin"
    suffix = ""
    if "." in base_name:
        suffix = "." + base_name.rsplit(".", 1)[-1]

    tmp = tempfile.NamedTemporaryFile(
        prefix="hermes-relay-upload-",
        suffix=suffix,
        delete=False,
    )
    bytes_written = 0
    max_size = server.media.max_size_bytes
    try:
        while True:
            chunk = await field.read_chunk(_MEDIA_UPLOAD_CHUNK_SIZE)
            if not chunk:
                break
            bytes_written += len(chunk)
            if bytes_written > max_size:
                tmp.close()
                try:
                    os.unlink(tmp.name)
                except OSError:
                    pass
                return web.json_response(
                    {
                        "ok": False,
                        "error": (
                            f"upload too large ({bytes_written} bytes, "
                            f"max {max_size})"
                        ),
                    },
                    status=413,
                )
            tmp.write(chunk)
    except (aiohttp.ClientPayloadError, OSError) as exc:
        tmp.close()
        try:
            os.unlink(tmp.name)
        except OSError:
            pass
        return web.json_response(
            {"ok": False, "error": f"upload aborted: {exc}"},
            status=400,
        )
    finally:
        if not tmp.closed:
            tmp.close()

    try:
        entry = await server.media.register(
            path=tmp.name,
            content_type=file_content_type,
            file_name=base_name,
        )
    except MediaRegistrationError as exc:
        try:
            os.unlink(tmp.name)
        except OSError:
            pass
        logger.info("Media upload registration rejected: %s", exc)
        return web.json_response(
            {"ok": False, "error": str(exc)}, status=400
        )

    logger.info(
        "Media uploaded: token=%s... bytes=%d type=%s file=%s",
        entry.token[:8],
        bytes_written,
        file_content_type,
        base_name,
    )
    return web.json_response(
        {
            "ok": True,
            "token": entry.token,
            "expires_at": entry.expires_at,
        }
    )


# === END PHASE3-bridge-server-followup ===


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


# === PHASE3-bridge-server: bridge HTTP routes ===
#
# The unified relay exposes the same HTTP shape as the legacy standalone
# relay on port 8766 so ``plugin/tools/android_tool.py`` only needs a
# one-line URL change. Requests are delegated straight through to
# ``BridgeHandler.handle_command`` which forwards them over the WSS
# channel to the connected phone.
#
# Auth model:
#   * These routes are **unauthenticated** at the HTTP layer on purpose —
#     the legacy relay was unauthenticated too, and the trust boundary
#     is the same: only tools running on the same host as the relay can
#     reach localhost:8767. The relay's default bind is 0.0.0.0 for the
#     WebSocket side, but tools should always point at ``localhost``, so
#     an attacker reaching port 8767 from the LAN would need the phone
#     to also have auth'd with a valid pairing code — without a paired
#     phone, every bridge HTTP call just returns 503.
#   * If tightening is needed later, wrap these handlers with the same
#     ``_require_bearer_session`` pattern used by ``/media/*`` — the
#     bridge grant is already tracked per-session in ``Session.grants``.
#
# A paired but disconnected phone still drops bridge calls with 503 —
# the tool caller should retry or tell the user to reconnect the app.


_BRIDGE_TIMEOUT_STATUS = 504
_BRIDGE_NO_PHONE_STATUS = 503
_BRIDGE_SEND_FAIL_STATUS = 502


def _bridge_error_response(
    exc: Exception, fallback_status: int = _BRIDGE_NO_PHONE_STATUS
) -> web.Response:
    """Convert a :class:`BridgeError` to a JSON response that matches the
    legacy standalone relay's error shape."""
    msg = str(exc) or "Bridge error"
    status = fallback_status
    lowered = msg.lower()
    if "did not respond" in lowered or "timeout" in lowered:
        status = _BRIDGE_TIMEOUT_STATUS
    elif "failed to send" in lowered:
        status = _BRIDGE_SEND_FAIL_STATUS
    return web.json_response({"error": msg}, status=status)


async def _bridge_dispatch(
    request: web.Request,
    path: str,
) -> web.Response:
    """Forward an HTTP request to the phone via the bridge channel."""
    server: RelayServer = request.app["server"]
    method = request.method  # GET or POST

    params: dict[str, Any] = dict(request.query)
    body: dict[str, Any] = {}
    if method == "POST":
        # android_tool.py always sends JSON bodies; treat anything else as empty.
        try:
            raw = await request.json()
            if isinstance(raw, dict):
                body = raw
        except (json.JSONDecodeError, ValueError, aiohttp.ContentTypeError):
            body = {}
    else:
        # H1/M5 fix: phone-side BridgeCommandHandler reads its arguments from
        # the envelope body, with a `params` fallback that's dead code because
        # an empty {} JsonObject is not null. For GET requests we therefore
        # merge query-string params into the body so handlers like /events
        # (limit/since) and /screen (include_bounds) actually see them.
        # Values from request.query are strings; phone-side parsers must use
        # .content.toIntOrNull() etc. rather than .intOrNull.
        body = dict(request.query)

    try:
        response = await server.bridge.handle_command(
            method=method,
            path=path,
            params=params,
            body=body,
        )
    except BridgeError as exc:
        return _bridge_error_response(exc)
    except ConnectionError as exc:
        return web.json_response({"error": str(exc)}, status=_BRIDGE_SEND_FAIL_STATUS)

    status = response.get("status", 200)
    if not isinstance(status, int):
        status = 200
    result = response.get("result")
    if not isinstance(result, (dict, list)):
        result = {"value": result} if result is not None else {}
    return web.json_response(result, status=status)


# Route adapters — aiohttp's router only gives us (request,), so we
# close over the path string here. One adapter per endpoint keeps the
# route registration self-documenting at the call site.
#
# Endpoint inventory mirrors ``plugin/tools/android_tool.py``'s 15 tools:
#   GET  /ping, /screen, /screenshot, /get_apps, /current_app
#   POST /tap, /tap_text, /type, /swipe, /open_app, /press_key,
#        /scroll, /describe_node, /wait, /setup
#
# NOTE: the legacy relay used ``/apps`` for list apps but the Android app
# expects ``/get_apps`` and the tool at line ~230 of android_tool.py calls
# ``_get("/apps")``. We register both so the legacy tool path keeps
# working while new code can use the canonical ``/get_apps``.


async def handle_bridge_ping(request: web.Request) -> web.Response:
    return await _bridge_dispatch(request, "/ping")


async def handle_bridge_screen(request: web.Request) -> web.Response:
    return await _bridge_dispatch(request, "/screen")


async def handle_bridge_screenshot(request: web.Request) -> web.Response:
    return await _bridge_dispatch(request, "/screenshot")


async def handle_bridge_get_apps(request: web.Request) -> web.Response:
    return await _bridge_dispatch(request, "/get_apps")


async def handle_bridge_apps_legacy(request: web.Request) -> web.Response:
    # Legacy alias — the pre-migration tool used /apps, keep it working.
    return await _bridge_dispatch(request, "/apps")


async def handle_bridge_current_app(request: web.Request) -> web.Response:
    return await _bridge_dispatch(request, "/current_app")


async def handle_bridge_tap(request: web.Request) -> web.Response:
    return await _bridge_dispatch(request, "/tap")


async def handle_bridge_tap_text(request: web.Request) -> web.Response:
    return await _bridge_dispatch(request, "/tap_text")


async def handle_bridge_long_press(request: web.Request) -> web.Response:
    # A1 long_press — new in v0.4 bridge feature expansion. Same forward-
    # through-to-phone pattern as /tap and /tap_text; the phone-side
    # BridgeCommandHandler validates args and clamps duration.
    return await _bridge_dispatch(request, "/long_press")


async def handle_bridge_type(request: web.Request) -> web.Response:
    return await _bridge_dispatch(request, "/type")


async def handle_bridge_swipe(request: web.Request) -> web.Response:
    return await _bridge_dispatch(request, "/swipe")


async def handle_bridge_drag(request: web.Request) -> web.Response:
    # Phase 3 / v0.4 bridge expansion A2: point-to-point drag with
    # explicit duration control. Routed to the phone via the same bridge
    # channel as /swipe — the phone's ActionExecutor.drag wraps a
    # single-stroke GestureDescription in a wake-lock scope.
    return await _bridge_dispatch(request, "/drag")


async def handle_bridge_open_app(request: web.Request) -> web.Response:
    return await _bridge_dispatch(request, "/open_app")


async def handle_bridge_press_key(request: web.Request) -> web.Response:
    return await _bridge_dispatch(request, "/press_key")


async def handle_bridge_scroll(request: web.Request) -> web.Response:
    return await _bridge_dispatch(request, "/scroll")


async def handle_bridge_describe_node(request: web.Request) -> web.Response:
    # A4: forwards POST /describe_node with a `{nodeId}` body through the
    # bridge channel. The phone resolves the ID via ScreenReader.findNodeById
    # and returns the full property bag.
    return await _bridge_dispatch(request, "/describe_node")


async def handle_bridge_wait(request: web.Request) -> web.Response:
    return await _bridge_dispatch(request, "/wait")


async def handle_bridge_setup(request: web.Request) -> web.Response:
    # /setup is the tool-side pairing helper that existed on the legacy
    # relay; the unified relay's pairing flow is handled via /pairing/*
    # but we keep the endpoint pluggable through the bridge channel for
    # phones that still implement it. If the phone doesn't implement it,
    # the bridge.response will come back with a non-200 status.
    return await _bridge_dispatch(request, "/setup")


# A6: clipboard bridge. One path, two methods — GET reads, POST writes.
# The phone's BridgeCommandHandler dispatches on the `method` field inside
# the bridge.command envelope, so we forward the method unchanged via the
# existing _bridge_dispatch helper (which already carries request.method
# through to BridgeHandler.handle_command → envelope payload).
async def handle_bridge_clipboard(request: web.Request) -> web.Response:
    return await _bridge_dispatch(request, "/clipboard")


# A7: media playback control — play/pause/toggle/next/previous via
# system-wide ACTION_MEDIA_BUTTON broadcast on the phone side. The
# body is forwarded verbatim; the phone validates the action string.
async def handle_bridge_media(request: web.Request) -> web.Response:
    return await _bridge_dispatch(request, "/media")


# A3: filtered accessibility-tree search — the phone's BridgeCommandHandler
# delegates to ScreenReader.searchNodes which walks all windows and
# filters by text/class_name/clickable, returning up to `limit` matches.
# Body shape (all optional except limit defaulting): {text, class_name,
# clickable, limit}.
async def handle_bridge_find_nodes(request: web.Request) -> web.Response:
    return await _bridge_dispatch(request, "/find_nodes")


# A5 — cheap change detection. /screen_hash is a GET so the tool side
# can call it from a simple `_get(...)`; /diff_screen takes the prior
# hash in a JSON body and is therefore POST.
async def handle_bridge_screen_hash(request: web.Request) -> web.Response:
    return await _bridge_dispatch(request, "/screen_hash")


async def handle_bridge_diff_screen(request: web.Request) -> web.Response:
    return await _bridge_dispatch(request, "/diff_screen")


# B4 — raw Intent escape hatch. `/send_intent` launches an Activity via
# `Context.startActivity` on the phone; `/broadcast` fires a broadcast
# via `Context.sendBroadcast`. Both accept an optional `package` field
# for blocklist gating on the phone side.
async def handle_bridge_send_intent(request: web.Request) -> web.Response:
    return await _bridge_dispatch(request, "/send_intent")


async def handle_bridge_broadcast(request: web.Request) -> web.Response:
    return await _bridge_dispatch(request, "/broadcast")


# B1 event-stream: poll recent AccessibilityEvents + toggle capture.
# Same trust model as the other bridge HTTP routes (host-gated via
# loopback), delegated straight through to the phone's EventStore via
# the bridge channel.
async def handle_bridge_events_recent(request: web.Request) -> web.Response:
    return await _bridge_dispatch(request, "/events")


async def handle_bridge_events_stream(request: web.Request) -> web.Response:
    return await _bridge_dispatch(request, "/events/stream")


# ── Tier C routes (C1-C4) — sideload-only on the phone side ──────────────────
#
# The Android client gates these paths on `BuildFlavor.isSideload` and
# returns 403 with `"sideload-only"` on googlePlay builds. The relay still
# exposes the routes on both flavors because it's flavor-blind — the gate
# lives on the phone.
async def handle_bridge_location(request: web.Request) -> web.Response:
    return await _bridge_dispatch(request, "/location")


async def handle_bridge_search_contacts(request: web.Request) -> web.Response:
    return await _bridge_dispatch(request, "/search_contacts")


async def handle_bridge_call(request: web.Request) -> web.Response:
    return await _bridge_dispatch(request, "/call")


async def handle_bridge_send_sms(request: web.Request) -> web.Response:
    return await _bridge_dispatch(request, "/send_sms")


# === END PHASE3-bridge-server ===


# === PHASE3-status: GET /bridge/status ===
#
# Loopback-only HTTP read endpoint for the cached bridge.status payload
# managed by ``BridgeHandler``. Called by the host-local
# ``android_phone_status()`` tool so the agent can see which phone-side
# permissions are granted BEFORE it makes a bridge command that would
# otherwise 403.
#
# Trust model matches ``/pairing/register`` and ``/media/register``:
# only processes on the same host (loopback) can read this. The agent's
# tool runs in the same Python venv as the relay, so 127.0.0.1 /::1 is
# always how it arrives. The data leaks package names + battery level,
# which is fine to share with a co-hosted agent but not something we
# want exposed to the LAN.
#
# Response shape matches the JSON contract consumed by
# ``android_phone_status()``. The phone-side ``BridgeStatusReporter``
# emits the nested groups verbatim, so the endpoint just wraps them
# with ``phone_connected`` + ``last_seen_seconds_ago`` and returns.


async def handle_bridge_status(request: web.Request) -> web.Response:
    """Return the most recent cached phone status.

    GET /bridge/status
      → 200 {"phone_connected": true, "last_seen_seconds_ago": N, ...}
        when the phone has pushed at least one bridge.status envelope
      → 503 {"phone_connected": false, "error": "no phone connected"}
        when no phone has ever connected to this relay process
      → 403 non-loopback caller
    """
    remote = request.remote or ""
    if remote not in ("127.0.0.1", "::1"):
        logger.warning(
            "Rejected /bridge/status from non-loopback peer %s", remote
        )
        raise web.HTTPForbidden(
            text="/bridge/status is restricted to localhost callers",
        )

    server: RelayServer = request.app["server"]
    bridge_handler = server.bridge

    if bridge_handler.latest_status is None:
        return web.json_response(
            {
                "phone_connected": False,
                "last_seen_seconds_ago": None,
                "error": "no phone connected",
            },
            status=503,
        )

    payload: dict[str, Any] = dict(bridge_handler.latest_status)
    last_seen_at = bridge_handler.last_seen_at
    if last_seen_at is None:
        last_seen_seconds_ago: int | None = None
    else:
        # Round to whole seconds — sub-second resolution is noise for a
        # status-cache consumer and keeps the JSON compact.
        last_seen_seconds_ago = max(0, int(time.time() - last_seen_at))

    response = {
        "phone_connected": bridge_handler.is_phone_connected(),
        "last_seen_seconds_ago": last_seen_seconds_ago,
    }
    # Merge the phone-provided groups (device / bridge / safety + any
    # legacy fields) into the top-level response. If the phone is on an
    # older build that only sends flat keys, they come through here too.
    response.update(payload)
    return web.json_response(response)


# === END PHASE3-status ===


# ── Dashboard-plugin loopback routes ─────────────────────────────────────────
#
# Three loopback-only endpoints surfaced for the co-hosted dashboard
# plugin. They expose relay state (bridge command ring buffer, media
# registry snapshot, aggregate /relay/info) that upstream can't see.
# Loopback-gated because the dashboard runs inside the gateway process
# on the same host; no bearer is needed.


async def handle_bridge_activity(request: web.Request) -> web.Response:
    """Return the recent bridge-command ring buffer (newest-first).

    GET /bridge/activity?limit=N
      → 200 {"activity": [...]}
      → 403 non-loopback caller
    """
    _require_loopback(request)

    raw_limit = request.query.get("limit")
    limit = 100
    if raw_limit is not None:
        try:
            parsed = int(raw_limit)
            if 1 <= parsed <= 500:
                limit = parsed
            elif parsed > 500:
                limit = 500
            elif parsed < 1:
                limit = 100
        except (TypeError, ValueError):
            limit = 100

    server: RelayServer = request.app["server"]
    activity = server.bridge.get_recent(limit)
    return web.json_response({"activity": activity})


async def handle_media_inspect(request: web.Request) -> web.Response:
    """Return a sanitized snapshot of the MediaRegistry.

    GET /media/inspect?include_expired=true
      → 200 {"media": [...]}
      → 403 non-loopback caller
    """
    _require_loopback(request)

    raw = request.query.get("include_expired", "")
    include_expired = raw.strip().lower() in ("1", "true", "yes")

    server: RelayServer = request.app["server"]
    media = await server.media.list_all(include_expired=include_expired)
    return web.json_response({"media": media})


async def handle_relay_info(request: web.Request) -> web.Response:
    """Aggregate relay-status endpoint consumed by the dashboard overview tab.

    GET /relay/info
      → 200 {version, uptime_seconds, session_count, paired_device_count,
             pending_commands, media_entry_count, health}
      → 403 non-loopback caller
    """
    _require_loopback(request)

    server: RelayServer = request.app["server"]
    uptime_seconds = int(time.monotonic() - server.start_time)
    session_count = len(server.sessions.list_sessions())
    pending_commands = len(server.bridge.pending)
    media_entry_count = await server.media.size()

    return web.json_response(
        {
            "version": __version__,
            "uptime_seconds": uptime_seconds,
            "session_count": session_count,
            # Sessions == paired devices in the current auth model.
            "paired_device_count": session_count,
            "pending_commands": pending_commands,
            "media_entry_count": media_entry_count,
            "health": "ok",
        }
    )


# ── Profile-scoped read-only config + skills ────────────────────────────────
#
# Loopback-or-bearer gated (same pattern as /notifications/recent). The
# dashboard plugin proxies these over 127.0.0.1; the paired phone hits
# them with its relay session bearer. Both surfaces reach the same data
# with profile scoping applied server-side — no client-side directory
# walking, no secret leakage (SOUL.md is intentionally emitted as
# system_message via auth.ok; these endpoints focus on config + skill
# metadata).
#
# READ ONLY for now — profile writes require an active_profile routing
# layer we haven't built yet. See docs/decisions.md §22.


def _resolve_profile_home(server: "RelayServer", name: str) -> Path | None:
    """Return the on-disk home directory for a profile name.

    ``"default"`` maps to the parent of ``hermes_config_path`` (i.e.
    ``~/.hermes``). Every other name maps to
    ``<hermes_dir>/profiles/<name>``. Returns ``None`` if the target
    directory does not exist — callers surface this as a 404.
    """
    root_config = Path(server.config.hermes_config_path).expanduser()
    hermes_dir = root_config.parent

    if name == "default":
        home = hermes_dir
    else:
        # Reject path traversal: profile name must be a plain directory
        # token, no separators, no "..".
        if not name or "/" in name or "\\" in name or name in (".", ".."):
            return None
        home = hermes_dir / "profiles" / name

    return home if home.is_dir() else None


def _parse_skill_frontmatter(text: str) -> dict[str, Any]:
    """Best-effort parse of the leading ``---`` YAML frontmatter block.

    Mirrors the shape used by upstream Hermes skills and our own
    ``skills/`` tree. Returns ``{}`` on any parse failure or when no
    frontmatter is present. We never raise — skill listing must tolerate
    hand-written SKILL.md files.
    """
    if not text.startswith("---"):
        return {}
    # Split on the first pair of --- lines.
    try:
        _, rest = text.split("---", 1)
        body, _ = rest.split("\n---", 1)
    except ValueError:
        return {}
    try:
        data = yaml.safe_load(body)
    except Exception:
        return {}
    return data if isinstance(data, dict) else {}


async def handle_profile_config(request: web.Request) -> web.Response:
    """Return the parsed ``config.yaml`` for a named profile.

    GET /api/profiles/{name}/config
      → 200 {"profile", "path", "config": {...}, "readonly": true}
      → 401 missing/invalid bearer (remote callers only)
      → 404 profile dir missing or no config.yaml
      → 500 yaml parse error

    Loopback callers may skip bearer auth (matches
    ``/notifications/recent``); remote callers must present a valid
    relay session token.
    """
    is_loopback = request.remote in ("127.0.0.1", "::1")
    if is_loopback:
        server: RelayServer = request.app["server"]
    else:
        server, _session = _require_bearer_session(request)

    name = request.match_info.get("name", "").strip()
    if not name:
        return web.json_response(
            {"ok": False, "error": "missing profile name"}, status=400
        )

    home = _resolve_profile_home(server, name)
    if home is None:
        return web.json_response(
            {"ok": False, "error": "profile not found", "profile": name},
            status=404,
        )

    config_path = home / "config.yaml"
    if not config_path.is_file():
        return web.json_response(
            {
                "ok": False,
                "error": "config.yaml not found for profile",
                "profile": name,
            },
            status=404,
        )

    try:
        with open(config_path, "r", encoding="utf-8") as fh:
            parsed = yaml.safe_load(fh)
    except Exception as exc:
        logger.warning(
            "Profile config read failed for %r at %s: %s",
            name,
            config_path,
            exc,
        )
        return web.json_response(
            {
                "ok": False,
                "error": "failed to parse config.yaml",
                "profile": name,
                "detail": str(exc),
            },
            status=500,
        )

    if parsed is None:
        parsed = {}

    return web.json_response(
        {
            "profile": name,
            "path": str(config_path),
            "config": parsed,
            "readonly": True,
        }
    )


async def handle_profile_skills(request: web.Request) -> web.Response:
    """Enumerate skills under a profile's ``skills/<category>/<name>/SKILL.md``.

    GET /api/profiles/{name}/skills
      → 200 {"profile", "skills": [...], "total": N}
      → 401 missing/invalid bearer (remote callers only)
      → 404 profile dir missing

    Each skill entry:
      {name, category, description, path, enabled: true}

    ``name``/``description`` come from YAML frontmatter when present,
    falling back to the directory basename and an empty string. Skills
    always report ``enabled: true`` — disabled-skill tracking is an
    upstream concern (see ``PUT /api/skills/toggle`` in the bootstrap,
    which is 501 today).
    """
    is_loopback = request.remote in ("127.0.0.1", "::1")
    if is_loopback:
        server: RelayServer = request.app["server"]
    else:
        server, _session = _require_bearer_session(request)

    name = request.match_info.get("name", "").strip()
    if not name:
        return web.json_response(
            {"ok": False, "error": "missing profile name"}, status=400
        )

    home = _resolve_profile_home(server, name)
    if home is None:
        return web.json_response(
            {"ok": False, "error": "profile not found", "profile": name},
            status=404,
        )

    skills_dir = home / "skills"
    skills: list[dict[str, Any]] = []

    if skills_dir.is_dir():
        try:
            skill_files = sorted(skills_dir.rglob("SKILL.md"))
        except OSError:
            skill_files = []

        for skill_md in skill_files:
            try:
                rel = skill_md.relative_to(skills_dir)
            except ValueError:
                continue
            parts = rel.parts
            if len(parts) < 2:
                # Skip files that live directly in skills/ — we require a
                # category directory.
                continue
            category = parts[0]
            skill_name = parts[-2]

            description = ""
            fm: dict[str, Any] = {}
            try:
                fm = _parse_skill_frontmatter(
                    skill_md.read_text(encoding="utf-8")
                )
            except Exception:
                fm = {}

            fm_name = fm.get("name")
            if isinstance(fm_name, str) and fm_name.strip():
                skill_name = fm_name.strip()
            fm_desc = fm.get("description")
            if isinstance(fm_desc, str):
                description = fm_desc.strip()

            skills.append(
                {
                    "name": skill_name,
                    "category": category,
                    "description": description,
                    "path": str(skill_md),
                    "enabled": True,
                }
            )

    return web.json_response(
        {
            "profile": name,
            "skills": skills,
            "total": len(skills),
        }
    )


# ── Profile-scoped SOUL.md + memory read endpoints ──────────────────────────
#
# Same auth + path-traversal model as ``/config`` and ``/skills`` above.
# These feed the phone's Profile Inspector viewer — READ ONLY. Content is
# capped to phone-safe sizes (SOUL: 200KB, each memory file: 50KB). The
# Inspector is a viewer, not a diff tool — see docs/decisions.md §22.

# Max bytes of SOUL.md content returned inline before truncation.
_PROFILE_SOUL_MAX_BYTES = 200 * 1024
# Max bytes per memory file returned inline before truncation.
_PROFILE_MEMORY_MAX_BYTES = 50 * 1024


def _read_profile_soul(soul_path: Path) -> tuple[str, bool, int]:
    """Read ``SOUL.md`` with a 200KB inline cap.

    Returns ``(content, truncated, size_bytes)`` where ``size_bytes`` is
    the file's on-disk size (not the length of the returned string). May
    raise ``OSError``/``UnicodeDecodeError`` — callers translate those
    into HTTP 500 with ``error: "soul_read_failed"``.
    """
    size_bytes = soul_path.stat().st_size
    with open(soul_path, "r", encoding="utf-8") as fh:
        content = fh.read(_PROFILE_SOUL_MAX_BYTES + 1)
    if len(content) > _PROFILE_SOUL_MAX_BYTES:
        return content[:_PROFILE_SOUL_MAX_BYTES], True, size_bytes
    return content, False, size_bytes


async def handle_profile_soul(request: web.Request) -> web.Response:
    """Return the raw ``SOUL.md`` for a named profile.

    GET /api/profiles/{name}/soul
      → 200 {"profile", "path", "content", "exists", "size_bytes", [truncated]}
      → 401 missing/invalid bearer (remote callers only)
      → 404 {"error": "profile_not_found", "profile": name}
      → 500 {"error": "soul_read_failed", "detail": "..."}

    Absent SOUL.md is NOT an error — returns 200 with ``exists: false``
    and an empty string body. Content over 200KB is truncated with
    ``truncated: true``.
    """
    is_loopback = request.remote in ("127.0.0.1", "::1")
    if is_loopback:
        server: RelayServer = request.app["server"]
    else:
        server, _session = _require_bearer_session(request)

    name = request.match_info.get("name", "").strip()
    if not name:
        return web.json_response(
            {"error": "profile_not_found", "profile": name}, status=404
        )

    home = _resolve_profile_home(server, name)
    if home is None:
        return web.json_response(
            {"error": "profile_not_found", "profile": name}, status=404
        )

    soul_path = home / "SOUL.md"

    if not soul_path.is_file():
        return web.json_response(
            {
                "profile": name,
                "path": str(soul_path),
                "content": "",
                "exists": False,
                "size_bytes": 0,
            }
        )

    try:
        content, truncated, size_bytes = _read_profile_soul(soul_path)
    except Exception as exc:
        logger.warning(
            "Profile SOUL read failed for %r at %s: %s",
            name,
            soul_path,
            exc,
        )
        return web.json_response(
            {"error": "soul_read_failed", "detail": str(exc)},
            status=500,
        )

    payload: dict[str, Any] = {
        "profile": name,
        "path": str(soul_path),
        "content": content,
        "exists": True,
        "size_bytes": size_bytes,
    }
    if truncated:
        payload["truncated"] = True
    return web.json_response(payload)


async def handle_profile_memory(request: web.Request) -> web.Response:
    """Return the memory files under ``<profile_home>/memories/``.

    GET /api/profiles/{name}/memory
      → 200 {"profile", "memories_dir", "entries": [...], "total"}
      → 401 missing/invalid bearer (remote callers only)
      → 404 {"error": "profile_not_found", "profile": name}

    Only the top-level ``*.md`` files are listed — we intentionally do
    NOT recurse. Per upstream (``hermes_cli/profiles.py``) MEMORY.md and
    USER.md are the canonical files; anything else the user dropped in
    the directory also surfaces. MEMORY.md and USER.md sort first (in
    that order); the rest are alphabetical. Each file's content is
    capped at 50KB; larger files set ``truncated: true``.

    An absent memories dir is NOT an error — returns an empty list.
    """
    is_loopback = request.remote in ("127.0.0.1", "::1")
    if is_loopback:
        server: RelayServer = request.app["server"]
    else:
        server, _session = _require_bearer_session(request)

    name = request.match_info.get("name", "").strip()
    if not name:
        return web.json_response(
            {"error": "profile_not_found", "profile": name}, status=404
        )

    home = _resolve_profile_home(server, name)
    if home is None:
        return web.json_response(
            {"error": "profile_not_found", "profile": name}, status=404
        )

    memories_dir = home / "memories"
    entries: list[dict[str, Any]] = []

    if memories_dir.is_dir():
        try:
            md_files = [
                p
                for p in memories_dir.iterdir()
                if p.is_file() and p.suffix == ".md"
            ]
        except OSError:
            md_files = []

        # MEMORY.md first, then USER.md, then the rest alphabetical by
        # filename (case-insensitive for predictable ordering on macOS).
        priority = {"MEMORY.md": 0, "USER.md": 1}

        def _sort_key(path: Path) -> tuple[int, str]:
            return (priority.get(path.name, 2), path.name.lower())

        md_files.sort(key=_sort_key)

        for md_path in md_files:
            try:
                size_bytes = md_path.stat().st_size
            except OSError:
                continue
            try:
                with open(md_path, "r", encoding="utf-8") as fh:
                    raw = fh.read(_PROFILE_MEMORY_MAX_BYTES + 1)
            except Exception as exc:
                logger.warning(
                    "Profile memory read failed for %r at %s: %s",
                    name,
                    md_path,
                    exc,
                )
                continue

            truncated = len(raw) > _PROFILE_MEMORY_MAX_BYTES
            if truncated:
                raw = raw[:_PROFILE_MEMORY_MAX_BYTES]

            stem = md_path.stem  # "MEMORY.md" → "MEMORY"
            entries.append(
                {
                    "name": stem,
                    "filename": md_path.name,
                    "path": str(md_path),
                    "content": raw,
                    "size_bytes": size_bytes,
                    "truncated": truncated,
                }
            )

    return web.json_response(
        {
            "profile": name,
            "memories_dir": str(memories_dir),
            "entries": entries,
            "total": len(entries),
        }
    )


# === PHASE3-notif-listener: notifications HTTP routes ===
#
# Bearer-auth'd HTTP read endpoint for the cached notification deque
# managed by ``NotificationsChannel``. The agent calls this through the
# ``android_notifications_recent`` tool to answer "what came in
# recently?" questions during a chat turn.
#
# The trust model matches every other phone-facing relay HTTP endpoint
# (``/media/*``, ``/sessions``, ``/voice/*``): a valid relay session
# token in ``Authorization: Bearer ...`` is required, and the same
# token serves as proof that the caller is one of the operator's
# paired devices.


async def handle_notifications_recent(request: web.Request) -> web.Response:
    """Return the most-recent N cached notification entries.

    Two callers, two auth modes:
      * **Loopback callers** (the in-process Hermes tool
        ``android_notifications_recent``) skip bearer auth — the trust
        boundary is host access, identical to ``/media/register`` and
        ``/pairing/register``. The agent runs on the same host as the
        relay, so by-definition tool calls hit us over 127.0.0.1.
      * **Remote callers** (a paired phone or other client) must
        present a valid relay session token via
        ``Authorization: Bearer <token>`` — this is the same gate as
        every other phone-facing relay HTTP route.

    GET /notifications/recent?limit=20
      → 200 {"notifications": [...], "count": <int>}
      → 400 invalid limit
      → 401 missing/invalid bearer (remote callers only)
    """
    remote = request.remote or ""
    is_loopback = remote in ("127.0.0.1", "::1")

    server: RelayServer
    if is_loopback:
        server = request.app["server"]
    else:
        server, _session = _require_bearer_session(request)

    raw_limit = request.query.get("limit", "20")
    try:
        limit = int(raw_limit)
    except (TypeError, ValueError):
        return web.json_response(
            {"ok": False, "error": "limit must be an integer"}, status=400
        )
    if limit < 1:
        return web.json_response(
            {"ok": False, "error": "limit must be >= 1"}, status=400
        )

    entries = server.notifications.get_recent(limit)
    return web.json_response(
        {
            "ok": True,
            "notifications": entries,
            "count": len(entries),
        }
    )


# === END PHASE3-notif-listener ===


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

    # ``profiles`` is the result of ``_load_profiles()`` — a list of
    # snake_case dicts with ``name``, ``model``, ``description``, and
    # ``system_message`` (which may be ``None``). The Kotlin client
    # deserializes into PairedDeviceInfo.profiles.
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
    # === PHASE3-notif-listener: notifications channel dispatch ===
    elif channel == "notifications":
        task = asyncio.create_task(server.notifications.handle(ws, envelope))
        _track_task(server, ws, task)
    # === END PHASE3-notif-listener ===
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

    # === PHASE3-bridge-server: fail in-flight bridge commands on phone disconnect ===
    # If this ws was the currently-latched phone, detach_ws flips phone_ws
    # back to None and resolves every pending bridge command future with a
    # ConnectionError so the HTTP side returns 502 instead of hanging to
    # the 30s timeout on every in-flight request_id.
    await server.bridge.detach_ws(ws, reason=f"client {remote_ip} disconnected")
    # === END PHASE3-bridge-server ===

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
    app.router.add_post("/pairing/mint", handle_pairing_mint)
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
    # === PHASE3-bridge-server-followup: /media/upload ===
    app.router.add_post("/media/upload", handle_media_upload)
    # === END PHASE3-bridge-server-followup ===
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

    # === PHASE3-bridge-server: bridge HTTP routes ===
    # 14 endpoints mirrored from the legacy standalone relay on port 8766.
    # Tool-side caller is plugin/tools/android_tool.py — only its
    # BRIDGE_URL changes, the wire shape is byte-for-byte identical.
    app.router.add_get("/ping", handle_bridge_ping)
    app.router.add_get("/screen", handle_bridge_screen)
    app.router.add_get("/screenshot", handle_bridge_screenshot)
    app.router.add_get("/get_apps", handle_bridge_get_apps)
    app.router.add_get("/apps", handle_bridge_apps_legacy)
    app.router.add_get("/current_app", handle_bridge_current_app)
    app.router.add_post("/tap", handle_bridge_tap)
    app.router.add_post("/tap_text", handle_bridge_tap_text)
    app.router.add_post("/long_press", handle_bridge_long_press)
    app.router.add_post("/type", handle_bridge_type)
    app.router.add_post("/swipe", handle_bridge_swipe)
    app.router.add_post("/drag", handle_bridge_drag)
    app.router.add_post("/open_app", handle_bridge_open_app)
    app.router.add_post("/press_key", handle_bridge_press_key)
    app.router.add_post("/scroll", handle_bridge_scroll)
    app.router.add_post("/describe_node", handle_bridge_describe_node)  # A4
    app.router.add_post("/wait", handle_bridge_wait)
    app.router.add_post("/setup", handle_bridge_setup)
    # A6: clipboard bridge — one path, two methods
    app.router.add_get("/clipboard", handle_bridge_clipboard)
    app.router.add_post("/clipboard", handle_bridge_clipboard)
    # A7: media playback control
    app.router.add_post("/media", handle_bridge_media)
    # A3: filtered accessibility-tree search
    app.router.add_post("/find_nodes", handle_bridge_find_nodes)
    # A5: screen-hash change detection
    app.router.add_get("/screen_hash", handle_bridge_screen_hash)
    app.router.add_post("/diff_screen", handle_bridge_diff_screen)
    # B4: raw Intent escape hatch
    app.router.add_post("/send_intent", handle_bridge_send_intent)
    app.router.add_post("/broadcast", handle_bridge_broadcast)
    # B1: event-stream — poll + toggle AccessibilityEvent capture
    app.router.add_get("/events", handle_bridge_events_recent)
    app.router.add_post("/events/stream", handle_bridge_events_stream)
    # Tier C (C1-C4): sideload-gated on the phone side
    app.router.add_get("/location", handle_bridge_location)
    app.router.add_post("/search_contacts", handle_bridge_search_contacts)
    app.router.add_post("/call", handle_bridge_call)
    app.router.add_post("/send_sms", handle_bridge_send_sms)
    # === END PHASE3-bridge-server ===

    # === PHASE3-status: loopback-gated structured phone status ===
    app.router.add_get("/bridge/status", handle_bridge_status)
    # === END PHASE3-status ===

    # Dashboard-plugin loopback routes — see handlers above.
    app.router.add_get("/bridge/activity", handle_bridge_activity)
    app.router.add_get("/media/inspect", handle_media_inspect)
    app.router.add_get("/relay/info", handle_relay_info)

    # === PHASE3-notif-listener: notifications HTTP routes ===
    app.router.add_get("/notifications/recent", handle_notifications_recent)

    # Profile-scoped read-only config + skills (§22).
    app.router.add_get(
        "/api/profiles/{name}/config", handle_profile_config
    )
    app.router.add_get(
        "/api/profiles/{name}/skills", handle_profile_skills
    )
    app.router.add_get(
        "/api/profiles/{name}/soul", handle_profile_soul
    )
    app.router.add_get(
        "/api/profiles/{name}/memory", handle_profile_memory
    )
    # === END PHASE3-notif-listener ===

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
        config.profiles = _load_profiles(
            config.hermes_config_path,
            enabled=config.profile_discovery_enabled,
        )
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
        "Profile discovery: %s",
        "enabled" if config.profile_discovery_enabled else "disabled",
    )
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
