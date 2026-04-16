"""
Unit tests for ``plugin.tools.android_tool.android_find_nodes``.

Uses only stdlib ``unittest`` + ``unittest.mock`` so it runs cleanly via::

    python -m unittest plugin.tests.test_android_find_nodes

without pulling in the ``responses`` dependency that the existing
``conftest.py`` imports at collection time. The conftest is pytest-only
and is not loaded by ``unittest``.

Coverage:
  * Filter by text only (case-insensitive substring match)
  * Filter by class_name only (exact match)
  * Filter by clickable only (bool)
  * Combined text + class + clickable (ANDed together)
  * Limit enforcement on the wire payload
  * Empty result handling
  * Schema + handler registration
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path
from unittest import mock

# Make `import plugin.tools.android_tool` work when the test runs from the
# repo root without the package being editable-installed. Mirrors the
# approach used by plugin/tests/test_android_navigate.py.
REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

# ``plugin.tools.android_tool`` imports the top-level ``tools.registry``
# for Hermes-agent's tool registry. Outside the hermes-agent venv that
# import resolves to nothing and is caught by a top-level try/except in
# the module itself, so import is safe here.
from plugin.tools import android_tool  # noqa: E402
from plugin.tools.android_tool import (  # noqa: E402
    _HANDLERS,
    _SCHEMAS,
    android_find_nodes,
)


def _make_match(
    *,
    node_id: str,
    text: str | None = None,
    content_description: str | None = None,
    class_name: str | None = None,
    clickable: bool = False,
) -> dict:
    """Build a fake ScreenNode dict — mirrors the Kotlin @Serializable shape."""
    return {
        "nodeId": node_id,
        "text": text,
        "contentDescription": content_description,
        "className": class_name,
        "viewId": None,
        "bounds": {"left": 0, "top": 0, "right": 100, "bottom": 100},
        "clickable": clickable,
        "longClickable": False,
        "scrollable": False,
        "editable": False,
        "focused": False,
        "selected": False,
        "enabled": True,
    }


class _FakeResponse:
    """Minimal duck-type for ``requests.Response``."""

    def __init__(self, payload: dict, status: int = 200):
        self._payload = payload
        self.status_code = status

    def json(self) -> dict:
        return self._payload

    def raise_for_status(self) -> None:
        if self.status_code >= 400:
            raise RuntimeError(f"HTTP {self.status_code}")


class TestFindNodesWireShape(unittest.TestCase):
    """The tool must forward the right params to POST /find_nodes."""

    def _capture_post(self, response_payload: dict):
        """Patch ``requests.post`` on the android_tool module."""
        return mock.patch.object(
            android_tool.requests,
            "post",
            return_value=_FakeResponse(response_payload),
        )

    def test_filter_by_text_only(self) -> None:
        payload = {
            "matches": [
                _make_match(node_id="w0:3", text="Send message", clickable=True),
            ],
            "count": 1,
        }
        with self._capture_post(payload) as post:
            out = android_find_nodes(text="send")
        body = post.call_args.kwargs["json"]
        self.assertEqual(body.get("text"), "send")
        self.assertNotIn("class_name", body)
        self.assertNotIn("clickable", body)
        self.assertEqual(body.get("limit"), 20)
        parsed = json.loads(out)
        self.assertEqual(parsed["count"], 1)
        self.assertEqual(parsed["matches"][0]["nodeId"], "w0:3")

    def test_filter_by_class_only(self) -> None:
        payload = {
            "matches": [
                _make_match(
                    node_id="w0:7",
                    class_name="android.widget.Button",
                    clickable=True,
                ),
            ],
            "count": 1,
        }
        with self._capture_post(payload) as post:
            out = android_find_nodes(class_name="android.widget.Button")
        body = post.call_args.kwargs["json"]
        self.assertEqual(body.get("class_name"), "android.widget.Button")
        self.assertNotIn("text", body)
        self.assertNotIn("clickable", body)
        parsed = json.loads(out)
        self.assertEqual(parsed["count"], 1)
        self.assertEqual(
            parsed["matches"][0]["className"], "android.widget.Button"
        )

    def test_filter_by_clickable_only(self) -> None:
        payload = {
            "matches": [
                _make_match(node_id="w0:1", clickable=True),
                _make_match(node_id="w1:4", clickable=True),
            ],
            "count": 2,
        }
        with self._capture_post(payload) as post:
            out = android_find_nodes(clickable=True)
        body = post.call_args.kwargs["json"]
        self.assertIs(body.get("clickable"), True)
        self.assertNotIn("text", body)
        self.assertNotIn("class_name", body)
        parsed = json.loads(out)
        self.assertEqual(parsed["count"], 2)
        self.assertTrue(all(m["clickable"] for m in parsed["matches"]))

    def test_filter_by_clickable_false_is_forwarded(self) -> None:
        """clickable=False is not the same as None — must still be sent."""
        payload = {"matches": [], "count": 0}
        with self._capture_post(payload) as post:
            android_find_nodes(clickable=False)
        body = post.call_args.kwargs["json"]
        self.assertIn("clickable", body)
        self.assertIs(body["clickable"], False)

    def test_combined_filters(self) -> None:
        payload = {
            "matches": [
                _make_match(
                    node_id="w0:2",
                    text="Send",
                    class_name="android.widget.Button",
                    clickable=True,
                ),
            ],
            "count": 1,
        }
        with self._capture_post(payload) as post:
            out = android_find_nodes(
                text="send",
                class_name="android.widget.Button",
                clickable=True,
                limit=5,
            )
        body = post.call_args.kwargs["json"]
        self.assertEqual(body.get("text"), "send")
        self.assertEqual(body.get("class_name"), "android.widget.Button")
        self.assertIs(body.get("clickable"), True)
        self.assertEqual(body.get("limit"), 5)
        parsed = json.loads(out)
        self.assertEqual(parsed["count"], 1)
        match = parsed["matches"][0]
        self.assertEqual(match["text"], "Send")
        self.assertEqual(match["className"], "android.widget.Button")
        self.assertTrue(match["clickable"])

    def test_limit_is_forwarded_as_integer(self) -> None:
        payload = {"matches": [], "count": 0}
        with self._capture_post(payload) as post:
            android_find_nodes(limit=42)
        body = post.call_args.kwargs["json"]
        self.assertEqual(body.get("limit"), 42)
        self.assertIsInstance(body["limit"], int)

    def test_empty_result(self) -> None:
        payload = {"matches": [], "count": 0}
        with self._capture_post(payload) as post:
            out = android_find_nodes(text="definitely not on screen")
        body = post.call_args.kwargs["json"]
        self.assertEqual(body.get("text"), "definitely not on screen")
        parsed = json.loads(out)
        self.assertEqual(parsed["count"], 0)
        self.assertEqual(parsed["matches"], [])

    def test_no_filters_still_sends_limit(self) -> None:
        """Zero filters is valid — tool still hits the endpoint and sends limit."""
        payload = {"matches": [], "count": 0}
        with self._capture_post(payload) as post:
            android_find_nodes()
        body = post.call_args.kwargs["json"]
        self.assertNotIn("text", body)
        self.assertNotIn("class_name", body)
        self.assertNotIn("clickable", body)
        self.assertEqual(body.get("limit"), 20)

    def test_exception_returns_error_json(self) -> None:
        """Network failures must be caught and surfaced as {error: ...}."""
        with mock.patch.object(
            android_tool.requests,
            "post",
            side_effect=RuntimeError("boom"),
        ):
            out = android_find_nodes(text="anything")
        parsed = json.loads(out)
        self.assertIn("error", parsed)
        self.assertIn("boom", parsed["error"])


class TestFindNodesRegistration(unittest.TestCase):
    """The tool must be discoverable alongside the other 14 android_* tools."""

    def test_schema_registered(self) -> None:
        self.assertIn("android_find_nodes", _SCHEMAS)
        schema = _SCHEMAS["android_find_nodes"]
        self.assertEqual(schema["name"], "android_find_nodes")
        self.assertIn("description", schema)
        params = schema["parameters"]["properties"]
        for key in ("text", "class_name", "clickable", "limit"):
            self.assertIn(key, params)

    def test_handler_registered(self) -> None:
        self.assertIn("android_find_nodes", _HANDLERS)
        # Handler is the kwargs-splat lambda — verify it forwards correctly
        # by stubbing requests.post and invoking via the handler key.
        payload = {"matches": [], "count": 0}
        with mock.patch.object(
            android_tool.requests,
            "post",
            return_value=_FakeResponse(payload),
        ) as post:
            out = _HANDLERS["android_find_nodes"]({"text": "abc", "limit": 3})
        body = post.call_args.kwargs["json"]
        self.assertEqual(body.get("text"), "abc")
        self.assertEqual(body.get("limit"), 3)
        parsed = json.loads(out)
        self.assertEqual(parsed["count"], 0)


if __name__ == "__main__":
    unittest.main()
