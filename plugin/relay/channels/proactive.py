"""Proactive channel handler — agent ↔ phone, two-way.

The mirror image of :mod:`plugin.relay.channels.bridge`. Where the bridge
carries server→phone *commands* that await a phone *response*, this channel
carries server→phone *messages* that the agent initiates. The outbound leg
(``phone.message``) is fire-and-forget; the inbound leg (``proactive.reply``,
Phase 2c) carries the user's answer back so the conversation continues.

Flow (outbound — agent → phone):
  1. Phone connects to the relay's ``/ws`` and authenticates normally.
  2. When the user has "Let Hermes message me" enabled, the app sends a
     ``proactive.subscribe`` envelope. :meth:`handle` latches that phone's
     WebSocket and acks with ``proactive.subscribed``.
  3. The agent calls ``send_message(target="phone", ...)`` → the plugin's
     phone platform adapter POSTs loopback to the relay's ``/phone/message``
     route → the HTTP handler calls :meth:`push`.
  4. :meth:`push` sends a ``phone.message`` envelope over the latched
     phone WebSocket. The app raises a notification / inbox entry. If no phone
     is subscribed, :meth:`push` instead *buffers* the message (bounded,
     drop-oldest, stale-pruned) and :meth:`_flush_outbound` delivers it on the
     next ``proactive.subscribe`` — so an answer to a backgrounded phone is
     queued, not lost. A queued message can be cancelled (``cancel_outbound``)
     or inspected (``peek_outbound``) before it flushes.

Flow (inbound — phone → agent, Phase 2c):
  5. The user replies (notification inline-reply or inbox reply box). The app
     sends a ``proactive.reply`` envelope over the same WS. :meth:`handle`
     buffers it via :meth:`enqueue_reply`.
  6. The phone platform adapter — running in the *separate gateway process* —
     long-polls the relay's loopback ``GET /phone/replies`` route, which calls
     :meth:`take_replies`. Each drained reply is turned into an inbound
     ``MessageEvent`` the agent processes on the ``phone`` platform; its answer
     rides the existing outbound ``send()`` back to the phone.

The buffer + poll mirror the existing outbound loopback hop (``POST
/phone/message``) reversed: the relay and the gateway adapter are different
processes, so a reply can't be handed over in-process — it is parked in a
bounded buffer the adapter drains.

Opt-in is enforced structurally: the relay can only push when it holds a
latched ``phone_ws``, and it only gets one when the app subscribes — which
the app does only when the user toggle is on. Combined with the server-side
``PHONE_ENABLED`` gate on the adapter, both sides must opt in. Replies are
only produced by the same authenticated phone over its already-paired WS.

Wire envelopes (frozen — do not rename fields):
  * ``proactive.subscribe``   — app → server: ``{}`` (latch this phone)
  * ``proactive.subscribed``  — server → app: ``{ts}`` (ack)
  * ``proactive.unsubscribe`` — app → server: ``{}`` (release)
  * ``phone.message``         — server → app: ``{message_id, chat_id, text,
      title, surfacing, reply_to, metadata, sent_at}``
  * ``proactive.reply``       — app → server: ``{text, chat_id, reply_to,
      message_id, ts}`` (the user's answer to a ``phone.message``;
      ``reply_to`` is the answered message's id, ``chat_id`` the conversation)
  * ``proactive.reply.ack``   — server → app: ``{client_msg_id, status, ts}``
      (per-reply ack: confirms the relay buffered the user's reply for the
      gateway poller, so the app can settle its optimistic "Sending…" bubble)
  * ``proactive.cancel``      — app → server: ``{message_id}`` (drop a queued
      outbound ``phone.message`` before it flushes; the WS-reachable twin of
      ``DELETE /phone/outbound``)

Concurrency model:
  * A single ``ProactiveChannel`` instance lives on :class:`RelayServer`.
  * One phone is expected at a time (single-client, like the bridge). If a
    second phone subscribes it wins ``self.phone_ws``; the previous one
    simply stops receiving pushes.
  * The reply buffer is a bounded deque drained by a single loopback poller
    (the gateway adapter). All access is on the relay's asyncio loop, so no
    locking is required.
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid
from collections import deque
from typing import Any

from aiohttp import web

logger = logging.getLogger(__name__)

# Bound the inbound reply buffer so a phone replying while the gateway poller
# is absent (gateway down / restarting) can't grow it without limit. A reply
# is only useful fresh, so overflow drops the OLDEST (deque maxlen semantics).
MAX_BUFFERED_REPLIES = 100

# Bound the OUTBOUND buffer — agent→phone messages queued while no phone is
# subscribed (backgrounded / offline). Overflow drops the OLDEST (deque maxlen).
# A queued message older than the TTL is dropped on flush rather than delivered
# stale (a day-old "build is green" is noise, not signal).
MAX_BUFFERED_OUTBOUND = 50
OUTBOUND_TTL_SECONDS = 24 * 3600


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

        # Inbound reply buffer (Phase 2c). Replies arrive over the phone WS
        # (``proactive.reply``) and are drained by the gateway adapter's
        # loopback long-poll (``GET /phone/replies``). Bounded — drops oldest
        # on overflow. The Event lets the long-poll wait without busy-looping.
        self._replies: deque[dict[str, Any]] = deque(maxlen=MAX_BUFFERED_REPLIES)
        self._reply_event: asyncio.Event = asyncio.Event()
        self.reply_count: int = 0

        # Outbound buffer (agent → phone): messages pushed while no phone is
        # subscribed are parked here and flushed FIFO on the next
        # ``proactive.subscribe``, so the agent's answer survives a
        # backgrounded/offline phone instead of being dropped with a 503.
        # Bounded + drop-oldest; stale entries pruned on flush.
        self._outbound: deque[dict[str, Any]] = deque(maxlen=MAX_BUFFERED_OUTBOUND)
        self.queued_count: int = 0

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
        elif msg_type == "proactive.reply":
            reply = self._handle_reply(envelope.get("payload") or {})
            if reply is not None:
                # Ack the specific reply back to the phone so the app can settle
                # its optimistic "Sending…" bubble. Best-effort: the reply is
                # already buffered for the gateway poller regardless of the ack.
                await self._send_reply_ack(ws, reply["message_id"])
        elif msg_type == "proactive.cancel":
            # Drop a not-yet-delivered outbound message the phone no longer
            # wants (the WS twin of DELETE /phone/outbound). No-op once flushed.
            payload = envelope.get("payload") or {}
            cancelled = self.cancel_outbound(payload.get("message_id"))
            logger.info(
                "proactive: cancel (message_id=%s) cancelled=%d",
                payload.get("message_id"),
                cancelled,
            )
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
        # Deliver anything that queued while no phone was subscribed.
        await self._flush_outbound(ws)

    async def _handle_unsubscribe(self, ws: web.WebSocketResponse) -> None:
        """Release ``ws`` if it is the current subscriber."""
        if self.phone_ws is ws:
            self.phone_ws = None
            self.subscribed_at = None
            logger.info("proactive: phone unsubscribed")

    async def _send_reply_ack(
        self,
        ws: web.WebSocketResponse,
        client_msg_id: str,
        status: str = "received",
    ) -> None:
        """Ack a received ``proactive.reply`` back to the phone (best-effort).

        ``status="received"`` means the relay buffered the reply for the gateway
        poller — not that the agent has processed it. The app uses the
        ``client_msg_id`` (the id the phone minted on the reply) to move the
        matching optimistic bubble from "Sending…" to a settled state.
        """
        try:
            await ws.send_str(
                _envelope(
                    "proactive.reply.ack",
                    {
                        "client_msg_id": client_msg_id,
                        "status": status,
                        "ts": int(time.time() * 1000),
                    },
                )
            )
        except Exception as exc:  # pragma: no cover - best-effort ack
            logger.debug("proactive: failed to ack reply %s: %s", client_msg_id, exc)

    # ── Outbound push (called from the HTTP handler) ─────────────────────

    def _build_out_payload(self, payload: dict[str, Any], message_id: str) -> dict[str, Any]:
        """Build the ``phone.message`` payload that is sent / buffered."""
        return {
            "message_id": message_id,
            "chat_id": payload.get("chat_id"),
            "text": payload.get("text", ""),
            "title": payload.get("title"),
            "surfacing": payload.get("surfacing"),
            "reply_to": payload.get("reply_to"),
            "metadata": payload.get("metadata") or {},
            "sent_at": int(time.time() * 1000),
        }

    async def push(self, payload: dict[str, Any]) -> dict[str, Any]:
        """Send a ``phone.message`` to the subscribed phone, or queue it.

        ``payload`` is the body POSTed by the phone platform adapter
        (``chat_id``, ``text``, ``title``, ``surfacing``, ``reply_to``,
        ``metadata``).

        Returns ``{delivered: True, message_id}`` on a live send. When **no
        phone is subscribed**, the message is parked in a bounded buffer and
        ``{delivered: False, queued: True, message_id, buffered}`` is returned
        — it flushes on the next ``proactive.subscribe`` so the agent's answer
        survives a backgrounded/offline phone (it used to be dropped with 503).
        A queued message can be cancelled before it flushes via
        :meth:`cancel_outbound`.

        Raises :class:`ProactiveError` only when a *live* socket write fails
        (→ HTTP 502); a missing subscriber is no longer an error.
        """
        message_id = str(payload.get("message_id") or uuid.uuid4().hex[:12])
        out_payload = self._build_out_payload(payload, message_id)

        ws = self.phone_ws
        if ws is None or ws.closed:
            self._outbound.append(out_payload)
            self.queued_count += 1
            logger.info(
                "proactive >>> queued (no subscriber) message_id=%s chat=%s (buffered=%d)",
                message_id,
                out_payload["chat_id"],
                len(self._outbound),
            )
            return {
                "delivered": False,
                "queued": True,
                "message_id": message_id,
                "buffered": len(self._outbound),
            }

        try:
            await ws.send_str(_envelope("phone.message", out_payload, message_id))
        except Exception as exc:
            logger.error("proactive: failed to push message: %s", exc)
            raise ProactiveError(f"Failed to send message to phone: {exc}") from exc

        self.last_push_at = out_payload["sent_at"] / 1000.0
        self.push_count += 1
        logger.info(
            "proactive >>> message_id=%s chat=%s len=%d",
            message_id,
            out_payload["chat_id"],
            len(out_payload["text"]),
        )
        return {"delivered": True, "message_id": message_id}

    async def _flush_outbound(self, ws: web.WebSocketResponse) -> None:
        """Deliver queued outbound messages to a freshly-subscribed phone.

        Drains the outbound buffer FIFO. Entries older than
        :data:`OUTBOUND_TTL_SECONDS` are dropped rather than delivered stale.
        If the socket dies mid-flush, the remaining (incl. the one that failed)
        are re-buffered for the next subscribe instead of lost.
        """
        if not self._outbound:
            return
        pending = list(self._outbound)
        self._outbound.clear()
        now_ms = int(time.time() * 1000)
        ttl_ms = OUTBOUND_TTL_SECONDS * 1000
        flushed = 0
        for i, out_payload in enumerate(pending):
            sent_at = out_payload.get("sent_at") or 0
            if ttl_ms and sent_at and (now_ms - sent_at) > ttl_ms:
                continue  # stale — drop rather than deliver late
            try:
                await ws.send_str(
                    _envelope("phone.message", out_payload, out_payload.get("message_id"))
                )
                flushed += 1
            except Exception as exc:
                logger.warning(
                    "proactive: outbound flush interrupted (%s) — re-buffering %d",
                    exc,
                    len(pending) - i,
                )
                for leftover in pending[i:]:
                    self._outbound.append(leftover)
                break
        if flushed:
            self.last_push_at = time.time()
            self.push_count += flushed
            logger.info(
                "proactive: flushed %d queued message(s) to phone on subscribe", flushed
            )

    def peek_outbound(self) -> list[dict[str, Any]]:
        """Return a UI-friendly summary of queued outbound messages (FIFO).

        Text is truncated so a status view / `relay` CLI can list what's
        waiting without dumping full bodies. Read-only — does not drain.
        """
        return [
            {
                "message_id": m.get("message_id"),
                "chat_id": m.get("chat_id"),
                "text": (m.get("text") or "")[:200],
                "sent_at": m.get("sent_at"),
            }
            for m in self._outbound
        ]

    def cancel_outbound(self, message_id: str | None = None) -> int:
        """Cancel queued outbound messages before they flush.

        ``message_id=None`` clears the whole queue; otherwise removes just that
        message. Returns the number cancelled. No effect on already-delivered
        messages (the buffer only holds the not-yet-delivered).
        """
        if message_id is None:
            n = len(self._outbound)
            self._outbound.clear()
            return n
        before = len(self._outbound)
        kept = [m for m in self._outbound if m.get("message_id") != message_id]
        self._outbound.clear()
        self._outbound.extend(kept)
        return before - len(self._outbound)

    # ── Inbound reply buffer (phone → agent) ─────────────────────────────

    def _handle_reply(self, payload: dict[str, Any]) -> dict[str, Any] | None:
        """Validate + buffer a ``proactive.reply`` from the phone.

        ``payload`` is the inner envelope payload (``text``, ``chat_id``,
        ``reply_to``, ``message_id``, ``ts``). An empty-text reply is dropped
        — the phone never sends one, but a malformed client shouldn't wake the
        gateway poller for nothing. The buffered shape is normalized so the
        adapter sees a stable record regardless of which app version sent it.

        Returns the normalized reply (including the resolved ``message_id``) so
        the caller can ack it, or ``None`` when the reply was dropped.
        """
        text = payload.get("text")
        if not isinstance(text, str) or not text.strip():
            logger.debug("proactive: dropping reply with empty text")
            return None
        reply = {
            "text": text,
            "chat_id": payload.get("chat_id"),
            "reply_to": payload.get("reply_to"),
            "message_id": str(payload.get("message_id") or uuid.uuid4().hex[:12]),
            "ts": payload.get("ts") or int(time.time() * 1000),
        }
        self.enqueue_reply(reply)
        return reply

    def enqueue_reply(self, reply: dict[str, Any]) -> None:
        """Park a normalized reply for the gateway poller and wake any waiter.

        Bounded: the deque drops the oldest reply on overflow (a stale reply
        is useless once the buffer is full and the gateway is clearly behind).
        """
        self._replies.append(reply)
        self.reply_count += 1
        self._reply_event.set()
        logger.info(
            "proactive <<< reply chat=%s reply_to=%s len=%d (buffered=%d)",
            reply.get("chat_id"),
            reply.get("reply_to"),
            len(reply.get("text") or ""),
            len(self._replies),
        )

    async def take_replies(self, timeout: float = 25.0) -> list[dict[str, Any]]:
        """Drain all buffered replies, waiting up to ``timeout`` for the first.

        Long-poll primitive for ``GET /phone/replies``. Returns immediately
        with everything buffered if any reply is already present; otherwise
        waits up to ``timeout`` seconds for one to arrive, returning ``[]`` on
        timeout so the poller can re-poll. Single-poller (the gateway adapter)
        on the relay's asyncio loop — no locking needed.
        """
        if not self._replies:
            self._reply_event.clear()
            try:
                await asyncio.wait_for(self._reply_event.wait(), timeout)
            except (asyncio.TimeoutError, TimeoutError):
                return []
        batch = list(self._replies)
        self._replies.clear()
        self._reply_event.clear()
        return batch

    def buffered_reply_count(self) -> int:
        """Number of replies currently waiting for the gateway poller."""
        return len(self._replies)

    def buffered_outbound_count(self) -> int:
        """Number of agent→phone messages queued for the next subscribe."""
        return len(self._outbound)

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
        """Server shutdown — drop the subscriber reference + buffers."""
        self.phone_ws = None
        self.subscribed_at = None
        self._replies.clear()
        self._reply_event.clear()
        self._outbound.clear()
