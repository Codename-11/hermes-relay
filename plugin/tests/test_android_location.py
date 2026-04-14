"""
Unit tests for ``plugin.tools.android_tool.android_location`` (Tier C1).

Uses only stdlib ``unittest`` + ``unittest.mock`` so it runs cleanly via::

    python -m unittest plugin.tests.test_android_location

without pulling in the ``responses`` dependency that ``conftest.py`` imports
at pytest collection time. The conftest is pytest-only and is not loaded
by ``unittest``.

Coverage:
  * happy path — bridge returns a full fix, tool echoes JSON.
  * permission denied — bridge returns the helpful error body.
  * stale location warning — bridge returns staleness_ms + warning field.
  * schema registration sanity (name, parameters, handler wiring).
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

# Import module-level so _post / _get patch targets resolve cleanly.
from plugin.tools import android_tool  # noqa: E402


class TestAndroidLocationHappyPath(unittest.TestCase):
    def test_returns_lat_lon_accuracy(self) -> None:
        fake = {
            "latitude": 37.4219,
            "longitude": -122.0840,
            "accuracy": 8.5,
            "provider": "gps",
            "timestamp": 1_700_000_000_000,
            "staleness_ms": 12_000,
        }
        with mock.patch.object(android_tool, "_get", return_value=fake):
            result = json.loads(android_tool.android_location())
        self.assertEqual(result["latitude"], 37.4219)
        self.assertEqual(result["longitude"], -122.0840)
        self.assertEqual(result["accuracy"], 8.5)
        self.assertEqual(result["provider"], "gps")
        self.assertNotIn("error", result)

    def test_includes_altitude_when_available(self) -> None:
        fake = {
            "latitude": 0.0,
            "longitude": 0.0,
            "accuracy": 10.0,
            "altitude": 42.0,
            "provider": "gps",
            "timestamp": 0,
            "staleness_ms": 0,
        }
        with mock.patch.object(android_tool, "_get", return_value=fake):
            result = json.loads(android_tool.android_location())
        self.assertEqual(result["altitude"], 42.0)


class TestAndroidLocationPermissionDenied(unittest.TestCase):
    def test_permission_denied_error_passthrough(self) -> None:
        """Bridge returns a human-readable permission error; tool forwards it."""
        fake = {
            "error": (
                "Grant location permission in Settings > Apps > "
                "Hermes-Relay > Permissions"
            )
        }
        with mock.patch.object(android_tool, "_get", return_value=fake):
            result = json.loads(android_tool.android_location())
        self.assertIn("error", result)
        self.assertIn("location permission", result["error"])

    def test_exception_becomes_error_json(self) -> None:
        """_get raises — tool returns {"error": "..."}."""
        with mock.patch.object(
            android_tool, "_get", side_effect=ConnectionError("refused")
        ):
            result = json.loads(android_tool.android_location())
        self.assertIn("error", result)
        self.assertIn("refused", result["error"])


class TestAndroidLocationStaleFix(unittest.TestCase):
    def test_stale_warning_field(self) -> None:
        """If the bridge reports a stale fix, the warning propagates."""
        fake = {
            "latitude": 37.0,
            "longitude": -122.0,
            "accuracy": 50.0,
            "provider": "network",
            "timestamp": 1_000_000,
            "staleness_ms": 15 * 60 * 1000,
            "warning": (
                "Last known location is >5min old; open a maps app briefly "
                "to refresh the fix."
            ),
        }
        with mock.patch.object(android_tool, "_get", return_value=fake):
            result = json.loads(android_tool.android_location())
        self.assertIn("warning", result)
        self.assertGreater(result["staleness_ms"], 5 * 60 * 1000)


class TestAndroidLocationSchema(unittest.TestCase):
    def test_registered_in_schemas(self) -> None:
        self.assertIn("android_location", android_tool._SCHEMAS)

    def test_registered_in_handlers(self) -> None:
        self.assertIn("android_location", android_tool._HANDLERS)

    def test_schema_has_required_fields(self) -> None:
        schema = android_tool._SCHEMAS["android_location"]
        self.assertEqual(schema["name"], "android_location")
        self.assertIn("description", schema)
        self.assertIn("sideload", schema["description"].lower())
        self.assertIn("parameters", schema)
        # Zero-arg tool.
        self.assertEqual(schema["parameters"].get("properties", {}), {})
        self.assertEqual(schema["parameters"].get("required", []), [])

    def test_handler_dispatches_correctly(self) -> None:
        fake = {"latitude": 1.0, "longitude": 2.0, "accuracy": 3.0}
        with mock.patch.object(android_tool, "_get", return_value=fake):
            handler = android_tool._HANDLERS["android_location"]
            out = handler({})
        result = json.loads(out)
        self.assertEqual(result["latitude"], 1.0)


if __name__ == "__main__":
    unittest.main()
