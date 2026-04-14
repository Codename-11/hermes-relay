"""
Unit tests for the A9 three-tier ``tapText`` fallback cascade, verified
from the Python tool side.

The cascade itself lives in Kotlin (``ActionExecutor.tapText``) and is
integration-tested against a live phone. On the Python side we only
need to confirm that:

  1. ``android_tap_text`` forwards text + exact flag to ``/tap_text`` on
     the bridge correctly.
  2. Each of the three possible success envelopes the phone can return
     (direct click / parent click / coordinate fallback) round-trips
     through ``android_tap_text`` as a parseable JSON blob with the
     ``via`` + ``message`` fields intact.
  3. The "no match" failure envelope is passed through untouched.

Uses only stdlib ``unittest`` + ``unittest.mock`` so it runs cleanly
via::

    python -m unittest plugin.tests.test_android_tap_text_cascade

without pulling in the ``responses`` dependency that the existing
``conftest.py`` imports at collection time (the conftest is pytest-only
and is not loaded by ``unittest``).
"""

from __future__ import annotations

import json
import re
import sys
import unittest
from pathlib import Path
from unittest import mock

# Make ``import plugin.tools.android_tool`` work when the test runs from
# the repo root without the package being installed. Matches the sys.path
# hack in test_android_navigate.py.
REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

# ``plugin.tools.android_tool`` pulls in ``requests`` at import time, but
# nothing else at module scope touches the network — the _post helper is
# only called when a handler runs, and we mock it below.
from plugin.tools import android_tool  # noqa: E402


# Regexes matching the exact message formats emitted by the Kotlin
# cascade. These are the strings a human reads in the Bridge activity
# log, so if either side drifts, these tests break loudly.
RE_DIRECT = re.compile(r"^tapped via direct click$")
RE_PARENT = re.compile(r"^tapped via parent click \(\d+ levels? up\)$")
RE_COORD = re.compile(r"^tapped via coordinate fallback at \(-?\d+, -?\d+\)$")


class _FakeBridge:
    """Tiny helper to capture the last ``_post`` call for assertions."""

    def __init__(self, reply: dict) -> None:
        self.reply = reply
        self.last_path: str | None = None
        self.last_payload: dict | None = None

    def __call__(self, path: str, payload: dict) -> dict:
        self.last_path = path
        self.last_payload = payload
        return self.reply


class TestTapTextForward(unittest.TestCase):
    """``android_tap_text`` should forward args to /tap_text verbatim."""

    def test_default_exact_false(self) -> None:
        fake = _FakeBridge({"ok": True, "via": "direct click"})
        with mock.patch.object(android_tool, "_post", side_effect=fake):
            raw = android_tool.android_tap_text("Continue")
        self.assertEqual(fake.last_path, "/tap_text")
        self.assertEqual(fake.last_payload, {"text": "Continue", "exact": False})
        parsed = json.loads(raw)
        self.assertTrue(parsed["ok"])

    def test_exact_true(self) -> None:
        fake = _FakeBridge({"ok": True, "via": "direct click"})
        with mock.patch.object(android_tool, "_post", side_effect=fake):
            android_tool.android_tap_text("Send", exact=True)
        self.assertEqual(fake.last_payload, {"text": "Send", "exact": True})

    def test_unicode_text(self) -> None:
        """Non-ASCII text must survive the JSON round trip."""
        fake = _FakeBridge({"ok": True, "via": "direct click"})
        with mock.patch.object(android_tool, "_post", side_effect=fake):
            android_tool.android_tap_text("送信")
        self.assertEqual(fake.last_payload["text"], "送信")


class TestTierOneDirectClick(unittest.TestCase):
    """Tier 1: the matched node is itself clickable."""

    def test_direct_click_envelope(self) -> None:
        reply = {
            "ok": True,
            "via": "direct click",
            "message": "tapped via direct click",
            "depth": 0,
            "needle": "Continue",
        }
        with mock.patch.object(android_tool, "_post", return_value=reply):
            raw = android_tool.android_tap_text("Continue")
        parsed = json.loads(raw)
        self.assertTrue(parsed["ok"])
        self.assertEqual(parsed["via"], "direct click")
        self.assertRegex(parsed["message"], RE_DIRECT)
        self.assertEqual(parsed["depth"], 0)


class TestTierTwoParentWalk(unittest.TestCase):
    """Tier 2: walk the parent chain to find a clickable ancestor."""

    def test_parent_click_one_level_up(self) -> None:
        reply = {
            "ok": True,
            "via": "parent click (1 levels up)",
            "message": "tapped via parent click (1 levels up)",
            "depth": 1,
            "needle": "Book ride",
        }
        with mock.patch.object(android_tool, "_post", return_value=reply):
            raw = android_tool.android_tap_text("Book ride")
        parsed = json.loads(raw)
        self.assertTrue(parsed["ok"])
        self.assertRegex(parsed["message"], RE_PARENT)
        self.assertEqual(parsed["depth"], 1)

    def test_parent_click_deep(self) -> None:
        """PARENT_WALK_MAX - 1 = 7 levels up is the deepest Tier 2 ever gets."""
        reply = {
            "ok": True,
            "via": "parent click (7 levels up)",
            "message": "tapped via parent click (7 levels up)",
            "depth": 7,
            "needle": "Play",
        }
        with mock.patch.object(android_tool, "_post", return_value=reply):
            raw = android_tool.android_tap_text("Play")
        parsed = json.loads(raw)
        self.assertTrue(parsed["ok"])
        self.assertRegex(parsed["message"], RE_PARENT)
        self.assertEqual(parsed["depth"], 7)


class TestTierThreeCoordinateFallback(unittest.TestCase):
    """Tier 3: no clickable ancestor found, tap at bounds center."""

    def test_coordinate_fallback_envelope(self) -> None:
        reply = {
            "ok": True,
            "via": "coordinate fallback",
            "message": "tapped via coordinate fallback at (540, 1122)",
            "x": 540,
            "y": 1122,
            "needle": "Match",
        }
        with mock.patch.object(android_tool, "_post", return_value=reply):
            raw = android_tool.android_tap_text("Match")
        parsed = json.loads(raw)
        self.assertTrue(parsed["ok"])
        self.assertEqual(parsed["via"], "coordinate fallback")
        self.assertRegex(parsed["message"], RE_COORD)
        self.assertEqual(parsed["x"], 540)
        self.assertEqual(parsed["y"], 1122)

    def test_message_regexes_are_mutually_exclusive(self) -> None:
        """Guard against a future refactor collapsing tier identifiers."""
        direct = "tapped via direct click"
        parent = "tapped via parent click (3 levels up)"
        coord = "tapped via coordinate fallback at (100, 200)"
        self.assertRegex(direct, RE_DIRECT)
        self.assertNotRegex(direct, RE_PARENT)
        self.assertNotRegex(direct, RE_COORD)
        self.assertRegex(parent, RE_PARENT)
        self.assertNotRegex(parent, RE_DIRECT)
        self.assertNotRegex(parent, RE_COORD)
        self.assertRegex(coord, RE_COORD)
        self.assertNotRegex(coord, RE_DIRECT)
        self.assertNotRegex(coord, RE_PARENT)


class TestNotFoundFailure(unittest.TestCase):
    """No node matched → the phone's failure envelope is returned as-is."""

    def test_no_node_found_passthrough(self) -> None:
        # The Kotlin side returns this as an ActionResult.failure which
        # BridgeCommandHandler translates to an HTTP 400-ish body. The
        # Python tool doesn't inspect the ``ok`` field — whatever the
        # bridge says, it JSON-re-encodes and returns.
        reply = {"ok": False, "error": "no node found matching text: Nonexistent"}
        with mock.patch.object(android_tool, "_post", return_value=reply):
            raw = android_tool.android_tap_text("Nonexistent")
        parsed = json.loads(raw)
        self.assertFalse(parsed["ok"])
        self.assertIn("no node found matching text", parsed["error"])
        self.assertIn("Nonexistent", parsed["error"])

    def test_http_exception_wrapped_as_error(self) -> None:
        """Bridge unreachable → android_tap_text returns ``{"error": ...}``."""
        class Boom(Exception):
            pass

        with mock.patch.object(android_tool, "_post", side_effect=Boom("connection refused")):
            raw = android_tool.android_tap_text("Continue")
        parsed = json.loads(raw)
        self.assertIn("error", parsed)
        self.assertIn("connection refused", parsed["error"])


class TestSchemaIntegrity(unittest.TestCase):
    """A9 must not reshape the tool schema — the tool call surface is frozen."""

    def test_android_tap_text_still_registered(self) -> None:
        self.assertIn("android_tap_text", android_tool._SCHEMAS)
        self.assertIn("android_tap_text", android_tool._HANDLERS)

    def test_schema_params_unchanged(self) -> None:
        schema = android_tool._SCHEMAS["android_tap_text"]
        self.assertEqual(schema["name"], "android_tap_text")
        # The parameters block must still accept the `text` field; A9 is
        # a phone-side cascade, not a wire-protocol change.
        params = schema["parameters"]
        properties = params.get("properties", {})
        self.assertIn("text", properties)


if __name__ == "__main__":
    unittest.main()
