"""
Unit tests for ``android_events`` + ``android_event_stream``.

Uses stdlib ``unittest`` + ``unittest.mock`` exclusively so the tests
run via::

    python -m unittest plugin.tests.test_android_events

without pulling in the ``responses`` dependency the existing
``conftest.py`` imports at collection time. ``unittest`` doesn't load
``conftest.py`` so there's no collision.

Coverage:
  * ``android_events`` happy path with default + explicit arguments.
  * ``android_events`` limit clamping (>500 → 500, <1 → 50).
  * ``android_events`` ``since`` parameter is forwarded on the URL.
  * ``android_events`` error envelope on bridge failure.
  * ``android_event_stream(True)`` + ``(False)`` happy paths.
  * ``android_event_stream`` rejects non-boolean ``enabled`` with a
    clean error envelope (no HTTP call).
  * Schema + handler registration sanity.
  * ``_SCHEMAS`` / ``_HANDLERS`` count bumped to include the two new
    tools.
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path
from unittest import mock

# Make ``import plugin.tools.android_tool`` work when the test runs from
# the repo root without the package being installed. The worktree layout
# already puts everything under ``plugin/``.
REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

# android_tool imports ``requests`` at module scope. Running on Windows
# without the server venv we may not have requests installed, so stub
# it out before the import if needed. In CI/server envs it's a no-op.
if "requests" not in sys.modules:
    try:
        import requests  # noqa: F401
    except ImportError:
        sys.modules["requests"] = mock.MagicMock()

from plugin.tools import android_tool  # noqa: E402
from plugin.tools.android_tool import (  # noqa: E402
    android_events,
    android_event_stream,
    _HANDLERS,
    _SCHEMAS,
)


# ── android_events ─────────────────────────────────────────────────────────


class TestAndroidEventsHappyPath(unittest.TestCase):
    def _fake_get_response(self, entries: list | None = None, streaming: bool = True) -> dict:
        return {
            "entries": entries
            or [
                {
                    "timestamp": 1_700_000_000_000,
                    "event_type": "click",
                    "package_name": "com.example",
                    "class_name": "android.widget.Button",
                    "text": "Submit",
                    "source": "TYPE_VIEW_CLICKED",
                }
            ],
            "count": 1,
            "streaming": streaming,
        }

    def test_happy_default_args(self) -> None:
        with mock.patch.object(
            android_tool, "_get", return_value=self._fake_get_response()
        ) as mget:
            raw = android_events()
            parsed = json.loads(raw)
            self.assertEqual(parsed["status"], "ok")
            self.assertEqual(parsed["count"], 1)
            self.assertEqual(parsed["entries"][0]["event_type"], "click")
            # Default is limit=50, since=0.
            mget.assert_called_once_with("/events?limit=50&since=0")

    def test_happy_explicit_limit(self) -> None:
        with mock.patch.object(
            android_tool, "_get", return_value=self._fake_get_response()
        ) as mget:
            android_events(limit=10)
            mget.assert_called_once_with("/events?limit=10&since=0")

    def test_happy_since_forwarded(self) -> None:
        with mock.patch.object(
            android_tool, "_get", return_value=self._fake_get_response()
        ) as mget:
            android_events(limit=10, since=1_234_567_890)
            mget.assert_called_once_with("/events?limit=10&since=1234567890")

    def test_limit_clamps_above_500(self) -> None:
        with mock.patch.object(
            android_tool, "_get", return_value=self._fake_get_response()
        ) as mget:
            android_events(limit=600)
            mget.assert_called_once_with("/events?limit=500&since=0")

    def test_limit_clamps_below_1_to_default(self) -> None:
        with mock.patch.object(
            android_tool, "_get", return_value=self._fake_get_response()
        ) as mget:
            android_events(limit=0)
            mget.assert_called_once_with("/events?limit=50&since=0")

    def test_limit_non_int_resets_to_default(self) -> None:
        with mock.patch.object(
            android_tool, "_get", return_value=self._fake_get_response()
        ) as mget:
            android_events(limit="garbage")  # type: ignore[arg-type]
            mget.assert_called_once_with("/events?limit=50&since=0")

    def test_since_negative_resets_to_zero(self) -> None:
        with mock.patch.object(
            android_tool, "_get", return_value=self._fake_get_response()
        ) as mget:
            android_events(limit=10, since=-42)
            mget.assert_called_once_with("/events?limit=10&since=0")

    def test_empty_entries_returns_ok_with_count_zero(self) -> None:
        with mock.patch.object(
            android_tool,
            "_get",
            return_value={"entries": [], "count": 0, "streaming": False},
        ):
            parsed = json.loads(android_events())
            self.assertEqual(parsed["status"], "ok")
            self.assertEqual(parsed["count"], 0)
            self.assertEqual(parsed["entries"], [])
            self.assertFalse(parsed["streaming"])

    def test_bridge_exception_produces_error_envelope(self) -> None:
        with mock.patch.object(
            android_tool, "_get", side_effect=RuntimeError("boom")
        ):
            parsed = json.loads(android_events())
            self.assertEqual(parsed["status"], "error")
            self.assertIn("boom", parsed["message"])


# ── android_event_stream ───────────────────────────────────────────────────


class TestAndroidEventStream(unittest.TestCase):
    def test_enable_happy_path(self) -> None:
        with mock.patch.object(
            android_tool,
            "_post",
            return_value={"streaming": True, "buffer_cleared": True},
        ) as mpost:
            parsed = json.loads(android_event_stream(True))
            self.assertEqual(parsed["status"], "ok")
            self.assertTrue(parsed["streaming"])
            self.assertTrue(parsed["buffer_cleared"])
            mpost.assert_called_once_with("/events/stream", {"enabled": True})

    def test_disable_happy_path(self) -> None:
        with mock.patch.object(
            android_tool,
            "_post",
            return_value={"streaming": False, "buffer_cleared": True},
        ) as mpost:
            parsed = json.loads(android_event_stream(False))
            self.assertEqual(parsed["status"], "ok")
            self.assertFalse(parsed["streaming"])
            self.assertTrue(parsed["buffer_cleared"])
            mpost.assert_called_once_with("/events/stream", {"enabled": False})

    def test_string_enabled_is_rejected_before_http(self) -> None:
        # Critical: passing "yes" must NOT silently enable streaming.
        with mock.patch.object(android_tool, "_post") as mpost:
            parsed = json.loads(android_event_stream("yes"))  # type: ignore[arg-type]
            self.assertEqual(parsed["status"], "error")
            self.assertIn("boolean", parsed["message"])
            mpost.assert_not_called()

    def test_int_enabled_is_rejected_before_http(self) -> None:
        with mock.patch.object(android_tool, "_post") as mpost:
            parsed = json.loads(android_event_stream(1))  # type: ignore[arg-type]
            self.assertEqual(parsed["status"], "error")
            mpost.assert_not_called()

    def test_none_enabled_is_rejected_before_http(self) -> None:
        with mock.patch.object(android_tool, "_post") as mpost:
            parsed = json.loads(android_event_stream(None))  # type: ignore[arg-type]
            self.assertEqual(parsed["status"], "error")
            mpost.assert_not_called()

    def test_bridge_exception_produces_error_envelope(self) -> None:
        with mock.patch.object(
            android_tool, "_post", side_effect=RuntimeError("relay down")
        ):
            parsed = json.loads(android_event_stream(True))
            self.assertEqual(parsed["status"], "error")
            self.assertIn("relay down", parsed["message"])


# ── Schema / handler registration ──────────────────────────────────────────


class TestSchemaRegistration(unittest.TestCase):
    def test_events_schema_present(self) -> None:
        self.assertIn("android_events", _SCHEMAS)
        schema = _SCHEMAS["android_events"]
        self.assertEqual(schema["name"], "android_events")
        self.assertIn("description", schema)
        self.assertIn("Privacy-sensitive", schema["description"])

        params = schema["parameters"]["properties"]
        self.assertIn("limit", params)
        self.assertIn("since", params)
        self.assertEqual(params["limit"]["type"], "integer")
        self.assertEqual(params["since"]["type"], "integer")

    def test_event_stream_schema_present(self) -> None:
        self.assertIn("android_event_stream", _SCHEMAS)
        schema = _SCHEMAS["android_event_stream"]
        self.assertEqual(schema["name"], "android_event_stream")
        self.assertIn("Privacy-sensitive", schema["description"])

        params = schema["parameters"]["properties"]
        self.assertIn("enabled", params)
        self.assertEqual(params["enabled"]["type"], "boolean")
        self.assertIn("enabled", schema["parameters"]["required"])

    def test_handlers_wired(self) -> None:
        self.assertIn("android_events", _HANDLERS)
        self.assertIn("android_event_stream", _HANDLERS)
        # The lambdas take (args, **kw); smoke-check they're callable.
        self.assertTrue(callable(_HANDLERS["android_events"]))
        self.assertTrue(callable(_HANDLERS["android_event_stream"]))

    def test_schema_and_handler_keys_align(self) -> None:
        self.assertEqual(set(_SCHEMAS.keys()), set(_HANDLERS.keys()))

    def test_tool_count_includes_new_tools(self) -> None:
        # B1 agent bumps the tool count from 14 (v0.3.0 baseline) to 16.
        # Wave 2 branches may have bumped this further on their own
        # branches — we bound the check at >= 16 so a future rebase that
        # adds more tools doesn't break us, while still failing fast if
        # anyone accidentally drops android_events / android_event_stream.
        self.assertGreaterEqual(len(_SCHEMAS), 16)
        self.assertGreaterEqual(len(_HANDLERS), 16)


if __name__ == "__main__":
    unittest.main()
