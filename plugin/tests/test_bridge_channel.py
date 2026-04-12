"""Tests for the Phase 3 bridge channel handler.

Validated surface:
  * :meth:`BridgeHandler.handle_command` routes the envelope through a
    mock phone WebSocket and resolves with the phone's response payload.
  * A timed-out command raises :class:`BridgeError` and clears its
    pending future (no leaks).
  * A phone disconnect via :meth:`detach_ws` fails all pending commands
    with ``ConnectionError``.
  * Envelope dispatch routes ``bridge.response`` to the waiting future
    and caches ``bridge.status`` payloads.

These tests run under plain ``unittest`` (no pytest, no ``responses``)
to skip the repo's ``conftest.py`` which imports ``responses`` — matching
the pattern used by the sibling test files per CLAUDE.md's server-side
workflow note.

Run with::

    python -m unittest plugin.tests.test_bridge_channel
"""

from __future__ import annotations

import asyncio
import json
import unittest
from typing import Any

from plugin.relay.channels.bridge import BridgeError, BridgeHandler, RESPONSE_TIMEOUT


class _FakeWs:
    """Minimal stand-in for ``aiohttp.web.WebSocketResponse``.

    Records every ``send_str`` call so tests can assert what went out
    over the wire. ``closed`` is a plain attribute so tests can flip it
    to simulate a phone disconnect mid-command.
    """

    def __init__(self) -> None:
        self.sent: list[dict[str, Any]] = []
        self.closed: bool = False
        self.send_raises: Exception | None = None

    async def send_str(self, payload: str) -> None:
        if self.send_raises is not None:
            raise self.send_raises
        self.sent.append(json.loads(payload))


def _run(coro):
    """Drive a coroutine to completion on a fresh event loop."""
    return asyncio.new_event_loop().run_until_complete(coro)


class BridgeHandlerTests(unittest.TestCase):
    # ── Envelope dispatch ────────────────────────────────────────────────

    def test_status_payload_cached_on_inbound_status_envelope(self) -> None:
        async def run() -> None:
            h = BridgeHandler()
            ws = _FakeWs()
            envelope = {
                "channel": "bridge",
                "type": "bridge.status",
                "id": "evt1",
                "payload": {
                    "screen_on": True,
                    "battery": 72,
                    "current_app": "com.example",
                    "accessibility_enabled": True,
                },
            }
            await h.handle(ws, envelope)
            self.assertTrue(h.is_phone_connected())
            self.assertEqual(h.phone_status["battery"], 72)
            self.assertEqual(h.phone_status["current_app"], "com.example")

        _run(run())

    def test_response_resolves_waiting_future(self) -> None:
        async def run() -> None:
            h = BridgeHandler()
            ws = _FakeWs()
            # Seed phone_ws directly so handle_command has a target.
            h.phone_ws = ws

            command_task = asyncio.create_task(
                h.handle_command(method="GET", path="/ping")
            )
            # Yield so the coroutine registers its future + sends the envelope.
            await asyncio.sleep(0)
            await asyncio.sleep(0)

            self.assertEqual(len(ws.sent), 1)
            sent = ws.sent[0]
            self.assertEqual(sent["channel"], "bridge")
            self.assertEqual(sent["type"], "bridge.command")
            self.assertEqual(sent["payload"]["method"], "GET")
            self.assertEqual(sent["payload"]["path"], "/ping")
            request_id = sent["payload"]["request_id"]
            self.assertTrue(request_id)

            # Route the phone's response back.
            response_envelope = {
                "channel": "bridge",
                "type": "bridge.response",
                "id": request_id,
                "payload": {
                    "request_id": request_id,
                    "status": 200,
                    "result": {"pong": True},
                },
            }
            await h.handle(ws, response_envelope)

            resolved = await asyncio.wait_for(command_task, timeout=1.0)
            self.assertEqual(resolved["status"], 200)
            self.assertEqual(resolved["result"], {"pong": True})
            # Pending queue should be drained — no leak.
            self.assertEqual(h.pending, {})

        _run(run())

    # ── Error paths ──────────────────────────────────────────────────────

    def test_command_without_phone_raises_bridge_error(self) -> None:
        async def run() -> None:
            h = BridgeHandler()
            with self.assertRaises(BridgeError):
                await h.handle_command(method="GET", path="/ping")

        _run(run())

    def test_detach_fails_all_pending_commands(self) -> None:
        async def run() -> None:
            h = BridgeHandler()
            ws = _FakeWs()
            h.phone_ws = ws

            t1 = asyncio.create_task(h.handle_command(method="GET", path="/ping"))
            t2 = asyncio.create_task(h.handle_command(method="GET", path="/screen"))
            # Give both tasks a chance to register pending futures.
            await asyncio.sleep(0)
            await asyncio.sleep(0)

            self.assertEqual(len(ws.sent), 2)
            self.assertEqual(len(h.pending), 2)

            ws.closed = True
            await h.detach_ws(ws, reason="unit test disconnect")
            self.assertFalse(h.is_phone_connected())
            self.assertEqual(h.pending, {})

            # Both awaiters should now raise ConnectionError.
            with self.assertRaises(ConnectionError):
                await asyncio.wait_for(t1, timeout=1.0)
            with self.assertRaises(ConnectionError):
                await asyncio.wait_for(t2, timeout=1.0)

        _run(run())

    def test_timeout_clears_pending_and_raises_bridge_error(self) -> None:
        async def run() -> None:
            import plugin.relay.channels.bridge as bridge_mod

            h = BridgeHandler()
            ws = _FakeWs()
            h.phone_ws = ws

            # Shrink the timeout so the test finishes fast. Restoring in
            # the finally block keeps the module state clean for other
            # tests in the same process.
            original = bridge_mod.RESPONSE_TIMEOUT
            bridge_mod.RESPONSE_TIMEOUT = 0.05
            try:
                with self.assertRaises(BridgeError):
                    await h.handle_command(method="POST", path="/tap")
            finally:
                bridge_mod.RESPONSE_TIMEOUT = original

            # Timed-out entry must be removed from pending so it doesn't leak.
            self.assertEqual(h.pending, {})
            # Envelope was dispatched before the timeout.
            self.assertEqual(len(ws.sent), 1)
            self.assertEqual(ws.sent[0]["payload"]["path"], "/tap")

        _run(run())

    def test_send_failure_clears_pending_future(self) -> None:
        async def run() -> None:
            h = BridgeHandler()
            ws = _FakeWs()
            ws.send_raises = ConnectionResetError("broken pipe")
            h.phone_ws = ws

            with self.assertRaises(BridgeError):
                await h.handle_command(method="GET", path="/ping")

            # Failed-to-send future must be cleaned up so memory doesn't grow.
            self.assertEqual(h.pending, {})

        _run(run())

    # ── Module surface ───────────────────────────────────────────────────

    def test_response_timeout_default_matches_legacy(self) -> None:
        # Legacy standalone relay used 30 s. Regression guard.
        self.assertEqual(RESPONSE_TIMEOUT, 30.0)


if __name__ == "__main__":
    unittest.main()
