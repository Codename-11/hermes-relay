"""
Unit tests for ``plugin.tools.android_tool.android_media``.

Uses only stdlib ``unittest`` + ``unittest.mock`` so it runs cleanly via::

    python -m unittest plugin.tests.test_android_media

without pulling in the ``responses`` dependency that the existing
``conftest.py`` imports at collection time. The conftest is pytest-only
and is not loaded by ``unittest``.

Coverage:
  * Every valid action (play / pause / toggle / next / previous) happy path.
  * Unknown action → structured error envelope, no HTTP call.
  * Missing / empty action arg → structured error envelope, no HTTP call.
  * Action is normalised (case-insensitive, whitespace-stripped).
  * The schema + handler map both include ``android_media``.
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path
from unittest import mock

# Make `import plugin.tools.android_tool` work when the test runs from the
# repo root without the package being installed. The worktree layout
# already puts everything under `plugin/`.
REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

# Importing the tool module pulls in ``requests`` which is a runtime dep
# of the plugin. The hermes-agent venv has it; the Windows dev env may
# not. Skip the whole suite cleanly if the import fails.
try:
    from plugin.tools import android_tool as tool  # noqa: E402
except Exception as exc:  # pragma: no cover - env-specific
    raise unittest.SkipTest(f"plugin.tools.android_tool unimportable: {exc}")


VALID_ACTIONS = ("play", "pause", "toggle", "next", "previous")


class TestAndroidMediaValidActions(unittest.TestCase):
    """Each valid action should POST /media with the normalised action string."""

    def _run(self, action: str):
        with mock.patch.object(tool, "_post") as mocked_post:
            mocked_post.return_value = {"ok": True, "action": action}
            raw = tool.android_media(action)
        return raw, mocked_post

    def test_play(self):
        raw, mocked_post = self._run("play")
        mocked_post.assert_called_once_with("/media", {"action": "play"})
        payload = json.loads(raw)
        self.assertTrue(payload["ok"])
        self.assertEqual(payload["action"], "play")

    def test_pause(self):
        raw, mocked_post = self._run("pause")
        mocked_post.assert_called_once_with("/media", {"action": "pause"})
        self.assertTrue(json.loads(raw)["ok"])

    def test_toggle(self):
        raw, mocked_post = self._run("toggle")
        mocked_post.assert_called_once_with("/media", {"action": "toggle"})
        self.assertTrue(json.loads(raw)["ok"])

    def test_next(self):
        raw, mocked_post = self._run("next")
        mocked_post.assert_called_once_with("/media", {"action": "next"})
        self.assertTrue(json.loads(raw)["ok"])

    def test_previous(self):
        raw, mocked_post = self._run("previous")
        mocked_post.assert_called_once_with("/media", {"action": "previous"})
        self.assertTrue(json.loads(raw)["ok"])


class TestAndroidMediaNormalisation(unittest.TestCase):
    def test_uppercase_action_is_normalised(self):
        with mock.patch.object(tool, "_post") as mocked_post:
            mocked_post.return_value = {"ok": True}
            tool.android_media("PLAY")
        mocked_post.assert_called_once_with("/media", {"action": "play"})

    def test_whitespace_is_stripped(self):
        with mock.patch.object(tool, "_post") as mocked_post:
            mocked_post.return_value = {"ok": True}
            tool.android_media("  Pause  ")
        mocked_post.assert_called_once_with("/media", {"action": "pause"})


class TestAndroidMediaErrors(unittest.TestCase):
    def test_unknown_action_returns_error_without_http_call(self):
        with mock.patch.object(tool, "_post") as mocked_post:
            raw = tool.android_media("stop")
        mocked_post.assert_not_called()
        payload = json.loads(raw)
        self.assertIn("error", payload)
        self.assertIn("stop", payload["error"])
        self.assertIn("valid_actions", payload)
        self.assertCountEqual(payload["valid_actions"], list(VALID_ACTIONS))

    def test_empty_string_returns_error(self):
        with mock.patch.object(tool, "_post") as mocked_post:
            raw = tool.android_media("")
        mocked_post.assert_not_called()
        payload = json.loads(raw)
        self.assertEqual(payload.get("error"), "action is required")

    def test_whitespace_only_returns_error(self):
        with mock.patch.object(tool, "_post") as mocked_post:
            raw = tool.android_media("   ")
        mocked_post.assert_not_called()
        payload = json.loads(raw)
        self.assertEqual(payload.get("error"), "action is required")

    def test_non_string_action_returns_error(self):
        with mock.patch.object(tool, "_post") as mocked_post:
            raw = tool.android_media(None)  # type: ignore[arg-type]
        mocked_post.assert_not_called()
        payload = json.loads(raw)
        self.assertEqual(payload.get("error"), "action is required")

    def test_http_failure_is_surfaced_as_error(self):
        with mock.patch.object(tool, "_post", side_effect=RuntimeError("boom")):
            raw = tool.android_media("play")
        payload = json.loads(raw)
        self.assertIn("error", payload)
        self.assertIn("boom", payload["error"])


class TestAndroidMediaRegistration(unittest.TestCase):
    def test_schema_is_registered(self):
        self.assertIn("android_media", tool._SCHEMAS)
        schema = tool._SCHEMAS["android_media"]
        self.assertEqual(schema["name"], "android_media")
        params = schema["parameters"]
        self.assertEqual(params["required"], ["action"])
        enum = params["properties"]["action"]["enum"]
        self.assertCountEqual(enum, list(VALID_ACTIONS))

    def test_handler_is_registered(self):
        self.assertIn("android_media", tool._HANDLERS)
        handler = tool._HANDLERS["android_media"]
        with mock.patch.object(tool, "_post") as mocked_post:
            mocked_post.return_value = {"ok": True}
            raw = handler({"action": "toggle"})
        mocked_post.assert_called_once_with("/media", {"action": "toggle"})
        self.assertTrue(json.loads(raw)["ok"])


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
