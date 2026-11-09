"""End-to-end HTTP bridge compatibility for directional Android swipes."""

from __future__ import annotations

import json
import unittest
from unittest.mock import patch

from plugin.relay import server as relay


class _Session:
    def channel_is_expired(self, _: str) -> bool:
        return False


class _Bridge:
    def __init__(self, bounds: dict | None = None) -> None:
        self.calls: list[dict] = []
        self.bounds = bounds or {"left": 0, "top": 0, "right": 1080, "bottom": 2400}

    async def handle_command(self, **kwargs):
        self.calls.append(kwargs)
        if kwargs["path"] == "/screen":
            return {"status": 200, "result": {"rootBounds": self.bounds}}
        return {"status": 200, "result": {"ok": True}}


class _Server:
    def __init__(self, bridge: _Bridge):
        self.bridge = bridge


class _Request:
    method = "POST"
    query = {}

    def __init__(self, body: dict):
        self._body = body

    async def json(self):
        return self._body


class DirectionalSwipeTests(unittest.IsolatedAsyncioTestCase):
    async def dispatch(self, payload: dict, bounds: dict | None = None):
        bridge = _Bridge(bounds)
        with patch.object(
            relay, "_require_bearer_session",
            return_value=(_Server(bridge), _Session()),
        ):
            response = await relay._bridge_dispatch(_Request(payload), "/swipe")
        return response, bridge

    async def test_up_swipe_uses_live_screen_bounds(self) -> None:
        response, bridge = await self.dispatch({"direction": "up", "distance": "medium"})
        self.assertEqual(response.status, 200)
        self.assertEqual([c["path"] for c in bridge.calls], ["/screen", "/swipe"])
        self.assertEqual(
            bridge.calls[-1]["body"],
            {"start_x": 540, "start_y": 1704, "end_x": 540, "end_y": 696, "duration_ms": 400},
        )

    async def test_direction_and_length_are_preserved(self) -> None:
        response, bridge = await self.dispatch({"direction": "right", "distance": "short"})
        self.assertEqual(response.status, 200)
        self.assertEqual(bridge.calls[-1]["body"]["start_y"], 1200)
        self.assertLess(
            bridge.calls[-1]["body"]["start_x"],
            bridge.calls[-1]["body"]["end_x"],
        )

    async def test_explicit_coordinates_unchanged(self) -> None:
        payload = {"start_x": 7, "start_y": 60, "end_x": 80, "end_y": 100, "duration_ms": 450}
        response, bridge = await self.dispatch(payload)
        self.assertEqual(response.status, 200)
        self.assertEqual(len(bridge.calls), 1)
        self.assertEqual(bridge.calls[-1]["body"], payload)

    async def test_invalid_direction_rejected_without_phone_call(self) -> None:
        response, bridge = await self.dispatch({"direction": "diagonal"})
        self.assertEqual(response.status, 400)
        self.assertEqual(bridge.calls, [])

    async def test_invalid_screen_bounds_fail_closed(self) -> None:
        response, bridge = await self.dispatch(
            {"direction": "down"},
            {"left": 0, "top": 0, "right": 100, "bottom": 50},
        )
        self.assertEqual(response.status, 503)
        self.assertEqual([c["path"] for c in bridge.calls], ["/screen"])


if __name__ == "__main__":
    unittest.main()
