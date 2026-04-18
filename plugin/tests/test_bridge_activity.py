"""Tests for the bridge command ring buffer (dashboard Activity feed).

Unit R1 of the dashboard-plugin plan — verifies:
  * ``handle_command`` appends a ``pending`` record before sending.
  * ``handle_response`` mutates the matching record to ``executed`` on
    2xx and ``error`` on >=400.
  * Params with sensitive keys are redacted (recursively).
  * The deque evicts the oldest entry at ``maxlen + 1``.
  * ``get_recent(limit=N)`` returns ``N`` records newest-first.

Modelled on ``test_bridge_channel.py`` (plain unittest + asyncio, fake
WebSocket). Skips pytest to avoid ``conftest.py``'s ``responses`` import.

Run with::

    python -m unittest plugin.tests.test_bridge_activity
"""

from __future__ import annotations

import asyncio
import json
import unittest
from typing import Any

from plugin.relay.channels.bridge import (
    RECENT_COMMANDS_MAX,
    BridgeCommandRecord,
    BridgeHandler,
    _redact_params,
)


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


class BridgeActivityTests(unittest.TestCase):
    # ── Record lifecycle ────────────────────────────────────────────────

    def test_handle_command_appends_pending_record(self) -> None:
        async def run() -> None:
            h = BridgeHandler()
            ws = _FakeWs()
            h.phone_ws = ws

            task = asyncio.create_task(
                h.handle_command(method="GET", path="/ping", params={"x": 1})
            )
            # Yield so handle_command registers the record + sends.
            await asyncio.sleep(0)
            await asyncio.sleep(0)

            self.assertEqual(len(h.recent_commands), 1)
            record = h.recent_commands[0]
            self.assertEqual(record.decision, "pending")
            self.assertEqual(record.method, "GET")
            self.assertEqual(record.path, "/ping")
            self.assertEqual(record.params, {"x": 1})
            self.assertGreater(record.sent_at, 0)
            self.assertIsNone(record.response_status)
            self.assertIsNone(record.error)

            # Clean up the outstanding task.
            request_id = record.request_id
            await h.handle(
                ws,
                {
                    "channel": "bridge",
                    "type": "bridge.response",
                    "id": request_id,
                    "payload": {
                        "request_id": request_id,
                        "status": 200,
                        "result": {"ok": True},
                    },
                },
            )
            await asyncio.wait_for(task, timeout=1.0)

        _run(run())

    def test_handle_response_marks_executed_on_success(self) -> None:
        async def run() -> None:
            h = BridgeHandler()
            ws = _FakeWs()
            h.phone_ws = ws

            task = asyncio.create_task(
                h.handle_command(method="POST", path="/tap")
            )
            await asyncio.sleep(0)
            await asyncio.sleep(0)

            request_id = h.recent_commands[0].request_id
            await h.handle(
                ws,
                {
                    "channel": "bridge",
                    "type": "bridge.response",
                    "id": request_id,
                    "payload": {
                        "request_id": request_id,
                        "status": 200,
                        "result": {"tapped": True},
                    },
                },
            )
            await asyncio.wait_for(task, timeout=1.0)

            record = h.recent_commands[0]
            self.assertEqual(record.decision, "executed")
            self.assertEqual(record.response_status, 200)
            self.assertIn("tapped", record.result_summary or "")
            self.assertIsNone(record.error)

        _run(run())

    def test_handle_response_marks_error_on_4xx(self) -> None:
        async def run() -> None:
            h = BridgeHandler()
            ws = _FakeWs()
            h.phone_ws = ws

            task = asyncio.create_task(h.handle_command(method="GET", path="/boom"))
            await asyncio.sleep(0)
            await asyncio.sleep(0)

            request_id = h.recent_commands[0].request_id
            await h.handle(
                ws,
                {
                    "channel": "bridge",
                    "type": "bridge.response",
                    "id": request_id,
                    "payload": {
                        "request_id": request_id,
                        "status": 500,
                        "error": "something broke",
                        "result": None,
                    },
                },
            )
            await asyncio.wait_for(task, timeout=1.0)

            record = h.recent_commands[0]
            self.assertEqual(record.decision, "error")
            self.assertEqual(record.response_status, 500)
            self.assertEqual(record.error, "something broke")

        _run(run())

    def test_handle_response_marks_blocked_by_safety_rail(self) -> None:
        async def run() -> None:
            h = BridgeHandler()
            ws = _FakeWs()
            h.phone_ws = ws

            task = asyncio.create_task(h.handle_command(method="POST", path="/send_sms"))
            await asyncio.sleep(0)
            await asyncio.sleep(0)

            request_id = h.recent_commands[0].request_id
            await h.handle(
                ws,
                {
                    "channel": "bridge",
                    "type": "bridge.response",
                    "id": request_id,
                    "payload": {
                        "request_id": request_id,
                        "status": 403,
                        "result": {"blocked": True, "reason": "blocklist"},
                    },
                },
            )
            await asyncio.wait_for(task, timeout=1.0)

            self.assertEqual(h.recent_commands[0].decision, "blocked")

        _run(run())

    # ── Redaction ────────────────────────────────────────────────────────

    def test_redact_top_level_and_nested_sensitive_keys(self) -> None:
        redacted = _redact_params(
            {
                "bearer": "abc",
                "nested": {"password": "xyz", "safe": 1},
                "list": [{"otp": "999", "keep": "hi"}],
            }
        )
        self.assertEqual(redacted["bearer"], "[redacted]")
        self.assertEqual(redacted["nested"]["password"], "[redacted]")
        self.assertEqual(redacted["nested"]["safe"], 1)
        self.assertEqual(redacted["list"][0]["otp"], "[redacted]")
        self.assertEqual(redacted["list"][0]["keep"], "hi")

    def test_handle_command_stores_redacted_params(self) -> None:
        async def run() -> None:
            h = BridgeHandler()
            ws = _FakeWs()
            h.phone_ws = ws

            task = asyncio.create_task(
                h.handle_command(
                    method="POST",
                    path="/secure",
                    params={"Token": "ZZZ", "normal": 1},
                )
            )
            await asyncio.sleep(0)
            await asyncio.sleep(0)

            record = h.recent_commands[0]
            self.assertEqual(record.params["Token"], "[redacted]")
            self.assertEqual(record.params["normal"], 1)

            # The outbound envelope itself is unredacted — only the
            # audit record is scrubbed.
            sent_params = ws.sent[0]["payload"]["params"]
            self.assertEqual(sent_params["Token"], "ZZZ")

            request_id = record.request_id
            await h.handle(
                ws,
                {
                    "channel": "bridge",
                    "type": "bridge.response",
                    "id": request_id,
                    "payload": {"request_id": request_id, "status": 200, "result": {}},
                },
            )
            await asyncio.wait_for(task, timeout=1.0)

        _run(run())

    # ── Ring-buffer behaviour ───────────────────────────────────────────

    def test_ring_buffer_evicts_oldest_at_capacity_plus_one(self) -> None:
        h = BridgeHandler()
        # Seed past the cap — one over should evict the first.
        for i in range(RECENT_COMMANDS_MAX + 1):
            h.recent_commands.append(
                BridgeCommandRecord(
                    request_id=f"req-{i}",
                    method="GET",
                    path=f"/p{i}",
                    sent_at=float(i),
                )
            )
        self.assertEqual(len(h.recent_commands), RECENT_COMMANDS_MAX)
        ids = [r.request_id for r in h.recent_commands]
        self.assertNotIn("req-0", ids)
        self.assertEqual(h.recent_commands[0].request_id, "req-1")
        self.assertEqual(
            h.recent_commands[-1].request_id, f"req-{RECENT_COMMANDS_MAX}"
        )

    def test_get_recent_returns_newest_first(self) -> None:
        h = BridgeHandler()
        for i in range(10):
            h.recent_commands.append(
                BridgeCommandRecord(
                    request_id=f"req-{i}",
                    method="GET",
                    path=f"/p{i}",
                    sent_at=float(i),
                )
            )

        recent = h.get_recent(limit=5)
        self.assertEqual(len(recent), 5)
        self.assertEqual(
            [r["request_id"] for r in recent],
            ["req-9", "req-8", "req-7", "req-6", "req-5"],
        )

        # Each dict carries the full dataclass shape.
        first = recent[0]
        for key in (
            "request_id",
            "method",
            "path",
            "params",
            "sent_at",
            "response_status",
            "result_summary",
            "error",
            "decision",
        ):
            self.assertIn(key, first)

    def test_get_recent_limit_larger_than_buffer(self) -> None:
        h = BridgeHandler()
        for i in range(3):
            h.recent_commands.append(
                BridgeCommandRecord(
                    request_id=f"req-{i}",
                    method="GET",
                    path=f"/p{i}",
                    sent_at=float(i),
                )
            )
        recent = h.get_recent(limit=500)
        self.assertEqual(len(recent), 3)
        self.assertEqual(recent[0]["request_id"], "req-2")


if __name__ == "__main__":
    unittest.main()
