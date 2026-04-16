"""
Unit tests for ``plugin.tools.android_tool.android_call`` (Tier C3).

Stdlib ``unittest`` + ``unittest.mock`` only. Run via::

    python -m unittest plugin.tests.test_android_call

Coverage:
  * happy path auto-dial (sideload — mode="auto_dial").
  * happy path dialer fallback (googlePlay or no-permission — mode="dialer_opened").
  * user denied destructive confirmation — bridge returns 403.
  * invalid number format.
  * no dialer installed.
  * schema registration sanity.

Note: the phone flavor decision (auto_dial vs dialer_opened) happens on the
phone, not in the Python tool. From the tool's perspective it just POSTs
``/call`` with the number and echoes whatever the bridge returns.
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


class TestAndroidCallHappyPath(unittest.TestCase):
    def test_auto_dial_mode(self) -> None:
        fake = {"number": "+15551234567", "mode": "auto_dial"}
        with mock.patch.object(android_tool, "_post", return_value=fake) as m:
            result = json.loads(android_tool.android_call("+15551234567"))
        self.assertEqual(result["mode"], "auto_dial")
        self.assertEqual(result["number"], "+15551234567")
        args = m.call_args.args
        kwargs = m.call_args.kwargs
        sent_path = args[0] if args else kwargs["path"]
        sent_body = args[1] if len(args) > 1 else kwargs.get("payload", {})
        self.assertEqual(sent_path, "/call")
        self.assertEqual(sent_body["number"], "+15551234567")

    def test_dialer_fallback_mode(self) -> None:
        """googlePlay flavor (or permission-denied) opens the dialer."""
        fake = {
            "number": "555-1234",
            "mode": "dialer_opened",
            "note": "opened dialer — user must tap Call manually",
        }
        with mock.patch.object(android_tool, "_post", return_value=fake):
            result = json.loads(android_tool.android_call("555-1234"))
        self.assertEqual(result["mode"], "dialer_opened")
        self.assertIn("note", result)


class TestAndroidCallDenied(unittest.TestCase):
    def test_user_denied_confirmation(self) -> None:
        """Phone safety-rails destructive-verb modal was denied."""
        fake = {
            "error": "user denied destructive action",
            "reason": "confirmation_denied_or_timeout",
        }
        with mock.patch.object(android_tool, "_post", return_value=fake):
            result = json.loads(android_tool.android_call("+15551234567"))
        self.assertIn("error", result)
        self.assertEqual(result["reason"], "confirmation_denied_or_timeout")

    def test_invalid_number_passthrough(self) -> None:
        fake = {"error": "call: number contains invalid characters"}
        with mock.patch.object(android_tool, "_post", return_value=fake):
            result = json.loads(android_tool.android_call("abcdef"))
        self.assertIn("error", result)
        self.assertIn("invalid", result["error"])


class TestAndroidCallInfrastructure(unittest.TestCase):
    def test_no_dialer_installed(self) -> None:
        fake = {"error": "no dialer installed"}
        with mock.patch.object(android_tool, "_post", return_value=fake):
            result = json.loads(android_tool.android_call("+15551234567"))
        self.assertIn("no dialer", result["error"])

    def test_network_error(self) -> None:
        with mock.patch.object(
            android_tool, "_post", side_effect=ConnectionError("relay down")
        ):
            result = json.loads(android_tool.android_call("+15551234567"))
        self.assertIn("error", result)
        self.assertIn("relay down", result["error"])

    def test_sideload_only_on_googleplay(self) -> None:
        """googlePlay build where auto-dial is forbidden — bridge rejects at handler."""
        fake = {"error": "android_call auto-dial is sideload-only"}
        with mock.patch.object(android_tool, "_post", return_value=fake):
            result = json.loads(android_tool.android_call("+15551234567"))
        self.assertIn("sideload-only", result["error"])


class TestAndroidCallSchema(unittest.TestCase):
    def test_registered(self) -> None:
        self.assertIn("android_call", android_tool._SCHEMAS)
        self.assertIn("android_call", android_tool._HANDLERS)

    def test_schema_shape(self) -> None:
        schema = android_tool._SCHEMAS["android_call"]
        self.assertEqual(schema["name"], "android_call")
        self.assertIn("confirmation", schema["description"].lower())
        self.assertIn("number", schema["parameters"]["properties"])
        self.assertIn("number", schema["parameters"]["required"])

    def test_handler_dispatch(self) -> None:
        fake = {"number": "x", "mode": "auto_dial"}
        with mock.patch.object(android_tool, "_post", return_value=fake):
            out = android_tool._HANDLERS["android_call"]({"number": "x"})
        result = json.loads(out)
        self.assertEqual(result["mode"], "auto_dial")


if __name__ == "__main__":
    unittest.main()
