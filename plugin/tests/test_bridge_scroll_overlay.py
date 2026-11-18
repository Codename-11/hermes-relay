"""Relay avoids overlay-intercepted node scroll gestures without claiming movement."""

from __future__ import annotations

import json
import unittest
from unittest.mock import patch

from plugin.relay import server as relay


ROOT = {"left": 0, "top": 0, "right": 1080, "bottom": 2340}


def _node(top: int, bottom: int) -> dict:
    return {
        "nodeId": "w1:21",
        "text": "Search results",
        "bounds": {"left": 0, "top": top, "right": 1080, "bottom": bottom},
        "scrollable": True,
    }


class _Session:
    def channel_is_expired(self, _: str) -> bool:
        return False


class _Bridge:
    def __init__(self):
        self.node = _node(950, 2238)
        self.calls: list[dict] = []

    async def handle_command(self, **kwargs):
        self.calls.append(kwargs)
        if kwargs["path"] == "/screen":
            return {
                "status": 200,
                "result": {"nodes": [self.node.copy()], "rootBounds": ROOT},
            }
        if kwargs["path"] == "/scroll":
            is_screen_level = "nodeId" not in kwargs["body"]
            return {
                "status": 200,
                "result": {"ok": True, "start_y": 1755 if is_screen_level else 2179},
            }
        raise AssertionError(kwargs["path"])


class _Server:
    def __init__(self):
        self.bridge = _Bridge()


class _Request:
    method = "POST"
    query = {}

    def __init__(self, body: dict):
        self._body = body

    async def json(self):
        return self._body


class ScrollSafetyTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.server = _Server()
        self.mock_auth = patch.object(
            relay, "_require_bearer_session",
            return_value=(self.server, _Session()),
        )
        self.mock_auth.start()

    def tearDown(self) -> None:
        self.mock_auth.stop()

    async def scroll(self, body: dict):
        response = await relay._bridge_dispatch(_Request(body), "/scroll")
        return response.status, json.loads(response.text)

    async def test_bottom_overlay_falls_back_within_target(self):
        status, result = await self.scroll({"direction": "down", "nodeId": "w1:21"})
        self.assertEqual(status, 200)
        self.assertEqual(result["scroll_fallback"], "screen_center")
        self.assertEqual(result["start_y"], 1755)
        self.assertTrue(result["gesture_dispatched_only"])
        self.assertIsNone(result["content_movement_verified"])
        self.assertEqual(
            [c["path"] for c in self.server.bridge.calls], ["/screen", "/scroll"]
        )
        self.assertNotIn("nodeId", self.server.bridge.calls[-1]["body"])

    async def test_bottom_only_target_fails_closed(self):
        self.server.bridge.node = _node(1775, 2300)
        status, result = await self.scroll({"direction": "down", "nodeId": "w1:21"})
        self.assertEqual(status, 409)
        self.assertFalse(result["action_performed"])
        self.assertEqual(
            [c["path"] for c in self.server.bridge.calls], ["/screen"]
        )

    async def test_safe_node_remains_targeted(self):
        self.server.bridge.node = _node(400, 1750)
        status, result = await self.scroll({"direction": "down", "nodeId": "w1:21"})
        self.assertEqual(status, 200)
        self.assertNotIn("scroll_fallback", result)
        self.assertEqual(result["start_y"], 2179)
        self.assertEqual(self.server.bridge.calls[-1]["body"]["nodeId"], "w1:21")

    async def test_screen_level_scroll_preserves_existing_behavior(self):
        status, result = await self.scroll({"direction": "up"})
        self.assertEqual(status, 200)
        self.assertEqual(
            [c["path"] for c in self.server.bridge.calls], ["/scroll"]
        )
        self.assertTrue(result["gesture_dispatched_only"])
        self.assertIsNone(result["content_movement_verified"])

    def test_directional_geometry_risk_detection(self):
        # Reported failure: down gesture starts at Y=2179 in overlay area.
        self.assertEqual(
            relay._scroll_target_near_edge(_node(950, 2238), ROOT, "down"),
            (True, True),
        )
        self.assertEqual(
            relay._scroll_target_near_edge(_node(1775, 2300), ROOT, "down"),
            (True, False),
        )
        self.assertEqual(
            relay._scroll_target_near_edge(_node(400, 1750), ROOT, "down"),
            (False, True),
        )


if __name__ == "__main__":
    unittest.main()
