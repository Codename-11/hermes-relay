"""
Unit tests for ``plugin.tools.android_tool.android_send_sms`` (Tier C4).

Stdlib ``unittest`` + ``unittest.mock`` only. Run via::

    python -m unittest plugin.tests.test_android_send_sms

Coverage:
  * happy path — single-part SMS.
  * multi-part — long body, bridge reports parts > 1.
  * permission denied — bridge returns helpful error body.
  * user denied confirmation — bridge returns 403.
  * timeout — carrier never acked.
  * invalid number format.
  * schema registration sanity (name, required fields, handler wiring).
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


class TestAndroidSendSmsHappyPath(unittest.TestCase):
    def test_single_part(self) -> None:
        fake = {"to": "+15551234567", "length": 5, "parts": 1}
        with mock.patch.object(android_tool, "_post", return_value=fake) as m:
            result = json.loads(
                android_tool.android_send_sms("+15551234567", "hello")
            )
        self.assertEqual(result["parts"], 1)
        self.assertEqual(result["length"], 5)
        args = m.call_args.args
        kwargs = m.call_args.kwargs
        sent_path = args[0] if args else kwargs["path"]
        sent_body = args[1] if len(args) > 1 else kwargs.get("payload", {})
        self.assertEqual(sent_path, "/send_sms")
        self.assertEqual(sent_body["to"], "+15551234567")
        self.assertEqual(sent_body["body"], "hello")

    def test_multi_part(self) -> None:
        """Body >160 chars — bridge reports multiple parts."""
        long_body = "x" * 300
        fake = {"to": "+15551234567", "length": 300, "parts": 2}
        with mock.patch.object(android_tool, "_post", return_value=fake):
            result = json.loads(
                android_tool.android_send_sms("+15551234567", long_body)
            )
        self.assertEqual(result["parts"], 2)
        self.assertEqual(result["length"], 300)


class TestAndroidSendSmsPermissionDenied(unittest.TestCase):
    def test_permission_error_passthrough(self) -> None:
        fake = {
            "error": (
                "Grant SMS permission in Settings > Apps > "
                "Hermes-Relay > Permissions"
            )
        }
        with mock.patch.object(android_tool, "_post", return_value=fake):
            result = json.loads(
                android_tool.android_send_sms("+15551234567", "test")
            )
        self.assertIn("SMS permission", result["error"])

    def test_sideload_only_on_googleplay(self) -> None:
        fake = {"error": "android_send_sms is sideload-only"}
        with mock.patch.object(android_tool, "_post", return_value=fake):
            result = json.loads(
                android_tool.android_send_sms("+15551234567", "test")
            )
        self.assertIn("sideload-only", result["error"])


class TestAndroidSendSmsDenied(unittest.TestCase):
    def test_user_denied_confirmation(self) -> None:
        fake = {
            "error": "user denied destructive action",
            "reason": "confirmation_denied_or_timeout",
        }
        with mock.patch.object(android_tool, "_post", return_value=fake):
            result = json.loads(
                android_tool.android_send_sms("+15551234567", "sensitive")
            )
        self.assertEqual(result["reason"], "confirmation_denied_or_timeout")


class TestAndroidSendSmsRadioFailures(unittest.TestCase):
    def test_timeout(self) -> None:
        fake = {"error": "send_sms timeout after 15000ms — carrier never acked"}
        with mock.patch.object(android_tool, "_post", return_value=fake):
            result = json.loads(
                android_tool.android_send_sms("+15551234567", "hello")
            )
        self.assertIn("timeout", result["error"])

    def test_radio_off(self) -> None:
        fake = {"error": "send_sms failed: radio off (airplane mode?)"}
        with mock.patch.object(android_tool, "_post", return_value=fake):
            result = json.loads(
                android_tool.android_send_sms("+15551234567", "hi")
            )
        self.assertIn("radio off", result["error"])

    def test_invalid_number(self) -> None:
        fake = {"error": "send_sms: recipient contains invalid characters"}
        with mock.patch.object(android_tool, "_post", return_value=fake):
            result = json.loads(
                android_tool.android_send_sms("notanumber!!!", "hi")
            )
        self.assertIn("invalid", result["error"])

    def test_network_error(self) -> None:
        with mock.patch.object(
            android_tool, "_post", side_effect=ConnectionError("relay down")
        ):
            result = json.loads(
                android_tool.android_send_sms("+15551234567", "hi")
            )
        self.assertIn("error", result)
        self.assertIn("relay down", result["error"])


class TestAndroidSendSmsSchema(unittest.TestCase):
    def test_registered(self) -> None:
        self.assertIn("android_send_sms", android_tool._SCHEMAS)
        self.assertIn("android_send_sms", android_tool._HANDLERS)

    def test_schema_shape(self) -> None:
        schema = android_tool._SCHEMAS["android_send_sms"]
        self.assertEqual(schema["name"], "android_send_sms")
        self.assertIn("sideload", schema["description"].lower())
        props = schema["parameters"]["properties"]
        self.assertIn("to", props)
        self.assertIn("body", props)
        required = set(schema["parameters"]["required"])
        self.assertEqual(required, {"to", "body"})

    def test_handler_dispatch(self) -> None:
        fake = {"to": "x", "length": 4, "parts": 1}
        with mock.patch.object(android_tool, "_post", return_value=fake):
            out = android_tool._HANDLERS["android_send_sms"](
                {"to": "x", "body": "test"}
            )
        result = json.loads(out)
        self.assertEqual(result["parts"], 1)


if __name__ == "__main__":
    unittest.main()
