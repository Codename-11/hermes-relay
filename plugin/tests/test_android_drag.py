"""
Unit tests for ``plugin.tools.android_tool.android_drag`` (v0.4 / A2).

Uses only stdlib ``unittest`` + ``unittest.mock`` so it runs cleanly via::

    python -m unittest plugin.tests.test_android_drag

without pulling in the ``responses`` dependency that ``conftest.py``
imports at collection time (the conftest is pytest-only and is not
loaded by ``unittest``).

Coverage:
  * Happy path — valid drag forwards to ``POST /drag`` on the bridge.
  * Duration clamping — below 100 ms → 100, above 3000 ms → 3000.
  * Validation errors — non-int coords, negative coords, non-int duration,
    boolean-as-int (Python's ``isinstance(True, int)`` footgun).
  * Schema + handler registration sanity.
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path
from unittest import mock

# Make ``import plugin.tools.android_tool`` work when running from the repo
# root without the package being installed. Matches the existing
# ``test_android_navigate.py`` bootstrap.
REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

# android_tool imports ``requests`` at module load time; tests don't hit
# the network because every call path goes through a mocked ``_post``.
from plugin.tools import android_tool as at  # noqa: E402


class TestAndroidDragHappyPath(unittest.TestCase):
    """Valid inputs forward to the bridge and surface its response."""

    def test_basic_drag_forwards_to_bridge(self) -> None:
        fake_response = {"ok": True, "start_x": 100, "start_y": 200,
                         "end_x": 300, "end_y": 400, "duration_ms": 500}
        with mock.patch.object(at, "_post", return_value=fake_response) as post:
            raw = at.android_drag(100, 200, 300, 400)
        result = json.loads(raw)
        self.assertEqual(result["ok"], True)
        self.assertEqual(result["duration_ms"], 500)

        # The tool should have POSTed to /drag with snake_case coords
        # that match the Kotlin BridgeCommandHandler parser.
        post.assert_called_once()
        args, _ = post.call_args
        self.assertEqual(args[0], "/drag")
        payload = args[1]
        self.assertEqual(payload["start_x"], 100)
        self.assertEqual(payload["start_y"], 200)
        self.assertEqual(payload["end_x"], 300)
        self.assertEqual(payload["end_y"], 400)
        self.assertEqual(payload["duration_ms"], 500)

    def test_explicit_duration_passes_through_when_in_range(self) -> None:
        with mock.patch.object(at, "_post", return_value={"ok": True}) as post:
            at.android_drag(0, 0, 10, 10, duration=1200)
        payload = post.call_args.args[1]
        self.assertEqual(payload["duration_ms"], 1200)


class TestAndroidDragDurationClamping(unittest.TestCase):
    """Duration is silently coerced into 100..3000 ms."""

    def test_duration_below_min_clamps_up_to_100(self) -> None:
        with mock.patch.object(at, "_post", return_value={"ok": True}) as post:
            at.android_drag(0, 0, 10, 10, duration=50)
        payload = post.call_args.args[1]
        self.assertEqual(payload["duration_ms"], 100)

    def test_duration_above_max_clamps_down_to_3000(self) -> None:
        with mock.patch.object(at, "_post", return_value={"ok": True}) as post:
            at.android_drag(0, 0, 10, 10, duration=10_000)
        payload = post.call_args.args[1]
        self.assertEqual(payload["duration_ms"], 3000)

    def test_duration_exactly_at_bounds_survives(self) -> None:
        for d in (100, 3000):
            with self.subTest(duration=d):
                with mock.patch.object(
                    at, "_post", return_value={"ok": True}
                ) as post:
                    at.android_drag(0, 0, 10, 10, duration=d)
                self.assertEqual(post.call_args.args[1]["duration_ms"], d)

    def test_zero_duration_clamps_up(self) -> None:
        with mock.patch.object(at, "_post", return_value={"ok": True}) as post:
            at.android_drag(0, 0, 10, 10, duration=0)
        self.assertEqual(post.call_args.args[1]["duration_ms"], 100)

    def test_negative_duration_clamps_up(self) -> None:
        with mock.patch.object(at, "_post", return_value={"ok": True}) as post:
            at.android_drag(0, 0, 10, 10, duration=-500)
        self.assertEqual(post.call_args.args[1]["duration_ms"], 100)


class TestAndroidDragValidation(unittest.TestCase):
    """Bad inputs surface ``{"error": ...}`` without hitting the bridge."""

    def _expect_error(self, *args, **kwargs) -> dict:
        with mock.patch.object(at, "_post") as post:
            raw = at.android_drag(*args, **kwargs)
            post.assert_not_called()
        return json.loads(raw)

    def test_string_coord_is_rejected(self) -> None:
        result = self._expect_error("100", 0, 0, 0)
        self.assertIn("error", result)
        self.assertIn("start_x", result["error"])

    def test_float_coord_is_rejected(self) -> None:
        result = self._expect_error(0, 0, 0, 1.5)
        self.assertIn("error", result)
        self.assertIn("end_y", result["error"])

    def test_boolean_coord_is_rejected(self) -> None:
        # isinstance(True, int) is True in Python — the validator must
        # special-case bool so an agent can't smuggle a truthy value as
        # a coordinate.
        result = self._expect_error(True, 0, 0, 0)
        self.assertIn("error", result)
        self.assertIn("start_x", result["error"])

    def test_negative_start_coord_is_rejected(self) -> None:
        result = self._expect_error(-5, 0, 10, 10)
        self.assertIn("error", result)
        self.assertIn("start_x", result["error"])

    def test_negative_end_coord_is_rejected(self) -> None:
        result = self._expect_error(0, 0, 10, -1)
        self.assertIn("error", result)
        self.assertIn("end_y", result["error"])

    def test_string_duration_is_rejected(self) -> None:
        result = self._expect_error(0, 0, 10, 10, duration="500")
        self.assertIn("error", result)
        self.assertIn("duration", result["error"])

    def test_boolean_duration_is_rejected(self) -> None:
        result = self._expect_error(0, 0, 10, 10, duration=True)
        self.assertIn("error", result)
        self.assertIn("duration", result["error"])

    def test_bridge_exception_surfaces_as_error(self) -> None:
        with mock.patch.object(at, "_post", side_effect=RuntimeError("boom")):
            raw = at.android_drag(0, 0, 10, 10)
        result = json.loads(raw)
        self.assertIn("error", result)
        self.assertIn("boom", result["error"])


class TestAndroidDragRegistration(unittest.TestCase):
    """The tool is wired into the plugin registry alongside its peers."""

    def test_schema_present(self) -> None:
        self.assertIn("android_drag", at._SCHEMAS)
        schema = at._SCHEMAS["android_drag"]
        self.assertEqual(schema["name"], "android_drag")
        required = schema["parameters"]["required"]
        self.assertEqual(
            set(required), {"start_x", "start_y", "end_x", "end_y"}
        )
        # duration is optional but declared on the schema with a default.
        props = schema["parameters"]["properties"]
        self.assertIn("duration", props)
        self.assertEqual(props["duration"]["default"], 500)

    def test_handler_present(self) -> None:
        self.assertIn("android_drag", at._HANDLERS)

    def test_handler_dispatches_kwargs(self) -> None:
        with mock.patch.object(at, "_post", return_value={"ok": True}) as post:
            raw = at._HANDLERS["android_drag"](
                {"start_x": 1, "start_y": 2, "end_x": 3, "end_y": 4}
            )
        self.assertEqual(post.call_args.args[0], "/drag")
        self.assertEqual(json.loads(raw)["ok"], True)

    def test_schema_count_increments(self) -> None:
        # A2 adds exactly one tool. If future waves add more, this
        # assertion needs updating — but catching an accidental double
        # registration or a missing one is worth the churn.
        self.assertEqual(len(at._SCHEMAS), len(at._HANDLERS))


if __name__ == "__main__":
    unittest.main()
