"""FastAPI proxy router for the Hermes-Relay dashboard plugin.

Loopback-only; mounted by hermes-agent at ``/api/plugins/hermes-relay/*``.

Most routes are thin pass-throughs to the already-running relay HTTP server
on ``127.0.0.1:{HERMES_RELAY_PORT}``. Provider usage is the deliberate
exception: the Dashboard process owns the live Gateway session and can resolve
its active credential without waiting for another turn.

Route map
---------
- ``GET /overview``         → relay ``GET /relay/info``
- ``GET /sessions``         → relay ``GET /sessions`` (loopback-exempt since R3)
- ``GET /bridge-activity``  → relay ``GET /bridge/activity`` (forwards ``limit``)
- ``GET /media``            → relay ``GET /media/inspect`` (forwards ``include_expired``)
- ``GET /agent-context``    → relay ``GET /context/injected`` + local env settings
- ``GET /provider-usage``   → live-session-aware provider usage from this plugin
- ``GET /push``             → static stub (no network call) until FCM is wired

Error translation
-----------------
- Relay connect-error / timeout / 5xx → ``502 Bad Gateway`` with a human-readable
  ``detail`` pointing at ``127.0.0.1:{RELAY_PORT}``.
- Relay 4xx → status + body passed through verbatim.
"""

from __future__ import annotations

import importlib
import asyncio
import hashlib
import hmac
import json
import os
import re
import sys
import time
import types
from pathlib import Path as FsPath
from typing import Any, Optional
from urllib.parse import quote, urlparse

import httpx
import aiohttp
from fastapi import APIRouter, Body, HTTPException, Path, Query, Request, Response, WebSocket
from fastapi.responses import JSONResponse
from starlette.websockets import WebSocketDisconnect

# ── Plugin-package bootstrap ──────────────────────────────────────────────
# hermes-agent's web server loads this file standalone via
# ``importlib.util.spec_from_file_location`` (no parent package), so relative
# imports cannot work here. The plugin package's import name also varies by
# install method: classic editable install = ``plugin``, native
# ``hermes plugins install`` = ``hermes_plugins.hermes_relay`` (issue #165).
# ``_plugin_module()`` resolves sibling plugin modules in every context:
#
# 1. Loaded as a submodule of the plugin package (tests, native loader) —
#    import through the REAL parent package so there is a single module
#    instance (test monkeypatching of e.g. ``plugin.relay.tailscale`` must
#    stay effective).
# 2. Loaded standalone by the dashboard web server — synthesize the parent
#    package: a bare ``ModuleType`` whose ``__path__`` points at the plugin
#    directory, registered in ``sys.modules`` under a stable alias. The
#    import system then resolves submodules against that path WITHOUT ever
#    exec'ing ``plugin/__init__.py`` (whose tool registration side effects
#    must not run inside the web server).

_PLUGIN_PKG_ALIAS = "_hermes_relay_plugin_pkg"


def _plugin_module(name: str) -> types.ModuleType:
    """Import ``<plugin package>.<name>`` in whatever layout we're running."""
    if __package__ and "." in __package__:
        # Assumes our parent package IS the plugin package — true for both real
        # layouts: ``plugin.dashboard`` → ``plugin`` and
        # ``hermes_plugins.hermes_relay.dashboard`` → ``hermes_plugins.hermes_relay``.
        parent = __package__.rsplit(".", 1)[0]  # strip trailing ".dashboard"
        return importlib.import_module(f"{parent}.{name}")
    pkg = sys.modules.get(_PLUGIN_PKG_ALIAS)
    if pkg is None:
        plugin_dir = FsPath(__file__).resolve().parent.parent
        pkg = types.ModuleType(_PLUGIN_PKG_ALIAS)
        pkg.__path__ = [str(plugin_dir)]  # type: ignore[attr-defined]
        pkg.__package__ = _PLUGIN_PKG_ALIAS
        sys.modules[_PLUGIN_PKG_ALIAS] = pkg
    return importlib.import_module(f"{_PLUGIN_PKG_ALIAS}.{name}")

# Read once at import time — hermes-agent restarts pick up env changes.
RELAY_PORT: int = int(os.environ.get("HERMES_RELAY_PORT", "8767"))
_RELAY_BASE: str = f"http://127.0.0.1:{RELAY_PORT}"
_TIMEOUT: float = 5.0

# The transport ingress is deliberately more constrained than the dashboard's
# management API.  It is an authenticated facade over selected client-facing
# Relay routes, not a generic loopback reverse proxy.
_TRANSPORT_REQUEST_LIMIT = 32 * 1024 * 1024
_TRANSPORT_RESPONSE_LIMIT = 64 * 1024 * 1024
_TRANSPORT_WS_LIMIT = 4 * 1024 * 1024
_TRANSPORT_TIMEOUT = httpx.Timeout(120.0, connect=5.0, read=60.0, write=60.0)
_TRANSPORT_SESSION_HEADER = "X-Hermes-Relay-Session"
_DASHBOARD_PROXY_SECRET_HEADER = "X-Hermes-Dashboard-Proxy-Secret"
_PROXY_PEER_HEADER = "X-Hermes-Proxy-Peer"
_PROXY_PROTO_HEADER = "X-Hermes-Proxy-Proto"
_SESSION_ID_RE = re.compile(r"^[A-Za-z0-9_-]{16,128}$")
_OPAQUE_MEDIA_TOKEN_RE = re.compile(r"^[A-Za-z0-9_-]{20,64}$")
_SESSION_PREFIX_RE = re.compile(r"^[A-Za-z0-9_-]{8,64}$")
_DASHBOARD_PROXY_CONTEXT = b"hermes-relay/dashboard-ingress/v1"

_HTTP_ROUTE_SPECS: tuple[tuple[str, str], ...] = (
    ("GET", "/health"),
    ("POST", "/media/upload"),
    ("GET", "/media/by-path"),
    ("GET", "/media/{token}"),
    ("POST", "/clipboard/inbox"),
    ("GET", "/chat/image-activity"),
    ("GET", "/context/injected"),
    ("GET", "/phone/threads"),
    ("GET", "/relay/info"),
    ("GET", "/relay/update-check"),
    ("POST", "/relay/model-capabilities"),
    ("GET", "/usage/providers"),
    ("GET", "/sessions"),
    ("PATCH", "/sessions/{token_prefix}"),
    ("DELETE", "/sessions/{token_prefix}"),
    ("GET", "/api/profiles/{name}/config"),
    ("GET", "/api/profiles/{name}/avatar"),
    ("GET", "/api/profiles/{name}/skills"),
    ("GET", "/api/profiles/{name}/soul"),
    ("PUT", "/api/profiles/{name}/soul"),
    ("GET", "/api/profiles/{name}/memory"),
    ("PUT", "/api/profiles/{name}/memory/{filename}"),
    ("POST", "/voice/transcribe"),
    ("POST", "/voice/synthesize"),
    ("GET", "/voice/config"),
    ("GET", "/voice/output/config"),
    ("PATCH", "/voice/output/config"),
    ("GET", "/voice/output/providers/{provider_id}/options"),
    ("POST", "/voice/output/providers/{provider_id}/validate"),
    ("POST", "/voice/output/session"),
    ("GET", "/voice/realtime/config"),
    ("PATCH", "/voice/realtime/config"),
    ("GET", "/voice/realtime/providers/{provider_id}/options"),
    ("POST", "/voice/realtime/providers/{provider_id}/validate"),
    ("POST", "/voice/realtime/session"),
    ("GET", "/voice/realtime-agent/config"),
    ("PATCH", "/voice/realtime-agent/config"),
    ("GET", "/voice/realtime-agent/providers/{provider_id}/options"),
    ("POST", "/voice/realtime-agent/providers/{provider_id}/validate"),
    ("POST", "/voice/realtime-agent/session"),
)

_RESPONSE_HEADERS = frozenset({
    "accept-ranges", "cache-control", "content-disposition", "content-language",
    "content-range", "content-type", "etag", "expires", "last-modified",
    "retry-after", "vary", "www-authenticate",
})
_REQUEST_HEADERS = frozenset({
    "accept", "accept-encoding", "content-encoding", "content-type", "if-match",
    "if-modified-since", "if-none-match", "if-range", "range", "user-agent",
})

# Per-host state file for the "public URL" the operator pinned into the
# Remote Access tab. Lives alongside the other ``~/.hermes/`` state so a
# hermes-agent restart preserves it. Deliberately small + human-readable
# so operators can clear it with a plain editor if needed.
_REMOTE_STATE_FILENAME = "relay-remote.json"


def _hermes_home() -> FsPath:
    return FsPath(os.environ.get("HERMES_HOME", FsPath.home() / ".hermes"))


def _remote_state_path() -> FsPath:
    return _hermes_home() / _REMOTE_STATE_FILENAME


def _read_remote_state() -> dict[str, Any]:
    """Read the pinned-endpoint state file. Missing/malformed → empty dict."""
    path = _remote_state_path()
    try:
        raw = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        return {}
    except OSError:
        return {}
    try:
        data = json.loads(raw)
    except (ValueError, json.JSONDecodeError):
        return {}
    if not isinstance(data, dict):
        return {}
    return data


def _write_remote_state(state: dict[str, Any]) -> None:
    """Atomically persist the remote state. Best-effort — raises on OSError."""
    path = _remote_state_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(state, indent=2, sort_keys=True), encoding="utf-8")
    os.replace(tmp, path)


def _validate_public_url(url: str) -> str:
    """Validate & normalize a public URL. Empty string → '' (clears)."""
    trimmed = url.strip()
    if not trimmed:
        return ""
    try:
        return _plugin_module("pair").normalize_dashboard_url(trimmed)
    except ValueError as exc:
        raise ValueError(f"invalid public Dashboard URL: {exc}") from exc


router = APIRouter()
router.include_router(_plugin_module("dashboard.mobile_plugin_api").router)
router.include_router(_plugin_module("dashboard.git_api").router)


def _dashboard_proxy_secret() -> str:
    """Return a purpose-separated credential shared with the loopback Relay.

    An operator may supply an explicit process-shared value.  The normal path
    derives a distinct credential from Relay's existing private host secret,
    so no additional secret needs to be copied into Android or the Dashboard.
    """
    configured = os.environ.get("HERMES_RELAY_DASHBOARD_PROXY_SECRET", "").strip()
    if configured:
        return hashlib.sha256(_DASHBOARD_PROXY_CONTEXT + configured.encode()).hexdigest()
    qr_sign = _plugin_module("relay.qr_sign")
    root_secret = qr_sign.load_or_create_secret()
    return hmac.new(root_secret, _DASHBOARD_PROXY_CONTEXT, hashlib.sha256).hexdigest()


def _outer_proto(scope_scheme: str) -> str:
    return "wss" if scope_scheme.lower() in {"https", "wss"} else "ws"


def _transport_peer(request: Request | WebSocket) -> str:
    client = request.client
    if client is None:
        return "unknown"
    peer = str(client.host or "").strip()
    return peer[:128] or "unknown"


def _relay_transport_path(template: str, request: Request | WebSocket) -> str:
    media_token = request.path_params.get("token")
    if media_token is not None and not _OPAQUE_MEDIA_TOKEN_RE.fullmatch(str(media_token)):
        raise HTTPException(status_code=404, detail="relay route not found")
    token_prefix = request.path_params.get("token_prefix")
    if token_prefix is not None and not _SESSION_PREFIX_RE.fullmatch(str(token_prefix)):
        raise HTTPException(status_code=404, detail="relay route not found")
    values = {
        name: quote(str(value), safe="")
        for name, value in request.path_params.items()
    }
    return template.format_map(values)


def _transport_session(request: Request | WebSocket, *, required: bool = True) -> str:
    token = request.headers.get(_TRANSPORT_SESSION_HEADER, "").strip()
    if not token and required:
        raise HTTPException(status_code=401, detail="relay session header required")
    if len(token) > 8192 or "\r" in token or "\n" in token:
        raise HTTPException(status_code=400, detail="invalid relay session header")
    return token


def _transport_request_headers(request: Request | WebSocket, token: str) -> dict[str, str]:
    headers = {
        name: value
        for name, value in request.headers.items()
        if name.lower() in _REQUEST_HEADERS
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"
    headers.update({
        _DASHBOARD_PROXY_SECRET_HEADER: _dashboard_proxy_secret(),
        _PROXY_PEER_HEADER: _transport_peer(request),
        _PROXY_PROTO_HEADER: _outer_proto(request.url.scheme),
    })
    return headers


async def _proxy_transport_http(request: Request, relay_template: str) -> Response:
    relay_path = _relay_transport_path(relay_template, request)
    token = _transport_session(request, required=relay_path != "/health")
    content_length = request.headers.get("content-length", "")
    if content_length:
        try:
            if int(content_length) > _TRANSPORT_REQUEST_LIMIT:
                raise HTTPException(status_code=413, detail="relay request exceeds size limit")
        except ValueError as exc:
            raise HTTPException(status_code=400, detail="invalid content length") from exc
    # GET ingress routes never carry an application body. Some HTTP/2 reverse
    # proxies do not emit a body-complete frame until the downstream client
    # closes; awaiting ``request.body()`` here therefore held /transport/health
    # open until Android's probe timeout, accumulating ClientDisconnect tasks in
    # the Dashboard process. Body-bearing methods retain the bounded read.
    body = b"" if request.method in {"GET", "HEAD"} else await request.body()
    if len(body) > _TRANSPORT_REQUEST_LIMIT:
        raise HTTPException(status_code=413, detail="relay request exceeds size limit")

    try:
        async with httpx.AsyncClient(timeout=_TRANSPORT_TIMEOUT) as client:
            async with client.stream(
                request.method,
                f"{_RELAY_BASE}{relay_path}",
                params=request.query_params,
                headers=_transport_request_headers(request, token),
                content=body,
                follow_redirects=False,
            ) as upstream:
                response_length = upstream.headers.get("content-length", "")
                if response_length:
                    try:
                        if int(response_length) > _TRANSPORT_RESPONSE_LIMIT:
                            raise HTTPException(
                                status_code=502,
                                detail="relay response exceeds size limit",
                            )
                    except ValueError:
                        pass
                response_body = bytearray()
                async for chunk in upstream.aiter_bytes():
                    response_body.extend(chunk)
                    if len(response_body) > _TRANSPORT_RESPONSE_LIMIT:
                        raise HTTPException(
                            status_code=502,
                            detail="relay response exceeds size limit",
                        )
                status_code = upstream.status_code
                response_headers = dict(upstream.headers)
    except httpx.TimeoutException as exc:
        raise HTTPException(status_code=504, detail="relay transport timed out") from exc
    except httpx.HTTPError as exc:
        raise _relay_unreachable(exc) from exc

    headers = {
        name: value
        for name, value in response_headers.items()
        if name.lower() in _RESPONSE_HEADERS
    }
    if relay_path == "/health" and 200 <= status_code < 300:
        try:
            health = json.loads(response_body)
        except (UnicodeDecodeError, json.JSONDecodeError):
            health = {"relay_status": "available"}
        if not isinstance(health, dict):
            health = {"relay_status": "available"}
        health["dashboard_ingress"] = {
            "available": True,
            "path": "/api/plugins/hermes-relay/transport",
            "capabilities": ["relay_http", "relay_websocket"],
        }
        return JSONResponse(content=health, status_code=status_code, headers=headers)
    return Response(content=bytes(response_body), status_code=status_code, headers=headers)


def _dashboard_ws_guards() -> tuple[Any, Any] | None:
    """Feature-detect the Dashboard's private WS guards without importing it.

    The current upstream plugin contract mounts routers but does not inject an
    auth dependency for WebSockets.  Using the host's already-loaded helpers
    keeps ticket consumption and Host/Origin/IP policy identical to `/api/ws`.
    If upstream moves either helper, this ingress fails closed.
    """
    candidates = [sys.modules.get("hermes_cli.web_server")]
    candidates.extend(
        module for name, module in tuple(sys.modules.items())
        if name.endswith(".web_server") and module not in candidates
    )
    for module in candidates:
        allowed = getattr(module, "_ws_request_is_allowed", None)
        authed = getattr(module, "_ws_auth_ok", None)
        if callable(allowed) and callable(authed):
            return allowed, authed
    return None


def _dashboard_plugin_is_enabled() -> bool:
    """Mirror the host runtime gate for WebSockets, which skip HTTP middleware."""
    module = sys.modules.get("hermes_cli.web_server")
    get_plugins = getattr(module, "_get_dashboard_plugins", None)
    if not callable(get_plugins):
        return False
    try:
        plugin = next(
            (item for item in get_plugins() if item.get("name") == "hermes-relay"),
            None,
        )
        if not isinstance(plugin, dict):
            return False
        plugins_cmd = importlib.import_module("hermes_cli.plugins_cmd")
        enabled = plugins_cmd._get_enabled_set()
        disabled = plugins_cmd._get_disabled_set()
        source = plugin.get("source")
        if source == "bundled":
            return "hermes-relay" not in disabled
        if source == "user":
            return "hermes-relay" in enabled and "hermes-relay" not in disabled
        return False
    except Exception:
        return False


async def _admit_transport_websocket(websocket: WebSocket) -> str | None:
    if not _dashboard_plugin_is_enabled():
        await websocket.close(code=1008, reason="Relay plugin is disabled")
        return None
    guards = _dashboard_ws_guards()
    if guards is None:
        await websocket.close(code=1011, reason="Dashboard WebSocket auth unavailable")
        return None
    request_allowed, auth_ok = guards
    try:
        if not request_allowed(websocket) or not auth_ok(websocket):
            await websocket.close(code=1008, reason="Dashboard WebSocket access denied")
            return None
    except Exception:
        await websocket.close(code=1011, reason="Dashboard WebSocket auth unavailable")
        return None
    selected = getattr(websocket, "_hermes_ws_subprotocol", None)
    offered = {
        value.strip()
        for value in websocket.headers.get("sec-websocket-protocol", "").split(",")
        if value.strip()
    }
    return selected if selected in offered else ""


async def _proxy_transport_websocket(
    websocket: WebSocket,
    relay_path: str,
    *,
    require_session_header: bool,
) -> None:
    selected_protocol = await _admit_transport_websocket(websocket)
    if websocket.application_state.name == "DISCONNECTED":
        return
    try:
        token = _transport_session(websocket, required=require_session_header)
    except HTTPException:
        await websocket.close(code=1008, reason="Relay session header required")
        return
    headers = _transport_request_headers(websocket, token)
    try:
        async with aiohttp.ClientSession(
            timeout=aiohttp.ClientTimeout(total=None, connect=5, sock_read=90)
        ) as client:
            async with client.ws_connect(
                f"ws://127.0.0.1:{RELAY_PORT}{relay_path}",
                headers=headers,
                heartbeat=30,
                max_msg_size=_TRANSPORT_WS_LIMIT,
            ) as upstream:
                await websocket.accept(subprotocol=selected_protocol or None)

                async def downstream_to_upstream() -> None:
                    try:
                        while True:
                            message = await websocket.receive()
                            message_type = message["type"]
                            if message_type == "websocket.disconnect":
                                await upstream.close(code=int(message.get("code") or 1000))
                                return
                            text_data = message.get("text")
                            bytes_data = message.get("bytes")
                            if text_data is not None:
                                if len(text_data.encode("utf-8")) > _TRANSPORT_WS_LIMIT:
                                    await upstream.close(code=1009)
                                    return
                                await upstream.send_str(text_data)
                            elif bytes_data is not None:
                                if len(bytes_data) > _TRANSPORT_WS_LIMIT:
                                    await upstream.close(code=1009)
                                    return
                                await upstream.send_bytes(bytes_data)
                    except WebSocketDisconnect as exc:
                        await upstream.close(code=exc.code or 1000)

                async def upstream_to_downstream() -> None:
                    async for message in upstream:
                        if message.type == aiohttp.WSMsgType.TEXT:
                            await websocket.send_text(message.data)
                        elif message.type == aiohttp.WSMsgType.BINARY:
                            await websocket.send_bytes(message.data)
                        elif message.type in {
                            aiohttp.WSMsgType.CLOSE,
                            aiohttp.WSMsgType.CLOSING,
                            aiohttp.WSMsgType.CLOSED,
                        }:
                            await websocket.close(
                                code=int(upstream.close_code or 1000),
                                reason=str(message.extra or ""),
                            )
                            return
                        elif message.type == aiohttp.WSMsgType.ERROR:
                            await websocket.close(code=1011, reason="Relay WebSocket failed")
                            return
                    # aiohttp's client iterator normally stops when it consumes
                    # the peer CLOSE frame instead of yielding that frame.  Do
                    # not leave the Dashboard client half-open in that normal
                    # shutdown path.
                    if websocket.application_state.name != "DISCONNECTED":
                        await websocket.close(
                            code=int(upstream.close_code or 1000),
                            reason="",
                        )

                async def plugin_enabled_watch() -> None:
                    while _dashboard_plugin_is_enabled():
                        await asyncio.sleep(2)
                    await upstream.close(code=1008, message=b"Relay plugin disabled")
                    await websocket.close(code=1008, reason="Relay plugin is disabled")

                tasks = {
                    asyncio.create_task(downstream_to_upstream()),
                    asyncio.create_task(upstream_to_downstream()),
                    asyncio.create_task(plugin_enabled_watch()),
                }
                done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
                for task in pending:
                    task.cancel()
                await asyncio.gather(*done, *pending, return_exceptions=True)
    except (aiohttp.ClientError, asyncio.TimeoutError):
        if websocket.application_state.name != "DISCONNECTED":
            await websocket.close(code=1011, reason="Relay WebSocket unavailable")


@router.websocket("/transport/ws")
async def transport_websocket(websocket: WebSocket) -> None:
    # Relay itself still owns first-frame pairing/session authentication.
    await _proxy_transport_websocket(websocket, "/ws", require_session_header=False)


async def _voice_transport_websocket(websocket: WebSocket, relay_template: str) -> None:
    session_id = str(websocket.path_params.get("session_id", ""))
    if not _SESSION_ID_RE.fullmatch(session_id):
        await websocket.close(code=1008, reason="Invalid Relay voice session")
        return
    await _proxy_transport_websocket(
        websocket,
        _relay_transport_path(relay_template, websocket),
        require_session_header=True,
    )


@router.websocket("/transport/voice/output/{session_id}")
async def transport_voice_output(websocket: WebSocket) -> None:
    await _voice_transport_websocket(websocket, "/voice/output/{session_id}")


@router.websocket("/transport/voice/realtime/{session_id}")
async def transport_voice_realtime(websocket: WebSocket) -> None:
    await _voice_transport_websocket(websocket, "/voice/realtime/{session_id}")


@router.websocket("/transport/voice/realtime-agent/{session_id}")
async def transport_voice_realtime_agent(websocket: WebSocket) -> None:
    await _voice_transport_websocket(websocket, "/voice/realtime-agent/{session_id}")


def _make_transport_endpoint(relay_template: str) -> Any:
    async def endpoint(request: Request) -> Response:
        return await _proxy_transport_http(request, relay_template)

    return endpoint


for _transport_method, _transport_path in _HTTP_ROUTE_SPECS:
    router.add_api_route(
        f"/transport{_transport_path}",
        _make_transport_endpoint(_transport_path),
        methods=[_transport_method],
        name=f"relay_transport_{_transport_method.lower()}_{_transport_path}",
        include_in_schema=False,
    )


def _relay_unreachable(err: Exception) -> HTTPException:
    """Build the canonical 502 for connect-errors / timeouts / 5xx."""
    return HTTPException(
        status_code=502,
        detail=f"relay unreachable at 127.0.0.1:{RELAY_PORT}: {err}",
    )


async def _proxy_get(
    path: str,
    *,
    params: Optional[dict[str, Any]] = None,
) -> Any:
    """Forward a GET to the relay, translating errors per this module's contract.

    - Network failures (connect/timeout) and 5xx responses → ``HTTPException(502)``.
    - 4xx responses → ``HTTPException(status, detail=<relay body>)`` passthrough.
    - 2xx → returns the parsed JSON body (or raw text if not JSON).
    """
    url = f"{_RELAY_BASE}{path}"
    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
            resp = await client.get(url, params=params)
    except (httpx.TimeoutException, httpx.ConnectError, httpx.TransportError) as err:
        raise _relay_unreachable(err) from err
    except httpx.HTTPError as err:
        # Any other httpx-level error → treat as unreachable.
        raise _relay_unreachable(err) from err

    if 500 <= resp.status_code < 600:
        raise _relay_unreachable(
            RuntimeError(f"relay returned {resp.status_code}: {resp.text[:200]}")
        )

    if 400 <= resp.status_code < 500:
        # Pass 4xx through. Prefer JSON body if possible, else text.
        try:
            detail: Any = resp.json()
        except ValueError:
            detail = resp.text
        raise HTTPException(status_code=resp.status_code, detail=detail)

    # 2xx — return parsed JSON (or raw text as a fallback).
    try:
        return resp.json()
    except ValueError:
        return resp.text


@router.get("/overview")
async def get_overview() -> Any:
    """Aggregate relay status for the management tab."""
    return await _proxy_get("/relay/info")


@router.get("/sessions")
async def get_sessions() -> Any:
    """Paired-device session list (loopback branch on relay — no bearer needed)."""
    return await _proxy_get("/sessions")


@router.get("/bridge-activity")
async def get_bridge_activity(limit: Optional[int] = Query(default=None)) -> Any:
    """Recent bridge commands ring buffer."""
    params: dict[str, Any] = {}
    if limit is not None:
        params["limit"] = limit
    return await _proxy_get("/bridge/activity", params=params or None)


@router.get("/media")
async def get_media(include_expired: Optional[bool] = Query(default=None)) -> Any:
    """Active MediaRegistry tokens (basename-only, no absolute paths)."""
    params: dict[str, Any] = {}
    if include_expired is not None:
        # httpx serializes bool as "True"/"False"; relay expects lower-case.
        params["include_expired"] = "true" if include_expired else "false"
    return await _proxy_get("/media/inspect", params=params or None)


@router.get("/agent-context")
async def get_agent_context() -> dict[str, Any]:
    """Return current Agent context flags and the relay audit payload."""
    config = _plugin_module("config")

    return {
        "settings": {
            "RELAY_AGENT_CONTEXT_ENABLED": config.agent_context_enabled(),
            "RELAY_CONTEXT_MEDIA_SENSITIVITY": config.context_media_sensitivity_enabled(),
        },
        "injected": await _proxy_get("/context/injected"),
    }


@router.get("/provider-usage")
async def get_provider_usage(
    profile: Optional[str] = Query(default=None),
    session_id: Optional[str] = Query(default=None),
) -> dict[str, Any]:
    """Return provider usage with the live session's active pool entry.

    This route runs inside the Dashboard process that owns ``tui_gateway``.
    Unlike the standalone Relay server, it can read the already-instantiated
    agent directly and therefore does not need a new turn to learn which
    credential is active.
    """
    provider_usage = _plugin_module("relay.provider_usage")
    hooks = _plugin_module("hooks")
    try:
        profile_home = provider_usage.resolve_profile_home(
            str(_hermes_home() / "config.yaml"),
            profile,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    active_credential_id = None
    live = hooks.resolve_live_active_credential(session_id or "")
    if (
        isinstance(live, dict)
        and live.get("provider_id") == "openai-codex"
        and FsPath(live.get("profile_home")).resolve() == profile_home
    ):
        active_credential_id = str(live.get("credential_id") or "") or None

    return await provider_usage.collect_provider_usage(
        profile_home=profile_home,
        session_id=session_id,
        active_credential_id=active_credential_id,
    )


@router.get("/phone/config")
async def get_phone_config() -> dict[str, Any]:
    """Phone-platform home-channel config for the Management tab.

    Reads the same env the adapter resolves, so the dashboard surfaces the
    *effective* home-channel name + id. The name is editable from the tab via
    the host ``PUT /api/env`` (``PHONE_HOME_CHANNEL_NAME``); the id is shown
    read-only because changing it would orphan existing phone Threads (the
    ``chat_id`` keys the gateway session). ``enabled`` reflects the
    ``PHONE_ENABLED`` gate so the tab can hide the card when the platform is
    off. No relay round-trip — env is process-local.
    """
    phone_platform = _plugin_module("phone_platform")

    return {
        "enabled": phone_platform._phone_enabled(),
        "home_channel_id": phone_platform._home_channel(),
        "home_channel_name": phone_platform._home_channel_name(),
        "name_env_key": "PHONE_HOME_CHANNEL_NAME",
    }


# Update-check cache — a GitHub round-trip per dashboard poll would be wasteful
# and risks rate-limiting. Cache the resolved latest tag for an hour; the
# current/compare/command are recomputed cheaply each call.
_UPDATE_CACHE: dict[str, Any] = {"latest": None, "fetched_at": 0.0, "error": None}
_UPDATE_CACHE_TTL: float = 3600.0


@router.get("/update-check")
async def get_update_check(refresh: Optional[bool] = Query(default=False)) -> dict[str, Any]:
    """Report whether a newer hermes-relay plugin release is available.

    Compares the installed ``plugin.relay.__version__`` against the latest
    ``server-v*`` GitHub release (with historical ``plugin-v*`` fallback). The GitHub fetch is cached for an hour
    (``?refresh=true`` forces it) so a polling dashboard doesn't hammer the
    releases API. Network failures degrade to ``update_available=false`` with
    an ``error`` string — never a 5xx — so the card can show "couldn't check".
    """
    update_check = _plugin_module("update_check")

    now = time.time()
    stale = (now - _UPDATE_CACHE["fetched_at"]) > _UPDATE_CACHE_TTL
    if refresh or stale or _UPDATE_CACHE["fetched_at"] == 0.0:
        latest, error = None, None
        try:
            async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
                resp = await client.get(
                    update_check.GITHUB_RELEASES_URL,
                    headers={
                        "Accept": "application/vnd.github+json",
                        "User-Agent": "hermes-relay-update-check",
                    },
                )
            if resp.status_code == 200:
                latest = update_check.pick_latest_plugin_tag(resp.json())
            else:
                error = f"GitHub returned {resp.status_code}"
        except Exception as exc:  # network down, rate-limited, bad JSON
            error = str(exc)
        _UPDATE_CACHE.update(latest=latest, error=error, fetched_at=now)

    result = update_check.build_result(update_check.current_version(), _UPDATE_CACHE["latest"])
    result["error"] = _UPDATE_CACHE["error"]
    result["checked_at"] = int(_UPDATE_CACHE["fetched_at"])
    return result


@router.get("/push")
async def get_push() -> dict[str, Any]:
    """Push console stub — no network call until FCM lands."""
    return {
        "configured": False,
        "reason": (
            "FCM not yet wired; see docs/plans/2026-04-18-dashboard-plugin.md "
            "and the 'Deferred Features' memory entry."
        ),
    }


async def _proxy(
    method: str,
    path: str,
    *,
    json: Optional[dict[str, Any]] = None,
) -> Any:
    """Forward an arbitrary method to the relay, translating errors."""
    url = f"{_RELAY_BASE}{path}"
    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
            resp = await client.request(method, url, json=json)
    except (httpx.TimeoutException, httpx.ConnectError, httpx.TransportError) as err:
        raise _relay_unreachable(err) from err
    except httpx.HTTPError as err:
        raise _relay_unreachable(err) from err

    if 500 <= resp.status_code < 600:
        raise _relay_unreachable(
            RuntimeError(f"relay returned {resp.status_code}: {resp.text[:200]}")
        )
    if 400 <= resp.status_code < 500:
        try:
            detail: Any = resp.json()
        except ValueError:
            detail = resp.text
        raise HTTPException(status_code=resp.status_code, detail=detail)
    try:
        return resp.json()
    except ValueError:
        return resp.text


@router.post("/pairing")
async def mint_pairing(body: dict[str, Any] = Body(default_factory=dict)) -> Any:
    """Mint a fresh pairing code + return a signed QR payload.

    Body (all fields optional — relay fills them from its config):
      - host: "192.168.1.100"     API server host the phone will hit
                                  (defaults to RelayConfig.webapi_url host,
                                  resolved to a LAN-routable IP)
      - port: 8642                API server port
      - tls: false                API server TLS
      - api_key: "<token>"        Optional API bearer token override. When
                                  omitted, the relay reads the same local
                                  Hermes API key config as `hermes-pair`.
      - ttl_seconds: <int>        Session TTL
      - grants: {...}             Per-channel TTL map
      - transport_hint: "wss"|"ws"

    Multi-endpoint (ADR 24 — optional, additive):
      - mode: "auto"|"lan"|"tailscale"|"public"
                                  Triggers ``build_endpoint_candidates``
                                  from ``plugin.pair``. When present, the
                                  resulting endpoints array is forwarded
                                  to the relay so the signed payload
                                  bumps to v3.
      - public_url: "https://relay.example.com"
                                  Required when ``mode == "public"``.
                                  Optional (but consumed) in ``auto`` —
                                  when absent and the caller has pinned
                                  one via ``PUT /remote-access/public-url``
                                  we fall back to that stored value.
      - prefer: "lan"|"tailscale"|"public"|...
                                  Promotes the named role to priority 0
                                  with the rest renumbered in their
                                  natural order. Warns (server-side
                                  stderr) when the named role isn't in
                                  the detected candidates and emits the
                                  natural order instead.

    The returned ``qr_payload`` matches the schema in the Android app's
    ``QrPairingScanner.kt``: top-level host/port/key/tls configure the
    Hermes API server, and the nested ``relay`` block (which the relay
    fills in with its own URL + the minted pairing code) configures WSS.
    The dashboard does not need to handle the API key directly; the relay
    inserts it from host-local config unless the request explicitly
    overrides ``api_key``.
    """
    if "api_enabled" not in body:
        try:
            body["api_enabled"] = bool(_plugin_module("pair").read_server_config().get("enabled"))
        except Exception:
            # Old/partial installs retain the legacy Relay-side default.
            pass

    mode_raw = body.pop("mode", None)
    public_url_raw = body.pop("public_url", None)
    prefer_raw = body.pop("prefer", None)
    dashboard_url_raw = body.get("dashboard_url") or body.get("dashboardUrl")
    legacy_direct_relay_explicit = "legacy_direct_relay" in body
    legacy_direct_relay_raw = body.get("legacy_direct_relay", False)
    if not isinstance(legacy_direct_relay_raw, bool):
        raise HTTPException(
            status_code=400, detail="legacy_direct_relay must be a boolean"
        )

    pair = _plugin_module("pair")
    if dashboard_url_raw is not None:
        try:
            body["dashboard_url"] = pair.normalize_dashboard_url(
                str(dashboard_url_raw)
            )
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        body.pop("dashboardUrl", None)

    if mode_raw is not None:
        mode = str(mode_raw).strip().lower()
        # Lazy import so a bare dashboard install without plugin/pair.py
        # on sys.path (smoke tests, docs render, etc.) still loads the
        # module. Any failure here becomes a 500 via HTTPException below.
        try:
            build_endpoint_candidates = pair.build_endpoint_candidates
            read_relay_config = pair.read_relay_config
        except ImportError as exc:
            raise HTTPException(
                status_code=500,
                detail=f"endpoint builder unavailable: {exc}",
            ) from exc

        # Operator may have stored the public URL via the Remote Access
        # tab — use that when the request body omits it. Empty string
        # after validation counts as "cleared" → treat as absent.
        effective_public_url: Optional[str] = None
        if isinstance(public_url_raw, str) and public_url_raw.strip():
            effective_public_url = public_url_raw.strip()
        else:
            remote_state = _read_remote_state()
            pinned = remote_state.get("public_url")
            if isinstance(pinned, str) and pinned.strip():
                effective_public_url = pinned.strip()
                if (
                    not legacy_direct_relay_explicit
                    and remote_state.get("legacy_direct_relay") is True
                ):
                    legacy_direct_relay_raw = True
                    body["legacy_direct_relay"] = True

        # API defaults come from the same config chain ``pair.py`` uses so
        # the dashboard-minted QR matches what ``hermes-pair --mode auto``
        # would emit from the CLI.
        api_cfg = pair.read_server_config()
        relay_cfg = read_relay_config()
        api_host = str(body.get("host") or api_cfg.get("host") or "127.0.0.1")
        api_port = int(body.get("port") or api_cfg.get("port") or 8642)
        api_tls = bool(body.get("tls") if body.get("tls") is not None else api_cfg.get("tls"))

        prefer: Optional[str] = None
        if isinstance(prefer_raw, str) and prefer_raw.strip():
            prefer = prefer_raw.strip()

        try:
            endpoints = build_endpoint_candidates(
                mode=mode,
                api_host=api_host,
                api_port=api_port,
                api_tls=api_tls,
                relay_host=relay_cfg["host"],
                relay_port=relay_cfg["port"],
                relay_tls=bool(relay_cfg.get("tls")),
                public_url=effective_public_url,
                prefer=prefer,
                legacy_direct_relay=legacy_direct_relay_raw,
            )
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

        if endpoints:
            # The relay's ``/pairing/mint`` accepts an opaque ``endpoints``
            # list and mirrors it through HMAC signing verbatim. Drop
            # ``mode`` / ``public_url`` on the way out so the relay never
            # tries to interpret them.
            body["endpoints"] = endpoints

    return await _proxy("POST", "/pairing/mint", json=body)


# ── Remote Access tab ────────────────────────────────────────────────────────
#
# Routes that expose the Tailscale helper + a persisted "public URL"
# alongside an aggregate status endpoint. These sit at
# ``/api/plugins/hermes-relay/remote-access/*`` and are consumed by the
# dashboard's Remote Access React tab. Same loopback-trust model as the
# other routes in this file — the dashboard is loopback-only and we
# never accept callers from elsewhere.

_TAILSCALE_MANAGED_PORTS = frozenset((9119, 8642, 8767))


def _managed_tailscale_port(body: dict[str, Any]) -> int:
    """Return a dashboard-managed port, rejecting arbitrary proxy targets."""
    port_raw = body.get("port", 9119)
    if isinstance(port_raw, bool):
        raise HTTPException(
            status_code=400, detail=f"port must be an integer (got {port_raw!r})"
        )
    try:
        port = int(port_raw)
    except (TypeError, ValueError) as exc:
        raise HTTPException(
            status_code=400, detail=f"port must be an integer (got {port_raw!r})"
        ) from exc
    if port not in _TAILSCALE_MANAGED_PORTS:
        supported = ", ".join(str(value) for value in sorted(_TAILSCALE_MANAGED_PORTS))
        raise HTTPException(
            status_code=400,
            detail=(
                "dashboard may manage only Dashboard 9119, optional API 8642, "
                f"or legacy Relay 8767: {supported}"
            ),
        )
    return port


def _tailscale_status_dict() -> dict[str, Any]:
    """Flatten ``tailscale.status()`` into a JSON-safe dict.

    The helper returns ``None`` when the CLI is missing or the daemon
    is sulking. We prefer to surface that explicitly so the UI can
    render a "not installed" state without a second round-trip.
    """
    try:
        tailscale = _plugin_module("relay.tailscale")
    except ImportError:
        return {"available": False, "reason": "helper not importable"}
    try:
        status = tailscale.status()
    except Exception as exc:  # defensive — helper promises not to raise
        return {"available": False, "reason": f"helper raised: {exc}"}
    if status is None:
        return {"available": False, "reason": "tailscale daemon not reachable"}
    normalized = dict(status)
    serve_ports = {
        port for port in normalized.get("serve_ports", []) if isinstance(port, int)
    }
    services = normalized.get("serve_services")
    service_map = services if isinstance(services, dict) else {}
    dashboard_service = service_map.get("dashboard")
    api_service = service_map.get("api")
    legacy_service = service_map.get("legacy_relay")
    normalized.update({
        "dashboard_9119_active": (
            dashboard_service.get("active") is True
            if isinstance(dashboard_service, dict)
            else 9119 in serve_ports
        ),
        "api_8642_active": (
            api_service.get("active") is True
            if isinstance(api_service, dict)
            else 8642 in serve_ports
        ),
        "legacy_8767_active": (
            legacy_service.get("active") is True
            if isinstance(legacy_service, dict)
            else 8767 in serve_ports
        ),
    })
    return normalized


def _canonical_upstream_present() -> bool:
    try:
        tailscale = _plugin_module("relay.tailscale")
    except ImportError:
        return False
    try:
        return bool(tailscale.canonical_upstream_present())
    except Exception:
        return False


@router.get("/remote-access/status")
async def get_remote_access_status() -> dict[str, Any]:
    """Aggregate status for the Remote Access tab.

    Returns ``{tailscale, public, upstream_canonical}`` where ``public``
    reflects whatever URL the operator has pinned via
    ``PUT /remote-access/public-url``. ``reachable`` is reported as
    ``None`` here so the tab can call ``POST /remote-access/probe`` for
    a live check — doing it inline would make this endpoint's latency
    unpredictable.
    """
    state = _read_remote_state()
    pinned = state.get("public_url")
    if not isinstance(pinned, str) or not pinned.strip():
        pinned = None

    secure_link: dict[str, Any] = {
        "enabled": False,
        "reason": "Hermes Secure Link is not enabled on the Relay host",
    }
    try:
        relay_health = await _proxy_get("/health")
        candidate = (
            relay_health.get("secure_proxy")
            if isinstance(relay_health, dict)
            else None
        )
        if isinstance(candidate, dict):
            proxy = candidate.get("proxy")
            relay_secure_link = relay_health.get("secure_link", {})
            reach = relay_secure_link.get("reach", {}) if isinstance(relay_secure_link, dict) else {}
            secure_link = {
                "enabled": True,
                "role": candidate.get("role"),
                "recommended": candidate.get("recommended") is True,
                "security": candidate.get("security"),
                "url": proxy.get("url") if isinstance(proxy, dict) else None,
                "surfaces": proxy.get("surfaces", []) if isinstance(proxy, dict) else [],
                "reach": {
                    "enabled": reach.get("enabled") is True,
                    "state": reach.get("state") if isinstance(reach.get("state"), str) else "disabled",
                    "last_error": reach.get("last_error") if isinstance(reach.get("last_error"), str) else None,
                } if isinstance(reach, dict) else {"enabled": False, "state": "disabled"},
            }
    except HTTPException as exc:
        secure_link = {
            "enabled": False,
            "reason": f"Relay status unavailable: {exc.detail}",
        }

    return {
        "tailscale": _tailscale_status_dict(),
        "secure_link": secure_link,
        "public": {
            "url": pinned,
            "reachable": None,
            "legacy_direct_relay": state.get("legacy_direct_relay") is True,
        },
        "upstream_canonical": _canonical_upstream_present(),
    }


@router.post("/remote-access/tailscale/enable")
async def tailscale_enable(
    body: dict[str, Any] = Body(default_factory=dict),
) -> dict[str, Any]:
    """Call ``tailscale.enable(port)`` and return its verbatim result."""
    try:
        tailscale = _plugin_module("relay.tailscale")
    except ImportError as exc:
        raise HTTPException(
            status_code=500,
            detail=f"tailscale helper unavailable: {exc}",
        ) from exc

    if body.get("stack") is True:
        return tailscale.enable_stack()
    port = _managed_tailscale_port(body)
    return tailscale.enable(port=port)


@router.post("/remote-access/tailscale/disable")
async def tailscale_disable(
    body: dict[str, Any] = Body(default_factory=dict),
) -> dict[str, Any]:
    """Call ``tailscale.disable(port)`` and return its verbatim result."""
    try:
        tailscale = _plugin_module("relay.tailscale")
    except ImportError as exc:
        raise HTTPException(
            status_code=500,
            detail=f"tailscale helper unavailable: {exc}",
        ) from exc

    if body.get("stack") is True:
        return tailscale.disable_stack()
    port = _managed_tailscale_port(body)
    return tailscale.disable(port=port)


@router.get("/remote-access/public-url")
async def get_public_url() -> dict[str, Any]:
    """Return the currently pinned public URL (or ``null`` when unset)."""
    state = _read_remote_state()
    pinned = state.get("public_url")
    if not isinstance(pinned, str) or not pinned.strip():
        return {"url": None, "legacy_direct_relay": False}
    return {
        "url": pinned,
        "legacy_direct_relay": state.get("legacy_direct_relay") is True,
    }


@router.put("/remote-access/public-url")
async def put_public_url(
    body: dict[str, Any] = Body(default_factory=dict),
) -> dict[str, Any]:
    """Pin / clear the public URL used by the next pairing QR.

    Empty string or ``null`` clears the pin. Validation limits the scheme
    to ``http`` / ``https`` — anything fancier (custom schemes, paths
    that would break ``urlparse``) is rejected with 400 rather than
    silently persisted.
    """
    raw = body.get("url")
    legacy_direct_relay = body.get("legacy_direct_relay", False)
    if not isinstance(legacy_direct_relay, bool):
        raise HTTPException(
            status_code=400, detail="legacy_direct_relay must be a boolean"
        )
    if raw is None:
        normalized = ""
    elif isinstance(raw, str):
        try:
            normalized = _validate_public_url(raw)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
    else:
        raise HTTPException(
            status_code=400, detail=f"'url' must be a string or null (got {type(raw).__name__})"
        )
    if (
        normalized
        and _plugin_module("pair").is_explicit_relay_url(normalized)
        and not legacy_direct_relay
    ):
        raise HTTPException(
            status_code=400,
            detail="an explicit Relay public path requires legacy_direct_relay=true",
        )

    state = _read_remote_state()
    now = int(time.time())
    if normalized:
        state["public_url"] = normalized
        state["legacy_direct_relay"] = legacy_direct_relay
        state["updated_at"] = now
    else:
        state.pop("public_url", None)
        state.pop("legacy_direct_relay", None)
        state["cleared_at"] = now

    try:
        _write_remote_state(state)
    except OSError as exc:
        raise HTTPException(
            status_code=500,
            detail=f"could not persist {_remote_state_path()}: {exc}",
        ) from exc

    return {
        "url": normalized or None,
        "legacy_direct_relay": legacy_direct_relay if normalized else False,
        "updated_at": state.get("updated_at"),
    }


@router.post("/remote-access/probe")
async def probe_endpoints(
    body: dict[str, Any] = Body(default_factory=dict),
) -> dict[str, Any]:
    """Probe explicit Dashboard, Relay, or API health surfaces.

    Body shape::

        { "candidates": [
            {"role": "public", "priority": 1,
             "surface": "dashboard", "url": "https://example.com"},
            {"role": "public", "priority": 1,
             "surface": "relay",
             "url": "wss://example.com/api/plugins/hermes-relay/transport"}
        ] }

    String entries remain supported as legacy direct-Relay URLs.

    Returns::

        { "results": [{ "role": str|null, "priority": int|null,
                        "surface": str, "url": "...", "probe_url": "...",
                        "reachable": bool, "status": int|null,
                        "latency_ms": int|null, "error": str|null }] }

    2s per-probe timeout; errors are captured per-entry so one flaky
    endpoint doesn't poison the whole response. This runs from the
    relay host's network perspective — useful for confirming the
    public URL is actually externally reachable without bouncing
    through a phone.
    """
    raw = body.get("candidates")
    if raw is None:
        raw = []
    if not isinstance(raw, list):
        raise HTTPException(
            status_code=400, detail="'candidates' must be an array"
        )

    results: list[dict[str, Any]] = []
    async with httpx.AsyncClient(timeout=2.0) as client:
        for entry in raw:
            role: Any = None
            priority: Any = None
            surface = "relay"
            entry_url: Any = entry
            if isinstance(entry, dict):
                role = entry.get("role")
                priority = entry.get("priority")
                surface = str(entry.get("surface") or "").strip().lower()
                entry_url = entry.get("url")

            base_result = {
                "role": role,
                "priority": priority,
                "surface": surface,
                "url": entry_url,
            }
            if surface not in {"dashboard", "relay", "api"}:
                results.append(
                    {
                        **base_result,
                        "probe_url": None,
                        "reachable": False,
                        "status": None,
                        "latency_ms": None,
                        "error": "surface must be dashboard, relay, or api",
                    }
                )
                continue
            if not isinstance(entry_url, str) or not entry_url.strip():
                results.append({
                    **base_result,
                    "probe_url": None,
                    "reachable": False,
                    "status": None,
                    "latency_ms": None,
                    "error": "empty url",
                })
                continue

            parsed = urlparse(entry_url.strip())
            allowed_schemes = (
                {"http", "https", "ws", "wss"}
                if surface == "relay"
                else {"http", "https"}
            )
            if (
                parsed.scheme.lower() not in allowed_schemes
                or not parsed.netloc
                or parsed.username is not None
                or parsed.password is not None
                or parsed.query
                or parsed.fragment
            ):
                results.append({
                    **base_result,
                    "probe_url": None,
                    "reachable": False,
                    "status": None,
                    "latency_ms": None,
                    "error": f"invalid {surface} url",
                })
                continue

            normalized = entry_url.strip().rstrip("/")
            if surface == "dashboard":
                url = normalized + "/api/health"
            elif surface == "relay":
                http_scheme = {
                    "wss": "https",
                    "ws": "http",
                }.get(parsed.scheme.lower(), parsed.scheme.lower())
                http_base = parsed._replace(scheme=http_scheme).geturl().rstrip("/")
                url = http_base + "/health"
            else:
                url = normalized + "/health"
            t0 = time.perf_counter()
            try:
                resp = await client.get(url)
            except httpx.HTTPError as exc:
                results.append(
                    {
                        **base_result,
                        "probe_url": url,
                        "reachable": False,
                        "status": None,
                        "latency_ms": None,
                        "error": str(exc),
                    }
                )
                continue
            latency_ms = int((time.perf_counter() - t0) * 1000)
            results.append(
                {
                    **base_result,
                    "probe_url": url,
                    "reachable": 200 <= resp.status_code < 300,
                    "status": resp.status_code,
                    "latency_ms": latency_ms,
                    "error": None,
                }
            )
    return {"results": results}


@router.delete("/sessions/{token_prefix}")
async def revoke_session(
    token_prefix: str = Path(..., min_length=1, max_length=64),
) -> Any:
    """Revoke a paired device by token prefix (loopback branch on relay)."""
    return await _proxy("DELETE", f"/sessions/{token_prefix}")


__all__ = ["router", "RELAY_PORT"]
