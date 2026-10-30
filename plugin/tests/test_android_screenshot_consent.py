"""Expose actionable MediaProjection consent failure without leaking tokens."""

from __future__ import annotations

import unittest
from unittest.mock import patch

import requests

from plugin.tools import android_tool


class _Response:
    def __init__(self, payload: object):
        self.payload = payload

    def json(self):
        if isinstance(self.payload, BaseException):
            raise self.payload
        return self.payload


class ConsentDiagnosticsTests(unittest.TestCase):
    def test_missing_media_projection_consent(self):
        exc = requests.HTTPError("HTTP 500", response=_Response({
            "error": "MediaProjection not granted — start capture on Android"
        }))
        with patch.object(android_tool, "_get", side_effect=exc):
            result = android_tool.android_screenshot()
        self.assertIn("consent expired", result["error"])
        self.assertIn("Share entire screen", result["error"])
        self.assertNotIn("HTTP 500", result["error"])

    def test_other_errors_remain_generic(self):
        exc = requests.HTTPError("HTTP 401 secret bearer", response=_Response({
            "error": "invalid bearer token private details"
        }))
        with patch.object(android_tool, "_get", side_effect=exc):
            result = android_tool.android_screenshot()
        self.assertEqual(result, {"error": "Screenshot unavailable"})

    def test_non_json_response_remains_generic(self):
        exc = requests.HTTPError("HTTP 500", response=_Response(ValueError("bad JSON")))
        with patch.object(android_tool, "_get", side_effect=exc):
            result = android_tool.android_screenshot()
        self.assertEqual(result, {"error": "Screenshot unavailable"})


if __name__ == "__main__":
    unittest.main()
