"""HTTP regressions for stale Android Accessibility node targeting.

Run with:
    python -m unittest plugin.tests.test_bridge_node_handles -v
"""
from __future__ import annotations

import os
import tempfile
import time
from unittest import mock

from aiohttp import web
from aiohttp.test_utils import AioHTTPTestCase

from plugin.relay.config import RelayConfig
from plugin.relay import server as relay_server
from plugin.relay.server import create_app


def node(text: str = "=", node_id: str = "w1:34") -> dict:
    return {
        "nodeId": node_id,
        "text": text,
        "contentDescription": None,
        "className": "android.widget.Button",
        "viewId": "button",
        "bounds": {"left": 20, "top": 100, "right": 70, "bottom": 150},
        "clickable": True,
        "longClickable": False,
        "scrollable": False,
        "editable": False,
        "enabled": True,
    }


class SnapshotNodeTests(AioHTTPTestCase):
    async def asyncSetUp(self):
        self._temp = tempfile.TemporaryDirectory()
        self._patch = mock.patch.dict(os.environ, {"HERMES_HOME": self._temp.name})
        self._patch.start()
        await super().asyncSetUp()
        self.session = self.app["server"].sessions.create_session("device", "device")
        self.headers = {"Authorization": f"Bearer {self.session.token}"}
        self.text = "="
        self.calls = []

        async def fake_device(*, method, path, params, body, device):
            self.calls.append((method, path, body.copy(), device))
            if path == "/screen":
                # PR #818 fixes the Android compact mode to return nodes with
                # zeroed geometry. Verification must not compare that against
                # a separate full-geometry response and reject a valid tap.
                compact = str(body.get("include_bounds", "true")) == "false"
                item = node(self.text)
                if compact:
                    item["bounds"] = {"left": 0, "top": 0, "right": 0, "bottom": 0}
                return {"status": 200, "result": {
                    "packageName": "com.example.calc", "nodes": [item],
                    "rootBounds": {"left": 0, "top": 0, "right": 1080, "bottom": 2400},
                }}
            if path == "/find_nodes":
                return {"status": 200, "result": {"matches": [node(self.text)], "count": 1}}
            if path == "/describe_node":
                return {"status": 200, "result": node(self.text)}
            if path in ("/tap", "/long_press", "/scroll"):
                return {"status": 200, "result": {"ok": True}}
            raise AssertionError(f"unexpected path: {path}")

        self.app["server"].bridge.handle_command = mock.AsyncMock(side_effect=fake_device)

    async def asyncTearDown(self):
        relay_server._BRIDGE_NODE_SNAPSHOTS.clear()
        await super().asyncTearDown()
        self._patch.stop()
        self._temp.cleanup()

    async def get_application(self) -> web.Application:
        return create_app(RelayConfig(profile_discovery_enabled=False))

    async def read_id(self, *, headers=None, path="/screen"):
        if path == "/screen":
            resp = await self.client.get(path, headers=headers or self.headers)
            key = "nodes"
        else:
            resp = await self.client.post(path, json={}, headers=headers or self.headers)
            key = "matches"
        self.assertEqual(resp.status, 200)
        return (await resp.json())[key][0]["nodeId"]

    async def tap(self, identifier, *, headers=None):
        return await self.client.post(
            "/tap", json={"nodeId": identifier}, headers=headers or self.headers,
        )

    async def test_new_id_is_opaque_and_only_usable_once(self):
        identifier = await self.read_id()
        self.assertTrue(identifier.startswith("n-"))
        self.assertTrue(identifier.endswith(".w1:34"))
        reply = await self.tap(identifier)
        self.assertEqual(reply.status, 200)
        self.assertEqual(self.calls[-1][2]["nodeId"], "w1:34")
        before = len(self.calls)
        replay = await self.tap(identifier)
        self.assertEqual(replay.status, 409)
        self.assertFalse((await replay.json())["action_performed"])
        self.assertEqual(len(self.calls), before)

    async def test_compact_screen_preserves_output_and_allows_verified_tap(self):
        # Host defaults to include_bounds=false; on Android with #818 that
        # returns nodes with zero coordinates, but still requires fresh
        # full-geometry verification for a secure tap.
        response = await self.client.get(
            "/screen?include_bounds=false", headers=self.headers,
        )
        self.assertEqual(response.status, 200)
        data = await response.json()
        self.assertEqual(data["rootBounds"]["bottom"], 2400)
        self.assertEqual(data["nodes"][0]["bounds"], {
            "left": 0, "top": 0, "right": 0, "bottom": 0,
        })
        identifier = data["nodes"][0]["nodeId"]
        self.assertTrue(identifier.startswith("n-"))
        self.assertEqual((await self.tap(identifier)).status, 200)
        self.assertEqual(self.calls[-1][2]["nodeId"], "w1:34")

    async def test_compact_screen_still_rejects_changed_control(self):
        response = await self.client.get(
            "/screen?include_bounds=false", headers=self.headers,
        )
        identifier = (await response.json())["nodes"][0]["nodeId"]
        self.text = "0"
        before = len(self.calls)
        refused = await self.tap(identifier)
        self.assertEqual(refused.status, 409)
        self.assertIn("Stale", (await refused.json())["error"])
        self.assertEqual(len(self.calls), before + 1)
        self.assertEqual(self.calls[-1][1], "/screen")

    async def test_full_screen_preserves_actual_bounds(self):
        response = await self.client.get(
            "/screen?include_bounds=true", headers=self.headers,
        )
        self.assertEqual(response.status, 200)
        self.assertEqual((await response.json())["nodes"][0]["bounds"], {
            "left": 20, "top": 100, "right": 70, "bottom": 150,
        })
        self.assertEqual(self.calls[-1][2]["include_bounds"], "true")

    async def test_new_screen_read_invalidates_old_target(self):
        first = await self.read_id()
        self.text = "("
        second = await self.read_id()
        self.assertNotEqual(first, second)
        before = len(self.calls)
        old = await self.tap(first)
        self.assertEqual(old.status, 409)
        self.assertEqual(len(self.calls), before)
        self.assertEqual((await self.tap(second)).status, 200)

    async def test_changed_control_without_new_read_is_rejected(self):
        original = await self.read_id()
        self.text = "0"
        before = len(self.calls)
        reply = await self.tap(original)
        self.assertEqual(reply.status, 409)
        self.assertIn("Stale", (await reply.json())["error"])
        self.assertEqual(len(self.calls), before + 1)  # verification GET only
        self.assertEqual(self.calls[-1][1], "/screen")

    async def test_raw_positional_id_is_refused(self):
        await self.read_id()
        before = len(self.calls)
        reply = await self.tap("w1:34")
        self.assertEqual(reply.status, 409)
        self.assertIn("Unsafe raw", (await reply.json())["error"])
        self.assertEqual(len(self.calls), before)

    async def test_expired_id_is_refused(self):
        identifier = await self.read_id()
        scope = (self.session.token, "")
        relay_server._BRIDGE_NODE_SNAPSHOTS[scope]["issued"] = (
            time.monotonic() - relay_server._BRIDGE_NODE_MAX_AGE_SECONDS - 1
        )
        before = len(self.calls)
        reply = await self.tap(identifier)
        self.assertEqual(reply.status, 409)
        self.assertIn("Expired", (await reply.json())["error"])
        self.assertEqual(len(self.calls), before)

    async def test_session_isolation(self):
        other = self.app["server"].sessions.create_session("other-device", "other-device")
        other_headers = {"Authorization": f"Bearer {other.token}"}
        own_id = await self.read_id()
        other_id = await self.read_id(headers=other_headers)
        before = len(self.calls)
        refused = await self.tap(own_id, headers=other_headers)
        self.assertEqual(refused.status, 409)
        self.assertEqual(len(self.calls), before)
        self.assertEqual((await self.tap(other_id, headers=other_headers)).status, 200)
        self.assertEqual((await self.tap(own_id)).status, 200)

    async def test_find_nodes_and_describe_maintain_handle(self):
        identifier = await self.read_id(path="/find_nodes")
        resp = await self.client.post(
            "/describe_node", json={"nodeId": identifier}, headers=self.headers,
        )
        self.assertEqual(resp.status, 200)
        self.assertEqual((await resp.json())["nodeId"], identifier)
        self.assertEqual((await self.tap(identifier)).status, 200)

    async def test_long_press_and_scroll_accept_fresh_ids(self):
        target = await self.read_id()
        response = await self.client.post(
            "/long_press", json={"node_id": target, "duration": 400},
            headers=self.headers,
        )
        self.assertEqual(response.status, 200)
        self.assertEqual(self.calls[-1][2]["node_id"], "w1:34")
        target = await self.read_id()
        response = await self.client.post(
            "/scroll", json={"nodeId": target, "direction": "down"},
            headers=self.headers,
        )
        self.assertEqual(response.status, 200)
        self.assertEqual(self.calls[-1][2]["nodeId"], "w1:34")

    async def test_coordinates_unchanged(self):
        response = await self.client.post(
            "/tap", json={"x": 15, "y": 30}, headers=self.headers,
        )
        self.assertEqual(response.status, 200)
        self.assertEqual(self.calls[-1][2], {"x": 15, "y": 30})


if __name__ == "__main__":
    import unittest
    unittest.main()
