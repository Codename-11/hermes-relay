"""
Unit tests for ``plugin.tools.android_tool.android_describe_node``.

Uses only stdlib ``unittest`` + ``unittest.mock`` so it runs cleanly via::

    python -m unittest plugin.tests.test_android_describe_node

without pulling in the ``responses`` dependency that the existing
``conftest.py`` imports at collection time. The conftest is pytest-only
and is not loaded by ``unittest``.

Coverage:
  * Happy path — bridge returns a full property bag, tool echoes it as JSON.
  * Unknown nodeId — bridge returns ``{"error": "node not found: ..."}``,
    tool surfaces the error string.
  * Missing / empty ``node_id`` argument — tool short-circuits before the
    HTTP call and returns its own guard error, so the relay never gets hit.
  * Schema sanity — the new tool is registered with the expected shape in
    ``_SCHEMAS`` / ``_HANDLERS``.
  * Registry wiring — ``_HANDLERS['android_describe_node']`` dispatches
    ``args`` dict into the underlying function.
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path
from unittest import mock

# Make `import plugin.tools.android_tool` work when the test runs from the
# repo root without the package being installed. Same bootstrap as
# test_android_navigate.py.
REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from plugin.tools import android_tool  # noqa: E402
from plugin.tools.android_tool import (  # noqa: E402
    _HANDLERS,
    _SCHEMAS,
    android_describe_node,
)


class TestAndroidDescribeNodeSchema(unittest.TestCase):
    def test_schema_registered(self) -> None:
        self.assertIn("android_describe_node", _SCHEMAS)
        schema = _SCHEMAS["android_describe_node"]
        self.assertEqual(schema["name"], "android_describe_node")
        self.assertIn("description", schema)
        self.assertIn("parameters", schema)

    def test_schema_requires_node_id(self) -> None:
        schema = _SCHEMAS["android_describe_node"]
        self.assertEqual(schema["parameters"]["required"], ["node_id"])
        props = schema["parameters"]["properties"]
        self.assertIn("node_id", props)
        self.assertEqual(props["node_id"]["type"], "string")

    def test_handler_registered(self) -> None:
        self.assertIn("android_describe_node", _HANDLERS)

    def test_handler_dispatches_args_dict(self) -> None:
        handler = _HANDLERS["android_describe_node"]
        with mock.patch.object(
            android_tool,
            "_post",
            return_value={
                "nodeId": "w0:7",
                "bounds": {"left": 0, "top": 0, "right": 100, "bottom": 100},
                "className": "android.widget.Button",
                "clickable": True,
            },
        ) as post_mock:
            raw = handler({"node_id": "w0:7"})
            result = json.loads(raw)
            self.assertEqual(result["nodeId"], "w0:7")
            post_mock.assert_called_once_with("/describe_node", {"nodeId": "w0:7"})


class TestAndroidDescribeNodeHappyPath(unittest.TestCase):
    def test_returns_property_bag(self) -> None:
        bridge_response = {
            "nodeId": "w0:12",
            "bounds": {
                "left": 100,
                "top": 200,
                "right": 300,
                "bottom": 400,
                "centerX": 200,
                "centerY": 300,
                "width": 200,
                "height": 200,
            },
            "className": "android.widget.Switch",
            "text": "Wi-Fi",
            "contentDescription": None,
            "hintText": None,
            "viewIdResourceName": "com.android.settings:id/switch_widget",
            "childCount": 0,
            "clickable": True,
            "longClickable": False,
            "focusable": True,
            "focused": False,
            "editable": False,
            "scrollable": False,
            "checkable": True,
            "checked": True,  # NOT null — it's a toggle and it's on
            "enabled": True,
            "selected": False,
            "password": False,
        }
        with mock.patch.object(
            android_tool, "_post", return_value=bridge_response
        ) as post_mock:
            raw = android_describe_node("w0:12")
            result = json.loads(raw)
            self.assertEqual(result, bridge_response)
            # Confirm the tool forwards `node_id` as `nodeId` on the wire —
            # this is the Python↔Kotlin body-key contract and the reason we
            # can't just `json.dumps({"node_id": ...})`.
            post_mock.assert_called_once_with("/describe_node", {"nodeId": "w0:12"})

    def test_checkable_false_means_checked_is_null(self) -> None:
        # When a node isn't a toggle, the Kotlin side emits `checked: null`
        # so the agent can distinguish "not a toggle" from "unchecked toggle".
        # We just verify the tool doesn't mangle that on its way through.
        bridge_response = {
            "nodeId": "w0:3",
            "className": "android.widget.TextView",
            "text": "Hello",
            "checkable": False,
            "checked": None,
            "enabled": True,
            "clickable": False,
        }
        with mock.patch.object(
            android_tool, "_post", return_value=bridge_response
        ):
            raw = android_describe_node("w0:3")
            result = json.loads(raw)
            self.assertIsNone(result["checked"])
            self.assertFalse(result["checkable"])


class TestAndroidDescribeNodeUnknownId(unittest.TestCase):
    def test_unknown_node_id_returns_error_payload(self) -> None:
        # The Kotlin handler responds 404 with `{"error": "node not found: ..."}`
        # and the relay's `_bridge_dispatch` bubbles the result object back up
        # with status preserved on the HTTP layer — the raw JSON body is what
        # we actually get back in the tool via `_post(...).json()`.
        with mock.patch.object(
            android_tool,
            "_post",
            return_value={"error": "node not found: w9:999"},
        ):
            raw = android_describe_node("w9:999")
            result = json.loads(raw)
            self.assertIn("error", result)
            self.assertIn("not found", result["error"])

    def test_malformed_node_id_bridge_returns_404(self) -> None:
        # `findNodeById` rejects malformed IDs (not starting with 'w', bad
        # colon placement, non-numeric indices) and surfaces that as a 404
        # error envelope.
        with mock.patch.object(
            android_tool,
            "_post",
            return_value={"error": "node not found: garbage-id"},
        ):
            raw = android_describe_node("garbage-id")
            result = json.loads(raw)
            self.assertIn("error", result)


class TestAndroidDescribeNodeMissingArg(unittest.TestCase):
    def test_empty_string_node_id_short_circuits(self) -> None:
        with mock.patch.object(android_tool, "_post") as post_mock:
            raw = android_describe_node("")
            result = json.loads(raw)
            self.assertIn("error", result)
            self.assertIn("node_id is required", result["error"])
            # Crucially, we never hit the wire.
            post_mock.assert_not_called()

    def test_none_node_id_short_circuits(self) -> None:
        with mock.patch.object(android_tool, "_post") as post_mock:
            # type: ignore[arg-type] — we're deliberately testing the None case
            raw = android_describe_node(None)  # type: ignore[arg-type]
            result = json.loads(raw)
            self.assertIn("error", result)
            post_mock.assert_not_called()

    def test_handler_missing_node_id_raises(self) -> None:
        # The registry handler uses `**args`, so calling it with no node_id
        # should raise a TypeError — this is the contract for "required"
        # parameters at the registry layer.
        handler = _HANDLERS["android_describe_node"]
        with self.assertRaises(TypeError):
            handler({})


class TestAndroidDescribeNodeBridgeErrors(unittest.TestCase):
    def test_bridge_raises_propagates_as_error_json(self) -> None:
        # Network/HTTP failure shouldn't crash the tool — it should echo the
        # exception message in the `{"error": ...}` envelope, matching how
        # every other android_* tool handles _post failures.
        with mock.patch.object(
            android_tool,
            "_post",
            side_effect=RuntimeError("relay unreachable"),
        ):
            raw = android_describe_node("w0:1")
            result = json.loads(raw)
            self.assertIn("error", result)
            self.assertEqual(result["error"], "relay unreachable")


if __name__ == "__main__":
    unittest.main()
