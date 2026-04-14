"""
Unit tests for A6 clipboard bridge tools (``android_clipboard_read`` and
``android_clipboard_write``).

Uses stdlib ``unittest`` + ``unittest.mock`` so it runs cleanly via::

    python -m unittest plugin.tests.test_android_clipboard

without pulling in the ``responses`` dependency that the existing
``conftest.py`` imports at collection time. The conftest is pytest-only
and is not loaded by ``unittest``.

Coverage:
  * ``android_clipboard_read`` — happy path (non-empty text), empty
    clipboard (returns ``{"text": ""}`` successfully, NOT an error),
    transport failure (exception surfaced as ``{"error": ...}``).
  * ``android_clipboard_write`` — happy path, empty-string write
    (allowed, effectively clears the clipboard), transport failure.
  * Schema sanity — both tools are registered with the expected
    parameter shape.
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path
from unittest import mock

# Make ``plugin.tools.android_tool`` importable when the test runs from
# the repo root without the package being installed.
REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

# Import from the canonical location so the registry registration path
# (under ``tools.registry``) is exercised as a no-op ImportError — exactly
# like android_phone_status tests do.
from plugin.tools import android_tool  # noqa: E402


# ── android_clipboard_read ───────────────────────────────────────────────────


class TestClipboardRead(unittest.TestCase):
    def test_read_happy(self) -> None:
        """Non-empty clipboard round-trips the text through the tool."""
        with mock.patch.object(
            android_tool,
            "_get",
            return_value={"text": "hello world"},
        ) as mock_get:
            out = android_tool.android_clipboard_read()
        mock_get.assert_called_once_with("/clipboard")
        parsed = json.loads(out)
        self.assertEqual(parsed, {"text": "hello world"})
        self.assertNotIn("error", parsed)

    def test_read_empty(self) -> None:
        """Empty clipboard is NOT an error — it's a legitimate state."""
        with mock.patch.object(
            android_tool,
            "_get",
            return_value={"text": ""},
        ):
            out = android_tool.android_clipboard_read()
        parsed = json.loads(out)
        self.assertEqual(parsed, {"text": ""})
        self.assertNotIn("error", parsed)

    def test_read_transport_error(self) -> None:
        """Network / relay failures surface as {"error": ...}."""
        with mock.patch.object(
            android_tool,
            "_get",
            side_effect=RuntimeError("connection refused"),
        ):
            out = android_tool.android_clipboard_read()
        parsed = json.loads(out)
        self.assertIn("error", parsed)
        self.assertIn("connection refused", parsed["error"])


# ── android_clipboard_write ──────────────────────────────────────────────────


class TestClipboardWrite(unittest.TestCase):
    def test_write_happy(self) -> None:
        """Writing non-empty text succeeds."""
        with mock.patch.object(
            android_tool,
            "_post",
            return_value={"success": True, "length": 11},
        ) as mock_post:
            out = android_tool.android_clipboard_write("hello world")
        mock_post.assert_called_once_with("/clipboard", {"text": "hello world"})
        parsed = json.loads(out)
        self.assertEqual(parsed.get("success"), True)
        self.assertNotIn("error", parsed)

    def test_write_empty_string(self) -> None:
        """Empty-string writes are allowed (effectively clears clipboard)."""
        with mock.patch.object(
            android_tool,
            "_post",
            return_value={"success": True, "length": 0},
        ) as mock_post:
            out = android_tool.android_clipboard_write("")
        mock_post.assert_called_once_with("/clipboard", {"text": ""})
        parsed = json.loads(out)
        self.assertEqual(parsed.get("success"), True)
        self.assertNotIn("error", parsed)

    def test_write_transport_error(self) -> None:
        """Network / relay failures surface as {"error": ...}."""
        with mock.patch.object(
            android_tool,
            "_post",
            side_effect=RuntimeError("bridge offline"),
        ):
            out = android_tool.android_clipboard_write("anything")
        parsed = json.loads(out)
        self.assertIn("error", parsed)
        self.assertIn("bridge offline", parsed["error"])


# ── Schema sanity ────────────────────────────────────────────────────────────


class TestClipboardSchemas(unittest.TestCase):
    def test_both_tools_registered(self) -> None:
        self.assertIn("android_clipboard_read", android_tool._SCHEMAS)
        self.assertIn("android_clipboard_write", android_tool._SCHEMAS)
        self.assertIn("android_clipboard_read", android_tool._HANDLERS)
        self.assertIn("android_clipboard_write", android_tool._HANDLERS)

    def test_read_schema_takes_no_args(self) -> None:
        schema = android_tool._SCHEMAS["android_clipboard_read"]
        self.assertEqual(schema["parameters"]["required"], [])
        self.assertEqual(schema["parameters"]["properties"], {})

    def test_write_schema_requires_text(self) -> None:
        schema = android_tool._SCHEMAS["android_clipboard_write"]
        self.assertEqual(schema["parameters"]["required"], ["text"])
        self.assertIn("text", schema["parameters"]["properties"])
        self.assertEqual(
            schema["parameters"]["properties"]["text"]["type"], "string"
        )

    def test_descriptions_mention_android_12_toast(self) -> None:
        """LLM needs to know the system shows a privacy toast on API 31+."""
        read_desc = android_tool._SCHEMAS["android_clipboard_read"]["description"]
        write_desc = android_tool._SCHEMAS["android_clipboard_write"]["description"]
        self.assertIn("Android 12", read_desc)
        self.assertIn("Android 12", write_desc)


if __name__ == "__main__":
    unittest.main()
