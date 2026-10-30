"""Snapshot-bound Android Accessibility node-ID contract at Relay HTTP boundary."""

from __future__ import annotations

import json
import time
import unittest
from unittest.mock import patch

from plugin.relay import server as relay
from plugin.tools import android_tool


def _node(label: str, node_id: str = "w1:34") -> dict:
    return {
        "nodeId": node_id,
        "text": label,
        "contentDescription": None,
        "className": "android.widget.Button",
        "viewId": "calculator_key",
        "bounds": {"left": 30, "top": 150, "right": 110, "bottom": 200},
        "clickable": True,
        "longClickable": False,
        "scrollable": False,
        "editable": False,
        "enabled": True,
    }


class _Session:
    def __init__(self, token: str = "demo-session"):
        self.token = token

    def channel_is_expired(self, _: str) -> bool:
        return False


class _Bridge:
    def __init__(self):
        self.label = "="
        self.actions: list[dict] = []

    async def handle_command(self, **kwargs):
        path = kwargs["path"]
        if path == "/screen":
            return {
                "status": 200,
                "result": {
                    "packageName": "com.example.calculator",
                    "nodes": [_node(self.label)],
                },
            }
        if path == "/find_nodes":
            return {"status": 200, "result": {"matches": [_node(self.label)], "count": 1}}
        if path == "/describe_node":
            return {
                "status": 200,
                "result": {"nodeId": kwargs["body"]["nodeId"], "text": self.label},
            }
        if path in ("/tap", "/scroll", "/long_press"):
            self.actions.append(kwargs)
            return {"status": 200, "result": {"ok": True}}
        raise AssertionError(f"Unexpected Android bridge path: {path}")


class _Server:
    def __init__(self):
        self.bridge = _Bridge()


class _Request:
    query: dict = {}

    def __init__(self, method: str, payload: dict | None = None):
        self.method = method
        self.payload = payload or {}

    async def json(self):
        return self.payload


class SnapshotIDTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        relay._BRIDGE_NODE_SNAPSHOTS.clear()
        self.server = _Server()
        self.session = _Session()
        self.auth = patch.object(
            relay,
            "_require_bearer_session",
            return_value=(self.server, self.session),
        )
        self.auth.start()

    def tearDown(self) -> None:
        self.auth.stop()
        relay._BRIDGE_NODE_SNAPSHOTS.clear()

    async def dispatch(self, method: str, path: str, body: dict | None = None):
        response = await relay._bridge_dispatch(_Request(method, body), path)
        return response.status, json.loads(response.text)

    async def snapshot(self) -> str:
        status, result = await self.dispatch("GET", "/screen")
        self.assertEqual(status, 200)
        self.assertEqual(len(result["nodes"]), 1)
        return result["nodes"][0]["nodeId"]

    async def test_fresh_id_taps_once_and_reuse_is_refused(self) -> None:
        handle = await self.snapshot()
        self.assertTrue(handle.startswith("n-"))
        status, _ = await self.dispatch("POST", "/tap", {"nodeId": handle})
        self.assertEqual(status, 200)
        self.assertEqual(len(self.server.bridge.actions), 1)
        self.assertEqual(self.server.bridge.actions[0]["body"]["nodeId"], "w1:34")
        status, result = await self.dispatch("POST", "/tap", {"nodeId": handle})
        self.assertEqual(status, 409)
        self.assertFalse(result["action_performed"])
        self.assertEqual(len(self.server.bridge.actions), 1)

    async def test_new_read_invalidates_previous_id_before_any_tap(self) -> None:
        previous = await self.snapshot()
        self.server.bridge.label = "("
        fresh = await self.snapshot()
        self.assertNotEqual(previous, fresh)
        status, _ = await self.dispatch("POST", "/tap", {"nodeId": previous})
        self.assertEqual(status, 409)
        self.assertEqual(self.server.bridge.actions, [])
        status, _ = await self.dispatch("POST", "/tap", {"nodeId": fresh})
        self.assertEqual(status, 200)

    async def test_current_tree_changes_without_read_is_refused(self) -> None:
        old = await self.snapshot()
        self.server.bridge.label = "0"
        status, body = await self.dispatch("POST", "/tap", {"nodeId": old})
        self.assertEqual(status, 409)
        self.assertIn("changed", body["error"])
        self.assertEqual(self.server.bridge.actions, [])

    async def test_raw_node_id_never_reaches_phone(self) -> None:
        await self.snapshot()
        for path in ("/tap", "/long_press", "/scroll", "/describe_node"):
            status, body = await self.dispatch("POST", path, {"nodeId": "w1:34"})
            self.assertEqual(status, 409, path)
            self.assertFalse(body["action_performed"])
        self.assertEqual(self.server.bridge.actions, [])

    async def test_session_and_device_isolation(self) -> None:
        handle = await self.snapshot()
        self.session.token = "different-session"
        status, _ = await self.dispatch("POST", "/tap", {"nodeId": handle})
        self.assertEqual(status, 409)
        self.assertEqual(self.server.bridge.actions, [])

    async def test_expired_handle_is_refused(self) -> None:
        handle = await self.snapshot()
        scope = relay._bridge_node_scope(self.session, None)
        relay._BRIDGE_NODE_SNAPSHOTS[scope]["issued"] = (
            time.monotonic() - relay._BRIDGE_NODE_MAX_AGE_SECONDS - 1
        )
        status, body = await self.dispatch("POST", "/tap", {"nodeId": handle})
        self.assertEqual(status, 409)
        self.assertIn("Expired", body["error"])

    async def test_find_nodes_and_describe_preserve_public_handle(self) -> None:
        status, result = await self.dispatch("POST", "/find_nodes", {"limit": 2})
        self.assertEqual(status, 200)
        handle = result["matches"][0]["nodeId"]
        self.assertTrue(handle.startswith("n-"))
        status, described = await self.dispatch(
            "POST", "/describe_node", {"nodeId": handle}
        )
        self.assertEqual(status, 200)
        self.assertEqual(described["nodeId"], handle)
        status, _ = await self.dispatch("POST", "/tap", {"nodeId": handle})
        self.assertEqual(status, 200)


class RefusalMessageTests(unittest.TestCase):
    def test_tool_reports_stale_error_without_claiming_a_tap(self):
        class _Response:
            status_code = 409

            def json(self):
                return {"error": "Stale node ID: read again"}

        class _Error(Exception):
            response = _Response()

        result = json.loads(android_tool._gesture_failure(_Error("HTTP 409")))
        self.assertEqual(result["error"], "Stale node ID: read again")
        self.assertFalse(result["action_performed"])


if __name__ == "__main__":
    unittest.main()
