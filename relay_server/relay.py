"""Relay Server — Main WSS server.

Serves a single WebSocket endpoint at ``/ws`` and a health check at ``/health``.
Phone connects, authenticates with a pairing code or session token, then sends
typed envelope messages that get routed to the appropriate channel handler.

Usage:
    python -m relay_server
    python -m relay_server --port 8767 --no-ssl
    python -m relay_server --config /path/to/config.yaml
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import signal
import ssl
import sys
import time
import uuid
from typing import Any

import aiohttp
from aiohttp import web

from . import __version__
from .auth import PairingManager, RateLimiter, SessionManager
from .channels.bridge import BridgeHandler
from .channels.chat import ChatHandler
from .channels.terminal import TerminalHandler
from .config import RelayConfig

logger = logging.getLogger("relay_server")


# ── Server state ─────────────────────────────────────────────────────────────


class RelayServer:
    """Holds all mutable state for the running relay."""

    def __init__(self, config: RelayConfig) -> None:
        self.config = config

        # Auth
        self.pairing = PairingManager()
        self.sessions = SessionManager()
        self.rate_limiter = RateLimiter()

        # Channel handlers
        self.chat = ChatHandler(webapi_url=config.webapi_url)
        self.terminal = TerminalHandler()
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
        session_token = await _authenticate(ws, server, remote_ip)
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


async def _authenticate(
    ws: web.WebSocketResponse,
    server: RelayServer,
    remote_ip: str,
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

    # Try session token first (reconnection)
    if session_token_attempt:
        session = server.sessions.get_session(session_token_attempt)
        if session is not None:
            server.rate_limiter.record_success(remote_ip)
            await _send_system(
                ws,
                "auth.ok",
                {
                    "session_token": session.token,
                    "server_version": __version__,
                    "profiles": server.config.profiles,
                },
            )
            return session.token

    # Try pairing code
    if pairing_code:
        if server.pairing.consume_code(pairing_code):
            # Code is valid — create a session
            session = server.sessions.create_session(device_name, device_id)
            server.rate_limiter.record_success(remote_ip)
            await _send_system(
                ws,
                "auth.ok",
                {
                    "session_token": session.token,
                    "server_version": __version__,
                    "profiles": server.config.profiles,
                },
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

    # Routes
    app.router.add_get("/ws", handle_ws)
    app.router.add_get("/health", handle_health)
    app.router.add_post("/pairing", handle_pairing)

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
        description="Hermes Relay Server — WSS server for the Android app"
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
        "--version",
        action="version",
        version=f"companion-relay {__version__}",
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
