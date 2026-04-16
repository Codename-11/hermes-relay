"""
Unit tests for B4 tools — ``android_send_intent`` and ``android_broadcast``.

Uses stdlib ``unittest`` + ``unittest.mock`` so it runs cleanly via::

    python -m unittest plugin.tests.test_android_send_intent

without pulling in the ``responses`` dependency that the pytest-based
``conftest.py`` imports at collection time. The conftest is pytest-only
and is not loaded by ``unittest``.

Coverage:
  * ``android_send_intent`` — happy path with action only, with data/pkg/
    component/extras/category, missing action, malformed component string.
  * ``android_broadcast`` — happy path, extras forwarding, missing action.
  * Both — forwarded HTTP body shape matches the ``_bridge_dispatch``
    wire protocol expected by ``BridgeCommandHandler``.
  * Schema sanity — both tools registered with their required fields.
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path
from unittest import mock

# Make `import plugin.tools.android_tool` work when the test runs from the
# repo root without the package being installed editable.
REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

# Import via the namespaced path so we don't collide with the top-level
# ``tools`` package used by the pytest-based ``test_android_tool.py``.
from plugin.tools import android_tool  # noqa: E402


class TestSendIntentHappyPath(unittest.TestCase):
    def test_action_only(self):
        """Minimal send_intent with just an action forwards correctly."""
        with mock.patch.object(android_tool, "_post", return_value={"ok": True}) as mp:
            raw = android_tool.android_send_intent("android.intent.action.VIEW")
        result = json.loads(raw)
        self.assertEqual(result, {"ok": True})
        mp.assert_called_once_with(
            "/send_intent",
            {"action": "android.intent.action.VIEW"},
        )

    def test_full_payload_forwarded(self):
        """Every optional field (data/package/component/extras/category)
        gets forwarded in the POST body."""
        captured: dict = {}

        def fake_post(path, body):
            captured["path"] = path
            captured["body"] = body
            return {"ok": True}

        with mock.patch.object(android_tool, "_post", side_effect=fake_post):
            android_tool.android_send_intent(
                action="android.intent.action.VIEW",
                data="google.navigation:q=Empire+State",
                package="com.google.android.apps.maps",
                component="com.google.android.apps.maps/.MapsActivity",
                extras={"zoom": "17"},
                category="android.intent.category.DEFAULT",
            )

        self.assertEqual(captured["path"], "/send_intent")
        body = captured["body"]
        self.assertEqual(body["action"], "android.intent.action.VIEW")
        self.assertEqual(body["data"], "google.navigation:q=Empire+State")
        self.assertEqual(body["package"], "com.google.android.apps.maps")
        self.assertEqual(
            body["component"], "com.google.android.apps.maps/.MapsActivity"
        )
        self.assertEqual(body["extras"], {"zoom": "17"})
        self.assertEqual(body["category"], "android.intent.category.DEFAULT")

    def test_extras_forwarded_as_dict(self):
        """Extras dict should land in the body untouched — the phone is
        responsible for coercing values to Intent.putExtra string form."""
        with mock.patch.object(
            android_tool, "_post", return_value={"ok": True}
        ) as mp:
            android_tool.android_send_intent(
                action="android.intent.action.SENDTO",
                data="smsto:5551234",
                extras={"sms_body": "hello world", "subject": "howdy"},
            )
        _, body = mp.call_args[0]
        self.assertEqual(
            body["extras"], {"sms_body": "hello world", "subject": "howdy"}
        )

    def test_bridge_returns_error_envelope(self):
        """If _post raises (e.g. network error), the tool wraps it into
        the standard ``{"error": ...}`` JSON envelope."""
        with mock.patch.object(
            android_tool, "_post", side_effect=RuntimeError("boom")
        ):
            raw = android_tool.android_send_intent("foo")
        result = json.loads(raw)
        self.assertIn("error", result)
        self.assertEqual(result["error"], "boom")

    def test_none_values_are_dropped_from_body(self):
        """When an optional field is None, the tool should NOT send it
        at all — not send ``"key": null``. Keeps the wire shape clean."""
        with mock.patch.object(
            android_tool, "_post", return_value={"ok": True}
        ) as mp:
            android_tool.android_send_intent(
                action="X",
                data=None,
                package=None,
                component=None,
                extras=None,
                category=None,
            )
        _, body = mp.call_args[0]
        self.assertEqual(set(body.keys()), {"action"})


class TestBroadcastHappyPath(unittest.TestCase):
    def test_action_only(self):
        with mock.patch.object(
            android_tool, "_post", return_value={"ok": True}
        ) as mp:
            raw = android_tool.android_broadcast("com.example.PING")
        result = json.loads(raw)
        self.assertEqual(result, {"ok": True})
        mp.assert_called_once_with("/broadcast", {"action": "com.example.PING"})

    def test_extras_forwarded(self):
        with mock.patch.object(
            android_tool, "_post", return_value={"ok": True}
        ) as mp:
            android_tool.android_broadcast(
                action="com.example.PING",
                data="content://foo",
                package="com.example",
                extras={"k1": "v1", "k2": "v2"},
            )
        _, body = mp.call_args[0]
        self.assertEqual(body["action"], "com.example.PING")
        self.assertEqual(body["data"], "content://foo")
        self.assertEqual(body["package"], "com.example")
        self.assertEqual(body["extras"], {"k1": "v1", "k2": "v2"})

    def test_bridge_error_wrapped(self):
        with mock.patch.object(
            android_tool, "_post", side_effect=ValueError("no phone")
        ):
            raw = android_tool.android_broadcast("X")
        result = json.loads(raw)
        self.assertEqual(result, {"error": "no phone"})


class TestSchemaSanity(unittest.TestCase):
    def test_send_intent_schema_registered(self):
        self.assertIn("android_send_intent", android_tool._SCHEMAS)
        schema = android_tool._SCHEMAS["android_send_intent"]
        self.assertEqual(schema["name"], "android_send_intent")
        self.assertIn("action", schema["parameters"]["properties"])
        self.assertEqual(schema["parameters"]["required"], ["action"])

    def test_broadcast_schema_registered(self):
        self.assertIn("android_broadcast", android_tool._SCHEMAS)
        schema = android_tool._SCHEMAS["android_broadcast"]
        self.assertEqual(schema["name"], "android_broadcast")
        self.assertIn("action", schema["parameters"]["properties"])
        self.assertEqual(schema["parameters"]["required"], ["action"])

    def test_handlers_registered(self):
        self.assertIn("android_send_intent", android_tool._HANDLERS)
        self.assertIn("android_broadcast", android_tool._HANDLERS)

    def test_handlers_route_through_tool_functions(self):
        """The handler dispatch map forwards positional args as kwargs."""
        with mock.patch.object(
            android_tool, "android_send_intent", return_value='{"ok":true}'
        ) as send_mock:
            android_tool._HANDLERS["android_send_intent"](
                {"action": "foo", "data": "bar"}
            )
            send_mock.assert_called_once_with(action="foo", data="bar")

        with mock.patch.object(
            android_tool, "android_broadcast", return_value='{"ok":true}'
        ) as bcast_mock:
            android_tool._HANDLERS["android_broadcast"](
                {"action": "com.example.FOO"}
            )
            bcast_mock.assert_called_once_with(action="com.example.FOO")


# ── Error cases / wire-shape assertions ──────────────────────────────────────
#
# These don't directly unit-test the Python side's error responses (the
# tool does no pre-validation — it forwards to the phone), but they
# confirm the wire shape the BridgeCommandHandler and ActionExecutor
# expect when surfacing errors back to the agent.


class TestErrorShapes(unittest.TestCase):
    def test_missing_action_on_phone_surfaces_as_bridge_error(self):
        """The phone returns a 400 JSON body like ``{"error": "missing
        'action' in body"}``. The tool passes the _post exception
        upstream (raise_for_status), and our catch wraps it."""

        class FakeHTTPError(Exception):
            pass

        with mock.patch.object(
            android_tool, "_post", side_effect=FakeHTTPError("HTTP 400")
        ):
            raw = android_tool.android_send_intent("")
        # The tool itself doesn't validate "" locally — it sends and
        # lets the server reject. This test documents the behavior so
        # anyone tightening it later can update the test in lockstep.
        result = json.loads(raw)
        self.assertIn("error", result)

    def test_malformed_component_passes_through(self):
        """A malformed component string like 'no-slash' is forwarded
        verbatim — the phone rejects it with 'invalid component' in the
        ActionResult. This test confirms the tool doesn't strip or
        normalize it client-side."""
        with mock.patch.object(
            android_tool, "_post", return_value={"error": "send_intent: invalid component 'no-slash'"}
        ) as mp:
            raw = android_tool.android_send_intent(
                action="android.intent.action.MAIN",
                component="no-slash",
            )
        _, body = mp.call_args[0]
        self.assertEqual(body["component"], "no-slash")
        result = json.loads(raw)
        self.assertIn("error", result)
        self.assertIn("invalid component", result["error"])


if __name__ == "__main__":
    unittest.main()
