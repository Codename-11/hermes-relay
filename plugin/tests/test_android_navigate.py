"""
Unit tests for ``plugin.tools.android_navigate``.

Uses only stdlib ``unittest`` + ``unittest.mock`` so it runs cleanly via::

    python -m unittest plugin.tests.test_android_navigate

without pulling in the ``responses`` dependency that the existing
``conftest.py`` imports at collection time. The conftest is pytest-only
and is not loaded by ``unittest``.

Coverage:
  * ``parse_response`` — every valid verb + a grid of malformed inputs.
  * ``android_navigate`` main loop — success path, iteration cap,
    screenshot failure, malformed reply, action-execution failure,
    unwired-LLM (``llm_gap``) envelope.
  * Schema sanity — the tool is registered with the expected shape.
"""

from __future__ import annotations

import json
import os
import sys
import unittest
from pathlib import Path
from unittest import mock

# Make `import plugin.tools.android_navigate` work when the test runs from
# the repo root without the package being installed. The worktree layout
# already puts everything under `plugin/`.
REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from plugin.tools import android_navigate as nav  # noqa: E402
from plugin.tools.android_navigate_prompt import (  # noqa: E402
    ParsedAction,
    VALID_ACTIONS,
    build_prompt,
    parse_response,
)


# ── Parser tests ──────────────────────────────────────────────────────────────


class TestParseResponse(unittest.TestCase):
    def _assert_ok(self, parsed: ParsedAction, verb: str) -> None:
        self.assertEqual(parsed.action, verb, msg=f"raw={parsed.raw!r}")
        self.assertNotEqual(parsed.action, "error")

    def test_tap_text_basic(self) -> None:
        reply = (
            "ACTION: tap_text\n"
            'PARAMS: {"text": "Continue"}\n'
            "REASON: Continue button advances the flow."
        )
        parsed = parse_response(reply)
        self._assert_ok(parsed, "tap_text")
        self.assertEqual(parsed.params["text"], "Continue")
        self.assertIn("advances", parsed.reasoning)

    def test_tap_text_with_exact(self) -> None:
        reply = (
            "ACTION: tap_text\n"
            'PARAMS: {"text": "OK", "exact": true}\n'
            "REASON: Exact match avoids hitting similar labels."
        )
        parsed = parse_response(reply)
        self._assert_ok(parsed, "tap_text")
        self.assertIs(parsed.params["exact"], True)

    def test_tap_by_coordinates(self) -> None:
        reply = (
            "ACTION: tap\n"
            'PARAMS: {"x": 540, "y": 1200}\n'
            "REASON: Center of compose button, no text label visible."
        )
        parsed = parse_response(reply)
        self._assert_ok(parsed, "tap")
        self.assertEqual(parsed.params, {"x": 540, "y": 1200})

    def test_tap_by_node_id(self) -> None:
        reply = (
            "ACTION: tap\n"
            'PARAMS: {"node_id": "n42"}\n'
            "REASON: Node from accessibility tree is the most reliable target."
        )
        parsed = parse_response(reply)
        self._assert_ok(parsed, "tap")
        self.assertEqual(parsed.params["node_id"], "n42")

    def test_tap_missing_fields(self) -> None:
        parsed = parse_response(
            "ACTION: tap\nPARAMS: {}\nREASON: lost"
        )
        self.assertEqual(parsed.action, "error")
        self.assertIn("tap requires", parsed.reasoning)

    def test_type_basic(self) -> None:
        reply = (
            "ACTION: type\n"
            'PARAMS: {"text": "Hello world", "clear_first": true}\n'
            "REASON: Draft the tweet body."
        )
        parsed = parse_response(reply)
        self._assert_ok(parsed, "type")
        self.assertEqual(parsed.params["text"], "Hello world")
        self.assertIs(parsed.params["clear_first"], True)

    def test_swipe_basic(self) -> None:
        reply = (
            "ACTION: swipe\n"
            'PARAMS: {"direction": "down", "distance": "long"}\n'
            "REASON: Pull notification shade."
        )
        parsed = parse_response(reply)
        self._assert_ok(parsed, "swipe")
        self.assertEqual(parsed.params["direction"], "down")

    def test_swipe_bad_direction(self) -> None:
        reply = (
            "ACTION: swipe\n"
            'PARAMS: {"direction": "sideways"}\n'
            "REASON: bad"
        )
        parsed = parse_response(reply)
        self.assertEqual(parsed.action, "error")
        self.assertIn("direction", parsed.reasoning)

    def test_press_key_basic(self) -> None:
        reply = (
            "ACTION: press_key\n"
            'PARAMS: {"key": "back"}\n'
            "REASON: Dismiss the modal."
        )
        parsed = parse_response(reply)
        self._assert_ok(parsed, "press_key")
        self.assertEqual(parsed.params["key"], "back")

    def test_done_no_params(self) -> None:
        # `done` is allowed to omit the PARAMS line entirely.
        parsed = parse_response(
            "ACTION: done\nREASON: Tweet successfully posted."
        )
        self._assert_ok(parsed, "done")
        self.assertEqual(parsed.params, {})
        self.assertIn("Tweet", parsed.reasoning)

    def test_done_empty_params(self) -> None:
        parsed = parse_response(
            "ACTION: done\nPARAMS: {}\nREASON: goal reached"
        )
        self._assert_ok(parsed, "done")

    def test_case_insensitive(self) -> None:
        reply = (
            "action: TAP_TEXT\n"
            'params: {"text": "Send"}\n'
            "reason: Send it."
        )
        parsed = parse_response(reply)
        self._assert_ok(parsed, "tap_text")

    def test_trailing_punctuation_on_verb(self) -> None:
        reply = (
            "ACTION: tap_text,\n"
            'PARAMS: {"text": "Send"}\n'
            "REASON: .."
        )
        parsed = parse_response(reply)
        self._assert_ok(parsed, "tap_text")

    def test_empty_reply(self) -> None:
        self.assertEqual(parse_response("").action, "error")
        self.assertEqual(parse_response("   \n  ").action, "error")
        self.assertEqual(parse_response(None).action, "error")  # type: ignore[arg-type]

    def test_missing_action_line(self) -> None:
        parsed = parse_response('PARAMS: {"text": "x"}\nREASON: nope')
        self.assertEqual(parsed.action, "error")
        self.assertIn("ACTION", parsed.reasoning)

    def test_unknown_verb(self) -> None:
        parsed = parse_response(
            "ACTION: teleport\nPARAMS: {}\nREASON: magic"
        )
        self.assertEqual(parsed.action, "error")
        self.assertIn("unknown action", parsed.reasoning)

    def test_malformed_json_params(self) -> None:
        parsed = parse_response(
            'ACTION: tap_text\nPARAMS: {text: no quotes}\nREASON: bad json'
        )
        self.assertEqual(parsed.action, "error")
        self.assertIn("PARAMS", parsed.reasoning)

    def test_params_not_object(self) -> None:
        parsed = parse_response(
            'ACTION: tap_text\nPARAMS: ["Continue"]\nREASON: list not object'
        )
        self.assertEqual(parsed.action, "error")
        self.assertIn("object", parsed.reasoning)

    def test_missing_params_line_non_done(self) -> None:
        parsed = parse_response("ACTION: tap_text\nREASON: forgot params")
        self.assertEqual(parsed.action, "error")
        self.assertIn("PARAMS", parsed.reasoning)


class TestBuildPrompt(unittest.TestCase):
    def test_includes_intent_and_step(self) -> None:
        p = build_prompt("compose a tweet", step=2, max_steps=5)
        self.assertIn("compose a tweet", p)
        self.assertIn("STEP 2", p)
        self.assertIn("5", p)

    def test_empty_tree_is_labelled(self) -> None:
        p = build_prompt("test", step=1, max_steps=1, accessibility_tree="")
        self.assertIn("(not provided)", p)

    def test_tree_is_embedded(self) -> None:
        p = build_prompt(
            "test",
            step=1,
            max_steps=1,
            accessibility_tree='{"tree": [{"text": "hello"}]}',
        )
        self.assertIn("hello", p)

    def test_empty_intent_placeholder(self) -> None:
        p = build_prompt("", step=1, max_steps=1)
        self.assertIn("(empty intent)", p)


# ── Loop tests ────────────────────────────────────────────────────────────────


def _mk_screenshot(token: str = "hermes-relay://fake-token") -> nav._Screenshot:
    return nav._Screenshot(token=token, local_path="/tmp/fake.jpg")


class TestNavigateLoop(unittest.TestCase):
    def setUp(self) -> None:
        # Belt-and-suspenders: make sure the stub env var never leaks
        # across tests from an earlier smoke run.
        self._saved_stub = os.environ.pop("HERMES_NAVIGATE_STUB_REPLY", None)

    def tearDown(self) -> None:
        if self._saved_stub is not None:
            os.environ["HERMES_NAVIGATE_STUB_REPLY"] = self._saved_stub
        else:
            os.environ.pop("HERMES_NAVIGATE_STUB_REPLY", None)

    def _run_with_replies(
        self,
        replies: list[str],
        *,
        intent: str = "compose a tweet",
        max_iterations: int = 5,
        screenshot_side_effect: object = None,
        action_side_effect: object = None,
    ) -> dict:
        """Run the loop with a deterministic sequence of model replies."""

        reply_iter = iter(replies)

        def fake_vision(
            prompt: str, screenshot_path: str, screenshot_token: str | None
        ) -> str:
            return next(reply_iter)

        patches = [
            mock.patch.object(nav, "call_vision_model", side_effect=fake_vision),
            mock.patch.object(
                nav,
                "_capture_screenshot",
                side_effect=screenshot_side_effect or (lambda: _mk_screenshot()),
            ),
            mock.patch.object(nav, "_read_accessibility_tree", return_value=""),
            # Avoid the 200 ms settle sleep piling up in the suite.
            mock.patch.object(nav.time, "sleep", lambda *_a, **_k: None),
        ]

        if action_side_effect is not None:
            patches.append(
                mock.patch.object(
                    nav, "_execute_action", side_effect=action_side_effect
                )
            )
        else:
            patches.append(
                mock.patch.object(
                    nav,
                    "_execute_action",
                    return_value={"success": True},
                )
            )

        for p in patches:
            p.start()
        try:
            raw = nav.android_navigate(intent, max_iterations=max_iterations)
        finally:
            for p in patches:
                p.stop()

        return json.loads(raw)

    def test_success_done_on_first_step(self) -> None:
        result = self._run_with_replies(
            ["ACTION: done\nREASON: already there"]
        )
        self.assertEqual(result["status"], "ok")
        self.assertTrue(result["done"])
        self.assertEqual(result["iterations"], 1)
        self.assertEqual(len(result["trace"]), 1)
        self.assertEqual(result["trace"][0]["action"], "done")

    def test_success_multi_step_path(self) -> None:
        replies = [
            'ACTION: tap_text\nPARAMS: {"text": "Compose"}\nREASON: open composer',
            'ACTION: type\nPARAMS: {"text": "hello"}\nREASON: draft',
            'ACTION: tap_text\nPARAMS: {"text": "Post"}\nREASON: submit',
            "ACTION: done\nREASON: tweet posted",
        ]
        result = self._run_with_replies(replies)
        self.assertEqual(result["status"], "ok")
        self.assertEqual(result["iterations"], 4)
        verbs = [step["action"] for step in result["trace"]]
        self.assertEqual(verbs, ["tap_text", "type", "tap_text", "done"])
        # Every step in the trace carries a screenshot token.
        for step in result["trace"]:
            self.assertTrue(step["screenshot_token"].startswith("hermes-relay://"))

    def test_iteration_cap(self) -> None:
        # Every reply is a valid non-done action, so the loop runs out.
        reply = 'ACTION: tap_text\nPARAMS: {"text": "Next"}\nREASON: keep going'
        result = self._run_with_replies([reply] * 10, max_iterations=3)
        self.assertEqual(result["status"], "error")
        self.assertEqual(result["reason"], "iteration_cap")
        self.assertEqual(result["iterations"], 3)
        self.assertEqual(len(result["trace"]), 3)

    def test_parse_error_aborts(self) -> None:
        result = self._run_with_replies(
            ["this is not a valid reply"]
        )
        self.assertEqual(result["status"], "error")
        self.assertEqual(result["reason"], "parse_error")
        self.assertEqual(len(result["trace"]), 1)
        self.assertEqual(result["trace"][0]["action"], "error")

    def test_screenshot_failure_aborts(self) -> None:
        def boom() -> nav._Screenshot:
            raise RuntimeError("bridge offline")

        result = self._run_with_replies(
            [],  # never consumed
            screenshot_side_effect=boom,
        )
        self.assertEqual(result["status"], "error")
        self.assertEqual(result["reason"], "screenshot_failed")
        self.assertIn("bridge offline", result["detail"])
        self.assertEqual(result["trace"], [])

    def test_action_execution_failure(self) -> None:
        def fake_exec(verb: str, params: dict) -> dict:
            raise RuntimeError("tap endpoint returned 500")

        result = self._run_with_replies(
            [
                'ACTION: tap_text\nPARAMS: {"text": "Continue"}\nREASON: try it',
            ],
            action_side_effect=fake_exec,
        )
        self.assertEqual(result["status"], "error")
        self.assertEqual(result["reason"], "action_failed")
        self.assertEqual(len(result["trace"]), 1)
        self.assertEqual(result["trace"][0]["action"], "tap_text")
        self.assertIn("tap endpoint", result["trace"][0]["result"]["error"])

    def test_llm_gap_envelope(self) -> None:
        # With no stub env var and no test patch in place, the default
        # vision model raises NotImplementedError which the loop must
        # convert into a clean error envelope.
        with mock.patch.object(
            nav, "_capture_screenshot", return_value=_mk_screenshot()
        ), mock.patch.object(
            nav, "_read_accessibility_tree", return_value=""
        ), mock.patch.object(nav.time, "sleep", lambda *_a, **_k: None):
            # Also ensure call_vision_model is the real default, not a
            # leftover patch from another test.
            with mock.patch.object(
                nav, "call_vision_model", nav._default_vision_model
            ):
                raw = nav.android_navigate("do something", max_iterations=3)

        result = json.loads(raw)
        self.assertEqual(result["status"], "error")
        self.assertEqual(result["reason"], "llm_gap")
        self.assertIn("vision-model", result["detail"])
        self.assertEqual(result["trace"], [])

    def test_empty_intent_short_circuits(self) -> None:
        result = json.loads(nav.android_navigate("   "))
        self.assertEqual(result["status"], "error")
        self.assertIn("empty intent", result["reason"])

    def test_max_iterations_clamped(self) -> None:
        # Passing a huge number should be clamped to ABSOLUTE_MAX_ITERATIONS.
        # We feed exactly ABSOLUTE_MAX_ITERATIONS+5 non-done replies and
        # expect the loop to bail after ABSOLUTE_MAX_ITERATIONS.
        reply = 'ACTION: tap_text\nPARAMS: {"text": "Next"}\nREASON: loop'
        result = self._run_with_replies(
            [reply] * (nav.ABSOLUTE_MAX_ITERATIONS + 5),
            max_iterations=999,
        )
        self.assertEqual(result["status"], "error")
        self.assertEqual(result["reason"], "iteration_cap")
        self.assertEqual(result["iterations"], nav.ABSOLUTE_MAX_ITERATIONS)


class TestSchema(unittest.TestCase):
    def test_schema_shape(self) -> None:
        self.assertIn("android_navigate", nav._SCHEMAS)
        schema = nav._SCHEMAS["android_navigate"]
        self.assertEqual(schema["name"], "android_navigate")
        self.assertIn("description", schema)
        self.assertIn("parameters", schema)
        self.assertIn("intent", schema["parameters"]["properties"])
        self.assertIn("max_iterations", schema["parameters"]["properties"])
        self.assertEqual(schema["parameters"]["required"], ["intent"])

    def test_handler_calls_android_navigate(self) -> None:
        # The lambda wrapper should unpack its args dict and route to
        # android_navigate. We patch android_navigate itself so we don't
        # trigger a real loop.
        with mock.patch.object(nav, "android_navigate", return_value='{"ok": 1}') as m:
            out = nav._HANDLERS["android_navigate"](
                {"intent": "do stuff", "max_iterations": 2}
            )
        m.assert_called_once_with(intent="do stuff", max_iterations=2)
        self.assertEqual(out, '{"ok": 1}')

    def test_valid_actions_constant(self) -> None:
        self.assertEqual(
            set(VALID_ACTIONS),
            {"tap_text", "tap", "type", "swipe", "press_key", "done"},
        )


if __name__ == "__main__":
    unittest.main()
