"""
Unit tests for ``plugin.tools.android_tool.android_read_screen``.

Stdlib ``unittest`` + ``unittest.mock`` only — no ``pytest`` or
``responses``. Run via::

    python -m unittest plugin.tests.test_android_read_screen

Why stdlib unittest: the existing ``plugin/tests/conftest.py`` imports
``responses`` which is not in every venv (notably the hermes-host venv).
``python -m unittest`` bypasses pytest's conftest discovery so the test
runs cleanly regardless of whether ``responses`` is installed.

Coverage:
  * happy path — bridge returns a serialized ScreenContent payload
  * include_bounds defaults to false and gets URL-encoded as such
  * include_bounds=True flips the query string parameter
  * empty screen — bridge returns an empty nodes list
  * service-not-connected — bridge returns 503 error body
  * network failure — _get raises, tool surfaces as JSON error
  * schema registration sanity (params, defaults, handler dispatch)
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path
from unittest import mock

REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from plugin.tools import android_tool  # noqa: E402


class TestAndroidReadScreenHappyPath(unittest.TestCase):
    def test_returns_screen_content(self) -> None:
        fake = {
            "rootBounds": {"left": 0, "top": 0, "right": 1080, "bottom": 2400},
            "nodes": [
                {
                    "nodeId": "n1",
                    "text": "Settings",
                    "className": "android.widget.TextView",
                    "clickable": True,
                },
                {
                    "nodeId": "n2",
                    "text": "Network & internet",
                    "className": "android.widget.TextView",
                    "clickable": True,
                },
            ],
            "truncated": False,
        }
        with mock.patch.object(android_tool, "_get", return_value=fake) as m:
            result = json.loads(android_tool.android_read_screen())

        self.assertEqual(len(result["nodes"]), 2)
        self.assertEqual(result["nodes"][0]["nodeId"], "n1")
        self.assertFalse(result["truncated"])

        # Default include_bounds=False should encode as ?bounds=false
        m.assert_called_once()
        called_path = m.call_args.args[0]
        self.assertEqual(called_path, "/screen?include_bounds=false")

    def test_include_bounds_true_passes_query(self) -> None:
        fake = {"rootBounds": {}, "nodes": [], "truncated": False}
        with mock.patch.object(android_tool, "_get", return_value=fake) as m:
            android_tool.android_read_screen(include_bounds=True)
        called_path = m.call_args.args[0]
        self.assertEqual(called_path, "/screen?include_bounds=true")

    def test_include_bounds_false_explicit_passes_query(self) -> None:
        # Explicit False should still produce ?bounds=false (not omitted).
        fake = {"rootBounds": {}, "nodes": [], "truncated": False}
        with mock.patch.object(android_tool, "_get", return_value=fake) as m:
            android_tool.android_read_screen(include_bounds=False)
        called_path = m.call_args.args[0]
        self.assertEqual(called_path, "/screen?include_bounds=false")


class TestAndroidReadScreenEmpty(unittest.TestCase):
    def test_empty_node_list(self) -> None:
        fake = {"rootBounds": {}, "nodes": [], "truncated": False}
        with mock.patch.object(android_tool, "_get", return_value=fake):
            result = json.loads(android_tool.android_read_screen())
        self.assertEqual(result["nodes"], [])
        self.assertFalse(result["truncated"])


class TestAndroidReadScreenErrorPaths(unittest.TestCase):
    def test_service_not_connected_passthrough(self) -> None:
        # When BridgeCommandHandler rejects with 503, the relay returns the
        # error body verbatim. android_read_screen propagates the dict.
        fake = {
            "error": (
                "Hermes AccessibilityService not connected — enable it in "
                "Android Settings"
            )
        }
        with mock.patch.object(android_tool, "_get", return_value=fake):
            result = json.loads(android_tool.android_read_screen())
        self.assertIn("error", result)
        self.assertIn("AccessibilityService", result["error"])

    def test_network_error_returns_error_json(self) -> None:
        # _get raises (relay down, DNS failure, etc.) — tool catches and
        # returns a JSON-serialized error envelope rather than crashing.
        with mock.patch.object(
            android_tool, "_get", side_effect=ConnectionError("relay unreachable")
        ):
            result = json.loads(android_tool.android_read_screen())
        self.assertIn("error", result)
        self.assertIn("relay unreachable", result["error"])

    def test_timeout_error(self) -> None:
        # Distinct exception type to verify the catch-all behavior.
        import requests
        with mock.patch.object(
            android_tool, "_get", side_effect=requests.Timeout("read timed out")
        ):
            result = json.loads(android_tool.android_read_screen())
        self.assertIn("error", result)


class TestAndroidReadScreenSchema(unittest.TestCase):
    def test_registered(self) -> None:
        self.assertIn("android_read_screen", android_tool._SCHEMAS)
        self.assertIn("android_read_screen", android_tool._HANDLERS)

    def test_schema_params(self) -> None:
        schema = android_tool._SCHEMAS["android_read_screen"]
        self.assertEqual(schema["name"], "android_read_screen")
        self.assertIn("accessibility tree", schema["description"].lower())
        props = schema["parameters"]["properties"]
        self.assertIn("include_bounds", props)
        self.assertEqual(props["include_bounds"]["type"], "boolean")
        self.assertFalse(props["include_bounds"]["default"])
        # include_bounds is optional — should NOT be in required.
        self.assertEqual(schema["parameters"]["required"], [])

    def test_handler_dispatch_with_default_args(self) -> None:
        fake = {"rootBounds": {}, "nodes": [], "truncated": False}
        with mock.patch.object(android_tool, "_get", return_value=fake):
            out = android_tool._HANDLERS["android_read_screen"]({})
        result = json.loads(out)
        self.assertEqual(result["nodes"], [])

    def test_handler_dispatch_with_include_bounds(self) -> None:
        fake = {"rootBounds": {}, "nodes": [], "truncated": False}
        with mock.patch.object(android_tool, "_get", return_value=fake) as m:
            out = android_tool._HANDLERS["android_read_screen"](
                {"include_bounds": True}
            )
        called_path = m.call_args.args[0]
        self.assertEqual(called_path, "/screen?include_bounds=true")
        result = json.loads(out)
        self.assertEqual(result["nodes"], [])


if __name__ == "__main__":
    unittest.main()
