"""
Unit tests for ``plugin.tools.resolve_result`` and the JIT permission-denied
surfacing in the Tier C wrappers (v0.4.1).

Stdlib ``unittest`` + ``unittest.mock`` only — matches the rest of the
plugin/tests/ harness, avoids the ``responses`` dependency that conftest.py
imports for the pytest path.

Run with::

    python -m unittest plugin.tests.test_resolve_result

Coverage:
  * ResolveResult.from_bridge_response classifies all three variants
  * Both canonical (``code`` / ``permission``) and legacy
    (``error_code`` / ``required_permission``) wire keys are recognized
  * android_search_contacts → permission_denied envelope upgrades to JIT
    structured response with deep-link copy
  * android_send_sms → same
  * android_call → same
  * android_location → same
  * Non-permission errors pass through unchanged
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
from plugin.tools.resolve_result import (  # noqa: E402
    Found,
    NotFound,
    PermissionDenied,
    from_bridge_response,
)


class TestResolveResultClassification(unittest.TestCase):
    """Bare-bones ResolveResult classifier behaviour."""

    def test_permission_denied_canonical_keys(self) -> None:
        """v0.4.1 canonical wire shape uses `code` + `permission`."""
        result = from_bridge_response(
            {
                "error": "Grant Contacts permission in Settings...",
                "code": "permission_denied",
                "permission": "android.permission.READ_CONTACTS",
            }
        )
        self.assertIsInstance(result, PermissionDenied)
        assert isinstance(result, PermissionDenied)  # narrow for mypy
        self.assertEqual(result.permission, "android.permission.READ_CONTACTS")
        self.assertIn("Contacts", result.reason)

    def test_permission_denied_legacy_keys(self) -> None:
        """Pre-v0.4.1 phone builds emit `error_code` + `required_permission`."""
        result = from_bridge_response(
            {
                "error": "Grant SMS permission in Settings...",
                "error_code": "permission_denied",
                "required_permission": "android.permission.SEND_SMS",
            }
        )
        self.assertIsInstance(result, PermissionDenied)
        assert isinstance(result, PermissionDenied)
        self.assertEqual(result.permission, "android.permission.SEND_SMS")

    def test_permission_denied_mixed_keys(self) -> None:
        """Phone emits BOTH alias pairs — canonical takes precedence."""
        result = from_bridge_response(
            {
                "error": "Grant Phone permission",
                "code": "permission_denied",
                "error_code": "permission_denied",
                "permission": "android.permission.CALL_PHONE",
                "required_permission": "android.permission.CALL_PHONE",
            }
        )
        self.assertIsInstance(result, PermissionDenied)
        assert isinstance(result, PermissionDenied)
        self.assertEqual(result.permission, "android.permission.CALL_PHONE")

    def test_not_found_with_detail(self) -> None:
        result = from_bridge_response({"error": "no contact named Sam"})
        self.assertIsInstance(result, NotFound)
        assert isinstance(result, NotFound)
        self.assertEqual(result.detail, "no contact named Sam")

    def test_not_found_clean(self) -> None:
        """Empty/zero-result responses with no error string."""
        result = from_bridge_response({"contacts": []})
        self.assertIsInstance(result, NotFound)
        assert isinstance(result, NotFound)
        self.assertIsNone(result.detail)

    def test_found_explicit_value(self) -> None:
        """Caller pre-extracts the payload and asks for a Found wrapper."""
        result = from_bridge_response({"latitude": 47.6}, found_value={"latitude": 47.6})
        self.assertIsInstance(result, Found)
        assert isinstance(result, Found)
        self.assertEqual(result.value, {"latitude": 47.6})

    def test_unknown_error_code_falls_through(self) -> None:
        """`code` other than `permission_denied` is not classified as permission."""
        result = from_bridge_response(
            {"error": "user denied", "code": "user_denied"}
        )
        self.assertNotIsInstance(result, PermissionDenied)
        self.assertIsInstance(result, NotFound)


class TestAndroidSearchContactsJitPermission(unittest.TestCase):
    """Verify android_search_contacts upgrades a permission_denied bridge
    response into a JIT-structured agent-tool error."""

    def test_permission_denied_canonical(self) -> None:
        fake = {
            "error": "Grant contacts permission in Settings > Apps > Hermes-Relay > Permissions",
            "code": "permission_denied",
            "permission": "android.permission.READ_CONTACTS",
        }
        with mock.patch.object(android_tool, "_post", return_value=fake):
            raw = android_tool.android_search_contacts("Sam")
        result = json.loads(raw)
        self.assertFalse(result["ok"])
        self.assertEqual(result["code"], "permission_denied")
        self.assertEqual(result["permission"], "android.permission.READ_CONTACTS")
        self.assertIn("Contacts", result["error"])
        self.assertIn("Settings", result["error"])
        self.assertIn("Hermes Relay", result["error"])
        self.assertIn("android_search_contacts", result["error"])

    def test_permission_denied_legacy_aliases(self) -> None:
        """Phone running pre-v0.4.1 APK still emits the legacy aliases."""
        fake = {
            "error": "Grant contacts permission",
            "error_code": "permission_denied",
            "required_permission": "android.permission.READ_CONTACTS",
        }
        with mock.patch.object(android_tool, "_post", return_value=fake):
            raw = android_tool.android_search_contacts("Sam")
        result = json.loads(raw)
        self.assertEqual(result["code"], "permission_denied")
        self.assertEqual(result["permission"], "android.permission.READ_CONTACTS")

    def test_success_passes_through(self) -> None:
        """Non-error responses are not modified."""
        fake = {"count": 1, "contacts": [{"id": 1, "name": "Sam", "phones": []}]}
        with mock.patch.object(android_tool, "_post", return_value=fake):
            raw = android_tool.android_search_contacts("Sam")
        result = json.loads(raw)
        self.assertEqual(result, fake)
        self.assertNotIn("code", result)

    def test_non_permission_error_passes_through(self) -> None:
        """Other errors keep their original shape (no JIT upgrade)."""
        fake = {"error": "contacts query failed", "error_code": "service_unavailable"}
        with mock.patch.object(android_tool, "_post", return_value=fake):
            raw = android_tool.android_search_contacts("Sam")
        result = json.loads(raw)
        self.assertEqual(result["error_code"], "service_unavailable")
        self.assertNotIn("code", result)


class TestAndroidSendSmsJitPermission(unittest.TestCase):
    def test_permission_denied(self) -> None:
        fake = {
            "error": "Grant SMS permission in Settings...",
            "code": "permission_denied",
            "permission": "android.permission.SEND_SMS",
        }
        with mock.patch.object(android_tool, "_post", return_value=fake):
            raw = android_tool.android_send_sms("+15551234567", "hi")
        result = json.loads(raw)
        self.assertFalse(result["ok"])
        self.assertEqual(result["code"], "permission_denied")
        self.assertEqual(result["permission"], "android.permission.SEND_SMS")
        self.assertIn("SMS", result["error"])
        self.assertIn("android_send_sms", result["error"])


class TestAndroidCallJitPermission(unittest.TestCase):
    def test_permission_denied(self) -> None:
        fake = {
            "error": "permission denied despite grant",
            "code": "permission_denied",
            "permission": "android.permission.CALL_PHONE",
        }
        with mock.patch.object(android_tool, "_post", return_value=fake):
            raw = android_tool.android_call("+15551234567")
        result = json.loads(raw)
        self.assertFalse(result["ok"])
        self.assertEqual(result["code"], "permission_denied")
        self.assertEqual(result["permission"], "android.permission.CALL_PHONE")
        self.assertIn("Phone", result["error"])


class TestAndroidLocationJitPermission(unittest.TestCase):
    def test_permission_denied(self) -> None:
        fake = {
            "error": "Grant location permission",
            "code": "permission_denied",
            "permission": "android.permission.ACCESS_FINE_LOCATION",
        }
        with mock.patch.object(android_tool, "_get", return_value=fake):
            raw = android_tool.android_location()
        result = json.loads(raw)
        self.assertFalse(result["ok"])
        self.assertEqual(result["code"], "permission_denied")
        self.assertEqual(result["permission"], "android.permission.ACCESS_FINE_LOCATION")
        self.assertIn("Location", result["error"])

    def test_success_passes_through(self) -> None:
        fake = {"latitude": 47.6, "longitude": -122.3, "accuracy": 5.0}
        with mock.patch.object(android_tool, "_get", return_value=fake):
            raw = android_tool.android_location()
        result = json.loads(raw)
        self.assertEqual(result, fake)


class TestPermissionDeniedFallback(unittest.TestCase):
    """Edge cases around malformed permission-denied envelopes."""

    def test_permission_denied_missing_permission_field(self) -> None:
        """Phone sent ``code: permission_denied`` but no permission field."""
        fake = {"error": "missing permission", "code": "permission_denied"}
        with mock.patch.object(android_tool, "_post", return_value=fake):
            raw = android_tool.android_send_sms("+15551234567", "hi")
        result = json.loads(raw)
        self.assertEqual(result["code"], "permission_denied")
        self.assertEqual(result["permission"], "")
        # Friendly mapping falls back to the empty permission key.
        self.assertIn("android_send_sms", result["error"])

    def test_unknown_permission_uses_raw_string(self) -> None:
        fake = {
            "error": "permission denied",
            "code": "permission_denied",
            "permission": "android.permission.SOMETHING_NEW",
        }
        with mock.patch.object(android_tool, "_post", return_value=fake):
            raw = android_tool.android_send_sms("+15551234567", "hi")
        result = json.loads(raw)
        self.assertEqual(result["permission"], "android.permission.SOMETHING_NEW")
        # Falls back to the raw constant when not in the friendly map.
        self.assertIn("android.permission.SOMETHING_NEW", result["error"])


if __name__ == "__main__":
    unittest.main()
