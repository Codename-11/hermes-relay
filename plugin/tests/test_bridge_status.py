"""Tests for the Phase 3 ``GET /bridge/status`` loopback endpoint and
the ``BridgeHandler.latest_status`` / ``last_seen_at`` cache it reads.

Validated surface:
  * Empty cache (no phone has ever pushed) → 503 +
    ``{"phone_connected": false, "last_seen_seconds_ago": null, ...}``.
  * Populated cache after a ``bridge.status`` envelope → 200 with the
    full nested ``device`` / ``bridge`` / ``safety`` groups and a
    ``last_seen_seconds_ago`` integer.
  * Non-loopback peer → 403.
  * ``handle_status`` stamps both ``latest_status`` and ``last_seen_at``
    so a fresh envelope always bumps the freshness clock.

These tests run under plain ``unittest`` (no pytest, no ``responses``)
to skip the repo's ``conftest.py`` which imports ``responses`` —
matching the pattern used by ``test_bridge_channel.py`` and the other
relay tests per CLAUDE.md's server-side workflow note.

Run with::

    python -m unittest plugin.tests.test_bridge_status
"""

from __future__ import annotations

import asyncio
import json
import time
import unittest
from typing import Any
from unittest.mock import MagicMock

from plugin.relay.channels.bridge import BridgeHandler
from plugin.relay.server import handle_bridge_status


def _run(coro):
    """Drive a coroutine to completion on a fresh event loop."""
    return asyncio.new_event_loop().run_until_complete(coro)


class _FakeWs:
    """Minimal stand-in for ``aiohttp.web.WebSocketResponse`` — used
    to stamp ``BridgeHandler.phone_ws`` so ``is_phone_connected()``
    returns True for the populated-cache test.
    """

    def __init__(self) -> None:
        self.closed: bool = False

    async def send_str(self, payload: str) -> None:  # pragma: no cover
        pass


class _FakeRequest:
    """Minimal aiohttp request stand-in with just the two fields the
    ``handle_bridge_status`` handler reads: ``remote`` and ``app``."""

    def __init__(self, remote: str, server: Any) -> None:
        self.remote = remote
        self.app = {"server": server}


class _FakeServer:
    """Stub for :class:`plugin.relay.server.RelayServer` — the
    ``handle_bridge_status`` handler only dereferences
    ``request.app['server'].bridge``.
    """

    def __init__(self, bridge: BridgeHandler) -> None:
        self.bridge = bridge


# Representative Phase 3 status payload. Mirrors the Kotlin
# ``BridgeStatusReporter`` emitTick() output so the test wire format
# tracks the real thing.
SAMPLE_STATUS_PAYLOAD: dict[str, Any] = {
    "device": {
        "name": "SM-S921U",
        "battery_percent": 78,
        "screen_on": True,
        "current_app": "com.android.chrome",
    },
    "bridge": {
        "master_enabled": True,
        "accessibility_granted": True,
        "screen_capture_granted": True,
        "overlay_granted": True,
        "notification_listener_granted": True,
    },
    "safety": {
        "blocklist_count": 30,
        "destructive_verbs_count": 12,
        "auto_disable_minutes": 30,
        "auto_disable_at_ms": None,
    },
    # Legacy top-level fields — also pushed by the Kotlin reporter for
    # backwards compat. The /bridge/status endpoint forwards them.
    "screen_on": True,
    "battery": 78,
    "current_app": "com.android.chrome",
    "accessibility_enabled": True,
    "ts": 1_700_000_000_000,
}


class BridgeStatusCacheTests(unittest.TestCase):
    """Covers the cache stamping on ``BridgeHandler``."""

    def test_handle_status_stamps_latest_and_last_seen(self) -> None:
        async def run() -> None:
            h = BridgeHandler()
            self.assertIsNone(h.latest_status)
            self.assertIsNone(h.last_seen_at)

            envelope = {
                "channel": "bridge",
                "type": "bridge.status",
                "id": "evt1",
                "payload": SAMPLE_STATUS_PAYLOAD,
            }
            before = time.time()
            await h.handle(_FakeWs(), envelope)
            after = time.time()

            self.assertIsNotNone(h.latest_status)
            assert h.latest_status is not None  # narrow for type checker
            self.assertEqual(
                h.latest_status["device"]["battery_percent"], 78
            )
            self.assertEqual(
                h.latest_status["safety"]["blocklist_count"], 30
            )
            self.assertIsNotNone(h.last_seen_at)
            assert h.last_seen_at is not None
            self.assertGreaterEqual(h.last_seen_at, before)
            self.assertLessEqual(h.last_seen_at, after)

        _run(run())

    def test_handle_status_is_a_snapshot_not_a_reference(self) -> None:
        # If BridgeHandler kept the payload by-reference, mutating the
        # caller's dict after the handler returned would poison the
        # cache. Regression guard against that.
        async def run() -> None:
            h = BridgeHandler()
            payload = dict(SAMPLE_STATUS_PAYLOAD)
            envelope = {
                "channel": "bridge",
                "type": "bridge.status",
                "id": "evt2",
                "payload": payload,
            }
            await h.handle(_FakeWs(), envelope)

            payload["accessibility_enabled"] = False
            payload["battery"] = 1
            assert h.latest_status is not None
            self.assertTrue(h.latest_status["accessibility_enabled"])
            self.assertEqual(h.latest_status["battery"], 78)

        _run(run())


class BridgeStatusRouteTests(unittest.TestCase):
    """Covers the ``GET /bridge/status`` HTTP handler."""

    def _make_request(self, bridge: BridgeHandler, remote: str) -> _FakeRequest:
        return _FakeRequest(remote=remote, server=_FakeServer(bridge))

    def _read_json(self, response: Any) -> dict[str, Any]:
        # aiohttp's json_response stores the serialized body on .body.
        # ._body is present on aiohttp.web.Response for prepared responses.
        body = response.body
        if isinstance(body, (bytes, bytearray)):
            return json.loads(body.decode("utf-8"))
        return json.loads(str(body))

    def test_empty_cache_returns_503_phone_not_connected(self) -> None:
        async def run() -> None:
            h = BridgeHandler()
            req = self._make_request(h, remote="127.0.0.1")
            response = await handle_bridge_status(req)  # type: ignore[arg-type]
            self.assertEqual(response.status, 503)
            body = self._read_json(response)
            self.assertFalse(body["phone_connected"])
            self.assertIsNone(body["last_seen_seconds_ago"])
            self.assertEqual(body["error"], "no phone connected")

        _run(run())

    def test_populated_cache_returns_200_with_full_contract(self) -> None:
        async def run() -> None:
            h = BridgeHandler()
            # Attach a fake ws so is_phone_connected() reports True.
            h.phone_ws = _FakeWs()  # type: ignore[assignment]
            envelope = {
                "channel": "bridge",
                "type": "bridge.status",
                "id": "evt3",
                "payload": SAMPLE_STATUS_PAYLOAD,
            }
            await h.handle(h.phone_ws, envelope)  # type: ignore[arg-type]

            req = self._make_request(h, remote="127.0.0.1")
            response = await handle_bridge_status(req)  # type: ignore[arg-type]
            self.assertEqual(response.status, 200)
            body = self._read_json(response)

            self.assertTrue(body["phone_connected"])
            self.assertIn("last_seen_seconds_ago", body)
            self.assertIsInstance(body["last_seen_seconds_ago"], int)
            self.assertGreaterEqual(body["last_seen_seconds_ago"], 0)
            self.assertLessEqual(body["last_seen_seconds_ago"], 5)

            # Nested groups come through unchanged.
            self.assertEqual(body["device"]["name"], "SM-S921U")
            self.assertEqual(body["device"]["battery_percent"], 78)
            self.assertTrue(body["bridge"]["master_enabled"])
            self.assertTrue(body["bridge"]["accessibility_granted"])
            self.assertEqual(body["safety"]["blocklist_count"], 30)
            self.assertEqual(
                body["safety"]["destructive_verbs_count"], 12
            )
            self.assertIsNone(body["safety"]["auto_disable_at_ms"])

        _run(run())

    def test_populated_cache_but_phone_disconnected_is_still_200(self) -> None:
        # The contract says phone_connected comes from live ws state, not
        # from the cache. If the phone reported status and then
        # disconnected, we should still serve the last-known snapshot but
        # with phone_connected=false so the caller can decide how stale
        # it is. This is the "stale cache" code path the agent cares
        # about.
        async def run() -> None:
            h = BridgeHandler()
            # Populate cache via handle() with a closed ws.
            ws = _FakeWs()
            envelope = {
                "channel": "bridge",
                "type": "bridge.status",
                "id": "evt4",
                "payload": SAMPLE_STATUS_PAYLOAD,
            }
            await h.handle(ws, envelope)  # type: ignore[arg-type]
            # Simulate the phone going away.
            ws.closed = True
            h.phone_ws = None

            req = self._make_request(h, remote="127.0.0.1")
            response = await handle_bridge_status(req)  # type: ignore[arg-type]
            self.assertEqual(response.status, 200)
            body = self._read_json(response)
            self.assertFalse(body["phone_connected"])
            self.assertIsInstance(body["last_seen_seconds_ago"], int)
            # Nested groups still present from the cached snapshot.
            self.assertEqual(body["device"]["name"], "SM-S921U")

        _run(run())

    def test_non_loopback_is_forbidden(self) -> None:
        async def run() -> None:
            from aiohttp import web

            h = BridgeHandler()
            # Populate so we're sure the 403 doesn't come from an early
            # empty-cache return path.
            envelope = {
                "channel": "bridge",
                "type": "bridge.status",
                "id": "evt5",
                "payload": SAMPLE_STATUS_PAYLOAD,
            }
            await h.handle(_FakeWs(), envelope)  # type: ignore[arg-type]

            for remote in ("192.168.1.42", "10.0.0.1", "::ffff:192.168.1.1", ""):
                req = self._make_request(h, remote=remote)
                with self.assertRaises(web.HTTPForbidden, msg=remote):
                    await handle_bridge_status(req)  # type: ignore[arg-type]

        _run(run())

    def test_ipv6_loopback_is_allowed(self) -> None:
        async def run() -> None:
            h = BridgeHandler()
            envelope = {
                "channel": "bridge",
                "type": "bridge.status",
                "id": "evt6",
                "payload": SAMPLE_STATUS_PAYLOAD,
            }
            await h.handle(_FakeWs(), envelope)  # type: ignore[arg-type]

            req = self._make_request(h, remote="::1")
            response = await handle_bridge_status(req)  # type: ignore[arg-type]
            self.assertEqual(response.status, 200)

        _run(run())


if __name__ == "__main__":
    unittest.main()
