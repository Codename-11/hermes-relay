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
import base64
import binascii
import json
import logging
import math
import mimetypes
import os
import secrets
import signal
import ssl
import stat
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
    _PAIRING_CODE_TTL,
    PairingManager,
    RateLimiter,
    Session,
    SessionManager,
)
from .channels.bridge import BridgeError, BridgeHandler
from .channels.chat import ChatHandler
from .channels.desktop import DesktopChannel
from .channels.notifications import NotificationsChannel
from .channels.proactive import ProactiveChannel, ProactiveError
from .channels.terminal import TerminalHandler
from .channels.tui import TuiHandler
from .config import RelayConfig
from .media import MediaRegistrationError, MediaRegistry, validate_media_path
from .session_store import read_phone_threads
from .voice import VoiceHandler
from .voice_output import VoiceOutputHandler
from .realtime_voice import RealtimeVoiceHandler
from .realtime_agent import RealtimeAgentHandler
from ..enhancements.context_injection import injected_context_payload

logger = logging.getLogger("hermes_relay")


# ── Server state ─────────────────────────────────────────────────────────────


class RelayServer:
    """Holds all mutable state for the running relay."""

    def __init__(self, config: RelayConfig) -> None:
        self.config = config
        self.start_time: float = time.monotonic()

        # Auth — SessionManager persistence is opt-in via
        # ``config.session_persistence_path``. ``RelayConfig.from_env``
        # sets it to ``<hermes_config_path.parent>/hermes-relay-sessions.json``
        # for real startups; tests constructing ``RelayConfig()``
        # directly get None (in-memory only), preserving the previous
        # test isolation guarantees.
        self.pairing = PairingManager()
        self.sessions = SessionManager(
            persistence_path=config.session_persistence_path,
        )
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
        self.voice_output = VoiceOutputHandler(config)
        self.realtime_voice = RealtimeVoiceHandler(config)
        self.realtime_agent = RealtimeAgentHandler(config)

        # Channel handlers
        self.chat = ChatHandler(webapi_url=config.webapi_url)
        self.terminal = TerminalHandler(default_shell=config.terminal_shell)
        self.bridge = BridgeHandler()
        self.tui = TuiHandler()
        # === PHASE3-notif-listener: notifications channel ===
        self.notifications = NotificationsChannel()
        # === END PHASE3-notif-listener ===
        # Proactive channel — agent-initiated messages pushed to the phone
        # (send_message target=phone). Mirror of bridge, reversed: server→app
        # push with no awaited reply. Latched on proactive.subscribe.
        self.proactive = ProactiveChannel()
        # Durable fallback for realtime voice: a background run whose result
        # never reached the phone (voice session died before resume) is pushed
        # as a proactive phone message — buffered while the phone is offline.
        self.realtime_agent.proactive_push = self.proactive.push
        # Desktop CLI awareness channel — stashes workspace + active-editor
        # hints per session (ephemeral; no persistence). Wired in alpha.6
        # as the keystone for future prompt-injection plugin hooks.
        self.desktop = DesktopChannel()

        # Connected clients: ws → session token
        self._clients: dict[web.WebSocketResponse, str] = {}

        # In-flight tasks per client (for cancellation on disconnect)
        self._client_tasks: dict[web.WebSocketResponse, set[asyncio.Task[Any]]] = {}

        # Negotiated websocket capabilities per connected client. Kept outside
        # Session so reconnects can renegotiate independently and older stored
        # tokens do not accidentally opt in to a new protocol.
        self._client_capabilities: dict[web.WebSocketResponse, dict[str, Any]] = {}

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
        await self.desktop.close()
        await self.tui.close()
        await self.proactive.close()

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
      "host": "192.168.1.100", "port": 8642, "key": "<api-key>", "tls": false,
      "relay": {"url": "ws://192.168.1.100:8767", "code": "ABC123",
                "ttl_seconds": 604800, "transport_hint": "ws"}
    }
    ```

    This mirrors ``pair.py``'s CLI ``build_payload(...)`` call. Before
    2026-04-18 the endpoint put the minted code in top-level ``key`` and
    emitted top-level ``port`` as the relay port, which broke scanning —
    the phone treated the relay port as the API server address and the
    relay block had no url/code for it to open a WSS against.

    POST /pairing/mint
      body (all optional — fall back to RelayConfig / local Hermes defaults):
        - host: "192.168.1.100"        API server host override (LAN IP)
        - port: 8642                    API server port override
        - tls: false                    API server TLS override
        - api_key: "<token>"            API bearer token override (goes in
                                        top-level "key"; omitted = read the
                                        same local config as hermes-pair,
                                        explicit empty = open access)
        - ttl_seconds: <int>            Session TTL
        - transport_hint: "wss"|"ws"    Forwarded verbatim
        - grants: {...}                 Channel TTL map
      → 200 {ok, code, qr_payload, pairing_url, expires_at, host, port, tls, relay_url}
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

    from ..pair import (
        build_pairing_invite_url,
        build_pairing_qr_payload,
        build_relay_pairing_block,
        normalize_endpoint_candidates,
        read_server_config,
        _relay_lan_base_url,
        _resolve_lan_ip,
    )

    # ── API server info (top-level of the QR payload) ────────────────────
    # Defaults come from ``RelayConfig.webapi_url`` (the Hermes API gateway
    # URL the relay is fronting). The dashboard's "editable pair URL" UI
    # overrides any of host/port/tls via the request body.
    default_api_url = urlparse(server.config.webapi_url or "http://localhost:8642")
    api_host_override = payload.get("host")
    api_port_override = payload.get("port")
    api_tls_override = payload.get("tls")
    dashboard_url_raw = payload.get("dashboard_url") or payload.get("dashboardUrl")

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

    if "api_key" in payload:
        api_key_raw = payload.get("api_key")
        api_key = str(api_key_raw) if api_key_raw is not None else ""
    else:
        try:
            api_key = str(read_server_config().get("key") or "")
        except Exception as exc:  # pragma: no cover - defensive config fallback
            logger.warning(
                "Pairing mint could not read Hermes API key from local config: %s",
                exc,
            )
            api_key = ""
    dashboard_url = (
        str(dashboard_url_raw).strip().rstrip("/")
        if dashboard_url_raw is not None
        else None
    ) or None

    # ── Pairing metadata ─────────────────────────────────────────────────
    ttl_seconds, grants, transport_hint, err = _parse_pairing_metadata(payload)
    if err is not None:
        return web.json_response({"ok": False, "error": err}, status=400)

    # Optional v3 multi-endpoint array (ADR 24). The caller (dashboard
    # "pair new device" UI) composes this; the relay validates only the
    # outer array shape here. Server-owned normalization happens below,
    # immediately before QR signing, so the signed payload and response
    # still round-trip consistently.
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
    if endpoints_list is not None:
        normalized_endpoints = normalize_endpoint_candidates(
            endpoints_list,
            api_port=api_port,
            relay_port=server.config.port,
            api_tls=api_tls,
            relay_tls=relay_tls,
        )
        if normalized_endpoints != endpoints_list:
            logger.info(
                "Normalized pairing endpoint candidates before QR signing",
            )
        endpoints_list = normalized_endpoints

    relay_block = build_relay_pairing_block(
        relay_url=relay_url,
        code=code,
        ttl_seconds=ttl_seconds,
        grants=grants,
        transport_hint=transport_hint,
    )

    qr_payload = build_pairing_qr_payload(
        host=api_host,
        port=api_port,
        key=api_key,
        tls=api_tls,
        relay=relay_block,
        sign=True,
        endpoints=endpoints_list or None,
        dashboard_url=dashboard_url,
    )
    pairing_url = build_pairing_invite_url(qr_payload)

    # This is the pairing-code expiry (how long the user has to scan),
    # not the future session's TTL. The pairing-code hard-cap is
    # ``_PAIRING_CODE_TTL`` (10 minutes); the per-session TTL, if the
    # caller pinned one, is stamped into ``grants`` / the QR payload's
    # top-level ``ttl_seconds`` — it governs post-pair session life, not
    # the window to scan. Earlier code conflated the two and defaulted
    # to 60s when no session TTL was passed, which made dashboard-minted
    # QRs appear to expire in ~1 minute even though the code itself was
    # valid for 10.
    expires_at = int(time.time() + _PAIRING_CODE_TTL)

    # Same rationale as /pairing/register and /pairing/approve — a phone
    # self-banned on the rate limiter (e.g. reconnect-loop after a relay
    # restart invalidated its session token) must not be left unpairable
    # just because the minting path is different. Any loopback-originated
    # mint implies operator intent to pair, so wiping the block table is
    # safe and matches the other two pairing entry points.
    server.rate_limiter.clear_all_blocks()

    logger.info(
        "Minted pairing code via /pairing/mint: %s "
        "(api=%s://%s:%d api_key=%s relay=%s)",
        code,
        "https" if api_tls else "http",
        api_host,
        api_port,
        "present" if api_key else "absent",
        relay_url,
    )
    mint_response: dict[str, Any] = {
        "ok": True,
        "code": code,
        "qr_payload": qr_payload,
        "pairing_url": pairing_url,
        "expires_at": expires_at,
        "host": api_host,
        "port": api_port,
        "tls": api_tls,
        "relay_url": relay_url,
    }
    if dashboard_url is not None:
        mint_response["dashboard_url"] = dashboard_url
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
        "client_surface": session.client_surface,
        "device_form_factor": session.device_form_factor,
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
    """Reduce the calling device's session TTL and/or channel grants.

    A normal Relay bearer is session identity, not session-management
    authority.  It may reduce only its own live policy.  Extending a
    lifetime, adding or lengthening a grant, or changing another session
    requires a fresh operator-approved pairing flow.

    PATCH /sessions/{token_prefix}
      Body: {"ttl_seconds": 300}                  # shorten only
           | {"grants": {"terminal": 60}}         # reduce grants
           | {"ttl_seconds": 300, "grants": {...}}  # both
      → 200 {"ok": true, "expires_at": ..., "grants": {...}}
      → 400 missing/invalid body or no fields provided
      → 401 missing/invalid bearer
      → 404 prefix doesn't match any active session
      → 403 cross-session target or policy expansion
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
            if (
                not isinstance(k, str)
                or not isinstance(v, (int, float))
                or not math.isfinite(v)
                or v < 0
            ):
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
    if not secrets.compare_digest(target.token, current_session.token):
        raise web.HTTPForbidden(text="cannot modify another session")

    try:
        updated = server.sessions.reduce_session_policy(
            target.token,
            ttl_seconds=ttl_seconds,
            grants=grants,
        )
    except ValueError as exc:
        return web.json_response({"ok": False, "error": str(exc)}, status=400)
    except PermissionError as exc:
        raise web.HTTPForbidden(text=str(exc)) from exc
    if updated is None:
        # Raced with expiry or revocation between find_by_prefix and update.
        raise web.HTTPNotFound(text="session vanished mid-update, retry")

    logger.info(
        "Reduced session policy %s... (%s) [self]",
        target.token[:8],
        target.device_name,
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


# ── Desktop tool dispatch (loopback HTTP shim for desktop_tool.py) ──────────


async def handle_desktop_ping(request: web.Request) -> web.Response:
    """GET /desktop/_ping?tool=<name>
      → 200 {"ok": true}      a desktop client is connected AND advertises
                              the queried tool (or `tool` was omitted).
      → 503 {"ok": false}     no client connected, OR client connected but
                              hasn't advertised this specific tool.

    Loopback-only — the only legitimate caller is `plugin/tools/desktop_tool.py`
    running inside hermes-gateway on the same host. Phone bridges have their
    own auth model; desktop is mediated by the agent.
    """
    _require_loopback(request)
    server: RelayServer = request.app["server"]
    tool = request.query.get("tool", "").strip()
    if not server.desktop.is_client_connected():
        return web.json_response(
            {"ok": False, "error": "no desktop client connected"},
            status=503,
        )
    if tool and not server.desktop.has_client_for(tool):
        return web.json_response(
            {"ok": False, "error": f"client connected but does not advertise tool {tool!r}"},
            status=503,
        )
    return web.json_response({"ok": True})


async def handle_desktop_health(request: web.Request) -> web.Response:
    """GET /desktop/health
      → 200 {
          connected: bool,
          host?, platform?, arch?, node?, version?, pid?,
          uptime_ms?, started_at_ms?,
          advertised_tools: [...],
          pending_commands: int,
          last_seen_at?: float,
          last_error?: {message, tool, ts},
          recent_commands: [...]   # last 20
        }

    Loopback-only — same surface as ``/desktop/_ping``, but returns the full
    snapshot (client metadata, advertised tools, recent activity) for the
    ``desktop_health`` agent tool and the dashboard. This route exists so
    a daemon-style desktop client can be inspected without round-tripping
    through ``desktop.command``: the latest ``desktop.status`` heartbeat
    already carries everything we need.
    """
    _require_loopback(request)
    server: RelayServer = request.app["server"]
    snap = server.desktop.status_snapshot()
    cs = snap.get("client_status") or {}
    out = {
        "connected": snap.get("connected", False),
        "advertised_tools": snap.get("advertised_tools") or [],
        "pending_commands": snap.get("pending_commands", 0),
        "last_seen_at": snap.get("last_seen_at"),
        # Surface every interesting field the client heartbeat provides;
        # missing keys just become None in the response, which the agent
        # tool handles gracefully.
        "host": cs.get("host"),
        "platform": cs.get("platform"),
        "arch": cs.get("arch"),
        "node": cs.get("node"),
        "version": cs.get("version"),
        "pid": cs.get("pid"),
        "uptime_ms": cs.get("uptime_ms"),
        "started_at_ms": cs.get("started_at_ms"),
        "interactive": cs.get("interactive"),
        "last_error": cs.get("last_error"),
        "computer_use": cs.get("computer_use"),
        "recent_commands": server.desktop.get_recent(limit=20),
    }
    return web.json_response(out)


async def handle_desktop_dispatch(request: web.Request) -> web.Response:
    """POST /desktop/{tool_name}
      Body: {... tool args ...}
      → 200 {response payload from client}
      → 502 client error / not connected / not advertised
      → 504 client took longer than RESPONSE_TIMEOUT to respond

    Loopback-only — see handle_desktop_ping.
    """
    _require_loopback(request)
    server: RelayServer = request.app["server"]
    tool_name = request.match_info["tool_name"]
    try:
        args = await request.json()
        if not isinstance(args, dict):
            args = {}
    except Exception:  # noqa: BLE001
        args = {}
    try:
        result = await server.desktop.handle_command(tool_name, args)
        return web.json_response(result)
    except Exception as exc:  # DesktopError or asyncio.TimeoutError
        msg = str(exc) or exc.__class__.__name__
        # Map timeout to 504; everything else to 502 — the agent's tool
        # framework distinguishes "transient/retry" (504) from "tool error"
        # (502) on HTTP status alone.
        status = 504 if "timeout" in msg.lower() or isinstance(exc, asyncio.TimeoutError) else 502
        return web.json_response({"ok": False, "error": msg}, status=status)


# ── Clipboard inbox (remote paste rendezvous) ───────────────────────────────


# Magic-byte sniffer — same shape as tui_gateway/server.py::image.attach.bytes.
# We accept the same five formats the upstream TUI's /paste accepts. Magic-byte
# verification prevents a misformatted Authorization or a bytes-claim-png-but-
# is-something-else from polluting the inbox.
_IMAGE_MAGIC = {
    "png": (b"\x89PNG\r\n\x1a\n",),
    "jpg": (b"\xff\xd8\xff",),
    "jpeg": (b"\xff\xd8\xff",),
    "webp": (b"RIFF",),  # RIFF + WEBP at offset 8 — verify both
    "gif": (b"GIF87a", b"GIF89a"),
}
_INBOX_MAX_BYTES = 25 * 1024 * 1024  # mirror tui_gateway image.attach.bytes
_INBOX_DIR = Path.home() / ".hermes" / "images" / "inbox"
# Mirror hermes_cli/clipboard.py::_INBOX_TTL_SECONDS — pastes older than this
# are considered abandoned and won't be picked up by /paste anyway, so the
# write path opportunistically deletes them to keep the inbox dir from
# growing unbounded over time.
_INBOX_TTL_SECONDS = 300


def _sweep_inbox_stale() -> int:
    """Delete inbox files older than the TTL. Best-effort — exceptions
    swallowed so an inbox-write doesn't fail because of a sweep glitch.
    Returns the count of files unlinked, for the log line. Cost: one
    listdir + one stat per file in the inbox; tiny in steady state."""
    swept = 0
    try:
        if not _INBOX_DIR.is_dir():
            return 0
        cutoff = time.time() - _INBOX_TTL_SECONDS
        for path in _INBOX_DIR.iterdir():
            try:
                if path.is_file() and path.stat().st_mtime < cutoff:
                    path.unlink(missing_ok=True)
                    swept += 1
            except OSError:
                continue
    except OSError:
        pass
    return swept


async def handle_clipboard_inbox(request: web.Request) -> web.Response:
    """Stage an image from a remote client into the local paste-inbox.

    POST /clipboard/inbox  Authorization: Bearer <session_token>
      Body: {"format": "png", "bytes_base64": "...", "filename_hint"?: "clip"}
      → 200 {"ok": true, "path": "/home/.../inbox/<file>", "size_bytes": N}
      → 400 invalid format / base64 / magic-byte mismatch / empty payload
      → 401 missing or invalid bearer
      → 413 payload exceeds 25 MB cap
      → 500 disk write failure

    The hermes CLI's ``/paste`` (and Alt+V) consult this inbox first via
    ``hermes_cli.clipboard._inbox_freshest`` (axiom-fork patch). Files older
    than 5 minutes are ignored as stale; the consumer unlinks on save so
    pastes are one-shot.

    Bearer-protected so a random LAN host can't fill the inbox; only a
    paired client with a valid session token can stage paste data.
    """
    server, _session = _require_bearer_session(request)
    del server  # acknowledged; we don't need the server reference for inbox

    try:
        body = await request.json()
    except Exception:  # noqa: BLE001 — aiohttp raises a varied set
        raise web.HTTPBadRequest(text="invalid JSON body")

    fmt = str(body.get("format", "")).strip().lower()
    bytes_b64 = body.get("bytes_base64", "")
    filename_hint = str(body.get("filename_hint", "") or "").strip()

    if not fmt or not isinstance(bytes_b64, str):
        raise web.HTTPBadRequest(text="format and bytes_base64 are required")
    if fmt not in _IMAGE_MAGIC:
        raise web.HTTPBadRequest(
            text=f"unsupported format {fmt!r} — expected one of "
            + ", ".join(sorted(_IMAGE_MAGIC.keys()))
        )

    try:
        data = base64.b64decode(bytes_b64, validate=True)
    except (binascii.Error, ValueError):
        raise web.HTTPBadRequest(text="invalid base64 payload")
    if not data:
        raise web.HTTPBadRequest(text="empty payload")
    if len(data) > _INBOX_MAX_BYTES:
        return web.json_response(
            {"ok": False, "error": f"payload exceeds {_INBOX_MAX_BYTES // 1024 // 1024} MB cap"},
            status=413,
        )

    # Magic-byte check — exempt only WEBP which has a 12-byte signature.
    magics = _IMAGE_MAGIC[fmt]
    if fmt == "webp":
        ok = data[:4] == b"RIFF" and len(data) >= 12 and data[8:12] == b"WEBP"
    else:
        ok = any(data.startswith(m) for m in magics)
    if not ok:
        raise web.HTTPBadRequest(
            text=f"format mismatch — body does not look like {fmt}"
        )

    # Sanitize filename hint: only [A-Za-z0-9_-], cap 40 chars. Same policy
    # as tui_gateway/server.py::image.attach.bytes.
    hint = "".join(c for c in filename_hint if c.isalnum() or c in "_-")[:40]
    ext = "jpeg" if fmt == "jpg" else fmt
    ts = time.strftime("%Y%m%d_%H%M%S")
    suffix = f"_{hint}" if hint else ""
    target = _INBOX_DIR / f"clip_{ts}_{os.getpid()}{suffix}.{ext}"

    try:
        _INBOX_DIR.mkdir(parents=True, exist_ok=True)
        tmp = target.with_suffix(target.suffix + ".tmp")
        tmp.write_bytes(data)
        os.replace(tmp, target)
    except OSError as exc:
        logger.warning("clipboard inbox write failed: %s", exc)
        return web.json_response(
            {"ok": False, "error": f"disk write failed: {exc}"},
            status=500,
        )

    # Opportunistic sweep — keep the inbox from accumulating abandoned
    # pastes. Runs AFTER the new write so a sweep failure can't damage
    # the just-staged file.
    swept = _sweep_inbox_stale()

    logger.info(
        "/clipboard/inbox: staged %d bytes -> %s%s",
        len(data),
        target.name,
        f" (swept {swept} stale)" if swept else "",
    )
    return web.json_response(
        {"ok": True, "path": str(target), "size_bytes": len(data), "swept_stale": swept}
    )


# ── Media handlers ───────────────────────────────────────────────────────────

# Canonical image MIME for each magic-byte format key. Used by the optional
# content re-sniff (D6) to correct a mislabeled image content-type before it
# reaches the phone's renderer. "jpg"/"jpeg" both normalize to image/jpeg.
_IMAGE_MAGIC_MIME = {
    "png": "image/png",
    "jpg": "image/jpeg",
    "jpeg": "image/jpeg",
    "webp": "image/webp",
    "gif": "image/gif",
}

_SENSITIVE_TRUE_STRINGS = frozenset({"1", "true", "yes", "on"})


def _coerce_sensitive(value: object) -> bool:
    """Coerce a JSON/query ``sensitive`` value into a strict bool.

    Accepts the shapes a producer or query string might supply: ``bool``,
    ``int`` (nonzero → True), or a string (``"1"``/``"true"``/``"yes"``/``"on"``,
    case-insensitive → True). Anything else — including ``None`` and unknown
    strings — is False. This keeps the sensitivity bit a faithful one-way
    transport: when in doubt, don't blur.
    """
    if isinstance(value, bool):
        return value
    if isinstance(value, int):
        return value != 0
    if isinstance(value, str):
        return value.strip().lower() in _SENSITIVE_TRUE_STRINGS
    return False


def _sniff_image_mime(path: str) -> str | None:
    """Best-effort magic-byte sniff of ``path`` → canonical image MIME.

    Reuses the ``_IMAGE_MAGIC`` table (the same one the clipboard inbox uses)
    to identify PNG / JPEG / GIF / WEBP from the file's leading bytes. Returns
    the canonical ``image/*`` MIME on a confident match, or ``None`` when the
    bytes don't match any known image signature (or the file can't be read).

    Used only to *correct* a mislabeled image content-type on serve — never to
    reject. Peek-only (16 bytes); all I/O errors collapse to ``None`` so a
    sniff glitch can never fail an otherwise-valid media fetch.
    """
    try:
        with open(path, "rb") as fh:
            head = fh.read(16)
    except OSError:
        return None
    if not head:
        return None
    # WEBP carries a 12-byte signature (RIFF....WEBP) — check it explicitly
    # before the simple startswith() formats so a bare "RIFF" (e.g. a WAV)
    # doesn't get mislabeled as an image.
    if head[:4] == b"RIFF" and len(head) >= 12 and head[8:12] == b"WEBP":
        return _IMAGE_MAGIC_MIME["webp"]
    for fmt, magics in _IMAGE_MAGIC.items():
        if fmt == "webp":
            continue
        if any(head.startswith(magic) for magic in magics):
            return _IMAGE_MAGIC_MIME[fmt]
    return None


def _resniffed_image_content_type(declared: str, path: str) -> str:
    """Return the content-type to serve for ``path``, re-sniffed when it's an image.

    If ``declared`` claims an image type but the file's magic bytes clearly
    say otherwise, return the sniffed canonical MIME (and let the caller log
    the correction). Non-image declarations, unreadable files, and confident
    matches all pass ``declared`` through unchanged — this only ever *fixes*
    an image type, never downgrades a non-image one.
    """
    if not declared.lower().startswith("image/"):
        return declared
    sniffed = _sniff_image_mime(path)
    if sniffed is None or sniffed == declared.lower().split(";", 1)[0].strip():
        return declared
    logger.info(
        "Media content re-sniff: declared %r but magic bytes say %r — "
        "serving sniffed type (path=%s)",
        declared,
        sniffed,
        path,
    )
    return sniffed


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
    # Model-emitted sensitivity hint — transported verbatim. Absent/falsey
    # → not sensitive (back-compat with older callers that never send it).
    sensitive = _coerce_sensitive(payload.get("sensitive"))

    server: RelayServer = request.app["server"]
    try:
        entry = await server.media.register(
            path=path,
            content_type=content_type,
            file_name=file_name,
            sensitive=sensitive,
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
            "sensitive": entry.sensitive,
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

    # Optional content re-sniff (D6): correct a mislabeled image type so the
    # phone renders it right. No-op for non-image types and confident matches.
    content_type = _resniffed_image_content_type(entry.content_type, entry.path)

    headers = {
        "Content-Type": content_type,
        "Cache-Control": "private, max-age=3600",
    }
    # Model-emitted sensitivity bit, transported verbatim. Header is present
    # only when sensitive — absence means "not sensitive" (back-compat: old
    # clients ignore the header, new clients blur per the user's setting).
    if entry.sensitive:
        headers["X-Media-Sensitive"] = "1"
    if entry.file_name:
        # Quote the filename to survive weird characters. RFC 6266 inline
        # disposition with quoted filename is the broadest-compat form.
        safe_name = entry.file_name.replace('"', "")
        headers["Content-Disposition"] = f'inline; filename="{safe_name}"'
    else:
        headers["Content-Disposition"] = "inline"

    logger.debug(
        "Serving media token=%s... path=%s size=%d sensitive=%s to session=%s...",
        token[:8],
        entry.path,
        entry.size,
        entry.sensitive,
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
      * **Credential/system denylist (always on)** — even in permissive
        mode, paths that resolve into credential or system locations
        (``~/.hermes/.env``, ``auth.json``, ``config.yaml``, OAuth token
        stores, ``pairing/``, ``mcp-tokens/``, ``~/.ssh``, ``/etc``, ...)
        are rejected with 403. Mirrors upstream hermes-agent's native
        media-delivery hardening (``validate_media_delivery_path``): the
        "LLM can already exfil via plain text" rationale below does not
        extend to files the agent itself is forbidden to read, so a
        prompt-injected ``MEDIA:`` marker can't deliver live secrets as a
        native attachment. Symlinks are resolved first, so a link into a
        denied path is caught. See ``plugin/relay/media.py``
        ``_is_denied_media_path``.
      * **Allowed-roots sandbox (opt-in)** — off by default. Set
        ``RELAY_MEDIA_STRICT_SANDBOX=1`` to re-enable the allowlist
        enforcement. Rationale: if the LLM already has filesystem-reading
        tools (search_files, read_file, etc.), it can exfiltrate bytes via
        plain text responses, so the allowlist is defense-in-depth rather
        than a hard boundary. In practice the allowlist surfaced mostly as
        false positives — the LLM finds ``~/projects/foo/readme.png`` and
        the phone renders a "Path not allowed" card. Operators who want the
        tighter default back can opt in via env.

    GET /media/by-path?path=<urlencoded-abs-path>&content_type=<optional-type>&sensitive=<0|1>
      → 200 file bytes
      → 400 missing path query param
      → 401 missing/invalid bearer
      → 403 path not absolute / denied credential-or-system path / other
        validation failure (or outside allowlist in strict mode)
      → 404 file does not exist or is not a regular file
      → 413 file exceeds RELAY_MEDIA_MAX_SIZE_MB

    The optional ``sensitive`` query hint mirrors the token path's stored
    metadata: there's no registration step for bare-path markers, so the
    sensitivity bit (if any) must ride the query string. When truthy the
    response carries ``X-Media-Sensitive: 1``. Absent → not sensitive.
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

    # Optional model-emitted sensitivity hint. Bare-path markers have no
    # registration step, so the bit (if any) rides the query string.
    sensitive = _coerce_sensitive(request.query.get("sensitive"))

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

    # Optional content re-sniff (D6): correct a mislabeled image type (whether
    # it came from the phone hint or the extension guess) so it renders right.
    content_type = _resniffed_image_content_type(content_type, real_path)

    # Extract a display file name from the path for Content-Disposition.
    file_name = os.path.basename(real_path)
    safe_name = file_name.replace('"', "")

    headers = {
        "Content-Type": content_type,
        "Cache-Control": "private, max-age=3600",
        "Content-Disposition": f'inline; filename="{safe_name}"',
    }
    if sensitive:
        headers["X-Media-Sensitive"] = "1"

    logger.debug(
        "Serving media by-path %s size=%d type=%s sensitive=%s to session=%s...",
        real_path,
        size,
        content_type,
        sensitive,
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
#   * Every route requires a live Relay bearer with an active ``bridge``
#     grant. The same listener accepts external WebSocket connections, so
#     callers are never trusted merely because host tools normally use
#     ``localhost``.
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
    status = getattr(exc, "status_code", None) or fallback_status
    lowered = msg.lower()
    if status == fallback_status:
        if "did not respond" in lowered or "timeout" in lowered:
            status = _BRIDGE_TIMEOUT_STATUS
        elif "failed to send" in lowered:
            status = _BRIDGE_SEND_FAIL_STATUS
    return web.json_response({"error": msg}, status=status)


async def _bridge_dispatch(
    request: web.Request,
    path: str,
) -> web.Response:
    """Forward an HTTP request to an Android bridge device."""
    server, session = _require_bearer_session(request)
    if session.channel_is_expired("bridge"):
        raise web.HTTPForbidden(text="active bridge grant required")
    method = request.method  # GET or POST

    params: dict[str, Any] = dict(request.query)
    body: dict[str, Any] = {}
    selector = (
        params.pop("device", None)
        or params.pop("device_id", None)
        or params.pop("deviceId", None)
    )
    if method == "POST":
        # android_tool.py always sends JSON bodies; treat anything else as empty.
        try:
            raw = await request.json()
            if isinstance(raw, dict):
                body = dict(raw)
        except (json.JSONDecodeError, ValueError, aiohttp.ContentTypeError):
            body = {}
        if selector is None:
            selector = (
                body.pop("device", None)
                or body.pop("device_id", None)
                or body.pop("deviceId", None)
            )
        else:
            body.pop("device", None)
            body.pop("device_id", None)
            body.pop("deviceId", None)
    else:
        # H1/M5 fix: phone-side BridgeCommandHandler reads its arguments from
        # the envelope body, with a `params` fallback that's dead code because
        # an empty {} JsonObject is not null. For GET requests we therefore
        # merge query-string params into the body so handlers like /events
        # (limit/since) and /screen (include_bounds) actually see them.
        # Values from request.query are strings; phone-side parsers must use
        # .content.toIntOrNull() etc. rather than .intOrNull.
        body = dict(params)

    if selector is not None and not isinstance(selector, str):
        return web.json_response({"error": "device selector must be a string"}, status=400)

    try:
        response = await server.bridge.handle_command(
            method=method,
            path=path,
            params=params,
            body=body,
            device=selector,
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
# Endpoint inventory mirrors ``plugin/tools/android_tool.py``'s bridge tools:
#   GET  /ping, /screen, /screenshot, /get_apps, /current_app
#   POST /tap, /tap_text, /type, /swipe, /open_app, /press_key,
#        /scroll, /describe_node, /wait, /setup, /return_to_hermes,
#        /share_media, /send_mms
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


async def handle_bridge_return_to_hermes(request: web.Request) -> web.Response:
    return await _bridge_dispatch(request, "/return_to_hermes")


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


async def handle_bridge_share_media(request: web.Request) -> web.Response:
    return await _bridge_dispatch(request, "/share_media")


async def handle_bridge_send_mms(request: web.Request) -> web.Response:
    return await _bridge_dispatch(request, "/send_mms")


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


async def handle_bridge_devices(request: web.Request) -> web.Response:
    """Return connected/known Android bridge devices for local callers."""
    _require_loopback(request)
    server: RelayServer = request.app["server"]
    return web.json_response(server.bridge.devices_payload())


async def handle_bridge_select_active(request: web.Request) -> web.Response:
    """Select the default Android bridge device used by untargeted calls."""
    _require_loopback(request)
    try:
        payload = await request.json() if request.body_exists else {}
    except (json.JSONDecodeError, ValueError, aiohttp.ContentTypeError):
        return web.json_response({"ok": False, "error": "invalid JSON"}, status=400)
    if not isinstance(payload, dict):
        return web.json_response({"ok": False, "error": "body must be a JSON object"}, status=400)
    selector = payload.get("device") or payload.get("device_id") or payload.get("deviceId")
    if not isinstance(selector, str) or not selector.strip():
        return web.json_response(
            {"ok": False, "error": "missing device or device_id"}, status=400
        )
    server: RelayServer = request.app["server"]
    try:
        selected = server.bridge.select_active(selector)
    except BridgeError as exc:
        return _bridge_error_response(exc)
    return web.json_response(selected)


async def handle_bridge_status(request: web.Request) -> web.Response:
    """Return cached Android bridge status, optionally for a selected device.

    GET /bridge/status
      → 200 {"phone_connected": true, "last_seen_seconds_ago": N, ...}
        for the active/default device
      → 503 {"phone_connected": false, "error": "no phone connected"}
        when no Android bridge has ever connected to this relay process
      → 403 non-loopback caller

    GET /bridge/status?device=boox
      → 200 status for the matching connected/known bridge device
      → 404/409 when the selector is unknown or ambiguous
    """
    _require_loopback(request)

    server: RelayServer = request.app["server"]
    bridge_handler = server.bridge
    query = getattr(request, "query", {})
    selector = query.get("device") or query.get("device_id") or query.get("deviceId")

    try:
        response = bridge_handler.status_payload(selector)
    except BridgeError as exc:
        if getattr(exc, "status_code", None) == 503:
            return web.json_response(
                {
                    "phone_connected": False,
                    "last_seen_seconds_ago": None,
                    "error": "no phone connected",
                },
                status=503,
            )
        return _bridge_error_response(exc)
    return web.json_response(response)


# === END PHASE3-status ===


# ── Dashboard-plugin loopback routes ─────────────────────────────────────────
#
# Loopback-only endpoints surfaced for the co-hosted dashboard plugin and
# local CLI. They expose relay state (bridge command ring buffer, media
# registry snapshot, aggregate /relay/info, runtime security toggles) that
# upstream can't see.
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
    """Return the authenticated relay contract and a sanitized status snapshot.

    GET /relay/info
      → 200 {plugin_version, protocol_version, capabilities, profiles, ...}
      → 401 non-loopback caller without a valid paired-device bearer
    """
    if request.remote in ("127.0.0.1", "::1") and not request.headers.get(
        "Authorization"
    ):
        server: RelayServer = request.app["server"]
    else:
        server, _session = _require_bearer_session(request)

    from ..gateway_diagnostics import assess_gateway_heartbeat
    from ..profiles import discover_profile_configs, relay_state

    profiles = [
        {"name": name, "relay_state": relay_state(path)}
        for name, path in discover_profile_configs()
    ]
    uptime_seconds = int(time.monotonic() - server.start_time)
    session_count = len(server.sessions.list_sessions())
    pending_commands = len(server.bridge.pending)
    media_entry_count = await server.media.size()

    return web.json_response(
        {
            "version": __version__,
            "plugin_version": __version__,
            "protocol_version": 1,
            "capabilities": [
                "bridge",
                "media",
                "notifications",
                "profiles",
                "proactive",
                "relay_voice",
                "terminal",
            ],
            "profiles": profiles,
            "uptime_seconds": uptime_seconds,
            "session_count": session_count,
            # Sessions == paired devices in the current auth model.
            "paired_device_count": session_count,
            "pending_commands": pending_commands,
            "media_entry_count": media_entry_count,
            "health": "ok",
            # Read-only upstream signal. It is intentionally separate from
            # Relay's own health and never drives restart/fallback behavior.
            "gateway_heartbeat": assess_gateway_heartbeat(),
        }
    )


def _relay_security_payload(server: RelayServer) -> dict[str, Any]:
    return {
        "ok": True,
        "scope": "runtime",
        "allow_insecure_api_bearer": bool(
            server.config.allow_insecure_api_bearer
        ),
        "trust_proxy_headers": bool(server.config.trust_proxy_headers),
    }


async def handle_relay_security_get(request: web.Request) -> web.Response:
    """Return runtime security toggles for local operators.

    GET /relay/security
      → 200 {allow_insecure_api_bearer, trust_proxy_headers, scope}
      → 403 non-loopback caller
    """
    _require_loopback(request)

    server: RelayServer = request.app["server"]
    return web.json_response(_relay_security_payload(server))


async def handle_relay_security_patch(request: web.Request) -> web.Response:
    """Patch runtime security toggles for local operators.

    PATCH /relay/security {"allow_insecure_api_bearer": true|false}
      → 200 {allow_insecure_api_bearer, trust_proxy_headers, scope}
      → 400 invalid body
      → 403 non-loopback caller
    """
    _require_loopback(request)

    try:
        payload = await request.json()
    except (json.JSONDecodeError, ValueError):
        return web.json_response({"ok": False, "error": "invalid JSON"}, status=400)
    if not isinstance(payload, dict):
        return web.json_response(
            {"ok": False, "error": "body must be a JSON object"},
            status=400,
        )

    if "allow_insecure_api_bearer" not in payload:
        return web.json_response(
            {
                "ok": False,
                "error": "missing allow_insecure_api_bearer",
            },
            status=400,
        )
    value = payload["allow_insecure_api_bearer"]
    if not isinstance(value, bool):
        return web.json_response(
            {
                "ok": False,
                "error": "allow_insecure_api_bearer must be a boolean",
            },
            status=400,
        )

    server: RelayServer = request.app["server"]
    server.config.allow_insecure_api_bearer = value
    if value:
        logger.warning(
            "Runtime security toggle enabled: Hermes API bearer voice auth "
            "is allowed over non-loopback plaintext HTTP"
        )
    else:
        logger.info(
            "Runtime security toggle disabled: Hermes API bearer voice auth "
            "requires HTTPS outside loopback"
        )
    return web.json_response(_relay_security_payload(server))


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


_PROFILE_AVATAR_STEMS = (
    "avatar",
    "profile",
    "profile-image",
    "profile_image",
    "agent",
    "icon",
)
_PROFILE_AVATAR_SUFFIXES = (".png", ".jpg", ".jpeg", ".webp", ".gif")


def _discover_profile_avatar(home: Path) -> Path | None:
    """Return the highest-priority conventional image in ``home``.

    Matching is case-insensitive and intentionally limited to direct children
    of the profile directory. This keeps discovery predictable and avoids a
    recursive walk through memories, skills, sessions, or generated media.
    """
    try:
        files: dict[str, Path] = {}
        for child in sorted(home.iterdir(), key=lambda path: path.name.lower()):
            if child.is_file():
                files.setdefault(child.name.lower(), child)
    except OSError:
        return None

    for stem in _PROFILE_AVATAR_STEMS:
        for suffix in _PROFILE_AVATAR_SUFFIXES:
            candidate = files.get(f"{stem}{suffix}")
            if candidate is not None:
                return candidate
    return None


async def handle_profile_avatar(request: web.Request) -> web.StreamResponse:
    """Serve a conventional profile/avatar image from a Hermes profile home.

    ``GET /api/profiles/{name}/avatar`` searches the profile directory for a
    direct-child image such as ``avatar.png`` or ``profile.jpg``. Remote callers
    require the existing Relay session bearer; loopback callers may omit it,
    matching the other profile read endpoints.
    """
    is_loopback = request.remote in ("127.0.0.1", "::1")
    if is_loopback:
        server: RelayServer = request.app["server"]
    else:
        server, _session = _require_bearer_session(request)

    name = request.match_info.get("name", "").strip()
    home = _resolve_profile_home(server, name)
    if home is None:
        return web.json_response(
            {"error": "profile_not_found", "profile": name}, status=404
        )

    if name == "default":
        # The synthetic default row follows Hermes' sticky active_profile
        # marker. Keep avatar discovery aligned with the identity advertised in
        # auth.ok without changing the older profile inspector/write routes.
        from .config import _effective_default_profile_home

        home = _effective_default_profile_home(home)

    avatar_path = _discover_profile_avatar(home)
    if avatar_path is None:
        return web.json_response(
            {
                "error": "profile_avatar_not_found",
                "profile": name,
                "expected_names": [
                    f"{stem}{suffix}"
                    for stem in ("avatar", "profile")
                    for suffix in _PROFILE_AVATAR_SUFFIXES
                ],
            },
            status=404,
        )

    # Resolve symlinks and enforce both the profile-home boundary and the
    # relay's configured media-size cap before serving bytes to the phone.
    try:
        real_path, _size = validate_media_path(
            str(avatar_path),
            [str(home.resolve())],
            server.media.max_size_bytes,
        )
    except MediaRegistrationError as exc:
        logger.info("Profile avatar rejected for %r: %s", name, exc)
        raise web.HTTPForbidden(text=str(exc))

    content_type = _sniff_image_mime(real_path)
    if content_type is None:
        return web.json_response(
            {
                "error": "profile_avatar_invalid",
                "profile": name,
                "detail": "discovered file is not a supported image",
            },
            status=415,
        )

    safe_name = os.path.basename(real_path).replace('"', "")
    return web.FileResponse(
        real_path,
        headers={
            "Content-Type": content_type,
            "Cache-Control": "private, max-age=3600",
            "Content-Disposition": f'inline; filename="{safe_name}"',
        },
    )


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


def _public_profile_config(parsed: object) -> dict[str, Any]:
    """Build the explicitly public subset of a Hermes profile config.

    Remote profile inspection must not serialize arbitrary configuration
    sections: provider and extension fields may contain reusable credentials.
    Keep this schema deliberately small and add fields only after classifying
    them as safe for every paired Relay client.
    """
    if not isinstance(parsed, dict):
        return {}

    public: dict[str, Any] = {}
    description = parsed.get("description")
    if isinstance(description, str):
        public["description"] = description

    model = parsed.get("model")
    if isinstance(model, dict):
        default_model = model.get("default")
        if isinstance(default_model, str):
            public["model"] = {"default": default_model}

    return public


async def handle_profile_config(request: web.Request) -> web.Response:
    """Return a safe view of ``config.yaml`` for a named profile.

    GET /api/profiles/{name}/config
      → 200 {"profile", "path", "config": {...}, "readonly": true}
      → 401 missing/invalid bearer (remote callers only)
      → 404 profile dir missing or no config.yaml
      → 500 yaml parse error

    Loopback callers may skip bearer auth and receive the complete parsed file.
    Remote callers must present a valid relay session token and receive only
    the explicitly public profile schema, never arbitrary config sections or
    the host filesystem path.
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

    response_config = parsed if is_loopback else _public_profile_config(parsed)
    response_path = str(config_path) if is_loopback else config_path.name

    return web.json_response(
        {
            "profile": name,
            "path": response_path,
            "config": response_config,
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


# ── Profile-scoped SOUL.md + memory write endpoints ──────────────────────────
#
# Symmetric to the GET endpoints above — same loopback-or-bearer auth,
# same ``_resolve_profile_home`` path resolver, same
# ``profile_not_found`` 404 shape. Wire contracts:
#
#   PUT /api/profiles/{name}/soul
#     Body: {"content": "..."}
#     → 200 {"ok": true, "profile", "path", "bytes_written"}
#     → 404 {"error": "profile_not_found", "profile": name}
#     → 413 {"error": "payload_too_large", "limit_bytes": 1048576}
#
#   PUT /api/profiles/{name}/memory/{filename}
#     Body: {"content": "..."}
#     → 200 {"ok": true, "profile", "filename", "path", "bytes_written"}
#     → 400 {"error": "invalid_filename", "detail": "..."}
#     → 404 {"error": "profile_not_found", "profile": name}
#     → 413 {"error": "payload_too_large", "limit_bytes": 1048576}
#
# Atomic write pattern: write to ``<name>.tmp``, ``os.replace()`` into
# place. Preserves the original file's POSIX mode when present — the
# operator's existing choice wins over any default we might impose
# (SOUL files are typically world-readable in a home directory).

# Max bytes we accept for a single SOUL.md or memory-file write.
_PROFILE_WRITE_MAX_BYTES = 1 * 1024 * 1024  # 1 MB


def _extract_write_content(body: Any) -> tuple[str | None, web.Response | None]:
    """Pull the ``content`` field out of a PUT body with shared
    validation.

    Returns ``(content, None)`` on success or ``(None, error_response)``
    on validation failure — the caller ``return``s the response
    unchanged. Separated from the handlers so both share identical
    wire shapes.
    """
    if not isinstance(body, dict):
        return None, web.json_response(
            {"error": "invalid_body", "detail": "body must be a JSON object"},
            status=400,
        )
    content = body.get("content")
    if content is None:
        return None, web.json_response(
            {"error": "invalid_body", "detail": "missing 'content' field"},
            status=400,
        )
    if not isinstance(content, str):
        return None, web.json_response(
            {"error": "invalid_body", "detail": "'content' must be a string"},
            status=400,
        )
    # Size gate — measured in UTF-8 bytes since that's what lands on
    # disk. ``len(content)`` alone underestimates for non-ASCII.
    encoded_size = len(content.encode("utf-8"))
    if encoded_size > _PROFILE_WRITE_MAX_BYTES:
        return None, web.json_response(
            {
                "error": "payload_too_large",
                "limit_bytes": _PROFILE_WRITE_MAX_BYTES,
                "received_bytes": encoded_size,
            },
            status=413,
        )
    return content, None


def _atomic_write_text(target: Path, content: str) -> int:
    """Write ``content`` to ``target`` atomically, preserving the
    existing file's POSIX mode when present. Returns the bytes
    written.

    Implementation: write to a sibling ``<name>.tmp`` file, fsync,
    ``os.replace()``. Raises :class:`OSError` on IO failure — callers
    decide how to surface that (typically 500).
    """
    target.parent.mkdir(parents=True, exist_ok=True)

    # Preserve mode of existing file if present.
    preserve_mode: int | None = None
    if target.exists():
        try:
            preserve_mode = stat.S_IMODE(target.stat().st_mode)
        except OSError:
            preserve_mode = None

    tmp_path = target.with_name(target.name + ".tmp")
    data = content.encode("utf-8")

    with open(tmp_path, "wb") as fh:
        fh.write(data)
        fh.flush()
        try:
            os.fsync(fh.fileno())
        except OSError:
            pass

    if preserve_mode is not None:
        try:
            os.chmod(tmp_path, preserve_mode)
        except OSError:
            pass

    os.replace(tmp_path, target)
    return len(data)


async def handle_profile_soul_put(request: web.Request) -> web.Response:
    """Write ``SOUL.md`` for a named profile.

    PUT /api/profiles/{name}/soul
      Body: {"content": "..."}
      → 200 {"ok": true, "profile", "path", "bytes_written"}
      → 400 invalid body (missing / wrong-type content)
      → 401 missing/invalid bearer (remote callers only)
      → 404 {"error": "profile_not_found", "profile": name}
      → 413 {"error": "payload_too_large", "limit_bytes": 1048576}
      → 500 {"error": "soul_write_failed", "detail": "..."}

    Atomic: writes ``SOUL.md.tmp``, replaces on success. Preserves the
    existing SOUL.md's mode when present.
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

    try:
        body = await request.json()
    except (json.JSONDecodeError, ValueError):
        return web.json_response(
            {"error": "invalid_body", "detail": "invalid JSON"}, status=400
        )

    content, err = _extract_write_content(body)
    if err is not None:
        return err
    assert content is not None  # narrow for type checker

    soul_path = home / "SOUL.md"
    try:
        bytes_written = _atomic_write_text(soul_path, content)
    except OSError as exc:
        logger.warning(
            "Profile SOUL write failed for %r at %s: %s",
            name,
            soul_path,
            exc,
        )
        return web.json_response(
            {"error": "soul_write_failed", "detail": str(exc)},
            status=500,
        )

    logger.info(
        "Profile SOUL write: profile=%s bytes=%d path=%s",
        name,
        bytes_written,
        soul_path,
    )
    # Profile metadata may have shifted (description falls back to
    # SOUL.md's first line if config.yaml lacks one). Broadcast wiring
    # lands in the follow-up ``feat(relay): push profiles.updated...``
    # commit; the hook below is a stub so the write-path signature
    # freezes now.
    _notify_profiles_changed(server, reason="soul_written", profile=name)
    return web.json_response(
        {
            "ok": True,
            "profile": name,
            "path": str(soul_path),
            "bytes_written": bytes_written,
        }
    )


def _validate_memory_filename(filename: str) -> str | None:
    """Return ``None`` if ``filename`` is safe; otherwise a human-
    readable detail message for the 400 response body.

    Rules:
      * must end with ``.md`` (case-sensitive — matches upstream
        MEMORY.md / USER.md / anything.md convention)
      * must not contain ``/`` ``\\`` or ``..``
      * must not start with ``.`` (even ``.MEMORY.md`` is rejected —
        we only allow ``.`` as the separator before ``md``)
      * must not be empty
      * must not be the literal name ``SOUL.md`` (that has its own
        endpoint — avoid stomping via memory path)
      * charset limited to ascii letters, digits, and ``._-`` for
        predictable behavior on case-insensitive filesystems.
    """
    if not filename:
        return "filename is empty"
    if "/" in filename or "\\" in filename:
        return "filename must not contain path separators"
    if ".." in filename:
        return "filename must not contain '..'"
    if filename.startswith("."):
        return "filename must not start with '.'"
    if not filename.endswith(".md"):
        return "filename must end with '.md'"
    if filename == "SOUL.md":
        return "use the /soul endpoint to write SOUL.md"
    for ch in filename:
        if not (ch.isalnum() or ch in "._-"):
            return f"filename contains disallowed character {ch!r}"
    return None


async def handle_profile_memory_put(request: web.Request) -> web.Response:
    """Write a memory file under ``<profile>/memories/`` for a named profile.

    PUT /api/profiles/{name}/memory/{filename}
      Body: {"content": "..."}
      → 200 {"ok": true, "profile", "filename", "path", "bytes_written"}
      → 400 invalid filename or invalid body
      → 401 missing/invalid bearer (remote callers only)
      → 404 {"error": "profile_not_found", "profile": name}
      → 413 {"error": "payload_too_large", "limit_bytes": 1048576}
      → 500 {"error": "memory_write_failed", "detail": "..."}

    Creates ``memories/`` if absent. Atomic write mirrors the SOUL
    path. Filename validation rejects path traversal, leading dot,
    and non-.md extensions.
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

    filename = request.match_info.get("filename", "")
    detail = _validate_memory_filename(filename)
    if detail is not None:
        return web.json_response(
            {"error": "invalid_filename", "detail": detail},
            status=400,
        )

    try:
        body = await request.json()
    except (json.JSONDecodeError, ValueError):
        return web.json_response(
            {"error": "invalid_body", "detail": "invalid JSON"}, status=400
        )

    content, err = _extract_write_content(body)
    if err is not None:
        return err
    assert content is not None

    memory_path = home / "memories" / filename
    try:
        bytes_written = _atomic_write_text(memory_path, content)
    except OSError as exc:
        logger.warning(
            "Profile memory write failed for %r/%s at %s: %s",
            name,
            filename,
            memory_path,
            exc,
        )
        return web.json_response(
            {"error": "memory_write_failed", "detail": str(exc)},
            status=500,
        )

    logger.info(
        "Profile memory write: profile=%s filename=%s bytes=%d path=%s",
        name,
        filename,
        bytes_written,
        memory_path,
    )
    _notify_profiles_changed(
        server,
        reason="memory_written",
        profile=name,
        filename=filename,
    )
    return web.json_response(
        {
            "ok": True,
            "profile": name,
            "filename": filename,
            "path": str(memory_path),
            "bytes_written": bytes_written,
        }
    )


def _notify_profiles_changed(
    server: "RelayServer",
    *,
    reason: str,
    profile: str | None = None,
    filename: str | None = None,
) -> None:
    """Hook called after a profile write lands on disk.

    Re-runs profile discovery, compares against the cached snapshot
    stored on the server, and if the shape differs broadcasts a
    ``pairing/profiles.updated`` envelope to every authenticated
    client. Whole-array diff — we don't bother per-profile — simpler
    than per-field and the response size is identical either way.
    """
    # Lazy import to avoid a module-level circular (_load_profiles
    # lives in .config which already imports from .auth).
    from .config import _load_profiles

    try:
        fresh_profiles = _load_profiles(
            server.config.hermes_config_path,
            enabled=server.config.profile_discovery_enabled,
            base_api_url=server.config.webapi_url,
        )
    except Exception:
        logger.warning(
            "profiles-changed hook: _load_profiles raised (reason=%s profile=%s)",
            reason,
            profile,
            exc_info=True,
        )
        return

    previous = server.config.profiles
    if fresh_profiles == previous:
        logger.debug(
            "profiles-changed hook: no diff after reason=%s profile=%s filename=%s",
            reason,
            profile,
            filename,
        )
        return

    server.config.profiles = fresh_profiles
    logger.info(
        "profiles-changed hook: reshape detected (reason=%s profile=%s) — "
        "broadcasting profiles.updated to %d client(s)",
        reason,
        profile,
        len(server._clients),
    )
    # Fire-and-forget the broadcast — keep the write-path handler
    # non-async-blocking. Create_task on the running loop; if there's
    # no loop (called from a sync context, e.g. the rescan task that
    # IS in an async context will still work), fall back to logging.
    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        logger.debug(
            "profiles-changed hook: no running loop — "
            "skipping broadcast (will be picked up on next rescan)"
        )
        return
    loop.create_task(
        _broadcast_profiles_updated(server, fresh_profiles),
        name="profiles-updated-broadcast",
    )


async def _broadcast_profiles_updated(
    server: "RelayServer",
    profiles: list[dict[str, Any]],
) -> None:
    """Send a ``pairing/profiles.updated`` envelope to every client.

    Wire contract:
    ``{"channel": "pairing", "type": "profiles.updated",
       "id": <uuid>, "payload": {"profiles": [...]}}``

    Clients update their local cache on receipt; no ack expected. We
    iterate over a snapshot of ``_clients`` so disconnects mid-send
    don't blow up the loop.
    """
    blob = json.dumps(
        {
            "channel": "pairing",
            "type": "profiles.updated",
            "id": str(uuid.uuid4()),
            "payload": {"profiles": profiles},
        }
    )
    for ws in list(server._clients):
        if ws.closed:
            continue
        try:
            await ws.send_str(blob)
        except ConnectionResetError:
            # Client dropped during send — disconnect cleanup will
            # remove it from _clients on the next WS read tick.
            continue
        except Exception:
            logger.debug(
                "profiles.updated broadcast: send failed for one ws",
                exc_info=True,
            )


# Interval for the background profile-rescan task. Low enough that
# an operator tweaking config.yaml / dropping a skills/ dir sees the
# phone reflect it quickly; high enough that the filesystem walk
# stays cheap. 30 seconds matches the coordinator spec.
_PROFILE_RESCAN_INTERVAL_SECONDS = 30.0


async def _profile_rescan_loop(app: web.Application) -> None:
    """Background task that rescans the profile tree every
    ``_PROFILE_RESCAN_INTERVAL_SECONDS`` and broadcasts
    ``profiles.updated`` whenever the array differs from the
    cached snapshot.

    Fires-and-forgets per iteration so a slow filesystem scan doesn't
    queue up duplicate rescans. Cancellation-safe — shutdown clears
    the task via aiohttp's on_cleanup hook.
    """
    from .config import _load_profiles

    server: RelayServer = app["server"]
    try:
        while True:
            await asyncio.sleep(_PROFILE_RESCAN_INTERVAL_SECONDS)
            try:
                fresh = _load_profiles(
                    server.config.hermes_config_path,
                    enabled=server.config.profile_discovery_enabled,
                    base_api_url=server.config.webapi_url,
                )
            except Exception:
                logger.warning(
                    "profile rescan: _load_profiles raised — skipping cycle",
                    exc_info=True,
                )
                continue
            if fresh == server.config.profiles:
                continue
            server.config.profiles = fresh
            logger.info(
                "profile rescan: reshape detected — "
                "broadcasting profiles.updated to %d client(s)",
                len(server._clients),
            )
            await _broadcast_profiles_updated(server, fresh)
    except asyncio.CancelledError:
        logger.info("profile rescan loop cancelled")
        raise


# === PHASE3-notif-listener: notifications HTTP routes ===
#
# Bearer-auth'd HTTP read endpoint for the cached notification deque
# managed by ``NotificationsChannel``. The agent calls this through the
# ``android_notifications_recent`` tool to answer "what came in
# recently?" questions during a chat turn.
#
# The trust model matches other phone-facing relay HTTP endpoints
# (``/media/*``, ``/sessions``): a valid relay session token in
# ``Authorization: Bearer ...`` is required, and the same token serves as
# proof that the caller is one of the operator's paired devices. ``/voice/*``
# has a narrow additional Hermes API bearer path in ``voice_auth``.


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


async def handle_phone_message(request: web.Request) -> web.Response:
    """Forward an agent-initiated message to the subscribed phone.

    Called by the phone platform adapter (``plugin/phone_platform.py``) over
    loopback. The relay pushes a ``phone.message`` envelope over the phone's
    WSS via the :class:`ProactiveChannel`.

    Loopback-only: the adapter runs in the gateway process on the same host.
    Unlike the bridge routes (which need a paired phone to do anything), an
    outbound push could spam the user's notifications, so this route is not
    exposed to the LAN.

    POST /phone/message  {chat_id, text, title?, surfacing?, reply_to?, metadata?}
      → 200 {"delivered": true, "message_id": "..."}  (sent to a live phone)
      → 200 {"delivered": false, "queued": true, "message_id": "...", "buffered": N}
            (no phone subscribed — queued, flushed on the next subscribe)
      → 400 invalid body / empty text
      → 403 non-loopback caller
      → 502 socket write failed
    """
    remote = request.remote or ""
    if remote not in ("127.0.0.1", "::1"):
        return web.json_response({"error": "loopback only"}, status=403)

    server: RelayServer = request.app["server"]
    try:
        payload = await request.json()
    except (json.JSONDecodeError, ValueError, aiohttp.ContentTypeError):
        return web.json_response({"error": "invalid JSON body"}, status=400)
    if not isinstance(payload, dict):
        return web.json_response({"error": "body must be a JSON object"}, status=400)

    text = payload.get("text")
    if not isinstance(text, str) or not text.strip():
        return web.json_response({"error": "text is required"}, status=400)

    try:
        result = await server.proactive.push(payload)
    except ProactiveError as exc:
        # push() now buffers when no phone is subscribed, so the only
        # ProactiveError path left is a live socket write that failed → 502.
        return web.json_response({"error": str(exc)}, status=502)

    return web.json_response(result, status=200)


async def handle_phone_outbound(request: web.Request) -> web.Response:
    """Inspect or cancel the queued agent→phone outbound messages.

    Loopback-only (same rationale as ``/phone/message``). Lets a status view /
    `relay` CLI / dashboard show what's waiting for an offline phone and cancel
    it before it flushes on the next subscribe.

    GET    /phone/outbound                  → {queued, messages:[{message_id, chat_id, text, sent_at}]}
    DELETE /phone/outbound                  → cancel ALL → {cancelled}
    DELETE /phone/outbound?message_id=<id>  → cancel one → {cancelled}
    """
    remote = request.remote or ""
    if remote not in ("127.0.0.1", "::1"):
        return web.json_response({"error": "loopback only"}, status=403)

    server: RelayServer = request.app["server"]
    if request.method == "DELETE":
        mid = request.query.get("message_id") or None
        cancelled = server.proactive.cancel_outbound(mid)
        return web.json_response({"cancelled": cancelled}, status=200)

    messages = server.proactive.peek_outbound()
    return web.json_response({"queued": len(messages), "messages": messages}, status=200)


# Cap the server-side long-poll hold so a wedged gateway poller can't pin a
# request open forever; the adapter re-polls. Generous vs. the adapter's own
# read timeout so a normal empty poll returns from here, not from a client
# timeout.
_REPLIES_MAX_LONGPOLL_SECONDS = 30.0


async def handle_phone_replies(request: web.Request) -> web.Response:
    """Long-poll for buffered phone replies (the inbound reply leg).

    The phone platform adapter (``plugin/phone_platform.py``) runs in the
    gateway process and polls this loopback route. Replies arrive over the
    phone WSS as ``proactive.reply`` envelopes and are buffered by the
    :class:`ProactiveChannel`; this drains them. Mirror image of the outbound
    ``POST /phone/message`` hop — the two processes can't hand a reply over
    in-process, so it is parked and polled.

    Loopback-only for the same reason as ``/phone/message``: only the
    co-located gateway adapter should consume the user's replies.

    GET /phone/replies?timeout=<seconds>
      → 200 {"replies": [{text, chat_id, reply_to, message_id, ts}, ...]}
            (possibly empty after the long-poll window elapses)
      → 403 non-loopback caller
    """
    remote = request.remote or ""
    if remote not in ("127.0.0.1", "::1"):
        return web.json_response({"error": "loopback only"}, status=403)

    server: RelayServer = request.app["server"]

    raw_timeout = request.query.get("timeout", "25")
    try:
        timeout = float(raw_timeout)
    except (TypeError, ValueError):
        timeout = 25.0
    # Clamp to a sane window: non-negative, and never longer than the server cap.
    timeout = max(0.0, min(timeout, _REPLIES_MAX_LONGPOLL_SECONDS))

    replies = await server.proactive.take_replies(timeout)
    return web.json_response({"replies": replies}, status=200)


async def handle_phone_threads(request: web.Request) -> web.Response:
    """Map phone Threads to their ``chat_id`` — the field ``/api/sessions`` omits.

    A phone **Thread** is a ``source=phone`` gateway session keyed by a
    ``chat_id`` (the platform conversation id). The gateway persists ``chat_id``
    in its session store but does NOT return it from ``/api/sessions`` (only the
    opaque timestamp ``id`` + ``source``), so the Android client can't map a
    session to its Thread to route a reply. This reads the gateway store
    (read-only) and returns the mapping the client needs — it survives an app
    restart and covers Threads the client didn't create itself. Redundant once
    upstream adds ``chat_id`` to ``/api/sessions``.

    Loopback callers skip bearer auth (dashboard/diagnostics); the paired phone
    app presents its relay session bearer (same gate as ``/context/injected``).

    GET /phone/threads
      → 200 {"threads": [{session_id, chat_id, title}, ...]}
      → 401 missing/invalid bearer (remote callers only)
    """
    remote = request.remote or ""
    is_loopback = remote in ("127.0.0.1", "::1")
    if not is_loopback:
        _server, _session = _require_bearer_session(request)
    return web.json_response({"threads": read_phone_threads()})


# Update-check cache — a GitHub round-trip per poll would be wasteful and risk
# rate-limiting, so the resolved result is cached for an hour. The app polls
# this far less often than that; ``?refresh=1`` forces a re-fetch.
_UPDATE_CHECK_CACHE: dict[str, Any] = {"result": None, "at": 0.0}
_UPDATE_CHECK_TTL: float = 3600.0


async def handle_relay_update_check(request: web.Request) -> web.Response:
    """Report whether a newer hermes-relay plugin release is available.

    The dashboard has its own (loopback) update-check; this is the **app-facing**
    twin on the relay port so the phone can surface a soft "your relay is behind"
    nudge. Compares the installed ``plugin.relay.__version__`` against the latest
    ``server-v*`` GitHub release and names the right update command for the host.

    Loopback callers skip bearer auth (diagnostics); the paired phone presents
    its relay session bearer (same gate as ``/phone/threads``). The blocking
    GitHub fetch runs in an executor so it never stalls the event loop, and the
    result is cached for an hour. Failures degrade to
    ``update_available=false`` with an ``error`` — never a 5xx.

    GET /relay/update-check[?refresh=1]
      → 200 {current, latest, update_available, update_command, error?}
      → 401 missing/invalid bearer (remote callers only)
    """
    remote = request.remote or ""
    is_loopback = remote in ("127.0.0.1", "::1")
    if not is_loopback:
        _server, _session = _require_bearer_session(request)

    force = request.query.get("refresh", "").strip().lower() in ("1", "true", "yes")
    now = time.monotonic()
    cached = _UPDATE_CHECK_CACHE["result"]
    if force or cached is None or (now - _UPDATE_CHECK_CACHE["at"]) > _UPDATE_CHECK_TTL:
        from .. import update_check

        loop = asyncio.get_running_loop()
        result = await loop.run_in_executor(None, update_check.check)
        _UPDATE_CHECK_CACHE["result"] = result
        _UPDATE_CHECK_CACHE["at"] = now

    return web.json_response(_UPDATE_CHECK_CACHE["result"])


async def handle_context_injected(request: web.Request) -> web.Response:
    """Return the relay-owned system-prompt context audit shape.

    Loopback callers skip bearer auth, matching ``/notifications/recent``.
    Remote callers must present a valid relay session bearer.

    GET /context/injected
      -> 200 {"enabled": <bool>, "blocks": [{"name": ..., "text": ...}]}
      -> 401 missing/invalid bearer (remote callers only)
    """
    remote = request.remote or ""
    is_loopback = remote in ("127.0.0.1", "::1")

    if not is_loopback:
        _server, _session = _require_bearer_session(request)

    return web.json_response(injected_context_payload())


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
        server._client_capabilities.pop(ws, None)
        server.chat.detach_ws(ws)
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


def _extract_client_capabilities(payload: dict[str, Any]) -> dict[str, Any]:
    """Normalize optional client capability negotiation from auth payload."""
    supports = payload.get("supports")
    if not isinstance(supports, dict):
        supports = {}
    typed = supports.get("typed_stream_events") is True
    version = supports.get("event_schema_version", supports.get("typed_stream_event_schema_version", 1))
    try:
        version_int = int(version)
    except (TypeError, ValueError):
        version_int = 0
    return {
        "supports": {
            "typed_stream_events": bool(typed and version_int == 1),
            "event_schema_version": 1 if typed and version_int == 1 else version_int,
        }
    }


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
    payload: dict[str, Any] = {
        "session_token": session.token,
        "server_version": __version__,
        "profiles": server.config.profiles,
        "expires_at": _norm(session.expires_at),
        "grants": {k: _norm(v) for k, v in session.grants.items()},
        "transport_hint": session.transport_hint,
        "client_surface": session.client_surface,
        "device_form_factor": session.device_form_factor,
    }
    if session.refresh_token:
        payload["refresh_token"] = session.refresh_token
    return payload


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
    if not isinstance(payload, dict):
        payload = {}
    server._client_capabilities[ws] = _extract_client_capabilities(payload)
    pairing_code = payload.get("pairing_code", "")
    session_token_attempt = payload.get("session_token", "")
    refresh_token_attempt = str(payload.get("refresh_token", "") or "").strip()
    device_name = payload.get("device_name", "Unknown device")
    device_id = payload.get("device_id", "unknown")
    client_surface = str(payload.get("client_surface", "unknown") or "unknown")
    device_form_factor = str(payload.get("device_form_factor", "unknown") or "unknown")

    # Pairing policy is attached by a loopback-only operator flow. Clients
    # may still send ttl_seconds / grants for wire compatibility, but those
    # fields are not an authority boundary and must not influence sessions.
    # Missing host metadata therefore resolves to SessionManager defaults.
    detected_transport = _detect_transport_hint(request)

    # Try session token first (reconnection)
    if session_token_attempt:
        session = server.sessions.get_session(session_token_attempt)
        if session is not None:
            if (
                not refresh_token_attempt
                and device_id
                and not server.sessions.has_trusted_device(session.device_id)
            ):
                server.sessions.issue_refresh_token_for_session(session)
            server.rate_limiter.record_success(remote_ip)
            await _send_system(
                ws, "auth.ok", _build_auth_ok_payload(session, server)
            )
            return session.token

    # If the short session token was lost, revoked during an update, or reset
    # on a stateless deployment, a trusted-device refresh token can recover
    # without forcing a new QR scan. The refresh credential is rotated on
    # success and the replacement session is returned through the normal
    # auth.ok shape.
    if refresh_token_attempt:
        session = server.sessions.refresh_session(
            refresh_token_attempt,
            device_name=device_name,
            device_id=device_id,
            transport_hint=detected_transport,
            client_surface=client_surface,
            device_form_factor=device_form_factor,
        )
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
            # Thread host-side metadata (from a loopback-only pairing flow)
            # through to the session. Missing metadata uses bounded library
            # defaults; the network client cannot author session policy.
            ttl_seconds: float | None = metadata.ttl_seconds
            grants: dict[str, float] | None = metadata.grants
            transport_hint = metadata.transport_hint or detected_transport

            session = server.sessions.create_session(
                device_name,
                device_id,
                ttl_seconds=ttl_seconds,
                grants=grants,
                transport_hint=transport_hint,
                client_surface=client_surface,
                device_form_factor=device_form_factor,
                issue_refresh_token=True,
            )
            server.rate_limiter.record_success(remote_ip)
            await _send_system(
                ws, "auth.ok", _build_auth_ok_payload(session, server)
            )
            return session.token

    # Both failed. Route the failure to the appropriate bucket so
    # legitimate-but-fumbled pairing attempts don't get penalized at the
    # stricter session-token threshold. If the client sent a pairing
    # code (attempting fresh pair), it's a pairing failure; if it sent
    # a session token (reconnect attempt), it's a session failure. If
    # the client sent both (unusual — phone testing its cached token
    # then falling back to a QR), we count both buckets since both
    # attempts genuinely failed.
    recorded = False
    if session_token_attempt:
        server.rate_limiter.record_session_failure(remote_ip)
        recorded = True
    if refresh_token_attempt:
        server.rate_limiter.record_session_failure(remote_ip)
        recorded = True
    if pairing_code:
        server.rate_limiter.record_pairing_failure(remote_ip)
        recorded = True
    if not recorded:
        # Auth envelope with neither code nor token — treat as a
        # session-bucket failure (stricter) since the client sent
        # nothing at all to validate.
        server.rate_limiter.record_session_failure(remote_ip)

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
        # Run chat handling as a tracked task so we can cancel on disconnect.
        # Capability negotiation happens at system/auth; the chat channel uses
        # it to decide typed stream.event passthrough vs legacy text envelopes.
        task = asyncio.create_task(
            server.chat.handle(
                ws, envelope, server._client_capabilities.get(ws)
            )
        )
        _track_task(server, ws, task)
    elif channel == "terminal":
        token = server._clients.get(ws)
        session = server.sessions.get_session(token) if token else None
        if session is None or session.channel_is_expired("terminal"):
            logger.warning(
                "Rejected terminal message from device=%s: terminal grant expired",
                session.device_id if session is not None else "unknown",
            )
            await _send_system(
                ws,
                "error",
                {"message": "Terminal grant expired for this device"},
                msg_id=msg_id,
            )
            return
        task = asyncio.create_task(server.terminal.handle(ws, envelope))
        _track_task(server, ws, task)
    elif channel == "bridge":
        token = server._clients.get(ws)
        session = server.sessions.get_session(token) if token else None
        if session is not None and session.channel_is_expired("bridge"):
            logger.warning(
                "Rejected bridge message from device=%s: bridge grant expired",
                session.device_id,
            )
            await _send_system(
                ws,
                "error",
                {"message": "Bridge grant expired for this device"},
                msg_id=msg_id,
            )
            return
        task = asyncio.create_task(server.bridge.handle(ws, envelope, session=session))
        _track_task(server, ws, task)
    elif channel == "tui":
        task = asyncio.create_task(server.tui.handle(ws, envelope))
        _track_task(server, ws, task)
    # === PHASE3-notif-listener: notifications channel dispatch ===
    elif channel == "notifications":
        task = asyncio.create_task(server.notifications.handle(ws, envelope))
        _track_task(server, ws, task)
    # === END PHASE3-notif-listener ===
    elif channel == "proactive":
        # Phone subscribing to / unsubscribing from agent-initiated pushes.
        # Outbound phone.message envelopes originate from the HTTP route, not
        # here; this only handles the phone→server subscribe lifecycle.
        task = asyncio.create_task(server.proactive.handle(ws, envelope))
        _track_task(server, ws, task)
    elif channel == "desktop":
        # Desktop CLI awareness — workspace + active-editor hints. Stashed
        # on the session; never replied to. Dispatching as a tracked task
        # keeps it consistent with other channels so disconnect-cancel
        # works uniformly.
        task = asyncio.create_task(server.desktop.handle(ws, envelope))
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
    server._client_capabilities.pop(ws, None)
    server.chat.detach_ws(ws)

    # === PHASE3-bridge-server: fail in-flight bridge commands on phone disconnect ===
    # If this ws was the currently-latched phone, detach_ws flips phone_ws
    # back to None and resolves every pending bridge command future with a
    # ConnectionError so the HTTP side returns 502 instead of hanging to
    # the 30s timeout on every in-flight request_id.
    await server.bridge.detach_ws(ws, reason=f"client {remote_ip} disconnected")
    # === END PHASE3-bridge-server ===

    # If this ws was the proactive subscriber, release it so the next
    # /phone/message returns 503 (no phone) instead of writing to a dead
    # socket.
    await server.proactive.detach_ws(ws, reason=f"client {remote_ip} disconnected")

    # Desktop CLI context is per-ws and should not outlive the socket — the
    # next client connect will re-advertise its workspace anyway.
    await server.desktop.detach_ws(ws)

    # Kill any tui_gateway subprocess this client had spawned so it doesn't
    # linger after the WSS drops. Safe to call unconditionally — it no-ops
    # when the client never attached on the tui channel.
    await server.tui.detach_ws(ws, reason=f"client {remote_ip} disconnected")

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
    # aiohttp's default client_max_size (1 MiB) would short-circuit
    # phone uploads at the aiohttp layer, returning a plain-text 413
    # before our handlers can produce the structured JSON error
    # bodies the phone expects. Bump to 2 MiB so our 1 MB ``content``
    # gate is the real gate — plus a little slack for JSON envelope
    # overhead (``{"content": "..."}``). The voice/media upload
    # routes have their own per-route streaming/offloading which is
    # unaffected by this setting.
    app = web.Application(client_max_size=2 * 1024 * 1024)

    server = RelayServer(config)
    app["server"] = server

    # Routes — /ws is canonical but we also accept "/" as an alias so clients
    # that pass a bare ws://host:port URL (no path) still connect cleanly.
    app.router.add_get("/ws", handle_ws)
    app.router.add_get("/", handle_ws)
    app.router.add_get("/health", handle_health)
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
    # Desktop tool dispatch — HTTP shim called by `plugin/tools/desktop_tool.py`
    # running inside hermes-gateway. Both endpoints loopback-only.
    # `/desktop/_ping` is the availability check the agent's check_fn uses
    # to fail-fast when no desktop client is connected. `/desktop/{tool_name}`
    # forwards the tool call to the connected DesktopHandler client and returns
    # its response. Order matters: fixed `/desktop/_ping` must come BEFORE the
    # wildcard `/desktop/{tool_name}` or aiohttp swallows `_ping` as a tool
    # literal and 502s.
    app.router.add_get("/desktop/_ping", handle_desktop_ping)
    # `/desktop/health` is the JSON-rich diagnostic — full status snapshot
    # plus the recent-commands ring. Like `_ping`, it must come before the
    # wildcard route or aiohttp swallows it.
    app.router.add_get("/desktop/health", handle_desktop_health)
    app.router.add_post("/desktop/{tool_name}", handle_desktop_dispatch)

    # Clipboard inbox — remote-client paste rendezvous for the upstream
    # hermes CLI's /paste / Alt+V. Bearer-protected. Pairs with the
    # axiom-fork patch in hermes_cli/clipboard.py that consults the inbox
    # before native platform clipboard.
    app.router.add_post("/clipboard/inbox", handle_clipboard_inbox)
    app.router.add_post("/media/register", handle_media_register)
    # === PHASE3-bridge-server-followup: /media/upload ===
    app.router.add_post("/media/upload", handle_media_upload)
    # === END PHASE3-bridge-server-followup ===
    # Order matters: the fixed-path "/media/by-path" route must be declared
    # before the wildcard "/media/{token}" route or aiohttp will swallow
    # "by-path" as a token literal and handle_media_get will 404.
    app.router.add_get("/media/by-path", handle_media_by_path)
    app.router.add_get("/media/{token}", handle_media_get)

    # Voice endpoints — TTS / STT bridge, bearer-auth'd by voice_auth.
    async def _voice_transcribe(request: web.Request) -> web.StreamResponse:
        s: RelayServer = request.app["server"]
        return await s.voice.handle_transcribe(request)

    async def _voice_synthesize(request: web.Request) -> web.StreamResponse:
        s: RelayServer = request.app["server"]
        return await s.voice.handle_synthesize(request)

    async def _voice_config(request: web.Request) -> web.StreamResponse:
        s: RelayServer = request.app["server"]
        return await s.voice.handle_voice_config(request)

    async def _voice_output_config(request: web.Request) -> web.StreamResponse:
        s: RelayServer = request.app["server"]
        return await s.voice_output.handle_config(request)

    async def _voice_output_config_patch(request: web.Request) -> web.StreamResponse:
        s: RelayServer = request.app["server"]
        return await s.voice_output.handle_update_config(request)

    async def _voice_output_provider_options(request: web.Request) -> web.StreamResponse:
        s: RelayServer = request.app["server"]
        return await s.voice_output.handle_provider_options(request)

    async def _voice_output_provider_validate(request: web.Request) -> web.StreamResponse:
        s: RelayServer = request.app["server"]
        return await s.voice_output.handle_provider_validate(request)

    async def _voice_output_session(request: web.Request) -> web.StreamResponse:
        s: RelayServer = request.app["server"]
        return await s.voice_output.handle_create_session(request)

    async def _voice_output_ws(request: web.Request) -> web.StreamResponse:
        s: RelayServer = request.app["server"]
        return await s.voice_output.handle_ws(request)

    async def _voice_realtime_config(request: web.Request) -> web.StreamResponse:
        s: RelayServer = request.app["server"]
        return await s.realtime_voice.handle_config(request)

    async def _voice_realtime_config_patch(request: web.Request) -> web.StreamResponse:
        s: RelayServer = request.app["server"]
        return await s.realtime_voice.handle_update_config(request)

    async def _voice_realtime_provider_options(request: web.Request) -> web.StreamResponse:
        s: RelayServer = request.app["server"]
        return await s.realtime_voice.handle_provider_options(request)

    async def _voice_realtime_provider_validate(request: web.Request) -> web.StreamResponse:
        s: RelayServer = request.app["server"]
        return await s.realtime_voice.handle_provider_validate(request)

    async def _voice_realtime_session(request: web.Request) -> web.StreamResponse:
        s: RelayServer = request.app["server"]
        return await s.realtime_voice.handle_create_session(request)

    async def _voice_realtime_ws(request: web.Request) -> web.StreamResponse:
        s: RelayServer = request.app["server"]
        return await s.realtime_voice.handle_ws(request)

    async def _voice_realtime_agent_config(request: web.Request) -> web.StreamResponse:
        s: RelayServer = request.app["server"]
        return await s.realtime_agent.handle_config(request)

    async def _voice_realtime_agent_config_patch(request: web.Request) -> web.StreamResponse:
        s: RelayServer = request.app["server"]
        return await s.realtime_agent.handle_update_config(request)

    async def _voice_realtime_agent_provider_options(request: web.Request) -> web.StreamResponse:
        s: RelayServer = request.app["server"]
        return await s.realtime_agent.handle_provider_options(request)

    async def _voice_realtime_agent_provider_validate(request: web.Request) -> web.StreamResponse:
        s: RelayServer = request.app["server"]
        return await s.realtime_agent.handle_provider_validate(request)

    async def _voice_realtime_agent_session(request: web.Request) -> web.StreamResponse:
        s: RelayServer = request.app["server"]
        return await s.realtime_agent.handle_create_session(request)

    async def _voice_realtime_agent_ws(request: web.Request) -> web.StreamResponse:
        s: RelayServer = request.app["server"]
        return await s.realtime_agent.handle_ws(request)

    app.router.add_post("/voice/transcribe", _voice_transcribe)
    app.router.add_post("/voice/synthesize", _voice_synthesize)
    app.router.add_get("/voice/config", _voice_config)
    app.router.add_get("/voice/output/config", _voice_output_config)
    app.router.add_patch("/voice/output/config", _voice_output_config_patch)
    app.router.add_get(
        "/voice/output/providers/{provider_id}/options",
        _voice_output_provider_options,
    )
    app.router.add_post(
        "/voice/output/providers/{provider_id}/validate",
        _voice_output_provider_validate,
    )
    app.router.add_post("/voice/output/session", _voice_output_session)
    app.router.add_get("/voice/output/{session_id}", _voice_output_ws)
    app.router.add_get("/voice/realtime/config", _voice_realtime_config)
    app.router.add_patch("/voice/realtime/config", _voice_realtime_config_patch)
    app.router.add_get(
        "/voice/realtime/providers/{provider_id}/options",
        _voice_realtime_provider_options,
    )
    app.router.add_post(
        "/voice/realtime/providers/{provider_id}/validate",
        _voice_realtime_provider_validate,
    )
    app.router.add_post("/voice/realtime/session", _voice_realtime_session)
    app.router.add_get("/voice/realtime/{session_id}", _voice_realtime_ws)
    app.router.add_get("/voice/realtime-agent/config", _voice_realtime_agent_config)
    app.router.add_patch("/voice/realtime-agent/config", _voice_realtime_agent_config_patch)
    app.router.add_get(
        "/voice/realtime-agent/providers/{provider_id}/options",
        _voice_realtime_agent_provider_options,
    )
    app.router.add_post(
        "/voice/realtime-agent/providers/{provider_id}/validate",
        _voice_realtime_agent_provider_validate,
    )
    app.router.add_post("/voice/realtime-agent/session", _voice_realtime_agent_session)
    app.router.add_get("/voice/realtime-agent/{session_id}", _voice_realtime_agent_ws)

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
    app.router.add_post("/return_to_hermes", handle_bridge_return_to_hermes)
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
    app.router.add_post("/share_media", handle_bridge_share_media)
    app.router.add_post("/send_mms", handle_bridge_send_mms)
    # === END PHASE3-bridge-server ===

    # === PHASE3-status: loopback-gated structured Android bridge status ===
    app.router.add_get("/bridge/devices", handle_bridge_devices)
    app.router.add_post("/bridge/select-active", handle_bridge_select_active)
    app.router.add_get("/bridge/status", handle_bridge_status)
    # === END PHASE3-status ===

    # Dashboard-plugin loopback routes — see handlers above.
    app.router.add_get("/bridge/activity", handle_bridge_activity)
    app.router.add_get("/media/inspect", handle_media_inspect)
    app.router.add_get("/relay/info", handle_relay_info)
    app.router.add_get("/relay/security", handle_relay_security_get)
    app.router.add_patch("/relay/security", handle_relay_security_patch)

    # === PHASE3-notif-listener: notifications HTTP routes ===
    app.router.add_get("/notifications/recent", handle_notifications_recent)
    # Proactive push: agent → phone. Loopback-only (the phone platform
    # adapter POSTs here). Forwards over the phone WSS via ProactiveChannel.
    app.router.add_post("/phone/message", handle_phone_message)
    # Inbound reply leg (Phase 2c) — the gateway adapter long-polls here to
    # drain ``proactive.reply`` envelopes buffered by the ProactiveChannel.
    app.router.add_get("/phone/replies", handle_phone_replies)
    # Inspect / cancel the queued agent→phone outbound buffer (offline phone).
    app.router.add_get("/phone/outbound", handle_phone_outbound)
    app.router.add_delete("/phone/outbound", handle_phone_outbound)
    # Map phone Threads → chat_id (the field /api/sessions omits) so the app can
    # route a reply into the right Thread. Bearer for the app; loopback for diag.
    app.router.add_get("/phone/threads", handle_phone_threads)
    app.router.add_get("/relay/update-check", handle_relay_update_check)

    # Relay-owned agent context audit.
    app.router.add_get("/context/injected", handle_context_injected)

    # Profile-scoped read-only config + skills (§22).
    app.router.add_get(
        "/api/profiles/{name}/config", handle_profile_config
    )
    app.router.add_get(
        "/api/profiles/{name}/avatar", handle_profile_avatar
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
    # Profile-scoped SOUL + memory WRITE endpoints (§22 addendum).
    app.router.add_put(
        "/api/profiles/{name}/soul", handle_profile_soul_put
    )
    app.router.add_put(
        "/api/profiles/{name}/memory/{filename}",
        handle_profile_memory_put,
    )
    # === END PHASE3-notif-listener ===

    # Background profile-rescan task — catches filesystem-level
    # changes (operator edits config.yaml, drops a new SKILL.md, etc.)
    # that bypass the write endpoints. Registered as on_startup /
    # on_cleanup so the task lives exactly as long as the aiohttp app.
    app.on_startup.append(_on_app_startup)
    app.on_cleanup.append(_on_app_cleanup)

    # Cleanup on shutdown
    app.on_shutdown.append(_on_app_shutdown)

    return app


async def _on_app_startup(app: web.Application) -> None:
    """Launch background tasks that live for the app lifetime."""
    task = asyncio.create_task(
        _profile_rescan_loop(app), name="profile-rescan-loop"
    )
    app["_profile_rescan_task"] = task


async def _on_app_cleanup(app: web.Application) -> None:
    """Cancel background tasks cleanly."""
    task = app.get("_profile_rescan_task")
    if task is not None and not task.done():
        task.cancel()
        try:
            await task
        except (asyncio.CancelledError, Exception):
            pass


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
        "--allow-insecure-api-key",
        "--allow-insecure-api-bearer",
        action="store_true",
        dest="allow_insecure_api_bearer",
        help=(
            "Allow Hermes API-key voice auth over non-loopback plain HTTP "
            "(local LAN testing only)."
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
    if args.webapi_url is not None:
        config.webapi_url = args.webapi_url
    if args.config is not None or args.webapi_url is not None:
        # Reload profiles with any CLI-overridden Hermes home or API base URL.
        from .config import _load_profiles
        config.profiles = _load_profiles(
            config.hermes_config_path,
            enabled=config.profile_discovery_enabled,
            base_api_url=config.webapi_url,
        )
    if args.log_level is not None:
        config.log_level = args.log_level
    if args.no_ssl:
        config.ssl_cert = None
        config.ssl_key = None
    if args.allow_insecure_api_bearer:
        config.allow_insecure_api_bearer = True

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
