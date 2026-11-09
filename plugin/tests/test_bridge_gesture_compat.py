"""Regression tests for host/phone bridge gesture compatibility.

Run with:
  python -m unittest plugin.tests.test_bridge_gesture_compat -v
"""
from __future__ import annotations

import os
import tempfile
import unittest
from unittest import mock

from aiohttp import web
from aiohttp.test_utils import AioHTTPTestCase

from plugin.relay.config import RelayConfig
from plugin.relay.server import create_app


ROOT = {"left": 0, "top": 0, "right": 1080, "bottom": 2340}


def screen(*, top: int = 900, bottom: int = 2250, scrollable: bool = True):
    return {
        "status": 200,
        "result": {
            "rootBounds": ROOT,
            "nodes": [{
                "nodeId": "w1:34",
                "text": "",
                "className": "android.widget.ScrollView",
                "scrollable": scrollable,
                "bounds": {"left": 0, "top": top, "right": 1080, "bottom": bottom},
            }],
        },
    }


class GestureCompatibilityTests(AioHTTPTestCase):
    async def asyncSetUp(self):
        self._temp = tempfile.TemporaryDirectory()
        self._patch = mock.patch.dict(os.environ, {"HERMES_HOME": self._temp.name})
        self._patch.start()
        await super().asyncSetUp()
        self.session = self.app["server"].sessions.create_session("device", "device")
        self.headers = {"Authorization": f"Bearer {self.session.token}"}
        self.bridge = mock.AsyncMock()
        self.app["server"].bridge.handle_command = self.bridge

    async def asyncTearDown(self):
        await super().asyncTearDown()
        self._patch.stop()
        self._temp.cleanup()

    async def get_application(self) -> web.Application:
        return create_app(RelayConfig(profile_discovery_enabled=False))

    async def test_legacy_directional_swipe_gets_coordinates(self):
        self.bridge.side_effect = [screen(), {"status": 200, "result": {"ok": True}}]
        response = await self.client.post(
            "/swipe", json={"direction": "up", "distance": "short"}, headers=self.headers
        )
        self.assertEqual(response.status, 200)
        self.assertEqual(self.bridge.await_count, 2)
        screen_request, swipe_request = self.bridge.await_args_list
        self.assertEqual(screen_request.kwargs["path"], "/screen")
        self.assertEqual(swipe_request.kwargs["path"], "/swipe")
        self.assertEqual(swipe_request.kwargs["body"], {
            "start_x": 540, "start_y": 1404,
            "end_x": 540, "end_y": 936,
            "duration_ms": 400,
        })

    async def test_coordinate_swipe_remains_unchanged(self):
        self.bridge.return_value = {"status": 200, "result": {"ok": True}}
        coords = {"start_x": 100, "start_y": 200, "end_x": 300, "end_y": 400,
                  "duration_ms": 250}
        response = await self.client.post("/swipe", json=coords, headers=self.headers)
        self.assertEqual(response.status, 200)
        self.bridge.assert_awaited_once()
        self.assertEqual(self.bridge.await_args.kwargs["body"], coords)

    async def test_invalid_screen_geometry_fails_without_gesture(self):
        self.bridge.return_value = {"status": 200, "result": {"rootBounds": {}}}
        response = await self.client.post(
            "/swipe", json={"direction": "down", "distance": "long"}, headers=self.headers
        )
        self.assertEqual(response.status, 503)
        self.bridge.assert_awaited_once()
        self.assertEqual(self.bridge.await_args.kwargs["path"], "/screen")

    async def test_near_edge_scroll_falls_back_to_screen_center(self):
        self.bridge.side_effect = [screen(top=950, bottom=2238),
                                   {"status": 200, "result": {"ok": True}}]
        response = await self.client.post(
            "/scroll", json={"nodeId": "w1:34", "direction": "down"}, headers=self.headers
        )
        data = await response.json()
        self.assertEqual(response.status, 200)
        self.assertEqual(data["scroll_fallback"], "screen_center")
        self.assertTrue(data["gesture_dispatched_only"])
        self.assertIsNone(data["content_movement_verified"])
        self.assertNotIn("nodeId", self.bridge.await_args_list[1].kwargs["body"])

    async def test_dangerous_edge_scroll_is_refused(self):
        self.bridge.return_value = screen(top=1775, bottom=2300)
        response = await self.client.post(
            "/scroll", json={"nodeId": "w1:34", "direction": "down"}, headers=self.headers
        )
        data = await response.json()
        self.assertEqual(response.status, 409)
        self.assertFalse(data["action_performed"])
        self.bridge.assert_awaited_once()

    async def test_non_scrollable_node_is_refused(self):
        self.bridge.return_value = screen(scrollable=False)
        response = await self.client.post(
            "/scroll", json={"nodeId": "w1:34", "direction": "down"}, headers=self.headers
        )
        self.assertEqual(response.status, 409)
        self.bridge.assert_awaited_once()

    async def test_safe_target_keeps_node_and_does_not_claim_movement(self):
        self.bridge.side_effect = [screen(top=400, bottom=1750),
                                   {"status": 200, "result": {"ok": True}}]
        response = await self.client.post(
            "/scroll", json={"nodeId": "w1:34", "direction": "down"}, headers=self.headers
        )
        data = await response.json()
        self.assertEqual(response.status, 200)
        self.assertNotIn("scroll_fallback", data)
        self.assertIsNone(data["content_movement_verified"])
        self.assertEqual(self.bridge.await_args_list[1].kwargs["body"]["nodeId"], "w1:34")


if __name__ == "__main__":
    unittest.main()
