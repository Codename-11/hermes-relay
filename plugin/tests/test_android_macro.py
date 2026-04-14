"""
Unit tests for ``plugin.tools.android_tool.android_macro``.

Uses only stdlib ``unittest`` + ``unittest.mock`` so it runs cleanly via::

    python -m unittest plugin.tests.test_android_macro

without pulling in the ``responses`` dependency that the existing
``conftest.py`` imports at collection time. The conftest is pytest-only and
is not loaded by ``unittest``.

Coverage:
  * Happy path (all steps succeed, full trace returned).
  * Failure on step N (returns partial trace + error + completed=N).
  * Unknown tool name rejection.
  * Empty steps list → immediate success.
  * Missing ``tool`` key → rejection with structured error.
  * ``pace_ms=0`` → no ``time.sleep`` calls.
  * ``pace_ms < 0`` → rejection at entry.
  * Non-dict ``args`` → rejection.
  * Non-list ``steps`` → rejection.
  * Mixed failure signals (``success: False``, ``error`` key, ``status:
    "error"``) all trigger the stop-loop path.
  * ``pace_ms`` between steps sleeps ``(N-1)`` times (not N).
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path
from unittest import mock

# Make `import plugin.tools.android_tool` work when running from the repo
# root without the package being installed.
REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from plugin.tools import android_tool  # noqa: E402
from plugin.tools.android_tool import _HANDLERS, android_macro  # noqa: E402


def _ok(payload: dict | None = None) -> str:
    """Serialize a canonical success response the way android_* tools do."""
    base = {"success": True}
    if payload:
        base.update(payload)
    return json.dumps(base)


def _err(msg: str) -> str:
    return json.dumps({"error": msg})


# ── Happy path ────────────────────────────────────────────────────────────────


class TestHappyPath(unittest.TestCase):
    def test_three_successful_steps(self) -> None:
        tap = mock.Mock(return_value=_ok({"tapped": True}))
        type_ = mock.Mock(return_value=_ok({"typed": "hello"}))
        swipe = mock.Mock(return_value=_ok({"swiped": "up"}))

        with mock.patch.dict(
            _HANDLERS,
            {
                "android_tap": lambda args, **kw: tap(args),
                "android_type": lambda args, **kw: type_(args),
                "android_swipe": lambda args, **kw: swipe(args),
            },
            clear=False,
        ):
            with mock.patch.object(android_tool.time, "sleep") as sleep:
                result = android_macro(
                    steps=[
                        {"tool": "android_tap", "args": {"x": 100, "y": 200}},
                        {"tool": "android_type", "args": {"text": "hello"}},
                        {"tool": "android_swipe", "args": {"direction": "up"}},
                    ],
                    name="demo_macro",
                    pace_ms=500,
                )

        data = json.loads(result)
        self.assertTrue(data["success"], msg=data)
        self.assertEqual(data["name"], "demo_macro")
        self.assertEqual(data["completed"], 3)
        self.assertNotIn("error", data)
        self.assertEqual(len(data["results"]), 3)
        self.assertEqual(data["results"][0]["tool"], "android_tap")
        self.assertEqual(data["results"][0]["result"]["tapped"], True)
        self.assertEqual(data["results"][1]["tool"], "android_type")
        self.assertEqual(data["results"][2]["tool"], "android_swipe")

        # Each underlying handler called once with exactly the args passed in.
        tap.assert_called_once_with({"x": 100, "y": 200})
        type_.assert_called_once_with({"text": "hello"})
        swipe.assert_called_once_with({"direction": "up"})

        # Pacing: N-1 sleeps for N steps at pace_ms=500 → 0.5s each.
        self.assertEqual(sleep.call_count, 2)
        for call_args in sleep.call_args_list:
            self.assertAlmostEqual(call_args.args[0], 0.5, places=6)

    def test_step_without_args_key_passes_empty_dict(self) -> None:
        ping = mock.Mock(return_value=_ok({"pong": True}))

        with mock.patch.dict(
            _HANDLERS,
            {"android_ping": lambda args, **kw: ping(args)},
            clear=False,
        ):
            with mock.patch.object(android_tool.time, "sleep"):
                result = android_macro(steps=[{"tool": "android_ping"}])

        data = json.loads(result)
        self.assertTrue(data["success"])
        self.assertEqual(data["completed"], 1)
        ping.assert_called_once_with({})


# ── Step-N failure ────────────────────────────────────────────────────────────


class TestStepFailure(unittest.TestCase):
    def test_second_step_fails_stops_loop(self) -> None:
        step1 = mock.Mock(return_value=_ok({"opened": True}))
        step2 = mock.Mock(return_value=_err("element not found"))
        step3 = mock.Mock(return_value=_ok())  # should NEVER be called

        with mock.patch.dict(
            _HANDLERS,
            {
                "android_open_app": lambda args, **kw: step1(args),
                "android_tap_text": lambda args, **kw: step2(args),
                "android_type": lambda args, **kw: step3(args),
            },
            clear=False,
        ):
            with mock.patch.object(android_tool.time, "sleep"):
                result = android_macro(
                    steps=[
                        {"tool": "android_open_app", "args": {"package": "com.example"}},
                        {"tool": "android_tap_text", "args": {"text": "Search"}},
                        {"tool": "android_type", "args": {"text": "never reached"}},
                    ],
                    name="failing_macro",
                )

        data = json.loads(result)
        self.assertFalse(data["success"])
        self.assertEqual(data["name"], "failing_macro")
        self.assertEqual(data["completed"], 1)  # step index of the failure
        self.assertIn("error", data)
        self.assertIn("android_tap_text", data["error"])
        self.assertIn("element not found", data["error"])

        # Results includes BOTH the first (successful) step AND the failed
        # step so the trace shows exactly where we stopped.
        self.assertEqual(len(data["results"]), 2)
        self.assertEqual(data["results"][0]["tool"], "android_open_app")
        self.assertEqual(data["results"][1]["tool"], "android_tap_text")

        step1.assert_called_once()
        step2.assert_called_once()
        step3.assert_not_called()

    def test_success_false_signal_triggers_failure(self) -> None:
        step1 = mock.Mock(return_value=_ok())
        step2 = mock.Mock(
            return_value=json.dumps({"success": False, "message": "timed out"})
        )

        with mock.patch.dict(
            _HANDLERS,
            {
                "android_ping": lambda args, **kw: step1(args),
                "android_wait": lambda args, **kw: step2(args),
            },
            clear=False,
        ):
            with mock.patch.object(android_tool.time, "sleep"):
                result = android_macro(
                    steps=[
                        {"tool": "android_ping"},
                        {"tool": "android_wait", "args": {"text": "Done"}},
                    ]
                )

        data = json.loads(result)
        self.assertFalse(data["success"])
        self.assertEqual(data["completed"], 1)
        self.assertIn("timed out", data["error"])

    def test_status_error_signal_triggers_failure(self) -> None:
        step1 = mock.Mock(
            return_value=json.dumps({"status": "error", "message": "relay down"})
        )

        with mock.patch.dict(
            _HANDLERS,
            {"android_ping": lambda args, **kw: step1(args)},
            clear=False,
        ):
            with mock.patch.object(android_tool.time, "sleep"):
                result = android_macro(steps=[{"tool": "android_ping"}])

        data = json.loads(result)
        self.assertFalse(data["success"])
        self.assertEqual(data["completed"], 0)
        self.assertIn("relay down", data["error"])


# ── Unknown tool name ─────────────────────────────────────────────────────────


class TestUnknownTool(unittest.TestCase):
    def test_unknown_tool_rejected_before_any_call(self) -> None:
        # Ensure `android_does_not_exist` is not in _HANDLERS.
        self.assertNotIn("android_does_not_exist", _HANDLERS)

        real_tap = mock.Mock(return_value=_ok())
        with mock.patch.dict(
            _HANDLERS,
            {"android_tap": lambda args, **kw: real_tap(args)},
            clear=False,
        ):
            with mock.patch.object(android_tool.time, "sleep"):
                result = android_macro(
                    steps=[
                        {"tool": "android_does_not_exist", "args": {}},
                        {"tool": "android_tap", "args": {"x": 1, "y": 2}},
                    ]
                )

        data = json.loads(result)
        self.assertFalse(data["success"])
        self.assertEqual(data["completed"], 0)
        self.assertIn("unknown tool", data["error"])
        self.assertIn("android_does_not_exist", data["error"])
        self.assertEqual(data["results"], [])
        real_tap.assert_not_called()


# ── Empty steps list ──────────────────────────────────────────────────────────


class TestEmptySteps(unittest.TestCase):
    def test_empty_list_is_immediate_success(self) -> None:
        with mock.patch.object(android_tool.time, "sleep") as sleep:
            result = android_macro(steps=[], name="nothing")

        data = json.loads(result)
        self.assertTrue(data["success"])
        self.assertEqual(data["name"], "nothing")
        self.assertEqual(data["completed"], 0)
        self.assertEqual(data["results"], [])
        sleep.assert_not_called()


# ── Malformed step ────────────────────────────────────────────────────────────


class TestMalformedStep(unittest.TestCase):
    def test_missing_tool_key_rejected(self) -> None:
        with mock.patch.object(android_tool.time, "sleep"):
            result = android_macro(steps=[{"args": {"x": 1}}])

        data = json.loads(result)
        self.assertFalse(data["success"])
        self.assertEqual(data["completed"], 0)
        self.assertIn("missing 'tool' key", data["error"])

    def test_non_dict_step_rejected(self) -> None:
        with mock.patch.object(android_tool.time, "sleep"):
            result = android_macro(steps=["not a dict"])  # type: ignore[list-item]

        data = json.loads(result)
        self.assertFalse(data["success"])
        self.assertEqual(data["completed"], 0)
        self.assertIn("must be a dict", data["error"])

    def test_non_dict_args_rejected(self) -> None:
        with mock.patch.dict(
            _HANDLERS,
            {"android_tap": lambda args, **kw: _ok()},
            clear=False,
        ):
            with mock.patch.object(android_tool.time, "sleep"):
                result = android_macro(
                    steps=[{"tool": "android_tap", "args": "bad"}]
                )

        data = json.loads(result)
        self.assertFalse(data["success"])
        self.assertEqual(data["completed"], 0)
        self.assertIn("'args' must be a dict", data["error"])

    def test_non_list_steps_rejected(self) -> None:
        result = android_macro(steps="not a list")  # type: ignore[arg-type]
        data = json.loads(result)
        self.assertFalse(data["success"])
        self.assertIn("steps must be a list", data["error"])


# ── Pacing ────────────────────────────────────────────────────────────────────


class TestPacing(unittest.TestCase):
    def test_pace_zero_does_not_sleep(self) -> None:
        step = mock.Mock(return_value=_ok())

        with mock.patch.dict(
            _HANDLERS,
            {"android_ping": lambda args, **kw: step(args)},
            clear=False,
        ):
            with mock.patch.object(android_tool.time, "sleep") as sleep:
                result = android_macro(
                    steps=[
                        {"tool": "android_ping"},
                        {"tool": "android_ping"},
                        {"tool": "android_ping"},
                    ],
                    pace_ms=0,
                )

        data = json.loads(result)
        self.assertTrue(data["success"])
        self.assertEqual(data["completed"], 3)
        sleep.assert_not_called()

    def test_negative_pace_rejected(self) -> None:
        with mock.patch.object(android_tool.time, "sleep") as sleep:
            result = android_macro(steps=[], pace_ms=-1)

        data = json.loads(result)
        self.assertFalse(data["success"])
        self.assertIn("pace_ms must be >= 0", data["error"])
        sleep.assert_not_called()

    def test_pace_sleeps_n_minus_one_times(self) -> None:
        step = mock.Mock(return_value=_ok())

        with mock.patch.dict(
            _HANDLERS,
            {"android_ping": lambda args, **kw: step(args)},
            clear=False,
        ):
            with mock.patch.object(android_tool.time, "sleep") as sleep:
                android_macro(
                    steps=[{"tool": "android_ping"}] * 5,
                    pace_ms=250,
                )

        # 5 steps → 4 sleeps (none after the last).
        self.assertEqual(sleep.call_count, 4)
        for call_args in sleep.call_args_list:
            self.assertAlmostEqual(call_args.args[0], 0.25, places=6)


# ── Schema + registry sanity ─────────────────────────────────────────────────


class TestSchemaRegistered(unittest.TestCase):
    def test_macro_has_schema_entry(self) -> None:
        self.assertIn("android_macro", android_tool._SCHEMAS)
        schema = android_tool._SCHEMAS["android_macro"]
        self.assertEqual(schema["name"], "android_macro")
        self.assertIn("description", schema)
        self.assertIn("steps", schema["parameters"]["properties"])
        self.assertIn("steps", schema["parameters"]["required"])

    def test_macro_has_handler_entry(self) -> None:
        self.assertIn("android_macro", _HANDLERS)

    def test_schema_and_handler_keys_match(self) -> None:
        self.assertEqual(
            set(android_tool._SCHEMAS.keys()),
            set(_HANDLERS.keys()),
        )


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
