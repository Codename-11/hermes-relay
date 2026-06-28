"""Tests for the proactive channel handler (agent → phone push).

Validated surface:
  * ``proactive.subscribe`` latches the phone WS and acks with
    ``proactive.subscribed``.
  * :meth:`ProactiveChannel.push` sends a ``phone.message`` envelope over the
    latched WS and returns ``{delivered, message_id}``.
  * push with no subscriber raises :class:`ProactiveError`.
  * push over a closed/failing socket raises :class:`ProactiveError`.
  * ``proactive.unsubscribe`` / ``detach_ws`` release the subscriber.
  * a phone must not originate ``phone.message`` (ignored).

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

    def test_push_without_subscriber_raises(self) -> None:
        async def run() -> None:
            ch = ProactiveChannel()
            with self.assertRaises(ProactiveError) as ctx:
                await ch.push({"text": "hi"})
            self.assertIn("subscrib", str(ctx.exception).lower())

        _run(run())

    def test_push_over_closed_ws_raises(self) -> None:
        async def run() -> None:
            ch = ProactiveChannel()
            ws = _FakeWs()
            await ch.handle(ws, {"type": "proactive.subscribe"})
            ws.closed = True
            with self.assertRaises(ProactiveError):
                await ch.push({"text": "hi"})

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


if __name__ == "__main__":
    unittest.main()
