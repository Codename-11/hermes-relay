"""Safe error reporting when Android revokes per-session screen-capture consent."""
from __future__ import annotations

import unittest
from unittest import mock

import requests

from plugin.tools import android_tool


def http_error(*, status: int = 500, payload: bytes = b'{"error":"MediaProjection not granted - grant screenshots"}'):
    response = requests.Response()
    response.status_code = status
    response._content = payload
    response.headers["Content-Type"] = "application/json"
    err = requests.HTTPError("private relay URL with token redacted")
    err.response = response
    return err


class ScreenshotConsentTests(unittest.TestCase):
    def test_expired_projection_explains_user_consent(self):
        with mock.patch.object(android_tool, "_get", side_effect=http_error()):
            result = android_tool.android_screenshot()
        self.assertIn("screen capture consent expired", result["error"])
        self.assertIn("Share entire screen", result["error"])
        self.assertNotIn("private relay URL", str(result))

    def test_unrelated_http_failure_does_not_expose_private_response(self):
        private_message = b'{"error":"SECRET-BEARER should never be printed"}'
        with mock.patch.object(android_tool, "_get", side_effect=http_error(payload=private_message)):
            result = android_tool.android_screenshot()
        self.assertIn("Screenshot unavailable", result["error"])
        self.assertNotIn("SECRET-BEARER", str(result))

    def test_malformed_json_is_handled_safely(self):
        with mock.patch.object(android_tool, "_get", side_effect=http_error(payload=b"not-json")):
            result = android_tool.android_screenshot()
        self.assertIn("Screenshot unavailable", result["error"])

    def test_other_network_errors_are_sanitized(self):
        with mock.patch.object(android_tool, "_get", side_effect=ConnectionError("private internal address")):
            result = android_tool.android_screenshot()
        self.assertIn("Screenshot unavailable", result["error"])
        self.assertNotIn("private internal address", str(result))


if __name__ == "__main__":
    unittest.main()
