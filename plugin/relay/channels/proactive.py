"""Proactive channel handler — agent → phone push.

The mirror image of :mod:`plugin.relay.channels.bridge`. Where the bridge
carries server→phone *commands* that await a phone *response*, this channel
carries server→phone *messages* that the agent initiates — there is no
awaited reply (notification semantics: best-effort, fire-and-forget).

Flow:
  1. Phone connects to the relay's ``/ws`` and authenticates normally.
  2. When the user has "Let Hermes message me" enabled, the app sends a
     ``proactive.subscribe`` envelope. :meth:`handle` latches that phone's
     WebSocket and acks with ``proactive.subscribed``.
  3. The agent calls ``send_message(target="phone", ...)`` → the plugin's
     phone platform adapter POSTs loopback to the relay's ``/phone/message``
     route → the HTTP handler calls :meth:`push`.
  4. :meth:`push` sends a ``phone.message`` envelope over the latched
     phone WebSocket. The app raises a notification / inbox entry.

Opt-in is enforced structurally: the relay can only push when it holds a
latched ``phone_ws``, and it only gets one when the app subscribes — which
the app does only when the user toggle is on. Combined with the server-side
``PHONE_ENABLED`` gate on the adapter, both sides must opt in.

Wire envelopes (frozen — do not rename fields):
  * ``proactive.subscribe``   — app → server: ``{}`` (latch this phone)
  * ``proactive.subscribed``  — server → app: ``{ts}`` (ack)
  * ``proactive.unsubscribe`` — app → server: ``{}`` (release)
  * ``phone.message``         — server → app: ``{message_id, chat_id, text,
      title, surfacing, reply_to, metadata, sent_at}``

Concurrency model:
  * A single ``ProactiveChannel`` instance lives on :class:`RelayServer`.
  * One phone is expected at a time (single-client, like the bridge). If a
    second phone subscribes it wins ``self.phone_ws``; the previous one
    simply stops receiving pushes.
"""

from __future__ import annotations

import json
import logging
import time
import uuid
from typing import Any

from aiohttp import web

logger = logging.getLogger(__name__)


class ProactiveError(Exception):
    """Raised when a proactive message cannot be delivered to a phone."""


def _envelope(msg_type: str, payload: dict[str, Any], msg_id: str | None = None) -> str:
    return json.dumps(
        {
            "channel": "proactive",
            "type": msg_type,
            "id": msg_id or str(uuid.uuid4()),
            "payload": payload,
        }
    )


class ProactiveChannel:
    """Routes agent-initiated messages to the subscribed phone.

    All state is held on the instance — no module-level globals. Lifetime
    matches :class:`plugin.relay.server.RelayServer`: one handler per relay
    process, reused across phone reconnects.
    """

    def __init__(self) -> None:
        # The currently-subscribed phone's WebSocket, or None. Latched on
        # ``proactive.subscribe`` and cleared on unsubscribe / disconnect.
        self.phone_ws: web.WebSocketResponse | None = None

        # Lightweight diagnostics (served by future dashboard/health if needed).
        self.subscribed_at: float | None = None
        self.last_push_at: float | None = None
        self.push_count: int = 0

    # ── Envelope dispatch (inbound from phone) ───────────────────────────

    async def handle(
        self,
        ws: web.WebSocketResponse,
        envelope: dict[str, Any],
    ) -> None:
        """Route an incoming proactive-channel envelope from the phone."""
        msg_type = envelope.get("type", "")

        if msg_type == "proactive.subscribe":
            await self._handle_subscribe(ws)
        elif msg_type == "proactive.unsubscribe":
            await self._handle_unsubscribe(ws)
        elif msg_type == "phone.message":
            # Server→app only — a phone must never originate this.
            logger.warning("proactive: ignoring unexpected phone.message from phone")
        else:
            logger.debug("proactive: ignoring unknown type %r", msg_type)

    async def _handle_subscribe(self, ws: web.WebSocketResponse) -> None:
        """Latch ``ws`` as the proactive push target and ack."""
        if self.phone_ws is not ws:
            if self.phone_ws is not None and not self.phone_ws.closed:
                logger.info("proactive: replacing previous subscriber — new client took over")
            self.phone_ws = ws
        self.subscribed_at = time.time()
        logger.info("proactive: phone subscribed for agent-initiated messages")
        try:
            await ws.send_str(
                _envelope("proactive.subscribed", {"ts": int(self.subscribed_at * 1000)})
            )
        except Exception as exc:  # pragma: no cover - best-effort ack
            logger.debug("proactive: failed to ack subscribe: %s", exc)

    async def _handle_unsubscribe(self, ws: web.WebSocketResponse) -> None:
        """Release ``ws`` if it is the current subscriber."""
        if self.phone_ws is ws:
            self.phone_ws = None
            self.subscribed_at = None
            logger.info("proactive: phone unsubscribed")

    # ── Outbound push (called from the HTTP handler) ─────────────────────

    async def push(self, payload: dict[str, Any]) -> dict[str, Any]:
        """Send a ``phone.message`` envelope to the subscribed phone.

        ``payload`` is the body POSTed by the phone platform adapter
        (``chat_id``, ``text``, ``title``, ``surfacing``, ``reply_to``,
        ``metadata``). Returns ``{delivered, message_id}``.

        Raises :class:`ProactiveError` if no phone is subscribed (→ HTTP
        503) or the socket write fails (→ HTTP 502).
        """
        ws = self.phone_ws
        if ws is None or ws.closed:
            raise ProactiveError(
                "No phone subscribed. Open the Hermes app and enable "
                "'Let Hermes message me'."
            )

        message_id = str(payload.get("message_id") or uuid.uuid4().hex[:12])
        sent_at = time.time()
        out_payload = {
            "message_id": message_id,
            "chat_id": payload.get("chat_id"),
            "text": payload.get("text", ""),
            "title": payload.get("title"),
            "surfacing": payload.get("surfacing"),
            "reply_to": payload.get("reply_to"),
            "metadata": payload.get("metadata") or {},
            "sent_at": int(sent_at * 1000),
        }

        try:
            await ws.send_str(_envelope("phone.message", out_payload, message_id))
        except Exception as exc:
            logger.error("proactive: failed to push message: %s", exc)
            raise ProactiveError(f"Failed to send message to phone: {exc}") from exc

        self.last_push_at = sent_at
        self.push_count += 1
        logger.info(
            "proactive >>> message_id=%s chat=%s len=%d",
            message_id,
            out_payload["chat_id"],
            len(out_payload["text"]),
        )
        return {"delivered": True, "message_id": message_id}

    # ── Lifecycle ────────────────────────────────────────────────────────

    def is_phone_subscribed(self) -> bool:
        ws = self.phone_ws
        return ws is not None and not ws.closed

    async def detach_ws(self, ws: web.WebSocketResponse, reason: str = "") -> None:
        """Release ``ws`` if it is the current subscriber (disconnect path)."""
        if self.phone_ws is ws:
            self.phone_ws = None
            self.subscribed_at = None
            logger.info(
                "proactive: subscriber detached (%s)", reason or "disconnect"
            )

    async def close(self) -> None:
        """Server shutdown — drop the subscriber reference."""
        self.phone_ws = None
        self.subscribed_at = None
