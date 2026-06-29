"""Tests for the proactive channel handler (agent → phone push).

Validated surface:
  * ``proactive.subscribe`` latches the phone WS and acks with
    ``proactive.subscribed``.
  * :meth:`ProactiveChannel.push` sends a ``phone.message`` envelope over the
    latched WS and returns ``{delivered, message_id}``.
  * push with no subscriber buffers the message; it flushes FIFO on the next
    ``proactive.subscribe`` (bounded, drop-oldest, stale-pruned) and can be
    cancelled (``cancel_outbound``) before it flushes.
  * push over a closed/failing socket raises :class:`ProactiveError`.
  * ``proactive.unsubscribe`` / ``detach_ws`` release the subscriber.
  * a phone must not originate ``phone.message`` (ignored).
  * ``proactive.reply`` (Phase 2c) buffers the user's answer and
    :meth:`take_replies` drains it (long-poll); empty-text dropped; the
    buffer is bounded (drops oldest); timeout returns ``[]``.

Runs under plain ``unittest`` (no pytest, no ``responses``) to skip the
repo's ``conftest.py``. Run with::

    python -m unittest plugin.tests.test_proactive_channel
"""

from __future__ import annotations

import asyncio
import json
import unittest
from typing import Any

from plugin.relay.channels.proactive import ProactiveChannel, ProactiveError


class _FakeWs:
    """Minimal stand-in for ``aiohttp.web.WebSocketResponse``."""

    def __init__(self) -> None:
        self.sent: list[dict[str, Any]] = []
        self.closed: bool = False
        self.send_raises: Exception | None = None

    async def send_str(self, payload: str) -> None:
        if self.send_raises is not None:
            raise self.send_raises
        self.sent.append(json.loads(payload))


def _run(coro):
    return asyncio.new_event_loop().run_until_complete(coro)


class ProactiveChannelTests(unittest.TestCase):
    # ── Subscribe lifecycle ──────────────────────────────────────────────

    def test_subscribe_latches_and_acks(self) -> None:
        async def run() -> None:
            ch = ProactiveChannel()
            ws = _FakeWs()
            await ch.handle(ws, {"channel": "proactive", "type": "proactive.subscribe"})
            self.assertIs(ch.phone_ws, ws)
            self.assertTrue(ch.is_phone_subscribed())
            self.assertEqual(len(ws.sent), 1)
            self.assertEqual(ws.sent[0]["type"], "proactive.subscribed")

        _run(run())

    def test_unsubscribe_releases(self) -> None:
        async def run() -> None:
            ch = ProactiveChannel()
            ws = _FakeWs()
            await ch.handle(ws, {"type": "proactive.subscribe"})
            await ch.handle(ws, {"type": "proactive.unsubscribe"})
            self.assertIsNone(ch.phone_ws)
            self.assertFalse(ch.is_phone_subscribed())

        _run(run())

    def test_detach_ws_releases_matching_only(self) -> None:
        async def run() -> None:
            ch = ProactiveChannel()
            ws1, ws2 = _FakeWs(), _FakeWs()
            await ch.handle(ws1, {"type": "proactive.subscribe"})
            # A different ws detaching is a no-op.
            await ch.detach_ws(ws2)
            self.assertIs(ch.phone_ws, ws1)
            await ch.detach_ws(ws1)
            self.assertIsNone(ch.phone_ws)

        _run(run())

    def test_second_subscriber_takes_over(self) -> None:
        async def run() -> None:
            ch = ProactiveChannel()
            ws1, ws2 = _FakeWs(), _FakeWs()
            await ch.handle(ws1, {"type": "proactive.subscribe"})
            await ch.handle(ws2, {"type": "proactive.subscribe"})
            self.assertIs(ch.phone_ws, ws2)

        _run(run())

    # ── Push ─────────────────────────────────────────────────────────────

    def test_push_sends_envelope(self) -> None:
        async def run() -> None:
            ch = ProactiveChannel()
            ws = _FakeWs()
            await ch.handle(ws, {"type": "proactive.subscribe"})
            ws.sent.clear()  # drop the subscribe ack
            result = await ch.push(
                {
                    "chat_id": "phone",
                    "text": "build is green",
                    "title": "Hermes",
                    "surfacing": "inbox",
                    "metadata": {"k": "v"},
                }
            )
            self.assertTrue(result["delivered"])
            self.assertTrue(result["message_id"])
            self.assertEqual(len(ws.sent), 1)
            env = ws.sent[0]
            self.assertEqual(env["channel"], "proactive")
            self.assertEqual(env["type"], "phone.message")
            payload = env["payload"]
            self.assertEqual(payload["text"], "build is green")
            self.assertEqual(payload["chat_id"], "phone")
            self.assertEqual(payload["surfacing"], "inbox")
            self.assertEqual(payload["message_id"], result["message_id"])
            self.assertIn("sent_at", payload)
            self.assertEqual(ch.push_count, 1)

        _run(run())

    def test_push_preserves_supplied_message_id(self) -> None:
        async def run() -> None:
            ch = ProactiveChannel()
            ws = _FakeWs()
            await ch.handle(ws, {"type": "proactive.subscribe"})
            result = await ch.push({"text": "hi", "message_id": "fixed-id"})
            self.assertEqual(result["message_id"], "fixed-id")

        _run(run())

    def test_push_without_subscriber_queues(self) -> None:
        async def run() -> None:
            ch = ProactiveChannel()
            result = await ch.push({"text": "hi", "chat_id": "phone"})
            self.assertFalse(result["delivered"])
            self.assertTrue(result["queued"])
            self.assertEqual(result["buffered"], 1)
            self.assertTrue(result["message_id"])
            self.assertEqual(ch.buffered_outbound_count(), 1)

        _run(run())

    def test_push_over_closed_ws_queues(self) -> None:
        async def run() -> None:
            ch = ProactiveChannel()
            ws = _FakeWs()
            await ch.handle(ws, {"type": "proactive.subscribe"})
            ws.closed = True
            result = await ch.push({"text": "hi"})
            self.assertTrue(result["queued"])
            self.assertEqual(ch.buffered_outbound_count(), 1)

        _run(run())

    def test_push_send_failure_raises(self) -> None:
        async def run() -> None:
            ch = ProactiveChannel()
            ws = _FakeWs()
            await ch.handle(ws, {"type": "proactive.subscribe"})
            ws.send_raises = ConnectionError("socket gone")
            with self.assertRaises(ProactiveError) as ctx:
                await ch.push({"text": "hi"})
            self.assertIn("failed to send", str(ctx.exception).lower())

        _run(run())

    # ── Outbound buffering (queue when no phone, flush on subscribe) ──────

    def test_queued_outbound_flushes_on_subscribe(self) -> None:
        async def run() -> None:
            ch = ProactiveChannel()
            # Pushed with no phone subscribed → queued, FIFO.
            await ch.push({"text": "first", "chat_id": "phone"})
            await ch.push({"text": "second", "chat_id": "phone"})
            self.assertEqual(ch.buffered_outbound_count(), 2)
            # Phone subscribes → ack, then both queued messages delivered.
            ws = _FakeWs()
            await ch.handle(ws, {"type": "proactive.subscribe"})
            types = [m["type"] for m in ws.sent]
            self.assertEqual(
                types, ["proactive.subscribed", "phone.message", "phone.message"]
            )
            texts = [m["payload"]["text"] for m in ws.sent if m["type"] == "phone.message"]
            self.assertEqual(texts, ["first", "second"])
            self.assertEqual(ch.buffered_outbound_count(), 0)

        _run(run())

    def test_outbound_buffer_bounded_drops_oldest(self) -> None:
        async def run() -> None:
            from plugin.relay.channels import proactive as mod

            ch = ProactiveChannel()
            overflow = mod.MAX_BUFFERED_OUTBOUND + 5
            for i in range(overflow):
                await ch.push({"text": f"m{i}", "chat_id": "phone"})
            self.assertEqual(ch.buffered_outbound_count(), mod.MAX_BUFFERED_OUTBOUND)
            ws = _FakeWs()
            await ch.handle(ws, {"type": "proactive.subscribe"})
            texts = [m["payload"]["text"] for m in ws.sent if m["type"] == "phone.message"]
            self.assertEqual(texts[0], "m5")  # oldest 5 dropped
            self.assertEqual(texts[-1], f"m{overflow - 1}")

        _run(run())

    def test_stale_outbound_dropped_on_flush(self) -> None:
        async def run() -> None:
            from plugin.relay.channels import proactive as mod

            ch = ProactiveChannel()
            await ch.push({"text": "fresh", "chat_id": "phone"})
            stale = ch._build_out_payload({"text": "stale", "chat_id": "phone"}, "old")
            stale["sent_at"] -= (mod.OUTBOUND_TTL_SECONDS + 60) * 1000
            ch._outbound.appendleft(stale)  # oldest
            ws = _FakeWs()
            await ch.handle(ws, {"type": "proactive.subscribe"})
            texts = [m["payload"]["text"] for m in ws.sent if m["type"] == "phone.message"]
            self.assertEqual(texts, ["fresh"])  # stale dropped, fresh delivered

        _run(run())

    def test_cancel_outbound_one_and_all(self) -> None:
        async def run() -> None:
            ch = ProactiveChannel()
            r1 = await ch.push({"text": "a", "chat_id": "phone"})
            await ch.push({"text": "b", "chat_id": "phone"})
            self.assertEqual(ch.buffered_outbound_count(), 2)
            # peek summary is read-only.
            self.assertEqual([p["text"] for p in ch.peek_outbound()], ["a", "b"])
            self.assertEqual(ch.buffered_outbound_count(), 2)
            # cancel one by id, then cancel the rest.
            self.assertEqual(ch.cancel_outbound(r1["message_id"]), 1)
            self.assertEqual([p["text"] for p in ch.peek_outbound()], ["b"])
            self.assertEqual(ch.cancel_outbound(), 1)
            self.assertEqual(ch.buffered_outbound_count(), 0)

        _run(run())

    def test_close_clears_outbound_buffer(self) -> None:
        async def run() -> None:
            ch = ProactiveChannel()
            await ch.push({"text": "hi", "chat_id": "phone"})
            self.assertEqual(ch.buffered_outbound_count(), 1)
            await ch.close()
            self.assertEqual(ch.buffered_outbound_count(), 0)

        _run(run())

    # ── Hardening ────────────────────────────────────────────────────────

    def test_phone_message_from_phone_ignored(self) -> None:
        async def run() -> None:
            ch = ProactiveChannel()
            ws = _FakeWs()
            await ch.handle(ws, {"type": "phone.message", "payload": {"text": "spoof"}})
            # No latch, no crash.
            self.assertIsNone(ch.phone_ws)
            self.assertEqual(ws.sent, [])

        _run(run())

    def test_unknown_type_ignored(self) -> None:
        async def run() -> None:
            ch = ProactiveChannel()
            ws = _FakeWs()
            await ch.handle(ws, {"type": "proactive.bogus"})
            self.assertEqual(ws.sent, [])

        _run(run())

    # ── Inbound reply (Phase 2c) ──────────────────────────────────────────

    def test_reply_buffers_and_drains(self) -> None:
        async def run() -> None:
            ch = ProactiveChannel()
            ws = _FakeWs()
            await ch.handle(
                ws,
                {
                    "type": "proactive.reply",
                    "payload": {
                        "text": "on my way",
                        "chat_id": "phone",
                        "reply_to": "m-1",
                        "message_id": "r-1",
                        "ts": 1719600000000,
                    },
                },
            )
            self.assertEqual(ch.buffered_reply_count(), 1)
            # A reply never goes back out over the WS — it is buffered.
            self.assertEqual(ws.sent, [])
            replies = await ch.take_replies(timeout=0.1)
            self.assertEqual(len(replies), 1)
            r = replies[0]
            self.assertEqual(r["text"], "on my way")
            self.assertEqual(r["chat_id"], "phone")
            self.assertEqual(r["reply_to"], "m-1")
            self.assertEqual(r["message_id"], "r-1")
            self.assertEqual(r["ts"], 1719600000000)
            self.assertEqual(ch.reply_count, 1)
            # Drained — buffer is empty.
            self.assertEqual(ch.buffered_reply_count(), 0)

        _run(run())

    def test_reply_empty_text_dropped(self) -> None:
        async def run() -> None:
            ch = ProactiveChannel()
            ws = _FakeWs()
            await ch.handle(ws, {"type": "proactive.reply", "payload": {"text": "   "}})
            await ch.handle(ws, {"type": "proactive.reply", "payload": {}})
            self.assertEqual(ch.buffered_reply_count(), 0)

        _run(run())

    def test_reply_synthesizes_message_id_and_ts(self) -> None:
        async def run() -> None:
            ch = ProactiveChannel()
            ws = _FakeWs()
            await ch.handle(ws, {"type": "proactive.reply", "payload": {"text": "hi"}})
            replies = await ch.take_replies(timeout=0.1)
            self.assertEqual(len(replies), 1)
            self.assertTrue(replies[0]["message_id"])  # synthesized
            self.assertTrue(replies[0]["ts"])  # synthesized
            self.assertIsNone(replies[0]["chat_id"])
            self.assertIsNone(replies[0]["reply_to"])

        _run(run())

    def test_take_replies_drains_all_buffered(self) -> None:
        async def run() -> None:
            ch = ProactiveChannel()
            ws = _FakeWs()
            for i in range(3):
                await ch.handle(
                    ws, {"type": "proactive.reply", "payload": {"text": f"m{i}"}}
                )
            replies = await ch.take_replies(timeout=0.1)
            self.assertEqual([r["text"] for r in replies], ["m0", "m1", "m2"])

        _run(run())

    def test_take_replies_timeout_returns_empty(self) -> None:
        async def run() -> None:
            ch = ProactiveChannel()
            replies = await ch.take_replies(timeout=0.05)
            self.assertEqual(replies, [])

        _run(run())

    def test_take_replies_wakes_on_late_reply(self) -> None:
        async def run() -> None:
            ch = ProactiveChannel()
            ws = _FakeWs()

            async def deliver_soon() -> None:
                await asyncio.sleep(0.02)
                await ch.handle(ws, {"type": "proactive.reply", "payload": {"text": "late"}})

            task = asyncio.ensure_future(deliver_soon())
            replies = await ch.take_replies(timeout=1.0)
            await task
            self.assertEqual(len(replies), 1)
            self.assertEqual(replies[0]["text"], "late")

        _run(run())

    def test_reply_buffer_is_bounded_drops_oldest(self) -> None:
        async def run() -> None:
            from plugin.relay.channels import proactive as mod

            ch = ProactiveChannel()
            ws = _FakeWs()
            overflow = mod.MAX_BUFFERED_REPLIES + 5
            for i in range(overflow):
                await ch.handle(
                    ws, {"type": "proactive.reply", "payload": {"text": f"m{i}"}}
                )
            self.assertEqual(ch.buffered_reply_count(), mod.MAX_BUFFERED_REPLIES)
            replies = await ch.take_replies(timeout=0.1)
            # Oldest 5 dropped; newest kept, order preserved.
            self.assertEqual(replies[0]["text"], "m5")
            self.assertEqual(replies[-1]["text"], f"m{overflow - 1}")

        _run(run())

    def test_close_clears_reply_buffer(self) -> None:
        async def run() -> None:
            ch = ProactiveChannel()
            ws = _FakeWs()
            await ch.handle(ws, {"type": "proactive.reply", "payload": {"text": "hi"}})
            await ch.close()
            self.assertEqual(ch.buffered_reply_count(), 0)

        _run(run())


if __name__ == "__main__":
    unittest.main()
