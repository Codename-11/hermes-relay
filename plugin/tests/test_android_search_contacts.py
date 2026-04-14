"""
Unit tests for ``plugin.tools.android_tool.android_search_contacts`` (Tier C2).

Stdlib ``unittest`` + ``unittest.mock`` only. Run via::

    python -m unittest plugin.tests.test_android_search_contacts

Coverage:
  * happy path — bridge returns a contact list with phones.
  * empty result — bridge returns `count=0, contacts=[]`.
  * permission denied — bridge returns helpful error body.
  * default limit parameter.
  * schema registration sanity.
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


class TestAndroidSearchContactsHappyPath(unittest.TestCase):
    def test_returns_contacts_list(self) -> None:
        fake = {
            "count": 2,
            "query": "Sam",
            "contacts": [
                {"id": 1, "name": "Sam Wilson", "phones": "+15551234567"},
                {
                    "id": 2,
                    "name": "Samantha Carter",
                    "phones": "+15559876543, +15550000000",
                },
            ],
        }
        with mock.patch.object(android_tool, "_post", return_value=fake) as m:
            result = json.loads(android_tool.android_search_contacts("Sam"))
        self.assertEqual(result["count"], 2)
        self.assertEqual(len(result["contacts"]), 2)
        self.assertEqual(result["contacts"][0]["name"], "Sam Wilson")
        # Verify the payload shape the tool sent to the bridge.
        m.assert_called_once()
        args, kwargs = m.call_args
        # Either positional or kwarg form.
        sent_path = args[0] if args else kwargs["path"]
        sent_body = args[1] if len(args) > 1 else kwargs.get("payload", {})
        self.assertEqual(sent_path, "/search_contacts")
        self.assertEqual(sent_body["query"], "Sam")
        self.assertEqual(sent_body["limit"], 20)

    def test_custom_limit(self) -> None:
        fake = {"count": 0, "contacts": []}
        with mock.patch.object(android_tool, "_post", return_value=fake) as m:
            android_tool.android_search_contacts("X", limit=5)
        _, kwargs = m.call_args
        args = m.call_args.args
        sent_body = args[1] if len(args) > 1 else kwargs.get("payload", {})
        self.assertEqual(sent_body["limit"], 5)


class TestAndroidSearchContactsEmpty(unittest.TestCase):
    def test_no_matches(self) -> None:
        fake = {"count": 0, "query": "Nonexistent", "contacts": []}
        with mock.patch.object(android_tool, "_post", return_value=fake):
            result = json.loads(
                android_tool.android_search_contacts("Nonexistent")
            )
        self.assertEqual(result["count"], 0)
        self.assertEqual(result["contacts"], [])


class TestAndroidSearchContactsPermissionDenied(unittest.TestCase):
    def test_permission_error_passthrough(self) -> None:
        fake = {
            "error": (
                "Grant contacts permission in Settings > Apps > "
                "Hermes-Relay > Permissions"
            )
        }
        with mock.patch.object(android_tool, "_post", return_value=fake):
            result = json.loads(android_tool.android_search_contacts("Sam"))
        self.assertIn("error", result)
        self.assertIn("contacts permission", result["error"])

    def test_network_error(self) -> None:
        with mock.patch.object(
            android_tool, "_post", side_effect=ConnectionError("down")
        ):
            result = json.loads(android_tool.android_search_contacts("Sam"))
        self.assertIn("error", result)


class TestAndroidSearchContactsSchema(unittest.TestCase):
    def test_registered(self) -> None:
        self.assertIn("android_search_contacts", android_tool._SCHEMAS)
        self.assertIn("android_search_contacts", android_tool._HANDLERS)

    def test_schema_params(self) -> None:
        schema = android_tool._SCHEMAS["android_search_contacts"]
        self.assertEqual(schema["name"], "android_search_contacts")
        self.assertIn("sideload", schema["description"].lower())
        props = schema["parameters"]["properties"]
        self.assertIn("query", props)
        self.assertIn("limit", props)
        self.assertIn("query", schema["parameters"]["required"])

    def test_handler_dispatch(self) -> None:
        fake = {"count": 0, "contacts": []}
        with mock.patch.object(android_tool, "_post", return_value=fake):
            out = android_tool._HANDLERS["android_search_contacts"](
                {"query": "Test"}
            )
        result = json.loads(out)
        self.assertEqual(result["count"], 0)


if __name__ == "__main__":
    unittest.main()
