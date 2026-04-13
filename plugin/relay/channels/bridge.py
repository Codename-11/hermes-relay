"""Bridge channel handler — Phase 3.

Migrated from the legacy standalone relay (``plugin/tools/android_relay.py``,
port 8766) into the unified relay on port 8767. The wire protocol is
identical to the pre-migration ``android_relay.py`` — envelopes are just
wrapped in the multiplexed ``channel: "bridge"`` envelope instead of a
standalone WebSocket.

Flow:
  1. Phone connects to unified relay's ``/ws`` and authenticates normally.
  2. Agent's Python tool (``plugin/tools/android_tool.py``) POSTs to one of
     the 14 HTTP endpoints registered on the unified relay.
  3. HTTP handler delegates to :meth:`BridgeHandler.handle_command`, which
     sends a ``bridge.command`` envelope to the connected phone and awaits
     a ``bridge.response``.
  4. Phone's bridge channel sends ``bridge.response`` — :meth:`handle` routes
     it to :meth:`handle_response`, which resolves the pending future.
  5. HTTP handler returns the result to the agent tool.

Wire envelopes (frozen — do not rename fields):
  * ``bridge.command``   — server → app: ``{request_id, method, path, params?, body?}``
  * ``bridge.response``  — app → server: ``{request_id, status, result}``
  * ``bridge.status``    — app → server: ``{screen_on, battery, current_app, accessibility_enabled}``

Concurrency model:
  * A single ``BridgeHandler`` instance lives on :class:`RelayServer`.
  * Only one phone is expected to be connected at a time (the per-client
    grant check happens at auth time). If multiple phones authenticate,
    the most-recent one wins ``self.phone_ws`` — earlier commands in
    flight against the previous phone resolve with ``ConnectionError``
    when :meth:`detach_ws` is called.
  * ``self.pending`` is protected by an asyncio lock so add/remove races
    from concurrent commands and responses can't drop futures.
  * 30 s timeout matches the legacy ``android_relay._RESPONSE_TIMEOUT``.
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid
from typing import Any

from aiohttp import web

logger = logging.getLogger(__name__)


RESPONSE_TIMEOUT = 30.0  # seconds — matches legacy android_relay._RESPONSE_TIMEOUT


class BridgeError(Exception):
    """Raised when a bridge command cannot be dispatched or times out."""


def _envelope(msg_type: str, payload: dict[str, Any], msg_id: str | None = None) -> str:
    return json.dumps(
        {
            "channel": "bridge",
            "type": msg_type,
            "id": msg_id or str(uuid.uuid4()),
            "payload": payload,
        }
    )


class BridgeHandler:
    """Routes agent tool calls to the connected phone.

    All state is held on the handler instance — there is no module-level
    global. Lifetime matches :class:`plugin.relay.server.RelayServer`: one
    handler per relay process, reused across phone reconnects.
    """

    def __init__(self) -> None:
        # The currently-connected phone's WebSocket. Assigned when a phone
        # sends its first bridge envelope (bridge.status typically) and
        # cleared via :meth:`detach_ws` when the phone disconnects.
        self.phone_ws: web.WebSocketResponse | None = None

        # request_id → asyncio.Future. Populated by handle_command before
        # the command is sent; resolved by handle_response when the phone
        # replies; cancelled with ConnectionError when the phone drops.
        self.pending: dict[str, asyncio.Future[dict[str, Any]]] = {}
        self._lock = asyncio.Lock()

        # Last bridge.status envelope payload — exposed for Phase 3 health UI.
        self.phone_status: dict[str, Any] = {}

        # === PHASE3-status: structured status cache ===
        # Raw payload of the most recent ``bridge.status`` envelope the
        # phone sent. Served via ``GET /bridge/status`` on the relay so
        # the ``android_phone_status()`` tool can answer "is the phone
        # connected + which permissions are granted?" without blocking
        # on a round-trip through the WSS bridge channel.
        #
        # ``None`` means no phone has ever pushed a status to this relay
        # process — the endpoint returns 503 in that case so the agent
        # can distinguish "no phone" from "stale phone".
        self.latest_status: dict[str, Any] | None = None
        self.last_seen_at: float | None = None
        # === END PHASE3-status ===

    # ── Envelope dispatch ────────────────────────────────────────────────

    async def handle(
        self,
        ws: web.WebSocketResponse,
        envelope: dict[str, Any],
    ) -> None:
        """Route an incoming bridge-channel envelope from the phone."""
        msg_type = envelope.get("type", "")
        payload = envelope.get("payload") or {}
        if not isinstance(payload, dict):
            logger.warning("bridge: non-dict payload for type=%s", msg_type)
            return

        # Opportunistically latch the phone's WebSocket. The phone sends
        # a bridge.status shortly after auth; from then on we have a
        # target for outbound bridge.command envelopes.
        if self.phone_ws is not ws:
            if self.phone_ws is not None and not self.phone_ws.closed:
                logger.info("bridge: replacing previous phone ws — new client took over")
            self.phone_ws = ws

        if msg_type == "bridge.response":
            await self.handle_response(ws, envelope)
        elif msg_type == "bridge.status":
            await self.handle_status(ws, envelope)
        elif msg_type == "bridge.command":
            # The phone should never send bridge.command — it's server→app only.
            logger.warning("bridge: ignoring unexpected bridge.command from phone")
        else:
            logger.warning("bridge: unknown message type %r", msg_type)

    # ── Outbound commands (called from HTTP handlers) ────────────────────

    async def handle_command(
        self,
        method: str,
        path: str,
        params: dict[str, Any] | None = None,
        body: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        """Dispatch an ``android_*`` HTTP call to the phone over the bridge channel.

        Returns the parsed ``bridge.response`` payload
        ``{request_id, status, result}``. Raises :class:`BridgeError` if no
        phone is connected, the send fails, or the phone doesn't respond
        within :data:`RESPONSE_TIMEOUT` seconds.
        """
        ws = self.phone_ws
        if ws is None or ws.closed:
            raise BridgeError(
                "No phone connected. Open the Hermes app on your phone and connect."
            )

        request_id = str(uuid.uuid4())
        future: asyncio.Future[dict[str, Any]] = asyncio.get_event_loop().create_future()

        async with self._lock:
            self.pending[request_id] = future

        command_payload = {
            "request_id": request_id,
            "method": method,
            "path": path,
            "params": params or {},
            "body": body or {},
        }
        logger.info(
            "bridge >>> %s %s body=%s",
            method,
            path,
            json.dumps(body) if body else "{}",
        )

        try:
            await ws.send_str(_envelope("bridge.command", command_payload, request_id))
        except Exception as exc:
            async with self._lock:
                self.pending.pop(request_id, None)
            logger.error("bridge: failed to send command: %s", exc)
            raise BridgeError(f"Failed to send command to phone: {exc}") from exc

        try:
            return await asyncio.wait_for(future, timeout=RESPONSE_TIMEOUT)
        except asyncio.TimeoutError:
            async with self._lock:
                self.pending.pop(request_id, None)
            logger.warning(
                "bridge: phone did not respond within %.0fs for %s %s",
                RESPONSE_TIMEOUT,
                method,
                path,
            )
            raise BridgeError(
                f"Phone did not respond within {RESPONSE_TIMEOUT:.0f}s"
            ) from None

    # ── Inbound response routing ────────────────────────────────────────

    async def handle_response(
        self,
        ws: web.WebSocketResponse,
        envelope: dict[str, Any],
    ) -> None:
        """Resolve the pending future for an incoming ``bridge.response``."""
        payload = envelope.get("payload") or {}
        request_id = payload.get("request_id")
        if not isinstance(request_id, str) or not request_id:
            logger.warning("bridge: response missing request_id: %s", payload)
            return

        async with self._lock:
            future = self.pending.pop(request_id, None)

        if future is None:
            # Timed out or cancelled — drop silently.
            logger.debug("bridge: no pending future for request_id=%s", request_id)
            return

        if not future.done():
            future.set_result(payload)

    async def handle_status(
        self,
        ws: web.WebSocketResponse,
        envelope: dict[str, Any],
    ) -> None:
        """Cache the latest device status snapshot from the phone."""
        payload = envelope.get("payload") or {}
        if not isinstance(payload, dict):
            return
        self.phone_status = dict(payload)
        # === PHASE3-status: feed structured cache for /bridge/status ===
        # We snapshot the whole payload — the phone sends both the new
        # nested ``device``/``bridge``/``safety`` groups and the legacy
        # flat keys, and it's cheaper to cache everything than to pick.
        self.latest_status = dict(payload)
        self.last_seen_at = time.time()
        # === END PHASE3-status ===
        logger.debug("bridge: status update %s", self.phone_status)

    # ── Lifecycle ───────────────────────────────────────────────────────

    def is_phone_connected(self) -> bool:
        ws = self.phone_ws
        return ws is not None and not ws.closed

    async def detach_ws(self, ws: web.WebSocketResponse, reason: str = "") -> None:
        """Release ``ws`` if it's the currently-attached phone, failing pending
        commands. Called from the main WebSocket disconnect path.
        """
        if self.phone_ws is not ws:
            return
        self.phone_ws = None

        async with self._lock:
            pending = dict(self.pending)
            self.pending.clear()

        if pending:
            err = ConnectionError(f"Phone disconnected ({reason})" if reason else "Phone disconnected")
            for fut in pending.values():
                if not fut.done():
                    fut.set_exception(err)
            logger.info(
                "bridge: failed %d pending commands after phone disconnect (%s)",
                len(pending),
                reason or "unknown",
            )

    async def close(self) -> None:
        """Server shutdown — cancel all pending commands and drop the phone ref."""
        ws = self.phone_ws
        self.phone_ws = None

        async with self._lock:
            pending = dict(self.pending)
            self.pending.clear()

        for fut in pending.values():
            if not fut.done():
                fut.set_exception(ConnectionError("Relay server shutting down"))

        if pending:
            logger.info("bridge: cancelled %d pending commands on shutdown", len(pending))
        if ws is not None and not ws.closed:
            # Don't close here — server.close() owns the WebSocket lifecycle.
            pass
