"""Unit tests for ``plugin.tools.android_tool.android_long_press`` (A1).

Uses only stdlib ``unittest`` + ``unittest.mock`` so it runs cleanly via::

    python -m unittest plugin.tests.test_android_long_press

without pulling in the ``responses`` dependency that the existing
``conftest.py`` imports at collection time. The conftest is pytest-only
and is not loaded by ``unittest``.

Coverage:
  * Happy path with ``(x, y)`` coordinates.
  * Happy path with ``node_id``.
  * Invalid args — missing both coords and node_id.
  * Invalid args — providing both coords and node_id.
  * Duration clamping — below 100 ms, above 3000 ms, non-int.
  * HTTP wire format — body shape posted to ``/long_press``.
  * Schema sanity — ``android_long_press`` is registered with the
    expected parameter shape.
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path
from unittest import mock

# Make ``import plugin.tools.android_tool`` work when the test runs from
# the repo root without the package being installed. Mirrors the
# bootstrap used by ``test_android_navigate.py``.
REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from plugin.tools import android_tool  # noqa: E402
from plugin.tools.android_tool import (  # noqa: E402
    _HANDLERS,
    _SCHEMAS,
    android_long_press,
)


# ── Helpers ──────────────────────────────────────────────────────────────────


class _FakeResponse:
    """Minimal stand-in for ``requests.Response`` that ``_post`` uses."""

    def __init__(self, payload: dict, status_code: int = 200) -> None:
        self._payload = payload
        self.status_code = status_code

    def raise_for_status(self) -> None:
        if self.status_code >= 400:
            raise RuntimeError(f"HTTP {self.status_code}")

    def json(self) -> dict:
        return self._payload


def _fake_ok_response(payload: dict | None = None) -> _FakeResponse:
    return _FakeResponse(payload or {"ok": True, "status": 200})


# ── Schema sanity ────────────────────────────────────────────────────────────


class SchemaSanityTests(unittest.TestCase):
    def test_long_press_is_registered(self) -> None:
        self.assertIn("android_long_press", _SCHEMAS)
        self.assertIn("android_long_press", _HANDLERS)

    def test_schema_parameters_shape(self) -> None:
        schema = _SCHEMAS["android_long_press"]
        self.assertEqual(schema["name"], "android_long_press")
        params = schema["parameters"]
        self.assertEqual(params["type"], "object")
        props = params["properties"]
        for key in ("x", "y", "node_id", "duration"):
            self.assertIn(key, props, msg=f"missing property '{key}'")
        self.assertEqual(props["duration"].get("default"), 500)
        # Neither branch is required — the function validates at runtime
        # because "exactly one of (x,y) or node_id" isn't expressible in
        # basic JSON Schema without oneOf.
        self.assertEqual(params.get("required", []), [])

    def test_handler_invokes_function(self) -> None:
        handler = _HANDLERS["android_long_press"]
        with mock.patch.object(android_tool, "_post") as post:
            post.return_value = {"ok": True}
            out = handler({"x": 10, "y": 20})
        self.assertIn("ok", json.loads(out))


# ── Happy path — coordinates ─────────────────────────────────────────────────


class HappyPathCoordinatesTests(unittest.TestCase):
    def test_default_duration(self) -> None:
        with mock.patch.object(android_tool.requests, "post") as post:
            post.return_value = _fake_ok_response({"ok": True, "mode": "gesture"})
            out = json.loads(android_long_press(x=100, y=200))

        self.assertEqual(out, {"ok": True, "mode": "gesture"})
        post.assert_called_once()
        args, kwargs = post.call_args
        self.assertTrue(args[0].endswith("/long_press"))
        body = kwargs["json"]
        self.assertEqual(body["x"], 100)
        self.assertEqual(body["y"], 200)
        self.assertEqual(body["duration"], 500)
        self.assertNotIn("node_id", body)

    def test_custom_duration_within_range(self) -> None:
        with mock.patch.object(android_tool.requests, "post") as post:
            post.return_value = _fake_ok_response()
            json.loads(android_long_press(x=0, y=0, duration=1_500))

        body = post.call_args.kwargs["json"]
        self.assertEqual(body["duration"], 1_500)

    def test_min_duration_accepted(self) -> None:
        with mock.patch.object(android_tool.requests, "post") as post:
            post.return_value = _fake_ok_response()
            json.loads(android_long_press(x=5, y=5, duration=100))
        body = post.call_args.kwargs["json"]
        self.assertEqual(body["duration"], 100)

    def test_max_duration_accepted(self) -> None:
        with mock.patch.object(android_tool.requests, "post") as post:
            post.return_value = _fake_ok_response()
            json.loads(android_long_press(x=5, y=5, duration=3_000))
        body = post.call_args.kwargs["json"]
        self.assertEqual(body["duration"], 3_000)


# ── Happy path — node_id ─────────────────────────────────────────────────────


class HappyPathNodeIdTests(unittest.TestCase):
    def test_node_id_only(self) -> None:
        with mock.patch.object(android_tool.requests, "post") as post:
            post.return_value = _fake_ok_response(
                {"ok": True, "node_id": "com.app:id/menu", "mode": "action_long_click"}
            )
            out = json.loads(android_long_press(node_id="com.app:id/menu"))

        self.assertEqual(out["ok"], True)
        body = post.call_args.kwargs["json"]
        self.assertEqual(body["node_id"], "com.app:id/menu")
        self.assertNotIn("x", body)
        self.assertNotIn("y", body)
        self.assertEqual(body["duration"], 500)

    def test_node_id_with_custom_duration(self) -> None:
        with mock.patch.object(android_tool.requests, "post") as post:
            post.return_value = _fake_ok_response()
            json.loads(
                android_long_press(node_id="com.app:id/row_0", duration=750)
            )
        body = post.call_args.kwargs["json"]
        self.assertEqual(body["duration"], 750)


# ── Argument validation ─────────────────────────────────────────────────────


class ArgumentValidationTests(unittest.TestCase):
    def test_missing_all_args(self) -> None:
        with mock.patch.object(android_tool.requests, "post") as post:
            out = json.loads(android_long_press())
        self.assertIn("error", out)
        self.assertIn("(x, y)", out["error"])
        post.assert_not_called()

    def test_missing_y(self) -> None:
        with mock.patch.object(android_tool.requests, "post") as post:
            out = json.loads(android_long_press(x=100))
        self.assertIn("error", out)
        post.assert_not_called()

    def test_missing_x(self) -> None:
        with mock.patch.object(android_tool.requests, "post") as post:
            out = json.loads(android_long_press(y=200))
        self.assertIn("error", out)
        post.assert_not_called()

    def test_empty_node_id_treated_as_missing(self) -> None:
        with mock.patch.object(android_tool.requests, "post") as post:
            out = json.loads(android_long_press(node_id="   "))
        self.assertIn("error", out)
        post.assert_not_called()

    def test_both_coords_and_node_id_rejected(self) -> None:
        with mock.patch.object(android_tool.requests, "post") as post:
            out = json.loads(
                android_long_press(x=10, y=20, node_id="com.app:id/x")
            )
        self.assertIn("error", out)
        self.assertIn("not both", out["error"])
        post.assert_not_called()


# ── Duration validation ─────────────────────────────────────────────────────


class DurationValidationTests(unittest.TestCase):
    def test_below_minimum(self) -> None:
        with mock.patch.object(android_tool.requests, "post") as post:
            out = json.loads(android_long_press(x=10, y=20, duration=50))
        self.assertIn("error", out)
        self.assertIn("duration", out["error"].lower())
        post.assert_not_called()

    def test_above_maximum(self) -> None:
        with mock.patch.object(android_tool.requests, "post") as post:
            out = json.loads(android_long_press(x=10, y=20, duration=5_000))
        self.assertIn("error", out)
        self.assertIn("duration", out["error"].lower())
        post.assert_not_called()

    def test_zero_duration(self) -> None:
        with mock.patch.object(android_tool.requests, "post") as post:
            out = json.loads(android_long_press(x=10, y=20, duration=0))
        self.assertIn("error", out)
        post.assert_not_called()

    def test_negative_duration(self) -> None:
        with mock.patch.object(android_tool.requests, "post") as post:
            out = json.loads(android_long_press(x=10, y=20, duration=-100))
        self.assertIn("error", out)
        post.assert_not_called()

    def test_non_integer_duration_rejected(self) -> None:
        with mock.patch.object(android_tool.requests, "post") as post:
            out = json.loads(
                android_long_press(x=10, y=20, duration="500")  # type: ignore[arg-type]
            )
        self.assertIn("error", out)
        post.assert_not_called()

    def test_bool_duration_rejected(self) -> None:
        # ``bool`` is a subclass of ``int`` in Python — we explicitly
        # exclude it so ``android_long_press(duration=True)`` doesn't
        # silently become ``duration=1``.
        with mock.patch.object(android_tool.requests, "post") as post:
            out = json.loads(
                android_long_press(x=10, y=20, duration=True)  # type: ignore[arg-type]
            )
        self.assertIn("error", out)
        post.assert_not_called()


# ── HTTP error propagation ──────────────────────────────────────────────────


class HttpErrorTests(unittest.TestCase):
    def test_network_error_surfaces_as_error_field(self) -> None:
        with mock.patch.object(android_tool.requests, "post") as post:
            post.side_effect = RuntimeError("connection refused")
            out = json.loads(android_long_press(x=10, y=20))
        self.assertIn("error", out)
        self.assertIn("connection refused", out["error"])

    def test_http_500_surfaces_as_error_field(self) -> None:
        with mock.patch.object(android_tool.requests, "post") as post:
            post.return_value = _FakeResponse({}, status_code=500)
            out = json.loads(android_long_press(x=10, y=20))
        self.assertIn("error", out)


if __name__ == "__main__":
    unittest.main()
